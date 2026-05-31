package com.codesage.ide.inline.prompt

import com.codesage.ide.inline.InlineChatMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InlineChatPromptsTest {

    @Test
    fun `system prompt should contain inline chat rules`() {
        val prompt = InlineChatPrompts.INLINE_SYSTEM_PROMPT
        assertTrue(prompt.contains("CodeSage"))
        assertTrue(prompt.contains("Diff"))
        assertTrue(prompt.contains("```language"))
    }

    @Test
    fun `explain prompt should contain code and request`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.EXPLAIN,
            selectedCode = "val x = 1",
            language = "Kotlin",
            userInstruction = ""
        )

        assertTrue(prompt.contains("val x = 1"))
        assertTrue(prompt.contains("Kotlin"))
        assertTrue(prompt.contains("解释"))
    }

    @Test
    fun `refactor prompt should contain user instruction`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.REFACTOR,
            selectedCode = "fun foo() {}",
            language = "Java",
            userInstruction = "extract method"
        )

        assertTrue(prompt.contains("foo"))
        assertTrue(prompt.contains("extract method"))
        assertTrue(prompt.contains("重构"))
    }

    @Test
    fun `fix prompt should contain error info`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.FIX,
            selectedCode = "val x = null",
            language = "Kotlin",
            userInstruction = "",
            diagnostics = listOf("Null pointer exception")
        )

        assertTrue(prompt.contains("修复"))
        assertTrue(prompt.contains("Null pointer exception"))
    }

    @Test
    fun `fix prompt should work without diagnostics`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.FIX,
            selectedCode = "val x = null",
            language = "Kotlin",
            userInstruction = ""
        )

        assertTrue(prompt.contains("修复"))
        assertTrue(prompt.contains("代码存在错误"))
    }

    @Test
    fun `test prompt should ask for unit tests`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.TEST,
            selectedCode = "fun add(a: Int, b: Int) = a + b",
            language = "Kotlin",
            userInstruction = ""
        )

        assertTrue(prompt.contains("单元测试"))
        assertTrue(prompt.contains("add"))
    }

    @Test
    fun `chat prompt should include user instruction`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.CHAT,
            selectedCode = "val x = 1",
            language = "Kotlin",
            userInstruction = "Make this const"
        )

        assertTrue(prompt.contains("val x = 1"))
        assertTrue(prompt.contains("Make this const"))
    }

    @Test
    fun `prompt should handle null language`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.EXPLAIN,
            selectedCode = "x = 1",
            language = null,
            userInstruction = ""
        )

        assertTrue(prompt.contains("x = 1"))
    }

    @Test
    fun `prompt should handle empty language`() {
        val prompt = InlineChatPrompts.buildPrompt(
            mode = InlineChatMode.EXPLAIN,
            selectedCode = "x = 1",
            language = "",
            userInstruction = ""
        )

        assertTrue(prompt.contains("x = 1"))
    }
}
