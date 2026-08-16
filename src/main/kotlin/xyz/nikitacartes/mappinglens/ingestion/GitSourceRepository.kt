package xyz.nikitacartes.mappinglens.ingestion

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Read-only access to the source repositories configured as yarn-repo / mojmap-repo.
 *
 * Those paths point at Git worktrees containing `minecraft/src` for every version as Git
 * refs/tags, not at directories laid out as `{version}/...`. All versioned reads therefore
 * use Git plumbing (`ls-tree`, `cat-file`, `show`) and never checkout or mutate the worktree.
 */
class GitSourceRepository(private val repoRoot: Path) {
    private val log = LoggerFactory.getLogger(GitSourceRepository::class.java)

    data class DiffEntry(val relativePath: String, val changeType: String)

    fun isGitWorkTree(): Boolean = runGitText("rev-parse", "--is-inside-work-tree")?.trim() == "true"

    fun diff(fromVersionId: String, toVersionId: String, pathPrefix: String? = null): List<DiffEntry>? {
        if (!isGitWorkTree()) return null
        val normalizedPrefix = pathPrefix?.let(::normalizeRelativePath)?.takeIf { it.isNotBlank() }
        val pathSpec = normalizedPrefix?.let { "$SOURCE_TREE/$it" } ?: SOURCE_TREE
        val output = runGitBytes(
            "diff",
            "--name-status",
            "--no-renames",
            "--diff-filter=ADM",
            "-z",
            fromVersionId,
            toVersionId,
            "--",
            pathSpec,
        ) ?: return null
        if (output.isEmpty()) return emptyList()

        val tokens = output.toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .filter { it.isNotBlank() }
        val result = mutableListOf<DiffEntry>()
        var index = 0
        while (index + 1 < tokens.size) {
            val status = tokens[index++].trim()
            val rawPath = normalizeRelativePath(tokens[index++])
            if (!rawPath.startsWith("$SOURCE_TREE/")) continue
            val relativePath = rawPath.removePrefix("$SOURCE_TREE/")
            if (!relativePath.endsWith(".java")) continue
            val changeType = when {
                status.startsWith("A") -> "added"
                status.startsWith("D") -> "removed"
                else -> "modified"
            }
            result += DiffEntry(relativePath, changeType)
        }
        return result.sortedWith(compareBy<DiffEntry> { it.relativePath }.thenBy { it.changeType })
    }

    fun scan(versionId: String): List<SourceFileInfo>? {
        if (!isGitWorkTree()) return null
        val entries = listSourceEntries(versionId) ?: return emptyList()
        val javaEntries = entries.filter { it.relativePath.endsWith(".java") }
        if (javaEntries.isEmpty()) return emptyList()

        val hashesByObject = sha256Objects(javaEntries.map { it.objectId }.distinct())
        return javaEntries.mapNotNull { entry ->
            val hash = hashesByObject[entry.objectId] ?: return@mapNotNull null
            SourceFileInfo(
                relativePath = entry.relativePath,
                contentHash = hash,
                classFqn = entry.relativePath.removeSuffix(".java"),
            )
        }
    }

    fun read(versionId: String, relativePath: String): String? {
        if (!isGitWorkTree()) return null
        val normalizedPath = normalizeRelativePath(relativePath)
        val bytes = runGitBytes("show", "$versionId:$SOURCE_TREE/$normalizedPath") ?: return null
        return bytes.toString(StandardCharsets.UTF_8)
    }

    /**
     * Version that last changed each line of [relativePath] as of [versionId], line 1 first.
     *
     * One `git blame` walks the whole history in a single process: Git compares blob ids through
     * the trees and reads content only where a commit changed the file. Each version is one commit
     * whose subject is the canonical version id, so `--porcelain` reports the answer in its
     * `summary` lines and no ref lookup is needed.
     */
    fun blame(versionId: String, relativePath: String): List<String>? {
        if (!isGitWorkTree()) return null
        val path = normalizeRelativePath(relativePath)
        // Tags replace spaces with underscores, as VersionMeta.gitTagYarn does; a ref cannot hold a space.
        val tag = versionId.replace(' ', '_')
        val output = runGitBytes("blame", "--porcelain", tag, "--", "$SOURCE_TREE/$path") ?: return null
        val summaries = mutableMapOf<String, String>()
        val lines = mutableListOf<String>()
        var commit = ""
        for (raw in output.toString(StandardCharsets.UTF_8).lineSequence()) {
            when {
                // A tab starts the blamed line itself and closes the header block before it.
                raw.startsWith("\t") -> lines += summaries[commit].orEmpty()
                raw.startsWith("summary ") -> summaries[commit] = raw.removePrefix("summary ").trim()
                else -> raw.substringBefore(' ').let { if (isCommitId(it)) commit = it }
            }
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun isCommitId(token: String): Boolean =
        (token.length == 40 || token.length == 64) && token.all { it in '0'..'9' || it in 'a'..'f' }

    private fun listSourceEntries(versionId: String): List<GitTreeEntry>? {
        val output = runGitBytes("ls-tree", "-r", "-z", "$versionId:$SOURCE_TREE") ?: return null
        if (output.isEmpty()) return emptyList()
        return output.toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(::parseTreeEntry)
            .toList()
    }

    private fun parseTreeEntry(record: String): GitTreeEntry? {
        val tab = record.indexOf('\t')
        if (tab < 0) return null
        val meta = record.substring(0, tab).split(' ')
        val objectId = meta.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        val path = normalizeRelativePath(record.substring(tab + 1))
        return GitTreeEntry(objectId, path)
    }

    private fun sha256Objects(objectIds: List<String>): Map<String, String> {
        if (objectIds.isEmpty()) return emptyMap()
        return try {
            val process = ProcessBuilder("git", "-C", repoRoot.toString(), "cat-file", "--batch")
                .redirectErrorStream(false)
                .start()

            val input = process.inputStream.buffered()
            val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            val hashes = linkedMapOf<String, String>()
            for (expectedObjectId in objectIds) {
                writer.write(expectedObjectId)
                writer.newLine()
                writer.flush()
                val header = readAsciiLine(input) ?: break
                val parts = header.split(' ')
                if (parts.size < 3 || parts[1] != "blob") break
                val objectId = parts[0]
                val size = parts[2].toIntOrNull() ?: break
                val content = input.readNBytes(size)
                input.read() // trailing LF emitted by cat-file --batch after each object body
                hashes[objectId] = Hashing.sha256(ByteArrayInputStream(content))
                if (objectId != expectedObjectId) {
                    log.debug("git cat-file returned {} while {} was requested", objectId, expectedObjectId)
                }
            }
            writer.close()
            process.waitFor()
            hashes
        } catch (e: Exception) {
            log.debug("git cat-file failed for {}: {}", repoRoot, e.message)
            emptyMap()
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

    private fun runGitBytes(vararg args: String): ByteArray? {
        return try {
            val command = buildList {
                add("git")
                add("-C")
                add(repoRoot.toString())
                addAll(args)
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes()
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        } catch (e: Exception) {
            log.debug("git command failed for {}: {}", repoRoot, e.message)
            null
        }
    }

    private data class GitTreeEntry(val objectId: String, val relativePath: String)

    companion object {
        const val SOURCE_TREE = "minecraft/src"

        fun filesystemSourceRoot(repoRoot: Path): Path? {
            if (!repoRoot.exists() || !repoRoot.isDirectory()) return null
            val nested = repoRoot.resolve("minecraft").resolve("src")
            if (nested.exists() && nested.isDirectory()) return nested
            if (repoRoot.resolve("net").exists() || repoRoot.resolve("com").exists()) return repoRoot
            return null
        }

        fun normalizeRelativePath(path: String): String = path.trim().replace('\\', '/').removePrefix("/")
    }
}
