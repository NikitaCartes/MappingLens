package dev.mappinglens.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeBootstrapTest {

    @Test
    fun `creates a template config when the target file is missing`(@TempDir tmp: Path) {
        val configPath = tmp.resolve("generated/application.conf")

        val startup = RuntimeBootstrap.load(arrayOf("-config=${configPath.toAbsolutePath()}"))

        assertTrue(startup.templateCreated)
        assertEquals(configPath.toAbsolutePath().normalize(), startup.configPath)
        assertTrue(Files.exists(configPath))

        val content = Files.readString(configPath)
        assertTrue(content.contains("mappinglens {"))
        assertEquals("0.0.0.0", startup.host)
        assertEquals(8080, startup.port)
        assertEquals("data/mappinglens.db", startup.appConfig.databasePath)
        assertEquals(emptyList(), startup.appConfig.initialVersions)
    }

    @Test
    fun `existing config overrides bundled defaults`(@TempDir tmp: Path) {
        val configPath = tmp.resolve("application.conf")
        Files.writeString(
            configPath,
            """
            ktor {
                deployment {
                    host = "127.0.0.1"
                    port = 8181
                }
            }

            mappinglens {
                database {
                    path = "custom/mappinglens.db"
                }
            }
            """.trimIndent(),
        )

        val startup = RuntimeBootstrap.load(arrayOf("-config", configPath.toString()))

        assertFalse(startup.templateCreated)
        assertEquals("127.0.0.1", startup.host)
        assertEquals(8181, startup.port)
        assertEquals("custom/mappinglens.db", startup.appConfig.databasePath)
        assertEquals("data/artifact-store", startup.appConfig.sources.artifactStore)
    }

    @Test
    fun `cli host and port override config values`(@TempDir tmp: Path) {
        val configPath = tmp.resolve("application.conf")

        val startup = RuntimeBootstrap.load(
            arrayOf(
                "-config", configPath.toString(),
                "-host=127.0.0.1",
                "-port", "9090",
            ),
        )

        assertTrue(Files.exists(configPath))
        assertEquals("127.0.0.1", startup.host)
        assertEquals(9090, startup.port)
    }
}