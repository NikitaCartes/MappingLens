package dev.mappinglens.service

import dev.mappinglens.config.AppConfig
import dev.mappinglens.db.tables.ClassTable
import dev.mappinglens.db.tables.FieldTable
import dev.mappinglens.db.tables.MethodTable
import dev.mappinglens.db.tables.VersionTable
import dev.mappinglens.ingestion.GitSourceRepository
import dev.mappinglens.ingestion.JarAnalyzer
import dev.mappinglens.model.BytecodeResponse
import dev.mappinglens.model.SourceResponse
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import org.objectweb.asm.commons.Remapper

class BytecodeService(private val config: AppConfig, private val db: Database) {

    // The index is immutable for the server's lifetime, so name maps (3 full-table reads) are cached
    // per (version, namespace) instead of being rebuilt on every intermediary bytecode request.
    private val nameMapCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, BytecodeNameMaps>()

    /**
     * Loads a JAR for the version and produces a textual disassembly via ASM Textifier.
     *
     * For `yarn`/`mojmap`, the pre-remapped JAR under `artifact-store/remapped-mc/{version}/`
     * is used directly with the requested class name; this skips loading the full
     * class/method/field maps from SQLite. The obfuscated merged JAR plus a DB-backed
     * remapper is used as the fallback (and is required for the `intermediary` namespace).
     */
    fun bytecode(versionId: String, className: String, namespace: String): BytecodeResponse? {
        if (namespace == "yarn" || namespace == "mojmap") {
            config.sources.remappedJar(versionId, namespace)?.let { remappedJar ->
                JarAnalyzer.disassembleText(remappedJar, className, null)?.let { text ->
                    return BytecodeResponse(versionId, className, text)
                }
            }
        }
        val obfName = resolveObfName(versionId, className, namespace) ?: return null
        val jar = locateJar(versionId) ?: return null
        val remapper = bytecodeRemapper(versionId, namespace)
        val text = JarAnalyzer.disassembleText(jar, obfName, remapper) ?: return null
        return BytecodeResponse(versionId, className, text)
    }

    fun source(versionId: String, className: String, namespace: String): SourceResponse? {
        val mappingType = if (namespace == "mojmap") "mojmap" else "yarn"
        val rootDir = if (mappingType == "yarn") config.sources.yarnRepo else config.sources.mojmapRepo
        val rootPath = Paths.get(rootDir)
        val sourceClassName = resolveSourceClassName(versionId, className, namespace, mappingType) ?: className
        // Try {root}/{version}/{className}.java first, then {root}/{className}.java
        val rel = "$sourceClassName.java"
        readSourceFromArtifactStore(versionId, mappingType, rel)?.let { source ->
            return SourceResponse(versionId, className, mappingType, source, rel)
        }

        if (!rootPath.exists()) return null
        val gitSource = GitSourceRepository(rootPath)
        if (gitSource.isGitWorkTree()) {
            val source = gitSource.read(versionId, rel) ?: return null
            return SourceResponse(versionId, className, mappingType, source, rel)
        }

        val sourceRoot = GitSourceRepository.filesystemSourceRoot(rootPath) ?: return null
        val match = sourceRoot.resolve(rel)
        if (!Files.isRegularFile(match)) return null
        val source = Files.readString(match)
        val pathRel = sourceRoot.relativize(match).toString().replace('\\', '/')
        return SourceResponse(versionId, className, mappingType, source, pathRel)
    }

    private fun readSourceFromArtifactStore(versionId: String, mappingType: String, relativePath: String): String? {
        val sourceJar = config.sources.decompiledSourceJar(versionId, mappingType) ?: return null
        return ZipFile(sourceJar.toFile()).use { zip ->
            val entry = zip.getEntry(relativePath) ?: return null
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    private fun resolveSourceClassName(versionId: String, className: String, fromNamespace: String, toMappingType: String): String? = transaction(db) {
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq versionId }
            .singleOrNull() ?: return@transaction null
        val versionRowId = versionRow[VersionTable.id].value
        val fromCol = when (fromNamespace) {
            "mojmap" -> ClassTable.mojmapName
            "intermediary" -> ClassTable.intermediaryName
            "obfuscated", "obf" -> ClassTable.obfName
            else -> ClassTable.yarnName
        }
        val toCol = if (toMappingType == "mojmap") ClassTable.mojmapName else ClassTable.yarnName
        ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (fromCol eq className) }
            .firstOrNull()
            ?.get(toCol)
    }

    private fun resolveObfName(versionId: String, className: String, namespace: String): String? = transaction(db) {
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq versionId }
            .singleOrNull() ?: return@transaction null
        val versionRowId = versionRow[VersionTable.id].value
        val nameCol = when (namespace) {
            "mojmap" -> ClassTable.mojmapName
            "intermediary" -> ClassTable.intermediaryName
            "obfuscated", "obf" -> ClassTable.obfName
            else -> ClassTable.yarnName
        }
        ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (nameCol eq className) }
            .firstOrNull()?.get(ClassTable.obfName)
    }

    private data class BytecodeNameMaps(
        val classes: Map<String, String>,
        val methods: Map<MemberKey, String>,
        val fields: Map<MemberKey, String>,
    )

    private data class MemberKey(val owner: String, val name: String, val descriptor: String)

    private fun bytecodeRemapper(versionId: String, namespace: String): Remapper? {
        if (namespace == "obfuscated" || namespace == "obf") return null
        val maps = nameMapCache.computeIfAbsent(versionId to namespace) { (v, ns) -> loadBytecodeNameMaps(v, ns) }
        if (maps.classes.isEmpty() && maps.methods.isEmpty() && maps.fields.isEmpty()) return null
        return object : Remapper() {
            override fun map(internalName: String): String = maps.classes[internalName] ?: internalName

            override fun mapMethodName(owner: String, name: String, descriptor: String): String =
                maps.methods[MemberKey(owner, name, descriptor)] ?: name

            override fun mapFieldName(owner: String, name: String, descriptor: String): String =
                maps.fields[MemberKey(owner, name, descriptor)] ?: name
        }
    }

    private fun loadBytecodeNameMaps(versionId: String, namespace: String): BytecodeNameMaps = transaction(db) {
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq versionId }
            .singleOrNull() ?: return@transaction BytecodeNameMaps(emptyMap(), emptyMap(), emptyMap())
        val versionRowId = versionRow[VersionTable.id].value

        val classes = ClassTable.selectAll()
            .where { ClassTable.versionId eq versionRowId }
            .associate { row -> row[ClassTable.obfName].orEmpty() to targetClassName(row, namespace) }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.isNotBlank() }

        val methods = MethodTable.innerJoin(ClassTable)
            .selectAll()
            .where { MethodTable.versionId eq versionRowId }
            .mapNotNull { row ->
                val owner = row[ClassTable.obfName] ?: return@mapNotNull null
                val name = row[MethodTable.obfName] ?: return@mapNotNull null
                val desc = row[MethodTable.obfDesc] ?: return@mapNotNull null
                val mapped = targetMethodName(row, namespace) ?: return@mapNotNull null
                MemberKey(owner, name, desc) to mapped
            }
            .toMap()

        val fields = FieldTable.innerJoin(ClassTable)
            .selectAll()
            .where { FieldTable.versionId eq versionRowId }
            .mapNotNull { row ->
                val owner = row[ClassTable.obfName] ?: return@mapNotNull null
                val name = row[FieldTable.obfName] ?: return@mapNotNull null
                val desc = row[FieldTable.obfDesc] ?: return@mapNotNull null
                val mapped = targetFieldName(row, namespace) ?: return@mapNotNull null
                MemberKey(owner, name, desc) to mapped
            }
            .toMap()

        BytecodeNameMaps(classes, methods, fields)
    }

    private fun targetClassName(row: ResultRow, namespace: String): String = when (namespace) {
        "mojmap" -> row[ClassTable.mojmapName]
        "intermediary" -> row[ClassTable.intermediaryName]
        else -> row[ClassTable.yarnName]
    } ?: row[ClassTable.obfName].orEmpty()

    private fun targetMethodName(row: ResultRow, namespace: String): String? = when (namespace) {
        "mojmap" -> row[MethodTable.mojmapName]
        "intermediary" -> row[MethodTable.intermediaryName]
        else -> row[MethodTable.yarnName]
    }

    private fun targetFieldName(row: ResultRow, namespace: String): String? = when (namespace) {
        "mojmap" -> row[FieldTable.mojmapName]
        "intermediary" -> row[FieldTable.intermediaryName]
        else -> row[FieldTable.yarnName]
    }

    private fun locateJar(versionId: String): Path? {
        val jarsDir = config.sources.minecraftJarsPath()
        val versionDir = jarsDir.resolve(versionId)
        if (!versionDir.exists() || !versionDir.isDirectory()) return null
        val candidates = Files.list(versionDir).use { stream ->
            stream.filter { p -> p.name.endsWith(".jar") }.toList()
        }
        // Prefer "merged-*.jar", then "client-*.jar"
        return candidates.firstOrNull { it.name.startsWith("merged-") }
            ?: candidates.firstOrNull { it.name.startsWith("client") }
            ?: candidates.firstOrNull()
    }
}
