# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

## [9.7]

### Fixed
- Mojang's unobfuscated releases lost their mojmap namespace as soon as yarn covered them: every
  version from `1.21.11_unobfuscated` on came out of the indexer with yarn names alone, and
  `hasMojmap` false. Those releases publish no obfuscation mappings, so no `-moj.tiny` reaches the
  artifact store and the Mojang names live in the jar itself. The indexer read them by ASM-scanning
  the jar, but only while the version had no mappings at all, and RelativityMC then published yarn
  and intermediary for the whole family. The `official` namespace of that yarn tiny is the Mojang
  name, so the indexer now reads the mojmap namespace off `official`, and marks the class
  `presence: both`. Membership in the unobfuscated-intermediary repository is what tells such a
  version apart from an obfuscated one that Mojang published no mappings for (everything before
  19w36a), which keeps `hasMojmap` false as before.
- `check_minecraft` ran the index only when the mojmap build exited 0, so one version GitCraft could
    not build kept every version it did build out of the index, cycle after cycle.
- A version indexed before its mojmap landed stayed without mojmap for good. `check_yarn` indexed
  the versions its own build produced, and every later plain run skipped them as already indexed, so
  nothing ever read their mojmap. Deleting `index/` and `state/` reproduced it for the whole
  catalog: the yarn build landed first and the index came out with yarn alone.
- Indexing no longer follows a build. `docker/entrypoint.sh` keeps a listing of the mapping files of
  the artifact store in `state/store.files`, written only after an index run succeeds. Each cycle
  builds first, then indexes the versions whose files differ from that listing, then every version
  the database still lacks. A container restart between a build and its index run therefore loses
  nothing, and a deleted `index/` is rebuilt in full.

## [9.6.1]

### Fixed
- `GITCRAFT_JAVA_OPTS` had no effect on the heap of a GitCraft run. GitCraft puts `-Xmx12G` on the
  command line of the run task, and the JVM reads `JAVA_TOOL_OPTIONS` before the command line, so
  the command line won. The entrypoint now passes the options in `_JAVA_OPTIONS`, which the JVM
  reads after the command line.

## [9.6]

### Fixed
- The Blame button in the frontend annotated no line. Monaco discards an injected-text decoration on
  an empty range unless the decoration sets `showIfCollapsed`, and the blame column puts every
  annotation on the empty range at column 1. The decorations now set `showIfCollapsed`, and
  `inlineClassNameAffectsLetterSpacing`, because the `.blame-anno` class gives the injected text a
  width of `13ch` that Monaco must count when it maps a pixel position to a column.
- The Blame button kept a loading indicator forever in the bytecode view. Blame applies to source
  only, so no request was sent and the response that the indicator waited for never arrived.

## [9.5.2]

### Fixed
- `docker/entrypoint.sh` never built yarn on an artifact store that held no yarn mappings. The three
  two-file joins in `check_yarn` selected the first file with `NR == FNR`. awk does not read an empty
  file, so the condition stayed true over the second file and every record was swallowed: the list of
  versions to build came out empty, and `check_yarn` returned without a log line. The joins now test
  `FILENAME == ARGV[1]`.

## [9.5]

### Added
- A second compose service, `frontend`: `npm run build` on Node 24, and the bundle served by nginx
  on host port 3000. The bundle calls the API on its own origin, so `docker/nginx.conf` proxies
  `/api/` to `http://mappinglens:8080`. Both images build from `docker/` with the project root as
  their context, and `.dockerignore` now drops only `frontend/node_modules` and `frontend/dist`.

## [9.4]

### Added
- `docker/` builds an image that serves the API and keeps its own data current. It carries the
  MappingLens fat jar, GitCraft and the four mapping checkouts (`FabricMC/intermediary`,
  `RelativityMC/intermediary`, `FabricMC/yarn`, `RelativityMC/yarn`). The compose file declares the
  volumes, the Dockerfile declares none, and one volume holds the artifact store, both source
  repositories, the index and the update markers.
  - Every `UPDATE_INTERVAL_SECONDS` the entrypoint reads the Mojang manifest and both yarn
    publishers. A new Minecraft version is built with mojmap and indexed at once, so it is
    searchable without waiting for yarn or intermediary.
  - Yarn that appears later, and a new yarn build for a version already built, are found by
    comparing the build number in the artifact store file name with the published one, over every
    version rather than the newest. GitCraft rebuilds those versions, and only the versions whose
    mappings file actually changed are re-indexed with `-force -versions=…`, followed by a restart
    of `serve`.
  - A version whose intermediary or yarn is not published yet fails to build, and is retried when
    any of the four mapping repositories gets a commit. Until then the same set is not rebuilt.

  `SERVICE.md` documents the volume layout, the environment variables and the presets.


## [9.3]

### Added
- `index -versions=1.21.4,26.2` builds only the versions given, overriding
  `mappinglens.indexing.initial-versions`. A version that GitCraft rebuilt on a newer yarn build is
  already in the database, and a plain run skips it, so a rebuild of it takes `-force` as well.
  Before, the same run needed a second config file holding nothing but the version list.

## [9.2]

### Added
- `GET /api/v1/blame/{version}/{className}?namespace={yarn|mojmap}` — the version that last changed
  each line of a class source. `lines` holds one entry per line, line 1 first, and each entry indexes
  `versions`, so a 606-line file with 44 contributing versions is 7.9 KB rather than a version name
  repeated 606 times. `className` resolves as it does for `/source`.
  - One `git blame` over the source repository answers the whole file: Git compares blob ids through
    the trees and reads content only where a commit changed the file. Measured on the 461-version
    mojmap repository, `net/minecraft/world/entity/monster/EnderMan` takes 72 ms in one request.
    Walking `/diff/patch` version by version to the same answer needs about 460 requests at 32 ms
    each, which the 200-requests-a-minute rate limit spreads over more than two minutes.
  - Each version is one commit whose subject is the canonical version id, so no ref lookup is needed.
    Tags replace spaces with underscores, as `VersionMeta.gitTagYarn` does.
  - A version indexed from the decompiled-source jars alone has no source repository, and gives a
    `404`. The jars carry no history.

## [9.1]

### Added
- `GET /api/v1/history?q={key}` — one class or member across every indexed version in a single
  request, answered from the mapping tables alone. Consecutive versions with the same answer collapse
  into one span (`{from, to, versions, present, ...}`), so a 511-version index returns a handful of
  entries instead of 511 rows. `q` takes a class internal name or a member `owner:name` key, and is
  repeatable (up to 50), which covers batching a few keys over many versions without a second
  endpoint. `from`/`to` narrow the version walk; `namespace` is `yarn`, `mojmap`, or `intermediary`.
  - The class is followed by the intermediary name of its newest match rather than by the string
    queried, so a rename or a package move stays one history and the name from any version returns
    the same answer. `ZombifiedPiglin` (moved to `…/monster/zombie/` in 1.21.11-pre1) reads as one
    continuous history over all 511 versions, from either name.
  - Mojang's unobfuscated releases (everything after 1.21.11) carry intermediary only when the new
    `unobfuscated-intermediary-mappings` source below is configured. Without it a name taken from one
    of them is also looked up in the newest mapped version before it, by the simple name that a
    package move preserves. Without that step 16% of the newest version's classes answered only for
    the unobfuscated versions themselves. Two names alive in the same version are never linked, since
    a class carries one name per version — so `virtualfilesystem/Node` is not joined to
    `world/level/pathfinder/Node`, and a class *renamed* (not moved) after 1.21.11 then keeps only
    its post-1.21.11 history.
  - Named descriptors are not indexed, so a signature change is visible as a changed
    `members[].intermediaryDescriptor`, and only on versions that carry intermediary.
  - `present: false` with a non-null `owner` means the class is still there and the member is gone;
    `owner: null` means the class itself is gone.
- `mappinglens.sources.unobfuscated-intermediary-mappings` (env `MAPPINGLENS_UNOBFUSCATED_INTERMEDIARY`,
  empty by default) — a directory of `<version>.tiny` intermediary mappings for Mojang's unobfuscated
  releases, whose `official` namespace holds the unobfuscated name rather than an obfuscated one.
  Those versions ship no mappings of their own, so the indexer ASM-scans their jar; with this source
  it also writes the intermediary name of every class and member it finds. The setting is separate
  from `intermediary-mappings` on purpose: a `<version>.tiny` under that directory means "this version
  is obfuscated", which is exactly what these versions are not. The mappings are not part of the
  artifact store — point the setting at a checkout that has them.

  What it changes for versions after 1.21.11, once they are re-indexed:
  - `GET /api/v1/history` follows a class across the 1.21.11 boundary and between unobfuscated
    versions by intermediary name, so a rename is one history instead of two. 77 classes were renamed
    between 26.1 and the newest indexed 26.3 snapshot, `Gui` -> `Hud` among them. A query in the yarn
    namespace works the same way, even though the unobfuscated versions have no yarn names of their
    own.
  - `GET /api/v1/diff` stops falling back to `mojmap_name` as its cross-version identity, so a diff
    between two unobfuscated versions reports renames instead of always `renamed: 0`.
  - `GET /api/v1/translate?to=intermediary` answers instead of `422 namespace_unavailable`, and
    `hasIntermediary` is true for those versions in `GET /api/v1/versions`.

## [9.0]

### Fixed
- `versions.has_intermediary` is now set for every version that has yarn mappings. Yarn's merged
  tiny v2 is `official->intermediary->named`, so the indexer always wrote intermediary names for
  those versions, but the flag was set only when a standalone intermediary tiny file existed (the
  artifact store holds those up to 20w09a). On a full index that left 446 of 511 versions claiming
  no intermediary while the names sat in the tables. Two endpoints read the flag and both answered
  wrongly:
  - `GET /api/v1/translate?to=intermediary` returned `422 namespace_unavailable` on every modern
    version.
  - `GET /api/v1/diff` fell back to `mojmap_name` as its cross-version identity. A rename changes
    that name, so renames could not be detected: `1.21.10 -> 1.21.11` reported 1026 classes added
    and 704 removed with `renamed: 0`, listing both sides of one rename as separate entries.

  Existing indexes are corrected without a re-index by `dev/migrate-intermediary-flag.sql`.
- `GET /api/v1/diff` now falls back to `mojmap_name` whenever the two versions do not both carry
  intermediary, instead of only when neither does. A diff across the unobfuscated boundary
  (`1.21.11 -> 26.1`) keeps comparing classes rather than returning an empty result.

### Added
- `format=text` on `GET /api/v1/source/{version}/{className}` (raw `.java` as `text/plain`) and on
  `GET /api/v1/tokens/{version}/{className}` (one token per line as TSV, with a `#`-prefixed header
  line). Both endpoints previously served JSON only, so reading a file meant unwrapping the envelope
  first.
- `GET /api/v1/source/{version}/{className}` resolves a class name that matches nothing exactly to
  the one class of that version with the same simple name. A bare simple name (`ZombifiedPiglin`)
  and a class that moved package between versions both resolve, instead of 404ing per package
  candidate. The response `class` field names the class actually served. When the simple name is
  ambiguous or unknown, the `404` message lists the candidates.

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
  full-file rewrite when one early edit broke the old prefix/suffix fast path (e.g. a ~4k-line class
  dropped from a ~240 KB "everything changed" patch to a few KB of actual changes). The old LCS
  path remains only as a fallback for near-total reformats.

## [7.1]

### Fixed
- `GET /api/v1/tokens/{version}/{className}` now resolves identifiers whose
  type comes from a Minecraft library (Guava, Brigadier, DataFixerUpper,
  fastutil, slf4j, …) instead of silently dropping them: the symbol solver's
  classpath now includes the version's declared library jars (read from its
  mc-meta manifest), not just the JDK and the remapped Minecraft jar.
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
