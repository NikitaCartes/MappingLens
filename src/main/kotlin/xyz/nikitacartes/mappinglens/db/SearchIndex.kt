package xyz.nikitacartes.mappinglens.db

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * The name search index: one contentless FTS5 table for each version, in a file of its own beside
 * the main index.
 *
 * One table for each version because FTS5 answers a prefix term by merging the doclists of every
 * term that carries the prefix over the whole table, before a rowid filter narrows anything. A
 * single table over all 526 versions therefore reads 5.5M postings of `get*` to rank the 13697 that
 * belong to the version asked for: 959ms, where that version's own table answers in 24ms. The split
 * costs 2% in bytes.
 *
 * A file of its own because 526 virtual tables add about 2600 rows to `sqlite_master`, and the
 * server opens a fresh connection for each request. Parsing them costs 7ms that every endpoint
 * would pay. In a separate file only a search opens them, so only a search pays.
 *
 * The tables are contentless. The server reads no name back out of them, only which row matched and
 * how bm25 ranks it, so the copy of every indexed name that FTS5 stores by default is bytes nothing
 * reads. Over 52.3M rows the whole file is 5.3 GB where the single table it replaces was 14.9 GB.
 * The row identity rides in the rowid instead (see [rowid]), which leaves no side table either.
 */
object SearchIndex {
    private val log = LoggerFactory.getLogger(SearchIndex::class.java)

    /** Element kinds in the order search results group them, and the low two bits of a rowid. */
    private val KINDS = listOf("class", "method", "field")

    /** The search index beside [databasePath]: `mappinglens.db` gives `mappinglens-search.db`. */
    fun path(databasePath: String): Path {
        val main = Paths.get(databasePath).toAbsolutePath()
        return main.resolveSibling(main.fileName.toString().removeSuffix(".db") + "-search.db")
    }

    /** The FTS5 table that holds the names of the version with row id [versionRowId]. */
    fun table(versionRowId: Int): String = "search_v$versionRowId"

    /**
     * A row's identity, packed so the FTS5 table needs no stored columns at all: the element kind in
     * the low two bits, the element's row id above them. The kind codes are the order results are
     * grouped by, so `ORDER BY rowid & 3` puts classes before methods before fields.
     */
    fun rowid(elementType: String, elementId: Int): Long =
        (elementId.toLong() shl 2) or kindCode(elementType)!!.toLong()

    fun elementId(rowid: Long): Int = (rowid shr 2).toInt()

    fun elementType(rowid: Long): String? = KINDS.getOrNull((rowid and 3L).toInt())

    /** The code [elementType] carries in a rowid, or null when it names no element kind. */
    fun kindCode(elementType: String): Int? = KINDS.indexOf(elementType).takeIf { it >= 0 }

    /**
     * Opens the search index for the indexer, creating the file when it is not there yet. The
     * connection does not auto-commit: a version writes tens of thousands of rows in batches, and
     * committing each batch on its own turned the build into hours. The caller commits per version.
     * The PRAGMAs run first, because `journal_mode` cannot be set inside a transaction.
     */
    fun openWritable(databasePath: String): Connection {
        val file = path(databasePath)
        file.parent?.let { Files.createDirectories(it) }
        log.info("Opening search index at {}", file)
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=NORMAL;")
                st.execute("PRAGMA temp_store=MEMORY;")
            }
            autoCommit = false
        }
    }

    /** Opens the search index for reading. The caller closes it; the server opens one per search. */
    fun openReadOnly(databasePath: String): Connection {
        val file = path(databasePath)
        require(Files.exists(file)) {
            "Search index not found at $file. Build it first with: mappinglens index"
        }
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA query_only=ON;")
                st.execute("PRAGMA mmap_size=2147483648;")   // 2 GiB
                st.execute("PRAGMA cache_size=-65536;")       // 64 MiB
                st.execute("PRAGMA temp_store=MEMORY;")
                st.execute("PRAGMA busy_timeout=3000;")
            }
        }
    }

    /** Builds the table of [versionRowId] from scratch, replacing what an earlier run left there. */
    fun createTable(conn: Connection, versionRowId: Int) {
        val table = table(versionRowId)
        conn.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS $table;")
            st.execute(
                """
                CREATE VIRTUAL TABLE $table USING fts5(
                    yarn_name,
                    mojmap_name,
                    intermediary_name,
                    obf_name,
                    simple_name,
                    tokenize='unicode61 remove_diacritics 2',
                    content=''
                );
                """.trimIndent()
            )
        }
    }

    /** Inserts names under the packed rowid of [elementType] and the element row ids given. */
    fun insertRows(
        conn: Connection,
        versionRowId: Int,
        elementType: String,
        elementIds: List<Int>,
        names: (Int) -> Names,
    ) {
        if (elementIds.isEmpty()) return
        insertStatement(conn, versionRowId).use { ps ->
            elementIds.forEachIndexed { idx, elementId ->
                addRow(ps, elementType, elementId, names(idx))
            }
            ps.executeBatch()
        }
    }

    /** An insert into [versionRowId]'s table, filled through [addRow]. The caller closes it. */
    fun insertStatement(conn: Connection, versionRowId: Int): PreparedStatement =
        conn.prepareStatement(
            "INSERT INTO ${table(versionRowId)}(rowid, yarn_name, mojmap_name, " +
                "intermediary_name, obf_name, simple_name) VALUES (?, ?, ?, ?, ?, ?)"
        )

    /** Adds one row to a batch of [insertStatement]. FTS5 has no nulls, so an absent name is "". */
    fun addRow(ps: PreparedStatement, elementType: String, elementId: Int, names: Names) {
        ps.setLong(1, rowid(elementType, elementId))
        ps.setString(2, names.yarn ?: "")
        ps.setString(3, names.mojmap ?: "")
        ps.setString(4, names.intermediary ?: "")
        ps.setString(5, names.obf ?: "")
        ps.setString(6, names.simple ?: "")
        ps.addBatch()
    }

    /** The five name columns of one indexed row. */
    data class Names(
        val yarn: String?,
        val mojmap: String?,
        val intermediary: String?,
        val obf: String?,
        val simple: String?,
    )

    private fun jdbcUrl(file: Path) = "jdbc:sqlite:${file.toString().replace('\\', '/')}"
}
