package xyz.nikitacartes.mappinglens.ingestion

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Read-only access to a clone of `misode/mcmeta` through git plumbing.
 *
 * The clone is never checked out. Every read goes through `log --raw` and `cat-file`, so one clone
 * serves every branch at once and the working tree stays on whatever branch the operator left it on.
 */
class ResourceRepository(private val repoRoot: Path) {
    private val log = LoggerFactory.getLogger(ResourceRepository::class.java)

    /** One changed file of one commit: the blob before, the blob after, and the path. */
    data class Change(val status: Char, val oldSha: String, val newSha: String, val path: String)

    /** One commit of a branch, oldest first, with everything it changed against its parent. */
    data class Commit(val sha: String, val changes: List<Change>)

    fun isGitRepo(): Boolean = repoRoot.exists() &&
        runGitText("rev-parse", "--git-dir")?.isNotBlank() == true

    /** The branches of [names] this clone holds, as full ref names keyed by branch name. */
    fun resolveBranches(names: List<String>): Map<String, String> {
        val refs = runGitText("for-each-ref", "--format=%(refname)", "refs/heads", "refs/remotes/origin")
            ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        return names.mapNotNull { name ->
            val ref = listOf("refs/heads/$name", "refs/remotes/origin/$name").firstOrNull { it in refs }
            ref?.let { name to it }
        }.toMap()
    }

    /**
     * Every commit of [ref], oldest first, each with its changes against its parent.
     *
     * One `git log` walks the whole branch. The alternative — one `diff-tree` for each pair of
     * neighbouring commits — starts 450 processes for the same answer. `-z` is needed because a
     * resource path may hold any byte a file name allows.
     */
    fun walk(ref: String): List<Commit> {
        val output = runGitBytes(
            "log", "--reverse", "--no-abbrev", "--no-renames", "--format=%x00commit%x00%H", "--raw", "-z", ref,
        ) ?: return emptyList()
        val tokens = output.toString(StandardCharsets.UTF_8).split(NUL)
        val commits = mutableListOf<Commit>()
        var sha: String? = null
        var changes = mutableListOf<Change>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index].trimStart('\n', '\r')
            when {
                token == "commit" -> {
                    sha?.let { commits += Commit(it, changes) }
                    sha = tokens.getOrNull(index + 1)?.trim()
                    changes = mutableListOf()
                    index += 2
                }
                // ":<oldmode> <newmode> <oldsha> <newsha> <status>" followed by the path.
                token.startsWith(":") -> {
                    val fields = token.substring(1).split(' ')
                    val path = tokens.getOrNull(index + 1)
                    if (fields.size >= 5 && path != null && fields[4].isNotEmpty()) {
                        changes += Change(fields[4].first(), fields[2], fields[3], path)
                    }
                    index += 2
                }
                else -> index++
            }
        }
        sha?.let { commits += Commit(it, changes) }
        return commits
    }

    /** Byte size of every blob in the clone, keyed by sha. One process, no round trips. */
    fun blobSizes(): Map<String, Long> {
        val format = "--batch-check=%(objectname) %(objecttype) %(objectsize)"
        val output = runGitBytes("cat-file", "--batch-all-objects", format) ?: return emptyMap()
        val sizes = HashMap<String, Long>(1 shl 19)
        output.toString(StandardCharsets.UTF_8).lineSequence().forEach { line ->
            val parts = line.split(' ')
            if (parts.size == 3 && parts[1] == "blob") parts[2].toLongOrNull()?.let { sizes[parts[0]] = it }
        }
        return sizes
    }

    /** The bytes of one object, or null when the clone does not hold it. */
    fun read(spec: String): ByteArray? = runGitBytes("cat-file", "blob", spec)

    /**
     * Streams the bytes of every request of [requests] to [consume], in order.
     *
     * A request is a blob sha or a `<commit>:<path>` spec. One `cat-file --batch` process serves them
     * all. Each request is written and its answer read before the next is written, so neither side of
     * the pipe ever waits on a full buffer. A request the clone cannot resolve is skipped.
     */
    fun batch(requests: Iterable<String>, consume: (String, ByteArray) -> Unit) {
        val process = ProcessBuilder("git", "-C", repoRoot.toString(), "cat-file", "--batch")
            .redirectErrorStream(false)
            .start()
        try {
            val input = process.inputStream.buffered(1 shl 16)
            val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            for (request in requests) {
                writer.write(request)
                writer.newLine()
                writer.flush()
                val header = readAsciiLine(input) ?: break
                val parts = header.split(' ')
                if (parts.size < 3 || parts[1] != "blob") continue  // "<request> missing"
                val size = parts[2].toIntOrNull() ?: break
                val content = input.readNBytes(size)
                input.read() // trailing LF after each object body
                consume(request, content)
            }
            writer.close()
        } catch (e: Exception) {
            log.warn("git cat-file --batch failed for {}: {}", repoRoot, e.message)
        } finally {
            process.destroy()
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.UTF_8)
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(StandardCharsets.UTF_8)
    }

    private fun runGitText(vararg args: String): String? = runGitBytes(*args)?.toString(StandardCharsets.UTF_8)

    private fun runGitBytes(vararg args: String): ByteArray? = try {
        val process = ProcessBuilder(listOf("git", "-C", repoRoot.toString()) + args)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.readBytes()
        if (process.waitFor() == 0) output else null
    } catch (e: Exception) {
        log.debug("git command failed for {}: {}", repoRoot, e.message)
        null
    }

    private companion object {
        /** The record separator of `-z` output. Written as a code point so no source file holds one. */
        val NUL = Char(0)
    }
}
