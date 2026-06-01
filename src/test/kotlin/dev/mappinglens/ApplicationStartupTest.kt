package dev.mappinglens

import dev.mappinglens.config.AppConfig
import dev.mappinglens.config.IndexingConfig
import dev.mappinglens.config.SearchConfig
import dev.mappinglens.config.SourcesConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class ApplicationStartupTest {

    @Test
    fun `application module starts and serves health endpoint`(@TempDir tmp: Path) = testApplication {
        application {
            module(
                AppConfig(
                    databasePath = tmp.resolve("mappinglens.db").toString(),
                    sources = SourcesConfig(
                        yarnRepo = tmp.resolve("yarn-src").toString(),
                        mojmapRepo = tmp.resolve("mojmap-src").toString(),
                        intermediaryMappings = tmp.resolve("intermediary").toString(),
                        artifactStore = tmp.resolve("artifact-store").toString(),
                    ),
                    indexing = IndexingConfig(
                        pollIntervalSeconds = 3600,
                        initialVersions = emptyList(),
                        indexOnStartup = false,
                    ),
                    search = SearchConfig(
                        maxResults = 200,
                        defaultResults = 50,
                    ),
                ),
                includeDocs = false,
            )
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }
}