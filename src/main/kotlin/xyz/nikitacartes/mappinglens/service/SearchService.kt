package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.SearchIndex
import xyz.nikitacartes.mappinglens.db.tables.*
import xyz.nikitacartes.mappinglens.ingestion.Names
import xyz.nikitacartes.mappinglens.model.ClassRef
import xyz.nikitacartes.mappinglens.model.SearchResponse
import xyz.nikitacartes.mappinglens.model.SearchResultEntry
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.sql.ResultSet

/**
 * Name search. The match runs against [SearchIndex], one FTS5 table for each version in a file of
 * its own; the names of the rows it returns are read back from the main index here.
 */
class SearchService(
    private val db: Database,
    private val versionService: VersionService,
    private val databasePath: String,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(SearchService::class.java)

    fun search(
        query: String,
        version: String?,
        type: String,
        namespace: String,
        limit: Int,
        offset: Int,
        exact: Boolean,
        includeSynthetic: Boolean = false,
    ): SearchResponse = transaction(db) {
        val effectiveVersion = version ?: versionService.latestRelease()
            ?: return@transaction SearchResponse(query, "", 0, emptyList())
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq effectiveVersion }
            .singleOrNull() ?: return@transaction SearchResponse(query, effectiveVersion, 0, emptyList())
        val table = SearchIndex.table(versionRow[VersionTable.id].value)

        // Owner#member, owner.member or short owner/member splitting.
        val ownerMember = splitOwnerMemberQuery(query.trim())
        val ownerPart = ownerMember?.first
        val memberPart = ownerMember?.second

        val versionRowId = versionRow[VersionTable.id].value
        val results = SearchIndex.openReadOnly(databasePath).use { search ->
            if (ownerPart != null && memberPart != null) {
                searchOwnerMember(search, table, versionRowId, ownerPart, memberPart, type, namespace, limit, offset, exact, includeSynthetic)
            } else {
                searchSingle(search, table, versionRowId, query.trim(), type, namespace, limit, offset, exact, includeSynthetic)
            }
        }
        SearchResponse(query, effectiveVersion, results.size, results)
    }

    private fun splitOwnerMemberQuery(query: String): Pair<String, String>? {
        val explicitIdx = query.lastIndexOfAny(charArrayOf('#', '.'))
        if (explicitIdx > 0 && explicitIdx < query.lastIndex) {
            return query.substring(0, explicitIdx).trim() to query.substring(explicitIdx + 1).trim()
        }

        val slashIdx = query.lastIndexOf('/')
        if (slashIdx <= 0 || slashIdx >= query.lastIndex) return null
        val owner = query.substring(0, slashIdx).trim()
        val member = query.substring(slashIdx + 1).trim()
        val ownerSimple = owner.substringAfterLast('/')
        val looksLikeClassPath = ownerSimple.firstOrNull()?.isLowerCase() == true && member.firstOrNull()?.isUpperCase() == true
        return if (looksLikeClassPath) null else owner to member
    }

    private fun searchSingle(
        search: Connection, table: String, versionRowId: Int, q: String, type: String, namespace: String,
        limit: Int, offset: Int, exact: Boolean, includeSynthetic: Boolean,
    ): List<SearchResultEntry> {
        val kind = SearchIndex.kindCode(type)
        val matchExpr = buildFtsMatch(q, namespace, exact)
        // The kind sits in the low two bits of the rowid in ranking order, so `rowid & 3` both
        // filters by element type and groups classes before methods before fields.
        val sql = """
            SELECT rowid, bm25($table) AS rank
            FROM $table
            WHERE $table MATCH ?
              ${if (kind != null) "AND (rowid & 3) = ?" else ""}
            ORDER BY rowid & 3 ASC, rank ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        // Lambdas are dropped after the match, because the FTS table is contentless and has no
        // column to filter them on. Over-fetching keeps a full page for the common query, the same
        // way [searchOwnerMember] pays for its own post-filter.
        val params = buildList<Any> {
            add(matchExpr)
            if (kind != null) add(kind)
            add(if (includeSynthetic) limit else limit * 4); add(offset)
        }
        return resolve(versionRowId, hits(search, sql, params))
            .map { it.entry }
            .filter { includeSynthetic || !it.synthetic }
            .take(limit)
    }

    private fun searchOwnerMember(
        search: Connection, table: String, versionRowId: Int, ownerPart: String, memberPart: String,
        type: String, namespace: String, limit: Int, offset: Int, exact: Boolean, includeSynthetic: Boolean,
    ): List<SearchResultEntry> {
        // First, find candidate class ids matching ownerPart.
        val ownerMatch = buildFtsMatch(ownerPart, namespace, exact)
        val ownerSql = """
            SELECT rowid, bm25($table) AS rank
            FROM $table
            WHERE $table MATCH ?
              AND (rowid & 3) = ?
            ORDER BY rank ASC
            LIMIT 50
        """.trimIndent()
        val classIds = hits(search, ownerSql, listOf(ownerMatch, SearchIndex.kindCode("class")!!))
            .map { SearchIndex.elementId(it.rowid) }
        if (classIds.isEmpty()) return emptyList()
        val typeForMember = if (type == "class") "method" else type
        val kind = SearchIndex.kindCode(typeForMember)
        val memberMatch = buildFtsMatch(memberPart, namespace, exact)

        // The FTS index does not store the owner, so members are matched on their own name and then
        // kept only where the class they belong to is one of the candidates above.
        val classIdSet = classIds.toSet()
        val sql = """
            SELECT rowid, bm25($table) AS rank
            FROM $table
            WHERE $table MATCH ?
              ${if (kind != null) "AND (rowid & 3) = ?" else ""}
            ORDER BY CASE rowid & 3 WHEN 1 THEN 0 WHEN 2 THEN 1 ELSE 2 END ASC, rank ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val memberParams = buildList<Any> {
            add(memberMatch)
            if (kind != null) add(kind)
            add(limit * 4); add(offset)
        }
        return resolve(versionRowId, hits(search, sql, memberParams))
            .filter { it.ownerClassId in classIdSet }
            .map { it.entry }
            .filter { includeSynthetic || !it.synthetic }
            .take(limit)
    }

    private fun buildFtsMatch(q: String, namespace: String, exact: Boolean): String {
        val cleaned = q.replace("\"", "").replace("'", "").trim()
        val token = if (exact) "\"$cleaned\"" else "${cleaned.replace("/", " ")}*"
        // Restrict to a column when namespace is specific
        val column = when (namespace) {
            "yarn" -> "yarn_name"
            "mojmap" -> "mojmap_name"
            "intermediary" -> "intermediary_name"
            else -> null
        }
        return if (column != null) "$column:$token" else token
    }

    /**
     * bm25 returns lower=better (negative-ish). Convert to a [0..1) score where higher is better,
     * which is the order the rows already come back in. Dividing 1 by the same magnitude inverted
     * the field against that order, so sorting the results by `score` descending picked the worst
     * match of the page.
     */
    private fun scoreFromBm25(rank: Double): Double = Math.abs(rank) / (1.0 + Math.abs(rank))

    /** One match: the packed row identity the FTS index returns, and its score. */
    private class Hit(val rowid: Long, val score: Double)

    /**
     * A hydrated hit, the class it belongs to (which owner#member search filters on) and the
     * official descriptor the named ones are derived from.
     */
    private class Resolved(
        val entry: SearchResultEntry,
        val ownerClassId: Int?,
        val obfDescriptor: String? = null,
    )

    private fun hits(search: Connection, sql: String, params: List<Any>): List<Hit> {
        val rows = mutableListOf<Hit>()
        execRaw(search, sql, params) { rs ->
            // FTS5 bm25 returns negative-ish lower=better; convert to positive score
            while (rs.next()) rows += Hit(rs.getLong("rowid"), scoreFromBm25(rs.getDouble("rank")))
        }
        return rows
    }

    /**
     * Reads the names of every hit in one query per element table plus one for the owner classes.
     * Row by row this cost two statements for each result and about 250ms of the 1214ms that
     * `/search?q=get` took, for lookups SQLite itself answers in a millisecond.
     */
    private fun resolve(versionRowId: Int, hits: List<Hit>): List<Resolved> {
        if (hits.isEmpty()) return emptyList()
        val idsByKind = hits.groupBy({ SearchIndex.elementType(it.rowid) }, { SearchIndex.elementId(it.rowid) })
        val methods = rowsById(MethodTable, idsByKind["method"].orEmpty())
        val fields = rowsById(FieldTable, idsByKind["field"].orEmpty())
        val classes = rowsById(
            ClassTable,
            methods.values.map { it[MethodTable.classId].value } +
                fields.values.map { it[FieldTable.classId].value } +
                idsByKind["class"].orEmpty(),
        )
        return withNamedDescriptors(versionRowId, hits.mapNotNull { hit ->
            val elementId = SearchIndex.elementId(hit.rowid)
            when (SearchIndex.elementType(hit.rowid)) {
                "class" -> classes[elementId]?.let { Resolved(classEntry(it, hit.score), elementId) }
                "method" -> methods[elementId]?.let { memberEntry(MethodTable, it, classes, hit.score) }
                "field" -> fields[elementId]?.let { memberEntry(FieldTable, it, classes, hit.score) }
                else -> null
            }
        })
    }

    /**
     * Fills in the descriptors of the named namespaces, which the index does not store: the
     * official descriptor is rewritten through the classes of this version, in one query for the
     * whole page of results. Without them a key from `/search` cannot be pasted into `/exists`,
     * which wants the descriptor of the namespace it was asked about.
     *
     * A type the version does not name stays as it came, the way [Descriptors.mapTypes] leaves a
     * JDK class alone. A namespace the entry itself has no name in gets no descriptor either.
     */
    private fun withNamedDescriptors(versionRowId: Int, resolved: List<Resolved>): List<Resolved> {
        val types = resolved.mapNotNull { it.obfDescriptor }.flatMapTo(HashSet()) { classTypesOf(it) }
        // An all-primitive descriptor needs no renaming, but it still needs to be reported.
        val named = types.chunked(500)
            .flatMap { chunk ->
                ClassTable.selectAll()
                    .where { (ClassTable.versionId eq versionRowId) and (ClassTable.obfName inList chunk) }
                    .toList()
            }
            .associateBy { it[ClassTable.obfName].orEmpty() }
        return resolved.map { r ->
            val desc = r.obfDescriptor ?: return@map r
            fun spell(column: Column<String?>) = Descriptors.mapTypes(desc) { named[it]?.get(column) }
            Resolved(
                r.entry.copy(
                    yarnDescriptor = if (r.entry.yarn != null) spell(ClassTable.yarnName) else null,
                    mojmapDescriptor = if (r.entry.mojmap != null) spell(ClassTable.mojmapName) else null,
                ),
                r.ownerClassId,
            )
        }
    }

    /** Every class type of a descriptor, collected by walking it with a rename that renames nothing. */
    private fun classTypesOf(descriptor: String): List<String> =
        ArrayList<String>().also { out -> Descriptors.mapTypes(descriptor) { out.add(it); null } }

    /** Reads rows by id, in batches, because SQLite binds a limited number of parameters. */
    private fun rowsById(table: IntIdTable, ids: Collection<Int>): Map<Int, ResultRow> =
        ids.distinct().chunked(500)
            .flatMap { chunk -> table.selectAll().where { table.id inList chunk }.toList() }
            .associateBy { it[table.id].value }

    private fun classEntry(r: ResultRow, score: Double) = SearchResultEntry(
        type = "class",
        intermediary = r[ClassTable.intermediaryName],
        yarn = r[ClassTable.yarnName],
        mojmap = r[ClassTable.mojmapName],
        obfuscated = r[ClassTable.obfName],
        score = score,
    )

    private fun memberEntry(
        cols: MemberTable, r: ResultRow, classes: Map<Int, ResultRow>, score: Double,
    ): Resolved {
        val ownerId = r[cols.classId].value
        val classRow = classes[ownerId]
        return Resolved(
            SearchResultEntry(
                type = cols.kind,
                intermediary = combine(classRow?.get(ClassTable.intermediaryName), r[cols.intermediaryName]),
                yarn = combine(classRow?.get(ClassTable.yarnName), r[cols.yarnName]),
                mojmap = combine(classRow?.get(ClassTable.mojmapName), r[cols.mojmapName]),
                obfuscated = combine(classRow?.get(ClassTable.obfName), r[cols.obfName]),
                owner = classRow?.let {
                    ClassRef(
                        intermediary = it[ClassTable.intermediaryName],
                        yarn = it[ClassTable.yarnName],
                        mojmap = it[ClassTable.mojmapName],
                        obfuscated = it[ClassTable.obfName],
                    )
                },
                intermediaryDescriptor = r[cols.intermediaryDesc] ?: r[cols.obfDesc],
                score = score,
                synthetic = Names.isLambda(r[cols.yarnName]) || Names.isLambda(r[cols.mojmapName]),
            ),
            ownerId,
            r[cols.obfDesc],
        )
    }

    private fun combine(owner: String?, member: String?): String? {
        if (member == null) return null
        if (owner == null) return member
        return "$owner#$member"
    }

    /** Runs a parameterized FTS query; parameters are bound, never string-substituted. */
    private fun execRaw(conn: Connection, sql: String, params: List<Any>, action: (ResultSet) -> Unit) {
        try {
            conn.prepareStatement(sql).use { ps ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is Int -> ps.setInt(i + 1, p)
                        is Long -> ps.setLong(i + 1, p)
                        else -> ps.setString(i + 1, p.toString())
                    }
                }
                ps.executeQuery().use { rs -> action(rs) }
            }
        } catch (e: Exception) {
            log.warn("FTS5 query failed: {} | sql={}", e.message, sql)
        }
    }
}
