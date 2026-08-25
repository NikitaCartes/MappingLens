package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.db.ClassDecl
import xyz.nikitacartes.mappinglens.service.HierarchyService.Companion.computeHierarchy
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyServiceTest {

    // A/ (abstract) -> B implements I; B -> C, B -> D. I is an interface. Object is filtered out.
    // The subtype edges are stored with each class, as the declaration index holds them.
    private val classes = mapOf(
        "A" to decl(Opcodes.ACC_ABSTRACT, supertypes = emptyList(), subtypes = listOf("B")),
        "I" to decl(Opcodes.ACC_INTERFACE, supertypes = emptyList(), subtypes = listOf("B")),
        "B" to decl(0, supertypes = listOf("A", "I"), subtypes = listOf("C", "D")),
        "C" to decl(0, supertypes = listOf("B"), subtypes = emptyList()),
        "D" to decl(0, supertypes = listOf("B"), subtypes = emptyList()),
    )

    private fun decl(access: Int, supertypes: List<String>, subtypes: List<String>) =
        ClassDecl(access, supertypes, subtypes, emptyList())

    @Test
    fun `collects ancestors and descendants with edges`() {
        val r = computeHierarchy(classes::get, "v", "mojmap", "B")!!
        val names = r.nodes.map { it.name }.toSet()
        assertEquals(setOf("A", "I", "B", "C", "D"), names)
        val edges = r.edges.map { it.parent to it.child }.toSet()
        assertEquals(setOf("A" to "B", "I" to "B", "B" to "C", "B" to "D"), edges)
    }

    @Test
    fun `flags interfaces and abstract classes`() {
        val r = computeHierarchy(classes::get, "v", "mojmap", "B")!!
        assertTrue(r.nodes.single { it.name == "I" }.isInterface)
        assertTrue(r.nodes.single { it.name == "A" }.isAbstract)
        assertTrue(r.nodes.single { it.name == "B" }.let { !it.isInterface && !it.isAbstract })
    }

    @Test
    fun `leaf class has no subtype edges`() {
        val r = computeHierarchy(classes::get, "v", "mojmap", "C")!!
        assertEquals(setOf("A", "I", "B", "C"), r.nodes.map { it.name }.toSet())
        assertTrue(r.edges.none { it.parent == "C" })
    }

    @Test
    fun `unknown root returns null`() {
        assertNull(computeHierarchy(classes::get, "v", "mojmap", "Nope"))
    }

    @Test
    fun `object supertype is filtered out`() {
        val withObject = mapOf("X" to decl(0, supertypes = listOf("java/lang/Object"), subtypes = emptyList()))
        val r = computeHierarchy(withObject::get, "v", "yarn", "X")!!
        assertEquals(setOf("X"), r.nodes.map { it.name }.toSet())
        assertTrue(r.edges.isEmpty())
    }
}
