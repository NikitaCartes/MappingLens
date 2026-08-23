package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.ResourceIndex
import xyz.nikitacartes.mappinglens.ingestion.ResourceRepository
import xyz.nikitacartes.mappinglens.model.*
import java.nio.file.Paths
import java.sql.Connection
import java.sql.ResultSet

/**
 * Reads the resource index and the mcmeta clone.
 *
 * A fresh connection is opened for each call, as everywhere else in the stateless server: SQLite
 * costs about a millisecond to open a file and a pool would hold locks across requests. The index
 * holds no file content, so every byte the caller asks for comes from `git cat-file`.
 */
class ResourceService(private val appConfig: AppConfig) {

    private val repo = ResourceRepository(Paths.get(appConfig.resources.repo.ifBlank { "." }))

    /** False when no mcmeta clone is configured or the resource index was never built. */
    val available: Boolean =
        appConfig.resources.repo.isNotBlank() && ResourceIndex.exists(appConfig.databasePath)

    private fun <T> read(block: (Connection) -> T): T? =
        ResourceIndex.openReadOnly(appConfig.databasePath)?.use(block)

    fun versions(): ResourceVersionListResponse? = read { conn ->
        val branches = HashMap<Int, MutableList<String>>()
        conn.query("SELECT ord, branch FROM branch_versions") { rs ->
            branches.getOrPut(rs.getInt(1)) { mutableListOf() } += rs.getString(2)
        }
        val versions = mutableListOf<ResourceVersion>()
        conn.query(
            "SELECT ord, mcmeta_id, name, version_id, release_type, release_time FROM versions ORDER BY ord DESC"
        ) { rs ->
            versions += ResourceVersion(
                ord = rs.getInt(1),
                mcmetaId = rs.getString(2),
                name = rs.getString(3),
                versionId = rs.getString(4),
                releaseType = rs.getString(5),
                releaseTime = rs.getString(6),
                branches = branches[rs.getInt(1)]?.sorted().orEmpty(),
            )
        }
        ResourceVersionListResponse(versions)
    }

    /** One directory level of [branch] at [version], or null when the version is unknown. */
    fun tree(version: String, branch: String, path: String): ResourceTreeResponse? = read { conn ->
        val ord = conn.ordOf(version) ?: return@read null
        val prefix = normalizeDir(path)
        val dirs = sortedSetOf<String>()
        val files = mutableListOf<ResourceEntry>()
        conn.query(
            """
            SELECT p.path, b.sha, b.size
            FROM paths p JOIN runs r ON r.path_id = p.path_id JOIN blobs b ON b.blob_id = r.blob_id
            WHERE p.branch = ? AND p.path >= ? AND p.path < ? AND r.from_ord <= ? AND r.to_ord >= ?
            """.trimIndent(),
            branch, prefix, prefixEnd(prefix), ord, ord,
        ) { rs ->
            val full = rs.getString(1)
            val rest = full.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                dirs += rest.substring(0, slash)
            } else {
                files += ResourceEntry(rest, full, false, rs.getLong(3), rs.getString(2))
            }
        }
        ResourceTreeResponse(
            version = version,
            branch = branch,
            path = prefix.removeSuffix("/"),
            entries = dirs.map { ResourceEntry(it, prefix + it, true) } + files.sortedBy { it.name },
        )
    }

    /** The blob id and bytes of one file, or null when it is absent at that version. */
    fun file(version: String, branch: String, path: String): Pair<String, ByteArray>? = read { conn ->
        val ord = conn.ordOf(version) ?: return@read null
        var sha: String? = null
        conn.query(
            """
            SELECT b.sha FROM paths p JOIN runs r ON r.path_id = p.path_id JOIN blobs b ON b.blob_id = r.blob_id
            WHERE p.branch = ? AND p.path = ? AND r.from_ord <= ? AND r.to_ord >= ? LIMIT 1
            """.trimIndent(),
            branch, path, ord, ord,
        ) { rs -> sha = rs.getString(1) }
        sha?.let { s -> repo.read(s)?.let { s to it } }
    }

    /** Every path of [branch] that differs between the two versions, under [path]. */
    fun diff(from: String, to: String, branch: String, path: String): ResourceDiffResponse? = read { conn ->
        val fromOrd = conn.ordOf(from) ?: return@read null
        val toOrd = conn.ordOf(to) ?: return@read null
        val before = conn.state(branch, normalizeDir(path), fromOrd)
        val after = conn.state(branch, normalizeDir(path), toOrd)
        val changes = (before.keys + after.keys).sorted().mapNotNull { p ->
            val a = before[p]
            val b = after[p]
            when {
                a == b -> null
                a == null -> ResourceChange(p, "added", null, b)
                b == null -> ResourceChange(p, "removed", a, null)
                else -> ResourceChange(p, "modified", a, b)
            }
        }
        ResourceDiffResponse(from, to, branch, changes)
    }

    /** The version ranges over which one path held each of its contents, oldest first. */
    fun history(branch: String, path: String): ResourceHistoryResponse? = read { conn ->
        val names = conn.versionNames()
        val entries = mutableListOf<ResourceHistoryEntry>()
        conn.query(
            """
            SELECT r.from_ord, r.to_ord, b.sha, b.size
            FROM paths p JOIN runs r ON r.path_id = p.path_id JOIN blobs b ON b.blob_id = r.blob_id
            WHERE p.branch = ? AND p.path = ? ORDER BY r.from_ord
            """.trimIndent(),
            branch, path,
        ) { rs ->
            entries += ResourceHistoryEntry(
                fromVersion = names[rs.getInt(1)].orEmpty(),
                toVersion = names[rs.getInt(2)].orEmpty(),
                sha = rs.getString(3),
                size = rs.getLong(4),
            )
        }
        if (entries.isEmpty()) null else ResourceHistoryResponse(branch, path, entries)
    }

    /**
     * Full-text search over the file contents or over the translations.
     *
     * A content hit names the blob that matched, so one row is reported for each path and version
     * range that held it. A translation hit names the key, the language file and the range.
     */
    fun search(query: String, type: String, version: String?, limit: Int): ResourceSearchResponse? = read { conn ->
        val names = conn.versionNames()
        val ord = version?.let { conn.ordOf(it) }
        val match = ftsQuery(query)
        val results = if (type == "translation") {
            searchTranslations(conn, match, ord, limit, names)
        } else {
            searchContent(conn, match, ord, limit, names)
        }
        ResourceSearchResponse(query, type, results.size, results)
    }

    /**
     * The version filter belongs inside the ranked subquery, and not after it.
     *
     * A term that a version holds in 145 files is held by many more blobs over the whole version
     * line, and the newest blob of a file is not the highest ranked one. A `LIMIT` taken before the
     * filter therefore reports what is left of the top 50 matches, which for `copper_golem` at the
     * newest version was 31 rows of 145, and 0 rows at a limit of 3. `EXISTS` costs nothing here,
     * because `ORDER BY rank` already reads every match.
     */
    private fun searchContent(
        conn: Connection,
        query: String,
        ord: Int?,
        limit: Int,
        names: Map<Int, String>,
    ): List<ResourceHit> {
        val hits = mutableListOf<ResourceHit>()
        val at = ord ?: -1
        conn.query(
            """
            SELECT p.branch, p.path, r.from_ord, r.to_ord, b.sha
            FROM (
                SELECT rowid FROM content_fts f
                WHERE content_fts MATCH ?
                  AND (? < 0 OR EXISTS (
                      SELECT 1 FROM runs v
                      WHERE v.blob_id = f.rowid AND v.from_ord <= ? AND v.to_ord >= ?))
                ORDER BY rank LIMIT ?
            ) m
            JOIN runs r ON r.blob_id = m.rowid
            JOIN paths p ON p.path_id = r.path_id
            JOIN blobs b ON b.blob_id = m.rowid
            WHERE (? < 0 OR (r.from_ord <= ? AND r.to_ord >= ?))
            LIMIT ?
            """.trimIndent(),
            query, at, at, at, limit, at, at, at, limit,
        ) { rs ->
            hits += ResourceHit(
                branch = rs.getString(1),
                path = rs.getString(2),
                fromVersion = names[rs.getInt(3)].orEmpty(),
                toVersion = names[rs.getInt(4)].orEmpty(),
                sha = rs.getString(5),
            )
        }
        return hits
    }

    /** The version filter sits inside the ranked subquery, for the reason [searchContent] gives. */
    private fun searchTranslations(
        conn: Connection,
        query: String,
        ord: Int?,
        limit: Int,
        names: Map<Int, String>,
    ): List<ResourceHit> {
        val hits = mutableListOf<ResourceHit>()
        val at = ord ?: -1
        conn.query(
            """
            SELECT p.branch, p.path, t.from_ord, t.to_ord, k.key, t.value
            FROM (
                SELECT rowid FROM translations_fts f
                WHERE translations_fts MATCH ?
                  AND (? < 0 OR EXISTS (
                      SELECT 1 FROM translations v
                      WHERE v.tr_id = f.rowid AND v.from_ord <= ? AND v.to_ord >= ?))
                ORDER BY rank LIMIT ?
            ) m
            JOIN translations t ON t.tr_id = m.rowid
            JOIN tr_keys k ON k.key_id = t.key_id
            JOIN paths p ON p.path_id = t.path_id
            WHERE (? < 0 OR (t.from_ord <= ? AND t.to_ord >= ?))
            LIMIT ?
            """.trimIndent(),
            query, at, at, at, limit, at, at, at, limit,
        ) { rs ->
            hits += ResourceHit(
                branch = rs.getString(1),
                path = rs.getString(2),
                fromVersion = names[rs.getInt(3)].orEmpty(),
                toVersion = names[rs.getInt(4)].orEmpty(),
                key = rs.getString(5),
                value = rs.getString(6),
            )
        }
        return hits
    }

    /** Path to blob sha for every file of [branch] under [prefix] at [ord]. */
    private fun Connection.state(branch: String, prefix: String, ord: Int): Map<String, String> {
        val out = HashMap<String, String>()
        query(
            """
            SELECT p.path, b.sha
            FROM paths p JOIN runs r ON r.path_id = p.path_id JOIN blobs b ON b.blob_id = r.blob_id
            WHERE p.branch = ? AND p.path >= ? AND p.path < ? AND r.from_ord <= ? AND r.to_ord >= ?
            """.trimIndent(),
            branch, prefix, prefixEnd(prefix), ord, ord,
        ) { rs -> out[rs.getString(1)] = rs.getString(2) }
        return out
    }

    /** A version may be named by its mcmeta id, its display name or its MappingLens version id. */
    private fun Connection.ordOf(version: String): Int? {
        var ord: Int? = null
        query(
            "SELECT ord FROM versions WHERE mcmeta_id = ? OR name = ? OR version_id = ? LIMIT 1",
            version, version, version,
        ) { rs -> ord = rs.getInt(1) }
        return ord
    }

    private fun Connection.versionNames(): Map<Int, String> {
        val out = HashMap<Int, String>()
        query("SELECT ord, name FROM versions") { rs -> out[rs.getInt(1)] = rs.getString(2) }
        return out
    }

    /** Runs [statement] with [params] bound in order and hands every row to [row]. */
    private fun Connection.query(statement: String, vararg params: Any?, row: (ResultSet) -> Unit) {
        prepareStatement(statement).use { ps ->
            params.forEachIndexed { i, value ->
                when (value) {
                    is Int -> ps.setInt(i + 1, value)
                    else -> ps.setString(i + 1, value?.toString())
                }
            }
            ps.executeQuery().use { rs -> while (rs.next()) row(rs) }
        }
    }

    companion object {
        /** `""` and `"assets"` both mean a directory; the tree query needs the trailing slash. */
        fun normalizeDir(path: String): String {
            val clean = path.trim().trim('/')
            return if (clean.isEmpty()) "" else "$clean/"
        }

        /** The exclusive upper bound of a prefix range, so the unique index on (branch, path) is used. */
        fun prefixEnd(prefix: String): String = prefix + Char(0xFFFF)

        /**
         * Turns what the reader typed into an FTS5 expression.
         *
         * FTS5 reads `:` as a column filter and `-` as a negation, and a resource query is full of
         * both: `minecraft:copper_golem` is a query, not a syntax error. Each term is therefore
         * quoted as a phrase. Several terms still mean "every one of them", which is the FTS5 default.
         */
        fun ftsQuery(raw: String): String = raw.split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"" }

        /** Content type for a resource file, so the browser can put it in an img or audio element. */
        fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "ogg" -> "audio/ogg"
            "json", "mcmeta" -> "application/json"
            "txt", "snbt", "fsh", "vsh", "glsl" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
