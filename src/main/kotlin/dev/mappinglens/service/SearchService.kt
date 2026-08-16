package dev.mappinglens.service

import dev.mappinglens.db.tables.*
import dev.mappinglens.model.ClassRef
import dev.mappinglens.model.SearchResponse
import dev.mappinglens.model.SearchResultEntry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet

class SearchService(private val db: Database, private val versionService: VersionService) {

    private val log = org.slf4j.LoggerFactory.getLogger(SearchService::class.java)

    fun search(
        query: String,
        version: String?,
        type: String,
        namespace: String,
        limit: Int,
        offset: Int,
        exact: Boolean,
    ): SearchResponse = transaction(db) {
        val effectiveVersion = version ?: versionService.latestRelease()
            ?: return@transaction SearchResponse(query, "", 0, emptyList())
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq effectiveVersion }
            .singleOrNull() ?: return@transaction SearchResponse(query, effectiveVersion, 0, emptyList())
        val scope = VersionScope(
            rowId = versionRow[VersionTable.id].value,
            ftsLo = versionRow[VersionTable.ftsMinRowid],
            ftsHi = versionRow[VersionTable.ftsMaxRowid],
        )

        // Owner#member, owner.member or short owner/member splitting.
        val ownerMember = splitOwnerMemberQuery(query.trim())
        val ownerPart = ownerMember?.first
        val memberPart = ownerMember?.second

        val results = if (ownerPart != null && memberPart != null) {
            searchOwnerMember(scope, ownerPart, memberPart, type, namespace, limit, offset, exact)
        } else {
            searchSingle(scope, query.trim(), type, namespace, limit, offset, exact)
        }
        SearchResponse(query, effectiveVersion, results.size, results)
    }

    /**
     * One version's identity for an FTS query. [ftsLo]/[ftsHi] are its inclusive search_index rowid
     * range; when present we constrain `rowid BETWEEN ftsLo AND ftsHi` so FTS5 ranks only this
     * version's rows. Falls back to the version_id post-filter when the range is absent (an index
     * built before this column existed) — same results, just the old slow scan.
     */
    private class VersionScope(val rowId: Int, val ftsLo: Long?, val ftsHi: Long?) {
        /** Returns the SQL fragment scoping the match to this version plus its bound parameters. */
        fun clause(): Pair<String, List<Any>> =
            if (ftsLo != null && ftsHi != null) "rowid BETWEEN ? AND ?" to listOf(ftsLo, ftsHi)
            else "version_id = ?" to listOf(rowId)
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
        scope: VersionScope, q: String, type: String, namespace: String,
        limit: Int, offset: Int, exact: Boolean,
    ): List<SearchResultEntry> {
        val typeFilter = typeFilterClause(type)
        val matchExpr = buildFtsMatch(q, namespace, exact)
        val (versionClause, versionParams) = scope.clause()
        val sql = """
            SELECT element_type, element_id, version_id, bm25(search_index) AS rank
            FROM search_index
            WHERE search_index MATCH ?
              AND $versionClause
              $typeFilter
                        ORDER BY CASE element_type
                                             WHEN 'class' THEN 0
                                             WHEN 'method' THEN 1
                                             WHEN 'field' THEN 2
                                             ELSE 3
                                         END ASC,
                                         rank ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val params = buildList<Any> {
            add(matchExpr); addAll(versionParams)
            if (typeFilter.isNotEmpty()) add(type)
            add(limit); add(offset)
        }
        val rows = mutableListOf<Triple<String, Int, Double>>()
        execRaw(sql, params) { rs ->
            while (rs.next()) {
                val rank = rs.getDouble("rank")
                // FTS5 bm25 returns negative-ish lower=better; convert to positive score
                val score = scoreFromBm25(rank)
                rows += Triple(rs.getString("element_type"), rs.getInt("element_id"), score)
            }
        }
        return rows.mapNotNull { (etype, eid, score) -> hydrate(etype, eid, score) }
    }

    private fun searchOwnerMember(
        scope: VersionScope, ownerPart: String, memberPart: String,
        type: String, namespace: String, limit: Int, offset: Int, exact: Boolean,
    ): List<SearchResultEntry> {
        val (versionClause, versionParams) = scope.clause()
        // First, find candidate class ids matching ownerPart.
        val ownerMatch = buildFtsMatch(ownerPart, namespace, exact)
        val classIds = mutableListOf<Int>()
        val ownerSql = """
            SELECT element_id FROM search_index
            WHERE search_index MATCH ?
              AND $versionClause
              AND element_type = 'class'
            ORDER BY bm25(search_index) ASC
            LIMIT 50
        """.trimIndent()
        execRaw(ownerSql, buildList<Any> { add(ownerMatch); addAll(versionParams) }) { rs ->
            while (rs.next()) classIds += rs.getInt("element_id")
        }
        if (classIds.isEmpty()) return emptyList()
        val typeForMember = if (type == "class") "method" else type
        val typeFilter = typeFilterClause(typeForMember)
        val memberMatch = buildFtsMatch(memberPart, namespace, exact)

        // Look up methods/fields filtering by class_id IN list. The FTS index stores class_id NOT directly,
        // so we resolve element_id -> class_id via the relational tables.
        val classIdSet = classIds.toSet()
        val results = mutableListOf<SearchResultEntry>()
        val sql = """
            SELECT element_type, element_id, bm25(search_index) AS rank
            FROM search_index
            WHERE search_index MATCH ?
              AND $versionClause
              $typeFilter
                        ORDER BY CASE element_type
                                             WHEN 'method' THEN 0
                                             WHEN 'field' THEN 1
                                             ELSE 2
                                         END ASC,
                                         rank ASC
            LIMIT ? OFFSET ?
        """.trimIndent()
        val memberParams = buildList<Any> {
            add(memberMatch); addAll(versionParams)
            if (typeFilter.isNotEmpty()) add(typeForMember)
            add(limit * 4); add(offset)
        }
        execRaw(sql, memberParams) { rs ->
            while (rs.next()) {
                val etype = rs.getString("element_type")
                val eid = rs.getInt("element_id")
                val rank = rs.getDouble("rank")
                val classId = lookupOwnerClassId(etype, eid) ?: continue
                if (classId !in classIdSet) continue
                val entry = hydrate(etype, eid, scoreFromBm25(rank)) ?: continue
                results += entry
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun lookupOwnerClassId(elementType: String, elementId: Int): Int? {
        return when (elementType) {
            "method" -> MethodTable.selectAll().where { MethodTable.id eq elementId }.singleOrNull()?.get(MethodTable.classId)?.value
            "field" -> FieldTable.selectAll().where { FieldTable.id eq elementId }.singleOrNull()?.get(FieldTable.classId)?.value
            "class" -> elementId
            else -> null
        }
    }

    private fun typeFilterClause(type: String): String = when (type) {
        "class", "method", "field" -> "AND element_type = ?"
        else -> ""
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

    /** bm25 returns lower=better (negative-ish). Convert to a [0..1+] score. */
    private fun scoreFromBm25(rank: Double): Double = 1.0 / (1.0 + Math.abs(rank))

    private fun hydrate(elementType: String, elementId: Int, score: Double): SearchResultEntry? {
        if (elementType == "class") {
            val r = ClassTable.selectAll().where { ClassTable.id eq elementId }.singleOrNull() ?: return null
            return SearchResultEntry(
                type = "class",
                intermediary = r[ClassTable.intermediaryName],
                yarn = r[ClassTable.yarnName],
                mojmap = r[ClassTable.mojmapName],
                obfuscated = r[ClassTable.obfName],
                score = score,
            )
        }
        val cols = when (elementType) {
            "method" -> MethodTable
            "field" -> FieldTable
            else -> return null
        }
        val r = cols.selectAll().where { cols.id eq elementId }.singleOrNull() ?: return null
        val classRow = ClassTable.selectAll().where { ClassTable.id eq r[cols.classId] }.singleOrNull()
        return SearchResultEntry(
            type = elementType,
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
            descriptor = r[cols.intermediaryDesc] ?: r[cols.obfDesc],
            score = score,
        )
    }

    private fun combine(owner: String?, member: String?): String? {
        if (member == null) return null
        if (owner == null) return member
        return "$owner#$member"
    }

    /** Runs a parameterized FTS query; parameters are bound, never string-substituted. */
    private fun execRaw(sql: String, params: List<Any>, action: (ResultSet) -> Unit) {
        val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
            .connection as java.sql.Connection
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
