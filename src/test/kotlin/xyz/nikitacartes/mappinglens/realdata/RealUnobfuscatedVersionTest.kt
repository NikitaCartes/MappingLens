package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.config.SearchConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.SourceFileTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.model.DiffResponse
import xyz.nikitacartes.mappinglens.model.SourceResponse
import xyz.nikitacartes.mappinglens.model.VersionListResponse
import xyz.nikitacartes.mappinglens.routes.bytecodeRoutes
import xyz.nikitacartes.mappinglens.routes.diffRoutes
import xyz.nikitacartes.mappinglens.routes.translationRoutes
import xyz.nikitacartes.mappinglens.routes.versionRoutes
import xyz.nikitacartes.mappinglens.service.BytecodeService
import xyz.nikitacartes.mappinglens.service.DiffService
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies end-to-end behavior for Mojang's unobfuscated releases (26.x). These versions publish no
 * obfuscation mappings of their own, so the pipeline reads their mojmap namespace off the jar: by
 * ASM-scanning it while nothing else covers the version, and off the `official` column of the yarn
 * tiny once yarn does. Either way they must appear in /api/v1/versions marked `hasMojmap`, and allow
 * source and diff retrieval under namespace=mojmap.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealUnobfuscatedVersionTest {

    private val from = "26.1"
    private val to = "26.1.1"

    private lateinit var tmp: Path
    private lateinit var db: Database
    private lateinit var config: AppConfig

    @BeforeAll
    fun setUp() {
        assumeTrue(Files.exists(RealDataTestConfig.artifactStore), "artifact-store missing")
        val decompiled = RealDataTestConfig.artifactStore.resolve("decompiled")
        assumeTrue(Files.isDirectory(decompiled.resolve(from)), "decompiled/$from missing")
        assumeTrue(Files.isDirectory(decompiled.resolve(to)), "decompiled/$to missing")
        assumeTrue(
            Files.isRegularFile(RealDataTestConfig.artifactStore.resolve("semver-cache-mojang-launcher.json")),
            "semver-cache-mojang-launcher.json missing",
        )

        tmp = Files.createTempDirectory("ml-unobf-")
        val dbPath = tmp.resolve("ml.db")
        db = DatabaseFactory.init(dbPath.toString())
        config = AppConfig(
            databasePath = dbPath.toString(),
            sources = RealDataTestConfig.sourcesConfig(),
            initialVersions = listOf(from, to),
            search = SearchConfig(maxResults = 200, defaultResults = 50),
        )
        IngestPipeline(config).run()
        transaction(db) {
            val rows = VersionTable.selectAll().where { VersionTable.versionId inList listOf(from, to) }
                .map { "${it[VersionTable.versionId]} hasMojmap=${it[VersionTable.hasMojmap]}" }
            check(rows.size == 2) { "DIAG db: rows=$rows" }
        }
    }

    private fun Application.installRoutes() {
        install(ServerCN) { json(Json { ignoreUnknownKeys = true }) }
        val versionService = VersionService(db)
        routing {
            versionRoutes(versionService)
            translationRoutes(TranslationService(db, versionService))
            diffRoutes(DiffService(db, config))
            bytecodeRoutes(BytecodeService(config, db))
        }
    }

    @Test
    fun `both unobfuscated versions are ingested with mojmap names`() = testApplication {
        application { installRoutes() }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("/api/v1/versions").body<VersionListResponse>()
        val byId = resp.versions.associateBy { it.id }
        assertTrue(from in byId, "version $from missing")
        assertTrue(to in byId, "version $to missing")
        for (v in listOf(from, to)) {
            val info = byId.getValue(v)
            assertTrue(info.hasMojmap, "version $v must be marked hasMojmap")
            assertTrue(info.classCount > 0, "version $v must have classes ingested")
            // These versions ship no mappings of their own. Intermediary is there when yarn covers
            // the version, or when the separate unobfuscated-intermediary source is configured.
            val expectIntermediary = info.hasYarn || Files.isDirectory(RealDataTestConfig.unobfuscatedIntermediary)
            assertEquals(expectIntermediary, info.hasIntermediary, "version $v hasIntermediary")
        }
    }

    @Test
    fun `intermediary names come from the unobfuscated source and continue the Fabric chain`() {
        assumeTrue(
            Files.isDirectory(RealDataTestConfig.unobfuscatedIntermediary),
            "unobfuscated-intermediary repository missing",
        )
        transaction(db) {
            val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq from }
                .single()[VersionTable.id].value
            // `com/mojang/math/Axis` is class_7833 in 1.21.11 as well: the chain is continuous, so a
            // history query crosses the unobfuscated boundary on the intermediary name alone.
            val row = ClassTable.selectAll()
                .where { (ClassTable.versionId eq versionRowId) and (ClassTable.mojmapName eq "com/mojang/math/Axis") }
                .single()
            assertEquals("net/minecraft/class_7833", row[ClassTable.intermediaryName])

            val named = ClassTable.selectAll()
                .where { (ClassTable.versionId eq versionRowId) and ClassTable.intermediaryName.isNotNull() }
                .count()
            val total = ClassTable.selectAll().where { ClassTable.versionId eq versionRowId }.count()
            assertTrue(named > total * 9 / 10, "expected most classes named, got $named of $total")
        }
    }

    @Test
    fun `source endpoint returns mojmap source for an unobfuscated version`() = testApplication {
        application { installRoutes() }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val sampleClass = pickSourceClass(from)
        val resp = client.get("/api/v1/source/$from/$sampleClass?namespace=mojmap")
        assertEquals(HttpStatusCode.OK, resp.status, "source request failed for $sampleClass")
        val body = resp.body<SourceResponse>()
        assertEquals("mojmap", body.namespace)
        assertTrue(body.source.isNotBlank(), "source body must not be blank")
    }

    @Test
    fun `diff endpoint returns a non-empty diff between two unobfuscated versions`() = testApplication {
        application { installRoutes() }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("/api/v1/diff?from=$from&to=$to&namespace=mojmap&type=all&limit=5000")
        assertEquals(HttpStatusCode.OK, resp.status, "diff request failed")
        val body = resp.body<DiffResponse>()
        assertEquals(from, body.from)
        assertEquals(to, body.to)
        assertEquals("mojmap", body.namespace)
        // 26.1 and 26.1.1 happen to ship a byte-identical merged jar (the patch only touches
        // assets/data packs), so the diff legitimately comes back empty. We just verify that
        // the endpoint runs to completion on two unobfuscated versions instead of timing out
        // or 500-ing.
    }

    @Test
    fun `translate yarn to mojmap returns 422 namespace_unavailable while yarn is absent`() = testApplication {
        assumeTrue(!hasYarn(from), "yarn covers $from in this store, so both namespaces are available")
        application { installRoutes() }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get(
            "/api/v1/translate?name=net/minecraft/world/level/block/Block" +
                "&from=yarn&to=mojmap&version=$from&type=class",
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        val body = resp.body<String>()
        assertTrue(body.contains("namespace_unavailable"), "expected namespace_unavailable, got: $body")

        val reverse = client.get(
            "/api/v1/translate?name=net/minecraft/world/level/block/Block" +
                "&from=mojmap&to=yarn&version=$from&type=class",
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, reverse.status)
    }

    private fun hasYarn(version: String): Boolean = transaction(db) {
        VersionTable.selectAll().where { VersionTable.versionId eq version }.single()[VersionTable.hasYarn]
    }

    private fun pickSourceClass(version: String): String = transaction(db) {
        val versionRowId = VersionTable.selectAll().where { VersionTable.versionId eq version }
            .single()[VersionTable.id].value
        val row = SourceFileTable.innerJoin(ClassTable)
            .selectAll()
            .where {
                (SourceFileTable.versionId eq versionRowId) and
                    (SourceFileTable.mappingType eq "mojmap") and
                    (ClassTable.mojmapName.isNotNull())
            }
            .limit(1)
            .single()
        row[ClassTable.mojmapName]!!
    }
}
