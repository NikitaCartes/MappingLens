package dev.mappinglens.ingestion

import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            updateDigest(md, input)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        updateDigest(md, input)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigest(md: MessageDigest, input: InputStream) {
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
}

object Names {
    fun simpleName(fqn: String?): String? {
        if (fqn == null) return null
        val s = fqn.substringAfterLast('/')
        return s.substringAfterLast('$')
    }

    fun packagePath(fqn: String?): String? {
        if (fqn == null) return null
        val idx = fqn.lastIndexOf('/')
        return if (idx >= 0) fqn.substring(0, idx) else ""
    }
}
