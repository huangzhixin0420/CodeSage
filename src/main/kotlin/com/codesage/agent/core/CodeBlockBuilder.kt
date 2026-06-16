package com.codesage.agent.core

/**
 * 2026-06: 代码块跨 chunk 累积 builder(协议层 StreamEvent.CodeBlock.*)。
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.5
 *
 * 状态:
 *   - [isOpen] = true: 已 Started 但未 Ended
 *   - 累积的 content 在 Ended 时(或 Flow.Finished 兜底)用 AgentStreamEvent.CodeBlockEnd 收尾
 */
class CodeBlockBuilder(val codeBlockId: String, val language: String?) {
    private val content: StringBuilder = StringBuilder()
    var isOpen: Boolean = true
        private set

    fun append(delta: String) {
        content.append(delta)
    }

    fun text(): String = content.toString()

    fun close() {
        isOpen = false
    }

    override fun toString(): String = "CodeBlockBuilder(id=${hashCode()}, lang=$language, content.len=${content.length}, isOpen=$isOpen)"
}
