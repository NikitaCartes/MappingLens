package dev.mappinglens.realdata

import dev.mappinglens.RealDataTestConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealDataFolderAnalysisTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    @Test
    fun `configured external folders have expected repository layout`() {
        val yarnSourceRoot = RealDataTestConfig.yarnRepo.resolve("minecraft/src")
            .takeIf { Files.isDirectory(it) } ?: RealDataTestConfig.yarnRepo
        val mojmapSourceRoot = RealDataTestConfig.mojmapRepo.resolve("minecraft/src")
            .takeIf { Files.isDirectory(it) } ?: RealDataTestConfig.mojmapRepo
        assertTrue(Files.isDirectory(yarnSourceRoot.resolve("net/minecraft/block")))
        assertTrue(Files.isDirectory(yarnSourceRoot.resolve("net/minecraft/item")))
        assertTrue(Files.isDirectory(mojmapSourceRoot.resolve("net/minecraft/world/level/block/state")))
        assertTrue(Files.isDirectory(mojmapSourceRoot.resolve("net/minecraft/world/item")))
        assertTrue(Files.isDirectory(RealDataTestConfig.artifactStore))
        assertTrue(Files.isDirectory(RealDataTestConfig.mappingFilesDir))
        assertTrue(Files.isDirectory(RealDataTestConfig.intermediaryMappings))
        assertTrue(Files.isDirectory(RealDataTestConfig.minecraftVersionsDir))
    }

    @Test
    fun `real source files contain expected yarn and mojmap code markers`() {
        for (sourceCase in RealDataTestConfig.sourceCases) {
            val root = if (sourceCase.namespace == "mojmap") RealDataTestConfig.mojmapRepo else RealDataTestConfig.yarnRepo
            val file = sourceCase.file(root)
            assertTrue(Files.exists(file), "Missing real source file: $file")

            val content = Files.readString(file)
            for (marker in sourceCase.markers) {
                assertTrue(
                    content.contains(marker),
                    "Expected marker '$marker' in ${sourceCase.namespace} source ${sourceCase.relativePath}",
                )
            }
        }
    }

    @Test
    fun `real mapping and jar files exist for all configured versions`() {
        for (version in RealDataTestConfig.versions) {
            assertTrue(Files.exists(RealDataTestConfig.intermediaryMappingFor(version)))
            assertTrue(Files.exists(RealDataTestConfig.yarnMappingFor(version)))
            assertTrue(Files.exists(RealDataTestConfig.mojmapMappingFor(version)))

            val jars = Files.list(RealDataTestConfig.jarDirFor(version)).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
            assertTrue(jars.any { it.startsWith("merged-") && it.endsWith(".jar") }, "No merged jar for $version: $jars")
        }
    }

    @Test
    fun `central fixture list covers yarn and mojmap classes across versions`() {
        assertEquals(RealDataTestConfig.versions.toSet(), RealDataTestConfig.blockStateClasses.map { it.version }.toSet())
        assertEquals(RealDataTestConfig.versions.toSet(), RealDataTestConfig.itemStackClasses.map { it.version }.toSet())
        assertTrue(RealDataTestConfig.sourceCases.any { it.namespace == "yarn" })
        assertTrue(RealDataTestConfig.sourceCases.any { it.namespace == "mojmap" })
    }
}
