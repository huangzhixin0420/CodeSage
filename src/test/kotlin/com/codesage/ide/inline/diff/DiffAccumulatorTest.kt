package com.codesage.ide.inline.diff

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DiffAccumulatorTest {

    @Test
    fun `should return null when no code block extracted yet`() {
        val accumulator = DiffAccumulator("val x = 1")
        val result = accumulator.append("Some explanation text")
        assertNull(result)
        assertFalse(accumulator.hasExtractedCodeBlock())
    }

    @Test
    fun `should extract code block from markdown fence`() {
        val accumulator = DiffAccumulator("val x = 1")
        val text = """
            Here's the fix:
            ```kotlin
            val x = 2
            ```
        """.trimIndent()

        val result = accumulator.append(text)
        assertNotNull(result)
        assertTrue(accumulator.hasExtractedCodeBlock())
    }

    @Test
    fun `should compute diff when code block is extracted`() {
        val original = "val x = 1\nval y = 2"
        val accumulator = DiffAccumulator(original)

        val text = """
            ```kotlin
            val x = 1
            val y = 3
            ```
        """.trimIndent()

        val result = accumulator.append(text)
        assertNotNull(result)
        assertTrue(result!!.hasChanges)
        assertEquals(1, result.removedCount)
        assertEquals(1, result.addedCount)
    }

    @Test
    fun `should not return diff if unchanged`() {
        val original = "val x = 1"
        val accumulator = DiffAccumulator(original)

        val text = """
            ```kotlin
            val x = 1
            ```
        """.trimIndent()

        val result = accumulator.append(text)
        assertNotNull(result)
        assertFalse(result!!.hasChanges)
    }

    @Test
    fun `finalize should return diff even without code block markers`() {
        val original = "val x = 1"
        val accumulator = DiffAccumulator(original)

        accumulator.append("val x = 2")
        val result = accumulator.finalize()

        assertTrue(result.hasChanges)
    }

    @Test
    fun `should accumulate buffer content`() {
        val accumulator = DiffAccumulator("original")
        accumulator.append("Hello")
        accumulator.append(" World")

        assertEquals("Hello World", accumulator.getBuffer())
    }

    @Test
    fun `extractCodeBlock should extract content from markdown`() {
        val text = """
            ```kotlin
            val x = 1
            val y = 2
            ```
        """.trimIndent()

        val result = DiffAccumulator.extractCodeBlock(text)
        assertEquals("val x = 1\nval y = 2", result)
    }

    @Test
    fun `extractCodeBlock should handle no language specifier`() {
        val text = """
            ```
            some code
            ```
        """.trimIndent()

        val result = DiffAccumulator.extractCodeBlock(text)
        assertEquals("some code", result)
    }

    @Test
    fun `extractCodeBlock should return null for plain text`() {
        val result = DiffAccumulator.extractCodeBlock("just plain text")
        assertNull(result)
    }

    @Test
    fun `computeLineDiff should detect added lines`() {
        val oldCode = "line1\nline2"
        val newCode = "line1\nline2\nline3"

        val result = DiffAccumulator.computeLineDiff(oldCode, newCode)

        assertTrue(result.hasChanges)
        assertEquals(0, result.removedCount)
        assertEquals(1, result.addedCount)
        assertEquals(2, result.lines.count { it.type == DiffType.CONTEXT })
    }

    @Test
    fun `computeLineDiff should detect removed lines`() {
        val oldCode = "line1\nline2\nline3"
        val newCode = "line1\nline3"

        val result = DiffAccumulator.computeLineDiff(oldCode, newCode)

        assertTrue(result.hasChanges)
        assertEquals(1, result.removedCount)
        assertEquals(0, result.addedCount)
    }

    @Test
    fun `computeLineDiff should detect replaced lines`() {
        val oldCode = "line1\noldLine\nline3"
        val newCode = "line1\nnewLine\nline3"

        val result = DiffAccumulator.computeLineDiff(oldCode, newCode)

        assertTrue(result.hasChanges)
        assertEquals(1, result.removedCount)
        assertEquals(1, result.addedCount)
    }

    @Test
    fun `computeLineDiff should return all context for identical code`() {
        val code = "line1\nline2\nline3"
        val result = DiffAccumulator.computeLineDiff(code, code)

        assertFalse(result.hasChanges)
        assertEquals(3, result.lines.size)
        assertTrue(result.lines.all { it.type == DiffType.CONTEXT })
    }

    @Test
    fun `computeLineDiff should handle completely different code`() {
        val oldCode = "foo\nbar"
        val newCode = "baz\nqux"

        val result = DiffAccumulator.computeLineDiff(oldCode, newCode)

        assertTrue(result.hasChanges)
        assertEquals(2, result.removedCount)
        assertEquals(2, result.addedCount)
    }

    @Test
    fun `computeLineDiff should handle empty old code`() {
        val result = DiffAccumulator.computeLineDiff("", "line1\nline2")
        assertEquals(2, result.addedCount)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun `computeLineDiff should handle empty new code`() {
        val result = DiffAccumulator.computeLineDiff("line1\nline2", "")
        assertEquals(0, result.addedCount)
        assertEquals(2, result.removedCount)
    }

    @Test
    fun `stream append should only return diff when changed`() {
        val original = "val x = 1\nval y = 2"
        val accumulator = DiffAccumulator(original)

        // First append: code block appears
        val r1 = accumulator.append("```kotlin\nval x = 1\nval y = 2\n```")
        assertNotNull(r1)
        assertFalse(r1!!.hasChanges)

        // Second append: no new code block or change
        val r2 = accumulator.append(" More text")
        // Buffer changed but code block content same, should return null or same diff
        assertNull(r2)
    }
}
