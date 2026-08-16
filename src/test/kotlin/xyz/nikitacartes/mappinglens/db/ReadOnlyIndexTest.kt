package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ReadOnlyIndexTest {

    @Test
    fun `openReadOnly requires the index to already exist`(@TempDir tmp: Path) {
        assertFailsWith<IllegalArgumentException> {
            DatabaseFactory.openReadOnly(tmp.resolve("missing.db").toString())
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
