# MappingLens — План переписывания (без обратной совместимости)

> Этот документ заменяет `PLAN.md` (он описывает текущую реализацию и остаётся как исторический).
> Старый REST-контракт из `SERVICE.md` **не сохраняется** — API проектируется заново.

---

## 0. Решения, зафиксированные сразу (TL;DR)

| Решение | Выбор | Почему |
|---|---|---|
| **Язык/стек сервера** | Kotlin + Ktor (Netty), JVM 25 | Вся экосистема маппингов Minecraft — JVM (mapping-io, ASM, JGit). Доменная логика уже написана и покрыта тестами. |
| **Стиль API** | **REST + OpenAPI 3.1**, `GET /api/v1/*`, JSON | Agent-friendly (curl, машиночитаемая схема для tool-use), кэшируется по `ETag`, нативно ложится на `fetch` в UI. GraphQL/gRPC отвергнуты (см. §3). |
| **Сервер** | **Stateless, read-only** | Никакого фонового ingest, polling-а git, мутаций БД во время запросов. Любой инстанс взаимозаменяем; перезапуск теряет только тёплые кэши. |
| **Что в БД** | **Только read-only индекс маппингов**: версии + унифицированные символы (class/method/field) + FTS5 по именам | Декомпилированный исходник, байткод, токены, git-коммиты **в БД не хранятся** — читаются по требованию из read-only стора и кэшируются в памяти. |
| **Кто строит БД** | Отдельная offline-команда `mappinglens index` | Сервер БД не пишет → остаётся stateless. Индекс — такой же read-only артефакт данных, как и сам GitCraft-стор. |
| **Декомпиляция** | Сервер **не декомпилирует** | Исходник уже декомпилирован в `artifact-store/decompiled/*.jar` (читаем .java на лету). Богатая навигация (go-to-def, references, inheritance) остаётся **в браузере** (подход mcsrc). |
| **Compare Yarn↔Mojmap** | **Таблица соответствия членов** (без панелей исходника) | Текстовый diff yarn- vs mojmap-исходника бесполезен (≈100% строк «изменены»). Таблица — правдивый и дешёвый ответ. |
| **UI** | Порт mcsrc; навигация — в браузере; данные агентских фич — из API | По решению заказчика: server-side только агентские/API-фичи; in-browser декомпиляция/деобфускация — на будущее. |

---

## 1. Цель и принципы

**Что строим:** stateless REST-сервис поверх read-only данных GitCraft, отдающий маппинги Minecraft (Yarn / Mojmap / Intermediary / Obfuscated) для **агентов и локальной обработки** — поиск, перевод имён, diff между версиями, сравнение Yarn↔Mojmap, байткод и исходник класса. Плюс UI на базе mcsrc, который потребляет эти данные (а тяжёлую навигацию по коду делает в браузере, как сейчас).

**Принципы:**
1. **Stateless сервер.** Единственный писатель — offline-индексатор. Сервер открывает индекс в режиме `mode=ro`, остальные источники читает только на чтение. In-memory кэши — эфемерные, реконструируемые из источника, не являются авторитетным состоянием.
2. **Read-only источники.** `yarn/`, `mojmap/`, `intermediary/` (git-репозитории) и `artifact-store/` никогда не модифицируются (соответствует требованию immutability).
3. **Agent-first.** Любая фича доступна по API без UI. Ответы — типизированный JSON по OpenAPI-схеме.
4. **Simplicity first.** Переиспользуем корректную доменную логику; выкидываем мёртвый код и переусложнённые слои (см. §9).
5. **Декомпиляция — не задача сервера.** Для API исходник берётся готовым из `decompiled/*.jar`; для UI-навигации — браузер.

---

## 2. Архитектура

```
                 OFFLINE (запускается при обновлении данных GitCraft)
   ┌───────────────────────────────────────────────────────────────┐
   │  mappinglens index                                              │
   │   read-only GitCraft store ──► парсинг tiny (v1/v2) ──►          │
   │   CorrespondenceResolver (full outer join по obf) ──►            │
   │   read-only SQLite индекс: versions + classes/methods/fields    │
   │   + FTS5(search_index)                                          │
   └───────────────────────────────────────────────────────────────┘
                                   │ (read-only артефакт)
                                   ▼
                 ONLINE (stateless, N реплик)
   ┌───────────────────────────────────────────────────────────────┐
   │  mappinglens serve  (Ktor)                                      │
   │   ┌──────────── Routes /api/v1 (REST, OpenAPI) ──────────────┐  │
   │   │ versions │ search │ translate │ diff │ compare │ source │ bytecode │
   │   └─────────────────────────┬─────────────────────────────────┘  │
   │   ┌─────────────────────────▼─────────────────────────────────┐  │
   │   │ Services (читают индекс RO + стор RO, кэши в памяти)        │  │
   │   └───┬───────────────┬───────────────┬───────────────┬───────┘  │
   │       │ index (RO)    │ jars (decompiled / remapped / obf)        │
   │       ▼               ▼               ▼ ASM Textifier  ▼ JGit(opt)│
   └───────────────────────────────────────────────────────────────┘
        SQLite (ro)   .java из jar   remapped/obf jar   git refs (опц.)

   UI (mcsrc, отдельный фронтенд):
     • код-вью + навигация (go-to-def, references, inheritance) — В БРАУЗЕРЕ (как сейчас)
     • поиск имён / translate / diff / compare yarn↔mojmap / bytecode — вызовы /api/v1
```

**Три исполняемых артефакта:**
- `mappinglens index` — offline CLI, единственный писатель индекса.
- `mappinglens serve` — stateless HTTP-сервер.
- mcsrc-фронтенд — статика (React/Vite), деплоится отдельно; в dev проксирует `/api` на сервер.

---

## 3. API: фиксируем REST + OpenAPI

**Выбор: REST поверх HTTP, JSON, описанный OpenAPI 3.1, под префиксом `/api/v1`.**

Почему REST, а не альтернативы:
- **Агенты.** Простые `GET`-запросы тривиально вызываются из любого окружения (curl, http-клиент LLM-тула). OpenAPI-схема даёт машиночитаемое описание для tool-use/кодогенерации.
- **Кэшируемость.** Ответы по `version+class+namespace` детерминированы → `ETag`/`Cache-Control`, CDN, browser cache. Для stateless-сервера это и есть основной механизм производительности.
- **UI.** mcsrc уже работает на `fetch`; REST ложится без адаптеров.
- **GraphQL отвергнут:** усложняет кэширование (POST-запросы, один URL), требует схемного клиента у агентов, выгоды (под-выборка полей) для наших плоских ответов нет.
- **gRPC отвергнут:** бинарный протокол, не браузерный без прокси, не curl-friendly — против agent-first.

**Общие правила контракта:**
- `className` в путях — internal name через `/` (`net/minecraft/block/Block`), без `.class`/`.java`. Слэши — часть catch-all path-параметра.
- `namespace` ∈ `{yarn, mojmap, intermediary, obfuscated}` (алиас `obf`).
- Версия по умолчанию — последний **release** по semver-порядку (не лексикографически — см. §5).
- Ошибки: `400 invalid_query`, `404 not_found`, `422 namespace_unavailable` (версия не имеет запрошенного namespace).
- `/openapi.json` отдаёт **настоящий JSON** (текущая реализация отдаёт YAML под видом JSON — баг, исправить), `/openapi.yaml` — YAML, `/docs` — Swagger UI.
- `GET /health` — liveness, вне rate-limit.

Полный список эндпоинтов — §7.

---

## 4. Что хранить в БД (и что — нет)

**БД = read-only SQLite-индекс маппингов**, собираемый offline-командой `index`. Сервер открывает его `mode=ro`.

### Хранить в БД

```
versions
  canonical_id   TEXT PK   -- каноничный id (форма с пробелами, см. §5): "1.14.1 Pre-Release 1"
  display_name   TEXT      -- человекочитаемое
  release_type   TEXT      -- release|snapshot|pre_release|release_candidate (из mc-meta)
  release_time   TEXT      -- из mc-meta (сейчас всегда null — исправить)
  semver_sort    TEXT      -- из semver-cache-mojang-launcher.json (каноничный порядок)
  git_tag_yarn   TEXT?     -- маппинг canonical_id → git-тег репо yarn
  git_tag_mojmap TEXT?
  has_yarn       INT  has_mojmap INT  has_intermediary INT
  src_jar_yarn   TEXT?     -- закэшированный путь к decompiled/<v>/merged-map_yarn-*.jar
  src_jar_mojmap TEXT?
  class_count INT  method_count INT  field_count INT   -- ПЕРСИСТИМ (не считаем на каждый /versions)

classes      -- унифицированная строка, ключ — obf-имя; ДОЛЖНА включать mojmap-only классы (full outer join)
  version_id  obf_name?  intermediary_name?  yarn_name?  mojmap_name?
  package_path  simple_name  presence(both|yarn_only|mojmap_only)

methods / fields
  class_id  version_id
  obf_name?  obf_desc?  intermediary_name?  intermediary_desc?  yarn_name?  mojmap_name?  simple_name

search_index  -- FTS5(unicode61), UNINDEXED: element_type, element_id, version_id;
              -- индексируемые: yarn_name, mojmap_name, intermediary_name, obf_name, simple_name
```

Индекс мал (имена символов, не тексты) и идеален для stateless read-only сервера: один файл, мгновенный FTS, кросс-версионные запросы.

### НЕ хранить в БД (читать по требованию + эфемерный кэш в памяти)

| Данные | Источник на лету | Кэш |
|---|---|---|
| Декомпилированный исходник | entry `<name>.java` из `decompiled/<v>/merged-map_<ns>-*.jar` | OS page cache + опц. LRU открытых `ZipFile` |
| Байткод (text) | ASM `Textifier` поверх `remapped-mc/<v>/merged-remapped-map_<ns>-*.jar` (для yarn/mojmap) или `mc-versions/<v>/merged-*.jar` (obf) | LRU карты имён по версии (для obf→intermediary remap) |
| Токены/семантика для навигации | **не производятся сервером** (браузер) | — |
| Reference-индекс, inheritance | **не сервер** (браузер, mcsrc) | — |
| Git-коммиты/блобы | JGit по ref (опционально) | — |

> Прямое подтверждение от заказчика: «Кэш декомпилированных версий или коммитов не обязательно хранить в БД». Здесь он не в БД вовсе — jar-файлы GitCraft *уже являются* этим кэшем.

**Statelessness гарантируется тем, что:** сервер не открывает ни одного writable-хендла; индекс — `mode=ro`; кэши реконструируются из read-only источников; убийство/перезапуск/масштабирование на N реплик не теряет авторитетных данных.

---

## 5. Модель идентичности версий (ключевое исправление)

Одна логическая версия пишется **четырьмя** способами (подтверждено по данным):

| Источник | Пример строки |
|---|---|
| git-тег yarn/mojmap | `1.14.1_Pre-Release_1`, `1.14.3-pre1`, `26.2-pre-1` |
| папка `decompiled/` / `remapped-mc/` / `mc-versions/` | `1.14.1 Pre-Release 1` (пробелы) |
| файл `intermediary/mappings/` и `artifact-store/mappings/` | `1.14.1 Pre-Release 1-...tiny` |
| `semver-cache` (каноничный semver) | `1.14.1-rc.1` |

Из-за этого текущий код **молча проваливает все git-операции** для версий с пробелами (`git show "1.14 Pre-Release 1:..."` → fatal) и падает в jar-fallback.

**Решение — явная модель версии:**
- **Каноничный id = имя папки (форма с пробелами).** Это общий ключ для `decompiled/`, `remapped-mc/`, `mc-versions/`, `intermediary/mappings/`, `artifact-store/mappings/` и ключ в `semver-cache`.
- Хранить производные идентификаторы: `git_tag_yarn`, `git_tag_mojmap` (детерминированный трансформ + спец-случаи для веток `April-/Combat-/Experimental-Snapshots`, суффиксов `_original`/`_unobfuscated`).
- **Порядок версий** — из `semver-cache-mojang-launcher.json` (готовая semver-строка), не лексикографикой. Это же чинит `latestRelease()` (сейчас `1.9 > 1.10`).
- **release_type / release_time** — из `mc-meta/mojang-launcher/<id>_<sha>.json` (поля `type`/`releaseTime`); при нескольких sha брать по mtime.
- Перечислять версии — объединением имён папок `decompiled|remapped-mc|mc-versions` + git `tag` + `branch -a` (спец-издания живут на ветках). Дедуп по каноничному id.

---

## 6. Слой доступа к данным (read-only)

**Парсеры маппингов (три пути, не один):**
- **Yarn merged** `mappings/<v>-yarn-build.<N>.tiny` — tiny v2, namespaces `official→intermediary→named`. Глоб по максимальному `build.N`. *Гард:* если есть только `-yarn-unmerged-` (без `official`) — obf-join рушится, явно зафиксировать ошибку версии.
- **Mojmap** `mappings/<v>-client-moj.tiny` + `-server-moj.tiny` — tiny v2, `official→named`. **Слить** client+server по obf-ключу (как сейчас в `mergeMappings`).
- **Intermediary** `intermediary/mappings/<v>.tiny` — **tiny v1** (461 версия; авторитетнее, чем 83 версии в artifact-store). Нужен v1-парсер (mapping-io умеет v1).

`mapping-io` (`MemoryMappingTree`) парсит и v1, и v2 — переиспользуем тонкую обёртку `TinyV2Parser`, расширив на v1.

**Глоббинг обязателен** (имена jar содержат непредсказуемый `id_<8hex>-<8hex>`): пути к `decompiled/`, `remapped-mc/`, `mc-versions/`, yarn-build.tiny — только через glob, **никогда не конструировать**. Результаты глоба кэшировать (в индексе — колонки `src_jar_*`; в сервере — in-memory).

**Git-транспорт:** текущий shell-out в `git` CLI заменить на **JGit** (без процессов на запрос, не зависит от PATH, тестируемо) — но git нужен только для опциональных «коммит-точных» diff/истории. **Основной субстрат исходника и diff — jar-файлы artifact-store** (это обходит проблему §5 для горячего пути).

**Исключить из индексации** (мусор для наших фич): `intermediary/matches/` (provenance), `assets-*`, `patched/`, `patches/`, `runtime/`, `libraries/`, `maven-cache.json`, `extra-versions/` (пусто).

---

## 7. Эндпоинты

### Версии
- `GET /api/v1/versions` → список (id, release_type, release_time, has_yarn/mojmap/intermediary, counts). Порядок — semver.
- `GET /api/v1/versions/{version}` → метаданные одной версии (или 404).

### Поиск (приоритет — имена методов и классов)
- `GET /api/v1/search?q=&version=&type=&namespace=&limit=&offset=&exact=&fuzzy=`
  - `type` ∈ {class, method, field, all}; `namespace` ∈ {yarn, mojmap, intermediary, all}.
  - **Синтаксис запроса (заимствуем у linkie):** `Owner#member` / `Owner.member` / `Owner/member` (нормализуем `.`/`#`→`/`, сплит по последнему `/`); пакетные пути `net/minecraft/...`; вложенные `Outer$Inner`; прямой поиск intermediary (`field_9360`, даже `9360`); `*`.
  - **Две фазы:** exact (FTS5 prefix/`MATCH`), при пустом результате — fuzzy (нормализованный Левенштейн), флаг `fuzzy:true` в ответе.
  - **Ранжирование:** слот mapped > intermediary > obf; класс `^0.9`, родитель члена `^0.6`; `bm25` как база. (Тонко — это улучшение; для v1 достаточно FTS prefix + сплит owner#member.)
  - **Параметризованный SQL** (текущая ручная подстановка `?` и `quote()` — выпиливается).

### Перевод
- `GET /api/v1/translate?name=&from=&to=&version=&type=` — class/method/field/auto.
- `GET /api/v1/translate/class/{name...}?from=&to=` — шорткат.
- `422 namespace_unavailable`, если версия не имеет `from`/`to` (напр. yarn на 26.x).

### Diff между версиями
- `GET /api/v1/diff?from=&to=&namespace=&type=&package=&changeType=&limit=` — diff маппингов (added/removed/renamed + summary). Ключ стабильной идентичности: `intermediary_name`, иначе `mojmap_name` (для 26.x unobfuscated).
- `GET /api/v1/diff/files?from=&to=&namespace=&path=&format=` — список изменённых файлов.
- `GET /api/v1/diff/patch?from=&to=&namespace=&path=&file=&function=&context=&format=` — unified diff.
  - **Детекция изменений — по хэшам entry декомпилированных jar** (а не git — обходит §5). Движок LCS/hunk/`function`-slice из `DiffService` переиспользуем как есть (лучший код в репо), вынеся из сырого SQL.

### Compare Yarn ↔ Mojmap (НОВОЕ) — см. §8
- `GET /api/v1/compare/{version}/{className...}` → таблица соответствия членов.

### Source (для агентов/API)
- `GET /api/v1/source/{version}/{className...}?namespace=` → `{version, class, namespace, source, path}`.
  - Читает `<name>.java` из `decompiled/<v>/merged-map_<ns>-*.jar`. Без токенов (токены — браузер).

### Bytecode
- `GET /api/v1/bytecode/{version}/{className...}?namespace=&format=` → `{version, class, namespace, bytecode}` (`format` text|json — реально учитывать, сейчас игнорируется).
  - yarn/mojmap → `remapped-mc/<v>/merged-remapped-map_<ns>-*.jar` (быстрый путь, без БД).
  - obfuscated → `mc-versions/<v>/merged-*.jar`.
  - intermediary → remap obf-jar на лету (`ClassRemapper` + карты имён из индекса, **кэшировать карты по версии** — сейчас грузятся на каждый запрос).

### Документация
- `GET /openapi.json` (JSON), `GET /openapi.yaml`, `GET /docs` (Swagger UI).

---

## 8. Compare Yarn ↔ Mojmap (таблица членов)

**Ключ выравнивания — obfuscated-имя** (`official`), присутствует в **обоих** источниках (col 0 yarn merged tiny и mojmap tiny). Для членов — `(obfName, obfDesc)`.

**Почему descriptor обязателен (подтверждено):** в классе obf `enq` (1.16.5) восемь методов с obf-именем `a`, различаются только дескриптором; yarn даёт двум разным перегрузкам одно имя `loadStatic`, mojmap — разные (`getCompleteBuffer`/`preload`). Join только по имени был бы катастрофически неверен. Внутри одной версии descriptor'ы обоих файлов — **byte-identical** (оба описывают один obf-jar), поэтому remap дескрипторов для join не нужен.

**Что чинить в ingest:** `CorrespondenceResolver` сейчас — **left join от yarn** → mojmap-only классы (5747 vs 5445 в 1.16.5) теряются. Сделать **full outer join по obf** (итерировать объединение obf-ключей yarn ∪ intermediary ∪ mojmap), проставлять `presence ∈ {both, yarn_only, mojmap_only}`.

**Эндпоинт:**
```
GET /api/v1/compare/{version}/{className...}?from=yarn&to=mojmap
```
```jsonc
{
  "version": "1.16.5",
  "obf": "aok", "intermediary": "net/minecraft/class_1259",
  "yarnClass": "net/minecraft/entity/boss/BossBar",
  "mojmapClass": "net/minecraft/world/BossEvent",
  "presence": "both",
  "members": [
    { "kind": "method", "obfName": "a", "obfDesc": "(F)V",
      "intermediary": "method_5408", "yarn": "setPercent", "mojmap": "setProgress",
      "status": "matched" },          // status: matched|yarnOnly|mojmapOnly|unmappedYarn|synthetic
    ...
  ]
}
```
- `members` — проекция строк `methods`+`fields` по `class_id` (obf-join уже сделан в индексе). `status` выводится на лету: `^(method_|field_)\d+$` → `unmappedYarn`; `lambda$...` → `synthetic`; `<init>/<clinit>` — группировать отдельно.
- **Без панелей исходника, без текстового diff** (решение заказчика). Данные — чистая проекция индекса, исходник не читается.
- **Coverage / 422:** 50 версий yarn-only (до 1.14.4), 57 mojmap-only (26.x, `_unobfuscated`), ~396 — обе. Если версия не имеет mojmap — `has_mojmap=false`, отдаём yarn-only представление (или `422` если запрошен отсутствующий `from/to`).

Схема БД менять **не нужно** (кроме full-outer-join при индексации и опц. колонки `presence`).

---

## 9. Что переиспользуем / что переделываем

### Переносим как есть (корректно, покрыто тестами)
- `TinyV2Parser` (расширить на tiny v1), `CorrespondenceResolver` (алгоритм obf-join; **исправить на full outer join**), `UnobfuscatedJarScanner` (для 26.x), `JarAnalyzer` (ASM Textifier), `Util` (`Hashing`, `Names`).
- **Движок diff/patch** в `DiffService` (LCS, trim, hunk-формат, `function`-slice, `MAX_LCS_CELLS`) — лучший код, вынести из сырого SQL.
- DTO-слой и контрактные realdata-тесты — это спецификация поведения (см. §12).

### Переделываем (слабые места)
- **Stateless:** убрать `IngestPipeline` из сервера, `GitWatcher`/`pollIntervalSeconds`, `index-on-startup` мутации → вынести в offline `index`.
- **Версии:** ввести модель идентичности (§5); `latestRelease()` по semver; персистить counts.
- **Сырой SQL с ручным экранированием** (`SearchService.execRaw`, FTS-инсерты `quote()`, весь `DiffService` class/member diff) → параметризованные запросы / типизированный слой.
- **Git-транспорт:** CLI `ProcessBuilder` → JGit (или вовсе jar-only для горячего пути).
- **PRAGMA** (`foreign_keys`/`temp_store` ставятся на throwaway-соединении и не применяются к рабочим) → через JDBC URL пула.
- **Кэши:** карты имён байткода (грузятся на каждый запрос), глоб путей.
- **Выкинуть:** `exposed-dao` (не используется), мёртвые `release_time`/`protocol_version` (теперь заполняем), `bytecode?format` без эффекта, `scoreFromBm25` игнор аргументов, `/openapi.json` отдающий YAML.

---

## 10. UI (mcsrc)

**Решение заказчика:** богатая навигация (go-to-def, hover, find-all-references, inheritance tree/graph, structure) — **UI-only, остаётся в браузере** по текущей модели mcsrc (декомпиляция + анализ в воркерах). Server-side — только агентские/API-фичи. In-browser декомпиляция/деобфускация для UI — **на будущее, низкий приоритет**.

**Что делает UI сейчас (оставляем):** скачивает vanilla-jar, декомпилирует в браузере (`@run-slicer/vf`), индексирует (`jar-index` TeaVM), строит токены/references/inheritance — всё клиентское. State/Settings/Permalink/Tabs/Code.tsx/DiffView — чистый UI, переиспользуется.

**Что добавляем к UI (вызовы `/api/v1`):**
- **Compare Yarn↔Mojmap** (требование #3) — новая вьюха, дёргает `GET /api/v1/compare/...`, рендерит таблицу членов (obf | intermediary | yarn | mojmap | status). Это данные, не декомпиляция → чисто API.
- Опционально: поиск по маппингам (`/search`), translate (`/translate`), diff маппингов (`/diff`), bytecode (`/bytecode`) — как дополнение к клиентским аналогам.

**Дев-интеграция:** Vite proxy `/api` → сервер (по аналогии с текущим `/v1`→8080 для javadoc-бэка). Прод: фронтенд — статика (как сейчас, Cloudflare SPA); сервер отдаёт только `/api/v1`.

**Будущее (не сейчас):** деобфускация/декомпиляция в браузере для версий без готового jar; переход части кода-вью на server-source; починка бага префикса reference (`s:` в Java-индексере vs `c:` в TS).

> Поскольку сервер stateless и не отдаёт токены, **семантическую навигацию UI продолжает считать в браузере** — это сознательный компромисс, а не упущение.

---

## 11. Структура проекта

```
MappingLens/
├─ settings.gradle.kts           # модули: :core, :server, :indexer (или единый + CLI-подкоманды)
├─ core/    (доменная логика, без Ktor/SQLite-инфры)
│   ingestion/  TinyParser(v1+v2), CorrespondenceResolver(full-outer), UnobfuscatedJarScanner
│   bytecode/   JarAnalyzer (ASM Textifier + Remapper)
│   diff/       LineDiff/Patch-движок
│   model/      VersionId, UnifiedClassEntry, DTOs
│   data/       GitCraftStore (glob, parsers), VersionIdentity (§5), JGit (opt)
├─ indexer/  CLI `mappinglens index` → пишет read-only SQLite (единственный писатель)
├─ server/   CLI `mappinglens serve` → Ktor, открывает индекс mode=ro
│   routes/  versions, search, translate, diff, compare, source, bytecode, docs
│   service/ *Service (читают индекс RO + стор RO, in-memory LRU)
│   resources/openapi/mappinglens-api.yaml
└─ frontend/ (или отдельный репозиторий mcsrc) — порт mcsrc + Compare-вьюха
```

Зависимости (server/core): Kotlin 2.2, Ktor 3.2 (Netty, content-negotiation, status-pages, cors, rate-limit, swagger), Exposed 0.56 (**только** core+jdbc, без dao) + sqlite-jdbc, `mapping-io` 0.7, ASM 9.10, JGit (вместо CLI), kotlinx-serialization/coroutines, logback.

---

## 12. Тесты

- **Перенести realdata-контрактные тесты первыми** — они кодируют спецификацию и сверяются с независимо выведенной «истиной» (obf-join; version-scoped search без утечек; diff summary == независимый rename-набор; file-diff == хэш-скан jar; bytecode == byte-exact ASM; source == точные байты entry; 422 для недоступных namespace; merge client+server mojmap; unobfuscated-путь). Делаем так, чтобы переписанный код их проходил.
- **Новое:** контракт `compare` (full-outer-join даёт mojmap-only классы; overload `enq`→8 методов различаются по desc; status-лейблы).
- **Заимствовать кейсы запросов linkie** (`LinkieTest`) как acceptance-набор для синтаксиса поиска.
- **Stateless-инвариант:** тест, что `serve` не открывает writable-хендлов (индекс `mode=ro`); индекс собирается отдельной командой.

---

## 13. Дорожная карта по фазам

1. **Foundations.** Модель идентичности версий (§5); `GitCraftStore` (glob+parsers v1/v2+moj-merge); `CorrespondenceResolver` → full outer join. Перенос core-логики + контрактных тестов.
2. **Indexer.** `mappinglens index` строит read-only SQLite (versions + symbols + FTS, counts). Idempotent, перезапускаемый.
3. **Stateless server — агентский API.** versions, search, translate, diff (+files/patch), bytecode, source, **compare**. OpenAPI + Swagger. Кэши в памяти. ← основной deliverable для агентов/локальной обработки.
4. **UI.** Порт mcsrc; **Compare-вьюха** на `/api/v1/compare`; опц. интеграция search/translate/diff/bytecode. Навигация — в браузере (без изменений).
5. **Future (низкий приоритет).** In-browser деобфускация/декомпиляция; коммит-точные diff через JGit; улучшение ранжирования поиска.

---

## 14. Риски и открытые вопросы

- **Регенерация индекса.** Сервер stateless → при обновлении GitCraft-данных оператор перезапускает `index`. (Прагматичный опциональный тумблер: авто-сборка индекса при старте, если файла нет — но это нарушает чистый stateless; по умолчанию выключено.)
- **Coverage-разрывы Compare** (50 yarn-only / 57 mojmap-only) — UI должен дизейблить/деградировать действие по `has_*` флагам.
- **Tiny unmerged без obf-колонки** — явный гард при индексации.
- **Совместимость декомпилятора (на будущее).** Если когда-нибудь захотим server-source с токенами — придётся пинить Vineflower к версии GitCraft, иначе текст разъедется. Сейчас неактуально (сервер не декомпилирует).
- **Где живёт фронтенд** — модуль в этом репо или отдельный (mcsrc). Предполагается отдельный; уточнить при старте фазы 4.
```
