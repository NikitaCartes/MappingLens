package dev.mappinglens.ingestion

import dev.mappinglens.config.SourcesConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

class VersionDiscoveryTest {

    private fun sourcesOn(tmp: Path, intermediaryDir: String, artifactStoreDir: String) = SourcesConfig(
        yarnRepo = tmp.resolve("yarn").toString(),
        mojmapRepo = tmp.resolve("moj").toString(),
        intermediaryMappings = intermediaryDir,
        artifactStore = artifactStoreDir,
    )

    @Test
    fun `discovers versions from intermediary and artifact-store directories`(@TempDir tmp: Path) {
        val intermediary = tmp.resolve("intermediary").createDirectories()
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val mappings = artifactStore.resolve("mappings").createDirectories()
        // intermediary
        intermediary.resolve("1.21.1.tiny").writeText("tiny\t2\t0\tofficial\tintermediary\n")
        intermediary.resolve("1.21.tiny").writeText("tiny\t2\t0\tofficial\tintermediary\n")
        intermediary.resolve("1.20-v1.tiny").writeText("ignored") // v1 must be skipped
        // artifact-store: moj, multiple yarn builds (max wins), intermediary copy
        mappings.resolve("1.21.1-moj.tiny").writeText("tiny\t2\t0\tofficial\tnamed\n")
        mappings.resolve("1.21.1-yarn-build.5.tiny").writeText("yarn 5")
        mappings.resolve("1.21.1-yarn-build.12.tiny").writeText("yarn 12")
        mappings.resolve("1.21.1-yarn-build.12-constants.tiny").writeText("ignored constants")
        mappings.resolve("1.21-moj.tiny").writeText("moj 1.21")

        val discovery = VersionDiscovery(sourcesOn(tmp, intermediary.toString(), artifactStore.toString()))
        val result = discovery.discover().associateBy { it.versionId }

        assertEquals(setOf("1.21", "1.21.1"), result.keys)
        val v1 = result["1.21.1"]!!
        assertNotNull(v1.intermediary)
        assertNotNull(v1.mojmap)
        assertNotNull(v1.yarn)
        assertTrue(v1.yarn!!.fileName.toString().contains("build.12"))
    }

    @Test
    fun `returns empty list when no source directories exist`(@TempDir tmp: Path) {
        val discovery = VersionDiscovery(
            sourcesOn(tmp, tmp.resolve("nope1").toString(), tmp.resolve("nope2").toString())
        )
        assertEquals(emptyList(), discovery.discover())
    }

    @Test
    fun `discovers mojmap-only version`(@TempDir tmp: Path) {
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val mappings = artifactStore.resolve("mappings").createDirectories()
        mappings.resolve("1.20.4-moj.tiny").writeText("moj")

        val result = VersionDiscovery(sourcesOn(tmp, tmp.resolve("ix").toString(), artifactStore.toString())).discover()
        assertEquals(listOf("1.20.4"), result.map { it.versionId })
        val v = result.single()
        assertNotNull(v.mojmap)
        assertNull(v.yarn)
        assertNull(v.intermediary)
    }

    @Test
    fun `discovers client and server mojmap files as the base version`(@TempDir tmp: Path) {
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val mappings = artifactStore.resolve("mappings").createDirectories()
        mappings.resolve("1.21-client-moj.tiny").writeText("client")
        mappings.resolve("1.21-server-moj.tiny").writeText("server")
        mappings.resolve("1.21-yarn-build.9.tiny").writeText("yarn")

        val result = VersionDiscovery(sourcesOn(tmp, tmp.resolve("ix").toString(), artifactStore.toString()))
            .discover()
            .associateBy { it.versionId }

        assertEquals(setOf("1.21"), result.keys)
        val v = result["1.21"]!!
        assertNotNull(v.mojmap)
        assertTrue(v.mojmap!!.fileName.toString().endsWith("-client-moj.tiny"))
        assertEquals(2, v.mojmaps.size)
        assertTrue(v.mojmaps.any { it.fileName.toString().endsWith("-server-moj.tiny") })
        assertNotNull(v.yarn)
    }

    @Test
    fun `artifact-store root supplies mappings when explicit mapping path is stale`(@TempDir tmp: Path) {
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val mappings = artifactStore.resolve("mappings").createDirectories()
        mappings.resolve("1.21.1-yarn-build.7.tiny").writeText("yarn")
        mappings.resolve("1.21.1-client-moj.tiny").writeText("moj")

        val sources = SourcesConfig(
            yarnRepo = tmp.resolve("yarn").toString(),
            mojmapRepo = tmp.resolve("moj").toString(),
            intermediaryMappings = tmp.resolve("missing-intermediary").toString(),
            artifactStore = artifactStore.toString(),
        )

        val version = VersionDiscovery(sources).discover().single()
        assertEquals("1.21.1", version.versionId)
        assertEquals(mappings.resolve("1.21.1-yarn-build.7.tiny"), version.yarn)
        assertEquals(mappings.resolve("1.21.1-client-moj.tiny"), version.mojmap)
    }
}
