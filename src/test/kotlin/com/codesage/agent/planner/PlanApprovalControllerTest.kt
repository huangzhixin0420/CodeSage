package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlanApprovalControllerTest {

    @Test
    fun `should emit PlanGenerated and PlanApproved on approve`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("approve_test")

        val flow = controller.requestApproval(plan)

        // 在后台批准
        launch {
            delay(100)
            controller.approve("approve_test")
        }

        val events = flow.toList()

        assertTrue(events.any { it is AgentStreamEvent.PlanGenerated })
        assertTrue(events.any { it is AgentStreamEvent.PlanApproved })

        val approved = events.filterIsInstance<AgentStreamEvent.PlanApproved>().first()
        assertEquals("approve_test", approved.planId)
    }

    @Test
    fun `should emit PlanRejected on reject`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("reject_test")

        val flow = controller.requestApproval(plan)

        launch {
            delay(50)
            controller.reject("reject_test", "User doesn't like this plan")
        }

        val events = flow.toList()

        assertTrue(events.any { it is AgentStreamEvent.PlanRejected })
        val rejected = events.filterIsInstance<AgentStreamEvent.PlanRejected>().first()
        assertEquals("reject_test", rejected.planId)
        assertEquals("User doesn't like this plan", rejected.reason)
    }

    @Test
    fun `should emit PlanModified on modify`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("modify_test")
        val modifiedPlan = createTestPlan("modify_test").copy(
            steps = listOf(TaskStep(id = "step_new", description = "New step", dependencies = emptyList()))
        )

        val flow = controller.requestApproval(plan)

        launch {
            delay(50)
            controller.modify("modify_test", modifiedPlan)
        }

        val events = flow.toList()

        assertTrue(events.any { it is AgentStreamEvent.PlanModified })
        val modified = events.filterIsInstance<AgentStreamEvent.PlanModified>().first()
        assertEquals("modify_test", modified.planId)
        assertEquals(1, modified.steps.size)
        assertEquals("step_new", modified.steps[0].id)
    }

    @Test
    fun `should timeout if no user response`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("timeout_test")

        val flow = controller.requestApproval(plan, timeout = 200.milliseconds)
        val events = flow.toList()

        assertTrue(events.any { it is AgentStreamEvent.PlanRejected })
        val rejected = events.filterIsInstance<AgentStreamEvent.PlanRejected>().first()
        assertEquals("timeout_test", rejected.planId)
        assertTrue(rejected.reason.contains("timeout") || rejected.reason.contains("超时"))
    }

    @Test
    fun `should track pending approval status`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("status_test")

        assertNull(controller.getApprovalStatus("status_test"))

        val flow = controller.requestApproval(plan, timeout = 5.seconds)

        // 在收集事件前，状态应该是 PENDING
        // 但由于 Flow 是冷流，requestApproval 的代码直到收集时才开始执行
        // 所以我们在后台收集
        val job = launch {
            flow.toList()
        }

        delay(50)
        assertEquals(PlanApprovalStatus.PENDING, controller.getApprovalStatus("status_test"))
        assertTrue(controller.isPending("status_test"))

        controller.reject("status_test", "test")
        job.join()

        assertEquals(PlanApprovalStatus.REJECTED, controller.getApprovalStatus("status_test"))
        assertFalse(controller.isPending("status_test"))
    }

    @Test
    fun `should get pending approvals list`() = runBlocking {
        val controller = PlanApprovalController()

        val plan1 = createTestPlan("pending_1")
        val plan2 = createTestPlan("pending_2")

        val job1 = launch { controller.requestApproval(plan1, timeout = 5.seconds).toList() }
        val job2 = launch { controller.requestApproval(plan2, timeout = 5.seconds).toList() }

        delay(50)
        val pending = controller.getPendingApprovals()
        assertEquals(2, pending.size)

        controller.approve("pending_1")
        controller.reject("pending_2", "cleanup")

        job1.join()
        job2.join()
    }

    @Test
    fun `should return false when approving non-existent plan`() = runBlocking {
        val controller = PlanApprovalController()
        val result = controller.approve("non_existent")
        assertFalse(result)
    }

    @Test
    fun `should cancel approval request`() = runBlocking {
        val controller = PlanApprovalController()
        val plan = createTestPlan("cancel_test")

        val job = launch { controller.requestApproval(plan, timeout = 10.seconds).toList() }
        delay(50)

        assertTrue(controller.isPending("cancel_test"))
        controller.cancel("cancel_test")
        job.join()

        assertNull(controller.getApprovalStatus("cancel_test"))
    }

    private fun createTestPlan(planId: String): DagTaskPlan {
        return DagTaskPlan(
            taskId = planId,
            description = "Test plan",
            steps = listOf(
                TaskStep(id = "step_1", description = "First step", dependencies = emptyList()),
                TaskStep(id = "step_2", description = "Second step", dependencies = listOf("step_1"))
            ),
            executionOrder = listOf(listOf("step_1"), listOf("step_2")),
            estimatedSteps = 2,
            totalSteps = 2
        )
    }
}
