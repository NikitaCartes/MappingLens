package xyz.nikitacartes.mappinglens.ingestion

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.ResourcesConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.db.ResourceIndex
import xyz.nikitacartes.mappinglens.service.ResourceService
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The run-length index over a three-version fixture repository.
 *
 * The fixture holds one file that never changes, one that is added and then deleted, one that is
 * modified, and one language file whose keys change and disappear. Together they exercise every
 * branch of the run builder and of the translation builder.
 */
class ResourceIndexerTest {

    @Test
    fun `runs and translations cover the version ranges of a fixture repository`(@TempDir tmp: Path) {
        val repo = tmp.resolve("mcmeta").createDirectories()
        git(repo, "init", "-b", "assets")

        commit(repo, "1.0", "2020-01-01T00:00:00+00:00") {
            write(repo, "a.txt", "alpha")
            write(repo, "assets/minecraft/lang/en_us.json", """{"k1":"one","k2":"two"}""")
        }
        commit(repo, "1.1", "2020-02-01T00:00:00+00:00") {
            write(repo, "b.txt", "bee")
            write(repo, "assets/minecraft/lang/en_us.json", """{"k1":"one","k2":"TWO"}""")
        }
        commit(repo, "1.2", "2020-03-01T00:00:00+00:00") {
            write(repo, "a.txt", "alpha2")
            Files.delete(repo.resolve("b.txt"))
            write(repo, "assets/minecraft/lang/en_us.json", """{"k1":"one"}""")
        }

        val databasePath = tmp.resolve("idx.db").toString()
        ResourceIndexer(repo.toString(), databasePath, fts = "all").run()

        val conn = requireNotNull(ResourceIndex.openReadOnly(databasePath)) { "index was not written" }
        conn.use {
            assertEquals(
                listOf(0 to "1.0", 1 to "1.1", 2 to "1.2"),
                conn.rows("SELECT ord, mcmeta_id FROM versions ORDER BY ord") { it.getInt(1) to it.getString(2) },
                "versions are ordered as the branch commits them",
            )

            assertEquals(listOf(0 to 1, 2 to 2), runsOf(conn, "a.txt"), "a.txt keeps its first content over 1.0 and 1.1")
            assertEquals(listOf(1 to 1), runsOf(conn, "b.txt"), "b.txt exists in 1.1 alone")
            assertEquals(
                listOf(0 to 0, 1 to 1, 2 to 2),
                runsOf(conn, "version.json"),
                "version.json changes in every commit, so each version gets a run of its own",
            )

            // The tree of each version, rebuilt from the runs.
            assertEquals(setOf("a.txt", "assets/minecraft/lang/en_us.json", "version.json"), treeAt(conn, 0))
            assertEquals(setOf("a.txt", "b.txt", "assets/minecraft/lang/en_us.json", "version.json"), treeAt(conn, 1))
            assertEquals(setOf("a.txt", "assets/minecraft/lang/en_us.json", "version.json"), treeAt(conn, 2))

            val translations = conn.rows(
                """
                SELECT k.key, t.value, t.from_ord, t.to_ord FROM translations t
                JOIN tr_keys k ON k.key_id = t.key_id ORDER BY k.key, t.from_ord
                """.trimIndent(),
            ) { listOf(it.getString(1), it.getString(2), it.getInt(3).toString(), it.getInt(4).toString()) }
            assertEquals(
                listOf(
                    listOf("k1", "one", "0", "2"),
                    listOf("k2", "two", "0", "0"),
                    listOf("k2", "TWO", "1", "1"),
                ),
                translations,
                "an unchanged key is one row; a changed key closes at the version before the change",
            )

            val hits = conn.rows(
                "SELECT p.path FROM (SELECT rowid FROM content_fts WHERE content_fts MATCH ?) m " +
                    "JOIN runs r ON r.blob_id = m.rowid JOIN paths p ON p.path_id = r.path_id",
                "\"alpha2\"",
            ) { it.getString(1) }
            assertTrue(hits.contains("a.txt"), "the content index finds the new text of a.txt, got $hits")
        }
    }

    /**
     * A version-scoped search reports what the version holds, and not what is left of the highest
     * ranked matches after the version filter runs. The two decoy files rank above `keep.txt`,
     * because bm25 puts a short file with two occurrences above a long file with one, and both are
     * deleted before the version searched for.
     */
    @Test
    fun `a version-scoped search fills its limit from the versions that carry the term`(@TempDir tmp: Path) {
        val repo = tmp.resolve("mcmeta").createDirectories()
        git(repo, "init", "-b", "assets")

        val filler = (1..400).joinToString(" ") { "filler$it" }
        commit(repo, "1.0", "2020-01-01T00:00:00+00:00") {
            write(repo, "decoy1.txt", "zebra zebra")
            write(repo, "decoy2.txt", "zebra zebra")
            write(repo, "keep.txt", "$filler zebra $filler")
        }
        commit(repo, "1.1", "2020-02-01T00:00:00+00:00") {
            Files.delete(repo.resolve("decoy1.txt"))
            Files.delete(repo.resolve("decoy2.txt"))
        }

        val databasePath = tmp.resolve("idx.db").toString()
        ResourceIndexer(repo.toString(), databasePath, fts = "content").run()

        val service = ResourceService(
            AppConfig(
                databasePath = databasePath,
                sources = SourcesConfig("", "", "", ""),
                initialVersions = emptyList(),
                search = SearchConfig(maxResults = 100, defaultResults = 50),
                resources = ResourcesConfig(repo = repo.toString()),
            ),
        )
        assertTrue(service.available, "the fixture index counts as available")

        val scoped = requireNotNull(service.search("zebra", "content", "1.1", 1))
        assertEquals(
            listOf("keep.txt"),
            scoped.results.map { it.path },
            "1.1 holds keep.txt alone, and one hit was asked for",
        )
        val unscoped = requireNotNull(service.search("zebra", "content", null, 3))
        assertEquals(
            setOf("decoy1.txt", "decoy2.txt", "keep.txt"),
            unscoped.results.map { it.path }.toSet(),
            "without a version every file that ever held the term is a hit",
        )
    }

    /** The (from_ord, to_ord) pairs of one path, oldest first. */
    private fun runsOf(conn: Connection, path: String): List<Pair<Int, Int>> = conn.rows(
        "SELECT r.from_ord, r.to_ord FROM paths p JOIN runs r ON r.path_id = p.path_id " +
            "WHERE p.path = ? ORDER BY r.from_ord",
        path,
    ) { it.getInt(1) to it.getInt(2) }

    private fun treeAt(conn: Connection, ord: Int): Set<String> = conn.rows(
        "SELECT p.path FROM paths p JOIN runs r ON r.path_id = p.path_id " +
            "WHERE r.from_ord <= ? AND r.to_ord >= ?",
        ord.toString(), ord.toString(),
    ) { it.getString(1) }.toSet()

    private fun <T> Connection.rows(sql: String, vararg params: String, read: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, value -> ps.setString(i + 1, value) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }

    private fun write(repo: Path, path: String, text: String) {
        val file = repo.resolve(path)
        file.parent?.createDirectories()
        file.writeText(text)
    }

    /** One version of the fixture: the files [changes] writes, plus the `version.json` mcmeta writes. */
    private fun commit(repo: Path, id: String, time: String, changes: () -> Unit) {
        changes()
        write(
            repo,
            "version.json",
            """{"id":"$id","name":"$id","type":"release","release_time":"$time"}""",
        )
        git(repo, "add", "-A")
        git(repo, "commit", "-m", id)
    }

    private fun git(repo: Path, vararg args: String) {
        val command = listOf("git", "-C", repo.toString(), "-c", "user.name=test", "-c", "user.email=test@example.com") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
