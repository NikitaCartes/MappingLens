package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.ingestion.CorrespondenceResolver
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IndexerContractTest {

    @BeforeEach
    fun requireRealData() = RealDataTestConfig.assumeAvailable()

    @Test
    fun `index persists presence sortIndex releaseTime and is idempotent`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("idx.db")
        DatabaseFactory.init(dbPath.toString())
        val config = RealDataTestConfig.appConfig(dbPath, initialVersions = listOf(RealDataTestConfig.V_1_21))

        IngestPipeline(config).run()

        transaction {
            val row = VersionTable.selectAll()
                .where { VersionTable.versionId eq RealDataTestConfig.V_1_21 }.single()
            assertNotNull(row[VersionTable.sortIndex], "sortIndex should be assigned from semver order")
            assertEquals("release", row[VersionTable.releaseType], "release type from mc-meta")
            assertNotNull(row[VersionTable.releaseTime], "releaseTime should be populated from mc-meta")
        }

        // presence is persisted; Block exists on both the yarn and mojmap side.
        val blockPresence = transaction {
            ClassTable.selectAll().where { ClassTable.yarnName eq "net/minecraft/block/Block" }
                .firstOrNull()?.get(ClassTable.presence)
        }
        assertEquals(CorrespondenceResolver.PRESENCE_BOTH, blockPresence)

        val classCountAfterFirst = transaction { ClassTable.selectAll().count() }
        IngestPipeline(config).run() // re-run
        val classCountAfterSecond = transaction { ClassTable.selectAll().count() }
        assertEquals(classCountAfterFirst, classCountAfterSecond, "re-indexing must be idempotent")
    }
}
