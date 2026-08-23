---
name: mappinglens
description: 'Use when: working with MappingLens, Minecraft mappings, Yarn, Mojmap, Intermediary, obfuscated names, mapping search, namespace translation, cross-version mapping diffs, source lookup, bytecode inspection, or Minecraft resources (assets, textures, sounds, the integrated datapack, registries, translations) and how they changed between versions, via the MappingLens REST API at 127.0.0.1:8080.'
argument-hint: 'mapping task, name, namespace, version, or version diff'
---

# MappingLens

MappingLens is a read-only REST API for Minecraft mappings across Yarn, Mojmap, Intermediary and
obfuscated namespaces, plus the game's resource files. Base URL: `127.0.0.1:8080`.

Two reference files hold the detail. Read the one the task needs, not both:

- `reference/endpoints.md` — parameters and behavior of every endpoint.
- `reference/responses.md` — one example response per endpoint.

The machine-readable contract is served live at `GET /openapi.yaml`.

## When to Use

- Translate or search names across `yarn`, `mojmap`, `intermediary` and `obfuscated`.
- Track one class or member across every indexed version at once: first appearance, renames, moves,
  removal.
- Compare two versions: mapping elements, source files, a real source patch, or call sites.
- Read decompiled source, bytecode, an inheritance graph, or resolved source tokens.
- Check that mixin and shadow targets still hold, in one version or across a range.
- Read a Minecraft resource of any version from 1.14 on (model, blockstate, texture, sound, loot
  table, recipe, registry dump, language file), say when it changed, and find a translation key by
  the text it carries.

Do not use MappingLens as an authority for general Minecraft gameplay facts. It covers names,
mappings, source paths, diffs, bytecode and source lookup, and the game's resource files.

## General Workflow

1. When the user gives no version and the exact version matters, call `GET /api/v1/versions` and
   choose the latest indexed release. Ask instead when the ambiguity would change the answer.
2. Prefer `/api/v1/search` when the namespace, type, owner class or exact name is uncertain. Prefer
   `/api/v1/translate` when both namespaces are known.
3. For version-to-version changes, pick by need: `/api/v1/diff` for mapping elements,
   `/api/v1/diff/files` for source file movement, `/api/v1/diff/patch` for a git-style source diff.
4. For implementation details, read `/api/v1/source/{version}/{className}` first. Use bytecode only
   when source is missing or instruction-level detail is required.
5. URL-encode query parameters and slash-containing path values. Keep JVM-style slash-separated
   class names unless the calling tool requires escaping.
6. Treat every endpoint as read-only. Do not assume every Minecraft version or namespace is indexed.
   Handle `404` and empty results explicitly.

## Recipe: update a mod to a new version

1. `POST /api/v1/validate?from={old}&to={new}&releasesOnly=true` with every mixin target the mod
   declares. One call answers the whole range, and the `at` block catches an injection point that
   moved while the signature stayed.
2. For anything that came back `call_moved` or `missing`, ask
   `/api/v1/references/{old}?q={key}&to={new}&releasesOnly=true` to see which sites the call left and
   which it arrived at.
3. For a class that changed a lot, `GET /api/v1/diff?from={old}&to={new}&class={internalName}` lists
   its added, removed and renamed members by name.

Two traps, both detailed in `reference/endpoints.md`:

- **`/validate` proves only the targets that were sent.** An all-`ok` report is not a coverage
  report. Build the list from the mod's own `@At` annotations and match by `@At(target = ...)`.
- **A key without a descriptor does not resolve.** `/references` says so with `resolved: false` and
  `/exists` with `candidates[]`. Resolve out of `candidates` first, then re-send.

## Endpoint index

All paths take the `/api/v1` prefix unless noted.

- **Names**: `/search`, `/translate`, `POST /translate/{version}` (up to 2000 keys),
  `/classes/{version}`.
- **Across versions**: `/history` (one key, every version, as spans), `/diff` (mapping elements),
  `/diff/files`, `/diff/patch` (real source patch), `/diff/references` (call sites), `/bodyhash`
  (did a body change), `/blame/{version}/{className}` (which version last touched each line).
- **One version**: `/compare/{version}/{className}` (Yarn and Mojmap member by member), `/source`,
  `/bytecode`, `/tokens`, `/hierarchy`, `/references/{version}`, `POST /exists/{version}`.
- **Mixins**: `POST /validate` over a range, `POST /exists/{version}` for one version, `/references`
  and `/diff/references` for the `@At(target = ...)` half.
- **Resources**: `/resources/versions`, `/tree`, `/file`, `/diff`, `/history`, `/search`.
- **Meta**: `/versions`, `/versions/{version}`, and `GET /health`, `/openapi.yaml`, `/openapi.json`,
  `/docs`.

The resource endpoints are optional and answer `404 resources_disabled` on a deployment that carries
no mcmeta clone. Their version ids are mcmeta's own (`26.3-snapshot-9`), and a MappingLens version id
is accepted as well, so `/api/v1/resources/versions` is the list to check first.

## Rate limits and caching

`/api/v1` is rate-limited to 200 requests per 60 seconds; exceeding it returns `429`.

Successful `/api/v1` responses are served with `Cache-Control: public, max-age=2592000, immutable`,
so reuse results across a session rather than refetching. `/api/v1/versions` is the exception at
`max-age=3600`, because an indexer run adds versions and changes the counts and flags of the ones
already listed. Error responses and `POST` responses are not cached.

## Response Handling

- `200`: use the JSON directly. Summarize only the fields the question needs.
- `400`: check parameter names, enum values and URL encoding.
- `404`: the version, class, mapping entry, source or bytecode may not be indexed. Try search first,
  or report that there is no indexed match.
- `422` (compare): the requested `from`/`to` namespace is unavailable for that version. Check
  `GET /api/v1/versions`.
- Empty results: an empty array can mean the query was not resolvable rather than that the answer is
  empty. `/references` marks that with `resolved: false` and `/exists` with `candidates[]`. Check
  those before reporting a negative, then broaden with `namespace=all`, `type=all`, or by dropping
  `exact=true`.

## Answering Guidelines

- Include the version, namespace direction and element type when relevant.
- If several candidates match, show the top few and say why the chosen one is likely correct.
- For translations, also report the intermediary and obfuscated names when present. They are stable
  anchors.
- For diffs, separate `added`, `removed` and `renamed`, and mention the requested limit if results
  may be truncated.
- Do not fabricate mappings when MappingLens returns no result. Say that no indexed result was found
  and suggest a broader query.
