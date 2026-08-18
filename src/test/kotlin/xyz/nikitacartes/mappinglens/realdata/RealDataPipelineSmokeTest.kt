package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.db.SearchIndex
import xyz.nikitacartes.mappinglens.db.tables.SourceFileTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.service.SearchService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

        val search = SearchService(db, versionService, Fixtures.dbPath(db)).search(
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

    /**
     * A run that skips an already-indexed version rebuilds that version's search table from the
     * index when it has none, which is how an index built before the search index moved to its own
     * file is filled. The rebuilt table has to spell the names exactly as the ingest spelled them,
     * owner qualification included, so the same queries have to match the same rows.
     */
    @Test
    fun `a later run rebuilds a missing search table to the same rows`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("pipeline-real.db")
        val db = DatabaseFactory.init(dbPath.toString())
        val config = RealDataTestConfig.appConfig(
            databasePath = dbPath,
            initialVersions = listOf(RealDataTestConfig.V_1_21_1),
        )
        IngestPipeline(config).run()

        val versionRowId = transaction(db) {
            VersionTable.selectAll().where { VersionTable.versionId eq RealDataTestConfig.V_1_21_1 }
                .single()[VersionTable.id].value
        }
        val table = SearchIndex.table(versionRowId)
        val queries = listOf(
            "yarn_name:BlockState*", "mojmap_name:BlockState*", "intermediary_name:class_2680*",
            "yarn_name:getDefaultState*", "obf_name:dpb*", "block*", "\"BlockState\"",
        )
        fun probe(): Map<String, List<Long>> = SearchIndex.openReadOnly(dbPath.toString()).use { conn ->
            queries.associateWith { q ->
                conn.prepareStatement("SELECT rowid FROM $table WHERE $table MATCH ? ORDER BY rowid").use { ps ->
                    ps.setString(1, q)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
                }
            }
        }
        val ingested = probe()
        assertTrue(ingested.getValue("yarn_name:BlockState*").isNotEmpty(), "ingest indexed no names")

        SearchIndex.openWritable(dbPath.toString()).use { conn ->
            conn.createStatement().use { it.execute("DROP TABLE $table") }
            conn.commit()
        }
        IngestPipeline(config).run() // skips the indexed version, so only the backfill can rebuild it

        assertEquals(ingested, probe(), "the rebuilt search table does not match the ingested one")
    }
}
