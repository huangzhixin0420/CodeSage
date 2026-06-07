package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.ChatMode
import com.codesage.model.dto.Role
import com.codesage.model.dto.ToolCall
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * UI-Bridge 契约测试
 *
 * 目的: 验证 Kotlin 端 EventRouter/EventConsumer/JCEFChatPanel.sendToJS 产出的
 * 每一个事件 type 都能被前端 main.js handleBridgeMessage 路由到对应 handler,
 * 避免"重写 UI 后事件命名漂移导致用户消息发不出去 / 工具堆底"等回归。
 *
 * 策略:
 *  1. 用一个 captured-events 列表模拟前端,枚举"前端能识别的 type"
 *  2. 触发后端各事件源(EventRouter + EventConsumer + JCEFChatPanel.sendToJS)
 *  3. 对每个产出的事件,断言其 type 在前端白名单内
 *
 * 真正的"前端白名单"维护方式: 读 main.js 的 case 列表,而不是硬编码,
 * 这样前端加 case 不会立刻被测试打断(只在 main.js 删 case 时报警)。
 */
class UIBridgeContractTest {
    /**
     * 前端 main.js handleBridgeMessage 识别的 case 列表
     *
     * 维护: 每次前端加 case 必须同步这里;每次后端新增 sendToJS type 也必须同步。
     * 写在一个常量里方便 grep / 审查。
     */
    private val frontendHandledTypes = setOf(
        // EventRouter 产出
        "text_delta",
        "thinking_update",
        "tool_call_start",
        "tool_call_delta",
        "tool_call_complete",
        "tool_call_error",
        "tool_confirmation_needed",
        "plan_generated",
        "plan_approved",
        "plan_rejected",
        "plan_modified",
        "context_compressed",
        "session_migrated",
        "mode_suggestion",
        "error",
        // EventConsumer 改写/展开
        "thinking_start",     // 首条 Thinking 重写
        "thinking_complete",
        "turn_complete",
        // JCEFChatPanel 直接发出
        "model_changed",        // = set_model 改名前
        "available_models",     // = set_models 改名前
        "theme",
        "clear_chat",           // = clear 改名前
        "sessions_updated",     // 替代 session_created (统一发列表)
        "session_switched",
        "session_deleted",
        "session_renamed",
        "history",
        "artifact_add",         // = artifact 改名前
        "file_references",
        "file_suggestions",
        // AgentToolWindowPanel 适配层
        "start_turn",
        "end_turn",
        "user_message_ack",
        "input_attachments",
        "show_thinking",
        // settings/walkthrough 委派 (chat 转发)
        "settings_data",
        "settings_saved",
        "settings_error",
        "set_api_key_result",
        "test_provider_result",
        "legacy_migration_done",
        "legacy_migration_error",
        "legacy_migration_preview",
        "legacy_migration_skipped",
    )

    private val captured = mutableListOf<Map<String, Any?>>()

    @Test
    fun `EventRouter produces only frontend-handled types`() = runBlocking {
        val router = EventRouter()

        // 1. 文本 delta
        captured.add(router.toMessage(AgentStreamEvent.TextDelta("hello"), "turn_1")!!)
        // 2. thinking
        captured.add(router.toMessage(AgentStreamEvent.Thinking("analyzing"), "turn_1")!!)
        // 3. tool call 生命周期
        captured.add(
            router.toMessage(
                AgentStreamEvent.ToolCallStart(
                    ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"a.kt"}""")
                ),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.ToolCallDelta("tc1", "read_file", "d"),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.ToolCallResult("tc1", "read_file", """{"ok":true}""", success = true),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.ToolCallError("tc1", "boom"),
                "turn_1"
            )!!
        )
        // 4. plan
        captured.add(
            router.toMessage(
                AgentStreamEvent.PlanGenerated(
                    planId = "p1",
                    description = "fix bug",
                    steps = listOf(
                        AgentStreamEvent.PlanStep("s1", "step 1"),
                    ),
                ),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(AgentStreamEvent.PlanApproved("p1"), "turn_1")!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.PlanRejected("p1", "no"),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.PlanModified(
                    planId = "p1",
                    steps = emptyList(),
                ),
                "turn_1"
            )!!
        )
        // 5. context / session
        captured.add(
            router.toMessage(
                AgentStreamEvent.ContextCompressed(1000, 500, "summarize"),
                "turn_1"
            )!!
        )
        captured.add(
            router.toMessage(
                AgentStreamEvent.SessionMigrated("old", "new", 10),
                "turn_1"
            )!!
        )
        // 6. mode
        captured.add(
            router.toMessage(
                AgentStreamEvent.ModeSuggestion(
                    effective = ChatMode.CODING,
                    suggestion = ChatMode.REASONING,
                    userExplicit = false,
                ),
                "turn_1"
            )!!
        )
        // 7. error
        captured.add(
            router.toMessage(AgentStreamEvent.Error("test error"), "turn_1")!!
        )

        assertAllEventsHandled("EventRouter")
    }

    @Test
    fun `EventConsumer Done expansion produces only frontend-handled types`() = runBlocking {
        val router = EventRouter()
        val delivered = mutableListOf<Map<String, Any?>>()
        val consumer = EventConsumer(router, { delivered.add(it) }, flushIntervalMs = Long.MAX_VALUE)
        val flow = flowOf(AgentStreamEvent.Done)
        consumer.consumeTurn(flow, "turn_1") {}

        assertEquals(2, delivered.size, "Done should expand to exactly 2 messages")
        val types = delivered.map { it["type"] }
        assertEquals(listOf("thinking_complete", "turn_complete"), types)
        for (t in types) {
            assertTrue(
                t in frontendHandledTypes,
                "Frontend has no case for '$t' — main.js handler is missing"
            )
        }
    }

    @Test
    fun `all turn events delivered by EventConsumer match frontend types`() = runBlocking {
        val router = EventRouter()
        val delivered = mutableListOf<Map<String, Any?>>()
        val consumer = EventConsumer(router, { delivered.add(it) }, flushIntervalMs = Long.MAX_VALUE)

        // 真实场景: 一次完整 turn 的事件流
        val flow = flowOf(
            AgentStreamEvent.TextDelta("Hi"),
            AgentStreamEvent.Thinking("thinking"),
            AgentStreamEvent.ToolCallStart(
                ToolCall(id = "tc1", name = "read_file", arguments = """{"path":"x"}""")
            ),
            AgentStreamEvent.ToolCallResult("tc1", "read_file", "ok", true),
            AgentStreamEvent.PlanGenerated("p1", "fix", listOf(AgentStreamEvent.PlanStep("s1", "s"))),
            AgentStreamEvent.PlanApproved("p1"),
            AgentStreamEvent.Done,
        )

        consumer.consumeTurn(flow, "turn_test") {}

        assertTrue(delivered.isNotEmpty(), "Should have delivered events")
        val unhandled = delivered.mapNotNull { (it["type"] as? String) }
            .filter { it !in frontendHandledTypes }
        assertTrue(
            unhandled.isEmpty(),
            "Frontend has no case for these event types: $unhandled. " +
            "Add a case in main.js handleBridgeMessage or update frontendHandledTypes in UIBridgeContractTest."
        )
    }

    /**
     * v2.0 修复:available_models / sessions_updated / model_changed 等事件,
     * 前端 main.js handleBridgeMessage 读的字段名必须与 JCEFChatPanel.sendToJS
     * 写出的字段名一致。例如历史上 modelGroups vs models 漂移导致模型下拉一直空。
     */
    @Test
    fun `Kotlin sendToJS field names match JS main js handleBridgeMessage readers`() {
        val jcefSrc = readResource("kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt")
        val mainJsSrc = readResource("webui/js/main.js")

        // 1) available_models — Kotlin 发 "models" 数组,JS 读 msg.models
        val kotlinAvailableModels = findSendToJSForType(jcefSrc, "available_models")
        assertTrue(
            kotlinAvailableModels.contains(""""models""""),
            "JCEFChatPanel.setAvailableModels 写出的字段名必须是 \"models\"; 实际: $kotlinAvailableModels"
        )
        val jsAvailableModels = findCaseBody(mainJsSrc, "available_models")
        assertTrue(
            jsAvailableModels.contains("msg.models"),
            "main.js handleBridgeMessage 的 available_models case 必须读 msg.models; 实际: $jsAvailableModels"
        )
        assertFalse(
            jsAvailableModels.contains("msg.modelGroups"),
            "main.js 不应再读废弃的 msg.modelGroups(Kotlin 不再发这个字段); 实际: $jsAvailableModels"
        )

        // 2) model_changed — 双方都走 model + provider
        val kotlinModelChanged = findSendToJSForType(jcefSrc, "model_changed")
        assertTrue(
            kotlinModelChanged.contains(""""model"""") && kotlinModelChanged.contains(""""provider""""),
            "JCEFChatPanel.setModelLabel 应发出 model + provider"
        )
        val jsModelChanged = findCaseBody(mainJsSrc, "model_changed")
        assertTrue(
            jsModelChanged.contains("msg.model") && jsModelChanged.contains("msg.provider"),
            "main.js model_changed 应读 msg.model 和 msg.provider"
        )

        // 3) sessions_updated — 双方都走 sessions 数组
        val kotlinSessions = findSendToJSForType(jcefSrc, "sessions_updated")
        assertTrue(
            kotlinSessions.contains(""""sessions""""),
            "JCEFChatPanel.sendSessions 应发出 \"sessions\" 数组"
        )
        val jsSessions = findCaseBody(mainJsSrc, "sessions_updated")
        assertTrue(
            jsSessions.contains("msg.sessions"),
            "main.js sessions_updated 应读 msg.sessions"
        )

        // 4) user_message_ack — Kotlin 写 text + fileRefs,JS 读 text + images + fileRefs
        //    text/content 的 fallback 是有意为之,只要 text 命中即可。
        val kotlinAck = findSendToJSForType(jcefSrc, "user_message_ack")
        assertTrue(
            kotlinAck.contains(""""text"""") && kotlinAck.contains(""""fileRefs""""),
            "JCEFChatPanel.user_message_ack 应发出 text + fileRefs"
        )
        val jsAck = findCaseBody(mainJsSrc, "user_message_ack")
        assertTrue(
            jsAck.contains("msg.text"),
            "main.js user_message_ack 应读 msg.text"
        )

        // 5) history — 双方都走 messages 数组
        val kotlinHistory = findSendToJSForType(jcefSrc, "history")
        assertTrue(kotlinHistory.contains(""""messages""""))
        val jsHistory = findCaseBody(mainJsSrc, "history")
        assertTrue(jsHistory.contains("msg.messages"))
    }

    private fun readResource(path: String): String {
        // 优先按 src/main/<path> 读(测试运行时 cwd 是 repo 根)。
        // 找不到再走 classpath(JCEF 资源复制到 build/resources 时,路径会带 kotlin/ 前缀)。
        val fsPath = "src/main/$path"
        val tryPaths = listOf(
            fsPath,
            path.removePrefix("kotlin/"),
            // 在 build/resources/main 下,webui 资源会保留 webui/ 前缀
            "build/resources/main/" + path.removePrefix("kotlin/"),
        )
        for (p in tryPaths) {
            try {
                val f = java.io.File(p)
                if (f.exists()) return f.readText(Charsets.UTF_8)
            } catch (_: Exception) { /* try next */ }
        }
        return try {
            javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun findSendToJSForType(src: String, typeName: String): String {
        // 找 "type" to "typeName" 的位置, 周围 400 字符
        val q = "\""
        val needle = q + "type" + q + " to " + q + typeName + q
        val idx = src.indexOf(needle)
        if (idx < 0) return ""
        val start = maxOf(0, idx - 50)
        val end = minOf(src.length, idx + 400)
        return src.substring(start, end)
    }



    private fun findCaseBody(src: String, caseName: String): String {
        val regex = Regex("""case\s+["']""" + Regex.escape(caseName) + """["']\s*:[^:]+?break;""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(src)?.value ?: ""
    }

    private fun assertAllEventsHandled(source: String) {
        val unhandled = captured.mapNotNull { (it["type"] as? String) }
            .filter { it !in frontendHandledTypes }
        assertTrue(
            unhandled.isEmpty(),
            "[$source] Frontend has no case for these event types: $unhandled. " +
            "Either add a case in main.js handleBridgeMessage, or rename the event in Kotlin sendToJS. " +
            "(See UIBridgeContractTest.frontendHandledTypes for the canonical list.)"
        )
    }
}
