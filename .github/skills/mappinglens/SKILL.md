---
name: mappinglens
description: 'Use when: working with MappingLens, Minecraft mappings, Yarn, Mojmap, Intermediary, obfuscated names, mapping search, namespace translation, cross-version mapping diffs, source lookup, or bytecode inspection via the MappingLens REST API at <url>.'
argument-hint: 'mapping task, name, namespace, version, or version diff'
---

# MappingLens

Use this skill to query MappingLens, a read-only REST API for Minecraft mappings across Yarn, Mojmap, Intermediary, and obfuscated namespaces.

Base URL: `<url>`

For the full OpenAPI contract in this repository, see `../../../src/main/resources/openapi/mappinglens-api.yaml`.

## When to Use

- Translate class, method, or field names between `yarn`, `mojmap`, `intermediary`, and `obfuscated` namespaces.
- Search for Minecraft classes, methods, and fields by mapped names, intermediary IDs, descriptors, or owner/member expressions.
- Compare mapping changes between Minecraft versions.
- Fetch git-like/unified source patches between Minecraft versions.
- Fetch indexed decompiled source or ASM textified bytecode for a class.
- Validate which Minecraft versions are indexed before answering mapping questions.

Do not use MappingLens as an authority for general Minecraft gameplay facts; it is focused on names, mappings, source paths, diffs, and bytecode/source lookup.

## General Workflow

1. If the user did not specify a version and exact version matters, call `GET <url>/api/v1/versions` and choose the latest indexed release, or ask for the version if ambiguity affects the answer.
2. Prefer `GET <url>/api/v1/search` when the namespace, type, owner class, or exact name is uncertain.
3. Prefer `GET <url>/api/v1/translate` when the input namespace and target namespace are known.
4. For version-to-version changes, use `GET <url>/api/v1/diff` for mapping elements, `GET <url>/api/v1/diff/files` for source file movement/modification, and `GET <url>/api/v1/diff/patch` when the user asks for a real git/patch-style source diff.
5. For implementation details, use `GET <url>/api/v1/source/{version}/{className}` first; use bytecode only when source is missing or bytecode-level details are required.
6. URL-encode query parameters and slash-containing path values. If an endpoint supports catch-all class path segments, keep JVM-style slash-separated class names unless the calling tool requires escaping.
7. Treat all endpoints as read-only. Do not assume MappingLens has indexed every Minecraft version or namespace; handle `404` and empty results explicitly.

## Endpoint Quick Reference

### Versions

- `GET <url>/api/v1/versions`
  - Lists indexed versions with counts and namespace availability.
- `GET <url>/api/v1/versions/{version}`
  - Gets metadata for one indexed version.

### Search

- `GET <url>/api/v1/search?q={query}&version={version}&type={type}&namespace={namespace}&limit={limit}&offset={offset}&exact={bool}`
- `q` is required.
- `type`: `class`, `method`, `field`, or `all`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, or `all`.
- Use owner/member forms such as `Block#getDefaultState` or class simple names such as `BlockState`.
- Use `exact=true` only when exact-name lookup is desired; otherwise fuzzy/prefix search is usually better.

### Translate

- `GET <url>/api/v1/translate?name={name}&from={from}&to={to}&version={version}&type={type}`
- `from` / `to`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- `type`: `class`, `method`, `field`, or `auto`.
- Use this when the input name and namespace are already known.
- `GET <url>/api/v1/translate/class/{name}?from={from}&to={to}&version={version}` is a class-specific shortcut.

### Diff

- `GET <url>/api/v1/diff?from={fromVersion}&to={toVersion}&namespace={namespace}&type={type}&package={packagePrefix}&changeType={changeType}&limit={limit}`
- `namespace`: `yarn`, `mojmap`, or `intermediary`.
- `changeType`: `added`, `removed`, `renamed`, or `all`.
- Use `package` for path/package prefix filters such as `net/minecraft/block`.

### File Diff

- `GET <url>/api/v1/diff/files?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&format={format}`
- `namespace`: `yarn` or `mojmap`.
- `format`: `json` (default), `patch`, or `git`.
- Use `json` when the question is about changed source files rather than renamed mapping entries. Use `patch`/`git` when raw unified diff text is requested.

### Patch Diff

- `GET <url>/api/v1/diff/patch?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `namespace`: `yarn` or `mojmap`.
- `path` / `file`: optional folder or source file prefix such as `net/minecraft/block` or `net/minecraft/block/Block.java`.
- `function`: optional method/function filter such as `getDefaultState` or `Block#getDefaultState`.
- `context`: hunk context lines, 0-20; default 3.
- `format`: `patch`/`git` returns `text/x-diff`; `json` returns metadata plus the patch string.
- Use this endpoint for full real source diffs between versions; it compares actual decompiled source content, not only renamed mapping entries.

### Source

- `GET <url>/api/v1/source/{version}/{className}?namespace={namespace}`
- `namespace`: `yarn` or `mojmap`.
- Returns decompiled source text and the indexed source path.
- Use class names such as `net/minecraft/block/Block` or the namespace-specific class name known to MappingLens.

### Bytecode

- `GET <url>/api/v1/bytecode/{version}/{className}?format={format}&namespace={namespace}`
- `format`: `text` or `json`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- Use this for descriptor-level or instruction-level inspection.

### API Docs

- `GET <url>/openapi.yaml`
- `GET <url>/openapi.json`
- `GET <url>/docs`

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
| `mappinglens_get_source` | Fetch decompiled source | `version`, `className` | `namespace` |
| `mappinglens_get_bytecode` | Fetch bytecode/disassembly | `version`, `className` | `namespace`, `format` |
| `mappinglens_get_openapi` | Fetch the API specification | none | `format` |

For MCP schemas, keep enum values identical to the REST API. Return the JSON body unchanged plus the request URL used when useful for debugging.

## Response Handling

- `200`: Use the returned JSON directly; summarize only the fields needed for the user's question.
- `400`: Check parameter names, enum values, and URL encoding.
- `404`: The version, class, mapping entry, source, or bytecode may not be indexed; try search first or report that MappingLens has no indexed match.
- Empty search results: broaden `namespace=all`, `type=all`, remove `exact=true`, or search by simple name/intermediary ID.

## Answering Guidelines

- Include the version, namespace direction, and element type in final answers when relevant.
- If multiple candidates match, show the top few and explain why the chosen result is likely correct.
- For translations, report the intermediary and obfuscated names too when present; they are useful stable anchors.
- For diffs, separate `added`, `removed`, and `renamed` results, and mention the requested limit if results may be truncated.
- Do not fabricate mappings when MappingLens returns no result. Say that no indexed result was found and suggest a broader query.