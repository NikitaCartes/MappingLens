package dev.mappinglens

import dev.mappinglens.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.time.Instant

/**
 * Small in-memory mappings dataset used across service / route tests.
 *
 * Names are based on real Yarn ↔ Mojang ↔ Intermediary correspondences
 * for Minecraft 1.21.1, as exposed by https://linkie.shedaniel.dev/.
 * We only keep a handful of entries — enough to drive translation, search and diff.
 */
object Fixtures {
    const val V_1_21_1 = "1.21.1"
    const val V_1_21 = "1.21"

    data class ClassEntry(
        val obf: String?,
        val intermediary: String,
        val yarn: String,
        val mojmap: String,
    )

    data class MemberEntry(
        val obfName: String?,
        val obfDesc: String?,
        val intermediary: String,
        val intermediaryDesc: String,
        val yarn: String,
        val mojmap: String,
    )

    // 1.21.1 (current per linkie at time of writing) — small representative slice
    val blockState_1_21_1 = ClassEntry(
        obf = "dpb",
        intermediary = "net/minecraft/class_2680",
        yarn = "net/minecraft/block/BlockState",
        mojmap = "net/minecraft/world/level/block/state/BlockState",
    )
    val block_1_21_1 = ClassEntry(
        obf = "dnv",
        intermediary = "net/minecraft/class_2248",
        yarn = "net/minecraft/block/Block",
        mojmap = "net/minecraft/world/level/block/Block",
    )

    // Block#getDefaultState (yarn) == Block.defaultBlockState (mojmap)
    val getDefaultState = MemberEntry(
        obfName = "n",
        obfDesc = "()Ldpb;",
        intermediary = "method_9564",
        intermediaryDesc = "()Lnet/minecraft/class_2680;",
        yarn = "getDefaultState",
        mojmap = "defaultBlockState",
    )

    // Block#STATE_IDS field (yarn) == Block.BLOCK_STATE_REGISTRY (mojmap)
    val stateIds = MemberEntry(
        obfName = "j",
        obfDesc = "Lgs;",
        intermediary = "field_10651",
        intermediaryDesc = "Lnet/minecraft/class_2378;",
        yarn = "STATE_IDS",
        mojmap = "BLOCK_STATE_REGISTRY",
    )

    // Slightly different 1.21 data: same intermediary, same class names, but
    //  - one method renamed in yarn vs 1.21.1 (renamed)
    //  - one extra class only present in 1.21.1 (added)
    //  - one extra class only present in 1.21 (removed)
    val onlyIn1_21_1 = ClassEntry(
        obf = "abc",
        intermediary = "net/minecraft/class_9999",
        yarn = "net/minecraft/block/NewBlock",
        mojmap = "net/minecraft/world/level/block/NewBlock",
    )
    val onlyIn1_21 = ClassEntry(
        obf = "xyz",
        intermediary = "net/minecraft/class_8888",
        yarn = "net/minecraft/block/OldBlock",
        mojmap = "net/minecraft/world/level/block/OldBlock",
    )

    fun newDb(tmp: Path): Database {
        val dbFile = tmp.resolve("test-${System.nanoTime()}.db")
        val db = Database.connect(
            url = "jdbc:sqlite:${dbFile.toString().replace('\\', '/')}",
            driver = "org.sqlite.JDBC",
        )
        // Create schema + FTS5 search index. Mirrors DatabaseFactory.init but skips the
        // WAL PRAGMA (which cannot run inside the implicit transaction Exposed opens).
        transaction(db) {
            SchemaUtils.create(VersionTable, ClassTable, MethodTable, FieldTable, SourceFileTable)
            exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS search_index USING fts5(
                    element_type UNINDEXED,
                    element_id UNINDEXED,
                    version_id UNINDEXED,
                    yarn_name,
                    mojmap_name,
                    intermediary_name,
                    obf_name,
                    simple_name,
                    tokenize='unicode61 remove_diacritics 2'
                );
                """.trimIndent()
            )
        }
        return db
    }

    /** Inserts the canonical 1.21.1 slice. */
    fun seed_1_21_1(db: Database) = transaction(db) {
        val vId = insertVersion(V_1_21_1)
        val blockId = insertClass(vId, block_1_21_1)
        val blockStateId = insertClass(vId, blockState_1_21_1)
        insertMethod(vId, blockId, getDefaultState)
        insertField(vId, blockId, stateIds)
        insertClass(vId, onlyIn1_21_1)
        Unit
    }

    /** Inserts a 1.21 slice that differs slightly from 1.21.1 for diff tests. */
    fun seed_1_21(db: Database) = transaction(db) {
        val vId = insertVersion(V_1_21)
        val blockId = insertClass(vId, block_1_21_1)
        insertClass(vId, blockState_1_21_1)
        // same method, but renamed in this older version
        insertMethod(vId, blockId, getDefaultState.copy(yarn = "getDefaultStateOld"))
        insertField(vId, blockId, stateIds)
        insertClass(vId, onlyIn1_21)
        Unit
    }

    private fun insertVersion(versionId: String): Int = VersionTable.insertAndGetId {
        it[VersionTable.versionId] = versionId
        it[releaseType] = "release"
        it[indexedAt] = Instant.now().toString()
        it[hasYarn] = true
        it[hasMojmap] = true
        it[hasIntermediary] = true
    }.value

    private fun insertClass(vId: Int, c: ClassEntry): Int {
        val id = ClassTable.insertAndGetId {
            it[versionId] = org.jetbrains.exposed.dao.id.EntityID(vId, VersionTable)
            it[obfName] = c.obf
            it[intermediaryName] = c.intermediary
            it[yarnName] = c.yarn
            it[mojmapName] = c.mojmap
            it[packagePath] = c.yarn.substringBeforeLast('/')
            it[simpleName] = c.yarn.substringAfterLast('/')
        }.value
        // Mirror into FTS5 for search tests
        org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
            """
            INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name)
            VALUES ('class', $id, $vId,
                    ${q(c.yarn)}, ${q(c.mojmap)}, ${q(c.intermediary)}, ${q(c.obf)},
                    ${q(c.yarn.substringAfterLast('/'))});
            """.trimIndent()
        )
        return id
    }

    private fun insertMethod(vId: Int, classRowId: Int, m: MemberEntry) {
        val id = MethodTable.insertAndGetId {
            it[versionId] = org.jetbrains.exposed.dao.id.EntityID(vId, VersionTable)
            it[classId] = org.jetbrains.exposed.dao.id.EntityID(classRowId, ClassTable)
            it[obfName] = m.obfName
            it[obfDesc] = m.obfDesc
            it[intermediaryName] = m.intermediary
            it[intermediaryDesc] = m.intermediaryDesc
            it[yarnName] = m.yarn
            it[mojmapName] = m.mojmap
            it[simpleName] = m.yarn
        }.value
        org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
            """
            INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name)
            VALUES ('method', $id, $vId,
                    ${q("${block_1_21_1.yarn}#${m.yarn}")}, ${q("${block_1_21_1.mojmap}#${m.mojmap}")},
                    ${q("${block_1_21_1.intermediary}#${m.intermediary}")}, ${q("${block_1_21_1.obf}#${m.obfName}")}, ${q(m.yarn)});
            """.trimIndent()
        )
    }

    private fun insertField(vId: Int, classRowId: Int, f: MemberEntry) {
        val id = FieldTable.insertAndGetId {
            it[versionId] = org.jetbrains.exposed.dao.id.EntityID(vId, VersionTable)
            it[classId] = org.jetbrains.exposed.dao.id.EntityID(classRowId, ClassTable)
            it[obfName] = f.obfName
            it[obfDesc] = f.obfDesc
            it[intermediaryName] = f.intermediary
            it[intermediaryDesc] = f.intermediaryDesc
            it[yarnName] = f.yarn
            it[mojmapName] = f.mojmap
            it[simpleName] = f.yarn
        }.value
        org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec(
            """
            INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name)
            VALUES ('field', $id, $vId,
                    ${q("${block_1_21_1.yarn}#${f.yarn}")}, ${q("${block_1_21_1.mojmap}#${f.mojmap}")},
                    ${q("${block_1_21_1.intermediary}#${f.intermediary}")}, ${q("${block_1_21_1.obf}#${f.obfName}")}, ${q(f.yarn)});
            """.trimIndent()
        )
    }

    private fun q(s: String?): String = if (s == null) "''" else "'" + s.replace("'", "''") + "'"
}
