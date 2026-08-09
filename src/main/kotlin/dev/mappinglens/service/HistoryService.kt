package dev.mappinglens.service

import dev.mappinglens.db.tables.ClassTable
import dev.mappinglens.db.tables.FieldTable
import dev.mappinglens.db.tables.MethodTable
import dev.mappinglens.db.tables.VersionTable
import dev.mappinglens.model.HistoryEntry
import dev.mappinglens.model.HistoryMember
import dev.mappinglens.model.HistoryResponse
import dev.mappinglens.model.HistorySpan
import dev.mappinglens.routes.normalizeClassName
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * "Which versions have this, and under what name?" — one class or member tracked across every
 * indexed version, answered from the mapping tables alone (no jar or source access).
 *
 * The query is anchored by its name in the requested namespace, then followed by the intermediary
 * name of the newest match. Intermediary names are stable by construction, so a class that was
 * renamed or moved package stays one history instead of splitting into two. Versions with no
 * intermediary at all (Mojang's unobfuscated releases) still match on the name itself.
 *
 * Consecutive versions with the same answer collapse into one span, so a 500-version index answers
 * in a handful of entries. Named descriptors are not stored (only obf and intermediary are), so
 * signature changes are reported in intermediary terms.
 */
class HistoryService(private val db: Database) {

    /** One indexed version in canonical semver order. */
    private data class Ver(val rowId: Int, val versionId: String, val hasIntermediary: Boolean)

    /** What makes two versions answer alike. Data-class equality decides where a span breaks. */
    private data class SpanKey(
        val present: Boolean,
        val intermediary: String? = null,
        val yarn: String? = null,
        val mojmap: String? = null,
        val owner: String? = null,
        val members: List<HistoryMember> = emptyList(),
    )

    /** The method and field tables carry the same columns under different [Column] instances. */
    private class MemberCols(
        val table: IntIdTable,
        val kind: String,
        val classId: Column<EntityID<Int>>,
        val versionId: Column<EntityID<Int>>,
        val intermediaryName: Column<String?>,
        val intermediaryDesc: Column<String?>,
        val yarnName: Column<String?>,
        val mojmapName: Column<String?>,
    ) {
        fun named(namespace: String): Column<String?> = when (namespace) {
            "yarn" -> yarnName
            "intermediary" -> intermediaryName
            else -> mojmapName
        }
    }

    private val methodCols = MemberCols(
        MethodTable, "method", MethodTable.classId, MethodTable.versionId,
        MethodTable.intermediaryName, MethodTable.intermediaryDesc, MethodTable.yarnName, MethodTable.mojmapName,
    )

    private val fieldCols = MemberCols(
        FieldTable, "field", FieldTable.classId, FieldTable.versionId,
        FieldTable.intermediaryName, FieldTable.intermediaryDesc, FieldTable.yarnName, FieldTable.mojmapName,
    )

    /**
     * One entry per query, in the order given. Returns null when [from] or [to] names a version that
     * is not indexed; either bound may be the older one.
     */
    fun history(queries: List<String>, namespace: String, from: String?, to: String?): HistoryResponse? = transaction(db) {
        val all = orderedVersions()
        var lo = 0
        var hi = all.size - 1
        if (from != null) {
            lo = all.indexOfFirst { it.versionId == from }
            if (lo < 0) return@transaction null
        }
        if (to != null) {
            hi = all.indexOfFirst { it.versionId == to }
            if (hi < 0) return@transaction null
        }
        val versions = all.subList(minOf(lo, hi), maxOf(lo, hi) + 1)
        // Anchoring picks the newest match over the whole index, not just the requested range, so a
        // narrow range still follows the same class the caller meant.
        val order = all.withIndex().associate { (i, v) -> v.rowId to i }
        HistoryResponse(namespace, queries.map { entry(it, namespace, versions, all, order) })
    }

    private fun entry(
        query: String,
        namespace: String,
        versions: List<Ver>,
        all: List<Ver>,
        order: Map<Int, Int>,
    ): HistoryEntry {
        // `owner`, `owner:name`, or the mcsrc `owner:name:descriptor` key. Named descriptors are not
        // indexed, so a third segment is accepted but not matched on — overloads come back together.
        val parts = query.split(':')
        val owner = normalizeClassName(parts[0])
        val member = parts.getOrNull(1).orEmpty()
        val ownerRows = classRows(owner, namespace, all, order)
        if (ownerRows.isEmpty()) return HistoryEntry(query, "unknown", emptyList())
        if (member.isEmpty()) {
            return HistoryEntry(query, "class", collapse(versions) { v ->
                val row = ownerRows[v.rowId] ?: return@collapse SpanKey(present = false)
                SpanKey(
                    present = true,
                    intermediary = row[ClassTable.intermediaryName],
                    yarn = row[ClassTable.yarnName],
                    mojmap = row[ClassTable.mojmapName],
                )
            })
        }
        val ownerNameCol = classNameColumn(namespace)
        for (cols in listOf(methodCols, fieldCols)) {
            val rows = memberRows(cols, ownerRows, member, namespace, order)
            if (rows.isEmpty()) continue
            return HistoryEntry(query, cols.kind, collapse(versions) { v ->
                val ownerName = ownerRows[v.rowId]?.get(ownerNameCol)
                val found = rows[v.rowId].orEmpty().map {
                    HistoryMember(
                        intermediary = it[cols.intermediaryName],
                        yarn = it[cols.yarnName],
                        mojmap = it[cols.mojmapName],
                        intermediaryDescriptor = it[cols.intermediaryDesc],
                    )
                }.sortedWith(compareBy({ it.intermediary ?: "" }, { it.intermediaryDescriptor ?: "" }))
                SpanKey(present = found.isNotEmpty(), owner = ownerName, members = found)
            })
        }
        return HistoryEntry(query, "unknown", emptyList())
    }

    /** Every version's row for one class, keyed by version row id. */
    private fun classRows(name: String, namespace: String, all: List<Ver>, order: Map<Int, Int>): Map<Int, ResultRow> {
        val col = classNameColumn(namespace)
        val byName = ClassTable.selectAll().where { col eq name }.toList()
        // The newest match that carries an intermediary name, not simply the newest one: an
        // unobfuscated version has none, and anchoring there would turn the tracking off.
        val intermediary = byName.filter { it[ClassTable.intermediaryName] != null }
            .maxByOrNull { order[it[ClassTable.versionId].value] ?: -1 }
            ?.get(ClassTable.intermediaryName)
            ?: mappedNeighbourIntermediary(byName, col, name, all, order)
        val rows = if (intermediary == null) byName else {
            ClassTable.selectAll()
                .where { (col eq name) or (ClassTable.intermediaryName eq intermediary) }
                .toList()
        }
        val found = rows.associateBy { it[ClassTable.versionId].value }
        return found + movedWithoutIntermediary(name, col, intermediary, found)
    }

    /**
     * A name read off an unobfuscated version carries no intermediary, so on its own it can only
     * ever answer for the unobfuscated versions themselves. The same class in the newest mapped
     * version before them does carry one, and a package move keeps the simple name, so one probe
     * there re-anchors the whole history. Probing that one version costs a scan of its classes
     * (~3 ms) instead of the index-wide `LIKE` the mapped simple name would otherwise need — the
     * `simple_name` column is derived from the yarn name, so it cannot answer this.
     */
    private fun mappedNeighbourIntermediary(
        byName: List<ResultRow>,
        col: Column<String?>,
        name: String,
        all: List<Ver>,
        order: Map<Int, Int>,
    ): String? {
        val oldestRow = byName.minByOrNull { order[it[ClassTable.versionId].value] ?: Int.MAX_VALUE }
            ?: return null
        val oldestVersion = oldestRow[ClassTable.versionId].value
        val oldest = order[oldestVersion] ?: return null
        val neighbour = all.lastOrNull { it.hasIntermediary && (order[it.rowId] ?: -1) < oldest } ?: return null
        val simple = name.substringAfterLast('/')
        val candidate = ClassTable.selectAll()
            .where { (ClassTable.versionId eq neighbour.rowId) and (col like "%/$simple") }
            .filter { it[col]?.substringAfterLast('/') == simple }
            .singleOrNull() ?: return null
        // A move means the old name is gone. When the candidate's own name is still in use next to
        // the queried one, the two are unrelated classes that happen to share a simple name — as
        // `util/filefix/virtualfilesystem/Node` and `world/level/pathfinder/Node` do.
        val candidateName = candidate[col] ?: return null
        val separateClasses = ClassTable.selectAll()
            .where { (ClassTable.versionId eq oldestVersion) and (col eq candidateName) }
            .limit(1).any()
        return if (separateClasses) null else candidate[ClassTable.intermediaryName]
    }

    /**
     * Versions that carry no intermediary for this class — Mojang's unobfuscated releases, and rows
     * that exist in only one mapping set — cannot be followed through a rename by intermediary, so a
     * class that moved package there reads as removed. The one class of that version with the same
     * simple name is the same class, which is the rule `/source` resolves by. Candidates that do
     * carry a different intermediary name are a different class and stay unmatched.
     */
    private fun movedWithoutIntermediary(
        name: String,
        col: Column<String?>,
        intermediary: String?,
        found: Map<Int, ResultRow>,
    ): Map<Int, ResultRow> {
        val simple = name.substringAfterLast('/')
        val candidates = ClassTable.selectAll().where { ClassTable.simpleName eq simple }
            .filter { it[ClassTable.versionId].value !in found.keys }
            .filter { it[col]?.substringAfterLast('/') == simple }
            .filter { it[ClassTable.intermediaryName].let { im -> im == null || im == intermediary } }
            .groupBy { it[ClassTable.versionId].value }
            .filterValues { it.size == 1 }
            .mapValues { (_, rows) -> rows.single() }
        val separate = candidates.values.mapNotNull { it[col] }.distinct()
            .filter { coexistsWithUs(it, col, found) }
            .toSet()
        return candidates.filterValues { it[col] !in separate }
    }

    /**
     * True when [candidateName] is alive in a version where our class already answers to a different
     * name. A class carries one name per version, so two names alive at once are two classes —
     * `util/filefix/virtualfilesystem/Node` and `world/level/pathfinder/Node` share a simple name
     * and both exist in 26.1-snapshot-6, and linking them would invent a history.
     */
    private fun coexistsWithUs(candidateName: String, col: Column<String?>, found: Map<Int, ResultRow>): Boolean {
        val elsewhere = found.filterValues { it[col] != candidateName }.keys
        if (elsewhere.isEmpty()) return false
        return ClassTable.selectAll()
            .where { (col eq candidateName) and (ClassTable.versionId inList elsewhere.toList()) }
            .limit(1).any()
    }

    /** Every version's rows for one member of [ownerRows], keyed by version row id (one per overload). */
    private fun memberRows(
        cols: MemberCols,
        ownerRows: Map<Int, ResultRow>,
        member: String,
        namespace: String,
        order: Map<Int, Int>,
    ): Map<Int, List<ResultRow>> {
        val classIds = ownerRows.values.map { it[ClassTable.id].value }
        val col = cols.named(namespace)
        val byName = cols.table.selectAll()
            .where { (cols.classId inList classIds) and (col eq member) }
            .toList()
        val intermediary = byName.filter { it[cols.intermediaryName] != null }
            .maxByOrNull { order[it[cols.versionId].value] ?: -1 }
            ?.get(cols.intermediaryName)
        val rows = if (intermediary == null) byName else {
            cols.table.selectAll()
                .where { (cols.classId inList classIds) and ((col eq member) or (cols.intermediaryName eq intermediary)) }
                .toList()
        }
        return rows.groupBy { it[cols.versionId].value }
    }

    /** Merges each run of versions with an equal [key] into one span. */
    private fun collapse(versions: List<Ver>, key: (Ver) -> SpanKey): List<HistorySpan> {
        val spans = ArrayList<HistorySpan>()
        var current: SpanKey? = null
        var from = ""
        var to = ""
        var count = 0
        fun flush() {
            val k = current ?: return
            spans += HistorySpan(from, to, count, k.present, k.intermediary, k.yarn, k.mojmap, k.owner, k.members)
        }
        for (v in versions) {
            val k = key(v)
            if (k != current) {
                flush()
                current = k
                from = v.versionId
                count = 0
            }
            to = v.versionId
            count++
        }
        flush()
        return spans
    }

    private fun orderedVersions(): List<Ver> = VersionTable.selectAll()
        .orderBy(VersionTable.sortIndex to SortOrder.ASC_NULLS_LAST, VersionTable.versionId to SortOrder.ASC)
        .map { Ver(it[VersionTable.id].value, it[VersionTable.versionId], it[VersionTable.hasIntermediary]) }

    private fun classNameColumn(namespace: String): Column<String?> = when (namespace) {
        "yarn" -> ClassTable.yarnName
        "intermediary" -> ClassTable.intermediaryName
        else -> ClassTable.mojmapName
    }
}
