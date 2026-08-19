package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BodyHashServiceTest {

    /** `Caller.run()V` pushes [constant], then calls `foo()V` on [callee]. */
    private fun caller(constant: Int, callee: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, "Caller", null, "java/lang/Object", null)
        cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            visitIntInsn(BIPUSH, constant)
            visitInsn(POP)
            visitMethodInsn(INVOKESTATIC, callee, "foo", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
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

    private fun addVersion(db: Database, id: String) = transaction(db) {
        VersionTable.insertAndGetId {
            it[versionId] = id
            it[releaseType] = "release"
            it[indexedAt] = Instant.now().toString()
            it[hasYarn] = true
            it[hasMojmap] = true
            it[hasIntermediary] = true
        }.value
    }

    private fun addClass(db: Database, versionRowId: Int, mojmap: String, intermediary: String) = transaction(db) {
        ClassTable.insertAndGetId {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[mojmapName] = mojmap
            it[intermediaryName] = intermediary
            it[simpleName] = mojmap.substringAfterLast('/')
        }
    }

    @Test
    fun `an unchanged body keeps its hash, and a changed one breaks the span`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        jarFor(store, "1.21", caller(1, "Callee"))
        jarFor(store, "1.21.1", caller(1, "Callee"))
        jarFor(store, "1.21.2", caller(2, "Callee"))
        val service = BodyHashService(config(tmp), Fixtures.newDb(tmp))

        val spans = service.hashes(listOf("Caller:run:()V"), "mojmap", listOf("1.21", "1.21.1", "1.21.2"), "named")
            .results.single().spans

        assertEquals(listOf("1.21" to "1.21.1", "1.21.2" to "1.21.2"), spans.map { it.from to it.to })
        assertEquals(listOf(2, 1), spans.map { it.versions })
        assertNotEquals(spans[0].hash, spans[1].hash)
    }

    @Test
    fun `a version without the method hashes to null`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        jarFor(store, "1.21", caller(1, "Callee"))
        jarFor(store, "1.21.1")
        val service = BodyHashService(config(tmp), Fixtures.newDb(tmp))

        val spans = service.hashes(listOf("Caller:run:()V"), "mojmap", listOf("1.21", "1.21.1"), "named")
            .results.single().spans

        assertEquals(2, spans.size)
        assertNull(spans[1].hash)
    }

    @Test
    fun `normalize=intermediary sees through a renamed class, named does not`(@TempDir tmp: Path) {
        val store = tmp.resolve("artifact-store")
        jarFor(store, "1.21", caller(1, "OldName"))
        jarFor(store, "1.21.1", caller(1, "NewName"))
        val db = Fixtures.newDb(tmp)
        addClass(db, addVersion(db, "1.21"), "OldName", "net/minecraft/class_1")
        addClass(db, addVersion(db, "1.21.1"), "NewName", "net/minecraft/class_1")
        val service = BodyHashService(config(tmp), db)
        val versions = listOf("1.21", "1.21.1")

        assertEquals(
            2,
            service.hashes(listOf("Caller:run:()V"), "mojmap", versions, "named").results.single().spans.size,
        )
        assertEquals(
            1,
            service.hashes(listOf("Caller:run:()V"), "mojmap", versions, "intermediary").results.single().spans.size,
        )
    }
}
