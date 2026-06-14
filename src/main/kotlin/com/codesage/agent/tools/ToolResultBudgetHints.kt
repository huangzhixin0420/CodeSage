package com.codesage.agent.tools

import com.codesage.agent.context.ContextBudgetManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 为工具结果计算 token 预算提示。
 *
 * 6.12.2：在 `ToolExecutor` 层根据 `ContextBudgetManager` 为结果追加
 * `context_cost_estimate` 与 `remaining_context_hint`，帮助模型在长会话中主动分页。
 */
object ToolResultBudgetHints {

    /**
     * 估算结果字符串的 token 消耗。
     *
     * 采用保守经验值：平均 1 token ≈ 4 个字符（中英混合场景），向上取整。
     */
    fun estimateTokens(content: String): Int = max(1, (content.length / 4.0).roundToInt())

    /**
     * 生成剩余上下文提示文本。
     *
     * @param budgetManager 上下文预算管理器；为 null 时返回 null
     * @param costEstimate 当前结果估算 token 数
     */
    fun remainingHint(budgetManager: ContextBudgetManager?, costEstimate: Int): String? {
        if (budgetManager == null) return null
        val tokensLeft = budgetManager.tokensLeft()
        val percentUsed = (budgetManager.percentUsed() * 100).roundToInt()
        return "${tokensLeft} tokens left (${percentUsed}% used); this result estimated ~${costEstimate} tokens."
    }
}
