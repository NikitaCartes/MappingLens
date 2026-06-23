package dev.mappinglens.version

import dev.mappinglens.RealDataTestConfig
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
    fun `latestRelease ignores pre-releases and picks highest stable`() {
        val cat = VersionCatalog(
            mapOf(
                "1.20.6" to VersionMeta("1.20.6", "1.20.6", "release", null),
                "1.21" to VersionMeta("1.21", "1.21", "release", null),
                "1.21-rc.1" to VersionMeta("1.21-rc.1", "1.21-rc.1", "snapshot", null),
            )
        )
        assertEquals("1.21", cat.latestRelease(listOf("1.20.6", "1.21", "1.21-rc.1")))
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
        assertEquals("26.2", cat.latestRelease(ids))
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
        assertEquals("1.10", cat.latestRelease(listOf("1.9", "1.10")))
    }
}
