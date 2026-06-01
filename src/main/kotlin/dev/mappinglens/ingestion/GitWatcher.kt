package dev.mappinglens.ingestion

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path

/**
 * Resolves the current git revision of a repository (best-effort).
 * Returns null if the repo is not a git checkout or git is not available.
 */
class GitWatcher(private val repoPath: Path) {
    private val log = LoggerFactory.getLogger(GitWatcher::class.java)

    fun getCurrentRev(): String? {
        return try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repoPath.toFile())
                .redirectErrorStream(false)
                .start()
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && out.isNotEmpty()) out else null
        } catch (e: IOException) {
            log.debug("git rev-parse failed for {}: {}", repoPath, e.message)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun hasChanged(storedRev: String?): Boolean {
        val current = getCurrentRev() ?: return storedRev != null // can't determine, assume unchanged unless was set
        return storedRev != current
    }
}
