package com.codesage.agent.core

/**
 * 迭代预算管理器
 *
 * 跟踪对话循环中的迭代消耗，支持预算退还（如 context 压缩后），
 * 并在预算耗尽时提供优雅降级。
 *
 * **已废弃**：请使用 [TaskBudget] 替代，支持多维度预算（迭代次数 + Token + 时间）
 * 及更丰富的预警和耗尽处理机制。
 *
 * @param maxIterations 最大允许迭代次数（默认 15）
 */
@Deprecated("Use TaskBudget instead", ReplaceWith("TaskBudget(TaskBudget.BudgetConfig(maxIterations = maxIterations))"))
class IterationBudget(private val maxIterations: Int = DEFAULT_MAX_ITERATIONS) {

    private var consumed = 0
    private var refunded = 0

    /**
     * 尝试消耗一次迭代预算
     * @return true 如果预算充足并已成功消耗，false 如果预算已耗尽
     */
    fun consume(): Boolean {
        if (consumed - refunded >= maxIterations) return false
        consumed++
        return true
    }

    /**
     * 退还一次迭代预算（例如 context 压缩后重置重试计数器）
     */
    fun refund() {
        refunded++
    }

    /**
     * 强制消耗，不检查预算（用于已知安全的场景）
     */
    fun forceConsume() {
        consumed++
    }

    /**
     * 获取剩余预算
     */
    fun remaining(): Int = maxIterations - (consumed - refunded)

    /**
     * 获取已消耗预算（净消耗 = 总消耗 - 退还）
     */
    fun netConsumed(): Int = consumed - refunded

    /**
     * 获取总消耗（含退还部分）
     */
    fun totalConsumed(): Int = consumed

    /**
     * 检查预算是否已耗尽
     */
    fun isExhausted(): Boolean = remaining() <= 0

    /**
     * 重置预算计数器（用于新会话或压缩后）
     */
    fun reset() {
        consumed = 0
        refunded = 0
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 15
        const val CONSERVATIVE_MAX_ITERATIONS = 10
    }
}
