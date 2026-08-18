package xyz.nikitacartes.mappinglens.realdata

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.db.DatabaseFactory
import xyz.nikitacartes.mappinglens.ingestion.IngestPipeline
import xyz.nikitacartes.mappinglens.model.DiffEntryItem
import xyz.nikitacartes.mappinglens.model.DiffResponse
import xyz.nikitacartes.mappinglens.routes.diffRoutes
import xyz.nikitacartes.mappinglens.service.DiffService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How `/api/v1/diff` pairs one member across two versions, over two versions whose obfuscation
 * differs. [RealCrossVersionContractTest] runs on 1.21 to 1.21.1, which share their obfuscated
 * names, so neither defect below shows there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealDiffKeyTest {

    private val from = RealDataTestConfig.V_1_20_6
    private val to = RealDataTestConfig.V_1_21

    private lateinit var db: Database

    @BeforeAll
    fun ingestRealData() {
        RealDataTestConfig.assumeAvailable()
        val dbPath = Files.createTempDirectory("ml-diff-key-").resolve("ml.db")
        db = DatabaseFactory.init(dbPath.toString())
        IngestPipeline(RealDataTestConfig.appConfig(dbPath, initialVersions = listOf(from, to))).run()
    }

    private fun Application.installRoutes() {
        install(ServerCN) { json(Json { ignoreUnknownKeys = true }) }
        routing { diffRoutes(DiffService(db)) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /**
     * An override carries no intermediary name, because the tiny files name a method only in the
     * class that first declares it. Keying the diff on the official descriptor gave such a member a
     * different key in each version, and it came back under `added` and `removed` at the same time —
     * 33 of them on this class alone. `stable_desc` reads the same in both versions.
     */
    @Test
    fun `no member is reported added and removed at the same time`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        for ((namespace, className) in listOf("mojmap" to SAMPLE_MOJMAP_CLASS, "yarn" to SAMPLE_YARN_CLASS)) {
            val diff = client.get("/api/v1/diff") {
                url {
                    parameters.append("from", from)
                    parameters.append("to", to)
                    parameters.append("namespace", namespace)
                    parameters.append("class", className)
                    parameters.append("type", "all")
                    parameters.append("limit", "5000")
                }
            }.body<DiffResponse>()

            fun names(items: List<DiffEntryItem>) = items.mapNotNull { it.name }.toSet()
            val both = names(diff.changes.added) intersect names(diff.changes.removed)
            assertTrue(both.isEmpty(), "$namespace: reported as added and removed at once: $both")
        }
    }

    /**
     * `package` names a package in the namespace the caller asked about. It used to be matched
     * against a path cut from the yarn name, so a mojmap package answered an empty diff while the
     * same change was visible without the filter.
     */
    @Test
    fun `package filter answers in the namespace it was given`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        suspend fun diffOf(namespace: String, pkg: String?) = client.get("/api/v1/diff") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
                parameters.append("namespace", namespace)
                parameters.append("type", "method")
                parameters.append("limit", "5000")
                if (pkg != null) parameters.append("package", pkg)
            }
        }.body<DiffResponse>()

        for (namespace in listOf("mojmap", "yarn")) {
            // A package that really changed, taken from the unfiltered diff rather than guessed.
            val owner = diffOf(namespace, null).changes.added.firstNotNullOfOrNull { it.owner }
            assertNotNull(owner, "$namespace: no method additions to derive a package from")
            val pkg = owner.substringBeforeLast('/')

            val filtered = diffOf(namespace, pkg).changes.added
            assertTrue(filtered.isNotEmpty(), "$namespace: package $pkg answered empty")
            assertTrue(
                filtered.all { it.owner?.startsWith("$pkg/") == true },
                "$namespace: package $pkg leaked entries from another package",
            )
        }
    }

    /**
     * `class=` answers from Kotlin and `package=` from SQL, so the two build the member key
     * separately. The SQL side keyed on `intermediary_name` alone, which reads NULL on an override,
     * and every such member fell to a per-row sentinel: added and removed at once, 1529 of them in
     * one package. Both paths must report the same members for the same class.
     */
    @Test
    fun `the class path and the package path agree on one class`() = testApplication {
        application { installRoutes() }
        val client = jsonClient()

        suspend fun diff(vararg extra: Pair<String, String>) = client.get("/api/v1/diff") {
            url {
                parameters.append("from", from)
                parameters.append("to", to)
                parameters.append("namespace", "mojmap")
                parameters.append("type", "method")
                parameters.append("limit", "5000")
                extra.forEach { (k, v) -> parameters.append(k, v) }
            }
        }.body<DiffResponse>()

        val byClass = diff("class" to SAMPLE_MOJMAP_CLASS).changes
        val byPackage = diff("package" to SAMPLE_MOJMAP_CLASS.substringBeforeLast('/')).changes
        fun ofClass(items: List<DiffEntryItem>) =
            items.filter { it.owner == SAMPLE_MOJMAP_CLASS }.mapNotNull { it.name }.toSet()

        assertEquals(ofClass(byClass.added), ofClass(byPackage.added), "added differ between the two paths")
        assertEquals(ofClass(byClass.removed), ofClass(byPackage.removed), "removed differ between the two paths")

        fun renames(items: List<DiffEntryItem>) =
            items.filter { it.owner == SAMPLE_MOJMAP_CLASS }.map { it.oldName to it.newName }.toSet()
        assertEquals(renames(byClass.renamed), renames(byPackage.renamed), "renamed differ between the two paths")
    }

    private companion object {
        // ServerLevel overrides plenty from Level, so it carries members with no intermediary name.
        const val SAMPLE_MOJMAP_CLASS = "net/minecraft/server/level/ServerLevel"
        const val SAMPLE_YARN_CLASS = "net/minecraft/server/world/ServerWorld"
    }
}
