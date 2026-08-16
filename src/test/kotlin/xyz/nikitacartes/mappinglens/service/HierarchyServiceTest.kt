package xyz.nikitacartes.mappinglens.service

import xyz.nikitacartes.mappinglens.service.HierarchyService.Companion.ClassInfo
import xyz.nikitacartes.mappinglens.service.HierarchyService.Companion.computeHierarchy
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HierarchyServiceTest {

    // A/ (abstract) -> B implements I; B -> C, B -> D. I is an interface. Object is filtered out.
    private val classes = mapOf(
        "A" to ClassInfo(null, emptyList(), Opcodes.ACC_ABSTRACT),
        "I" to ClassInfo(null, emptyList(), Opcodes.ACC_INTERFACE),
        "B" to ClassInfo("A", listOf("I"), 0),
        "C" to ClassInfo("B", emptyList(), 0),
        "D" to ClassInfo("B", emptyList(), 0),
    )

    @Test
    fun `collects ancestors and descendants with edges`() {
        val r = computeHierarchy(classes, "v", "mojmap", "B")!!
        val names = r.nodes.map { it.name }.toSet()
        assertEquals(setOf("A", "I", "B", "C", "D"), names)
        val edges = r.edges.map { it.parent to it.child }.toSet()
        assertEquals(setOf("A" to "B", "I" to "B", "B" to "C", "B" to "D"), edges)
    }

    @Test
    fun `flags interfaces and abstract classes`() {
        val r = computeHierarchy(classes, "v", "mojmap", "B")!!
        assertTrue(r.nodes.single { it.name == "I" }.isInterface)
        assertTrue(r.nodes.single { it.name == "A" }.isAbstract)
        assertTrue(r.nodes.single { it.name == "B" }.let { !it.isInterface && !it.isAbstract })
    }

    @Test
    fun `leaf class has no subtype edges`() {
        val r = computeHierarchy(classes, "v", "mojmap", "C")!!
        assertEquals(setOf("A", "I", "B", "C"), r.nodes.map { it.name }.toSet())
        assertTrue(r.edges.none { it.parent == "C" })
    }

    @Test
    fun `unknown root returns null`() {
        assertNull(computeHierarchy(classes, "v", "mojmap", "Nope"))
    }

    @Test
    fun `object supertype is filtered out`() {
        val withObject = mapOf("X" to ClassInfo("java/lang/Object", emptyList(), 0))
        val r = computeHierarchy(withObject, "v", "yarn", "X")!!
        assertEquals(setOf("X"), r.nodes.map { it.name }.toSet())
        assertTrue(r.edges.isEmpty())
    }
}
