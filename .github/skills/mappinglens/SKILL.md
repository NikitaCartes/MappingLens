---
name: mappinglens
description: 'Use when: working with MappingLens, Minecraft mappings, Yarn, Mojmap, Intermediary, obfuscated names, mapping search, namespace translation, cross-version mapping diffs, source lookup, or bytecode inspection via the MappingLens REST API at 127.0.0.1:8081.'
argument-hint: 'mapping task, name, namespace, version, or version diff'
---

# MappingLens

Use this skill to query MappingLens, a read-only REST API for Minecraft mappings across Yarn, Mojmap, Intermediary, and obfuscated namespaces.

Base URL: `127.0.0.1:8081`

For the full OpenAPI contract in this repository, see `../../../src/main/resources/openapi/mappinglens-api.yaml`.

## When to Use

- Translate class, method, or field names between `yarn`, `mojmap`, `intermediary`, and `obfuscated` namespaces.
- Search for Minecraft classes, methods, and fields by mapped names, intermediary IDs, descriptors, or owner/member expressions.
- Compare mapping changes between Minecraft versions.
- Align a single class across Yarn and Mojmap with a per-member correspondence table.
- Fetch git-like/unified source patches between Minecraft versions.
- Fetch indexed decompiled source or ASM textified bytecode for a class.
- Validate which Minecraft versions are indexed before answering mapping questions.

Do not use MappingLens as an authority for general Minecraft gameplay facts; it is focused on names, mappings, source paths, diffs, and bytecode/source lookup.

## General Workflow

1. If the user did not specify a version and exact version matters, call `GET 127.0.0.1:8081/api/v1/versions` and choose the latest indexed release, or ask for the version if ambiguity affects the answer.
2. Prefer `GET 127.0.0.1:8081/api/v1/search` when the namespace, type, owner class, or exact name is uncertain.
3. Prefer `GET 127.0.0.1:8081/api/v1/translate` when the input namespace and target namespace are known.
4. For version-to-version changes, use `GET 127.0.0.1:8081/api/v1/diff` for mapping elements, `GET 127.0.0.1:8081/api/v1/diff/files` for source file movement/modification, and `GET 127.0.0.1:8081/api/v1/diff/patch` when the user asks for a real git/patch-style source diff.
5. To line up one class's members across Yarn and Mojmap (without reading source), use `GET 127.0.0.1:8081/api/v1/compare/{version}/{className}`.
6. For implementation details, use `GET 127.0.0.1:8081/api/v1/source/{version}/{className}` first; use bytecode only when source is missing or bytecode-level details are required.
7. URL-encode query parameters and slash-containing path values. If an endpoint supports catch-all class path segments, keep JVM-style slash-separated class names unless the calling tool requires escaping.
8. Treat all endpoints as read-only. Do not assume MappingLens has indexed every Minecraft version or namespace; handle `404` and empty results explicitly.

## Endpoint Quick Reference

### Versions

- `GET 127.0.0.1:8081/api/v1/versions`
  - Lists indexed versions with counts and namespace availability.
- `GET 127.0.0.1:8081/api/v1/versions/{version}`
  - Gets metadata for one indexed version.

### Search

- `GET 127.0.0.1:8081/api/v1/search?q={query}&version={version}&type={type}&namespace={namespace}&limit={limit}&offset={offset}&exact={bool}`
- `q` is required.
- `type`: `class`, `method`, `field`, or `all`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, or `all`.
- Use owner/member forms such as `Block#getDefaultState` or class simple names such as `BlockState`.
- Use `exact=true` only when exact-name lookup is desired; otherwise fuzzy/prefix search is usually better.

### Translate

- `GET 127.0.0.1:8081/api/v1/translate?name={name}&from={from}&to={to}&version={version}&type={type}`
- `from` / `to`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- `type`: `class`, `method`, `field`, or `auto`.
- Use this when the input name and namespace are already known.
- `GET 127.0.0.1:8081/api/v1/translate/class/{name}?from={from}&to={to}&version={version}` is a class-specific shortcut.

### Diff

- `GET 127.0.0.1:8081/api/v1/diff?from={fromVersion}&to={toVersion}&namespace={namespace}&type={type}&package={packagePrefix}&changeType={changeType}&limit={limit}`
- `namespace`: `yarn`, `mojmap`, or `intermediary`.
- `changeType`: `added`, `removed`, `renamed`, or `all`.
- Use `package` for path/package prefix filters such as `net/minecraft/block`.

### File Diff

- `GET 127.0.0.1:8081/api/v1/diff/files?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `namespace`: `yarn` or `mojmap`.
- `file`: exact or prefix source path filter; takes precedence over `path`.
- `function`, `context`, `limit`: only used when `format=patch`/`git` (same semantics as the Patch Diff endpoint).
- `format`: `json` (default), `patch`, or `git`.
- Use `json` when the question is about changed source files rather than renamed mapping entries. Use `patch`/`git` when raw unified diff text is requested.

### Patch Diff

- `GET 127.0.0.1:8081/api/v1/diff/patch?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `namespace`: `yarn` or `mojmap`.
- `path` / `file`: optional folder or source file prefix such as `net/minecraft/block` or `net/minecraft/block/Block.java`.
- `function`: optional method/function filter such as `getDefaultState` or `Block#getDefaultState`.
- `context`: hunk context lines, 0-20; default 3.
- `format`: `patch`/`git` returns `text/x-diff`; `json` returns metadata plus the patch string.
- Use this endpoint for full real source diffs between versions; it compares actual decompiled source content, not only renamed mapping entries.

### Compare

- `GET 127.0.0.1:8081/api/v1/compare/{version}/{className}?from={from}&to={to}`
- `from`: namespace the class name is given in; defaults to `yarn`.
- `to`: namespace to compare against; defaults to `mojmap`.
- Returns the obf-keyed member table aligning the class across both namespaces, including members present in only one side. No source is read; it is a projection of the indexed mappings.
- Per-member `status`: `matched`, `yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`, `initializer`, or `unmapped`. Class-level `presence`: `both`, `yarn_only`, or `mojmap_only`.
- `422` is returned when the requested namespace is unavailable for that version.

### Source

- `GET 127.0.0.1:8081/api/v1/source/{version}/{className}?namespace={namespace}`
- `namespace`: `yarn` or `mojmap`.
- Returns decompiled source text and the indexed source path.
- Use class names such as `net/minecraft/block/Block` or the namespace-specific class name known to MappingLens.

### Bytecode

- `GET 127.0.0.1:8081/api/v1/bytecode/{version}/{className}?format={format}&namespace={namespace}`
- `format`: `text` or `json`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- Use this for descriptor-level or instruction-level inspection.

### API Docs

- `GET 127.0.0.1:8081/openapi.yaml`
- `GET 127.0.0.1:8081/openapi.json`
- `GET 127.0.0.1:8081/docs`

## Suggested MCP Tool Contract

If wrapping MappingLens as an MCP server, expose these read-only tools and map them directly to the REST endpoints above:

| Tool | Purpose | Required Inputs | Optional Inputs |
|------|---------|-----------------|-----------------|
| `mappinglens_list_versions` | List indexed Minecraft versions | none | none |
| `mappinglens_get_version` | Get one version's metadata | `version` | none |
| `mappinglens_search` | Search classes, methods, fields | `q` | `version`, `type`, `namespace`, `limit`, `offset`, `exact` |
| `mappinglens_translate` | Translate a name between namespaces | `name`, `from`, `to` | `version`, `type` |
| `mappinglens_diff` | Compare mapping elements between versions | `from`, `to` | `namespace`, `type`, `package`, `changeType`, `limit` |
| `mappinglens_diff_files` | Compare indexed source files | `from`, `to` | `namespace`, `path`, `format` |
| `mappinglens_diff_patch` | Return real unified source patch between versions | `from`, `to` | `namespace`, `path`, `file`, `function`, `context`, `limit`, `format` |
| `mappinglens_compare` | Yarn↔Mojmap per-member correspondence table for a class | `version`, `className` | `from`, `to` |
| `mappinglens_get_source` | Fetch decompiled source | `version`, `className` | `namespace` |
| `mappinglens_get_bytecode` | Fetch bytecode/disassembly | `version`, `className` | `namespace`, `format` |
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