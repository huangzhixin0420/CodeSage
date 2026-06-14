package com.codesage.agent.tools

/**
 * 统一附加在工具结果上的截断与上下文预算元数据。
 *
 * 6.12.1 / 6.12.2：在 `ToolExecutor` 层为所有工具结果追加一致的截断协议与 token 预算提示，
 * 降低模型处理不同工具截断字段的认知负担。
 *
 * @property truncated 结果是否被截断（工具自身截断或 guardrails 截断）
 * @property totalItems 原始总项数（行数、匹配数、列表项数等），若无法估算则为 null
 * @property returnedItems 实际返回的项数
 * @property nextOffset 建议的下次续读偏移（offset/limit 协议）
 * @property hint 给模型的续读/分页提示文本
 * @property contextCostEstimate 本次结果估算消耗的 token 数
 * @property remainingContextHint 剩余上下文提示（如 "12345 tokens left (42% used)"）
 */
data class ToolResultMetadata(
    val truncated: Boolean = false,
    val totalItems: Int? = null,
    val returnedItems: Int? = null,
    val nextOffset: Int? = null,
    val hint: String? = null,
    val contextCostEstimate: Int? = null,
    val remainingContextHint: String? = null
) {
    /**
     * 当且仅当存在非默认值时返回 true，用于决定是否需要在最终 JSON 中省略空元数据。
     */
    fun isEmpty(): Boolean =
        !truncated &&
                totalItems == null &&
                returnedItems == null &&
                nextOffset == null &&
                hint == null &&
                contextCostEstimate == null &&
                remainingContextHint == null

    companion object {
        /**
         * 空元数据占位，用于无截断、无预算提示的场景。
         */
        val EMPTY = ToolResultMetadata()
    }
}
