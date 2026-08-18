package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.tables.*
import xyz.nikitacartes.mappinglens.model.BatchTranslateItem
import xyz.nikitacartes.mappinglens.model.BatchTranslateResponse
import xyz.nikitacartes.mappinglens.model.TranslateInput
import xyz.nikitacartes.mappinglens.model.TranslateOutput
import xyz.nikitacartes.mappinglens.model.TranslateResponse
import xyz.nikitacartes.mappinglens.routes.normalizeClassName
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class TranslationService(private val db: Database, private val versionService: VersionService) {

    /**
     * Returns the first namespace from [namespaces] that is not available on the given version
     * (for example yarn/intermediary on Mojang's unobfuscated 26.x releases), together with the
     * resolved version id. Returns null if every namespace is available or the version is unknown.
     */
    fun firstUnavailableNamespace(version: String?, namespaces: List<String>): Pair<String, String>? = transaction(db) {
        val effectiveVersion = version ?: versionService.latestRelease() ?: return@transaction null
        val row = VersionTable.selectAll().where { VersionTable.versionId eq effectiveVersion }
            .singleOrNull() ?: return@transaction null
        val hasYarn = row[VersionTable.hasYarn]
        val hasMojmap = row[VersionTable.hasMojmap]
        val hasIntermediary = row[VersionTable.hasIntermediary]
        for (ns in namespaces) {
            val available = when (ns) {
                "yarn" -> hasYarn
                "mojmap" -> hasMojmap
                "intermediary" -> hasIntermediary
                "obfuscated", "obf" -> true
                else -> true
            }
            if (!available) return@transaction effectiveVersion to ns
        }
        null
    }

    fun translate(
        name: String,
        from: String,
        to: String,
        version: String?,
        type: String,
    ): TranslateResponse? = transaction(db) {
        val effectiveVersion = version ?: versionService.latestRelease() ?: return@transaction null
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq effectiveVersion }
            .singleOrNull() ?: return@transaction null
        val versionRowId = versionRow[VersionTable.id].value

        // Try class first if type=auto/class
        val tryTypes = when (type) {
            "class" -> listOf("class")
            "method" -> listOf("method")
            "field" -> listOf("field")
            else -> listOf("class", "method", "field")
        }

        for (t in tryTypes) {
            val r = lookup(versionRowId, name, from, to, t)
            if (r != null) return@transaction r.copy(version = effectiveVersion)
        }
        null
    }

    private fun lookup(versionRowId: Int, name: String, from: String, to: String, type: String): TranslateResponse? {
        return when (type) {
            "class" -> lookupClass(versionRowId, name, from, to)
            "method" -> lookupMember(MethodTable, versionRowId, name, from, to)
            "field" -> lookupMember(FieldTable, versionRowId, name, from, to)
            else -> null
        }
    }

    private fun lookupClass(versionRowId: Int, name: String, from: String, to: String): TranslateResponse? {
        val col = nameColumnClass(from)
        val row = ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (col eq name) }
            .firstOrNull() ?: return null
        val outName = readClassName(row, to)
        return TranslateResponse(
            input = TranslateInput(name, from),
            output = TranslateOutput(outName, to),
            intermediary = row[ClassTable.intermediaryName],
            obfuscated = row[ClassTable.obfName],
            version = "",
            type = "class",
        )
    }

    private fun lookupMember(
        cols: MemberTable,
        versionRowId: Int, name: String, from: String, to: String,
    ): TranslateResponse? {
        // Splits owner#member
        val splitIdx = name.lastIndexOfAny(charArrayOf('#', '.'))
        val (owner, member) = if (splitIdx > 0) name.substring(0, splitIdx) to name.substring(splitIdx + 1) else null to name

        val nameCol = nameColumnMember(cols, from)
        val ownerCol = nameColumnClass(from)
        val row = cols.innerJoin(ClassTable)
            .selectAll()
            .where {
                (cols.versionId eq versionRowId) and (nameCol eq member) and
                    (if (owner != null) (ownerCol eq owner) else Op.TRUE)
            }
            .limit(1).firstOrNull() ?: return null

        fun qualify(ownerName: String?, memberName: String?): String? =
            if (memberName != null && ownerName != null) "$ownerName#$memberName" else memberName

        return TranslateResponse(
            input = TranslateInput(name, from),
            output = TranslateOutput(qualify(readClassName(row, to), row[nameColumnMember(cols, to)]), to),
            intermediary = qualify(row[ClassTable.intermediaryName], row[cols.intermediaryName]),
            obfuscated = qualify(row[ClassTable.obfName], row[cols.obfName]),
            version = "",
            type = cols.kind,
        )
    }

    /**
     * Translates a batch of `/exists`-shaped keys from one namespace to another, descriptors
     * included. Returns null when [version] is not indexed.
     *
     * Named descriptors are not stored, so a descriptor is translated through the class table
     * instead: a descriptor is class names and primitives, and the class table knows every class of
     * the version in every namespace. The official descriptor is the pivot, because it is the one
     * column both the input and the output side can be matched against.
     *
     * Two queries serve the whole batch — one over the version's classes, one per member table over
     * the names asked for — so a mod's whole mixin surface is one request instead of one call per
     * member.
     */
    fun translateBatch(version: String, from: String, to: String, keys: List<String>): BatchTranslateResponse? =
        transaction(db) {
            val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq version }
                .singleOrNull()?.get(VersionTable.id)?.value ?: return@transaction null

            val classes = classIndex(versionRowId, from, to)
            val memberNames = keys.mapNotNull { it.split(':').getOrNull(1)?.takeIf(String::isNotEmpty) }.distinct()
            val members = if (memberNames.isEmpty()) emptyMap() else
                merge(
                    memberIndex(MethodTable, versionRowId, from, memberNames),
                    memberIndex(FieldTable, versionRowId, from, memberNames),
                )

            val results = keys.map { key -> translateKey(key, classes, members, to) }
            BatchTranslateResponse(version, from, to, results)
        }

    /** One class of the version, as the batch translation needs to see it. */
    private class ClassRec(val rowId: Int, val obf: String?, val target: String?, val intermediary: String?)

    /** The version's classes, by their `from` name, plus `official name -> to name` for descriptors. */
    private class ClassIndex(val byFromName: Map<String, ClassRec>, val targetByObf: Map<String, String>)

    private fun classIndex(versionRowId: Int, from: String, to: String): ClassIndex {
        val fromCol = nameColumnClass(from)
        val toCol = nameColumnClass(to)
        val byFromName = HashMap<String, ClassRec>()
        val targetByObf = HashMap<String, String>()
        ClassTable.selectAll().where { ClassTable.versionId eq versionRowId }.forEach { row ->
            val obf = row[ClassTable.obfName]
            val target = row[toCol]
            row[fromCol]?.let {
                byFromName[it] = ClassRec(row[ClassTable.id].value, obf, target, row[ClassTable.intermediaryName])
            }
            if (obf != null && target != null) targetByObf[obf] = target
        }
        return ClassIndex(byFromName, targetByObf)
    }

    /** One member row, carrying the table it came from so the reader knows which columns to ask for. */
    private class MemberHit(val cols: MemberTable, val row: ResultRow)

    /** Rows of one member table for the requested names, keyed by (class row id, `from` name). */
    private fun memberIndex(
        cols: MemberTable,
        versionRowId: Int,
        from: String,
        names: List<String>,
    ): Map<Pair<Int, String>, List<MemberHit>> {
        val nameCol = nameColumnMember(cols, from)
        // SQLite binds each `IN` element as its own parameter and the batch cap is well past the
        // limit of older builds, so ask in chunks rather than in one statement.
        return names.chunked(500)
            .flatMap { chunk ->
                cols.selectAll()
                    .where { (cols.versionId eq versionRowId) and (nameCol inList chunk) }
                    .toList()
            }
            .groupBy({ it[cols.classId].value to it[nameCol].orEmpty() }, { MemberHit(cols, it) })
    }

    private fun merge(
        methods: Map<Pair<Int, String>, List<MemberHit>>,
        fields: Map<Pair<Int, String>, List<MemberHit>>,
    ): Map<Pair<Int, String>, List<MemberHit>> {
        val merged = HashMap(methods)
        fields.forEach { (key, hits) -> merged.merge(key, hits) { a, b -> a + b } }
        return merged
    }

    private fun translateKey(
        key: String,
        classes: ClassIndex,
        members: Map<Pair<Int, String>, List<MemberHit>>,
        to: String,
    ): BatchTranslateItem {
        val parts = key.split(':')
        val ownerRec = classes.byFromName[normalizeClassName(parts[0])] ?: return BatchTranslateItem(key)
        if (parts.size == 1) return BatchTranslateItem(key, ownerRec.target, "class", ownerRec.intermediary)

        val hits = members[ownerRec.rowId to parts[1]].orEmpty()
        // The descriptor given names its classes in `from`; the stored one names them officially,
        // so the match runs on the official spelling of what the caller asked for.
        val wanted = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.let { Descriptors.mapTypes(it) { c -> classes.byFromName[c]?.obf } }
        val hit = when {
            wanted != null -> hits.firstOrNull { it.row[it.cols.obfDesc] == wanted }
            hits.size == 1 -> hits.single()
            // Without a descriptor an overloaded name has no single answer, and picking one would
            // hand back a key for a member the caller did not name.
            else -> null
        } ?: return BatchTranslateItem(key)

        val targetOwner = ownerRec.target ?: return BatchTranslateItem(key)
        val targetName = hit.row[nameColumnMember(hit.cols, to)] ?: return BatchTranslateItem(key)
        val targetDesc = hit.row[hit.cols.obfDesc]?.let { Descriptors.mapTypes(it) { c -> classes.targetByObf[c] } }
        val translated = if (targetDesc != null) "$targetOwner:$targetName:$targetDesc" else "$targetOwner:$targetName"
        return BatchTranslateItem(key, translated, hit.cols.kind, hit.row[hit.cols.intermediaryName])
    }

    private fun readClassName(row: ResultRow, namespace: String): String? = when (namespace) {
        "yarn" -> row[ClassTable.yarnName]
        "mojmap" -> row[ClassTable.mojmapName]
        "intermediary" -> row[ClassTable.intermediaryName]
        "obfuscated", "obf" -> row[ClassTable.obfName]
        else -> null
    }

    private fun nameColumnClass(ns: String): Column<String?> = when (ns) {
        "yarn" -> ClassTable.yarnName
        "mojmap" -> ClassTable.mojmapName
        "intermediary" -> ClassTable.intermediaryName
        "obfuscated", "obf" -> ClassTable.obfName
        else -> ClassTable.yarnName
    }

    private fun nameColumnMember(cols: MemberTable, ns: String): Column<String?> = when (ns) {
        "mojmap" -> cols.mojmapName
        "intermediary" -> cols.intermediaryName
        "obfuscated", "obf" -> cols.obfName
        else -> cols.yarnName
    }
}
