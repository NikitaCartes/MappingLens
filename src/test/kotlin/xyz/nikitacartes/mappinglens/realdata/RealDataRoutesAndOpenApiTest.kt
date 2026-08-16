package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.model.FileDiffResponse
import xyz.nikitacartes.mappinglens.model.SearchResponse
import xyz.nikitacartes.mappinglens.model.SourceResponse
import xyz.nikitacartes.mappinglens.model.TranslateResponse
import xyz.nikitacartes.mappinglens.routes.bytecodeRoutes
import xyz.nikitacartes.mappinglens.routes.diffRoutes
import xyz.nikitacartes.mappinglens.routes.searchRoutes
import xyz.nikitacartes.mappinglens.routes.translationRoutes
import xyz.nikitacartes.mappinglens.routes.versionRoutes
import xyz.nikitacartes.mappinglens.service.BytecodeService
import xyz.nikitacartes.mappinglens.service.DiffService
import xyz.nikitacartes.mappinglens.service.SearchService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealDataRoutesAndOpenApiTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    private fun Application.installJson() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `routes return real translations search hits file diffs and source code`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        RealDataTestConfig.seedMappingSlice(db)
        val config = RealDataTestConfig.appConfig(tmp.resolve("db.sqlite"))
        val versionService = VersionService(db)

        application {
            installJson()
            routing {
                versionRoutes(versionService)
                translationRoutes(TranslationService(db, versionService))
                searchRoutes(SearchService(db, versionService), config)
                diffRoutes(DiffService(db))
                bytecodeRoutes(BytecodeService(config, db))
            }
        }

        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val classCase = RealDataTestConfig.blockStateClasses.single { it.version == RealDataTestConfig.V_1_21_1 }
        val translate = client.get("/api/v1/translate") {
            url {
                parameters.append("name", classCase.yarn)
                parameters.append("from", "yarn")
                parameters.append("to", "mojmap")
                parameters.append("version", classCase.version)
                parameters.append("type", "class")
            }
        }
        assertEquals(HttpStatusCode.OK, translate.status)
        assertEquals(classCase.mojmap, translate.body<TranslateResponse>().output.name)

        val search = client.get("/api/v1/search") {
            url {
                parameters.append("q", "BlockState")
                parameters.append("version", classCase.version)
                parameters.append("type", "class")
                parameters.append("namespace", "yarn")
            }
        }.body<SearchResponse>()
        assertTrue(search.results.any { it.yarn == classCase.yarn && it.mojmap == classCase.mojmap })

        val diffFiles = client.get("/api/v1/diff/files") {
            url {
                parameters.append("from", RealDataTestConfig.V_1_21)
                parameters.append("to", RealDataTestConfig.V_1_21_1)
                parameters.append("namespace", "yarn")
                parameters.append("path", "net/minecraft/block")
            }
        }.body<FileDiffResponse>()
        assertEquals(emptyList(), diffFiles.files.added)
        assertEquals(emptyList(), diffFiles.files.removed)
        assertEquals(emptyList(), diffFiles.files.modified)

        val source = client.get("/api/v1/source/${classCase.version}/${classCase.yarn}") {
            url { parameters.append("namespace", "yarn") }
        }
        assertEquals(HttpStatusCode.OK, source.status)
        val sourceBody = source.body<SourceResponse>()
        assertEquals(classCase.yarn, sourceBody.`class`)
        assertTrue(sourceBody.source.contains("public class BlockState extends AbstractBlock.AbstractBlockState"))
    }

    @Test
    fun `source resolves a class by simple name and serves raw text`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        RealDataTestConfig.seedMappingSlice(db)
        val config = RealDataTestConfig.appConfig(tmp.resolve("db.sqlite"))
        val bytecodeService = BytecodeService(config, db)

        application {
            installJson()
            routing { bytecodeRoutes(bytecodeService) }
        }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val classCase = RealDataTestConfig.blockStateClasses.single { it.version == RealDataTestConfig.V_1_21_1 }
        val simpleName = classCase.yarn.substringAfterLast('/')

        // A bare simple name resolves to the one class of that version carrying it.
        val bySimpleName = client.get("/api/v1/source/${classCase.version}/$simpleName?namespace=yarn")
        assertEquals(HttpStatusCode.OK, bySimpleName.status)
        val body = bySimpleName.body<SourceResponse>()
        assertEquals(classCase.yarn, body.`class`, "response must name the class it actually served")

        // The same holds for a class asked for under a package it no longer lives in.
        val movedPackage = client.get("/api/v1/source/${classCase.version}/net/minecraft/gone/$simpleName?namespace=yarn")
        assertEquals(HttpStatusCode.OK, movedPackage.status)
        assertEquals(classCase.yarn, movedPackage.body<SourceResponse>().`class`)

        // format=text is the same source without the JSON envelope.
        val asText = client.get("/api/v1/source/${classCase.version}/$simpleName?namespace=yarn&format=text")
        assertEquals(HttpStatusCode.OK, asText.status)
        assertEquals(body.source, asText.bodyAsText())

        // A name that matches nothing stays a 404, and offers no candidate.
        val unknown = client.get("/api/v1/source/${classCase.version}/net/minecraft/block/NoSuchClassHere?namespace=yarn")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(emptyList(), bytecodeService.classCandidates(classCase.version, "NoSuchClassHere", "yarn"))
        assertEquals(listOf(classCase.yarn), bytecodeService.classCandidates(classCase.version, simpleName, "yarn"))
    }

    @Test
    fun `route validation rejects missing required query parameters with real services`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        RealDataTestConfig.seedMappingSlice(db)
        val config = RealDataTestConfig.appConfig(tmp.resolve("db.sqlite"))
        val versionService = VersionService(db)

        application {
            installJson()
            routing {
                searchRoutes(SearchService(db, versionService), config)
                translationRoutes(TranslationService(db, versionService))
                diffRoutes(DiffService(db))
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/search").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/translate").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/diff").status)
    }

    @Test
    fun `openapi spec lists endpoints from the implementation plan`() {
        val spec = Files.readString(Path.of("src/main/resources/openapi/mappinglens-api.yaml"))
        val expectedPaths = listOf(
            "/api/v1/versions",
            "/api/v1/versions/{version}",
            "/api/v1/search",
            "/api/v1/diff",
            "/api/v1/diff/files",
            "/api/v1/diff/patch",
            "/api/v1/translate",
            "/api/v1/bytecode/{version}/{className}",
            "/api/v1/source/{version}/{className}",
            "/api/v1/compare/{version}/{className}",
        )

        for (path in expectedPaths) {
            assertTrue(spec.contains(path), "OpenAPI spec is missing $path")
        }
    }
}
