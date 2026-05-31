package com.codesage.skill

import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkillExecutorTest {

    @Test
    fun `should execute skill and return success`() = runBlocking {
        val registry = SkillRegistry()
        val executor = SkillExecutor(registry)
        val skill = TestSkill("test_skill", true)
        registry.register(skill)

        val result = executor.execute(
            skillId = "test_skill",
            input = SkillInput(mapOf("key" to "value")),
            context = ExecutionContext()
        )

        assertTrue(result.isSuccess)
        assertEquals("value", (result as SkillResult.Success).output["key"])
    }

    @Test
    fun `should return failure for unknown skill`() = runBlocking {
        val executor = SkillExecutor(SkillRegistry())

        val result = executor.execute(
            skillId = "unknown",
            input = SkillInput(emptyMap()),
            context = ExecutionContext()
        )

        assertFalse(result.isSuccess)
        assertTrue((result as SkillResult.Failure).error.contains("not found"))
    }

    @Test
    fun `should return failure when canExecute returns false`() = runBlocking {
        val registry = SkillRegistry()
        val executor = SkillExecutor(registry)
        val skill = TestSkill("blocked_skill", false)
        registry.register(skill)

        val result = executor.execute(
            skillId = "blocked_skill",
            input = SkillInput(emptyMap()),
            context = ExecutionContext()
        )

        assertFalse(result.isSuccess)
        assertTrue((result as SkillResult.Failure).error.contains("Cannot execute"))
    }

    @Test
    fun `should cancel running task`() {
        val executor = SkillExecutor(SkillRegistry())
        val taskId = executor.executeAsync(
            skillId = "unknown",
            input = SkillInput(emptyMap()),
            context = ExecutionContext()
        )

        // 即使任务很快完成，cancel 也不应该抛异常
        assertDoesNotThrow {
            executor.cancel(taskId)
        }
    }

    private class TestSkill(
        override val id: String,
        private val executable: Boolean
    ) : Skill {
        override val name: String = id
        override val description: String = "Test skill"
        override val version: String = "1.0"
        override val category: SkillCategory = SkillCategory.CUSTOM
        override val tags: Set<String> = emptySet()
        override val inputSchema: Map<String, Any> = emptyMap()
        override val outputSchema: Map<String, Any> = emptyMap()

        override fun canExecute(context: ExecutionContext): CanExecuteResult {
            return if (executable) CanExecuteResult(true) else CanExecuteResult(false, "Not executable")
        }

        override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
            return SkillResult.Success(input.arguments)
        }
    }
}
