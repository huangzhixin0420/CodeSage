package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 事件路由器 — 将 [AgentStreamEvent] 转换为发给前端的 JSON 消息
 *
 * 设计要点(执行计划 P1.5):
 *  - 用「类型 -> handler」注册表替代 100+ 行 when
 *  - 新增事件只需 1 行:register<NewEvent> { ... }
 *  - turnId 由调用方注入(因为事件本身不带 turnId,turnId 是 UI 层概念)
 *  - 允许 handler 返回 null(表示该事件不发送给前端)
 *
 * 使用方式:
 * ```
 * val router = EventRouter()
 * val msg = router.toMessage(event, turnId = currentTurnId)
 * if (msg != null) sendToJS(msg)
 * ```
 *
 * 字段约定(与前端 chat.js / 设计文档 §7.5 对齐):
 *   - type: 消息类型字符串
 *   - turnId: 当前 turn id
 *   - 其余字段为业务字段
 */
class EventRouter {

    /** Handler 类型别名 */
    private typealias Handler = (AgentStreamEvent, String) -> Map<String, Any?>?

    // 用 internal (not private) 让 inline function 可以引用
    @PublishedApi
    internal val handlers: MutableMap<Class<out AgentStreamEvent>, Handler> = mutableMapOf()

    // 用于跨 Start/Complete 传递 task/toolset/startTime
    private val lastSubAgentTask = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastSubAgentToolset = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastSubAgentStart = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        // === 文本 / 错误 / 状态 ===
        register<AgentStreamEvent.TextDelta> { e, turnId ->
            mapOf("type" to "text_delta", "turnId" to turnId, "delta" to e.delta)
        }
        register<AgentStreamEvent.Error> { e, turnId ->
            mapOf("type" to "error", "turnId" to turnId, "message" to e.message)
        }
        register<AgentStreamEvent.Done> { _, turnId ->
            // Done 事件在 JCEFChatPanel 内部被展开为 thinking_complete + turn_complete
            // 这里返回 null,调用方负责展开
            null
        }

        // === 思考 ===
        // 首次 Thinking 事件发 thinking_start,后续 thinking_update
        // 这部分状态在调用方处理(因为是 per-turn),所以这里统一发 thinking_update
        register<AgentStreamEvent.Thinking> { e, turnId ->
            mapOf("type" to "thinking_update", "turnId" to turnId, "message" to e.message)
        }

        // === 工具调用 ===
        register<AgentStreamEvent.ToolCallStart> { e, turnId ->
            // 修复:summary 不再是 "Running X..." 跟工具名重复
            // 优先用 toolCall 自己的 summary 字段(工具实现里提供),否则用工具名
            val toolSummary = e.toolCall.summary?.takeIf { it.isNotBlank() }
                ?: e.toolCall.name
            // 取工具的 icon (来自 Tool schema),前端用它做个性化图标
            val toolIcon = e.toolCall.icon?.takeIf { it.isNotBlank() }
            mapOf(
                "type" to "tool_call_start",
                "turnId" to turnId,
                "toolId" to e.toolCall.id,
                "toolName" to e.toolCall.name,
                "summary" to toolSummary,
                "icon" to toolIcon,
                // 把 arguments 从 JSON string 解析为对象,方便前端展示
                "arguments" to parseJsonOrRaw(e.toolCall.arguments),
            )
        }
        register<AgentStreamEvent.ToolCallDelta> { e, turnId ->
            mapOf(
                "type" to "tool_call_delta",
                "turnId" to turnId,
                "toolId" to e.toolCallId,
                "toolName" to e.toolName,
                "delta" to e.delta,
            )
        }
        register<AgentStreamEvent.ToolCallResult> { e, turnId ->
            mapOf(
                "type" to "tool_call_complete",
                "turnId" to turnId,
                "toolId" to e.toolCallId,
                "success" to e.success,
                "result" to e.result,
            )
        }
        register<AgentStreamEvent.ToolCallError> { e, turnId ->
            mapOf(
                "type" to "tool_call_error",
                "turnId" to turnId,
                "toolId" to e.toolCallId,
                "error" to e.error,
            )
        }
        register<AgentStreamEvent.ToolConfirmationNeeded> { e, turnId ->
            mapOf(
                "type" to "tool_confirmation_needed",
                "turnId" to turnId,
                "toolId" to e.toolCallId,
                "toolName" to e.toolName,
                "arguments" to e.arguments,
                "reason" to e.reason,
            )
        }

        // === SubAgent(为了新协议,统一发 tool_call_* 类型但 toolName="subagent")===
        register<AgentStreamEvent.SubAgentStart> { e, turnId ->
            lastSubAgentTask[e.sessionId] = e.taskDescription
            lastSubAgentToolset[e.sessionId] = e.toolset
            lastSubAgentStart[e.sessionId] = System.currentTimeMillis()
            mapOf(
                "type" to "tool_call_start",
                "turnId" to turnId,
                "toolId" to e.sessionId,
                "toolName" to "subagent",
                "summary" to e.taskDescription,
                "arguments" to mapOf("toolset" to e.toolset, "task" to e.taskDescription),
                "startTimeMs" to System.currentTimeMillis(),
            )
        }
        register<AgentStreamEvent.SubAgentComplete> { e, turnId ->
            mapOf(
                "type" to "tool_call_complete",
                "turnId" to turnId,
                "toolId" to e.sessionId,
                "success" to e.success,
                "result" to mapOf(
                    "kind" to "subagent",
                    "sessionId" to e.sessionId,
                    "subagent" to mapOf(
                        "sessionId" to e.sessionId,
                        "success" to e.success,
                        "task" to (lastSubAgentTask[e.sessionId] ?: ""),
                        "toolset" to (lastSubAgentToolset[e.sessionId] ?: ""),
                        "output" to e.output,
                        "elapsedMs" to (System.currentTimeMillis() - (lastSubAgentStart[e.sessionId]
                            ?: System.currentTimeMillis())),
                    ),
                ),
            )
        }
        register<AgentStreamEvent.SubAgentProgress> { _, _ ->
            // progress 当前被并入 thinking,这里不发
            null
        }

        // === Plan ===
        register<AgentStreamEvent.PlanGenerated> { e, turnId ->
            mapOf(
                "type" to "plan_generated",
                "turnId" to turnId,
                "planId" to e.planId,
                "description" to e.description,
                "steps" to e.steps.map { s ->
                    mapOf("id" to s.id, "description" to s.description, "dependsOn" to s.dependsOn)
                },
            )
        }
        register<AgentStreamEvent.PlanApproved> { e, turnId ->
            mapOf("type" to "plan_approved", "turnId" to turnId, "planId" to e.planId)
        }
        register<AgentStreamEvent.PlanModified> { e, turnId ->
            mapOf(
                "type" to "plan_modified",
                "turnId" to turnId,
                "planId" to e.planId,
                "steps" to e.steps.map { s ->
                    mapOf("id" to s.id, "description" to s.description, "dependsOn" to s.dependsOn)
                },
            )
        }
        register<AgentStreamEvent.PlanRejected> { e, turnId ->
            mapOf(
                "type" to "plan_rejected",
                "turnId" to turnId,
                "planId" to e.planId,
                "reason" to e.reason,
            )
        }

        // === 上下文压缩 / 会话迁移 ===
        register<AgentStreamEvent.ContextCompressed> { e, turnId ->
            mapOf(
                "type" to "context_compressed",
                "turnId" to turnId,
                "originalTokens" to e.originalTokens,
                "compressedTokens" to e.compressedTokens,
                "strategy" to e.strategy,
            )
        }
        register<AgentStreamEvent.SessionMigrated> { e, turnId ->
            mapOf(
                "type" to "session_migrated",
                "turnId" to turnId,
                "oldSessionId" to e.oldSessionId,
                "newSessionId" to e.newSessionId,
                "messageCount" to e.messageCount,
            )
        }

        // === T1.5: ChatMode 自动建议 ===
        register<AgentStreamEvent.ModeSuggestion> { e, turnId ->
            mapOf(
                "type" to "mode_suggestion",
                "turnId" to turnId,
                "effective" to e.effective.name,
                "suggestion" to e.suggestion.name,
                "userExplicit" to e.userExplicit,
            )
        }
    }

    /** 注册一个事件类型的 handler */
    inline fun <reified T : AgentStreamEvent> register(noinline handler: (T, String) -> Map<String, Any?>?) {
        @Suppress("UNCHECKED_CAST")
        handlers[T::class.java as Class<out AgentStreamEvent>] =
            { event: AgentStreamEvent, turnId: String ->
                @Suppress("UNCHECKED_CAST")
                if (event is T) (handler as (AgentStreamEvent, String) -> Map<String, Any?>?)(event, turnId) else null
            }
    }

    /** 把事件转换成前端消息,无 handler 时返回 null */
    fun toMessage(event: AgentStreamEvent, turnId: String): Map<String, Any?>? {
        val handler = handlers[event::class.java] ?: return null
        return handler(event, turnId)
    }

    /**
     * 尝试把 string 当 JSON 解析,失败则当 raw 字符串返回
     * 用于 ToolCall.arguments(可能是 JSON string 也可能是普通文本)
     */
    private fun parseJsonOrRaw(s: String): Any {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return s
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return try {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            } catch (_: Exception) {
                s
            }
        }
        return s
    }
}

/**
 * 工具:把 Map<String, Any?> 转成 JsonElement
 * 支持 Map / List / String / Number / Boolean / null 嵌套
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal fun mapToJsonElement(value: Any?): JsonElement {
    return when (value) {
        is Map<*, *> -> {
            val pairs = value.entries.map { (k, v) -> k.toString() to mapToJsonElement(v) }
            JsonObject(pairs.toMap())
        }

        is List<*> -> buildJsonArray { value.forEach { add(mapToJsonElement(it)) } }
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        null -> JsonNull
        else -> JsonPrimitive(value.toString())
    }
}

/**
 * 工具:把 Map 序列化为 JSON 字符串(用于 JBCefJSQuery)
 */
internal fun mapToJsonString(map: Map<String, Any?>): String {
    val obj = mapToJsonElement(map)
    return obj.toString()
}
