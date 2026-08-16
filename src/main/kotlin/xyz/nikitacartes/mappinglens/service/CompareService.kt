package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.FieldTable
import xyz.nikitacartes.mappinglens.db.tables.MethodTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.CompareMember
import xyz.nikitacartes.mappinglens.model.CompareResponse
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Builds the Yarn<->Mojmap member-correspondence table for one class. The obf-keyed join is already
 * materialised in the index (every method/field row carries obf/intermediary/yarn/mojmap), so this
 * is a projection of one class's rows — no source is read. See REWRITE_PLAN.md section 8.
 */
class CompareService(private val db: Database, private val versionService: VersionService) {

    sealed interface Result {
        data class Ok(val response: CompareResponse) : Result
        object VersionNotFound : Result
        data class NamespaceUnavailable(val version: String, val namespace: String) : Result
        object ClassNotFound : Result
    }

    private val unmappedYarn = Regex("^(method_|field_)[0-9]+$")

    fun compare(version: String?, className: String, from: String, to: String): Result = transaction(db) {
        val effectiveVersion = version ?: versionService.latestRelease() ?: return@transaction Result.VersionNotFound
        val versionRow = VersionTable.selectAll().where { VersionTable.versionId eq effectiveVersion }
            .singleOrNull() ?: return@transaction Result.VersionNotFound
        val versionRowId = versionRow[VersionTable.id].value

        if (!namespaceAvailable(versionRow, from)) {
            return@transaction Result.NamespaceUnavailable(effectiveVersion, from)
        }

        val classRow = ClassTable.selectAll()
            .where { (ClassTable.versionId eq versionRowId) and (classColumn(from) eq className) }
            .firstOrNull() ?: return@transaction Result.ClassNotFound
        val classRowId = classRow[ClassTable.id].value

        val methods = MethodTable.selectAll()
            .where { (MethodTable.versionId eq versionRowId) and (MethodTable.classId eq classRowId) }
            .map { row ->
                member(
                    kind = "method",
                    obfName = row[MethodTable.obfName],
                    obfDesc = row[MethodTable.obfDesc],
                    intermediary = row[MethodTable.intermediaryName],
                    yarn = row[MethodTable.yarnName],
                    mojmap = row[MethodTable.mojmapName],
                )
            }
        val fields = FieldTable.selectAll()
            .where { (FieldTable.versionId eq versionRowId) and (FieldTable.classId eq classRowId) }
            .map { row ->
                member(
                    kind = "field",
                    obfName = row[FieldTable.obfName],
                    obfDesc = row[FieldTable.obfDesc],
                    intermediary = row[FieldTable.intermediaryName],
                    yarn = row[FieldTable.yarnName],
                    mojmap = row[FieldTable.mojmapName],
                )
            }

        Result.Ok(
            CompareResponse(
                version = effectiveVersion,
                from = from,
                to = to,
                obf = classRow[ClassTable.obfName],
                intermediary = classRow[ClassTable.intermediaryName],
                yarnClass = classRow[ClassTable.yarnName],
                mojmapClass = classRow[ClassTable.mojmapName],
                presence = classRow[ClassTable.presence],
                members = methods + fields,
            ),
        )
    }

    private fun member(kind: String, obfName: String?, obfDesc: String?, intermediary: String?, yarn: String?, mojmap: String?) =
        CompareMember(
            kind = kind,
            obfName = obfName,
            obfDesc = obfDesc,
            intermediary = intermediary,
            yarn = yarn,
            mojmap = mojmap,
            status = status(yarn, mojmap),
        )

    /** Derives the per-member correspondence status on the fly (not stored). */
    private fun status(yarn: String?, mojmap: String?): String {
        val name = yarn ?: mojmap
        if (name == "<init>" || name == "<clinit>") return "initializer"
        if (yarn != null && yarn.startsWith("lambda\$")) return "synthetic"
        if (yarn != null && unmappedYarn.matches(yarn)) return "unmappedYarn"
        return when {
            yarn != null && mojmap != null -> "matched"
            yarn != null -> "yarnOnly"
            mojmap != null -> "mojmapOnly"
            else -> "unmapped"
        }
    }

    private fun namespaceAvailable(versionRow: ResultRow, ns: String): Boolean = when (ns) {
        "yarn" -> versionRow[VersionTable.hasYarn]
        "mojmap" -> versionRow[VersionTable.hasMojmap]
        "intermediary" -> versionRow[VersionTable.hasIntermediary]
        "obfuscated", "obf" -> true
        else -> false
    }

    private fun classColumn(ns: String): Column<String?> = when (ns) {
        "yarn" -> ClassTable.yarnName
        "mojmap" -> ClassTable.mojmapName
        "intermediary" -> ClassTable.intermediaryName
        "obfuscated", "obf" -> ClassTable.obfName
        else -> ClassTable.yarnName
    }
}
