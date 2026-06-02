package com.codesage.agent.core

import com.codesage.model.dto.Message
import com.codesage.model.registry.ModelRegistry
import com.codesage.shared.exceptions.*
import com.codesage.shared.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * 故障转移原因枚举
 * 对应 Hermes 中的 FailoverReason，覆盖 16+ 种错误场景
 */
enum class FailoverReason {
    RATE_LIMIT,              // 429
    AUTH_EXPIRED,            // 401
    CONTEXT_TOO_LONG,        // 413 / context limit
    IMAGE_TOO_LARGE,         // 400 image size
    MULTIMODAL_UNSUPPORTED,  // provider 不支持多模态 tool content
    TIMEOUT,                 // 网络超时
    EMPTY_RESPONSE,          // 模型返回空内容
    INCOMPLETE_SCRATCHPAD,   // 推理标签未闭合
    INVALID_TOOL_CALL,       // 工具名/参数错误
    INVALID_JSON,            // 返回的 JSON 无效
    PROVIDER_UNAVAILABLE,    // 服务完全不可用
    UNKNOWN                  // 未知错误
}

/**
 * 分类后的错误信息
 */
data class ClassifiedError(
    val reason: FailoverReason,
    val retryable: Boolean,
    val shouldCompress: Boolean,
    val shouldFallback: Boolean,
    val statusCode: Int?,
    val originalError: Throwable,
    val modelName: String = "unknown"
)

/**
 * 恢复动作
 */
sealed class RecoveryAction {
    /** 使用指定模型重试 */
    data class RetryWithModel(val model: String, val delayMs: Long = 0) : RecoveryAction()

    /** 压缩上下文后重试 */
    data class CompressAndRetry(val auxiliaryModel: String? = null) : RecoveryAction()

    /** 刷新凭证后重试 */
    data class RefreshAndRetry(val delayMs: Long = 1000) : RecoveryAction()

    /** 直接重试（带 prefill 或 jitter） */
    data class SimpleRetry(val delayMs: Long, val prefill: String? = null) : RecoveryAction()

    /** 终止，无法恢复 */
    data class Abort(val message: String) : RecoveryAction()
}

/**
 * Agent 错误分类与恢复引擎
 *
 * 将原始异常分类为具体的 FailoverReason，并根据策略执行恢复动作。
 * 参考 Hermes 的 conversation_loop.py 中的多层错误处理逻辑。
 */
class AgentErrorRecovery {

    private val logger = Logger.getLogger<AgentErrorRecovery>()

    // 重试计数器（按错误类型 + 模型名隔离）
    // T0.4 修复：原本是 ConcurrentHashMap 无界增长，错误后从未清理。
    // 现改为有界 LRU，容量 [MAX_RETRY_KEYS]，超过则淘汰最久未使用的条目。
    private val retryCounters = BoundedConcurrentMap<String, AtomicInteger>(MAX_RETRY_KEYS)
    private val maxRetries = mapOf(
        FailoverReason.EMPTY_RESPONSE to 3,
        FailoverReason.INCOMPLETE_SCRATCHPAD to 2,
        FailoverReason.INVALID_JSON to 2,
        FailoverReason.INVALID_TOOL_CALL to 2,
        FailoverReason.RATE_LIMIT to 3,
        FailoverReason.TIMEOUT to 3,
        FailoverReason.CONTEXT_TOO_LONG to 2,
        FailoverReason.IMAGE_TOO_LARGE to 2,
        FailoverReason.MULTIMODAL_UNSUPPORTED to 1,
        FailoverReason.AUTH_EXPIRED to 2,
        FailoverReason.PROVIDER_UNAVAILABLE to 3,
        FailoverReason.UNKNOWN to 2  // 未知错误也给予 2 次重试机会
    )

    /**
     * 分类错误
     *
     * @param error 原始异常
     * @param provider 当前使用的 provider 名称
     * @param model 当前使用的模型名称
     * @param approxTokens 当前请求的估算 token 数
     */
    fun classify(
        error: Throwable,
        provider: String = "unknown",
        model: String = "unknown",
        approxTokens: Int? = null
    ): ClassifiedError {
        val message = error.message?.lowercase() ?: ""
        val statusCode = extractStatusCode(message)

        return when {
            // 速率限制
            statusCode == 429 || message.contains("rate limit") || message.contains("too many requests") ->
                ClassifiedError(
                    reason = FailoverReason.RATE_LIMIT,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = true,
                    statusCode = 429,
                    originalError = error
                )

            // 认证过期
            statusCode == 401 || message.contains("unauthorized") || message.contains("auth") || message.contains("api key") ->
                ClassifiedError(
                    reason = FailoverReason.AUTH_EXPIRED,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = false,
                    statusCode = 401,
                    originalError = error
                )

            // 上下文过长
            statusCode == 413 || (statusCode == 429 && message.contains("context")) ||
                    message.contains("context length") || message.contains("too long") ||
                    message.contains("maximum context") || message.contains("token limit") ->
                ClassifiedError(
                    reason = FailoverReason.CONTEXT_TOO_LONG,
                    retryable = true,
                    shouldCompress = true,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 图片过大
            message.contains("image") && (message.contains("too large") || message.contains("size")) ->
                ClassifiedError(
                    reason = FailoverReason.IMAGE_TOO_LARGE,
                    retryable = true,
                    shouldCompress = true,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 多模态不支持
            message.contains("multimodal") || message.contains("vision") || message.contains("image not supported") ->
                ClassifiedError(
                    reason = FailoverReason.MULTIMODAL_UNSUPPORTED,
                    retryable = false,
                    shouldCompress = true,
                    shouldFallback = true,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 超时
            error is java.net.SocketTimeoutException || message.contains("timeout") || message.contains("timed out") ->
                ClassifiedError(
                    reason = FailoverReason.TIMEOUT,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = true,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 空响应
            message.contains("empty") || message.contains("no content") || message.contains("null response") ->
                ClassifiedError(
                    reason = FailoverReason.EMPTY_RESPONSE,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 推理标签未闭合
            message.contains("scratchpad") || message.contains("incomplete") || message.contains("unclosed tag") ->
                ClassifiedError(
                    reason = FailoverReason.INCOMPLETE_SCRATCHPAD,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 模型未配置/未找到
            message.contains("模型未配置") || message.contains("model not found") || message.contains("no adapter found") ||
                    message.contains("not found") && message.contains("model") ->
                ClassifiedError(
                    reason = FailoverReason.PROVIDER_UNAVAILABLE,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = true,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 无效工具调用（区分 API 返回的 tool 错误和真正的工具调用格式错误）
            message.contains("tool") && (message.contains("invalid") || message.contains("not found") || message.contains(
                "unknown"
            )) && !message.contains("api error") && !message.contains("status_code") && !message.contains("model") ->
                ClassifiedError(
                    reason = FailoverReason.INVALID_TOOL_CALL,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 无效 JSON
            message.contains("json") || message.contains("parse") || message.contains("serialization") ->
                ClassifiedError(
                    reason = FailoverReason.INVALID_JSON,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = false,
                    statusCode = statusCode,
                    originalError = error,
                    modelName = model
                )

            // 提供者不可用
            statusCode == 503 || statusCode == 502 || statusCode == 500 ||
                    message.contains("unavailable") || message.contains("maintenance") || message.contains("overload") ->
                ClassifiedError(
                    reason = FailoverReason.PROVIDER_UNAVAILABLE,
                    retryable = true,
                    shouldCompress = false,
                    shouldFallback = true,
                    statusCode = statusCode,
                    originalError = error
                )

            // 未知错误
            else -> ClassifiedError(
                reason = FailoverReason.UNKNOWN,
                retryable = false,
                shouldCompress = false,
                shouldFallback = false,
                statusCode = statusCode,
                originalError = error,
                modelName = model
            )
        }
    }

    /**
     * 根据分类结果执行恢复策略
     *
     * @param agent 当前 AgentCore 实例（用于切换模型等操作）
     * @param classified 分类后的错误
     * @param fallbackModels 后备模型列表，按优先级排序
     */
    fun recover(
        agent: AgentCore,
        classified: ClassifiedError,
        fallbackModels: List<String> = DEFAULT_FALLBACK_MODELS
    ): RecoveryAction {
        val counterKey = "${classified.reason.name}:${classified.modelName}"
        val currentRetries = retryCounters.computeIfAbsent(counterKey) { AtomicInteger(0) }.get()
        val maxRetry = maxRetries[classified.reason] ?: 0

        logger.info(
            "Recovering from ${classified.reason} (model=${classified.modelName}) " +
                    "(retry $currentRetries/$maxRetry, status=${classified.statusCode})"
        )

        // 检查是否超过最大重试次数
        if (currentRetries >= maxRetry) {
            // 附上原始异常详情（类名 + 消息），让上层能看到根因
            val originalMsg = classified.originalError?.let { e ->
                val name = e.javaClass.simpleName
                val msg = e.message?.take(200) ?: "(no message)"
                "$name: $msg"
            } ?: "(no original error captured)"
            logger.warn(
                "Max retries exceeded for ${classified.reason} on model ${classified.modelName}, " +
                        "aborting. Original error: $originalMsg",
                classified.originalError
            )
            return RecoveryAction.Abort(
                "${classified.reason} 超过最大重试次数 ($maxRetry)，根因: $originalMsg"
            )
        }

        // 增加重试计数（同时更新 LRU 访问顺序）
        retryCounters.computeIfAbsent(counterKey) { AtomicInteger(0) }.incrementAndGet()

        // 动态获取可用的 fallback 模型（优先使用传入的列表，否则从 Registry 查询）
        val effectiveFallbackModels = fallbackModels.ifEmpty {
            getAvailableFallbackModels(classified.modelName)
        }

        val action = when (classified.reason) {
            FailoverReason.RATE_LIMIT -> {
                val delayMs = calculateBackoff(currentRetries)
                val fallback = effectiveFallbackModels.firstOrNull()
                if (fallback != null && classified.shouldFallback) {
                    RecoveryAction.RetryWithModel(fallback, delayMs)
                } else {
                    RecoveryAction.SimpleRetry(delayMs)
                }
            }

            FailoverReason.AUTH_EXPIRED ->
                RecoveryAction.RefreshAndRetry(delayMs = 1000L)

            FailoverReason.CONTEXT_TOO_LONG ->
                RecoveryAction.CompressAndRetry()

            FailoverReason.IMAGE_TOO_LARGE ->
                RecoveryAction.CompressAndRetry()

            FailoverReason.MULTIMODAL_UNSUPPORTED -> {
                val fallback = effectiveFallbackModels.firstOrNull()
                if (fallback != null) {
                    RecoveryAction.RetryWithModel(fallback)
                } else {
                    RecoveryAction.Abort("当前 provider 不支持多模态，且无可用后备模型")
                }
            }

            FailoverReason.TIMEOUT -> {
                val delayMs = calculateBackoff(currentRetries)
                val fallback = effectiveFallbackModels.firstOrNull()
                if (fallback != null && currentRetries >= 1) {
                    RecoveryAction.RetryWithModel(fallback, delayMs)
                } else {
                    RecoveryAction.SimpleRetry(delayMs)
                }
            }

            FailoverReason.EMPTY_RESPONSE ->
                RecoveryAction.SimpleRetry(
                    delayMs = calculateBackoff(currentRetries),
                    prefill = "<REASONING_SCRATCHPAD>\nLet me analyze the previous attempt...\n</REASONING_SCRATCHPAD>\n\n"
                )

            FailoverReason.INCOMPLETE_SCRATCHPAD ->
                RecoveryAction.SimpleRetry(
                    delayMs = 500,
                    prefill = "<REASONING_SCRATCHPAD>\n[Continuing from previous incomplete reasoning...]\n</REASONING_SCRATCHPAD>\n\n"
                )

            FailoverReason.INVALID_TOOL_CALL,
            FailoverReason.INVALID_JSON ->
                RecoveryAction.SimpleRetry(delayMs = 500)

            FailoverReason.PROVIDER_UNAVAILABLE -> {
                val fallback = effectiveFallbackModels.firstOrNull()
                if (fallback != null) {
                    RecoveryAction.RetryWithModel(fallback, calculateBackoff(currentRetries))
                } else {
                    RecoveryAction.Abort("Provider 不可用，且无可用后备模型")
                }
            }

            FailoverReason.UNKNOWN -> {
                val delayMs = calculateBackoff(currentRetries)
                val fallback = effectiveFallbackModels.firstOrNull()
                if (fallback != null && fallback != classified.modelName) {
                    // 原始错误原因不明，猜可能是当前 model 有问题
                    // 尝试切换到 fallback（跳到下一个 model，而不是 retry 同一个）
                    logger.warn(
                        "Unknown error on model=${classified.modelName}, " +
                                "falling back to model=$fallback: ${classified.originalError?.message}"
                    )
                    RecoveryAction.RetryWithModel(fallback, delayMs)
                } else {
                    logger.warn(
                        "Unknown error encountered, attempting simple retry: " +
                                "${classified.originalError?.message}"
                    )
                    RecoveryAction.SimpleRetry(delayMs)
                }
            }
        }

        // 将恢复动作应用到 AgentCore 实例
        applyRecoveryAction(agent, action)
        return action
    }

    /**
     * 将恢复动作直接应用到 AgentCore 实例
     */
    private fun applyRecoveryAction(agent: AgentCore, action: RecoveryAction) {
        when (action) {
            is RecoveryAction.RetryWithModel -> {
                logger.info("Applying recovery: switching model to ${action.model}")
                agent.switchModel(action.model)
            }

            is RecoveryAction.CompressAndRetry -> {
                logger.info("Applying recovery: compressing context")
                val success = agent.compressContext()
                if (!success) {
                    logger.warn("Context compression failed or not available")
                }
            }

            is RecoveryAction.SimpleRetry -> {
                // SimpleRetry 的 prefill 由 EnhancedAgentLoop 在上下文层处理
                logger.info("Applying recovery: simple retry")
            }

            is RecoveryAction.RefreshAndRetry -> {
                logger.info("Applying recovery: refresh and retry")
            }

            is RecoveryAction.Abort -> {
                logger.info("Applying recovery: abort")
            }
        }
    }

    /**
     * 重置指定错误类型的重试计数器
     */
    fun resetCounter(reason: FailoverReason, modelName: String = "unknown") {
        retryCounters.remove("${reason.name}:$modelName")
    }

    /**
     * 重置所有重试计数器
     */
    fun resetAllCounters() {
        retryCounters.clear()
    }

    /**
     * 获取指定原因和模型的当前重试次数（用于测试）
     */
    internal fun getRetryCount(reason: FailoverReason, modelName: String = "unknown"): Int {
        return retryCounters["${reason.name}:$modelName"]?.get() ?: 0
    }

    /**
     * 计算指数退避延迟（带 jitter）
     */
    private fun calculateBackoff(attempt: Int): Long {
        val baseDelay = 1000L
        val maxDelay = 30000L
        // 限制 shift 位数防止整数溢出（1 shl 31 会变成负数）
        val safeAttempt = attempt.coerceAtMost(30)
        val exponential = baseDelay * (1L shl safeAttempt) // 2^attempt
        val jitter = (Math.random() * 0.3 * exponential).toLong() // ±30% jitter
        return min(exponential + jitter, maxDelay)
    }

    /**
     * 从错误消息中提取 HTTP 状态码
     */
    private fun extractStatusCode(message: String): Int? {
        val regex = Regex("""\b(\d{3})\b""")
        val match = regex.find(message)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        /**
         * 默认后备模型列表（已废弃硬编码列表）。
         * 为避免 fallback 到用户未配置的提供商导致 ModelNotFoundException，
         * 现改为从 ModelRegistry 动态获取用户实际已配置的可用模型。
         */
        val DEFAULT_FALLBACK_MODELS = emptyList<String>()

        /**
         * T0.4 修复：retryCounters 的最大容量。
         * 超过此容量会淘汰最久未使用的 key。
         * 默认 256 足以容纳 12 种错误类型 × ~20 个模型名字。
         */
        const val MAX_RETRY_KEYS = 256
    }

    /**
     * 从 ModelRegistry 动态获取可用的 fallback 模型列表（排除当前失败的模型）。
     */
    private fun getAvailableFallbackModels(currentModel: String): List<String> {
        return try {
            val registry = ModelRegistry.getInstance()
            registry.listAvailableModels()
                .map { it.id }
                .filter { it != currentModel }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
