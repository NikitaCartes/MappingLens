package dev.mappinglens.service

import dev.mappinglens.db.tables.VersionTable
import dev.mappinglens.model.VersionInfo
import dev.mappinglens.model.VersionListResponse
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class VersionService(private val db: Database) {

    fun listVersions(): VersionListResponse = transaction(db) {
        val versions = VersionTable.selectAll().orderBy(VersionTable.versionId).map { row ->
            val versionRowId = row[VersionTable.id].value
            val (classes, methods, fields) = countsFor(versionRowId)
            VersionInfo(
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
        }
        VersionListResponse(versions)
    }

    fun getVersion(versionId: String): VersionInfo? = transaction(db) {
        val row = VersionTable.selectAll().where { VersionTable.versionId eq versionId }.singleOrNull()
            ?: return@transaction null
        val versionRowId = row[VersionTable.id].value
        val (classes, methods, fields) = countsFor(versionRowId)
        VersionInfo(
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
    }

    /**
     * Returns the latest "release" version, falling back to any version if none.
     */
    fun latestRelease(): String? = transaction(db) {
        VersionTable.selectAll().where { VersionTable.releaseType eq "release" }
            .orderBy(VersionTable.versionId, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1).singleOrNull()?.get(VersionTable.versionId)
            ?: VersionTable.selectAll()
                .orderBy(VersionTable.versionId, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(1).singleOrNull()?.get(VersionTable.versionId)
    }

    private fun countsFor(versionRowId: Int): Triple<Long, Long, Long> {
        val classCount = dev.mappinglens.db.tables.ClassTable.selectAll()
            .where { dev.mappinglens.db.tables.ClassTable.versionId eq versionRowId }.count()
        val methodCount = dev.mappinglens.db.tables.MethodTable.selectAll()
            .where { dev.mappinglens.db.tables.MethodTable.versionId eq versionRowId }.count()
        val fieldCount = dev.mappinglens.db.tables.FieldTable.selectAll()
            .where { dev.mappinglens.db.tables.FieldTable.versionId eq versionRowId }.count()
        return Triple(classCount, methodCount, fieldCount)
    }
}
