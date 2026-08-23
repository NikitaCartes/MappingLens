package xyz.nikitacartes.mappinglens

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.RuntimeBootstrap
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.db.SearchIndex
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.routes.*
import xyz.nikitacartes.mappinglens.service.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("xyz.nikitacartes.mappinglens.Application")
    // First non-flag token is the subcommand; default to "serve" so bare invocation still serves.
    val (command, rest) = if (args.isNotEmpty() && !args[0].startsWith("-")) {
        args[0] to args.copyOfRange(1, args.size)
    } else {
        "serve" to args
    }
    when (command) {
        "index" -> runIndex(rest, log)
        "serve" -> runServe(rest, log)
        else -> {
            System.err.println("Unknown command '$command'. Usage: mappinglens [serve|index] [options]")
            exitProcess(2)
        }
    }
}

/** Offline: build the read-only index. The only writer of the database. */
private fun runIndex(args: Array<String>, log: Logger) {
    val startup = RuntimeBootstrap.load(args)
    log.info("Building index at {} (config {})", startup.appConfig.databasePath, startup.configPath)
    DatabaseFactory.init(startup.appConfig.databasePath)
    val force = args.any { it == "-force" || it == "--force" }
    // -versions=1.21.4,26.2 rebuilds a subset without editing the config file. A version that GitCraft
    // refreshed is already indexed, so a rebuild of it needs -force as well.
    val only = args.firstOrNull { it.startsWith("-versions=") || it.startsWith("--versions=") }
        ?.substringAfter('=')?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    // -refs=releases (default), all, or none: how much of the prebuilt reverse-reference index to
    // build. Releases cost 0.96 GB and a minute; all 515 versions cost 10.3 GB and 11 minutes.
    val references = args.firstOrNull { it.startsWith("-refs=") || it.startsWith("--refs=") }
        ?.substringAfter('=') ?: "releases"
    if (references !in setOf("releases", "all", "none")) {
        System.err.println("Unknown -refs=$references. Use releases, all or none.")
        exitProcess(2)
    }
    IngestPipeline(startup.appConfig).run(force, only, references)
    log.info("Index build complete.")
}

/** Online: start the stateless HTTP server over the prebuilt read-only index. */
private fun runServe(args: Array<String>, log: Logger) {
    val startup = RuntimeBootstrap.load(args)
    if (startup.templateCreated) {
        log.info("Created configuration template at {}", startup.configPath)
    }
    log.info("Starting MappingLens (stateless) on {}:{} using {}", startup.host, startup.port, startup.configPath)
    embeddedServer(Netty, host = startup.host, port = startup.port) {
        module(startup.appConfig)
    }.start(wait = true)
}

fun Application.module() = module(AppConfig.load(environment.config))

fun Application.module(appConfig: AppConfig, includeDocs: Boolean = true) {
    val log = LoggerFactory.getLogger("xyz.nikitacartes.mappinglens.Application")

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        })
    }

    install(CallLogging)
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod("QUERY"))
        allowHeader(HttpHeaders.ContentType)
    }

    install(RateLimit) {
        register {
            rateLimiter(limit = 200, refillPeriod = 60.seconds)
        }
    }

    // Mappings for a given version never change, so let clients/CDNs cache successful /api/v1 responses
    // for a month. Only 2xx (or default-200, where status() is still null at respond time) are cached —
    // errors keep an explicit non-2xx status so a 404 for a not-yet-indexed class isn't frozen for weeks.
    // `/api/v1/versions` is the exception. It is the catalog, not per-version data: an indexer run adds
    // versions and changes the counts and the namespace flags of the ones already listed. A month-long
    // immutable copy froze that in every browser. The catalog is small, so a minute keeps repeated
    // navigation cheap and still shows an index rebuilt while the page was open.
    install(createApplicationPlugin("ApiCacheHeaders") {
        onCallRespond { call ->
            // GET only: POST /exists is keyed on its request body, which a URL-keyed shared cache
            // ignores — freezing one batch's answers for a different batch. Never cache it.
            val path = call.request.path()
            if (call.request.httpMethod == HttpMethod.Get && path.startsWith("/api/v1")) {
                val status = call.response.status()
                if (status == null || status.isSuccess()) {
                    val cache = if (path.startsWith("/api/v1/versions")) "public, max-age=3600"
                    else "public, max-age=2592000, immutable"
                    call.response.headers.append(HttpHeaders.CacheControl, cache)
                }
            }
        }
    })

    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", e.message ?: "Bad request", 400))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", e.message ?: "Internal error", 500))
        }
    }

    val database = DatabaseFactory.openReadOnly(appConfig.databasePath)
    // Fail at startup rather than on the first search when the search index is missing.
    SearchIndex.openReadOnly(appConfig.databasePath).close()
    val versionService = VersionService(database)
    val searchService = SearchService(database, versionService, appConfig.databasePath)
    val diffService = DiffService(database, appConfig)
    val translationService = TranslationService(database, versionService)
    val bytecodeService = BytecodeService(appConfig, database)
    val compareService = CompareService(database, versionService)
    val hierarchyService = HierarchyService(appConfig)
    val referenceService = ReferenceService(appConfig)
    val existsService = ExistsService(appConfig)
    val tokenService = TokenService(appConfig, bytecodeService)
    val historyService = HistoryService(database, appConfig)
    val bodyHashService = BodyHashService(appConfig, database)
    val validateService = ValidateService(appConfig, referenceService)

    routing {
        rateLimit {
            versionRoutes(versionService)
            searchRoutes(searchService, appConfig)
            diffRoutes(diffService)
            translationRoutes(translationService)
            bytecodeRoutes(bytecodeService)
            compareRoutes(compareService)
            hierarchyRoutes(hierarchyService)
            referenceRoutes(referenceService, versionService)
            existsRoutes(existsService)
            tokenRoutes(tokenService)
            historyRoutes(historyService)
            bodyHashRoutes(bodyHashService, versionService)
            validateRoutes(validateService, versionService)
        }

        get("/") {
            call.respondText("MappingLens API. See /docs for Swagger UI, /openapi.json for spec.")
        }
        get("/health") { call.respondText("ok") }
        get("/openapi.json") {
            call.respondText(openApiJson(), ContentType.Application.Json)
        }
        get("/openapi.yaml") {
            call.respondText(openApiSpec(), ContentType.parse("application/yaml"))
        }
        if (includeDocs) {
            // Swagger UI backed by the static bundled spec.
            swaggerUI(path = "docs", swaggerFile = "openapi/mappinglens-api.yaml")
        }
    }
}

private fun resourceText(path: String): String = checkNotNull(
    Thread.currentThread().contextClassLoader.getResource(path),
) { "Resource not found: $path" }.readText()

private fun openApiSpec(): String = resourceText("openapi/mappinglens-api.yaml")

// The spec is authored in YAML; /openapi.json serves the same document as real JSON. Converted once.
private val openApiJsonCache: String by lazy {
    Json.encodeToString(JsonElement.serializer(), yamlToJsonElement(Yaml().load(openApiSpec())))
}

private fun openApiJson(): String = openApiJsonCache

private fun yamlToJsonElement(node: Any?): JsonElement = when (node) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(node.entries.associate { (k, v) -> k.toString() to yamlToJsonElement(v) })
    is List<*> -> JsonArray(node.map { yamlToJsonElement(it) })
    is Boolean -> JsonPrimitive(node)
    is Number -> JsonPrimitive(node)
    else -> JsonPrimitive(node.toString())
}
