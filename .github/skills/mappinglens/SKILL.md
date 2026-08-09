---
name: mappinglens
description: 'Use when: working with MappingLens, Minecraft mappings, Yarn, Mojmap, Intermediary, obfuscated names, mapping search, namespace translation, cross-version mapping diffs, source lookup, or bytecode inspection via the MappingLens REST API at 127.0.0.1:8080.'
argument-hint: 'mapping task, name, namespace, version, or version diff'
---

# MappingLens

Use this skill to query MappingLens, a read-only REST API for Minecraft mappings across Yarn, Mojmap, Intermediary, and obfuscated namespaces.

Base URL: `127.0.0.1:8080`

For the full OpenAPI contract in this repository, see `../../../src/main/resources/openapi/mappinglens-api.yaml`.

## When to Use

- Translate class, method, or field names between `yarn`, `mojmap`, `intermediary`, and `obfuscated` namespaces.
- Search for Minecraft classes, methods, and fields by mapped names, intermediary IDs, descriptors, or owner/member expressions.
- Compare mapping changes between Minecraft versions.
- Track one class or member across every indexed version at once (when it appeared, when it was renamed or moved, when it disappeared) instead of querying version by version.
- Align a single class across Yarn and Mojmap with a per-member correspondence table.
- Fetch git-like/unified source patches between Minecraft versions.
- Fetch indexed decompiled source or ASM textified bytecode for a class.
- Inspect a class's inheritance hierarchy (supertypes/subtypes), find all references to a class or member, or resolve source identifiers to owner/name/descriptor tokens.
- Batch-validate that classes/members still exist with the same signature in a target version (e.g. checking mixin/shadow targets before updating a mod).
- Validate which Minecraft versions are indexed before answering mapping questions.

Do not use MappingLens as an authority for general Minecraft gameplay facts; it is focused on names, mappings, source paths, diffs, and bytecode/source lookup.

## General Workflow

1. If the user did not specify a version and exact version matters, call `GET 127.0.0.1:8080/api/v1/versions` and choose the latest indexed release, or ask for the version if ambiguity affects the answer.
2. Prefer `GET 127.0.0.1:8080/api/v1/search` when the namespace, type, owner class, or exact name is uncertain.
3. Prefer `GET 127.0.0.1:8080/api/v1/translate` when the input namespace and target namespace are known.
4. For version-to-version changes, use `GET 127.0.0.1:8080/api/v1/diff` for mapping elements, `GET 127.0.0.1:8080/api/v1/diff/files` for source file movement/modification, and `GET 127.0.0.1:8080/api/v1/diff/patch` when the user asks for a real git/patch-style source diff.
5. To line up one class's members across Yarn and Mojmap (without reading source), use `GET 127.0.0.1:8080/api/v1/compare/{version}/{className}`.
6. For implementation details, use `GET 127.0.0.1:8080/api/v1/source/{version}/{className}` first; use bytecode only when source is missing or bytecode-level details are required.
7. URL-encode query parameters and slash-containing path values. If an endpoint supports catch-all class path segments, keep JVM-style slash-separated class names unless the calling tool requires escaping.
8. Treat all endpoints as read-only. Do not assume MappingLens has indexed every Minecraft version or namespace; handle `404` and empty results explicitly.

## Endpoint Quick Reference

### Versions

- `GET 127.0.0.1:8080/api/v1/versions`
  - Lists indexed versions with counts and namespace availability.
  - `hasIntermediary` means the version carries intermediary names, not that a separate intermediary file was downloaded. Every yarn version has them, because yarn's merged tiny v2 is `official->intermediary->named`. Mojang's unobfuscated releases have no yarn, so they carry intermediary only when the indexer was given a separate source for them (`mappinglens.sources.unobfuscated-intermediary-mappings`).
- `GET 127.0.0.1:8080/api/v1/versions/{version}`
  - Gets metadata for one indexed version.
- `GET 127.0.0.1:8080/api/v1/classes/{version}`
  - Lists all indexed classes for a version with obf/intermediary/yarn/mojmap aliases and `presence` flag (`both`, `yarn_only`, `mojmap_only`). Intended for building package/class trees client-side. Returns `404` if version not found.

### Search

- `GET 127.0.0.1:8080/api/v1/search?q={query}&version={version}&type={type}&namespace={namespace}&limit={limit}&offset={offset}&exact={bool}`
- `q` is required.
- `type`: `class`, `method`, `field`, or `all`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, or `all`.
- Use owner/member forms such as `Block#getDefaultState` or class simple names such as `BlockState`.
- Use `exact=true` only when exact-name lookup is desired; otherwise fuzzy/prefix search is usually better.
- `totalResults` in the response is the count of results in the current page, not the total number of matches in the index.

### Translate

- `GET 127.0.0.1:8080/api/v1/translate?name={name}&from={from}&to={to}&version={version}&type={type}`
- `from` / `to`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- `type`: `class`, `method`, `field`, or `auto`.
- Use this when the input name and namespace are already known.
- `GET 127.0.0.1:8080/api/v1/translate/class/{name}?from={from}&to={to}&version={version}` is a class-specific shortcut.

### Diff

- `GET 127.0.0.1:8080/api/v1/diff?from={fromVersion}&to={toVersion}&namespace={namespace}&type={type}&package={packagePrefix}&class={classInternalName}&changeType={changeType}&limit={limit}`
- `namespace`: `yarn`, `mojmap`, or `intermediary`. Defaults to `mojmap`.
- `changeType`: `added`, `removed`, `renamed`, or `all`.
- `package` vs `class` (mutually exclusive; `class` wins if both are given):
  - `package` is a **package-path prefix** over the whole diff, e.g. `net/minecraft/block` matches every class under that package.
  - `class` targets **exactly one class** by its internal name in `namespace`, e.g. `net/minecraft/world/entity/Entity`. It lists added/removed/renamed members by name, each with `owner`, `name`, and JVM `descriptor`. Its `summary.methodsAdded`/`fieldsAdded` (etc.) match `/diff/files` for the same class bit-for-bit — use it to answer "which methods/fields were added to this class, by name?".
  - Note: a string like `net/minecraft/world/entity/Entity` is a class, not a package, so pass it as `class=`; using it as `package=` matches nothing (no class lives *under* a package named `Entity`).

### File Diff

- `GET 127.0.0.1:8080/api/v1/diff/files?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- `file`: exact or prefix source path filter; takes precedence over `path`.
- `function`, `context`, `limit`: only used when `format=patch`/`git` (same semantics as the Patch Diff endpoint).
- `format`: `json` (default), `patch`, or `git`.
- Use `json` when the question is about changed source files rather than renamed mapping entries. Use `patch`/`git` when raw unified diff text is requested.

### Patch Diff

- `GET 127.0.0.1:8080/api/v1/diff/patch?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- `path` / `file`: optional folder or source file prefix such as `net/minecraft/block` or `net/minecraft/block/Block.java`.
- `function`: optional method/function filter such as `getDefaultState` or `Block#getDefaultState`.
- `context`: hunk context lines, 0-20; default 3.
- `ignoreWhitespace` (default `false`): collapse hunks that differ only in whitespace, line breaks, or reindentation (decompiler cosmetics), leaving only real changes. Also available on `/diff/files?format=patch`.
- `format`: `patch`/`git` returns `text/x-diff`; `json` returns metadata plus the patch string.
- Use this endpoint for full real source diffs between versions; it compares actual decompiled source content, not only renamed mapping entries.
- Patches are minimal by construction (Myers diff): a class with a handful of real changes yields a handful of hunks, not a whole-file rewrite. `ignoreWhitespace=true` additionally erases pure reformatting — use it when a decompiler reindent would otherwise add noise; a class with no semantic change then yields an empty patch.

### History (one key across all versions)

- `GET 127.0.0.1:8080/api/v1/history?q={key}&namespace={namespace}&from={version}&to={version}`
- `q` (required, repeatable): a class internal name (dot-separated FQN also accepted) or a member key `owner:name`. A third `:descriptor` segment is accepted so `/references` and `/exists` keys paste in unchanged, but it does not filter — overloads come back together.
- `namespace`: `yarn`, `mojmap`, or `intermediary`. Defaults to `mojmap`.
- `from` / `to`: limit the version walk; either bound may be the older one. An unindexed version id returns `404`.
- Returns `{namespace, results[]}` — one entry per `q`, in request order, each `{query, type, spans[]}`. `type` is `class`, `method`, `field`, or `unknown` when nothing matched.
- Each span is a run of consecutive versions with the same answer: `{from, to, versions, present}` plus `intermediary`/`yarn`/`mojmap` for a class, or `owner` and `members[]` (one per overload) for a member. `from` is the older bound.
- **Use it instead of looping `/translate` or `/exists` over versions**: a few keys against 500 versions is one request. Repeat `q` to batch several keys.
- The class is followed by its intermediary name, not by the string queried, so a rename or a package move stays one history and the name from any version returns the same answer.
- Mojang's unobfuscated releases (everything after 1.21.11) ship no mappings of their own. When the indexer has the separate intermediary source for them, they carry intermediary like any other version and nothing below applies. Check `hasIntermediary` on `/api/v1/versions` to see which case the index is in.
- Without that source, a name taken from one of those versions is also looked up in the newest mapped version before it, by simple name, which a package move preserves — that is what recovers the rest of the history. Two names alive in the same version are never linked (a class has one name per version), so a class *renamed* after 1.21.11 then keeps only its post-1.21.11 history.
- Named descriptors are not indexed, so a signature change shows up as a changed `members[].intermediaryDescriptor`, and only on versions that have intermediary.
- `present: false` with a non-null `owner` means the class is still there and the member is gone; `owner: null` means the class itself is gone.

### Compare

- `GET 127.0.0.1:8080/api/v1/compare/{version}/{className}?from={from}&to={to}`
- `from`: namespace the class name is given in; defaults to `yarn`.
- `to`: namespace to compare against; defaults to `mojmap`.
- Returns the obf-keyed member table aligning the class across both namespaces, including members present in only one side. No source is read; it is a projection of the indexed mappings.
- Per-member `status`: `matched`, `yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`, or `initializer`. Class-level `presence`: `both`, `yarn_only`, or `mojmap_only`.
- `422` is returned when the requested namespace is unavailable for that version.

### Source

- `GET 127.0.0.1:8080/api/v1/source/{version}/{className}?namespace={namespace}&format={format}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`, falling back to `yarn` when the version has no mojmap source. An explicit `namespace` is used as-is (no fallback).
- `format`: `json` (default) or `text`. Use `text` to get the `.java` as `text/plain`, with no JSON envelope to unpack.
- Returns decompiled source text and the indexed source path.
- `className` accepts a slash-separated internal name (`net/minecraft/block/Block`) or a dot-separated FQN (`net.minecraft.block.Block`).
- A name that matches no class exactly falls back to the one class of that version with the same simple name. A bare simple name (`ZombifiedPiglin`) and a class that moved package between versions both resolve this way, so do not loop over candidate packages. The response `class` field names the class actually served.
- When the simple name is ambiguous or unknown the call is a `404`, and its `message` lists the candidate names.

### Blame

- `GET 127.0.0.1:8080/api/v1/blame/{version}/{className}?namespace={namespace}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- Returns the version that last changed each line of the class source. `lines` holds one entry per line, line 1 first, and each entry indexes `versions`.
- `className` resolves as it does for `/source`.
- One `git blame` over the source repository answers the whole file, so use this instead of walking `/diff/patch` version by version.
- A version indexed from the artifact store alone has no source repository and gives a `404`.

### Bytecode

- `GET 127.0.0.1:8080/api/v1/bytecode/{version}/{className}?format={format}&namespace={namespace}`
- `format`: `text` or `json`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`. Defaults to `mojmap`.
- `className` accepts a slash-separated internal name or a dot-separated FQN (`net.minecraft.block.Block`).
- Use this for descriptor-level or instruction-level inspection.

### Source Tokens

- `GET 127.0.0.1:8080/api/v1/tokens/{version}/{className}?namespace={namespace}&format={format}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- `format`: `json` (default) or `text`. `text` returns one token per line as TSV in the column order below, with a `#`-prefixed header line and empty fields for `null`.
- Returns `{source, tokens}`, where each token resolves one class/method/field identifier in the decompiled `.java` to `{startLine, startColumn, endLine, endColumn, type, className, name, descriptor, declaration}` (Monaco 1-based range, `endColumn` exclusive).
- Use to map a cursor position to an exact symbol (owner/name/descriptor). Identifiers the symbol solver cannot resolve are omitted; a partial file still returns its resolvable tokens.

### Inheritance

- `GET 127.0.0.1:8080/api/v1/hierarchy/{version}/{className}?namespace={namespace}`
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- Returns the class's supertypes and subtypes as `{nodes, edges}` (node: internal `name`, `simpleName`, `isInterface`, `isAbstract`; edge: `{parent, child}`). `java/lang/Object` is omitted. `404` if the class or the version's named jar is absent.

### References

- `GET 127.0.0.1:8080/api/v1/references/{version}?q={key}&namespace={namespace}`
- `q` (required): the target — a class internal name (`net/minecraft/world/level/block/Block`) or a member key `owner:name:descriptor`.
- `namespace`: `yarn` or `mojmap`. Defaults to `mojmap`.
- Returns each referencing site as `{owner, ownerSimple, member, descriptor, kind}` (the enclosing method, or the class header). Only references to Minecraft classes in the same jar are indexed; JDK/library targets are dropped.

### Exists (batch member/class existence)

- `POST 127.0.0.1:8080/api/v1/exists/{version}` with JSON body `{ "namespace": "mojmap", "members": ["net/minecraft/.../ChunkMap:move:(...)V", "net/minecraft/.../ChunkMap"] }`.
- `namespace`: `yarn` or `mojmap`. Each key is either a class internal name (`owner`) or a member `owner:name:descriptor`; descriptors are in the requested namespace.
- Returns `{version, namespace, results:[{key, exists, renamedTo}]}` — one boolean per key, in request order. `renamedTo` is reserved (currently always `null`; detecting a rename needs a source version to anchor against, which this single-version check does not take).
- Batch up to 2000 keys per call. Checks the version's pre-remapped named jar via ASM (cached per version+namespace), so descriptors match exactly with no remapping. Returns `404` if that jar is absent for the version.
- **Use it to validate mixin/shadow targets when updating a mod**: confirm every injected method and shadowed field still exists with the same signature in one request instead of many `search`/`source` calls. This is the only `POST` endpoint; it is not cached (results depend on the request body).

### Meta / Health

- `GET 127.0.0.1:8080/health` — returns `ok`; not rate-limited.
- `GET 127.0.0.1:8080/openapi.yaml`
- `GET 127.0.0.1:8080/openapi.json`
- `GET 127.0.0.1:8080/docs` — Swagger UI.

All `/api/v1` endpoints are rate-limited to 200 requests per 60 seconds. Exceeding this returns `429`.

Successful `/api/v1` responses are immutable for a given version and are served with `Cache-Control: public, max-age=2592000, immutable` (one month); error responses are not cached. Reuse cached results across a session rather than refetching.

## Suggested MCP Tool Contract

If wrapping MappingLens as an MCP server, expose these read-only tools and map them directly to the REST endpoints above:

| Tool | Purpose | Required Inputs | Optional Inputs |
|------|---------|-----------------|-----------------|
| `mappinglens_list_versions` | List indexed Minecraft versions | none | none |
| `mappinglens_get_version` | Get one version's metadata | `version` | none |
| `mappinglens_list_classes` | List all classes for a version | `version` | none |
| `mappinglens_search` | Search classes, methods, fields | `q` | `version`, `type`, `namespace`, `limit`, `offset`, `exact` |
| `mappinglens_translate` | Translate a name between namespaces | `name`, `from`, `to` | `version`, `type` |
| `mappinglens_diff` | Compare mapping elements between versions | `from`, `to` | `namespace`, `type`, `package`, `class`, `changeType`, `limit` |
| `mappinglens_diff_files` | Compare indexed source files | `from`, `to` | `namespace`, `path`, `file`, `format`, `ignoreWhitespace` |
| `mappinglens_diff_patch` | Return real unified source patch between versions | `from`, `to` | `namespace`, `path`, `file`, `function`, `context`, `limit`, `format`, `ignoreWhitespace` |
| `mappinglens_compare` | Yarn↔Mojmap per-member correspondence table for a class | `version`, `className` | `from`, `to` |
| `mappinglens_get_source` | Fetch decompiled source | `version`, `className` | `namespace` |
| `mappinglens_get_tokens` | Resolve source identifiers to owner/name/descriptor tokens | `version`, `className` | `namespace` |
| `mappinglens_get_bytecode` | Fetch bytecode/disassembly | `version`, `className` | `namespace`, `format` |
| `mappinglens_hierarchy` | Class supertypes/subtypes graph | `version`, `className` | `namespace` |
| `mappinglens_references` | Find references to a class or member | `version`, `q` | `namespace` |
| `mappinglens_exists` | Batch-check class/member existence in a version | `version`, `members` | `namespace` |
| `mappinglens_get_openapi` | Fetch the API specification | none | `format` |

For MCP schemas, keep enum values identical to the REST API. Return the JSON body unchanged plus the request URL used when useful for debugging.

## Response Handling

- `200`: Use the returned JSON directly; summarize only the fields needed for the user's question.
- `400`: Check parameter names, enum values, and URL encoding.
- `404`: The version, class, mapping entry, source, or bytecode may not be indexed; try search first or report that MappingLens has no indexed match.
- `422` (compare): the requested `from`/`to` namespace is not available for that version; check `GET /api/v1/versions` for namespace availability.
- Empty search results: broaden `namespace=all`, `type=all`, remove `exact=true`, or search by simple name/intermediary ID.

## Answering Guidelines

- Include the version, namespace direction, and element type in final answers when relevant.
- If multiple candidates match, show the top few and explain why the chosen result is likely correct.
- For translations, report the intermediary and obfuscated names too when present; they are useful stable anchors.
- For diffs, separate `added`, `removed`, and `renamed` results, and mention the requested limit if results may be truncated.
- Do not fabricate mappings when MappingLens returns no result. Say that no indexed result was found and suggest a broader query.