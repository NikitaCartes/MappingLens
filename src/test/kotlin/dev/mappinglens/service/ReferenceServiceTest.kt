package dev.mappinglens.service

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceServiceTest {

    private fun emptyClass(name: String, configure: ClassWriter.() -> Unit = {}): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null)
        cw.configure()
        cw.visitEnd()
        return cw.toByteArray()
    }

    // B has static field x:I and static method foo()V. A.m() calls B.foo, reads B.x, and `new C`.
    private val bBytes = emptyClass("B") {
        visitField(ACC_PUBLIC or ACC_STATIC, "x", "I", null, null).visitEnd()
        visitMethod(ACC_PUBLIC or ACC_STATIC, "foo", "()V", null, null).apply {
            visitCode(); visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
        }
    }
    private val cBytes = emptyClass("C")
    private val aBytes = emptyClass("A") {
        visitMethod(ACC_PUBLIC, "m", "()V", null, null).apply {
            visitCode()
            visitFieldInsn(GETSTATIC, "B", "x", "I")
            visitInsn(POP)
            visitMethodInsn(INVOKESTATIC, "B", "foo", "()V", false)
            visitTypeInsn(NEW, "C")
            visitInsn(POP)
            visitInsn(RETURN)
            visitMaxs(0, 0); visitEnd()
        }
    }

    private val index = ReferenceService.scan(listOf(aBytes, bBytes, cBytes))
    private val referrer = ReferenceService.Referrer("A", "m", "()V", "method")

    @Test
    fun `records method, field, and type references to the enclosing method`() {
        assertEquals(setOf(referrer), index["B:foo:()V"])
        assertEquals(setOf(referrer), index["B:x:I"])
        assertTrue(referrer in (index["B"] ?: emptySet()), "class ref to B via member owners")
        assertEquals(setOf(referrer), index["C"], "type ref via NEW")
    }

    @Test
    fun `drops references to non-Minecraft (JDK) classes`() {
        assertTrue(index.keys.none { it.startsWith("java/") }, "JDK targets must not be indexed")
    }

    @Test
    fun `unreferenced class has no entry`() {
        assertTrue(index["A"] == null || index["A"]!!.isEmpty())
    }
}
