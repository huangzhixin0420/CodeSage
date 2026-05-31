package com.codesage.ide.inline

import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.tools.guardrails.ToolGuardrails

/**
 * Inline Chat 专用安全护栏
 *
 * 限制 Inline Chat 可用的工具集，禁止危险操作。
 * 所有写操作（edit_file / write_file / delete_file 等）均被禁止，
 * 因为 Inline Chat 的正确交互方式是：AI 生成 diff → 用户 Accept/Reject 后统一应用。
 */
class InlineChatGuardrails {

    /** Inline Chat 允许使用的工具白名单（只读 + 搜索） */
    private val allowedTools = setOf(
        "read_file",
        "search_code",
        "grep_code",
        "get_file_info",
        "read_multiple_files"
    )

    /** 完全禁止的工具 */
    private val forbiddenTools = setOf(
        "edit_file",
        "write_file",
        "run_command",
        "delete_file",
        "move_file",
        "copy_file",
        "delegate_task"
    )

    /**
     * 检查工具是否允许在 Inline Chat 中使用
     */
    fun isToolAllowed(toolName: String): Boolean {
        return toolName in allowedTools
    }

    /**
     * 检查工具是否被禁止
     */
    fun isToolForbidden(toolName: String): Boolean {
        return toolName in forbiddenTools
    }

    /**
     * 获取工具被拦截时的错误消息
     */
    fun getForbiddenMessage(toolName: String): String {
        return when (toolName) {
            "edit_file", "write_file" -> "Inline Chat 不支持直接修改文件，请生成代码块由用户确认后应用"
            "run_command" -> "Inline Chat 不支持执行命令"
            "delete_file" -> "Inline Chat 不支持删除文件"
            "move_file", "copy_file" -> "Inline Chat 不支持文件移动/复制"
            "delegate_task" -> "Inline Chat 内不启用子 Agent"
            else -> "该工具在 Inline Chat 中不可用"
        }
    }

    /**
     * 转换为 ToolGuardrails 的确认回调，供 AgentCore 使用。
     * 允许白名单工具直接执行，禁止黑名单工具。
     */
    fun asConfirmationCallback(): ToolGuardrails.ConfirmationCallback {
        return object : ToolGuardrails.ConfirmationCallback {
            override suspend fun requestConfirmation(
                toolName: String,
                operation: String,
                reason: String,
                riskLevel: SensitiveActionPolicy.RiskLevel
            ): ToolGuardrails.Permission {
                return if (isToolAllowed(toolName)) {
                    ToolGuardrails.Permission.ALLOW_ONCE
                } else {
                    ToolGuardrails.Permission.DENY
                }
            }
        }
    }
}
