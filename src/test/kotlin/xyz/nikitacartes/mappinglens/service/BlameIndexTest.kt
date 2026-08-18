package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * [BytecodeService.blameIndex] folds an `_unobfuscated` variant into the version it was built from.
 * A variant is a second pass over a build already indexed, so its commit records how the decompiler
 * named things rather than a change to the class, and `/versions` does not list it.
 */
class BlameIndexTest {

    private val service: BytecodeService by lazy {
        val tmp = Files.createTempDirectory("ml-blame-index-")
        val dbPath = tmp.resolve("ml.db")
        val config = AppConfig(
            databasePath = dbPath.toString(),
            sources = SourcesConfig(
                yarnRepo = tmp.resolve("yarn-src").toString(),
                mojmapRepo = tmp.resolve("moj-src").toString(),
                intermediaryMappings = tmp.toString(),
                artifactStore = tmp.resolve("artifact-store").toString(),
            ),
            initialVersions = emptyList(),
            search = SearchConfig(maxResults = 100, defaultResults = 20),
        )
        BytecodeService(config, DatabaseFactory.init(dbPath.toString()))
    }

    @Test
    fun `folds a variant into its base version`() {
        val perLine = listOf("1.21.11", "25w45a_unobfuscated", "1.21.11", "25w45a", "26.1")
        val (versions, lines) = service.blameIndex(perLine, mapOf("25w45a_unobfuscated" to "25w45a"))

        assertEquals(listOf("1.21.11", "25w45a", "26.1"), versions, "the variant must not appear")
        assertEquals(listOf(0, 1, 0, 1, 2), lines, "both variant and base lines point at the base")
    }

    @Test
    fun `keeps the order and the indexes when nothing is a variant`() {
        val perLine = listOf("1.21.10", "1.21.11", "1.21.10")
        val (versions, lines) = service.blameIndex(perLine, emptyMap())

        assertEquals(listOf("1.21.10", "1.21.11"), versions)
        assertEquals(listOf(0, 1, 0), lines)
    }
}
