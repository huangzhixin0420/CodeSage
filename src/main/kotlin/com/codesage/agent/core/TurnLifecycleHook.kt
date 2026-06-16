package com.codesage.agent.core

import com.codesage.model.dto.Message

/**
 * 2026-06: Agent turn 生命周期钩子接口(OOP 策略模式)。
 *
 * 用于拆分 EnhancedAgentLoop 内的"业务编排事件"处理逻辑(SubAgentStart / PlanStep /
 * ContextCompressed / ModeSuggestion / SessionMigrated 等 15+ 类),
 * 让 loop 主函数退化成"组装者",业务逻辑可独立测试 / 运行时替换。
 *
 * 与 Reducer 的分工(EXEC §2.6):
 *   - Reducer: 处理模型流事件(~12 case, 高频, 固定) — 编译期穷举
 *   - Hook: 处理业务编排事件(15+ 类, 低频, 可变) — 可插拔 OOP
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.6
 */
interface TurnLifecycleHook {
    /**
     * 每轮对话开始时调用。返回要额外 emit 的事件列表。
     */
    fun onTurnStart(turnNumber: Int, state: TurnState): List<AgentStreamEvent> = emptyList()

    /**
     * 每轮对话结束时调用(流结束后)。
     */
    fun onTurnEnd(turnNumber: Int, state: TurnState, message: Message?): List<AgentStreamEvent> = emptyList()

    /**
     * 工具调用执行后调用(典型: SubAgentDispatch / ToolConfirmation)。
     */
    fun onToolExecuted(turnNumber: Int, toolName: String, success: Boolean, state: TurnState): List<AgentStreamEvent> = emptyList()

    /**
     * 错误恢复后调用(典型: ContextCompression / SessionMigration)。
     */
    fun onErrorRecovery(turnNumber: Int, error: Throwable, state: TurnState): List<AgentStreamEvent> = emptyList()

    /**
     * 上下文压缩时调用(典型: ContextCompression 钩子)。
     */
    fun onContextCompression(turnNumber: Int, originalTokens: Int, compressedTokens: Int, state: TurnState): List<AgentStreamEvent> = emptyList()

    /**
     * Session 迁移时调用(典型: SessionMigration 钩子)。
     */
    fun onSessionMigration(turnNumber: Int, oldSessionId: String, newSessionId: String, state: TurnState): List<AgentStreamEvent> = emptyList()
}
