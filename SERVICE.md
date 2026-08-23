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
| Method body hash      | One method's body hashed for each version of a range, equal neighbours collapsed into spans. Answers "is there anything to re-check" where a source diff reports the decompiler's cosmetics. |
| Compare Yarn/Mojmap   | Member-correspondence table for one class, between Yarn and Mojmap.                                                                                                                                     |
| Bytecode              | Disassembled bytecode of a class (ASM Textifier) in any namespace, as text or JSON.                                                                                                                     |
| Source                | Decompiled `.java` for a class, from the artifact store (Yarn or Mojmap namespace).                                                                                                                     |
| Inheritance hierarchy | Supertypes and subtypes of a class (ASM scan of the named jar), for the "View Inheritance" right-click action in the UI.                                                                                |
| Find all references   | Reverse index of where a class, method, or field is used (on-demand ASM scan of the named jar, cached per version and namespace).                                                                       |
| Existence check       | Batch check that classes or members still exist in a version (`POST /exists`, ASM scan of the named jar). Validates mixin targets before a mod update.                                                  |
| Mixin target matrix   | A set of mixin targets checked against every version of a range (`POST /validate`), collapsed into spans. Reports a call that left the hooked method, which a signature check cannot see. |
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
  - For `serve`: a **built index** at `database.path`, plus the search index beside it (`mappinglens.db` gives `mappinglens-search.db`). The server exits at startup when either is missing, with a hint to run `index`. Source repositories are not needed at startup, but `diff`, `bytecode`, and `source` read jars from `artifact-store` on demand, during the request.
  - For `/openapi.*` and `/docs`: the classpath resource `openapi/mappinglens-api.yaml` (bundled in the jar).

> **Schema compatibility.** An index built before the rewrite has no `versions.sort_index` or
> `classes.presence` column, and `serve` fails on it with `500 no such column: versions.sort_index`.
> Rebuild it with `index` to fix this.
>
> Names are searched through a second file, `mappinglens-search.db`, beside `database.path`.
> `serve` exits at startup when it is missing, and an index built before it has only the old
> single `search_index` table inside the main file. One ordinary `index` run fixes both: it builds
> the search table of every version that has none, reading the names out of the index itself
> (52.3M rows in 485s over 526 versions, no re-index of the mappings), and drops the old table.
> `VACUUM` on the main file then returns its 14.9 GB to the file system.
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
| `mappinglens.indexing.mappings`                          | `"yarn,mojmap"`       | `MAPPINGS`                              | Named mappings the `index` command reads: `yarn`, `mojmap` or both                   |
| `mappinglens.indexing.only-releases`                     | `false`               | `ONLY_RELEASES`                         | Index the stable releases alone; see the note below the table                        |

`indexing.only-releases` keeps the versions Mojang types as a release. Everything Mojang types
as a snapshot goes with the snapshots: pre-releases, release candidates, April Fools versions
and the combat snapshots. The `_unobfuscated` variants go with them, because each duplicates a
build that is indexed under its own id. `indexing.mappings` leaves a named mapping out of the
mapping scan, out of the source-file scan and out of the reference index, and `/versions` then
reports that namespace as absent for every version.

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
2. A new version in the Mojang manifest triggers the first preset `MAPPINGS` names, which is
   `mojmap` unless mojmap is left out. The run is not limited to the new version: GitCraft
   builds everything missing from the store, which is also what fills an empty store. Mojmap
   needs neither intermediary nor yarn, so the version reaches the search index in the same
   cycle. With `ONLY_RELEASES` the run carries `--only-stable`, and only the release id of the
   manifest is compared, so a new snapshot starts no cycle.
3. A published yarn build newer than the one in the artifact store, or no yarn on disk at
   all, triggers the `yarn` preset, with `--refresh-only-version` when needed. `MAPPINGS`
   without yarn skips this step.
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

| Variable                  | Default             | Purpose                                                                 |
|---------------------------|---------------------|-------------------------------------------------------------------------|
| `UPDATE_INTERVAL_SECONDS` | `3600`              | Pause between checks                                                    |
| `MAPPINGS`                | `mojmap yarn`       | Mappings to build and to index; the indexer reads the same variable     |
| `ONLY_RELEASES`           | `false`             | Builds and indexes the stable releases alone (GitCraft `--only-stable`) |
| `REFS`                    | `all`               | Scope of the reverse-reference index: `all`, `releases` or `none`       |
| `PORT`                    | `8080`              | `serve` port                                                            |
| `MAPPINGLENS_*`           | paths under `/data` | Same variables as outside the container                                 |
| `GITCRAFT_JAVA_OPTS`      | none                | JVM options for the GitCraft run, for example `-Xmx16g`                 |
| `INDEX_JAVA_OPTS`         | none                | JVM options for `index`, for example `-Xmx4g`                           |
| `SERVE_JAVA_OPTS`         | none                | JVM options for `serve`, for example `-Xmx4g`                           |

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
| `GET /`             | Text pointer to `/docs` and `/openapi.json`                                                   |
| `GET /health`       | Liveness check. Returns `ok`                                                                  |
| `GET /openapi.json` | OpenAPI 3.1 as real JSON (the YAML is parsed by SnakeYAML and re-exported)                    |
| `GET /openapi.yaml` | OpenAPI 3.1 as YAML                                                                           |
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
(1 to 200, default 50), `offset` (0 or more), `exact` (`true`/`false`), `includeSynthetic`
(`true`/`false`, default `false`). `q` also accepts the form `Owner#member`, `Owner.member`, or
`Owner/member`.

A member row carries the descriptor three times: `intermediaryDescriptor` as the index stores it,
plus `yarnDescriptor` and `mojmapDescriptor`, which are the official descriptor rewritten through
the classes of that version. The named two are what `/exists` matches on, so a key built out of a
search row goes there unchanged. Both are filled in one query for the whole page of results. A
namespace the row has no name in gets no descriptor either, and a type the version does not name
stays as it came, the way a JDK class does. `/diff` still reports the intermediary one alone.

`score` runs `0` to just under `1`, and higher is better. Rows come back best first, so the order
and the field agree. The value is derived from the FTS5 bm25 rank of that one query, and compares
the results of a single call only.

`includeSynthetic` (default `false`) decides whether javac lambda bodies are returned. The mappings
name `lambda$addRecipes$0` like any other method, so a search for `addRecipes` used to return the
lambda next to the method it is written in. A row that is kept carries `synthetic: true`. The FTS
table is contentless and has no column to filter on, so the drop happens after the match, and the
query over-fetches four times the page to keep a full one.

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

- `package` is a package-path prefix over the whole diff, read in `namespace`:
  `net/minecraft/world/level/block` for mojmap, `net/minecraft/block` for yarn.
  `class` targets exactly one class
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
- Because of that tracking, two spellings of one member return the same spans under two
  different `query` values. The name each version uses is in that span's `members[]`, never in
  `query`. `ResourceKey:location` and `ResourceKey:identifier` both answer with the whole
  history, one span reading `"mojmap": "location"` and the next `"mojmap": "identifier"`.
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
- `present: false` with a non-null `owner` means the class is still there and **the owner
  declares no member of that name**. `owner: null` means the class itself is gone.
- `present: false` with `reason: "inherited"` means a supertype declares it, so the call still
  resolves; `declaredIn` names that supertype in the requested namespace and `members[]`
  describes its declaration. With no `reason`, the member really is absent, on the owner and
  above it. The supertype is read from the named jar of the newest version of the range that has
  both the class and a jar, one class header at a time, and is then followed through the index
  like any other class, so a supertype renamed later in the range still answers. 13 of the
  2018-2019 snapshots carry no `sort_index`, which sorts them after everything else, so the
  anchor is picked out of the requested range rather than out of the index-wide order.
- `type: "unknown"` carries spans as well: nothing in the index names this member, and the spans
  say over which versions. It used to return an empty list, which read like a malformed query.
- `/history` reads the mapping index, and `/exists` reads the jar the mod runs against. When the
  two disagree, the jar is right.
- Twin versions (`1.21.11` and `1.21.11_unobfuscated`) sit next to each other in the version
  order, and the twin's yarn and intermediary names are empty, so such a pair used to yield two
  adjacent spans. The walk now skips variants; `includeVariants=true` brings the old behavior
  back. `releasesOnly=true` drops the snapshots as well.

### Method body hash

| Endpoint                       | Description                                                                     |
|--------------------------------|---------------------------------------------------------------------------------|
| `GET /api/v1/bodyhash?q=<key>` | One method's body hashed for each version of a range, equal neighbours collapsed |

Parameters: `q` (required, repeatable, up to 50: `owner:name` or `owner:name:descriptor`),
`namespace` (`yarn`/`mojmap`, default `mojmap`), `from` and `to` (both required),
`releasesOnly`, `includeVariants`, `normalize` (`named` by default, or `intermediary`).
The range is capped at 60 versions, counted after the filters, because each version costs a jar
open and one entry read.

The answer to "did the behavior change between A and B", which `/diff/patch` cannot give: that
one works on decompiled source and reports the decompiler's own cosmetics as a change even with
`ignoreWhitespace=true`. The response is `{namespace, normalize, results[]}`, each entry
`{query, spans[]}`, each span `{from, to, versions, hash}`. `hash` is null where the version has
no such method, so a gap reads as a gap. Without a descriptor every overload of the name hashes
together, sorted, so the answer does not depend on declaration order.

The hash covers the instructions, the labels they jump to, the try/catch table and the frame
sizes. ASM resolves the constant pool as it reads and `SKIP_DEBUG` drops line numbers and local
variable names, so a recompile on its own does not move it. `normalize=intermediary` renames the
class types through intermediary first, which is what makes a hash survive a class rename or a
package move; member names stay as the namespace spells them either way, so a renamed callee
still shows. On `LivingEntity.baseTick` over the 16 releases from 1.21 to 26.2 the span breaks at
1.21.9, where `WorldBorder.getDamageSafeZone` became `getSafeZone`, and `WorldBorder.getCenterX`
holds one hash across all 16.

Two ceilings. A lambda body is a method of its own and is not followed, so a change confined to a
lambda does not move the enclosing method's hash; the `$12` of `lambda$baseTick$12` is normalized
away, so an unrelated lambda added above does not move it either. And an unobfuscated version has
no intermediary names, so under `normalize=intermediary` its classes keep the names they have —
the obfuscation boundary breaks the span under either mode.

### Mixin target matrix

| Endpoint                | Description                                                            |
|-------------------------|------------------------------------------------------------------------|
| `POST /api/v1/validate` | A set of mixin targets checked against every version of a range        |

Query parameters: `from` and `to` (both required), `releasesOnly`, `includeVariants`. The range is
capped at 60 versions, counted after the filters. The body is
`{namespace, targets: [{id, owner, method, descriptor?, at?}]}`, at most 50 targets, where `at` is
`{value, target}` with `value` either `INVOKE` or `FIELD` and `target` a full
`owner:name:descriptor`. Omitting `descriptor` follows every overload of the name; omitting `at`
checks the signature alone.

The answer is `{namespace, results[]}`, each entry `{id, spans[]}`, each span
`{from, to, versions, status, atCount, closest, movedTo}`. Five statuses:

- `ok`: the method is there, and when `at` was given so is the instruction it names. `atCount` says
  how many times, which is what `@At(ordinal = N)` numbers `0 .. atCount-1`.
- `renamed`: the name is there under another descriptor, and `closest` carries the one the version
  has.
- `inherited`: a supertype declares it. The call resolves at runtime, but a mixin applies to the
  class that declares the method, so the target has to name the supertype, which `closest` does.
- `call_moved`: the method is there and the `at` instruction is not. `movedTo` names the method
  that holds the call now.
- `missing`: neither the method nor a near declaration. `movedTo` is still filled when the class
  holds the call in exactly one other method, which is what a renamed method looks like from here.

`call_moved` is the reason the endpoint exists. A call that moves out of the hooked method leaves
every signature intact, so `/exists` reports nothing while the injection point breaks in silence.
`ServerPlayer#adjustSpawnLocation` runs from 1.21 to 26.2 unchanged, and from 1.21.9 its border
call sits in `PlayerSpawnFinder#findSpawn`. A move inside the class is read straight out of the
same class parse; a move out of it is paired through `/diff/references` against the newest earlier
version that still had the call.

A pair costs one read of one jar entry, so the spec's own two targets over the 16 releases from
1.21 to 26.2 answer in 72ms. Supertypes are read one class header at a time, and only on a miss.

Two ceilings. A call that moved into a lambda of the hooked method reads as `call_moved` with
`movedTo` naming `lambda$stopSleeping$9` literally, because that is what a mixin has to target, and
the index moves between versions. And an `@At` value that does not name one instruction (`HEAD`,
`RETURN`, `CONSTANT`) is rejected rather than guessed at.

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

`versions` never names an `_unobfuscated` variant. A variant is a second pass over a build
already indexed, so its commit records how the decompiler named things rather than a change to
the class. Each line is attributed to the version the variant was built from.

### Hierarchy and references

| Endpoint                                         | Description                                                                                                                                                             |
|--------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/hierarchy/{version}/{className...}` | Supertypes and subtypes of a class (nodes/edges, ASM scan of the named jar); `namespace=yarn/mojmap`                                                                    |
| `GET /api/v1/references/{version}?q=<key>`       | Uses of classes or members (`q` is `owner` or `owner:name:descriptor`, repeatable); `namespace=yarn/mojmap`, `releasesOnly=<bool>`                                     |
| `POST /api/v1/references/{version}`              | The same call with the targets in a body `{namespace, targets[], to, releasesOnly, includeVariants, depth}`, up to 2000 targets; `QUERY` is accepted here too          |
| `GET /api/v1/diff/references?from=&to=&q=`      | How the sites using a class or member changed between two versions; `{changes: {added, removed, moved}}`                                                               |
| `POST /api/v1/exists/{version}`                  | Batch existence check for classes/members; body `{namespace, members[]}` (keys are `owner`, `owner:name` or `owner:name:descriptor`); response `{results:[{key, exists, closest, reason, candidates}]}` |
| `POST /api/v1/validate?from=&to=`                | The same check over a range, plus the injection point: body `{namespace, targets[]}`, response one span list per target with `ok`/`renamed`/`inherited`/`call_moved`/`missing` |

Each `references` group carries `resolved`. False means the group is not an answer about the target:
the key carries no descriptor and so can never match the `name:descriptor` the index is keyed by, or
its descriptor names no member the index knows, or the index holds no row for the owner. `candidates`
then lists the keys the query would have matched. All three used to answer with an empty
`references`, which is also what "nothing calls this" looks like, so a malformed key read as a fact
about the version. A key may resolve in one version of a range and not in the next, which is why the
flag sits on the group rather than on the request.

The owner's row is read once per group and answers both halves, so the flag costs nothing. The row
is what the reverse index stores, and it holds only what is referenced: a missing row is an absent
class or a class nothing mentions, and this endpoint cannot tell the two apart. It reports
`resolved: false` rather than claiming a zero.

`references` takes up to 25 `q` values, and `to` extends the walk from `{version}` to a second
version. The range itself is not capped. What is capped is how many of its versions the prebuilt
reference index does not cover, at 25, because each of those costs a jar scan of 0.4 to 0.8s and
~140 MB. The index covers releases, so `releasesOnly=true` walks the whole release line in one
call: 1.14 to 26.2 is 47 releases and answers in 48ms. Only 43 of them come back, because Mojang's
official mappings start at 1.14.4 and a version without a named jar has nothing to answer with.
Without the filter that same range is 469 versions, 426 of them uncovered, and is refused with a
message that says so.
A request line above 4096 bytes is rejected by the HTTP parser before the route sees it, which is
where the limit of 25 comes from; the `POST` form carries up to 2000 targets in a body instead, and
`QUERY` (RFC 10008) is accepted on the same path with the same body. Neither body form is cached,
because a URL-keyed cache cannot see the body. The response is `{namespace, results[]}`, one entry
per (version, target) as `{version, query, references[]}`. The per-version index is built once and
served to every target of that version, so asking many targets of one version costs one scan. Eight
indexes stay in memory at a time, the least recently used one first out.

The index records the owner written in the call instruction, not the class that declares the
member. A call to an inherited method carries the subclass the caller holds, so
`Level:getRespawnData` answers with nothing while `ServerLevel:getRespawnData` answers with 12
sites. No parameter walks the hierarchy. Read an empty result for a member as "no caller names this
owner", then ask `/hierarchy` for the subtypes and repeat the query for each subtype.

Each site carries `count`, the number of instructions in it that hit the target, which is what
`@At(ordinal = N)` numbers `0 .. count-1`. A call written inside a lambda reports `member` as the
method the lambda is written in and `synthetic` as the javac body it compiled to (`lambda$tick$3`),
because that index moves between versions. Calls inside the calling class itself are indexed, and
method references are followed through their `invokedynamic`. `depth` above 1 walks the callers of
the callers and adds `paths`, the chains reaching the target, outermost frame first, at most 200.

`diff/references` groups those sites by the method they sit in and reports the difference between
two versions. `moved` pairs a removed site with an added one when both reach exactly the same
members, matching by the same method name, then the same class, then a lone pair. A call that moves
between methods keeps every signature intact, so `/exists` reports nothing while `@At` breaks in
silence, which is the case this endpoint exists for.
Use the batch form for the `@At(target = ...)` half of a mixin: `/exists` covers the method
injected into, and a signature can survive a version while a call inside its body moves
elsewhere. `404` when no requested version has a named jar in that namespace.

`exists` scans the version's named jar with ASM (cached per version and namespace), so
descriptors match exactly, without remapping. It accepts up to 2000 keys per request, and
returns `404` when the named jar for the version is missing. This is the only `POST`
endpoint under `/exists`, and like every `POST` it is never cached: the result depends on the
request body. Intended for validating mixin or shadow targets before a mod update, in one call.

A key that missed carries the nearest declaration in `closest`, in the same key form, with
`reason` for why it differs:

- `inherited`: a supertype declares this exact signature, so the call still resolves at runtime.
  A mixin `@At` target is valid; a `@Shadow` has to name the supertype.
- `descriptor`: the owner declares this name under another descriptor **of the same kind**, so the
  signature changed. `closest` carries the descriptor the version has.
- `kind`: the owner declares this name as a field where a method was asked for, or the other way
  round. `closest` names that declaration, and it is not a drop-in replacement for the key. The two
  read apart by the descriptor alone, because a method descriptor opens with `(` and a field
  descriptor does not. On 26.2, `Level:getBlockState` asked for as a field answers this way.

A same-kind candidate wins over one of the other kind, which is what makes `descriptor` mean what it
says. `CommandSourceStack` on 26.2 declares both a field `permissions` and a method `permissions()`,
and the method is the answer to a method key.

Both are null when the version declares nothing of that name under that owner, and when `exists`
is true. Without them a bare `false` reads the same whether the descriptor moved, the member moved
to a supertype, or the name is gone, and answering that took a `/source` read per key.

A key given without a descriptor is a resolve rather than an existence check. It can never match
`owner:name:descriptor`, so instead of a bare `false` it answers with `candidates`: every
declaration under that name, in key form, read from the same jar with no ranking and no fuzzy
matching. One entry is the canonical key for `owner#name`, several are its overloads, and `closest`
and `reason` stay null. This is the resolve that taking `results[0]` from `/search` used to stand
in for.

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
(metadata, semver order, counts), and unified obf-keyed rows in `classes`/`methods`/`fields`
(with `presence` in `{both, yarn_only, mojmap_only}`). It does not store decompiled source,
bytecode, or git blobs; those are read on demand from the read-only store.

References are answered from a third file, `mappinglens-refs.db`, holding one row for each class
of each (version, namespace): who references each of its members, deflated. Without it the server
scans a version's whole named jar the first time it is asked about that version, which costs 0.4 to
0.8s and holds ~140 MB for as long as the index is cached. A row costs 0.7ms with a fresh connection
and 0.1ms on a warm one, and holds nothing: over the checks above the live heap stayed at 12 MB
where the scanning path reached 584 MB. One row for each class rather than for each member, because
the class is the unit every reader asks for and because it deflates 6.8 times where member-sized
blobs of the same data reach 2.7.

`index` builds it for the releases, which is 90 (version, namespace) pairs, 1.0 GB and 57s. Pass
`-refs=all` for every version (10.3 GB, 11 minutes) or `-refs=none` to skip the step. The container
passes `-refs=all` by default, through the `REFS` variable. A pair is
built once and then skipped, since the jar behind it never changes. The file is optional: a version
it does not cover is answered by the scan, so a partial file is a valid file, and a server without
the file behaves as it did before.

Names are searched through a second file, `mappinglens-search.db`, which holds one contentless
FTS5 table for each version, named `search_v<version row id>`. FTS5 answers a prefix term by
merging the doclists of every term carrying that prefix over the whole table, before a rowid
filter narrows anything, so one table over all versions read 5.5M postings of `get*` to rank the
13697 of the version asked for. One table for each version answers the same query in 24ms instead
of 959ms and costs 2% more bytes. The tables are contentless because the server reads no name back
out of them, only which row matched and its bm25 rank: the row identity rides in the rowid, the
element kind in its low two bits. Over 52.3M rows that makes the whole file 5.3 GB, where the
single table it replaces held 14.9 GB. The file is separate because 526 virtual tables add about
2600 rows to `sqlite_master`, and the server opens a fresh connection for each request; parsing
them costs 7ms that every endpoint would otherwise pay.

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
   class/owner bonus). `score` currently uses only `|rank|/(1+|rank|)`.
3. **Commit-accurate diff and history** through JGit (optional), on top of the current
   jar-based hot path.
4. **Phase 5 (low priority).** In-browser deobfuscation and decompilation, for versions
   without a ready jar.
