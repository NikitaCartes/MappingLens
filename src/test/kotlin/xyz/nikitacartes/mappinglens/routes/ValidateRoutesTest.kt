package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.model.ValidateAt
import xyz.nikitacartes.mappinglens.model.ValidateRequest
import xyz.nikitacartes.mappinglens.model.ValidateTarget
import xyz.nikitacartes.mappinglens.service.ReferenceService
import xyz.nikitacartes.mappinglens.service.ValidateService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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

class ValidateRoutesTest {

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

    private fun body(atTarget: String) = ValidateRequest(
        namespace = "mojmap",
        targets = listOf(ValidateTarget("dismount", "Caller", "run", "()V", ValidateAt("INVOKE", atTarget))),
    )

    @Test
    fun `an at target with one or two colons passes, anything else is rejected`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val config = cfg(tmp)

        application {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { validateRoutes(ValidateService(config, ReferenceService(config)), VersionService(db)) }
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        // No jar exists under the temp artifact store, so a request that reaches the service answers
        // `missing` with 200 — which is what separates "the at.target was accepted" from rejection.
        for (atTarget in listOf("Target:foo:()V", "Target:foo")) {
            val response = client.post("/api/v1/validate?from=1.21.1&to=1.21.1") {
                contentType(ContentType.Application.Json)
                setBody(body(atTarget))
            }
            assertEquals(HttpStatusCode.OK, response.status, "$atTarget is accepted")
        }

        for (atTarget in listOf("Targetfoo", "a:b:c:d")) {
            val response = client.post("/api/v1/validate?from=1.21.1&to=1.21.1") {
                contentType(ContentType.Application.Json)
                setBody(body(atTarget))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "$atTarget is rejected")
            assertTrue("owner:name[:descriptor]" in response.bodyAsText(), "the error names the accepted form")
        }
    }
}
