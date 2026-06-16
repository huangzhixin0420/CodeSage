package com.codesage.agent.tools

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.model.dto.Tool
import com.codesage.tools.guardrails.SensitiveActionPolicy
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
     * 执行支持流式事件发射的工具调用。
     *
     * 默认实现直接调用 [execute(args)]，忽略流式回调。需要流式输出能力的工具
     * （如 [run_command]）可重写此方法，在执行过程中通过 [onStream] 发射
     * [AgentStreamEvent.CommandOutputStream] 等事件。
     *
     * @param args AI 模型传入的参数
     * @param onStream 流式事件发射回调；实现方应保证不抛异常，避免中断工具执行
     * @return 工具执行结果
     */
    suspend fun execute(args: JsonObject, onStream: suspend (AgentStreamEvent) -> Unit): ToolResult {
        return execute(args)
    }

    /**
     * 工具名称（从 tool 中派生，便于快速访问）
     */
    val name: String get() = tool.name

    /**
     * 工具风险等级（用于 ToolGuardrails 决策，替代硬编码 KNOWN_SAFE_TOOLS 白名单）。
     *
     * 默认 SAFE：仅读取 / 分析类工具的合理默认，新工具保持默认即可通过 guardrails。
     * 危险工具（写文件、执行 shell、提交代码、调用外部服务等）必须显式 override 为 DANGEROUS。
     *
     * ToolGuardrails 在 else 分支会先查 handler.riskLevel，再决定 ALLOWED / REQUIRES_CONFIRMATION。
     */
    val riskLevel: SensitiveActionPolicy.RiskLevel get() = SensitiveActionPolicy.RiskLevel.SAFE
}

/**
* 将现有的非 suspend 工具方法适配为 ToolHandler
*/
abstract class SyncToolHandler(
    override val tool: Tool,
    override val riskLevel: SensitiveActionPolicy.RiskLevel = SensitiveActionPolicy.RiskLevel.SAFE
) : ToolHandler {
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
    override val riskLevel: SensitiveActionPolicy.RiskLevel = SensitiveActionPolicy.RiskLevel.SAFE,
    private val executor: (JsonObject) -> ToolResult
) : ToolHandler {
    override suspend fun execute(args: JsonObject): ToolResult = executor(args)
}

/**
 * 函数式工具处理器（支持流式回调）。
 *
 * 当工具被 [ToolExecutor] 以流式方式调用时，会走 [executorStreaming]；
 * 非流式调用或 handler 未提供流式闭包时，回退到普通 [executor]。
 */
class StreamingFunctionalToolHandler(
    override val tool: Tool,
    override val riskLevel: SensitiveActionPolicy.RiskLevel = SensitiveActionPolicy.RiskLevel.SAFE,
    private val executor: (JsonObject) -> ToolResult,
    private val executorStreaming: suspend (JsonObject, suspend (AgentStreamEvent) -> Unit) -> ToolResult
) : ToolHandler {
    override suspend fun execute(args: JsonObject): ToolResult = executor(args)

    override suspend fun execute(
        args: JsonObject,
        onStream: suspend (AgentStreamEvent) -> Unit
    ): ToolResult = executorStreaming(args, onStream)
}
