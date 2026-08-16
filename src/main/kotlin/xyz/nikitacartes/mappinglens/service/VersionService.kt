package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.FieldTable
import xyz.nikitacartes.mappinglens.db.tables.MethodTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.ClassEntry
import xyz.nikitacartes.mappinglens.model.ClassListResponse
import xyz.nikitacartes.mappinglens.model.VersionInfo
import xyz.nikitacartes.mappinglens.model.VersionListResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class VersionService(private val db: Database) {

    // The index is immutable for the server's lifetime (read-only, sole writer is the offline
    // indexer), so per-version row counts never change. Computing the three grouped COUNTs scans
    // ~50M index rows (~1.5s warm / ~6.5s cold on a full multi-version index); memoize them so only
    // the first /versions request pays. ponytail: a benign double-compute under a startup race is
    // fine (idempotent over immutable data); no lock needed.
    @Volatile
    private var countsCache: VersionCounts? = null

    private data class VersionCounts(
        val classes: Map<Int, Long>,
        val methods: Map<Int, Long>,
        val fields: Map<Int, Long>,
    )

    /** Must be called inside a [transaction]; populates and caches the per-version counts once. */
    private fun counts(): VersionCounts = countsCache ?: VersionCounts(
        classes = countsByVersion(ClassTable.versionId),
        methods = countsByVersion(MethodTable.versionId),
        fields = countsByVersion(FieldTable.versionId),
    ).also { countsCache = it }

    fun listVersions(): VersionListResponse = transaction(db) {
        val counts = counts()
        val versions = VersionTable.selectAll()
            .orderBy(VersionTable.sortIndex to SortOrder.DESC_NULLS_LAST, VersionTable.versionId to SortOrder.DESC)
            .map { row ->
                val versionRowId = row[VersionTable.id].value
                versionInfo(
                    row,
                    counts.classes[versionRowId] ?: 0,
                    counts.methods[versionRowId] ?: 0,
                    counts.fields[versionRowId] ?: 0,
                )
            }
        VersionListResponse(versions)
    }

    fun getVersion(versionId: String): VersionInfo? = transaction(db) {
        val row = VersionTable.selectAll().where { VersionTable.versionId eq versionId }.singleOrNull()
            ?: return@transaction null
        val versionRowId = row[VersionTable.id].value
        val (classes, methods, fields) = countsFor(versionRowId)
        versionInfo(row, classes, methods, fields)
    }

    private fun versionInfo(row: ResultRow, classes: Long, methods: Long, fields: Long) = VersionInfo(
        id = row[VersionTable.versionId],
        releaseType = row[VersionTable.releaseType],
        releaseTime = row[VersionTable.releaseTime],
        protocolVersion = row[VersionTable.protocolVersion],
        hasYarn = row[VersionTable.hasYarn],
        hasMojmap = row[VersionTable.hasMojmap],
        hasIntermediary = row[VersionTable.hasIntermediary],
        classCount = classes,
        methodCount = methods,
        fieldCount = fields,
        indexedAt = row[VersionTable.indexedAt],
    )

    /** Every class of one version (names per namespace + presence), for the client-side structure tree. */
    fun listClasses(versionId: String): ClassListResponse? = transaction(db) {
        val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq versionId }
            .singleOrNull()?.get(VersionTable.id)?.value ?: return@transaction null
        val classes = ClassTable.selectAll()
            .where { ClassTable.versionId eq versionRowId }
            .map { row ->
                ClassEntry(
                    obfuscated = row[ClassTable.obfName],
                    intermediary = row[ClassTable.intermediaryName],
                    yarn = row[ClassTable.yarnName],
                    mojmap = row[ClassTable.mojmapName],
                    presence = row[ClassTable.presence],
                )
            }
        ClassListResponse(versionId, classes)
    }

    /**
     * Returns the latest "release" version by semver order (via the persisted sort index),
     * falling back to any version if none. Versions without a sort index fall back to name order.
     */
    fun latestRelease(): String? = transaction(db) {
        val byRank = listOf(
            VersionTable.sortIndex to SortOrder.DESC_NULLS_LAST,
            VersionTable.versionId to SortOrder.DESC,
        ).toTypedArray()
        VersionTable.selectAll().where { VersionTable.releaseType eq "release" }
            .orderBy(*byRank).limit(1).singleOrNull()?.get(VersionTable.versionId)
            ?: VersionTable.selectAll().orderBy(*byRank).limit(1).singleOrNull()?.get(VersionTable.versionId)
    }

    private fun countsFor(versionRowId: Int): Triple<Long, Long, Long> {
        val classCount = ClassTable.selectAll()
            .where { ClassTable.versionId eq versionRowId }.count()
        val methodCount = MethodTable.selectAll()
            .where { MethodTable.versionId eq versionRowId }.count()
        val fieldCount = FieldTable.selectAll()
            .where { FieldTable.versionId eq versionRowId }.count()
        return Triple(classCount, methodCount, fieldCount)
    }

    /** Row count per version_id in a single grouped query (version_id -> count). */
    private fun countsByVersion(versionCol: Column<EntityID<Int>>): Map<Int, Long> {
        val cnt = versionCol.count()
        return versionCol.table.select(versionCol, cnt)
            .groupBy(versionCol)
            .associate { it[versionCol].value to it[cnt] }
    }
}
