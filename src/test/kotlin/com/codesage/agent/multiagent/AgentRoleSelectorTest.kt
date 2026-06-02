package com.codesage.agent.multiagent

import com.codesage.agent.planner.Task
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T4.2 修复验证测试：LLM 驱动的角色选择器
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T4.2）：
 * - [x] 单元测试（mock LLM）：不同 prompt 触发不同角色组合
 * - [x] 集成测试：复杂任务至少涉及 2 个 Agent 协作
 */
class AgentRoleSelectorTest {

    // === KeywordRoleSelector 测试 ===

    @Test
    fun `keyword selector picks REVIEWER for review keyword`() {
        val task = Task(id = "t1", description = "Please review this code", goal = "")
        val roles = KeywordRoleSelector().select(task, AgentRole.values().toList())
        assertTrue(AgentRole.REVIEWER in roles)
    }

    @Test
    fun `keyword selector picks TESTER for test keyword`() {
        val task = Task(id = "t1", description = "Write test cases for the new feature", goal = "")
        val roles = KeywordRoleSelector().select(task, AgentRole.values().toList())
        assertTrue(AgentRole.TESTER in roles)
    }

    @Test
    fun `keyword selector picks RESEARCHER for analyze keyword`() {
        val task = Task(id = "t1", description = "Analyze the trends in the industry", goal = "")
        val roles = KeywordRoleSelector().select(task, AgentRole.values().toList())
        assertTrue(AgentRole.RESEARCHER in roles)
    }

    @Test
    fun `keyword selector picks multiple roles for multi-keyword task`() {
        val task = Task(
            id = "t1",
            description = "Please code a function, then test it, then review the implementation",
            goal = ""
        )
        val roles = KeywordRoleSelector().select(task, AgentRole.values().toList())
        // 应包含 CODER + TESTER + REVIEWER
        assertTrue(AgentRole.CODER in roles)
        assertTrue(AgentRole.TESTER in roles)
        assertTrue(AgentRole.REVIEWER in roles)
    }

    @Test
    fun `keyword selector falls back to CODER for empty description`() {
        val task = Task(id = "t1", description = "x", goal = "")
        val roles = KeywordRoleSelector().select(task, AgentRole.values().toList())
        // 空描述：fallback to CODER
        assertEquals(listOf(AgentRole.CODER), roles)
    }

    // === AgentRoleSelector (with mock LLM) ===

    @Test
    fun `selector with explicit bypass returns explicit roles`() = runBlocking {
        val selector = AgentRoleSelector(
            explicit = listOf(AgentRole.CODER, AgentRole.REVIEWER)
        )
        val task = Task(id = "t1", description = "anything", goal = "")
        val roles = selector.select(task)
        assertEquals(listOf(AgentRole.CODER, AgentRole.REVIEWER), roles)
    }

    @Test
    fun `selector filters explicit roles against available list`() = runBlocking {
        val selector = AgentRoleSelector(
            explicit = listOf(AgentRole.CODER, AgentRole.PLANNER)
        )
        val task = Task(id = "t1", description = "x", goal = "")
        val roles = selector.select(task, available = listOf(AgentRole.CODER, AgentRole.REVIEWER))
        assertEquals(listOf(AgentRole.CODER), roles)
    }

    @Test
    fun `selector with LLM parses valid JSON response`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"roles": ["CODER", "TESTER"], "reasoning": "implement and test"}"""
        }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "Build a calculator app", goal = "")
        val roles = selector.select(task)
        assertEquals(setOf(AgentRole.CODER, AgentRole.TESTER), roles.toSet())
    }

    @Test
    fun `selector with LLM parses markdown-wrapped JSON`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """
            Here is my decision:
            ```json
            {"roles": ["REVIEWER", "CODER"], "reasoning": "review existing code"}
            ```
            """.trimIndent()
        }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "audit and fix", goal = "")
        val roles = selector.select(task)
        assertEquals(setOf(AgentRole.CODER, AgentRole.REVIEWER), roles.toSet())
    }

    @Test
    fun `selector with LLM falls back to keyword on invalid JSON`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> "I am not sure, sorry" }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "code a function", goal = "")
        val roles = selector.select(task)
        // fallback to keyword: CODER
        assertTrue(AgentRole.CODER in roles)
    }

    @Test
    fun `selector with LLM falls back when JSON is empty roles`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> """{"roles": [], "reasoning": "none"}""" }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "code a function", goal = "")
        val roles = selector.select(task)
        // empty roles → fallback to keyword
        assertTrue(AgentRole.CODER in roles)
    }

    @Test
    fun `selector with LLM ignores unknown role names`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"roles": ["CODER", "GHOST", "INVALID"], "reasoning": "x"}"""
        }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "x", goal = "")
        val roles = selector.select(task)
        // 未知 role 被忽略，只留 CODER
        assertEquals(listOf(AgentRole.CODER), roles)
    }

    @Test
    fun `selector without invoker falls back to keyword`() = runBlocking {
        val selector = AgentRoleSelector()  // 无 invoker
        val task = Task(id = "t1", description = "code a function", goal = "")
        val roles = selector.select(task)
        assertTrue(AgentRole.CODER in roles)
    }

    @Test
    fun `selector with throwing LLM falls back to keyword`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> throw RuntimeException("LLM unavailable") }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "code a function", goal = "")
        val roles = selector.select(task)
        assertTrue(AgentRole.CODER in roles)
    }

    @Test
    fun `selector filters LLM-selected roles against available list`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"roles": ["CODER", "PLANNER", "TESTER"], "reasoning": "x"}"""
        }
        val selector = AgentRoleSelector(invoker = mockLlm)
        val task = Task(id = "t1", description = "x", goal = "")
        // 限定 available 为 CODER + REVIEWER
        val roles = selector.select(task, available = listOf(AgentRole.CODER, AgentRole.REVIEWER))
        assertEquals(listOf(AgentRole.CODER), roles)
    }
}
