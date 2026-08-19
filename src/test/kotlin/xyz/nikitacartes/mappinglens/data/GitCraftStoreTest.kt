package xyz.nikitacartes.mappinglens.data

import xyz.nikitacartes.mappinglens.RealDataTestConfig
import xyz.nikitacartes.mappinglens.ingestion.CorrespondenceResolver
import xyz.nikitacartes.mappinglens.ingestion.UnifiedClassEntry
import xyz.nikitacartes.mappinglens.ingestion.UnifiedMemberEntry
import xyz.nikitacartes.mappinglens.version.VersionCatalog
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitCraftStoreTest {

    private fun store(): GitCraftStore {
        assumeTrue(Files.exists(RealDataTestConfig.artifactStore), "artifact-store missing")
        assumeTrue(Files.isDirectory(RealDataTestConfig.intermediaryMappings), "intermediary mappings missing")
        return GitCraftStore(RealDataTestConfig.artifactStore, RealDataTestConfig.intermediaryMappings)
    }

    @Test
    fun `resolves all mapping kinds for a release`() {
        val s = store()
        val src = s.resolve("1.21")
        assertNotNull(src.yarn, "yarn tiny"); assertTrue(Files.exists(src.yarn!!))
        assertTrue(src.mojmaps.isNotEmpty(), "mojmap tinies")
        src.mojmaps.forEach { assertTrue(Files.exists(it), "mojmap exists: $it") }
        assertNotNull(src.intermediary, "intermediary tiny"); assertTrue(Files.exists(src.intermediary!!))
        assertTrue(src.hasYarn && src.hasMojmap && src.hasIntermediary)
    }

    @Test
    fun `a yarn version carries intermediary names without a standalone intermediary tiny`() {
        val s = store()
        val src = s.resolve("1.21")
        assumeTrue(src.hasYarn, "1.21 yarn missing")
        // Yarn's merged tiny v2 is official->intermediary->named, so losing the standalone
        // intermediary file must not clear the flag the indexer writes to versions.has_intermediary.
        val yarnOnly = src.copy(intermediary = null)
        assertFalse(yarnOnly.hasIntermediary)
        assertTrue(yarnOnly.hasIntermediaryNames)
    }

    @Test
    fun `resolves space-named version and globs its jar (the section 5 hazard)`() {
        val s = store()
        val v = "1.14 Pre-Release 1"
        assumeTrue(s.resolve(v).hasAny, "$v not present in this store")
        val src = s.resolve(v)
        assertNotNull(src.intermediary, "intermediary v1 for space-named version")
        // Jar resolves via glob despite the unpredictable id suffix in the filename.
        val jar = s.decompiledJar(v, "yarn")
        assertNotNull(jar, "decompiled yarn jar for $v")
        assertTrue(Files.exists(jar!!))
    }

    @Test
    fun `enumerates versions sorted by semver`() {
        val s = store()
        val ids = s.versionIds()
        assertTrue(ids.contains("1.21"), "should include 1.21")
        if (ids.contains("1.9") && ids.contains("1.10")) {
            assertTrue(ids.indexOf("1.9") < ids.indexOf("1.10"), "1.9 must sort before 1.10")
        }
    }

    @Test
    fun `parseUnified joins Block across namespaces with presence both`() {
        val s = store()
        assumeTrue(s.resolve("1.21").hasYarn, "1.21 yarn missing")
        val unified = s.parseUnified("1.21")
        val block = unified.firstOrNull { it.yarnName == "net/minecraft/block/Block" }
        assertNotNull(block, "Block class")
        assertEquals("net/minecraft/world/level/block/Block", block.mojmapName)
        assertEquals(CorrespondenceResolver.PRESENCE_BOTH, block.presence)
    }

    @Test
    fun `an unobfuscated release covered by yarn takes its mojmap names from official`(@TempDir tmp: Path) {
        // Mojang's unobfuscated releases publish no obfuscation mappings, so the store holds no
        // `-moj.tiny` for them and the `official` column of the yarn tiny is the Mojang name.
        val mappings = Files.createDirectories(tmp.resolve("artifact-store/mappings"))
        Files.writeString(
            mappings.resolve("26.3-yarn-build.4.tiny"),
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n" +
                "c\tnet/minecraft/world/level/block/Block\tnet/minecraft/class_2248\tnet/minecraft/block/Block\n" +
                "\tm\t()Lnet/minecraft/world/level/block/state/BlockState;\tdefaultBlockState\tmethod_9564\tgetDefaultState\n",
        )
        val unobfuscatedIntermediary = Files.createDirectories(tmp.resolve("relativity/mappings"))
        Files.writeString(unobfuscatedIntermediary.resolve("26.3.tiny"), "v1\tofficial\tintermediary\n")

        val s = GitCraftStore(
            artifactStore = tmp.resolve("artifact-store"),
            intermediaryMappingsDir = tmp.resolve("no-intermediary"),
            unobfuscatedIntermediaryDir = unobfuscatedIntermediary,
        )
        assertTrue(s.resolve("26.3").hasMojmap, "an unobfuscated release must be marked hasMojmap")

        val block = s.parseUnified("26.3").single()
        assertEquals("net/minecraft/world/level/block/Block", block.mojmapName)
        assertEquals("net/minecraft/block/Block", block.yarnName)
        assertEquals(CorrespondenceResolver.PRESENCE_BOTH, block.presence)
        assertEquals("defaultBlockState", block.methods.single().mojmapName)
    }

    @Test
    fun `picks the highest yarn build and keeps both mojmap halves`(@TempDir tmp: Path) {
        val mappings = Files.createDirectories(tmp.resolve("artifact-store/mappings"))
        for (name in listOf(
            "1.21-yarn-build.5.tiny",
            "1.21-yarn-build.12.tiny",
            "1.21-yarn-build.12-constants.tiny",
            "1.21-yarn-build.12-unpick.tiny",
            "1.21-client-moj.tiny",
            "1.21-server-moj.tiny",
        )) Files.writeString(mappings.resolve(name), "")

        val s = GitCraftStore(tmp.resolve("artifact-store"), tmp.resolve("no-intermediary"))
        assertEquals(mappings.resolve("1.21-yarn-build.12.tiny"), s.yarnTiny("1.21"))
        assertEquals(
            listOf(mappings.resolve("1.21-client-moj.tiny"), mappings.resolve("1.21-server-moj.tiny")),
            s.mojmapTinies("1.21"),
        )
    }

    @Test
    fun `a namespace left out of indexing mappings resolves as absent`(@TempDir tmp: Path) {
        val dir = Files.createDirectories(tmp.resolve("artifact-store/mappings"))
        for (name in listOf("1.21-yarn-build.12.tiny", "1.21-client-moj.tiny")) {
            Files.writeString(dir.resolve(name), "")
        }
        val store = tmp.resolve("artifact-store")
        val noIntermediary = tmp.resolve("no-intermediary")

        val mojmapOnly = GitCraftStore(store, noIntermediary, mappings = setOf("mojmap"))
        assertNull(mojmapOnly.yarnTiny("1.21"), "yarn is not read")
        assertFalse(mojmapOnly.resolve("1.21").hasYarn)
        assertTrue(mojmapOnly.resolve("1.21").hasMojmap)

        val yarnOnly = GitCraftStore(store, noIntermediary, mappings = setOf("yarn"))
        assertEquals(emptyList(), yarnOnly.mojmapTinies("1.21"), "mojmap is not read")
        assertFalse(yarnOnly.resolve("1.21").hasMojmap)
        assertTrue(yarnOnly.resolve("1.21").hasYarn)
    }

    @Test
    fun `the intermediary repo underscore spelling resolves as its canonical id`(@TempDir tmp: Path) {
        // FabricMC/intermediary holds mappings/1_16_combat-0.tiny for the version the launcher calls
        // 1.16_combat-0. Both spellings once reached the index: the real version plus an
        // intermediary-only ghost carrying the same classes under a name nothing else uses.
        val intermediary = Files.createDirectories(tmp.resolve("intermediary/mappings"))
        Files.writeString(intermediary.resolve("1_16_combat-0.tiny"), "v1\tofficial\tintermediary\n")
        Files.writeString(intermediary.resolve("1.16_combat-1.tiny"), "v1\tofficial\tintermediary\n")

        val s = GitCraftStore(tmp.resolve("artifact-store"), intermediary)
        assertEquals(intermediary.resolve("1_16_combat-0.tiny"), s.intermediaryTiny("1.16_combat-0"))
        assertEquals(listOf("1.16_combat-0", "1.16_combat-1"), s.versionIds().sorted())
    }

    @Test
    fun `protocol version comes from the version json inside the jar`(@TempDir tmp: Path) {
        val versionDir = Files.createDirectories(tmp.resolve("artifact-store/mc-versions/1.21"))
        ZipOutputStream(Files.newOutputStream(versionDir.resolve("merged-1.21-id_abc.jar"))).use { zip ->
            zip.putNextEntry(ZipEntry("version.json"))
            zip.write("""{"id":"1.21","protocol_version":767}""".toByteArray())
            zip.closeEntry()
        }

        val s = GitCraftStore(tmp.resolve("artifact-store"), tmp.resolve("no-intermediary"))
        assertEquals(767, s.protocolVersion("1.21"))
        assertNull(s.protocolVersion("1.20.6"), "a version with no jar has no protocol version")
    }

    @Test
    fun `a version Mojang published no mappings for stays without mojmap`(@TempDir tmp: Path) {
        // Same shape as above minus the unobfuscated-intermediary file: an obfuscated version from
        // before 19w36a. Its `official` column is the obfuscated name and must not become mojmap.
        val mappings = Files.createDirectories(tmp.resolve("artifact-store/mappings"))
        Files.writeString(
            mappings.resolve("18w43b-yarn-build.4.tiny"),
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n" +
                "c\tdnv\tnet/minecraft/class_2248\tnet/minecraft/block/Block\n",
        )

        val s = GitCraftStore(
            artifactStore = tmp.resolve("artifact-store"),
            intermediaryMappingsDir = tmp.resolve("no-intermediary"),
            unobfuscatedIntermediaryDir = tmp.resolve("relativity/mappings"),
        )
        assertFalse(s.resolve("18w43b").hasMojmap)
        assertNull(s.parseUnified("18w43b").single().mojmapName)
    }
}

/**
 * The union that fills in the members an unobfuscated release's mappings leave out. Intermediary
 * names an override only where it is first declared, so without this every override of such a
 * version reaches the index as if the subclass did not declare it.
 */
class DeclaredMemberUnionTest {

    private val store = GitCraftStore(
        artifactStore = Path.of("."),
        intermediaryMappingsDir = Path.of("."),
        catalog = VersionCatalog(emptyMap()),
    )

    private fun member(name: String, desc: String, intermediary: String? = null) = UnifiedMemberEntry(
        obfName = name, obfDesc = desc,
        intermediaryName = intermediary, intermediaryDesc = null,
        yarnName = null, mojmapName = name,
    )

    private fun cls(name: String, methods: List<UnifiedMemberEntry>) = UnifiedClassEntry(
        obfName = name, intermediaryName = null, yarnName = null, mojmapName = name,
        methods = methods, fields = emptyList(), presence = CorrespondenceResolver.PRESENCE_BOTH,
    )

    @Test
    fun `adds the members only the jar declares and keeps the mapped ones intact`() {
        val mapped = listOf(cls("A", listOf(member("tick", "()V", intermediary = "method_1"))))
        val declared = listOf(
            cls("A", listOf(member("tick", "()V"), member("render", "()V"), member("tick", "(I)V"))),
        )

        val merged = store.withDeclaredMembers(mapped, declared).single()

        assertEquals(listOf("tick", "render", "tick"), merged.methods.map { it.mojmapName })
        // The mapped row keeps its intermediary name; the two the jar alone knows carry none, and
        // an overload is told apart by its descriptor rather than swallowed by the same name.
        assertEquals("method_1", merged.methods[0].intermediaryName)
        assertEquals(listOf("()V", "()V", "(I)V"), merged.methods.map { it.obfDesc })
        assertNull(merged.methods[1].intermediaryName)
        // `official` is the Mojang name here, so an addition has to answer the obf-keyed joins too.
        assertEquals("render", merged.methods[1].obfName)
    }

    @Test
    fun `leaves a class the jar does not carry alone`() {
        val mapped = listOf(cls("Gone", listOf(member("tick", "()V"))))
        assertEquals(mapped, store.withDeclaredMembers(mapped, emptyList()))
    }
}
