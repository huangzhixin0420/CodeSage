package com.codesage.agent.multiagent

import com.codesage.agent.planner.Task
import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T4.2 修复：LLM 驱动的角色选择器
 *
 * **目标**：替代原 keyword-based `determineParticipants` 方法。
 *
 * **设计选择**：
 * 1. 接受一个 LLM 调用回调（`LlmInvoker`），让调用方注入自己的 LLM 客户端
 * 2. LLM 返回 JSON `{"roles": ["CODER", "REVIEWER"], "reasoning": "..."}`
 * 3. 解析失败时回退到 keyword 路由（保留可配置回退）
 * 4. 支持 "显式指定" 旁路（bypass 模式，不调 LLM）
 *
 * **典型用法**：
 * ```
 * val selector = AgentRoleSelector(
 *     invoker = { prompt -> myLlmClient.complete(prompt) }
 * )
 * val roles = selector.select(task, available = AgentRole.values().toList())
 * ```
 */
class AgentRoleSelector(
    private val invoker: LlmInvoker? = null,
    private val fallback: KeywordRoleSelector = KeywordRoleSelector(),
    private val explicit: List<AgentRole>? = null
) {
    private val logger = Logger.getLogger<AgentRoleSelector>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 选择参与任务的角色
     *
     * @param task 任务描述
     * @param available 候选角色列表（默认全部）
     * @return 选中的角色列表
     */
    suspend fun select(task: Task, available: List<AgentRole> = AgentRole.values().toList()): List<AgentRole> {
        // 显式旁路
        if (explicit != null) {
            logger.debug("[RoleSelector] Using explicit roles: $explicit")
            return explicit.filter { it in available }
        }

        // LLM 路由
        if (invoker != null) {
            return try {
                val prompt = buildPrompt(task, available)
                val response = invoker.invoke(prompt)
                parseAndValidate(response, available) ?: fallback.select(task, available)
            } catch (e: Exception) {
                logger.warn("[RoleSelector] LLM selection failed: ${e.message}, falling back to keyword")
                fallback.select(task, available)
            }
        }

        // 无 invoker 时直接回退
        return fallback.select(task, available)
    }

    private fun buildPrompt(task: Task, available: List<AgentRole>): String = buildString {
        appendLine("You are a multi-agent task router. Given a user task, decide which agents should collaborate.")
        appendLine()
        appendLine("Available agents:")
        available.forEach { role ->
            appendLine("- ${role.name}: ${role.description()}")
        }
        appendLine()
        appendLine("User task: \"${task.description}\"")
        if (task.goal.isNotBlank()) appendLine("Goal: \"${task.goal}\"")
        appendLine()
        appendLine("Respond with a single JSON object (no markdown fencing):")
        appendLine("""{"roles": ["CODER", "REVIEWER"], "reasoning": "explain why these roles"}""")
    }

    private fun parseAndValidate(response: String, available: List<AgentRole>): List<AgentRole>? {
        // 尝试从响应中提取 JSON（容忍 markdown 包裹）
        val jsonText = extractJson(response) ?: return null
        val obj = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            logger.debug("[RoleSelector] Failed to parse JSON: $jsonText")
            return null
        }
        val rolesArray = obj["roles"] as? JsonArray ?: return null
        val selected = mutableListOf<AgentRole>()
        for (el in rolesArray) {
            val name = el.jsonPrimitive.contentOrNull ?: continue
            val role = try {
                AgentRole.valueOf(name)
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (role in available && role !in selected) {
                selected.add(role)
            }
        }
        if (selected.isEmpty()) return null
        logger.debug("[RoleSelector] LLM selected: $selected (reasoning: ${obj["reasoning"]?.jsonPrimitive?.contentOrNull})")
        return selected
    }

    private fun extractJson(text: String): String? {
        val trimmed = text.trim()
        // 直接就是 JSON
        if (trimmed.startsWith("{")) {
            val end = trimmed.lastIndexOf("}")
            return if (end >= 0) trimmed.substring(0, end + 1) else null
        }
        // 容忍 ```json ... ``` 包裹
        val fenceStart = trimmed.indexOf("```")
        if (fenceStart >= 0) {
            val inner = trimmed.substring(fenceStart).removePrefix("```").removePrefix("json").trimStart()
            val fenceEnd = inner.indexOf("```")
            val content = if (fenceEnd >= 0) inner.substring(0, fenceEnd) else inner
            return content.trim()
        }
        return null
    }
}

/**
 * LLM 调用回调（由调用方注入）
 */
fun interface LlmInvoker {
    suspend fun invoke(prompt: String): String
}

/**
 * 关键词回退（保留 keyword 路由作为 fallback）
 */
class KeywordRoleSelector : RoleSelectorStrategy {
    override fun select(task: Task, available: List<AgentRole>): List<AgentRole> {
        val description = task.description.lowercase()
        val participants = mutableSetOf<AgentRole>()

        if (description.contains("review") || description.contains("审查")) {
            participants.add(AgentRole.REVIEWER)
        }
        if (description.contains("test") || description.contains("测试")) {
            participants.add(AgentRole.TESTER)
        }
        if (description.contains("research") || description.contains("调研") ||
            description.contains("investigate") || description.contains("analyze")
        ) {
            participants.add(AgentRole.RESEARCHER)
        }
        if (description.contains("code") || description.contains("write") ||
            description.contains("implement") || description.contains("refactor") ||
            description.contains("fix") || description.contains("开发") ||
            participants.isEmpty()
        ) {
            participants.add(AgentRole.CODER)
        }
        return participants.filter { it in available }
    }
}

/**
 * 角色选择策略接口
 */
interface RoleSelectorStrategy {
    fun select(task: Task, available: List<AgentRole>): List<AgentRole>
}

/**
 * AgentRole 的可读描述（用于 prompt）
 */
fun AgentRole.description(): String = when (this) {
    AgentRole.PLANNER -> "Decomposes complex tasks into sub-tasks and outputs structured plans"
    AgentRole.CODER -> "Writes clean, working code (generation, refactor, fix)"
    AgentRole.REVIEWER -> "Reviews code for correctness, security, and style"
    AgentRole.TESTER -> "Generates and runs test cases"
    AgentRole.RESEARCHER -> "Gathers information and researches technologies"
}
