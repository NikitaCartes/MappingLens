package xyz.nikitacartes.mappinglens.ingestion

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension

data class SourceFileInfo(
    val relativePath: String,
    val contentHash: String,
    val classFqn: String?,
)

/**
 * Walks a decompiled-source repository (root) and produces metadata about the .java files.
 * yarn-repo and mojmap-repo are read-only Git worktrees whose versioned sources live under
 * `minecraft/src` at Git refs/tags such as `1.21.1`; they are not `{version}/...` directory
 * trees. For non-Git fixtures or exported snapshots, `minecraft/src` (or a direct source
 * root) is scanned as the current tree.
 */
class SourceScanner(private val repoRoot: Path) {

    fun scan(versionId: String): List<SourceFileInfo> {
        GitSourceRepository(repoRoot).scan(versionId)?.let { return it }
        val target = GitSourceRepository.filesystemSourceRoot(repoRoot) ?: return emptyList()
        return Files.walk(target).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "java" }
                .map { p ->
                    val rel = target.relativize(p).toString().replace('\\', '/')
                    SourceFileInfo(
                        relativePath = rel,
                        contentHash = Hashing.sha256(p),
                        classFqn = rel.removeSuffix(".java"),
                    )
                }
                .toList()
        }
    }

    companion object {
        fun scanJar(sourceJar: Path): List<SourceFileInfo> {
            if (!Files.isRegularFile(sourceJar)) return emptyList()
            return ZipFile(sourceJar.toFile()).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".java") }
                    .map { entry ->
                        val hash = zip.getInputStream(entry).use { Hashing.sha256(it) }
                        SourceFileInfo(
                            relativePath = entry.name,
                            contentHash = hash,
                            classFqn = entry.name.removeSuffix(".java"),
                        )
                    }
                    .toList()
            }
        }
    }
}
