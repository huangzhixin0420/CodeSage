package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class KanbanWorkerTest {

    @Test
    fun `should have correct system prompt`() {
        val agentCore = AgentCore()
        val subAgentExecutor = SubAgentExecutor(agentCore)
        val worker = KanbanWorker("worker_1", agentCore, subAgentExecutor)

        assertTrue(worker.systemPrompt.contains("ONLY execute tasks"))
        assertTrue(worker.systemPrompt.contains("escalate to orchestrator"))
    }

    @Test
    fun `should store worker id`() {
        val agentCore = AgentCore()
        val subAgentExecutor = SubAgentExecutor(agentCore)
        val worker = KanbanWorker("test_worker", agentCore, subAgentExecutor)

        assertEquals("test_worker", worker.workerId)
    }
}
