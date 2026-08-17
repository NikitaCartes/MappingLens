# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

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
