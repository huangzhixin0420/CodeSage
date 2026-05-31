package com.codesage.agent.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TaskBudgetTest {

    @Test
    fun `default config should have sensible defaults`() {
        val budget = TaskBudget()
        assertEquals(15, budget.config.maxIterations)
        assertEquals(0, budget.config.maxTokens)
        assertEquals(300_000L, budget.config.maxDurationMs)
        assertTrue(budget.config.enableIteration)
        assertFalse(budget.config.enableToken)
        assertTrue(budget.config.enableTime)
        assertEquals(70, budget.config.warningThresholdPercent)
    }

    @Test
    fun `consumeIteration should succeed when budget is available`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 3))
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertEquals(0, budget.remainingIterations())
    }

    @Test
    fun `consumeIteration should fail when budget is exhausted`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 2))
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertFalse(budget.consumeIteration())
        assertEquals(0, budget.remainingIterations())
    }

    @Test
    fun `refundIteration should restore consumed budget`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 2))
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertFalse(budget.consumeIteration())

        budget.refundIteration()
        assertEquals(1, budget.remainingIterations())
        assertTrue(budget.consumeIteration())
        assertEquals(0, budget.remainingIterations())
    }

    @Test
    fun `extendIterations should add extra budget`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 2))
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertFalse(budget.consumeIteration())

        budget.extendIterations(5)
        assertEquals(5, budget.remainingIterations())
        assertTrue(budget.consumeIteration())
        assertEquals(4, budget.remainingIterations())
    }

    @Test
    fun `recordTokens should accumulate token usage`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxTokens = 1000, enableToken = true))
        budget.recordTokens(300)
        budget.recordTokens(200)
        assertEquals(500, budget.consumedTokens())
        assertEquals(500, budget.remainingTokens())
    }

    @Test
    fun `token budget should trigger exhaustion`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxTokens = 100, enableToken = true))
        budget.recordTokens(100)
        assertTrue(budget.isExhausted())
        assertEquals("Token 预算已用尽 (100/100)", budget.exhaustedReason())
    }

    @Test
    fun `time budget should be checked`() {
        val startTime = System.currentTimeMillis() - 2000 // 2 seconds ago
        val budget = TaskBudget(
            config = TaskBudget.BudgetConfig(maxDurationMs = 1000, enableTime = true),
            startTimeMs = startTime
        )
        assertFalse(budget.checkTimeBudget())
        assertTrue(budget.isExhausted())
        assertTrue(budget.exhaustedReason().contains("时间预算已用尽"))
    }

    @Test
    fun `time budget should pass when within limit`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxDurationMs = 10_000, enableTime = true))
        assertTrue(budget.checkTimeBudget())
        assertFalse(budget.isExhausted())
    }

    @Test
    fun `status should progress from OK to WARNING to CRITICAL to EXHAUSTED`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 10, warningThresholdPercent = 70))
        assertEquals(TaskBudget.BudgetStatus.OK, budget.status())

        // 70% = 7 iterations consumed -> WARNING
        repeat(7) { budget.consumeIteration() }
        assertEquals(TaskBudget.BudgetStatus.WARNING, budget.status())

        // 85% = 8.5 -> CRITICAL at 9
        budget.consumeIteration()
        budget.consumeIteration()
        assertEquals(TaskBudget.BudgetStatus.CRITICAL, budget.status())

        // 100% -> EXHAUSTED
        budget.consumeIteration()
        assertEquals(TaskBudget.BudgetStatus.EXHAUSTED, budget.status())
    }

    @Test
    fun `disabled iteration budget should always allow consumption`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(enableIteration = false, maxIterations = 1))
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertTrue(budget.consumeIteration())
        assertFalse(budget.isExhausted())
    }

    @Test
    fun `disabled time budget should always pass`() {
        val startTime = System.currentTimeMillis() - 1_000_000
        val budget = TaskBudget(
            config = TaskBudget.BudgetConfig(enableTime = false, maxDurationMs = 1000),
            startTimeMs = startTime
        )
        assertTrue(budget.checkTimeBudget())
        assertFalse(budget.isExhausted())
    }

    @Test
    fun `usagePercent should return max of all enabled dimensions`() {
        val budget = TaskBudget(
            TaskBudget.BudgetConfig(
                maxIterations = 10,
                maxTokens = 1000,
                maxDurationMs = 10_000,
                enableIteration = true,
                enableToken = true,
                enableTime = true
            )
        )
        // iteration 50%, token 0%, time ~0%
        repeat(5) { budget.consumeIteration() }
        assertEquals(50, budget.usagePercent())

        // token 80% -> should dominate
        budget.recordTokens(800)
        assertEquals(80, budget.usagePercent())
    }

    @Test
    fun `exhaustedReason should combine multiple reasons`() {
        val startTime = System.currentTimeMillis() - 2000
        val budget = TaskBudget(
            config = TaskBudget.BudgetConfig(
                maxIterations = 2,
                maxTokens = 100,
                maxDurationMs = 1000,
                enableIteration = true,
                enableToken = true,
                enableTime = true
            ),
            startTimeMs = startTime
        )
        repeat(2) { budget.consumeIteration() }
        budget.recordTokens(100)

        val reason = budget.exhaustedReason()
        assertTrue(reason.contains("迭代次数"), "Should mention iterations")
        assertTrue(reason.contains("Token"), "Should mention tokens")
        assertTrue(reason.contains("时间"), "Should mention time")
    }

    @Test
    fun `summary should return formatted string`() {
        val budget = TaskBudget(
            TaskBudget.BudgetConfig(
                maxIterations = 10,
                maxTokens = 1000,
                maxDurationMs = 60_000,
                enableIteration = true,
                enableToken = true,
                enableTime = true
            )
        )
        budget.consumeIteration()
        budget.recordTokens(100)

        val summary = budget.summary()
        assertTrue(summary.contains("轮次"))
        assertTrue(summary.contains("Token"))
        assertTrue(summary.contains("时间"))
    }

    @Test
    fun `summary should omit disabled dimensions`() {
        val budget = TaskBudget(
            TaskBudget.BudgetConfig(
                maxIterations = 10,
                enableIteration = true,
                enableToken = false,
                enableTime = false
            )
        )
        val summary = budget.summary()
        assertTrue(summary.contains("轮次"))
        assertFalse(summary.contains("Token"))
        assertFalse(summary.contains("时间"))
    }

    @Test
    fun `netConsumedIterations should equal consumed minus refunded`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(maxIterations = 10))
        budget.consumeIteration()
        budget.consumeIteration()
        budget.consumeIteration()
        budget.refundIteration()
        assertEquals(2, budget.netConsumedIterations())
        assertEquals(3, budget.totalConsumedIterations())
    }

    @Test
    fun `remainingTokens should be MAX_VALUE when token budget is disabled`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(enableToken = false))
        assertEquals(Int.MAX_VALUE, budget.remainingTokens())
    }

    @Test
    fun `remainingMs should be MAX_VALUE when time budget is disabled`() {
        val budget = TaskBudget(TaskBudget.BudgetConfig(enableTime = false))
        assertEquals(Long.MAX_VALUE, budget.remainingMs())
    }
}
