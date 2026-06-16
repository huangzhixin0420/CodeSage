package com.codesage.model.dto

/**
 * 2026-06: 模型流结束原因(协议层归一后的枚举)。
 *
 * 替代之前散落在 [StreamChunk.finishReason: String?] 等位置的字符串字段,
 * 让 [com.codesage.model.adapter.StreamEventNormalizer] 与 [com.codesage.agent.core.TurnReducer]
 * 拥有编译期穷举的 enum,加新 provider 支持时只动 from() 一处。
 */
enum class FinishReason {
    /** 正常完成(模型自己决定停止) */
    STOP,
    /** 模型决定调用工具 */
    TOOL_CALLS,
    /** 命中 max_tokens 上限被截断 */
    LENGTH,
    /** 内容审核拒绝 */
    CONTENT_FILTER,
    /** 未知类型(协议未声明 / 解析失败) */
    UNKNOWN;

    companion object {
        /**
         * 协议层字符串 → 枚举的归一。
         * 大多数 provider 在流结束时不带 finishReason,默认按 STOP 处理。
         */
        fun from(raw: String?): FinishReason = when (raw) {
            "stop" -> STOP
            "tool_calls" -> TOOL_CALLS
            "length" -> LENGTH
            "content_filter", "content_filtered" -> CONTENT_FILTER
            null -> STOP
            else -> UNKNOWN
        }
    }
}
