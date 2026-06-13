package com.codesage.agent.context

import com.codesage.tools.guardrails.OutputTruncator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 上下文预算管理器
 *
 * 为工具输出截断和主动压缩提供动态的 token 预算视图。
 * - 根据当前会话剩余 token 计算推荐输出上限
 * - 为 `get_context_remaining` 工具提供统一状态
 * - 保留响应 token 余量（responseReserveTokens），避免工具输出占满后模型无空间回复
 */
class ContextBudgetManager(
    val contextLength: Int = ContextEngine.DEFAULT_CONTEXT_LENGTH,
    val thresholdPercent: Double = ContextEngine.DEFAULT_THRESHOLD_PERCENT,
    val responseReserveTokens: Int = DEFAULT_RESPONSE_RESERVE,
    contextManagerProvider: () -> ContextManager? = { null }
) {
    private var provider: () -> ContextManager? = contextManagerProvider

    /**
     * 动态替换 ContextManager 提供者（用于 AgentCore 在会话创建后重新绑定）
     */
    fun setContextManagerProvider(provider: () -> ContextManager?) {
        this.provider = provider
    }

    /**
     * 当前已用 token 数
     */
    fun tokensUsed(): Int = provider()?.estimateTokens() ?: 0

    /**
     * 剩余 token 数
     */
    fun tokensLeft(): Int = max(0, contextLength - tokensUsed())

    /**
     * 已用百分比（0.0 ~ 1.0+）
     */
    fun percentUsed(): Double = if (contextLength > 0) tokensUsed().toDouble() / contextLength else 0.0

    /**
     * 压缩阈值 token 数
     */
    fun thresholdTokens(): Int = (contextLength * thresholdPercent).toInt()

    /**
     * 是否达到主动压缩阈值
     */
    fun shouldCompress(): Boolean = tokensUsed() >= thresholdTokens()

    /**
     * 获取完整预算状态（供 `get_context_remaining` 工具使用）
     */
    fun getStatus(): ContextBudgetStatus = ContextBudgetStatus(
        tokensUsed = tokensUsed(),
        tokensLeft = tokensLeft(),
        contextLength = contextLength,
        percentUsed = percentUsed(),
        thresholdPercent = thresholdPercent,
        thresholdTokens = thresholdTokens()
    )

    /**
     * 根据剩余 token 预算动态计算工具输出截断上限。
     *
     * 计算逻辑：
     * - 先扣除响应预留量 [responseReserveTokens]
     * - 剩余字符预算 ≈ 剩余 token × 4（中英混合经验值）
     * - 剩余行预算 ≈ 剩余 token / 20
     * - 若预算充足则返回默认值，否则按比例缩小，但不低于最小保护值
     */
    fun getRecommendedOutputLimits(
        defaultMaxLength: Int = OutputTruncator.DEFAULT_MAX_LENGTH,
        defaultMaxLines: Int = OutputTruncator.DEFAULT_MAX_LINES,
        minMaxLength: Int = DEFAULT_MIN_MAX_LENGTH,
        minMaxLines: Int = DEFAULT_MIN_MAX_LINES
    ): OutputLimits {
        val available = max(0, tokensLeft() - responseReserveTokens)
        if (available <= 0) {
            return OutputLimits(minMaxLength, minMaxLines)
        }

        val charBudget = available * 4
        val lineBudget = available / 20

        val maxLength = if (charBudget >= defaultMaxLength) {
            defaultMaxLength
        } else {
            max(minMaxLength, charBudget)
        }

        val maxLines = if (lineBudget >= defaultMaxLines) {
            defaultMaxLines
        } else {
            max(minMaxLines, lineBudget)
        }

        return OutputLimits(maxLength, maxLines)
    }

    companion object {
        const val DEFAULT_RESPONSE_RESERVE = 4096
        const val DEFAULT_MIN_MAX_LENGTH = 800
        const val DEFAULT_MIN_MAX_LINES = 40
    }
}

/**
 * 上下文预算状态
 */
data class ContextBudgetStatus(
    val tokensUsed: Int,
    val tokensLeft: Int,
    val contextLength: Int,
    val percentUsed: Double,
    val thresholdPercent: Double,
    val thresholdTokens: Int
)

/**
 * 输出截断限制
 */
data class OutputLimits(
    val maxLength: Int,
    val maxLines: Int
)
