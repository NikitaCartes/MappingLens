package xyz.nikitacartes.mappinglens.ingestion

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.data.GitCraftStore
import xyz.nikitacartes.mappinglens.db.ReferenceIndexStore
import xyz.nikitacartes.mappinglens.db.SearchIndex
import xyz.nikitacartes.mappinglens.service.ReferenceService
import xyz.nikitacartes.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.Instant
import xyz.nikitacartes.mappinglens.service.Descriptors

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
        mappings = config.mappings,
    )

    /**
     * [only] restricts the run to the given version ids, overriding `indexing.initial-versions`.
     * [references] is the scope of the prebuilt reverse-reference index: `releases`, `all` or `none`.
     */
    fun run(force: Boolean = false, only: List<String> = emptyList(), references: String = "releases") {
        val allSorted = store.versionIds()
        val rankOf = allSorted.withIndex().associate { (i, v) -> v to i }
        val filterList = (only.takeIf { it.isNotEmpty() } ?: config.initialVersions)
            .takeIf { it.isNotEmpty() }?.toSet()
        filterList?.minus(allSorted.toSet())?.takeIf { it.isNotEmpty() }
            ?.let { log.warn("Requested versions are not in the store: {}", it.joinToString()) }
        var targets = allSorted.filter { filterList == null || it in filterList }

        if (config.onlyReleases) {
            val before = targets.size
            targets = targets.filter(::isStableRelease)
            if (before != targets.size) log.info("Skipping {} versions that are not stable releases", before - targets.size)
        }

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

        val known = allSorted.toSet()
        SearchIndex.openWritable(config.databasePath).use { search ->
            for (version in targets) {
                try {
                    ingestVersion(version, rankOf[version], variantBase(version, known), search)
                    search.commit()
                } catch (e: Exception) {
                    search.rollback()
                    log.error("Failed to index version {}: {}", version, e.message, e)
                }
            }
            backfillSearchIndex(search)
        }

        syncSortIndex(rankOf)
        buildReferenceIndex(references)
    }

    /**
     * Builds the prebuilt reverse-reference index of every version in scope that has none, so the
     * server answers "who calls this" from a row instead of scanning the whole named jar.
     *
     * Releases by default. All 515 versions would cost 10.3 GB and 11 minutes where the 47 releases
     * cost 0.96 GB and a minute, and a version outside the file still answers, by the scan. A
     * (version, namespace) pair is skipped once built, since the jar behind it never changes.
     */
    private fun buildReferenceIndex(scope: String) {
        if (scope == "none") return
        val versions = transaction {
            VersionTable.selectAll()
                .where { VersionTable.variantOf.isNull() }
                .map { it[VersionTable.versionId] to it[VersionTable.releaseType] }
        }
        ReferenceIndexStore.openWritable(config.databasePath).use { conn ->
            val built = ReferenceIndexStore.built(conn)
            val todo = versions
                .filter { scope == "all" || it.second == "release" }
                .flatMap { (version, _) -> config.mappings.map { version to it } }
                .filter { it !in built && config.sources.remappedJar(it.first, it.second) != null }
            if (todo.isEmpty()) return
            log.info("Building the reference index of {} version/namespace pairs", todo.size)
            val started = System.currentTimeMillis()
            todo.forEach { (version, namespace) ->
                val jar = config.sources.remappedJar(version, namespace)!!.toFile()
                ReferenceIndexStore.write(conn, version, namespace, ReferenceService.scanJar(jar))
                conn.commit()
                log.debug("  references of {} ({}) built", version, namespace)
            }
            log.info("Built {} pairs in {}s", todo.size, (System.currentTimeMillis() - started) / 1000)
        }
    }

    /**
     * Builds the search table of every indexed version that has none. The names are read back out of
     * the index, so an index built before the search index moved to a file of its own is filled in
     * minutes instead of being re-read from the GitCraft store. It also repairs a version whose run
     * failed between writing its rows and writing its names, which are separate files and so are
     * written in separate transactions.
     */
    private fun backfillSearchIndex(search: java.sql.Connection) {
        val built = search.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'search\\_v%' ESCAPE '\\'")
                .use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        val missing = transaction {
            VersionTable.selectAll()
                .map { it[VersionTable.id].value to it[VersionTable.versionId] }
                .filter { SearchIndex.table(it.first) !in built }
        }
        if (missing.isEmpty()) return
        log.info("Building the search index of {} versions from the index itself", missing.size)
        val started = System.currentTimeMillis()
        var rows = 0L
        missing.forEach { (versionRowId, version) ->
            SearchIndex.createTable(search, versionRowId)
            rows += transaction {
                val conn = TransactionManager.current().connection.connection as java.sql.Connection
                var n = 0L
                SearchIndex.insertStatement(search, versionRowId).use { ps ->
                    n += copyClasses(conn, ps, versionRowId)
                    n += copyMembers(conn, ps, versionRowId, "method")
                    n += copyMembers(conn, ps, versionRowId, "field")
                    ps.executeBatch()
                }
                n
            }
            search.commit()
            search.createStatement().use { st ->
                val table = SearchIndex.table(versionRowId)
                st.execute("INSERT INTO $table($table) VALUES('optimize');")
            }
            log.debug("  search index of {} built", version)
        }
        log.info("Built {} rows in {}s", rows, (System.currentTimeMillis() - started) / 1000)
    }

    private fun copyClasses(conn: java.sql.Connection, ps: java.sql.PreparedStatement, versionRowId: Int): Long {
        var n = 0L
        conn.prepareStatement(
            "SELECT id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name " +
                "FROM classes WHERE version_id = ?"
        ).use { q ->
            q.setInt(1, versionRowId)
            q.executeQuery().use { rs ->
                while (rs.next()) {
                    SearchIndex.addRow(
                        ps, "class", rs.getInt(1),
                        SearchIndex.Names(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                    )
                    n++
                }
            }
        }
        return n
    }

    /** Member names are indexed qualified by their class, exactly as [insertMemberFtsBatch] writes them. */
    private fun copyMembers(
        conn: java.sql.Connection,
        ps: java.sql.PreparedStatement,
        versionRowId: Int,
        elementType: String,
    ): Long {
        var n = 0L
        conn.prepareStatement(
            "SELECT m.id, m.yarn_name, m.mojmap_name, m.intermediary_name, m.obf_name, m.simple_name, " +
                "c.yarn_name, c.mojmap_name, c.intermediary_name " +
                "FROM ${elementType}s m JOIN classes c ON c.id = m.class_id WHERE m.version_id = ?"
        ).use { q ->
            q.setInt(1, versionRowId)
            q.executeQuery().use { rs ->
                while (rs.next()) {
                    fun qualified(member: Int, owner: Int): String? {
                        val m = rs.getString(member) ?: return null
                        val o = rs.getString(owner) ?: return m
                        return "$o#$m"
                    }
                    SearchIndex.addRow(
                        ps, elementType, rs.getInt(1),
                        SearchIndex.Names(
                            yarn = qualified(2, 7),
                            mojmap = qualified(3, 8),
                            intermediary = qualified(4, 9),
                            obf = rs.getString(5),
                            simple = rs.getString(6),
                        ),
                    )
                    n++
                }
            }
        }
        return n
    }

    /**
     * Rewrites the sort index of every version row. A version the store gains in the middle of the
     * order shifts the rank of every version after it, and the loop above writes the rank of the
     * versions it ingested alone, so a plain run repairs the whole column here rather than leaving
     * the order stale until a forced rebuild of all versions.
     */
    private fun syncSortIndex(rankOf: Map<String, Int>) = transaction {
        var changed = 0
        VersionTable.selectAll().forEach { row ->
            val rank = rankOf[row[VersionTable.versionId]]
            if (row[VersionTable.sortIndex] != rank) {
                VersionTable.update({ VersionTable.id eq row[VersionTable.id] }) { it[sortIndex] = rank }
                changed++
            }
        }
        if (changed > 0) log.info("Refreshed the sort index of {} versions", changed)
    }

    /**
     * True for the versions `indexing.only-releases` keeps. Mojang's own release type decides, so
     * everything it types as a snapshot goes with the snapshots: pre-releases, release candidates,
     * April Fools versions and the combat snapshots. A `_unobfuscated` variant duplicates a build
     * that is indexed under its own id, and it inherits that build's release type, so the suffix
     * drops it rather than the type. This is what GitCraft's `--only-stable` selects, apart from the
     * `_unobfuscated` variants, which GitCraft types as `unobfuscated` rather than as a snapshot.
     */
    internal fun isStableRelease(version: String): Boolean =
        !version.endsWith("_unobfuscated") &&
            (store.catalog.get(version)?.releaseType ?: classifyReleaseType(version)) == "release"

    /**
     * The version [version] re-indexes, or null when it stands on its own. GitCraft derives
     * `<id>_unobfuscated` from Mojang's pre-deobfuscated jar for a build it also indexes normally,
     * so the two describe one build. The suffix alone does not make a variant: the base id has to be
     * indexed as well, otherwise the unobfuscated jar is that build's only record.
     */
    private fun variantBase(version: String, known: Set<String>): String? =
        version.removeSuffix("_unobfuscated").takeIf { it != version && it in known }

    private fun ingestVersion(version: String, sortRank: Int?, variantBase: String?, search: java.sql.Connection) {
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
                VersionTable.update({ VersionTable.id eq existingId }) {
                    it[releaseTime] = release
                    it[releaseType] = classifiedType
                    it[protocolVersion] = protocol
                    it[indexedAt] = now
                    it[sortIndex] = sortRank
                    it[hasYarn] = src.hasYarn
                    it[hasMojmap] = src.hasMojmap
                    it[hasIntermediary] = src.hasIntermediaryNames
                    it[variantOf] = variantBase
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
                    it[variantOf] = variantBase
                    it[classCount] = classes
                    it[methodCount] = methods
                    it[fieldCount] = fields
                }.value
            }

            // The names go to the search index, which is a file of its own, so writing them is not
            // part of this transaction. A version that fails part-way through leaves a search table
            // that does not match its rows; re-indexing that version drops and rebuilds the table.
            SearchIndex.createTable(search, versionRowId)

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

            // Class identity by official name, the same choice the member names make: intermediary
            // first, mojmap where a version carries none. [stableDesc] is built through this map.
            val stableClassName = HashMap<String, String>(unified.size)
            unified.forEach { cls ->
                val obf = cls.obfName ?: return@forEach
                val stable = cls.intermediaryName ?: cls.mojmapName ?: return@forEach
                stableClassName[obf] = stable
            }
            fun stableDesc(obfDesc: String?): String? =
                obfDesc?.let { desc -> Descriptors.mapTypes(desc) { stableClassName[it] } }

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
                        this[MethodTable.stableDesc] = stableDesc(m.obfDesc)
                        this[MethodTable.yarnName] = m.yarnName
                        this[MethodTable.mojmapName] = m.mojmapName
                        this[MethodTable.simpleName] = m.yarnName ?: m.mojmapName ?: m.intermediaryName
                    }.map { it[MethodTable.id].value }
                    insertMemberFtsBatch(search, versionRowId, cls, cls.methods, methodIds, "method")
                }
                if (cls.fields.isNotEmpty()) {
                    val fieldIds = FieldTable.batchInsert(cls.fields, shouldReturnGeneratedValues = true) { f ->
                        this[FieldTable.classId] = classRowId
                        this[FieldTable.versionId] = versionRowId
                        this[FieldTable.obfName] = f.obfName
                        this[FieldTable.obfDesc] = f.obfDesc
                        this[FieldTable.intermediaryName] = f.intermediaryName
                        this[FieldTable.intermediaryDesc] = f.intermediaryDesc
                        this[FieldTable.stableDesc] = stableDesc(f.obfDesc)
                        this[FieldTable.yarnName] = f.yarnName
                        this[FieldTable.mojmapName] = f.mojmapName
                        this[FieldTable.simpleName] = f.yarnName ?: f.mojmapName ?: f.intermediaryName
                    }.map { it[FieldTable.id].value }
                    insertMemberFtsBatch(search, versionRowId, cls, cls.fields, fieldIds, "field")
                }
            }

            // Class FTS rows
            insertClassFtsBatch(search, versionRowId, unified, classIds)
        }

        // Source files outside the heavy mapping transaction
        indexSourceFiles(version)
        log.info("Done indexing {}", version)
    }

    private fun insertClassFtsBatch(
        search: java.sql.Connection,
        versionRowId: Int,
        classes: List<UnifiedClassEntry>,
        classIds: List<Int>,
    ) = SearchIndex.insertRows(search, versionRowId, "class", classIds) { idx ->
        val c = classes[idx]
        SearchIndex.Names(
            yarn = c.yarnName,
            mojmap = c.mojmapName,
            intermediary = c.intermediaryName,
            obf = c.obfName,
            simple = Names.simpleName(c.yarnName ?: c.mojmapName ?: c.intermediaryName),
        )
    }

    /** Method and field rows differ only in [elementType], so one insert serves both. */
    private fun insertMemberFtsBatch(
        search: java.sql.Connection,
        versionRowId: Int,
        cls: UnifiedClassEntry,
        members: List<UnifiedMemberEntry>,
        memberIds: List<Int>,
        elementType: String,
    ) = SearchIndex.insertRows(search, versionRowId, elementType, memberIds) { idx ->
        val m = members[idx]
        SearchIndex.Names(
            yarn = if (m.yarnName != null && cls.yarnName != null) "${cls.yarnName}#${m.yarnName}" else m.yarnName,
            mojmap = if (m.mojmapName != null && cls.mojmapName != null) "${cls.mojmapName}#${m.mojmapName}" else m.mojmapName,
            intermediary = if (m.intermediaryName != null && cls.intermediaryName != null) "${cls.intermediaryName}#${m.intermediaryName}" else m.intermediaryName,
            obf = m.obfName,
            simple = m.yarnName ?: m.mojmapName ?: m.intermediaryName,
        )
    }

    private fun indexSourceFiles(versionId: String) {
        val sourcePairs = listOf(
            "yarn" to config.sources.yarnRepo,
            "mojmap" to config.sources.mojmapRepo,
        ).filter { (mappingType, _) -> mappingType in config.mappings }
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
