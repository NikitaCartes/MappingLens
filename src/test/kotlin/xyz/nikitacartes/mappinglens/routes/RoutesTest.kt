package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.model.ClassListResponse
import xyz.nikitacartes.mappinglens.model.SearchResponse
import xyz.nikitacartes.mappinglens.model.TranslateResponse
import xyz.nikitacartes.mappinglens.model.VersionListResponse
import xyz.nikitacartes.mappinglens.service.DiffService
import xyz.nikitacartes.mappinglens.service.SearchService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {

    private fun cfg(tmp: Path) = AppConfig(
        databasePath = tmp.resolve("db.sqlite").toString(),
        sources = SourcesConfig(
            yarnRepo = tmp.toString(),
            mojmapRepo = tmp.toString(),
            intermediaryMappings = tmp.toString(),
            artifactStore = tmp.toString(),
        ),
        initialVersions = emptyList(),
        search = SearchConfig(maxResults = 100, defaultResults = 20),
    )

    private fun Application.installJson() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `GET versions returns seeded versions and GET translate resolves yarn to mojmap`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val versionService = VersionService(db)
        val searchService = SearchService(db, versionService, Fixtures.dbPath(db))
        val translationService = TranslationService(db, versionService)
        val diffService = DiffService(db)

        application {
            installJson()
            routing {
                versionRoutes(versionService)
                translationRoutes(translationService)
                searchRoutes(searchService, cfg(tmp))
                diffRoutes(diffService)
            }
        }

        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val versions = client.get("/api/v1/versions").body<VersionListResponse>()
        assertEquals(listOf("1.21.1"), versions.versions.map { it.id })

        val translateResp = client.get("/api/v1/translate") {
            url {
                parameters.append("name", "net/minecraft/block/BlockState")
                parameters.append("from", "yarn")
                parameters.append("to", "mojmap")
                parameters.append("version", "1.21.1")
                parameters.append("type", "class")
            }
        }
        assertEquals(HttpStatusCode.OK, translateResp.status)
        val t: TranslateResponse = translateResp.body()
        assertEquals("net/minecraft/world/level/block/state/BlockState", t.output.name)

        val searchResp = client.get("/api/v1/search") {
            url {
                parameters.append("q", "BlockState")
                parameters.append("version", "1.21.1")
                parameters.append("type", "class")
            }
        }
        val s: SearchResponse = searchResp.body()
        assertTrue(s.results.any { it.yarn?.endsWith("/BlockState") == true })
    }

    @Test
    fun `GET classes lists a version's classes and 404s for unknown version`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val versionService = VersionService(db)

        application {
            installJson()
            routing { versionRoutes(versionService) }
        }

        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val resp = client.get("/api/v1/classes/1.21.1")
        assertEquals(HttpStatusCode.OK, resp.status)
        val list: ClassListResponse = resp.body()
        assertEquals("1.21.1", list.version)
        assertTrue(list.classes.any { it.yarn?.endsWith("/BlockState") == true })

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/classes/9.9.9").status)
    }

    @Test
    fun `GET translate returns 404 for unknown name`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val versionService = VersionService(db)
        val translationService = TranslationService(db, versionService)

        application {
            installJson()
            routing { translationRoutes(translationService) }
        }

        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val resp = client.get("/api/v1/translate") {
            url {
                parameters.append("name", "net/minecraft/Nope")
                parameters.append("from", "yarn")
                parameters.append("to", "mojmap")
                parameters.append("version", "1.21.1")
            }
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET search returns 400 when q missing`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val versionService = VersionService(db)
        val searchService = SearchService(db, versionService, Fixtures.dbPath(db))

        application {
            installJson()
            routing { searchRoutes(searchService, cfg(tmp)) }
        }

        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val resp = client.get("/api/v1/search")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `GET search returns 400 for invalid enum parameters`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val versionService = VersionService(db)
        val searchService = SearchService(db, versionService, Fixtures.dbPath(db))

        application {
            installJson()
            routing { searchRoutes(searchService, cfg(tmp)) }
        }

        val resp = client.get("/api/v1/search") {
            url {
                parameters.append("q", "Block")
                parameters.append("type", "invalid")
            }
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
