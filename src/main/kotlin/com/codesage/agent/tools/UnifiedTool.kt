package com.codesage.agent.tools

import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.tools.guardrails.SensitiveActionPolicy
import kotlinx.serialization.json.JsonObject

/**
 * T6.1 修复：工具元数据 + Handler 实现的统一基类
 *
 * 解决原架构中"Tool 定义"（在 ToolRegistry.kt 顶层函数）+ "Handler 实现"（在 handlers/ 包下）
 * 的"三处声明混乱"问题。
 *
 * **老模式**（每个工具在 3 个地方被提及）：
 * 1. `ToolRegistry.kt` 顶层 `readFileTool()` 函数定义 Tool 元数据
 * 2. `IDETools.kt` 提供实际实现
 * 3. `IDEFileHandlers.kt` 用 `FunctionalToolHandler(readFileTool()) { ideTools.readFile(it) }` 装配
 * 4. `ToolExecutor` 还有硬编码 `when` 路由
 *
 * **新模式**（每个工具在 1 个地方被声明）：
 * ```kotlin
 * class ReadFileTool(private val ideTools: IDETools) : UnifiedTool(
 *     name = "read_file",
 *     description = "Read a file's content",
 *     parameters = ToolParameters(
 *         type = "object",
 *         properties = mapOf(
 *             "path" to ToolProperty("string", "File path")
 *         ),
 *         required = listOf("path")
 *     )
 * ) {
 *     override suspend fun execute(args: JsonObject): ToolResult = ideTools.readFile(args)
 * }
 * ```
 *
 * 注册：`registry.register(ReadFileTool(ideTools))`
 *
 * 优点：
 * - Tool 名称、描述、参数 schema、执行逻辑 都在一个类里，新增/修改无需跨文件
 * - 不再需要 FunctionalToolHandler 包装
 * - 不再需要 ToolRegistry 顶层工厂函数
 * - 简化 ToolExecutor（移除硬编码 when 路由）
 *
 * 注意：实现仍可委托给 IDETools / ExtendedTools 等"基础设施"类，
 * 避免重复实现逻辑。本基类只解决"声明分散"，不解决"实现合并"。
 */
abstract class UnifiedTool(
    name: String,
    description: String,
    parameters: ToolParameters,
    override val riskLevel: SensitiveActionPolicy.RiskLevel = SensitiveActionPolicy.RiskLevel.SAFE
) : ToolHandler {

    override val tool: Tool = Tool(
        name = name,
        description = description,
        parameters = parameters
    )

    final override val name: String get() = tool.name

    abstract override suspend fun execute(args: JsonObject): ToolResult

    override fun toString(): String = "UnifiedTool(name='$name')"
}
