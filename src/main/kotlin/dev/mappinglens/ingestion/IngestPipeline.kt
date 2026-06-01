package dev.mappinglens.ingestion

import dev.mappinglens.config.AppConfig
import dev.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Instant

/**
 * Orchestrates ingestion of all available versions: parses mappings, joins them by obfuscated
 * names, and writes to the SQLite database (including FTS5 search index).
 */
class IngestPipeline(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(IngestPipeline::class.java)

    fun run() {
        val discovery = VersionDiscovery(config.sources)
        val versions = discovery.discover()
        val filterList = config.indexing.initialVersions.takeIf { it.isNotEmpty() }?.toSet()
        val targets = versions.filter { filterList == null || it.versionId in filterList }
        log.info("Ingesting {} versions", targets.size)

        val yarnGit = GitWatcher(Paths.get(config.sources.yarnRepo)).getCurrentRev()
        val mojGit = GitWatcher(Paths.get(config.sources.mojmapRepo)).getCurrentRev()

        for (vf in targets) {
            try {
                ingestVersion(vf, yarnGit, mojGit)
            } catch (e: Exception) {
                log.error("Failed to ingest version {}: {}", vf.versionId, e.message, e)
            }
        }
    }

    private fun ingestVersion(vf: VersionDiscovery.VersionFiles, yarnGit: String?, mojGit: String?) {
        val (existingId, storedYarnGit, storedMojGit) = transaction {
            VersionTable.selectAll().where { VersionTable.versionId eq vf.versionId }
                .singleOrNull()
                ?.let { Triple(it[VersionTable.id].value, it[VersionTable.gitRevYarn], it[VersionTable.gitRevMojmap]) }
                ?: Triple(null, null, null)
        }

        val gitChanged = (yarnGit != null && yarnGit != storedYarnGit) ||
            (mojGit != null && mojGit != storedMojGit)
        if (existingId != null && !gitChanged) {
            log.info("Skipping version {} (up-to-date)", vf.versionId)
            return
        }

        log.info("Parsing mappings for {}", vf.versionId)
        val intermediary = vf.intermediary?.let { TinyV2Parser.parse(it) }
        val yarn = vf.yarn?.let { TinyV2Parser.parse(it) }
        val mojmap = mergeMappings(vf.mojmaps.map { TinyV2Parser.parse(it) })

        val unified = if (vf.unobfuscated && vf.unobfuscatedJar != null) {
            log.info("  unobfuscated jar scan: {}", vf.unobfuscatedJar)
            UnobfuscatedJarScanner.scan(vf.unobfuscatedJar)
        } else {
            CorrespondenceResolver.resolve(intermediary, yarn, mojmap)
        }
        log.info("  unified classes: {}", unified.size)

        val now = Instant.now().toString()
        val releaseType = classifyReleaseType(vf.versionId)

        transaction {
            // Replace existing data
            val versionRowId = if (existingId != null) {
                MethodTable.deleteWhere { MethodTable.versionId eq existingId }
                FieldTable.deleteWhere { FieldTable.versionId eq existingId }
                ClassTable.deleteWhere { ClassTable.versionId eq existingId }
                SourceFileTable.deleteWhere { SourceFileTable.versionId eq existingId }
                exec("DELETE FROM search_index WHERE version_id = $existingId;")
                VersionTable.update({ VersionTable.id eq existingId }) {
                    it[releaseTime] = null
                    it[VersionTable.releaseType] = releaseType
                    it[indexedAt] = now
                    it[gitRevYarn] = yarnGit
                    it[gitRevMojmap] = mojGit
                    it[hasYarn] = yarn != null
                    it[hasMojmap] = mojmap != null || vf.unobfuscated
                    it[hasIntermediary] = intermediary != null
                }
                existingId
            } else {
                VersionTable.insertAndGetId {
                    it[versionId] = vf.versionId
                    it[VersionTable.releaseType] = releaseType
                    it[indexedAt] = now
                    it[gitRevYarn] = yarnGit
                    it[gitRevMojmap] = mojGit
                    it[hasYarn] = yarn != null
                    it[hasMojmap] = mojmap != null || vf.unobfuscated
                    it[hasIntermediary] = intermediary != null
                }.value
            }

            // Insert classes (and capture generated ids in order)
            val classIds = ArrayList<Int>(unified.size)
            // Batch insert classes
            ClassTable.batchInsert(unified, shouldReturnGeneratedValues = true) { cls ->
                this[ClassTable.versionId] = versionRowId
                this[ClassTable.obfName] = cls.obfName
                this[ClassTable.intermediaryName] = cls.intermediaryName
                this[ClassTable.yarnName] = cls.yarnName
                this[ClassTable.mojmapName] = cls.mojmapName
                val nameForPath = cls.yarnName ?: cls.mojmapName ?: cls.intermediaryName
                this[ClassTable.packagePath] = Names.packagePath(nameForPath)
                this[ClassTable.simpleName] = Names.simpleName(nameForPath)
            }.forEach { classIds += it[ClassTable.id].value }

            // Methods + Fields
            val methodInsertRows = ArrayList<Triple<Int, UnifiedMemberEntry, Int>>() // (classRowId, member, generatedId placeholder)
            unified.forEachIndexed { idx, cls ->
                val classRowId = classIds[idx]
                val nameForPath = cls.yarnName ?: cls.mojmapName ?: cls.intermediaryName
                val classSimple = Names.simpleName(nameForPath)
                if (cls.methods.isNotEmpty()) {
                    val methodIds = MethodTable.batchInsert(cls.methods, shouldReturnGeneratedValues = true) { m ->
                        this[MethodTable.classId] = classRowId
                        this[MethodTable.versionId] = versionRowId
                        this[MethodTable.obfName] = m.obfName
                        this[MethodTable.obfDesc] = m.obfDesc
                        this[MethodTable.intermediaryName] = m.intermediaryName
                        this[MethodTable.intermediaryDesc] = m.intermediaryDesc
                        this[MethodTable.yarnName] = m.yarnName
                        this[MethodTable.mojmapName] = m.mojmapName
                        this[MethodTable.simpleName] = m.yarnName ?: m.mojmapName ?: m.intermediaryName
                    }.map { it[MethodTable.id].value }
                    insertMethodFtsBatch(versionRowId, cls, methodIds, classSimple)
                }
                if (cls.fields.isNotEmpty()) {
                    val fieldIds = FieldTable.batchInsert(cls.fields, shouldReturnGeneratedValues = true) { f ->
                        this[FieldTable.classId] = classRowId
                        this[FieldTable.versionId] = versionRowId
                        this[FieldTable.obfName] = f.obfName
                        this[FieldTable.obfDesc] = f.obfDesc
                        this[FieldTable.intermediaryName] = f.intermediaryName
                        this[FieldTable.intermediaryDesc] = f.intermediaryDesc
                        this[FieldTable.yarnName] = f.yarnName
                        this[FieldTable.mojmapName] = f.mojmapName
                        this[FieldTable.simpleName] = f.yarnName ?: f.mojmapName ?: f.intermediaryName
                    }.map { it[FieldTable.id].value }
                    insertFieldFtsBatch(versionRowId, cls, fieldIds, classSimple)
                }
            }

            // Class FTS rows
            insertClassFtsBatch(versionRowId, unified, classIds)
        }

        // Source files outside the heavy mapping transaction
        indexSourceFiles(vf.versionId)
        log.info("Done ingesting {}", vf.versionId)
    }

    private fun mergeMappings(mappings: List<ParsedMappings>): ParsedMappings? {
        if (mappings.isEmpty()) return null
        if (mappings.size == 1) return mappings.single()

        val namespaces = mappings.first().namespaces
        val classesByObf = linkedMapOf<String, ParsedClass>()
        for (mapping in mappings) {
            if (mapping.namespaces != namespaces) {
                log.warn(
                    "Skipping mapping merge input with namespaces {} because expected {}",
                    mapping.namespaces,
                    namespaces,
                )
                continue
            }
            for (cls in mapping.classes) {
                val key = cls.names.getOrNull(0) ?: cls.names.filterNotNull().joinToString("|")
                if (key.isBlank()) continue
                classesByObf[key] = classesByObf[key]?.let { mergeClass(it, cls) } ?: cls
            }
        }
        return ParsedMappings(namespaces, classesByObf.values.toList())
    }

    private fun mergeClass(left: ParsedClass, right: ParsedClass): ParsedClass = ParsedClass(
        names = mergeNullableLists(left.names, right.names),
        methods = mergeMethods(left.methods, right.methods),
        fields = mergeFields(left.fields, right.fields),
    )

    private fun mergeMethods(left: List<ParsedMethod>, right: List<ParsedMethod>): List<ParsedMethod> {
        val merged = linkedMapOf<Pair<String, String>, ParsedMethod>()
        fun add(method: ParsedMethod) {
            val key = memberKey(method.names, method.descs)
            merged[key] = merged[key]?.let {
                ParsedMethod(
                    names = mergeNullableLists(it.names, method.names),
                    descs = mergeNullableLists(it.descs, method.descs),
                )
            } ?: method
        }
        left.forEach(::add)
        right.forEach(::add)
        return merged.values.toList()
    }

    private fun mergeFields(left: List<ParsedField>, right: List<ParsedField>): List<ParsedField> {
        val merged = linkedMapOf<Pair<String, String>, ParsedField>()
        fun add(field: ParsedField) {
            val key = memberKey(field.names, field.descs)
            merged[key] = merged[key]?.let {
                ParsedField(
                    names = mergeNullableLists(it.names, field.names),
                    descs = mergeNullableLists(it.descs, field.descs),
                )
            } ?: field
        }
        left.forEach(::add)
        right.forEach(::add)
        return merged.values.toList()
    }

    private fun memberKey(names: List<String?>, descs: List<String?>): Pair<String, String> {
        val name = names.getOrNull(0) ?: names.filterNotNull().joinToString("|")
        val desc = descs.getOrNull(0) ?: descs.filterNotNull().joinToString("|")
        return name to desc
    }

    private fun mergeNullableLists(left: List<String?>, right: List<String?>): List<String?> {
        val size = maxOf(left.size, right.size)
        return (0 until size).map { idx -> left.getOrNull(idx) ?: right.getOrNull(idx) }
    }

    private fun Transaction.insertClassFtsBatch(versionRowId: Int, classes: List<UnifiedClassEntry>, classIds: List<Int>) {
        if (classes.isEmpty()) return
        // Build multi-row inserts in chunks. Real Minecraft versions have thousands of classes;
        // one giant VALUES statement can exceed SQLite's statement size limit.
        classes.indices.chunked(500).forEach { chunk ->
            val sb = StringBuilder("INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name) VALUES ")
            chunk.forEachIndexed { chunkIdx, idx ->
                val c = classes[idx]
                if (chunkIdx > 0) sb.append(',')
                sb.append("('class',")
                sb.append(classIds[idx]).append(',')
                sb.append(versionRowId).append(',')
                sb.append(quote(c.yarnName)).append(',')
                sb.append(quote(c.mojmapName)).append(',')
                sb.append(quote(c.intermediaryName)).append(',')
                sb.append(quote(c.obfName)).append(',')
                sb.append(quote(Names.simpleName(c.yarnName ?: c.mojmapName ?: c.intermediaryName)))
                sb.append(')')
            }
            sb.append(';')
            exec(sb.toString())
        }
    }

    private fun Transaction.insertMethodFtsBatch(versionRowId: Int, cls: UnifiedClassEntry, methodIds: List<Int>, classSimple: String?) {
        if (cls.methods.isEmpty()) return
        val sb = StringBuilder("INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name) VALUES ")
        cls.methods.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(',')
            val ownerYarn = cls.yarnName
            val ownerMoj = cls.mojmapName
            val ownerInterm = cls.intermediaryName
            val yarn = if (m.yarnName != null && ownerYarn != null) "$ownerYarn#${m.yarnName}" else m.yarnName
            val moj = if (m.mojmapName != null && ownerMoj != null) "$ownerMoj#${m.mojmapName}" else m.mojmapName
            val interm = if (m.intermediaryName != null && ownerInterm != null) "$ownerInterm#${m.intermediaryName}" else m.intermediaryName
            sb.append("('method',")
            sb.append(methodIds[idx]).append(',')
            sb.append(versionRowId).append(',')
            sb.append(quote(yarn)).append(',')
            sb.append(quote(moj)).append(',')
            sb.append(quote(interm)).append(',')
            sb.append(quote(m.obfName)).append(',')
            sb.append(quote(m.yarnName ?: m.mojmapName ?: m.intermediaryName))
            sb.append(')')
        }
        sb.append(';')
        exec(sb.toString())
    }

    private fun Transaction.insertFieldFtsBatch(versionRowId: Int, cls: UnifiedClassEntry, fieldIds: List<Int>, classSimple: String?) {
        if (cls.fields.isEmpty()) return
        val sb = StringBuilder("INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name) VALUES ")
        cls.fields.forEachIndexed { idx, f ->
            if (idx > 0) sb.append(',')
            val ownerYarn = cls.yarnName
            val ownerMoj = cls.mojmapName
            val ownerInterm = cls.intermediaryName
            val yarn = if (f.yarnName != null && ownerYarn != null) "$ownerYarn#${f.yarnName}" else f.yarnName
            val moj = if (f.mojmapName != null && ownerMoj != null) "$ownerMoj#${f.mojmapName}" else f.mojmapName
            val interm = if (f.intermediaryName != null && ownerInterm != null) "$ownerInterm#${f.intermediaryName}" else f.intermediaryName
            sb.append("('field',")
            sb.append(fieldIds[idx]).append(',')
            sb.append(versionRowId).append(',')
            sb.append(quote(yarn)).append(',')
            sb.append(quote(moj)).append(',')
            sb.append(quote(interm)).append(',')
            sb.append(quote(f.obfName)).append(',')
            sb.append(quote(f.yarnName ?: f.mojmapName ?: f.intermediaryName))
            sb.append(')')
        }
        sb.append(';')
        exec(sb.toString())
    }

    private fun quote(s: String?): String {
        if (s == null) return "''"
        return "'" + s.replace("'", "''") + "'"
    }

    private fun indexSourceFiles(versionId: String) {
        val sourcePairs = listOf(
            "yarn" to config.sources.yarnRepo,
            "mojmap" to config.sources.mojmapRepo,
        )
        for ((mappingType, root) in sourcePairs) {
            val rootPath = Paths.get(root)
            val scanner = SourceScanner(rootPath)
            val files = try {
                val sourceJar = config.sources.decompiledSourceJar(versionId, mappingType)
                if (sourceJar != null) SourceScanner.scanJar(sourceJar) else scanner.scan(versionId)
            } catch (e: Exception) {
                log.warn("Source scan failed for {} at {}: {}", mappingType, root, e.message); continue
            }
            if (files.isEmpty()) continue
            transaction {
                val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq versionId }
                    .single()[VersionTable.id].value
                val classNameColumn = if (mappingType == "mojmap") ClassTable.mojmapName else ClassTable.yarnName
                val classIdsByName = ClassTable.selectAll()
                    .where { ClassTable.versionId eq versionRowId }
                    .associate { row -> row[classNameColumn] to row[ClassTable.id].value }
                files.chunked(2000).forEach { chunk ->
                    SourceFileTable.batchInsert(chunk, ignore = true) { sf ->
                        this[SourceFileTable.versionId] = versionRowId
                        this[SourceFileTable.mappingType] = mappingType
                        classIdsByName[sf.classFqn]?.let { this[SourceFileTable.classId] = it }
                        this[SourceFileTable.relativePath] = sf.relativePath
                        this[SourceFileTable.contentHash] = sf.contentHash
                    }
                }
            }
            log.info("Indexed {} source files ({}) for {}", files.size, mappingType, versionId)
        }
    }

    private fun classifyReleaseType(versionId: String): String {
        return when {
            versionId.contains("rc", ignoreCase = true) -> "release-candidate"
            versionId.contains("pre", ignoreCase = true) -> "pre-release"
            Regex("\\d+w\\d+[a-z]?").matches(versionId) -> "snapshot"
            Regex("\\d+\\.\\d+(\\.\\d+)?").matches(versionId) -> "release"
            else -> "snapshot"
        }
    }
}
