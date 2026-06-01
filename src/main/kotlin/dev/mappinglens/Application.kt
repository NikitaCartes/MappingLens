package dev.mappinglens

import dev.mappinglens.config.AppConfig
import dev.mappinglens.config.RuntimeBootstrap
import dev.mappinglens.db.DatabaseFactory
import dev.mappinglens.ingestion.IngestPipeline
import dev.mappinglens.model.ApiError
import dev.mappinglens.routes.*
import dev.mappinglens.service.*
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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    val startup = RuntimeBootstrap.load(args)
    val log = LoggerFactory.getLogger("dev.mappinglens.Application")

    if (startup.templateCreated) {
        log.info("Created configuration template at {}", startup.configPath)
    }

    log.info("Starting MappingLens on {}:{} using {}", startup.host, startup.port, startup.configPath)

    embeddedServer(Netty, host = startup.host, port = startup.port) {
        module(startup.appConfig)
    }.start(wait = true)
}

fun Application.module() = module(AppConfig.load(environment.config))

fun Application.module(appConfig: AppConfig, includeDocs: Boolean = true) {
    val log = LoggerFactory.getLogger("dev.mappinglens.Application")

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
        allowHeader(HttpHeaders.ContentType)
    }

    install(RateLimit) {
        register {
            rateLimiter(limit = 200, refillPeriod = 60.seconds)
        }
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", e.message ?: "Bad request", 400))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", e.message ?: "Internal error", 500))
        }
    }

    val database = DatabaseFactory.init(appConfig.databasePath)
    val versionService = VersionService(database)
    val searchService = SearchService(database, versionService)
    val diffService = DiffService(database, appConfig)
    val translationService = TranslationService(database, versionService)
    val bytecodeService = BytecodeService(appConfig, database)

    routing {
        rateLimit {
            versionRoutes(versionService)
            searchRoutes(searchService, appConfig)
            diffRoutes(diffService)
            translationRoutes(translationService)
            bytecodeRoutes(bytecodeService)
        }

        get("/") {
            call.respondText("MappingLens API. See /docs for Swagger UI, /openapi.json for spec.")
        }
        get("/health") { call.respondText("ok") }
        get("/openapi.json") {
            call.respondText(openApiSpec(), ContentType.parse("application/yaml"))
        }
        get("/openapi.yaml") {
            call.respondText(openApiSpec(), ContentType.parse("application/yaml"))
        }

        if (includeDocs) {
            // Swagger UI backed by the static bundled spec.
            swaggerUI(path = "docs", swaggerFile = "openapi/mappinglens-api.yaml")
        }
    }

    if (appConfig.indexing.indexOnStartup) {
        launch(Dispatchers.IO) {
            try {
                log.info("Starting ingestion pipeline...")
                IngestPipeline(appConfig).run()
                log.info("Ingestion completed.")
            } catch (e: Exception) {
                log.error("Ingestion failed: {}", e.message, e)
            }
        }
    }
}

private fun openApiSpec(): String = checkNotNull(
    Thread.currentThread().contextClassLoader.getResource("openapi/mappinglens-api.yaml"),
) { "OpenAPI resource not found" }.readText()
