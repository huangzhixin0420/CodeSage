package com.codesage.agent.context

import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.utils.Logger

/**
 * 上下文压缩器（默认 ContextEngine 实现）
 *
 * 参考 Hermes 的 ContextCompressor 设计：
 * - 保护头部（system + first N）和尾部（last N）消息
 * - 压缩中间部分为结构化摘要
 * - 图像清理：旧 screenshot 替换为占位文本
 * - 工具输出截断
 *
 * 当前实现使用规则引擎生成结构化摘要（无需辅助 LLM）。
 * 后续可接入轻量级模型提升摘要质量。
 */
class ContextCompressor(
    private val auxiliaryModel: String? = null, // 预留：轻量级模型名称
    contextLength: Int = ContextEngine.DEFAULT_CONTEXT_LENGTH,
    private val modelGateway: ModelGateway? = null
) : ContextEngine() {

    override val name: String = "context_compressor"
    override var contextLength: Int = contextLength

    private val logger = Logger.getLogger<ContextCompressor>()

    override fun compress(
        messages: List<Message>,
        currentTokens: Int?,
        focusTopic: String?
    ): List<Message> {
        messagesSnapshot = messages
        compressionCount++

        val tokens = currentTokens ?: estimateTokens(messages)
        logger.info("Compressing context: $tokens tokens, compression #$compressionCount")

        // 1. 分离系统消息、头部、中间、尾部
        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val nonSystem = messages.filter { it.role != Role.SYSTEM }

        val head = nonSystem.take(protectFirstN)
        val tail = nonSystem.takeLast(protectLastN)
        val middle = nonSystem.drop(protectFirstN).dropLast(protectLastN)

        if (middle.isEmpty()) {
            logger.info("No middle messages to compress, returning as-is")
            return messages
        }

        // 2. 中间部分清理
        val cleanedMiddle = middle.map { cleanMessage(it) }

        // 3. 生成结构化摘要
        val summary = generateStructuredSummary(cleanedMiddle, focusTopic)
        val summaryMessage = Message.systemMessage(
            "[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below...\n\n$summary"
        )

        // 4. 合并：系统消息 + 摘要 + 头部 + 尾部
        val result = mutableListOf<Message>()
        result.addAll(systemMessages)
        result.add(summaryMessage)
        result.addAll(head)
        result.addAll(tail)

        val newTokens = estimateTokens(result)
        logger.info("Compression complete: $tokens -> $newTokens tokens (saved ${tokens - newTokens})")

        return result
    }

    /**
     * LLM 驱动的摘要生成（suspend 版本，供协程环境使用）
     *
     * 当配置了 modelGateway 和 auxiliaryModel 时，调用轻量级模型生成结构化摘要。
     * LLM 调用失败时降级为规则摘要（compress）。
     */
    suspend fun summarizeWithLLM(
        messages: List<Message>,
        currentTokens: Int? = null,
        focusTopic: String? = null
    ): List<Message> {
        val gateway = modelGateway
        val model = auxiliaryModel

        if (gateway == null || model.isNullOrBlank()) {
            logger.debug("No modelGateway or auxiliaryModel configured, falling back to rule-based compression")
            return compress(messages, currentTokens, focusTopic)
        }

        messagesSnapshot = messages
        compressionCount++

        val tokens = currentTokens ?: estimateTokens(messages)
        logger.info("LLM summarization triggered: $tokens tokens, compression #$compressionCount")

        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val nonSystem = messages.filter { it.role != Role.SYSTEM }

        val head = nonSystem.take(protectFirstN)
        val tail = nonSystem.takeLast(protectLastN)
        val middle = nonSystem.drop(protectFirstN).dropLast(protectLastN)

        if (middle.isEmpty()) {
            logger.info("No middle messages to summarize, returning as-is")
            return messages
        }

        val cleanedMiddle = middle.map { cleanMessage(it) }

        return try {
            val llmSummary = callLLMForSummary(gateway, model, cleanedMiddle)
            val summaryMessage = Message.systemMessage("[CONTEXT SUMMARY]\n\n$llmSummary")

            val result = mutableListOf<Message>()
            result.addAll(systemMessages)
            result.add(summaryMessage)
            result.addAll(head)
            result.addAll(tail)

            val newTokens = estimateTokens(result)
            logger.info("LLM summarization complete: $tokens -> $newTokens tokens (saved ${tokens - newTokens})")
            result
        } catch (e: Exception) {
            logger.error("LLM summarization failed, falling back to rule-based compression", e)
            compress(messages, tokens, focusTopic)
        }
    }

    /**
     * 生成摘要文本（供外部调用，如 HYBRID 策略）
     */
    fun generateSummaryText(messages: List<Message>, focusTopic: String? = null): String {
        return generateStructuredSummary(messages.map { cleanMessage(it) }, focusTopic)
    }

    /**
     * 清理单条消息（图像占位、工具输出截断）
     */
    private fun cleanMessage(message: Message): Message {
        var content = message.content

        // 图像占位清理（简化模式：检测 base64 或 image url）
        if (content.contains("data:image") || content.contains("![image]")) {
            content = content.replace(
                Regex("(data:image/[^;]+;base64,[A-Za-z0-9+/=]+)"),
                "[image removed to save context]"
            )
        }

        // 工具输出截断（如果内容过长）
        if (content.length > 4000 && message.role == Role.TOOL) {
            content = content.take(4000) + "\n...[truncated, original length: ${message.content.length} chars]"
        }

        return if (content != message.content) message.copy(content = content) else message
    }

    /**
     * 调用 LLM 生成结构化摘要
     */
    private suspend fun callLLMForSummary(
        gateway: ModelGateway,
        model: String,
        messages: List<Message>
    ): String {
        val prompt = buildSummaryPrompt(messages)
        val request = ChatRequest(
            model = model,
            messages = listOf(
                Message.systemMessage("You are a context summarization assistant. Summarize the conversation into structured sections."),
                Message.userMessage(prompt)
            ),
            temperature = 0.3,
            maxTokens = 2000,
            stream = false
        )

        val response = gateway.chat(request)
        return response.getOrNull()?.choices?.firstOrNull()?.message?.content
            ?: throw IllegalStateException("LLM returned empty response")
    }

    /**
     * 构建摘要提示词
     */
    private fun buildSummaryPrompt(messages: List<Message>): String {
        val conversation = messages.joinToString("\n\n") { msg ->
            val prefix = when (msg.role) {
                Role.USER -> "User:"
                Role.ASSISTANT -> "Assistant:"
                Role.TOOL -> "Tool:"
                Role.SYSTEM -> "System:"
            }
            "$prefix ${msg.content.take(1000)}"
        }

        return """
            Summarize the following conversation into structured sections:

            $conversation

            Format your response exactly with these sections:
            ## Active Task
            ## Resolved Decisions
            ## Files Modified
            ## Pending Questions
            ## Tool Calls Summary

            Be concise. Preserve all file paths, decisions, and tool call names.
        """.trimIndent()
    }

    /**
     * 生成结构化摘要
     *
     * 使用规则引擎提取关键信息，格式：
     * ## Active Task
     * ## Resolved Decisions
     * ## Pending Questions
     * ## Files Modified
     * ## Tool Calls Summary
     */
    private fun generateStructuredSummary(messages: List<Message>, focusTopic: String?): String {
        val builder = StringBuilder()

        // Active Task：从最近的用户消息和助手回复推断
        val activeTask = extractActiveTask(messages)
        if (activeTask.isNotBlank()) {
            builder.appendLine("## Active Task")
            builder.appendLine(activeTask)
            builder.appendLine()
        }

        // Resolved Decisions：检测 "decided", "chosen", "use X" 等模式
        val decisions = extractDecisions(messages)
        if (decisions.isNotEmpty()) {
            builder.appendLine("## Resolved Decisions")
            decisions.take(5).forEach { builder.appendLine("- $it") }
            builder.appendLine()
        }

        // Files Modified：检测文件路径和 write_file 工具调用
        val files = extractFiles(messages)
        if (files.isNotEmpty()) {
            builder.appendLine("## Files Modified")
            files.take(10).forEach { builder.appendLine("- $it") }
            builder.appendLine()
        }

        // Pending Questions：检测问句
        val questions = extractPendingQuestions(messages)
        if (questions.isNotEmpty()) {
            builder.appendLine("## Pending Questions")
            questions.take(3).forEach { builder.appendLine("- $it") }
            builder.appendLine()
        }

        // Tool Calls Summary
        val toolCalls = extractToolCalls(messages)
        if (toolCalls.isNotEmpty()) {
            builder.appendLine("## Tool Calls Summary")
            toolCalls.take(10).forEach { builder.appendLine("- $it") }
            builder.appendLine()
        }

        // 聚焦主题
        if (focusTopic != null) {
            builder.appendLine("## Focus Topic")
            builder.appendLine(focusTopic)
        }

        // 摘要预算控制
        val summaryText = builder.toString()
        val maxLength = calculateSummaryBudget(messages)
        return if (summaryText.length > maxLength) {
            summaryText.take(maxLength) + "\n...[summary truncated]"
        } else summaryText
    }

    private fun extractActiveTask(messages: List<Message>): String {
        // 从最后一条用户消息提取主要意图
        val lastUserMsg = messages.findLast { it.role == Role.USER }
        return lastUserMsg?.content?.take(200)?.replace("\n", " ") ?: ""
    }

    private fun extractDecisions(messages: List<Message>): List<String> {
        val decisions = mutableListOf<String>()
        val decisionPatterns = listOf(
            Regex("""(?i)(?:decided?|chos(?:e|en)?|opted?|agreed?|settled? on|going with|will use)\s+(.{0,100})""")
        )
        messages.forEach { msg ->
            decisionPatterns.forEach { pattern ->
                pattern.findAll(msg.content).forEach { match ->
                    match.groupValues.getOrNull(1)?.let { decision ->
                        if (decision.length > 5) decisions.add(decision.trim())
                    }
                }
            }
        }
        return decisions.distinct()
    }

    private fun extractFiles(messages: List<Message>): List<String> {
        val files = mutableSetOf<String>()
        val filePattern =
            Regex("""(?:path|file|write_file|read_file)\s*[=:]?\s*["']?([^"'\n]+\.(?:kt|java|py|js|ts|json|xml|yaml|gradle|md))["']?""")
        messages.forEach { msg ->
            filePattern.findAll(msg.content).forEach { match ->
                match.groupValues.getOrNull(1)?.let { files.add(it.trim()) }
            }
        }
        return files.toList()
    }

    private fun extractPendingQuestions(messages: List<Message>): List<String> {
        return messages.filter { it.role == Role.USER }
            .map { it.content }
            .filter { it.trimEnd().endsWith("?") || it.contains("?") }
            .map { it.split("?").first() + "?" }
            .distinct()
    }

    private fun extractToolCalls(messages: List<Message>): List<String> {
        val tools = mutableSetOf<String>()
        messages.forEach { msg ->
            msg.toolCalls?.forEach { toolCall ->
                tools.add("${toolCall.name}(${toolCall.arguments.take(100)})".replace(Regex("\\s+"), " "))
            }
            // 也检测文本中提到的工具调用
            val toolPattern = Regex("""(?i)(?:tool_call|called|executed|invoked)\s+['"`]?(\w+)['"`]?""")
            toolPattern.findAll(msg.content).forEach { match ->
                match.groupValues.getOrNull(1)?.let { tools.add(it) }
            }
        }
        return tools.toList()
    }

    private fun calculateSummaryBudget(messages: List<Message>): Int {
        val compressedTokens = estimateTokens(messages)
        val budget = (compressedTokens * SUMMARY_RATIO).toInt()
        // 至少保留 800 字符，确保摘要有意义
        return minOf(budget.coerceAtLeast(800), MAX_SUMMARY_TOKENS, 4000)
    }
}
