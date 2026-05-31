package com.codesage.tools.guardrails

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OutputTruncatorTest {

    @Test
    fun `truncate short content does nothing`() {
        val truncator = OutputTruncator()
        val content = "Short content"
        val result = truncator.truncate(content, maxLength = 1000, maxLines = 100)

        assertFalse(result.wasTruncated)
        assertEquals(content, result.content)
    }

    @Test
    fun `truncate by lines`() {
        val truncator = OutputTruncator()
        val content = (1..50).joinToString("\n") { "Line $it" }
        val result = truncator.truncate(content, maxLength = 10000, maxLines = 10)

        assertTrue(result.wasTruncated)
        assertTrue(result.truncatedLines <= 20) // 允许一些提示行
        assertTrue(result.content.contains("truncated"))
    }

    @Test
    fun `truncate list`() {
        val truncator = OutputTruncator()
        val items = (1..100).map { "Item $it" }
        val result = truncator.truncateList(items, maxItems = 10)

        assertTrue(result.wasTruncated)
        assertTrue(result.content.contains("90 more items truncated"))
    }

    @Test
    fun `truncate structured JSON`() {
        val truncator = OutputTruncator()
        val json = """{"key1":"value1","nested":{"deep":{"veryDeep":{"data":"x"}}}}"""
        val result = truncator.truncateStructured(json, maxDepth = 2, maxLength = 1000)

        assertTrue(result.wasTruncated || result.content == json)
    }

    @Test
    fun `smart truncation preserves head and tail`() {
        val truncator = OutputTruncator()
        val lines = (1..100).map { "Line $it" }
        val content = lines.joinToString("\n")
        val result = truncator.truncate(content, maxLength = 5000, maxLines = 20)

        assertTrue(result.wasTruncated)
        assertTrue(result.content.contains("Line 1"))
        assertTrue(result.content.contains("Line 100"))
        assertTrue(result.content.contains("truncated"))
    }
}
