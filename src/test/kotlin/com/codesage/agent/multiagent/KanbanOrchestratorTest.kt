package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class KanbanOrchestratorTest {

    private lateinit var orchestrator: KanbanOrchestrator

    @BeforeEach
    fun setUp() {
        val agentCore = AgentCore()
        val subAgentExecutor = SubAgentExecutor(agentCore)
        orchestrator = KanbanOrchestrator(agentCore, subAgentExecutor)
    }

    @Test
    fun `should create task`() {
        val task = orchestrator.createTask("Implement auth module", "dev")

        assertTrue(task.id.startsWith("kb_"))
        assertEquals("Implement auth module", task.description)
        assertEquals(KanbanStatus.BACKLOG, task.status)
        assertEquals("dev", task.toolset)
    }

    @Test
    fun `should assign task`() {
        val task = orchestrator.createTask("Test task")
        val success = orchestrator.assignTask(task.id, "worker_1")

        assertTrue(success)
        val assigned = orchestrator.getAllTasks().first()
        assertEquals(KanbanStatus.IN_PROGRESS, assigned.status)
        assertEquals("worker_1", assigned.assignedWorker)
    }

    @Test
    fun `should update task status`() {
        val task = orchestrator.createTask("Update test")
        orchestrator.updateTaskStatus(task.id, KanbanStatus.DONE, "Completed successfully")

        val updated = orchestrator.getAllTasks().first()
        assertEquals(KanbanStatus.DONE, updated.status)
        assertEquals("Completed successfully", updated.result)
    }

    @Test
    fun `should return false for non-existent task`() {
        assertFalse(orchestrator.assignTask("non_existent", "worker"))
        assertFalse(orchestrator.updateTaskStatus("non_existent", KanbanStatus.DONE))
    }

    @Test
    fun `should decompose complex task`() {
        val tasks = orchestrator.decomposeToKanban("Step 1, Step 2, Step 3")

        assertTrue(tasks.size >= 3, "Should decompose into multiple tasks")
    }

    @Test
    fun `should render board`() {
        orchestrator.createTask("Task A")
        orchestrator.createTask("Task B")
        orchestrator.assignTask(orchestrator.getAllTasks()[0].id, "worker_1")

        val board = orchestrator.renderBoard()

        assertTrue(board.contains("Kanban Board"))
        assertTrue(board.contains("Task A"))
        assertTrue(board.contains("Task B"))
    }

    @Test
    fun `should check all done`() {
        val task1 = orchestrator.createTask("Task 1")
        val task2 = orchestrator.createTask("Task 2")

        assertFalse(orchestrator.isAllDone())

        orchestrator.updateTaskStatus(task1.id, KanbanStatus.DONE)
        orchestrator.updateTaskStatus(task2.id, KanbanStatus.DONE)

        assertTrue(orchestrator.isAllDone())
    }

    @Test
    fun `should summarize results`() {
        val task = orchestrator.createTask("Summary test")
        orchestrator.updateTaskStatus(task.id, KanbanStatus.DONE, "Great result")

        val summary = orchestrator.summarizeResults()
        assertTrue(summary.contains("Summary test"))
        assertTrue(summary.contains("Great result"))
    }

    @Test
    fun `should clear board`() {
        orchestrator.createTask("To clear")
        orchestrator.clearBoard()

        assertTrue(orchestrator.getAllTasks().isEmpty())
    }

    @Test
    fun `should filter tasks by status`() {
        val task1 = orchestrator.createTask("Backlog task")
        val task2 = orchestrator.createTask("In progress task")
        val task3 = orchestrator.createTask("Done task")

        orchestrator.assignTask(task2.id, "worker")
        orchestrator.updateTaskStatus(task3.id, KanbanStatus.DONE)

        assertEquals(1, orchestrator.getBacklog().size)
        assertEquals(1, orchestrator.getInProgress().size)
        assertEquals(1, orchestrator.getDone().size)
    }
}
