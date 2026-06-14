package com.codesage.agent.memory

import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.*

/**
 * 6.9.2 自动会话摘要与关键事实提取。
 *
 * 优先使用轻量 LLM 异步生成结构化摘要和关键事实；当 LLM 不可用、调用失败或返回无法解析时，
 * 自动降级到零外部依赖的规则引擎，保证任何环境下都能输出摘要。
 *
 * @param modelGateway 模型网关；为 null 时直接使用规则引擎。
 * @param summaryModel 用于生成摘要的轻量模型 ID，默认读取系统属性
 *                     `codesage.memory.summary.model`，否则使用 `MiniMax-M2.1`。
 * @param maxInputChars 输入会话文本的最大字符数，超出时保留最近消息，控制 token 成本。
 * @param maxOutputTokens LLM 摘要的最大输出 token 数。
 * @param enableLlm 是否启用 LLM 摘要；可通过系统属性 `codesage.memory.llmSummary.enabled` 关闭。
 */
class SessionSummarizer(
    private val modelGateway: ModelGateway? = ModelGateway.getInstance(),
    private val summaryModel: String = System.getProperty("codesage.memory.summary.model", "MiniMax-M2.1"),
    private val maxInputChars: Int = DEFAULT_MAX_INPUT_CHARS,
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    private val enableLlm: Boolean = System.getProperty("codesage.memory.llmSummary.enabled", "true").toBoolean()
) {
    private val logger = Logger.getLogger<SessionSummarizer>()

    data class SessionSummary(
        val summary: String,
        val keyFacts: List<String>
    )

    /**
     * 从会话消息中提取摘要与关键事实。
     *
     * 实现策略：
     * 1. 若 [enableLlm] 为 true 且 [modelGateway] 不为 null，先尝试调用轻量 LLM；
     * 2. LLM 失败、超时或返回不可解析时，静默降级到规则引擎；
     * 3. 空会话返回固定摘要。
     */
    suspend fun summarize(messages: List<Message>): SessionSummary {
        if (messages.isEmpty()) {
            return SessionSummary("Empty session.", emptyList())
        }

        if (enableLlm && modelGateway != null) {
            val llmResult = runCatching { summarizeWithLlm(messages) }.getOrNull()
            if (llmResult != null) {
                logger.debug("LLM session summary generated, facts=${llmResult.keyFacts.size}")
                return llmResult
            }
        }

        return ruleBasedSummarize(messages)
    }

    /**
     * 调用轻量 LLM 生成结构化会话摘要。
     *
     * 期望模型返回 JSON：
     * ```json
     * {
     *   "summary": "简短会话摘要",
     *   "key_facts": ["事实1", "事实2"]
     * }
     * ```
     */
    private suspend fun summarizeWithLlm(messages: List<Message>): SessionSummary? {
        val transcript = buildTranscript(messages)
        if (transcript.isBlank()) return null

        val request = ChatRequest(
            model = summaryModel,
            messages = listOf(
                Message.systemMessage(LLM_SYSTEM_PROMPT),
                Message.userMessage(transcript)
            ),
            maxTokens = maxOutputTokens,
            temperature = 0.3,
            stream = false
        )

        val response = modelGateway!!.chat(request).getOrThrow()
        val content = response.choices.firstOrNull()?.message?.content?.trim() ?: return null
        return parseLlmSummary(content)
    }

    /**
     * 解析 LLM 返回的 JSON 摘要。
     *
     * 支持标准 markdown JSON code block 与普通 JSON；若解析失败返回 null，由上层降级。
     */
    private fun parseLlmSummary(content: String): SessionSummary? {
        val jsonText = content
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val obj = Json.parseToJsonElement(jsonText).jsonObject
            val summary = obj["summary"]?.jsonPrimitive?.content?.take(MAX_SUMMARY_LENGTH) ?: return null
            val facts = obj["key_facts"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?.take(MAX_KEY_FACTS)
                ?: emptyList()
            SessionSummary(summary, facts)
        } catch (e: Exception) {
            logger.debug("Failed to parse LLM session summary: ${e.message}")
            null
        }
    }

    /**
     * 将会话消息拼接为带角色前缀的文本，并按 [maxInputChars] 截断以控制成本。
     */
    private fun buildTranscript(messages: List<Message>): String {
        val raw = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        return if (raw.length > maxInputChars) {
            "...\n" + raw.takeLast(maxInputChars)
        } else raw
    }

    /**
     * 规则引擎兜底：从完整会话文本中提取偏好、决策、技术栈、文件路径等关键事实。
     */
    private fun ruleBasedSummarize(messages: List<Message>): SessionSummary {
        val fullText = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val keyFacts = extractKeyFacts(fullText).distinct().take(10)

        val summary = buildString {
            appendLine("Session summary: ${messages.size} messages.")
            val userTopics = messages
                .filter { it.role == Role.USER }
                .takeLast(3)
                .joinToString(" | ") { it.content.take(80).replace("\n", " ") }
            if (userTopics.isNotBlank()) {
                appendLine("Recent topics: $userTopics")
            }
            if (keyFacts.isNotEmpty()) {
                appendLine("Key facts extracted:")
                keyFacts.forEach { appendLine("- $it") }
            }
        }.trimEnd()

        return SessionSummary(summary, keyFacts)
    }

    private fun extractKeyFacts(text: String): List<String> {
        val facts = mutableListOf<String>()

        // 偏好
        listOf(
            Regex(
                "(?i)I (?:prefer|like|want|need|always use|usually|don't like|hate)\\s+(.{0,120})",
                RegexOption.IGNORE_CASE
            ),
            Regex("(?i)(?:使用|喜欢|偏好|习惯|想要|需要)(.{0,80})")
        ).forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val fact = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (fact.length > 3) facts.add("Preference: $fact")
            }
        }

        // 决策 / 架构选择
        listOf(
            Regex(
                """(?i)(?:decided?|chosen?|opted?|will use|going with|agreed to)\\s+(?:to\\s+)?(.{0,150})""",
                RegexOption.IGNORE_CASE
            ),
            Regex("""(?i)(?:决定|选择|采用|使用)(.{0,100})""")
        ).forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val fact = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (fact.length > 5) facts.add("Decision: $fact")
            }
        }

        // 技术栈
        Regex("(?i)(?:Kotlin|Java|Python|React|Vue|Spring|Gradle|Maven|Docker|Kubernetes|Node\\.js|TypeScript|Go|Rust)")
            .findAll(text)
            .map { it.value }
            .distinct()
            .forEach { tech ->
                facts.add("Tech: Project mentions $tech")
            }

        // 文件路径（粗略匹配）
        Regex("""(?:^|[\\s\"'`])([\\w./-]+\\.(?:kt|java|py|js|ts|tsx|jsx|go|rs|md|json|xml|yaml|yml|gradle|kts))""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .forEach { path ->
                facts.add("File: $path")
            }

        return facts
    }

    companion object {
        private const val DEFAULT_MAX_INPUT_CHARS = 6_000
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        private const val MAX_SUMMARY_LENGTH = 2_000
        private const val MAX_KEY_FACTS = 10

        private val LLM_SYSTEM_PROMPT = """
            You are a concise session summarizer for a coding assistant.
            Given the transcript of a conversation, produce a short summary and a list of key facts.
            Output strictly JSON in this format (no markdown, no explanation):
            {"summary":"...","key_facts":["...","..."]}
            Key facts should be atomic, user-preference or project-relevant statements.
        """.trimIndent()
    }
}
