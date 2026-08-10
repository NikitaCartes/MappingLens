package dev.mappinglens

import dev.mappinglens.config.AppConfig
import dev.mappinglens.config.IndexingConfig
import dev.mappinglens.config.SearchConfig
import dev.mappinglens.config.SourcesConfig
import dev.mappinglens.db.tables.ClassTable
import dev.mappinglens.db.tables.FieldTable
import dev.mappinglens.db.tables.MethodTable
import dev.mappinglens.db.tables.SourceFileTable
import dev.mappinglens.db.tables.VersionTable
import dev.mappinglens.ingestion.Hashing
import dev.mappinglens.ingestion.Names
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Centralized real-data paths and stable fixtures used by integration tests.
 *
 * The defaults are the same relative paths as `mappinglens-defaults.conf`, so a store that lives
 * elsewhere is pointed at with either JVM system properties or environment variables. Tests that
 * call [assumeAvailable] are skipped while the paths do not exist.
 * - mappinglens.test.yarnRepo / MAPPINGLENS_TEST_YARN_REPO
 * - mappinglens.test.mojmapRepo / MAPPINGLENS_TEST_MOJMAP_REPO
 * - mappinglens.test.intermediaryMappings / MAPPINGLENS_TEST_INTERMEDIARY
 * - mappinglens.test.artifactStore / MAPPINGLENS_TEST_ARTIFACT_STORE
 * - mappinglens.test.unobfuscatedIntermediary / MAPPINGLENS_TEST_UNOBFUSCATED_INTERMEDIARY
 */
object RealDataTestConfig {
    const val V_1_20_6 = "1.20.6"
    const val V_1_21 = "1.21"
    const val V_1_21_1 = "1.21.1"

    val versions = listOf(V_1_20_6, V_1_21, V_1_21_1)

    val yarnRepo: Path = configuredPath(
        property = "mappinglens.test.yarnRepo",
        env = "MAPPINGLENS_TEST_YARN_REPO",
        defaultValue = "data/yarn",
    )
    val mojmapRepo: Path = configuredPath(
        property = "mappinglens.test.mojmapRepo",
        env = "MAPPINGLENS_TEST_MOJMAP_REPO",
        defaultValue = "data/mojmap",
    )
    val intermediaryMappings: Path = configuredPath(
        property = "mappinglens.test.intermediaryMappings",
        env = "MAPPINGLENS_TEST_INTERMEDIARY",
        defaultValue = "data/intermediary",
    )
    val artifactStore: Path = configuredPath(
        property = "mappinglens.test.artifactStore",
        env = "MAPPINGLENS_TEST_ARTIFACT_STORE",
        defaultValue = "data/artifact-store",
    )

    /** Optional: absent on a machine that has not cloned the unobfuscated-intermediary repository. */
    val unobfuscatedIntermediary: Path = configuredPath(
        property = "mappinglens.test.unobfuscatedIntermediary",
        env = "MAPPINGLENS_TEST_UNOBFUSCATED_INTERMEDIARY",
        defaultValue = "data/unobfuscated-intermediary",
    )
    val mappingFilesDir: Path = artifactStore.resolve("mappings")
    val minecraftVersionsDir: Path = artifactStore.resolve("mc-versions")

    data class RealClassCase(
        val version: String,
        val obf: String,
        val intermediary: String,
        val yarn: String,
        val mojmap: String,
    )

    data class RealMemberCase(
        val version: String,
        val ownerIntermediary: String,
        val ownerYarn: String,
        val ownerMojmap: String,
        val ownerObf: String,
        val kind: String,
        val obfName: String,
        val obfDesc: String,
        val intermediaryName: String,
        val intermediaryDesc: String,
        val yarnName: String,
        val mojmapName: String,
    )

    data class RealSourceCase(
        val namespace: String,
        val className: String,
        val relativePath: String,
        val markers: List<String>,
    ) {
        fun file(root: Path): Path = fileInRepository(root).takeIf { Files.exists(it) } ?: root.resolve(relativePath)

        fun fileInRepository(root: Path): Path = root.resolve("minecraft").resolve("src").resolve(relativePath)
    }

    val blockClasses = listOf(
        RealClassCase(V_1_20_6, "dfb", "net/minecraft/class_2248", "net/minecraft/block/Block", "net/minecraft/world/level/block/Block"),
        RealClassCase(V_1_21, "dfy", "net/minecraft/class_2248", "net/minecraft/block/Block", "net/minecraft/world/level/block/Block"),
        RealClassCase(V_1_21_1, "dfy", "net/minecraft/class_2248", "net/minecraft/block/Block", "net/minecraft/world/level/block/Block"),
    )

    val blockStateClasses = listOf(
        RealClassCase(V_1_20_6, "dse", "net/minecraft/class_2680", "net/minecraft/block/BlockState", "net/minecraft/world/level/block/state/BlockState"),
        RealClassCase(V_1_21, "dtc", "net/minecraft/class_2680", "net/minecraft/block/BlockState", "net/minecraft/world/level/block/state/BlockState"),
        RealClassCase(V_1_21_1, "dtc", "net/minecraft/class_2680", "net/minecraft/block/BlockState", "net/minecraft/world/level/block/state/BlockState"),
    )

    val itemStackClasses = listOf(
        RealClassCase(V_1_20_6, "cur", "net/minecraft/class_1799", "net/minecraft/item/ItemStack", "net/minecraft/world/item/ItemStack"),
        RealClassCase(V_1_21, "cuq", "net/minecraft/class_1799", "net/minecraft/item/ItemStack", "net/minecraft/world/item/ItemStack"),
        RealClassCase(V_1_21_1, "cuq", "net/minecraft/class_1799", "net/minecraft/item/ItemStack", "net/minecraft/world/item/ItemStack"),
    )

    val classCases = blockClasses + blockStateClasses + itemStackClasses

    val defaultStateMethods = listOf(
        RealMemberCase(
            version = V_1_20_6,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfb",
            kind = "method",
            obfName = "o",
            obfDesc = "()Ldse;",
            intermediaryName = "method_9564",
            intermediaryDesc = "()Lnet/minecraft/class_2680;",
            yarnName = "getDefaultState",
            mojmapName = "defaultBlockState",
        ),
        RealMemberCase(
            version = V_1_21,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfy",
            kind = "method",
            obfName = "o",
            obfDesc = "()Ldtc;",
            intermediaryName = "method_9564",
            intermediaryDesc = "()Lnet/minecraft/class_2680;",
            yarnName = "getDefaultState",
            mojmapName = "defaultBlockState",
        ),
        RealMemberCase(
            version = V_1_21_1,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfy",
            kind = "method",
            obfName = "o",
            obfDesc = "()Ldtc;",
            intermediaryName = "method_9564",
            intermediaryDesc = "()Lnet/minecraft/class_2680;",
            yarnName = "getDefaultState",
            mojmapName = "defaultBlockState",
        ),
    )

    val stateIdsFields = listOf(
        RealMemberCase(
            version = V_1_20_6,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfb",
            kind = "field",
            obfName = "q",
            obfDesc = "Ljo;",
            intermediaryName = "field_10651",
            intermediaryDesc = "Lnet/minecraft/class_2378;",
            yarnName = "STATE_IDS",
            mojmapName = "BLOCK_STATE_REGISTRY",
        ),
        RealMemberCase(
            version = V_1_21,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfy",
            kind = "field",
            obfName = "q",
            obfDesc = "Ljs;",
            intermediaryName = "field_10651",
            intermediaryDesc = "Lnet/minecraft/class_2378;",
            yarnName = "STATE_IDS",
            mojmapName = "BLOCK_STATE_REGISTRY",
        ),
        RealMemberCase(
            version = V_1_21_1,
            ownerIntermediary = "net/minecraft/class_2248",
            ownerYarn = "net/minecraft/block/Block",
            ownerMojmap = "net/minecraft/world/level/block/Block",
            ownerObf = "dfy",
            kind = "field",
            obfName = "q",
            obfDesc = "Ljs;",
            intermediaryName = "field_10651",
            intermediaryDesc = "Lnet/minecraft/class_2378;",
            yarnName = "STATE_IDS",
            mojmapName = "BLOCK_STATE_REGISTRY",
        ),
    )

    val memberCases = defaultStateMethods + stateIdsFields

    val sourceCases = listOf(
        RealSourceCase(
            namespace = "yarn",
            className = "net/minecraft/block/BlockState",
            relativePath = "net/minecraft/block/BlockState.java",
            markers = listOf(
                "package net.minecraft.block;",
                "public class BlockState extends AbstractBlock.AbstractBlockState",
                "Block::getDefaultState",
            ),
        ),
        RealSourceCase(
            namespace = "mojmap",
            className = "net/minecraft/world/level/block/state/BlockState",
            relativePath = "net/minecraft/world/level/block/state/BlockState.java",
            markers = listOf(
                "package net.minecraft.world.level.block.state;",
                "public class BlockState extends BlockBehaviour.BlockStateBase",
                "Block::defaultBlockState",
            ),
        ),
        RealSourceCase(
            namespace = "yarn",
            className = "net/minecraft/item/ItemStack",
            relativePath = "net/minecraft/item/ItemStack.java",
            markers = listOf(
                "package net.minecraft.item;",
                "public final class ItemStack",
                "net.minecraft.block.BlockState",
            ),
        ),
        RealSourceCase(
            namespace = "mojmap",
            className = "net/minecraft/world/item/ItemStack",
            relativePath = "net/minecraft/world/item/ItemStack.java",
            markers = listOf(
                "package net.minecraft.world.item;",
                "public final class ItemStack",
                "net.minecraft.world.damagesource.DamageSource",
            ),
        ),
    )

    fun sourcesConfig(): SourcesConfig = SourcesConfig(
        yarnRepo = yarnRepo.toString(),
        mojmapRepo = mojmapRepo.toString(),
        intermediaryMappings = intermediaryMappings.toString(),
        artifactStore = artifactStore.toString(),
        unobfuscatedIntermediaryMappings =
            if (Files.isDirectory(unobfuscatedIntermediary)) unobfuscatedIntermediary.toString() else "",
    )

    fun appConfig(databasePath: Path, initialVersions: List<String> = versions): AppConfig = AppConfig(
        databasePath = databasePath.toString(),
        sources = sourcesConfig(),
        indexing = IndexingConfig(
            pollIntervalSeconds = 0,
            initialVersions = initialVersions,
            indexOnStartup = false,
        ),
        search = SearchConfig(maxResults = 200, defaultResults = 50),
    )

    fun assumeAvailable() {
        val missing = requiredRoots().filterNot { Files.exists(it) }
        assumeTrue(missing.isEmpty(), "Real test data folders are missing: ${missing.joinToString()}")

        val missingMappings = versions.flatMap { version ->
            listOfNotNull(
                intermediaryMappingFor(version).takeIf { !Files.exists(it) },
                runCatching { yarnMappingFor(version) }.getOrNull()?.takeIf { !Files.exists(it) },
                runCatching { mojmapMappingFor(version) }.getOrNull()?.takeIf { !Files.exists(it) },
            )
        }
        assumeTrue(missingMappings.isEmpty(), "Real mapping files are missing: ${missingMappings.joinToString()}")
    }

    fun yarnMappingFor(version: String): Path {
        if (!Files.isDirectory(mappingFilesDir)) return mappingFilesDir.resolve("$version-yarn-build.0.tiny")
        return Files.list(mappingFilesDir).use { stream ->
            stream.filter { path ->
                val name = path.fileName.toString()
                name.startsWith("$version-yarn-build.") && name.endsWith(".tiny") &&
                    !name.contains("-constants") && !name.contains("-unpick")
            }.toList().maxByOrNull { path ->
                path.fileName.toString()
                    .removePrefix("$version-yarn-build.")
                    .removeSuffix(".tiny")
                    .toIntOrNull() ?: -1
            }
        } ?: error("No yarn mapping found for $version in $mappingFilesDir")
    }

    fun mojmapMappingFor(version: String): Path = listOf(
        mappingFilesDir.resolve("$version-moj.tiny"),
        mappingFilesDir.resolve("$version-client-moj.tiny"),
        mappingFilesDir.resolve("$version-server-moj.tiny"),
    ).firstOrNull { Files.exists(it) } ?: error("No mojmap mapping found for $version in $mappingFilesDir")

    fun intermediaryMappingFor(version: String): Path = intermediaryMappings.resolve("$version.tiny")

    fun jarDirFor(version: String): Path = minecraftVersionsDir.resolve(version)

    fun seedMappingSlice(db: Database, seededVersions: List<String> = versions) {
        transaction(db) {
            for (version in seededVersions) {
                val versionRowId = VersionTable.insertAndGetId {
                    it[versionId] = version
                    it[releaseType] = "release"
                    it[indexedAt] = Instant.now().toString()
                    it[hasYarn] = true
                    it[hasMojmap] = true
                    it[hasIntermediary] = true
                }.value

                val classIds = mutableMapOf<String, Int>()
                classCases.filter { it.version == version }.forEach { cls ->
                    classIds[cls.intermediary] = insertClass(versionRowId, cls)
                }

                memberCases.filter { it.version == version }.forEach { member ->
                    val ownerId = classIds.getValue(member.ownerIntermediary)
                    when (member.kind) {
                        "method" -> insertMethod(versionRowId, ownerId, member)
                        "field" -> insertField(versionRowId, ownerId, member)
                    }
                }

                insertSourceRows(versionRowId)
            }
        }
    }

    private fun configuredPath(property: String, env: String, defaultValue: String): Path {
        return Paths.get(System.getProperty(property) ?: System.getenv(env) ?: defaultValue).toAbsolutePath()
    }

    private fun requiredRoots(): List<Path> = listOf(
        yarnRepo,
        mojmapRepo,
        intermediaryMappings,
        artifactStore,
        mappingFilesDir,
        minecraftVersionsDir,
    )

    private fun insertClass(versionRowId: Int, cls: RealClassCase): Int {
        val id = ClassTable.insertAndGetId {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[obfName] = cls.obf
            it[intermediaryName] = cls.intermediary
            it[yarnName] = cls.yarn
            it[mojmapName] = cls.mojmap
            it[packagePath] = Names.packagePath(cls.yarn)
            it[simpleName] = Names.simpleName(cls.yarn)
        }.value
        insertFts(
            elementType = "class",
            elementId = id,
            versionRowId = versionRowId,
            yarn = cls.yarn,
            mojmap = cls.mojmap,
            intermediary = cls.intermediary,
            obf = cls.obf,
            simpleName = Names.simpleName(cls.yarn),
        )
        return id
    }

    private fun insertMethod(versionRowId: Int, ownerId: Int, member: RealMemberCase) {
        val id = MethodTable.insertAndGetId {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[classId] = EntityID(ownerId, ClassTable)
            it[obfName] = member.obfName
            it[obfDesc] = member.obfDesc
            it[intermediaryName] = member.intermediaryName
            it[intermediaryDesc] = member.intermediaryDesc
            it[yarnName] = member.yarnName
            it[mojmapName] = member.mojmapName
            it[simpleName] = member.yarnName
        }.value
        insertFts(
            elementType = "method",
            elementId = id,
            versionRowId = versionRowId,
            yarn = "${member.ownerYarn}#${member.yarnName}",
            mojmap = "${member.ownerMojmap}#${member.mojmapName}",
            intermediary = "${member.ownerIntermediary}#${member.intermediaryName}",
            obf = "${member.ownerObf}#${member.obfName}",
            simpleName = member.yarnName,
        )
    }

    private fun insertField(versionRowId: Int, ownerId: Int, member: RealMemberCase) {
        val id = FieldTable.insertAndGetId {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[classId] = EntityID(ownerId, ClassTable)
            it[obfName] = member.obfName
            it[obfDesc] = member.obfDesc
            it[intermediaryName] = member.intermediaryName
            it[intermediaryDesc] = member.intermediaryDesc
            it[yarnName] = member.yarnName
            it[mojmapName] = member.mojmapName
            it[simpleName] = member.yarnName
        }.value
        insertFts(
            elementType = "field",
            elementId = id,
            versionRowId = versionRowId,
            yarn = "${member.ownerYarn}#${member.yarnName}",
            mojmap = "${member.ownerMojmap}#${member.mojmapName}",
            intermediary = "${member.ownerIntermediary}#${member.intermediaryName}",
            obf = "${member.ownerObf}#${member.obfName}",
            simpleName = member.yarnName,
        )
    }

    private fun insertSourceRows(versionRowId: Int) {
        for (sourceCase in sourceCases) {
            val root = if (sourceCase.namespace == "mojmap") mojmapRepo else yarnRepo
            val file = sourceCase.file(root)
            if (!Files.exists(file)) continue
            SourceFileTable.insertAndGetId {
                it[versionId] = EntityID(versionRowId, VersionTable)
                it[mappingType] = sourceCase.namespace
                it[relativePath] = sourceCase.relativePath
                it[contentHash] = Hashing.sha256(file)
            }
        }
    }

    private fun insertFts(
        elementType: String,
        elementId: Int,
        versionRowId: Int,
        yarn: String?,
        mojmap: String?,
        intermediary: String?,
        obf: String?,
        simpleName: String?,
    ) {
        TransactionManager.current().exec(
            """
            INSERT INTO search_index(element_type, element_id, version_id, yarn_name, mojmap_name, intermediary_name, obf_name, simple_name)
            VALUES (${quote(elementType)}, $elementId, $versionRowId, ${quote(yarn)}, ${quote(mojmap)}, ${quote(intermediary)}, ${quote(obf)}, ${quote(simpleName)});
            """.trimIndent()
        )
    }

    private fun quote(value: String?): String = if (value == null) "''" else "'" + value.replace("'", "''") + "'"
}
