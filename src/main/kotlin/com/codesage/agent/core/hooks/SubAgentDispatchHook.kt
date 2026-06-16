package com.codesage.agent.core.hooks

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.TurnLifecycleHook
import com.codesage.agent.core.TurnState

/**
 * 2026-06: SubAgentDispatchHook — 子 Agent 派发钩子。
 *
 * 责任: emit SubAgentStart / SubAgentProgress / SubAgentComplete 事件给 UI。
 * 替代 EnhancedAgentLoop 内的子 agent 派发块。
 */
class SubAgentDispatchHook : TurnLifecycleHook {
    override fun onTurnEnd(
        turnNumber: Int,
        state: TurnState,
        message: com.codesage.model.dto.Message?,
    ): List<AgentStreamEvent> = emptyList()
}
