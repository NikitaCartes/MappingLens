package dev.mappinglens

import dev.mappinglens.config.AppConfig
import dev.mappinglens.config.IndexingConfig
import dev.mappinglens.config.SearchConfig
import dev.mappinglens.config.SourcesConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationStartupTest {

    private fun testConfig(tmp: Path, dbPath: String) = AppConfig(
        databasePath = dbPath,
        sources = SourcesConfig(
            yarnRepo = tmp.resolve("yarn-src").toString(),
            mojmapRepo = tmp.resolve("mojmap-src").toString(),
            intermediaryMappings = tmp.resolve("intermediary").toString(),
            artifactStore = tmp.resolve("artifact-store").toString(),
        ),
        indexing = IndexingConfig(pollIntervalSeconds = 3600, initialVersions = emptyList(), indexOnStartup = false),
        search = SearchConfig(maxResults = 200, defaultResults = 50),
    )

    @Test
    fun `application module starts and serves health endpoint`(@TempDir tmp: Path) = testApplication {
        val dbPath = tmp.resolve("mappinglens.db").toString()
        // The stateless server opens a prebuilt index read-only, so create it first.
        dev.mappinglens.db.DatabaseFactory.init(dbPath)
        application { module(testConfig(tmp, dbPath), includeDocs = false) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `successful api responses cache for a month, errors and meta do not`(@TempDir tmp: Path) = testApplication {
        val dbPath = tmp.resolve("mappinglens.db").toString()
        dev.mappinglens.db.DatabaseFactory.init(dbPath)
        application { module(testConfig(tmp, dbPath), includeDocs = false) }

        // 200 on an /api/v1 route -> one-month immutable cache.
        val ok = client.get("/api/v1/versions")
        assertEquals(HttpStatusCode.OK, ok.status)
        val cache = ok.headers[HttpHeaders.CacheControl]
        assertTrue(cache?.contains("max-age=2592000") == true, "expected month-long cache, got: $cache")

        // 404 must not be frozen (a class/version may be indexed later).
        val notFound = client.get("/api/v1/versions/9.9.9")
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertNull(notFound.headers[HttpHeaders.CacheControl])

        // Non-api endpoints are untouched.
        assertNull(client.get("/health").headers[HttpHeaders.CacheControl])
    }
}