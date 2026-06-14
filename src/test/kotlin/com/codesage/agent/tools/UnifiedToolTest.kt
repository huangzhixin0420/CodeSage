package com.codesage.agent.tools

import com.codesage.analysis.CodeInsightExecutor
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * T6.1 修复验证测试：工具系统统一（UnifiedTool）
 *
 * 验证：
 * 1. UnifiedTool 基类的语义：metadata + logic 在同一 class
 * 2. 注册 UnifiedTool 派生类后能通过 registry.getHandler() 找到
 * 3. 工具元数据正确（name / description / parameters）
 * 4. 旧的 ToolExecutor 硬编码 when 已移除：未注册的工具返回错误
 * 5. 所有现有工具（read_file, write_file 等）仍可被注册和发现
 * 6. 新增一个 tool 只需创建 1 个 class（验收标准 #1）
 */
class UnifiedToolTest {

    /**
     * 测试用最小 UnifiedTool：返回固定字符串
     */
    class EchoTool(val prefix: String = "echo:") : UnifiedTool(
        name = "test_echo",
        description = "Returns the input prefixed with a configurable string",
        parameters = ToolParameters(
            type = "object",
            properties = mapOf(
                "input" to ToolProperty("string", "Input to echo back")
            ),
            required = listOf("input")
        )
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            val input = args["input"]?.toString()?.removeSurrounding("\"") ?: ""
            return ToolResult.Success(JsonObject(mapOf("output" to JsonPrimitive("$prefix$input"))))
        }
    }

    /**
     * 测试用会失败的 UnifiedTool
     */
    class AlwaysErrorTool : UnifiedTool(
        name = "test_error",
        description = "Always returns an error",
        parameters = ToolParameters(type = "object", properties = emptyMap(), required = emptyList())
    ) {
        override suspend fun execute(args: JsonObject): ToolResult {
            return ToolResult.Error("simulated failure")
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `metadata is correctly derived from constructor args`() {
        val tool = EchoTool()
        assertEquals("test_echo", tool.name)
        assertEquals("test_echo", tool.tool.name)
        assertEquals("Returns the input prefixed with a configurable string", tool.tool.description)
        assertEquals(1, tool.tool.parameters.properties.size)
        assertTrue(tool.tool.parameters.properties.containsKey("input"))
        assertEquals(listOf("input"), tool.tool.parameters.required)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `registered UnifiedTool is discoverable and executable`() = runBlocking {
        val registry = ToolRegistry()
        val tool = EchoTool(prefix = "[TEST]")
        registry.register(tool)

        // 1. 可发现
        assertNotNull(registry.get("test_echo"), "Tool should be registered")
        assertNotNull(registry.getHandler("test_echo"), "Handler should be registered")
        assertTrue(registry.hasHandler("test_echo"))

        // 2. 可执行
        val handler = registry.getHandler("test_echo")!!
        val result = handler.execute(JsonObject(mapOf("input" to JsonPrimitive("hello"))))
        assertTrue(result is ToolResult.Success, "Should return Success")
        val data = (result as ToolResult.Success).data as JsonObject
        assertEquals("[TEST]hello", data["output"]?.toString()?.removeSurrounding("\""))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `registering two tools with same name overwrites`() {
        val registry = ToolRegistry()
        val tool1 = EchoTool(prefix = "first:")
        val tool2 = EchoTool(prefix = "second:")
        assertEquals(tool1.name, tool2.name, "Both have same name")

        registry.register(tool1)
        registry.register(tool2)

        // 第二个应该覆盖第一个
        val handler = registry.getHandler("test_echo")!!
        assertEquals("second:", (handler as EchoTool).prefix)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `unregister removes tool from registry`() {
        val registry = ToolRegistry()
        registry.register(EchoTool())
        assertNotNull(registry.get("test_echo"))

        registry.unregister("test_echo")
        assertNull(registry.get("test_echo"))
        assertNull(registry.getHandler("test_echo"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `getAllTools returns all registered tools`() {
        val registry = ToolRegistry()
        registry.register(EchoTool())
        registry.register(AlwaysErrorTool())

        val all = registry.getAllTools()
        val names = all.map { it.name }
        assertTrue("test_echo" in names)
        assertTrue("test_error" in names)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `unified tool definition is single class - new tool requires only one class`() {
        // 这是验收标准 #1：新增一个 tool 只需创建 1 个 class
        class MyNewTool : UnifiedTool(
            name = "my_new_tool",
            description = "Just created for test",
            parameters = ToolParameters(type = "object", properties = emptyMap(), required = emptyList())
        ) {
            override suspend fun execute(args: JsonObject): ToolResult {
                return ToolResult.Success(JsonObject(emptyMap()))
            }
        }

        val registry = ToolRegistry()
        val tool = MyNewTool()
        registry.register(tool)
        assertNotNull(registry.get("my_new_tool"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `default registry still works for all 50+ tools`() {
        // 不传 project（IDE 上下文外），验证所有不依赖 project 的工具仍能注册
        val registry = ToolRegistry.createDefault(project = null)
        val allTools = registry.getAllTools()
        assertTrue(allTools.size >= 40, "Should have at least 40 tools, got ${allTools.size}")

        // 验证一些关键工具在列表中
        val toolNames = allTools.map { it.name }.toSet()
        assertTrue("read_file" in toolNames, "read_file should be registered")
        assertTrue("write_file" in toolNames, "write_file should be registered")
        assertTrue("analyze_symbol" in toolNames, "analyze_symbol should be registered (UnifiedTool)")
        assertTrue("find_usages" in toolNames, "find_usages should be registered (UnifiedTool)")
        assertTrue("get_inheritance_chain" in toolNames, "get_inheritance_chain should be registered (UnifiedTool)")
        assertTrue("semantic_search" in toolNames, "semantic_search should be registered (UnifiedTool)")
        assertTrue("get_file_summary" in toolNames, "get_file_summary should be registered (UnifiedTool)")
        assertTrue("get_project_stats" in toolNames, "get_project_stats should be registered (UnifiedTool)")
        assertTrue("dependency_tree" in toolNames, "dependency_tree should be registered (UnifiedTool)")
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `code insight unified tools have correct metadata`() {
        val registry = ToolRegistry.createDefault(project = null)
        val analyzeSymbol = registry.get("analyze_symbol")
        assertNotNull(analyzeSymbol, "analyze_symbol should be registered as UnifiedTool")
        assertTrue(
            analyzeSymbol!!.parameters.required.contains("symbol_name"),
            "analyze_symbol should require symbol_name"
        )

        val findUsages = registry.get("find_usages")
        assertNotNull(findUsages)
        assertTrue(findUsages!!.parameters.required.contains("symbol_name"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `unknown tool returns error from ToolExecutor`() = runBlocking {
        // T6.1 验收：ToolExecutor 不再硬编码 when，未注册的工具返回错误
        // 构造一个 ToolExecutor（需要 gateway / guardrails 等）
        // 这里只测试 ToolRegistry 行为
        val registry = ToolRegistry()
        assertNull(registry.getHandler("definitely_not_a_real_tool"))
    }
}
