# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

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
