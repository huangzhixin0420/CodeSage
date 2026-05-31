package com.codesage.ide.inline

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InlineChatContextTest {

    @Test
    fun `should create context with default values`() {
        val context = InlineChatContext()

        assertNull(context.selectedText)
        assertEquals(0, context.startLine)
        assertEquals(0, context.endLine)
        assertEquals(InlineChatMode.CHAT, context.mode)
        assertNull(context.filePath)
        assertNull(context.language)
        assertTrue(context.diagnostics.isEmpty())
    }

    @Test
    fun `should create context with all values`() {
        val diagnostics = listOf(
            DiagnosticInfo("Null pointer risk", "WARNING", 10),
            DiagnosticInfo("Unused import", "INFO", 3)
        )

        val context = InlineChatContext(
            selectedText = "val x = 1",
            startLine = 5,
            endLine = 10,
            mode = InlineChatMode.REFACTOR,
            filePath = "/src/main.kt",
            language = "Kotlin",
            diagnostics = diagnostics
        )

        assertEquals("val x = 1", context.selectedText)
        assertEquals(5, context.startLine)
        assertEquals(10, context.endLine)
        assertEquals(InlineChatMode.REFACTOR, context.mode)
        assertEquals("/src/main.kt", context.filePath)
        assertEquals("Kotlin", context.language)
        assertEquals(2, context.diagnostics.size)
    }

    @Test
    fun `hasSelection should return true when selectedText is not blank`() {
        val contextWithSelection = InlineChatContext(selectedText = "code")
        assertTrue(contextWithSelection.hasSelection())

        val contextWithBlank = InlineChatContext(selectedText = "   ")
        assertFalse(contextWithBlank.hasSelection())

        val contextWithoutSelection = InlineChatContext()
        assertFalse(contextWithoutSelection.hasSelection())
    }

    @Test
    fun `selectedLineCount should calculate correctly`() {
        val singleLine = InlineChatContext(startLine = 5, endLine = 5)
        assertEquals(1, singleLine.selectedLineCount())

        val multiLine = InlineChatContext(startLine = 2, endLine = 8)
        assertEquals(7, multiLine.selectedLineCount())

        val invalid = InlineChatContext(startLine = 5, endLine = 3)
        assertEquals(1, invalid.selectedLineCount()) // coerced to at least 1
    }

    @Test
    fun `getDefaultPrompt should return correct prompt for each mode`() {
        assertEquals("", InlineChatContext(mode = InlineChatMode.CHAT).getDefaultPrompt())

        assertEquals(
            "解释这段代码的功能、关键逻辑、潜在问题和优化建议",
            InlineChatContext(mode = InlineChatMode.EXPLAIN).getDefaultPrompt()
        )

        assertEquals(
            "重构这段代码，提高可读性和性能",
            InlineChatContext(mode = InlineChatMode.REFACTOR).getDefaultPrompt()
        )

        assertEquals(
            "修复这段代码中的错误",
            InlineChatContext(mode = InlineChatMode.FIX).getDefaultPrompt()
        )

        assertEquals(
            "为这段代码生成单元测试",
            InlineChatContext(mode = InlineChatMode.TEST).getDefaultPrompt()
        )
    }

    @Test
    fun `diagnostic info should hold correct data`() {
        val diagnostic = DiagnosticInfo(
            message = "Type mismatch",
            severity = "ERROR",
            lineNumber = 42
        )

        assertEquals("Type mismatch", diagnostic.message)
        assertEquals("ERROR", diagnostic.severity)
        assertEquals(42, diagnostic.lineNumber)
    }
}
