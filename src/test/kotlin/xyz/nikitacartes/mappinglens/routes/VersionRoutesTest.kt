package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.VersionInfo
import xyz.nikitacartes.mappinglens.model.VersionListResponse
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class VersionRoutesTest {

    private fun Application.installJson() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun seedVersions(db: Database) = transaction(db) {
        fun row(id: String, type: String, sort: Int, variantOf: String? = null) {
            VersionTable.insert {
                it[versionId] = id
                it[releaseType] = type
                it[indexedAt] = Instant.now().toString()
                it[hasYarn] = true
                it[sortIndex] = sort
                it[VersionTable.variantOf] = variantOf
            }
        }
        row("1.20.1", "release", 1)
        row("1.21", "release", 2)
        row("1.21.1", "release", 3)
        row("25w31a", "snapshot", 4)
        row("1.21.1_unobfuscated", "release", 3, variantOf = "1.21.1")
    }

    private fun ApplicationTestBuilder.versionsClient() =
        createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun `releasesOnly drops snapshots but keeps releases`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        val all = client.get("/api/v1/versions").body<VersionListResponse>().versions.map { it.id }
        assertEquals(listOf("25w31a", "1.21.1", "1.21", "1.20.1"), all)

        val releases = client.get("/api/v1/versions?releasesOnly=true").body<VersionListResponse>().versions.map { it.id }
        assertEquals(listOf("1.21.1", "1.21", "1.20.1"), releases)
    }

    @Test
    fun `releaseType matches exactly and unknown type returns empty list`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        val snapshots = client.get("/api/v1/versions?releaseType=snapshot").body<VersionListResponse>()
        assertEquals(listOf("25w31a"), snapshots.versions.map { it.id })

        val unknown = client.get("/api/v1/versions?releaseType=bogus")
        assertEquals(HttpStatusCode.OK, unknown.status)
        assertEquals(emptyList(), unknown.body<VersionListResponse>().versions)
    }

    @Test
    fun `idPrefix matches version id prefix and combines with other filters`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        val prefixed = client.get("/api/v1/versions?idPrefix=1.21").body<VersionListResponse>().versions.map { it.id }
        assertEquals(listOf("1.21.1", "1.21"), prefixed)

        val combined = client.get("/api/v1/versions?releasesOnly=true&idPrefix=25w").body<VersionListResponse>()
        assertEquals(emptyList(), combined.versions)
    }

    @Test
    fun `includeVariants still hides variants by default`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        val withVariants = client.get("/api/v1/versions?includeVariants=true").body<VersionListResponse>().versions.map { it.id }
        assertEquals(
            listOf("25w31a", "1.21.1_unobfuscated", "1.21.1", "1.21", "1.20.1"),
            withVariants,
        )
    }

    @Test
    fun `latest returns newest release as VersionInfo`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        // "latest" must hit /latest, not {version} (which would 404 on unknown id "latest").
        val resp = client.get("/api/v1/versions/latest")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("1.21.1", resp.body<VersionInfo>().id)
    }

    @Test
    fun `latest returns 404 when no versions indexed`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/versions/latest").status)
    }

    @Test
    fun `invalid releasesOnly returns 400`(@TempDir tmp: Path) = testApplication {
        val db = Fixtures.newDb(tmp)
        seedVersions(db)
        application {
            installJson()
            routing { versionRoutes(VersionService(db)) }
        }
        val client = versionsClient()

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/versions?releasesOnly=maybe").status)
    }
}
