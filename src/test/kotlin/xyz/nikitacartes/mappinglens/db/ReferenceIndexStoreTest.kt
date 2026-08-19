package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.service.ReferenceService.Referrer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceIndexStoreTest {

    @Test
    fun `a stored class comes back with its counts, nulls and synthetic names`(@TempDir tmp: Path) {
        val db = tmp.resolve("mappinglens.db").toString()
        val index = mapOf(
            "B" to mapOf(
                "" to mapOf(Referrer("A", null, null, "class") to 1),
                "foo:()V" to mapOf(
                    Referrer("A", "m", "()V", "method") to 2,
                    Referrer("A", "tick", "()V", "method", synthetic = "lambda\$tick\$3") to 1,
                ),
            ),
        )

        ReferenceIndexStore.openWritable(db).use { conn ->
            ReferenceIndexStore.write(conn, "1.21.1", "mojmap", index)
            conn.commit()
        }

        ReferenceIndexStore.openReadOnly(db)!!.use { conn ->
            assertEquals(setOf("1.21.1" to "mojmap"), ReferenceIndexStore.built(conn))
            assertEquals(index.getValue("B"), ReferenceIndexStore.read(conn, "1.21.1", "mojmap", "B"))
            assertTrue(ReferenceIndexStore.read(conn, "1.21.1", "mojmap", "Absent").isEmpty())
            assertTrue(ReferenceIndexStore.read(conn, "1.21.2", "mojmap", "B").isEmpty(), "another version")
        }
    }

    @Test
    fun `rebuilding a version replaces what was there`(@TempDir tmp: Path) {
        val db = tmp.resolve("mappinglens.db").toString()
        ReferenceIndexStore.openWritable(db).use { conn ->
            ReferenceIndexStore.write(conn, "1.21.1", "mojmap", mapOf("Gone" to mapOf("" to mapOf(Referrer("A", null, null, "class") to 1))))
            ReferenceIndexStore.write(conn, "1.21.1", "mojmap", mapOf("Kept" to mapOf("" to mapOf(Referrer("A", null, null, "class") to 1))))
            conn.commit()
        }
        ReferenceIndexStore.openReadOnly(db)!!.use { conn ->
            assertTrue(ReferenceIndexStore.read(conn, "1.21.1", "mojmap", "Gone").isEmpty())
            assertTrue(ReferenceIndexStore.read(conn, "1.21.1", "mojmap", "Kept").isNotEmpty())
        }
    }

    @Test
    fun `no file means no connection, and the server falls back to scanning`(@TempDir tmp: Path) {
        assertEquals(null, ReferenceIndexStore.openReadOnly(tmp.resolve("mappinglens.db").toString()))
    }
}
