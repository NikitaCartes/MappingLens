# MappingLens endpoints

Parameters and behavior, one section per endpoint. For an example response, see `responses.md`.

Every path below is relative to `127.0.0.1:8080`. Unless a section says otherwise, `namespace`
accepts `yarn` or `mojmap` and defaults to `mojmap`.

## Versions

- `GET /api/v1/versions?includeVariants={bool}` lists indexed versions with counts and namespace
  availability.
  - A **variant** is a second indexing of a build already listed under its own id: GitCraft derives
    `<id>_unobfuscated` from Mojang's pre-deobfuscated jar. Variants are hidden by default.
    `variantOf` names the build a variant re-indexes, and is null on every version in its own right.
    `includeVariants=true` shows them; a variant answers `/versions/{id}` and every other endpoint by
    name either way.
  - `hasIntermediary` means the version carries intermediary names, not that a separate intermediary
    file was downloaded. Every yarn version has them, because yarn's merged tiny v2 is
    `official->intermediary->named`. A Mojang unobfuscated release that yarn does not cover carries
    intermediary only when the indexer was given a separate source for it
    (`mappinglens.sources.unobfuscated-intermediary-mappings`).
  - `hasMojmap` is true on Mojang's unobfuscated releases, which publish no mappings of their own:
    the jar already carries the Mojang names, so `official` is the mojmap namespace. On those
    versions `obfuscated` and `mojmap` return the same name.
- `GET /api/v1/versions/{version}` gets metadata for one indexed version.
- `GET /api/v1/classes/{version}` lists every indexed class of a version with obf/intermediary/yarn/
  mojmap aliases and a `presence` flag (`both`, `yarn_only`, `mojmap_only`), for building package or
  class trees client-side. `404` when the version is not indexed.

## Search

`GET /api/v1/search?q={query}&version={version}&type={type}&namespace={namespace}&limit={limit}&offset={offset}&exact={bool}&includeSynthetic={bool}`

- `q` is required. Owner/member forms (`Block#getDefaultState`) and simple names (`BlockState`) both
  work.
- `type`: `class`, `method`, `field`, `all`. `namespace`: `yarn`, `mojmap`, `intermediary`, `all`.
- `exact=true` is for an exact-name lookup only. Fuzzy or prefix search works better otherwise.
- `score` runs `0` to just under `1`, and **higher is better**. `results` already comes back best
  first. The value is derived from the FTS5 bm25 rank of that one query, so it ranks one call's
  results and means nothing across calls.
- `totalResults` is the count of results in the current page, not the total number of matches.
- A member result carries the descriptor in three spellings: `intermediaryDescriptor`,
  `yarnDescriptor` and `mojmapDescriptor`. Build an `/exists` key straight out of the last two —
  `{mojmap}:{mojmapDescriptor}` with the `#` replaced by `:` — and it needs no translation. A
  namespace the version does not name gives null there, so a version without yarn returns
  `mojmapDescriptor` alone.
- `includeSynthetic` (default `false`): javac lambda bodies (`lambda$addRecipes$0`) are named in the
  mappings like any other method, and are left out unless asked for. A row that is kept carries
  `synthetic: true`. Write the mixin against the enclosing method either way, because the index in
  the name moves between versions.
- One `mojmap` name appears more than once when the method is overloaded. Those rows differ only in
  `mojmapDescriptor`.

## Body hash (did a method body change?)

`GET /api/v1/bodyhash?q={key}&namespace={namespace}&from={version}&to={version}&releasesOnly={bool}&normalize={mode}`

- Answers "is there anything to re-check between these versions". Prefer it over `/diff/patch`,
  which works on decompiled source and reports the decompiler's cosmetics as a change.
- `q` (required, repeatable, up to 50): `owner:name` or `owner:name:descriptor`. Without a
  descriptor every overload of the name hashes together.
- `namespace`: `yarn` or `mojmap` only, because the hash is read from a named jar. `from` and `to`
  are both required.
- `normalize`: `named` (default) hashes the body as the namespace spells it, so a renamed callee
  moves the hash. `intermediary` renames the class types first, so a class rename or a package move
  does not.
- Returns `{namespace, normalize, results[]}`, each `{query, spans[]}`, each span
  `{from, to, versions, hash}`. `hash` is null on a version with no such method.
- Equal hash means the body is the same. A changed hash names the versions to read with `/source` or
  `/bytecode`.
- A lambda body is a method of its own and is not followed, so a change confined to a lambda does not
  move the enclosing hash. The index in `lambda$name$12` is normalized away, so an unrelated lambda
  added above does not move it either.
- At most 60 versions per request. Pass `releasesOnly=true` to cover a wide range.

## Translate

- `GET /api/v1/translate?name={name}&from={from}&to={to}&version={version}&type={type}`
- `from` / `to`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, `obf`. `type`: `class`, `method`,
  `field`, `auto`.
- Use it when the input name and namespace are already known.
- `GET /api/v1/translate/class/{name}?from={from}&to={to}&version={version}` is a class shortcut.

### Batch translation

- `POST /api/v1/translate/{version}` with body
  `{"from": "yarn", "to": "mojmap", "keys": ["net/minecraft/block/Block:getDefaultState:()Lnet/minecraft/block/BlockState;"]}`.
- `keys`: class internal names, or `owner:name:descriptor` member keys, spelled in `from`. Up to
  2000 per call.
- Returns `{version, from, to, results:[{key, translated, type, intermediary}]}`, one result per key
  in request order. `translated` is the whole key in `to`, **descriptor included**, so it posts to
  `/exists/{version}` unchanged, and is null when the version has no such class or member.
- Descriptors are translated type by type through the class table. A type the version does not know
  (a JDK class) is left as it is.
- A key with no descriptor resolves only when the name has one match. An overloaded name needs the
  descriptor, otherwise `translated` is null rather than a guess.
- **Use it to move a batch of keys between namespaces**: reading an old Yarn branch against a Mojmap
  tree is one call, not one `/compare` per class.

## Diff

`GET /api/v1/diff?from={fromVersion}&to={toVersion}&namespace={namespace}&type={type}&package={packagePrefix}&class={classInternalName}&changeType={changeType}&limit={limit}`

- `namespace`: `yarn`, `mojmap` or `intermediary`. `changeType`: `added`, `removed`, `renamed`,
  `all`.
- `package` and `class` are mutually exclusive, and `class` wins if both are given:
  - `package` is a **package-path prefix** over the whole diff, given in `namespace`:
    `net/minecraft/world/level/block` for mojmap, `net/minecraft/block` for yarn.
  - `class` targets **exactly one class** by internal name and lists its added, removed and renamed
    members, each with `owner`, `name` and `intermediaryDescriptor`. Its `summary` counts match
    `/diff/files` for the same class exactly. Use it to answer which members a class gained or lost.
  - A string like `net/minecraft/world/entity/Entity` names a class, not a package. Pass it as
    `class=`. As `package=` it matches nothing, because no class lives under a package named
    `Entity`.

## File diff

`GET /api/v1/diff/files?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`

- `file`: exact or prefix source path filter. Takes precedence over `path`.
- `function`, `context`, `limit`: read only when `format=patch`/`git`, with the same meaning as on
  the patch endpoint.
- `format`: `json` (default), `patch`, `git`. Use `json` for which source files changed, `patch` or
  `git` for raw unified diff text.

## Patch diff

`GET /api/v1/diff/patch?from={fromVersion}&to={toVersion}&namespace={namespace}&path={pathPrefix}&file={filePath}&function={functionName}&context={lines}&limit={limit}&format={format}`

- `path` / `file`: folder or source file prefix, such as `net/minecraft/block` or
  `net/minecraft/block/Block.java`.
- `function`: method filter, such as `getDefaultState` or `Block#getDefaultState`.
- `context`: hunk context lines (0-20, default 3).
- `ignoreWhitespace` (default `false`): collapses hunks that differ only in whitespace, line breaks
  or reindentation. Also available on `/diff/files?format=patch`.
- `format`: `patch` or `git` returns `text/x-diff`; `json` returns metadata plus the patch string.
- This endpoint compares decompiled source content, not only renamed mapping entries. Patches are
  minimal by construction (Myers diff), and with `ignoreWhitespace=true` a class with no semantic
  change yields an empty patch.

## History (one key across all versions)

`GET /api/v1/history?q={key}&namespace={namespace}&from={version}&to={version}&releasesOnly={bool}&includeVariants={bool}`

- `q` (required, repeatable): a class internal name (dot-separated FQN also accepted) or a member key
  `owner:name`. A third `:descriptor` segment is accepted, so a key copied from `/references` or
  `/exists` works unchanged, but it does not filter: all overloads appear together.
- `namespace`: `yarn`, `mojmap` or `intermediary`.
- `from` / `to`: limit the version walk. Either bound may be the older one. An unindexed version id
  returns `404`. A bound may name a variant even while variants are hidden.
- `releasesOnly` (default `false`): walk releases alone. Snapshots between two releases split a span
  whenever a name moved and moved back.
- `includeVariants` (default `false`): walk `_unobfuscated` variants too. A variant sits next to the
  build it re-indexes and breaks a span in two without a change having happened.
- Returns `{namespace, results[]}`: one entry per `q`, in request order, each
  `{query, type, spans[]}`. `type` is `class`, `method`, `field`, or `unknown` when nothing matched.
- Each span is a run of consecutive versions with the same answer: `{from, to, versions, present}`
  plus `intermediary`/`yarn`/`mojmap` for a class, or `owner` and `members[]` (one per overload) for
  a member. `from` is the older bound.
- **Use it instead of looping `/translate` or `/exists` over versions**: a few keys against 500
  versions is one request. Repeat `q` to batch several keys.
- The class is tracked by its intermediary name, not by the string queried, so a rename or a package
  move stays one history and the name from any version returns the same answer.
- `query` is echoed back verbatim, and the name each version uses is in that span's `members[]`,
  never in `query`. Two spellings of one member therefore return the same spans under different
  `query` values: ask by `location` or by `identifier` and both return the whole history, with
  `members[].mojmap` naming whichever one that span's versions use.
- Mojang's unobfuscated releases (everything after 1.21.11) ship no mappings of their own. When the
  indexer has the separate intermediary source for them, they carry intermediary like any other
  version and nothing below applies. Check `hasIntermediary` on `/api/v1/versions`.
- Without that source, a name from one of those versions is also looked up in the newest mapped
  version before it, by simple name, which a package move preserves. Two names alive in the same
  version are never linked, because a class has one name per version. A class *renamed* after
  1.21.11 then keeps only its post-1.21.11 history.
- Named descriptors are not indexed, so a signature change appears only as a changed
  `members[].intermediaryDescriptor`, and only on versions that carry intermediary names.
- `present: false` with a non-null `owner` means the class is still there and **the owner declares no
  member of that name**. `owner: null` means the class itself is gone.
- `present: false` with `reason: "inherited"` means a supertype declares it and the call still
  resolves; `declaredIn` names that supertype and `members[]` describes its declaration. With no
  `reason`, the member is really not there, on the owner or above it, so no second call to `/exists`
  is needed to tell the two apart.
- `type: "unknown"` still carries spans: nothing in the index names this member, and the spans say
  over which versions.
- `/history` reads the mapping index, which holds declarations. `/exists` reads the version's jar.
  When the two disagree, the jar is right: it is the artifact the mod runs against.

## Compare

`GET /api/v1/compare/{version}/{className}?from={from}&to={to}`

- `from`: namespace the class name is given in, default `yarn`. `to`: namespace to compare against,
  default `mojmap`.
- Returns the obf-keyed member table aligning the class across both namespaces, including members
  present on only one side. No source is read.
- Per-member `status`: `matched`, `yarnOnly`, `mojmapOnly`, `unmappedYarn`, `synthetic`,
  `initializer`, or `unmapped` when neither namespace names the member. Class-level `presence`:
  `both`, `yarn_only`, `mojmap_only`.
- `422` when the requested namespace is unavailable for that version.

## Source

`GET /api/v1/source/{version}/{className}?namespace={namespace}&format={format}`

- `namespace` falls back to `yarn` when the version has no mojmap source. An explicit `namespace` is
  used as-is, with no fallback.
- `format`: `json` (default) or `text`. `text` gives the `.java` as `text/plain`, with no JSON
  envelope to unpack.
- `className` accepts a slash-separated internal name (`net/minecraft/block/Block`) or a
  dot-separated FQN (`net.minecraft.block.Block`).
- A name that matches no class exactly falls back to the one class of that version with the same
  simple name, so a bare simple name (`ZombifiedPiglin`) and a class that moved package both resolve.
  Do not loop over candidate packages. The response `class` field names the class actually served.
- When the simple name is ambiguous or unknown, the call returns `404` and `message` lists the
  candidates.

## Blame

`GET /api/v1/blame/{version}/{className}?namespace={namespace}`

- Returns the version that last changed each line of the class source. `lines` holds one entry per
  line, line 1 first, and each entry indexes `versions`.
- `className` resolves as it does for `/source`.
- One `git blame` answers the whole file, so use this instead of walking `/diff/patch` version by
  version.
- A version indexed from the artifact store alone has no source repository and gives a `404`.
- `versions` never names an `_unobfuscated` variant: a line blamed on one is attributed to the
  version it was built from.

## Bytecode

`GET /api/v1/bytecode/{version}/{className}?format={format}&namespace={namespace}`

- `format`: `text` or `json`. `namespace`: `yarn`, `mojmap`, `intermediary`, `obfuscated`, `obf`.
- `className` accepts an internal name or a dot-separated FQN.
- Use it for descriptor-level or instruction-level inspection.

## Source tokens

`GET /api/v1/tokens/{version}/{className}?namespace={namespace}&format={format}`

- `format`: `json` (default) or `text`. `text` returns one token per line as TSV in the column order
  below, with a `#`-prefixed header line and empty fields for `null`.
- Returns `{source, tokens}`, where each token resolves one class/method/field identifier in the
  decompiled `.java` to `{startLine, startColumn, endLine, endColumn, type, className, name,
  descriptor, declaration}` (Monaco 1-based range, `endColumn` exclusive).
- Use it to map a cursor position to an exact symbol. Identifiers the symbol solver cannot resolve
  are omitted, and a partial file still returns its resolvable tokens.

## Inheritance

`GET /api/v1/hierarchy/{version}/{className}?namespace={namespace}`

- Returns supertypes and subtypes as `{nodes, edges}`. Each node has internal `name`, `simpleName`,
  `isInterface`, `isAbstract`; each edge is `{parent, child}`. `java/lang/Object` is omitted. `404`
  when the class or the version's named jar is absent.

## References

- `GET /api/v1/references/{version}?q={key}&namespace={namespace}&to={version}&releasesOnly={bool}&includeVariants={bool}&depth={n}`
- `POST /api/v1/references/{version}` with
  `{"namespace", "targets": [...], "to", "releasesOnly", "includeVariants", "depth"}` for larger
  batches. `QUERY` works on the same path with the same body.
- `q` (required, repeatable): a class internal name
  (`net/minecraft/world/level/block/Block`) or a member key `owner:name:descriptor`. Up to 25 per
  call on `GET`, up to 2000 in a body. A request line above 4096 bytes never reaches the route, so
  post anything longer.
- **Read `resolved` before reading `references`.** `resolved: false` means the group is not an answer
  about the target, and `references` is empty for a reason that has nothing to do with call sites.
  Three causes: the key carries no descriptor, so it can never match the `name:descriptor` the index
  is keyed by; its descriptor names no member the index knows; or the index holds no row for the
  owner, which is either an absent class or a class nothing mentions. `candidates` then lists the
  keys the query would have matched, ready to re-send. A key can resolve in one version of a range
  and not in the next, so check it per group.
- `/history` takes `owner:name` and resolves it; here the same two-segment key never matches. That is
  the one arity difference in the API.
- `q` matches the owner written in the call instruction, not the class that declares the member. A
  call to an inherited method carries the subclass the caller holds, so
  `net/minecraft/world/level/Level:getRespawnData` returns no sites while
  `net/minecraft/server/level/ServerLevel:getRespawnData` returns 12. An empty `references` for a
  member is not proof that nothing calls it. No parameter walks the hierarchy: ask `/hierarchy` for
  the subtypes, then repeat the query for the subclass that callers hold. `/exists` reports the same
  asymmetry from the other side, as `reason: "inherited"`.
- `to`: the far end of a version range whose near end is `{version}`. Either bound may be the older
  one. Omit it to ask one version. The range is not capped; what is capped, at 25, is how many of its
  versions the prebuilt reference index does not cover, because each of those costs a jar scan.
- `releasesOnly`: walk releases alone. The prebuilt index covers releases, so this walks the whole
  release line in one call — `1.14` to `26.2` is 47 releases. Without it, that range is 469 versions
  and is refused.
- `depth` (1 to 5, default 1): frames of the caller chain to walk. Above 1 the response also carries
  `paths`, the chains that reach the target, outermost frame first, at most 200 of them. Use it for
  "which entry points reach this" instead of asking one level per round. Each frame of a chain is a
  `references[]` object, not a string.
- Returns `{namespace, results[]}`: one entry per (version, target) as
  `{version, query, references[], resolved, candidates[]}`, versions oldest first and targets in
  request order. `resolved: true` with an empty `references` is the honest "no site calls this".
- Each referencing site is `{owner, ownerSimple, member, descriptor, kind, count, synthetic}` (the
  enclosing method, or the class header). Only references to Minecraft classes in the same jar are
  indexed; JDK and library targets are dropped.
- `count` is how many instructions in that site hit the target. `@At(ordinal = N)` numbers them
  `0 .. count-1`, so `count: 2` means two injection points in one method.
- `synthetic` names the javac lambda body the call sits in (`lambda$stopSleeping$9`) while `member`
  names the method that lambda is written in. The lambda index moves between versions; write the
  mixin against `member`.
- Calls inside the calling class itself are indexed, and method references (`Foo::bar`) are followed
  through their `invokedynamic`.
- Descriptors here are in the requested namespace, so a key from `/references` posts to `/exists`
  unchanged.
- **Use the batch form to check the `@At(target = ...)` half of a mixin**: `/exists` covers the
  method injected into, and nothing else covers the calls inside its body. A signature can survive a
  version while the call inside it moves to another method.
- `404` when no requested version has a named jar in that namespace.

## Reference diff

`GET /api/v1/diff/references?from={version}&to={version}&q={key}&namespace={namespace}`

- Returns `{from, to, namespace, query, changes: {added, removed, moved}}`. Each entry of `added` and
  `removed` is `{owner, ownerSimple, member, targets}`, where `targets` are the members of `q` that
  site reaches.
- `moved` pairs a removed site with an added one when both reach exactly the same members: first by
  the same method name, then the same class, then a lone pair. An ambiguous group pairs nothing. A
  paired site stays in `added` and `removed` too.
- **This is the check `/exists` cannot make.** A call that moves from one method to another leaves
  every signature intact, so `/exists` reports nothing while `@At(target = ...)` breaks in silence.

## Exists (batch member/class existence)

- `POST /api/v1/exists/{version}` with body
  `{"namespace": "mojmap", "members": ["net/minecraft/.../ChunkMap:move:(...)V", "net/minecraft/.../ChunkMap"]}`.
- `namespace`: `yarn` or `mojmap`. Each key is either a class internal name (`owner`) or a member
  `owner:name:descriptor`. Descriptors are in the requested namespace.
- Returns `{version, namespace, results:[{key, exists, closest, reason, candidates}]}`, one answer
  per key in request order.
- A key that missed also reports the nearest declaration, so one call says *what* changed:
  - `reason: "inherited"` — a supertype declares this exact signature, named by `closest`. The call
    still resolves at runtime, so a mixin `@At` target is valid; a `@Shadow` has to name the
    supertype.
  - `reason: "descriptor"` — the owner declares this name under another descriptor **of the same
    kind**, so the signature changed. `closest` carries the descriptor the version has.
  - `reason: "kind"` — the owner declares this name as a field where a method was asked for, or the
    other way round. `closest` names that declaration and is **not** a drop-in replacement: pasting a
    field into an `@Inject` compiles into nothing useful. A method descriptor opens with `(` and a
    field descriptor does not.
  - Both null — the version declares nothing of that name under that owner, or the owner is gone.
    Both are also null when `exists` is true.
- **`owner:name` without a descriptor is a resolve, not an existence check.** Such a key can never
  match, so it comes back `exists: false` with `candidates[]`: every declaration under that name, in
  key form, read from the jar with no ranking and no fuzzy matching. One entry is the canonical key
  for `owner#name`, several are its overloads. `closest` and `reason` are null whenever `candidates`
  is filled. **Use it instead of taking `results[0]` from `/search`** when the descriptor is the only
  thing missing.
- Up to 2000 keys per call. Reads the declarations of the version's pre-remapped named jar, so
  descriptors match exactly with no remapping. `404` when the version has no such jar.
- **Use it to validate mixin/shadow targets when updating a mod**: every injected method and shadowed
  field in one request instead of many `search`/`source` calls. It checks one version; use
  `/validate` for a range. Like every `POST` endpoint it is not cached.

## Validate (mixin targets across a version range)

`POST /api/v1/validate?from={version}&to={version}&releasesOnly={bool}&includeVariants={bool}` with:

```json
{"namespace": "mojmap",
 "targets": [{"id": "dismount",
              "owner": "net/minecraft/world/entity/vehicle/DismountHelper",
              "method": "findSafeDismountLocation",
              "descriptor": "(Lnet/minecraft/world/entity/EntityType;...)Lnet/minecraft/world/phys/Vec3;",
              "at": {"value": "INVOKE",
                     "target": "net/minecraft/world/level/border/WorldBorder:isWithinBounds:(Lnet/minecraft/world/phys/AABB;)Z"}}]}
```

- One entry per target, one span per run of versions that answers alike. `from` and `to` are both
  required. At most 50 targets, and at most 60 versions counted after the filters, so
  `releasesOnly=true` is how a wide range is covered.
- `id` is the caller's own label and comes back unchanged. `descriptor` may be omitted, which follows
  every overload of the name. `at` may be omitted, which checks the signature alone.
- `at.value` is `INVOKE` or `FIELD`, because both name one instruction. `at.target` is always
  `owner:name:descriptor` in the requested namespace.
- Five statuses:
  - `ok` — the method is there, and when `at` was given so is the instruction it names. `atCount`
    says how many times, which is what `@At(ordinal = N)` numbers `0 .. atCount-1`.
  - `renamed` — the name is there under another descriptor. `closest` carries the signature the
    version has.
  - `inherited` — a supertype declares it. The call resolves at runtime, but a mixin applies to the
    class that declares the method, so the target has to name the supertype, which `closest` does.
  - `call_moved` — the method is there and the `at` instruction is not. `movedTo` names the method
    that holds the call now. **This is the check `/exists` cannot make**: every signature is intact
    and the injection point breaks in silence.
  - `missing` — neither the method nor a near declaration. `movedTo` is still set when the class
    holds the `at` call in exactly one other method, which is what a renamed method looks like from
    here.
- A call that moved into a lambda of the same method reads as `call_moved`, and `movedTo` names
  `lambda$stopSleeping$9` literally, because that is what a mixin has to target. The lambda index
  moves between versions, so such a span breaks wherever the index does.
- **`/validate` proves the targets that were sent, and says nothing about the targets that were
  not.** An all-`ok` report is not a coverage report. To check coverage, list every injection point
  the mod declares, then subtract the targets already sent. Match the two lists by
  `@At(target = ...)`, not by method or by `id`, because one method holds several injection points
  under one `id`.

## Resources (assets, datapack, registries, translations)

Optional. A deployment without an mcmeta clone answers every one of these `404 resources_disabled`.

- `GET /api/v1/resources/versions`: every version that carries resource data, newest first. Each
  entry has `ord`, `mcmetaId`, `name`, `versionId` (the MappingLens id, or null when that version is
  not indexed for mappings), `releaseType`, `releaseTime` and `branches`.
- `GET /api/v1/resources/tree?version={v}&branch={b}&path={dir}`: one directory level. An empty
  `path` means the root of the branch.
- `GET /api/v1/resources/file?version={v}&branch={b}&path={file}`: the raw bytes, with a
  `Content-Type` from the extension and the git blob id as `ETag`. Two versions that share content
  share an ETag, which is how to tell "unchanged" without reading twice.
- `GET /api/v1/resources/diff?from={v}&to={v}&branch={b}&path={dir}`: every path that differs, each
  with `changeType` (`added`, `removed`, `modified`) and the blob id on both sides.
- `GET /api/v1/resources/history?branch={b}&path={file}`: one entry per content the path held, oldest
  first, each with `fromVersion`, `toVersion`, `sha` and `size`. This answers "when did this file
  change" in one call.
- `GET /api/v1/resources/search?q={text}&type={content|translation}&version={v}&limit={n}`:
  `type=content` searches the text files and reports the paths and version ranges. `type=translation`
  searches the language files and reports `key`, the language file and the range.
- `version` accepts an mcmeta id (`26.3-snapshot-9`), an mcmeta display name (`26.3 Snapshot 9`) or a
  MappingLens version id (`1.21.4`).
- `branch` is `assets`, `diff`, `registries` or `atlas`, and defaults to `assets`:
  - `assets`: everything from the client jar. Textures, models, blockstates, sounds, all 145 language
    files.
  - `diff`: the integrated datapack with structures as readable SNBT, plus the condensed reports
    (blocks, commands, items, registries).
  - `registries`: one file per registry key.
  - `atlas`: the stitched texture atlases.
- Each term of `q` is matched as a phrase, so `minecraft:copper_golem` is a query and not an FTS5
  column filter. Several terms mean every one of them. There is no prefix or wildcard syntax.
- The server computes no textual diff over resources. Read `/file` at both versions and diff locally.
- mcmeta covers 1.14 onward and leaves out the April Fools, combat and experimental versions, so a
  version MappingLens indexes may still carry no resource data. `/tree` then answers
  `404 version_not_found`, which is a different code from `resources_disabled`.

## Meta / health

`GET /health` returns `ok` and is not rate-limited. `GET /openapi.yaml`, `GET /openapi.json`, and
`GET /docs` (Swagger UI).
