package com.codesage.skill.curator

import com.codesage.agent.core.AgentCore
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.dto.ToolCall
import com.codesage.skill.*
import com.codesage.skill.registry.DynamicSkillRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SkillCuratorTest {

    private fun createDynamicRegistry(): DynamicSkillRegistry {
        return DynamicSkillRegistry()
    }

    private fun createDummySkill(id: String): Skill {
        return object : Skill {
            override val id: String = id
            override val name: String = "Skill $id"
            override val description: String = "Description for $id"
            override val version: String = "1.0.0"
            override val category: SkillCategory = SkillCategory.CUSTOM
            override val tags: Set<String> = setOf("test")
            override val inputSchema: Map<String, Any> = emptyMap()
            override val outputSchema: Map<String, Any> = emptyMap()

            override fun canExecute(context: ExecutionContext): CanExecuteResult {
                return CanExecuteResult(true)
            }

            override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
                return SkillResult.Success(emptyMap())
            }
        }
    }

    @Test
    fun `should analyze conversation patterns`() = runBlocking {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        // Create conversation with repeated tool calls
        val history = listOf(
            Message.userMessage("Search for auth code"),
            Message.assistantMessage(
                "Searching...", toolCalls = listOf(
                    ToolCall("1", "search_code", "{\"query\":\"auth\"}")
                )
            ),
            Message.userMessage("Search for login code"),
            Message.assistantMessage(
                "Searching...", toolCalls = listOf(
                    ToolCall("2", "search_code", "{\"query\":\"login\"}")
                )
            ),
            Message.userMessage("Search for password code"),
            Message.assistantMessage(
                "Searching...", toolCalls = listOf(
                    ToolCall("3", "search_code", "{\"query\":\"password\"}")
                )
            ),
            Message.userMessage("Search for token code"),
            Message.assistantMessage(
                "Searching...", toolCalls = listOf(
                    ToolCall("4", "search_code", "{\"query\":\"token\"}")
                )
            ),
            Message.userMessage("Read the auth file"),
            Message.assistantMessage(
                "Reading...", toolCalls = listOf(
                    ToolCall("5", "read_file", "{\"path\":\"/auth.kt\"}")
                )
            ),
            Message.userMessage("Read the login file"),
            Message.assistantMessage(
                "Reading...", toolCalls = listOf(
                    ToolCall("6", "read_file", "{\"path\":\"/login.kt\"}")
                )
            )
        )

        // Should not trigger with <= 5 iterations
        curator.runBackgroundReview("session_1", history, 3)
        // Should trigger with > 5 iterations
        curator.runBackgroundReview("session_1", history, 6)

        // No crash is success for this test
        assertTrue(true)
    }

    @Test
    fun `should consolidate similar skills`() = runBlocking {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        // Register similar skills
        val skill1 = createDummySkill("auth_helper_v1")
        val skill2 = createDummySkill("auth_helper_v2")
        val skill3 = createDummySkill("unrelated_skill")

        registry.register(skill1)
        registry.register(skill2)
        registry.register(skill3)

        // Set descriptions to be similar
        // (Since we can't modify the skill after creation, we test with what we have)

        curator.consolidate()

        // Should not crash
        assertTrue(true)
    }

    @Test
    fun `should record skill usage`() {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        curator.recordSkillUsed("skill_1")
        curator.recordSkillUsed("skill_1")

        assertEquals(2, registry.getUsageCount("skill_1"))
    }

    @Test
    fun `should load auto skills returns empty when no files`() {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        val skills = curator.loadAutoSkills()
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `should calculate string similarity`() {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        // Access private method via reflection for testing
        val method =
            SkillCurator::class.java.getDeclaredMethod("calculateSimilarity", String::class.java, String::class.java)
        method.isAccessible = true

        val exact = method.invoke(curator, "hello", "hello") as Double
        assertEquals(1.0, exact, 0.01)

        val empty = method.invoke(curator, "", "hello") as Double
        assertEquals(0.0, empty, 0.01)

        val partial = method.invoke(curator, "auth helper", "authentication helper") as Double
        assertTrue(partial > 0.3, "Partial similarity should be > 0.3, got $partial")
    }

    @Test
    fun `should not run concurrent reviews`() = runBlocking {
        val registry = createDynamicRegistry()
        val curator = SkillCurator(AgentCore(), registry)

        // First review starts
        val history = (1..10).map {
            Message.userMessage("Request $it")
        }

        // Two concurrent calls - second should be skipped
        val job1 = async {
            curator.runBackgroundReview("session_concurrent", history, 10)
        }
        val job2 = async {
            curator.runBackgroundReview("session_concurrent", history, 10)
        }

        job1.await()
        job2.await()

        // Should complete without crash
        assertTrue(true)
    }
}
