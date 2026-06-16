package com.codesage.model.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 聊天请求
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null
)

/**
 * 聊天响应
 */
@Serializable
data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

/**
 * 选择项
 */
@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

/**
 * Token使用统计
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)

/**
 * 2026-06: 流式工具调用增量片段 — DEPRECATED, 2026-06-16 起被
 * [com.codesage.model.adapter.StreamEvent.ToolCall.Delta] 取代。
 * 保留该类用于既有 adapter / 既有测试的过渡期,新代码应使用 StreamEvent.ToolCall.Delta。
 */
@Deprecated(
    message = "Use StreamEvent.ToolCall.Delta. Kept temporarily for refactor transition.",
    replaceWith = ReplaceWith("com.codesage.model.adapter.StreamEvent.ToolCall.Delta")
)
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null
)

/**
 * 2026-06: 流式代码块事件 (fenced code block 的 Start / Delta / End 三选一) — DEPRECATED,
 * 2026-06-16 起被 [com.codesage.model.adapter.StreamEvent.CodeBlock] 取代。
 * 保留该 sealed class 用于既有 adapter / 既有测试的过渡期,新代码应使用
 * StreamEvent.CodeBlock.Started / Delta / Ended。
 *
 * 设计目的:让 adapter 直接 emit 结构化的代码块事件,而不是把代码块字符混在
 * [StreamChunk.delta] 里,这样:
 *   1) 多个代码块并行时按 [codeBlockId] 分桶
 *   2) 代码块字符不再进 assistantContent,避免前端的 markdown 二次解析
 *   3) 后续对接 Anthropic(Gemini)时,Anthropic 的 `content_block_start/delta/stop`
 *      可以直接透传为本 sealed 的 3 个成员
 */
@Deprecated(
    message = "Use StreamEvent.CodeBlock. Kept temporarily for refactor transition.",
    replaceWith = ReplaceWith("com.codesage.model.adapter.StreamEvent.CodeBlock")
)
sealed class CodeBlockEvent {
    /**
     * 开围栏识别成功时产生;codeBlockId 由 adapter 内部计数器生成(`cb-N`),
     * 同 turn 内从 1 开始递增。
     */
    data class Start(
        val codeBlockId: String,
        val language: String? = null,
        val filePath: String? = null,
    ) : CodeBlockEvent()

    /**
     * 开闭围栏之间的代码字符增量,可能跨多个 chunk,前端合并。
     */
    data class Delta(
        val codeBlockId: String,
        val delta: String,
    ) : CodeBlockEvent()

    /**
     * 闭围栏识别成功,或流正常结束但仍在代码块内(CommonMark 允许不闭合)。
     */
    data class End(
        val codeBlockId: String,
        val filePath: String? = null,
    ) : CodeBlockEvent()
}

/**
 * 2026-06: 流式响应片段 — DEPRECATED, 2026-06-16 起被
 * [com.codesage.model.adapter.StreamEvent] 取代。
 *
 * 保留该类用于既有 adapter / 既有测试的过渡期。2026-06-16 重构后
 * 整个项目不再 emit / consume 该类 — 改为 [com.codesage.model.adapter.StreamEvent]
 * 的分形 sealed tree。
 */
@Deprecated(
    message = "Use StreamEvent sealed tree. Kept temporarily for refactor transition.",
    replaceWith = ReplaceWith("com.codesage.model.adapter.StreamEvent")
)
data class StreamChunk(
    val id: String,
    val delta: String,
    val reasoningDelta: String? = null,
    val done: Boolean = false,
    val toolCallDeltas: List<StreamToolCallDelta> = emptyList(),
    val finishReason: String? = null,
    val usage: Usage? = null,
    /**
     * 2026-06: 代码块事件(Start / Delta / End 三选一)。
     * 一次 chunk 最多携带一个 codeBlock 事件,因为 fence 边界天然不会重叠。
     * 设计上跟 [toolCallDeltas] 同级(都是非 TextDelta 类的"结构化增量")。
     */
    val codeBlock: CodeBlockEvent? = null,
)

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String,
    val supportsStreaming: Boolean,
    val supportsFunctionCalling: Boolean,
    val supportsVision: Boolean
)
