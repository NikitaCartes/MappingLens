package dev.mappinglens.ingestion

import dev.mappinglens.config.AppConfig
import dev.mappinglens.data.GitCraftStore
import dev.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Instant

/**
 * Offline indexer: enumerates versions from the [GitCraftStore], joins their mappings by obfuscated
 * name and writes the read-only SQLite index (versions + classes/methods/fields + FTS5). This is the
 * single writer of the index; the server only ever reads it. Re-running is idempotent (per-version
 * replace), so it can simply be re-run when the GitCraft data is refreshed.
 */
class IngestPipeline(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(IngestPipeline::class.java)

    private val store = GitCraftStore(
        artifactStore = Paths.get(config.sources.artifactStore),
        intermediaryMappingsDir = Paths.get(config.sources.intermediaryMappings),
    )

    fun run(force: Boolean = false) {
        val allSorted = store.versionIds()
        val rankOf = allSorted.withIndex().associate { (i, v) -> v to i }
        val filterList = config.indexing.initialVersions.takeIf { it.isNotEmpty() }?.toSet()
        var targets = allSorted.filter { filterList == null || it in filterList }

        if (!force) {
            // Resume: skip versions already indexed (each version row is committed atomically with its
            // data, so its presence means it's complete). Re-run with -force to rebuild everything.
            val alreadyIndexed = transaction {
                VersionTable.selectAll().map { it[VersionTable.versionId] }.toSet()
            }
            val before = targets.size
            targets = targets.filter { it !in alreadyIndexed }
            if (before != targets.size) log.info("Skipping {} already-indexed versions", before - targets.size)
        }

        log.info("Indexing {} of {} versions", targets.size, allSorted.size)

        for (version in targets) {
            try {
                ingestVersion(version, rankOf[version])
            } catch (e: Exception) {
                log.error("Failed to index version {}: {}", version, e.message, e)
            }
        }
    }

    private fun ingestVersion(version: String, sortRank: Int?) {
        val src = store.resolve(version)
        if (!src.hasAny) {
            log.warn("Skipping {} (no resolvable mappings)", version)
            return
        }

        log.info("Indexing {}", version)
        val unified = store.parseUnified(version)
        log.info("  unified classes: {}", unified.size)

        val meta = src.meta
        val now = Instant.now().toString()
        val classifiedType = meta?.releaseType ?: classifyReleaseType(version)
        val release = meta?.releaseTime

        val existingId = transaction {
            VersionTable.selectAll().where { VersionTable.versionId eq version }
                .singleOrNull()?.get(VersionTable.id)?.value
        }

        transaction {
            // Replace existing data (idempotent re-index)
            val versionRowId = if (existingId != null) {
                MethodTable.deleteWhere { MethodTable.versionId eq existingId }
                FieldTable.deleteWhere { FieldTable.versionId eq existingId }
                ClassTable.deleteWhere { ClassTable.versionId eq existingId }
                SourceFileTable.deleteWhere { SourceFileTable.versionId eq existingId }
                exec("DELETE FROM search_index WHERE version_id = $existingId;")
                VersionTable.update({ VersionTable.id eq existingId }) {
                    it[releaseTime] = release
                    it[releaseType] = classifiedType
                    it[indexedAt] = now
                    it[sortIndex] = sortRank
                    it[hasYarn] = src.hasYarn
                    it[hasMojmap] = src.hasMojmap
                    it[hasIntermediary] = src.hasIntermediary
                }
                existingId
            } else {
                VersionTable.insertAndGetId {
                    it[versionId] = version
                    it[releaseType] = classifiedType
                    it[releaseTime] = release
                    it[indexedAt] = now
                    it[sortIndex] = sortRank
                    it[hasYarn] = src.hasYarn
                    it[hasMojmap] = src.hasMojmap
                    it[hasIntermediary] = src.hasIntermediary
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
                this[ClassTable.presence] = cls.presence
                val nameForPath = cls.yarnName ?: cls.mojmapName ?: cls.intermediaryName
                this[ClassTable.packagePath] = Names.packagePath(nameForPath)
                this[ClassTable.simpleName] = Names.simpleName(nameForPath)
            }.forEach { classIds += it[ClassTable.id].value }

            // Methods + Fields
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
        indexSourceFiles(version)
        log.info("Done indexing {}", version)
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
                val sourceJar = store.decompiledJar(versionId, mappingType)
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
