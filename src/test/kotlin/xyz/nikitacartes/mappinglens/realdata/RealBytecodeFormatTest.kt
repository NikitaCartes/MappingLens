package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.model.BytecodeResponse
import xyz.nikitacartes.mappinglens.routes.bytecodeRoutes
import xyz.nikitacartes.mappinglens.service.BytecodeService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealBytecodeFormatTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    private fun Application.installJson() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `bytecode format param returns plain text or json`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        RealDataTestConfig.seedMappingSlice(db)
        val config = RealDataTestConfig.appConfig(tmp.resolve("db.sqlite"))
        application {
            installJson()
            routing { bytecodeRoutes(BytecodeService(config, db)) }
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        // 1.21 Block obfuscated name; disassembled from the merged mc-versions jar (no remapper).
        val obf = "dfy"

        val text = client.get("/api/v1/bytecode/${RealDataTestConfig.V_1_21}/$obf?namespace=obfuscated&format=text")
        assertEquals(HttpStatusCode.OK, text.status)
        assertTrue(
            text.contentType().toString().startsWith("text/plain"),
            "format=text should be text/plain, was ${text.contentType()}",
        )
        assertTrue(text.bodyAsText().contains("class"), "disassembly should mention class")

        val jsonResp = client.get("/api/v1/bytecode/${RealDataTestConfig.V_1_21}/$obf?namespace=obfuscated&format=json")
        assertEquals(HttpStatusCode.OK, jsonResp.status)
        val body = jsonResp.body<BytecodeResponse>()
        assertTrue(body.bytecode.contains("class"), "json bytecode should mention class")
    }
}
