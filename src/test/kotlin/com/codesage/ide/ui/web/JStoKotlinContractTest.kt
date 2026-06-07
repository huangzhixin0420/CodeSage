package com.codesage.ide.ui.web

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * JS-to-Kotlin 契约测试
 *
 * 目的: 验证前端 main.js / chat.js 实际发出的每一条 bridge.send({ type: ... })
 * 消息,都能被 Kotlin 端 JCEFChatPanel.handleJSMessage 识别(不被丢入 "Unknown" 分支)。
 *
 * 与 UIBridgeContractTest 互补:
 *  - UIBridgeContractTest: Kotlin → JS 事件路由
 *  - 本测试: JS → Kotlin 消息识别
 *
 * 实现策略:
 *  显式枚举前端发出的 type + 后端识别的 type。两个白名单独立维护,
 *  通过测试保证它们始终一致 — 任一方漂移都会让测试失败。
 */
class JStoKotlinContractTest {

    /**
     * JCEFChatPanel.handleJSMessage + 三个 *BridgeHandler 实际能识别的 type。
     * 维护: 每次 JCEFChatPanel.kt / SettingsBridgeHandler.kt / ProviderBridgeHandler.kt /
     *       MigrationBridgeHandler.kt 加 case, 必须同步这里。
     */
    private val backendHandledTypes = setOf(
        // JCEFChatPanel.handleJSMessage 主 when
        "send_message",
        "stop_generation",
        "clear_session",
        "apply_artifact",
        "create_file_from_artifact",
        "regenerate",
        "file_search",
        "switch_model",
        "switch_chat_mode",
        "theme_changed",
        "reload_browser",
        "new_session",
        "switch_session",
        "delete_session",
        "rename_session",
        "request_sessions",
        "__client_error__",
        "__client_ready__",
        // 2026-06 v2.0 UI 配套 (frontend→backend)
        "plan_approve",
        "plan_reject",
        "plan_modify",
        "open_settings",
        "set_show_thinking",
        "attach_file",
        "attach_image",
        // SettingsBridgeHandler
        "settings_get",
        "settings_update",
        "settings_reload",
        "settings_open_folder",
        "settings_open_file",
        "open_settings_view",  // open_settings 内部转发的 key
        // ProviderBridgeHandler
        "set_api_key",
        "test_provider",
        // MigrationBridgeHandler
        "legacy_migration_check",
        "legacy_migration_run",
        "legacy_migration_skip",
    )

    /**
     * 前端 chat.js / main.js 实际 bridge.send 出去的 type。
     * 维护: 每次前端加 bridge.send({type:...}) 必须同步这里。
     */
    private val jsSentTypes = setOf(
        "__client_ready__",
        "apply_artifact",
        "attach_file",
        "attach_image",
        "delete_session",
        "stop_generation",
        "legacy_migration_check",
        "new_session",
        "open_settings",
        "plan_approve",
        "plan_modify",
        "plan_reject",
        "regenerate",
        "rename_session",
        "switch_chat_mode",
        "set_show_thinking",
        "theme_changed",
        "settings_get",
        "settings_update",
        "settings_reload",
        "settings_open_folder",
        "switch_model",
        "switch_session",
        "send_message",
    )

    @Test
    fun `all JS bridge send types are handled by backend`() {
        val unhandled = jsSentTypes - backendHandledTypes
        assertTrue(
            unhandled.isEmpty(),
            buildString {
                append("These JS bridge.send() types are NOT recognized by Kotlin JCEFChatPanel.handleJSMessage:\n")
                for (t in unhandled) append("  - $t\n")
                append("\nFix:\n")
                append("  (a) Add a 'case \"<type>\"' in JCEFChatPanel.handleJSMessage\n")
                append("  (b) Or rename the JS bridge.send type to match what backend already handles (see backendHandledTypes in this test)\n")
            }
        )
    }

    /**
     * v2.0 修复:设置按钮 → JCEFChatPanel.handleJSMessage("open_settings") →
     * settingsHandler.handle("settings_open_view") → SettingsBridgeHandler
     * 把消息回投到前端("open_settings_view") → main.js 打开 in-web 设置视图。
     * 任何一环漂移都会让 "设置按钮点了没反应" 重新出现。
     */
    @Test
    fun `settings open routing is wired across Kotlin and JS`() {
        val jcef = readResource("kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt")
        val sbh = readResource("kotlin/com/codesage/ide/ui/web/SettingsBridgeHandler.kt")
        val mainJs = readResource("webui/js/main.js")
        val chatJs = readResource("webui/js/views/chat.js")
        val html = readResource("webui/index.html")

        // 1) JCEFChatPanel.open_settings case 必须用 settings_open_view(被 settings_* handler 接受)
        val openSettingsCase = extractWhenCaseBody(jcef, "open_settings")
        assertTrue(
            openSettingsCase.contains("settings_open_view"),
            "JCEFChatPanel.open_settings case 应调 settingsHandler.handle('settings_open_view', ...); 实际: $openSettingsCase"
        )
        // 不能还用老的 "open_settings_view"(会被 SettingsBridgeHandler 拒绝)
        val codeOnly = jcef.replace(Regex("//[^\n]*"), "")
        val routes = Regex("""settingsHandler\.handle\(\s*"([^"]+)"""").findAll(codeOnly).map { it.groupValues[1] }.toList()
        assertFalse(
            "open_settings_view" in routes,
            "settingsHandler.handle() 不应再用 'open_settings_view'(被 SettingsBridgeHandler 的 startsWith guard 拒绝); 实际 routes=$routes"
        )

        // 2) SettingsBridgeHandler 必须接受 "settings_open_view",并回投 "open_settings_view"
        assertTrue(
            sbh.contains("\"settings_open_view\" ->"),
            "SettingsBridgeHandler 必须有 settings_open_view case"
        )
        assertTrue(
            sbh.contains("\"open_settings_view\""),
            "SettingsBridgeHandler.settings_open_view 应回投 'open_settings_view' 事件给前端"
        )

        // 3) main.js 必须监听 "open_settings_view" 并调 CodeSage.openSettings()
        assertTrue(
            mainJs.contains("\"open_settings_view\":"),
            "main.js handleBridgeMessage 必须有 open_settings_view case"
        )

        // 4) CodeSage.openSettings 必须真的显示设置根 + 加 body class + 调 settings.show()
        assertTrue(
            mainJs.contains("cs-settings-root") && mainJs.contains("style.display = \"\""),
            "CodeSage.openSettings 应显示 cs-settings-root"
        )
        assertTrue(
            mainJs.contains("cs-settings-open"),
            "CodeSage.openSettings 应加 body.cs-settings-open class"
        )
        assertTrue(
            mainJs.contains("settings.show()"),
            "CodeSage.openSettings 应调 settings.show()"
        )

        // 5) boot() 必须 init settings + 装上 onBack
        assertTrue(
            mainJs.contains("settings.init(") && mainJs.contains("settingsRoot"),
            "main.js boot() 必须 init(settingsRoot)"
        )
        assertTrue(
            mainJs.contains("settings.onBack"),
            "main.js boot() 必须设置 settings.onBack"
        )

        // 6) index.html 必须有 cs-settings-root 容器
        assertTrue(
            html.contains("cs-settings-root"),
            "index.html 必须包含 cs-settings-root 容器"
        )

        // 7) chat.js showSettings 函数的函数体(去掉注释后)必须调 CodeSage.openSettings
        val showSettingsBody = extractJsMethodBody(chatJs, "showSettings()")
        // 去掉 /* ... */ 和 // 注释,避免中文注释里出现 "CodeSage" 字样导致误判
        val showSettingsCode = showSettingsBody
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex(""".//[^\n]*"""), "")
        assertTrue(
            showSettingsCode.contains("CodeSage") || showSettingsCode.contains("codeSage"),
            "chat.js showSettings() 函数体应调 CodeSage.openSettings; 代码部分: $showSettingsCode"
        )
        // 反向:不能是旧实现(直接 bridge.send 老的 open_settings type)
        assertFalse(
            showSettingsCode.contains("\"open_settings\""),
            "chat.js showSettings() 不能走老路径 bridge.send('open_settings'); 代码部分: $showSettingsCode"
        )
    }

    /**
     * 在 JS 源码里找 method 定义 "name(...) {" 紧跟着大括号的函数体。
     * 不匹配 "this.name(...)" 这种调用,只匹配 "    name(...) {" 这种定义。
     */
    private fun extractJsMethodBody(src: String, sig: String): String {
        val pattern = Regex("""
[ 	]+""" + Regex.escape(sig) + """[ 	]*\{""")
        val m = pattern.find(src) ?: return ""
        val start = m.range.last + 1  // 指向 "{"
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



    @Test
    fun `JS-to-Kotlin message field names match backend expectations`() {
        // v2.0 修复后,所有 type 命名 + 字段名都已对齐 — 这条测试作为回归 watchdog。
        // 历史不匹配(已修复):
        //   user_message              -> send_message
        //   chatMode (字段)            -> userLanguage
        //   interrupt                 -> stop_generation
        //   set_chat_mode             -> switch_chat_mode
        //   set_theme                 -> theme_changed
        //   apply_to_editor           -> apply_artifact
        //   apply_to_editor(字段)     -> apply_artifact(artifactId, content)

        // 字段级断言(真实读取源代码,不再硬编码两组列表)
        val jcefSrc = readResource("kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt")
        val chatJsSrc = readResource("webui/js/views/chat.js")

        // 1) send_message — 后端 handleSendMessage 必须读 message 字段(不是 content)
        //    否则文本总是空字符串,UI 表现 "消息发出去没反应"。
        val sendMessageHandle = findHandleSendMessage(jcefSrc)
        assertTrue(
            sendMessageHandle.contains("""jsonObject["message"]"""),
            "JCEFChatPanel.handleSendMessage 必须读 \"message\" 字段(前端 chat.js _send 实际发的是 message); " +
            "读 \"content\" 会导致 message 永远是空字符串。"
        )
        assertFalse(
            sendMessageHandle.contains("""jsonObject["content"]"""),
            "JCEFChatPanel.handleSendMessage 还在读 \"content\" — 前端实际发送的是 message,会导致消息被忽略。"
        )

        // 2) send_message — 前端 chat.js _send 实际发送的字段
        val sendJsBlock = extractBridgeSendBlock(chatJsSrc, "send_message")
        // 真实 JS:  bridge.send({ type: "send_message", message: v, ... })
        // 容忍两种写法:  message: v   或   "message": v
        val hasMessageField = sendJsBlock.contains("message:") ||
            sendJsBlock.contains("\"message\"")
        assertTrue(
            hasMessageField,
            "chat.js 里 send_message 消息体应包含 message 字段; 实际: " + sendJsBlock
        )

        // 3) apply_artifact — 字段约定 (artifactId, content) 必须双方一致
        val applyArtifactHandle = extractWhenCaseBody(jcefSrc, "apply_artifact")
        assertTrue(
            applyArtifactHandle.contains("""jsonObject["artifactId"]""") &&
            applyArtifactHandle.contains("""jsonObject["content"]"""),
            "JCEFChatPanel.apply_artifact 必须读 artifactId 和 content 字段"
        )
        val applyArtifactJs = extractBridgeSendBlock(chatJsSrc, "apply_artifact")
        assertTrue(
            applyArtifactJs.contains("artifactId") && applyArtifactJs.contains("content"),
            "chat.js 里 apply_artifact 消息体应包含 artifactId 和 content; 实际: $applyArtifactJs"
        )

        // 4) apply_to_editor(旧)→ apply_artifact(新):Kotlin 不能还在处理旧 type
        assertFalse(
            jcefSrc.contains(""""apply_to_editor""""),
            "JCEFChatPanel 不应再处理旧的 apply_to_editor type(前端 v2.0 已统一为 apply_artifact)"
        )

        // 双向白名单 size 检查(防止白名单里有幻影 type)
        assertTrue(
            backendHandledTypes.size >= 20,
            "backendHandledTypes 太小,可能漏掉真 case"
        )
        assertTrue(
            jsSentTypes.size >= 15,
            "jsSentTypes 太小,可能漏掉真 bridge.send"
        )
    }

    /**
     * 从 classpath 读取源码/资源(测试在 build/resources 阶段能拿到这些副本)。
     * 找不到时退回到文件系统。
     */
    private fun readResource(path: String): String {
        return try {
            javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: java.io.File("src/main/$path").readText(Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                java.io.File("src/main/$path").readText(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun findHandleSendMessage(src: String): String {
        val marker = "private fun handleSendMessage("
        val idx = src.indexOf(marker)
        if (idx < 0) return ""
        // 取接下来 2000 字符作为该函数体近似
        return src.substring(idx, minOf(idx + 2000, src.length))
    }

    private fun extractWhenCaseBody(src: String, caseName: String): String {
        val pattern = Regex(""""$caseName"\s*->\s*\{""")
        val m = pattern.find(src) ?: return ""
        // 取接下来 500 字符
        return src.substring(m.range.first, minOf(m.range.first + 500, src.length))
    }

    private fun extractBridgeSendBlock(src: String, typeName: String): String {
        val pattern = Regex("""bridge\.send\(\s*\{[^}]*type:\s*["']$typeName["'][^}]*\}""", RegexOption.DOT_MATCHES_ALL)
        val m = pattern.find(src) ?: return ""
        return m.value
    }
}
