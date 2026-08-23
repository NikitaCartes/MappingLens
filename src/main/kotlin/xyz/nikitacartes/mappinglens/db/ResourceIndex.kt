package xyz.nikitacartes.mappinglens.db

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * The resource index: what every file of the mcmeta repository held in every version, in a file of
 * its own beside the main index.
 *
 * A file of its own because the resource explorer is optional. An operator who does not clone
 * mcmeta gets no file, the routes answer 404, and no other endpoint pays for the absence.
 *
 * The index stores no file content. It stores git blob ids, and `git cat-file blob <id>` produces
 * the bytes in about 75 ms. Git already keeps one copy of each unique blob and already computes the
 * diffs, so a second copy in SQLite costs disk and answers no faster.
 *
 * [RUNS] is the core table. A resource file keeps the same content over many consecutive versions,
 * so one row covers that whole range. About 216k rows describe 450 versions of four branches, and
 * the same rows answer the tree of a version, the diff of two versions, the history of a path and
 * the versions that hold a search hit.
 */
object ResourceIndex {
    private val log = LoggerFactory.getLogger(ResourceIndex::class.java)

    /** The mcmeta branches the indexer reads, in the order the version list prefers them. */
    val BRANCHES = listOf("assets", "diff", "registries", "atlas")

    const val RUNS = "runs"

    /** The resource index beside [databasePath]: `mappinglens.db` gives `mappinglens-resources.db`. */
    fun path(databasePath: String): Path {
        val main = Paths.get(databasePath).toAbsolutePath()
        return main.resolveSibling(main.fileName.toString().removeSuffix(".db") + "-resources.db")
    }

    fun exists(databasePath: String): Boolean = Files.exists(path(databasePath))

    /** Opens the index for the indexer, creating the file and the schema. The caller commits. */
    fun openWritable(databasePath: String): Connection {
        val file = path(databasePath)
        file.parent?.let { Files.createDirectories(it) }
        log.info("Opening resource index at {}", file)
        return DriverManager.getConnection(jdbcUrl(file)).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=OFF;")
                st.execute("PRAGMA temp_store=MEMORY;")
                st.execute("PRAGMA cache_size=-262144;") // 256 MiB
            }
            autoCommit = false
        }
    }

    /** Opens the index for reading, or null when the feature was never indexed. */
    fun openReadOnly(databasePath: String): Connection? {
        val file = path(databasePath)
        if (!Files.exists(file)) return null
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

    /** Drops and recreates every table. The indexer rebuilds the whole index in one run. */
    fun createSchema(conn: Connection) {
        conn.createStatement().use { st ->
            listOf(
                "translations_fts", "translations", "tr_keys",
                "content_fts", RUNS, "blobs", "paths", "branch_versions", "versions",
            ).forEach { st.execute("DROP TABLE IF EXISTS $it;") }
            st.execute(
                """
                CREATE TABLE versions(
                    ord INTEGER PRIMARY KEY,
                    mcmeta_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    version_id TEXT,          -- the MappingLens canonical id, null when unmatched
                    release_type TEXT,
                    release_time TEXT
                );
                """.trimIndent()
            )
            // Which versions a branch actually carries. The branches disagree: `atlas` holds 483
            // commits where `assets` holds 451.
            st.execute("CREATE TABLE branch_versions(branch TEXT NOT NULL, ord INTEGER NOT NULL);")
            st.execute("CREATE UNIQUE INDEX branch_versions_key ON branch_versions(branch, ord);")
            st.execute(
                """
                CREATE TABLE paths(
                    path_id INTEGER PRIMARY KEY,
                    branch TEXT NOT NULL,
                    path TEXT NOT NULL
                );
                """.trimIndent()
            )
            st.execute("CREATE UNIQUE INDEX paths_key ON paths(branch, path);")
            st.execute(
                """
                CREATE TABLE blobs(
                    blob_id INTEGER PRIMARY KEY,
                    sha TEXT NOT NULL UNIQUE,
                    size INTEGER NOT NULL,
                    kind TEXT NOT NULL          -- text | binary | lang
                );
                """.trimIndent()
            )
            // from_ord and to_ord are inclusive. to_ord is the newest indexed version while the file
            // still holds that content, so a live file has to_ord = the last version of its branch.
            st.execute(
                """
                CREATE TABLE $RUNS(
                    path_id INTEGER NOT NULL,
                    blob_id INTEGER NOT NULL,
                    from_ord INTEGER NOT NULL,
                    to_ord INTEGER NOT NULL
                );
                """.trimIndent()
            )
            st.execute("CREATE INDEX runs_path ON $RUNS(path_id, from_ord);")
            st.execute("CREATE INDEX runs_span ON $RUNS(from_ord, to_ord);")
            st.execute("CREATE INDEX runs_blob ON $RUNS(blob_id);")
            // Contentless: a hit reports which blob matched, and the bytes come from git.
            st.execute(
                "CREATE VIRTUAL TABLE content_fts USING fts5(text, " +
                    "tokenize='unicode61 remove_diacritics 2', content='');"
            )
            // A language file is a flat map of key to value, and a key keeps its value over many
            // versions. One row for each (file, key, value) run answers with the key, the language
            // and the version range, where a full-text index over the 12 GiB of raw language files
            // would answer that a 544 KB file contains the string.
            st.execute("CREATE TABLE tr_keys(key_id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE);")
            st.execute(
                """
                CREATE TABLE translations(
                    tr_id INTEGER PRIMARY KEY,
                    path_id INTEGER NOT NULL,
                    key_id INTEGER NOT NULL,
                    value TEXT NOT NULL,
                    from_ord INTEGER NOT NULL,
                    to_ord INTEGER NOT NULL
                );
                """.trimIndent()
            )
            st.execute("CREATE INDEX translations_key ON translations(key_id);")
            st.execute(
                "CREATE VIRTUAL TABLE translations_fts USING fts5(value, " +
                    "tokenize='unicode61 remove_diacritics 2', content='');"
            )
        }
        conn.commit()
    }

    private fun jdbcUrl(file: Path) = "jdbc:sqlite:${file.toString().replace('\\', '/')}"
}
