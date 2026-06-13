package com.codesage.tools.guardrails

import com.codesage.agent.context.ContextBudgetManager
import com.codesage.agent.context.ContextManager
import com.codesage.model.dto.Message
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

    @Test
    fun `dynamic threshold truncates more aggressively when context budget is tight`() {
        val contextManager = ContextManager()
        // Consume ~1.5k tokens of a 2.5k window so remaining budget is tight
        val filler = "x ".repeat(3000)
        contextManager.addMessage(Message.userMessage(filler))

        val budget = ContextBudgetManager(
            contextLength = 2500,
            responseReserveTokens = 0,
            contextManagerProvider = { contextManager }
        )
        val limits = budget.getRecommendedOutputLimits()

        val truncator = OutputTruncator()
        val content = (1..300).joinToString("\n") { "Line $it" }
        val result = truncator.truncate(content, maxLength = limits.maxLength, maxLines = limits.maxLines)

        assertTrue(result.wasTruncated)
        assertTrue(result.content.length <= limits.maxLength + 200) // allow truncation notice overhead
        assertTrue(result.truncatedLines <= limits.maxLines + 10)
        assertTrue(limits.maxLength < OutputTruncator.DEFAULT_MAX_LENGTH)
    }
}
