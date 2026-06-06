package com.codesage.agent.core

import com.codesage.model.dto.Message
import com.codesage.shared.utils.Logger

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
/**
 * 组合多个钩子（责任链模式）
 *
 * L11 修复：每个 hook 调用独立 try-catch，避免单个 hook 抛异常时
 * 中断主循环或让后续 hook 收不到事件。异常会记录到日志便于定位，
 * 不会影响其它 hook 的执行。
 */
class CompositeAgentHooks(private val hooks: List<AgentHooks>) : AgentHooks {

    private val logger = Logger.getLogger<CompositeAgentHooks>()

    /** 隔离执行单个 hook，捕获所有 Exception 但让 CancellationException 正常传播 */
    private suspend fun runHookSafely(hookName: String, hook: AgentHooks, block: suspend (AgentHooks) -> Unit) {
        try {
            block(hook)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // L11: 不让一个坏 hook 拖垮主循环
            val hookClassName = hook::class.java.simpleName
            logger.warn("[CompositeAgentHooks] hook $hookClassName.$hookName failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override suspend fun onSessionStart(session: AgentSession) {
        hooks.forEach { runHookSafely("onSessionStart", it) { h -> h.onSessionStart(session) } }
    }

    override suspend fun onTurnStart(turnNumber: Int, messages: List<Message>) {
        hooks.forEach { runHookSafely("onTurnStart", it) { h -> h.onTurnStart(turnNumber, messages) } }
    }

    override suspend fun preLLMCall(messages: List<Message>): List<Message> {
        // preLLMCall 是 fold 链路，任一环节抛异常都会中断；这里改成"短路 + 记录异常 + 返回上一步结果"
        var current = messages
        for (hook in hooks) {
            current = try {
                hook.preLLMCall(current)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val hookClassName = hook::class.java.simpleName
                logger.warn("[CompositeAgentHooks] hook $hookClassName.preLLMCall failed: ${e.javaClass.simpleName}: ${e.message}, using upstream messages")
                current
            }
        }
        return current
    }

    override suspend fun postApiRequest(response: Message?, error: Throwable?) {
        hooks.forEach { runHookSafely("postApiRequest", it) { h -> h.postApiRequest(response, error) } }
    }

    override suspend fun preToolExecution(toolName: String, arguments: Map<String, Any>) {
        hooks.forEach { runHookSafely("preToolExecution", it) { h -> h.preToolExecution(toolName, arguments) } }
    }

    override suspend fun postToolExecution(toolName: String, result: String, success: Boolean) {
        hooks.forEach { runHookSafely("postToolExecution", it) { h -> h.postToolExecution(toolName, result, success) } }
    }

    override suspend fun onTurnEnd(turnNumber: Int, assistantMessage: Message) {
        hooks.forEach { runHookSafely("onTurnEnd", it) { h -> h.onTurnEnd(turnNumber, assistantMessage) } }
    }

    override suspend fun onSessionEnd(session: AgentSession, messages: List<Message>) {
        hooks.forEach { runHookSafely("onSessionEnd", it) { h -> h.onSessionEnd(session, messages) } }
    }

    override suspend fun onErrorRecovery(classifiedError: ClassifiedError, action: RecoveryAction) {
        hooks.forEach { runHookSafely("onErrorRecovery", it) { h -> h.onErrorRecovery(classifiedError, action) } }
    }
}
