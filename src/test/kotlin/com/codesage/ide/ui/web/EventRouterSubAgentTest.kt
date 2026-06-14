package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.SubAgentExecutor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * EventRouter SubAgent 路径测试(P5.5)
 *
 * 验证 SubAgentStart/Complete 转换出的 JSON 包含:
 *  - tool_call_start 时有 arguments(toolset / task)
 *  - tool_call_complete 时 result.kind = "subagent" 且 result.subagent 有完整结构
 *  - elapsedMs 在两次事件间合理递增
 */
class EventRouterSubAgentTest {

    @Test
    fun `SubAgentStart produces tool_call_start with toolset and task`() {
        val router = EventRouter()
        val event = AgentStreamEvent.SubAgentStart(
            sessionId = "sa-1",
            taskDescription = "搜索代码",
            toolset = "code-search",
        )
        val msg = router.toMessage(event, "t-1")
        assertNotNull(msg)
        assertEquals("tool_call_start", msg!!["type"])
        assertEquals("subagent", msg["toolName"])
        assertEquals("sa-1", msg["toolId"])
        assertEquals("搜索代码", msg["summary"])
        val args = msg["arguments"] as? Map<*, *>
        assertNotNull(args)
        assertEquals("code-search", args!!["toolset"])
        assertEquals("搜索代码", args["task"])
        // 6.10.4: 默认字段也应透传到 Web UI 参数
        assertEquals(SubAgentExecutor.DEFAULT_MAX_RECURSION_DEPTH, args["maxDepth"])
        assertEquals(emptyList<String>(), args["allowedTools"])
        assertEquals(emptyList<String>(), args["deniedTools"])
        assertEquals(0, args["depth"])
        assertEquals(false, args["delegationForbidden"])
    }

    @Test
    fun `SubAgentComplete produces structured subagent result with elapsed time`() {
        val router = EventRouter()
        val sessionId = "sa-elapsed-test"
        // 先发 Start
        router.toMessage(
            AgentStreamEvent.SubAgentStart(
                sessionId = sessionId,
                taskDescription = "test task",
                toolset = "test-toolset",
            ),
            "t-1",
        )
        // 等待 10ms 以上,确保 elapsedMs > 0
        Thread.sleep(20)
        val msg = router.toMessage(
            AgentStreamEvent.SubAgentComplete(
                sessionId = sessionId,
                success = true,
                output = "任务完成",
            ),
            "t-1",
        )
        assertNotNull(msg)
        assertEquals("tool_call_complete", msg!!["type"])
        assertEquals(true, msg["success"])
        val result = msg["result"] as? Map<*, *>
        assertNotNull(result)
        assertEquals("subagent", result!!["kind"])
        val sa = result["subagent"] as? Map<*, *>
        assertNotNull(sa)
        assertEquals(sessionId, sa!!["sessionId"])
        val taskValue = sa["task"] as? String
        assertEquals("test task", taskValue)
        assertEquals("test-toolset", sa["toolset"])
        assertEquals("任务完成", sa["output"])
        val elapsed = (sa["elapsedMs"] as? Number)?.toLong()
        assertNotNull(elapsed)
        assertTrue(elapsed!! >= 10, "elapsedMs 应至少 10ms,实际=$elapsed")
    }

    @Test
    fun `SubAgentComplete without matching Start has empty task and toolset`() {
        val router = EventRouter()
        val msg = router.toMessage(
            AgentStreamEvent.SubAgentComplete(
                sessionId = "unknown-session",
                success = false,
                output = "failed",
            ),
            "t-1",
        )
        assertNotNull(msg)
        val result = msg!!["result"] as? Map<*, *>
        val sa = result?.get("subagent") as? Map<*, *>
        assertNotNull(sa)
        // 没有匹配的 Start → task 和 toolset 应为空字符串
        assertEquals("", sa!!["task"])
        assertEquals("", sa["toolset"])
    }
}
