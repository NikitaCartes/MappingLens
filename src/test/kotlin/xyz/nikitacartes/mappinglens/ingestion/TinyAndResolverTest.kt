package xyz.nikitacartes.mappinglens.ingestion

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mapping samples below mirror real Yarn/Mojang correspondences for
 * `net/minecraft/block/Block` and `Block#getDefaultState` in Minecraft 1.21.x
 * (verified via https://linkie.shedaniel.dev/).
 */
class TinyV2ParserTest {

    private fun writeTiny(tmp: Path, body: String): Path {
        val p = tmp.resolve("mappings.tiny")
        Files.writeString(p, body)
        return p
    }

    @Test
    fun `parses minimal yarn-style tiny v2 with class, method, field`(@TempDir tmp: Path) {
        val tiny = """
            tiny	2	0	official	intermediary	named
            c	dnv	net/minecraft/class_2248	net/minecraft/block/Block
            	m	()Ldpb;	n	method_9564	getDefaultState
            	f	Lgs;	j	field_10651	STATE_IDS
        """.trimIndent().replace(' ', '\t').let {
            // We carefully built the file with tabs already (the `replace(' ', '\t')` would corrupt the
            // header). Rebuild the file with explicit tab separators:
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n" +
                "c\tdnv\tnet/minecraft/class_2248\tnet/minecraft/block/Block\n" +
                "\tm\t()Ldpb;\tn\tmethod_9564\tgetDefaultState\n" +
                "\tf\tLgs;\tj\tfield_10651\tSTATE_IDS\n"
        }
        val file = writeTiny(tmp, tiny)
        val parsed = TinyV2Parser.parse(file)

        assertEquals(listOf("official", "intermediary", "named"), parsed.namespaces)
        assertEquals(1, parsed.classes.size)

        val cls = parsed.classes[0]
        assertEquals(listOf("dnv", "net/minecraft/class_2248", "net/minecraft/block/Block"), cls.names)
        assertEquals(1, cls.methods.size)
        assertEquals(1, cls.fields.size)

        val m = cls.methods[0]
        assertEquals(listOf("n", "method_9564", "getDefaultState"), m.names)
        assertTrue(m.descs[0]!!.startsWith("()L"), "descriptor preserved: ${m.descs}")

        val f = cls.fields[0]
        assertEquals(listOf("j", "field_10651", "STATE_IDS"), f.names)
    }

    @Test
    fun `parses intermediary tiny v1`(@TempDir tmp: Path) {
        // Intermediary mappings ship as tiny v1 (header "v1\tofficial\tintermediary"). MappingReader
        // auto-detects the format, so the same TinyV2Parser handles them.
        val tiny = "v1\tofficial\tintermediary\n" +
            "CLASS\ta\tnet/minecraft/class_1158\n" +
            "METHOD\ta\t()V\tb\tmethod_100\n" +
            "FIELD\ta\t[F\tc\tfield_5656\n"
        val file = writeTiny(tmp, tiny)
        val parsed = TinyV2Parser.parse(file)

        assertEquals(listOf("official", "intermediary"), parsed.namespaces)
        val cls = parsed.classes.single()
        assertEquals(listOf("a", "net/minecraft/class_1158"), cls.names)
        val m = cls.methods.single()
        assertEquals(listOf("b", "method_100"), m.names)
        assertEquals("()V", m.descs[0])
        val f = cls.fields.single()
        assertEquals(listOf("c", "field_5656"), f.names)
        assertEquals("[F", f.descs[0])
    }

    @Test
    fun `parses mojmap-style tiny v2 with two namespaces`(@TempDir tmp: Path) {
        val tiny = "tiny\t2\t0\tofficial\tnamed\n" +
            "c\tdnv\tnet/minecraft/world/level/block/Block\n" +
            "\tm\t()Ldpb;\tn\tdefaultBlockState\n"
        val file = writeTiny(tmp, tiny)
        val parsed = TinyV2Parser.parse(file)
        assertEquals(listOf("official", "named"), parsed.namespaces)
        val cls = parsed.classes.single()
        assertEquals("dnv", cls.names[0])
        assertEquals("net/minecraft/world/level/block/Block", cls.names[1])
        assertEquals("defaultBlockState", cls.methods.single().names[1])
    }
}

class CorrespondenceResolverTest {

    // Helpers --------------------------------------------------------------------------------

    private fun yarnTree() = ParsedMappings(
        namespaces = listOf("official", "intermediary", "named"),
        classes = listOf(
            ParsedClass(
                names = listOf("dnv", "net/minecraft/class_2248", "net/minecraft/block/Block"),
                methods = listOf(
                    ParsedMethod(
                        names = listOf("n", "method_9564", "getDefaultState"),
                        descs = listOf("()Ldpb;", "()Lnet/minecraft/class_2680;", "()Lnet/minecraft/class_2680;"),
                    )
                ),
                fields = listOf(
                    ParsedField(
                        names = listOf("j", "field_10651", "STATE_IDS"),
                        descs = listOf("Lgs;", "Lnet/minecraft/class_2378;", "Lnet/minecraft/class_2378;"),
                    )
                ),
            )
        ),
    )

    private fun mojTree() = ParsedMappings(
        namespaces = listOf("official", "named"),
        classes = listOf(
            ParsedClass(
                names = listOf("dnv", "net/minecraft/world/level/block/Block"),
                methods = listOf(
                    ParsedMethod(
                        names = listOf("n", "defaultBlockState"),
                        descs = listOf("()Ldpb;", "()Lnet/minecraft/world/level/block/state/BlockState;"),
                    )
                ),
                fields = listOf(
                    ParsedField(
                        names = listOf("j", "BLOCK_STATE_REGISTRY"),
                        descs = listOf("Lgs;", "Lnet/minecraft/core/IdMapper;"),
                    )
                ),
            )
        ),
    )

    @Test
    fun `joins yarn and mojmap by obfuscated names`() {
        val unified = CorrespondenceResolver.resolve(
            intermediary = null,
            yarn = yarnTree(),
            mojmap = mojTree(),
        )
        assertEquals(1, unified.size)
        val cls = unified.single()
        assertEquals("dnv", cls.obfName)
        assertEquals("net/minecraft/class_2248", cls.intermediaryName)
        assertEquals("net/minecraft/block/Block", cls.yarnName)
        assertEquals("net/minecraft/world/level/block/Block", cls.mojmapName)

        val m = cls.methods.single()
        assertEquals("getDefaultState", m.yarnName)
        assertEquals("defaultBlockState", m.mojmapName)
        assertEquals("method_9564", m.intermediaryName)

        val f = cls.fields.single()
        assertEquals("STATE_IDS", f.yarnName)
        assertEquals("BLOCK_STATE_REGISTRY", f.mojmapName)
        assertEquals("field_10651", f.intermediaryName)
    }

    @Test
    fun `falls back to mojmap-only when yarn and intermediary are missing`() {
        val unified = CorrespondenceResolver.resolve(null, null, mojTree())
        val cls = unified.single()
        assertEquals("dnv", cls.obfName)
        assertEquals("net/minecraft/world/level/block/Block", cls.mojmapName)
        assertEquals(null, cls.yarnName)
        assertEquals(null, cls.intermediaryName)
        assertEquals("defaultBlockState", cls.methods.single().mojmapName)
    }

    @Test
    fun `returns empty when no source provided`() {
        assertEquals(emptyList(), CorrespondenceResolver.resolve(null, null, null))
    }

    @Test
    fun `mojmap-only members are recovered when yarn class has no matching obf`() {
        val unified = CorrespondenceResolver.resolve(null, yarnTree(), null)
        val cls = unified.single()
        assertEquals(null, cls.mojmapName)
        assertNotNull(cls.yarnName)
    }

    @Test
    fun `full outer join recovers mojmap-only classes with presence label`() {
        val yarn = ParsedMappings(
            namespaces = listOf("official", "intermediary", "named"),
            classes = listOf(
                ParsedClass(listOf("a", "net/minecraft/class_1", "net/minecraft/Shared"), emptyList(), emptyList()),
            ),
        )
        val mojmap = ParsedMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(
                ParsedClass(listOf("a", "net/minecraft/world/Shared"), emptyList(), emptyList()),
                ParsedClass(listOf("b", "net/minecraft/world/MojOnly"), emptyList(), emptyList()),
            ),
        )
        val unified = CorrespondenceResolver.resolve(null, yarn, mojmap)
        assertEquals(2, unified.size)

        val shared = unified.first { it.obfName == "a" }
        assertEquals(CorrespondenceResolver.PRESENCE_BOTH, shared.presence)
        assertEquals("net/minecraft/Shared", shared.yarnName)
        assertEquals("net/minecraft/world/Shared", shared.mojmapName)

        val mojOnly = unified.first { it.obfName == "b" }
        assertEquals(CorrespondenceResolver.PRESENCE_MOJMAP_ONLY, mojOnly.presence)
        assertEquals(null, mojOnly.yarnName)
        assertEquals("net/minecraft/world/MojOnly", mojOnly.mojmapName)
    }

    @Test
    fun `yarn-only class is labelled yarn_only`() {
        val yarn = ParsedMappings(
            namespaces = listOf("official", "intermediary", "named"),
            classes = listOf(
                ParsedClass(listOf("a", "net/minecraft/class_1", "net/minecraft/YarnOnly"), emptyList(), emptyList()),
            ),
        )
        val mojmap = ParsedMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(
                ParsedClass(listOf("b", "net/minecraft/world/Other"), emptyList(), emptyList()),
            ),
        )
        val unified = CorrespondenceResolver.resolve(null, yarn, mojmap)
        assertEquals(CorrespondenceResolver.PRESENCE_YARN_ONLY, unified.first { it.obfName == "a" }.presence)
    }

    @Test
    fun `overloaded methods sharing an obf name are distinguished by descriptor`() {
        // One obf class with two methods both named "a", differing only by descriptor — yarn maps
        // both to one name, mojmap to distinct names. A name-only join would be catastrophically wrong.
        val yarn = ParsedMappings(
            namespaces = listOf("official", "intermediary", "named"),
            classes = listOf(
                ParsedClass(
                    names = listOf("enq", "net/minecraft/class_x", "net/minecraft/Buffer"),
                    methods = listOf(
                        ParsedMethod(listOf("a", "method_1", "loadStatic"), listOf("(I)V", "(I)V", "(I)V")),
                        ParsedMethod(listOf("a", "method_2", "loadStatic"), listOf("(F)V", "(F)V", "(F)V")),
                    ),
                    fields = emptyList(),
                ),
            ),
        )
        val mojmap = ParsedMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(
                ParsedClass(
                    names = listOf("enq", "net/minecraft/world/Buffer"),
                    methods = listOf(
                        ParsedMethod(listOf("a", "preload"), listOf("(I)V", "(I)V")),
                        ParsedMethod(listOf("a", "getCompleteBuffer"), listOf("(F)V", "(F)V")),
                    ),
                    fields = emptyList(),
                ),
            ),
        )
        val cls = CorrespondenceResolver.resolve(null, yarn, mojmap).single()
        assertEquals(2, cls.methods.size)
        val intMethod = cls.methods.first { it.obfDesc == "(I)V" }
        assertEquals("loadStatic", intMethod.yarnName)
        assertEquals("preload", intMethod.mojmapName)
        val floatMethod = cls.methods.first { it.obfDesc == "(F)V" }
        assertEquals("loadStatic", floatMethod.yarnName)
        assertEquals("getCompleteBuffer", floatMethod.mojmapName)
    }

    @Test
    fun `mojmap-only members within a shared class are recovered`() {
        val yarn = ParsedMappings(
            namespaces = listOf("official", "intermediary", "named"),
            classes = listOf(
                ParsedClass(
                    names = listOf("a", "net/minecraft/class_1", "net/minecraft/Shared"),
                    methods = listOf(ParsedMethod(listOf("x", "method_1", "shared"), listOf("()V", "()V", "()V"))),
                    fields = emptyList(),
                ),
            ),
        )
        val mojmap = ParsedMappings(
            namespaces = listOf("official", "named"),
            classes = listOf(
                ParsedClass(
                    names = listOf("a", "net/minecraft/world/Shared"),
                    methods = listOf(
                        ParsedMethod(listOf("x", "shared"), listOf("()V", "()V")),
                        ParsedMethod(listOf("y", "mojOnly"), listOf("()I", "()I")),
                    ),
                    fields = emptyList(),
                ),
            ),
        )
        val cls = CorrespondenceResolver.resolve(null, yarn, mojmap).single()
        assertEquals(2, cls.methods.size)
        val mojOnly = cls.methods.first { it.obfName == "y" }
        assertEquals(null, mojOnly.yarnName)
        assertEquals("mojOnly", mojOnly.mojmapName)
    }
}
