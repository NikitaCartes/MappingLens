package xyz.nikitacartes.mappinglens.ingestion

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version filter of `indexing.only-releases`, over ids alone. An empty store gives an empty
 * catalog, so every id here falls through to the id-shape classification, which is what an id
 * without an mc-meta file gets in a real run too.
 */
class ReleaseFilterTest {

    @Test
    fun `keeps the stable releases and nothing else`(@TempDir tmp: Path) {
        val pipeline = IngestPipeline(config(tmp))

        listOf("1.14", "1.21.4", "26.2").forEach {
            assertTrue(pipeline.isStableRelease(it), it)
        }
        listOf(
            "25w45a",
            "1.21.11-pre1",
            "1.21.11-rc1",
            "1.16_combat-1",
            "1.18_experimental-snapshot-1",
            "24w14potato",
            "1.21.11_unobfuscated",
        ).forEach { assertFalse(pipeline.isStableRelease(it), it) }
    }

    private fun config(tmp: Path) = AppConfig(
        databasePath = tmp.resolve("index.db").toString(),
        sources = SourcesConfig(
            yarnRepo = tmp.resolve("yarn").toString(),
            mojmapRepo = tmp.resolve("mojmap").toString(),
            intermediaryMappings = tmp.resolve("intermediary").toString(),
            artifactStore = tmp.resolve("artifact-store").toString(),
        ),
        initialVersions = emptyList(),
        search = SearchConfig(maxResults = 200, defaultResults = 50),
    )
}
