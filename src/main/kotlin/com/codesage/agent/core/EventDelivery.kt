package com.codesage.agent.core

/**
 * 事件投递语义 — 用于 EventConsumer 决策"该事件能否被合并/丢弃"
 *
 * 设计原则(2026-06 重构):
 *  - Terminal: 必须精确送达一次、不可丢弃 — 状态变更类事件
 *  - Coalescable: 同 (turnId, toolId?, type) key 下最新值可覆盖前序 — 流式文本类事件
 *
 * 历史 bug: 原 JCEFChatPanel.shouldEmit 按 simpleName + 500ms 跨 turn 缓存统一去重,
 * 导致并行 tool 调用的 ToolCallResult 互相吞、跨 turn 短间隔事件互相吞。
 * 修复: 显式分类 + per-turn 状态 + Terminal 事件完全旁路去重。
 */
sealed class EventDelivery {
    /** 状态变更 — 必须精确送达 */
    object Terminal : EventDelivery()
    /** 流式文本 — 同 key 可覆盖 */
    object Coalescable : EventDelivery()
}

/** 事件的投递语义。Coalescable 走 buffer + 16ms 合并;Terminal 直送 JS。 */
val AgentStreamEvent.delivery: EventDelivery
    get() = when (this) {
        is AgentStreamEvent.TextDelta -> EventDelivery.Coalescable
        is AgentStreamEvent.ModelReasoning -> EventDelivery.Coalescable
        is AgentStreamEvent.Thinking -> EventDelivery.Coalescable
        is AgentStreamEvent.ToolCallDelta -> EventDelivery.Coalescable
        // 其余 21+ 种全部 Terminal:状态变更类,精确送达
        else -> EventDelivery.Terminal
    }

/**
 * Coalescable 事件的合并 key。Terminal 事件返回 null(不参与合并)。
 *
 * key 设计:
 *  - text_delta / thinking: 全局合并(同一 turn 内只有一个活跃 stream)
 *  - tool_delta: per-toolId 合并(多 tool 并行时,各自的 delta 互不干扰)
 */
val AgentStreamEvent.coalesceKey: String?
    get() = when (this) {
        is AgentStreamEvent.TextDelta -> "text_delta"
        is AgentStreamEvent.ModelReasoning -> "model_reasoning"
        is AgentStreamEvent.Thinking -> "thinking"
        is AgentStreamEvent.ToolCallDelta -> "tool_delta/${this.toolCallId}"
        else -> null
    }

/**
 * 把两个同 key 的 Coalescable 事件合并。返回 null 表示类型不匹配(防御性:不应发生)。
 *
 * 合并策略(per-type,基于前端实际语义):
 *  - TextDelta: 拼接 — LLM 流式输出,前端 _onTextDelta 期望 turn.content += delta,
 *    中间字符不能丢
 *  - Thinking: 替换 (latest-wins) — Thinking 是"agent 当前在做什么"的状态指示,
 *    不像文本流一样累积;UI 期望每次更新显示最新状态,不是历史拼接
 *  - ToolCallDelta: 拼接 — LLM 流式吐 JSON 片段(参数构造),需要累积成完整 JSON
 *
 * 注意:同 key 但 toolCallId 不同(对 ToolCallDelta)走不同 key 路径,
 * 这里只处理"同 key"的情况,不会跨 tool 误合。
 */
fun AgentStreamEvent.mergeWith(other: AgentStreamEvent): AgentStreamEvent? = when {
    this is AgentStreamEvent.TextDelta && other is AgentStreamEvent.TextDelta ->
        AgentStreamEvent.TextDelta(this.delta + other.delta)
    this is AgentStreamEvent.ModelReasoning && other is AgentStreamEvent.ModelReasoning ->
        // 拼接:模型推理内容是流式累积的
        AgentStreamEvent.ModelReasoning(this.delta + other.delta)
    this is AgentStreamEvent.Thinking && other is AgentStreamEvent.Thinking ->
        // latest-wins: Thinking 是状态指示,新值覆盖旧值
        other
    this is AgentStreamEvent.ToolCallDelta && other is AgentStreamEvent.ToolCallDelta ->
        AgentStreamEvent.ToolCallDelta(this.toolCallId, this.toolName, this.delta + other.delta)
    else -> null
}
