package com.codesage.agent.core

/**
 * 2026-06: 工具调用跨 chunk 累积 builder(协议层 StreamEvent.ToolCall.Delta)。
 *
 * 协议层 emit 的 ToolCall.Delta 是"分片"形态:
 *   - 第一帧可能带 id + name(工具注册信息)
 *   - 后续帧可能只带 argumentsFragment(参数 JSON 片段)
 *
 * Reducer 把它们累积到 builder 里,等 Flow.Finished 时一次性 emit
 * AgentStreamEvent.ToolCallResult(或 Done 内嵌 toolCalls 列表)。
 */
class ToolCallBuilder(
    var id: String = "",
    var name: String = "",
) {
    val arguments: StringBuilder = StringBuilder()

    fun argumentsString(): String = arguments.toString()
}
