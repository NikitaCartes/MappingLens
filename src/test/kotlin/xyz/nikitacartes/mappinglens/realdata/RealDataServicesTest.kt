package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.service.BytecodeService
import xyz.nikitacartes.mappinglens.service.DiffService
import xyz.nikitacartes.mappinglens.service.SearchService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealDataServicesTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    private fun realDb(tmp: Path): Database {
        val db = Fixtures.newDb(tmp)
        RealDataTestConfig.seedMappingSlice(db)
        return db
    }

    @Test
    fun `version service reports real-data versions and counts`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val versions = VersionService(db).listVersions().versions.associateBy { it.id }

        assertEquals(RealDataTestConfig.versions.toSet(), versions.keys)
        for (version in RealDataTestConfig.versions) {
            val info = versions.getValue(version)
            assertEquals("release", info.releaseType)
            assertTrue(info.hasYarn)
            assertTrue(info.hasMojmap)
            assertTrue(info.hasIntermediary)
            assertTrue(info.classCount >= 3, "Expected at least Block, BlockState, ItemStack for $version")
            assertTrue(info.methodCount >= 1, "Expected Block#getDefaultState for $version")
            assertTrue(info.fieldCount >= 1, "Expected Block#STATE_IDS for $version")
        }
    }

    @Test
    fun `translation service translates real yarn and mojmap class names across versions`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = TranslationService(db, VersionService(db))

        for (case in RealDataTestConfig.blockStateClasses + RealDataTestConfig.itemStackClasses) {
            val toMojmap = service.translate(case.yarn, from = "yarn", to = "mojmap", version = case.version, type = "class")
            assertNotNull(toMojmap, "Missing yarn->mojmap translation for ${case.yarn} ${case.version}")
            assertEquals(case.mojmap, toMojmap.output.name)
            assertEquals(case.intermediary, toMojmap.intermediary)
            assertEquals(case.obf, toMojmap.obfuscated)

            val toYarn = service.translate(case.mojmap, from = "mojmap", to = "yarn", version = case.version, type = "class")
            assertNotNull(toYarn, "Missing mojmap->yarn translation for ${case.mojmap} ${case.version}")
            assertEquals(case.yarn, toYarn.output.name)
        }
    }

    @Test
    fun `translation service translates real Block method and field names`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = TranslationService(db, VersionService(db))

        for (member in RealDataTestConfig.defaultStateMethods) {
            val translated = service.translate(
                name = "${member.ownerYarn}#${member.yarnName}",
                from = "yarn",
                to = "mojmap",
                version = member.version,
                type = "method",
            )
            assertNotNull(translated, "Missing method translation for ${member.version}")
            assertEquals("${member.ownerMojmap}#${member.mojmapName}", translated.output.name)
            assertEquals("${member.ownerIntermediary}#${member.intermediaryName}", translated.intermediary)
            assertEquals("${member.ownerObf}#${member.obfName}", translated.obfuscated)
        }

        for (member in RealDataTestConfig.stateIdsFields) {
            val translated = service.translate(
                name = "${member.ownerYarn}#${member.yarnName}",
                from = "yarn",
                to = "mojmap",
                version = member.version,
                type = "field",
            )
            assertNotNull(translated, "Missing field translation for ${member.version}")
            assertEquals("${member.ownerMojmap}#${member.mojmapName}", translated.output.name)
            assertEquals("${member.ownerIntermediary}#${member.intermediaryName}", translated.intermediary)
        }
    }

    @Test
    fun `search service finds real class method and field names in different namespaces`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = SearchService(db, VersionService(db))

        for (case in RealDataTestConfig.blockStateClasses) {
            val byYarn = service.search("BlockState", case.version, type = "class", namespace = "yarn", limit = 20, offset = 0, exact = false)
            assertEquals(case.version, byYarn.version)
            val hit = byYarn.results.singleOrNull { it.yarn == case.yarn && it.mojmap == case.mojmap }
            assertNotNull(hit, "Missing BlockState yarn search hit for ${case.version}: ${byYarn.results}")
            assertEquals("class", hit.type)
            assertEquals(case.intermediary, hit.intermediary)
            assertEquals(case.obf, hit.obfuscated)
        }

        for (member in RealDataTestConfig.defaultStateMethods) {
            val byMojmap = service.search(member.mojmapName, member.version, type = "method", namespace = "mojmap", limit = 20, offset = 0, exact = true)
            assertTrue(
                byMojmap.results.any { it.mojmap == "${member.ownerMojmap}#${member.mojmapName}" },
                "Missing defaultBlockState mojmap search hit for ${member.version}: ${byMojmap.results}",
            )
        }

        for (member in RealDataTestConfig.stateIdsFields) {
            val byYarnField = service.search(member.yarnName, member.version, type = "field", namespace = "yarn", limit = 20, offset = 0, exact = true)
            assertTrue(
                byYarnField.results.any { it.yarn == "${member.ownerYarn}#${member.yarnName}" },
                "Missing STATE_IDS yarn search hit for ${member.version}: ${byYarnField.results}",
            )
        }
    }

    @Test
    fun `search service supports owner member query using real names`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = SearchService(db, VersionService(db))
        val member = RealDataTestConfig.defaultStateMethods.single { it.version == RealDataTestConfig.V_1_21_1 }

        val response = service.search(
            query = "Block#${member.yarnName}",
            version = member.version,
            type = "method",
            namespace = "yarn",
            limit = 20,
            offset = 0,
            exact = false,
        )

        assertTrue(
            response.results.any { it.yarn == "${member.ownerYarn}#${member.yarnName}" },
            "Owner/member search did not return ${member.ownerYarn}#${member.yarnName}: ${response.results}",
        )
    }

    @Test
    fun `diff service does not report stable real intermediary names as renamed`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val diff = DiffService(db).diff(
            from = RealDataTestConfig.V_1_20_6,
            to = RealDataTestConfig.V_1_21,
            namespace = "yarn",
            type = "all",
            packageFilter = null,
            changeType = "all",
            limit = 100,
        )

        assertEquals(0, diff.summary.classesAdded)
        assertEquals(0, diff.summary.classesRemoved)
        assertEquals(0, diff.summary.classesRenamed)
        assertEquals(0, diff.summary.methodsRenamed)
        assertEquals(0, diff.summary.fieldsRenamed)
        assertTrue(diff.changes.renamed.none { it.intermediary == "net/minecraft/class_2680" })
        assertTrue(diff.changes.renamed.none { it.intermediary == "method_9564" })
        assertTrue(diff.changes.renamed.none { it.intermediary == "field_10651" })
    }

    @Test
    fun `file diff service compares real source hashes across seeded versions`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val diff = DiffService(db).diffFiles(
            from = RealDataTestConfig.V_1_21,
            to = RealDataTestConfig.V_1_21_1,
            namespace = "yarn",
            pathPrefix = "net/minecraft/block",
        )

        assertEquals(emptyList(), diff.files.added)
        assertEquals(emptyList(), diff.files.removed)
        assertEquals(emptyList(), diff.files.modified)
    }

    @Test
    fun `source service returns real yarn and mojmap code for multiple versions`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = BytecodeService(RealDataTestConfig.appConfig(tmp.resolve("db.sqlite")), db)

        for (version in listOf(RealDataTestConfig.V_1_21, RealDataTestConfig.V_1_21_1)) {
            for (sourceCase in RealDataTestConfig.sourceCases) {
                val response = service.source(version, sourceCase.className, sourceCase.namespace)
                assertNotNull(response, "Missing source response for ${sourceCase.namespace}:${sourceCase.className} $version")
                assertEquals(version, response.version)
                assertEquals(sourceCase.namespace, response.namespace)
                assertTrue(response.path.replace('\\', '/').endsWith(sourceCase.relativePath))
                for (marker in sourceCase.markers) {
                    assertTrue(response.source.contains(marker), "Missing marker '$marker' in returned source for ${sourceCase.className}")
                }
            }
        }
    }

    @Test
    fun `bytecode service reads real merged jar by obfuscated class name`(@TempDir tmp: Path) {
        val db = realDb(tmp)
        val service = BytecodeService(RealDataTestConfig.appConfig(tmp.resolve("db.sqlite")), db)
        val blockState = RealDataTestConfig.blockStateClasses.single { it.version == RealDataTestConfig.V_1_21_1 }
        val jarDir = RealDataTestConfig.jarDirFor(blockState.version)

        assertTrue(Files.isDirectory(jarDir), "Missing jar dir $jarDir")
        val response = service.bytecode(blockState.version, blockState.yarn, namespace = "yarn")

        assertNotNull(response, "Expected bytecode for ${blockState.yarn} in ${blockState.version}")
        assertEquals(blockState.version, response.version)
        assertEquals(blockState.yarn, response.`class`)
        assertTrue(response.bytecode.contains("class version"), "ASM textifier output should include class version")
        assertTrue(response.bytecode.contains(blockState.yarn), "Yarn bytecode output should reference mapped class ${blockState.yarn}")

        val obfuscated = service.bytecode(blockState.version, blockState.obf, namespace = "obfuscated")
        assertNotNull(obfuscated, "Expected obfuscated bytecode for ${blockState.obf} in ${blockState.version}")
        assertTrue(obfuscated.bytecode.contains(blockState.obf), "Obfuscated bytecode output should reference ${blockState.obf}")
    }
}
