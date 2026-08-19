package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.FieldTable
import xyz.nikitacartes.mappinglens.db.tables.MemberTable
import xyz.nikitacartes.mappinglens.db.tables.MethodTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.HistoryEntry
import xyz.nikitacartes.mappinglens.model.HistoryMember
import xyz.nikitacartes.mappinglens.model.HistoryResponse
import xyz.nikitacartes.mappinglens.model.HistorySpan
import xyz.nikitacartes.mappinglens.routes.normalizeClassName
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.objectweb.asm.ClassReader
import java.util.zip.ZipFile

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
class HistoryService(private val db: Database, private val config: AppConfig? = null) {

    /** One indexed version in canonical semver order. */
    private data class Ver(
        val rowId: Int,
        val versionId: String,
        val hasIntermediary: Boolean,
        val releaseType: String,
        val isVariant: Boolean,
    )

    /** What makes two versions answer alike. Data-class equality decides where a span breaks. */
    private data class SpanKey(
        val present: Boolean,
        val intermediary: String? = null,
        val yarn: String? = null,
        val mojmap: String? = null,
        val owner: String? = null,
        val members: List<HistoryMember> = emptyList(),
        val reason: String? = null,
        val declaredIn: String? = null,
    )

    private fun MemberTable.named(namespace: String): Column<String?> = when (namespace) {
        "yarn" -> yarnName
        "intermediary" -> intermediaryName
        else -> mojmapName
    }

    /**
     * One entry per query, in the order given. Returns null when [from] or [to] names a version that
     * is not indexed; either bound may be the older one.
     *
     * [releasesOnly] and [includeVariants] narrow the versions that reach the spans, not the ones
     * the bounds may name: a caller can bound the walk by a snapshot and still read releases. The
     * anchoring below reads the whole index either way, so a narrowed walk follows the same class.
     */
    fun history(
        queries: List<String>,
        namespace: String,
        from: String?,
        to: String?,
        releasesOnly: Boolean = false,
        includeVariants: Boolean = false,
    ): HistoryResponse? = transaction(db) {
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
            .filter { (includeVariants || !it.isVariant) && (!releasesOnly || it.releaseType == "release") }
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
        for (cols in listOf(MethodTable, FieldTable)) {
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
        return inherited(query, namespace, member, ownerRows, versions, all, order)
            ?: HistoryEntry(query, "unknown", collapse(versions) { v ->
                // Neither the owner nor a supertype declares it. Saying so over the range is the
                // answer; the empty history this used to return read like the query was malformed.
                SpanKey(present = false, owner = ownerRows[v.rowId]?.get(ownerNameCol))
            })
    }

    /**
     * The history of a member the owner does not declare but inherits, or null when no supertype
     * declares it either. `present` stays false, the way `/exists` keeps `exists` false, and
     * `reason` carries the difference a second call to `/exists` used to have to explain.
     *
     * The supertype is found once, on the newest version the owner exists in, and is then followed
     * through the index like any other class, so a supertype renamed later in the range still
     * answers and a span breaks where its declaration changes.
     */
    private fun inherited(
        query: String,
        namespace: String,
        member: String,
        ownerRows: Map<Int, ResultRow>,
        versions: List<Ver>,
        all: List<Ver>,
        order: Map<Int, Int>,
    ): HistoryEntry? {
        val declaring = declaringSupertype(namespace, member, ownerRows, versions) ?: return null
        val superRows = classRows(declaring, namespace, all, order)
        val ownerNameCol = classNameColumn(namespace)
        for (cols in listOf(MethodTable, FieldTable)) {
            val rows = memberRows(cols, superRows, member, namespace, order)
            if (rows.isEmpty()) continue
            return HistoryEntry(query, cols.kind, collapse(versions) { v ->
                val found = rows[v.rowId].orEmpty().map {
                    HistoryMember(
                        intermediary = it[cols.intermediaryName],
                        yarn = it[cols.yarnName],
                        mojmap = it[cols.mojmapName],
                        intermediaryDescriptor = it[cols.intermediaryDesc],
                    )
                }.sortedWith(compareBy({ it.intermediary ?: "" }, { it.intermediaryDescriptor ?: "" }))
                SpanKey(
                    present = false,
                    owner = ownerRows[v.rowId]?.get(ownerNameCol),
                    members = found,
                    reason = if (found.isEmpty()) null else "inherited",
                    declaredIn = if (found.isEmpty()) null else superRows[v.rowId]?.get(ownerNameCol),
                )
            })
        }
        return null
    }

    /**
     * The nearest supertype of the owner that declares [member], read from a named jar one class
     * header at a time. Only the classes on the chain are unzipped, so this costs a jar open and a
     * handful of entries rather than the whole-jar scan `/hierarchy` pays for.
     *
     * The newest version of the range that has both the class and a jar is the one asked, not the
     * newest of the index: 13 of the 2018-2019 snapshots carry no `sort_index`, which sorts them
     * after everything else and would anchor the whole answer on 19w14b.
     */
    private fun declaringSupertype(
        namespace: String,
        member: String,
        ownerRows: Map<Int, ResultRow>,
        versions: List<Ver>,
    ): String? {
        val config = this.config ?: return null
        val col = classNameColumn(namespace)
        for (version in versions.asReversed()) {
            val start = ownerRows[version.rowId]?.get(col) ?: continue
            val jar = config.sources.remappedJar(version.versionId, namespace) ?: continue
            val chain = ZipFile(jar.toFile()).use { zip -> supertypeChain(zip, start) }
            val classIds = ClassTable.selectAll()
                .where { (ClassTable.versionId eq version.rowId) and (col inList chain) }
                .associate { (it[col] ?: "") to it[ClassTable.id].value }
            return chain.firstOrNull { candidate ->
                val classId = classIds[candidate] ?: return@firstOrNull false
                listOf(MethodTable, FieldTable).any { cols ->
                    cols.selectAll()
                        .where { (cols.classId eq classId) and (cols.named(namespace) eq member) }
                        .limit(1).any()
                }
            }
        }
        return null
    }

    /** Every supertype of [start], nearest first, breadth-first so a direct parent beats a distant one. */
    private fun supertypeChain(zip: ZipFile, start: String): List<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(parentsOf(zip, start))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == "java/lang/Object" || !seen.add(next)) continue
            queue += parentsOf(zip, next)
        }
        return seen.toList()
    }

    private fun parentsOf(zip: ZipFile, name: String): List<String> {
        val entry = zip.getEntry("$name.class") ?: return emptyList()
        val reader = zip.getInputStream(entry).use { ClassReader(it.readBytes()) }
        return listOfNotNull(reader.superName) + reader.interfaces
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
        cols: MemberTable,
        ownerRows: Map<Int, ResultRow>,
        member: String,
        namespace: String,
        order: Map<Int, Int>,
    ): Map<Int, List<ResultRow>> {
        val classIds = ownerRows.values.map { it[ClassTable.id].value }
        val col = cols.named(namespace)
        val byName = cols.selectAll()
            .where { (cols.classId inList classIds) and (col eq member) }
            .toList()
        val intermediary = byName.filter { it[cols.intermediaryName] != null }
            .maxByOrNull { order[it[cols.versionId].value] ?: -1 }
            ?.get(cols.intermediaryName)
        val rows = if (intermediary == null) byName else {
            cols.selectAll()
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
            spans += HistorySpan(
                from, to, count, k.present, k.intermediary, k.yarn, k.mojmap, k.owner, k.members,
                k.reason, k.declaredIn,
            )
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
        .map {
            Ver(
                rowId = it[VersionTable.id].value,
                versionId = it[VersionTable.versionId],
                hasIntermediary = it[VersionTable.hasIntermediary],
                releaseType = it[VersionTable.releaseType],
                isVariant = it[VersionTable.variantOf] != null,
            )
        }

    private fun classNameColumn(namespace: String): Column<String?> = when (namespace) {
        "yarn" -> ClassTable.yarnName
        "intermediary" -> ClassTable.intermediaryName
        else -> ClassTable.mojmapName
    }
}
