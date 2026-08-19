# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

## [12]

### Added
- `/api/v1/diff/references?from=&to=&q=` reports how the sites using a class or member changed
  between two versions, as `{changes: {added, removed, moved}}`. `moved` pairs a removed site with
  an added one when both reach exactly the same members. Between 26.1 and 26.2, `WorldBorder` gives
  four such pairs, among them `Entity#collectColliders` to `Entity#collectCollidersIgnoringWorldBorder`,
  a move that leaves every signature intact and that `/exists` therefore cannot see.
- `/api/v1/references` takes `depth` (1 to 5). Above 1 the response carries `paths`, the caller
  chains reaching the target, outermost frame first and at most 200 of them. On 1.21.1,
  `DismountHelper:findSafeDismountLocation` answers with four chains in one call, where the same
  question took three rounds of requests before.
- Each referencing site carries `count`, the number of instructions in it that hit the target, which
  is what `@At(ordinal = N)` numbers `0 .. count-1`. Repeated calls used to collapse into one entry:
  `PortalForcer#createPortal` calls `WorldBorder.isWithinBounds` twice and was reported once.
- Each referencing site carries `synthetic`, the javac lambda body a call sits in, while `member`
  now names the method that lambda is written in. The lambda index moves between versions
  (`lambda$stopSleeping$9` in 1.21.1, `$22` in 1.21.9, `$0` in 26.1), so a mixin can only be written
  against the enclosing name.

- `index` builds a prebuilt reverse-reference index beside the other two files,
  `mappinglens-refs.db`: one row for each class of each (version, namespace). The server reads a row
  instead of scanning the version's whole named jar, which turns a cold `/references` on 26.2 from
  770ms into 27ms, a 16-release walk from 6.6s into 62ms, a `depth=3` walk from 410ms into 17ms and
  `/diff/references` from 1.28s into 10ms. It also removes the memory the scan needed: the live heap
  over those four checks stayed at 12 MB, where the scanning path reached 584 MB. Releases by
  default, which is 90 (version, namespace) pairs, 1.0 GB and 57s to build; `-refs=all` covers every
  version (10.3 GB, 11 minutes) and `-refs=none` skips the step. The file is optional and may be
  partial: any version it does not cover is answered by the scan, exactly as before.

- `/search` reports `yarnDescriptor` and `mojmapDescriptor` beside `intermediaryDescriptor`. The
  index stores no named descriptor, so the official one is rewritten through the classes of that
  version, in one query for the whole page. A key built out of a search row now posts to `/exists`
  unchanged: all 14 keys of `WorldBorder#isWithinBounds` on 1.21.1, in both namespaces, come back
  `exists: true`.
- `/history` tells an inherited member from a missing one. `present: false` with
  `reason: "inherited"` names the declaring supertype in `declaredIn` and describes its
  declaration in `members`, so `ServerLevel:getBlockState` over 1.21 to 26.2 answers in one call
  where it used to need a second one to `/exists`. The supertype is read from a named jar one class
  header at a time, on the newest version of the range that has both the class and a jar, and is
  then followed through the index like any other class.
- `GET /api/v1/bodyhash` hashes one method's body for each version of a range and collapses equal
  neighbours into spans, which answers "is there anything to re-check" where `/diff/patch` reports
  the decompiler's cosmetics as a change. `normalize=intermediary` renames the class types first,
  so a class rename does not move the hash. On `LivingEntity.baseTick` across the 16 releases from
  1.21 to 26.2 the span breaks at 1.21.9, where `WorldBorder.getDamageSafeZone` became
  `getSafeZone`; `WorldBorder.getCenterX` holds one hash across all 16.

- `POST /api/v1/validate?from=&to=` checks a set of mixin targets against every version of a range
  and collapses equal neighbours into spans, which is the whole update pass in one request instead
  of five one-off scripts. A target is `{id, owner, method, descriptor?, at?}`, and each span
  answers `ok` (with `atCount`), `renamed` or `inherited` (with `closest`), `call_moved` (with
  `movedTo`) or `missing`. The spec's own two targets over the 16 releases from 1.21 to 26.2 answer
  in 72ms: `DismountHelper:findSafeDismountLocation` holds `ok` throughout, and
  `ServerPlayer:findRespawnPositionAndUseSpawnBlock` is `renamed` until 1.21.2. `call_moved` is the
  answer `/exists` cannot give: `ServerPlayer#adjustSpawnLocation` survives to 26.2, but from 1.21.9
  its border call sits in `PlayerSpawnFinder#findSpawn`.

### Fixed
- `/history` returned `type: "unknown"` with an empty span list for a member it could not find,
  which read like a malformed query. It now answers with spans over the range, so an absent member
  says where it is absent.

### Changed
- `/references` no longer caps the version range at 25. It caps how many of the range's versions the
  prebuilt reference index does not cover, at 25, because those are the ones that cost a jar scan.
  The index covers releases, so the whole release line now answers in one call: 1.14 to 26.2 is 47
  releases, 43 of which have a mojmap jar to answer from, in 48ms. Without `releasesOnly` that same
  range is 469 versions, 426 of them uncovered, and the refusal says exactly that.
- The reverse index records calls made inside the calling class itself, and follows method
  references (`Foo::bar`) through their `invokedynamic`. Without the first, a walk upwards stopped at
  the first private helper; without the second, 22472 edges of 26.2 were missing altogether.
- The reverse index keeps eight (version, namespace) entries instead of growing without a bound, and
  builds without holding every class file of the jar in memory at once.

### Added
- `/api/v1/references` takes `releasesOnly`, the same parameter `/history` has. The 25-version limit
  is counted after the filter, so `1.21` to `26.2` is one call over 16 releases instead of 145
  versions over 6 calls.
- `POST /api/v1/references/{version}` carries the targets in a body `{namespace, targets[], to,
  releasesOnly, includeVariants}` and takes up to 2000 of them, matching `/exists`. `GET` keeps its
  limit of 25: a request line above 4096 bytes is rejected by the HTTP parser before it reaches the
  route, which is about 33 keys of the usual length. `QUERY` (RFC 10008) is accepted on the same
  path with the same body. Neither body form is cached, because a URL-keyed cache cannot see the
  body.

## [11.2]

### Changed
- `/api/v1/search` answers a prefix query over one version instead of over all 526 at once. `q=get`
  went from 1214ms to 47ms, `q=Block` from 999ms to 28ms, `q=a` from 3739ms to 48ms, `q=Entity` from
  1465ms to 35ms and `q=Block#getDefaultState` from 1025ms to 20ms. Of 500 queries over five
  versions, 490 return the same rows in the same order and 4 return the same rows in a different
  order. The 6 that differ are ties: `"<init>"` matches about 10000 rows whose top 50 carry two
  distinct bm25 scores, so which 50 come back was always arbitrary.
- Names are searched through a second file, `mappinglens-search.db`, beside the index: one
  contentless FTS5 table for each version. `serve` needs both files and exits at startup without it.
  The second file is 5.3 GB where the table it replaces held 14.9 GB inside the index, so an `index`
  run followed by `VACUUM` leaves the pair 9.6 GB smaller.
- `index` builds the search table of any indexed version that has none, reading the names back out
  of the index rather than from the GitCraft store. An existing index is filled by one ordinary
  `index` run: 52.3M rows in 485s over 526 versions, with no re-index of the mappings.
- `/api/v1/search` reads the names of its results in one query for each element table instead of two
  queries for each result.

## [11.2]

### Fixed
- `/api/v1/diff` still reported the same member as added and removed at once everywhere but `class=`.
  A package diff of 1.21.10 to 1.21.11 dropped from 4627 additions to 323.
- `/api/v1/diff` read a constructor as renamed from nothing on the version where yarn started naming it.

### Changed
- `/api/v1/diff` pairs members inside a class pair to find a rename. A whole-index diff of 1.21.11 to
  26.1 went from 66s to 0.5s, and the answer is unchanged.
- The index carries `methods_stable_ident` and `fields_stable_ident`, one index for each member table
  on the identity a diff keys on. A whole-index diff of 1.21.11 to 26.1 went from 1.6s to 0.8s, and
  the answer is unchanged. The two add 2.9 GB to a 34 GB index. One indexer run builds them over an
  index that is already there, so no re-index is needed.

## [11.0]

### Fixed
- `/api/v1/diff` reported the same member as added and removed at once.
- `/api/v1/diff?package=` answered an empty diff for a package named in any namespace but yarn.
- `/api/v1/blame` attributed lines to `_unobfuscated` variants. 

## [10.0]

### Fixed
- The index held no overriding method of an unobfuscated release.

### Added
- `POST /api/v1/translate/{version}` translates a batch of keys between namespaces, descriptors
  included. It takes the same keys as `/exists` and returns each one whole in the target namespace,
  so a result posts to `/exists/{version}` unchanged. `/search` and `/diff` report intermediary
  descriptors and `/exists` matches named ones, and closing that gap by hand was one type at a time.
- `GET /api/v1/references/{version}` accepts a repeatable `q` (up to 25) and a `to` version, which
  extends the walk to a range (up to 25 versions). It answered only the first `q` before, silently.
  This is the batch form the `@At(target = ...)` half of a mixin needs: a signature can survive a
  version while a call inside its body moves elsewhere.
- `POST /api/v1/exists/{version}` reports the nearest declaration for a key that missed, as
  `closest` and `reason` (`inherited` when a supertype declares the same signature, `descriptor`
  when the owner declares that name under another one). A bare `false` read the same whether the
  descriptor moved, the member moved to a supertype, or the name is gone.
- `GET /api/v1/history` takes `releasesOnly` to walk releases alone, and `includeVariants`.

### Changed
- Variants (`<id>_unobfuscated`, a second indexing of a build already listed under its own id) are
  left out of `/versions`, of the `/history` walk and of the default-version choice.
  `includeVariants=true` brings them back. A variant sits next to the build it re-indexes and
  carries no yarn or intermediary names, so every walk over the versions reported the pair as a
  change: `Level:getRespawnData` from 1.21.9 to 26.1 came back as 21 spans, all of them `present`.
  A variant still answers `/versions/{id}` and every other endpoint by name.

  This adds the column `versions.variant_of`. An index built before it makes `serve` fail with
  `500 no such column: versions.variant_of`; re-running `index` adds it.
- `SearchResultEntry.descriptor` and `DiffEntryItem.descriptor` are now `intermediaryDescriptor`.
  Both always held the intermediary descriptor, whatever `namespace` asked for, and the old name
  invited pasting the value into `/exists`, which matches the descriptor of its own namespace.
- `GET /api/v1/references/{version}` returns `{namespace, results[]}` with one group per
  (version, target), instead of one flat `{version, namespace, query, references[]}`.
- `ExistsResult.renamedTo` is gone. It was reserved and always null; `closest` answers the question
  it was a placeholder for.
- `VersionInfo` carries `variantOf`.

### Documentation
- `/history`: `present: false` with a non-null `owner` means the index holds no member of that name
  under the class, which is not the same as the member being gone from the game. `/history` reads
  the mapping index and `/exists` reads the jar the mod runs against; when they disagree, the jar is
  right. The old wording ("the member is gone") sent readers to `/source` to find out otherwise.
- The skill document carries one response example per endpoint. It had none, and the shape of a
  nested answer (`/translate` puts the result in `output.name`) had to be guessed at or dumped.

## [9.9.1]

### Fixed
- `GET /api/v1/versions` listed every weekly snapshot (`18w43b` up to `26w14a`) and
  `3D Shareware v1.34` above the newest version, in name order. The semver of those ids comes from
  GitCraft's `semver-cache-mojang-launcher.json` alone, because the id carries no `major.minor.patch`
  to derive one from. GitCraft wrote that file to its own working directory, not into the artifact
  store where the indexer reads it, so the file was never found and 217 of 525 versions ranked as
  "unknown semver, sorts as if newest". GitCraft now keeps the file in the artifact store, and the
  container seeds a store that has no copy yet from the GitCraft checkout.

### Changed
- The `index` command rewrites the sort index of every version, not only of the versions it ingests.
  A version the store gains in the middle of the order shifts the rank of every version after it,
  which until now stayed stale until a forced rebuild of all versions. One plain `index` run is
  enough to reorder an existing index.

## [9.9]

### Changed
- `GET /api/v1/versions` no longer counts the class, method and field rows of every version on the
  first request after a server start.

  This adds the columns `versions.class_count`, `method_count` and `field_count`. An index built
  before them makes `serve` fail with `500 no such column: versions.class_count`.
  `dev/migrate-version-counts.sql` adds and fills the columns on an existing index in a few seconds,
  without a rebuild.

## [9.8.1]

### Added
- The code view shows the version of the open class next to its name. A tab opened from Blame no
  longer looks identical to the tab it was opened from.

### Fixed
- `GET /api/v1/versions` still ranked every `_unobfuscated` variant, including weekly-snapshot ones
  such as `25w46a_unobfuscated`, one slot above the version it derives from. The semver cache marks
  these variants with `+unobfuscated` build metadata. The parser dropped that metadata for ordering,
  so the variant tied with the plain version on precedence. The tie then fell back to raw id
  comparison, which always ranked the longer, suffixed id as newest. Build metadata now breaks the
  tie the other way, so the variant sorts right below its own release.

## [9.8]

### Added
- A click on a version in the Blame column opens the file at that version.
- The class tree opens `net/minecraft` by default.

### Fixed
- `GET /api/v1/versions` ranked the variant ids (`_unobfuscated`, `_combat-N`,
  `_experimental-snapshot-N`, and the space-form `1.14 Pre-Release N`) as the newest versions. They
  now sort next to the release they derive from.
- Two versions were listed twice, as `1_16_combat-0`/`1.16_combat-0` and
  `1_15_combat-6`/`1.15_combat-6`.

## [9.7.5]

### Fixed
- `protocolVersion` in `GET /api/v1/versions` and `GET /api/v1/versions/{version}` was always null,
  because no command wrote the column. The indexer now reads the number from the `version.json` that
  the Minecraft jar carries at its root. Versions published before 18w47b carry no such file and stay
  null. Re-index a version to fill the column.

### Removed
- The config keys `mappinglens.indexing.poll-interval-seconds` and
  `mappinglens.indexing.index-on-startup`. Neither was read by any command. An existing
  `application.conf` that still sets them keeps working, because the keys are simply ignored.
  `mappinglens.indexing.initial-versions` stays and still restricts the `index` command.

## [9.7.3]

### Fixed
- `GET /api/v1/versions` answered with `Cache-Control: public, max-age=2592000, immutable`. 
  The catalog now answers with `max-age=3600`.
- The frontend turned Blame off in every class opened after the one it was switched on in.

## [9.7.2]

### Added
- `GET /skill.md` serves the agent skill document, the same file as
  `.github/skills/mappinglens/SKILL.md`. The build bundles that file into the jar, so a client can
  fetch the skill from a running server instead of the repository.

## [9.7]

### Fixed
- Mojang's unobfuscated releases (`1.21.11_unobfuscated` and later) came out of the indexer with
  yarn names alone and `hasMojmap` false once yarn covered them. The indexer now reads the mojmap
  namespace from the `official` namespace of the yarn tiny file. Versions that Mojang published no
  mappings for (everything before 19w36a) keep `hasMojmap` false as before.
- `check_minecraft` ran the index only when the mojmap build exited 0, so one version GitCraft could
  not build kept every version it did build out of the index.
- A version indexed before its mojmap landed stayed without mojmap, because later runs skipped it as
  already indexed.
- Indexing no longer follows a build. `docker/entrypoint.sh` keeps a listing of the artifact store
  in `state/store.files`, and indexes the versions whose files differ from that listing, then every
  version the database still lacks. A restart between a build and its index run loses nothing.

## [9.6.1]

### Fixed
- `GITCRAFT_JAVA_OPTS` had no effect on the heap of a GitCraft run. The entrypoint now passes the
  options in `_JAVA_OPTIONS`, which the JVM reads after the `-Xmx12G` that GitCraft puts on the
  command line.

## [9.6]

### Fixed
- The Blame button in the frontend annotated no line. The blame decorations now set
  `showIfCollapsed` and `inlineClassNameAffectsLetterSpacing`.
- The Blame button kept a loading indicator forever in the bytecode view. Blame applies to source
  only.

## [9.5.2]

### Fixed
- `docker/entrypoint.sh` never built yarn on an artifact store that held no yarn mappings. The
  two-file joins in `check_yarn` now test `FILENAME == ARGV[1]` instead of `NR == FNR`, which is
  wrong when the first file is empty.

## [9.5]

### Added
- A second compose service, `frontend`: the built bundle served by nginx on host port 3000. The
  bundle calls the API on its own origin, so `docker/nginx.conf` proxies `/api/` to
  `http://mappinglens:8080`.

## [9.4]

### Added
- `docker/` builds an image that serves the API and keeps its own data current. It carries the
  MappingLens fat jar, GitCraft and the four mapping checkouts (`FabricMC/intermediary`,
  `RelativityMC/intermediary`, `FabricMC/yarn`, `RelativityMC/yarn`). One volume holds the artifact
  store, both source repositories, the index and the update markers.
  - Every `UPDATE_INTERVAL_SECONDS` the entrypoint reads the Mojang manifest and both yarn
    publishers. A new Minecraft version is built with mojmap and indexed at once, so it is
    searchable without waiting for yarn or intermediary.
  - Yarn that appears later, and a new yarn build for a version already built, are rebuilt and
    re-indexed, followed by a restart of `serve`.
  - A version whose intermediary or yarn is not published yet fails to build, and is retried when
    any of the four mapping repositories gets a commit.

  `SERVICE.md` documents the volume layout, the environment variables and the presets.

## [9.3]

### Added
- `index -versions=1.21.4,26.2` builds only the versions given, overriding
  `mappinglens.indexing.initial-versions`. A version already in the database is skipped, so a
  rebuild of it takes `-force` as well. Before, the same run needed a second config file holding
  nothing but the version list.

## [9.2]

### Added
- `GET /api/v1/blame/{version}/{className}?namespace={yarn|mojmap}` — the version that last changed
  each line of a class source. `lines` holds one entry per line, line 1 first, and each entry indexes
  `versions`. `className` resolves as it does for `/source`. One `git blame` over the source
  repository answers the whole file, so a class takes one request rather than a walk over
  `/diff/patch`.
  - A version indexed from the decompiled-source jars alone has no source repository, and gives a
    `404`. The jars carry no history.

## [9.1]

### Added
- `GET /api/v1/history?q={key}` — one class or member across every indexed version in a single
  request. Consecutive versions with the same answer collapse into one span
  (`{from, to, versions, present, ...}`), so a 511-version index returns a handful of entries instead
  of 511 rows. `q` takes a class internal name or a member `owner:name` key, and is repeatable (up to
  50). `from`/`to` narrow the version walk; `namespace` is `yarn`, `mojmap`, or `intermediary`.
  - A class is followed by intermediary name, so a rename or a package move stays one history and
    the name from any version returns the same answer.
  - A name taken from an unobfuscated release is also looked up by simple name in the newest mapped
    version before it, unless the `unobfuscated-intermediary-mappings` source below is configured.
    Two names alive in the same version are never linked.
  - Named descriptors are not indexed, so a signature change is visible as a changed
    `members[].intermediaryDescriptor`, and only on versions that carry intermediary.
  - `present: false` with a non-null `owner` means the class is still there and the member is gone;
    `owner: null` means the class itself is gone.
- `mappinglens.sources.unobfuscated-intermediary-mappings` (env `MAPPINGLENS_UNOBFUSCATED_INTERMEDIARY`,
  empty by default) — a directory of `<version>.tiny` intermediary mappings for Mojang's unobfuscated
  releases, whose `official` namespace holds the unobfuscated name rather than an obfuscated one. The
  setting is separate from `intermediary-mappings`, where a `<version>.tiny` means "this version is
  obfuscated". The mappings are not part of the artifact store — point the setting at a checkout that
  has them.

  What it changes for versions after 1.21.11, once they are re-indexed:
  - `GET /api/v1/history` follows a class across the 1.21.11 boundary and between unobfuscated
    versions by intermediary name, so a rename is one history instead of two. A query in the yarn
    namespace works the same way.
  - `GET /api/v1/diff` stops falling back to `mojmap_name` as its cross-version identity, so a diff
    between two unobfuscated versions reports renames instead of always `renamed: 0`.
  - `GET /api/v1/translate?to=intermediary` answers instead of `422 namespace_unavailable`, and
    `hasIntermediary` is true for those versions in `GET /api/v1/versions`.

## [9.0]

### Fixed
- `versions.has_intermediary` is now set for every version that has yarn mappings. The indexer
  always wrote intermediary names for those versions, but the flag was set only when a standalone
  intermediary tiny file existed. Two endpoints read the flag and both answered wrongly:
  - `GET /api/v1/translate?to=intermediary` returned `422 namespace_unavailable` on every modern
    version.
  - `GET /api/v1/diff` fell back to `mojmap_name` as its cross-version identity. A rename changes
    that name, so renames could not be detected, and both sides of one rename were listed as
    separate entries.

  Existing indexes are corrected without a re-index by `dev/migrate-intermediary-flag.sql`.
- `GET /api/v1/diff` now falls back to `mojmap_name` whenever the two versions do not both carry
  intermediary, instead of only when neither does. A diff across the unobfuscated boundary
  (`1.21.11 -> 26.1`) keeps comparing classes rather than returning an empty result.

### Added
- `format=text` on `GET /api/v1/source/{version}/{className}` (raw `.java` as `text/plain`) and on
  `GET /api/v1/tokens/{version}/{className}` (one token per line as TSV, with a `#`-prefixed header
  line). Both endpoints previously served JSON only.
- `GET /api/v1/source/{version}/{className}` resolves a class name that matches nothing exactly to
  the one class of that version with the same simple name, so a bare simple name
  (`ZombifiedPiglin`) resolves. The response `class` field names the class actually served. When the
  simple name is ambiguous or unknown, the `404` message lists the candidates.

## [8]

### Added
- `POST /api/v1/exists/{version}` — batch-check whether classes/members exist in a version.
  Body `{namespace, members[]}` (keys are a class internal name or `owner:name:descriptor`);
  returns one boolean per key. Descriptors are matched against the version's named jar, so they
  need no remapping. Intended for validating mixin/shadow targets when updating a mod.
- `GET /api/v1/diff?class={classInternalName}` — diff exactly one class, listing added/removed/
  renamed members by name (with `owner` and JVM `descriptor`). Its summary counts match
  `/diff/files` for the same class bit-for-bit. Distinct from `package=` (a package-path prefix).
  `DiffEntryItem` now carries an optional `descriptor` field.
- `ignoreWhitespace` (default `false`) on `/diff/patch` and `/diff/files?format=patch` — collapse
  hunks that differ only in whitespace, line breaks, or reindentation (decompiler cosmetics).

### Changed
- Source patches (`/diff/patch`, `/diff/files?format=patch`) are now generated with a Myers O(ND)
  diff instead of a bounded-LCS matrix. A class with a few real changes no longer degrades into a
  full-file rewrite. The old LCS path remains only as a fallback for near-total reformats.

## [7.1]

### Fixed
- `GET /api/v1/tokens/{version}/{className}` now resolves identifiers whose
  type comes from a Minecraft library (Guava, Brigadier, DataFixerUpper,
  fastutil, slf4j, …) instead of dropping them: the symbol solver's classpath
  now includes the version's declared library jars.
- Web frontend: the layout no longer collapses to the top of the screen
  (regression from the antd `App` wrapper element, which had no height).
- Web frontend: switching between open editor tabs no longer resets each tab's
  scroll position and its Yarn/Mojmap and Source/Bytecode toggles.

## [7]

### Added
- `GET /api/v1/hierarchy/{version}/{className}` — class inheritance hierarchy
  (supertypes + subtypes) as a node/edge graph.
- `GET /api/v1/references/{version}?q={key}` — find all references to a class or
  member (`q` is a class internal name or an `owner:name:descriptor` key).
- `GET /api/v1/tokens/{version}/{className}` — decompiled source plus a token per
  identifier resolved to owner/name/descriptor.
- Frontend right-click actions in the source view: Find All References, View
  Inheritance Hierarchy, and Copy Class Tweaker/Access Widener, Access
  Transformer, and Mixin Target — member-precise when the cursor is on a
  resolved identifier, class-scoped otherwise.
- Successful `/api/v1` responses are served with
  `Cache-Control: public, max-age=2592000, immutable` (one month); error
  responses are not cached.

## [6.3]

### Added
- Ability to show and download the full patch between two versions.

## [6.2]

### Changed
- `mojmap` is now the default `namespace` for the source, bytecode, and diff
  endpoints (previously `yarn`).
- `GET /api/v1/source/{version}/{className}` with no `namespace` now returns
  `mojmap` and falls back to `yarn` for versions that have no mojmap source.
  An explicit `namespace` is still served as-is (no fallback). This fixes
  `404 Source not found` on mojmap-only versions (e.g. newer snapshots).

### Added
- Source and bytecode endpoints accept dot-separated fully-qualified class
  names (`net.minecraft.commands.CommandSourceStack`) in addition to the
  slash-separated internal form (`net/minecraft/commands/CommandSourceStack`).

## [6]

### Changed
- `GET /api/v1/versions` and `GET /api/v1/search` are noticeably faster on a
  full multi-version index: per-version counts are now cached after the
  first request, and search scopes its FTS match to one version's rows
  instead of scanning every indexed version.
- The index database applies read-tuned SQLite pragmas (mmap, larger cache,
  in-memory temp store) for faster queries on large (tens of GB) indexes.

## [5]

### Added
- New web frontend: class tree browser, side-by-side source code viewer,
  and a yarn/mojmap member-correspondence compare view.
- `GET /api/v1/versions/{version}/classes` endpoint listing every class of
  a version, backing the class tree.

### Changed
- `GET /api/v1/versions` now returns versions newest-first (previously
  oldest-first).

### Fixed
- Source/bytecode lookup for nested classes (`Outer$Inner`) now finds the
  outer class's `.java` file instead of 404ing.

## [4.2]

### Added
- `index` command accepts `-force` to fully rebuild the database; without
  it, already-indexed versions are skipped so an interrupted index run can
  resume where it left off.

## [4]

### Added
- Initial web frontend: search bar, results list, and a version/namespace
  sidebar.

## [3]

### Added
- New `GET /api/v1/compare/{version}/{className}` endpoint returning the
  yarn<->mojmap member-correspondence table for a class.
- `GET /openapi.json`, a JSON form of the OpenAPI spec alongside the
  existing YAML.

### Changed
- Split into two subcommands: `index` (offline, builds the read-only
  database) and `serve` (stateless HTTP server reading it). The server no
  longer indexes on startup.
- Versions are now ordered by parsed semver instead of lexicographically,
  fixing cases like `1.9` sorting after `1.10`.
- Mojmap mappings are merged from separate client/server tiny files so
  members present in only one of them aren't dropped.

## [2]

### Changed
- `GET /api/v1/diff` narrows its class/member scan using the underlying
  git source diff (when source repos are configured), returning results
  faster and excluding classes whose source didn't change.
