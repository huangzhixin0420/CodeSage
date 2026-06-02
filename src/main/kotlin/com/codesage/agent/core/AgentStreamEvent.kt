package com.codesage.agent.core

import com.codesage.model.dto.ToolCall

/**
 * Agent 流式事件
 * 用于向 UI 传递对话过程中的各类事件
 */
sealed class AgentStreamEvent {
    /**
     * 文本增量（模拟流式输出）
     */
    data class TextDelta(val delta: String) : AgentStreamEvent()

    /**
     * 开始执行工具调用
     */
    data class ToolCallStart(val toolCall: ToolCall) : AgentStreamEvent()

    /**
     * 工具调用执行中的增量输出（用于长时间运行的工具，如命令执行）
     */
    data class ToolCallDelta(
        val toolCallId: String,
        val toolName: String,
        val delta: String
    ) : AgentStreamEvent()

    /**
     * 工具调用执行完成
     */
    data class ToolCallResult(
        val toolCallId: String,
        val toolName: String,
        val result: String,
        val success: Boolean
    ) : AgentStreamEvent()

    /**
     * 工具调用执行出错（流式解析或执行阶段）
     */
    data class ToolCallError(
        val toolCallId: String,
        val error: String
    ) : AgentStreamEvent()

    /**
     * 需要用户确认才能执行的工具调用
     */
    data class ToolConfirmationNeeded(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val reason: String
    ) : AgentStreamEvent()

    /**
     * 思考中/状态更新
     */
    data class Thinking(val message: String) : AgentStreamEvent()

    /**
     * 发生错误
     */
    data class Error(val message: String) : AgentStreamEvent()

    /**
     * 子 Agent 开始执行
     */
    data class SubAgentStart(
        val sessionId: String,
        val taskDescription: String,
        val toolset: String
    ) : AgentStreamEvent()

    /**
     * 子 Agent 进度更新
     */
    data class SubAgentProgress(
        val sessionId: String,
        val message: String
    ) : AgentStreamEvent()

    /**
     * 子 Agent 执行完成
     */
    data class SubAgentComplete(
        val sessionId: String,
        val success: Boolean,
        val output: String
    ) : AgentStreamEvent()

    /**
     * 预算状态更新（用于 UI 实时展示）
     */
    data class BudgetStatus(
        val status: String, // OK / WARNING / CRITICAL / EXHAUSTED
        val remainingIterations: Int,
        val remainingTokens: Int,
        val remainingSeconds: Int,
        val usagePercent: Int
    ) : AgentStreamEvent()

    /**
     * 预算耗尽（非错误，是可控暂停）
     */
    data class BudgetExhausted(
        val reason: String,
        val consumedIterations: Int,
        val consumedTokens: Int,
        val elapsedSeconds: Int,
        val allowContinue: Boolean
    ) : AgentStreamEvent()

    /**
     * 用户追加预算后恢复执行
     */
    data class BudgetExtended(
        val extraIterations: Int,
        val newRemainingIterations: Int
    ) : AgentStreamEvent()

    /**
     * 计划步骤定义
     */
    data class PlanStep(
        val id: String,
        val description: String,
        val dependsOn: List<String> = emptyList()
    )

    /**
     * 计划已生成（用于任务规划场景）
     */
    data class PlanGenerated(
        val planId: String,
        val description: String,
        val steps: List<PlanStep>
    ) : AgentStreamEvent()

    /**
     * 计划已批准
     */
    data class PlanApproved(val planId: String) : AgentStreamEvent()

    /**
     * 计划已修改
     */
    data class PlanModified(
        val planId: String,
        val steps: List<PlanStep>
    ) : AgentStreamEvent()

    /**
     * 计划已拒绝
     */
    data class PlanRejected(
        val planId: String,
        val reason: String
    ) : AgentStreamEvent()

    /**
     * 上下文已压缩
     */
    data class ContextCompressed(
        val originalTokens: Int,
        val compressedTokens: Int,
        val strategy: String
    ) : AgentStreamEvent()

    /**
     * T1.5 修复：ChatMode 自动建议
     *
     * 当用户未显式选择 mode 时，backend 通过 `ChatModeRouter.suggestChatMode` 推断出建议值，
     * 并以本事件 emit。UI 收到后可在 UI 上弹出轻提示（如 "已自动切换为编程模式"），
     * 让用户对 mode 选择有知情权。
     */
    data class ModeSuggestion(
        val effective: ChatMode,
        val suggestion: ChatMode,
        val userExplicit: Boolean
    ) : AgentStreamEvent()

    /**
     * 会话已迁移（如从旧会话恢复或切换）
     */
    data class SessionMigrated(
        val oldSessionId: String,
        val newSessionId: String,
        val messageCount: Int
    ) : AgentStreamEvent()

    /**
     * 对话完成
     */
    object Done : AgentStreamEvent()
}
