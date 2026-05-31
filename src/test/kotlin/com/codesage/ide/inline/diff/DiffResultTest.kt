package com.codesage.ide.inline.diff

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DiffResultTest {

    @Test
    fun `empty diff should have no changes`() {
        val result = DiffResult.EMPTY
        assertFalse(result.hasChanges)
        assertEquals(0, result.removedCount)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.modifiedCount)
    }

    @Test
    fun `should count removed lines`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.REMOVED, 0, "old1"),
                DiffLine(DiffType.REMOVED, 1, "old2"),
                DiffLine(DiffType.CONTEXT, 2, "same")
            )
        )
        assertTrue(result.hasChanges)
        assertEquals(2, result.removedCount)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.modifiedCount)
    }

    @Test
    fun `should count added lines`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.CONTEXT, 0, "same"),
                DiffLine(DiffType.ADDED, 1, "new1"),
                DiffLine(DiffType.ADDED, 2, "new2")
            )
        )
        assertTrue(result.hasChanges)
        assertEquals(0, result.removedCount)
        assertEquals(2, result.addedCount)
    }

    @Test
    fun `should count modified lines`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.MODIFIED, 0, "new", "old", emptyList()),
                DiffLine(DiffType.CONTEXT, 1, "same")
            )
        )
        assertEquals(0, result.removedCount)
        assertEquals(0, result.addedCount)
        assertEquals(1, result.modifiedCount)
    }

    @Test
    fun `should return single change block for consecutive changes`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.CONTEXT, 0, "before"),
                DiffLine(DiffType.REMOVED, 1, "old1"),
                DiffLine(DiffType.ADDED, 1, "new1"),
                DiffLine(DiffType.REMOVED, 2, "old2"),
                DiffLine(DiffType.CONTEXT, 3, "after")
            )
        )

        val blocks = result.getChangeBlocks()
        assertEquals(1, blocks.size)

        val block = blocks[0]
        assertEquals(1, block.startLine)
        assertEquals(2, block.endLine)
        assertEquals(3, block.lines.size)
        assertEquals("-2 +1", block.description)
    }

    @Test
    fun `should return multiple blocks for separated changes`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.CONTEXT, 0, "ctx1"),
                DiffLine(DiffType.REMOVED, 1, "old1"),
                DiffLine(DiffType.ADDED, 1, "new1"),
                DiffLine(DiffType.CONTEXT, 2, "ctx2"),
                DiffLine(DiffType.CONTEXT, 3, "ctx3"),
                DiffLine(DiffType.REMOVED, 4, "old2"),
                DiffLine(DiffType.ADDED, 4, "new2"),
                DiffLine(DiffType.ADDED, 5, "new3"),
                DiffLine(DiffType.CONTEXT, 6, "ctx4")
            )
        )

        val blocks = result.getChangeBlocks()
        assertEquals(2, blocks.size)

        assertEquals(1, blocks[0].startLine)
        assertEquals(1, blocks[0].endLine)
        assertEquals("-1 +1", blocks[0].description)

        assertEquals(4, blocks[1].startLine)
        assertEquals(5, blocks[1].endLine)
        assertEquals("-1 +2", blocks[1].description)
    }

    @Test
    fun `should return empty blocks when no changes`() {
        val result = DiffResult(
            lines = listOf(
                DiffLine(DiffType.CONTEXT, 0, "line1"),
                DiffLine(DiffType.CONTEXT, 1, "line2")
            )
        )

        val blocks = result.getChangeBlocks()
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `change block id should be unique`() {
        val lines = listOf(
            DiffLine(DiffType.REMOVED, 1, "old1"),
            DiffLine(DiffType.ADDED, 1, "new1"),
            DiffLine(DiffType.CONTEXT, 2, "ctx"),
            DiffLine(DiffType.REMOVED, 3, "old2"),
            DiffLine(DiffType.ADDED, 3, "new2")
        )
        val result = DiffResult(lines = lines)

        val blocks = result.getChangeBlocks()
        assertEquals(2, blocks.size)
        assertNotEquals(blocks[0].id, blocks[1].id)
    }

    @Test
    fun `change block description should show only added when no removed`() {
        val lines = listOf(
            DiffLine(DiffType.ADDED, 1, "new1"),
            DiffLine(DiffType.ADDED, 2, "new2")
        )
        val result = DiffResult(lines = lines)

        val blocks = result.getChangeBlocks()
        assertEquals("+2", blocks[0].description)
    }

    @Test
    fun `change block description should show only removed when no added`() {
        val lines = listOf(
            DiffLine(DiffType.REMOVED, 1, "old1"),
            DiffLine(DiffType.REMOVED, 2, "old2")
        )
        val result = DiffResult(lines = lines)

        val blocks = result.getChangeBlocks()
        assertEquals("-2", blocks[0].description)
    }

    @Test
    fun `diff line should support char diffs for modified lines`() {
        val charDiffs = listOf(
            CharDiff(start = 5, end = 8, isDeletion = true),
            CharDiff(start = 5, end = 5, isDeletion = false, replacement = "NEW")
        )
        val line = DiffLine(
            type = DiffType.MODIFIED,
            lineNumber = 10,
            content = "val NEW = 1",
            oldContent = "val old = 1",
            charDiffs = charDiffs
        )

        assertEquals(DiffType.MODIFIED, line.type)
        assertEquals(10, line.lineNumber)
        assertEquals("val NEW = 1", line.content)
        assertEquals("val old = 1", line.oldContent)
        assertEquals(2, line.charDiffs.size)
    }
}
