package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.db.tables.stableMemberDesc
import xyz.nikitacartes.mappinglens.db.tables.stableMemberName
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadOnlyIndexTest {

    @Test
    fun `openReadOnly requires the index to already exist`(@TempDir tmp: Path) {
        assertFailsWith<IllegalArgumentException> {
            DatabaseFactory.openReadOnly(tmp.resolve("missing.db").toString())
        }
    }

    /**
     * The diff pairs members inside a class pair on the stable identity, which SQLite can only seek
     * when `${table}_stable_ident` spells the two expressions exactly as the query does. Drift
     * between the two answers the same and scans every member of the class against every member of
     * its counterpart: 726ms for one pair of versions where the seek costs 33ms.
     */
    @Test
    fun `the member identity index is seekable by the diff query`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("plan.db").toString()
        val db = DatabaseFactory.init(dbPath)

        listOf("methods", "fields").forEach { table ->
            val plan = transaction(db) {
                val conn = TransactionManager.current().connection.connection as java.sql.Connection
                conn.createStatement().use { st ->
                    st.executeQuery(
                        """
                        EXPLAIN QUERY PLAN
                        SELECT m.id FROM $table m
                        WHERE m.class_id = 1
                          AND ${stableMemberName("m.")} = 'x'
                          AND ${stableMemberDesc("m.")} = 'y'
                        """.trimIndent()
                    ).use { rs ->
                        buildString { while (rs.next()) append(rs.getString("detail")).append('\n') }
                    }
                }
            }
            assertTrue(
                plan.contains("${table}_stable_ident"),
                "$table diff lookup no longer uses the identity index: $plan",
            )
        }
    }

    @Test
    fun `server connection reads but cannot mutate the index`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("ro.db").toString()
        DatabaseFactory.init(dbPath) // build the (empty) index as the offline command would

        val ro = DatabaseFactory.openReadOnly(dbPath)

        // Reads work.
        val count = transaction(ro) { VersionTable.selectAll().count() }
        assertEquals(0L, count)

        // Writes are rejected (query_only) — this is the stateless invariant.
        assertFails {
            transaction(ro) {
                VersionTable.insertAndGetId {
                    it[versionId] = "x"
                    it[releaseType] = "release"
                    it[indexedAt] = "now"
                }
            }
        }
    }
}
