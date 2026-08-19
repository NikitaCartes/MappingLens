package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.model.ValidateAt
import xyz.nikitacartes.mappinglens.model.ValidateRequest
import xyz.nikitacartes.mappinglens.model.ValidateTarget
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidateServiceTest {

    private val at = ValidateAt("INVOKE", "Target:foo:()V")

    /** `<name>.run()V` calls `Target.foo()V` [calls] times, and `<name>.helper()V` calls it [inHelper] times. */
    private fun caller(name: String, calls: Int, inHelper: Int = 0): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null)
        for ((method, count) in listOf("run" to calls, "helper" to inHelper)) {
            cw.visitMethod(ACC_PUBLIC or ACC_STATIC, method, "()V", null, null).apply {
                visitCode()
                repeat(count) { visitMethodInsn(INVOKESTATIC, "Target", "foo", "()V", false) }
                visitInsn(RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun plain(name: String, superName: String, vararg methods: Pair<String, String>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, name, null, superName, null)
        for ((method, descriptor) in methods) {
            cw.visitMethod(ACC_PUBLIC or ACC_STATIC, method, descriptor, null, null).apply {
                visitCode()
                visitInsn(RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jarFor(store: Path, version: String, vararg classes: ByteArray) {
        val dir = Files.createDirectories(store.resolve("remapped-mc/$version"))
        JarOutputStream(Files.newOutputStream(dir.resolve("merged-remapped-map_mojmap-test.jar"))).use { jar ->
            for (bytes in classes) {
                jar.putNextEntry(ZipEntry(ClassReader(bytes).className + ".class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }

    private fun config(tmp: Path) = AppConfig(
        databasePath = tmp.resolve("db.sqlite").toString(),
        sources = SourcesConfig(
            yarnRepo = tmp.toString(),
            mojmapRepo = tmp.toString(),
            intermediaryMappings = tmp.toString(),
            artifactStore = tmp.resolve("artifact-store").toString(),
        ),
        initialVersions = emptyList(),
        search = SearchConfig(maxResults = 100, defaultResults = 20),
    )

    private fun service(tmp: Path) = config(tmp).let { ValidateService(it, ReferenceService(it)) }

    private fun target(id: String, method: String, descriptor: String?, at: ValidateAt? = null) =
        ValidateTarget(id, "Caller", method, descriptor, at)

    @Test
    fun `the call leaving the hooked method breaks the span and names where it went`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        val target = plain("Target", "java/lang/Object", "foo" to "()V")
        jarFor(store, "1.21", caller("Caller", 2), target)
        jarFor(store, "1.21.1", caller("Caller", 2), target)
        jarFor(store, "1.21.2", caller("Caller", 0, inHelper = 1), target)

        val spans = service(tmp).validate(
            ValidateRequest("mojmap", listOf(target("dismount", "run", "()V", at))),
            listOf("1.21", "1.21.1", "1.21.2"),
        ).results.single().spans

        assertEquals(listOf("ok", "call_moved"), spans.map { it.status })
        assertEquals(listOf("1.21" to "1.21.1", "1.21.2" to "1.21.2"), spans.map { it.from to it.to })
        assertEquals(2, spans[0].atCount)
        assertEquals("Caller#helper", spans[1].movedTo)
    }

    @Test
    fun `a call that left the class is paired through the reverse index`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        val target = plain("Target", "java/lang/Object", "foo" to "()V")
        jarFor(store, "1.21", caller("Caller", 1), target)
        jarFor(store, "1.21.1", caller("Caller", 0), caller("Mover", 1), target)

        val spans = service(tmp).validate(
            ValidateRequest("mojmap", listOf(target("dismount", "run", "()V", at))),
            listOf("1.21", "1.21.1"),
        ).results.single().spans

        assertEquals(listOf("ok", "call_moved"), spans.map { it.status })
        assertEquals("Mover#run", spans[1].movedTo)
    }

    @Test
    fun `renamed, inherited and missing each name what to hook instead`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        jarFor(
            store, "1.21",
            plain("Caller", "Parent", "run" to "(I)V"),
            plain("Parent", "java/lang/Object", "inheritedRun" to "()V"),
        )
        val request = ValidateRequest(
            "mojmap",
            listOf(
                target("renamed", "run", "()V"),
                target("inherited", "inheritedRun", "()V"),
                target("missing", "goneRun", "()V"),
            ),
        )

        val results = service(tmp).validate(request, listOf("1.21")).results

        assertEquals(listOf("renamed", "inherited", "missing"), results.map { it.spans.single().status })
        assertEquals("Caller:run:(I)V", results[0].spans.single().closest)
        assertEquals("Parent:inheritedRun:()V", results[1].spans.single().closest)
        assertNull(results[2].spans.single().closest)
    }
}
