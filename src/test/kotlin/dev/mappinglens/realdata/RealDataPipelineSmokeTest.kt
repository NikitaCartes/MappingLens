package dev.mappinglens.realdata

import dev.mappinglens.RealDataTestConfig
import dev.mappinglens.db.DatabaseFactory
import dev.mappinglens.db.tables.SourceFileTable
import dev.mappinglens.ingestion.IngestPipeline
import dev.mappinglens.service.SearchService
import dev.mappinglens.service.TranslationService
import dev.mappinglens.service.VersionService
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealDataPipelineSmokeTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    @Test
    fun `ingest pipeline indexes one real version with mappings fts and source files`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("pipeline-real.db")
        val db = DatabaseFactory.init(dbPath.toString())
        val config = RealDataTestConfig.appConfig(
            databasePath = dbPath,
            initialVersions = listOf(RealDataTestConfig.V_1_21_1),
        )

        IngestPipeline(config).run()

        val versionService = VersionService(db)
        val version = versionService.getVersion(RealDataTestConfig.V_1_21_1)
        assertNotNull(version, "Pipeline did not insert version ${RealDataTestConfig.V_1_21_1}")
        assertTrue(version.classCount > 1_000, "Expected a substantial real class count, got ${version.classCount}")
        assertTrue(version.methodCount > 1_000, "Expected a substantial real method count, got ${version.methodCount}")
        assertTrue(version.fieldCount > 100, "Expected a substantial real field count, got ${version.fieldCount}")

        val classCase = RealDataTestConfig.blockStateClasses.single { it.version == RealDataTestConfig.V_1_21_1 }
        val translation = TranslationService(db, versionService).translate(
            name = classCase.yarn,
            from = "yarn",
            to = "mojmap",
            version = classCase.version,
            type = "class",
        )
        assertNotNull(translation)
        assertEquals(classCase.mojmap, translation.output.name)
        assertEquals(classCase.obf, translation.obfuscated)

        val search = SearchService(db, versionService).search(
            query = "BlockState",
            version = classCase.version,
            type = "class",
            namespace = "yarn",
            limit = 20,
            offset = 0,
            exact = false,
        )
        assertTrue(search.results.any { it.yarn == classCase.yarn }, "FTS search did not find ${classCase.yarn}: ${search.results}")

        val sourceRows = transaction(db) { SourceFileTable.selectAll().count() }
        assertTrue(sourceRows > 0, "Pipeline did not index source file hashes")
    }
}
