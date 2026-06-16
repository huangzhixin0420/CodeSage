package com.codesage.agent.core.hooks

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.TurnLifecycleHook
import com.codesage.agent.core.TurnState

/**
 * 2026-06: SessionMigrationHook — Session 迁移钩子。
 */
class SessionMigrationHook : TurnLifecycleHook {
    override fun onSessionMigration(
        turnNumber: Int,
        oldSessionId: String,
        newSessionId: String,
        state: TurnState,
    ): List<AgentStreamEvent> = listOf(
        AgentStreamEvent.SessionMigrated(
            oldSessionId = oldSessionId,
            newSessionId = newSessionId,
            messageCount = 0,
        )
    )
}
