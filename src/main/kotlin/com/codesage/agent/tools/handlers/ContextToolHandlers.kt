package com.codesage.agent.tools.handlers

import com.codesage.agent.context.ContextBudgetManager
import com.codesage.agent.tools.FunctionalToolHandler
import com.codesage.agent.tools.ToolHandler
import com.codesage.agent.tools.ToolResult
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 上下文自管理工具 Handler
 */
object ContextToolHandlers {

    fun createGetContextRemainingHandler(budgetManager: ContextBudgetManager): ToolHandler =
        FunctionalToolHandler(getContextRemainingTool()) { _ ->
            val status = budgetManager.getStatus()
            val limits = budgetManager.getRecommendedOutputLimits()

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "tokens_used" to JsonPrimitive(status.tokensUsed),
                        "tokens_left" to JsonPrimitive(status.tokensLeft),
                        "context_length" to JsonPrimitive(status.contextLength),
                        "percent" to JsonPrimitive(status.percentUsed),
                        "threshold_percent" to JsonPrimitive(status.thresholdPercent),
                        "threshold_tokens" to JsonPrimitive(status.thresholdTokens),
                        "recommended_max_output_length" to JsonPrimitive(limits.maxLength),
                        "recommended_max_output_lines" to JsonPrimitive(limits.maxLines)
                    )
                )
            )
        }
}

internal fun getContextRemainingTool() = Tool(
    name = "get_context_remaining",
    description = """
        Summary: 查询当前会话的上下文预算余量（已用 token、剩余 token、使用率、压缩阈值）。
        Args: 无。
        Do: 在准备读取大文件或执行可能产生巨量输出的命令前调用；根据 tokens_left 决定分页/过滤策略。
        Don't: 不要频繁无意义调用；不要拿该工具的返回值替代对工具输出截断提示的阅读。
        Parallel: Yes，与只读查询并行无冲突。
        Cap: 返回 tokens_used / tokens_left / percent / threshold_tokens / recommended_max_output_length / recommended_max_output_lines。
    """.trimIndent(),
    parameters = ToolParameters(
        properties = emptyMap(),
        required = emptyList()
    )
)
