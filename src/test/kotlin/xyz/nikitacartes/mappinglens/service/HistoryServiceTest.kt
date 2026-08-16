package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.Fixtures
import xyz.nikitacartes.mappinglens.db.tables.ClassTable
import xyz.nikitacartes.mappinglens.db.tables.VersionTable
import xyz.nikitacartes.mappinglens.model.HistoryResponse
import xyz.nikitacartes.mappinglens.routes.historyRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class HistoryServiceTest {

    // Mirrors the real ZombifiedPiglin case: a class that moved package in mojmap, with an
    // unobfuscated version (no yarn, so no intermediary) sitting between the old and the new name.
    private val oldName = "net/minecraft/world/entity/monster/Piglin"
    private val movedName = "net/minecraft/world/entity/monster/zombie/Piglin"
    private val piglinYarn = "net/minecraft/entity/mob/PiglinEntity"
    private val piglinIntermediary = "net/minecraft/class_5000"
    private val pathNode = "net/minecraft/world/level/pathfinder/Node"
    private val unrelatedNode = "net/minecraft/util/filefix/virtualfilesystem/Node"
    private val bornAs = "net/minecraft/world/waypoints/TrackedWaypoint"
    private val renamedTo = "net/minecraft/world/waypoints/Waypoint"

    private fun addVersion(id: String, yarn: Boolean) = VersionTable.insertAndGetId {
        it[versionId] = id
        it[releaseType] = "release"
        it[indexedAt] = Instant.now().toString()
        it[hasYarn] = yarn
        it[hasMojmap] = true
        it[hasIntermediary] = yarn
    }.value

    /** Inserts one Piglin row, with `simple_name` derived the way the indexer derives it. */
    private fun addPiglin(versionRowId: Int, mojmap: String, yarn: Boolean) = ClassTable.insertAndGetId {
        it[versionId] = EntityID(versionRowId, VersionTable)
        it[intermediaryName] = if (yarn) piglinIntermediary else null
        it[yarnName] = if (yarn) piglinYarn else null
        it[mojmapName] = mojmap
        it[simpleName] = (if (yarn) piglinYarn else mojmap).substringAfterLast('/')
    }

    private fun addClass(versionRowId: Int, mojmap: String, intermediary: String?) = ClassTable.insertAndGetId {
        it[versionId] = EntityID(versionRowId, VersionTable)
        it[intermediaryName] = intermediary
        it[mojmapName] = mojmap
        it[simpleName] = mojmap.substringAfterLast('/')
    }

    private fun seedFourVersions(tmp: Path): Database {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        transaction(db) {
            val ids = VersionTable.selectAll().associate { it[VersionTable.versionId] to it[VersionTable.id].value }
            addPiglin(ids.getValue(Fixtures.V_1_21), oldName, yarn = true)
            addPiglin(ids.getValue(Fixtures.V_1_21_1), oldName, yarn = true)
            // Newest holder of the old name, and the one with no intermediary to be followed by.
            val unobfuscated = addVersion("1.21.1_unobfuscated", yarn = false)
            addPiglin(unobfuscated, oldName, yarn = false)
            addClass(unobfuscated, pathNode, null)
            // The move happens after the last mapped version, so the moved name exists only where
            // there is no intermediary at all — the real shape of Mojang's unobfuscated releases.
            val last = addVersion("1.21.2", yarn = false)
            addPiglin(last, movedName, yarn = false)
            // Two unrelated classes sharing a simple name, both alive in the newest version.
            addClass(ids.getValue(Fixtures.V_1_21_1), pathNode, "net/minecraft/class_9")
            addClass(last, pathNode, null)
            addClass(last, unrelatedNode, null)
            // A class born after the last mapped version and renamed there: no intermediary on
            // either side, and the simple name changes, so the two rows share nothing.
            addClass(unobfuscated, bornAs, null)
            addClass(last, renamedTo, null)
        }
        return db
    }

    @Test
    fun `class history follows a package move and collapses unchanged runs`(@TempDir tmp: Path) {
        val service = HistoryService(seedFourVersions(tmp))

        val entry = service.history(listOf(oldName), "mojmap", null, null)!!.results.single()
        assertEquals("class", entry.type)
        assertEquals(listOf(true, true, true), entry.spans.map { it.present })
        val (before, unobfuscated, moved) = entry.spans
        assertEquals(listOf("1.21", "1.21.1", 2), listOf(before.from, before.to, before.versions))
        assertEquals(oldName, before.mojmap)
        // The version with no intermediary answers alike but has no yarn name, so it stands alone.
        assertEquals(listOf("1.21.1_unobfuscated", null), listOf(unobfuscated.from, unobfuscated.yarn))
        assertEquals(listOf("1.21.2", movedName, null), listOf(moved.from, moved.mojmap, moved.yarn))

        // The moved name answers the same, although it exists only where there is no intermediary:
        // the class is found again in the newest mapped version before it, which re-anchors the rest.
        val fromNewName = service.history(listOf(movedName), "mojmap", null, null)!!.results.single()
        assertEquals(entry.spans, fromNewName.spans)
        assertEquals(piglinIntermediary, fromNewName.spans.first().intermediary)

        // A class new in the last version is not linked to an older one just because they share a
        // simple name — the older one is still there next to it, so they are different classes.
        val unrelated = service.history(listOf(unrelatedNode), "mojmap", null, null)!!.results.single()
        assertEquals(listOf(false, true), unrelated.spans.map { it.present })
        assertEquals("1.21.2", unrelated.spans.last().from)

        // A rename with no intermediary on either side splits the history in two: each name answers
        // only for the versions that spell it that way.
        val born = service.history(listOf(bornAs), "mojmap", null, null)!!.results.single()
        assertEquals(listOf(false, true, false), born.spans.map { it.present })
        assertEquals(listOf("1.21.1_unobfuscated", "1.21.2"), listOf(born.spans[1].from, born.spans[2].from))
        val renamed = service.history(listOf(renamedTo), "mojmap", null, null)!!.results.single()
        assertEquals(listOf(false, true), renamed.spans.map { it.present })
        assertEquals("1.21.2", renamed.spans.last().from)

        // A class that only exists in one version reads as absent / present / absent.
        val added = service.history(listOf(Fixtures.onlyIn1_21_1.mojmap), "mojmap", null, null)!!.results.single()
        assertEquals(listOf(false, true, false), added.spans.map { it.present })
        assertEquals(listOf("1.21", "1.21.1", "1.21.1_unobfuscated"), added.spans.map { it.from })
    }

    @Test
    fun `intermediary on the unobfuscated versions follows a rename in both namespaces`(@TempDir tmp: Path) {
        // What the separate unobfuscated-intermediary source buys: `Gui` -> `Hud` is a rename, not a
        // move, so the simple name cannot bridge it — only the shared intermediary can.
        val intermediary = "net/minecraft/class_329"
        val gui = "net/minecraft/client/gui/Gui"
        val hud = "net/minecraft/client/gui/Hud"
        val inGameHud = "net/minecraft/client/gui/hud/InGameHud"
        val db = Fixtures.newDb(tmp)
        transaction(db) {
            fun version(id: String, yarn: Boolean) = VersionTable.insertAndGetId {
                it[versionId] = id
                it[releaseType] = "release"
                it[indexedAt] = Instant.now().toString()
                it[hasYarn] = yarn
                it[hasMojmap] = true
                it[hasIntermediary] = true
            }.value
            fun cls(versionRowId: Int, mojmap: String, yarn: String?) = ClassTable.insert {
                it[versionId] = EntityID(versionRowId, VersionTable)
                it[intermediaryName] = intermediary
                it[yarnName] = yarn
                it[mojmapName] = mojmap
                it[simpleName] = (yarn ?: mojmap).substringAfterLast('/')
            }
            cls(version("1.21.11", yarn = true), gui, inGameHud)
            cls(version("26.1", yarn = false), gui, null)
            cls(version("26.2", yarn = false), hud, null)
        }
        val service = HistoryService(db)

        for (query in listOf(gui, hud)) {
            val entry = service.history(listOf(query), "mojmap", null, null)!!.results.single()
            assertEquals(listOf(true, true, true), entry.spans.map { it.present })
            assertEquals(listOf(gui, gui, hud), entry.spans.map { it.mojmap })
        }

        // The yarn name exists only on the mapped version, and still answers for all three.
        val byYarn = service.history(listOf(inGameHud), "yarn", null, null)!!.results.single()
        assertEquals(listOf("1.21.11", "26.1", "26.2"), byYarn.spans.map { it.from })
        assertEquals(listOf(inGameHud, null, null), byYarn.spans.map { it.yarn })
        assertEquals(hud, byYarn.spans.last().mojmap)
    }

    @Test
    fun `member history follows a rename and reports intermediary descriptors`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21(db)
        Fixtures.seed_1_21_1(db)
        val service = HistoryService(db)
        val owner = Fixtures.block_1_21_1.yarn

        val entry = service.history(listOf("$owner:${Fixtures.getDefaultState.yarn}"), "yarn", null, null)!!.results.single()
        assertEquals("method", entry.type)
        assertEquals(listOf("getDefaultStateOld", "getDefaultState"), entry.spans.map { it.members.single().yarn })
        assertEquals(
            Fixtures.getDefaultState.intermediaryDesc,
            entry.spans.last().members.single().intermediaryDescriptor,
        )
        assertEquals(owner, entry.spans.last().owner)

        // Querying the name from the older version anchors the same history.
        val fromOldName = service.history(listOf("$owner:getDefaultStateOld"), "yarn", null, null)!!.results.single()
        assertEquals(entry.spans, fromOldName.spans)

        // A name that is not a method falls through to the field table.
        val field = service.history(
            listOf("${Fixtures.block_1_21_1.mojmap}:${Fixtures.stateIds.mojmap}"), "mojmap", null, null,
        )!!.results.single()
        assertEquals("field", field.type)
        assertEquals(1, field.spans.size)
        assertEquals(Fixtures.stateIds.intermediary, field.spans.single().members.single().intermediary)
    }

    @Test
    fun `history route batches queries, honours a version range and rejects bad input`(@TempDir tmp: Path) = testApplication {
        val service = HistoryService(seedFourVersions(tmp))
        application {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { historyRoutes(service) }
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        // Several keys in one request, answered in the order given and echoed verbatim.
        val dotted = oldName.replace('/', '.')
        val batch = client.get("/api/v1/history?q=$dotted&q=net/minecraft/Nope").body<HistoryResponse>()
        assertEquals(listOf(dotted, "net/minecraft/Nope"), batch.results.map { it.query })
        assertEquals(listOf("class", "unknown"), batch.results.map { it.type })
        assertEquals(movedName, batch.results.first().spans.last().mojmap)

        val ranged = client.get("/api/v1/history?q=$oldName&from=1.21.1&to=1.21.1").body<HistoryResponse>()
        assertEquals(listOf("1.21.1" to "1.21.1"), ranged.results.single().spans.map { it.from to it.to })

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/history?q=a&from=0.0.1").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/history").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/history?q=a&namespace=obfuscated").status)
    }
}
