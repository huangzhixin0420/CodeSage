package com.codesage.agent.memory

import com.codesage.model.dto.Message
import com.codesage.model.dto.Tool
import com.codesage.shared.utils.Logger

/**
 * 记忆管理器统一编排
 *
 * 管理多个 MemoryProvider 的协调：
 * - 只允许一个外部 provider（防止 schema 膨胀）
 * - 每轮自动聚合所有 provider 的系统提示块
 * - 每轮自动聚合所有 provider 的预取记忆
 * - 每轮结束后同步写入所有 provider
 *
 * 参考 Hermes 的 MemoryManager 设计。
 */
class MemoryManager {

    private val logger = Logger.getLogger<MemoryManager>()
    private val providers = mutableListOf<MemoryProvider>()

    // 默认内置 provider
    private val builtInProvider: BuiltInMemoryProvider = BuiltInMemoryProvider()

    init {
        providers.add(builtInProvider)
    }

    /**
     * 添加记忆提供者
     * 只允许一个外部 provider（防止 schema 膨胀）
     */
    fun addProvider(provider: MemoryProvider) {
        if (provider !is BuiltInMemoryProvider && providers.any { it !is BuiltInMemoryProvider }) {
            logger.warn("Only one external memory provider allowed. Ignoring: ${provider.name}")
            return
        }
        providers.add(provider)
        logger.info("Added memory provider: ${provider.name}. Total providers: ${providers.size}")
    }

    /**
     * 移除记忆提供者
     */
    fun removeProvider(providerName: String) {
        providers.removeAll { it.name == providerName && it !is BuiltInMemoryProvider }
        logger.info("Removed memory provider: $providerName")
    }

    /**
     * 初始化所有 provider
     */
    fun initializeAll(sessionId: String, homeDir: String, platform: String = "intellij") {
        providers.forEach { provider ->
            try {
                provider.initialize(sessionId, homeDir, platform)
            } catch (e: Exception) {
                logger.error("Failed to initialize memory provider: ${provider.name}", e)
            }
        }
    }

    /**
     * 构建聚合的系统提示块
     */
    fun buildSystemPrompt(): String {
        val blocks = providers.mapNotNull { provider ->
            try {
                val block = provider.systemPromptBlock()
                if (block.isNotBlank()) block else null
            } catch (e: Exception) {
                logger.error("Failed to get system prompt from ${provider.name}", e)
                null
            }
        }
        return if (blocks.isEmpty()) "" else blocks.joinToString("\n\n")
    }

    /**
     * 聚合预取所有 provider 的记忆
     */
    fun prefetchAll(query: String, sessionId: String): String {
        val memories = providers.mapNotNull { provider ->
            try {
                val mem = provider.prefetch(query, sessionId)
                if (mem.isNotBlank()) mem else null
            } catch (e: Exception) {
                logger.error("Prefetch failed for ${provider.name}", e)
                null
            }
        }
        return if (memories.isEmpty()) "" else memories.joinToString("\n\n")
    }

    /**
     * 后台预取下一轮记忆
     */
    fun queuePrefetchAll(query: String, sessionId: String) {
        providers.forEach { provider ->
            try {
                provider.queuePrefetch(query, sessionId)
            } catch (e: Exception) {
                logger.error("Queue prefetch failed for ${provider.name}", e)
            }
        }
    }

    /**
     * 同步写入所有 provider
     */
    fun syncAll(userMsg: String, assistantMsg: String, sessionId: String) {
        providers.forEach { provider ->
            try {
                provider.syncTurn(userMsg, assistantMsg, sessionId)
            } catch (e: Exception) {
                logger.error("Sync turn failed for ${provider.name}", e)
            }
        }
    }

    /**
     * 获取所有 provider 的工具 schema
     */
    fun getAllToolSchemas(): List<Tool> {
        return providers.flatMap { provider ->
            try {
                provider.getToolSchemas()
            } catch (e: Exception) {
                logger.error("Get tool schemas failed for ${provider.name}", e)
                emptyList()
            }
        }
    }

    /**
     * 分派工具调用到对应的 provider
     */
    fun handleToolCall(toolName: String, args: Map<String, Any>): String {
        // 工具名前缀匹配 provider（如 builtin_memory_search, external_memory_search）
        for (provider in providers) {
            val schemas = provider.getToolSchemas()
            if (schemas.any { it.name == toolName }) {
                return try {
                    provider.handleToolCall(toolName, args)
                } catch (e: Exception) {
                    logger.error("Tool call failed for ${provider.name}: $toolName", e)
                    "{\"success\":false,\"error\":\"${e.message}\"}"
                }
            }
        }
        return "{\"success\":false,\"error\":\"No provider handles tool: $toolName\"}"
    }

    /**
     * 会话切换时通知所有 provider
     */
    fun onSessionSwitch(newSessionId: String, parentSessionId: String? = null, reset: Boolean = false) {
        providers.forEach { provider ->
            try {
                provider.onSessionSwitch(newSessionId, parentSessionId, reset)
            } catch (e: Exception) {
                logger.error("Session switch failed for ${provider.name}", e)
            }
        }
    }

    /**
     * 会话结束时通知所有 provider
     */
    fun onSessionEnd(messages: List<Message>) {
        providers.forEach { provider ->
            try {
                provider.onSessionEnd(messages)
            } catch (e: Exception) {
                logger.error("Session end failed for ${provider.name}", e)
            }
        }
    }

    /**
     * 关闭所有 provider
     */
    fun shutdownAll() {
        providers.forEach { provider ->
            try {
                provider.shutdown()
            } catch (e: Exception) {
                logger.error("Shutdown failed for ${provider.name}", e)
            }
        }
        providers.clear()
    }

    /**
     * 获取内置 provider（用于直接访问高级功能）
     */
    fun getBuiltInProvider(): BuiltInMemoryProvider = builtInProvider

    /**
     * 获取当前注册的 provider 列表
     */
    fun getProviders(): List<MemoryProvider> = providers.toList()
}
