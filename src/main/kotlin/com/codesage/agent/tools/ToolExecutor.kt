package com.codesage.agent.tools

import com.codesage.analysis.CodeInsightExecutor
import com.codesage.model.dto.ToolCall
import com.codesage.shared.utils.Logger
import com.codesage.tools.guardrails.*
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 工具执行器
 * 负责根据 AI 返回的 ToolCall 调用对应的 IDE 工具
 * 集成 Guardrails（权限控制、输出截断）
 */
class ToolExecutor(
    private val project: Project?,
    private val guardrails: ToolGuardrails? = null,
    private val rateLimiter: ToolRateLimiter? = null,
    private val auditLog: ToolAuditLog? = null
) {
    private val logger = Logger.getLogger<ToolExecutor>()
    private val json = Json { ignoreUnknownKeys = true }

    private val ideTools = IDETools(project)
    private val extendedTools = ExtendedTools(project)
    private val codeInsightExecutor = CodeInsightExecutor(project)

    /**
     * 执行单个工具调用
     * @return 工具执行结果的 JSON 字符串
     */
    suspend fun execute(toolCall: ToolCall): String {
        logger.info("Executing tool: ${toolCall.name} with args: ${toolCall.arguments}")
        val startTime = System.currentTimeMillis()

        // 1. 频率限制检查
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

            // 2. Guardrails 前置检查
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

                    else -> {}
                }
            }

            val result = when (toolCall.name) {
                "read_file" -> ideTools.readFile(args)
                "write_file" -> ideTools.writeFile(args)
                "list_directory" -> ideTools.listDirectory(args)
                "search_code" -> ideTools.searchCode(args)
                "run_command" -> ideTools.runCommand(args)
                "get_project_structure" -> ideTools.getProjectStructure(args)
                "find_file" -> ideTools.findFile(args)
                "grep_code" -> ideTools.grepCode(args)
                "get_file_info" -> ideTools.getFileInfo(args)
                "read_multiple_files" -> ideTools.readMultipleFiles(args)
                "edit_file" -> ideTools.editFile(args)
                "delete_file" -> ideTools.deleteFile(args)
                "copy_file" -> ideTools.copyFile(args)
                "move_file" -> ideTools.moveFile(args)
                // Git 工具
                "git_status" -> extendedTools.gitStatus(args)
                "git_diff" -> extendedTools.gitDiff(args)
                "git_log" -> extendedTools.gitLog(args)
                "git_branch" -> extendedTools.gitBranch(args)
                // Shell / HTTP / 数据处理工具
                "exec_shell" -> extendedTools.execShell(args)
                "http_request" -> extendedTools.httpRequest(args)
                "parse_json" -> extendedTools.parseJson(args)
                "encode_base64" -> extendedTools.encodeBase64(args)
                "decode_base64" -> extendedTools.decodeBase64(args)
                "format_json" -> extendedTools.formatJson(args)
                "hash_md5" -> extendedTools.hashMd5(args)
                "hash_sha256" -> extendedTools.hashSha256(args)
                // 代码洞察工具（真实 PSI 实现）
                "analyze_symbol" -> codeInsightExecutor.analyzeSymbol(args)
                "find_usages" -> codeInsightExecutor.findUsages(args)
                "get_inheritance_chain" -> codeInsightExecutor.getInheritanceChain(args)
                "semantic_search" -> codeInsightExecutor.semanticSearch(args)
                "get_file_summary" -> codeInsightExecutor.getFileSummary(args)
                "get_project_stats" -> codeInsightExecutor.getProjectStats(args)
                else -> ToolResult.Error("Unknown tool: ${toolCall.name}")
            }

            // 3. Guardrails 后置处理（截断）
            val processedResult = guardrails?.postProcess(toolCall.name, result) ?: result
            val formatted = formatResult(processedResult)
            val duration = System.currentTimeMillis() - startTime

            // 4. 记录审计日志
            val wasTruncated = processedResult is ToolResult.Success &&
                    result is ToolResult.Success &&
                    processedResult.data.toString().length < result.data.toString().length
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

            // 5. 频率限制成功重置（仅业务成功时重置）
            if (result is ToolResult.Success) {
                rateLimiter?.recordSuccess(toolCall.name)
            }

            logger.info("[ToolExecutor] ${toolCall.name} completed, result length=${formatted.length}, duration=${duration}ms")
            formatted
        } catch (e: ToolExecutionBlocked) {
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
            formatResult(ToolResult.Error("Execution failed: ${e.message}"))
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

    private fun formatResult(result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> {
                json.encodeToString(
                    JsonObject.serializer(), JsonObject(
                        mapOf(
                            "success" to JsonPrimitive(true),
                            "data" to result.data
                        )
                    )
                )
            }

            is ToolResult.Error -> {
                json.encodeToString(
                    JsonObject.serializer(), JsonObject(
                        mapOf(
                            "success" to JsonPrimitive(false),
                            "error" to JsonPrimitive(result.message)
                        )
                    )
                )
            }
        }
    }
}

/**
 * 工具执行结果
 */
sealed class ToolResult {
    data class Success(val data: JsonElement) : ToolResult()
    data class Error(val message: String) : ToolResult()
}
