package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /**
     * Opens the prebuilt index for the stateless server. Every connection is set `query_only`, so the
     * server can never mutate the index (the offline `index` command is the sole writer). The index
     * must already exist — the server does not build it. query_only is used instead of an OS-level
     * read-only handle so a WAL-mode index opens cleanly.
     */
    fun openReadOnly(databasePath: String): Database {
        val path = Paths.get(databasePath).toAbsolutePath()
        require(Files.exists(path)) {
            "Index database not found at $path. Build it first with: mappinglens index"
        }
        val jdbcUrl = "jdbc:sqlite:${path.toString().replace('\\', '/')}"
        log.info("Opening SQLite index (query-only) at {}", path)
        return Database.connect(getNewConnection = {
            DriverManager.getConnection(jdbcUrl).also { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA query_only=ON;")
                    // Read tuning for a large (tens of GB) immutable index. mmap lets reads share the
                    // OS page cache without per-connection copies; temp_store=MEMORY keeps the search
                    // ORDER BY temp b-tree off disk. cache_size is modest because connections are
                    // short-lived (a fresh one per request), so a big per-connection cache never warms.
                    st.execute("PRAGMA mmap_size=2147483648;")   // 2 GiB
                    st.execute("PRAGMA cache_size=-65536;")       // 64 MiB
                    st.execute("PRAGMA temp_store=MEMORY;")
                    st.execute("PRAGMA busy_timeout=3000;")
                }
            }
        })
    }

    fun init(databasePath: String): Database {
        val path = Paths.get(databasePath).toAbsolutePath()
        path.parent?.let { Files.createDirectories(it) }
        log.info("Initializing SQLite database at {}", path)
        val jdbcUrl = "jdbc:sqlite:${path.toString().replace('\\', '/')}"
        try {
            DriverManager.getConnection(jdbcUrl).use { conn ->
                conn.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL;")
                    statement.execute("PRAGMA synchronous=NORMAL;")
                    statement.execute("PRAGMA foreign_keys=ON;")
                    statement.execute("PRAGMA temp_store=MEMORY;")
                }
            }
        } catch (e: Exception) {
            log.warn("Could not apply SQLite PRAGMA defaults for {}: {}", path, e.message)
        }
        val db = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC",
        )
        // Better defaults for SQLite
        transaction(db) {
            SchemaUtils.create(
                VersionTable,
                ClassTable,
                MethodTable,
                FieldTable,
                SourceFileTable,
            )

            // FTS5 virtual table
            exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
                    element_type UNINDEXED,
                    element_id UNINDEXED,
                    version_id UNINDEXED,
                    yarn_name,
                    mojmap_name,
                    intermediary_name,
                    obf_name,
                    simple_name,
                    tokenize='unicode61 remove_diacritics 2'
                );
                """.trimIndent()
            )

            // The cross-version identity of a member, spelled exactly as the diff spells it: the
            // stable name (the intermediary name, or the display name where the tiny files leave it
            // empty) and the stable descriptor. Without these two the rename query pairs every
            // member of a class against every member of its counterpart, which cost 726ms of a
            // 1.6s diff on one pair of versions and costs 33ms with them.
            // SQLite reads an index on expressions only when the query repeats the expression as
            // written, so both stay in step with `memberKey` and `keyDesc` in DiffService.
            listOf("methods", "fields").forEach { table ->
                exec(
                    """
                    CREATE INDEX IF NOT EXISTS ${table}_stable_ident ON $table(
                        class_id, ${stableMemberName()}, ${stableMemberDesc()}
                    );
                    """.trimIndent()
                )
            }
        }
        return db
    }
}
