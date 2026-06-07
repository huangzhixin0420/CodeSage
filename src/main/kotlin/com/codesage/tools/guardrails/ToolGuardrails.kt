package com.codesage.tools.guardrails

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.tools.ToolResult
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 工具防护栏
 * 在工具执行前后进行策略检查、输出截断和审计
 */
class ToolGuardrails(
    private val policy: SensitiveActionPolicy = SensitiveActionPolicy,
    private val truncator: OutputTruncator = OutputTruncator(),
    private val projectRoot: String? = null,
    private val confirmationCallback: ConfirmationCallback? = null,
    private val confirmationTimeoutMs: Long = 30_000,
    private val eventEmitter: ((AgentStreamEvent.ToolConfirmationNeeded) -> Unit)? = null
) {
    private val logger = Logger.getLogger<ToolGuardrails>()

    /**
     * C3 修复：已知安全的工具白名单。未在此列表中的工具一律要求用户确认。
     *
     * 包括：只读工具（read_file, search_code, list_directory 等）和分析类工具
     * （symbol_search, parse_json 等），不修改文件、不执行 shell、不访问网络。
     */
    private val KNOWN_SAFE_TOOLS = setOf(
        // 文件系统（只读）
        "read_file", "read_multiple_files", "list_directory",
        "file_exists", "get_file_info", "get_project_structure",
        // 搜索 / 分析
        "search_code", "grep_code", "find_files", "find_files_recursive",
        "symbol_search", "semantic_search",
        // Git（只读）
        "git_status", "git_diff", "git_log", "git_branch", "git_show",
        // HTTP（受 SSRF 防护）
        "http_request",
        // 数据处理
        "parse_json", "format_json", "encode_base64", "decode_base64",
        "hash_md5", "hash_sha256",
        // 知识库
        "memory_search", "memory_get", "skill_list",
        // CodeInsight(只读 AST 分析,2026-06 修复:这些工具是 agent 默认就会调用的项目洞察
        // 入口,被错放进 require-confirmation 分支会让 LLM 第一次想用就 User declined。
        // 全部只读、不写文件、不执行 shell、不访问网络,放行安全)
        "analyze_symbol", "find_usages", "get_inheritance_chain",
        "get_file_summary", "get_project_stats"
        // 注:semantic_search 已在上面 搜索 / 分析 一组里
    )

    /**
     * 用户许可类型
     */
    enum class Permission {
        ALLOW_ONCE,        // 允许这一次
        ALLOW_SESSION,     // 本次对话允许
        ALLOW_PERMANENTLY, // 永久允许
        DENY               // 拒绝
    }

    /**
     * 确认回调接口
     */
    interface ConfirmationCallback {
        suspend fun requestConfirmation(
            toolName: String,
            operation: String,
            reason: String,
            riskLevel: SensitiveActionPolicy.RiskLevel
        ): Permission
    }

    // 权限存储：exact key -> 单次允许；category key -> session/永久允许
    private val oncePermissions = mutableSetOf<String>()
    private val sessionPermissions = mutableSetOf<String>()
    private val permanentPermissions = mutableSetOf<String>()

    /**
     * 执行前检查
     * @param toolCallId 可选的工具调用ID，用于事件发射
     */
    suspend fun preCheck(toolName: String, args: Map<String, Any>, toolCallId: String? = null): PreCheckResult {
        val decision = evaluateToolOperation(toolName, args)

        // 绝对禁止的操作直接拒绝，不进入确认流程
        if (decision.verdict == SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED) {
            return PreCheckResult.Denied(decision.reason)
        }

        // 允许执行，无需确认
        if (decision.verdict == SensitiveActionPolicy.PolicyDecision.Verdict.ALLOWED) {
            return PreCheckResult.Allowed
        }

        // REQUIRES_CONFIRMATION: 进入确认流程

        val exactKey = generateExactKey(toolName, args)
        val categoryKey = generateCategoryKey(toolName, args)

        // 检查已有权限
        if (oncePermissions.remove(exactKey)) {
            return PreCheckResult.Allowed
        }
        if (sessionPermissions.contains(categoryKey) || permanentPermissions.contains(categoryKey)) {
            return PreCheckResult.Allowed
        }

        // 发送 TOOL_CONFIRMATION_NEEDED 事件
        if (toolCallId != null) {
            eventEmitter?.invoke(
                AgentStreamEvent.ToolConfirmationNeeded(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    arguments = args.entries.joinToString(",") { "${it.key}=${it.value}" },
                    reason = decision.reason
                )
            )
        }

        // 请求用户确认（带超时）
        val permission = if (confirmationCallback == null) {
            // Headless / 无 UI 环境下的降级策略：记录日志并拒绝
            // 注意：自动化场景可通过配置调整为 ALLOW_ONCE
            logger.warn("No confirmation callback available for $toolName, operation denied in headless mode")
            Permission.DENY
        } else {
            withTimeoutOrNull(confirmationTimeoutMs) {
                confirmationCallback.requestConfirmation(
                    toolName = toolName,
                    operation = describeOperation(toolName, args),
                    reason = decision.reason,
                    riskLevel = decision.riskLevel
                )
            } ?: Permission.DENY
        }

        return when (permission) {
            Permission.ALLOW_ONCE -> {
                // 仅允许这一次，不存储权限
                PreCheckResult.Allowed
            }

            Permission.ALLOW_SESSION -> {
                sessionPermissions.add(categoryKey)
                PreCheckResult.Allowed
            }

            Permission.ALLOW_PERMANENTLY -> {
                permanentPermissions.add(categoryKey)
                PreCheckResult.Allowed
            }

            Permission.DENY -> PreCheckResult.Denied("User declined: ${decision.reason}")
        }
    }

    /**
     * 清空本次对话的临时权限
     */
    fun clearSessionPermissions() {
        sessionPermissions.clear()
        oncePermissions.clear()
    }

    /**
     * 生成精确操作 key（用于 ALLOW_ONCE）
     */
    private fun generateExactKey(toolName: String, args: Map<String, Any>): String {
        val sortedArgs = args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return "$toolName:$sortedArgs"
    }

    /**
     * 生成分类 key（用于 ALLOW_SESSION / ALLOW_PERMANENTLY）
     */
    private fun generateCategoryKey(toolName: String, args: Map<String, Any>): String {
        return when (toolName) {
            "run_command" -> {
                val command = args["command"]?.toString() ?: ""
                val tokens = command.split(Regex("""[\s;|&`$()<>"'\r\n]+"""))
                val primary = tokens.firstOrNull() ?: command
                "$toolName:$primary"
            }

            "delete_file" -> "$toolName:delete"
            "write_file" -> "$toolName:write"
            "move_file" -> "$toolName:move"
            "edit_file" -> "$toolName:edit"
            else -> "$toolName:$toolName"
        }
    }

    /**
     * 执行后处理（截断等）
     */
    fun postProcess(toolName: String, result: ToolResult): ToolResult {
        return when (result) {
            is ToolResult.Success -> {
                val content = result.data.toString()
                val truncationResult = truncator.truncate(content)

                if (truncationResult.wasTruncated) {
                    logger.info(
                        "Truncated output for $toolName: " +
                                "${truncationResult.originalLines} lines → ${truncationResult.truncatedLines} lines"
                    )
                }

                ToolResult.Success(kotlinx.serialization.json.JsonPrimitive(truncationResult.content) as kotlinx.serialization.json.JsonElement)
            }

            else -> result
        }
    }

    /**
     * 截断工具输出（用于不经过完整guardrails的工具调用）
     */
    fun truncateOutput(content: String, toolName: String? = null): String {
        // 根据工具类型调整截断策略
        val maxLength = when (toolName) {
            "read_file", "read_multiple_files" -> 12000
            "search_code", "grep_code" -> 6000
            "run_command" -> 10000
            "list_directory" -> 4000
            else -> 8000
        }

        val result = truncator.truncate(content, maxLength = maxLength)
        return result.content
    }

    /**
     * 评估工具操作
     */
    private fun evaluateToolOperation(
        toolName: String,
        args: Map<String, Any>
    ): SensitiveActionPolicy.PolicyDecision {
        return when (toolName) {
            "delete_file" -> {
                val path = args["path"]?.toString() ?: return SensitiveActionPolicy.PolicyDecision(
                    verdict = SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED,
                    riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS,
                    reason = "Missing path argument"
                )
                policy.evaluateDelete(path, projectRoot)
            }

            "write_file" -> {
                val path = args["path"]?.toString() ?: return safeDeny("Missing path")
                val content = args["content"]?.toString()
                policy.evaluateWrite(path, projectRoot, content)
            }

            "run_command" -> {
                val command = args["command"]?.toString() ?: return safeDeny("Missing command")
                policy.evaluateCommand(command)
            }

            "exec_shell" -> {
                // exec_shell 与 run_command 语义相同（都是执行 shell 命令），
                // 复用 evaluateCommand 让危险模式（rm -rf、dd、fork 炸弹等）和网络命令
                // （curl/wget/nc）的确认流程与 run_command 保持一致。
                val command = args["command"]?.toString() ?: return safeDeny("Missing command")
                policy.evaluateCommand(command)
            }

            "move_file" -> {
                val source = args["source"]?.toString() ?: return safeDeny("Missing source")
                val dest = args["destination"]?.toString() ?: return safeDeny("Missing destination")
                policy.evaluateMove(source, dest, projectRoot)
            }

            "edit_file" -> {
                val path = args["path"]?.toString() ?: return safeDeny("Missing path")
                // edit_file 视为 write 操作
                policy.evaluateWrite(path, projectRoot)
            }

            else -> {
                // C3 修复：白名单反转。
                // 旧逻辑：未在白名单的工具默认 ALLOWED —— 任何 LLM 注册的自定义工具都直接通过，
                //          可能被 prompt injection 诱导执行恶意工具。
                // 新逻辑：默认 REQUIRES_CONFIRMATION；只有 [KNOWN_SAFE_TOOLS] 中的工具直接放行。
                // 注意：这里只是为了解决 review C3 的白名单反转问题。如果用户在 PluginConfig 里
                // 显式启用了某个工具，建议由 ToolRegistry 在注册时把 allow 状态写到 Tool metadata，
                // Guardrails 直接读 metadata（不与具体工具名硬编码）。
                if (toolName in KNOWN_SAFE_TOOLS) {
                    SensitiveActionPolicy.PolicyDecision(
                        verdict = SensitiveActionPolicy.PolicyDecision.Verdict.ALLOWED,
                        riskLevel = SensitiveActionPolicy.RiskLevel.SAFE,
                        reason = "Known safe tool: $toolName"
                    )
                } else {
                    logger.warn("[ToolGuardrails] Unknown tool name: $toolName, requiring confirmation")
                    SensitiveActionPolicy.PolicyDecision(
                        verdict = SensitiveActionPolicy.PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                        riskLevel = SensitiveActionPolicy.RiskLevel.CAUTION,
                        reason = "Unknown tool \'$toolName\', explicit user confirmation required"
                    )
                }
            }
        }
    }

    private fun safeDeny(reason: String): SensitiveActionPolicy.PolicyDecision {
        return SensitiveActionPolicy.PolicyDecision(
            verdict = SensitiveActionPolicy.PolicyDecision.Verdict.BLOCKED,
            riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS,
            reason = reason
        )
    }

    private fun describeOperation(toolName: String, args: Map<String, Any>): String {
        return when (toolName) {
            "delete_file" -> "Delete file: ${args["path"] ?: "unknown"}"
            "write_file" -> "Write to file: ${args["path"] ?: "unknown"}"
            "run_command", "exec_shell" -> "Execute: ${(args["command"] ?: "").toString().take(60)}"
            "move_file" -> "Move: ${args["source"] ?: "unknown"} → ${args["destination"] ?: "unknown"}"
            "edit_file" -> "Edit file: ${args["path"] ?: "unknown"}"
            else -> "$toolName(${args.keys.joinToString(", ")})"
        }
    }

    /**
     * 执行前检查结果
     */
    sealed class PreCheckResult {
        object Allowed : PreCheckResult()
        data class Denied(val reason: String) : PreCheckResult()
    }
}
