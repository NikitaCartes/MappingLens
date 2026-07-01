package dev.mappinglens.service

import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import dev.mappinglens.model.SourceToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TokenServiceTest {

    private val source = """
        package p;
        import java.util.List;
        public class Foo {
            int count;
            List<String> items;
            String[] arr;
            void run(String s, int n) {
                this.count = n;
                s.trim();
                items.size();
            }
            <T> T id(T x) { return x; }
        }
    """.trimIndent()

    // Resolved against the JDK only (Foo itself is resolved from the same compilation unit).
    private val tokens: List<SourceToken> =
        TokenService.tokensFor(source, JavaSymbolSolver(ReflectionTypeSolver()))

    private fun find(type: String, name: String?, className: String): SourceToken? =
        tokens.firstOrNull { it.type == type && it.name == name && it.className == className }

    @Test
    fun `method descriptors use JVM param and return types`() {
        val run = find("method", "run", "p/Foo")
        assertNotNull(run, "run() should resolve")
        assertEquals("(Ljava/lang/String;I)V", run.descriptor)
        assertTrue(run.declaration)

        assertEquals("()Ljava/lang/String;", find("method", "trim", "java/lang/String")?.descriptor)
        assertEquals("()I", find("method", "size", "java/util/List")?.descriptor)
    }

    @Test
    fun `field descriptors cover primitive, generic-erased, and array types`() {
        assertEquals("I", find("field", "count", "p/Foo")?.descriptor)
        assertEquals("Ljava/util/List;", find("field", "items", "p/Foo")?.descriptor)
        assertEquals("[Ljava/lang/String;", find("field", "arr", "p/Foo")?.descriptor)
    }

    @Test
    fun `type variables erase to Object`() {
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", find("method", "id", "p/Foo")?.descriptor)
    }

    @Test
    fun `class tokens carry internal owner names`() {
        assertNotNull(find("class", null, "java/lang/String"), "String type should be a class token")
        assertNotNull(find("class", null, "java/util/List"), "List type should be a class token")
    }

    @Test
    fun `tokens carry a 1-based range`() {
        val run = find("method", "run", "p/Foo")!!
        assertTrue(run.startLine >= 1 && run.startColumn >= 1)
        assertTrue(run.endColumn > run.startColumn)
    }
}
