package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.lruCache
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.FieldTable
import xyz.nikitacartes.mappinglens.db.tables.MethodTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.ingestion.GitSourceRepository
import xyz.nikitacartes.mappinglens.ingestion.JarAnalyzer
import xyz.nikitacartes.mappinglens.model.BlameResponse
import xyz.nikitacartes.mappinglens.model.BytecodeResponse
import xyz.nikitacartes.mappinglens.model.SourceResponse
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
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
    // Measured over 1.21.4 and 1.21.8: the three reads cost 83 to 115ms warm and 712 to 720ms cold,
    // against 32ms for the same class's 7400 names read one at a time out of a purpose-built index,
    // so the bulk read stays. The limit comes from `cache.name-maps`.
    private val nameMapCache = lruCache<Pair<String, String>, BytecodeNameMaps>(config.cache.nameMaps)

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
        // Nested classes (Outer$Inner) live in the top-level class's .java file, so strip the
        // "$Inner" suffix to find the file. Try {root}/{version}/{file} first, then {root}/{file}.
        val rel = "${sourceClassName.substringBefore('$')}.java"
        readSourceFromArtifactStore(versionId, mappingType, rel)?.let { source ->
            return SourceResponse(versionId, sourceClassName, mappingType, source, rel)
        }

        if (!rootPath.exists()) return null
        val gitSource = GitSourceRepository(rootPath)
        if (gitSource.isGitWorkTree()) {
            val source = gitSource.read(versionId, rel) ?: return null
            return SourceResponse(versionId, sourceClassName, mappingType, source, rel)
        }

        val sourceRoot = GitSourceRepository.filesystemSourceRoot(rootPath) ?: return null
        val match = sourceRoot.resolve(rel)
        if (!Files.isRegularFile(match)) return null
        val source = Files.readString(match)
        val pathRel = sourceRoot.relativize(match).toString().replace('\\', '/')
        return SourceResponse(versionId, sourceClassName, mappingType, source, pathRel)
    }

    /**
     * Version that last changed each line of the class source, line 1 first.
     *
     * ponytail: the git worktree answers this, the decompiled-source jars cannot. A version that is
     * indexed from the artifact store alone therefore has no blame.
     */
    fun blame(versionId: String, className: String, namespace: String): BlameResponse? {
        val mappingType = if (namespace == "mojmap") "mojmap" else "yarn"
        val rootPath = Paths.get(if (mappingType == "yarn") config.sources.yarnRepo else config.sources.mojmapRepo)
        if (!rootPath.exists()) return null
        val sourceClassName = resolveSourceClassName(versionId, className, namespace, mappingType) ?: className
        val rel = "${sourceClassName.substringBefore('$')}.java"
        val perLine = GitSourceRepository(rootPath).blame(versionId, rel) ?: return null
        val (versions, lines) = blameIndex(perLine, variantBases(perLine.toSet()))
        return BlameResponse(versionId, sourceClassName, mappingType, rel, versions, lines)
    }

    /**
     * The per-line versions of a blame as the distinct list the response carries plus one index per
     * line into that list. Every id in [variantBase] is replaced by the version it is a variant of,
     * before the list is cut, so a base and its variant fold into one entry. Pure over the two
     * inputs (no repository, no database), so it is unit-testable.
     */
    internal fun blameIndex(perLine: List<String>, variantBase: Map<String, String>): Pair<List<String>, List<Int>> {
        val lines = perLine.map { variantBase[it] ?: it }
        val versions = lines.distinct()
        val index = versions.withIndex().associate { (i, v) -> v to i }
        return versions to lines.map { index.getValue(it) }
    }

    /**
     * The `_unobfuscated` ids among [ids], each mapped to the version it is a variant of. A variant
     * is a second pass over a build already indexed, so its commit changes only how the decompiler
     * named things. Blaming a line on the variant reports a rename as a change and names a version
     * that `/versions` does not list, so the line is attributed to the base version instead.
     */
    private fun variantBases(ids: Set<String>): Map<String, String> = transaction(db) {
        VersionTable.selectAll()
            .where { VersionTable.versionId inList ids }
            .mapNotNull { row -> row[VersionTable.variantOf]?.let { row[VersionTable.versionId] to it } }
            .toMap()
    }

    /**
     * Class names in [versionId] and [namespace] whose simple name is the simple name of [className].
     * A 404 hint for a class that moved package (`.../monster/ZombifiedPiglin` became
     * `.../monster/zombie/ZombifiedPiglin` in 1.21.11) or for an ambiguous simple name.
     */
    fun classCandidates(versionId: String, className: String, namespace: String, limit: Int = 10): List<String> = transaction(db) {
        val versionRowId = versionRowId(versionId) ?: return@transaction emptyList()
        val nameCol = nameColumn(namespace)
        sameSimpleName(versionRowId, nameCol, className).mapNotNull { it[nameCol] }.take(limit)
    }

    private fun readSourceFromArtifactStore(versionId: String, mappingType: String, relativePath: String): String? {
        val sourceJar = config.sources.decompiledSourceJar(versionId, mappingType) ?: return null
        return ZipFile(sourceJar.toFile()).use { zip ->
            val entry = zip.getEntry(relativePath) ?: return null
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    private fun resolveSourceClassName(versionId: String, className: String, fromNamespace: String, toMappingType: String): String? = transaction(db) {
        val versionRowId = versionRowId(versionId) ?: return@transaction null
        val fromCol = nameColumn(fromNamespace)
        val toCol = if (toMappingType == "mojmap") ClassTable.mojmapName else ClassTable.yarnName
        ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (fromCol eq className) }
            .firstOrNull()
            ?.get(toCol)
        // A class that moved package misses on its full name, and a bare simple name never matches
        // one. Both resolve when exactly one class of this version carries that simple name.
            ?: sameSimpleName(versionRowId, fromCol, className).singleOrNull()?.get(toCol)
    }

    private fun resolveObfName(versionId: String, className: String, namespace: String): String? = transaction(db) {
        val versionRowId = versionRowId(versionId) ?: return@transaction null
        val nameCol = nameColumn(namespace)
        ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (nameCol eq className) }
            .firstOrNull()?.get(ClassTable.obfName)
    }

    private fun versionRowId(versionId: String): Int? =
        VersionTable.selectAll().where { VersionTable.versionId eq versionId }
            .singleOrNull()?.get(VersionTable.id)?.value

    private fun nameColumn(namespace: String) = when (namespace) {
        "mojmap" -> ClassTable.mojmapName
        "intermediary" -> ClassTable.intermediaryName
        "obfuscated", "obf" -> ClassTable.obfName
        else -> ClassTable.yarnName
    }

    /**
     * Rows of [versionRowId] whose [nameCol] ends in the simple name of [className]. The LIKE only
     * narrows the scan to one version's classes, because `_` is a SQL wildcard and a legal character
     * in a class name. The tail is therefore compared exactly in Kotlin.
     */
    private fun sameSimpleName(versionRowId: Int, nameCol: org.jetbrains.exposed.sql.Column<String?>, className: String): List<ResultRow> {
        val simple = className.substringAfterLast('/')
        return ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (nameCol like "%/$simple") }
            .filter { it[nameCol]?.substringAfterLast('/') == simple }
    }

    private data class BytecodeNameMaps(
        val classes: Map<String, String>,
        val methods: Map<MemberKey, String>,
        val fields: Map<MemberKey, String>,
    )

    private data class MemberKey(val owner: String, val name: String, val descriptor: String)

    private fun bytecodeRemapper(versionId: String, namespace: String): Remapper? {
        if (namespace == "obfuscated" || namespace == "obf") return null
        val key = versionId to namespace
        val maps = nameMapCache[key] ?: loadBytecodeNameMaps(versionId, namespace).also { nameMapCache[key] = it }
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
