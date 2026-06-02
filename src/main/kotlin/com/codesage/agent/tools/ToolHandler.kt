package com.codesage.agent.tools

import com.codesage.model.dto.Tool
import kotlinx.serialization.json.JsonObject

/**
 * 工具处理器接口
 *
 * 将工具定义（Tool）与工具执行逻辑绑定，消除 ToolExecutor 中的硬编码 switch 路由。
 * 所有新工具应实现此接口并通过 ToolRegistry.register(handler) 注册。
 */
interface ToolHandler {
    /**
     * 工具元数据定义，用于 OpenAI Function Calling 格式
     */
    val tool: Tool

    /**
     * 执行工具调用
     * @param args AI 模型传入的参数
     * @return 工具执行结果
     */
    suspend fun execute(args: JsonObject): ToolResult

    /**
     * 工具名称（从 tool 中派生，便于快速访问）
     */
    val name: String get() = tool.name
}

/**
 * 将现有的非 suspend 工具方法适配为 ToolHandler
 */
abstract class SyncToolHandler(override val tool: Tool) : ToolHandler {
    final override suspend fun execute(args: JsonObject): ToolResult {
        return executeSync(args)
    }

    protected abstract fun executeSync(args: JsonObject): ToolResult
}

/**
 * 函数式工具处理器：通过闭包快速创建工具
 */
class FunctionalToolHandler(
    override val tool: Tool,
    private val executor: (JsonObject) -> ToolResult
) : ToolHandler {
    override suspend fun execute(args: JsonObject): ToolResult = executor(args)
}
