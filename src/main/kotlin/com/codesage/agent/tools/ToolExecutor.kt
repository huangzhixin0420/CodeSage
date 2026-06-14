package com.codesage.agent.tools

import com.codesage.analysis.CodeInsightExecutor
import com.codesage.model.dto.ToolCall
import com.codesage.observability.ExecutionTracer
import com.codesage.shared.utils.Logger
import com.codesage.tools.guardrails.*
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * 工具执行器
 * 负责根据 AI 返回的 ToolCall 调用对应的 IDE 工具
 * 集成 Guardrails（权限控制、输出截断）
 *
 * 执行路由优先级：
 * 1. ToolRegistry 中注册的 ToolHandler（推荐，支持动态扩展）
 * 2. 硬编码 fallback 路由（遗留代码洞察工具）
 */
class ToolExecutor(
    private val project: Project?,
    private val guardrails: ToolGuardrails? = null,
    private val rateLimiter: ToolRateLimiter? = null,
    private val auditLog: ToolAuditLog? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val tracer: ExecutionTracer? = null,
    private val traceContext: ExecutionTracer.TraceContext? = null,
    // 6.12.2：上下文预算管理器，用于在工具结果中嵌入 token 预算提示
    private val contextBudgetManager: com.codesage.agent.context.ContextBudgetManager? = null
) {
    private val logger = Logger.getLogger<ToolExecutor>()
    private val json = Json { ignoreUnknownKeys = true }

    // 遗留工具实例（仅用于 fallback）
    private val ideTools = IDETools(project, auditLog)
    private val extendedTools = ExtendedTools(project)
    private val codeInsightExecutor = CodeInsightExecutor(project)

    /**
     * 执行单个工具调用（带重试，非流式）。
     */
    suspend fun execute(toolCall: ToolCall): String = execute(toolCall) { }

    private val noOpStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit = { }

    /**
     * 执行单个工具调用（带重试），支持流式事件回调。
     *
     * @param onStream 若工具支持流式执行，会通过该回调发送中间事件；
     *                 回调接收的事件会被注入当前 [toolCall.id]。
     */
    suspend fun execute(
        toolCall: ToolCall,
        onStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit = noOpStream
    ): String {
        logger.info("Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")
        val startTime = System.currentTimeMillis()

        // T7.2：开始 child span
        val spanId = if (tracer != null && traceContext != null) {
            tracer.addSpan(
                traceId = traceContext.traceId,
                parentSpanId = traceContext.currentSpanId,
                name = "tool.${toolCall.name}",
                attributes = mapOf(
                    "tool.id" to toolCall.id,
                    "tool.name" to toolCall.name,
                    "args.size" to toolCall.arguments.length.toString()
                )
            )
        } else null

        // 1. 频率限制检查（不重试）
        val rateLimitResult = rateLimiter?.check(toolCall.name)
        if (rateLimitResult != null && !rateLimitResult.allowed) {
            val message = rateLimitResult.warning ?: "Tool '${toolCall.name}' blocked by rate limiter"
            logger.warn("[ToolExecutor] $message")
            auditLog?.log(
                toolName = toolCall.name,
                arguments = parseArguments(toolCall.arguments),
                resultStatus = "blocked",
                durationMs = System.currentTimeMillis() - startTime,
                rateLimitWarning = rateLimitResult.warning
            )
            throw ToolExecutionBlocked(message, toolCall.name, ToolExecutionBlocked.BlockReason.RATE_LIMIT)
        }

        return try {
            val args = parseArguments(toolCall.arguments)

            // 2. Guardrails 前置检查（不重试）
            guardrails?.let { g ->
                when (val preCheck = g.preCheck(toolCall.name, args, toolCall.id)) {
                    is ToolGuardrails.PreCheckResult.Denied -> {
                        val reason = preCheck.reason
                        auditLog?.log(
                            toolName = toolCall.name,
                            arguments = args,
                            resultStatus = "denied",
                            durationMs = System.currentTimeMillis() - startTime,
                            confirmationStatus = "denied: $reason"
                        )
                        throw ToolExecutionBlocked(
                            "Guardrails denied: $reason",
                            toolCall.name,
                            if (reason.contains("declined")) ToolExecutionBlocked.BlockReason.CONFIRMATION_DENIED
                            else ToolExecutionBlocked.BlockReason.POLICY_VIOLATION
                        )
                    }

                    is ToolGuardrails.PreCheckResult.Allowed -> { /* 正常执行 */
                    }
                }
            }

            // 3. 执行工具（带重试）
            val result = executeToolWithRetry(toolCall, args, onStream)

            // 4. 归一化工具层截断元数据并附着到结果（6.12.1）
            val rawMetadata = ToolResultTruncationNormalizer.extract(toolCall.name, result, args)
            val resultWithMetadata = if (result is ToolResult.Success && rawMetadata != ToolResultMetadata.EMPTY) {
                result.copy(metadata = rawMetadata)
            } else result

            // 5. Guardrails 后置处理（截断）
            val processedResult = guardrails?.postProcess(toolCall.name, resultWithMetadata) ?: resultWithMetadata

            // 6. 计算 token 预算提示并合并到元数据（6.12.2）
            val finalMetadata = computeFinalMetadata(processedResult)

            val formatted = formatResult(processedResult, finalMetadata)
            val duration = System.currentTimeMillis() - startTime

            // 7. 记录审计日志
            val wasTruncated = (processedResult is ToolResult.Success && processedResult.metadata?.truncated == true) ||
                    (processedResult is ToolResult.Success && result is ToolResult.Success &&
                            processedResult.data.toString().length < result.data.toString().length)
            auditLog?.log(
                toolName = toolCall.name,
                arguments = args,
                resultStatus = if (result is ToolResult.Success) "success" else "error",
                durationMs = duration,
                truncated = wasTruncated,
                originalLength = if (result is ToolResult.Success) result.data.toString().length else null,
                truncatedLength = if (processedResult is ToolResult.Success) processedResult.data.toString().length else null,
                rateLimitWarning = rateLimitResult?.warning
            )

            // 6. 频率限制成功重置（仅业务成功时重置）
            if (result is ToolResult.Success) {
                rateLimiter?.recordSuccess(toolCall.name)
            }

            // T7.2：结束 span (success)
            if (spanId != null && traceContext != null) {
                tracer?.endSpan(
                    traceId = traceContext.traceId,
                    spanId = spanId,
                    status = ExecutionTracer.TraceStatus.OK
                )
                tracer?.addEvent(
                    traceId = traceContext.traceId,
                    spanId = spanId,
                    eventName = "tool.completed",
                    attributes = mapOf("duration_ms" to duration.toString())
                )
            }

            logger.info("[ToolExecutor] ${toolCall.name} completed, result length=${formatted.length}, duration=${duration}ms")
            formatted
        } catch (e: ToolExecutionBlocked) {
            // T7.2：结束 span (cancelled)
            if (spanId != null && traceContext != null) {
                tracer?.endSpan(
                    traceId = traceContext.traceId,
                    spanId = spanId,
                    status = ExecutionTracer.TraceStatus.CANCELLED
                )
            }
            throw e
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error(
                "[ToolExecutor] Tool execution FAILED: ${toolCall.name}, error=${e.javaClass.name}: ${e.message}",
                e
            )
            auditLog?.log(
                toolName = toolCall.name,
                arguments = parseArguments(toolCall.arguments),
                resultStatus = "error",
                durationMs = duration
            )
            // T7.2：结束 span (error)
            if (spanId != null && traceContext != null) {
                tracer?.endSpan(
                    traceId = traceContext.traceId,
                    spanId = spanId,
                    status = ExecutionTracer.TraceStatus.ERROR
                )
            }
            formatResult(ToolResult.Error("Execution failed: ${e.message}"))
        }
    }

    /**
     * 执行工具（带重试机制）
     * 仅对瞬时错误（IO异常、超时、进程锁等）进行重试，永久性错误（文件不存在、未知工具等）不重试
     */
    private suspend fun executeToolWithRetry(
        toolCall: ToolCall,
        args: JsonObject,
        onStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit?
    ): ToolResult {
        val maxRetries = 2
        val baseDelayMs = 500L

        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return executeToolOnce(toolCall, args, onStream)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries && isRetryableError(e)) {
                    val delayMs = baseDelayMs * (attempt + 1)
                    logger.warn("[ToolExecutor] Tool ${toolCall.name} failed on attempt ${attempt + 1}, retrying after ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                } else {
                    break
                }
            }
        }
        // H10 修复：防御性检查 lastException 不为 null
        if (lastException == null) {
            // 理论上只有 maxRetries=0 且 first call 异常时才可能（已 break），
            // 但保险起见加一个分支避免静默返回"Unknown error"。
            logger.warn("[ToolExecutor] retry loop exited with no exception captured")
            return ToolResult.Error("Execution failed after ${maxRetries + 1} attempts: no error captured")
        }
        val errorMsg = lastException.message ?: "Unknown error"
        return ToolResult.Error("Execution failed after ${maxRetries + 1} attempts: $errorMsg")
    }

    /**
     * 单次工具执行（无重试）
     *
     * T6.1 修复：移除硬编码 when 路由。所有工具都应该通过 ToolRegistry 注册为 handler。
     * 如果遇到未注册的工具名，返回错误（不再走 fallback to 硬编码路径）。
     */
    private suspend fun executeToolOnce(
        toolCall: ToolCall,
        args: JsonObject,
        onStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit?
    ): ToolResult {
        val handler = toolRegistry?.getHandler(toolCall.name)
            ?: return ToolResult.Error("Unknown tool: ${toolCall.name}. Tool must be registered via ToolRegistry.register() before use.")

        logger.debug("[ToolExecutor] Routing '${toolCall.name}' to ToolHandler")
        return if (onStream != null) {
            handler.execute(args) { event ->
                // 为流式事件注入当前 toolCall.id，保证 UI 正确关联
                val injected = when (event) {
                    is com.codesage.agent.core.AgentStreamEvent.CommandOutputStream ->
                        if (event.toolCallId.isEmpty()) event.copy(toolCallId = toolCall.id) else event

                    else -> event
                }
                onStream(injected)
            }
        } else {
            handler.execute(args)
        }
    }

    /**
     * 判断错误是否可重试
     *
     * H10 修复：
     * - 显式排除 [java.nio.file.AccessDeniedException]（永久错误，重试无意义）
     * - 显式排除 [java.nio.file.NoSuchFileException] / NotDirectoryException（永久错误）
     * - IOException 子类中区分"瞬时"vs"永久"，避免权限错误浪费 3x 延迟
     */
    private fun isRetryableError(error: Throwable): Boolean {
        return when (error) {
            is TimeoutException -> true
            is java.nio.file.AccessDeniedException -> false
            is java.nio.file.NoSuchFileException -> false
            is java.nio.file.NotDirectoryException -> false
            is java.nio.file.FileAlreadyExistsException -> false
            is java.io.FileNotFoundException -> false
            is IOException -> {
                // 其它 IOException（SocketException、ConnectException、UnknownHostException 等）
                // 视为瞬时错误，可重试
                true
            }

            else -> {
                val msg = error.message?.lowercase() ?: ""
                // Git 索引锁、文件被占用等临时错误
                msg.contains("unable to create") ||
                        msg.contains("index.lock") ||
                        msg.contains("resource busy") ||
                        msg.contains("device or resource busy") ||
                        msg.contains("temporary") ||
                        msg.contains("try again")
            }
        }
    }

    private fun parseArguments(arguments: String): JsonObject {
        return try {
            json.parseToJsonElement(arguments).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun parseArguments(arguments: JsonObject): Map<String, Any> {
        return arguments.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        }
    }

    /**
     * 合并 guardrails 层可能产生的截断元数据与 token 预算提示，生成最终元数据。
     *
     * 6.12.2：context 预算提示始终追加（预算管理器可用时），让模型感知单次工具调用的
     * 上下文消耗。
     */
    private fun computeFinalMetadata(result: ToolResult): ToolResultMetadata {
        val base = if (result is ToolResult.Success) result.metadata else null
        val content = when (result) {
            is ToolResult.Success -> result.data.toString()
            is ToolResult.Error -> result.message
        }
        val costEstimate = ToolResultBudgetHints.estimateTokens(content)
        val remainingHint = ToolResultBudgetHints.remainingHint(contextBudgetManager, costEstimate)

        return if (base == null || base.isEmpty()) {
            ToolResultMetadata(
                contextCostEstimate = costEstimate,
                remainingContextHint = remainingHint
            )
        } else {
            base.copy(
                contextCostEstimate = costEstimate,
                remainingContextHint = remainingHint
            )
        }
    }

    private fun formatResult(result: ToolResult): String = formatResult(result, computeFinalMetadata(result))

    /**
     * 将工具结果序列化为 JSON，并追加统一截断元数据与 token 预算提示。
     *
     * 6.12.1 / 6.12.2：成功结果除了 `{success, data}` 之外，还会在顶层输出
     * `{truncated, total_items, returned_items, next_offset, hint,
     *  context_cost_estimate, remaining_context_hint}` 中的非空字段。
     */
    private fun formatResult(result: ToolResult, metadata: ToolResultMetadata): String {
        return when (result) {
            is ToolResult.Success -> {
                val fields = mutableMapOf<String, JsonElement>(
                    "success" to JsonPrimitive(true),
                    "data" to result.data
                )
                appendMetadata(fields, metadata)
                json.encodeToString(JsonObject.serializer(), JsonObject(fields))
            }

            is ToolResult.Error -> {
                val fields = mutableMapOf<String, JsonElement>(
                    "success" to JsonPrimitive(false),
                    "error" to JsonPrimitive(result.message)
                )
                appendMetadata(fields, metadata)
                json.encodeToString(JsonObject.serializer(), JsonObject(fields))
            }
        }
    }

    private fun appendMetadata(fields: MutableMap<String, JsonElement>, metadata: ToolResultMetadata) {
        if (metadata.truncated) fields["truncated"] = JsonPrimitive(true)
        metadata.totalItems?.let { fields["total_items"] = JsonPrimitive(it) }
        metadata.returnedItems?.let { fields["returned_items"] = JsonPrimitive(it) }
        metadata.nextOffset?.let { fields["next_offset"] = JsonPrimitive(it) }
        metadata.hint?.let { fields["hint"] = JsonPrimitive(it) }
        metadata.contextCostEstimate?.let { fields["context_cost_estimate"] = JsonPrimitive(it) }
        metadata.remainingContextHint?.let { fields["remaining_context_hint"] = JsonPrimitive(it) }
    }
}

/**
 * 工具执行结果
 *
 * 6.12.1 / 6.12.2：`Success` 新增可选 [metadata]，用于在 `ToolExecutor` 层统一携带
 * 截断协议与 token 预算提示，同时保持所有现有 `ToolResult.Success(data)` 调用的向后兼容。
 */
sealed class ToolResult {
    data class Success(
        val data: JsonElement,
        val metadata: ToolResultMetadata? = null
    ) : ToolResult()

    data class Error(val message: String) : ToolResult()
}
