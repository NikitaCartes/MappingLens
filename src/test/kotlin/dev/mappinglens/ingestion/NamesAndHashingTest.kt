package dev.mappinglens.ingestion

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamesTest {

    @Test
    fun `simpleName extracts last segment after slash and dollar`() {
        assertEquals("BlockState", Names.simpleName("net/minecraft/block/BlockState"))
        assertEquals("Builder", Names.simpleName("net/minecraft/block/AbstractBlock\$Builder"))
        assertEquals("Foo", Names.simpleName("Foo"))
        assertNull(Names.simpleName(null))
    }

    @Test
    fun `packagePath returns prefix or empty string for default package`() {
        assertEquals("net/minecraft/block", Names.packagePath("net/minecraft/block/BlockState"))
        assertEquals("", Names.packagePath("BlockState"))
        assertNull(Names.packagePath(null))
    }
}

class HashingTest {

    @Test
    fun `sha256 is deterministic and matches known value for empty file`(@TempDir tmp: Path) {
        val empty = tmp.resolve("empty.bin").also { Files.createFile(it) }
        val hashed = Hashing.sha256(empty)
        // SHA-256("") well-known constant
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hashed,
        )
    }

    @Test
    fun `sha256 of small payload matches known value`(@TempDir tmp: Path) {
        val p = tmp.resolve("a.txt")
        Files.writeString(p, "abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256(p),
        )
    }
}
