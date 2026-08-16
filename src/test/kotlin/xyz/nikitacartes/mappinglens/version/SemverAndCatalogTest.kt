package xyz.nikitacartes.mappinglens.version

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemverTest {

    private fun lt(a: String, b: String) {
        val sa = assertNotNull(Semver.parse(a), "parse $a")
        val sb = assertNotNull(Semver.parse(b), "parse $b")
        assertTrue(sa < sb, "$a should sort before $b")
    }

    private fun ltId(a: String, b: String) {
        val sa = assertNotNull(Semver.fromMinecraftId(a), "fromMinecraftId $a")
        val sb = assertNotNull(Semver.fromMinecraftId(b), "fromMinecraftId $b")
        assertTrue(sa < sb, "$a should sort before $b")
    }

    @Test
    fun `minor numbers compare numerically not lexically`() {
        lt("1.9", "1.10")          // the classic latestRelease() bug
        lt("1.9.4", "1.10")
        lt("1.10.1", "1.10.2")
    }

    @Test
    fun `pre-release sorts before its final release`() {
        lt("1.14-rc.1", "1.14")
        lt("1.14-rc.1", "1.14-rc.2")
        lt("1.21.11-alpha.25.43.a", "1.21.11")
    }

    @Test
    fun `snapshot semvers order chronologically`() {
        lt("1.21.11-alpha.25.43.a", "1.21.11-alpha.25.44.a")
        lt("1.21.11-alpha.25.43.a", "1.21.11-alpha.25.43.b")
    }

    @Test
    fun `numeric pre-release identifiers rank below alphanumeric`() {
        lt("1.0.0-1", "1.0.0-alpha")
    }

    @Test
    fun `fromMinecraftId reads the leading version out of underscore and space-form ids`() {
        assertEquals(Semver(1, 21, 11, listOf("unobfuscated")), Semver.fromMinecraftId("1.21.11_unobfuscated"))
        assertEquals(Semver(1, 16, 0, listOf("combat", "6")), Semver.fromMinecraftId("1.16_combat-6"))
        assertEquals(Semver(1, 19, 0, listOf("deep", "dark", "experimental", "snapshot", "1")),
            Semver.fromMinecraftId("1.19_deep_dark_experimental_snapshot-1"))
        val preRelease = assertNotNull(Semver.fromMinecraftId("1.14.2 Pre-Release 4"))
        assertEquals(1, preRelease.major)
        assertEquals(14, preRelease.minor)
        assertEquals(2, preRelease.patch)
    }

    @Test
    fun `underscore and space-form variants never outrank their own bare release`() {
        ltId("1.21.11_unobfuscated", "1.21.11")
        ltId("1.16_combat-6", "1.16")
        ltId("1.14 Pre-Release 1", "1.14")
        ltId("1.14.2 Pre-Release 4", "1.14.2")
    }

    @Test
    fun `weekly snapshots still fall through to the cache (no leading dotted version)`() {
        assertEquals(null, Semver.fromMinecraftId("25w43a"))
        assertEquals(null, Semver.fromMinecraftId("weird"))
    }

    @Test
    fun `build metadata never outranks the untagged build it tags`() {
        // Real case: the cache spells `1.21.11_unobfuscated` as `1.21.11+unobfuscated` — same
        // major.minor.patch and pre-release as the untagged `1.21.11`, so only build metadata tells
        // them apart. Semver 2.0.0 ignores build metadata for precedence, which used to make the two
        // compare equal and fall through to raw id comparison — always ranking the longer, suffixed
        // id (so every `_unobfuscated` snapshot) as newest.
        lt("1.21.11+unobfuscated", "1.21.11")
        lt("1.21.11-rc.1+unobfuscated", "1.21.11-rc.1")
        lt("1.21.11-alpha.25.45.a+unobfuscated", "1.21.11-alpha.25.45.a")
    }
}

class VersionCatalogTest {

    private fun catalog(vararg pairs: Pair<String, String?>) = VersionCatalog(
        pairs.associate { (id, semver) -> id to VersionMeta(id, semver, "release", null) }
    )

    @Test
    fun `sorted orders by semver`() {
        val cat = catalog("1.10" to "1.10", "1.9" to "1.9", "1.9.4" to "1.9.4")
        assertEquals(listOf("1.9", "1.9.4", "1.10"), cat.sorted(listOf("1.10", "1.9", "1.9.4")))
    }

    @Test
    fun `ids with unknown semver sort last`() {
        val cat = catalog("1.21" to "1.21", "weird" to null)
        assertEquals(listOf("1.21", "weird"), cat.sorted(listOf("weird", "1.21")))
    }

    @Test
    fun `release without a cache entry still sorts above its own pre and rc builds`() {
        // Real case: semver-cache lags behind, so 26.2 (release), pre-3 and rc-2 have no cached
        // semver while pre-2/snapshot-1 do. Derivation must keep the bare release newest.
        val cat = catalog(
            "26.2-snapshot-1" to "26.2-alpha.1",
            "26.2-pre-2" to "26.2-pre.2",
            "26.2-pre-3" to null,
            "26.2-rc-2" to null,
            "26.2" to null,
        )
        val ids = listOf("26.2", "26.2-rc-2", "26.2-pre-3", "26.2-pre-2", "26.2-snapshot-1")
        assertEquals(
            listOf("26.2-snapshot-1", "26.2-pre-2", "26.2-pre-3", "26.2-rc-2", "26.2"),
            cat.sorted(ids),
        )
    }

    @Test
    fun `unobfuscated, combat, and old pre-release variants sort near their own base version`() {
        // Real case (the reported bug): these ids are derived by GitCraft/MappingLens itself from an
        // unobfuscated jar or a combat/experimental test build, never read from Mojang's own launcher
        // manifest — so they are not catalog keys AT ALL (no cache entry, no mc-meta file), not merely
        // "a key with no semver value". They reach sorted() only via GitCraftStore.versionIds()'s
        // filesystem-discovered id list. Before the fix they fell into "unknown semver" and sorted as
        // if newest — right after 26.3-snapshot-1 instead of next to 1.14 / 1.16 / 1.21.11.
        val cat = catalog(
            "1.14" to "1.14",
            "1.16" to "1.16",
            "1.21.11" to "1.21.11",
            "26.3-snapshot-1" to "26.3-alpha.1",
        )
        val ids = listOf(
            "1.14", "1.14 Pre-Release 1", "1.16", "1.16_combat-6",
            "1.21.11", "1.21.11_unobfuscated", "26.3-snapshot-1",
        )
        assertEquals(
            listOf(
                "1.14 Pre-Release 1", "1.14", "1.16_combat-6", "1.16",
                "1.21.11_unobfuscated", "1.21.11", "26.3-snapshot-1",
            ),
            cat.sorted(ids),
        )
    }

    @Test
    fun `unobfuscated variant with its own cache entry still sorts below its base`() {
        // Real case (the reported bug): unlike the combat/experimental variants above, GitCraft's
        // cache DOES carry a semver for `_unobfuscated` ids — spelled with `+unobfuscated` build
        // metadata, e.g. `25w45a` -> "1.21.11-alpha.25.45.a" and `25w45a_unobfuscated` ->
        // "1.21.11-alpha.25.45.a+unobfuscated". Before the fix this tied with the untagged id on
        // precedence and fell through to the id-string tiebreak, which sorted every `_unobfuscated`
        // row one slot newer than its own base.
        val cat = catalog(
            "1.21.11" to "1.21.11",
            "1.21.11_unobfuscated" to "1.21.11+unobfuscated",
            "25w45a" to "1.21.11-alpha.25.45.a",
            "25w45a_unobfuscated" to "1.21.11-alpha.25.45.a+unobfuscated",
        )
        val ids = listOf("1.21.11_unobfuscated", "1.21.11", "25w45a_unobfuscated", "25w45a")
        assertEquals(
            listOf("25w45a_unobfuscated", "25w45a", "1.21.11_unobfuscated", "1.21.11"),
            cat.sorted(ids),
        )
    }

    @Test
    fun `loads real semver-cache and mc-meta`() {
        assumeTrue(Files.exists(RealDataTestConfig.artifactStore), "artifact-store missing")
        val cat = VersionCatalog.load(RealDataTestConfig.artifactStore)
        assertTrue(cat.all().isNotEmpty(), "catalog should not be empty")

        // 1.14 is a real release with mc-meta type=release.
        val v114 = cat.get("1.14")
        assertNotNull(v114, "1.14 should be in the catalog")
        assertEquals("release", v114.releaseType)
        assertNotNull(v114.semver, "1.14 should have a semver")

        // The classic ordering bug: 1.9 must precede 1.10 by semver.
        assertEquals(listOf("1.9", "1.10"), cat.sorted(listOf("1.10", "1.9")))
    }
}
