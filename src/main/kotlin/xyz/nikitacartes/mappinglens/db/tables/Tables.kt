package xyz.nikitacartes.mappinglens.db.tables

import org.jetbrains.exposed.dao.id.IntIdTable

object VersionTable : IntIdTable("versions") {
    val versionId = text("version_id").uniqueIndex()
    val releaseType = text("release_type")
    val releaseTime = text("release_time").nullable()
    val protocolVersion = integer("protocol_version").nullable()
    val indexedAt = text("indexed_at")
    val hasYarn = bool("has_yarn").default(false)
    val hasMojmap = bool("has_mojmap").default(false)
    val hasIntermediary = bool("has_intermediary").default(false)

    // Rank of this version in canonical semver order (lower = older). Lets the stateless server
    // order versions and resolve "latest release" correctly without re-reading the semver cache.
    val sortIndex = integer("sort_index").nullable().index()

    // The version this one is a re-indexing of, or null when it is a version in its own right.
    // `1.21.11_unobfuscated` is the same build as `1.21.11`, read from Mojang's pre-deobfuscated jar
    // instead of the obfuscated one, so the two sit next to each other in semver order and every
    // walk over the versions reports the pair as a change. Endpoints that walk versions skip these
    // rows unless asked for them; a direct lookup by id still answers.
    val variantOf = text("variant_of").nullable()

    // Inclusive [min,max] FTS5 rowid range of this version's search_index rows. Each version's rows
    // are inserted contiguously, so the server prunes a search MATCH with `rowid BETWEEN ? AND ?`
    // (which FTS5 pushes into the scan) instead of post-filtering the version-agnostic match across
    // all ~500 versions — same results, but it ranks only one version's rows. See IngestPipeline.
    val ftsMinRowid = long("fts_min_rowid").nullable()
    val ftsMaxRowid = long("fts_max_rowid").nullable()

    // Number of class, method and field rows of this version. The indexer holds these three numbers
    // while it writes the rows, so recording them costs it nothing. Without them the version catalog
    // has to derive them, and that means three grouped COUNTs over ~51M rows on every server start.
    // Null on an index built before these columns; see VersionService.
    val classCount = long("class_count").nullable()
    val methodCount = long("method_count").nullable()
    val fieldCount = long("field_count").nullable()
}

object ClassTable : IntIdTable("classes") {
    val versionId = reference("version_id", VersionTable).index()
    val obfName = text("obf_name").nullable().index()
    val intermediaryName = text("intermediary_name").nullable().index()
    val yarnName = text("yarn_name").nullable().index()
    val mojmapName = text("mojmap_name").nullable().index()
    val packagePath = text("package_path").nullable().index()
    val simpleName = text("simple_name").nullable().index()

    // Yarn<->Mojmap correspondence side: both | yarn_only | mojmap_only (see CorrespondenceResolver).
    val presence = text("presence").nullable()

    init {
        // Cross-version class diff joins on (version_id, intermediary_name). The standalone
        // intermediary_name index is version-agnostic, so a rename scan fans out across all ~500
        // indexed versions (5s+ on a full index). This composite — mirroring the methods/fields
        // (version_id, intermediary_name, ...) indexes — makes that join a direct seek (~3ms).
        index(isUnique = false, versionId, intermediaryName)
    }
}

/**
 * Methods and fields carry the same columns; only the table name and [kind] differ. Sharing the
 * declaration lets one query body serve both kinds of member. Name selection per namespace stays
 * with the caller: the services disagree on which namespace an unknown value falls back to.
 */
sealed class MemberTable(name: String, val kind: String) : IntIdTable(name) {
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

object MethodTable : MemberTable("methods", "method")

object FieldTable : MemberTable("fields", "field")

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
