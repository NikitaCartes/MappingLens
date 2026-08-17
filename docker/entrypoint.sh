#!/bin/sh
# Serves the MappingLens API and keeps the GitCraft artifact store, the decompiled repositories
# and the search index in step with upstream.
#
# One cycle:
#   1. Update the four mapping checkouts.
#   2. New Minecraft version -> build mojmap, without waiting for yarn.
#   3. Yarn missing or older than the published build -> build yarn.
#   4. Index what the artifact store gained or changed since the last successful index.
#   5. Restart serve if the index changed, then sleep.
#
# Steps 2 and 3 only build. Step 4 is the only step that indexes, and it compares the store against
# a snapshot on disk rather than reading the exit code of a build, so a container restart between a
# build and its index run loses nothing.
set -u

JAR=/opt/mappinglens/mappinglens.jar
CONF=/data/config/application.conf
STATE=/data/state
CHECKOUTS="/opt/mappings/intermediary /opt/mappings/relativity-intermediary /opt/mappings/yarn /opt/mappings/relativity-yarn"
MC_MANIFEST=https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
YARN_META=https://meta.fabricmc.net/v2/versions/yarn
MODERN_YARN_META=https://repo.codemc.io/repository/relativitymc/org/relativitymc/modern-yarn/maven-metadata.xml

serve_pid=
child=
fingerprint=

log() { echo "[mappinglens] $*"; }

# Every child runs in the background so that a GitCraft run lasting hours does not delay SIGTERM.
run() { "$@" & child=$!; wait "$child"; }

shutdown() {
	log "stop requested"
	[ -n "$child" ] && kill "$child" 2>/dev/null
	[ -n "$serve_pid" ] && kill "$serve_pid" 2>/dev/null
	exit 0
}
trap shutdown INT TERM

serve_start() {
	if [ ! -f "$MAPPINGLENS_DB_PATH" ]; then
		log "no index at $MAPPINGLENS_DB_PATH, serve starts after the first index build"
		return
	fi
	java $SERVE_JAVA_OPTS -jar "$JAR" serve -config="$CONF" &
	serve_pid=$!
	log "serve runs as pid $serve_pid on port $PORT"
}

# The server memoizes per-version counts and resolved jar paths, so a rebuilt index becomes
# visible only after a restart.
serve_restart() {
	if [ -n "$serve_pid" ]; then
		kill "$serve_pid" 2>/dev/null
		wait "$serve_pid" 2>/dev/null
		serve_pid=
	fi
	serve_start
}

# The checkouts are read-only data, so a hard reset is always correct and cannot conflict.
update_checkout() {
	if git -C "$1" fetch --quiet --depth 1 origin HEAD 2>/dev/null; then
		git -C "$1" reset --quiet --hard FETCH_HEAD
	else
		log "fetch failed for $1"
	fi
}

# One hash over the refs of all four checkouts. Any commit in them can unblock a version whose yarn
# build failed, and a commit on a version branch never moves the checked-out HEAD, so the refs are
# read with ls-remote instead of the working tree.
checkout_fingerprint() {
	for checkout in $CHECKOUTS; do
		git -C "$checkout" ls-remote origin 2>/dev/null
	done | sha1sum | cut -d' ' -f1
}

run_gitcraft() {
	preset=$1
	shift
	log "gitcraft $preset $*"
	# GitCraft puts -Xmx12G on the command line of the run task, through applicationDefaultJvmArgs.
	# The JVM reads JAVA_TOOL_OPTIONS before the command line, so the command line wins and the
	# options given here are lost. The JVM reads _JAVA_OPTIONS after the command line, so the
	# options given here win.
	run env _JAVA_OPTIONS="$GITCRAFT_JAVA_OPTS" ./gradlew --no-daemon run --args="--preset=/opt/presets/$preset.args $*"
}

# The indexer skips versions that are already in the database, so a plain run picks up exactly the
# versions the database does not hold yet, and an empty database means every version in the store.
reindex() { run java $INDEX_JAVA_OPTS -jar "$JAR" index -config="$CONF"; }

# A version already in the database is not re-read by a plain run, so a rebuilt version needs
# -force, and -versions restricts the rebuild to the versions given.
reindex_versions() {
	run java $INDEX_JAVA_OPTS -jar "$JAR" index -config="$CONF" -force "-versions=$(printf '%s' "$1" | paste -sd, -)"
}

# The mapping file names of the artifact store, which is what the indexer reads for a version. A
# version gains a file when its mojmap or its intermediary lands, and a new yarn build is a new file
# name, so every change the indexer cares about is a change in this listing.
store_state() { ls "$MAPPINGLENS_ARTIFACT_STORE/mappings" 2>/dev/null | sort; }

# The version id a mapping file name belongs to. Names that are not one of the three forms are not
# mappings this indexer reads, and are dropped.
mapping_versions() {
	sed -n \
		-e 's/-yarn-build\.[0-9][0-9]*\.tiny$//p' \
		-e 's/-\(client-\|server-\)\{0,1\}moj\.tiny$//p' \
		-e 's/-intermediary\.tiny$//p' \
		| sort -u
}

# The only step that indexes. A build writes files into the store, and this compares the store
# against the snapshot of the last successful index, so what reaches the index never depends on the
# exit code of a build or on the container staying up between the two.
sync_index() {
	store_state > /tmp/store.now
	if [ ! -s /tmp/store.now ]; then
		log "artifact store holds no mappings, nothing to index"
		return 1
	fi
	# Nothing landed since the last successful index, and the database is where it was left.
	[ -f "$MAPPINGLENS_DB_PATH" ] && cmp -s "$STATE/store.files" /tmp/store.now && return 1

	# Versions whose mapping files are new since the last successful index. Without a snapshot every
	# version is new, and the plain run below already covers all of them, so the forced run is left
	# out rather than given a command line of a thousand version names.
	changed=
	[ -f "$STATE/store.files" ] && changed=$(comm -13 "$STATE/store.files" /tmp/store.now | mapping_versions)

	# The forced run first, because a plain run skips a version that is already in the database. This
	# is what repairs a version indexed from yarn alone, before its mojmap landed: the plain run can
	# never see that version again, and until now nothing else looked at it either.
	if [ -n "$changed" ]; then
		log "changed in the store: $(printf '%s' "$changed" | paste -sd' ' -)"
		reindex_versions "$changed" || return 1
	fi
	# Then the plain run, which adds every version the database still lacks. It is the net under an
	# index run that died halfway and under a deleted database, neither of which shows up as a change
	# in the store.
	reindex || return 1

	cp /tmp/store.now "$STATE/store.files"
}

# Latest release/snapshot ids, the same manifest GitCraft reads.
mc_latest() { curl -fsSL "$MC_MANIFEST" | jq -c .latest; }

# Published yarn as "<version><tab><latest build>", from both publishers: Fabric up to 1.21.11,
# RelativityMC's modern yarn after it. Modern yarn has no meta service, and its build number is the
# Jenkins build counter, so the builds are read from the maven metadata, as GitCraft reads them.
yarn_upstream() {
	{
		curl -fsSL "$YARN_META" | jq -r 'group_by(.gameVersion)[] | "\(.[0].gameVersion)\t\(map(.build) | max)"'
		curl -fsSL "$MODERN_YARN_META" \
			| grep -o '<version>[^<]*</version>' \
			| sed 's/<[^>]*>//g' \
			| awk -F'[+]build[.]' 'NF == 2 && $2 ~ /^[0-9]+$/ { print $1 "\t" $2 }'
	} | awk -F'\t' '{ if ($2 + 0 > b[$1]) b[$1] = $2 + 0 } END { for (v in b) print v "\t" b[v] }' | sort
}

# The yarn build each version was built from, taken from the artifact store file names. This is the
# same choice the indexer makes, which takes the highest build of a version.
yarn_local() {
	ls "$MAPPINGLENS_ARTIFACT_STORE/mappings" 2>/dev/null \
		| sed -n 's/^\(.*\)-yarn-build\.\([0-9]*\)\.tiny$/\1\t\2/p' \
		| awk -F'\t' '{ if ($2 + 0 > b[$1]) b[$1] = $2 + 0 } END { for (v in b) print v "\t" b[v] }' | sort
}

check_minecraft() {
	latest=$(mc_latest)
	if [ -z "$latest" ]; then
		log "version manifest unavailable"
		return
	fi
	[ "$latest" = "$(cat "$STATE/mc.latest" 2>/dev/null)" ] && return

	log "new Minecraft version: $latest"
	# The run is not restricted to the new version: GitCraft builds every version the store lacks,
	# which is also what fills an empty store. Mojmap needs no intermediary and no yarn, so a new
	# version reaches the store in this cycle, and sync_index indexes it in this cycle too.
	# GitCraft stops at the first version it cannot build, and the marker stays unwritten then, which
	# is what makes the next cycle build again. What did land is indexed either way.
	if run_gitcraft mojmap; then
		printf '%s\n' "$latest" > "$STATE/mc.latest"
	else
		log "mojmap build incomplete, retrying next cycle"
	fi
}

check_yarn() {
	yarn_upstream > /tmp/yarn.upstream
	if [ ! -s /tmp/yarn.upstream ]; then
		log "yarn metadata unavailable"
		return
	fi
	yarn_local > /tmp/yarn.local

	# Versions whose store copy is older than the published build. GitCraft rebuilds a version it
	# already has only when the version is named explicitly.
	# The two-file joins below test FILENAME, not NR == FNR: an empty first file is never read, so
	# NR == FNR would stay true over the second file and swallow every record.
	stale=$(awk -F'\t' 'FILENAME == ARGV[1] { local[$1] = $2; next } ($1 in local) && local[$1] + 0 < $2 + 0 { print $1 }' \
		/tmp/yarn.local /tmp/yarn.upstream)
	# Versions with published yarn and nothing in the store: yarn released after the Minecraft
	# version lands here. A version the store does not know is skipped, because GitCraft rejects a
	# version name that its manifest does not contain.
	missing=$(awk -F'\t' 'FILENAME == ARGV[1] { local[$1]; next } !($1 in local) { print $1 }' \
		/tmp/yarn.local /tmp/yarn.upstream \
		| while read -r version; do
			[ -d "$MAPPINGLENS_ARTIFACT_STORE/mc-versions/$version" ] && printf '%s\n' "$version"
		done)
	# GitCraft takes its options as one whitespace-split string, so a version name containing a
	# space (the old pre-releases) cannot be named on the command line.
	pending=$(printf '%s\n%s\n' "$stale" "$missing" | grep -v '^$' | grep -v ' ' | sort -u)
	[ -z "$pending" ] && return

	# The same set failed before and no mapping repository has moved since: the missing mappings are
	# not published yet, so a rerun would fail the same way.
	if [ "$pending" = "$(cat "$STATE/yarn.pending" 2>/dev/null)" ] &&
		[ "$fingerprint" = "$(cat "$STATE/checkouts" 2>/dev/null)" ]; then
		return
	fi

	log "yarn to build: $(printf '%s' "$pending" | paste -sd' ' -)"
	refresh=
	if [ -n "$stale" ]; then
		# Rebuilding a version rewrites it and every later commit in the yarn repository. The list
		# holds no spaces, so leaving it unquoted below splits it into one option.
		refresh="--refresh-only-version=$(printf '%s' "$stale" | grep -v ' ' | paste -sd, -)"
	fi
	run_gitcraft yarn $refresh
	printf '%s\n' "$pending" > "$STATE/yarn.pending"
	printf '%s\n' "$fingerprint" > "$STATE/checkouts"
	# What the run produced is not read here. A version whose mappings are still unpublished writes
	# no file, and sync_index indexes the files that appeared, whichever versions those are.
}

cycle() {
	for checkout in $CHECKOUTS; do
		update_checkout "$checkout"
	done
	fingerprint=$(checkout_fingerprint)

	check_minecraft
	check_yarn

	if sync_index; then
		serve_restart
	elif [ -z "$serve_pid" ] || ! kill -0 "$serve_pid" 2>/dev/null; then
		serve_pid=
		serve_start
	fi
}

mkdir -p "$STATE" /data/config /data/index /data/repos "$MAPPINGLENS_ARTIFACT_STORE"
# GitCraft keeps the semver cache in the artifact store, and both GitCraft and the indexer read it
# from there. A store built before the cache moved into it carries no copy, so seed the store from
# the checkout: -n keeps the copy GitCraft wrote. Without the file the indexer gives no semver to
# every id that the cache alone carries one for (18w43b, 25w46a, 3D Shareware v1.34), and those ids
# sort as if they were the newest version. GitCraft itself would rebuild the file over the network.
cp -n /opt/gitcraft/semver-cache-mojang-launcher.json "$MAPPINGLENS_ARTIFACT_STORE/" 2>/dev/null
log "starting, update interval ${UPDATE_INTERVAL_SECONDS}s"
serve_start
while true; do
	cycle
	log "sleeping ${UPDATE_INTERVAL_SECONDS}s"
	run sleep "$UPDATE_INTERVAL_SECONDS"
done
