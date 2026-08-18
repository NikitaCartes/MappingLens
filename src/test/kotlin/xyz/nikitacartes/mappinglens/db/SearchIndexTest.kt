package xyz.nikitacartes.mappinglens.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexTest {

    /**
     * The rowid is the only place the search index records what a row is, so a match is read back
     * through this packing. Getting it wrong turns every hit into a lookup of the wrong element.
     */
    @Test
    fun `a rowid carries the element kind and id through a round trip`() {
        listOf("class", "method", "field").forEach { kind ->
            listOf(1, 2, 3, 4, 12345, 31_800_000).forEach { id ->
                val rowid = SearchIndex.rowid(kind, id)
                assertEquals(kind, SearchIndex.elementType(rowid), "kind of $kind/$id")
                assertEquals(id, SearchIndex.elementId(rowid), "id of $kind/$id")
            }
        }
        // Ranking groups classes before methods before fields, which is what `rowid & 3` sorts by.
        assertEquals(0, SearchIndex.kindCode("class"))
        assertEquals(1, SearchIndex.kindCode("method"))
        assertEquals(2, SearchIndex.kindCode("field"))
        assertEquals(null, SearchIndex.kindCode("parameter"))
    }

    @Test
    fun `init builds the search index beside the main one`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("mappinglens.db").toString()
        DatabaseFactory.init(dbPath)
        assertEquals(tmp.resolve("mappinglens-search.db"), SearchIndex.path(dbPath))
        assertTrue(Files.exists(SearchIndex.path(dbPath)), "search index was not created")
    }

    @Test
    fun `a prefix match returns the row that was indexed`(@TempDir tmp: Path) {
        val dbPath = tmp.resolve("mappinglens.db").toString()
        SearchIndex.openWritable(dbPath).use { conn ->
            SearchIndex.createTable(conn, versionRowId = 7)
            SearchIndex.insertRows(conn, 7, "method", listOf(42)) {
                SearchIndex.Names(
                    yarn = "net/minecraft/block/Block#getDefaultState",
                    mojmap = "net/minecraft/world/level/block/Block#defaultBlockState",
                    intermediary = "net/minecraft/class_2248#method_9564",
                    obf = "dnv#n",
                    simple = "getDefaultState",
                )
            }
            val table = SearchIndex.table(7)
            val rowids = conn.createStatement().use { st ->
                st.executeQuery("SELECT rowid FROM $table WHERE $table MATCH 'yarn_name:getDefault*'").use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1)) }
                }
            }
            assertEquals(listOf(SearchIndex.rowid("method", 42)), rowids)
            assertEquals("method", SearchIndex.elementType(rowids.single()))
            assertEquals(42, SearchIndex.elementId(rowids.single()))
        }
    }
}
