package com.codesage.ide.inline.diff

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CharDiffComputerTest {

    @Test
    fun `should return empty diff for identical strings`() {
        val result = CharDiffComputer.compute("hello", "hello")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return single insertion for empty old text`() {
        val result = CharDiffComputer.compute("", "abc")
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(0, result[0].end)
        assertFalse(result[0].isDeletion)
        assertEquals("abc", result[0].replacement)
    }

    @Test
    fun `should return single deletion for empty new text`() {
        val result = CharDiffComputer.compute("abc", "")
        assertEquals(1, result.size)
        assertEquals(0, result[0].start)
        assertEquals(3, result[0].end)
        assertTrue(result[0].isDeletion)
    }

    @Test
    fun `should detect single character insertion`() {
        val result = CharDiffComputer.compute("helo", "hello")
        assertEquals(1, result.size)
        assertEquals(2, result[0].start)
        assertEquals(2, result[0].end)
        assertFalse(result[0].isDeletion)
        assertEquals("l", result[0].replacement)
    }

    @Test
    fun `should detect single character deletion`() {
        val result = CharDiffComputer.compute("hello", "helo")
        assertEquals(1, result.size)
        assertEquals(2, result[0].start)
        assertEquals(3, result[0].end)
        assertTrue(result[0].isDeletion)
    }

    @Test
    fun `should detect single character replacement`() {
        val result = CharDiffComputer.compute("hello", "hallo")
        // 'e' -> 'a'
        assertEquals(2, result.size)

        val deletion = result.find { it.isDeletion }
        assertNotNull(deletion)
        assertEquals(1, deletion!!.start)
        assertEquals(2, deletion.end)

        val insertion = result.find { !it.isDeletion }
        assertNotNull(insertion)
        assertEquals(1, insertion!!.start)
        assertEquals(1, insertion.end)
        assertEquals("a", insertion.replacement)
    }

    @Test
    fun `should detect multiple insertions`() {
        val result = CharDiffComputer.compute("abc", "axbyc")
        // Insert 'x' after 'a', insert 'y' after 'b'
        // 不连续的插入产生独立的 diff 块
        assertTrue(result.isNotEmpty())

        val insertions = result.filter { !it.isDeletion }
        assertEquals(2, insertions.size)

        assertEquals(1, insertions[0].start)
        assertEquals("x", insertions[0].replacement)

        assertEquals(2, insertions[1].start)
        assertEquals("y", insertions[1].replacement)
    }

    @Test
    fun `should detect multiple deletions`() {
        val result = CharDiffComputer.compute("axbyc", "abc")
        // Delete 'x' and 'y' - 不连续的删除产生独立的 diff 块
        assertTrue(result.isNotEmpty())

        val deletions = result.filter { it.isDeletion }
        assertEquals(2, deletions.size)

        assertEquals(1, deletions[0].start)
        assertEquals(2, deletions[0].end)
        assertTrue(deletions[0].isDeletion)

        assertEquals(3, deletions[1].start)
        assertEquals(4, deletions[1].end)
        assertTrue(deletions[1].isDeletion)
    }

    @Test
    fun `should detect prefix change`() {
        val result = CharDiffComputer.compute("oldValue", "newValue")
        assertTrue(result.isNotEmpty())

        val deletion = result.find { it.isDeletion }
        assertNotNull(deletion)

        val insertion = result.find { !it.isDeletion }
        assertNotNull(insertion)
    }

    @Test
    fun `should detect suffix change`() {
        val result = CharDiffComputer.compute("valueOld", "valueNew")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `should handle whitespace changes`() {
        val result = CharDiffComputer.compute("val x=1", "val x = 1")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `should handle completely different strings`() {
        val result = CharDiffComputer.compute("foo", "bar")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `should handle long strings efficiently`() {
        val oldText = "a".repeat(1000)
        val newText = "a".repeat(500) + "b".repeat(500)

        val result = CharDiffComputer.compute(oldText, newText)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `char diff should have correct properties`() {
        val charDiff = CharDiff(
            start = 5,
            end = 8,
            isDeletion = true,
            replacement = null
        )

        assertEquals(5, charDiff.start)
        assertEquals(8, charDiff.end)
        assertTrue(charDiff.isDeletion)
        assertNull(charDiff.replacement)
    }

    @Test
    fun `insertion char diff should have replacement`() {
        val charDiff = CharDiff(
            start = 3,
            end = 3,
            isDeletion = false,
            replacement = "NEW"
        )

        assertFalse(charDiff.isDeletion)
        assertEquals("NEW", charDiff.replacement)
    }
}
