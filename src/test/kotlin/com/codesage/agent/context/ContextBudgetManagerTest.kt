package com.codesage.agent.context

import com.codesage.model.dto.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextBudgetManagerTest {

    @Test
    fun `status reflects token usage from context manager`() {
        val contextManager = ContextManager()
        contextManager.addMessage(Message.userMessage("Hello world"))
        contextManager.addMessage(Message.assistantMessage("Hi there"))

        val budget = ContextBudgetManager(
            contextLength = 1000,
            contextManagerProvider = { contextManager }
        )

        val status = budget.getStatus()
        assertTrue(status.tokensUsed > 0)
        assertEquals(1000, status.contextLength)
        assertEquals(1000 - status.tokensUsed, status.tokensLeft)
        assertEquals(status.tokensUsed.toDouble() / 1000, status.percentUsed, 0.001)
    }

    @Test
    fun `shouldCompress returns true when usage exceeds threshold`() {
        val contextManager = ContextManager()
        // Fill with enough English text to exceed 75% of a small window
        val longText = "word ".repeat(2000)
        contextManager.addMessage(Message.userMessage(longText))

        val budget = ContextBudgetManager(
            contextLength = 1000,
            contextManagerProvider = { contextManager }
        )

        assertTrue(budget.shouldCompress())
    }

    @Test
    fun `shouldCompress returns false when below threshold`() {
        val contextManager = ContextManager()
        contextManager.addMessage(Message.userMessage("short"))

        val budget = ContextBudgetManager(
            contextLength = 10000,
            contextManagerProvider = { contextManager }
        )

        assertFalse(budget.shouldCompress())
    }

    @Test
    fun `recommended limits are default when budget is ample`() {
        val contextManager = ContextManager()
        contextManager.addMessage(Message.userMessage("tiny"))

        val budget = ContextBudgetManager(
            contextLength = 128000,
            contextManagerProvider = { contextManager }
        )

        val limits = budget.getRecommendedOutputLimits()
        assertEquals(com.codesage.tools.guardrails.OutputTruncator.DEFAULT_MAX_LENGTH, limits.maxLength)
        assertEquals(com.codesage.tools.guardrails.OutputTruncator.DEFAULT_MAX_LINES, limits.maxLines)
    }

    @Test
    fun `recommended limits shrink when budget is tight`() {
        val contextManager = ContextManager()
        // Fill ~1.5k tokens of a small 3k window -> remaining budget tight
        val filler = "a ".repeat(3000)
        contextManager.addMessage(Message.userMessage(filler))

        val budget = ContextBudgetManager(
            contextLength = 3000,
            responseReserveTokens = 0,
            contextManagerProvider = { contextManager }
        )

        val limits = budget.getRecommendedOutputLimits()
        assertTrue(limits.maxLength < com.codesage.tools.guardrails.OutputTruncator.DEFAULT_MAX_LENGTH)
        assertTrue(limits.maxLines < com.codesage.tools.guardrails.OutputTruncator.DEFAULT_MAX_LINES)
        assertTrue(limits.maxLength >= ContextBudgetManager.DEFAULT_MIN_MAX_LENGTH)
        assertTrue(limits.maxLines >= ContextBudgetManager.DEFAULT_MIN_MAX_LINES)
    }

    @Test
    fun `provider can be swapped dynamically`() {
        val first = ContextManager().apply { addMessage(Message.userMessage("hello")) }
        val second = ContextManager().apply {
            addMessage(Message.userMessage("one"))
            addMessage(Message.userMessage("two"))
        }

        val budget = ContextBudgetManager(
            contextLength = 1000,
            contextManagerProvider = { first }
        )
        val usedFirst = budget.tokensUsed()

        budget.setContextManagerProvider { second }
        val usedSecond = budget.tokensUsed()

        assertTrue(usedSecond > usedFirst)
    }
}
