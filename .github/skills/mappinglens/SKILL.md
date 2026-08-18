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
- Track one class or member across every indexed version at once (first appearance, renames, moves, removal), instead of querying version by version.
- Align a single class across Yarn and Mojmap with a per-member correspondence table.
- Fetch unified, git-style source patches between Minecraft versions.
- Fetch indexed decompiled source or ASM textified bytecode for a class.
- Inspect a class's inheritance hierarchy (supertypes and subtypes).
- Find all references to a class or member.
- Resolve source identifiers to owner/name/descriptor tokens.
- Batch-validate that classes and members still exist with the same signature in a target version, for example to check mixin or shadow targets before a mod update.
- Validate which Minecraft versions are indexed before answering mapping questions.

Do not use MappingLens as an authority for general Minecraft gameplay facts. It covers names, mappings, source paths, diffs, and bytecode/source lookup.

## General Workflow

1. When the user does not give a version and the exact version matters, call `GET 127.0.0.1:8080/api/v1/versions` and choose the latest indexed release. Ask for the version instead if ambiguity would affect the answer.
2. Prefer `GET 127.0.0.1:8080/api/v1/search` when the namespace, type, owner class, or exact name is uncertain.
3. Prefer `GET 127.0.0.1:8080/api/v1/translate` when the input namespace and target namespace are known.
4. For version-to-version changes, pick by need:
   - `GET 127.0.0.1:8080/api/v1/diff` for mapping elements.
   - `GET 127.0.0.1:8080/api/v1/diff/files` for source file movement or modification.
   - `GET 127.0.0.1:8080/api/v1/diff/patch` for a real git-style source diff.
5. To line up one class's members across Yarn and Mojmap without reading source, use `GET 127.0.0.1:8080/api/v1/compare/{version}/{className}`.
6. For implementation details, use `GET 127.0.0.1:8080/api/v1/source/{version}/{className}` first. Use bytecode only when source is missing or bytecode-level details are required.
7. URL-encode query parameters and slash-containing path values. If an endpoint supports catch-all class path segments, keep JVM-style slash-separated class names unless the calling tool requires escaping.
8. Treat all endpoints as read-only. Do not assume MappingLens has indexed every Minecraft version or namespace. Handle `404` and empty results explicitly.

## Endpoint Quick Reference

Unless a subsection says otherwise, `namespace` accepts `yarn` or `mojmap` and defaults to `mojmap`.

### Versions

- `GET 127.0.0.1:8080/api/v1/versions?includeVariants={bool}`
  - Lists indexed versions with counts and namespace availability.
  - A **variant** is a second indexing of a build that is already listed under its own id: GitCraft derives `<id>_unobfuscated` from Mojang's pre-deobfuscated jar. Variants are left out by default, because listing both makes one build read as two versions and puts a step between every pair of neighbours. `variantOf` names the build a variant re-indexes, and is null on every version in its own right. Pass `includeVariants=true` to see them; a variant answers `/versions/{id}` and every other endpoint by name either way.
  - `hasIntermediary` means the version carries intermediary names, not that a separate intermediary file was downloaded. Every yarn version has them, because yarn's merged tiny v2 is `official->intermediary->named`. A Mojang unobfuscated release that yarn does not cover carries intermediary only when the indexer was given a separate source for it (`mappinglens.sources.unobfuscated-intermediary-mappings`).
  - `hasMojmap` is true on Mojang's unobfuscated releases, which publish no mappings of their own: the jar already carries the Mojang names, so the `official` namespace is the mojmap namespace. On those versions `obfuscated` and `mojmap` return the same name.
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
- Use `exact=true` only for an exact-name lookup. Fuzzy or prefix search works better otherwise.
- `totalResults` in the response is the count of results in the current page, not the total number of matches in the index.
- A result carries `intermediaryDescriptor`, never a named one, whatever `namespace` asked for: named descriptors are not indexed. Do not paste it into `/exists`, which matches the descriptor of its own namespace. Pass the key through `POST /translate/{version}` first, or read the named descriptor off `/tokens`.

### Translate

- `GET 127.0.0.1:8080/api/v1/translate?name={name}&from={from}&to={to}&version={version}&type={type}`
- `from` / `to`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`.
- `type`: `class`, `method`, `field`, or `auto`.
- Use this when the input name and namespace are already known.
- `GET 127.0.0.1:8080/api/v1/translate/class/{name}?from={from}&to={to}&version={version}` is a class-specific shortcut.

#### Batch translation

- `POST 127.0.0.1:8080/api/v1/translate/{version}` with JSON body `{ "from": "yarn", "to": "mojmap", "keys": ["net/minecraft/block/Block:getDefaultState:()Lnet/minecraft/block/BlockState;"] }`.
- `keys`: class internal names, or `owner:name:descriptor` member keys, spelled in `from`. Up to 2000 per call.
- Returns `{version, from, to, results:[{key, translated, type, intermediary}]}`, one result per key in request order. `translated` is the whole key in `to`, **descriptor included**, so it posts to `/exists/{version}` unchanged. `translated` is null when the version has no such class or member.
- Descriptors are translated type by type through the class table. A type the version does not know (a JDK class) is left as it is.
- A key with no descriptor resolves only when the name has one match. An overloaded name needs the descriptor, otherwise `translated` is null rather than a guess.
- **Use it to move a batch of keys between namespaces**: reading an old Yarn branch against a Mojmap tree is one call, not one `/compare` per class.

### Diff

- `GET 127.0.0.1:8080/api/v1/diff?from={fromVersion}&to={toVersion}&namespace={namespace}&type={type}&package={packagePrefix}&class={classInternalName}&changeType={changeType}&limit={limit}`
- `namespace`: `yarn`, `mojmap`, or `intermediary`. Defaults to `mojmap`.
- `changeType`: `added`, `removed`, `renamed`, or `all`.
- `package` vs `class` (mutually exclusive, `class` wins if both are given):
  - `package` is a **package-path prefix** over the whole diff, for example `net/minecraft/block` matches every class under that package. Give the path in `namespace`: `net/minecraft/world/level/block` for mojmap, `net/minecraft/block` for yarn.
  - `class` targets **exactly one class** by its internal name in `namespace`, for example `net/minecraft/world/entity/Entity`. It lists added, removed, and renamed members by name, each with `owner`, `name`, and `intermediaryDescriptor`. Its `summary` counts (`methodsAdded`, `fieldsAdded`, and so on) match `/diff/files` for the same class exactly. Use it to answer which methods or fields were added to a class, by name.
  - A string like `net/minecraft/world/entity/Entity` names a class, not a package. Pass it as `class=`. Using it as `package=` matches nothing, because no class lives under a package named `Entity`.

### File Diff

- `GET 127.0.0.1:8080/api/v1/diff/files?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `file`: exact or prefix source path filter. Takes precedence over `path`.
- `function`, `context`, `limit`: only used when `format=patch`/`git` (same semantics as the Patch Diff endpoint).
- `format`: `json` (default), `patch`, or `git`.
- Use `json` when the question is about changed source files rather than renamed mapping entries. Use `patch`/`git` when raw unified diff text is requested.

### Patch Diff

- `GET 127.0.0.1:8080/api/v1/diff/patch?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`
- `path` / `file`: optional folder or source file prefix such as `net/minecraft/block` or `net/minecraft/block/Block.java`.
- `function`: optional method/function filter such as `getDefaultState` or `Block#getDefaultState`.
- `context`: hunk context lines (0-20, default 3).
- `ignoreWhitespace` (default `false`): collapse hunks that differ only in whitespace, line breaks, or reindentation (decompiler cosmetics), leaving only real changes. Also available on `/diff/files?format=patch`.
- `format`: `patch` or `git` returns `text/x-diff`. `json` returns metadata plus the patch string.
- Use this endpoint for full source diffs between versions. It compares actual decompiled source content, not only renamed mapping entries.
- Patches are minimal by construction (Myers diff): a class with a few real changes yields a few hunks, not a whole-file rewrite. `ignoreWhitespace=true` also erases pure reformatting, for a decompiler reindent that would otherwise add noise. A class with no semantic change then yields an empty patch.

### History (one key across all versions)

- `GET 127.0.0.1:8080/api/v1/history?q={key}&namespace={namespace}&from={version}&to={version}&releasesOnly={bool}&includeVariants={bool}`
- `q` (required, repeatable): a class internal name (dot-separated FQN also accepted) or a member key `owner:name`. A third `:descriptor` segment is accepted, so a key copied from `/references` or `/exists` works unchanged, but it does not filter. All overloads appear together in the response.
- `namespace`: `yarn`, `mojmap`, or `intermediary`. Defaults to `mojmap`.
- `from` / `to`: limit the version walk. Either bound may be the older one. An unindexed version ID returns `404`. A bound may name a variant even while variants are hidden.
- `releasesOnly` (default `false`): walk releases alone. Use it to read the answer by eye — snapshots between two releases split a span whenever a name moved and moved back.
- `includeVariants` (default `false`): walk `_unobfuscated` variants too. Hidden by default, because a variant sits next to the build it re-indexes and breaks the span in two without a change having happened.
- Returns `{namespace, results[]}`: one entry per `q`, in request order, each `{query, type, spans[]}`. `type` is `class`, `method`, `field`, or `unknown` when nothing matched.
- Each span is a run of consecutive versions with the same answer: `{from, to, versions, present}` plus `intermediary`/`yarn`/`mojmap` for a class, or `owner` and `members[]` (one per overload) for a member. `from` is the older bound.
- **Use it instead of looping `/translate` or `/exists` over versions**: a few keys against 500 versions is one request. Repeat `q` to batch several keys.
- The class is tracked by its intermediary name, not by the string queried. A rename or a package move therefore stays one history, and the name from any version returns the same answer.
- Mojang's unobfuscated releases (everything after 1.21.11) ship no mappings of their own. When the indexer has the separate intermediary source for them, they carry intermediary like any other version and nothing below applies. Check `hasIntermediary` on `/api/v1/versions` to see which case the index is in.
- Without that source, a name from one of those versions is also looked up in the newest mapped version before it, by simple name, which a package move preserves. That lookup is what recovers the rest of the history. Two names alive in the same version are never linked, because a class has one name per version. A class *renamed* after 1.21.11 then keeps only its post-1.21.11 history.
- Named descriptors are not indexed, so a signature change appears only as a changed `members[].intermediaryDescriptor`, and only on versions that carry intermediary names.
- `present: false` with a non-null `owner` means the class is still there and **the index holds no member of that name under it**. `owner: null` means the class itself is gone.
- That is not the same as the member being gone from the game, and the difference matters for a mixin. `/history` reads the mapping index, which holds declarations. `/exists` reads the version's jar. When the two disagree, the jar is right: it is the artifact the mod runs against. Ask `/exists` before concluding that a member was removed.

### Compare

- `GET 127.0.0.1:8080/api/v1/compare/{version}/{className}?from={from}&to={to}`
- `from`: namespace the class name is given in. Defaults to `yarn`.
- `to`: namespace to compare against. Defaults to `mojmap`.
- Returns the obf-keyed member table aligning the class across both namespaces, including members present in only one side. No source is read. The response is a projection of the indexed mappings.
- Per-member `status`: `matched`, `yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`, or `initializer`. Class-level `presence`: `both`, `yarn_only`, or `mojmap_only`.
- `422` is returned when the requested namespace is unavailable for that version.

### Source

- `GET 127.0.0.1:8080/api/v1/source/{version}/{className}?namespace={namespace}&format={format}`
- `namespace`: falls back to `yarn` when the version has no mojmap source. An explicit `namespace` is used as-is, with no fallback.
- `format`: `json` (default) or `text`. Use `text` to get the `.java` as `text/plain`, with no JSON envelope to unpack.
- Returns decompiled source text and the indexed source path.
- `className` accepts a slash-separated internal name (`net/minecraft/block/Block`) or a dot-separated FQN (`net.minecraft.block.Block`).
- A name that matches no class exactly falls back to the one class of that version with the same simple name. A bare simple name (`ZombifiedPiglin`) and a class that moved package between versions both resolve this way, so do not loop over candidate packages. The response `class` field names the class actually served.
- When the simple name is ambiguous or unknown, the call returns `404`, and `message` lists the candidate names.

### Blame

- `GET 127.0.0.1:8080/api/v1/blame/{version}/{className}?namespace={namespace}`
- Returns the version that last changed each line of the class source. `lines` holds one entry per line, line 1 first, and each entry indexes `versions`.
- `className` resolves as it does for `/source`.
- One `git blame` over the source repository answers the whole file, so use this instead of walking `/diff/patch` version by version.
- A version indexed from the artifact store alone has no source repository and gives a `404`.
- `versions` never names an `_unobfuscated` variant. A variant is a second pass over a build already indexed, so a line blamed on it is attributed to the version it was built from.

### Bytecode

- `GET 127.0.0.1:8080/api/v1/bytecode/{version}/{className}?format={format}&namespace={namespace}`
- `format`: `text` or `json`.
- `namespace`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, or `obf`. Defaults to `mojmap`.
- `className` accepts a slash-separated internal name or a dot-separated FQN (`net.minecraft.block.Block`).
- Use this for descriptor-level or instruction-level inspection.

### Source Tokens

- `GET 127.0.0.1:8080/api/v1/tokens/{version}/{className}?namespace={namespace}&format={format}`
- `format`: `json` (default) or `text`. `text` returns one token per line as TSV in the column order below, with a `#`-prefixed header line and empty fields for `null`.
- Returns `{source, tokens}`, where each token resolves one class/method/field identifier in the decompiled `.java` to `{startLine, startColumn, endLine, endColumn, type, className, name, descriptor, declaration}` (Monaco 1-based range, `endColumn` exclusive).
- Use to map a cursor position to an exact symbol (owner/name/descriptor). Identifiers the symbol solver cannot resolve are omitted. A partial file still returns its resolvable tokens.

### Inheritance

- `GET 127.0.0.1:8080/api/v1/hierarchy/{version}/{className}?namespace={namespace}`
- Returns the class's supertypes and subtypes as `{nodes, edges}`. Each node has internal `name`, `simpleName`, `isInterface`, `isAbstract`. Each edge is `{parent, child}`. `java/lang/Object` is omitted. Returns `404` if the class or the version's named jar is absent.

### References

- `GET 127.0.0.1:8080/api/v1/references/{version}?q={key}&namespace={namespace}&to={version}&includeVariants={bool}`
- `q` (required, repeatable): the target, either a class internal name (`net/minecraft/world/level/block/Block`) or a member key `owner:name:descriptor`. Up to 25 per call.
- `to`: the far end of a version range whose near end is `{version}`. Either bound may be the older one. Up to 25 versions per call. Omit it to ask one version.
- Returns `{namespace, results[]}`: one entry per (version, target) as `{version, query, references[]}`, versions oldest first and targets in request order. A version that has a named jar but knows nothing of a target gives an empty `references`.
- Each referencing site is `{owner, ownerSimple, member, descriptor, kind}` (the enclosing method, or the class header). Only references to Minecraft classes in the same jar are indexed. JDK and library targets are dropped.
- Descriptors here are in the requested namespace, so a key from `/references` posts to `/exists` unchanged.
- **Use the batch form to check the `@At(target = ...)` half of a mixin**: `/exists` covers the method injected into, and nothing else covers the calls inside its body. A signature can survive a version while the call inside it moves to another method.
- `404` when no requested version has a named jar in that namespace.

### Exists (batch member/class existence)

- `POST 127.0.0.1:8080/api/v1/exists/{version}` with JSON body `{ "namespace": "mojmap", "members": ["net/minecraft/.../ChunkMap:move:(...)V", "net/minecraft/.../ChunkMap"] }`.
- `namespace`: `yarn` or `mojmap`. Each key is either a class internal name (`owner`) or a member `owner:name:descriptor`. Descriptors are in the requested namespace.
- Returns `{version, namespace, results:[{key, exists, closest, reason}]}`: one answer per key, in request order.
- A key that missed also reports the nearest declaration, so one call says *what* changed:
  - `reason: "inherited"` — a supertype declares this exact signature. `closest` names it. The call still resolves at runtime, so a mixin `@At` target is valid; a `@Shadow` has to name the supertype.
  - `reason: "descriptor"` — the owner declares this name under another descriptor, so the signature changed. `closest` carries the descriptor the version has.
  - Both null — the version declares nothing of that name under that owner, or the owner itself is gone.
- Both fields are null when `exists` is true.
- Batch up to 2000 keys per call. Checks the version's pre-remapped named jar via ASM (cached per version+namespace), so descriptors match exactly with no remapping. Returns `404` if that jar is absent for the version.
- **Use it to validate mixin/shadow targets when updating a mod**: confirm every injected method and shadowed field still exists with the same signature in one request instead of many `search`/`source` calls. This is the only `POST` endpoint. It is not cached, because results depend on the request body.

### Meta / Health

- `GET 127.0.0.1:8080/health`: returns `ok`. Not rate-limited.
- `GET 127.0.0.1:8080/openapi.yaml`
- `GET 127.0.0.1:8080/openapi.json`
- `GET 127.0.0.1:8080/docs`: Swagger UI.

All `/api/v1` endpoints are rate-limited to 200 requests per 60 seconds. Exceeding this returns `429`.

Successful `/api/v1` responses are immutable for a given version. They are served with `Cache-Control: public, max-age=2592000, immutable` (one month). Error responses are not cached. Reuse cached results across a session rather than refetching. `/api/v1/versions` is the exception at `max-age=3600`, because an indexer run adds versions and changes the counts and the namespace flags of the ones already listed.

## Response Shapes

One example per endpoint, trimmed to one entry per array. Read the field off the example rather than guessing at it: several responses nest the answer (`/translate` puts the result in `output.name`, not at the top level).

### `/versions` and `/versions/{version}`

```json
{"versions": [
  {"id": "1.21.11", "releaseType": "release", "releaseTime": "2025-12-09T12:23:30+00:00",
   "protocolVersion": 774, "hasYarn": true, "hasMojmap": true, "hasIntermediary": true,
   "variantOf": null, "classCount": 10291, "methodCount": 89606, "fieldCount": 45679,
   "indexedAt": "2026-08-16T23:51:04.726392807Z"}
]}
```

`/versions/{version}` returns one such object, unwrapped.

### `/classes/{version}`

```json
{"version": "1.21.11", "classes": [
  {"obfuscated": "a", "intermediary": "net/minecraft/class_7833",
   "yarn": "net/minecraft/util/math/RotationAxis", "mojmap": "com/mojang/math/Axis", "presence": "both"}
]}
```

### `/search`

```json
{"query": "Level#getRespawnData", "version": "26.1", "totalResults": 1, "results": [
  {"type": "method", "intermediary": "net/minecraft/class_1937#method_74854",
   "yarn": "net/minecraft/world/World#getSpawnPoint",
   "mojmap": "net/minecraft/world/level/Level#getRespawnData",
   "obfuscated": "net/minecraft/world/level/Level#getRespawnData",
   "owner": {"intermediary": "net/minecraft/class_1937", "yarn": "net/minecraft/world/World",
             "mojmap": "net/minecraft/world/level/Level", "obfuscated": "net/minecraft/world/level/Level"},
   "intermediaryDescriptor": "()Lnet/minecraft/class_5217$class_12064;", "score": 0.078}
]}
```

### `/translate`

```json
{"input": {"name": "net/minecraft/block/Block", "namespace": "yarn"},
 "output": {"name": "net/minecraft/world/level/block/Block", "namespace": "mojmap"},
 "intermediary": "net/minecraft/class_2248", "obfuscated": "dzq",
 "version": "1.21.11", "type": "class"}
```

The translated name is `output.name`.

### `POST /translate/{version}`

```json
{"version": "1.21.11", "from": "yarn", "to": "mojmap", "results": [
  {"key": "net/minecraft/block/Block:getDefaultState:()Lnet/minecraft/block/BlockState;",
   "translated": "net/minecraft/world/level/block/Block:defaultBlockState:()Lnet/minecraft/world/level/block/state/BlockState;",
   "type": "method", "intermediary": "method_9564"}
]}
```

### `/diff`

```json
{"from": "1.21.10", "to": "1.21.11", "namespace": "mojmap",
 "changes": {
   "added": [],
   "removed": [{"type": "method", "name": "getRandomSequences", "intermediary": null,
                "owner": "net/minecraft/server/level/ServerLevel",
                "intermediaryDescriptor": "()Lnet/minecraft/class_8565;",
                "oldName": null, "newName": null}],
   "renamed": []},
 "summary": {"classesAdded": 0, "classesRemoved": 0, "classesRenamed": 0,
             "methodsAdded": 7, "methodsRemoved": 66, "methodsRenamed": 18,
             "fieldsAdded": 0, "fieldsRemoved": 1, "fieldsRenamed": 0}}
```

### `/diff/files`

```json
{"from": "1.21.10", "to": "1.21.11", "namespace": "mojmap",
 "files": {"added": [], "removed": [],
   "modified": [{"path": "net/minecraft/world/waypoints/Waypoint.java",
                 "methodsAdded": 0, "methodsRemoved": 0, "fieldsAdded": 0, "fieldsRemoved": 0}]}}
```

### `/diff/patch` (`format=json`)

```json
{"from": "1.21.10", "to": "1.21.11", "namespace": "mojmap",
 "path": "net/minecraft/world/waypoints/Waypoint.java", "function": null,
 "files": [{"path": "net/minecraft/world/waypoints/Waypoint.java", "changeType": "modified"}],
 "fileCount": 1, "truncated": false,
 "patch": "diff --git a/... b/...\n--- a/...\n+++ b/...\n@@ ..."}
```

`format=patch` and `format=git` return the patch string alone as `text/x-diff`.

### `/history`

```json
{"namespace": "mojmap", "results": [
  {"query": "net/minecraft/world/level/Level:getRespawnData", "type": "method", "spans": [
    {"from": "1.21.9", "to": "26.1", "versions": 28, "present": true,
     "intermediary": null, "yarn": null, "mojmap": null,
     "owner": "net/minecraft/world/level/Level",
     "members": [{"intermediary": "method_74854", "yarn": "getSpawnPoint", "mojmap": "getRespawnData",
                  "intermediaryDescriptor": "()Lnet/minecraft/class_5217$class_12064;"}]}
  ]}
]}
```

A class query fills `intermediary`/`yarn`/`mojmap` on the span and leaves `owner`/`members` empty; a member query does the reverse.

### `/compare/{version}/{className}`

```json
{"version": "1.21.11", "from": "yarn", "to": "mojmap", "obf": "dzq",
 "intermediary": "net/minecraft/class_2248",
 "yarnClass": "net/minecraft/block/Block", "mojmapClass": "net/minecraft/world/level/block/Block",
 "presence": "both",
 "members": [{"kind": "method", "obfName": "a", "obfDesc": "(D)Lfug;", "intermediary": "method_66393",
              "yarn": "createCubeShape", "mojmap": "cube", "status": "matched"}]}
```

### `/source/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/WaypointStyleAssets",
 "namespace": "mojmap", "source": "package net.minecraft.world.waypoints;\n\n...",
 "path": "net/minecraft/world/waypoints/WaypointStyleAssets.java"}
```

`format=text` returns the `.java` alone as `text/plain`.

### `/blame/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/Waypoint", "namespace": "mojmap",
 "path": "net/minecraft/world/waypoints/Waypoint.java",
 "versions": ["25w15a", "25w45a", "25w17a", "1.21.11-pre4"],
 "lines": [0, 0, 1, 2]}
```

`lines` holds one index into `versions` per source line, line 1 first.

### `/bytecode/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/WaypointStyleAssets",
 "bytecode": "// class version 65.0 (65)\npublic abstract interface ..."}
```

### `/tokens/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/Waypoint", "namespace": "mojmap",
 "source": "package net.minecraft.world.waypoints;\n\n...",
 "tokens": [{"startLine": 21, "startColumn": 6, "endLine": 21, "endColumn": 15,
             "type": "field", "className": "net/minecraft/world/waypoints/Waypoint",
             "name": "MAX_RANGE", "descriptor": "I", "declaration": true}]}
```

`descriptor` here **is** in the requested namespace. `format=text` gives the same columns as TSV with a `#` header line, which is the cheapest way to read a whole class's named descriptors.

### `/hierarchy/{version}/{className}`

```json
{"version": "1.21.11", "namespace": "mojmap",
 "root": "net/minecraft/world/level/block/state/BlockState",
 "nodes": [{"name": "net/minecraft/world/level/block/state/BlockState", "simpleName": "BlockState",
            "isInterface": false, "isAbstract": false}],
 "edges": [{"parent": "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
            "child": "net/minecraft/world/level/block/state/BlockState"}]}
```

### `/references/{version}`

```json
{"namespace": "mojmap", "results": [
  {"version": "26.1", "query": "net/minecraft/server/level/ServerLevel", "references": [
    {"owner": "net/minecraft/advancements/AdvancementRewards", "ownerSimple": "AdvancementRewards",
     "member": "grant", "descriptor": "(Lnet/minecraft/server/level/ServerPlayer;)V", "kind": "method"}
  ]}
]}
```

### `POST /exists/{version}`

```json
{"version": "26.1", "namespace": "mojmap", "results": [
  {"key": "net/minecraft/server/level/ServerLevel:getRespawnData:()Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
   "exists": true, "closest": null, "reason": null},
  {"key": "net/minecraft/server/level/ServerLevel:getRespawnData:()Ljava/lang/String;",
   "exists": false,
   "closest": "net/minecraft/server/level/ServerLevel:getRespawnData:()Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
   "reason": "descriptor"}
]}
```

### Errors

```json
{"error": "invalid_query", "message": "Missing required parameter 'q'", "status": 400}
```

## Suggested MCP Tool Contract

If wrapping MappingLens as an MCP server, expose these read-only tools and map them directly to the REST endpoints above:

| Tool | Purpose | Required Inputs | Optional Inputs |
|------|---------|-----------------|-----------------|
| `mappinglens_list_versions` | List indexed Minecraft versions | none | none |
| `mappinglens_get_version` | Get one version's metadata | `version` | none |
| `mappinglens_list_classes` | List all classes for a version | `version` | none |
| `mappinglens_search` | Search classes, methods, fields | `q` | `version`, `type`, `namespace`, `limit`, `offset`, `exact` |
| `mappinglens_translate` | Translate a name between namespaces | `name`, `from`, `to` | `version`, `type` |
| `mappinglens_translate_batch` | Translate a batch of keys, descriptors included | `version`, `keys` | `from`, `to` |
| `mappinglens_diff` | Compare mapping elements between versions | `from`, `to` | `namespace`, `type`, `package`, `class`, `changeType`, `limit` |
| `mappinglens_diff_files` | Compare indexed source files | `from`, `to` | `namespace`, `path`, `file`, `format`, `ignoreWhitespace` |
| `mappinglens_diff_patch` | Return real unified source patch between versions | `from`, `to` | `namespace`, `path`, `file`, `function`, `context`, `limit`, `format`, `ignoreWhitespace` |
| `mappinglens_compare` | Yarn↔Mojmap per-member correspondence table for a class | `version`, `className` | `from`, `to` |
| `mappinglens_get_source` | Fetch decompiled source | `version`, `className` | `namespace` |
| `mappinglens_get_tokens` | Resolve source identifiers to owner/name/descriptor tokens | `version`, `className` | `namespace` |
| `mappinglens_get_bytecode` | Fetch bytecode/disassembly | `version`, `className` | `namespace`, `format` |
| `mappinglens_hierarchy` | Class supertypes/subtypes graph | `version`, `className` | `namespace` |
| `mappinglens_references` | Find references to classes or members | `version`, `q` | `namespace`, `to`, `includeVariants` |
| `mappinglens_exists` | Batch-check class/member existence in a version | `version`, `members` | `namespace` |
| `mappinglens_get_openapi` | Fetch the API specification | none | `format` |

For MCP schemas, keep enum values identical to the REST API. Return the JSON body unchanged plus the request URL used when useful for debugging.

## Response Handling

- `200`: Use the returned JSON directly. Summarize only the fields the user's question needs.
- `400`: Check parameter names, enum values, and URL encoding.
- `404`: The version, class, mapping entry, source, or bytecode may not be indexed. Try search first, or report that MappingLens has no indexed match.
- `422` (compare): the requested `from`/`to` namespace is not available for that version. Check `GET /api/v1/versions` for namespace availability.
- Empty search results: broaden `namespace=all`, `type=all`, remove `exact=true`, or search by simple name/intermediary ID.

## Answering Guidelines

- Include the version, namespace direction, and element type in final answers when relevant.
- If multiple candidates match, show the top few and explain why the chosen result is likely correct.
- For translations, also report the intermediary and obfuscated names when present. They are useful, stable anchors.
- For diffs, separate `added`, `removed`, and `renamed` results, and mention the requested limit if results may be truncated.
- Do not fabricate mappings when MappingLens returns no result. Say that no indexed result was found and suggest a broader query.
