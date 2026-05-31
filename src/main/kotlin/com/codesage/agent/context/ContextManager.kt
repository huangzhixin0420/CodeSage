package com.codesage.agent.context

import com.codesage.agent.memory.MemoryProvider
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.shared.utils.Logger

/**
 * 上下文截断策略
 */
enum class TruncationStrategy {
    KEEP_RECENT,  // 保留最近的消息
    SUMMARIZE,    // 生成摘要压缩
    RAG检索,      // 向量检索
    HYBRID        // 混合策略
}

/**
 * 上下文管理配置
 */
data class ContextManagementConfig(
    val truncationStrategy: TruncationStrategy = TruncationStrategy.HYBRID,
    val maxHistoryMessages: Int = 50,
    val summarizeThreshold: Int = 30,
    val preserveSystemMessages: Boolean = true,
    val preserveLastNMessages: Int = 3,
    val enableContextEngine: Boolean = true,        // 新增：是否启用 ContextEngine
    val contextLength: Int = ContextEngine.DEFAULT_CONTEXT_LENGTH,
    val auxiliaryModel: String? = null               // 轻量级模型名称（如 moonshot-v1-8k）
)

/**
 * 上下文管理器
 *
 * 负责管理对话历史和上下文截断。
 * Phase 2 升级：集成 ContextEngine，支持 token 预算驱动的智能压缩。
 *
 * **重要：每个对话会话应持有独立的 ContextManager 实例，避免多会话上下文串扰。**
 */
class ContextManager(
    private val config: ContextManagementConfig = ContextManagementConfig(),
    private val memoryProvider: MemoryProvider? = null
) {
    private val logger = Logger.getLogger<ContextManager>()
    private val history = ArrayList<Message>()
    private val historyLock = Any()

    // ContextEngine（可选，用于智能压缩）
    private var contextEngine: ContextEngine? = null

    // 当前会话 ID（用于 RAG 检索）
    private var sessionId: String = ""

    init {
        if (config.enableContextEngine) {
            contextEngine = ContextCompressor(
                auxiliaryModel = config.auxiliaryModel,
                contextLength = config.contextLength
            )
        }
    }

    /**
     * 设置自定义的 ContextEngine
     */
    fun setContextEngine(engine: ContextEngine) {
        contextEngine = engine
    }

    /**
     * 获取当前上下文消息列表
     * 注意：在 maybeTruncate() 执行期间，history 可能被清空并重建。
     * 为保证读取一致性，此处加锁。
     */
    fun getContext(): List<Message> {
        synchronized(historyLock) {
            return history.toList()
        }
    }

    /**
     * 获取系统消息
     */
    fun getSystemMessages(): List<Message> {
        synchronized(historyLock) {
            return history.filter { it.role == Role.SYSTEM }
        }
    }

    /**
     * 获取对话历史
     */
    fun getConversationHistory(): List<Message> {
        synchronized(historyLock) {
            return history.filter { it.role != Role.SYSTEM }
        }
    }

    /**
     * 添加消息
     */
    fun addMessage(message: Message) {
        synchronized(historyLock) {
            history.add(message)
            maybeTruncate()
        }
    }

    /**
     * 添加多条消息
     */
    fun addMessages(messages: List<Message>) {
        synchronized(historyLock) {
            history.addAll(messages)
            maybeTruncate()
        }
    }

    /**
     * 主动压缩上下文（用于错误恢复等场景）
     */
    fun compressContext(): Boolean {
        val engine = contextEngine ?: return false
        val currentTokens = estimateTokens()
        val compressed = engine.compress(history.toList(), currentTokens)
        synchronized(historyLock) {
            history.clear()
            history.addAll(compressed)
        }
        return true
    }

    /**
     * 使用 LLM 压缩上下文（suspend 版本，供协程环境使用）
     */
    suspend fun compressContextWithLLM(): Boolean {
        val engine = contextEngine as? ContextCompressor ?: return compressContext()
        val currentTokens = estimateTokens()
        val compressed = engine.summarizeWithLLM(history.toList(), currentTokens)
        synchronized(historyLock) {
            history.clear()
            history.addAll(compressed)
        }
        return true
    }

    /**
     * 清空历史
     */
    fun clear() {
        synchronized(historyLock) {
            history.clear()
        }
    }

    /**
     * 清空非系统消息
     */
    fun clearHistory() {
        synchronized(historyLock) {
            history.removeAll { it.role != Role.SYSTEM }
        }
    }

    /**
     * 获取消息总数
     */
    fun size(): Int {
        synchronized(historyLock) {
            return history.size
        }
    }

    /**
     * 估算当前上下文的 token 数
     */
    fun estimateTokens(): Int {
        synchronized(historyLock) {
            return TokenEstimator.estimateMessagesTokens(history)
        }
    }

    /**
     * 获取最后一条消息
     */
    fun lastMessage(): Message? {
        synchronized(historyLock) {
            return history.lastOrNull()
        }
    }

    /**
     * 检查是否需要截断/压缩
     */
    private fun maybeTruncate() {
        // 优先使用 ContextEngine 进行 token 预算压缩
        val engine = contextEngine
        if (engine != null && config.enableContextEngine) {
            val currentTokens = estimateTokens()
            if (engine.shouldCompress(currentTokens)) {
                val beforeCount = history.size
                logger.info("Context compression triggered: $currentTokens tokens >= ${engine.thresholdTokens()} threshold, messages=$beforeCount")
                val compressed = engine.compress(history.toList(), currentTokens)
                logger.info("Context compression complete: ${compressed.size} messages (was $beforeCount)")
                history.clear()
                history.addAll(compressed)
                return
            }
        }

        // 后备：基于消息数的简单截断
        val threshold = when (config.truncationStrategy) {
            TruncationStrategy.KEEP_RECENT -> config.maxHistoryMessages
            else -> minOf(config.summarizeThreshold, config.maxHistoryMessages)
        }
        if (history.size > threshold) {
            logger.info("Context truncation triggered: ${history.size} messages > $threshold threshold")
            truncate()
        }
    }

    /**
     * 执行截断
     */
    private fun truncate() {
        when (config.truncationStrategy) {
            TruncationStrategy.KEEP_RECENT -> keepRecent()
            TruncationStrategy.SUMMARIZE -> summarize()
            TruncationStrategy.RAG检索 -> ragRetrieval()
            TruncationStrategy.HYBRID -> hybridTruncate()
        }
    }

    /**
     * 保留最近的消息
     */
    private fun keepRecent() {
        val systemMsgs = history.filter { it.role == Role.SYSTEM }
        val nonSystemCapacity = (config.maxHistoryMessages - systemMsgs.size).coerceAtLeast(1)
        val recentMsgs = history
            .filter { it.role != Role.SYSTEM }
            .takeLast(nonSystemCapacity)

        synchronized(historyLock) {
            val beforeCount = history.size
            history.clear()
            history.addAll(systemMsgs)
            history.addAll(recentMsgs)
            logger.info("keepRecent truncation: $beforeCount -> ${history.size} messages (system=${systemMsgs.size}, recent=${recentMsgs.size})")
        }
    }

    /**
     * 生成摘要（规则驱动，如需 LLM 摘要请在协程中调用 compressContextWithLLM）
     */
    private fun summarize() {
        synchronized(historyLock) {
            val compressed = compressContext()
            if (!compressed) {
                keepRecent()
            }
        }
    }

    /**
     * 向量检索：基于 BuiltInMemoryProvider 的记忆召回
     */
    private fun ragRetrieval() {
        val provider = memoryProvider
        if (provider == null) {
            logger.warn("memoryProvider is null, falling back to keepRecent")
            keepRecent()
            return
        }

        // 取最近一条 USER 消息作为查询
        val lastUserMsg = history.findLast { it.role == Role.USER }?.content ?: ""
        if (lastUserMsg.isBlank()) {
            logger.warn("No user message found for RAG query, falling back to keepRecent")
            keepRecent()
            return
        }

        val ragResult = try {
            provider.prefetch(lastUserMsg, sessionId)
        } catch (e: Exception) {
            logger.error("RAG retrieval failed", e)
            ""
        }

        if (ragResult.isBlank()) {
            logger.info("RAG returned empty result, falling back to keepRecent")
            keepRecent()
            return
        }

        // 保留系统消息 + RAG 结果 + 最近消息
        val systemMsgs = history.filter { it.role == Role.SYSTEM }
        val recentMsgs = history
            .filter { it.role != Role.SYSTEM }
            .takeLast(config.maxHistoryMessages - systemMsgs.size - 1) // 留一个位置给 RAG

        synchronized(historyLock) {
            history.clear()
            history.addAll(systemMsgs)
            history.add(Message.systemMessage("[RELEVANT CONTEXT]\n$ragResult"))
            history.addAll(recentMsgs)
        }

        logger.info("RAG retrieval injected ${ragResult.length} chars of relevant context")
    }

    /**
     * 混合截断策略：保护头尾 + 中间摘要 + RAG 片段注入
     *
     * Token 预算分配：头部 20% : 摘要 40% : RAG 20% : 尾部 20%
     */
    private fun hybridTruncate() {
        val systemMsgs = history.filter { it.role == Role.SYSTEM }
        val nonSystem = history.filter { it.role != Role.SYSTEM }

        val protectFirstN = contextEngine?.protectFirstN ?: ContextEngine.DEFAULT_PROTECT_FIRST_N
        val protectLastN = contextEngine?.protectLastN ?: ContextEngine.DEFAULT_PROTECT_LAST_N

        val head = nonSystem.take(protectFirstN)
        val tail = nonSystem.takeLast(protectLastN)
        val middle = nonSystem.drop(protectFirstN).dropLast(protectLastN)

        if (middle.isEmpty()) {
            // 没有中间消息，执行 keepRecent 逻辑
            keepRecent()
            return
        }

        // Token 预算分配（基于 contextLength）
        val totalBudget = config.contextLength
        val headBudget = (totalBudget * 0.20).toInt()
        val summaryBudget = (totalBudget * 0.40).toInt()
        val ragBudget = (totalBudget * 0.20).toInt()
        val tailBudget = (totalBudget * 0.20).toInt()

        // 1. 生成结构化摘要（规则摘要）
        val compressor = contextEngine as? ContextCompressor
        val summaryContent = compressor?.generateSummaryText(middle)
            ?: generateBasicSummary(middle)

        // 2. RAG 召回
        val lastUserMsg = tail.findLast { it.role == Role.USER }?.content
            ?: head.findLast { it.role == Role.USER }?.content
            ?: ""
        val ragContent = if (memoryProvider != null && lastUserMsg.isNotBlank()) {
            try {
                memoryProvider.prefetch(lastUserMsg, sessionId).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                logger.error("RAG prefetch failed in hybrid mode", e)
                null
            }
        } else null

        // 3. 按预算截断各区域
        val trimmedHead = trimMessagesToBudget(head, headBudget)
        val trimmedTail = trimMessagesToBudget(tail, tailBudget)
        val trimmedSummary = trimSummaryToBudget(summaryContent, summaryBudget)
        val trimmedRag = if (ragContent != null) trimTextToBudget(ragContent, ragBudget) else null

        // 4. 组装最终消息列表
        // 顺序：系统消息 → RAG片段 → 结构化摘要 → 头部 → 尾部
        synchronized(historyLock) {
            history.clear()
            history.addAll(systemMsgs)
            if (trimmedRag != null) {
                history.add(Message.systemMessage("[RELEVANT CONTEXT]\n$trimmedRag"))
            }
            history.add(Message.systemMessage("[CONTEXT SUMMARY]\n$trimmedSummary"))
            history.addAll(trimmedHead)
            history.addAll(trimmedTail)
        }

        val newTokens = estimateTokens()
        logger.info("Hybrid truncation complete: ${history.size} messages, ~$newTokens tokens")
    }

    /**
     * 将消息列表截断到指定 token 预算内
     */
    private fun trimMessagesToBudget(messages: List<Message>, budgetTokens: Int): List<Message> {
        var currentTokens = 0
        val result = mutableListOf<Message>()
        for (msg in messages) {
            val msgTokens = TokenEstimator.estimateMessageTokens(msg)
            if (currentTokens + msgTokens > budgetTokens) break
            result.add(msg)
            currentTokens += msgTokens
        }
        return result
    }

    /**
     * 将摘要文本截断到指定 token 预算内，保留关键区块
     */
    private fun trimSummaryToBudget(summary: String, budgetTokens: Int): String {
        val maxChars = budgetTokens * 3 // 粗略估算
        if (summary.length <= maxChars) return summary

        val lines = summary.lines()
        val essentialSections = setOf("Resolved Decisions", "Tool Calls Summary")
        val essentialLines = mutableListOf<String>()
        val optionalLines = mutableListOf<String>()

        var currentSection = ""
        for (line in lines) {
            if (line.startsWith("## ")) {
                currentSection = line.removePrefix("## ").trim()
            }
            if (currentSection in essentialSections || line.startsWith("## ")) {
                essentialLines.add(line)
            } else {
                optionalLines.add(line)
            }
        }

        val builder = StringBuilder()
        for (line in essentialLines) {
            if (builder.length + line.length > maxChars) break
            builder.appendLine(line)
        }
        for (line in optionalLines) {
            if (builder.length + line.length > maxChars) break
            builder.appendLine(line)
        }
        return builder.toString().trim()
    }

    /**
     * 将普通文本截断到指定 token 预算内
     */
    private fun trimTextToBudget(text: String, budgetTokens: Int): String {
        val maxChars = budgetTokens * 3
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n...[truncated]"
    }

    /**
     * 生成基础摘要（当没有 ContextCompressor 时的降级方案）
     */
    private fun generateBasicSummary(messages: List<Message>): String {
        val builder = StringBuilder()
        builder.appendLine("## Active Task")
        val lastUser = messages.findLast { it.role == Role.USER }
        builder.appendLine(lastUser?.content?.take(200)?.replace("\n", " ") ?: "Unknown")
        builder.appendLine()
        builder.appendLine("## Messages Summary")
        builder.appendLine("${messages.size} messages compacted")
        return builder.toString()
    }

    /**
     * 创建新的会话
     */
    fun newSession(systemMessages: List<Message> = emptyList(), newSessionId: String = "") {
        this.sessionId = newSessionId
        synchronized(historyLock) {
            history.clear()
            systemMessages.forEach { history.add(it) }
        }
    }

    /**
     * 替换系统提示（保留历史，只更新系统消息）
     */
    fun updateSystemPrompt(newPrompt: String) {
        synchronized(historyLock) {
            history.removeAll { it.role == Role.SYSTEM }
            history.add(0, Message.systemMessage(newPrompt))
        }
    }

    /**
     * 注入记忆上下文（在系统提示后插入）
     */
    fun injectMemoryContext(memoryText: String) {
        if (memoryText.isBlank()) return

        synchronized(historyLock) {
            // 移除已有的记忆上下文（避免重复）
            // 支持多种记忆上下文标记格式
            history.removeAll {
                it.role == Role.SYSTEM && isMemoryContextMessage(it.content)
            }

            // 重新计算最后一个系统消息的位置（移除后可能变化）
            val lastSystemIndex = history.indexOfLast { it.role == Role.SYSTEM }
            val insertIndex = if (lastSystemIndex >= 0) lastSystemIndex + 1 else 0

            history.add(insertIndex, Message.systemMessage(memoryText))
        }
    }

    /**
     * 判断是否为记忆相关的系统消息（注入的记忆上下文）
     * 注意：这里只匹配由本类 injectMemoryContext 生成的标记格式，
     * 避免误删用户原始系统提示中恰好包含这些子串的内容。
     */
    private fun isMemoryContextMessage(content: String): Boolean {
        // 必须是系统消息，且以特定标记开头（避免误匹配用户提示中的示例）
        return content.startsWith("<memory-context>") ||
                content.startsWith("[MEMORY SYSTEM]") ||
                content.startsWith("[SYSTEM NOTE:") ||
                content.startsWith("[RELEVANT CONTEXT]") ||
                content.startsWith("[CONTEXT SUMMARY]") ||
                content.startsWith("[CONTEXT COMPACTION")
    }

    companion object {
        @Deprecated(
            "Use per-session instance instead",
            ReplaceWith("ContextManager()")
        )
        fun getInstance(): ContextManager {
            return ContextManager()
        }
    }
}
