package xyz.nikitacartes.mappinglens.ingestion

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.data.GitCraftStore
import xyz.nikitacartes.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
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
        unobfuscatedIntermediaryDir = config.sources.unobfuscatedIntermediaryMappings
            .takeIf { it.isNotBlank() }?.let { Paths.get(it) },
    )

    /** [only] restricts the run to the given version ids, overriding `indexing.initial-versions`. */
    fun run(force: Boolean = false, only: List<String> = emptyList()) {
        val allSorted = store.versionIds()
        val rankOf = allSorted.withIndex().associate { (i, v) -> v to i }
        val filterList = (only.takeIf { it.isNotEmpty() } ?: config.initialVersions)
            .takeIf { it.isNotEmpty() }?.toSet()
        filterList?.minus(allSorted.toSet())?.takeIf { it.isNotEmpty() }
            ?.let { log.warn("Requested versions are not in the store: {}", it.joinToString()) }
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

        populateFtsRanges()
    }

    /**
     * Records each version's inclusive [min,max] search_index rowid range so the server can prune a
     * search MATCH by rowid instead of post-filtering version_id (see VersionTable.ftsMinRowid).
     * A version's FTS rows are inserted contiguously, so one grouped scan over the FTS yields the
     * ranges; run once at the end of indexing (its cost is trivial next to building the index).
     */
    private fun populateFtsRanges() = transaction {
        val ranges = ArrayList<Triple<Int, Long, Long>>()
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.createStatement().use { st ->
            st.executeQuery("SELECT version_id, MIN(rowid), MAX(rowid) FROM search_index GROUP BY version_id").use { rs ->
                while (rs.next()) ranges += Triple(rs.getInt(1), rs.getLong(2), rs.getLong(3))
            }
        }
        ranges.forEach { (vid, lo, hi) ->
            VersionTable.update({ VersionTable.id eq vid }) {
                it[ftsMinRowid] = lo
                it[ftsMaxRowid] = hi
            }
        }
        log.info("Recorded FTS rowid ranges for {} versions", ranges.size)
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
        val protocol = store.protocolVersion(version)

        val existingId = transaction {
            VersionTable.selectAll().where { VersionTable.versionId eq version }
                .singleOrNull()?.get(VersionTable.id)?.value
        }

        // Recorded on the version row so /versions reads them instead of counting the rows itself.
        val classes = unified.size.toLong()
        val methods = unified.sumOf { it.methods.size }.toLong()
        val fields = unified.sumOf { it.fields.size }.toLong()

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
                    it[protocolVersion] = protocol
                    it[indexedAt] = now
                    it[sortIndex] = sortRank
                    it[hasYarn] = src.hasYarn
                    it[hasMojmap] = src.hasMojmap
                    it[hasIntermediary] = src.hasIntermediaryNames
                    it[classCount] = classes
                    it[methodCount] = methods
                    it[fieldCount] = fields
                }
                existingId
            } else {
                VersionTable.insertAndGetId {
                    it[versionId] = version
                    it[releaseType] = classifiedType
                    it[releaseTime] = release
                    it[protocolVersion] = protocol
                    it[indexedAt] = now
                    it[sortIndex] = sortRank
                    it[hasYarn] = src.hasYarn
                    it[hasMojmap] = src.hasMojmap
                    it[hasIntermediary] = src.hasIntermediaryNames
                    it[classCount] = classes
                    it[methodCount] = methods
                    it[fieldCount] = fields
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
                    insertMemberFtsBatch(versionRowId, cls, cls.methods, methodIds, "method")
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
                    insertMemberFtsBatch(versionRowId, cls, cls.fields, fieldIds, "field")
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

    /** Method and field FTS rows differ only in [elementType], so one insert serves both. */
    private fun Transaction.insertMemberFtsBatch(
        versionRowId: Int,
        cls: UnifiedClassEntry,
        members: List<UnifiedMemberEntry>,
        memberIds: List<Int>,
        elementType: String,
    ) {
        if (members.isEmpty()) return
        val sb = StringBuilder("INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name) VALUES ")
        members.forEachIndexed { idx, m ->
            if (idx > 0) sb.append(',')
            val yarn = if (m.yarnName != null && cls.yarnName != null) "${cls.yarnName}#${m.yarnName}" else m.yarnName
            val moj = if (m.mojmapName != null && cls.mojmapName != null) "${cls.mojmapName}#${m.mojmapName}" else m.mojmapName
            val interm = if (m.intermediaryName != null && cls.intermediaryName != null) "${cls.intermediaryName}#${m.intermediaryName}" else m.intermediaryName
            sb.append("('").append(elementType).append("',")
            sb.append(memberIds[idx]).append(',')
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
