package dev.mappinglens.realdata

import dev.mappinglens.Fixtures
import dev.mappinglens.RealDataTestConfig
import dev.mappinglens.model.FileDiffResponse
import dev.mappinglens.model.SearchResponse
import dev.mappinglens.model.SourceResponse
import dev.mappinglens.model.TranslateResponse
import dev.mappinglens.routes.bytecodeRoutes
import dev.mappinglens.routes.diffRoutes
import dev.mappinglens.routes.searchRoutes
import dev.mappinglens.routes.translationRoutes
import dev.mappinglens.routes.versionRoutes
import dev.mappinglens.service.BytecodeService
import dev.mappinglens.service.DiffService
import dev.mappinglens.service.SearchService
import dev.mappinglens.service.TranslationService
import dev.mappinglens.service.VersionService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
        )

        for (path in expectedPaths) {
            assertTrue(spec.contains(path), "OpenAPI spec is missing $path")
        }
    }
}
