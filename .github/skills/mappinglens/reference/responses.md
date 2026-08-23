# MappingLens response shapes

One example per endpoint, trimmed to one entry per array. Read the field off the example rather than guessing at it: several responses nest the answer (`/translate` puts the result in `output.name`, not at the top level).

For parameters and behavior, see `endpoints.md`.

## `/versions` and `/versions/{version}`

```json
{"versions": [
  {"id": "1.21.11", "releaseType": "release", "releaseTime": "2025-12-09T12:23:30+00:00",
   "protocolVersion": 774, "hasYarn": true, "hasMojmap": true, "hasIntermediary": true,
   "variantOf": null, "classCount": 10291, "methodCount": 89606, "fieldCount": 45679,
   "indexedAt": "2026-08-16T23:51:04.726392807Z"}
]}
```

`/versions/{version}` returns one such object, unwrapped.

## `/classes/{version}`

```json
{"version": "1.21.11", "classes": [
  {"obfuscated": "a", "intermediary": "net/minecraft/class_7833",
   "yarn": "net/minecraft/util/math/RotationAxis", "mojmap": "com/mojang/math/Axis", "presence": "both"}
]}
```

## `/search`

```json
{"query": "Level#getRespawnData", "version": "26.1", "totalResults": 1, "results": [
  {"type": "method", "intermediary": "net/minecraft/class_1937#method_74854",
   "yarn": "net/minecraft/world/World#getSpawnPoint",
   "mojmap": "net/minecraft/world/level/Level#getRespawnData",
   "obfuscated": "net/minecraft/world/level/Level#getRespawnData",
   "owner": {"intermediary": "net/minecraft/class_1937", "yarn": "net/minecraft/world/World",
             "mojmap": "net/minecraft/world/level/Level", "obfuscated": "net/minecraft/world/level/Level"},
   "intermediaryDescriptor": "()Lnet/minecraft/class_5217$class_12064;",
   "yarnDescriptor": "()Lnet/minecraft/world/WorldProperties$SpawnPoint;",
   "mojmapDescriptor": "()Lnet/minecraft/world/level/storage/LevelData$RespawnData;", "score": 0.911,
   "synthetic": false}
]}
```

`{mojmap}:{mojmapDescriptor}` with the `#` replaced by `:` is an `/exists` key, ready as it is.

`score` runs `0` to just under `1` and higher is better, which is the order `results` is already in.

## `/translate`

```json
{"input": {"name": "net/minecraft/block/Block", "namespace": "yarn"},
 "output": {"name": "net/minecraft/world/level/block/Block", "namespace": "mojmap"},
 "intermediary": "net/minecraft/class_2248", "obfuscated": "dzq",
 "version": "1.21.11", "type": "class"}
```

The translated name is `output.name`.

## `POST /translate/{version}`

```json
{"version": "1.21.11", "from": "yarn", "to": "mojmap", "results": [
  {"key": "net/minecraft/block/Block:getDefaultState:()Lnet/minecraft/block/BlockState;",
   "translated": "net/minecraft/world/level/block/Block:defaultBlockState:()Lnet/minecraft/world/level/block/state/BlockState;",
   "type": "method", "intermediary": "method_9564"}
]}
```

## `/diff`

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

## `/diff/files`

```json
{"from": "1.21.10", "to": "1.21.11", "namespace": "mojmap",
 "files": {"added": [], "removed": [],
   "modified": [{"path": "net/minecraft/world/waypoints/Waypoint.java",
                 "methodsAdded": 0, "methodsRemoved": 0, "fieldsAdded": 0, "fieldsRemoved": 0}]}}
```

## `/diff/patch` (`format=json`)

```json
{"from": "1.21.10", "to": "1.21.11", "namespace": "mojmap",
 "path": "net/minecraft/world/waypoints/Waypoint.java", "function": null,
 "files": [{"path": "net/minecraft/world/waypoints/Waypoint.java", "changeType": "modified"}],
 "fileCount": 1, "truncated": false,
 "patch": "diff --git a/... b/...\n--- a/...\n+++ b/...\n@@ ..."}
```

`format=patch` and `format=git` return the patch string alone as `text/x-diff`.

## `/history`

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

`query` holds the string that was asked, unchanged. The name the versions of a span use is in `members[]`. Asking `ResourceKey:location` and asking `ResourceKey:identifier` therefore return the same spans, one span carrying `"mojmap": "location"` and the next `"mojmap": "identifier"`, under two different `query` values.

A member the owner inherits rather than declares comes back `present: false` with a reason:

```json
{"from": "1.21", "to": "26.2", "versions": 16, "present": false,
 "owner": "net/minecraft/server/level/ServerLevel",
 "reason": "inherited", "declaredIn": "net/minecraft/world/level/Level",
 "members": [{"intermediary": null, "yarn": null, "mojmap": "getBlockState",
              "intermediaryDescriptor": null}]}
```

The intermediary side is empty here because the tiny files name a declaration, not an override, and
`Level.getBlockState` overrides `BlockGetter.getBlockState`. That is the same reason a signature
change is sometimes invisible in `members[].intermediaryDescriptor`.

## `/bodyhash`

```json
{"namespace": "mojmap", "normalize": "named", "results": [
  {"query": "net/minecraft/world/entity/LivingEntity:baseTick:()V", "spans": [
    {"from": "1.21.5", "to": "1.21.8", "versions": 4, "hash": "d7f8e2ad7aebb3a2"},
    {"from": "1.21.9", "to": "1.21.10", "versions": 2, "hash": "391f465f330464ab"}
  ]}
]}
```

## `/compare/{version}/{className}`

```json
{"version": "1.21.11", "from": "yarn", "to": "mojmap", "obf": "dzq",
 "intermediary": "net/minecraft/class_2248",
 "yarnClass": "net/minecraft/block/Block", "mojmapClass": "net/minecraft/world/level/block/Block",
 "presence": "both",
 "members": [{"kind": "method", "obfName": "a", "obfDesc": "(D)Lfug;", "intermediary": "method_66393",
              "yarn": "createCubeShape", "mojmap": "cube", "status": "matched"}]}
```

## `/source/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/WaypointStyleAssets",
 "namespace": "mojmap", "source": "package net.minecraft.world.waypoints;\n\n...",
 "path": "net/minecraft/world/waypoints/WaypointStyleAssets.java"}
```

`format=text` returns the `.java` alone as `text/plain`.

## `/blame/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/Waypoint", "namespace": "mojmap",
 "path": "net/minecraft/world/waypoints/Waypoint.java",
 "versions": ["25w15a", "25w45a", "25w17a", "1.21.11-pre4"],
 "lines": [0, 0, 1, 2]}
```

`lines` holds one index into `versions` per source line, line 1 first.

## `/bytecode/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/WaypointStyleAssets",
 "bytecode": "// class version 65.0 (65)\npublic abstract interface ..."}
```

## `/tokens/{version}/{className}`

```json
{"version": "1.21.11", "class": "net/minecraft/world/waypoints/Waypoint", "namespace": "mojmap",
 "source": "package net.minecraft.world.waypoints;\n\n...",
 "tokens": [{"startLine": 21, "startColumn": 6, "endLine": 21, "endColumn": 15,
             "type": "field", "className": "net/minecraft/world/waypoints/Waypoint",
             "name": "MAX_RANGE", "descriptor": "I", "declaration": true}]}
```

`descriptor` here **is** in the requested namespace. `format=text` gives the same columns as TSV with a `#` header line, which is the cheapest way to read a whole class's named descriptors.

## `/hierarchy/{version}/{className}`

```json
{"version": "1.21.11", "namespace": "mojmap",
 "root": "net/minecraft/world/level/block/state/BlockState",
 "nodes": [{"name": "net/minecraft/world/level/block/state/BlockState", "simpleName": "BlockState",
            "isInterface": false, "isAbstract": false}],
 "edges": [{"parent": "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
            "child": "net/minecraft/world/level/block/state/BlockState"}]}
```

## `/references/{version}`

```json
{"namespace": "mojmap", "results": [
  {"version": "26.1", "query": "net/minecraft/server/level/ServerLevel", "references": [
    {"owner": "net/minecraft/advancements/AdvancementRewards", "ownerSimple": "AdvancementRewards",
     "member": "grant", "descriptor": "(Lnet/minecraft/server/level/ServerPlayer;)V", "kind": "method",
     "count": 2, "synthetic": null}
  ],
   "resolved": true, "candidates": [],
   "paths": [
    [{"owner": "net/minecraft/server/PlayerAdvancements", "ownerSimple": "PlayerAdvancements",
      "member": "award", "descriptor": "(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z",
      "kind": "method", "count": 1, "synthetic": null},
     {"owner": "net/minecraft/advancements/AdvancementRewards", "ownerSimple": "AdvancementRewards",
      "member": "grant", "descriptor": "(Lnet/minecraft/server/level/ServerPlayer;)V",
      "kind": "method", "count": 2, "synthetic": null}]
  ]}
]}
```

`paths` is a list of chains, and each chain is a list of the same objects that `references[]` holds,
outermost frame first. The last frame of a chain is the site that touches the target itself, so a
chain holds one frame when that site has no caller within `depth`. `paths` is `[]` unless `depth`
was above 1.

A key the index cannot resolve answers with `resolved: false` and the keys it would have matched:

```json
{"version": "26.2", "query": "net/minecraft/world/inventory/CraftingMenu:slotChangedCraftingGrid",
 "references": [], "resolved": false,
 "candidates": ["net/minecraft/world/inventory/CraftingMenu:slotChangedCraftingGrid:(Lnet/minecraft/world/inventory/AbstractContainerMenu;...)V"],
 "paths": []}
```

Only `resolved: true` with an empty `references` means nothing calls the target.

## `POST /exists/{version}`

```json
{"version": "26.1", "namespace": "mojmap", "results": [
  {"key": "net/minecraft/server/level/ServerLevel:getRespawnData:()Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
   "exists": true, "closest": null, "reason": null},
  {"key": "net/minecraft/server/level/ServerLevel:getRespawnData:()Ljava/lang/String;",
   "exists": false,
   "closest": "net/minecraft/server/level/ServerLevel:getRespawnData:()Lnet/minecraft/world/level/storage/LevelData$RespawnData;",
   "reason": "descriptor"},
  {"key": "net/minecraft/world/level/Level:getBlockState:Lnet/minecraft/world/level/block/state/BlockState;",
   "exists": false,
   "closest": "net/minecraft/world/level/Level:getBlockState:(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
   "reason": "kind"},
  {"key": "net/minecraft/world/level/Level:getBlockState",
   "exists": false, "closest": null, "reason": null,
   "candidates": ["net/minecraft/world/level/Level:getBlockState:(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"]}
]}
```

The third result asked for `getBlockState` as a field and the version declares a method. `closest` opens with `(`, and `reason` says so rather than reading as a changed signature.

The fourth carries no descriptor, so it is a resolve rather than an existence check: `candidates` holds every declaration under that name, and one entry means the key is canonical.

## `POST /validate`

```json
{"namespace": "mojmap", "results": [
  {"id": "dismount", "spans": [
    {"from": "1.21", "to": "26.2", "versions": 16, "status": "ok",
     "atCount": 1, "closest": null, "movedTo": null}]},
  {"id": "respawn", "spans": [
    {"from": "1.21", "to": "1.21.1", "versions": 2, "status": "renamed", "atCount": null,
     "closest": "net/minecraft/server/level/ServerPlayer:findRespawnPositionAndUseSpawnBlock:(ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;",
     "movedTo": null},
    {"from": "1.21.2", "to": "26.2", "versions": 14, "status": "ok",
     "atCount": null, "closest": null, "movedTo": null}]}
]}
```

A `call_moved` span looks like this, and is the reason to send `at` at all:

```json
{"from": "1.21.9", "to": "26.2", "versions": 7, "status": "call_moved", "atCount": null,
 "closest": null, "movedTo": "net/minecraft/server/level/PlayerSpawnFinder#findSpawn"}
```

`ServerPlayer#adjustSpawnLocation` still exists in all seven of those versions, so `/exists` reports
nothing, while the border call it used to make lives in another class.

## `/resources/versions`

```json
{"versions": [
  {"ord": 449, "mcmetaId": "26.3-snapshot-9", "name": "26.3 Snapshot 9", "versionId": null,
   "releaseType": "snapshot", "releaseTime": "2026-08-17T11:46:16+00:00",
   "branches": ["assets", "atlas", "diff", "registries"]}
]}
```

## `/resources/tree`

```json
{"version": "1.21.4", "branch": "assets", "path": "assets/minecraft/lang", "entries": [
  {"name": "af_za.json", "path": "assets/minecraft/lang/af_za.json", "directory": false,
   "size": 468227, "sha": "32eb2553e58cdd2210044d0631aa3ca8c8bc03f3"}
]}
```

## `/resources/diff`

```json
{"from": "1.21.3", "to": "1.21.4", "branch": "registries", "changes": [
  {"path": "block/data.json", "changeType": "modified",
   "fromSha": "ecf9b4e042a23f5cadc12bb7b3d028b87b153d15",
   "toSha": "fb48dd53b7e16ef1a0a2460f23c70234f0fcc1b3"}
]}
```

## `/resources/history`

```json
{"branch": "assets", "path": "assets/minecraft/textures/block/stone.png", "entries": [
  {"fromVersion": "1.14", "toVersion": "1.20.1",
   "sha": "2665baef2a32e0e8ae787419f3e5c87c6acc2e72", "size": 215},
  {"fromVersion": "23w31a", "toVersion": "1.21.5",
   "sha": "de078145f71d63196633e6061e7d007b5b7a79e4", "size": 157}
]}
```

The texture kept one content over 88 versions, so the whole history is three rows.

## `/resources/search`

`type=content`:

```json
{"query": "minecraft:copper_golem", "type": "content", "total": 3, "results": [
  {"branch": "assets", "path": "assets/minecraft/items/copper_golem_statue.json",
   "fromVersion": "25w36a", "toVersion": "26.1 Snapshot 10", "key": null, "value": null,
   "sha": "72eebf6fd2805f649ba7cff7d89f06b48169887b"}
]}
```

`type=translation`:

```json
{"query": "Crafting Table", "type": "translation", "total": 3, "results": [
  {"branch": "assets", "path": "assets/minecraft/lang/en_us.json",
   "fromVersion": "1.14", "toVersion": "26.3 Snapshot 9",
   "key": "block.minecraft.crafting_table", "value": "Crafting Table", "sha": null}
]}
```

## Errors

```json
{"error": "invalid_query", "message": "Missing required parameter 'q'", "status": 400}
```
