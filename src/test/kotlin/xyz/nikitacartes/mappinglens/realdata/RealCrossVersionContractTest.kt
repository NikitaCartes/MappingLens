package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.ingestion.CorrespondenceResolver
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.ingestion.JarAnalyzer
import xyz.nikitacartes.mappinglens.ingestion.TinyV2Parser
import xyz.nikitacartes.mappinglens.ingestion.UnifiedClassEntry
import xyz.nikitacartes.mappinglens.model.BytecodeResponse
import xyz.nikitacartes.mappinglens.model.DiffResponse
import xyz.nikitacartes.mappinglens.model.FileDiffResponse
import xyz.nikitacartes.mappinglens.model.SearchResponse
import xyz.nikitacartes.mappinglens.model.SourceResponse
import xyz.nikitacartes.mappinglens.model.TranslateResponse
import xyz.nikitacartes.mappinglens.model.VersionListResponse
import xyz.nikitacartes.mappinglens.routes.bytecodeRoutes
import xyz.nikitacartes.mappinglens.routes.diffRoutes
import xyz.nikitacartes.mappinglens.routes.searchRoutes
import xyz.nikitacartes.mappinglens.routes.translationRoutes
import xyz.nikitacartes.mappinglens.routes.versionRoutes
import xyz.nikitacartes.mappinglens.service.BytecodeService
import xyz.nikitacartes.mappinglens.service.DiffService
import xyz.nikitacartes.mappinglens.service.SearchService
import xyz.nikitacartes.mappinglens.service.TranslationService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerCN
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import xyz.nikitacartes.mappinglens.ingestion.UnifiedMemberEntry
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract test that ingests two real Minecraft versions end-to-end and verifies every
 * documented endpoint against expected values computed directly from the raw repositories
 * (tiny mapping files + decompiled source jars + merged class jars). The "expected" data is
 * not hard-coded — it is derived from the very same files the pipeline reads, but through an
 * independent code path inside the test itself.
 *
 * Covers: search (with and without version), diff (mappings), diff (files/folders),
 * yarn <-> mojmap correspondence and bytecode retrieval.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealCrossVersionContractTest {

    private val from = RealDataTestConfig.V_1_21
    private val to = RealDataTestConfig.V_1_21_1

    private lateinit var tmp: Path
    private lateinit var db: Database
    private lateinit var config: AppConfig

    private lateinit var unifiedFrom: List<UnifiedClassEntry>
    private lateinit var unifiedTo: List<UnifiedClassEntry>

    @BeforeAll
    fun ingestRealData() {
        RealDataTestConfig.assumeAvailable()
        tmp = Files.createTempDirectory("ml-cross-version-")
        val dbPath = tmp.resolve("ml.db")
        db = DatabaseFactory.init(dbPath.toString())
        config = RealDataTestConfig.appConfig(dbPath, initialVersions = listOf(from, to))
        IngestPipeline(config).run()

        unifiedFrom = resolveUnified(from)
        unifiedTo = resolveUnified(to)
    }

    private fun resolveUnified(version: String): List<UnifiedClassEntry> {
        val intermediary = TinyV2Parser.parse(RealDataTestConfig.intermediaryMappingFor(version))
        val yarn = TinyV2Parser.parse(RealDataTestConfig.yarnMappingFor(version))
        val mojmap = TinyV2Parser.parse(RealDataTestConfig.mojmapMappingFor(version))
        return CorrespondenceResolver.resolve(intermediary, yarn, mojmap)
    }

    private fun Application.installRoutes() {
        install(ServerCN) { json(Json { ignoreUnknownKeys = true }) }
        val versionService = VersionService(db)
        routing {
            versionRoutes(versionService)
            translationRoutes(TranslationService(db, versionService))
            searchRoutes(SearchService(db, versionService, Fixtures.dbPath(db)), config)
            diffRoutes(DiffService(db))
            bytecodeRoutes(BytecodeService(config, db))
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // -------- /api/v1/versions --------

    @Test
    fun `versions endpoint reports both ingested versions with non-zero counts`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val resp = client.get("/api/v1/versions").body<VersionListResponse>()
        val byId = resp.versions.associateBy { it.id }
        assertEquals(setOf(from, to), byId.keys, "Expected both real versions to be indexed")

        assertEquals(unifiedFrom.size.toLong(), byId.getValue(from).classCount, "Class count for $from must match resolved unified mappings")
        assertEquals(unifiedTo.size.toLong(), byId.getValue(to).classCount, "Class count for $to must match resolved unified mappings")
        assertEquals(unifiedFrom.sumOf { it.methods.size }.toLong(), byId.getValue(from).methodCount)
        assertEquals(unifiedTo.sumOf { it.methods.size }.toLong(), byId.getValue(to).methodCount)
        assertEquals(unifiedFrom.sumOf { it.fields.size }.toLong(), byId.getValue(from).fieldCount)
        assertEquals(unifiedTo.sumOf { it.fields.size }.toLong(), byId.getValue(to).fieldCount)
    }

    // -------- /api/v1/translate (yarn <-> mojmap correspondence) --------

    @Test
    fun `translate endpoint returns the yarn-mojmap-intermediary chain derived from tiny files`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        // Pick concrete classes whose chains we re-derive directly from the tiny files.
        val sampleIntermediaries = listOf(
            "net/minecraft/class_2248", // Block / BlockState owner
            "net/minecraft/class_2680", // BlockState
            "net/minecraft/class_1799", // ItemStack
            "net/minecraft/class_1297", // Entity
            "net/minecraft/class_3218", // ServerLevel / ServerWorld
        )

        val byInter = unifiedTo.filter { it.intermediaryName != null }.associateBy { it.intermediaryName!! }
        for (interm in sampleIntermediaries) {
            val expected = byInter[interm]
            assertNotNull(expected, "Missing expected unified entry for $interm in $to")
            assertNotNull(expected.yarnName, "Expected yarn name for $interm")
            assertNotNull(expected.mojmapName, "Expected mojmap name for $interm")

            // yarn -> mojmap
            val toMoj = client.get("/api/v1/translate") {
                url {
                    parameters.append("name", expected.yarnName!!)
                    parameters.append("from", "yarn")
                    parameters.append("to", "mojmap")
                    parameters.append("version", to)
                    parameters.append("type", "class")
                }
            }
            assertEquals(HttpStatusCode.OK, toMoj.status, "translate yarn->mojmap failed for ${expected.yarnName}")
            val toMojBody = toMoj.body<TranslateResponse>()
            assertEquals(expected.mojmapName, toMojBody.output.name)
            assertEquals(expected.intermediaryName, toMojBody.intermediary)
            assertEquals(expected.obfName, toMojBody.obfuscated)

            // mojmap -> yarn (reverse correspondence)
            val toYarn = client.get("/api/v1/translate") {
                url {
                    parameters.append("name", expected.mojmapName!!)
                    parameters.append("from", "mojmap")
                    parameters.append("to", "yarn")
                    parameters.append("version", to)
                    parameters.append("type", "class")
                }
            }.body<TranslateResponse>()
            assertEquals(expected.yarnName, toYarn.output.name)
            assertEquals(expected.intermediaryName, toYarn.intermediary)
        }
    }

    @Test
    fun `translate endpoint returns method correspondence for Block_getDefaultState in both versions`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        for (version in listOf(from, to)) {
            val unified = if (version == from) unifiedFrom else unifiedTo
            val block = unified.single { it.intermediaryName == "net/minecraft/class_2248" }
            val defaultStateMethod = block.methods.single { it.intermediaryName == "method_9564" && it.intermediaryDesc == "()Lnet/minecraft/class_2680;" }
            assertNotNull(defaultStateMethod.yarnName)
            assertNotNull(defaultStateMethod.mojmapName)

            val resp = client.get("/api/v1/translate") {
                url {
                    parameters.append("name", "${block.yarnName}#${defaultStateMethod.yarnName}")
                    parameters.append("from", "yarn")
                    parameters.append("to", "mojmap")
                    parameters.append("version", version)
                    parameters.append("type", "method")
                }
            }
            assertEquals(HttpStatusCode.OK, resp.status, "translate method failed for $version")
            val body = resp.body<TranslateResponse>()
            assertEquals("${block.mojmapName}#${defaultStateMethod.mojmapName}", body.output.name)
            assertEquals("${block.intermediaryName}#${defaultStateMethod.intermediaryName}", body.intermediary)
        }
    }

    // -------- /api/v1/search --------

    @Test
    fun `search endpoint finds a class by yarn simple name limited to the requested version`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        // Use BlockState as a stable, well-known sample whose yarn name we already resolved.
        val expected = unifiedTo.single { it.intermediaryName == "net/minecraft/class_2680" }
        val response = client.get("/api/v1/search") {
            url {
                parameters.append("q", "BlockState")
                parameters.append("version", to)
                parameters.append("type", "class")
                parameters.append("namespace", "yarn")
                parameters.append("limit", "50")
            }
        }.body<SearchResponse>()

        assertEquals(to, response.version)
        val hit = response.results.singleOrNull { it.yarn == expected.yarnName }
        assertNotNull(hit, "BlockState hit missing in yarn search for $to: ${response.results.map { it.yarn }}")
        assertEquals("class", hit.type)
        assertEquals(expected.mojmapName, hit.mojmap)
        assertEquals(expected.intermediaryName, hit.intermediary)
        assertEquals(expected.obfName, hit.obfuscated)
    }

    @Test
    fun `search endpoint scoped to a single version does not leak results from another version`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        // The obfuscated name `dfy` belongs to Block in 1.21 / 1.21.1 only — neither older nor newer.
        // We query strictly within one version and assert results belong to it.
        val block = unifiedTo.single { it.intermediaryName == "net/minecraft/class_2248" }
        val response = client.get("/api/v1/search") {
            url {
                parameters.append("q", block.obfName!!)
                parameters.append("version", to)
                parameters.append("type", "class")
                parameters.append("namespace", "all")
                parameters.append("exact", "false")
            }
        }.body<SearchResponse>()

        assertEquals(to, response.version)
        assertTrue(response.results.isNotEmpty(), "expected at least one search hit for obf ${block.obfName} in $to")
        for (hit in response.results) {
            assertEquals("class", hit.type)
        }
        assertTrue(response.results.any { it.intermediary == block.intermediaryName })
    }

    @Test
    fun `search endpoint finds a method by mojmap simple name in the right version`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val block = unifiedTo.single { it.intermediaryName == "net/minecraft/class_2248" }
        val method = block.methods.single { it.intermediaryName == "method_9564" && it.intermediaryDesc == "()Lnet/minecraft/class_2680;" }
        val mojName = method.mojmapName!!

        val response = client.get("/api/v1/search") {
            url {
                parameters.append("q", mojName)
                parameters.append("version", to)
                parameters.append("type", "method")
                parameters.append("namespace", "mojmap")
                parameters.append("exact", "true")
                parameters.append("limit", "50")
            }
        }.body<SearchResponse>()

        assertTrue(
            response.results.any { it.mojmap == "${block.mojmapName}#$mojName" },
            "Expected mojmap method ${block.mojmapName}#$mojName to be returned by search: ${response.results.map { it.mojmap }}",
        )
    }

    // -------- /api/v1/diff (mappings diff between versions) --------

    @Test
    fun `mapping diff summary equals the rename count derived directly from the tiny mappings`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val expected = expectedRenameCounts(unifiedFrom, unifiedTo, namespace = "yarn")
        val diff = client.get("/api/v1/diff") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
                parameters.append("namespace", "yarn")
                parameters.append("type", "all")
                parameters.append("changeType", "all")
                parameters.append("limit", "5000")
            }
        }.body<DiffResponse>()

        assertEquals(from, diff.from)
        assertEquals(to, diff.to)
        assertEquals(expected.classesRenamed, diff.summary.classesRenamed, "yarn class renames diverged")
        assertEquals(expected.classesAdded, diff.summary.classesAdded, "class additions diverged")
        assertEquals(expected.classesRemoved, diff.summary.classesRemoved, "class removals diverged")
        assertEquals(expected.methodsRenamed, diff.summary.methodsRenamed, "yarn method renames diverged")
        assertEquals(expected.fieldsRenamed, diff.summary.fieldsRenamed, "yarn field renames diverged")

        // The renamed list returned by the endpoint must be a subset (limit applied) of the
        // independently computed rename set, keyed by intermediary name.
        val expectedRenamedClassInters = expected.renamedClassIntermediaries
        for (entry in diff.changes.renamed.filter { it.type == "class" }) {
            assertTrue(
                entry.intermediary in expectedRenamedClassInters,
                "Endpoint reported class rename for intermediary ${entry.intermediary} that was not detected by independent resolution",
            )
        }
    }

    @Test
    fun `mapping diff filtered by package only returns entries below that package`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val diff = client.get("/api/v1/diff") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
                parameters.append("namespace", "yarn")
                parameters.append("type", "class")
                parameters.append("changeType", "all")
                parameters.append("package", "net/minecraft/block")
                parameters.append("limit", "5000")
            }
        }.body<DiffResponse>()

        fun allow(name: String?) = name == null || name.startsWith("net/minecraft/block")
        for (entry in diff.changes.added + diff.changes.removed) {
            assertTrue(allow(entry.name), "package filter leaked: ${entry.name}")
        }
        for (entry in diff.changes.renamed) {
            assertTrue(allow(entry.oldName) || allow(entry.newName), "package filter leaked: ${entry.oldName} -> ${entry.newName}")
        }
    }

    // -------- /api/v1/diff/files (folder/file diff between versions) --------

    @Test
    fun `file diff for yarn namespace matches an independent scan of the decompiled source jars`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val fromJar = config.sources.decompiledSourceJar(from, "yarn")
            ?: error("Missing decompiled yarn jar for $from")
        val toJar = config.sources.decompiledSourceJar(to, "yarn")
            ?: error("Missing decompiled yarn jar for $to")
        val fromFiles = jarFileHashes(fromJar)
        val toFiles = jarFileHashes(toJar)

        val pathPrefix = "net/minecraft/world"
        val expectedAdded = (toFiles.keys - fromFiles.keys).filter { it.startsWith(pathPrefix) }.sorted()
        val expectedRemoved = (fromFiles.keys - toFiles.keys).filter { it.startsWith(pathPrefix) }.sorted()
        val expectedModified = (fromFiles.keys intersect toFiles.keys)
            .filter { it.startsWith(pathPrefix) }
            .filter { fromFiles.getValue(it) != toFiles.getValue(it) }
            .sorted()

        val response = client.get("/api/v1/diff/files") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
                parameters.append("namespace", "yarn")
                parameters.append("path", pathPrefix)
            }
        }.body<FileDiffResponse>()

        assertEquals(expectedAdded, response.files.added, "added file set diverged from raw jar scan")
        assertEquals(expectedRemoved, response.files.removed, "removed file set diverged from raw jar scan")
        assertEquals(expectedModified, response.files.modified.map { it.path }.sorted(), "modified file set diverged from raw jar scan")
    }

    // -------- /api/v1/bytecode --------

    @Test
    fun `bytecode endpoint matches a direct ASM Textifier disassembly of the merged jar`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val block = unifiedTo.single { it.intermediaryName == "net/minecraft/class_2248" }
        val obf = block.obfName!!

        val jarDir = RealDataTestConfig.jarDirFor(to)
        val mergedJar = Files.list(jarDir).use { stream ->
            stream.filter { p -> p.fileName.toString().startsWith("merged-") && p.fileName.toString().endsWith(".jar") }
                .toList()
                .firstOrNull()
        } ?: error("Missing merged jar for $to under $jarDir")

        val expectedObfText = JarAnalyzer.disassembleText(mergedJar, obf, null)
            ?: error("ASM disassembly returned null for obf $obf in $mergedJar")

        // Obfuscated namespace: the API skips the remapper and must match byte-for-byte.
        val obfResponse = client.get("/api/v1/bytecode/$to/$obf") {
            url { parameters.append("namespace", "obfuscated") }
        }
        assertEquals(HttpStatusCode.OK, obfResponse.status)
        val obfBody = obfResponse.body<BytecodeResponse>()
        assertEquals(to, obfBody.version)
        assertEquals(obf, obfBody.`class`)
        assertEquals(expectedObfText, obfBody.bytecode, "obfuscated bytecode disassembly diverged from raw ASM Textifier output")

        // Yarn namespace: the API applies a remapper. Class header line must now mention the yarn name.
        val yarnResponse = client.get("/api/v1/bytecode/$to/${block.yarnName}") {
            url { parameters.append("namespace", "yarn") }
        }
        assertEquals(HttpStatusCode.OK, yarnResponse.status)
        val yarnBody = yarnResponse.body<BytecodeResponse>()
        assertTrue(yarnBody.bytecode.contains(block.yarnName!!), "yarn-remapped bytecode should mention ${block.yarnName}")
        assertTrue(yarnBody.bytecode.contains("class version"), "expected ASM Textifier 'class version' marker in yarn bytecode")
    }

    // -------- /api/v1/source --------

    @Test
    fun `source endpoint returns the exact content stored inside the decompiled source jar`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        val block = unifiedTo.single { it.intermediaryName == "net/minecraft/class_2680" }
        val yarnJar = config.sources.decompiledSourceJar(to, "yarn") ?: error("Missing decompiled yarn jar for $to")

        val expected = ZipFile(yarnJar.toFile()).use { zip ->
            val entryName = "${block.yarnName}.java"
            val entry = zip.getEntry(entryName) ?: error("Missing $entryName in $yarnJar")
            zip.getInputStream(entry).bufferedReader().use { it.readText() }
        }

        val response = client.get("/api/v1/source/$to/${block.yarnName}") {
            url { parameters.append("namespace", "yarn") }
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<SourceResponse>()
        assertEquals(block.yarnName, body.`class`)
        assertEquals(expected, body.source, "source endpoint diverged from the raw decompiled jar entry")
    }

    // -------- expected-value helpers (re-implementation of join logic for ground truth) --------

    private data class ExpectedDiff(
        val classesAdded: Int,
        val classesRemoved: Int,
        val classesRenamed: Int,
        val methodsRenamed: Int,
        val fieldsRenamed: Int,
        val renamedClassIntermediaries: Set<String>,
    )

    private fun expectedRenameCounts(
        from: List<UnifiedClassEntry>,
        to: List<UnifiedClassEntry>,
        namespace: String,
    ): ExpectedDiff {
        val fromByInter = from.filter { it.intermediaryName != null }.associateBy { it.intermediaryName!! }
        val toByInter = to.filter { it.intermediaryName != null }.associateBy { it.intermediaryName!! }

        val added = toByInter.keys - fromByInter.keys
        val removed = fromByInter.keys - toByInter.keys
        val renamedClasses = (fromByInter.keys intersect toByInter.keys).filter { interm ->
            val a = fromByInter.getValue(interm)
            val b = toByInter.getValue(interm)
            classNameInNamespace(a, namespace).orEmpty() != classNameInNamespace(b, namespace).orEmpty()
        }.toSet()

        var renamedMethods = 0
        var renamedFields = 0
        for (interm in fromByInter.keys intersect toByInter.keys) {
            val a = fromByInter.getValue(interm)
            val b = toByInter.getValue(interm)
            renamedMethods += countMemberRenames(a.methods, b.methods, namespace) { it.intermediaryName to it.intermediaryDesc }
            renamedFields += countMemberRenames(a.fields, b.fields, namespace) { it.intermediaryName to it.intermediaryDesc }
        }

        return ExpectedDiff(
            classesAdded = added.size,
            classesRemoved = removed.size,
            classesRenamed = renamedClasses.size,
            methodsRenamed = renamedMethods,
            fieldsRenamed = renamedFields,
            renamedClassIntermediaries = renamedClasses,
        )
    }

    private fun classNameInNamespace(cls: UnifiedClassEntry, namespace: String): String? = when (namespace) {
        "mojmap" -> cls.mojmapName
        "intermediary" -> cls.intermediaryName
        else -> cls.yarnName
    }

    private fun <T> countMemberRenames(
        from: List<T>,
        to: List<T>,
        namespace: String,
        keyOf: (T) -> Pair<String?, String?>,
    ): Int {
        val nameOf: (T) -> String? = { m ->
            when (m) {
                is UnifiedMemberEntry -> when (namespace) {
                    "mojmap" -> m.mojmapName
                    "intermediary" -> m.intermediaryName
                    else -> m.yarnName
                }
                else -> null
            }
        }
        val fromByKey = from
            .filter { keyOf(it).first != null && keyOf(it).second != null }
            .associateBy { keyOf(it) }
        val toByKey = to
            .filter { keyOf(it).first != null && keyOf(it).second != null }
            .associateBy { keyOf(it) }
        var renamed = 0
        for (key in fromByKey.keys intersect toByKey.keys) {
            val a = nameOf(fromByKey.getValue(key)).orEmpty()
            val b = nameOf(toByKey.getValue(key)).orEmpty()
            if (a != b) renamed++
        }
        return renamed
    }

    private fun jarFileHashes(jar: Path): Map<String, String> {
        val out = HashMap<String, String>()
        ZipFile(jar.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".java")) continue
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                out[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        return out
    }
}
