package com.codesage.agent.core

import com.codesage.shared.exceptions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.SocketTimeoutException

class AgentErrorRecoveryTest {

    private val recovery = AgentErrorRecovery()

    @Test
    fun `should classify rate limit error`() {
        val error = RateLimitException("Rate limit exceeded", retryAfterMs = 5000)
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.RATE_LIMIT, classified.reason)
        assertTrue(classified.retryable)
        assertFalse(classified.shouldCompress)
        assertTrue(classified.shouldFallback)
    }

    @Test
    fun `should classify auth expired error`() {
        val error = AuthExpiredException("Unauthorized: invalid API key")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.AUTH_EXPIRED, classified.reason)
        assertTrue(classified.retryable)
        assertFalse(classified.shouldCompress)
        assertFalse(classified.shouldFallback)
    }

    @Test
    fun `should classify context too long error`() {
        val error = ContextTooLongException("Context length exceeded", approxTokens = 15000, maxTokens = 128000)
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.CONTEXT_TOO_LONG, classified.reason)
        assertTrue(classified.retryable)
        assertTrue(classified.shouldCompress)
    }

    @Test
    fun `should classify timeout error`() {
        val error = SocketTimeoutException("Read timed out")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.TIMEOUT, classified.reason)
        assertTrue(classified.retryable)
        assertTrue(classified.shouldFallback)
    }

    @Test
    fun `should classify empty response error`() {
        val error = EmptyResponseException("Empty response from model")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.EMPTY_RESPONSE, classified.reason)
        assertTrue(classified.retryable)
        assertFalse(classified.shouldFallback)
    }

    @Test
    fun `should classify invalid tool call error`() {
        val error = InvalidToolCallException("Unknown tool: foo", toolName = "foo")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.INVALID_TOOL_CALL, classified.reason)
        assertTrue(classified.retryable)
    }

    @Test
    fun `should classify provider unavailable error`() {
        val error = ProviderUnavailableException("Service temporarily unavailable")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.PROVIDER_UNAVAILABLE, classified.reason)
        assertTrue(classified.retryable)
        assertTrue(classified.shouldFallback)
    }

    @Test
    fun `should classify unknown error`() {
        val error = RuntimeException("Something weird happened")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.UNKNOWN, classified.reason)
        assertFalse(classified.retryable)
    }

    @Test
    fun `should extract status code from message`() {
        val error = NetworkException("HTTP 429: Too Many Requests")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.RATE_LIMIT, classified.reason)
        assertEquals(429, classified.statusCode)
    }

    @Test
    fun `should return retry with model for rate limit`() {
        val error = RateLimitException("Rate limited")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified, fallbackModels = listOf("fallback-model"))

        assertTrue(action is RecoveryAction.RetryWithModel)
        val retryAction = action as RecoveryAction.RetryWithModel
        assertEquals("fallback-model", retryAction.model)
    }

    @Test
    fun `should return compress and retry for context too long`() {
        val error = ContextTooLongException("Too long")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified)

        assertTrue(action is RecoveryAction.CompressAndRetry)
    }

    @Test
    fun `should return simple retry with prefill for empty response`() {
        val error = EmptyResponseException("Empty")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified)

        assertTrue(action is RecoveryAction.SimpleRetry)
        val simpleAction = action as RecoveryAction.SimpleRetry
        assertNotNull(simpleAction.prefill)
        assertTrue(simpleAction.prefill!!.contains("REASONING_SCRATCHPAD"))
    }

    @Test
    fun `should abort after max retries exceeded`() {
        val error = EmptyResponseException("Empty")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        // Exhaust retries
        repeat(3) {
            recovery.recover(agent, classified)
        }

        // 4th attempt should abort
        val action = recovery.recover(agent, classified)
        assertTrue(action is RecoveryAction.Abort)
    }

    @Test
    fun `should reset counter for specific reason`() {
        val error = EmptyResponseException("Empty")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        recovery.recover(agent, classified)
        recovery.resetCounter(FailoverReason.EMPTY_RESPONSE)

        // After reset, should be able to retry again
        val action = recovery.recover(agent, classified)
        assertTrue(action is RecoveryAction.SimpleRetry)
    }

    @Test
    fun `should retry for unknown errors`() {
        val error = RuntimeException("Unknown")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified)

        // UNKNOWN 错误现在默认给予 2 次 SimpleRetry 机会，而非直接 Abort
        assertTrue(action is RecoveryAction.SimpleRetry)
    }

    @Test
    fun `should fallback to abort when no fallback models available for multimodal unsupported`() {
        val error = NetworkException("Multimodal not supported")
        val classified = recovery.classify(error)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified, fallbackModels = emptyList())

        assertTrue(action is RecoveryAction.Abort)
    }

    @Test
    fun `recover should modify agent currentModel`() {
        val error = RateLimitException("Rate limit exceeded")
        val classified = recovery.classify(error, model = "model-a")
        val agent = AgentCore()
        agent.initialize(AgentConfig(defaultModel = "model-a"))
        assertEquals("model-a", agent.getCurrentModel())

        val action = recovery.recover(agent, classified, fallbackModels = listOf("fallback-model"))

        assertTrue(action is RecoveryAction.RetryWithModel)
        assertEquals("fallback-model", agent.getCurrentModel())
    }

    @Test
    fun `classify 429 should return RATE_LIMIT and retryable=true`() {
        val error = NetworkException("HTTP 429: Too Many Requests")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.RATE_LIMIT, classified.reason)
        assertTrue(classified.retryable)
        assertTrue(classified.shouldFallback)
        assertEquals(429, classified.statusCode)
    }

    @Test
    fun `classify 413 should return CONTEXT_TOO_LONG and shouldCompress=true`() {
        val error = NetworkException("HTTP 413: Payload Too Large")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.CONTEXT_TOO_LONG, classified.reason)
        assertTrue(classified.retryable)
        assertTrue(classified.shouldCompress)
    }

    @Test
    fun `classify empty response should return EMPTY_RESPONSE and retryable=true`() {
        val error = EmptyResponseException("Empty response from model")
        val classified = recovery.classify(error)

        assertEquals(FailoverReason.EMPTY_RESPONSE, classified.reason)
        assertTrue(classified.retryable)
        assertFalse(classified.shouldCompress)
    }

    @Test
    fun `retry counters should be isolated per model`() {
        val error = EmptyResponseException("Empty response")
        val agent = AgentCore()

        // Exhaust retries on model-a (max 3)
        val classifiedA = recovery.classify(error, model = "model-a")
        repeat(3) {
            val action = recovery.recover(agent, classifiedA, fallbackModels = emptyList())
            assertTrue(action is RecoveryAction.SimpleRetry, "Attempt ${it + 1} on model-a should retry")
        }

        // 4th attempt on model-a should abort
        val actionA = recovery.recover(agent, classifiedA, fallbackModels = emptyList())
        assertTrue(actionA is RecoveryAction.Abort, "4th attempt on model-a should abort")

        // Same reason on model-b should still be retryable
        val classifiedB = recovery.classify(error, model = "model-b")
        val actionB = recovery.recover(agent, classifiedB, fallbackModels = emptyList())
        assertTrue(actionB is RecoveryAction.SimpleRetry, "1st attempt on model-b should retry")

        // Verify internal counter isolation
        // 3 retries consumed on model-a, 4th attempt aborted => counter stays at 3
        assertEquals(3, recovery.getRetryCount(FailoverReason.EMPTY_RESPONSE, "model-a"))
        assertEquals(1, recovery.getRetryCount(FailoverReason.EMPTY_RESPONSE, "model-b"))
    }
}
