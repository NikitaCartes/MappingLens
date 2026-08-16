package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.service.CompareService
import xyz.nikitacartes.mappinglens.service.VersionService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealCompareTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    @Test
    fun `compare projects the full obf join for Block on 1_21`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("compare.db")
        val db = DatabaseFactory.init(dbPath.toString())
        val config = RealDataTestConfig.appConfig(dbPath, initialVersions = listOf(RealDataTestConfig.V_1_21))
        IngestPipeline(config).run()

        val result = CompareService(db, VersionService(db))
            .compare(RealDataTestConfig.V_1_21, "net/minecraft/block/Block", "yarn", "mojmap")
        assertTrue(result is CompareService.Result.Ok, "expected Ok, got $result")
        val resp = (result as CompareService.Result.Ok).response

        assertEquals("both", resp.presence)
        assertEquals("net/minecraft/world/level/block/Block", resp.mojmapClass)
        assertEquals("net/minecraft/class_2248", resp.intermediary)

        val getDefaultState = resp.members.firstOrNull { it.yarn == "getDefaultState" }
        assertNotNull(getDefaultState, "getDefaultState present")
        assertEquals("defaultBlockState", getDefaultState.mojmap)
        assertEquals("matched", getDefaultState.status)
        assertNotNull(getDefaultState.obfDesc, "descriptor preserved for join")

        assertTrue(resp.members.size > 20, "Block has many members, got ${resp.members.size}")
        val allowed = setOf("matched", "yarnOnly", "mojmapOnly", "unmappedYarn", "synthetic", "initializer", "unmapped")
        assertTrue(resp.members.all { it.status in allowed }, "all members have a known status")
    }

    @Test
    fun `compare 422 when requested namespace is unavailable on the version`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("compare-unobf.db")
        val db = DatabaseFactory.init(dbPath.toString())
        // An unobfuscated release (26.x) has no yarn namespace.
        val config = RealDataTestConfig.appConfig(dbPath, initialVersions = listOf("26.1.1"))
        IngestPipeline(config).run()

        val unobf = VersionService(db).listVersions().versions.firstOrNull { !it.hasYarn && it.hasMojmap }
        org.junit.jupiter.api.Assumptions.assumeTrue(unobf != null, "no unobfuscated version indexed (26.1.1 absent)")

        val result = CompareService(db, VersionService(db))
            .compare(unobf!!.id, "net/minecraft/world/level/block/Block", "yarn", "mojmap")
        assertTrue(
            result is CompareService.Result.NamespaceUnavailable,
            "expected NamespaceUnavailable for yarn on $unobf, got $result",
        )
    }
}
