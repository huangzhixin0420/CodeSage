package com.codesage.tools.guardrails

/**
 * 工具执行被 Guardrails 阻止时抛出的异常
 */
class ToolExecutionBlocked(
    message: String,
    val toolName: String? = null,
    val reason: BlockReason = BlockReason.UNKNOWN
) : Exception(message) {

    enum class BlockReason {
        RATE_LIMIT,         // 频率限制
        CONFIRMATION_DENIED,// 用户拒绝确认
        CONFIRMATION_TIMEOUT,// 确认超时
        POLICY_VIOLATION,   // 策略违规（如危险命令）
        UNKNOWN
    }
}
