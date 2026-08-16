package xyz.nikitacartes.mappinglens

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiJsonTest {

    @Test
    fun `openapi json is real json and documents the compare endpoint`(@TempDir tmp: Path) = testApplication {
        val dbPath = tmp.resolve("idx.db").toString()
        DatabaseFactory.init(dbPath)
        application {
            module(
                AppConfig(
                    databasePath = dbPath,
                    sources = SourcesConfig(tmp.toString(), tmp.toString(), tmp.toString(), tmp.toString()),
                    initialVersions = emptyList(),
                    search = SearchConfig(200, 50),
                ),
                includeDocs = false,
            )
        }

        val resp = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        // Real JSON: parses (YAML-as-text would throw here).
        val root = Json.parseToJsonElement(body).jsonObject
        assertTrue(root.containsKey("paths"), "spec has paths")
        assertTrue(body.contains("/api/v1/compare/"), "spec documents compare endpoint")

        // Guards the packaging: SKILL.md reaches the classpath only through the resources srcDir
        // that build.gradle.kts adds for .github/skills/mappinglens.
        val skill = client.get("/skill.md")
        assertEquals(HttpStatusCode.OK, skill.status)
        assertTrue(skill.bodyAsText().contains("# MappingLens"), "skill document is served")
    }
}
