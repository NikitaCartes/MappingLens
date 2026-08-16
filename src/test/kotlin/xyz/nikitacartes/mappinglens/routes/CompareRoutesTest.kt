package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.model.CompareResponse
import xyz.nikitacartes.mappinglens.service.CompareService
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
import kotlin.test.assertNotNull

class CompareRoutesTest {

    private fun Application.installJson() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `compare returns member table with statuses and 404 for unknown class`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        application {
            installJson()
            routing { compareRoutes(CompareService(db, VersionService(db))) }
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val resp = client.get("/api/v1/compare/1.21.1/net/minecraft/block/Block?from=yarn&to=mojmap")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<CompareResponse>()
        assertEquals("net/minecraft/block/Block", body.yarnClass)
        assertEquals("net/minecraft/world/level/block/Block", body.mojmapClass)

        val getDef = body.members.firstOrNull { it.yarn == "getDefaultState" }
        assertNotNull(getDef, "getDefaultState member present")
        assertEquals("defaultBlockState", getDef.mojmap)
        assertEquals("matched", getDef.status)

        val stateIds = body.members.firstOrNull { it.yarn == "STATE_IDS" }
        assertNotNull(stateIds, "STATE_IDS member present")
        assertEquals("field", stateIds.kind)
        assertEquals("matched", stateIds.status)

        val notFound = client.get("/api/v1/compare/1.21.1/net/minecraft/block/DoesNotExist?from=yarn&to=mojmap")
        assertEquals(HttpStatusCode.NotFound, notFound.status)
    }
}
