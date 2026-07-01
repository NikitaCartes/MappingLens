# MappingLens — Описание сервиса

MappingLens — **stateless read-only** сервис для поиска, перевода, сравнения и инспекции
маппингов Minecraft (Yarn / Mojmap / Intermediary / Obfuscated). Состоит из трёх артефактов:

- **`index`** — offline-индексатор; единственный писатель SQLite-индекса.
- **`serve`** — stateless HTTP-сервер (Ktor/Netty), открывает индекс только на чтение.
- **`frontend/`** — браузерный explorer маппингов (React + Vite), потребляет REST API.

Сервер ничего не декомпилирует и не мутирует: исходники/байткод читаются по требованию из
read-only GitCraft-стора, индекс открывается с `PRAGMA query_only=ON`.

---

## Доступные фичи

| Фича | Описание |
|---|---|
| Поиск по маппингам | FTS5-поиск классов/методов/полей в одной версии; каждая строка результата — имя во всех неймспейсах сразу |
| Перевод имён | Перевод имени класса/метода/поля между неймспейсами (`from`→`to`), с авто-определением типа |
| Diff символов | Added / removed / renamed классов, методов и полей между двумя версиями + сводка |
| Diff исходников | Список изменённых файлов и unified/git-патч исходников между версиями (фильтр по пути/функции) |
| Compare Yarn↔Mojmap | Таблица соответствия членов одного класса между Yarn и Mojmap |
| Байткод | Дизассемблированный байткод класса (ASM Textifier) в любом неймспейсе, text или JSON |
| Исходники | Декомпилированный `.java` класса из artifact-store (namespace yarn/mojmap) |
| Иерархия наследования | Супертипы + подтипы класса (ASM-скан named-jar'а), для right-click «View Inheritance» в UI |
| Find All References | Обратный индекс использований класса/метода/поля (on-demand ASM-скан named-jar'а, кэш per version/namespace) |
| Токены исходника | Резолв каждого идентификатора `.java` в owner/name/descriptor (JavaParser symbol-solver) → `{source, tokens}`; питает member-level right-click (Copy AW/AT/Mixin) |
| Версии | Список проиндексированных версий с флагами доступности неймспейсов и counts, порядок — semver (новые сверху) |
| OpenAPI / Swagger | Машиночитаемая спецификация (`/openapi.json`, `/openapi.yaml`) + Swagger UI (`/docs`) |
| Frontend explorer | Браузерный UI: выбор версии, фильтры неймспейса/типа, debounced-поиск, кросс-неймспейс карточки с click-to-copy |

---

## Требования

- **JDK 21** (Gradle toolchain `languageVersion 21`, `jvmTarget JVM_21`). Стек: Kotlin 2.2.20, Ktor 3.2.3, Exposed 0.56, sqlite-jdbc.
- **Node.js** (для frontend; у пользователя установлен в `C:\MyPrograms\Node`).
- **Данные на диске:**
  - Для `index` — источники: `artifact-store` (с подпапками `mappings/`, `mc-versions/`, `decompiled/<ver>/`, `remapped-mc/<ver>/`), `yarn`, `mojmap`, `intermediary`.
  - Для `serve` — **готовый индекс** по пути `database.path` (иначе сервер падает на старте с подсказкой запустить `index`). Источники репозиториев на старте не нужны, но `diff`/`bytecode`/`source` лениво читают jar'ы из `artifact-store` во время запроса.
  - Для `/openapi.*` и `/docs` — ресурс `openapi/mappinglens-api.yaml` на classpath (поставляется в jar).

> ⚠️ **Совместимость схемы.** Индекс, собранный до переписывания, не содержит колонок `versions.sort_index` / `classes.presence`, и `serve` на нём вернёт 500 `no such column: versions.sort_index` — лечится пересборкой через `index`.
>
> Индекс, собранный до оптимизации поиска/diff, не содержит колонок `versions.fts_min_rowid` / `versions.fts_max_rowid` и составного индекса `classes(version_id, intermediary_name)`. Их добавляет **`index`** при сборке; для уже существующего большого индекса есть быстрая миграция **без переразбора исходников** (всё выводится из уже записанных строк):
> ```sh
> sqlite3 data/mappinglens.db < dev/migrate-search-perf.sql   # ~1.5 мин на полном индексе
> ```
> Пока `fts_*_rowid` пусты (старый индекс, миграция не запускалась), поиск автоматически откатывается на прежний медленный путь — результаты те же.

---

## Конфигурация

HOCON-файл `application.conf` (создаётся из встроенного шаблона при первом запуске, если отсутствует).
Все пути переопределяются переменными окружения.

| Ключ | По умолчанию | Env / CLI | Назначение |
|---|---|---|---|
| `ktor.deployment.host` | `0.0.0.0` | `HOST`, `-host` | Адрес привязки |
| `ktor.deployment.port` | `8080` | `PORT`, `-port` | Порт |
| `mappinglens.database.path` | `data/mappinglens.db` | `MAPPINGLENS_DB_PATH` | SQLite-индекс (serve — RO, index — пишет) |
| `mappinglens.sources.artifact-store` | `data/artifact-store` | `MAPPINGLENS_ARTIFACT_STORE` | Корень стора (mappings/mc-versions/decompiled/remapped-mc) |
| `mappinglens.sources.yarn-repo` | `data/yarn` | `MAPPINGLENS_YARN_REPO` | Источник Yarn (только индексация) |
| `mappinglens.sources.mojmap-repo` | `data/mojmap` | `MAPPINGLENS_MOJMAP_REPO` | Источник Mojmap (только индексация) |
| `mappinglens.sources.intermediary-mappings` | `data/intermediary` | `MAPPINGLENS_INTERMEDIARY` | Источник Intermediary (только индексация) |
| `mappinglens.search.max-results` | `200` | — | Верхняя граница `limit` для поиска |
| `mappinglens.search.default-results` | `50` | — | `limit` по умолчанию |
| `mappinglens.indexing.poll-interval-seconds` | `3600` | — | **Игнорируется `serve`** (наследие, см. ниже) |
| `mappinglens.indexing.initial-versions` | `[]` (все) | — | **Игнорируется `serve`** |
| `mappinglens.indexing.index-on-startup` | `false` | — | **Игнорируется `serve`** — индексация только через команду `index` |

**Плагины Ktor:** ContentNegotiation (kotlinx JSON, `prettyPrint`, `encodeDefaults`, `ignoreUnknownKeys`),
CallLogging, CORS (`anyHost`, метод GET, заголовок `Content-Type`), RateLimit (200 запросов / 60 с — только
на группы `/api/v1`), StatusPages (`IllegalArgumentException`→`400 invalid_query`, прочее→`500 internal_error`).

---

## Запуск

```sh
# 1. Собрать read-only индекс (единственный писатель БД)
./gradlew run --args="index"

# 2. Запустить сервер (stateless, RO). serve — команда по умолчанию,
#    если первый аргумент отсутствует или начинается с '-'.
./gradlew run --args="serve"            # слушает :8080

# 3. Frontend (dev): Vite проксирует /api -> http://localhost:8080
cd frontend
npm install
npm run dev                              # http://localhost:5173
npm run build                            # статика в dist/ (tsc + vite)
```

`main()` берёт `args[0]` как подкоманду только если она не начинается с `-`; известные команды —
`index` (пишет БД) и `serve` (RO). Любой другой токен печатает usage и выходит с кодом 2.

Прод-вариант frontend: задеплоить `dist/` как статику и проксировать `/api` на работающий `serve`.

---

## Эндпоинты

Базовый префикс — `/api/v1`. Все эндпоинты — `GET`. `{className...}` / `{name...}` — catch-all:
остаток пути (со слэшами) склеивается в internal-имя класса.

### Мета (без rate-limit)

| Эндпоинт | Описание |
|---|---|
| `GET /` | Текстовый указатель на `/docs` и `/openapi.json` |
| `GET /health` | Liveness, отвечает `ok` |
| `GET /openapi.json` | OpenAPI 3.1 как **настоящий JSON** (YAML парсится SnakeYAML и реэкспортируется) |
| `GET /openapi.yaml` | OpenAPI 3.1 в YAML |
| `GET /docs` | Swagger UI (включён по умолчанию, `includeDocs=true`) |

### Версии

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/versions` | Список всех версий (флаги `hasYarn/Mojmap/Intermediary` + counts), порядок semver (новые сверху) |
| `GET /api/v1/versions/{version}` | Метаданные одной версии (`404 version_not_found`, если нет) |

### Поиск

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/search` | FTS-поиск классов/методов/полей в версии; каждая строка — имя во всех неймспейсах |

Параметры: `q` (обяз.), `version` (по умолч. последний release), `type` (`class/method/field/all`),
`namespace` (`yarn/mojmap/intermediary/all`), `limit` (1–200, по умолч. 50), `offset` (≥0), `exact` (`true/false`).
`q` поддерживает форму `Owner#member` / `Owner.member` / `Owner/member`.

### Перевод

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/translate` | Перевод имени между неймспейсами (`name`, `from`, `to`, `version?`, `type=auto`) |
| `GET /api/v1/translate/class/{name...}` | Шорткат перевода класса (тип фиксирован `class`; `from/to` по умолч. `yarn`→`mojmap`) |

### Diff

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/diff` | Символьный diff (added/removed/renamed классов/методов/полей) + summary |
| `GET /api/v1/diff/files` | Список изменённых файлов (`format=json`) или raw-патч (`format=patch/git`) |
| `GET /api/v1/diff/patch` | Unified/git-патч исходников (фильтры `path`/`file`/`function`, `context`, `limit`) или JSON с метаданными |

Параметры diff: `from`, `to` (обяз.); `namespace` (для `/diff` — `yarn/mojmap/intermediary`, для
`/diff/files` и `/diff/patch` — только `yarn/mojmap`); `type`, `package`, `changeType`, `limit`.

### Compare

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/compare/{version}/{className...}` | Таблица соответствия членов (методы+поля) Yarn↔Mojmap для класса |

Параметры: `from` (по умолч. `yarn` — выбирает колонку поиска класса и проверку доступности),
`to` (по умолч. `mojmap`, см. замечание ниже).

### Байткод и исходники

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/bytecode/{version}/{className...}` | Дизассемблированный байткод (ASM Textifier); `namespace`, `format=text/json` |
| `GET /api/v1/source/{version}/{className...}` | Декомпилированный `.java` класса; `namespace=yarn/mojmap` |
| `GET /api/v1/tokens/{version}/{className...}` | `{source, tokens}`: каждый идентификатор `.java` резолвится в owner/name/descriptor (JavaParser); `namespace=yarn/mojmap` |

### Иерархия и ссылки

| Эндпоинт | Описание |
|---|---|
| `GET /api/v1/hierarchy/{version}/{className...}` | Супертипы+подтипы класса (nodes/edges, ASM-скан named-jar'а); `namespace=yarn/mojmap` |
| `GET /api/v1/references/{version}?q=<key>` | Использования класса/члена (`q` = `owner` или `owner:name:descriptor`); `namespace=yarn/mojmap` |

---

## Общие правила контракта

- **Неймспейсы:** `yarn`, `mojmap`, `intermediary`, `obfuscated` (алиас `obf`). Где какие допускаются — см. таблицы выше.
- **Версия по умолчанию** (search/translate) — последний `release` по semver-порядку.
- **Ошибки** — единый `ApiError { error, message, status }`, где `status` дублирует HTTP-код:
  `400 invalid_query` (валидация), `404 not_found`, `422 namespace_unavailable` (версия не имеет запрошенного неймспейса).
- **Форматы ответов:** JSON по умолчанию; `bytecode?format=text` → `text/plain`; `diff` патч (`format=patch/git`) → `text/x-diff`.
- **Rate limit:** 200 запросов / 60 с на группы `/api/v1` (мета-эндпоинты не лимитируются).

### Поведенческие замечания (важно для агентов)

- `search.totalResults` — это размер **текущей страницы**, а не общее число совпадений.
- `diff*` с несуществующей версией возвращает **200** с пустым/нулевым результатом (не 404).
- `translate` и `compare` с несуществующей версией возвращают **404**, не 422.
- `compare`: параметр `to` валидируется, но на выборку не влияет — выравнивание всегда Yarn↔Mojmap;
  `from` выбирает колонку для поиска класса и определяет проверку 422.
- `/diff/files`: `context`/`limit`/`function` читаются только при `format=patch/git`; при `format=json` игнорируются.

---

## Что хранит индекс

SQLite-индекс (собирается `index`, открывается `serve` как RO): `versions` (метаданные + semver-порядок + counts + FTS rowid-диапазон версии),
унифицированные obf-ключённые строки `classes`/`methods`/`fields` (с `presence ∈ {both, yarn_only, mojmap_only}`)
и FTS5-таблица `search_index` по именам. **Не хранит** декомпилированный исходник, байткод и git-блобы —
они читаются по требованию из read-only стора.

---

## План на будущее

1. **Очистка мёртвого кода переписывания.** `VersionDiscovery`, `GitWatcher` и флаги `indexing.*`
   (`poll-interval-seconds`, `initial-versions`, `index-on-startup`) больше не используются `serve` —
   оставлены с проходящими тестами, подлежат удалению.
2. **Frontend → полный explorer.** Добавить drill-down вьюху на `GET /api/v1/compare` (таблица членов),
   опционально интегрировать `translate` / `diff` / `bytecode`; рассмотреть отдачу статики `dist/` самим Ktor
   (единый деплой вместо отдельного reverse-proxy).
3. **Качество поиска.** Улучшить ранжирование (mapped > intermediary > obf, бонус класса/владельца) —
   сейчас `score` использует только `1/(1+|rank|)`, аргументы `query`/`type` в `scoreFromBm25` игнорируются.
4. **Коммит-точные diff/история** через JGit (опционально), поверх текущего jar-based горячего пути.
5. **Phase 5 (низкий приоритет).** In-browser деобфускация/декомпиляция для версий без готового jar.
