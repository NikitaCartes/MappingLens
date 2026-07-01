# Changelog

Notable, externally-visible changes to the MappingLens API. Format follows
[Keep a Changelog](https://keepachangelog.com/).

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
