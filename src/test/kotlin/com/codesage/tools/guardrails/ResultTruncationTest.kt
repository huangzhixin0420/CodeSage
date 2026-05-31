package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResultTruncationTest {

    @Test
    fun `should truncate result exceeding limit`() {
        val truncator = OutputTruncator()
        val content = "A".repeat(10_000)

        val result = truncator.truncate(content, maxLength = 8000, maxLines = 1000)

        assertTrue(result.wasTruncated)
        assertTrue(result.originalLength > 8000)
        assertTrue(result.content.length <= 8000 + 200) // 允许截断提示的额外长度
    }

    @Test
    fun `should not truncate result within limit`() {
        val truncator = OutputTruncator()
        val content = "Short content within limit"

        val result = truncator.truncate(content, maxLength = 8000, maxLines = 1000)

        assertFalse(result.wasTruncated)
        assertEquals(content, result.content)
        assertEquals(content.length, result.originalLength)
    }

    @Test
    fun `should mark truncated result`() {
        val truncator = OutputTruncator()
        val content = (1..500).joinToString("\n") { "Line $it" }

        val result = truncator.truncate(content, maxLength = 100_000, maxLines = 10)

        assertTrue(result.wasTruncated)
        assertTrue(
            result.content.contains("[Output truncated...]"),
            "Truncated result should contain '[Output truncated...]' marker, but was: ${result.content.take(200)}"
        )
    }

    @Test
    fun `should report original and truncated length`() {
        val truncator = OutputTruncator()
        val content = "X".repeat(5000)

        val result = truncator.truncate(content, maxLength = 1000, maxLines = 1000)

        assertTrue(result.wasTruncated)
        assertEquals(5000, result.originalLength)
        assertTrue(result.content.length < result.originalLength)
    }
}
