package dev.mappinglens.service

import dev.mappinglens.Fixtures
import dev.mappinglens.config.AppConfig
import dev.mappinglens.config.IndexingConfig
import dev.mappinglens.config.SearchConfig
import dev.mappinglens.config.SourcesConfig
import dev.mappinglens.db.tables.SourceFileTable
import dev.mappinglens.db.tables.VersionTable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionServiceTest {

    @Test
    fun `listVersions returns indexed versions with counts`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        Fixtures.seed_1_21(db)

        val resp = VersionService(db).listVersions()
        val byId = resp.versions.associateBy { it.id }
        assertEquals(setOf("1.21", "1.21.1"), byId.keys)

        val v = byId["1.21.1"]!!
        // 3 classes seeded for 1.21.1: Block, BlockState, NewBlock
        assertEquals(3L, v.classCount)
        assertEquals(1L, v.methodCount)
        assertEquals(1L, v.fieldCount)
        assertTrue(v.hasYarn && v.hasMojmap && v.hasIntermediary)
    }

    @Test
    fun `getVersion returns null for unknown version`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        assertNull(VersionService(db).getVersion("9.9.9"))
        assertNotNull(VersionService(db).getVersion("1.21.1"))
    }

    @Test
    fun `latestRelease prefers release type, returns highest by id sort`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        Fixtures.seed_1_21(db)
        assertEquals("1.21.1", VersionService(db).latestRelease())
    }
}

class TranslationServiceTest {

    /**
     * Expected translations are real Yarn<->Mojang<->Intermediary correspondences
     * sourced from https://linkie.shedaniel.dev/ for Minecraft 1.21.1.
     */

    private fun setup(@TempDir tmp: Path): TranslationService {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        return TranslationService(db, VersionService(db))
    }

    @Test
    fun `class yarn to mojmap`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val r = s.translate("net/minecraft/block/BlockState", from = "yarn", to = "mojmap", version = "1.21.1", type = "class")
        assertNotNull(r)
        assertEquals("net/minecraft/world/level/block/state/BlockState", r.output.name)
        assertEquals("mojmap", r.output.namespace)
        assertEquals("net/minecraft/class_2680", r.intermediary)
        assertEquals("dpb", r.obfuscated)
        assertEquals("class", r.type)
        assertEquals("1.21.1", r.version)
    }

    @Test
    fun `class mojmap to yarn`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val r = s.translate(
            "net/minecraft/world/level/block/state/BlockState",
            from = "mojmap", to = "yarn", version = "1.21.1", type = "class",
        )
        assertNotNull(r)
        assertEquals("net/minecraft/block/BlockState", r.output.name)
    }

    @Test
    fun `class intermediary to yarn and mojmap`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val toYarn = s.translate("net/minecraft/class_2680", "intermediary", "yarn", "1.21.1", "class")
        val toMoj = s.translate("net/minecraft/class_2680", "intermediary", "mojmap", "1.21.1", "class")
        assertEquals("net/minecraft/block/BlockState", toYarn?.output?.name)
        assertEquals("net/minecraft/world/level/block/state/BlockState", toMoj?.output?.name)
    }

    @Test
    fun `method translation yarn to mojmap with owner prefix`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val r = s.translate(
            "net/minecraft/block/Block#getDefaultState",
            from = "yarn", to = "mojmap", version = "1.21.1", type = "method",
        )
        assertNotNull(r)
        assertEquals("net/minecraft/world/level/block/Block#defaultBlockState", r.output.name)
        assertEquals("net/minecraft/class_2248#method_9564", r.intermediary)
        assertEquals("method", r.type)
    }

    @Test
    fun `field translation yarn to mojmap`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val r = s.translate("STATE_IDS", from = "yarn", to = "mojmap", version = "1.21.1", type = "field")
        assertNotNull(r)
        assertEquals("net/minecraft/world/level/block/Block#BLOCK_STATE_REGISTRY", r.output.name)
    }

    @Test
    fun `unknown name returns null`(@TempDir tmp: Path) {
        val s = setup(tmp)
        assertNull(s.translate("net/minecraft/Nope", "yarn", "mojmap", "1.21.1", "class"))
    }

    @Test
    fun `unknown version returns null`(@TempDir tmp: Path) {
        val s = setup(tmp)
        assertNull(s.translate("net/minecraft/block/Block", "yarn", "mojmap", "9.9.9", "class"))
    }

    @Test
    fun `auto type resolves class without explicit hint`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val r = s.translate("net/minecraft/block/Block", "yarn", "mojmap", "1.21.1", type = "auto")
        assertNotNull(r)
        assertEquals("class", r.type)
        assertEquals("net/minecraft/world/level/block/Block", r.output.name)
    }
}

class SearchServiceTest {

    private fun setup(@TempDir tmp: Path): SearchService {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        return SearchService(db, VersionService(db))
    }

    @Test
    fun `prefix class search by simple name returns matching classes`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.search("BlockState", version = "1.21.1", type = "class", namespace = "all", limit = 20, offset = 0, exact = false)
        assertTrue(resp.results.isNotEmpty(), "expected matches, got: ${resp.results}")
        val yarn = resp.results.mapNotNull { it.yarn }
        assertTrue(yarn.any { it.endsWith("/BlockState") })
    }

    @Test
    fun `namespace-restricted search ignores hits in other namespaces`(@TempDir tmp: Path) {
        val s = setup(tmp)
        // mojmap-only token "defaultBlockState"; restricted to yarn should yield no results
        val mojResp = s.search("defaultBlockState", "1.21.1", "method", "mojmap", 10, 0, true)
        val yarnResp = s.search("defaultBlockState", "1.21.1", "method", "yarn", 10, 0, true)
        assertTrue(mojResp.results.isNotEmpty())
        assertEquals(0, yarnResp.results.size)
    }

    @Test
    fun `exact field search by yarn name returns one hit`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.search("STATE_IDS", "1.21.1", "field", "yarn", 10, 0, exact = true)
        assertEquals(1, resp.results.size)
        val r = resp.results.single()
        assertEquals("field", r.type)
        assertTrue(r.yarn!!.endsWith("#STATE_IDS"))
        assertTrue(r.mojmap!!.endsWith("#BLOCK_STATE_REGISTRY"))
    }

    @Test
    fun `all-type search prioritizes class names before member hits`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.search("Block", "1.21.1", "all", "yarn", 10, 0, exact = false)

        val firstThree = resp.results.take(3)
        assertEquals(listOf("class", "class", "class"), firstThree.map { it.type })
        assertEquals(
            setOf(
                "net/minecraft/block/Block",
                "net/minecraft/block/BlockState",
                "net/minecraft/block/NewBlock",
            ),
            firstThree.mapNotNull { it.yarn }.toSet(),
        )
    }
}

class DiffServiceTest {

    private fun setup(@TempDir tmp: Path): DiffService {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        return DiffService(db)
    }

    private fun appConfig(tmp: Path, yarnRepo: Path) = AppConfig(
        databasePath = tmp.resolve("db.sqlite").toString(),
        sources = SourcesConfig(
            yarnRepo = yarnRepo.toString(),
            mojmapRepo = tmp.resolve("mojmap-repo").toString(),
            intermediaryMappings = tmp.toString(),
            artifactStore = tmp.resolve("artifact-store").toString(),
        ),
        indexing = IndexingConfig(0, emptyList(), false),
        search = SearchConfig(maxResults = 100, defaultResults = 20),
    )

    @Test
    fun `class diff detects added and removed`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.diff(from = "1.21", to = "1.21.1", namespace = "yarn", type = "class", packageFilter = null, changeType = "all", limit = 100)
        val addedNames = resp.changes.added.mapNotNull { it.name }.toSet()
        val removedNames = resp.changes.removed.mapNotNull { it.name }.toSet()
        assertTrue("net/minecraft/block/NewBlock" in addedNames, "added=$addedNames")
        assertTrue("net/minecraft/block/OldBlock" in removedNames, "removed=$removedNames")
        assertEquals(1, resp.summary.classesAdded)
        assertEquals(1, resp.summary.classesRemoved)
    }

    @Test
    fun `method diff detects rename across versions`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.diff("1.21", "1.21.1", "yarn", "method", null, "renamed", 100)
        val renamed = resp.changes.renamed.singleOrNull { it.type == "method" }
        assertNotNull(renamed, "expected one renamed method, got=${resp.changes.renamed}")
        assertEquals("getDefaultStateOld", renamed.oldName)
        assertEquals("getDefaultState", renamed.newName)
        assertEquals("method_9564", renamed.intermediary)
    }

    @Test
    fun `diff uses git backed source candidates when source files are not indexed`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        val yarnRepo = java.nio.file.Files.createDirectories(tmp.resolve("yarn-repo"))

        initGitSourceRepo(
            yarnRepo,
            Fixtures.V_1_21,
            mapOf(
                "net/minecraft/block/Block.java" to blockSource(changedReturn = 1, otherReturn = 3),
                "net/minecraft/block/BlockState.java" to "package net.minecraft.block; public class BlockState {}",
                "net/minecraft/block/OldBlock.java" to "package net.minecraft.block; public class OldBlock {}",
            ),
        )
        commitGitSourceTree(
            yarnRepo,
            Fixtures.V_1_21_1,
            mapOf(
                "net/minecraft/block/Block.java" to blockSource(changedReturn = 2, otherReturn = 3),
                "net/minecraft/block/BlockState.java" to "package net.minecraft.block; public class BlockState {}",
                "net/minecraft/block/NewBlock.java" to "package net.minecraft.block; public class NewBlock {}",
            ),
            removed = listOf("net/minecraft/block/OldBlock.java"),
        )

        val diff = DiffService(db, appConfig(tmp, yarnRepo)).diff(
            from = Fixtures.V_1_21,
            to = Fixtures.V_1_21_1,
            namespace = "yarn",
            type = "all",
            packageFilter = null,
            changeType = "all",
            limit = 100,
        )

        assertTrue(diff.changes.added.any { it.type == "class" && it.name == "net/minecraft/block/NewBlock" })
        assertTrue(diff.changes.removed.any { it.type == "class" && it.name == "net/minecraft/block/OldBlock" })
        val renamedMethod = diff.changes.renamed.singleOrNull { it.type == "method" }
        assertNotNull(renamedMethod)
        assertEquals("getDefaultStateOld", renamedMethod.oldName)
        assertEquals("getDefaultState", renamedMethod.newName)
    }

    @Test
    fun `unknown version returns empty diff`(@TempDir tmp: Path) {
        val s = setup(tmp)
        val resp = s.diff("0.0", "1.21.1", "yarn", "all", null, "all", 100)
        assertEquals(0, resp.summary.classesAdded + resp.summary.classesRemoved + resp.summary.classesRenamed)
    }

    @Test
    fun `file diff applies path prefix with exact added removed and modified files`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        transaction(db) {
            val fromVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21 }
                .single()[VersionTable.id].value
            val toVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21_1 }
                .single()[VersionTable.id].value

            insertSourceFile(fromVersion, "net/minecraft/block/Block.java", "from-block")
            insertSourceFile(toVersion, "net/minecraft/block/Block.java", "to-block")
            insertSourceFile(fromVersion, "net/minecraft/block/OldBlock.java", "old")
            insertSourceFile(toVersion, "net/minecraft/block/NewBlock.java", "new")
            insertSourceFile(fromVersion, "net/minecraft/item/Outside.java", "outside-from")
            insertSourceFile(toVersion, "net/minecraft/item/Outside.java", "outside-to")
        }

        val diff = DiffService(db).diffFiles(Fixtures.V_1_21, Fixtures.V_1_21_1, "yarn", "net/minecraft/block")

        assertEquals(listOf("net/minecraft/block/NewBlock.java"), diff.files.added)
        assertEquals(listOf("net/minecraft/block/OldBlock.java"), diff.files.removed)
        assertEquals(listOf("net/minecraft/block/Block.java"), diff.files.modified.map { it.path })
    }

    @Test
    fun `patch diff returns real unified file content changes`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        val yarnRepo = java.nio.file.Files.createDirectories(tmp.resolve("yarn-repo"))
        val relativePath = "net/minecraft/block/Block.java"
        initGitSourceRepo(yarnRepo, Fixtures.V_1_21, relativePath, blockSource(changedReturn = 1, otherReturn = 3))
        commitGitSourceVersion(yarnRepo, Fixtures.V_1_21_1, relativePath, blockSource(changedReturn = 2, otherReturn = 3))

        transaction(db) {
            val fromVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21 }
                .single()[VersionTable.id].value
            val toVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21_1 }
                .single()[VersionTable.id].value
            insertSourceFile(fromVersion, relativePath, "from-block")
            insertSourceFile(toVersion, relativePath, "to-block")
        }

        val diff = DiffService(db, appConfig(tmp, yarnRepo)).diffPatch(
            from = Fixtures.V_1_21,
            to = Fixtures.V_1_21_1,
            namespace = "yarn",
            pathPrefix = relativePath,
            functionFilter = null,
            contextLines = 3,
            limit = 100,
        )

        assertEquals(listOf(relativePath), diff.files.map { it.path })
        assertTrue(diff.patch.contains("diff --git a/$relativePath b/$relativePath"), diff.patch)
        assertTrue(diff.patch.contains("-        return 1;"), diff.patch)
        assertTrue(diff.patch.contains("+        return 2;"), diff.patch)
    }

    @Test
    fun `patch diff can be filtered to one function`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        val yarnRepo = java.nio.file.Files.createDirectories(tmp.resolve("yarn-repo"))
        val relativePath = "net/minecraft/block/Block.java"
        initGitSourceRepo(yarnRepo, Fixtures.V_1_21, relativePath, blockSource(changedReturn = 1, otherReturn = 3))
        commitGitSourceVersion(yarnRepo, Fixtures.V_1_21_1, relativePath, blockSource(changedReturn = 2, otherReturn = 4))

        transaction(db) {
            val fromVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21 }
                .single()[VersionTable.id].value
            val toVersion = VersionTable.selectAll()
                .where { VersionTable.versionId eq Fixtures.V_1_21_1 }
                .single()[VersionTable.id].value
            insertSourceFile(fromVersion, relativePath, "from-block")
            insertSourceFile(toVersion, relativePath, "to-block")
        }

        val diff = DiffService(db, appConfig(tmp, yarnRepo)).diffPatch(
            from = Fixtures.V_1_21,
            to = Fixtures.V_1_21_1,
            namespace = "yarn",
            pathPrefix = "net/minecraft/block",
            functionFilter = "Block#changed",
            contextLines = 1,
            limit = 100,
        )

        assertTrue(diff.patch.contains("public int changed()"), diff.patch)
        assertTrue(diff.patch.contains("+        return 2;"), diff.patch)
        assertFalse(diff.patch.contains("return 4;"), diff.patch)
        assertFalse(diff.patch.contains("return 3;"), diff.patch)
    }

    private fun insertSourceFile(versionRowId: Int, path: String, hash: String) {
        SourceFileTable.insert {
            it[versionId] = EntityID(versionRowId, VersionTable)
            it[mappingType] = "yarn"
            it[relativePath] = path
            it[contentHash] = hash
        }
    }

    private fun initGitSourceRepo(root: Path, tag: String, relativePath: String, content: String) {
        runGit(root, "init")
        runGit(root, "config", "user.email", "test@example.invalid")
        runGit(root, "config", "user.name", "MappingLens Test")
        commitGitSourceVersion(root, tag, relativePath, content)
    }

    private fun initGitSourceRepo(root: Path, tag: String, files: Map<String, String>) {
        runGit(root, "init")
        runGit(root, "config", "user.email", "test@example.invalid")
        runGit(root, "config", "user.name", "MappingLens Test")
        commitGitSourceTree(root, tag, files)
    }

    private fun commitGitSourceVersion(root: Path, tag: String, relativePath: String, content: String) {
        val file = root.resolve("minecraft/src").resolve(relativePath)
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, content)
        runGit(root, "add", "minecraft/src/$relativePath")
        runGit(root, "commit", "-m", tag)
        runGit(root, "tag", tag)
    }

    private fun commitGitSourceTree(root: Path, tag: String, files: Map<String, String>, removed: List<String> = emptyList()) {
        for (relativePath in removed) {
            java.nio.file.Files.deleteIfExists(root.resolve("minecraft/src").resolve(relativePath))
        }
        for ((relativePath, content) in files) {
            val file = root.resolve("minecraft/src").resolve(relativePath)
            java.nio.file.Files.createDirectories(file.parent)
            java.nio.file.Files.writeString(file, content)
        }
        runGit(root, "add", "-A", "minecraft/src")
        runGit(root, "commit", "-m", tag)
        runGit(root, "tag", tag)
    }

    private fun runGit(root: Path, vararg args: String) {
        val exit = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }

    private fun blockSource(changedReturn: Int, otherReturn: Int): String =
        """
        package net.minecraft.block;

        public class Block {
            public int changed() {
                return $changedReturn;
            }

            public int other() {
                return $otherReturn;
            }
        }
        """.trimIndent()
}

class BytecodeServiceTest {

    private fun appConfig(tmp: Path) = AppConfig(
        databasePath = tmp.resolve("db.sqlite").toString(),
        sources = SourcesConfig(
            yarnRepo = tmp.resolve("yarn-src").toString(),
            mojmapRepo = tmp.resolve("moj-src").toString(),
            intermediaryMappings = tmp.toString(),
            artifactStore = tmp.resolve("artifact-store").toString(),
        ),
        indexing = IndexingConfig(0, emptyList(), false),
        search = SearchConfig(maxResults = 100, defaultResults = 20),
    )

    @Test
    fun `source returns content from direct source root fallback`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val cfg = appConfig(tmp)
        val target = java.nio.file.Files.createDirectories(
            java.nio.file.Paths.get(cfg.sources.yarnRepo, "net", "minecraft", "block")
        ).resolve("BlockState.java")
        java.nio.file.Files.writeString(target, "package net.minecraft.block; public class BlockState {}")

        val r = BytecodeService(cfg, db).source("1.21.1", "net/minecraft/block/BlockState", "yarn")
        assertNotNull(r)
        assertTrue(r.source.contains("class BlockState"))
        assertEquals("yarn", r.namespace)
    }

    @Test
    fun `source returns versioned content from read only git repository root`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val repo = java.nio.file.Files.createDirectories(tmp.resolve("git-yarn"))
        initGitSourceRepo(repo, "1.21", "net/minecraft/block/BlockState.java", "package net.minecraft.block; public class OldBlockState {}")
        commitGitSourceVersion(repo, "1.21.1", "net/minecraft/block/BlockState.java", "package net.minecraft.block; public class BlockState {}")
        val cfg = appConfig(tmp).copy(sources = appConfig(tmp).sources.copy(yarnRepo = repo.toString()))

        val r = BytecodeService(cfg, db).source("1.21.1", "net/minecraft/block/BlockState", "yarn")

        assertNotNull(r)
        assertEquals("net/minecraft/block/BlockState.java", r.path)
        assertTrue(r.source.contains("class BlockState"))
        assertFalse(r.source.contains("OldBlockState"))
    }

    @Test
    fun `source reads decompiled source jar from artifact-store root`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val artifactStore = java.nio.file.Files.createDirectories(tmp.resolve("artifact-store"))
        val decompiled = java.nio.file.Files.createDirectories(artifactStore.resolve("decompiled/1.21.1"))
        JarOutputStream(java.nio.file.Files.newOutputStream(decompiled.resolve("merged-map_yarn-test.jar"))).use { output ->
            output.putNextEntry(ZipEntry("net/minecraft/block/BlockState.java"))
            output.write("package net.minecraft.block; public class BlockState {}".toByteArray())
            output.closeEntry()
        }
        val cfg = appConfig(tmp).copy(
            sources = appConfig(tmp).sources.copy(
                yarnRepo = tmp.resolve("missing-yarn-src").toString(),
                artifactStore = artifactStore.toString(),
            ),
        )

        val r = BytecodeService(cfg, db).source("1.21.1", "net/minecraft/block/BlockState", "yarn")

        assertNotNull(r)
        assertEquals("net/minecraft/block/BlockState.java", r.path)
        assertTrue(r.source.contains("public class BlockState"))
    }

    @Test
    fun `source returns null when file missing`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        assertNull(BytecodeService(appConfig(tmp), db).source("1.21.1", "net/minecraft/Nope", "yarn"))
    }

    @Test
    fun `bytecode returns null when jar is absent`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        assertNull(BytecodeService(appConfig(tmp), db).bytecode("1.21.1", "net/minecraft/block/BlockState", "yarn"))
    }

    private fun initGitSourceRepo(root: Path, tag: String, relativePath: String, content: String) {
        runGit(root, "init")
        runGit(root, "config", "user.email", "test@example.invalid")
        runGit(root, "config", "user.name", "MappingLens Test")
        commitGitSourceVersion(root, tag, relativePath, content)
    }

    private fun commitGitSourceVersion(root: Path, tag: String, relativePath: String, content: String) {
        val file = root.resolve("minecraft/src").resolve(relativePath)
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, content)
        runGit(root, "add", "minecraft/src/$relativePath")
        runGit(root, "commit", "-m", tag)
        runGit(root, "tag", tag)
    }

    private fun runGit(root: Path, vararg args: String) {
        val exit = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        assertEquals(0, exit, "git ${args.joinToString(" ")} failed")
    }
}
