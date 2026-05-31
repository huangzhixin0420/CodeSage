package com.codesage.ide.ui.components.kanban

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.agent.multiagent.KanbanOrchestrator
import com.codesage.agent.multiagent.KanbanStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KanbanBoardPanelTest {

    @Test
    fun `should create board panel without error`() {
        val agentCore = AgentCore()
        val executor = SubAgentExecutor(agentCore)
        val orchestrator = KanbanOrchestrator(agentCore, executor)
        val panel = KanbanBoardPanel(orchestrator)

        assertNotNull(panel)
    }

    @Test
    fun `should refresh board with tasks`() {
        val agentCore = AgentCore()
        val executor = SubAgentExecutor(agentCore)
        val orchestrator = KanbanOrchestrator(agentCore, executor)

        orchestrator.createTask("Task 1", "dev")
        orchestrator.createTask("Task 2", "test")
        orchestrator.assignTask(orchestrator.getAllTasks()[0].id, "worker_1")

        val panel = KanbanBoardPanel(orchestrator)
        panel.refreshBoard()

        // Should not throw
        assertTrue(true)
    }
}
