package dev.mappinglens.data

import dev.mappinglens.RealDataTestConfig
import dev.mappinglens.ingestion.CorrespondenceResolver
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitCraftStoreTest {

    private fun store(): GitCraftStore {
        assumeTrue(Files.exists(RealDataTestConfig.artifactStore), "artifact-store missing")
        assumeTrue(Files.isDirectory(RealDataTestConfig.intermediaryMappings), "intermediary mappings missing")
        return GitCraftStore(RealDataTestConfig.artifactStore, RealDataTestConfig.intermediaryMappings)
    }

    @Test
    fun `resolves all mapping kinds for a release`() {
        val s = store()
        val src = s.resolve("1.21")
        assertNotNull(src.yarn, "yarn tiny"); assertTrue(Files.exists(src.yarn!!))
        assertTrue(src.mojmaps.isNotEmpty(), "mojmap tinies")
        src.mojmaps.forEach { assertTrue(Files.exists(it), "mojmap exists: $it") }
        assertNotNull(src.intermediary, "intermediary tiny"); assertTrue(Files.exists(src.intermediary!!))
        assertTrue(src.hasYarn && src.hasMojmap && src.hasIntermediary)
    }

    @Test
    fun `resolves space-named version and globs its jar (the section 5 hazard)`() {
        val s = store()
        val v = "1.14 Pre-Release 1"
        assumeTrue(s.resolve(v).hasAny, "$v not present in this store")
        val src = s.resolve(v)
        assertNotNull(src.intermediary, "intermediary v1 for space-named version")
        // Jar resolves via glob despite the unpredictable id suffix in the filename.
        val jar = s.decompiledJar(v, "yarn")
        assertNotNull(jar, "decompiled yarn jar for $v")
        assertTrue(Files.exists(jar!!))
    }

    @Test
    fun `enumerates versions sorted by semver`() {
        val s = store()
        val ids = s.versionIds()
        assertTrue(ids.contains("1.21"), "should include 1.21")
        if (ids.contains("1.9") && ids.contains("1.10")) {
            assertTrue(ids.indexOf("1.9") < ids.indexOf("1.10"), "1.9 must sort before 1.10")
        }
    }

    @Test
    fun `parseUnified joins Block across namespaces with presence both`() {
        val s = store()
        assumeTrue(s.resolve("1.21").hasYarn, "1.21 yarn missing")
        val unified = s.parseUnified("1.21")
        val block = unified.firstOrNull { it.yarnName == "net/minecraft/block/Block" }
        assertNotNull(block, "Block class")
        assertEquals("net/minecraft/world/level/block/Block", block.mojmapName)
        assertEquals(CorrespondenceResolver.PRESENCE_BOTH, block.presence)
    }
}
