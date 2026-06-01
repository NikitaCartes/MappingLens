package dev.mappinglens.db.tables

import org.jetbrains.exposed.dao.id.IntIdTable

object VersionTable : IntIdTable("versions") {
    val versionId = text("version_id").uniqueIndex()
    val releaseType = text("release_type")
    val releaseTime = text("release_time").nullable()
    val protocolVersion = integer("protocol_version").nullable()
    val indexedAt = text("indexed_at")
    val gitRevYarn = text("git_rev_yarn").nullable()
    val gitRevMojmap = text("git_rev_mojmap").nullable()
    val hasYarn = bool("has_yarn").default(false)
    val hasMojmap = bool("has_mojmap").default(false)
    val hasIntermediary = bool("has_intermediary").default(false)
}

object ClassTable : IntIdTable("classes") {
    val versionId = reference("version_id", VersionTable).index()
    val obfName = text("obf_name").nullable().index()
    val intermediaryName = text("intermediary_name").nullable().index()
    val yarnName = text("yarn_name").nullable().index()
    val mojmapName = text("mojmap_name").nullable().index()
    val packagePath = text("package_path").nullable().index()
    val simpleName = text("simple_name").nullable().index()
}

object MethodTable : IntIdTable("methods") {
    val classId = reference("class_id", ClassTable).index()
    val versionId = reference("version_id", VersionTable).index()
    val obfName = text("obf_name").nullable()
    val obfDesc = text("obf_desc").nullable()
    val intermediaryName = text("intermediary_name").nullable().index()
    val intermediaryDesc = text("intermediary_desc").nullable()
    val yarnName = text("yarn_name").nullable()
    val mojmapName = text("mojmap_name").nullable()
    val simpleName = text("simple_name").nullable().index()

    init {
        index(isUnique = false, versionId, intermediaryName, intermediaryDesc)
        index(isUnique = false, versionId, mojmapName, obfDesc)
    }
}

object FieldTable : IntIdTable("fields") {
    val classId = reference("class_id", ClassTable).index()
    val versionId = reference("version_id", VersionTable).index()
    val obfName = text("obf_name").nullable()
    val obfDesc = text("obf_desc").nullable()
    val intermediaryName = text("intermediary_name").nullable().index()
    val intermediaryDesc = text("intermediary_desc").nullable()
    val yarnName = text("yarn_name").nullable()
    val mojmapName = text("mojmap_name").nullable()
    val simpleName = text("simple_name").nullable().index()

    init {
        index(isUnique = false, versionId, intermediaryName, intermediaryDesc)
        index(isUnique = false, versionId, mojmapName, obfDesc)
    }
}

object SourceFileTable : IntIdTable("source_files") {
    val versionId = reference("version_id", VersionTable).index()
    val mappingType = text("mapping_type") // yarn / mojmap
    val classId = reference("class_id", ClassTable).nullable()
    val relativePath = text("relative_path").index()
    val contentHash = text("content_hash")

    init {
        uniqueIndex("source_files_unique", versionId, mappingType, relativePath)
    }
}
