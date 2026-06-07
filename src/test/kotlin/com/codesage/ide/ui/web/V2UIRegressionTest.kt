package com.codesage.ide.ui.web

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// v2.0 UI 改造 回归测试
// =====================
//
// 把 4 个用户报告的核心 UI bug 锁在测试里,防止后续重构再次漂移:
//
//  A) 对话不能得到回答
//     - Kotlin 端的 start_turn 事件带 turnId,前端 _startAITurn 必须使用它,
//       否则 this.turns Map 跟后续 text_delta / tool_call_* / thinking_*
//       事件对不上 → 所有事件被丢弃 → 看不到回答。
//  B) 设置页面样式 全部失效
//     - settings.js 实际渲染的类名 (cs-settings-nav-item, cs-settings-nav-subtitle,
//       cs-input, cs-select, cs-toggle, cs-slider, cs-form-field*,
//       cs-shortcuts-table, cs-mcp-*, cs-migration-*, cs-settings-h2, ...)
//       必须在 settings.css 有对应规则。
//  C) 新会话按钮无效
//     - AgentToolWindowPanel.createNewSession 必须走 sendSessions 全量数组协议,
//       不能用旧的 notifySessionCreated(单数 session 字段) — 后者前端 main.js
//       读 msg.sessions 拿到 undefined,sidebar 被空数组覆盖。
//  D) 图片 / 文件按钮点了没反应
//     - attach_image / attach_file 必须调出真正的文件选择逻辑(openFilePicker),
//       选完后通过 type=input_attachments / attachments 字段回投前端 — 这是
//       前端 main.js 唯一会处理的 附件回填 事件。
class V2UIRegressionTest {

    // ==================== A. Chat turnId alignment ====================

    @Test
    fun `A - main js start_turn case passes turnId to chat startAITurn`() {
        val mainJs = readFile("src/main/resources/webui/js/main.js")
        val caseBody = extractCaseBody(mainJs, "start_turn")
        assertTrue(
            caseBody.contains("chat._startAITurn(turnId)"),
            "main.js 的 start_turn case 必须把 turnId 传给 chat._startAITurn; 实际: $caseBody"
        )
        assertFalse(
            caseBody.contains("chat._startAITurn()"),
            "main.js start_turn 不应再调无参的 chat._startAITurn(); 实际: $caseBody"
        )
    }

    @Test
    fun `A - chat js startAITurn accepts turnId param and uses it`() {
        val chatJs = readFile("src/main/resources/webui/js/views/chat.js")
        val body = extractJsMethodBody(chatJs, "_startAITurn(")
        assertTrue(
            body.isNotEmpty(),
            "chat.js 找不到 _startAITurn 方法体"
        )
        assertTrue(
            body.contains("turnId"),
            "chat.js _startAITurn 必须使用 turnId 参数(后端 start_turn 事件带过来); 实际: $body"
        )
        assertTrue(
            body.contains("turnId ||") || body.contains("|| genId"),
            "chat.js _startAITurn 缺省时应 fallback 到 genId('turn'); 实际: $body"
        )
    }

    // ==================== B. Settings CSS coverage ====================

    @Test
    fun `B - settings css covers all classes used in settings js`() {
        val css = readFile("src/main/resources/webui/styles/settings.css")
        val js = readFile("src/main/resources/webui/js/views/settings.js")

        // 从 settings.js 实际出现的 cs-* 类名(从 class 属性里)
        val usedClasses = mutableSetOf<String>()
        val classAttr = Regex("""class\s*=\s*"([^"]+)"""")
        for (m in classAttr.findAll(js)) {
            for (c in m.groupValues[1].split(Regex("\\s+"))) {
                if (c.startsWith("cs-") || c.startsWith("cs_")) usedClasses.add(c)
            }
        }

        // 必选清单(都在 settings.js 实际渲染,以前是裸 HTML 没有样式)
        val required = listOf(
            "cs-settings-root",
            "cs-settings",
            "cs-settings-shell",
            "cs-settings-sidebar",
            "cs-settings-header",
            "cs-settings-back",
            "cs-settings-reload",
            "cs-settings-title",
            "cs-settings-nav",
            "cs-settings-nav-item",
            "cs-settings-nav-text",
            "cs-settings-nav-label",
            "cs-settings-nav-subtitle",
            "cs-settings-footer",
            "cs-settings-path",
            "cs-settings-main",
            "cs-settings-h2",
            "cs-settings-section-desc",
            "cs-settings-section",
            "cs-settings-section-header",
            "cs-settings-section-title",
            "cs-settings-empty",
            "cs-settings-loading",
            "cs-settings-onboarding",
            "cs-settings-onboarding-actions",
            "cs-form-field",
            "cs-form-field-label",
            "cs-form-field-hint",
            "cs-form-field-desc",
            "cs-form-field-control",
            "cs-input",
            "cs-select",
            "cs-toggle",
            "cs-toggle-track",
            "cs-toggle-thumb",
            "cs-toggle-label",
            "cs-slider",
            "cs-slider-range",
            "cs-slider-value",
            "cs-shortcuts-table",
            "cs-shortcut-key",
            "cs-mcp-list",
            "cs-mcp-empty",
            "cs-migration-wizard",
            "cs-migration-stat",
            "cs-migration-actions",
        )

        val missing = required.filter { !cssHasRule(css, it) }
        assertTrue(
            missing.isEmpty(),
            "settings.css 缺少这些类名的规则(类在 settings.js 用到但 CSS 没样式 → 原始状态):\n" +
            missing.joinToString("\n") { "  - $it" }
        )
    }

    @Test
    fun `B - settings css no longer uses orphan class names cs-nav-item and cs-nav-subtitle`() {
        val css = readFile("src/main/resources/webui/styles/settings.css")
        // 这两个老类名 JS 根本不会渲染,留着只会让维护者误以为还在用
        assertFalse(
            Regex("""\.cs-nav-item\b""").containsMatchIn(css),
            "settings.css 不应再用孤儿 .cs-nav-item(应改用 .cs-settings-nav-item)"
        )
        assertFalse(
            Regex("""\.cs-nav-subtitle\b""").containsMatchIn(css),
            "settings.css 不应再用孤儿 .cs-nav-subtitle(应改用 .cs-settings-nav-subtitle)"
        )
    }

    // ==================== C. New session flow ====================

    @Test
    fun `C - new session handler uses sendSessions protocol, not stale notifySessionCreated`() {
        val panelSrc = readFile("src/main/kotlin/com/codesage/ide/toolwindow/AgentToolWindowPanel.kt")
        val jcefSrc = readFile("src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt")
        val mainJs = readFile("src/main/resources/webui/js/main.js")

        // 1) AgentToolWindowPanel.createNewSession 不能再调旧 notifySessionCreated(单数 session 协议)
        val createNewBody = extractKotlinFunctionBody(panelSrc, "private fun createNewSession()")
        // 去掉 // 单行注释 + /* ... */ 块注释,避免注释里提到旧名字触发误报
        val createNewCode = createNewBody
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        assertFalse(
            createNewCode.contains("notifySessionCreated"),
            "AgentToolWindowPanel.createNewSession 不应再调 notifySessionCreated" +
            "(旧协议发 session 单数字段,前端 main.js 读 msg.sessions 拿到 undefined → sidebar 被清空); 实际: $createNewCode"
        )
        assertTrue(
            createNewBody.contains("sendSessions") || createNewBody.contains("refreshSessionList"),
            "AgentToolWindowPanel.createNewSession 必须走 sendSessions 全量推送协议; 实际: $createNewBody"
        )

        // 2) JCEFChatPanel.sendSessions 必须用 sessions 数组字段
        val sendSessions = findSendToJSForType(jcefSrc, "sessions_updated")
        assertTrue(
            sendSessions.contains("\"sessions\""),
            "JCEFChatPanel.sendSessions 写出的字段名必须是 sessions; 实际: $sendSessions"
        )

        // 3) main.js 读 msg.sessions
        val sessionsCase = extractCaseBody(mainJs, "sessions_updated")
        assertTrue(
            sessionsCase.contains("msg.sessions"),
            "main.js sessions_updated case 应读 msg.sessions; 实际: $sessionsCase"
        )
    }

    // ==================== A.6 Sidebar must be visible in DOM (not just in memory) ====================

    @Test
    fun A3_sidebar_element_must_be_appended_to_container_not_just_in_memory() {
        val sidebarJs = readFile("src/main/resources/webui/js/components/cs-sidebar.js")
        // 旧实现: this.el = document.createElement("aside") ... 然后就不 append 了。
        // 整个 sidebar 活在内存里、用户永远看不到 — 新会话按钮点了 sidebar 也不显示。
        // 正确实现: 要么 appendChild 到 container,要么复用 index.html 里已存在的 <aside.cs-sidebar> 占位。
        // 找从 constructor(opts = {}) { 到 this._render(); 之间的代码
        val startIdx = sidebarJs.indexOf("constructor(opts")
        val renderIdx = sidebarJs.indexOf("this._render();", startIdx)
        val constructorMatch = if (startIdx < 0 || renderIdx < 0) "" else sidebarJs.substring(startIdx, renderIdx + "this._render();".length)
        assertTrue(
            constructorMatch.isNotEmpty(),
            "cs-sidebar.js constructor not found; 实际: " + constructorMatch
        )
        val usesExisting = constructorMatch.contains("""querySelector(".cs-sidebar")""") ||
            constructorMatch.contains("""querySelector('.cs-sidebar')""")
        val appendsNew = constructorMatch.contains("appendChild")
        assertTrue(
            usesExisting || appendsNew,
            "cs-sidebar.js Sidebar 构造器必须把 el 放进 DOM(要么复用 .cs-sidebar 占位、要么 appendChild); 实际: " + constructorMatch
        )
    }

    // ==================== A.5 Cursor / segment: text must survive end_turn ====================

    @Test
    fun A2_end_turn_must_not_delete_streamed_text_via_cursor_remove() {
        val chatJs = readFile("src/main/resources/webui/js/views/chat.js")
        // 关键: _startAITurn 不能再把 cursor 直接当 currentStreamSegment,
        // 否则 end_turn 里 cursor?.remove() 会把流式文本一起删掉,用户看不到回答。
        // 允许的实现:turn.currentStreamSegment = null (新段在第一次 text_delta 时创建)
        // 或:cursor.parentNode 一开始就是 null / 其他方式确保 cursor 和 segment 分离
        val startTurnBody = extractJsMethodBody(chatJs, "_startAITurn(")
        // 旧的 buggy 写法: turn.currentStreamSegment = cursor;  (cursor 就是那个空 span)
        // 正确写法: turn.currentStreamSegment = null; (新段在第一次 text_delta 时创建)
        val isOldBuggyStyle = startTurnBody.contains("turn.currentStreamSegment = cursor;")
        val isNewSafeStyle = startTurnBody.contains("turn.currentStreamSegment = null;") ||
            startTurnBody.contains("currentStreamSegment = null;")
        assertFalse(
            isOldBuggyStyle && !isNewSafeStyle,
            "chat.js _startAITurn must not use turn.currentStreamSegment = cursor; (end_turn cursor?.remove() deletes streamed text); 实际: " + startTurnBody
        )
    }

    // ==================== D. Image / file attach ====================

    @Test
    fun `D - attach_image and attach_file use input_attachments protocol with attachments field`() {
        val jcefSrc = readFile("src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt")
        val mainJs = readFile("src/main/resources/webui/js/main.js")

        // 1) attach_image case 必须调出真正的文件选择器(不再发空 file_references 占位)
        val attachImage = extractWhenCaseBody(jcefSrc, "attach_image")
        // 注意:必须查引号包裹的 "file_references" — 新的 file_references_added
        // 事件名只是含 "file_references" 子串,不应被误判为废弃事件。
        assertFalse(
            attachImage.contains("\"file_references\""),
            "attach_image 不应再发废弃的 \"file_references\"(前端 main.js 不读); 实际: $attachImage"
        )
        assertTrue(
            attachImage.contains("openFilePicker") || attachImage.contains("FileChooser"),
            "attach_image 应调出真实文件选择器(openFilePicker / FileChooser); 实际: $attachImage"
        )

        // 2) attach_file 同上(同上,只查带引号的废弃事件字符串)
        val attachFile = extractWhenCaseBody(jcefSrc, "attach_file")
        assertFalse(
            attachFile.contains("\"file_references\""),
            "attach_file 不应再发废弃的 \"file_references\"; 实际: $attachFile"
        )
        assertTrue(
            attachFile.contains("openFilePicker") || attachFile.contains("FileChooser"),
            "attach_file 应调出真实文件选择器; 实际: $attachFile"
        )

        // 3) Kotlin 端必须有 emit input_attachments + attachments 字段的代码
        assertTrue(
            jcefSrc.contains("\"input_attachments\"") &&
                jcefSrc.contains("\"attachments\""),
            "JCEFChatPanel 必须有 emit input_attachments + attachments 字段的代码"
        )

        // 4) main.js 的 input_attachments case 读 msg.attachments
        val inputAttachmentsCase = extractCaseBody(mainJs, "input_attachments")
        assertTrue(
            inputAttachmentsCase.contains("msg.attachments"),
            "main.js input_attachments case 应读 msg.attachments; 实际: $inputAttachmentsCase"
        )
    }

    // ==================== helpers ====================

    private fun readFile(path: String): String {
        return try {
            java.io.File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            fail<String>("Cannot read $path: ${e.message}")
            ""
        }
    }

    private fun extractCaseBody(src: String, caseName: String): String {
        val regex = Regex("""case\s+["']""" + Regex.escape(caseName) + """["']\s*:[^:]+?break;""", RegexOption.DOT_MATCHES_ALL)
        return regex.find(src)?.value ?: ""
    }

    private fun extractJsMethodBody(src: String, sig: String): String {
        // 找形如: "    sig + params ) {"  的方法定义,sig 可能带或不带开括号
        // 然后向后扫描找匹配的右大括号
        val sigIdx = src.indexOf(sig)
        if (sigIdx < 0) return ""
        // 跳过方法参数直到遇到 {  (参数里也可能有 () 嵌套,需平衡)
        var i = sigIdx + sig.length
        if (i < src.length && src[i] == '(') {
            // 平衡括号跳过参数
            var depth = 1
            i++
            while (i < src.length && depth > 0) {
                if (src[i] == '(') depth++
                else if (src[i] == ')') depth--
                i++
            }
        }
        // 跳过空白找 {
        while (i < src.length && src[i] != '{') i++
        if (i >= src.length) return ""
        val start = i + 1
        var depth = 0
        var end = start
        while (end < src.length) {
            if (src[end] == '{') depth++
            else if (src[end] == '}') {
                depth--
                if (depth == 0) { end++; break }
            }
            end++
        }
        return src.substring(start, end)
    }

    private fun extractKotlinFunctionBody(src: String, sig: String): String {
        val idx = src.indexOf(sig)
        if (idx < 0) return ""
        val braceStart = src.indexOf('{', idx)
        if (braceStart < 0) return ""
        var depth = 0
        var end = braceStart
        while (end < src.length) {
            if (src[end] == '{') depth++
            else if (src[end] == '}') {
                depth--
                if (depth == 0) { end++; break }
            }
            end++
        }
        return src.substring(braceStart, end)
    }

    private fun extractWhenCaseBody(src: String, caseName: String): String {
        val pattern = Regex(""""$caseName"\s*->\s*\{""")
        val m = pattern.find(src) ?: return ""
        val braceStart = m.range.last
        var depth = 0
        var end = braceStart
        while (end < src.length) {
            if (src[end] == '{') depth++
            else if (src[end] == '}') {
                depth--
                if (depth == 0) { end++; break }
            }
            end++
        }
        return src.substring(m.range.first, end)
    }

    private fun findSendToJSForType(src: String, typeName: String): String {
        val q = "\""
        val needle = q + "type" + q + " to " + q + typeName + q
        val idx = src.indexOf(needle)
        if (idx < 0) return ""
        val start = maxOf(0, idx - 50)
        val end = minOf(src.length, idx + 400)
        return src.substring(start, end)
    }

    // 检查 CSS 里有没有 .className 后面跟 空白/:/./{/,/ 等选择器分隔符的规则
    private fun cssHasRule(css: String, className: String): Boolean {
        val fallback = Regex("""\.""" + Regex.escape(className) + """[\s:.{>+~\[]""")
        return fallback.containsMatchIn(css)
    }
}
