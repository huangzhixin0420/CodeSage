package com.codesage.agent.core

import com.codesage.agent.tools.SkillToolAdapter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking

class SubAgentExecutorTest {

    @Test
    fun `should create sub agent config`() {
        val agentCore = AgentCore()
        val executor = SubAgentExecutor(agentCore)

        // Verify the executor was created without error
        assertNotNull(executor)
    }

    @Test
    fun `should validate sub task config`() {
        val config = SubAgentExecutor.SubTaskConfig(
            description = "Test task",
            toolset = "dev",
            maxIterations = 5,
            contextFiles = listOf("/test.txt")
        )

        assertEquals("Test task", config.description)
        assertEquals("dev", config.toolset)
        assertEquals(5, config.maxIterations)
        assertEquals(listOf("/test.txt"), config.contextFiles)
    }

    @Test
    fun `sub task config should have defaults`() {
        val config = SubAgentExecutor.SubTaskConfig(
            description = "Simple task"
        )

        assertEquals("dev", config.toolset)
        assertEquals(10, config.maxIterations)
        assertTrue(config.contextFiles.isEmpty())
    }
}
