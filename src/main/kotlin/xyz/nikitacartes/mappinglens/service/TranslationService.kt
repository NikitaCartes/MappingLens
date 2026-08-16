package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.tables.*
import xyz.nikitacartes.mappinglens.model.TranslateInput
import xyz.nikitacartes.mappinglens.model.TranslateOutput
import xyz.nikitacartes.mappinglens.model.TranslateResponse
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
