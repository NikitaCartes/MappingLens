# MappingLens Service Reference

*(Русская версия: [SERVICE.ru.md](SERVICE.ru.md))*

MappingLens is a stateless, read-only service for search, translation, comparison, and
inspection of Minecraft mappings (Yarn, Mojmap, Intermediary, Obfuscated). Three artifacts
make up the project:

- **`index`**: the offline indexer. It is the only writer of the SQLite index.
- **`serve`**: the stateless HTTP server (Ktor/Netty). It opens the index read-only.
- **`frontend/`**: a browser explorer for mappings (React + Vite). It consumes the REST API.

The server does not decompile or mutate anything. Source and bytecode are read on demand
from a read-only GitCraft store. The server opens the index with `PRAGMA query_only=ON`.

---

## Available features

| Feature               | Description                                                                                                                                                                                             |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Mapping search        | FTS5 search over classes, methods, and fields in one version. Each result row carries the name in every namespace at once.                                                                              |
| Name translation      | Translates a class, method, or field name between namespaces (`from` to `to`), with automatic type detection.                                                                                           |
| Symbol diff           | Added, removed, and renamed classes, methods, and fields between two versions, plus a summary.                                                                                                          |
| Source diff           | List of changed files, and a unified or git patch of the source between two versions (filterable by path or function).                                                                                  |
| Version history       | One class or member across every indexed version at once: version ranges that share an answer, rename tracking through intermediary names, several keys per request.                                    |
| Compare Yarn/Mojmap   | Member-correspondence table for one class, between Yarn and Mojmap.                                                                                                                                     |
| Bytecode              | Disassembled bytecode of a class (ASM Textifier) in any namespace, as text or JSON.                                                                                                                     |
| Source                | Decompiled `.java` for a class, from the artifact store (Yarn or Mojmap namespace).                                                                                                                     |
| Inheritance hierarchy | Supertypes and subtypes of a class (ASM scan of the named jar), for the "View Inheritance" right-click action in the UI.                                                                                |
| Find all references   | Reverse index of where a class, method, or field is used (on-demand ASM scan of the named jar, cached per version and namespace).                                                                       |
| Existence check       | Batch check that classes or members still exist in a version (`POST /exists`, ASM scan of the named jar). Validates mixin targets before a mod update.                                                  |
| Source tokens         | Resolves each identifier in a `.java` file to owner, name, and descriptor (JavaParser symbol solver), as `{source, tokens}`. Backs the member-level right-click actions (copy AW, AT, or Mixin target). |
| Versions              | List of indexed versions, with namespace-availability flags, counts, and semver order (newest first).                                                                                                   |
| OpenAPI / Swagger     | Machine-readable spec (`/openapi.json`, `/openapi.yaml`) plus Swagger UI (`/docs`).                                                                                                                     |
| Frontend explorer     | Browser UI: version picker, namespace and type filters, debounced search, cross-namespace cards with click-to-copy.                                                                                     |
| Blame by version      | Shows which version last changed each line, in the source viewer: one `git blame` over the source repository per request (`/blame/{version}/{class}`).                                                  |

---

## Requirements

- **JDK 21** (Gradle toolchain, `languageVersion 21`, `jvmTarget JVM_21`). Stack: Kotlin 2.2.20, Ktor 3.2.3, Exposed 0.56, sqlite-jdbc.
- **Node.js** (for the frontend).
- **Data on disk:**
  - For `index`: the sources `artifact-store` (with subfolders `mappings/`, `mc-versions/`, `decompiled/<version>/`, `remapped-mc/<version>/`), `yarn`, `mojmap`, `intermediary`.
  - For `serve`: a **built index** at `database.path` (otherwise the server exits at startup with a hint to run `index`). Source repositories are not needed at startup, but `diff`, `bytecode`, and `source` read jars from `artifact-store` on demand, during the request.
  - For `/openapi.*` and `/docs`: the classpath resource `openapi/mappinglens-api.yaml` (bundled in the jar).
  - For `/skill.md`: the classpath resource `SKILL.md`. Gradle places `.github/skills/mappinglens/SKILL.md` into the jar under that name.

> **Schema compatibility.** An index built before the rewrite has no `versions.sort_index` or
> `classes.presence` column, and `serve` fails on it with `500 no such column: versions.sort_index`.
> Rebuild it with `index` to fix this.
>
> An index built before the search and diff optimization has no `versions.fts_min_rowid` /
> `fts_max_rowid` columns and no composite index on `classes(version_id, intermediary_name)`. A
> fresh `index` build adds both. Until a large existing index is rebuilt, search falls back to
> the older, slower path automatically; the results are the same either way.
>
> An index built before the version-count columns has no `versions.class_count` / `method_count` /
> `field_count`, and `serve` fails on it with `500 no such column: versions.class_count`. A fresh
> `index` build adds the columns; `dev/migrate-version-counts.sql` adds and fills them on an
> existing index without a rebuild. A version row whose columns are null still answers correctly,
> from the older grouped-count path.

---

## Configuration

A HOCON file, `application.conf`, is created from the bundled template on first run if it does
not exist. Every path is overridable through an environment variable.

| Key                                                      | Default               | Env / CLI                               | Purpose                                                                              |
|----------------------------------------------------------|-----------------------|-----------------------------------------|--------------------------------------------------------------------------------------|
| `ktor.deployment.host`                                   | `0.0.0.0`             | `HOST`, `-host`                         | Bind address                                                                         |
| `ktor.deployment.port`                                   | `8080`                | `PORT`, `-port`                         | Port                                                                                 |
| `mappinglens.database.path`                              | `data/mappinglens.db` | `MAPPINGLENS_DB_PATH`                   | SQLite index (`serve` opens it read-only; `index` writes it)                         |
| `mappinglens.sources.artifact-store`                     | `data/artifact-store` | `MAPPINGLENS_ARTIFACT_STORE`            | Store root (`mappings`, `mc-versions`, `decompiled`, `remapped-mc`)                  |
| `mappinglens.sources.yarn-repo`                          | `data/yarn`           | `MAPPINGLENS_YARN_REPO`                 | Yarn source (indexing only)                                                          |
| `mappinglens.sources.mojmap-repo`                        | `data/mojmap`         | `MAPPINGLENS_MOJMAP_REPO`               | Mojmap source (indexing only)                                                        |
| `mappinglens.sources.intermediary-mappings`              | `data/intermediary`   | `MAPPINGLENS_INTERMEDIARY`              | Intermediary source (indexing only)                                                  |
| `mappinglens.sources.unobfuscated-intermediary-mappings` | `""` (off)            | `MAPPINGLENS_UNOBFUSCATED_INTERMEDIARY` | Intermediary source for unobfuscated releases, a separate repository (indexing only) |
| `mappinglens.search.max-results`                         | `200`                 | n/a                                     | Upper bound for the search `limit`                                                   |
| `mappinglens.search.default-results`                     | `50`                  | n/a                                     | Default search `limit`                                                               |
| `mappinglens.indexing.initial-versions`                  | `[]` (all)            | n/a                                     | Versions the `index` command builds; **ignored by `serve`**                          |

**Ktor plugins:** ContentNegotiation (kotlinx JSON: `prettyPrint`, `encodeDefaults`,
`ignoreUnknownKeys`), CallLogging, CORS (`anyHost`, GET and POST methods, `Content-Type`
header), RateLimit (200 requests per 60 seconds, on the `/api/v1` route group only),
StatusPages (`IllegalArgumentException` maps to `400 invalid_query`, everything else maps
to `500 internal_error`), and a cache-headers plugin that sets `Cache-Control` on successful
`/api/v1` responses (see the contract rules below).

---

## Running

```sh
# 1. Build the read-only index (the only writer of the database).
./gradlew run --args="index"

# 1a. Rebuild only the given versions (for example, after GitCraft rebuilds a version on a
#     new yarn build). `-versions` overrides `indexing.initial-versions`; rebuilding a version
#     already indexed also needs `-force`.
./gradlew run --args="index -force -versions=1.21.4,26.2"

# 2. Start the server (stateless, read-only). `serve` is the default command when the first
#    argument is absent or starts with '-'.
./gradlew run --args="serve"            # listens on :8080

# 3. Frontend (dev): Vite proxies /api -> http://localhost:8080
cd frontend
npm install
npm run dev                              # http://localhost:5173
npm run build                            # static bundle in dist/ (tsc + vite)
```

`main()` reads `args[0]` as the subcommand only when it does not start with `-`. The known
commands are `index` (writes the database) and `serve` (read-only). Any other token prints
usage and exits with code 2.

For a production frontend, deploy `dist/` as static files and proxy `/api` to a running
`serve` instance (the `frontend` container does this in compose, see below).

---

## Docker

The compose file starts two services. `mappinglens` keeps `serve` running and builds its own
data: GitCraft fills the artifact store and the source repositories, and `index` updates the
index. `frontend` serves the built bundle.

```sh
docker compose -f docker/docker-compose.yml up -d --build
```

| Service       | Host port                             | Dockerfile                   |
|---------------|---------------------------------------|------------------------------|
| `mappinglens` | `8080`: API and Swagger UI at `/docs` | `docker/Dockerfile`          |
| `frontend`    | `3000`: UI                            | `docker/Dockerfile.frontend` |

The build context for both images is the project root. `.dockerignore` excludes
`frontend/node_modules` and `frontend/dist`; the frontend source itself stays in the context.
The frontend build runs `npm ci` and `npm run build` on Node 24, then nginx serves `dist/`.
The bundle calls the API on its own origin (`/api/v1/...`), so `docker/nginx.conf` proxies
`/api/` to `http://mappinglens:8080`; port `8080` is exposed only for direct API requests.

In the API image: the MappingLens fat jar (built in its own layer on JDK 21), GitCraft
(requires JDK 25), checkouts of `FabricMC/intermediary`, `RelativityMC/intermediary`,
`FabricMC/yarn`, `RelativityMC/yarn`, and the GitCraft presets. The Dockerfile declares no
`VOLUME`; volumes are declared in the compose file.

| Directory under the `/data` volume | Contents                                                                    |
|------------------------------------|-----------------------------------------------------------------------------|
| `artifact-store/`                  | GitCraft's artifact store (`mappings/`, `mc-versions/`, `decompiled/`, ...) |
| `repos/yarn`, `repos/mojmap`       | Git repositories of decompiled source                                       |
| `index/mappinglens.db`             | SQLite index                                                                |
| `state/`                           | Update-cycle markers, including the store snapshot `store.files`            |
| `gradle/`                          | Gradle home for the GitCraft runs                                           |

`docker/entrypoint.sh` cycle, every `UPDATE_INTERVAL_SECONDS`:

1. Updates the four checkouts and computes one fingerprint over their refs (`git ls-remote`).
2. A new version in the Mojang manifest triggers the `mojmap` preset. The run is not limited
   to the new version: GitCraft builds everything missing from the store, which is also what
   fills an empty store. Mojmap needs neither intermediary nor yarn, so the version reaches
   the search index in the same cycle.
3. A published yarn build newer than the one in the artifact store, or no yarn on disk at
   all, triggers the `yarn` preset, with `--refresh-only-version` when needed.
4. Indexing. Steps 2 and 3 only build; this step is the only one that indexes, and it checks
   the store, not a build's exit code. The `artifact-store/mappings` listing is compared
   against the snapshot `state/store.files`: first `index -force -versions=<...>` for
   versions whose files changed, then a plain `index` for versions still missing from the
   database. The snapshot is written only after success, so a container restart between a
   build and an index run loses nothing, and a removed `index/` directory rebuilds in full.
5. An index change restarts `serve`: the server memoizes version counts and jar paths.

Every version is compared, not only recent ones. When the set of unbuildable versions has
not changed and no mapping repository has moved, the GitCraft run is skipped: those versions
are still waiting on intermediary or yarn to be published.

The presets `docker/presets/mojmap.args` and `docker/presets/yarn.args` hold one argument per
line; lines starting with `#` are ignored. GitCraft receives them as `--preset=<file>`.

| Variable                  | Default             | Purpose                                                 |
|---------------------------|---------------------|---------------------------------------------------------|
| `UPDATE_INTERVAL_SECONDS` | `3600`              | Pause between checks                                    |
| `PORT`                    | `8080`              | `serve` port                                            |
| `MAPPINGLENS_*`           | paths under `/data` | Same variables as outside the container                 |
| `GITCRAFT_JAVA_OPTS`      | none                | JVM options for the GitCraft run, for example `-Xmx16g` |
| `INDEX_JAVA_OPTS`         | none                | JVM options for `index`, for example `-Xmx4g`           |
| `SERVE_JAVA_OPTS`         | none                | JVM options for `serve`, for example `-Xmx4g`           |

The build args `GITCRAFT_REPO` and `GITCRAFT_REF` set where GitCraft comes from. It needs the
`--preset`, `--artifact-store-path`, `--override-repo-target`, and `--fabric-intermediary-repo`
options.

> On an empty volume, the first run builds every version from nothing and takes days; `serve`
> starts only once an index exists. Mount an existing artifact store over
> `/data/artifact-store` to skip that first build (see the commented example in the compose
> file).

---

## Endpoints

Base prefix: `/api/v1`. Every endpoint is `GET` unless noted otherwise. `{className...}` and
`{name...}` are catch-all path segments: the remaining path (with slashes) joins into a
class internal name.

### Meta (not rate-limited)

| Endpoint            | Description                                                                                   |
|---------------------|-----------------------------------------------------------------------------------------------|
| `GET /`             | Text pointer to `/docs`, `/openapi.json`, and `/skill.md`                                     |
| `GET /health`       | Liveness check. Returns `ok`                                                                  |
| `GET /openapi.json` | OpenAPI 3.1 as real JSON (the YAML is parsed by SnakeYAML and re-exported)                    |
| `GET /openapi.yaml` | OpenAPI 3.1 as YAML                                                                           |
| `GET /skill.md`     | The agent skill document in Markdown (the same file as `.github/skills/mappinglens/SKILL.md`) |
| `GET /docs`         | Swagger UI (on by default, `includeDocs=true`)                                                |

### Versions

| Endpoint                         | Description                                                                                           |
|----------------------------------|-------------------------------------------------------------------------------------------------------|
| `GET /api/v1/versions`           | All versions (`hasYarn`/`hasMojmap`/`hasIntermediary` flags plus counts), semver order (newest first) |
| `GET /api/v1/versions/{version}` | Metadata for one version (`404 version_not_found` if absent)                                          |

A **variant** is a second indexing of a build that the list already carries under its own id:
GitCraft derives `<id>_unobfuscated` from Mojang's pre-deobfuscated jar. `variantOf` names the
build a variant re-indexes, and is null on a version in its own right. Variants are left out of
the listing, out of the `/history` walk, and out of the default-version choice, because one build
listed twice reads as two versions and puts a step between every pair of neighbours.
`includeVariants=true` brings them back on `/versions`, `/history` and `/references`. A variant
answers `/versions/{id}` and every other endpoint by name either way.

### Search

| Endpoint             | Description                                                                                               |
|----------------------|-----------------------------------------------------------------------------------------------------------|
| `GET /api/v1/search` | FTS search over classes, methods, and fields in one version; each row carries the name in every namespace |

Parameters: `q` (required), `version` (default: the latest release), `type`
(`class`/`method`/`field`/`all`), `namespace` (`yarn`/`mojmap`/`intermediary`/`all`), `limit`
(1 to 200, default 50), `offset` (0 or more), `exact` (`true`/`false`). `q` also accepts the
form `Owner#member`, `Owner.member`, or `Owner/member`.

A member row carries `intermediaryDescriptor`, never a named descriptor, whatever `namespace`
asked for: named descriptors are not indexed. The field is named after what it holds, because
read as a plain `descriptor` it invites being pasted into `/exists`, which matches the descriptor
of its own namespace and rejects an intermediary one. `POST /api/v1/translate/{version}` converts
a whole key, descriptor included. `/diff` reports the same field for the same reason.

### Translate

| Endpoint                                | Description                                                                                           |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------|
| `GET /api/v1/translate`                 | Translates a name between namespaces (`name`, `from`, `to`, `version?`, `type=auto`)                  |
| `GET /api/v1/translate/class/{name...}` | Shortcut for a class translation (`type` fixed to `class`; `from`/`to` default to `yarn` to `mojmap`) |
| `POST /api/v1/translate/{version}`      | Batch key translation; body `{from, to, keys[]}`; response `{results:[{key, translated, type, intermediary}]}` |

The batch form takes the same keys as `/exists` — a class internal name, or
`owner:name:descriptor` — spelled in `from`, and up to 2000 per request. `translated` is the whole
key in `to`, descriptor included, so a result posts to `/exists/{version}` unchanged. That is the
point of the endpoint: `/search` and `/diff` report intermediary descriptors, `/exists` matches
named ones, and translating a batch by hand is what the gap costs otherwise.

Descriptors are translated type by type through the class table, because a descriptor is class
names and primitives and the class table knows every class of the version in every namespace. The
official descriptor is the pivot. A type the version does not know (a JDK class) is left as it is.
A key with no descriptor resolves only when the name has a single match: an overloaded name gives
`translated: null` rather than a guess.

### Diff

| Endpoint                 | Description                                                                                                      |
|--------------------------|------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/diff`       | Symbol diff (added, removed, renamed classes/methods/fields) plus a summary                                      |
| `GET /api/v1/diff/files` | List of changed files (`format=json`), or a raw patch (`format=patch`/`git`)                                     |
| `GET /api/v1/diff/patch` | Unified or git patch of the source (`path`/`file`/`function` filters, `context`, `limit`), or JSON with metadata |

Diff parameters: `from`, `to` (required); `namespace` (for `/diff`: `yarn`/`mojmap`/`intermediary`;
for `/diff/files` and `/diff/patch`: `yarn`/`mojmap` only); `type`, `package`, `class`,
`changeType`, `limit`.

- `package` is a package-path prefix over the whole diff. `class` targets exactly one class
  by internal name in `namespace`: it lists added, removed, and renamed members by name, with
  `owner` and JVM `descriptor`, and its `summary` matches `/diff/files` for the same class
  exactly. `class` wins when both are given.
- `/diff/files?format=patch` and `/diff/patch` accept `ignoreWhitespace` (default `false`):
  it collapses hunks that differ only in whitespace, line breaks, or reindentation (decompiler
  cosmetics). Patches are minimal by construction (Myers O(ND) diff): a class with a few real
  changes yields a few hunks, not a rewritten file. The older LCS path remains only as a
  fallback for near-total reformats.

### Version history

| Endpoint                      | Description                                                                                               |
|-------------------------------|-----------------------------------------------------------------------------------------------------------|
| `GET /api/v1/history?q=<key>` | One class or member across every indexed version at once: a list of ranges (`spans`) that share an answer |

Parameters: `q` (required, repeatable: up to 50 keys per request), `namespace`
(`yarn`/`mojmap`/`intermediary`, default `mojmap`), `from`/`to` (bound the version walk;
either bound may be the older one; an unknown version returns `404`), `releasesOnly`
(default `false`: walk releases alone), `includeVariants` (default `false`, see Versions).
A bound may name a version the walk then skips, so bounding by a snapshot or by a variant
works. The key `q` is a class
internal name (dots allowed) or `owner:name`. A third `:descriptor` segment is accepted, for
compatibility with keys from `/references` and `/exists`, but it does not filter the result.

The response is `{namespace, results[]}`: one entry per `q`, in request order, and `query`
repeats the input string exactly. `type` is `class`, `method`, `field`, or `unknown`. Each
`span` is a run of consecutive versions with the same answer: `from` (the older bound), `to`
(the newer bound), `versions` (the count), `present`. A class entry also carries
`intermediary`/`yarn`/`mojmap`; a member entry carries `owner` (in the requested namespace)
and `members[]` (one entry per overload).

- A class is tracked by the intermediary name of its newest match, not by the name queried,
  so a rename or a package move stays one history: the name from any version gives the same
  answer.
- Unobfuscated releases (everything after 1.21.11) ship no mappings of their own. When the
  indexer has `sources.unobfuscated-intermediary-mappings` set, they carry intermediary like
  any other version, and history works the same way. Check `hasIntermediary` on
  `/api/v1/versions` to see which case an index is in.
- Without that source, a name taken from one of those versions is also looked up in the
  nearest earlier version that carries intermediary, by simple name (a package move preserves
  it). That lookup recovers the rest of the history. Two names alive in the same version are
  never linked, because a class has one name per version: `util/filefix/virtualfilesystem/Node`
  is not linked to `world/level/pathfinder/Node`, even though the two share a simple name. The
  cost of this rule: without the separate source, a class renamed, not moved, after 1.21.11
  gets no history before 1.21.11.
- Named descriptors are not indexed, so a signature change is visible only as a changed
  `members[].intermediaryDescriptor`, and only on versions that carry intermediary.
- `present: false` with a non-null `owner` means the class is still there and **the index holds
  no member of that name under it**. `owner: null` means the class itself is gone. Read it as
  "not declared here", not as "removed from the game": `/history` reads the mapping index, and
  `/exists` reads the jar the mod runs against. When the two disagree, the jar is right.
- Twin versions (`1.21.11` and `1.21.11_unobfuscated`) sit next to each other in the version
  order, and the twin's yarn and intermediary names are empty, so such a pair used to yield two
  adjacent spans. The walk now skips variants; `includeVariants=true` brings the old behavior
  back. `releasesOnly=true` drops the snapshots as well.

### Compare

| Endpoint                                       | Description                                                                             |
|------------------------------------------------|-----------------------------------------------------------------------------------------|
| `GET /api/v1/compare/{version}/{className...}` | Member-correspondence table (methods and fields) between Yarn and Mojmap, for one class |

Parameters: `from` (default `yarn`: picks the column for the class lookup and the
availability check), `to` (default `mojmap`, see the note below).

Response: the obf-keyed member table aligning the class across both namespaces, including
members present on only one side. No source is read. Per-member `status`: `matched`,
`yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`, `initializer`, or `unmapped` (neither
namespace names the member). Class-level `presence`: `both`, `yarn_only`, or `mojmap_only`.
`422` when the requested `from` namespace is unavailable for that version.

Note: `to` is validated as a namespace name, but it is never checked for availability and it
does not affect what is returned. Alignment is always Yarn to Mojmap.

### Bytecode and source

| Endpoint                                        | Description                                                                                                                                   |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/bytecode/{version}/{className...}` | Disassembled bytecode (ASM Textifier); `namespace`, `format=text/json`                                                                        |
| `GET /api/v1/source/{version}/{className...}`   | Decompiled `.java` for a class; `namespace=yarn/mojmap`, `format=text/json`                                                                   |
| `GET /api/v1/tokens/{version}/{className...}`   | `{source, tokens}`: resolves each `.java` identifier to owner, name, and descriptor (JavaParser); `namespace=yarn/mojmap`, `format=text/json` |

`source`: when no class matches the name exactly, the endpoint falls back to the single class
of that version with the same simple name. This resolves both a short name
(`ZombifiedPiglin`) and a class that moved package. The response `class` field names the
class actually served. When the simple name is ambiguous or unknown, the call returns `404`,
and `message` lists the candidates. `format=text` returns the source as `text/plain`.
`format=text` on `tokens` returns TSV with a `#`-prefixed header line, one token per line.

### Blame

| Endpoint                                     | Description                                                                                                                                |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/blame/{version}/{className...}` | Version that last changed each line of the class source. `lines` holds one entry per line, line 1 first, and each entry indexes `versions` |

`className` resolves the same way as `/source`. One `git blame` over the source repository
answers the whole file, so use this instead of walking `/diff/patch` version by version. A
version indexed from the artifact store alone has no source repository and returns `404`.

### Hierarchy and references

| Endpoint                                         | Description                                                                                                                                                             |
|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/hierarchy/{version}/{className...}` | Supertypes and subtypes of a class (nodes/edges, ASM scan of the named jar); `namespace=yarn/mojmap`                                                                    |
| `GET /api/v1/references/{version}?q=<key>`       | Uses of classes or members (`q` is `owner` or `owner:name:descriptor`, repeatable); `namespace=yarn/mojmap`                                                            |
| `POST /api/v1/exists/{version}`                  | Batch existence check for classes/members; body `{namespace, members[]}` (keys are `owner` or `owner:name:descriptor`); response `{results:[{key, exists, closest, reason}]}` |

`references` takes up to 25 `q` values, and `to` extends the walk from `{version}` to a second
version, up to 25 versions per call. The response is `{namespace, results[]}`, one entry per
(version, target) as `{version, query, references[]}`. The per-version index is built once and
served to every target of that version, so asking many targets of one version costs one scan.
Use the batch form for the `@At(target = ...)` half of a mixin: `/exists` covers the method
injected into, and a signature can survive a version while a call inside its body moves
elsewhere. `404` when no requested version has a named jar in that namespace.

`exists` scans the version's named jar with ASM (cached per version and namespace), so
descriptors match exactly, without remapping. It accepts up to 2000 keys per request, and
returns `404` when the named jar for the version is missing. This is the only `POST`
endpoint under `/exists`, and one of two endpoints never cached: the result depends on the
request body. Intended for validating mixin or shadow targets before a mod update, in one call.

A key that missed carries the nearest declaration in `closest`, in the same key form, with
`reason` for why it differs:

- `inherited`: a supertype declares this exact signature, so the call still resolves at runtime.
  A mixin `@At` target is valid; a `@Shadow` has to name the supertype.
- `descriptor`: the owner declares this name under another descriptor, so the signature changed.
  `closest` carries the descriptor the version has.

Both are null when the version declares nothing of that name under that owner, and when `exists`
is true. Without them a bare `false` reads the same whether the descriptor moved, the member moved
to a supertype, or the name is gone, and answering that took a `/source` read per key.

---

## General contract rules

- **Namespaces:** `yarn`, `mojmap`, `intermediary`, `obfuscated` (alias `obf`). See the
  tables above for which endpoints accept which.
- **Default version** (search, translate): the latest `release` by semver order.
- **Errors:** one `ApiError { error, message, status }` shape, where `status` repeats the
  HTTP code: `400 invalid_query` (validation), `404 not_found`, `422 namespace_unavailable`
  (the version lacks the requested namespace).
- **Response formats:** JSON by default; `bytecode`/`source`/`tokens?format=text` return
  `text/plain`; a diff patch (`format=patch`/`git`) returns `text/x-diff`.
- **`hasIntermediary`** means the version carries intermediary names, not that a standalone
  intermediary file exists. Yarn's tiny v2 format is `official->intermediary->named`, so
  every yarn version carries intermediary. An unobfuscated release that yarn does not cover
  carries intermediary only when `sources.unobfuscated-intermediary-mappings` is set.
- **`hasMojmap`** is also true on unobfuscated releases. They publish no mappings of their
  own, but the jar already carries Mojang's names, so the `official` namespace is the mojmap
  namespace: on those versions, `obfuscated` and `mojmap` return the same name.
- **Rate limit:** 200 requests per 60 seconds on the `/api/v1` route group (meta endpoints
  are not limited).

### Behavioral notes (for agents)

- `search.totalResults` is the size of the current page, not the total match count.
- `diff*` on an unknown version returns `200` with an empty or zero result, not `404`.
- `translate` and `compare` on an unknown version return `404`, not `422`.
- `compare`: the `to` parameter is validated but does not affect the result; alignment is
  always Yarn to Mojmap. `from` picks the column for the class lookup and decides the `422`
  check.
- `/diff/files`: `context`, `limit`, and `function` are read only when `format=patch/git`;
  they are ignored when `format=json`.

---

## What the index stores

The SQLite index (built by `index`, opened read-only by `serve`) holds: `versions`
(metadata, semver order, counts, and each version's FTS rowid range), unified obf-keyed rows
in `classes`/`methods`/`fields` (with `presence` in `{both, yarn_only, mojmap_only}`), and
the FTS5 table `search_index` over names. It does not store decompiled source, bytecode, or
git blobs; those are read on demand from the read-only store.

### Members of an unobfuscated release

Intermediary names an overriding method only in the class that first declares it, and the name
propagates down the hierarchy instead of being written again for each subclass. An obfuscated
release hides that, because its mojmap tiny lists every declared member and fills the gap. An
unobfuscated release has no mojmap tiny, so a mapping-only parse gives it no overrides at all.

The indexer therefore reads the version's own jar for these releases and adds the members the
mappings leave out. Members the mappings know keep their yarn and intermediary names; the ones
only the jar knows enter mojmap-only, which is what they are. The classes come from the mappings
either way.

Scale of the gap before the fix, measured on one build indexed both ways: `1.21.11` held 89,606
methods against 56,986 for `1.21.11_unobfuscated`, and a `/diff` between the two reported 32,355
methods removed with nothing having changed. Fields were within 1% (45,679 against 45,263),
because a field is never overridden. Reaching `/diff` from 1.21.11 to 26.1, that same gap made
49 of 62 members reported as removed from `ServerLevel` still declared in 26.1.

An index built before this fix carries the gap until the affected versions are re-indexed.

---

## Future plan

1. **Frontend: full explorer.** Add a drill-down view over `GET /api/v1/compare` (member
   table). Optionally integrate `translate`, `diff`, and `bytecode`. Consider serving the
   `dist/` static bundle from Ktor itself, instead of a separate reverse proxy.
2. **Search quality.** Improve ranking (mapped over intermediary over obf, with a
   class/owner bonus). `score` currently uses only `1/(1+|rank|)`.
3. **Commit-accurate diff and history** through JGit (optional), on top of the current
   jar-based hot path.
4. **Phase 5 (low priority).** In-browser deobfuscation and decompilation, for versions
   without a ready jar.
