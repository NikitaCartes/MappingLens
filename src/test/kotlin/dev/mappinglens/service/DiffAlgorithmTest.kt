package dev.mappinglens.service

import org.jetbrains.exposed.sql.Database
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-logic checks for the Myers-primary patch engine (no DB access; the connection is unused). */
class DiffAlgorithmTest {
    private val service = DiffService(Database.connect("jdbc:sqlite::memory:"))

    @Test
    fun `detects a single line change`() {
        val diff = service.diffLinesForTest(listOf("a", "b", "c"), listOf("a", "x", "c"), ignoreWhitespace = false)
        assertEquals(listOf(" a", "-b", "+x", " c"), diff)
    }

    @Test
    fun `ignoreWhitespace collapses reindentation but keeps real changes`() {
        val old = listOf("\tint x = 1;", "\treturn x;")
        val new = listOf("    int x = 1;", "    return y;")
        assertEquals(0, service.diffLinesForTest(old, new, ignoreWhitespace = false).count { it.startsWith(" ") })
        val ws = service.diffLinesForTest(old, new, ignoreWhitespace = true)
        assertEquals(1, ws.count { it.startsWith(" ") }) // reindented `int x = 1;` is unchanged
        assertEquals(1, ws.count { it.startsWith("-") }) // `return x;` -> `return y;` is real
        assertEquals(1, ws.count { it.startsWith("+") })
    }

    @Test
    fun `one insertion in a large file stays one hunk, not a whole-file dump`() {
        // Guards the regression that made buildLineDiff dump a 4k-line class as one -/+ block once
        // the LCS matrix exceeded MAX_LCS_CELLS. Myers must report exactly the single insertion.
        val big = (1..3000).map { "line $it" }
        val inserted = big.toMutableList().apply { add(1500, "INSERTED") }
        val diff = service.diffLinesForTest(big, inserted, ignoreWhitespace = false)
        assertEquals(0, diff.count { it.startsWith("-") })
        assertEquals(listOf("+INSERTED"), diff.filter { it.startsWith("+") })
    }
}
