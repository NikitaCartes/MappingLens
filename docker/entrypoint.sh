#!/bin/sh
# Serves the MappingLens API and keeps the GitCraft artifact store, the decompiled repositories
# and the search index in step with upstream.
#
# One cycle:
#   1. Update the four mapping checkouts.
#   2. New Minecraft version -> build mojmap and index it, without waiting for yarn.
#   3. Yarn missing or older than the published build -> build yarn, re-index what changed.
#   4. Restart serve if the index changed, then sleep.
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

# The indexer skips versions that are already in the database, so a plain run picks up exactly
# the new Minecraft versions.
reindex() { run java $INDEX_JAVA_OPTS -jar "$JAR" index -config="$CONF"; }

# A version already in the database is not re-read by a plain run, so a rebuilt version needs
# -force, and -versions restricts the rebuild to the versions given.
reindex_versions() {
	run java $INDEX_JAVA_OPTS -jar "$JAR" index -config="$CONF" -force "-versions=$(printf '%s' "$1" | paste -sd, -)"
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
		return 1
	fi
	[ "$latest" = "$(cat "$STATE/mc.latest" 2>/dev/null)" ] && return 1

	log "new Minecraft version: $latest"
	# Mojmap needs no intermediary and no yarn, so a new version becomes searchable in this cycle.
	# Yarn follows in the same cycle when it is already published, and in a later cycle otherwise.
	if run_gitcraft mojmap && reindex; then
		printf '%s\n' "$latest" > "$STATE/mc.latest"
		return 0
	fi
	log "mojmap build failed, retrying next cycle"
	return 1
}

check_yarn() {
	yarn_upstream > /tmp/yarn.upstream
	if [ ! -s /tmp/yarn.upstream ]; then
		log "yarn metadata unavailable"
		return 1
	fi
	yarn_local > /tmp/yarn.before

	# Versions whose store copy is older than the published build. GitCraft rebuilds a version it
	# already has only when the version is named explicitly.
	# The two-file joins below test FILENAME, not NR == FNR: an empty first file is never read, so
	# NR == FNR would stay true over the second file and swallow every record.
	stale=$(awk -F'\t' 'FILENAME == ARGV[1] { local[$1] = $2; next } ($1 in local) && local[$1] + 0 < $2 + 0 { print $1 }' \
		/tmp/yarn.before /tmp/yarn.upstream)
	# Versions with published yarn and nothing in the store: yarn released after the Minecraft
	# version lands here. A version the store does not know is skipped, because GitCraft rejects a
	# version name that its manifest does not contain.
	missing=$(awk -F'\t' 'FILENAME == ARGV[1] { local[$1]; next } !($1 in local) { print $1 }' \
		/tmp/yarn.before /tmp/yarn.upstream \
		| while read -r version; do
			[ -d "$MAPPINGLENS_ARTIFACT_STORE/mc-versions/$version" ] && printf '%s\n' "$version"
		done)
	# GitCraft takes its options as one whitespace-split string, so a version name containing a
	# space (the old pre-releases) cannot be named on the command line.
	pending=$(printf '%s\n%s\n' "$stale" "$missing" | grep -v '^$' | grep -v ' ' | sort -u)
	[ -z "$pending" ] && return 1

	# The same set failed before and no mapping repository has moved since: the missing mappings are
	# not published yet, so a rerun would fail the same way.
	if [ "$pending" = "$(cat "$STATE/yarn.pending" 2>/dev/null)" ] &&
		[ "$fingerprint" = "$(cat "$STATE/checkouts" 2>/dev/null)" ]; then
		return 1
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

	# What the run actually produced. A version whose mappings are still unpublished changes nothing
	# and stays out of the index until a later cycle builds it.
	yarn_local > /tmp/yarn.after
	landed=$(awk -F'\t' 'FILENAME == ARGV[1] { before[$1] = $2; next } !($1 in before) || before[$1] != $2 { print $1 }' \
		/tmp/yarn.before /tmp/yarn.after)
	if [ -z "$landed" ]; then
		log "no yarn build landed, retrying when a mapping repository moves"
		return 1
	fi
	log "yarn built for: $(printf '%s' "$landed" | paste -sd' ' -)"
	reindex_versions "$landed"
}

cycle() {
	for checkout in $CHECKOUTS; do
		update_checkout "$checkout"
	done
	fingerprint=$(checkout_fingerprint)

	indexed=0
	check_minecraft && indexed=1
	check_yarn && indexed=1

	if [ "$indexed" = 1 ]; then
		serve_restart
	elif [ -z "$serve_pid" ] || ! kill -0 "$serve_pid" 2>/dev/null; then
		serve_pid=
		serve_start
	fi
}

mkdir -p "$STATE" /data/config /data/index /data/repos "$MAPPINGLENS_ARTIFACT_STORE"
log "starting, update interval ${UPDATE_INTERVAL_SECONDS}s"
serve_start
while true; do
	cycle
	log "sleeping ${UPDATE_INTERVAL_SECONDS}s"
	run sleep "$UPDATE_INTERVAL_SECONDS"
done
