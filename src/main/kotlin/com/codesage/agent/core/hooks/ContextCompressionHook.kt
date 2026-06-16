package com.codesage.agent.core.hooks

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.TurnLifecycleHook
import com.codesage.agent.core.TurnState

/**
 * 2026-06: ContextCompressionHook — 上下文压缩钩子。
 */
class ContextCompressionHook : TurnLifecycleHook {
    override fun onContextCompression(
        turnNumber: Int,
        originalTokens: Int,
        compressedTokens: Int,
        state: TurnState,
    ): List<AgentStreamEvent> = listOf(
        AgentStreamEvent.ContextCompressed(
            originalTokens = originalTokens,
            compressedTokens = compressedTokens,
            strategy = "llm_summarize",
        )
    )
}
