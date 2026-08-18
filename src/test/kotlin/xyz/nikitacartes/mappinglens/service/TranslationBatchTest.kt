package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.Fixtures
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The batch translation exists so a key read off one namespace can be posted to `/exists` in
 * another, descriptor included. These check that the descriptor really is rewritten, which is the
 * part that has no other way to be right.
 */
class TranslationBatchTest {

    @Test
    fun `maps the class types of a descriptor and leaves the rest alone`() {
        val rename = mapOf("a/B" to "pkg/Renamed")::get
        assertEquals("(ILpkg/Renamed;)V", Descriptors.mapTypes("(ILa/B;)V", rename))
        assertEquals("([[Lpkg/Renamed;)[I", Descriptors.mapTypes("([[La/B;)[I", rename))
        assertEquals("()Ljava/lang/String;", Descriptors.mapTypes("()Ljava/lang/String;", rename))
        assertEquals("(JZ)V", Descriptors.mapTypes("(JZ)V", rename))
    }

    @Test
    fun `translates a member key whole, descriptor included`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val service = TranslationService(db, VersionService(db))

        val keys = listOf(
            "net/minecraft/block/Block",
            "net/minecraft/block/Block:getDefaultState:()Lnet/minecraft/block/BlockState;",
            "net/minecraft/block/Block:STATE_IDS",
            "net/minecraft/block/Block:noSuchMember:()V",
        )
        val results = service.translateBatch(Fixtures.V_1_21_1, "yarn", "mojmap", keys)!!.results

        assertEquals("net/minecraft/world/level/block/Block", results[0].translated)
        assertEquals("class", results[0].type)
        // The point of the endpoint: the owner, the name and every type of the descriptor move over.
        assertEquals(
            "net/minecraft/world/level/block/Block:defaultBlockState:()Lnet/minecraft/world/level/block/state/BlockState;",
            results[1].translated,
        )
        assertEquals("method", results[1].type)
        // A name with one match resolves without a descriptor; `gs` is no class of this version, so
        // that type stays as it is instead of being dropped.
        assertEquals("net/minecraft/world/level/block/Block:BLOCK_STATE_REGISTRY:Lgs;", results[2].translated)
        assertEquals("field", results[2].type)
        assertNull(results[3].translated)
    }

    @Test
    fun `reports nothing for a descriptor that matches no overload`(@TempDir tmp: Path) {
        val db = Fixtures.newDb(tmp)
        Fixtures.seed_1_21_1(db)
        val service = TranslationService(db, VersionService(db))

        val key = "net/minecraft/block/Block:getDefaultState:(I)V"
        assertNull(service.translateBatch(Fixtures.V_1_21_1, "yarn", "mojmap", listOf(key))!!.results.single().translated)
    }
}
