package dev.mappinglens.realdata

import dev.mappinglens.RealDataTestConfig
import dev.mappinglens.ingestion.CorrespondenceResolver
import dev.mappinglens.ingestion.GitWatcher
import dev.mappinglens.ingestion.ParsedMappings
import dev.mappinglens.ingestion.SourceScanner
import dev.mappinglens.ingestion.TinyV2Parser
import dev.mappinglens.ingestion.VersionDiscovery
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDataIngestionComponentsTest {
    private lateinit var yarnTrees: Map<String, ParsedMappings>
    private lateinit var mojmapTrees: Map<String, ParsedMappings>
    private lateinit var intermediaryTrees: Map<String, ParsedMappings>

    @BeforeAll
    fun loadRealMappings() {
        RealDataTestConfig.assumeAvailable()
        yarnTrees = RealDataTestConfig.versions.associateWith { TinyV2Parser.parse(RealDataTestConfig.yarnMappingFor(it)) }
        mojmapTrees = RealDataTestConfig.versions.associateWith { TinyV2Parser.parse(RealDataTestConfig.mojmapMappingFor(it)) }
        intermediaryTrees = RealDataTestConfig.versions.associateWith { TinyV2Parser.parse(RealDataTestConfig.intermediaryMappingFor(it)) }
    }

    @Test
    fun `version discovery resolves yarn intermediary and mojmap files in real folders`() {
        val discovered = VersionDiscovery(RealDataTestConfig.sourcesConfig())
            .discover()
            .associateBy { it.versionId }

        for (version in RealDataTestConfig.versions) {
            val files = discovered[version]
            assertNotNull(files, "Version $version was not discovered")
            assertNotNull(files.intermediary, "Missing intermediary mapping for $version")
            assertNotNull(files.yarn, "Missing yarn mapping for $version")
            assertNotNull(files.mojmap, "Missing mojmap mapping for $version")
            assertTrue(files.mojmap!!.fileName.toString().endsWith("-moj.tiny"))
        }
    }

    @Test
    fun `tiny parser reads real yarn mojmap and intermediary class names`() {
        for (case in RealDataTestConfig.blockStateClasses + RealDataTestConfig.itemStackClasses) {
            val yarn = yarnTrees.getValue(case.version)
            val yarnNamedIndex = yarn.namespaces.indexOfLast { it == "named" || it == "yarn" }
            val yarnIntermediaryIndex = yarn.namespaces.indexOf("intermediary")
            val yarnClass = yarn.classes.singleOrNull { it.names.getOrNull(yarnNamedIndex) == case.yarn }
            assertNotNull(yarnClass, "Missing yarn class ${case.yarn} in ${case.version}")
            assertEquals(case.obf, yarnClass.names[0])
            assertEquals(case.intermediary, yarnClass.names[yarnIntermediaryIndex])

            val mojmap = mojmapTrees.getValue(case.version)
            val mojNamedIndex = mojmap.namespaces.indexOfLast { it == "named" || it == "mojmap" || it == "mojang" }
            val mojClass = mojmap.classes.singleOrNull { it.names.getOrNull(mojNamedIndex) == case.mojmap }
            assertNotNull(mojClass, "Missing mojmap class ${case.mojmap} in ${case.version}")
            assertEquals(case.obf, mojClass.names[0])

            val intermediary = intermediaryTrees.getValue(case.version)
            val intermediaryIndex = intermediary.namespaces.indexOf("intermediary")
            val intermediaryClass = intermediary.classes.singleOrNull { it.names.getOrNull(intermediaryIndex) == case.intermediary }
            assertNotNull(intermediaryClass, "Missing intermediary class ${case.intermediary} in ${case.version}")
            assertEquals(case.obf, intermediaryClass.names[0])
        }
    }

    @Test
    fun `correspondence resolver joins real yarn and mojmap classes and members across versions`() {
        for (version in RealDataTestConfig.versions) {
            val unified = CorrespondenceResolver.resolve(
                intermediary = intermediaryTrees.getValue(version),
                yarn = yarnTrees.getValue(version),
                mojmap = mojmapTrees.getValue(version),
            )

            for (case in (RealDataTestConfig.blockStateClasses + RealDataTestConfig.itemStackClasses).filter { it.version == version }) {
                val cls = unified.singleOrNull { it.intermediaryName == case.intermediary }
                assertNotNull(cls, "Missing unified class ${case.intermediary} for $version")
                assertEquals(case.obf, cls.obfName)
                assertEquals(case.yarn, cls.yarnName)
                assertEquals(case.mojmap, cls.mojmapName)
            }

            val block = unified.singleOrNull { it.intermediaryName == "net/minecraft/class_2248" }
            assertNotNull(block, "Missing unified Block class for $version")

            val methodCase = RealDataTestConfig.defaultStateMethods.single { it.version == version }
            val method = block.methods.singleOrNull {
                it.intermediaryName == methodCase.intermediaryName && it.obfDesc == methodCase.obfDesc
            }
            assertNotNull(method, "Missing Block#getDefaultState for $version")
            assertEquals(methodCase.yarnName, method.yarnName)
            assertEquals(methodCase.mojmapName, method.mojmapName)

            val fieldCase = RealDataTestConfig.stateIdsFields.single { it.version == version }
            val field = block.fields.singleOrNull {
                it.intermediaryName == fieldCase.intermediaryName && it.obfName == fieldCase.obfName
            }
            assertNotNull(field, "Missing Block#STATE_IDS for $version")
            assertEquals(fieldCase.yarnName, field.yarnName)
            assertEquals(fieldCase.mojmapName, field.mojmapName)
        }
    }

    @Test
    fun `source scanner finds real yarn and mojmap java files using git version refs`() {
        val yarnFiles = SourceScanner(RealDataTestConfig.yarnRepo).scan(RealDataTestConfig.V_1_21_1)
            .associateBy { it.relativePath }
        val mojmapFiles = SourceScanner(RealDataTestConfig.mojmapRepo).scan(RealDataTestConfig.V_1_21_1)
            .associateBy { it.relativePath }

        for (sourceCase in RealDataTestConfig.sourceCases) {
            val scanned = if (sourceCase.namespace == "mojmap") mojmapFiles else yarnFiles
            val info = scanned[sourceCase.relativePath]
            assertNotNull(info, "SourceScanner did not find ${sourceCase.relativePath}")
            assertEquals(sourceCase.className, info.classFqn)
            assertEquals(64, info.contentHash.length)
        }
    }

    @Test
    fun `git watcher is best effort for attached repositories`() {
        val yarnRev = GitWatcher(RealDataTestConfig.yarnRepo).getCurrentRev()
        val mojmapRev = GitWatcher(RealDataTestConfig.mojmapRepo).getCurrentRev()

        for (rev in listOfNotNull(yarnRev, mojmapRev)) {
            assertTrue(Regex("[0-9a-fA-F]{40}").matches(rev), "Unexpected git rev: $rev")
        }

        // If these folders are exported source snapshots rather than git checkouts, null is valid.
        assertTrue(yarnRev == null || yarnRev.length == 40)
        assertTrue(mojmapRev == null || mojmapRev.length == 40)
    }

    @Test
    fun `real source files referenced by config are regular files`() {
        for (sourceCase in RealDataTestConfig.sourceCases) {
            val root = if (sourceCase.namespace == "mojmap") RealDataTestConfig.mojmapRepo else RealDataTestConfig.yarnRepo
            assertTrue(Files.isRegularFile(sourceCase.file(root)))
        }
    }
}
