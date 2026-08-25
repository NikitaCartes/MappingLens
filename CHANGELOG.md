# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

## [13.2]

### Added
- `index` builds `mappinglens-decl.db`, a prebuilt declaration index. `/exists` and `/hierarchy`
  read it instead of scanning the jar. `-decl=all` (default), `releases` or `none`.
- A `mappinglens.cache` config block (`CACHE_*` in the environment) bounds everything held between
  requests. `symbol-solvers` bounds the entry count only; JavaParser never releases one.

### Changed
- `/exists` and `/hierarchy` answer identically but no longer keep a whole version's declarations in
  memory once the declaration index covers that version.

## [13.1]

### Added
- Fixed wrongly detected updates for Yarn 19w04b, 19w08a and 19w12b

## [13.0]

### Added
- Resource explorer at `/api/v1/resources`, over a clone of
  [`misode/mcmeta`](https://github.com/misode/mcmeta):  every Minecraft resource of every version from 1.14 on.
- `mappinglens.resources.repo` (`MAPPINGLENS_MCMETA_REPO`) turns the feature on. Empty by default,
  and every resource endpoint then answers `404 resources_disabled`.
- `index-resources`, a third command, builds `mappinglens-resources.db` beside the main index.
- The frontend gained a resource explorer beside the mappings one: file tree, viewer, changes panel
  and search panel.
- The container clones four mcmeta branches and builds the resource index without configuration.
  `MCMETA_BRANCHES` narrows the set, and an empty value turns the feature off.

## [12.2]

### Added
- Every `/api/v1/references` group carries `resolved` and `candidates`, so an unresolvable key no
  longer reads as "nothing calls this".
- `/api/v1/exists` answers a key without a descriptor with `candidates`: every declaration under
  `owner:name`, in key form.
- `/api/v1/search` results carry `synthetic`, and the endpoint takes `includeSynthetic`.
- `/api/v1/exists` reports `reason: "kind"` when the owner declares the name as a field where a
  method was asked for, or the other way round.

### Changed
- `/api/v1/search` leaves javac lambda bodies out unless `includeSynthetic=true`.
- `/api/v1/exists` prefers a candidate of the same kind for `closest`, so `reason: "descriptor"`
  means what it says.
- `score` on `/api/v1/search` is now higher-is-better, in `[0, 1)`. A row that read `0.089` now
  reads `0.911`.
- The agent skill is three files: `SKILL.md`, `reference/endpoints.md`, `reference/responses.md`.

### Removed
- `GET /skill.md`. The skill is read from `.github/skills/mappinglens/`.

## [12.1]

### Added
- `indexing.mappings` (env `MAPPINGS`) names the mappings `index` reads: `yarn`, `mojmap` or both.
  `/versions` reports a mapping left out as an absent namespace.
- `indexing.only-releases` (env `ONLY_RELEASES`) restricts `index` to the versions Mojang types as
  a release.

### Changed
- The container passes `-refs=all`, so the reverse-reference index covers every indexed version.
  `REFS` takes `all`, `releases` or `none`.

## [12]

### Added
- `GET /api/v1/diff/references?from=&to=&q=` reports how the sites using a class or member changed
  between two versions, as `{changes: {added, removed, moved}}`.
- `/api/v1/references` takes `depth` (1 to 5) and then carries `paths`, the caller chains reaching
  the target, at most 200 of them.
- Each referencing site carries `count`, the number of instructions in it that hit the target,
  which is what `@At(ordinal = N)` numbers `0 .. count-1`.
- Each referencing site carries `synthetic`, the javac lambda body the call sits in, while `member`
  names the method that lambda is written in.
- `index` builds `mappinglens-refs.db`, a prebuilt reverse-reference index. A cold `/references` on
  26.2 drops from 770ms to 27ms. Releases by default; `-refs=all` covers every version, `-refs=none`
  skips the step.
- `/search` reports `yarnDescriptor` and `mojmapDescriptor` beside `intermediaryDescriptor`, so a
  key built out of a search row posts to `/exists` unchanged.
- `/history` tells an inherited member from a missing one: `reason: "inherited"` names the declaring
  supertype in `declaredIn`.
- `GET /api/v1/bodyhash` hashes one method's body for each version of a range and collapses equal
  neighbours into spans. `normalize=intermediary` keeps a class rename from moving the hash.
- `POST /api/v1/validate?from=&to=` checks a set of mixin targets against every version of a range,
  answering `ok`, `renamed`, `inherited`, `call_moved` or `missing` per span.
- `/api/v1/references` takes `releasesOnly`.
- `POST /api/v1/references/{version}` carries up to 2000 targets in a body, and `QUERY` (RFC 10008)
  is accepted on the same path. `GET` keeps its limit of 25.

### Fixed
- `/history` returned `type: "unknown"` with an empty span list for a member it could not find,
  which read like a malformed query. It now answers with spans over the range.

### Changed
- `/references` caps how many versions of the range the prebuilt index does not cover, at 25,
  instead of capping the range itself.
- The reverse index records calls made inside the calling class, and follows method references
  (`Foo::bar`) through their `invokedynamic`. Without the second, 22472 edges of 26.2 were missing.
- The reverse index keeps eight (version, namespace) entries, and builds without holding every
  class file of the jar in memory at once.

## [11.2]

### Changed
- `/api/v1/search` answers a prefix query over one version instead of over all 526 at once. `q=get`
  went from 1214ms to 47ms, `q=a` from 3739ms to 48ms.
- Names are searched through a second file, `mappinglens-search.db`, holding one contentless FTS5
  table for each version. `serve` needs both files and exits at startup without it.
- `index` builds the search table of any indexed version that has none, so one ordinary run fills an
  existing index without re-indexing the mappings.

## [11.2]

### Fixed
- `/api/v1/diff` still reported the same member as added and removed at once everywhere but
  `class=`. A package diff of 1.21.10 to 1.21.11 dropped from 4627 additions to 323.
- `/api/v1/diff` read a constructor as renamed from nothing on the version where yarn started
  naming it.

### Changed
- `/api/v1/diff` pairs members inside a class pair to find a rename. A whole-index diff of 1.21.11
  to 26.1 went from 66s to 0.5s, and the answer is unchanged.
- The index carries `methods_stable_ident` and `fields_stable_ident`. One indexer run builds them
  over an existing index, so no re-index is needed.

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
  included, so a result posts to `/exists/{version}` unchanged.
- `GET /api/v1/references/{version}` accepts a repeatable `q` (up to 25) and a `to` version. It
  answered only the first `q` before, silently.
- `POST /api/v1/exists/{version}` reports the nearest declaration for a key that missed, as
  `closest` and `reason` (`inherited` or `descriptor`).
- `GET /api/v1/history` takes `releasesOnly` and `includeVariants`.

### Changed
- Variants (`<id>_unobfuscated`) are left out of `/versions`, of the `/history` walk and of the
  default-version choice. `includeVariants=true` brings them back. This adds the column
  `versions.variant_of`; an index built before it makes `serve` fail, and `index` adds it.
- `SearchResultEntry.descriptor` and `DiffEntryItem.descriptor` are now `intermediaryDescriptor`.
- `GET /api/v1/references/{version}` returns `{namespace, results[]}`, one group per
  (version, target).
- `ExistsResult.renamedTo` is gone; `closest` answers the question it was a placeholder for.
- `VersionInfo` carries `variantOf`.

### Documentation
- `/history`: `present: false` with a non-null `owner` means the index holds no member of that name
  under the class, which is not the same as the member being gone from the game.
- The skill document carries one response example per endpoint.

## [9.9.1]

### Fixed
- `GET /api/v1/versions` listed every weekly snapshot and `3D Shareware v1.34` above the newest
  version. GitCraft wrote `semver-cache-mojang-launcher.json` to its working directory instead of
  the artifact store, so 217 of 525 versions ranked as "unknown semver, sorts as if newest".

### Changed
- The `index` command rewrites the sort index of every version, not only of the versions it
  ingests, so one plain run reorders an existing index.

## [9.9]

### Changed
- `GET /api/v1/versions` no longer counts the class, method and field rows of every version on the
  first request after a server start. This adds `versions.class_count`, `method_count` and
  `field_count`; `dev/migrate-version-counts.sql` fills them on an existing index.

## [9.8.1]

### Added
- The code view shows the version of the open class next to its name.

### Fixed
- `GET /api/v1/versions` ranked every `_unobfuscated` variant one slot above the version it derives
  from. The parser dropped the `+unobfuscated` build metadata, so the tie fell back to raw id
  comparison, which ranks the longer id as newest.

## [9.8]

### Added
- A click on a version in the Blame column opens the file at that version.
- The class tree opens `net/minecraft` by default.

### Fixed
- `GET /api/v1/versions` ranked the variant ids (`_unobfuscated`, `_combat-N`,
  `_experimental-snapshot-N`, `1.14 Pre-Release N`) as the newest versions.
- Two versions were listed twice, as `1_16_combat-0`/`1.16_combat-0` and
  `1_15_combat-6`/`1.15_combat-6`.

## [9.7.5]

### Fixed
- `protocolVersion` was always null, because no command wrote the column. The indexer now reads the
  number from the `version.json` in the Minecraft jar. Versions before 18w47b carry no such file
  and stay null.

### Removed
- The config keys `mappinglens.indexing.poll-interval-seconds` and `indexing.index-on-startup`.
  Neither was read by any command, and an `application.conf` that still sets them keeps working.

## [9.7.3]

### Fixed
- `GET /api/v1/versions` answered with `Cache-Control: max-age=2592000, immutable`. The catalog now
  answers with `max-age=3600`.
- The frontend turned Blame off in every class opened after the one it was switched on in.

## [9.7.2]

### Added
- `GET /skill.md` serves the agent skill document from the jar.

## [9.7]

### Fixed
- Mojang's unobfuscated releases came out of the indexer with yarn names alone and `hasMojmap`
  false. The indexer now reads the mojmap namespace from the `official` namespace of the yarn tiny
  file.
- `check_minecraft` ran the index only when the mojmap build exited 0, so one unbuildable version
  kept every built version out of the index.
- A version indexed before its mojmap landed stayed without mojmap, because later runs skipped it
  as already indexed.
- Indexing no longer follows a build. The entrypoint keeps a listing of the artifact store in
  `state/store.files` and indexes from that, so a restart between a build and its index run loses
  nothing.

## [9.6.1]

### Fixed
- `GITCRAFT_JAVA_OPTS` had no effect on the heap of a GitCraft run. The entrypoint now passes the
  options in `_JAVA_OPTIONS`, which the JVM reads after GitCraft's own `-Xmx12G`.

## [9.6]

### Fixed
- The Blame button annotated no line. The decorations now set `showIfCollapsed` and
  `inlineClassNameAffectsLetterSpacing`.
- The Blame button kept a loading indicator forever in the bytecode view. Blame applies to source
  only.

## [9.5.2]

### Fixed
- `docker/entrypoint.sh` never built yarn on an artifact store that held no yarn mappings. The
  two-file joins in `check_yarn` now test `FILENAME == ARGV[1]` instead of `NR == FNR`, which is
  wrong when the first file is empty.

## [9.5]

### Added
- A second compose service, `frontend`: the built bundle served by nginx on host port 3000.

## [9.4]

### Added
- `docker/` builds an image that serves the API and keeps its own data current: the fat jar,
  GitCraft, the four mapping checkouts, and one volume for the store, the repositories and the
  index. `SERVICE.md` documents the layout, the variables and the presets.

## [9.3]

### Added
- `index -versions=1.21.4,26.2` builds only the versions given, overriding
  `indexing.initial-versions`. Rebuilding an indexed version also takes `-force`.

## [9.2]

### Added
- `GET /api/v1/blame/{version}/{className}` reports the version that last changed each line of a
  class source. One `git blame` answers the whole file. A version with no source repository gives
  a `404`.

## [9.1]

### Added
- `GET /api/v1/history?q={key}` reports one class or member across every indexed version, with
  consecutive versions that share an answer collapsed into one span. `q` is repeatable (up to 50);
  a class is followed by intermediary name, so a rename stays one history.
- `mappinglens.sources.unobfuscated-intermediary-mappings` names a directory of `<version>.tiny`
  intermediary mappings for Mojang's unobfuscated releases. Once those versions are re-indexed,
  `/history` follows a class across the 1.21.11 boundary, `/diff` reports renames between two
  unobfuscated versions, and `translate?to=intermediary` answers instead of `422`.

## [9.0]

### Fixed
- `versions.has_intermediary` is now set for every version that has yarn mappings. The flag was set
  only when a standalone intermediary tiny file existed, which made
  `translate?to=intermediary` answer `422` on every modern version and made `/diff` fall back to
  `mojmap_name` and miss every rename. `dev/migrate-intermediary-flag.sql` corrects an existing
  index.
- `GET /api/v1/diff` now falls back to `mojmap_name` whenever the two versions do not both carry
  intermediary, instead of only when neither does.

### Added
- `format=text` on `GET /api/v1/source/{version}/{className}` and on `/tokens`.
- `GET /api/v1/source/{version}/{className}` resolves a bare simple name (`ZombifiedPiglin`) to the
  one class of that version with that name; an ambiguous name gives a `404` listing the candidates.

## [8]

### Added
- `POST /api/v1/exists/{version}` batch-checks whether classes or members exist in a version.
  Descriptors are matched against the version's named jar, so they need no remapping.
- `GET /api/v1/diff?class={classInternalName}` diffs exactly one class, listing added, removed and
  renamed members with `owner` and JVM `descriptor`.
- `ignoreWhitespace` on `/diff/patch` and `/diff/files?format=patch` collapses hunks that differ
  only in decompiler cosmetics.

### Changed
- Source patches use a Myers O(ND) diff instead of a bounded-LCS matrix, so a class with a few real
  changes no longer degrades into a full-file rewrite.

## [7.1]

### Fixed
- `GET /api/v1/tokens/{version}/{className}` dropped identifiers whose type comes from a Minecraft
  library. The symbol solver's classpath now includes the version's declared library jars.
- Web frontend: the layout no longer collapses to the top of the screen.
- Web frontend: switching between open editor tabs no longer resets each tab's scroll position and
  its toggles.

## [7]

### Added
- `GET /api/v1/hierarchy/{version}/{className}` returns the class inheritance hierarchy as a
  node/edge graph.
- `GET /api/v1/references/{version}?q={key}` finds all references to a class or member.
- `GET /api/v1/tokens/{version}/{className}` returns decompiled source plus one token per
  identifier, resolved to owner, name and descriptor.
- Frontend right-click actions: Find All References, View Inheritance Hierarchy, and Copy Class
  Tweaker/Access Widener, Access Transformer and Mixin Target.
- Successful `/api/v1` responses carry `Cache-Control: public, max-age=2592000, immutable`.

## [6.3]

### Added
- The full patch between two versions can be shown and downloaded.

## [6.2]

### Changed
- `mojmap` is the default `namespace` for the source, bytecode and diff endpoints.
- `/source` with no `namespace` returns `mojmap` and falls back to `yarn`, which fixes
  `404 Source not found` on mojmap-only versions.

### Added
- Source and bytecode endpoints accept dot-separated fully-qualified class names.

## [6]

### Changed
- `GET /api/v1/versions` and `GET /api/v1/search` are faster on a full multi-version index:
  per-version counts are cached, and search scopes its FTS match to one version's rows.
- The index database applies read-tuned SQLite pragmas (mmap, larger cache, in-memory temp store).

## [5]

### Added
- New web frontend: class tree browser, side-by-side source viewer, and a yarn/mojmap
  member-correspondence compare view.
- `GET /api/v1/versions/{version}/classes` lists every class of a version.

### Changed
- `GET /api/v1/versions` returns versions newest-first.

### Fixed
- Source and bytecode lookup for nested classes (`Outer$Inner`) now finds the outer class's `.java`
  file instead of answering `404`.

## [4.2]

### Added
- `index -force` fully rebuilds the database. Without it, indexed versions are skipped, so an
  interrupted run resumes where it left off.

## [4]

### Added
- Initial web frontend: search bar, results list, and a version/namespace sidebar.

## [3]

### Added
- `GET /api/v1/compare/{version}/{className}` returns the yarn/mojmap member-correspondence table
  for a class.
- `GET /openapi.json`, a JSON form of the OpenAPI spec.

### Changed
- Split into two subcommands: `index` (offline, writes the database) and `serve` (stateless, reads
  it). The server no longer indexes on startup.
- Versions are ordered by parsed semver instead of lexicographically, which fixes `1.9` sorting
  after `1.10`.
- Mojmap mappings are merged from separate client and server tiny files, so members present in only
  one of them are not dropped.

## [2]

### Changed
- `GET /api/v1/diff` narrows its class and member scan using the underlying git source diff, which
  is faster and excludes classes whose source did not change.
