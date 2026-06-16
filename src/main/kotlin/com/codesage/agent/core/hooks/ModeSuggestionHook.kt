package com.codesage.agent.core.hooks

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.TurnLifecycleHook
import com.codesage.agent.core.TurnState
// ChatMode is in same package

/**
 * 2026-06: ModeSuggestionHook — ChatMode 建议钩子。
 */
class ModeSuggestionHook : TurnLifecycleHook {
    override fun onTurnStart(
        turnNumber: Int,
        state: TurnState,
    ): List<AgentStreamEvent> = emptyList()
}
