package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferenceServiceTest {

    private fun emptyClass(name: String, configure: ClassWriter.() -> Unit = {}): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.configure()
        cw.visitEnd()
        return cw.toByteArray()
    }

    // B has static field x:I and static methods foo()V and bar()V.
    private val bBytes = emptyClass("B") {
        visitField(ACC_PUBLIC or ACC_STATIC, "x", "I", null, null).visitEnd()
        for (name in listOf("foo", "bar")) {
            visitMethod(ACC_PUBLIC or ACC_STATIC, name, "()V", null, null).apply {
                visitCode(); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
        }
    }
    private val cBytes = emptyClass("C")

    /**
     * A.m() reads B.x, calls B.foo twice, does `new C`, and calls its own helper. A.h() is that
     * helper. A.lambda$m$0() is what javac makes of a lambda written inside m(), and it reaches B.foo
     * through a method reference, which only lives in the invokedynamic bootstrap arguments.
     */
    private val aBytes = emptyClass("A") {
        visitMethod(ACC_PUBLIC, "m", "()V", null, null).apply {
            visitCode()
            visitFieldInsn(GETSTATIC, "B", "x", "I")
            visitInsn(POP)
            visitMethodInsn(INVOKESTATIC, "B", "foo", "()V", false)
            visitMethodInsn(INVOKESTATIC, "B", "foo", "()V", false)
            visitTypeInsn(NEW, "C")
            visitInsn(POP)
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKEVIRTUAL, "A", "h", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0); visitEnd()
        }
        visitMethod(ACC_PRIVATE, "h", "()V", null, null).apply {
            visitCode(); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
        visitMethod(ACC_PRIVATE or ACC_STATIC or ACC_SYNTHETIC, "lambda\$m\$0", "()V", null, null).apply {
            visitCode()
            visitInvokeDynamicInsn(
                "run", "()Ljava/lang/Runnable;",
                Handle(
                    H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                        "Ljava/lang/invoke/CallSite;",
                    false,
                ),
                org.objectweb.asm.Type.getType("()V"),
                Handle(H_INVOKESTATIC, "B", "bar", "()V", false),
                org.objectweb.asm.Type.getType("()V"),
            )
            visitInsn(POP)
            visitInsn(RETURN)
            visitMaxs(0, 0); visitEnd()
        }
    }

    private val index = ReferenceService.scan(setOf("A", "B", "C"), sequenceOf(aBytes, bBytes, cBytes))
    private val fromM = ReferenceService.Referrer("A", "m", "()V", "method")

    @Test
    fun `records method, field, and type references to the enclosing method`() {
        assertTrue(fromM in index.at("B:foo:()V"))
        assertEquals(setOf(fromM), index.at("B:x:I").keys)
        assertTrue(fromM in index.at("B"), "class ref to B via member owners")
        assertEquals(setOf(fromM), index.at("C").keys, "type ref via NEW")
    }

    @Test
    fun `counts repeated call sites instead of collapsing them`() {
        assertEquals(2, index.at("B:foo:()V")[fromM], "B.foo is called twice from A.m")
        assertEquals(1, index.at("B:x:I")[fromM])
    }

    @Test
    fun `records a call to a member of the calling class itself`() {
        assertEquals(setOf(fromM), index.at("A:h:()V").keys, "a walk upwards dies without this edge")
        assertTrue(index.at("A").isEmpty(), "but the class itself is not its own user")
    }

    @Test
    fun `folds a synthetic lambda body into the method it was written in`() {
        val referrer = index.at("B:bar:()V").keys.single()
        assertEquals("m", referrer.member, "the lambda index moves between versions, the name does not")
        assertEquals("()V", referrer.descriptor)
        assertEquals("lambda\$m\$0", referrer.synthetic)
    }

    @Test
    fun `follows a method reference hidden in an invokedynamic`() {
        assertTrue(index.at("B:bar:()V").isNotEmpty(), "B.bar is only reached through LambdaMetafactory")
    }

    @Test
    fun `drops references to non-Minecraft (JDK) classes`() {
        assertTrue(index.keys.none { it.startsWith("java/") }, "JDK targets must not be indexed")
    }

    @Test
    fun `walks the caller chain up to depth and reports it outermost first`(@TempDir tmp: Path) {
        val service = ReferenceService(jarWith(tmp, VERSION, aBytes, bBytes, cBytes))

        val oneLevel = service.references(listOf(VERSION), listOf("A:h:()V"), "mojmap")
        assertEquals(emptyList(), oneLevel!!.results.single().paths, "depth 1 answers with references alone")

        val walked = service.references(listOf(VERSION), listOf("B:foo:()V"), "mojmap", depth = 3)
        val paths = walked!!.results.single().paths
        // B.foo is called by A.m, which nothing calls: one chain, one frame.
        assertEquals(listOf(listOf("A#m")), paths.map { path -> path.map { "${it.owner}#${it.member}" } })

        val toHelper = service.references(listOf(VERSION), listOf("A:h:()V"), "mojmap", depth = 3)
        assertEquals(listOf(listOf("A#m")), toHelper!!.results.single().paths.map { p -> p.map { "${it.owner}#${it.member}" } })
    }

    @Test
    fun `unreferenced class has no entry`() {
        assertTrue(index.at("A").isEmpty())
    }

    @Test
    fun `diffReferences pairs a call that moved to another method`(@TempDir tmp: Path) {
        // Both callers reach the same one member of B, so the target sets alone cannot tell the two
        // moves apart: D keeps its name and renames the method, E renames the class and keeps it.
        fun caller(owner: String, method: String) = emptyClass(owner) {
            visitMethod(ACC_PUBLIC, method, "()V", null, null).apply {
                visitCode()
                visitMethodInsn(INVOKESTATIC, "B", "foo", "()V", false)
                visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        val config = jarWith(tmp, VERSION, caller("D", "collectColliders"), caller("E", "extractBlockOutline"), bBytes)
        jarWith(tmp, NEXT_VERSION, caller("D", "collectCollidersIgnoringWorldBorder"), caller("F", "extractBlockOutline"), bBytes)

        val diff = ReferenceService(config).diffReferences(VERSION, NEXT_VERSION, "B", "mojmap")

        assertNotNull(diff)
        assertEquals(
            listOf("D#collectColliders", "E#extractBlockOutline"),
            diff.changes.removed.map { "${it.owner}#${it.member}" },
        )
        assertEquals(listOf("foo:()V"), diff.changes.removed.first().targets)
        assertEquals(
            listOf("D#collectCollidersIgnoringWorldBorder", "F#extractBlockOutline"),
            diff.changes.added.map { "${it.owner}#${it.member}" },
        )
        assertEquals(
            setOf(
                "E#extractBlockOutline" to "F#extractBlockOutline",
                "D#collectColliders" to "D#collectCollidersIgnoringWorldBorder",
            ),
            diff.changes.moved.map { it.from to it.to }.toSet(),
        )
    }

    private fun jarWith(tmp: Path, version: String, vararg classes: ByteArray): AppConfig {
        val store = Files.createDirectories(tmp.resolve("artifact-store/remapped-mc/$version"))
        JarOutputStream(Files.newOutputStream(store.resolve("merged-remapped-map_mojmap-test.jar"))).use { jar ->
            for (bytes in classes) {
                jar.putNextEntry(ZipEntry(org.objectweb.asm.ClassReader(bytes).className + ".class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return AppConfig(
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
    }

    private companion object {
        const val VERSION = "1.21.1"
        const val NEXT_VERSION = "1.21.2"
    }
}
