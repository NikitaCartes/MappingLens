package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.model.ReferenceRequest
import xyz.nikitacartes.mappinglens.service.ReferenceService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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

class ReferenceRoutesTest {

    /**
     * No jar exists under the temp artifact store, so a request that reaches the service answers
     * `not_found` — which is what separates "the body was understood" from "the body was rejected".
     */
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

    @Test
    fun `targets travel in a body over POST and QUERY, and an oversized query points at them`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val config = cfg(tmp)

        application {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { referenceRoutes(ReferenceService(config), VersionService(db)) }
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        for (method in listOf(HttpMethod.Post, HttpMethod("QUERY"))) {
            val response = client.request("/api/v1/references/1.21.1") {
                this.method = method
                contentType(ContentType.Application.Json)
                setBody(ReferenceRequest(namespace = "mojmap", targets = listOf("net/minecraft/world/level/block/Block")))
            }
            assertEquals(HttpStatusCode.NotFound, response.status, "$method reaches the service")
            assertTrue("not_found" in response.bodyAsText(), "$method got past body parsing")
        }

        val tooMany = client.request("/api/v1/references/1.21.1") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(ReferenceRequest(targets = List(2001) { "C$it" }))
        }
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertTrue("At most 2000" in tooMany.bodyAsText())

        val longQuery = client.get("/api/v1/references/1.21.1") {
            url { repeat(26) { parameters.append("q", "C$it") } }
        }
        assertEquals(HttpStatusCode.BadRequest, longQuery.status)
        assertTrue("POST" in longQuery.bodyAsText(), "the query cap names the way out")
    }
}
