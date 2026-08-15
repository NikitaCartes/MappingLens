package dev.mappinglens.db.tables

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column

/**
 * [MethodTable] and [FieldTable] carry the same columns under different [Column] instances. Holding
 * them together lets one query body serve both kinds of member. Name selection per namespace stays
 * with the caller: the services disagree on which namespace an unknown value falls back to.
 */
class MemberCols private constructor(
    val table: IntIdTable,
    val kind: String,
    val classId: Column<EntityID<Int>>,
    val versionId: Column<EntityID<Int>>,
    val obfName: Column<String?>,
    val obfDesc: Column<String?>,
    val intermediaryName: Column<String?>,
    val intermediaryDesc: Column<String?>,
    val yarnName: Column<String?>,
    val mojmapName: Column<String?>,
) {
    companion object {
        val METHOD = MemberCols(
            MethodTable, "method", MethodTable.classId, MethodTable.versionId,
            MethodTable.obfName, MethodTable.obfDesc, MethodTable.intermediaryName,
            MethodTable.intermediaryDesc, MethodTable.yarnName, MethodTable.mojmapName,
        )

        val FIELD = MemberCols(
            FieldTable, "field", FieldTable.classId, FieldTable.versionId,
            FieldTable.obfName, FieldTable.obfDesc, FieldTable.intermediaryName,
            FieldTable.intermediaryDesc, FieldTable.yarnName, FieldTable.mojmapName,
        )

        fun of(table: IntIdTable): MemberCols = when (table) {
            MethodTable -> METHOD
            FieldTable -> FIELD
            else -> error("no member columns for ${table.tableName}")
        }
    }
}
