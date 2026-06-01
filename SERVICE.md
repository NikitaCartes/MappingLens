# MappingLens — Описание сервиса

## Что делает

MappingLens — read-only REST API для поиска, перевода и инспекции маппингов Minecraft (Yarn, Mojmap, Intermediary, Obfuscated).  
Позволяет переводить имена классов/методов/полей между неймспейсами, искать по FTS, сравнивать изменения между версиями и просматривать декомпилированные исходники/байткод.

---

## Папки и источники данных

| Путь (по умолчанию) | Переменная окружения | Назначение |
|---|---|---|
| `data/mappinglens.db` | `MAPPINGLENS_DB_PATH` | SQLite база — хранит все проиндексированные маппинги, FTS-индексы, метаданные версий |
| `data/artifact-store` | `MAPPINGLENS_ARTIFACT_STORE` | Основной источник: Mojmap tiny-файлы (`mappings/{ver}-client-moj.tiny`, `{ver}-server-moj.tiny`), декомпилированные jar (`decompiled/{ver}/merged-map_{yarn|mojmap}-*.jar`) |
| `data/yarn` | `MAPPINGLENS_YARN_REPO` | Read-only Git-репозиторий Yarn (корень, например `.../yarn`; версии читаются из Git refs/tags как `{version}:minecraft/src`, без подпапок по версиям) |
| `data/mojmap` | `MAPPINGLENS_MOJMAP_REPO` | Read-only Git-репозиторий Mojmap (корень, например `.../mojmap`; версии читаются из Git refs/tags как `{version}:minecraft/src`, без подпапок по версиям) |
| `data/intermediary` | `MAPPINGLENS_INTERMEDIARY` | Репозиторий Intermediary-маппингов |

### Что откуда берётся

- **Маппинги** (tiny v2) → парсятся `TinyV2Parser`, индексируются в SQLite
- **Декомпилированные исходники** → из `artifact-store/decompiled/` jar-файлов, отдаются через `/api/v1/source`
- **Байткод** → из оригинальных jar Minecraft, дизассемблируется ASM Textifier на лету
- **Версии** → обнаруживаются `VersionDiscovery` из файлов в source-папках

---

## Эндпоинты

### 1. Versions — Список проиндексированных версий

#### `GET /api/v1/versions`

Возвращает все проиндексированные версии с количеством классов/методов/полей.

```
GET http://localhost:8080/api/v1/versions
```

Ответ:
```json
{
  "versions": [
    {
      "id": "1.21.1",
      "releaseType": "release",
      "hasYarn": true,
      "hasMojmap": true,
      "hasIntermediary": true,
      "classCount": 7842,
      "methodCount": 52341,
      "fieldCount": 31205,
      "indexedAt": "2025-01-15T12:00:00Z"
    }
  ]
}
```

#### `GET /api/v1/versions/{version}`

Метаданные одной версии.

```
GET http://localhost:8080/api/v1/versions/1.21.1
```

---

### 2. Search — Поиск по маппингам (FTS)

#### `GET /api/v1/search`

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `q` | да | — | Поисковый запрос (имя, часть имени, `Owner#member`) |
| `version` | нет | последний release | Версия Minecraft |
| `type` | нет | `all` | `class`, `method`, `field`, `all` |
| `namespace` | нет | `all` | `yarn`, `mojmap`, `intermediary`, `all` |
| `limit` | нет | 50 | 1–200 |
| `offset` | нет | 0 | Пагинация |
| `exact` | нет | false | Точное совпадение |

```
GET http://localhost:8080/api/v1/search?q=BlockState&type=class&namespace=yarn
```

Ответ:
```json
{
  "query": "BlockState",
  "version": "1.21.1",
  "totalResults": 3,
  "results": [
    {
      "type": "class",
      "yarn": "net/minecraft/block/BlockState",
      "mojmap": "net/minecraft/world/level/block/state/BlockState",
      "intermediary": "net/minecraft/class_2680",
      "obfuscated": "dtt",
      "owner": null,
      "descriptor": null,
      "score": 12.5
    }
  ]
}
```

Пример поиска метода:
```
GET http://localhost:8080/api/v1/search?q=getDefaultState&type=method
```

Пример поиска `Owner#member`:
```
GET http://localhost:8080/api/v1/search?q=Block%23getDefaultState
```

---

### 3. Translate — Перевод имени между неймспейсами

#### `GET /api/v1/translate`

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `name` | да | — | Имя для перевода |
| `from` | да | — | Исходный неймспейс: `yarn`, `mojmap`, `intermediary`, `obfuscated`/`obf` |
| `to` | да | — | Целевой неймспейс |
| `version` | нет | последний | Версия |
| `type` | нет | `auto` | `class`, `method`, `field`, `auto` |

```
GET http://localhost:8080/api/v1/translate?name=net/minecraft/block/Block&from=yarn&to=mojmap&version=1.21.1
```

Ответ:
```json
{
  "input": { "name": "net/minecraft/block/Block", "namespace": "yarn" },
  "output": { "name": "net/minecraft/world/level/block/Block", "namespace": "mojmap" },
  "intermediary": "net/minecraft/class_2248",
  "obfuscated": "cmt",
  "version": "1.21.1",
  "type": "class"
}
```

#### `GET /api/v1/translate/class/{name}`

Шорткат для перевода классов с path-параметром (слэши в имени — часть пути):

```
GET http://localhost:8080/api/v1/translate/class/net/minecraft/block/Block?from=yarn&to=mojmap
```

---

### 4. Diff — Сравнение маппингов между версиями

#### `GET /api/v1/diff`

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `from` | да | — | Версия "до" |
| `to` | да | — | Версия "после" |
| `namespace` | нет | `yarn` | `yarn`, `mojmap`, `intermediary` |
| `type` | нет | `all` | `class`, `method`, `field`, `all` |
| `package` | нет | — | Фильтр по пакету (например `net/minecraft/block`) |
| `changeType` | нет | `all` | `added`, `removed`, `renamed`, `all` |
| `limit` | нет | 100 | 1–5000 |

```
GET http://localhost:8080/api/v1/diff?from=1.20.4&to=1.21.1&namespace=yarn&type=class&changeType=renamed
```

Ответ:
```json
{
  "from": "1.20.4",
  "to": "1.21.1",
  "namespace": "yarn",
  "changes": {
    "added": [],
    "removed": [],
    "renamed": [
      { "type": "class", "intermediary": "net/minecraft/class_1234", "oldName": "OldClassName", "newName": "NewClassName" }
    ]
  },
  "summary": {
    "classesAdded": 15,
    "classesRemoved": 3,
    "classesRenamed": 7,
    "methodsAdded": 120,
    "methodsRemoved": 45,
    "methodsRenamed": 30,
    "fieldsAdded": 80,
    "fieldsRemoved": 20,
    "fieldsRenamed": 12
  }
}
```

#### `GET /api/v1/diff/files`

Сравнение исходных файлов (добавленные/удалённые/изменённые файлы).

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `from` | да | — | Версия "до" |
| `to` | да | — | Версия "после" |
| `namespace` | нет | `yarn` | `yarn`, `mojmap` |
| `path` | нет | — | Фильтр по пути (префикс) |

```
GET http://localhost:8080/api/v1/diff/files?from=1.20.4&to=1.21.1&namespace=yarn&path=net/minecraft/block
```

Ответ:
```json
{
  "from": "1.20.4",
  "to": "1.21.1",
  "namespace": "yarn",
  "files": {
    "added": ["net/minecraft/block/NewBlock.java"],
    "removed": ["net/minecraft/block/OldBlock.java"],
    "modified": [
      { "path": "net/minecraft/block/Block.java", "methodsAdded": 2, "methodsRemoved": 1, "fieldsAdded": 0, "fieldsRemoved": 0 }
    ]
  }
}
```

#### `GET /api/v1/diff/patch`

Возвращает реальную разницу между исходными файлами двух версий в git-like unified diff формате.

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `from` | да | — | Версия "до" |
| `to` | да | — | Версия "после" |
| `namespace` | нет | `yarn` | `yarn`, `mojmap` |
| `path` / `file` | нет | — | Фильтр по папке или файлу, например `net/minecraft/block/Block.java` |
| `function` | нет | — | Фильтр по функции/методу, например `getDefaultState` или `Block#getDefaultState` |
| `context` | нет | 3 | Количество context-строк в hunk (0–20) |
| `limit` | нет | 5000 | Максимум файлов в patch |
| `format` | нет | `patch` | `patch`/`git` для raw diff, `json` для JSON с метаданными и строкой `patch` |

```
GET http://localhost:8080/api/v1/diff/patch?from=1.21&to=1.21.1&namespace=yarn&path=net/minecraft/block&function=getDefaultState
```

Ответ (`text/x-diff`):
```diff
diff --git a/net/minecraft/block/Block.java b/net/minecraft/block/Block.java
--- a/net/minecraft/block/Block.java
+++ b/net/minecraft/block/Block.java
@@ -120,7 +120,7 @@
 public BlockState getDefaultState() {
-    return oldState;
+    return newState;
 }
```

Также `/api/v1/diff/files?format=patch` возвращает такой же raw patch, сохраняя совместимость с endpoint списка файлов.

---

### 5. Source — Декомпилированные исходники

#### `GET /api/v1/source/{version}/{className}`

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `version` | да (path) | — | Версия Minecraft |
| `className` | да (path) | — | Имя класса через `/` |
| `namespace` | нет | `yarn` | `yarn`, `mojmap` |

```
GET http://localhost:8080/api/v1/source/1.21.1/net/minecraft/block/Block?namespace=yarn
```

Ответ:
```json
{
  "version": "1.21.1",
  "class": "net/minecraft/block/Block",
  "namespace": "yarn",
  "source": "package net.minecraft.block;\n\npublic class Block extends ... {\n    ...\n}",
  "path": "net/minecraft/block/Block.java"
}
```

---

### 6. Bytecode — Дизассемблированный байткод

#### `GET /api/v1/bytecode/{version}/{className}`

| Параметр | Обяз. | По умолч. | Описание |
|---|---|---|---|
| `version` | да (path) | — | Версия Minecraft |
| `className` | да (path) | — | Имя класса через `/` |
| `format` | нет | `text` | `text` (ASM Textifier) или `json` |
| `namespace` | нет | `yarn` | `yarn`, `mojmap`, `intermediary`, `obfuscated`/`obf` |

```
GET http://localhost:8080/api/v1/bytecode/1.21.1/net/minecraft/block/Block?namespace=yarn&format=text
```

Ответ:
```json
{
  "version": "1.21.1",
  "class": "net/minecraft/block/Block",
  "bytecode": "// class version 65.0 (65)\npublic class net/minecraft/block/Block ...\n  INVOKEVIRTUAL ..."
}
```

---

### 7. Docs — Документация API

| Эндпоинт | Описание |
|---|---|
| `GET /openapi.yaml` | OpenAPI 3.1 спецификация (YAML) |
| `GET /openapi.json` | OpenAPI 3.1 спецификация (JSON) |
| `GET /docs` | Swagger UI |

---

## Конфигурация (mappinglens-defaults.conf)

```hocon
ktor {
    deployment { host = "0.0.0.0", port = 8080 }
}
mappinglens {
    database.path = "data/mappinglens.db"
    sources {
        artifact-store = "data/artifact-store"
        yarn-repo = "data/yarn"
        mojmap-repo = "data/mojmap"
        intermediary-mappings = "data/intermediary"
    }
    indexing {
        poll-interval-seconds = 3600
        index-on-startup = false
    }
    search { max-results = 200, default-results = 50 }
}
```

Все пути можно переопределить через переменные окружения (`MAPPINGLENS_DB_PATH`, `MAPPINGLENS_ARTIFACT_STORE` и т.д.).
