package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.service.Referrers
import xyz.nikitacartes.mappinglens.service.ReferenceService.Referrer
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The prebuilt reverse-reference index: one row for each class of each (version, namespace), in a
 * file of its own beside the main index.
 *
 * Without it the server scans a version's whole named jar on the first question about that version:
 * 0.4 to 0.8s, and 34 to 61 MB held in memory for as long as the index is cached. Reading a class out of
 * this file instead costs 0.7ms with a fresh connection and 0.1ms on a warm one, and holds nothing.
 *
 * One row for each class rather than for each member, because the class is the unit every reader
 * asks for, and because it compresses: 12189 blobs of 26.2 deflate 6.8 times where 141555 member
 * blobs of the same data reach 2.7. A whole (version, namespace) costs about 11 MB on disk.
 *
 * The file is optional and holds whichever versions were built into it. Anything it does not cover
 * falls back to the scan, so a partial file (releases alone, say) is a valid file.
 */
object ReferenceIndexStore {
    private val log = LoggerFactory.getLogger(ReferenceIndexStore::class.java)

    /** The reference index beside [databasePath]: `mappinglens.db` gives `mappinglens-refs.db`. */
    fun path(databasePath: String): Path {
        val main = Paths.get(databasePath).toAbsolutePath()
        return main.resolveSibling(main.fileName.toString().removeSuffix(".db") + "-refs.db")
    }

    fun exists(databasePath: String): Boolean = Files.exists(path(databasePath))

    /** Opens the file for reading, or returns null when there is none. The caller closes it. */
    fun openReadOnly(databasePath: String): Connection? {
        val file = path(databasePath)
        if (!Files.exists(file)) return null
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA query_only=ON;")
                st.execute("PRAGMA mmap_size=2147483648;")   // 2 GiB
                st.execute("PRAGMA cache_size=-16384;")       // 16 MiB
                st.execute("PRAGMA busy_timeout=3000;")
            }
        }
    }

    /** Opens the file for the indexer, creating it and its tables when they are not there yet. */
    fun openWritable(databasePath: String): Connection {
        val file = path(databasePath)
        file.parent?.let { Files.createDirectories(it) }
        log.info("Opening reference index at {}", file)
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=NORMAL;")
                st.execute("PRAGMA page_size=8192;")
                st.execute("PRAGMA temp_store=MEMORY;")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS refs(" +
                        "version TEXT, ns TEXT, owner TEXT, blob BLOB, " +
                        "PRIMARY KEY(version, ns, owner)) WITHOUT ROWID;"
                )
                // Which (version, namespace) pairs are complete. Asking `refs` itself would mean a
                // scan, and an absent class is indistinguishable from an unbuilt version there.
                st.execute(
                    "CREATE TABLE IF NOT EXISTS refs_built(" +
                        "version TEXT, ns TEXT, PRIMARY KEY(version, ns)) WITHOUT ROWID;"
                )
            }
            autoCommit = false
        }
    }

    /** The (version, namespace) pairs the file holds. Read once per connection; it is a small table. */
    fun built(conn: Connection): Set<Pair<String, String>> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT version, ns FROM refs_built").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    /** Replaces everything stored for one (version, namespace). The caller commits. */
    fun write(conn: Connection, version: String, namespace: String, index: Map<String, Map<String, Referrers>>) {
        conn.prepareStatement("DELETE FROM refs WHERE version = ? AND ns = ?").use { ps ->
            ps.setString(1, version); ps.setString(2, namespace); ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO refs(version, ns, owner, blob) VALUES (?, ?, ?, ?)").use { ps ->
            for ((owner, members) in index) {
                ps.setString(1, version)
                ps.setString(2, namespace)
                ps.setString(3, owner)
                ps.setBytes(4, deflate(encode(members)))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT OR REPLACE INTO refs_built(version, ns) VALUES (?, ?)").use { ps ->
            ps.setString(1, version); ps.setString(2, namespace); ps.executeUpdate()
        }
    }

    /** Everything that references any member of [owner], or an empty map when nothing does. */
    fun read(conn: Connection, version: String, namespace: String, owner: String): Map<String, Referrers> =
        conn.prepareStatement("SELECT blob FROM refs WHERE version = ? AND ns = ? AND owner = ?").use { ps ->
            ps.setString(1, version); ps.setString(2, namespace); ps.setString(3, owner)
            ps.executeQuery().use { rs -> if (rs.next()) decode(inflate(rs.getBytes(1))) else emptyMap() }
        }

    /**
     * One class as text: a member key on its own line, then its referring sites indented by a tab.
     * Java names and descriptors carry neither tab nor newline, so no escaping is needed, and text
     * of this shape deflates better than any packing worth writing by hand.
     */
    private fun encode(members: Map<String, Referrers>): ByteArray {
        val out = StringBuilder()
        for ((member, referrers) in members) {
            out.append(member).append('\n')
            for ((referrer, count) in referrers) {
                out.append('\t').append(referrer.owner)
                    .append('\t').append(referrer.member.orEmpty())
                    .append('\t').append(referrer.descriptor.orEmpty())
                    .append('\t').append(referrer.kind)
                    .append('\t').append(referrer.synthetic.orEmpty())
                    .append('\t').append(count)
                    .append('\n')
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Map<String, Referrers> {
        val members = HashMap<String, MutableMap<Referrer, Int>>()
        var current: MutableMap<Referrer, Int>? = null
        // The class itself is keyed by the empty string, so an empty line is a member header
        // rather than padding. Only the terminator of the very last row is dropped.
        for (line in String(bytes, Charsets.UTF_8).removeSuffix("\n").split('\n')) {
            if (line.firstOrNull() != '\t') {
                current = members.getOrPut(line) { HashMap() }
                continue
            }
            val f = line.substring(1).split('\t')
            current?.put(
                Referrer(
                    owner = f[0],
                    member = f[1].ifEmpty { null },
                    descriptor = f[2].ifEmpty { null },
                    kind = f[3],
                    synthetic = f[4].ifEmpty { null },
                ),
                f[5].toInt(),
            )
        }
        return members
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(bytes) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun jdbcUrl(file: Path) = "jdbc:sqlite:${file.toString().replace('\\', '/')}"
}
