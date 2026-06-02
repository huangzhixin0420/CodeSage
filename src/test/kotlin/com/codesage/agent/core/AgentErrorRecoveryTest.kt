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

    // ===== 修复：Abort 消息带上根因 + UNKNOWN 走 fallback =====

    @Test
    fun `Abort message should include original error details for diagnosis`() {
        // 场景：不能被任何 pattern 匹配的异常被分类为 UNKNOWN，retry 耗尽后
        // Abort 消息应包含原始异常的类名 + 消息，让上层 / 用户能看到根因
        // （不再只是 "超过最大重试次数"）。
        // 这里用一个不会被认出的 RuntimeException 子类。
        class UnrecognizedUpstreamError(message: String) : RuntimeException(message)

        val originalError = UnrecognizedUpstreamError("upstream returned weird payload XYZ")
        val classified = recovery.classify(originalError, model = "test-model")
        assertEquals(FailoverReason.UNKNOWN, classified.reason)
        val agent = AgentCore()

        // UNKNOWN max retry = 2，2 次后第三次应该 abort
        repeat(2) {
            recovery.recover(agent, classified, fallbackModels = emptyList())
        }
        val action = recovery.recover(agent, classified, fallbackModels = emptyList())
        assertTrue(action is RecoveryAction.Abort)
        val msg = (action as RecoveryAction.Abort).message
        assertTrue(
            msg.contains("UnrecognizedUpstreamError"),
            "Abort message should include original exception class name, got: $msg"
        )
        assertTrue(
            msg.contains("upstream returned weird payload"),
            "Abort message should include original exception message, got: $msg"
        )
        assertTrue(
            msg.contains("根因") || msg.contains("original"),
            "Abort message should explicitly mark this as root cause, got: $msg"
        )
    }

    @Test
    fun `UNKNOWN error should fall back to a different model when fallback is available`() {
        // 场景：原始错误不能分类（UNKNOWN），但有 fallback model 可用
        // 期望：不要重复用同一个 model，而是切到 fallback
        val originalError = RuntimeException("Some unrecognized upstream error")
        val classified = recovery.classify(originalError, model = "primary-model")
        assertEquals(FailoverReason.UNKNOWN, classified.reason)
        val agent = AgentCore()

        val action = recovery.recover(agent, classified, fallbackModels = listOf("fallback-model"))
        assertTrue(
            action is RecoveryAction.RetryWithModel,
            "UNKNOWN should switch to fallback when available, got: $action"
        )
        val retry = action as RecoveryAction.RetryWithModel
        assertEquals("fallback-model", retry.model)
    }

    @Test
    fun `UNKNOWN error should SimpleRetry when no fallback model is available`() {
        // 反例：无 fallback 时回到 SimpleRetry（保持向后兼容）
        val originalError = RuntimeException("Unrecognized")
        val classified = recovery.classify(originalError, model = "primary-model")
        val agent = AgentCore()

        val action = recovery.recover(agent, classified, fallbackModels = emptyList())
        assertTrue(
            action is RecoveryAction.SimpleRetry,
            "UNKNOWN without fallback should SimpleRetry, got: $action"
        )
    }

    @Test
    fun `UNKNOWN fallback to same model should fall back to SimpleRetry`() {
        // 边界：fallback 列表里第一个仍然是当前 model（防御）—— 不应该无限跳同个 model
        val originalError = RuntimeException("X")
        val classified = recovery.classify(originalError, model = "primary-model")
        val agent = AgentCore()

        val action = recovery.recover(
            agent,
            classified,
            fallbackModels = listOf("primary-model", "other-model")
        )
        // 第一个 fallback 就是当前 model，应当跳过走到 SimpleRetry
        assertTrue(action is RecoveryAction.SimpleRetry, "got: $action")
    }

    // ===== 修复：HTTP 400 单独分类为 BAD_REQUEST =====

    @Test
    fun `HTTP 400 should classify as BAD_REQUEST not UNKNOWN`() {
        // 场景：LLM API 返回 HTTP 400（Bad Request），原代码会落到 UNKNOWN 分支，
        // 然后重试 2 次同 payload 都失败。现在 400 单独分类为 BAD_REQUEST，
        // 可以走 fallback 避免同模型死磕。
        val error = NetworkException("HTTP 400: {\"error\":\"invalid tool_calls format\"}")
        val classified = recovery.classify(error, model = "primary-model")
        assertEquals(
            FailoverReason.BAD_REQUEST, classified.reason,
            "HTTP 400 should be BAD_REQUEST, not UNKNOWN (was ${classified.reason})"
        )
        assertTrue(classified.shouldFallback, "BAD_REQUEST should try fallback")
        assertTrue(classified.shouldCompress, "BAD_REQUEST should compress (payload too big is common cause)")
        assertTrue(classified.retryable, "BAD_REQUEST should be retryable (via fallback)")
        assertEquals(400, classified.statusCode)
    }

    @Test
    fun `BAD_REQUEST with fallback should switch model once then abort`() {
        // 场景：原 model 返回 400，有 fallback 可用
        // 期望：切到 fallback（不是同 model 死磕）；同 (reason, model) 计数限制只 1 次
        val error = NetworkException("HTTP 400: bad param")
        val classified = recovery.classify(error, model = "primary-model")
        val agent = AgentCore()

        // 第一次 recover：fallback 跳到 fallback-model
        val action1 = recovery.recover(agent, classified, fallbackModels = listOf("fallback-model"))
        assertTrue(
            action1 is RecoveryAction.RetryWithModel,
            "first BAD_REQUEST should switch to fallback, got: $action1"
        )
        assertEquals("fallback-model", (action1 as RecoveryAction.RetryWithModel).model)

        // counter 只 1 (max=1)，下一次调用同一 reason + primary-model 仍 OK，因为换了 model 后 key 也换了
        // 但如果是同一 model 第二次（同 key 累加），应该 abort
        val classifiedPrimary = recovery.classify(error, model = "primary-model")
        val action2 = recovery.recover(agent, classifiedPrimary, fallbackModels = listOf("fallback-model"))
        // counter 现在 = 1（上面那次 increment 的），1 >= max=1，所以 abort
        assertTrue(
            action2 is RecoveryAction.Abort,
            "second BAD_REQUEST on same model should abort (max=1 reached), got: $action2"
        )
    }

    @Test
    fun `BAD_REQUEST with no fallback should compress and retry`() {
        // 没有 fallback 但 shouldCompress=true，应当试 CompressAndRetry
        val error = NetworkException("HTTP 400: payload too large")
        val classified = recovery.classify(error, model = "primary-model")
        val agent = AgentCore()

        val action = recovery.recover(agent, classified, fallbackModels = emptyList())
        assertTrue(
            action is RecoveryAction.CompressAndRetry,
            "BAD_REQUEST with no fallback should CompressAndRetry, got: $action"
        )
    }

    @Test
    fun `400 with image keyword should still classify as IMAGE_TOO_LARGE`() {
        // 重要：400 with "image" 不能被 BAD_REQUEST 抢走，要走 IMAGE_TOO_LARGE
        val error = NetworkException("HTTP 400: image too large, max size 5MB")
        val classified = recovery.classify(error, model = "primary-model")
        assertEquals(
            FailoverReason.IMAGE_TOO_LARGE, classified.reason,
            "400 with 'image' keyword should stay IMAGE_TOO_LARGE, got: ${classified.reason}"
        )
    }

    @Test
    fun `BAD_REQUEST abort message should include body for diagnosis`() {
        // Abort 消息要带上 400 的 body，让上层 / 用户能看到 LLM 拒的具体原因
        val error = NetworkException("HTTP 400: {\"error\":\"tools[0].function.name is required\"}")
        val classified = recovery.classify(error, model = "primary-model")
        val agent = AgentCore()

        // 第一次 (counter=0) 跳 fallback-model，counter += 1
        recovery.recover(agent, classified, fallbackModels = listOf("fallback-model"))
        // 第二次 (counter=1) 同 model 达到 max=1 → abort
        val classified2 = recovery.classify(error, model = "primary-model")
        val action = recovery.recover(agent, classified2, fallbackModels = listOf("fallback-model"))
        assertTrue(action is RecoveryAction.Abort)
        val msg = (action as RecoveryAction.Abort).message
        assertTrue(
            msg.contains("HTTP 400") || msg.contains("Bad Request"),
            "abort should mention HTTP 400, got: $msg"
        )
        assertTrue(
            msg.contains("function.name") || msg.contains("tools"),
            "abort should include the 400 body for diagnosis, got: $msg"
        )
    }
}
