package com.codesage.ide.inline.modification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CodeChangeTest {

    @Test
    fun `replace change should be valid`() {
        val change = CodeChange(
            type = ChangeType.REPLACE,
            startLine = 5,
            endLine = 7,
            newContent = "new line 1\nnew line 2",
            originalContent = "old line 1\nold line 2\nold line 3"
        )

        assertTrue(change.isValid())
        assertEquals(3, change.affectedLineCount())
        assertEquals(2, change.newLineCount())
    }

    @Test
    fun `insert change should have zero affected lines`() {
        val change = CodeChange(
            type = ChangeType.INSERT,
            startLine = 10,
            newContent = "inserted line 1\ninserted line 2"
        )

        assertTrue(change.isValid())
        assertEquals(0, change.affectedLineCount())
        assertEquals(2, change.newLineCount())
    }

    @Test
    fun `delete change should calculate affected lines`() {
        val change = CodeChange(
            type = ChangeType.DELETE,
            startLine = 3,
            endLine = 5
        )

        assertTrue(change.isValid())
        assertEquals(3, change.affectedLineCount())
        assertEquals(0, change.newLineCount())
    }

    @Test
    fun `invalid change should have negative start line`() {
        val change = CodeChange(
            type = ChangeType.REPLACE,
            startLine = -1,
            endLine = 3
        )
        assertFalse(change.isValid())
    }

    @Test
    fun `invalid change should have end line before start line`() {
        val change = CodeChange(
            type = ChangeType.REPLACE,
            startLine = 5,
            endLine = 3
        )
        assertFalse(change.isValid())
    }

    @Test
    fun `single line replace should have affected count of 1`() {
        val change = CodeChange(
            type = ChangeType.REPLACE,
            startLine = 5,
            endLine = 5
        )
        assertEquals(1, change.affectedLineCount())
    }

    @Test
    fun `empty new content should have zero new lines`() {
        val change = CodeChange(
            type = ChangeType.INSERT,
            startLine = 0,
            newContent = ""
        )
        assertEquals(0, change.newLineCount())
    }

    @Test
    fun `code changes collection should be valid when all changes valid`() {
        val changes = CodeChanges(
            changes = listOf(
                CodeChange(ChangeType.REPLACE, 1, 2, "new1"),
                CodeChange(ChangeType.INSERT, 5, newContent = "new2")
            )
        )

        assertTrue(changes.isValid())
        assertEquals(2, changes.changeCount())
    }

    @Test
    fun `code changes collection should be invalid when any change invalid`() {
        val changes = CodeChanges(
            changes = listOf(
                CodeChange(ChangeType.REPLACE, 1, 2, "new1"),
                CodeChange(ChangeType.REPLACE, -1, 2, "new2")
            )
        )

        assertFalse(changes.isValid())
    }

    @Test
    fun `should count total deleted lines`() {
        val changes = CodeChanges(
            changes = listOf(
                CodeChange(ChangeType.DELETE, 1, 3), // 3 lines
                CodeChange(ChangeType.REPLACE, 5, 7), // 3 lines
                CodeChange(ChangeType.DELETE, 10, 10) // 1 line
            )
        )

        assertEquals(4, changes.totalDeletedLines())
    }

    @Test
    fun `should count total added lines`() {
        val changes = CodeChanges(
            changes = listOf(
                CodeChange(ChangeType.INSERT, 1, newContent = "a\nb"), // 2 lines
                CodeChange(ChangeType.REPLACE, 5, 7, "c\nd\ne"), // 3 lines
                CodeChange(ChangeType.DELETE, 10, 10) // 0 lines
            )
        )

        assertEquals(5, changes.totalAddedLines())
    }

    @Test
    fun `description should be stored`() {
        val changes = CodeChanges(
            changes = emptyList(),
            description = "Refactor extraction"
        )
        assertEquals("Refactor extraction", changes.description)
    }
}
