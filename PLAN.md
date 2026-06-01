# MappingLens — Детальный план реализации

## 1. Обзор проекта

**MappingLens** — REST API сервис для поиска, индексации и сравнения маппингов Minecraft между версиями и типами маппингов (Yarn ↔ Mojmap).

**Стек:** Kotlin + Ktor + SQLite (через Exposed ORM) + mapping-io

---

## 2. Обоснование выбора Kotlin

| Критерий | Kotlin | Python |
|----------|--------|--------|
| Совместимость с mapping-io | ✅ Нативная (Java-библиотека) | ❌ Нет биндингов |
| Совместимость с Tiny v2 парсерами | ✅ FabricMC парсеры напрямую | ❌ Пришлось бы писать свои |
| Типизация маппинг-структур | ✅ Строгая, null-safety | ⚠️ Слабее |
| Экосистема Minecraft-тулинга | ✅ Вся экосистема на JVM | ❌ Ничего нет |
| Работа с JAR/ZIP | ✅ java.util.zip нативно | ⚠️ zipfile, но ASM нет |
| Дизассемблирование байт-кода | ✅ ASM/CFR/Procyon нативно | ❌ Нет аналогов |
| Корутины для IO | ✅ Kotlinx.coroutines | ✅ asyncio |

**Вердикт:** Kotlin однозначно, т.к. вся экосистема маппингов Minecraft — JVM.

---

## 3. Архитектура

```
┌─────────────────────────────────────────────────┐
│                  Ktor HTTP Server                │
│  ┌───────────┐ ┌──────────┐ ┌────────────────┐  │
│  │  Search   │ │   Diff   │ │  Translation   │  │
│  │  Routes   │ │  Routes  │ │    Routes      │  │
│  └─────┬─────┘ └────┬─────┘ └───────┬────────┘  │
│        │             │               │           │
│  ┌─────▼─────────────▼───────────────▼────────┐  │
│  │              Service Layer                  │  │
│  │  SearchService │ DiffService │ TranslateService │
│  └─────┬─────────────┬───────────────┬────────┘  │
│        │             │               │           │
│  ┌─────▼─────────────▼───────────────▼────────┐  │
│  │            Index / Repository Layer          │  │
│  │  MappingIndex │ SourceIndex │ VersionRegistry │
│  └─────┬─────────────┬───────────────┬────────┘  │
│        │             │               │           │
│  ┌─────▼─────────────▼───────────────▼────────┐  │
│  │              SQLite (Exposed ORM)           │  │
│  │  mappings │ classes │ methods │ fields │ src │  │
│  └─────────────────────────────────────────────┘  │
│                                                   │
│  ┌─────────────────────────────────────────────┐  │
│  │          Data Ingestion Pipeline            │  │
│  │  TinyParser │ SourceScanner │ JarAnalyzer   │  │
│  └─────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
         │              │              │
    ┌────▼────┐   ┌────▼─────┐  ┌────▼────┐
    │  Yarn   │   │  Mojmap  │  │  MC     │
    │  Repo   │   │  Repo    │  │  JARs   │
    │(readonly)│   │(readonly)│  │(readonly)│
    └─────────┘   └──────────┘  └─────────┘
```

---

## 4. Структура проекта

```
MappingLens/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── dev/mappinglens/
│       │       ├── Application.kt              # Точка входа, конфигурация Ktor
│       │       ├── config/
│       │       │   └── AppConfig.kt            # Конфигурация (пути, порт)
│       │       ├── db/
│       │       │   ├── Database.kt             # Инициализация SQLite
│       │       │   └── tables/
│       │       │       ├── ClassTable.kt
│       │       │       ├── MethodTable.kt
│       │       │       ├── FieldTable.kt
│       │       │       ├── VersionTable.kt
│       │       │       ├── MappingCorrespondenceTable.kt
│       │       │       └── SourceFileTable.kt
│       │       ├── model/
│       │       │   ├── MappingEntry.kt         # Data classes для API ответов
│       │       │   ├── SearchResult.kt
│       │       │   ├── DiffResult.kt
│       │       │   └── VersionInfo.kt
│       │       ├── ingestion/
│       │       │   ├── IngestPipeline.kt       # Оркестратор импорта
│       │       │   ├── TinyV2Parser.kt         # Парсер Tiny v2 маппингов
│       │       │   ├── SourceScanner.kt        # Сканер .java файлов
│       │       │   ├── JarAnalyzer.kt          # ASM-анализ JAR файлов
│       │       │   └── GitWatcher.kt           # Отслеживание изменений (git rev)
│       │       ├── index/
│       │       │   ├── MappingIndex.kt         # FTS5 поиск по маппингам
│       │       │   ├── SourceIndex.kt          # Индекс исходного кода
│       │       │   └── CorrespondenceResolver.kt # Yarn ↔ Mojmap через obf
│       │       ├── service/
│       │       │   ├── SearchService.kt
│       │       │   ├── DiffService.kt
│       │       │   ├── TranslationService.kt
│       │       │   └── BytecodeService.kt
│       │       └── routes/
│       │           ├── SearchRoutes.kt
│       │           ├── DiffRoutes.kt
│       │           ├── TranslationRoutes.kt
│       │           ├── VersionRoutes.kt
│       │           └── BytecodeRoutes.kt
│       └── resources/
│           ├── application.conf               # Ktor HOCON конфигурация
│           └── openapi/
│               └── mappinglens-api.yaml       # OpenAPI 3.1 спецификация
├── data/                                      # Runtime данные (gitignored)
│   └── mappinglens.db                         # SQLite база
└── PLAN.md
```

---

## 5. Зависимости (build.gradle.kts)

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.3"
}

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-openapi")

    // Database
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Mappings
    implementation("net.fabricmc:mapping-io:0.7.1")

    // Bytecode analysis (optional)
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
}
```

---

## 6. База данных (SQLite + FTS5)

### 6.1 Таблица `versions`

```sql
CREATE TABLE versions (
    id INTEGER PRIMARY KEY,
    version_id TEXT NOT NULL UNIQUE,     -- "1.21-rc1", "1.20.4"
    release_type TEXT NOT NULL,          -- "release", "snapshot", "pre-release"
    release_time TEXT,                   -- ISO-8601
    protocol_version INTEGER,
    indexed_at TEXT NOT NULL,            -- когда проиндексировали
    git_rev_yarn TEXT,                   -- git rev для определения актуальности
    git_rev_mojmap TEXT
);
```

### 6.2 Таблица `classes`

```sql
CREATE TABLE classes (
    id INTEGER PRIMARY KEY,
    version_id INTEGER NOT NULL REFERENCES versions(id),
    obf_name TEXT,                       -- "abc"
    intermediary_name TEXT,              -- "net/minecraft/class_1234"
    yarn_name TEXT,                      -- "net/minecraft/block/BlockState"
    mojmap_name TEXT,                    -- "net/minecraft/world/level/block/state/BlockState"
    package_path TEXT,                   -- "net/minecraft/block" (для фильтрации по папкам)
    UNIQUE(version_id, intermediary_name)
);
CREATE INDEX idx_classes_version ON classes(version_id);
CREATE INDEX idx_classes_obf ON classes(obf_name, version_id);
CREATE INDEX idx_classes_intermediary ON classes(intermediary_name);
```

### 6.3 Таблица `methods`

```sql
CREATE TABLE methods (
    id INTEGER PRIMARY KEY,
    class_id INTEGER NOT NULL REFERENCES classes(id),
    version_id INTEGER NOT NULL REFERENCES versions(id),
    obf_name TEXT,
    obf_desc TEXT,                       -- дескриптор в обфусцированных именах
    intermediary_name TEXT,              -- "method_12345"
    intermediary_desc TEXT,              -- дескриптор в intermediary именах
    yarn_name TEXT,                      -- "getBlockState"
    mojmap_name TEXT,                    -- "getBlockState"
    UNIQUE(version_id, class_id, intermediary_name, intermediary_desc)
);
CREATE INDEX idx_methods_version ON methods(version_id);
CREATE INDEX idx_methods_intermediary ON methods(intermediary_name);
```

### 6.4 Таблица `fields`

```sql
CREATE TABLE fields (
    id INTEGER PRIMARY KEY,
    class_id INTEGER NOT NULL REFERENCES classes(id),
    version_id INTEGER NOT NULL REFERENCES versions(id),
    obf_name TEXT,
    obf_desc TEXT,
    intermediary_name TEXT,              -- "field_12345"
    intermediary_desc TEXT,
    yarn_name TEXT,
    mojmap_name TEXT,
    UNIQUE(version_id, class_id, intermediary_name)
);
CREATE INDEX idx_fields_version ON fields(version_id);
CREATE INDEX idx_fields_intermediary ON fields(intermediary_name);
```

### 6.5 FTS5 виртуальная таблица для полнотекстового поиска

```sql
CREATE VIRTUAL TABLE search_index USING fts5(
    element_type,          -- "class", "method", "field"
    element_id,            -- rowid в соответствующей таблице
    version_id,            -- для фильтрации
    yarn_name,             -- полное имя Yarn
    mojmap_name,           -- полное имя Mojmap
    intermediary_name,     -- intermediary имя
    simple_name,           -- простое имя (без пакета/владельца)
    tokenize='unicode61 remove_diacritics 2 separators "_"'
);
```

### 6.6 Таблица `source_files` (для поиска по исходному коду)

```sql
CREATE TABLE source_files (
    id INTEGER PRIMARY KEY,
    version_id INTEGER NOT NULL REFERENCES versions(id),
    mapping_type TEXT NOT NULL,          -- "yarn" или "mojmap"
    class_id INTEGER REFERENCES classes(id),
    relative_path TEXT NOT NULL,         -- "net/minecraft/block/Block.java"
    content_hash TEXT NOT NULL,          -- SHA-256 для определения изменений
    UNIQUE(version_id, mapping_type, relative_path)
);
```

---

## 7. Ingestion Pipeline (Импорт данных)

### 7.1 Общий flow

```
1. Определить доступные версии (сканирование папок)
2. Для каждой версии:
   a. Проверить git rev (сравнить с сохранённым в versions)
   b. Если изменился или отсутствует → переиндексировать
3. Индексация одной версии:
   a. Парсинг intermediary .tiny → получаем obf ↔ intermediary
   b. Парсинг yarn .tiny → получаем intermediary ↔ yarn
   c. Парсинг mojmap .tiny → получаем obf ↔ mojmap
   d. Объединение через obf: intermediary ↔ yarn ↔ mojmap
   e. Сканирование .java файлов из repos/yarn/ и repos/mojmap/
   f. Запись в SQLite (batch insert в транзакции)
   g. Обновление FTS5 индекса
```

### 7.2 TinyV2Parser.kt

Использует `net.fabricmc.mappingio.MappingReader` из mapping-io:

```kotlin
class TinyV2Parser {
    data class ParsedMappings(
        val namespaces: List<String>,
        val classes: List<ParsedClass>
    )

    data class ParsedClass(
        val names: Map<String, String>,  // namespace → name
        val methods: List<ParsedMethod>,
        val fields: List<ParsedField>
    )

    fun parse(tinyFile: Path): ParsedMappings {
        val tree = MemoryMappingTree()
        MappingReader.read(tinyFile, tree)
        // Трансформация tree → ParsedMappings
    }
}
```

### 7.3 SourceScanner.kt

Сканирует decompiled .java файлы, индексирует пути и хеши:

```kotlin
class SourceScanner(private val repoRoot: Path) {
    fun scan(versionDir: Path): List<SourceFileInfo> {
        return Files.walk(versionDir)
            .filter { it.extension == "java" }
            .map { SourceFileInfo(
                relativePath = repoRoot.relativize(it).toString(),
                contentHash = sha256(it),
                className = extractClassName(it)
            )}
            .toList()
    }
}
```

### 7.4 GitWatcher.kt

Отслеживает, нужна ли переиндексация:

```kotlin
class GitWatcher(private val repoPath: Path) {
    fun getCurrentRev(): String {
        // git rev-parse HEAD в repoPath
        return ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoPath.toFile())
            .start().inputStream.bufferedReader().readText().trim()
    }

    fun hasChanged(storedRev: String?): Boolean {
        return storedRev == null || storedRev != getCurrentRev()
    }
}
```

### 7.5 CorrespondenceResolver.kt

Устанавливает соответствие Yarn ↔ Mojmap через obfuscated имена:

```kotlin
class CorrespondenceResolver {
    /**
     * Для данной версии, зная:
     * - intermediary.tiny: obf ↔ intermediary
     * - yarn.tiny: obf ↔ intermediary ↔ yarn (или intermediary ↔ yarn)
     * - mojmap.tiny: obf ↔ mojmap
     *
     * Join по obf_name даёт: intermediary ↔ yarn ↔ mojmap
     */
    fun resolve(
        intermediaryMappings: ParsedMappings,
        yarnMappings: ParsedMappings,
        mojmapMappings: ParsedMappings
    ): List<UnifiedClassEntry> { ... }
}
```

---

## 8. REST API Endpoints

### 8.1 Версии

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/versions` | Список всех проиндексированных версий |
| GET | `/api/v1/versions/{version}` | Информация о конкретной версии |

**Response `GET /api/v1/versions`:**
```json
{
  "versions": [
    {
      "id": "1.21",
      "releaseType": "release",
      "releaseTime": "2024-06-13T...",
      "hasYarn": true,
      "hasMojmap": true,
      "classCount": 7234,
      "methodCount": 52341,
      "fieldCount": 31205
    }
  ]
}
```

---

### 8.2 Поиск

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/search` | Поиск по маппингам (классы, методы, поля) |

**Query параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `q` | string | ✅ | Поисковый запрос |
| `version` | string | ❌ | Фильтр по версии (по умолчанию latest release) |
| `type` | string | ❌ | `class`, `method`, `field`, `all` (default: `all`) |
| `namespace` | string | ❌ | `yarn`, `mojmap`, `intermediary`, `all` (default: `all`) |
| `limit` | int | ❌ | Лимит результатов, 1-200 (default: 50) |
| `offset` | int | ❌ | Пагинация |
| `exact` | bool | ❌ | Точное совпадение vs fuzzy (default: false) |

**Response:**
```json
{
  "query": "BlockState",
  "version": "1.21",
  "totalResults": 15,
  "results": [
    {
      "type": "class",
      "intermediary": "net/minecraft/class_2680",
      "yarn": "net/minecraft/block/BlockState",
      "mojmap": "net/minecraft/world/level/block/state/BlockState",
      "obfuscated": "dtr",
      "score": 1.0
    },
    {
      "type": "method",
      "intermediary": "method_26204",
      "yarn": "net/minecraft/block/AbstractBlock#getDefaultState",
      "mojmap": "net/minecraft/world/level/block/state/BlockBehaviour#defaultBlockState",
      "obfuscated": "dsp#p",
      "owner": {
        "intermediary": "net/minecraft/class_4970",
        "yarn": "net/minecraft/block/AbstractBlock",
        "mojmap": "net/minecraft/world/level/block/state/BlockBehaviour"
      },
      "descriptor": "()Lnet/minecraft/class_2680;",
      "score": 0.85
    }
  ]
}
```

**Алгоритм поиска:**

1. **Парсинг запроса:** Разделение по `/`, `#`, `.` на owner-часть и member-часть
2. **FTS5 prefix-match:** `search_index MATCH 'blockstat*'` для быстрого поиска
3. **Ранжирование:** BM25 из FTS5 + бонус за exact match + бонус за mapped name
4. **Фильтрация:** по версии, типу, namespace

---

### 8.3 Diff между версиями

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/diff` | Diff маппингов между двумя версиями |
| GET | `/api/v1/diff/files` | Diff по файлам/папкам |

**Query параметры для `/api/v1/diff`:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `from` | string | ✅ | Исходная версия |
| `to` | string | ✅ | Целевая версия |
| `namespace` | string | ❌ | `yarn`, `mojmap` (default: `yarn`) |
| `type` | string | ❌ | `class`, `method`, `field`, `all` |
| `package` | string | ❌ | Фильтр по пакету (prefix match) |
| `limit` | int | ❌ | default: 100 |
| `changeType` | string | ❌ | `added`, `removed`, `renamed`, `all` |

**Response:**
```json
{
  "from": "1.20.4",
  "to": "1.21",
  "namespace": "yarn",
  "changes": {
    "added": [
      {
        "type": "class",
        "name": "net/minecraft/block/VaultBlock",
        "intermediary": "net/minecraft/class_9876"
      }
    ],
    "removed": [
      {
        "type": "method",
        "name": "net/minecraft/block/OldBlock#deprecatedMethod",
        "intermediary": "method_1111"
      }
    ],
    "renamed": [
      {
        "type": "method",
        "intermediary": "method_5555",
        "oldName": "getOldName",
        "newName": "getNewName",
        "owner": "net/minecraft/class_2680"
      }
    ]
  },
  "summary": {
    "classesAdded": 42,
    "classesRemoved": 3,
    "methodsAdded": 156,
    "methodsRemoved": 12,
    "methodsRenamed": 87,
    "fieldsAdded": 89,
    "fieldsRemoved": 5,
    "fieldsRenamed": 34
  }
}
```

**Query параметры для `/api/v1/diff/files`:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `from` | string | ✅ | Исходная версия |
| `to` | string | ✅ | Целевая версия |
| `namespace` | string | ❌ | `yarn`, `mojmap` |
| `path` | string | ❌ | Фильтр по пути (prefix) |

**Response:**
```json
{
  "from": "1.20.4",
  "to": "1.21",
  "files": {
    "added": ["net/minecraft/block/VaultBlock.java"],
    "removed": ["net/minecraft/block/OldBlock.java"],
    "modified": [
      {
        "path": "net/minecraft/block/Block.java",
        "changes": {
          "methodsAdded": 3,
          "methodsRemoved": 1,
          "fieldsAdded": 2
        }
      }
    ]
  }
}
```

**Алгоритм diff:**

1. Join таблицы `classes` по `intermediary_name` для обеих версий
2. `added` = есть в `to`, нет в `from` (по intermediary)
3. `removed` = есть в `from`, нет в `to`
4. `renamed` = intermediary совпадает, но yarn/mojmap name изменилось
5. Для файлов: сравнение `source_files.content_hash` между версиями

---

### 8.4 Перевод между маппингами (Yarn ↔ Mojmap)

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/translate` | Перевод имени из одного маппинга в другой |
| GET | `/api/v1/translate/class/{name}` | Перевод конкретного класса |

**Query параметры для `/api/v1/translate`:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `name` | string | ✅ | Имя для перевода |
| `from` | string | ✅ | `yarn`, `mojmap`, `intermediary` |
| `to` | string | ✅ | `yarn`, `mojmap`, `intermediary` |
| `version` | string | ❌ | Версия (default: latest) |
| `type` | string | ❌ | `class`, `method`, `field`, `auto` |

**Response:**
```json
{
  "input": {
    "name": "net/minecraft/block/BlockState",
    "namespace": "yarn"
  },
  "output": {
    "name": "net/minecraft/world/level/block/state/BlockState",
    "namespace": "mojmap"
  },
  "intermediary": "net/minecraft/class_2680",
  "obfuscated": "dtr",
  "version": "1.21",
  "type": "class"
}
```

**Алгоритм:** Lookup по `yarn_name`/`mojmap_name` в `classes`/`methods`/`fields`, возврат соответствующего столбца.

---

### 8.5 Байт-код (опционально)

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/bytecode/{version}/{className}` | Байт-код класса |

**Query параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `format` | string | ❌ | `text` (javap-like), `json` (structured) |
| `namespace` | string | ❌ | В каком маппинге показывать имена |

**Response (format=text):**
```json
{
  "version": "1.21",
  "class": "net/minecraft/block/Block",
  "bytecode": "// class version 65.0 (65)\npublic class net/minecraft/block/Block {\n  ...\n}"
}
```

**Реализация:**
1. Открыть JAR файл из `artifact-store/mc-versions/{version}/merged-*.jar`
2. Найти .class файл внутри JAR по obfuscated имени
3. Использовать ASM `ClassReader` + `Textifier` для дизассемблирования
4. Опционально: ремап имён через маппинги перед выводом

---

### 8.6 Исходный код

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/api/v1/source/{version}/{className}` | Исходный код класса |

**Query параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|:-----------:|----------|
| `namespace` | string | ❌ | `yarn`, `mojmap` (default: `yarn`) |

**Response:**
```json
{
  "version": "1.21",
  "class": "net/minecraft/block/Block",
  "namespace": "yarn",
  "source": "package net.minecraft.block;\n\nimport ...\n\npublic class Block extends AbstractBlock {\n    ...\n}",
  "path": "net/minecraft/block/Block.java"
}
```

---

### 8.7 OpenAPI / Docs

| Method | Endpoint | Описание |
|--------|----------|----------|
| GET | `/openapi.json` | OpenAPI 3.1 спецификация |
| GET | `/docs` | Swagger UI |

---

## 9. Конфигурация

**application.conf (HOCON):**

```hocon
ktor {
    deployment {
        port = 8080
    }
    application {
        modules = [dev.mappinglens.ApplicationKt.module]
    }
}

mappinglens {
    database {
        path = "data/mappinglens.db"
    }
    sources {
        # Пути к read-only данным
        yarn-repo = "/path/to/MinecraftDeobfuscated-Yarn"
        mojmap-repo = "/path/to/MinecraftDeobfuscated-Mojang"
        intermediary-mappings = "/path/to/intermediary/mappings"
        artifact-store = "/path/to/artifact-store"
    }
    indexing {
        # Интервал проверки обновлений (секунды)
        poll-interval = 3600
        # Сколько версий загружать при старте
        initial-versions = ["1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1"]
    }
    search {
        max-results = 200
        default-results = 50
    }
}
```

---

## 10. Алгоритм поиска — детальное описание

### 10.1 Стратегия

Комбинированный подход: **SQLite FTS5** для быстрого prefix/fuzzy поиска + **точные индексы** для lookup по intermediary ID.

### 10.2 Индексация имён для поиска

При ingestion каждое имя разбивается на токены:
- `net/minecraft/block/BlockState` → `["net", "minecraft", "block", "BlockState", "blockstate"]`
- CamelCase split: `BlockState` → `["Block", "State", "block", "state"]`
- Snake_case split: `field_12345` → `["field", "12345"]`

Все токены записываются в FTS5. Простое имя (без пакета) дублируется в отдельный столбец `simple_name` с повышенным весом.

### 10.3 Ранжирование результатов

```kotlin
fun calculateScore(query: String, entry: SearchEntry): Double {
    val fts5Rank = bm25Score  // из SQLite FTS5
    val exactBonus = if (entry.simpleName.equals(query, ignoreCase = true)) 2.0 else 0.0
    val prefixBonus = if (entry.simpleName.startsWith(query, ignoreCase = true)) 1.0 else 0.0
    val mappedBonus = if (entry.hasMappedName) 0.5 else 0.0  // prefer named over unmapped
    return fts5Rank + exactBonus + prefixBonus + mappedBonus
}
```

### 10.4 Поиск с owner-фильтром

Запрос `Block#getDefaultState`:
1. Разбить на owner=`Block`, member=`getDefaultState`
2. Найти классы matching `Block` (FTS5: `simple_name MATCH 'Block'`)
3. Для найденных class_id найти методы matching `getDefaultState`
4. Объединить результаты

---

## 11. Алгоритм Diff — детальное описание

### 11.1 Определение изменений между версиями

Ключ для идентификации элемента между версиями — **intermediary name** (стабильный идентификатор FabricMC).

```sql
-- Добавленные классы (есть в to, нет в from)
SELECT c2.* FROM classes c2
LEFT JOIN classes c1 ON c1.intermediary_name = c2.intermediary_name
    AND c1.version_id = :from_version_id
WHERE c2.version_id = :to_version_id AND c1.id IS NULL;

-- Переименованные методы
SELECT m1.yarn_name as old_name, m2.yarn_name as new_name, m1.intermediary_name
FROM methods m1
JOIN methods m2 ON m1.intermediary_name = m2.intermediary_name
    AND m1.intermediary_desc = m2.intermediary_desc
WHERE m1.version_id = :from_version_id
    AND m2.version_id = :to_version_id
    AND m1.yarn_name != m2.yarn_name;
```

### 11.2 Diff по файлам/папкам

Сравнивается структура `source_files`:
- Файл считается **modified**, если `content_hash` отличается
- Файл считается **added/removed** если он есть только в одной версии

---

## 12. Соответствие Yarn ↔ Mojmap

### 12.1 Механизм связи

Obfuscated имя — общий ключ между всеми маппингами одной версии:

```
Yarn:   obf "abc" → intermediary "class_1234" → yarn "BlockState"
Mojmap: obf "abc" → mojmap "BlockState"
```

Join по `obf_name` в рамках одной версии даёт полное соответствие.

### 12.2 При ingestion

```kotlin
// 1. Парсим intermediary.tiny → Map<obf, intermediary>
// 2. Парсим yarn.tiny → Map<intermediary, yarn> (или Map<obf, yarn>)
// 3. Парсим mojmap.tiny → Map<obf, mojmap>
// 4. Объединяем по obf ключу:
for ((obf, intermediary) in intermediaryMap) {
    val yarnName = yarnMap[intermediary] ?: yarnMap[obf]
    val mojmapName = mojmapMap[obf]
    // Insert unified record
}
```

---

## 13. Необходимые внешние данные

### 13.1 Маппинги (нужны для каждой версии)

| Файл | Откуда взять | Формат |
|------|-------------|--------|
| Intermediary | `intermediary/mappings/{version}.tiny` (уже есть) | Tiny v2: official ↔ intermediary |
| Yarn | Maven: `https://maven.fabricmc.net/net/fabricmc/yarn/{version}+build.N/yarn-{version}+build.N-v2.jar` (содержит `mappings/mappings.tiny`) | Tiny v2: official ↔ intermediary ↔ named |
| Mojmap | `artifact-store/mappings/{version}-*-moj.tiny` (уже есть) | Tiny v2: official ↔ named |

### 13.2 Репозитории маппингов (рекомендую подключить)

| Репозиторий | URL | Что содержит |
|-------------|-----|-------------|
| **yarn** (маппинги) | `https://github.com/FabricMC/yarn` | .mapping файлы (enigma формат), дающие intermediary → yarn |
| **intermediary** | `https://github.com/FabricMC/intermediary` | .tiny файлы для всех версий, match файлы между версиями |

**Но!** Тебе скорее всего достаточно уже имеющихся данных:
- `intermediary/mappings/*.tiny` — уже есть
- `artifact-store/mappings/*.tiny` — уже есть  
- `repos/yarn/` и `repos/mojmap/` — decompiled source

Если нужны raw yarn маппинги (не decompiled source, а mapping файлы):
```
git clone https://github.com/FabricMC/yarn.git
```

### 13.3 JAR файлы

Уже доступны в `artifact-store/mc-versions/{version}/merged-*.jar` или `client-*.jar`.

---

## 14. Pipeline индексации — пошаговый алгоритм

```
┌─────────────────────────────────────────────────┐
│ 1. DISCOVER VERSIONS                            │
│    Scan intermediary/mappings/*.tiny             │
│    → Extract version IDs                        │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ 2. CHECK STALENESS                              │
│    For each version:                            │
│    - Compare git rev with stored                │
│    - Compare file mtime/hash with stored        │
│    → Skip if up-to-date                         │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ 3. PARSE MAPPINGS                               │
│    a) intermediary.tiny → Map<obf, intermediary> │
│    b) yarn.tiny → Map<obf/interm, yarn>         │
│    c) mojmap.tiny → Map<obf, mojmap>            │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ 4. UNIFY & RESOLVE                              │
│    Join all maps by obf_name                    │
│    → UnifiedEntry(obf, interm, yarn, mojmap)    │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ 5. WRITE TO DB                                  │
│    In single transaction:                       │
│    - DELETE old data for this version            │
│    - INSERT INTO classes/methods/fields          │
│    - INSERT INTO search_index (FTS5)            │
│    - UPDATE versions metadata                   │
└──────────────────────┬──────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────┐
│ 6. INDEX SOURCE FILES                           │
│    Scan repos/yarn/ and repos/mojmap/           │
│    → Record paths and content hashes            │
└─────────────────────────────────────────────────┘
```

---

## 15. Обработка ошибок

```kotlin
// Стандартизированный формат ошибок
@Serializable
data class ApiError(
    val error: String,
    val message: String,
    val status: Int
)

// Status Pages в Ktor
install(StatusPages) {
    exception<VersionNotFoundException> { call, e ->
        call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", e.message, 404))
    }
    exception<InvalidQueryException> { call, e ->
        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", e.message, 400))
    }
    exception<Exception> { call, e ->
        logger.error("Internal error", e)
        call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Unexpected error", 500))
    }
}
```

---

## 16. Rate Limiting & CORS

```kotlin
install(RateLimit) {
    global {
        rateLimiter(limit = 100, refillPeriod = 60.seconds)
    }
}

install(CORS) {
    anyHost()  // или конкретные origins
    allowMethod(HttpMethod.Get)
    allowHeader(HttpHeaders.ContentType)
}
```

---

## 17. OpenAPI спецификация

Генерировать автоматически через Ktor OpenAPI plugin или описать вручную в YAML.

Ktor 3.x поддерживает аннотации для автогенерации:

```kotlin
// В routes:
get("/api/v1/search") {
    // Ktor-OpenAPI-Generator плагин может генерить spec из типизированных route
}
```

Альтернативно — ручной `openapi.yaml` в resources, отдавать через:
```kotlin
routing {
    openAPI(path = "openapi", swaggerFile = "openapi/mappinglens-api.yaml")
    swaggerUI(path = "docs", swaggerFile = "openapi/mappinglens-api.yaml")
}
```

---

## 18. Порядок реализации (этапы)

### Этап 1: Каркас (1-2 дня)
- [ ] Инициализация Gradle проекта
- [ ] Базовая структура пакетов
- [ ] Application.kt с Ktor
- [ ] Конфигурация (application.conf)
- [ ] SQLite подключение через Exposed
- [ ] Создание таблиц

### Этап 2: Ingestion (2-3 дня)
- [ ] TinyV2Parser (через mapping-io)
- [ ] CorrespondenceResolver (join obf)
- [ ] IngestPipeline (оркестратор)
- [ ] Batch insert в БД
- [ ] FTS5 индексация
- [ ] GitWatcher (проверка актуальности)

### Этап 3: Поиск (1-2 дня)
- [ ] SearchService (FTS5 query builder)
- [ ] Query parsing (owner/member split)
- [ ] SearchRoutes + сериализация ответа
- [ ] Фильтрация по версии/типу/namespace

### Этап 4: Diff (1-2 дня)
- [ ] DiffService (SQL joins по intermediary)
- [ ] DiffRoutes
- [ ] Файловый diff (через source_files)

### Этап 5: Translation (0.5-1 день)
- [ ] TranslationService (lookup по столбцам)
- [ ] TranslationRoutes

### Этап 6: OpenAPI + Polish (1 день)
- [ ] Написать/сгенерировать openapi.yaml
- [ ] Swagger UI endpoint
- [ ] Валидация параметров
- [ ] Логирование

### Этап 7: Bytecode (опционально, 1-2 дня)
- [ ] JarAnalyzer (ASM ClassReader)
- [ ] Ремаппинг имён в bytecode output
- [ ] BytecodeRoutes

---

## 19. Тестирование

```kotlin
// Использовать Ktor testApplication
class SearchRoutesTest {
    @Test
    fun `search by class name returns results`() = testApplication {
        application { module() }
        val response = client.get("/api/v1/search?q=BlockState&version=1.21")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<SearchResponse>()
        assertTrue(body.results.isNotEmpty())
        assertEquals("class", body.results.first().type)
    }
}
```

---

## 20. Запуск

```bash
# Первый запуск (создаст БД и проиндексирует данные)
./gradlew run

# Или собрать fat JAR
./gradlew shadowJar
java -jar build/libs/mappinglens-all.jar
```

При первом запуске pipeline автоматически обнаружит все доступные версии и проиндексирует их. Последующие запуски проверяют git rev и переиндексируют только изменённые версии.

---

## 21. Резюме архитектурных решений

| Решение | Обоснование |
|---------|-------------|
| SQLite + FTS5 | Лёгкий, embedded, FTS5 даёт prefix search из коробки, zero-config |
| mapping-io | Официальная библиотека FabricMC для парсинга всех форматов маппингов |
| Intermediary как primary key | Стабилен между версиями, используется всей экосистемой Fabric |
| Obf name как join key | Единственный общий ключ между Yarn и Mojmap в рамках одной версии |
| Ktor | Лёгкий, идиоматичный Kotlin, нативная поддержка OpenAPI/Swagger |
| Exposed ORM | Typesafe SQL для Kotlin, отличная поддержка SQLite |
| Git rev для invalidation | Не нужен polling файлов, один вызов `git rev-parse HEAD` |
