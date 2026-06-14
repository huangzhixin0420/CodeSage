package com.codesage.agent.memory

import com.codesage.model.dto.Message
import com.codesage.model.dto.Role

/**
 * 6.9.2 自动会话摘要与关键事实提取。
 *
 * 当前实现为零外部依赖的规则引擎：
 * - 从完整会话文本中提取偏好、决策、技术栈、文件路径等关键事实。
 * - 生成简短的结构化摘要。
 *
 * 未来可在此接入 LLM 调用（ModelGateway.chat），使用轻量模型获得更高质量摘要。
 */
object SessionSummarizer {

    data class SessionSummary(
        val summary: String,
        val keyFacts: List<String>
    )

    /**
     * 从会话消息中提取摘要与关键事实。
     */
    fun summarize(messages: List<Message>): SessionSummary {
        if (messages.isEmpty()) {
            return SessionSummary("Empty session.", emptyList())
        }

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
        Regex("""(?:^|[\\s\"'`])([\\w\-./]+\\.(?:kt|java|py|js|ts|tsx|jsx|go|rs|md|json|xml|yaml|yml|gradle|kts))""")
            .findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .take(5)
            .forEach { path ->
                facts.add("File: $path")
            }

        return facts
    }
}
