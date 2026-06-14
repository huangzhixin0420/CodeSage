package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.UseSkillTool
import com.codesage.model.dto.ToolCategory
import com.codesage.skill.*
import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 6.11.3 Skill 工具适配器与元工具测试
 *
 * 验证：
 * 1. [SkillToolAdapter.toTools] 生成的 Tool 包含 category、tags 与 examples。
 * 2. [SkillToolAdapter.execute] 能正确路由到技能并返回 JSON。
 * 3. [UseSkillTool] 可通过 `use_skill(skill_id, arguments)` 执行任意技能。
 */
class SkillToolAdapterTest {

    /**
     * 测试用技能：将输入的字符串转为大写并返回。
     */
    private class UppercaseSkill : Skill {
        override val id = "test_uppercase"
        override val name = "Uppercase"
        override val description = "Convert text to uppercase"
        override val version = "1.0.0"
        override val category = SkillCategory.CUSTOM
        override val tags = setOf("text", "transform")
        override val examples = listOf(
            "{\"text\": \"hello\"}",
            "Use text=\"world\" to get WORLD"
        )
        override val inputSchema = mapOf(
            "text" to mapOf("type" to "string", "description" to "Input text", "required" to true)
        )
        override val outputSchema = mapOf(
            "result" to mapOf("type" to "string")
        )

        override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

        override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
            val text = input.getString("text") ?: return SkillResult.Failure("Missing text")
            return SkillResult.Success(mapOf("result" to text.uppercase()))
        }
    }

    /**
     * 总是失败的测试技能。
     */
    private class FailingSkill : Skill {
        override val id = "test_failing"
        override val name = "Failing"
        override val description = "Always fails"
        override val version = "1.0.0"
        override val category = SkillCategory.EXECUTION
        override val tags = setOf("test", "failure")
        override val inputSchema = emptyMap<String, Any>()
        override val outputSchema = emptyMap<String, Any>()

        override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

        override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
            return SkillResult.Failure("simulated failure")
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `toTools includes category tags and examples`() {
        val registry = SkillRegistry()
        registry.register(UppercaseSkill())
        val adapter = SkillToolAdapter(registry, SkillExecutor(registry))

        val tools = adapter.toTools()
        assertEquals(1, tools.size)

        val tool = tools.first()
        assertEquals("skill_test_uppercase", tool.name)
        assertEquals(ToolCategory.GENERAL, tool.category)
        assertEquals(setOf("text", "transform"), tool.tags)
        assertTrue(tool.description.contains("Examples:"), "Description should include examples header")
        assertTrue(tool.description.contains("{\"text\": \"hello\"}"), "Description should include first example")
        assertTrue(tool.parameters.properties.containsKey("text"))
        assertEquals(listOf("text"), tool.parameters.required)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `execute routes skill call and returns success json`() = runBlocking {
        val registry = SkillRegistry()
        registry.register(UppercaseSkill())
        val adapter = SkillToolAdapter(registry, SkillExecutor(registry))

        val result = adapter.execute("skill_test_uppercase", "{\"text\":\"hello\"}")
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(JsonPrimitive(true), json["success"])
        assertEquals("HELLO", json["output"]?.jsonObject?.get("result")?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `execute returns error json for failing skill`() = runBlocking {
        val registry = SkillRegistry()
        registry.register(FailingSkill())
        val adapter = SkillToolAdapter(registry, SkillExecutor(registry))

        val result = adapter.execute("skill_test_failing", "{}")
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(JsonPrimitive(false), json["success"])
        assertEquals("simulated failure", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `use_skill executes skill by id`() = runBlocking {
        val registry = SkillRegistry()
        registry.register(UppercaseSkill())
        val useSkillTool = UseSkillTool(registry, SkillExecutor(registry))

        val result = useSkillTool.execute(
            JsonObject(
                mapOf(
                    "skill_id" to JsonPrimitive("test_uppercase"),
                    "arguments" to JsonObject(mapOf("text" to JsonPrimitive("kotlin")))
                )
            )
        )

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data as JsonObject
        assertEquals(true, data["success"]?.jsonPrimitive?.boolean)
        assertEquals("KOTLIN", data["output"]?.jsonObject?.get("result")?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `use_skill returns error for missing skill_id`() = runBlocking {
        val registry = SkillRegistry()
        val useSkillTool = UseSkillTool(registry, SkillExecutor(registry))

        val result = useSkillTool.execute(JsonObject(mapOf("arguments" to JsonObject(emptyMap()))))
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("skill_id"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `use_skill returns error for unknown skill`() = runBlocking {
        val registry = SkillRegistry()
        val useSkillTool = UseSkillTool(registry, SkillExecutor(registry))

        val result = useSkillTool.execute(
            JsonObject(mapOf("skill_id" to JsonPrimitive("unknown")))
        )
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("not found"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `use_skill is registered in default registry when skill components provided`() {
        val registry = SkillRegistry()
        registry.register(UppercaseSkill())
        val toolRegistry = ToolRegistry.createDefault(
            project = null,
            skillRegistry = registry,
            skillExecutor = SkillExecutor(registry)
        )

        assertNotNull(toolRegistry.get("use_skill"), "use_skill should be registered")
        assertNotNull(toolRegistry.getHandler("use_skill"), "use_skill handler should be registered")
    }
}
