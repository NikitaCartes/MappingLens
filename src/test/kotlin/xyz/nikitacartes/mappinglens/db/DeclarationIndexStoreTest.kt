package xyz.nikitacartes.mappinglens.db

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.service.ExistsService
import xyz.nikitacartes.mappinglens.service.HierarchyService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_ABSTRACT
import org.objectweb.asm.Opcodes.ACC_INTERFACE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The declaration index and the whole-jar scan it replaces must answer alike, since a version the
 * file does not cover falls back to the scan. Every test here builds the same three-class jar and
 * asks it both ways.
 */
class DeclarationIndexStoreTest {

    private companion object {
        const val VERSION = "1.21.1"
        const val NS = "mojmap"
    }

    // A (abstract) <- B implements I; B <- C. B declares foo()V, foo(I)V and x:I.
    private val aBytes = classOf("A", ACC_ABSTRACT)
    private val iBytes = classOf("I", ACC_INTERFACE)
    private val bBytes = classOf("B", 0, superName = "A", interfaces = arrayOf("I")) {
        visitField(ACC_PUBLIC or ACC_STATIC, "x", "I", null, null).visitEnd()
        for (desc in listOf("()V", "(I)V")) {
            visitMethod(ACC_PUBLIC or ACC_STATIC, "foo", desc, null, null).apply {
                visitCode(); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
        }
    }
    private val cBytes = classOf("C", 0, superName = "B")

    private fun classOf(
        name: String,
        access: Int,
        superName: String = "java/lang/Object",
        interfaces: Array<String>? = null,
        configure: ClassWriter.() -> Unit = {},
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_8, ACC_PUBLIC or access, name, null, superName, interfaces)
        cw.configure()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `scan reads the header, the members and the subtype edges`(@TempDir tmp: Path) {
        val classes = DeclarationIndexStore.scan(jarOf(tmp).toFile())

        assertEquals(setOf("A", "I", "B", "C"), classes.keys)
        val b = classes.getValue("B")
        assertEquals(listOf("A", "I"), b.supertypes)
        assertEquals(listOf("C"), b.subtypes)
        assertEquals(listOf("x:I", "foo:()V", "foo:(I)V"), b.members)
        assertEquals(listOf("B"), classes.getValue("A").subtypes)
        assertEquals(listOf("B"), classes.getValue("I").subtypes, "an interface collects implementors")
        assertTrue(classes.getValue("C").subtypes.isEmpty())
        // java/lang/Object is named by the header but is not in the jar, so it gets no row.
        assertEquals(listOf("java/lang/Object"), classes.getValue("A").supertypes)
        assertNull(classes["java/lang/Object"])
        assertTrue(classes.getValue("I").access and ACC_INTERFACE != 0)
        assertTrue(classes.getValue("A").access and ACC_ABSTRACT != 0)
    }

    @Test
    fun `a stored class comes back as it was scanned`(@TempDir tmp: Path) {
        val db = tmp.resolve("mappinglens.db").toString()
        val scanned = DeclarationIndexStore.scan(jarOf(tmp).toFile())

        DeclarationIndexStore.openWritable(db).use { conn ->
            DeclarationIndexStore.write(conn, VERSION, NS, scanned)
            conn.commit()
        }

        DeclarationIndexStore.openReadOnly(db)!!.use { conn ->
            assertEquals(setOf(VERSION to NS), DeclarationIndexStore.built(conn))
            DeclarationIndexStore.readStatement(conn).use { ps ->
                for ((owner, decl) in scanned) {
                    assertEquals(decl, DeclarationIndexStore.read(ps, VERSION, NS, owner), owner)
                }
                assertNull(DeclarationIndexStore.read(ps, VERSION, NS, "Absent"))
                assertNull(DeclarationIndexStore.read(ps, "1.21.2", NS, "B"), "another version")
                assertNull(DeclarationIndexStore.read(ps, VERSION, "yarn", "B"), "another namespace")
            }
        }
    }

    @Test
    fun `a class with no member and no supertype survives the round trip`(@TempDir tmp: Path) {
        val db = tmp.resolve("mappinglens.db").toString()
        val bare = ClassDecl(access = 0, supertypes = emptyList(), subtypes = emptyList(), members = emptyList())
        DeclarationIndexStore.openWritable(db).use { conn ->
            DeclarationIndexStore.write(conn, VERSION, NS, mapOf("Bare" to bare))
            conn.commit()
        }
        DeclarationIndexStore.openReadOnly(db)!!.use { conn ->
            DeclarationIndexStore.readStatement(conn).use { ps ->
                assertEquals(bare, DeclarationIndexStore.read(ps, VERSION, NS, "Bare"))
            }
        }
    }

    @Test
    fun `constructor names survive the round trip`(@TempDir tmp: Path) {
        // `<init>` opens with a character the encoding must not read as a prefix.
        val db = tmp.resolve("mappinglens.db").toString()
        val decl = ClassDecl(0, listOf("A"), listOf("C"), listOf("<init>:()V", "<clinit>:()V"))
        DeclarationIndexStore.openWritable(db).use { conn ->
            DeclarationIndexStore.write(conn, VERSION, NS, mapOf("B" to decl))
            conn.commit()
        }
        DeclarationIndexStore.openReadOnly(db)!!.use { conn ->
            DeclarationIndexStore.readStatement(conn).use { ps ->
                assertEquals(decl, DeclarationIndexStore.read(ps, VERSION, NS, "B"))
            }
        }
    }

    @Test
    fun `rebuilding a version replaces what was there`(@TempDir tmp: Path) {
        val db = tmp.resolve("mappinglens.db").toString()
        val decl = ClassDecl(0, emptyList(), emptyList(), emptyList())
        DeclarationIndexStore.openWritable(db).use { conn ->
            DeclarationIndexStore.write(conn, VERSION, NS, mapOf("Gone" to decl))
            DeclarationIndexStore.write(conn, VERSION, NS, mapOf("Kept" to decl))
            conn.commit()
        }
        DeclarationIndexStore.openReadOnly(db)!!.use { conn ->
            DeclarationIndexStore.readStatement(conn).use { ps ->
                assertNull(DeclarationIndexStore.read(ps, VERSION, NS, "Gone"))
                assertNotNull(DeclarationIndexStore.read(ps, VERSION, NS, "Kept"))
            }
        }
    }

    @Test
    fun `no file means no connection, and the reader falls back to scanning`(@TempDir tmp: Path) {
        assertNull(DeclarationIndexStore.openReadOnly(tmp.resolve("mappinglens.db").toString()))
    }

    @Test
    fun `exists answers the same prebuilt as scanned`(@TempDir tmp: Path) {
        val keys = listOf(
            "B",                    // a class that is there
            "Nope",                 // a class that is not
            "B:foo:()V",            // a member that is there
            "B:foo:(J)V",           // a descriptor that changed
            "B:foo",                // a name without a descriptor: report the overloads
            "B:x:I",                // a field that is there
            "B:x:J",                // a field whose descriptor changed
            "B:foo:I",              // a field descriptor on a name only methods declare
            "C:foo:()V",            // inherited from B
            "B:gone:()V",           // nothing like it
        )
        val prebuilt = ExistsService(withIndex(tmp)).exists(VERSION, NS, keys)
        val scanned = ExistsService(withoutIndex(tmp)).exists(VERSION, NS, keys)

        assertNotNull(prebuilt)
        assertEquals(scanned, prebuilt)
        val byKey = prebuilt.results.associateBy { it.key }
        assertTrue(byKey.getValue("B").exists)
        assertTrue(!byKey.getValue("Nope").exists)
        assertTrue(byKey.getValue("B:foo:()V").exists)
        assertEquals("B:foo:()V", byKey.getValue("B:foo:(J)V").closest)
        assertEquals("descriptor", byKey.getValue("B:foo:(J)V").reason)
        assertEquals(listOf("B:foo:()V", "B:foo:(I)V"), byKey.getValue("B:foo").candidates)
        assertEquals("B:foo:()V", byKey.getValue("C:foo:()V").closest)
        assertEquals("inherited", byKey.getValue("C:foo:()V").reason)
        assertEquals("B:x:I", byKey.getValue("B:x:J").closest)
        assertEquals("descriptor", byKey.getValue("B:x:J").reason, "a field against a field")
        assertEquals("B:foo:()V", byKey.getValue("B:foo:I").closest)
        assertEquals("kind", byKey.getValue("B:foo:I").reason, "a field against a method")
        assertNull(byKey.getValue("B:gone:()V").closest)
    }

    @Test
    fun `hierarchy answers the same prebuilt as scanned`(@TempDir tmp: Path) {
        val prebuilt = HierarchyService(withIndex(tmp)).hierarchy(VERSION, "B", NS)
        val scanned = HierarchyService(withoutIndex(tmp)).hierarchy(VERSION, "B", NS)

        assertNotNull(prebuilt)
        assertEquals(scanned, prebuilt)
        assertEquals(setOf("A", "I", "B", "C"), prebuilt.nodes.map { it.name }.toSet())
        assertEquals(
            setOf("A" to "B", "I" to "B", "B" to "C"),
            prebuilt.edges.map { it.parent to it.child }.toSet(),
        )
        assertNull(HierarchyService(withIndex(tmp)).hierarchy(VERSION, "Nope", NS))
    }

    /** A config whose database path has a declaration index beside it, holding the whole jar. */
    private fun withIndex(tmp: Path): AppConfig {
        val config = configFor(tmp, tmp.resolve("with-index/mappinglens.db"))
        if (!DeclarationIndexStore.exists(config.databasePath)) {
            DeclarationIndexStore.openWritable(config.databasePath).use { conn ->
                DeclarationIndexStore.write(conn, VERSION, NS, DeclarationIndexStore.scan(jarOf(tmp).toFile()))
                conn.commit()
            }
        }
        return config
    }

    /** A config with no declaration index, so every read falls back to the jar scan. */
    private fun withoutIndex(tmp: Path): AppConfig = configFor(tmp, tmp.resolve("no-index/mappinglens.db"))

    private fun configFor(tmp: Path, databasePath: Path): AppConfig {
        jarOf(tmp)
        return AppConfig(
            databasePath = databasePath.toString(),
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

    /** The four-class jar, written once per temporary directory. */
    private fun jarOf(tmp: Path): Path {
        val dir = Files.createDirectories(tmp.resolve("artifact-store/remapped-mc/$VERSION"))
        val jar = dir.resolve("merged-remapped-map_${NS}-test.jar")
        if (Files.exists(jar)) return jar
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for (bytes in listOf(aBytes, iBytes, bBytes, cBytes)) {
                out.putNextEntry(ZipEntry(ClassReader(bytes).className + ".class"))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }
}
