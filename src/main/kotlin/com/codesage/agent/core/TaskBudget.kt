package com.codesage.agent.core

/**
 * 统一任务预算管理器
 *
 * 管理单个用户任务（Task）的多维度预算：
 * - 迭代次数（LLM 调用轮次）
 * - Token 消耗（输入+输出累计）
 * - 执行时间（毫秒）
 *
 * 支持分层预警、预算退还、弹性耗尽后追加预算。
 *
 * @param config 预算配置
 * @param startTimeMs 任务开始时间戳（毫秒），默认当前时间
 */
class TaskBudget(
    val config: BudgetConfig = BudgetConfig(),
    private val startTimeMs: Long = System.currentTimeMillis()
) {

    /**
     * 预算配置数据类
     */
    data class BudgetConfig(
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        val maxTokens: Int = 0, // 0 = 不限制
        val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        val enableIteration: Boolean = true,
        val enableToken: Boolean = false,
        val enableTime: Boolean = true,
        val warningThresholdPercent: Int = DEFAULT_WARNING_THRESHOLD
    )

    /**
     * 预算状态枚举
     */
    enum class BudgetStatus { OK, WARNING, CRITICAL, EXHAUSTED }

    private var consumedIterations = 0
    private var refundedIterations = 0
    private var consumedTokens = 0
    private var extendedIterations = 0 // 用户追加的预算

    /**
     * 尝试消耗一次迭代预算
     * @return true 如果预算充足并成功消耗
     */
    fun consumeIteration(): Boolean {
        if (!config.enableIteration) return true
        return if (netConsumedIterations() < config.maxIterations + extendedIterations) {
            consumedIterations++
            true
        } else false
    }

    /**
     * 退还一次迭代预算（例如 context 压缩后重试不应占用用户预算）
     */
    fun refundIteration() {
        refundedIterations++
    }

    /**
     * 强制消耗一次迭代预算（不检查余额）
     */
    fun forceConsumeIteration() {
        consumedIterations++
    }

    /**
     * 追加迭代预算（用户选择"继续执行"后调用）
     */
    fun extendIterations(extra: Int) {
        extendedIterations += extra.coerceAtLeast(1)
    }

    /**
     * 记录 Token 消耗
     */
    fun recordTokens(tokens: Int) {
        if (tokens > 0) {
            consumedTokens += tokens
        }
    }

    /**
     * 检查时间预算是否仍充足
     */
    fun checkTimeBudget(): Boolean {
        if (!config.enableTime) return true
        return elapsedMs() < config.maxDurationMs
    }

    /**
     * 获取综合预算状态
     */
    fun status(): BudgetStatus {
        if (isExhausted()) return BudgetStatus.EXHAUSTED
        val pct = usagePercent()
        return when {
            pct >= 100 -> BudgetStatus.EXHAUSTED
            pct >= 85 -> BudgetStatus.CRITICAL
            pct >= config.warningThresholdPercent -> BudgetStatus.WARNING
            else -> BudgetStatus.OK
        }
    }

    fun remainingIterations(): Int = (config.maxIterations + extendedIterations) - netConsumedIterations()
    fun netConsumedIterations(): Int = consumedIterations - refundedIterations
    fun totalConsumedIterations(): Int = consumedIterations
    fun consumedTokens(): Int = consumedTokens
    fun remainingTokens(): Int =
        if (config.maxTokens > 0) (config.maxTokens - consumedTokens).coerceAtLeast(0) else Int.MAX_VALUE

    fun elapsedMs(): Long = System.currentTimeMillis() - startTimeMs
    fun remainingMs(): Long =
        if (config.enableTime) (config.maxDurationMs - elapsedMs()).coerceAtLeast(0) else Long.MAX_VALUE

    /**
     * 检查预算是否已耗尽（任一启用维度耗尽即视为耗尽）
     */
    fun isExhausted(): Boolean {
        if (config.enableIteration && netConsumedIterations() >= config.maxIterations + extendedIterations) return true
        if (config.enableToken && config.maxTokens > 0 && consumedTokens >= config.maxTokens) return true
        if (config.enableTime && elapsedMs() >= config.maxDurationMs) return true
        return false
    }

    /**
     * 获取耗尽原因描述
     */
    fun exhaustedReason(): String = buildString {
        val reasons = mutableListOf<String>()
        if (config.enableIteration && netConsumedIterations() >= config.maxIterations + extendedIterations)
            reasons.add("迭代次数已用尽 (${netConsumedIterations()}/${config.maxIterations + extendedIterations})")
        if (config.enableToken && config.maxTokens > 0 && consumedTokens >= config.maxTokens)
            reasons.add("Token 预算已用尽 (${consumedTokens}/${config.maxTokens})")
        if (config.enableTime && elapsedMs() >= config.maxDurationMs)
            reasons.add("时间预算已用尽 (${elapsedMs() / 1000}s/${config.maxDurationMs / 1000}s)")
        reasons.joinTo(this, "; ")
    }

    /**
     * 获取当前使用百分比（取三个维度的最大值）
     */
    fun usagePercent(): Int {
        val iterationPct = if (config.enableIteration && config.maxIterations + extendedIterations > 0)
            (netConsumedIterations() * 100 / (config.maxIterations + extendedIterations)) else 0
        val tokenPct = if (config.enableToken && config.maxTokens > 0)
            (consumedTokens * 100 / config.maxTokens) else 0
        val timePct = if (config.enableTime && config.maxDurationMs > 0)
            (elapsedMs() * 100 / config.maxDurationMs).toInt() else 0
        return maxOf(iterationPct, tokenPct, timePct)
    }

    /**
     * 获取预算摘要（用于日志和 UI 展示）
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (config.enableIteration) {
            parts.add("轮次 ${netConsumedIterations()}/${config.maxIterations + extendedIterations}")
        }
        if (config.enableToken && config.maxTokens > 0) {
            parts.add("Token ${consumedTokens}/${config.maxTokens}")
        }
        if (config.enableTime) {
            parts.add("时间 ${elapsedMs() / 1000}s/${config.maxDurationMs / 1000}s")
        }
        return parts.joinToString(" · ")
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 30
        const val DEFAULT_MAX_DURATION_MS = 600_000L // 10分钟
        const val DEFAULT_WARNING_THRESHOLD = 70
    }
}
