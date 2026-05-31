package com.codesage.agent.core

import com.codesage.model.dto.Message

/**
 * Agent 生命周期钩子接口
 *
 * 参考 Hermes 的 plugin hook 体系，在关键节点注入自定义逻辑。
 * 可用于：日志记录、指标采集、安全审查、自定义恢复策略等。
 */
interface AgentHooks {

    /**
     * 会话开始时调用
     */
    suspend fun onSessionStart(session: AgentSession) {}

    /**
     * 每轮对话开始前调用
     */
    suspend fun onTurnStart(turnNumber: Int, messages: List<Message>) {}

    /**
     * LLM API 调用前调用
     * @return 可能被修改的请求消息列表
     */
    suspend fun preLLMCall(messages: List<Message>): List<Message> = messages

    /**
     * API 请求完成后调用
     */
    suspend fun postApiRequest(response: Message?, error: Throwable?) {}

    /**
     * 工具调用执行前调用
     */
    suspend fun preToolExecution(toolName: String, arguments: Map<String, Any>) {}

    /**
     * 工具调用执行后调用
     */
    suspend fun postToolExecution(toolName: String, result: String, success: Boolean) {}

    /**
     * 每轮对话结束后调用
     */
    suspend fun onTurnEnd(turnNumber: Int, assistantMessage: Message) {}

    /**
     * 会话结束时调用
     */
    suspend fun onSessionEnd(session: AgentSession, messages: List<Message>) {}

    /**
     * 错误恢复时调用
     */
    suspend fun onErrorRecovery(classifiedError: ClassifiedError, action: RecoveryAction) {}
}

/**
 * 组合多个钩子（责任链模式）
 */
class CompositeAgentHooks(private val hooks: List<AgentHooks>) : AgentHooks {

    override suspend fun onSessionStart(session: AgentSession) {
        hooks.forEach { it.onSessionStart(session) }
    }

    override suspend fun onTurnStart(turnNumber: Int, messages: List<Message>) {
        hooks.forEach { it.onTurnStart(turnNumber, messages) }
    }

    override suspend fun preLLMCall(messages: List<Message>): List<Message> {
        return hooks.fold(messages) { acc, hook -> hook.preLLMCall(acc) }
    }

    override suspend fun postApiRequest(response: Message?, error: Throwable?) {
        hooks.forEach { it.postApiRequest(response, error) }
    }

    override suspend fun preToolExecution(toolName: String, arguments: Map<String, Any>) {
        hooks.forEach { it.preToolExecution(toolName, arguments) }
    }

    override suspend fun postToolExecution(toolName: String, result: String, success: Boolean) {
        hooks.forEach { it.postToolExecution(toolName, result, success) }
    }

    override suspend fun onTurnEnd(turnNumber: Int, assistantMessage: Message) {
        hooks.forEach { it.onTurnEnd(turnNumber, assistantMessage) }
    }

    override suspend fun onSessionEnd(session: AgentSession, messages: List<Message>) {
        hooks.forEach { it.onSessionEnd(session, messages) }
    }

    override suspend fun onErrorRecovery(classifiedError: ClassifiedError, action: RecoveryAction) {
        hooks.forEach { it.onErrorRecovery(classifiedError, action) }
    }
}
