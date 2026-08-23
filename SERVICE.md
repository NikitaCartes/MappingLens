# MappingLens Service Reference

*(Русская версия: [SERVICE.ru.md](SERVICE.ru.md))*

MappingLens is a stateless, read-only service for search, translation, comparison and inspection
of Minecraft mappings (Yarn, Mojmap, Intermediary, Obfuscated).

- **`index`**: the offline indexer. It is the only writer of the SQLite index.
- **`index-resources`**: builds the optional resource index.
- **`serve`**: the stateless HTTP server (Ktor/Netty). It opens the index read-only.
- **`frontend/`**: a browser explorer (React + Vite) over the REST API.

The server decompiles nothing and mutates nothing. Source and bytecode are read on demand from a
read-only GitCraft store, and the index is opened with `PRAGMA query_only=ON`.

---

## Features

| Feature               | Description                                                                          |
|-----------------------|--------------------------------------------------------------------------------------|
| Mapping search        | FTS5 search over classes, methods and fields in one version. Each row carries the name in every namespace. |
| Name translation      | Translates one name, or a batch of keys, between namespaces.                         |
| Symbol diff           | Added, removed and renamed classes, methods and fields between two versions.         |
| Source diff           | Changed files, and a unified or git patch of the source.                             |
| Version history       | One class or member across every version, as ranges that share an answer.            |
| Method body hash      | One method's body hashed for each version of a range, equal neighbours collapsed.    |
| Compare Yarn/Mojmap   | Member-correspondence table for one class.                                           |
| Bytecode              | ASM Textifier output for a class, in any namespace.                                  |
| Source                | Decompiled `.java` from the artifact store.                                          |
| Inheritance hierarchy | Supertypes and subtypes of a class.                                                  |
| References            | Where a class, method or field is used, from a prebuilt reverse index.               |
| Existence check       | Batch check that classes or members still exist in a version.                        |
| Mixin target matrix   | A set of mixin targets checked against every version of a range.                     |
| Source tokens         | Each `.java` identifier resolved to owner, name and descriptor.                      |
| Blame                 | The version that last changed each line of a class source.                           |
| Versions              | Indexed versions with namespace flags, counts and semver order.                      |
| Resource explorer     | Every Minecraft resource of every version from 1.14 on, from a clone of `misode/mcmeta`. Optional. |
| OpenAPI / Swagger     | `/openapi.json`, `/openapi.yaml`, and Swagger UI at `/docs`.                         |
| Frontend explorer     | Browser UI over the mappings and the resources.                                      |

---

## Requirements

- **JDK 21** (Gradle toolchain). Stack: Kotlin 2.2.20, Ktor 3.2.3, Exposed 0.56, sqlite-jdbc.
- **Node.js**, for the frontend.
- **Data on disk:**
  - `index` reads `artifact-store` (with `mappings/`, `mc-versions/`, `decompiled/<version>/`,
    `remapped-mc/<version>/`), plus `yarn`, `mojmap` and `intermediary`.
  - `serve` needs the index at `database.path` and the search index beside it
    (`mappinglens.db` gives `mappinglens-search.db`). It exits at startup when either is missing.
    Source repositories are not needed at startup, but `diff`, `bytecode` and `source` read jars
    from `artifact-store` during the request.
  - `/openapi.*` and `/docs` need the classpath resource `openapi/mappinglens-api.yaml`.
  - The resource explorer needs a clone of `misode/mcmeta` at `resources.repo` and its index
    (`mappinglens-resources.db`), built by `index-resources`. Without them the `/api/v1/resources`
    endpoints answer `404 resources_disabled`.

> **Schema compatibility.** An index missing `versions.sort_index`, `classes.presence` or
> `versions.class_count` makes `serve` fail with `500 no such column: ...`. One `index` run adds
> every column; `dev/migrate-version-counts.sql` adds and fills the count columns without a
> rebuild. An index built before `mappinglens-search.db` carries the old `search_index` table
> inside the main file: one `index` run builds the per-version tables and drops it, and `VACUUM`
> then returns 14.9 GB to the file system.

---

## Configuration

A HOCON file, `application.conf`, is created from the bundled template on first run. Every path is
overridable through an environment variable.

| Key                                                      | Default               | Env / CLI                               | Purpose                                                                              |
|----------------------------------------------------------|-----------------------|-----------------------------------------|--------------------------------------------------------------------------------------|
| `ktor.deployment.host`                                   | `0.0.0.0`             | `HOST`, `-host`                         | Bind address                                                                         |
| `ktor.deployment.port`                                   | `8080`                | `PORT`, `-port`                         | Port                                                                                 |
| `mappinglens.database.path`                              | `data/mappinglens.db` | `MAPPINGLENS_DB_PATH`                   | SQLite index (`serve` opens it read-only; `index` writes it)                         |
| `mappinglens.sources.artifact-store`                     | `data/artifact-store` | `MAPPINGLENS_ARTIFACT_STORE`            | Store root                                                                           |
| `mappinglens.sources.yarn-repo`                          | `data/yarn`           | `MAPPINGLENS_YARN_REPO`                 | Yarn source (indexing only)                                                          |
| `mappinglens.sources.mojmap-repo`                        | `data/mojmap`         | `MAPPINGLENS_MOJMAP_REPO`               | Mojmap source (indexing only)                                                        |
| `mappinglens.sources.intermediary-mappings`              | `data/intermediary`   | `MAPPINGLENS_INTERMEDIARY`              | Intermediary source (indexing only)                                                  |
| `mappinglens.sources.unobfuscated-intermediary-mappings` | `""` (off)            | `MAPPINGLENS_UNOBFUSCATED_INTERMEDIARY` | Intermediary source for unobfuscated releases, a separate repository (indexing only) |
| `mappinglens.search.max-results`                         | `200`                 | n/a                                     | Upper bound for the search `limit`                                                   |
| `mappinglens.search.default-results`                     | `50`                  | n/a                                     | Default search `limit`                                                               |
| `mappinglens.indexing.initial-versions`                  | `[]` (all)            | n/a                                     | Versions the `index` command builds; **ignored by `serve`**                          |
| `mappinglens.indexing.mappings`                          | `"yarn,mojmap"`       | `MAPPINGS`                              | Named mappings the `index` command reads: `yarn`, `mojmap` or both                   |
| `mappinglens.indexing.only-releases`                     | `false`               | `ONLY_RELEASES`                         | Index the stable releases alone                                                      |
| `mappinglens.resources.repo`                             | `""` (off)            | `MAPPINGLENS_MCMETA_REPO`               | Clone of `misode/mcmeta`; empty disables the resource explorer                       |

`resources.repo` is the whole switch for the resource explorer, and the branches the clone holds
are the granularity: the indexer reads `assets`, `diff`, `registries` and `atlas` and skips
whichever the clone does not carry. All four cost 2.1 GiB, of which `assets` is 1.5 GiB and `atlas`
575 MiB. Add a branch later with `git remote set-branches --add origin <branch>` and refetch.

`indexing.only-releases` keeps the versions Mojang types as a release. Pre-releases, release
candidates, April Fools versions, combat snapshots and the `_unobfuscated` variants go with the
snapshots. `indexing.mappings` leaves a named mapping out of the mapping scan, the source-file scan
and the reference index, and `/versions` then reports that namespace as absent.

**Ktor plugins:** ContentNegotiation (kotlinx JSON), CallLogging, CORS (`anyHost`, GET and POST,
`Content-Type`), RateLimit (200 requests per 60 seconds on `/api/v1`), StatusPages
(`IllegalArgumentException` maps to `400 invalid_query`, everything else to `500 internal_error`),
and a cache-headers plugin for successful `/api/v1` responses.

---

## Running

```sh
# 1. Build the read-only index (the only writer of the database).
./gradlew run --args="index"

# 1a. Rebuild only the given versions. `-versions` overrides `indexing.initial-versions`;
#     a version already indexed also needs `-force`.
./gradlew run --args="index -force -versions=1.21.4,26.2"

# 1b. Build the resource index (optional). Needs mappinglens.resources.repo.
#     `-fts=content` skips the translation index, `-fts=none` skips both.
./gradlew run --args="index-resources"

# 2. Start the server. `serve` is the default when the first argument is absent or starts with '-'.
./gradlew run --args="serve"            # listens on :8080

# 3. Frontend (dev): Vite proxies /api -> http://localhost:8080
cd frontend
npm install
npm run dev                              # http://localhost:5173
npm run build                            # static bundle in dist/
```

`main()` reads `args[0]` as the subcommand only when it does not start with `-`. Any token other
than `index`, `index-resources` or `serve` prints usage and exits with code 2.

For production, deploy `dist/` as static files and proxy `/api` to a running `serve`.

---

## Docker

Two services. `mappinglens` keeps `serve` running and builds its own data: GitCraft fills the
artifact store and the source repositories, and `index` updates the index. `frontend` serves the
built bundle through nginx, which proxies `/api/` to `http://mappinglens:8080`.

```sh
docker compose -f docker/docker-compose.yml up -d --build
```

| Service       | Host port                             | Dockerfile                   |
|---------------|---------------------------------------|------------------------------|
| `mappinglens` | `8080`: API and Swagger UI at `/docs` | `docker/Dockerfile`          |
| `frontend`    | `3000`: UI                            | `docker/Dockerfile.frontend` |

The API image carries the fat jar (JDK 21), GitCraft (JDK 25), checkouts of `FabricMC/intermediary`,
`RelativityMC/intermediary`, `FabricMC/yarn` and `RelativityMC/yarn`, and the GitCraft presets. The
build context for both images is the project root, and volumes are declared in the compose file.

| Directory under the `/data` volume | Contents                                                        |
|------------------------------------|-----------------------------------------------------------------|
| `artifact-store/`                  | GitCraft's artifact store                                       |
| `repos/yarn`, `repos/mojmap`       | Git repositories of decompiled source                           |
| `index/mappinglens.db`             | SQLite index                                                    |
| `mcmeta/`                          | Clone of `misode/mcmeta`, unless `MCMETA_BRANCHES` is empty     |
| `state/`                           | Update-cycle markers, including the store snapshot `store.files` |
| `gradle/`                          | Gradle home for the GitCraft runs                               |

`docker/entrypoint.sh` cycle, every `UPDATE_INTERVAL_SECONDS`:

1. Updates the four checkouts and fingerprints their refs.
2. A new version in the Mojang manifest triggers the first preset `MAPPINGS` names. GitCraft builds
   everything missing from the store, which is also what fills an empty store.
3. A published yarn build newer than the one in the store, or no yarn on disk, triggers the `yarn`
   preset.
4. Indexing, which is the only step that indexes, and which reads the store rather than a build's
   exit code: `index -force -versions=<...>` for versions whose files differ from
   `state/store.files`, then a plain `index` for versions missing from the database. The snapshot is
   written only after success.
5. The resource explorer, unless `MCMETA_BRANCHES` is empty. A branch tip that moved rebuilds
   `index/mappinglens-resources.db` in full.
6. A change in either index restarts `serve`, once per cycle.

Every version is compared, not only recent ones. When no mapping repository has moved and the set
of unbuildable versions has not changed, the GitCraft run is skipped.

| Variable                  | Default                            | Purpose                                                          |
|---------------------------|------------------------------------|------------------------------------------------------------------|
| `UPDATE_INTERVAL_SECONDS` | `3600`                             | Pause between checks                                             |
| `MAPPINGS`                | `mojmap yarn`                      | Mappings to build and to index                                   |
| `ONLY_RELEASES`           | `false`                            | Stable releases alone (GitCraft `--only-stable`)                 |
| `REFS`                    | `all`                              | Reverse-reference index: `all`, `releases` or `none`             |
| `MCMETA_BRANCHES`         | `assets diff registries atlas`     | mcmeta branches; empty turns the resource explorer off           |
| `MCMETA_FTS`              | `all`                              | Resource full-text tables: `all`, `content` or `none`            |
| `MCMETA_URL`              | `https://github.com/misode/mcmeta` | Where the clone comes from                                       |
| `PORT`                    | `8080`                             | `serve` port                                                     |
| `MAPPINGLENS_*`           | paths under `/data`                | Same variables as outside the container                          |
| `GITCRAFT_JAVA_OPTS`      | none                               | JVM options for the GitCraft run                                 |
| `INDEX_JAVA_OPTS`         | none                               | JVM options for `index`                                          |
| `SERVE_JAVA_OPTS`         | none                               | JVM options for `serve`                                          |

The build args `GITCRAFT_REPO` and `GITCRAFT_REF` set where GitCraft comes from.

> On an empty volume, the first run builds every version from nothing and takes days, and `serve`
> starts only once an index exists. Mount an existing artifact store over `/data/artifact-store` to
> skip that first build.

---

## Endpoints

Base prefix: `/api/v1`. Every endpoint is `GET` unless noted. `{className...}` and `{name...}` are
catch-all segments: the remaining path joins into a class internal name.

### Meta (not rate-limited)

| Endpoint            | Description                                 |
|---------------------|---------------------------------------------|
| `GET /`             | Text pointer to `/docs` and `/openapi.json` |
| `GET /health`       | Liveness check. Returns `ok`                |
| `GET /openapi.json` | OpenAPI 3.1 as JSON                         |
| `GET /openapi.yaml` | OpenAPI 3.1 as YAML                         |
| `GET /docs`         | Swagger UI                                  |

### Versions

| Endpoint                         | Description                                                                    |
|----------------------------------|--------------------------------------------------------------------------------|
| `GET /api/v1/versions`           | All versions, with namespace flags and counts, semver order (newest first)     |
| `GET /api/v1/versions/{version}` | Metadata for one version (`404 version_not_found`)                             |

A **variant** is a second indexing of a build the list already carries under its own id
(`<id>_unobfuscated`, from Mojang's pre-deobfuscated jar). `variantOf` names that build, and is null
on a version in its own right. Variants are left out of the listing, the `/history` walk and the
default-version choice, because one build listed twice reads as two versions.
`includeVariants=true` brings them back on `/versions`, `/history` and `/references`. A variant
answers by name either way.

### Search

| Endpoint             | Description                                                            |
|----------------------|------------------------------------------------------------------------|
| `GET /api/v1/search` | FTS search over classes, methods and fields in one version             |

Parameters: `q` (required, and it accepts `Owner#member`, `Owner.member` or `Owner/member`),
`version` (default: the latest release), `type` (`class`/`method`/`field`/`all`), `namespace`
(`yarn`/`mojmap`/`intermediary`/`all`), `limit` (1 to 200, default 50), `offset`, `exact`,
`includeSynthetic` (default `false`).

A member row carries the descriptor three times: `intermediaryDescriptor` as the index stores it,
plus `yarnDescriptor` and `mojmapDescriptor`, rewritten through the classes of that version. The
named two are what `/exists` matches on, so a key built out of a search row goes there unchanged. A
namespace the row has no name in gets no descriptor, and a type the version does not name stays as
it came. `/diff` reports the intermediary one alone.

`score` runs `0` to just under `1`, higher is better, and rows come back best first. The value is
derived from the bm25 rank of that one query and compares one call's results only.

`includeSynthetic` decides whether javac lambda bodies are returned; a row that is kept carries
`synthetic: true`.

### Translate

| Endpoint                                | Description                                                                                           |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------|
| `GET /api/v1/translate`                 | Translates a name between namespaces (`name`, `from`, `to`, `version?`, `type=auto`)                  |
| `GET /api/v1/translate/class/{name...}` | Shortcut for a class translation (`from`/`to` default to `yarn` to `mojmap`)                          |
| `POST /api/v1/translate/{version}`      | Batch key translation; body `{from, to, keys[]}`; response `{results:[{key, translated, type, intermediary}]}` |

The batch form takes the same keys as `/exists` (a class internal name, or `owner:name:descriptor`)
spelled in `from`, up to 2000 per request. `translated` is the whole key in `to`, descriptor
included, so a result posts to `/exists/{version}` unchanged.

Descriptors are translated type by type through the class table, with the official descriptor as
the pivot. A type the version does not know (a JDK class) is left as it is. A key with no descriptor
resolves only when the name has a single match: an overloaded name gives `translated: null`.

### Diff

| Endpoint                 | Description                                                                                                      |
|--------------------------|------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/diff`       | Symbol diff (added, removed, renamed classes/methods/fields) plus a summary                                      |
| `GET /api/v1/diff/files` | Changed files (`format=json`), or a raw patch (`format=patch`/`git`)                                             |
| `GET /api/v1/diff/patch` | Unified or git patch of the source (`path`/`file`/`function` filters, `context`, `limit`), or JSON with metadata |

Parameters: `from`, `to` (required); `namespace` (for `/diff`: `yarn`/`mojmap`/`intermediary`; for
`/diff/files` and `/diff/patch`: `yarn`/`mojmap`); `type`, `package`, `class`, `changeType`,
`limit`.

- `package` is a package-path prefix read in `namespace`. `class` targets exactly one class by
  internal name and lists its added, removed and renamed members with `owner` and JVM `descriptor`.
  `class` wins when both are given.
- `/diff/files?format=patch` and `/diff/patch` take `ignoreWhitespace` (default `false`), which
  collapses hunks that differ only in decompiler cosmetics. Patches are minimal by construction
  (Myers O(ND) diff).

### Version history

| Endpoint                      | Description                                                                          |
|-------------------------------|--------------------------------------------------------------------------------------|
| `GET /api/v1/history?q=<key>` | One class or member across every indexed version, as ranges (`spans`) sharing an answer |

Parameters: `q` (required, repeatable up to 50: a class internal name or `owner:name`), `namespace`
(default `mojmap`), `from`/`to` (either bound may be the older one; an unknown version returns
`404`), `releasesOnly`, `includeVariants`. A third `:descriptor` segment on `q` is accepted for
compatibility, but does not filter. A bound may name a version the walk then skips.

The response is `{namespace, results[]}`, one entry per `q` in request order, with `query` repeating
the input string exactly. `type` is `class`, `method`, `field` or `unknown`. Each `span` is a run of
consecutive versions with the same answer: `from`, `to`, `versions`, `present`. A class entry
carries `intermediary`/`yarn`/`mojmap`; a member entry carries `owner` and `members[]`, one per
overload.

- A class is tracked by the intermediary name of its newest match, so a rename or a package move
  stays one history. Two spellings of one member therefore return the same spans under two different
  `query` values, and the name each version uses is in that span's `members[]`.
- Unobfuscated releases (after 1.21.11) ship no mappings of their own. With
  `sources.unobfuscated-intermediary-mappings` set they carry intermediary like any other version;
  check `hasIntermediary` on `/versions`. Without it, a name from such a version is also looked up
  by simple name in the nearest earlier version that carries intermediary, so a class renamed rather
  than moved after 1.21.11 gets no earlier history. Two names alive in the same version are never
  linked.
- Named descriptors are not indexed, so a signature change is visible only as a changed
  `members[].intermediaryDescriptor`, and only on versions that carry intermediary.
- `present: false` with a non-null `owner` means the owner declares no member of that name;
  `owner: null` means the class itself is gone.
- `present: false` with `reason: "inherited"` means a supertype declares it, so the call still
  resolves. `declaredIn` names that supertype and `members[]` describes its declaration.
- `type: "unknown"` carries spans as well: nothing in the index names this member, and the spans
  say over which versions.
- `/history` reads the mapping index and `/exists` reads the jar the mod runs against. When the two
  disagree, the jar is right.

### Method body hash

| Endpoint                       | Description                                                                     |
|--------------------------------|---------------------------------------------------------------------------------|
| `GET /api/v1/bodyhash?q=<key>` | One method's body hashed for each version of a range, equal neighbours collapsed |

Parameters: `q` (required, repeatable up to 50: `owner:name` or `owner:name:descriptor`),
`namespace` (`yarn`/`mojmap`, default `mojmap`), `from` and `to` (both required), `releasesOnly`,
`includeVariants`, `normalize` (`named` or `intermediary`). The range is capped at 60 versions,
counted after the filters.

The answer to "did the behavior change between A and B", which `/diff/patch` cannot give, because
it works on decompiled source and reports the decompiler's cosmetics as a change. The response is
`{namespace, normalize, results[]}`, each entry `{query, spans[]}`, each span
`{from, to, versions, hash}`. `hash` is null where the version has no such method. Without a
descriptor every overload of the name hashes together, sorted.

The hash covers the instructions, the labels they jump to, the try/catch table and the frame sizes.
`SKIP_DEBUG` drops line numbers and local variable names, so a recompile alone does not move it.
`normalize=intermediary` renames the class types first, which is what makes a hash survive a class
rename or a package move; member names stay as the namespace spells them.

Two ceilings. A lambda body is a method of its own and is not followed, so a change confined to a
lambda does not move the enclosing hash. And an unobfuscated version has no intermediary names, so
the obfuscation boundary breaks the span under either mode.

### Mixin target matrix

| Endpoint                | Description                                                     |
|-------------------------|-----------------------------------------------------------------|
| `POST /api/v1/validate` | A set of mixin targets checked against every version of a range |

Query parameters: `from` and `to` (both required), `releasesOnly`, `includeVariants`. The range is
capped at 60 versions, counted after the filters. The body is
`{namespace, targets: [{id, owner, method, descriptor?, at?}]}`, at most 50 targets, where `at` is
`{value, target}` with `value` either `INVOKE` or `FIELD` and `target` a full
`owner:name:descriptor`. Omitting `descriptor` follows every overload; omitting `at` checks the
signature alone.

The answer is `{namespace, results[]}`, each entry `{id, spans[]}`, each span
`{from, to, versions, status, atCount, closest, movedTo}`. Five statuses:

- `ok`: the method is there, and so is the instruction `at` names. `atCount` says how many times,
  which is what `@At(ordinal = N)` numbers `0 .. atCount-1`.
- `renamed`: the name is there under another descriptor, carried in `closest`.
- `inherited`: a supertype declares it. The call resolves, but a mixin has to name the supertype,
  which `closest` does.
- `call_moved`: the method is there and the `at` instruction is not. `movedTo` names the method that
  holds the call now.
- `missing`: neither the method nor a near declaration. `movedTo` is still filled when exactly one
  other method of the class holds the call.

`call_moved` is the reason the endpoint exists: a call that moves out of the hooked method leaves
every signature intact, so `/exists` reports nothing while the injection point breaks in silence.

Two ceilings. A call that moved into a lambda reads as `call_moved` with `movedTo` naming
`lambda$stopSleeping$9` literally, because that is what a mixin has to target. And an `@At` value
that does not name one instruction (`HEAD`, `RETURN`, `CONSTANT`) is rejected rather than guessed at.

### Compare

| Endpoint                                       | Description                                                     |
|------------------------------------------------|-----------------------------------------------------------------|
| `GET /api/v1/compare/{version}/{className...}` | Member-correspondence table between Yarn and Mojmap, for one class |

Parameters: `from` (default `yarn`: picks the column for the class lookup and the availability
check), `to` (default `mojmap`, validated but never checked for availability and without effect on
the result; alignment is always Yarn to Mojmap).

Response: the obf-keyed member table, including members present on only one side. No source is read.
Per-member `status`: `matched`, `yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`,
`initializer` or `unmapped`. Class-level `presence`: `both`, `yarn_only` or `mojmap_only`. `422`
when the requested `from` namespace is unavailable for that version.

### Bytecode and source

| Endpoint                                        | Description                                                                                                    |
|-------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/bytecode/{version}/{className...}` | Disassembled bytecode (ASM Textifier); `namespace`, `format=text/json`                                          |
| `GET /api/v1/source/{version}/{className...}`   | Decompiled `.java`; `namespace=yarn/mojmap`, `format=text/json`                                                 |
| `GET /api/v1/tokens/{version}/{className...}`   | `{source, tokens}`: each identifier resolved to owner, name and descriptor; `namespace`, `format=text/json`     |

`source`: when no class matches the name exactly, the endpoint falls back to the single class of
that version with the same simple name, which resolves both a short name (`ZombifiedPiglin`) and a
class that moved package. The response `class` field names the class actually served, and an
ambiguous or unknown name gives a `404` listing the candidates. `format=text` on `tokens` returns
TSV with a `#`-prefixed header line, one token per line.

### Blame

| Endpoint                                     | Description                                                       |
|----------------------------------------------|-------------------------------------------------------------------|
| `GET /api/v1/blame/{version}/{className...}` | The version that last changed each line of the class source        |

`lines` holds one entry per line, line 1 first, and each entry indexes `versions`. `className`
resolves as it does for `/source`. One `git blame` answers the whole file, so use this instead of
walking `/diff/patch`. A version indexed from the artifact store alone has no source repository and
returns `404`.

`versions` never names an `_unobfuscated` variant: each line is attributed to the version the
variant was built from.

### Hierarchy and references

| Endpoint                                         | Description                                                                                                                    |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/hierarchy/{version}/{className...}` | Supertypes and subtypes of a class (nodes/edges); `namespace=yarn/mojmap`                                                       |
| `GET /api/v1/references/{version}?q=<key>`       | Uses of classes or members (`q` is `owner` or `owner:name:descriptor`, repeatable); `namespace`, `releasesOnly`, `to`, `depth`   |
| `POST /api/v1/references/{version}`              | The same call with the targets in a body, up to 2000; `QUERY` is accepted here too                                              |
| `GET /api/v1/diff/references?from=&to=&q=`       | How the sites using a class or member changed between two versions; `{changes: {added, removed, moved}}`                        |
| `POST /api/v1/exists/{version}`                  | Batch existence check; body `{namespace, members[]}`; response `{results:[{key, exists, closest, reason, candidates}]}`          |
| `POST /api/v1/validate?from=&to=`                | The same check over a range, plus the injection point (see Mixin target matrix)                                                 |

Each `references` group carries `resolved`. False means the group is not an answer about the target:
the key carries no descriptor and can never match the `name:descriptor` the index is keyed by, or
its descriptor names no member the index knows, or the index holds no row for the owner.
`candidates` then lists the keys the query would have matched. All three otherwise look like
"nothing calls this". A key may resolve in one version of a range and not in the next, which is why
the flag sits on the group.

`references` takes up to 25 `q` values, and `to` extends the walk to a second version. The range is
not capped; what is capped, at 25, is how many of its versions the prebuilt reference index does not
cover, because each of those costs a jar scan of 0.4 to 0.8s and ~140 MB. The index covers releases,
so `releasesOnly=true` walks 1.14 to 26.2 in one call, 47 releases in 48ms; 43 answer, because
Mojang's official mappings start at 1.14.4. A request line above 4096 bytes is rejected by the HTTP
parser, which is where the limit of 25 comes from; the `POST` and `QUERY` forms carry up to 2000
targets in a body and are never cached. The response is `{namespace, results[]}`, one entry per
(version, target) as `{version, query, references[]}`. Eight per-version indexes stay in memory,
least recently used first out.

The index records the owner written in the call instruction, not the class that declares the member,
so `Level:getRespawnData` answers with nothing while `ServerLevel:getRespawnData` answers with 12
sites. No parameter walks the hierarchy: read an empty result as "no caller names this owner", then
ask `/hierarchy` and repeat per subtype.

Each site carries `count`, the number of instructions in it that hit the target, which is what
`@At(ordinal = N)` numbers `0 .. count-1`. A call written inside a lambda reports `member` as the
method the lambda is written in and `synthetic` as the javac body (`lambda$tick$3`). Calls inside
the calling class are indexed, and method references are followed through their `invokedynamic`.
`depth` above 1 adds `paths`, the chains reaching the target, outermost frame first, at most 200.

`diff/references` groups those sites by the method they sit in and reports the difference between
two versions. `moved` pairs a removed site with an added one when both reach exactly the same
members, matching by method name, then class, then a lone pair. `404` when no requested version has
a named jar in that namespace.

`exists` scans the version's named jar with ASM (cached per version and namespace), so descriptors
match exactly, without remapping. It accepts up to 2000 keys, returns `404` when the named jar is
missing, and is never cached.

A key that missed carries the nearest declaration in `closest`, in key form, with `reason`:

- `inherited`: a supertype declares this exact signature, so the call still resolves. An `@At`
  target is valid; a `@Shadow` has to name the supertype.
- `descriptor`: the owner declares this name under another descriptor **of the same kind**, so the
  signature changed.
- `kind`: the owner declares this name as a field where a method was asked for, or the other way
  round. `closest` is not a drop-in replacement for the key.

A same-kind candidate wins over one of the other kind, which is what makes `descriptor` mean what it
says. Both fields are null when the version declares nothing of that name under that owner, and when
`exists` is true.

A key given without a descriptor is a resolve rather than an existence check: instead of a bare
`false` it answers with `candidates`, every declaration under that name in key form, with no ranking
and no fuzzy matching. `closest` and `reason` stay null.

### Resources

| Endpoint                         | Description                                                                          |
|----------------------------------|--------------------------------------------------------------------------------------|
| `GET /api/v1/resources/versions` | The versions the resource index carries, newest first, with the branches of each     |
| `GET /api/v1/resources/tree`     | One directory level of one version                                                   |
| `GET /api/v1/resources/file`     | The bytes of one file, with a `Content-Type` from the extension and the blob id as ETag |
| `GET /api/v1/resources/diff`     | Every path that differs between two versions, with the blob id on each side          |
| `GET /api/v1/resources/history`  | The version ranges over which one path held each of its contents                     |
| `GET /api/v1/resources/search`   | Full-text search over the contents (`type=content`) or the translations (`type=translation`) |

Parameters: `branch` (`assets`, `diff`, `registries` or `atlas`, default `assets`), `version`,
`from`/`to`, `path`, and `q`/`type`/`version`/`limit` on search. A version may be named by its
mcmeta id (`26.3-snapshot-9`), by its mcmeta display name (`26.3 Snapshot 9`) or by its MappingLens
version id.

The server computes no textual diff. `/file` returns raw bytes, so a client puts a PNG in an `img`
element and an OGG file in an `audio` element, and reads a text file at both versions to feed its
own diff view.

A content hit reports the blob that matched, so content shared by several paths comes back once per
path. A translation hit reports the translation key, the language file and the version range instead
of the file alone. Each term of `q` is matched as a phrase, so `minecraft:copper_golem` is a query
and not an FTS5 column filter.

When no clone is configured, or its index was never built, every endpoint answers
`404 resources_disabled`.

---

## General contract rules

- **Namespaces:** `yarn`, `mojmap`, `intermediary`, `obfuscated` (alias `obf`). See the tables above
  for which endpoints accept which.
- **Default version** (search, translate): the latest `release` by semver order.
- **Errors:** one `ApiError { error, message, status }` shape, where `status` repeats the HTTP code:
  `400 invalid_query`, `404 not_found`, `422 namespace_unavailable`.
- **Response formats:** JSON by default; `bytecode`/`source`/`tokens?format=text` return
  `text/plain`; a diff patch (`format=patch`/`git`) returns `text/x-diff`.
- **`hasIntermediary`** means the version carries intermediary names, not that a standalone
  intermediary file exists. Yarn's tiny v2 format is `official->intermediary->named`, so every yarn
  version carries intermediary. An unobfuscated release that yarn does not cover carries it only
  when `sources.unobfuscated-intermediary-mappings` is set.
- **`hasMojmap`** is also true on unobfuscated releases: the jar already carries Mojang's names, so
  `obfuscated` and `mojmap` return the same name there.
- **Rate limit:** 200 requests per 60 seconds on `/api/v1`; meta endpoints are not limited.

### Behavioral notes (for agents)

- `search.totalResults` is the size of the current page, not the total match count.
- `diff*` on an unknown version returns `200` with an empty result, not `404`.
- `translate` and `compare` on an unknown version return `404`, not `422`.
- `compare`: `to` is validated but does not affect the result.
- `/diff/files`: `context`, `limit` and `function` are read only when `format=patch/git`.

---

## What the index stores

The SQLite index holds `versions` (metadata, semver order, counts) and unified obf-keyed rows in
`classes`/`methods`/`fields` (with `presence` in `{both, yarn_only, mojmap_only}`). It stores no
decompiled source, bytecode or git blobs; those are read on demand from the read-only store.

Three files sit beside it:

- **`mappinglens-search.db`** holds one contentless FTS5 table for each version, named
  `search_v<version row id>`. FTS5 answers a prefix term by merging the doclists of every term with
  that prefix over the whole table before any rowid filter narrows it, so one table over all
  versions read 5.5M postings of `get*` to rank the 13697 of the version asked for. One table per
  version answers in 24ms instead of 959ms and costs 2% more bytes. The tables are contentless
  because the server reads no name back out of them: the row identity rides in the rowid and the
  element kind in its low two bits. 52.3M rows make 5.3 GB, where the single table it replaces held
  14.9 GB. The file is separate because 526 virtual tables add about 2600 rows to `sqlite_master`,
  and the server opens a fresh connection per request.
- **`mappinglens-refs.db`** holds one row for each class of each (version, namespace): who
  references each of its members, deflated. Without it the server scans a version's whole named jar
  on the first question about that version, at 0.4 to 0.8s and ~140 MB. A row costs 0.7ms cold and
  0.1ms warm. One row per class rather than per member, because the class is the unit readers ask
  for and because it deflates 6.8 times where member-sized blobs reach 2.7. `index` builds it for
  the releases (90 pairs, 1.0 GB, 57s); `-refs=all` covers every version (10.3 GB, 11 minutes) and
  `-refs=none` skips the step. The file is optional and may be partial: an uncovered version is
  answered by the scan.
- **`mappinglens-resources.db`**, built by `index-resources`, holds no file content either. Its core
  table is `runs`: one row covers `(path, blob, from_ord, to_ord)`, and the same 199k rows answer
  the tree, the diff, the history and the versions of a search hit. Beside it sit a contentless FTS5
  index over the 85k text blobs and 1.87M rows for the runs of a translation key. The whole file is
  313 MiB over 450 versions of four branches, and one build takes under two minutes. The bytes come
  from the clone, through `git cat-file`.

### Members of an unobfuscated release

Intermediary names an overriding method only in the class that first declares it, and the name
propagates down the hierarchy. An obfuscated release hides that, because its mojmap tiny lists every
declared member. An unobfuscated release has no mojmap tiny, so a mapping-only parse gives it no
overrides at all.

The indexer therefore reads the version's own jar for these releases and adds the members the
mappings leave out. Members the mappings know keep their yarn and intermediary names; the ones only
the jar knows enter mojmap-only. The classes come from the mappings either way.

Scale of the gap before the fix: `1.21.11` held 89,606 methods against 56,986 for
`1.21.11_unobfuscated`, and a `/diff` between the two reported 32,355 methods removed with nothing
having changed. Fields were within 1%, because a field is never overridden. An index built before
this fix carries the gap until the affected versions are re-indexed.

---

## Future plan

1. **Frontend: full explorer.** A drill-down view over `/compare`, optional `translate`, `diff` and
   `bytecode` integration, and possibly serving `dist/` from Ktor itself.
2. **Search quality.** Better ranking: mapped over intermediary over obf, with a class/owner bonus.
3. **Commit-accurate diff and history** through JGit, on top of the current jar-based hot path.
4. **Phase 5 (low priority).** In-browser deobfuscation and decompilation, for versions without a
   ready jar.
