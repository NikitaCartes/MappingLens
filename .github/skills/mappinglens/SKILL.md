---
name: mappinglens
description: 'Use when: working with MappingLens, Minecraft mappings, Yarn, Mojmap, Intermediary, obfuscated names, mapping search, namespace translation, cross-version mapping diffs, source lookup, or bytecode inspection via the MappingLens REST API at 127.0.0.1:8080.'
argument-hint: 'mapping task, name, namespace, version, or version diff'
---

# MappingLens

Use this skill to query MappingLens, a read-only REST API for Minecraft mappings across Yarn, Mojmap, Intermediary, and obfuscated namespaces.

Base URL: `127.0.0.1:8080`

Two reference files hold the detail. Read the one the task needs, not both:

- `reference/endpoints.md` — parameters and behavior of every endpoint.
- `reference/responses.md` — one example response per endpoint.

The machine-readable contract is served live at `GET 127.0.0.1:8080/openapi.yaml`.

## When to Use

- Translate class, method, or field names between `yarn`, `mojmap`, `intermediary`, and `obfuscated` namespaces.
- Search for Minecraft classes, methods, and fields by mapped names, intermediary IDs, descriptors, or owner/member expressions.
- Compare mapping changes between Minecraft versions.
- Track one class or member across every indexed version at once (first appearance, renames, moves, removal), instead of querying version by version.
- Align a single class across Yarn and Mojmap with a per-member correspondence table.
- Fetch unified, git-style source patches between Minecraft versions.
- Fetch indexed decompiled source or ASM textified bytecode for a class.
- Inspect a class's inheritance hierarchy (supertypes and subtypes).
- Find all references to a class or member.
- Resolve source identifiers to owner/name/descriptor tokens.
- Check that mixin and shadow targets still hold, in one version or across a range.
- Validate which Minecraft versions are indexed before answering mapping questions.

Do not use MappingLens as an authority for general Minecraft gameplay facts. It covers names, mappings, source paths, diffs, and bytecode/source lookup.

## General Workflow

1. When the user does not give a version and the exact version matters, call `GET /api/v1/versions` and choose the latest indexed release. Ask for the version instead if ambiguity would affect the answer.
2. Prefer `/api/v1/search` when the namespace, type, owner class, or exact name is uncertain. Prefer `/api/v1/translate` when both namespaces are known.
3. For version-to-version changes, pick by need: `/api/v1/diff` for mapping elements, `/api/v1/diff/files` for source file movement, `/api/v1/diff/patch` for a real git-style source diff.
4. For implementation details, read `/api/v1/source/{version}/{className}` first. Use bytecode only when source is missing or instruction-level detail is required.
5. URL-encode query parameters and slash-containing path values. Keep JVM-style slash-separated class names unless the calling tool requires escaping.
6. Treat all endpoints as read-only. Do not assume MappingLens has indexed every Minecraft version or namespace. Handle `404` and empty results explicitly.

## Recipe: update a mod to a new version

1. `POST /api/v1/validate?from={old}&to={new}&releasesOnly=true` with every mixin target the mod declares. One call answers the whole range, and the `at` block is what catches an injection point that moved while the signature stayed.
2. For anything that came back `call_moved` or `missing`, ask `/api/v1/references/{old}?q={key}&to={new}&releasesOnly=true` to see which sites the call left and which it arrived at.
3. For a class that changed a lot, `GET /api/v1/diff?from={old}&to={new}&class={internalName}` lists its added, removed, and renamed members by name.

Two traps in this workflow:

- **`/validate` proves only the targets that were sent.** An all-`ok` report is not a coverage report. Build the target list from the mod's own `@At` annotations, and match the two lists by `@At(target = ...)`, not by method or by `id`.
- **A key without a descriptor does not resolve.** `/references` says so with `resolved: false`, `/exists` says so by filling `candidates[]`, and both hand back the keys the query would have matched. Resolve first out of `candidates`, then re-send. Read `resolved` before reading `references`, and take `POST /exists` with a bare `owner:name` over `/search` when the descriptor is the only thing missing.

## Endpoint index

| Endpoint | Answers |
|----------|---------|
| `GET /api/v1/versions` | Which versions are indexed, with counts and namespace flags |
| `GET /api/v1/versions/{version}` | Metadata for one version |
| `GET /api/v1/classes/{version}` | Every class of a version, with all four names |
| `GET /api/v1/search` | Name search over classes, methods and fields |
| `GET /api/v1/translate` | One name from one namespace to another |
| `POST /api/v1/translate/{version}` | Up to 2000 keys at once, descriptors included |
| `GET /api/v1/diff` | Added, removed and renamed mapping elements between two versions |
| `GET /api/v1/diff/files` | Which source files moved or changed between two versions |
| `GET /api/v1/diff/patch` | A real unified source patch between two versions |
| `GET /api/v1/diff/references` | How the sites using a class or member changed between two versions |
| `GET /api/v1/history` | One key across every indexed version, collapsed into spans |
| `GET /api/v1/bodyhash` | Whether a method body changed, without reading source |
| `GET /api/v1/compare/{version}/{className}` | Yarn and Mojmap lined up member by member |
| `GET /api/v1/source/{version}/{className}` | Decompiled source of a class |
| `GET /api/v1/blame/{version}/{className}` | The version that last changed each source line |
| `GET /api/v1/bytecode/{version}/{className}` | ASM textified bytecode |
| `GET /api/v1/tokens/{version}/{className}` | Source identifiers resolved to owner/name/descriptor |
| `GET /api/v1/hierarchy/{version}/{className}` | Supertypes and subtypes as a graph |
| `GET /api/v1/references/{version}` | Every site that references a class or member |
| `POST /api/v1/exists/{version}` | Batch existence of classes and members in one version |
| `POST /api/v1/validate` | Mixin targets across a range of versions |
| `GET /health`, `/openapi.yaml`, `/openapi.json`, `/docs` | Meta |

Read `reference/endpoints.md` for the parameters of the endpoints a task uses.

## Rate limits and caching

All `/api/v1` endpoints are rate-limited to 200 requests per 60 seconds. Exceeding this returns `429`.

Successful `/api/v1` responses are immutable for a given version, and are served with `Cache-Control: public, max-age=2592000, immutable`. Reuse results across a session rather than refetching. `/api/v1/versions` is the exception at `max-age=3600`, because an indexer run adds versions and changes the counts and the namespace flags of the ones already listed. Error responses and `POST` responses are not cached.

## Response Handling

- `200`: Use the returned JSON directly. Summarize only the fields the user's question needs.
- `400`: Check parameter names, enum values, and URL encoding.
- `404`: The version, class, mapping entry, source, or bytecode may not be indexed. Try search first, or report that MappingLens has no indexed match.
- `422` (compare): the requested `from`/`to` namespace is not available for that version. Check `GET /api/v1/versions` for namespace availability.
- Empty results: an empty array can mean the query was not resolvable rather than that the answer is empty. `/references` marks that case with `resolved: false` and `/exists` with `candidates[]`. Check those before reporting a negative, then broaden with `namespace=all`, `type=all`, or by dropping `exact=true`.

## Answering Guidelines

- Include the version, namespace direction, and element type in final answers when relevant.
- If multiple candidates match, show the top few and explain why the chosen result is likely correct.
- For translations, also report the intermediary and obfuscated names when present. They are useful, stable anchors.
- For diffs, separate `added`, `removed`, and `renamed` results, and mention the requested limit if results may be truncated.
- Do not fabricate mappings when MappingLens returns no result. Say that no indexed result was found and suggest a broader query.
