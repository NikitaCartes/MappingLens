package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.config.SourcesConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.FieldTable
import xyz.nikitacartes.mappinglens.db.tables.MethodTable
import xyz.nikitacartes.mappinglens.db.tables.SourceFileTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.service.BytecodeService
import xyz.nikitacartes.mappinglens.service.DiffService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Executable contracts for the last plan items that used to be future work.
 */
class PlanFutureContractTest {

    @Test
    fun `file diff reports methodsAdded methodsRemoved fieldsAdded and fieldsRemoved from class members`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)

        transaction(db) {
            val fromVersion = versionRowId(Fixtures.V_1_21)
            val toVersion = versionRowId(Fixtures.V_1_21_1)
            val fromClass = classRowId(fromVersion, Fixtures.block_1_21_1.yarn)
            val toClass = classRowId(toVersion, Fixtures.block_1_21_1.yarn)

            SourceFileTable.insert {
                it[versionId] = EntityID(fromVersion, VersionTable)
                it[mappingType] = "yarn"
                it[classId] = EntityID(fromClass, ClassTable)
                it[relativePath] = "net/minecraft/block/Block.java"
                it[contentHash] = "from-hash"
            }
            SourceFileTable.insert {
                it[versionId] = EntityID(toVersion, VersionTable)
                it[mappingType] = "yarn"
                it[classId] = EntityID(toClass, ClassTable)
                it[relativePath] = "net/minecraft/block/Block.java"
                it[contentHash] = "to-hash"
            }
            MethodTable.insert {
                it[versionId] = EntityID(toVersion, VersionTable)
                it[classId] = EntityID(toClass, ClassTable)
                it[obfName] = "x"
                it[obfDesc] = "()V"
                it[intermediaryName] = "method_added_for_file_diff"
                it[intermediaryDesc] = "()V"
                it[yarnName] = "addedForFileDiff"
                it[mojmapName] = "addedForFileDiff"
                it[simpleName] = "addedForFileDiff"
            }
            FieldTable.insert {
                it[versionId] = EntityID(toVersion, VersionTable)
                it[classId] = EntityID(toClass, ClassTable)
                it[obfName] = "y"
                it[obfDesc] = "I"
                it[intermediaryName] = "field_added_for_file_diff"
                it[intermediaryDesc] = "I"
                it[yarnName] = "ADDED_FOR_FILE_DIFF"
                it[mojmapName] = "ADDED_FOR_FILE_DIFF"
                it[simpleName] = "ADDED_FOR_FILE_DIFF"
            }
        }

        val diff = DiffService(db).diffFiles(Fixtures.V_1_21, Fixtures.V_1_21_1, "yarn", "net/minecraft/block")
        val modified = diff.files.modified.single { it.path == "net/minecraft/block/Block.java" }
        assertEquals(1, modified.methodsAdded)
        assertEquals(0, modified.methodsRemoved)
        assertEquals(1, modified.fieldsAdded)
        assertEquals(0, modified.fieldsRemoved)
    }

    @Test
    fun `bytecode output remaps class method and field names into requested namespace`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        seedBytecodeMapping(db)
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val jarDir = artifactStore.resolve("mc-versions/1.0").createDirectories()
        writeTinyJar(jarDir.resolve("merged-test.jar"))

        val config = AppConfig(
            databasePath = tmp.resolve("db.sqlite").toString(),
            sources = SourcesConfig(
                yarnRepo = tmp.resolve("yarn-src").toString(),
                mojmapRepo = tmp.resolve("moj-src").toString(),
                intermediaryMappings = tmp.toString(),
                artifactStore = artifactStore.toString(),
            ),
            initialVersions = emptyList(),
            search = SearchConfig(maxResults = 100, defaultResults = 20),
        )

        val response = BytecodeService(config, db).bytecode("1.0", "pkg/Foo", "yarn")
        assertNotNull(response)
        assertTrue(response.bytecode.contains("pkg/Foo"), response.bytecode)
        assertTrue(response.bytecode.contains("run"), response.bytecode)
        assertTrue(response.bytecode.contains("value"), response.bytecode)
    }

    @Test
    fun `openapi spec defines full schemas for diff translate bytecode source and errors`() {
        val spec = Files.readString(Path.of("src/main/resources/openapi/mappinglens-api.yaml"))
        val expectedSchemas = listOf(
            "ApiError:",
            "TranslateResponse:",
            "DiffResponse:",
            "FileDiffResponse:",
            "BytecodeResponse:",
            "SourceResponse:",
        )
        val expectedPaths = listOf("/openapi.json", "/docs")

        for (schema in expectedSchemas) assertTrue(spec.contains(schema), "OpenAPI schema missing $schema")
        for (path in expectedPaths) assertTrue(spec.contains(path), "OpenAPI path missing $path")
    }

    @Test
    fun `ingestion merges client and server mojmap tiny files for complete namespace coverage`(@TempDir tmp: Path) {
        val intermediaryDir = tmp.resolve("intermediary").createDirectories()
        val artifactStore = tmp.resolve("artifact-store").createDirectories()
        val mappings = artifactStore.resolve("mappings").createDirectories()
        val yarnRepo = tmp.resolve("yarn-src").createDirectories()
        val mojmapRepo = tmp.resolve("moj-src").createDirectories()
        val dbPath = tmp.resolve("pipeline.db")
        val db = DatabaseFactory.init(dbPath.toString())

        intermediaryDir.resolve("1.0.tiny").writeText(
            "tiny\t2\t0\tofficial\tintermediary\n" +
                "c\ta\tnet/minecraft/class_1\n" +
                "c\tb\tnet/minecraft/class_2\n",
        )
        mappings.resolve("1.0-yarn-build.1.tiny").writeText(
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n" +
                "c\ta\tnet/minecraft/class_1\tpkg/ClientOnly\n" +
                "c\tb\tnet/minecraft/class_2\tpkg/ServerOnly\n",
        )
        mappings.resolve("1.0-client-moj.tiny").writeText(
            "tiny\t2\t0\tofficial\tnamed\n" +
                "c\ta\tpkg/moj/ClientOnly\n",
        )
        mappings.resolve("1.0-server-moj.tiny").writeText(
            "tiny\t2\t0\tofficial\tnamed\n" +
                "c\tb\tpkg/moj/ServerOnly\n",
        )

        val config = AppConfig(
            databasePath = dbPath.toString(),
            sources = SourcesConfig(
                yarnRepo = yarnRepo.toString(),
                mojmapRepo = mojmapRepo.toString(),
                intermediaryMappings = intermediaryDir.toString(),
                artifactStore = artifactStore.toString(),
            ),
            initialVersions = listOf("1.0"),
            search = SearchConfig(maxResults = 100, defaultResults = 20),
        )

        IngestPipeline(config).run()

        val service = TranslationService(db, VersionService(db))
        val clientClass = service.translate("pkg/ClientOnly", "yarn", "mojmap", "1.0", "class")
        val serverClass = service.translate("pkg/ServerOnly", "yarn", "mojmap", "1.0", "class")
        assertEquals("pkg/moj/ClientOnly", clientClass?.output?.name)
        assertEquals("pkg/moj/ServerOnly", serverClass?.output?.name)
    }

    private fun versionRowId(version: String): Int = VersionTable.selectAll()
        .where { VersionTable.versionId eq version }
        .single()[VersionTable.id].value

    private fun classRowId(versionRowId: Int, yarnName: String): Int = ClassTable.selectAll()
        .where { (ClassTable.versionId eq versionRowId) and (ClassTable.yarnName eq yarnName) }
        .single()[ClassTable.id].value

    private fun seedBytecodeMapping(db: Database) = transaction(db) {
        val versionRowId = VersionTable.insertAndGetId {
            it[versionId] = "1.0"
            it[releaseType] = "release"
            it[indexedAt] = "now"
            it[hasYarn] = true
            it[hasMojmap] = true
            it[hasIntermediary] = true
        }.value
        val classRowId = ClassTable.insertAndGetId {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[obfName] = "a"
            it[intermediaryName] = "net/minecraft/class_1"
            it[yarnName] = "pkg/Foo"
            it[mojmapName] = "pkg/moj/Foo"
            it[packagePath] = "pkg"
            it[simpleName] = "Foo"
        }.value
        MethodTable.insert {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[classId] = EntityID(classRowId, ClassTable)
            it[obfName] = "m"
            it[obfDesc] = "()V"
            it[intermediaryName] = "method_1"
            it[intermediaryDesc] = "()V"
            it[yarnName] = "run"
            it[mojmapName] = "runMoj"
            it[simpleName] = "run"
        }
        FieldTable.insert {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[classId] = EntityID(classRowId, ClassTable)
            it[obfName] = "f"
            it[obfDesc] = "I"
            it[intermediaryName] = "field_1"
            it[intermediaryDesc] = "I"
            it[yarnName] = "value"
            it[mojmapName] = "valueMoj"
            it[simpleName] = "value"
        }
    }

    private fun writeTinyJar(jar: Path) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "f", "I", null, null).visitEnd()
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null)
        method.visitCode()
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 1)
        method.visitEnd()
        writer.visitEnd()

        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(ZipEntry("a.class"))
            output.write(writer.toByteArray())
            output.closeEntry()
        }
    }
}
