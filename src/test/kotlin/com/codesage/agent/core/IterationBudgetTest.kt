package com.codesage.agent.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IterationBudgetTest {

    @Test
    fun `should consume budget successfully`() {
        val budget = IterationBudget(maxIterations = 3)

        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertEquals(0, budget.remaining())
    }

    @Test
    fun `should return false when budget exhausted`() {
        val budget = IterationBudget(maxIterations = 2)

        budget.consume()
        budget.consume()

        assertFalse(budget.consume())
        assertTrue(budget.isExhausted())
    }

    @Test
    fun `should refund consumed budget`() {
        val budget = IterationBudget(maxIterations = 3)

        budget.consume()
        budget.consume()
        assertEquals(1, budget.remaining())

        budget.refund()
        assertEquals(2, budget.remaining())

        // After refund, should be able to consume again
        assertTrue(budget.consume())
        assertTrue(budget.consume())
    }

    @Test
    fun `should track net and total consumed correctly`() {
        val budget = IterationBudget(maxIterations = 5)

        budget.consume() // total=1, net=1
        budget.consume() // total=2, net=2
        budget.refund()  // total=2, net=1

        assertEquals(2, budget.totalConsumed())
        assertEquals(1, budget.netConsumed())
    }

    @Test
    fun `should reset budget`() {
        val budget = IterationBudget(maxIterations = 5)

        repeat(3) { budget.consume() }
        budget.refund()
        budget.reset()

        assertEquals(5, budget.remaining())
        assertEquals(0, budget.totalConsumed())
        assertEquals(0, budget.netConsumed())
    }

    @Test
    fun `should force consume without checking budget`() {
        val budget = IterationBudget(maxIterations = 1)

        budget.consume()
        assertFalse(budget.consume())

        budget.forceConsume()
        assertEquals(-1, budget.remaining())
    }

    @Test
    fun `consume returns true until budget exhausted`() {
        val budget = IterationBudget(maxIterations = 3)

        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertTrue(budget.consume())
        assertFalse(budget.consume())
        assertTrue(budget.isExhausted())
    }

    @Test
    fun `refund should increase remaining`() {
        val budget = IterationBudget(maxIterations = 3)

        budget.consume()
        budget.consume()
        assertEquals(1, budget.remaining())

        budget.refund()
        assertEquals(2, budget.remaining())
    }

    @Test
    fun `remaining should reflect consumed and refunded`() {
        val budget = IterationBudget(maxIterations = 5)

        budget.consume() // consumed=1, refunded=0, remaining=4
        budget.consume() // consumed=2, refunded=0, remaining=3
        budget.refund()  // consumed=2, refunded=1, remaining=4

        assertEquals(4, budget.remaining())
        assertEquals(2, budget.totalConsumed())
        assertEquals(1, budget.netConsumed())
    }

    @Test
    fun `default max iterations should be 15`() {
        val budget = IterationBudget()
        assertEquals(15, budget.remaining())
    }
}
