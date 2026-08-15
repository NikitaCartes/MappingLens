package dev.mappinglens.ingestion

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceScannerTest {

    @Test
    fun `scans java files from git version refs without checkout`(@TempDir tmp: Path) {
        initGitSourceRepo(tmp, "1.21", "class Block { int value() { return 1; } }")
        commitGitSourceVersion(tmp, "1.21.1", "class Block { int value() { return 2; } }")

        val oldFile = SourceScanner(tmp).scan("1.21").single()
        val newFile = SourceScanner(tmp).scan("1.21.1").single()

        assertEquals("net/minecraft/block/Block.java", oldFile.relativePath)
        assertEquals("net/minecraft/block/Block", oldFile.classFqn)
        assertEquals(64, oldFile.contentHash.length)
        assertEquals(64, newFile.contentHash.length)
        assertNotEquals(oldFile.contentHash, newFile.contentHash)
    }

    @Test
    fun `scans direct source root fallback when git is unavailable`(@TempDir tmp: Path) {
        tmp.resolve("net/minecraft/block").createDirectories()
        Files.writeString(tmp.resolve("Foo.java"), "class Foo {}")
        val files = SourceScanner(tmp).scan("does-not-exist")
        assertEquals(listOf("Foo.java"), files.map { it.relativePath })
    }

    @Test
    fun `scans repository root minecraft src layout`(@TempDir tmp: Path) {
        val sourceDir = tmp.resolve("minecraft/src/net/minecraft/block").createDirectories()
        Files.writeString(sourceDir.resolve("Block.java"), "class Block {}")

        val files = SourceScanner(tmp).scan("1.21.1")

        assertEquals(listOf("net/minecraft/block/Block.java"), files.map { it.relativePath })
    }

    @Test
    fun `does not treat version directories as source roots`(@TempDir tmp: Path) {
        val versionDir = tmp.resolve("1.21.1/net/minecraft/block").createDirectories()
        Files.writeString(versionDir.resolve("Block.java"), "class Block {}")

        assertEquals(emptyList(), SourceScanner(tmp).scan("1.21.1"))
    }

    @Test
    fun `returns empty list when repo does not exist`(@TempDir tmp: Path) {
        val missing = tmp.resolve("missing-root")
        assertEquals(emptyList(), SourceScanner(missing).scan("any"))
    }

    @Test
    fun `scans java source entries from decompiled artifact jar`(@TempDir tmp: Path) {
        val jar = tmp.resolve("merged-map_yarn-test.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(ZipEntry("net/minecraft/block/Block.java"))
            output.write("package net.minecraft.block; public class Block {}".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("net/minecraft/block/Block.class"))
            output.write(byteArrayOf(0, 1, 2))
            output.closeEntry()
        }

        val files = SourceScanner.scanJar(jar)

        assertEquals(listOf("net/minecraft/block/Block.java"), files.map { it.relativePath })
        assertEquals(listOf("net/minecraft/block/Block"), files.map { it.classFqn })
        assertEquals(64, files.single().contentHash.length)
    }

    private fun initGitSourceRepo(root: Path, tag: String, content: String) {
        runGit(root, "init")
        runGit(root, "config", "user.email", "test@example.invalid")
        runGit(root, "config", "user.name", "MappingLens Test")
        commitGitSourceVersion(root, tag, content)
    }

    private fun commitGitSourceVersion(root: Path, tag: String, content: String) {
        val sourceDir = root.resolve("minecraft/src/net/minecraft/block").createDirectories()
        Files.writeString(sourceDir.resolve("Block.java"), content)
        runGit(root, "add", "minecraft/src/net/minecraft/block/Block.java")
        runGit(root, "commit", "-m", tag)
        runGit(root, "tag", tag)
    }

    private fun runGit(root: Path, vararg args: String) {
        val exit = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }
}

class UnobfuscatedJarScannerTest {

    private val cls = "net/minecraft/client/gui/Gui"

    @Test
    fun `names classes and members from the unobfuscated intermediary tiny`(@TempDir tmp: Path) {
        val jar = tmp.resolve("merged-remapped-map_mojmap-test.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(ZipEntry("$cls.class"))
            output.write(classBytes())
            output.closeEntry()
        }
        val tiny = tmp.resolve("26.1.tiny")
        tiny.writeText(
            listOf(
                "v1\tofficial\tintermediary",
                "CLASS\t$cls\tnet/minecraft/class_329",
                "METHOD\t$cls\t(L$cls;)V\trender\tmethod_1786",
                "FIELD\t$cls\tL$cls;\tINSTANCE\tfield_2035",
            ).joinToString("\n") + "\n",
        )

        val entry = UnobfuscatedJarScanner.scan(jar, TinyV2Parser.parse(tiny)).single()

        assertEquals("net/minecraft/class_329", entry.intermediaryName)
        assertEquals(cls, entry.mojmapName)
        val method = entry.methods.single { it.mojmapName == "render" }
        assertEquals("method_1786", method.intermediaryName)
        // The tiny states descriptors in official terms only; mapping-io remaps them for us.
        assertEquals("(Lnet/minecraft/class_329;)V", method.intermediaryDesc)
        val field = entry.fields.single { it.mojmapName == "INSTANCE" }
        assertEquals("field_2035", field.intermediaryName)
        assertEquals("Lnet/minecraft/class_329;", field.intermediaryDesc)
        // A member the mappings do not name keeps the jar's own name and no intermediary.
        val unmapped = entry.methods.single { it.mojmapName == "tick" }
        assertNull(unmapped.intermediaryName)
    }

    @Test
    fun `scans without mappings as before`(@TempDir tmp: Path) {
        val jar = tmp.resolve("merged-remapped-map_mojmap-test.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(ZipEntry("$cls.class"))
            output.write(classBytes())
            output.closeEntry()
        }

        val entry = UnobfuscatedJarScanner.scan(jar).single()

        assertEquals(cls, entry.mojmapName)
        assertNull(entry.intermediaryName)
        assertTrue(entry.methods.all { it.intermediaryName == null })
    }

    private fun classBytes(): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "INSTANCE", "L$cls;", null, null).visitEnd()
        writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "render", "(L$cls;)V", null, null).visitEnd()
        writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "tick", "()V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}

