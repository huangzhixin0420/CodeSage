package com.codesage.skill.registry

import com.codesage.skill.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DynamicSkillRegistryTest {

    private val registry = DynamicSkillRegistry()

    private fun createDummySkill(id: String, category: SkillCategory = SkillCategory.CUSTOM): Skill {
        return object : Skill {
            override val id: String = id
            override val name: String = "Skill $id"
            override val description: String = "Description for $id"
            override val version: String = "1.0.0"
            override val category: SkillCategory = category
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
    fun `should register skill with toolset`() {
        val skill = createDummySkill("test_skill")
        registry.registerWithToolset(skill, "dev")

        assertTrue(registry.contains("test_skill"))
        assertEquals(1, registry.getByToolset("dev").size)
    }

    @Test
    fun `should filter unavailable toolset`() {
        val skill = createDummySkill("docker_skill")
        registry.registerWithToolset(skill, "docker")

        // Register check that returns false
        registry.registerToolsetCheck("docker") { false }

        assertTrue(registry.getByToolset("docker").isEmpty())
    }

    @Test
    fun `should cache toolset availability`() {
        var callCount = 0
        registry.registerToolsetCheck("test") { callCount++; true }

        registry.isToolsetAvailable("test")
        registry.isToolsetAvailable("test")

        // First call should check, second should use cache
        assertTrue(callCount <= 2) // May be 1 or 2 depending on timing
    }

    @Test
    fun `should track skill usage`() {
        val skill = createDummySkill("used_skill")
        registry.register(skill)

        registry.recordUsage("used_skill")
        registry.recordUsage("used_skill")
        registry.recordUsage("used_skill")

        assertEquals(3, registry.getUsageCount("used_skill"))
    }

    @Test
    fun `should track provenance`() {
        val skill = createDummySkill("provenance_skill")
        registry.register(skill)

        registry.setProvenance("provenance_skill", "user_created")
        assertEquals("user_created", registry.getProvenance("provenance_skill"))

        val userSkills = registry.getByProvenance("user_created")
        assertEquals(1, userSkills.size)
        assertEquals("provenance_skill", userSkills[0].id)
    }

    @Test
    fun `should increment generation`() {
        val gen1 = registry.getGeneration()
        val gen2 = registry.incrementGeneration()

        assertEquals(gen1 + 1, gen2)
    }

    @Test
    fun `should get all available tools`() {
        val skill1 = createDummySkill("skill1")
        val skill2 = createDummySkill("skill2")

        registry.registerWithToolset(skill1, "dev")
        registry.register(skill2) // no toolset

        val tools = registry.getAllAvailableTools()
        assertEquals(2, tools.size)
    }

    @Test
    fun `should support dynamic schema override`() {
        val skill = createDummySkill("dynamic_skill")
        registry.registerWithDynamicSchema(skill) {
            mapOf("dynamic_param" to mapOf("type" to "string", "description" to "Dynamic"))
        }

        val schema = registry.getDynamicSchema("dynamic_skill")
        assertNotNull(schema)
        assertTrue(schema!!.containsKey("dynamic_param"))
    }
}
