package com.codesage.agent.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * P1 6.2.3：EditMatchEngine 纯函数匹配引擎测试。
 */
class EditMatchEngineTest {

    @Test
    fun `exact unique match returns candidate`() {
        val content = "class Foo {\n    val x = 1\n}"
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1")
        assertTrue(result is EditMatchEngine.FindResult.Unique)
        val match = (result as EditMatchEngine.FindResult.Unique).match
        assertEquals(2, match.lineNumber)
        assertEquals("val x = 1", match.matchedText)
    }

    @Test
    fun `no match returns not found`() {
        val result = EditMatchEngine.findReplacementRegion("hello world", "missing")
        assertTrue(result is EditMatchEngine.FindResult.NotFound)
    }

    @Test
    fun `ambiguous match returns candidates with line numbers`() {
        val content = """
            fun a() {
                val x = 1
            }
            fun b() {
                val x = 1
            }
        """.trimIndent()
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1")
        assertTrue(result is EditMatchEngine.FindResult.Ambiguous)
        val candidates = (result as EditMatchEngine.FindResult.Ambiguous).candidates
        assertEquals(2, candidates.size)
        assertTrue(candidates.any { it.lineNumber == 2 })
        assertTrue(candidates.any { it.lineNumber == 5 })
    }

    @Test
    fun `context disambiguation selects unique occurrence`() {
        val content = """
            fun a() {
                val x = 1
                println("a")
            }
            fun b() {
                val x = 1
                println("b")
            }
        """.trimIndent()
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1", fuzzy = true)
        assertTrue(result is EditMatchEngine.FindResult.Unique)
        val match = (result as EditMatchEngine.FindResult.Unique).match
        assertTrue(match.contextSnippet.contains("fun a()") || match.contextSnippet.contains("fun b()"))
    }

    @Test
    fun `fuzzy match ignores leading and trailing whitespace`() {
        val content = "        val x = 1        "
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1", fuzzy = true)
        assertTrue(result is EditMatchEngine.FindResult.Unique)
    }

    @Test
    fun `fuzzy match ignores indentation changes`() {
        val content = """
            class Foo {
                val x = 1
            }
        """.trimIndent()
        // 用户提供的 old_string 没有缩进
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1", fuzzy = true)
        assertTrue(result is EditMatchEngine.FindResult.Unique)
    }

    @Test
    fun `apply replacement replaces matched region`() {
        val content = "class Foo {\n    val x = 1\n}"
        val result = EditMatchEngine.findReplacementRegion(content, "val x = 1") as EditMatchEngine.FindResult.Unique
        val newContent = EditMatchEngine.applyReplacement(content, result.match, "val x = 2")
        assertTrue(newContent.contains("val x = 2"))
        assertFalse(newContent.contains("val x = 1"))
    }

    @Test
    fun `ambiguous message includes line numbers and snippets`() {
        val content = "a\nb\na\n"
        val result = EditMatchEngine.findReplacementRegion(content, "a") as EditMatchEngine.FindResult.Ambiguous
        val message = EditMatchEngine.formatAmbiguousMessage(result.candidates)
        assertTrue(message.contains("line 1"))
        assertTrue(message.contains("line 3"))
    }
}
