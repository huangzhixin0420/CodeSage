package com.codesage.agent.core

/**
 * 2026-06: 计划步骤累积 builder — 与 ToolCallBuilder / CodeBlockBuilder 同级。
 *
 * 每个 step 是协议层 Content.PlanStep 跨 chunk 累积的结果(同 stepIndex 拼到一起)。
 * 当前 reducer 收到 PlanStep.delta 时 append 进 builder。
 *
 * 未来 PlanStep 协议层跨协议适配时(见 future-tasks/PlanStep-跨协议适配.md),
 * 本类已经就绪,只需在 OpenAIStreamNormalizer / AnthropicStreamNormalizer 加映射即可。
 */
class PlanStepBuilder(val stepIndex: Int) {
    private val content: StringBuilder = StringBuilder()

    fun append(delta: String) {
        content.append(delta)
    }

    fun text(): String = content.toString()
}
