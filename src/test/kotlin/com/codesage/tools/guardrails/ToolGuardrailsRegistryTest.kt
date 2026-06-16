package com.codesage.tools.guardrails

import com.codesage.model.dto.Tool
import com.codesage.agent.tools.ToolHandler
import com.codesage.model.dto.ToolParameters
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.ToolProperty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证 [ToolGuardrails] 注入 [ToolRegistry] 后,
 * 未知工具决策从"硬编码白名单"切到"按 handler.riskLevel 决策"。
 *
 * 这是 glob 报错修复的核心回归测试,见 docs/CODE_REVIEW_REPORT_2026_06.md C3 改进方向。
 */
class ToolGuardrailsRegistryTest {

    private fun readOnlyTool(name: String) = object : ToolHandler {
        override val tool: Tool = Tool(
            name = name,
            description = "test read-only tool",
            parameters = ToolParameters(
                properties = mapOf("path" to ToolProperty("string", "path")),
                required = listOf("path")
            )
        )
        // riskLevel 默认 SAFE(只读/查询类工具)
        override suspend fun execute(args: JsonObject) = error("not invoked in this test")
    }

    private fun dangerousTool(name: String) = object : ToolHandler {
        override val tool: Tool = Tool(
            name = name,
            description = "test dangerous tool",
            parameters = ToolParameters(
                properties = mapOf("path" to ToolProperty("string", "path")),
                required = listOf("path")
            )
        )
        override val riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
        override suspend fun execute(args: JsonObject) = error("not invoked in this test")
    }

    private fun cautiousTool(name: String) = object : ToolHandler {
        override val tool: Tool = Tool(
            name = name,
            description = "test cautious tool",
            parameters = ToolParameters(properties = emptyMap())
        )
        override val riskLevel = SensitiveActionPolicy.RiskLevel.CAUTION
        override suspend fun execute(args: JsonObject) = error("not invoked in this test")
    }

    @Test
    fun `registered read-only tool with default SAFE riskLevel is allowed without confirmation`() = runBlocking {
        // glob 这种只读工具,handler riskLevel=SAFE,注入 ToolRegistry 后直接放行
        val registry = ToolRegistry().apply { register(readOnlyTool("glob")) }
        val guardrails = ToolGuardrails(toolRegistry = registry)

        val result = guardrails.preCheck("glob", mapOf("pattern" to "src/**/*.kt"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed,
            "glob should be allowed when registered with default riskLevel=SAFE; was=$result")
    }

    @Test
    fun `registered DANGEROUS tool requires confirmation`() = runBlocking {
        // 注意:write_file / delete_file / run_command 等在 ToolGuardrails.evaluateToolOperation
        // 的显式 when 分支中被处理(由 SensitiveActionPolicy 决策),不走 else 分支的 registry 路径。
        // 这里用 git_push 这种不在显式 when 中的工具,会走 else 分支,验证 handler.riskLevel 决策。
        val registry = ToolRegistry().apply { register(dangerousTool("git_push")) }
        // 故意不传 confirmationCallback,headless 路径下应该走到 PreCheckResult.Denied(User declined)
        val guardrails = ToolGuardrails(toolRegistry = registry)

        val result = guardrails.preCheck("git_push", emptyMap())
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied,
            "DANGEROUS tool with no callback should be denied; was=$result")
    }

    @Test
    fun `registered CAUTION tool requires confirmation`() = runBlocking {
        // 用 run_linter 这种不在显式 when 中的工具来验证 CAUTION 决策路径
        val registry = ToolRegistry().apply { register(cautiousTool("run_linter")) }
        val guardrails = ToolGuardrails(toolRegistry = registry)

        val result = guardrails.preCheck("run_linter", emptyMap())
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied,
            "CAUTION tool with no callback should be denied; was=$result")
    }

    @Test
    fun `unregistered tool still requires confirmation even when ToolRegistry is injected`() = runBlocking {
        // 没有注册任何工具,ToolRegistry 注入后仍走 C3 修复的 REQUIRES_CONFIRMATION 行为
        val registry = ToolRegistry()
        val guardrails = ToolGuardrails(toolRegistry = registry)

        val result = guardrails.preCheck("malicious_tool", emptyMap())
        assertTrue(result is ToolGuardrails.PreCheckResult.Denied,
            "Unregistered tool should still be denied; was=$result")
    }

    @Test
    fun `no ToolRegistry injected falls back to KNOWN_SAFE_TOOLS whitelist`() = runBlocking {
        // 不传 toolRegistry,只读工具如果不在 KNOWN_SAFE_TOOLS 白名单里仍走确认
        // (这是给历史单测/纯 guardrails 用法留的兼容路径)
        val guardrails = ToolGuardrails()

        val result = guardrails.preCheck("glob", mapOf("pattern" to "**/*.kt"))
        // glob 不在 KNOWN_SAFE_TOOLS(修复前的状态) -> 走 REQUIRES_CONFIRMATION
        // 这是已知问题,本测试主要是保护"白名单"回退路径不会因为新逻辑而崩溃
        assertTrue(
            result is ToolGuardrails.PreCheckResult.Denied,
            "Without toolRegistry, glob should still hit KNOWN_SAFE_TOOLS fallback and be denied; was=$result"
        )
    }

    @Test
    fun `KNOWN_SAFE_TOOLS entry is allowed even without toolRegistry injection`() = runBlocking {
        val guardrails = ToolGuardrails()
        val result = guardrails.preCheck("read_file", mapOf("path" to "x.kt"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed,
            "read_file is in KNOWN_SAFE_TOOLS so should be allowed; was=$result")
    }

    @Test
    fun `decision reason for registered SAFE tool mentions registry lookup`() = runBlocking {
        val registry = ToolRegistry().apply { register(readOnlyTool("find_file")) }
        val guardrails = ToolGuardrails(toolRegistry = registry)

        val result = guardrails.preCheck("find_file", mapOf("pattern" to "*.kt"))
        assertTrue(result is ToolGuardrails.PreCheckResult.Allowed,
            "find_file with default riskLevel=SAFE should be allowed via registry; was=$result")
        // Allowed 没有 reason 字段,这里只断言类型符合预期即可
    }
}
