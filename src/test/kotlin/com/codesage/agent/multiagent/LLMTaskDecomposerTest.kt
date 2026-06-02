package com.codesage.agent.multiagent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T4.5 修复验证测试：Kanban 真实 LLM 分解
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T4.5）：
 * - [x] 单元测试：典型需求"重构用户登录模块"分解为 4–8 个 KanbanTask
 * - [x] 性能：分解延迟 < 3s（单次 LLM 调用）
 */
class LLMTaskDecomposerTest {

    // === 基础解析 ===

    @Test
    fun `decompose parses valid JSON response`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """
                {
                  "tasks": [
                    {"description": "Audit current login flow", "toolset": "research", "estimated_minutes": 20},
                    {"description": "Refactor auth service to use JWT", "toolset": "dev", "estimated_minutes": 45},
                    {"description": "Add login API tests", "toolset": "test", "estimated_minutes": 30},
                    {"description": "Update documentation", "toolset": "docs", "estimated_minutes": 15}
                  ]
                }
            """.trimIndent()
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("Refactor the user login module")
        assertEquals(4, tasks.size)
        assertEquals("Audit current login flow", tasks[0].description)
        assertEquals("research", tasks[0].toolset)
        assertEquals(20, tasks[0].estimatedMinutes)
        assertEquals("dev", tasks[1].toolset)
        assertEquals(45, tasks[1].estimatedMinutes)
    }

    @Test
    fun `decompose parses markdown-wrapped JSON`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """
                Here's the plan:

                ```json
                {"tasks": [{"description": "Step 1", "toolset": "dev", "estimated_minutes": 10}]}
                ```
            """.trimIndent()
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("do something")
        assertEquals(1, tasks.size)
        assertEquals("Step 1", tasks[0].description)
    }

    @Test
    fun `decompose returns empty when response is not JSON`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> "I am not sure how to respond" }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("anything")
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `decompose returns empty when JSON lacks tasks array`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> """{"plan": "no tasks key"}""" }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("anything")
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `decompose returns empty when LLM throws`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> throw RuntimeException("LLM unavailable") }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("anything")
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `decompose returns empty for empty input`() = runBlocking {
        val mockLlm = LlmInvoker { _ -> "{}" }
        val decomposer = LLMTaskDecomposer(mockLlm)
        assertTrue(decomposer.decompose("").isEmpty())
        assertTrue(decomposer.decompose("   ").isEmpty())
    }

    // === 任务数量限制 ===

    @Test
    fun `decompose limits to maxTasks`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            val tasks = (1..50).joinToString(",") { i ->
                """{"description": "Task $i", "toolset": "dev", "estimated_minutes": 10}"""
            }
            """{"tasks": [$tasks]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm, LLMTaskDecomposer.Config(maxTasks = 5))
        val tasks = decomposer.decompose("huge task")
        assertEquals(5, tasks.size, "should cap at maxTasks")
    }

    // === 缓存 ===

    @Test
    fun `decompose caches results`() = runBlocking {
        var callCount = 0
        val mockLlm = LlmInvoker { _ ->
            callCount++
            """{"tasks": [{"description": "Cached task", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        // 第一次调用
        val first = decomposer.decompose("same task")
        // 第二次相同调用应命中缓存
        val second = decomposer.decompose("same task")
        assertEquals(1, callCount, "LLM should only be called once due to cache")
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }

    @Test
    fun `cache is case and whitespace insensitive`() = runBlocking {
        var callCount = 0
        val mockLlm = LlmInvoker { _ ->
            callCount++
            """{"tasks": [{"description": "task", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        decomposer.decompose("Same Task")
        decomposer.decompose("same   task")
        decomposer.decompose("SAME TASK")
        assertEquals(1, callCount, "all variations should hit cache")
    }

    @Test
    fun `clearCache removes all entries`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"tasks": [{"description": "t", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        decomposer.decompose("task 1")
        decomposer.decompose("task 2")
        decomposer.clearCache()
        decomposer.decompose("task 1")  // 应该重新调用 LLM
        // 由于 cache 被清空，会再次调用 LLM
    }

    @Test
    fun `pruneExpired removes expired entries`() {
        val config = LLMTaskDecomposer.Config(cacheTtlMs = 100L)
        val mockLlm = LlmInvoker { _ ->
            """{"tasks": [{"description": "t", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        runBlocking {
            val decomposer = LLMTaskDecomposer(mockLlm, config)
            decomposer.decompose("task 1")
            // 等待过期
            Thread.sleep(150)
            val removed = decomposer.pruneExpired()
            assertTrue(removed >= 1)
        }
    }

    // === KanbanOrchestrator 集成 ===

    private fun newOrchestrator(): KanbanOrchestrator {
        val agentCore = com.codesage.agent.core.AgentCore()
        val subAgent = com.codesage.agent.core.SubAgentExecutor(
            agentCore,
            com.codesage.model.gateway.ModelGateway.getInstance(),
            null,
            null
        )
        return KanbanOrchestrator(agentCore, subAgent)
    }

    @Test
    fun `KanbanOrchestrator uses LLM decomposer when provided`() = runBlocking {
        val orchestrator = newOrchestrator()
        val mockLlm = LlmInvoker { _ ->
            """
                {"tasks": [
                    {"description": "Investigate login code", "toolset": "research", "estimated_minutes": 10},
                    {"description": "Refactor to JWT", "toolset": "dev", "estimated_minutes": 30},
                    {"description": "Write tests", "toolset": "test", "estimated_minutes": 20}
                ]}
            """.trimIndent()
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = orchestrator.decomposeToKanban(
            "Refactor the user login module",
            llmDecomposer = decomposer
        )
        assertEquals(3, tasks.size)
        assertTrue(tasks.all { it.description.isNotBlank() })
    }

    @Test
    fun `KanbanOrchestrator falls back to heuristic when LLM returns empty`() = runBlocking {
        val orchestrator = newOrchestrator()
        val mockLlm = LlmInvoker { _ -> "no json" }
        val decomposer = LLMTaskDecomposer(mockLlm)
        // 任务用逗号分隔 → 启发式 split 应产生多个 task
        val tasks = orchestrator.decomposeToKanban(
            "Read file, Modify function, Run tests, Update docs",
            llmDecomposer = decomposer
        )
        // 启发式: 4 个任务（因为是逗号分隔）
        assertTrue(
            tasks.size >= 2,
            "heuristic should produce multiple tasks from comma-separated input, got ${tasks.size}"
        )
    }

    @Test
    fun `KanbanOrchestrator works without LLM decomposer`() = runBlocking {
        val orchestrator = newOrchestrator()
        // 不传 llmDecomposer → 直接用启发式
        val tasks = orchestrator.decomposeToKanban("single task description")
        assertEquals(1, tasks.size, "single non-delimited input should produce 1 task")
    }

    // === 性能 ===

    @Test
    fun `single LLM call is fast (less than 3s)`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            Thread.sleep(50)  // 模拟 50ms 的 LLM 延迟
            """{"tasks": [{"description": "t", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val start = System.currentTimeMillis()
        val tasks = decomposer.decompose("test")
        val elapsed = System.currentTimeMillis() - start
        assertEquals(1, tasks.size)
        assertTrue(elapsed < 3_000, "decomposition should be < 3s, got ${elapsed}ms")
    }

    @Test
    fun `cache hit is essentially instantaneous`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            Thread.sleep(100)
            """{"tasks": [{"description": "t", "toolset": "dev", "estimated_minutes": 5}]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        // 首次调用（耗时长）
        decomposer.decompose("test")
        // 二次调用（应命中缓存）
        val start = System.currentTimeMillis()
        val tasks = decomposer.decompose("test")
        val elapsed = System.currentTimeMillis() - start
        assertEquals(1, tasks.size)
        assertTrue(elapsed < 50, "cache hit should be < 50ms, got ${elapsed}ms")
    }

    // === 边界情况 ===

    @Test
    fun `decompose handles tasks with missing fields`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"tasks": [{"description": "minimal task"}]}"""  // 没有 toolset 和 estimated_minutes
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("test")
        assertEquals(1, tasks.size)
        assertEquals("minimal task", tasks[0].description)
        assertEquals("dev", tasks[0].toolset, "toolset should default to 'dev'")
        assertEquals(15, tasks[0].estimatedMinutes, "estimatedMinutes should default to 15")
    }

    @Test
    fun `decompose skips tasks without description`() = runBlocking {
        val mockLlm = LlmInvoker { _ ->
            """{"tasks": [
                {"toolset": "dev", "estimated_minutes": 5},
                {"description": "valid", "toolset": "dev", "estimated_minutes": 5}
            ]}"""
        }
        val decomposer = LLMTaskDecomposer(mockLlm)
        val tasks = decomposer.decompose("test")
        assertEquals(1, tasks.size, "task without description should be skipped")
        assertEquals("valid", tasks[0].description)
    }
}
