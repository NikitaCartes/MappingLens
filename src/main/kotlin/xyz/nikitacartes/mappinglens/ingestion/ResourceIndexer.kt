package xyz.nikitacartes.mappinglens.ingestion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import xyz.nikitacartes.mappinglens.db.ResourceIndex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * Builds `mappinglens-resources.db` from a clone of `misode/mcmeta`.
 *
 * The whole index is rebuilt in one run. It takes minutes, not hours, and an incremental build would
 * have to reconcile run ranges against a history that mcmeta rewrites whenever misode regenerates a
 * branch. The clone itself is never written to.
 *
 * @param fts `none` skips the two full-text tables, `content` builds the content index alone,
 *   `all` builds the translation index as well.
 */
class ResourceIndexer(
    private val repoPath: String,
    private val databasePath: String,
    private val fts: String = "all",
) {
    private val log = LoggerFactory.getLogger(ResourceIndexer::class.java)
    private val repo = ResourceRepository(Paths.get(repoPath))
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Version(val mcmetaId: String, val name: String, val type: String?, val time: String?)

    private class Run(val pathId: Int, val blobId: Int, val fromOrd: Int, var toOrd: Int)

    fun run() {
        require(repo.isGitRepo()) { "Not a git repository: $repoPath" }
        val refs = repo.resolveBranches(ResourceIndex.BRANCHES)
        require(refs.isNotEmpty()) {
            "Clone at $repoPath holds none of the mcmeta branches ${ResourceIndex.BRANCHES}"
        }
        log.info("Indexing mcmeta branches {} from {}", refs.keys, repoPath)

        val walks = refs.mapValues { (branch, ref) ->
            repo.walk(ref).also { log.info("Branch {}: {} commits", branch, it.size) }
        }
        val versionOfCommit = readVersions(walks)
        val ordered = orderVersions(walks, versionOfCommit)
        val ordOf = ordered.withIndex().associate { (i, v) -> v.mcmetaId to i }
        log.info("Version universe: {} versions", ordered.size)

        ResourceIndex.openWritable(databasePath).use { conn ->
            ResourceIndex.createSchema(conn)
            writeVersions(conn, ordered, matchCanonicalIds(ordered))

            val paths = Interner()
            val blobs = Interner()
            val blobKind = HashMap<Int, String>()
            val runs = ArrayList<Run>(1 shl 18)
            for ((branch, commits) in walks) {
                buildRuns(conn, branch, commits, versionOfCommit, ordOf, paths, blobs, blobKind, runs)
            }
            log.info("Runs: {} over {} paths and {} blobs", runs.size, paths.size, blobs.size)

            writePaths(conn, paths)
            writeBlobs(conn, blobs, blobKind)
            writeRuns(conn, runs)

            if (fts != "none") indexContent(conn, blobs, blobKind)
            if (fts == "all") indexTranslations(conn, paths, blobs, runs)

            conn.createStatement().use { it.execute("ANALYZE;") }
            conn.commit()
        }
        log.info("Resource index complete at {}", ResourceIndex.path(databasePath))
    }

    /**
     * Merges the branches into one version order, oldest first.
     *
     * The order is the one mcmeta commits in, which is the order the game versions belong in, not the
     * order they were released in: 1.16.5 comes after 20w45a in time but before it in the version
     * line, because 20w45a is a snapshot of the next release. Each branch therefore gives a chain of
     * "this version comes before that one", and the merge is a topological sort of those chains.
     * Release time only breaks ties, and only orders the versions no branch puts in sequence.
     */
    private fun orderVersions(
        walks: Map<String, List<ResourceRepository.Commit>>,
        versionOfCommit: Map<String, Version>,
    ): List<Version> {
        val byId = LinkedHashMap<String, Version>()
        val successors = HashMap<String, MutableSet<String>>()
        val indegree = HashMap<String, Int>()
        for (commits in walks.values) {
            var previous: String? = null
            for (commit in commits) {
                val version = versionOfCommit[commit.sha] ?: continue
                val id = version.mcmetaId
                byId.putIfAbsent(id, version)
                indegree.putIfAbsent(id, 0)
                if (previous != null && previous != id && successors.getOrPut(previous) { HashSet() }.add(id)) {
                    indegree[id] = indegree.getValue(id) + 1
                }
                previous = id
            }
        }
        val ready = java.util.PriorityQueue(compareBy<String>({ byId.getValue(it).time ?: "" }, { it }))
        indegree.forEach { (id, degree) -> if (degree == 0) ready += id }
        val remaining = HashSet(indegree.keys)
        val ordered = ArrayList<Version>(byId.size)
        while (remaining.isNotEmpty()) {
            // The fallback runs only when two branches disagree on the order of the same two
            // versions, which would leave every remaining node with an incoming edge.
            val id = ready.poll() ?: remaining.minByOrNull { byId.getValue(it).time ?: "" }!!
            if (!remaining.remove(id)) continue
            ordered += byId.getValue(id)
            successors[id]?.forEach { next ->
                val left = indegree.getValue(next) - 1
                indegree[next] = left
                if (left <= 0 && next in remaining) ready += next
            }
        }
        return ordered
    }

    /** `version.json` of every commit of every branch, read through one `cat-file --batch`. */
    private fun readVersions(walks: Map<String, List<ResourceRepository.Commit>>): Map<String, Version> {
        val specs = walks.values.flatten().map { "${it.sha}:version.json" }.distinct()
        val out = HashMap<String, Version>(specs.size)
        repo.batch(specs) { spec, bytes ->
            val obj = runCatching {
                json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return@batch
            val id = obj.str("id") ?: return@batch
            out[spec.substringBefore(':')] =
                Version(id, obj.str("name") ?: id, obj.str("type"), obj.str("release_time"))
        }
        return out
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    /**
     * The MappingLens canonical id of every mcmeta version, keyed by mcmeta id.
     *
     * mcmeta's `id` is the canonical id for almost every version. The old pre-releases are the
     * exception: mcmeta writes `1.14.1-pre1` where MappingLens indexes `1.14.1 Pre-Release 1`, which
     * is mcmeta's `name`. So the rule is `id` first, then `name`. A version that matches neither gets
     * no canonical id and the resource explorer still serves it under its mcmeta id.
     */
    private fun matchCanonicalIds(versions: List<Version>): Map<String, String> {
        val indexed = readIndexedVersionIds()
        if (indexed.isEmpty()) return emptyMap()
        return versions.mapNotNull { v ->
            val match = v.mcmetaId.takeIf { it in indexed } ?: v.name.takeIf { it in indexed }
            match?.let { v.mcmetaId to it }
        }.toMap()
    }

    private fun readIndexedVersionIds(): Set<String> {
        val main = Paths.get(databasePath).toAbsolutePath()
        if (!Files.exists(main)) {
            log.warn("Main index {} not found; resource versions get no MappingLens version id", main)
            return emptySet()
        }
        val url = "jdbc:sqlite:${main.toString().replace('\\', '/')}"
        return DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT version_id FROM versions").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
    }

    private fun writeVersions(conn: Connection, ordered: List<Version>, canonical: Map<String, String>) {
        conn.prepareStatement(
            "INSERT INTO versions(ord, mcmeta_id, name, version_id, release_type, release_time) " +
                "VALUES (?,?,?,?,?,?)"
        ).use { ps ->
            ordered.forEachIndexed { ord, v ->
                ps.setInt(1, ord)
                ps.setString(2, v.mcmetaId)
                ps.setString(3, v.name)
                ps.setString(4, canonical[v.mcmetaId])
                ps.setString(5, v.type)
                ps.setString(6, v.time)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        log.info("Matched {} of {} versions to a MappingLens version id", canonical.size, ordered.size)
    }

    /**
     * Turns one branch's commit walk into version ranges.
     *
     * A file keeps its content until a commit changes or deletes it, so one row covers that whole
     * range. A range closes at the previous commit **of this branch**, not at the previous version of
     * the universe: the branches do not carry the same set of versions.
     */
    private fun buildRuns(
        conn: Connection,
        branch: String,
        commits: List<ResourceRepository.Commit>,
        versionOfCommit: Map<String, Version>,
        ordOf: Map<String, Int>,
        paths: Interner,
        blobs: Interner,
        blobKind: HashMap<Int, String>,
        runs: MutableList<Run>,
    ) {
        val open = HashMap<Int, Run>()
        var prevOrd = -1
        // The newest version of this branch before the one being read. A branch may hold several
        // commits for one version, because misode regenerates a branch in place. The newest of them
        // wins, and the run an earlier one opened is dropped rather than closed.
        var closeAt = -1
        val branchOrds = ArrayList<Int>(commits.size)
        for (commit in commits) {
            val ord = versionOfCommit[commit.sha]?.let { ordOf[it.mcmetaId] } ?: continue
            if (ord != prevOrd) closeAt = prevOrd
            branchOrds += ord
            for (change in commit.changes) {
                if (change.path.startsWith(".git")) continue
                val pathId = paths.id("$branch ${change.path}")
                open.remove(pathId)?.let { if (it.fromOrd <= closeAt) { it.toOrd = closeAt; runs += it } }
                if (change.status == 'D') continue
                val blobId = blobs.id(change.newSha)
                blobKind.putIfAbsent(blobId, kindOf(branch, change.path))
                open[pathId] = Run(pathId, blobId, ord, ord)
            }
            prevOrd = ord
        }
        open.values.forEach { it.toOrd = prevOrd; runs += it }

        conn.prepareStatement("INSERT INTO branch_versions(branch, ord) VALUES (?,?)").use { ps ->
            branchOrds.distinct().forEach { ord ->
                ps.setString(1, branch)
                ps.setInt(2, ord)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun writePaths(conn: Connection, paths: Interner) {
        conn.prepareStatement("INSERT INTO paths(path_id, branch, path) VALUES (?,?,?)").use { ps ->
            paths.forEach { key, id ->
                ps.setInt(1, id)
                ps.setString(2, key.substringBefore(' '))
                ps.setString(3, key.substringAfter(' '))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
    }

    private fun writeBlobs(conn: Connection, blobs: Interner, blobKind: Map<Int, String>) {
        val sizes = repo.blobSizes()
        conn.prepareStatement("INSERT INTO blobs(blob_id, sha, size, kind) VALUES (?,?,?,?)").use { ps ->
            blobs.forEach { sha, id ->
                ps.setInt(1, id)
                ps.setString(2, sha)
                ps.setLong(3, sizes[sha] ?: 0L)
                ps.setString(4, blobKind[id] ?: "binary")
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
    }

    private fun writeRuns(conn: Connection, runs: List<Run>) {
        conn.prepareStatement("INSERT INTO runs(path_id, blob_id, from_ord, to_ord) VALUES (?,?,?,?)").use { ps ->
            runs.forEach { r ->
                ps.setInt(1, r.pathId)
                ps.setInt(2, r.blobId)
                ps.setInt(3, r.fromOrd)
                ps.setInt(4, r.toOrd)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.commit()
    }

    /** Full-text index over the text blobs, language files excluded. Contentless: rowid = blob_id. */
    private fun indexContent(conn: Connection, blobs: Interner, blobKind: Map<Int, String>) {
        val wanted = LinkedHashMap<String, Int>()
        blobs.forEach { sha, id -> if (blobKind[id] == "text") wanted[sha] = id }
        log.info("Content index: {} text blobs", wanted.size)
        var written = 0
        conn.prepareStatement("INSERT INTO content_fts(rowid, text) VALUES (?,?)").use { ps ->
            repo.batch(wanted.keys) { sha, bytes ->
                if (bytes.size <= MAX_FTS_BYTES) {
                    ps.setInt(1, wanted.getValue(sha))
                    ps.setString(2, bytes.toString(StandardCharsets.UTF_8))
                    ps.addBatch()
                    if (++written % 5000 == 0) ps.executeBatch()
                }
            }
            ps.executeBatch()
        }
        conn.commit()
        log.info("Content index: {} blobs written", written)
    }

    /**
     * Turns the language files into one row for each (file, key, value) run.
     *
     * The 27050 language blobs hold 12.04 GiB of the 12.71 GiB of text in the repository, so a
     * full-text index over them raw would cost gigabytes and report only that some 544 KB file
     * contains the string. One pass over each file's history instead reports the translation key, the
     * language and the version range.
     */
    private fun indexTranslations(conn: Connection, paths: Interner, blobs: Interner, runs: List<Run>) {
        val shaOf = HashMap<Int, String>(blobs.size)
        blobs.forEach { sha, id -> shaOf[id] = sha }
        val langPaths = HashMap<Int, String>()
        paths.forEach { key, id -> if (isLangPath(key.substringBefore(' '), key.substringAfter(' '))) langPaths[id] = key }
        val runsByPath = runs.filter { it.pathId in langPaths }.groupBy { it.pathId }
        log.info("Translation index: {} language files, {} runs", langPaths.size, runsByPath.values.sumOf { it.size })

        val keys = Interner()
        conn.prepareStatement(
            "INSERT INTO translations(path_id, key_id, value, from_ord, to_ord) VALUES (?,?,?,?,?)"
        ).use { insert ->
            for ((pathId, pathRuns) in runsByPath) {
                val ordered = pathRuns.sortedBy { it.fromOrd }
                // The value a key currently holds, and the version its current run opened at.
                val open = HashMap<Int, Pair<String, Int>>()
                var lastTo = -1

                fun emit(keyId: Int, value: String, from: Int, to: Int) {
                    insert.setInt(1, pathId); insert.setInt(2, keyId); insert.setString(3, value)
                    insert.setInt(4, from); insert.setInt(5, to)
                    insert.addBatch()
                }

                var next = 0
                repo.batch(ordered.map { shaOf.getValue(it.blobId) }) { _, bytes ->
                    val run = ordered[next++]
                    val entries = parseLang(bytes)
                    if (entries != null) {
                        val seen = HashSet<Int>(entries.size * 2)
                        for ((key, value) in entries) {
                            val keyId = keys.id(key)
                            seen += keyId
                            val cur = open[keyId]
                            if (cur == null) {
                                open[keyId] = value to run.fromOrd
                            } else if (cur.first != value) {
                                emit(keyId, cur.first, cur.second, lastTo)
                                open[keyId] = value to run.fromOrd
                            }
                        }
                        val gone = open.entries.iterator()
                        while (gone.hasNext()) {
                            val e = gone.next()
                            if (e.key !in seen) {
                                emit(e.key, e.value.first, e.value.second, lastTo)
                                gone.remove()
                            }
                        }
                    }
                    lastTo = run.toOrd
                }
                // The loop above pairs each answer with the run that asked for it by position, so a
                // blob the clone could not produce would shift every later range onto the wrong
                // version. Nothing but a damaged clone can do that, and a silently wrong range is
                // worse than a failed build.
                check(next == ordered.size) { "read $next of ${ordered.size} blobs for path $pathId" }
                open.forEach { (keyId, v) -> emit(keyId, v.first, v.second, lastTo) }
                insert.executeBatch()
                conn.commit()
            }
        }
        conn.prepareStatement("INSERT INTO tr_keys(key_id, key) VALUES (?,?)").use { ps ->
            keys.forEach { key, id ->
                ps.setInt(1, id)
                ps.setString(2, key)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        // The full-text table is filled in one pass over the finished table. Filling it row by row
        // beside the insert above would mean carrying the row id of each translation by hand, which
        // is what SQLite already does.
        conn.createStatement().use { st ->
            st.execute("INSERT INTO translations_fts(rowid, value) SELECT tr_id, value FROM translations;")
        }
        conn.commit()
        val rows = conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM translations").use { it.getInt(1) }
        }
        log.info("Translation index: {} rows over {} distinct keys", rows, keys.size)
    }

    /** A language file is a flat map of key to value. Anything that is not a string is skipped. */
    private fun parseLang(bytes: ByteArray): List<Pair<String, String>>? = runCatching {
        val obj = json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
            ?: return@runCatching null
        obj.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
    }.getOrNull()

    private fun kindOf(branch: String, path: String): String = when {
        isLangPath(branch, path) -> "lang"
        path.substringAfterLast('.', "") in TEXT_EXTENSIONS -> "text"
        else -> "binary"
    }

    private fun isLangPath(branch: String, path: String): Boolean =
        branch == "assets" && LANG_PATH.matches(path)

    private class Interner {
        private val ids = HashMap<String, Int>(1 shl 16)
        val size: Int get() = ids.size
        fun id(key: String): Int = ids.getOrPut(key) { ids.size + 1 }
        fun forEach(action: (String, Int) -> Unit) = ids.forEach { (k, v) -> action(k, v) }
    }

    private companion object {
        val LANG_PATH = Regex("""assets/[^/]+/lang/[^/]+\.json""")
        /** Every text extension the repository holds. The rest are `.png`, `.ogg`, `.zip` and friends. */
        val TEXT_EXTENSIONS = setOf("json", "snbt", "txt", "mcmeta", "fsh", "vsh", "glsl")

        /**
         * Blobs above this size are left out of the content index. mcmeta holds a few generated
         * reports of tens of megabytes whose tokens are of no use to a reader.
         * ponytail: a flat cap, not a per-branch rule. Raise it when a real file is missed.
         */
        const val MAX_FTS_BYTES = 8 * 1024 * 1024
    }
}
