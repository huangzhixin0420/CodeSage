package com.codesage.agent.core.hooks

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.TurnLifecycleHook
import com.codesage.agent.core.TurnState

/**
 * 2026-06: ToolConfirmationHook — 工具调用确认钩子。
 */
class ToolConfirmationHook : TurnLifecycleHook {
    override fun onToolExecuted(
        turnNumber: Int,
        toolName: String,
        success: Boolean,
        state: TurnState,
    ): List<AgentStreamEvent> = emptyList()
}
