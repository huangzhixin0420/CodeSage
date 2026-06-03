package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.ChatMode
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.Timer

/**
 * JCEF Web UI 聊天面板
 *
 * v2 重构(执行计划 P1.1-P1.5):
 *  - HTML 入口从 chat.html 改为 index.html(modular 结构)
 *  - 事件路由从 100+ 行 when 抽到 EventRouter
 *  - 行为不变,前端协议兼容
 *
 * 协议:
 *   - Kotlin → JS: window.javaBridge.sendMessage(json) → window.onJavaMessage(json)
 *   - JS → Kotlin: window.javaBridge.sendMessage(json) → 注入的 queryFunc(json)
 */
class JCEFChatPanel(
    private val project: Project?
) : JPanel(BorderLayout()) {

    private val logger = Logger.getLogger<JCEFChatPanel>()
    private val fileResolver = FileReferenceResolver(project)
    private val eventRouter = EventRouter()
    private val settingsHandler = SettingsBridgeHandler { msg -> sendToJS(msg) }
    private val providerHandler = ProviderBridgeHandler { msg -> sendToJS(msg) }
    private val migrationHandler = MigrationBridgeHandler { msg -> sendToJS(msg) }

    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null
    private var scope: CoroutineScope? = null
    private var currentCollectJob: Job? = null

    private var currentTurnId: String? = null

    /**
     * T1.5: 当前用户选中的 ChatMode(null = 后端走 suggestion)
     */
    private var currentChatMode: ChatMode? = null

    private var messageCallback: ((String, List<ImageAttachment>) -> Unit)? = null
    private var stopCallback: (() -> Unit)? = null
    private var switchModelCallback: ((String) -> Unit)? = null
    private var switchChatModeCallback: ((ChatMode) -> Unit)? = null
    private var sessionActionHandler: ((String, Map<String, Any>) -> Unit)? = null
    private var continueBudgetCallback: ((Int) -> Flow<AgentStreamEvent>)? = null

    private val pendingMessages = mutableListOf<String>()

    // 事件去重(高频事件节流)
    private val recentEventCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dedupWindowMs = 500L

    // 思考事件状态(per-turn):首条 Thinking 事件发 thinking_start,后续发 thinking_update
    private val thinkingStarted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var isBridgeReady = false

    init {
        if (JBCefApp.isSupported()) {
            initializeBrowser()
        } else {
            logger.warn("JCEF is not supported in this environment")
            add(createFallbackPanel(), BorderLayout.CENTER)
        }
    }

    private fun initializeBrowser() {
        try {
            val newBrowser = JBCefBrowser()
            browser = newBrowser

            // 提取 webui/* 到本地目录, 后面用 file:// 加载,
            // 这样 HTML 中所有相对路径 (CSS / JS / ES modules) 都能正确解析
            val extractedDir = try {
                WebResourceExtractor.extract()
            } catch (e: Exception) {
                logger.error("Failed to extract webui resources, using inline fallback", e)
                null
            }
            if (extractedDir != null) {
                // 将处理后的 index.html 写到提取目录（抹除内联 CDN URL, 走本地 file://）
                prepareExtractedIndexHtml(extractedDir)
                val indexFile = java.io.File(extractedDir, "index.html")
                val fileUrl = indexFile.toURI().toString()
                logger.info("[JCEFChatPanel] loading via file:// $fileUrl")
                newBrowser.loadURL(fileUrl)
            } else {
                val htmlContent = createFallbackHTML()
                newBrowser.loadHTML(htmlContent)
            }

            jsQuery = JBCefJSQuery.create(newBrowser).apply {
                addHandler { message ->
                    handleJSMessage(message)
                    JBCefJSQuery.Response("ok")
                }
            }

            newBrowser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    cefBrowser: org.cef.browser.CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean
                ) {
                    if (!isLoading) injectJSBridge()
                }
            }, newBrowser.cefBrowser)

            add(newBrowser.component, BorderLayout.CENTER)
            logger.info("JCEF browser initialized")
        } catch (e: Exception) {
            logger.error("Failed to initialize JCEF browser", e)
            add(createFallbackPanel(), BorderLayout.CENTER)
        }
    }

    /**
     * 读取从 classpath 提取出的 index.html，过一遍 ResourceInliner（处理可能的 CDN URL）后写回。
     * 主要价值在于保持与旧 chat.html 路径的兼容；新 index.html 几乎不需要内联处理，
     * 因为所有静态资源都已在提取目录中、浏览器通过 file:// 直接拿。
     */
    private fun prepareExtractedIndexHtml(extractedDir: java.io.File) {
        val indexFile = java.io.File(extractedDir, "index.html")
        val original = indexFile.readText(Charsets.UTF_8)
        val processed = try {
            ResourceInliner.inlineResources(original)
        } catch (e: Exception) {
            logger.warn("ResourceInliner failed, writing raw HTML: ${e.message}")
            original
        }
        if (processed !== original) {
            indexFile.writeText(processed, Charsets.UTF_8)
            logger.info("[JCEFChatPanel] index.html post-processed (CDN URLs inlined)")
        }
    }

    /**
     * 加载 webui/index.html(模块化结构)
     * 与原 chat.html 不同：用 ESM 拆分 + 自托管 vendor
     *
     * 注意：此函数仍然保留作为"读取 HTML 文本"的接口，
     * 但 initializeBrowser 不再使用它（改用 file:// + 提取目录）。
     */
    @Suppress("unused")
    private fun loadWebUiHtml(): String? {
        return try {
            javaClass.classLoader.getResourceAsStream("webui/index.html")?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }?.let { ResourceInliner.inlineResources(it) }
        } catch (e: Exception) {
            logger.error("Failed to load index.html, falling back to chat.html", e)
            javaClass.classLoader.getResourceAsStream("webui/chat.html")?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }
    }

    private fun injectJSBridge() {
        val query = jsQuery ?: return
        val cefBrowser = browser?.cefBrowser ?: return

        val bridgeCode = """
            (function() {
                var queryFunc = window['${query.funcName}'] || ${query.funcName};
                if (typeof queryFunc !== 'function') {
                    console.error('[CodeSage] JBCefJSQuery function not found');
                    return;
                }
                window.javaBridge = {
                    sendMessage: function(json) {
                        queryFunc({
                            request: '' + json,
                            onSuccess: function() {},
                            onFailure: function(code, msg) {
                                console.error('[JBCefJSQuery] Error ' + code + ': ' + msg);
                            }
                        });
                    }
                };
                if (window.onBridgeReady) window.onBridgeReady();
                console.log('[CodeSage] JS Bridge injected');
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(bridgeCode, cefBrowser.url ?: "", 0)
        isBridgeReady = true
        flushPendingMessages()
        scheduleLibraryCheck(cefBrowser)
    }

    private fun flushPendingMessages() {
        val cefBrowser = browser?.cefBrowser ?: return
        if (pendingMessages.isEmpty()) return
        val copy = pendingMessages.toList()
        pendingMessages.clear()
        copy.forEach { cefBrowser.executeJavaScript(it, cefBrowser.url ?: "", 0) }
    }

    /**
     * JS → Kotlin 消息入口
     */
    private fun handleJSMessage(message: String) {
        try {
            val json = Json.parseToJsonElement(message)
            val type = json.jsonObject["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "send_message" -> handleSendMessage(json)
                "stop_generation" -> stopCallback?.invoke()
                "clear_session" -> { /* handled by view */
                }

                "apply_artifact" -> {
                    val artifactId = json.jsonObject["artifactId"]?.jsonPrimitive?.content ?: ""
                    val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                    applyArtifactToEditor(artifactId, content)
                }

                "create_file_from_artifact" -> {
                    val title = json.jsonObject["title"]?.jsonPrimitive?.content ?: ""
                    val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                    createFileFromArtifact(title, content)
                }

                "regenerate" -> { /* TODO */
                }

                "file_search" -> {
                    val query = json.jsonObject["query"]?.jsonPrimitive?.content ?: ""
                    val suggestions = fileResolver.searchFiles(query)
                    sendToJS(
                        mapOf(
                            "type" to "file_suggestions",
                            "query" to query,
                            "suggestions" to suggestions.map {
                                mapOf(
                                    "name" to it.name,
                                    "path" to it.path,
                                    "relativePath" to it.relativePath,
                                    "icon" to it.icon,
                                )
                            }
                        ))
                }

                "switch_model" -> {
                    val model = json.jsonObject["model"]?.jsonPrimitive?.content ?: ""
                    if (model.isNotBlank()) switchModelCallback?.invoke(model)
                }

                "switch_chat_mode" -> {
                    val modeStr = json.jsonObject["mode"]?.jsonPrimitive?.content ?: ""
                    val mode = ChatMode.fromString(modeStr)
                    if (modeStr.isNotBlank() && mode != null) {
                        currentChatMode = mode
                        switchChatModeCallback?.invoke(mode)
                    }
                }

                "theme_changed" -> { /* persisted in JS */
                }

                "reload_browser" -> reloadBrowser()
                "new_session" -> sessionActionHandler?.invoke("new_session", emptyMap())
                "switch_session" -> {
                    val sid = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    if (sid.isNotBlank()) sessionActionHandler?.invoke("switch_session", mapOf("sessionId" to sid))
                }

                "delete_session" -> {
                    val sid = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    if (sid.isNotBlank()) sessionActionHandler?.invoke("delete_session", mapOf("sessionId" to sid))
                }

                "rename_session" -> {
                    val sid = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    val name = json.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    if (sid.isNotBlank() && name.isNotBlank()) {
                        sessionActionHandler?.invoke("rename_session", mapOf("sessionId" to sid, "name" to name))
                    }
                }

                "request_sessions" -> sessionActionHandler?.invoke("request_sessions", emptyMap())
                "continue_task" -> handleContinueTask(json)
                "settings_get",
                "settings_update",
                "settings_reload",
                "settings_open_folder",
                "settings_open_file",
                    -> {
                    val handled = settingsHandler.handle(
                        type,
                        json.jsonObject.entries.associate { (k, v) -> k to v },
                    )
                    if (!handled) logger.debug("Unhandled settings message: $type")
                }

                "set_api_key",
                "test_provider",
                    -> {
                    val handled = providerHandler.handle(
                        type,
                        json.jsonObject.entries.associate { (k, v) -> k to v },
                    )
                    if (!handled) logger.debug("Unhandled provider message: $type")
                }

                "legacy_migration_check",
                "legacy_migration_run",
                "legacy_migration_skip",
                    -> {
                    val handled = migrationHandler.handle(
                        type,
                        json.jsonObject.entries.associate { (k, v) -> k to v },
                    )
                    if (!handled) logger.debug("Unhandled migration message: $type")
                }

                "__client_error__" -> {
                    // 上报前端 JS 异常到 Kotlin 日志
                    val msg = json.jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    val src = json.jsonObject["source"]?.jsonPrimitive?.content ?: ""
                    val stack = json.jsonObject["stack"]?.jsonPrimitive?.content ?: ""
                    logger.warn("[JS client error] source=$src message=$msg stack=$stack")
                }

                "__client_ready__" -> {
                    logger.info("[CodeSage] client reported ready")
                }

                else -> logger.debug("Unknown message type: $type")
            }
        } catch (e: Exception) {
            logger.error("Failed to handle JS message: $message", e)
        }
    }

    private fun handleSendMessage(json: kotlinx.serialization.json.JsonElement) {
        val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: ""
        val clientTurnId = json.jsonObject["turnId"]?.jsonPrimitive?.content
        val messageChatMode = json.jsonObject["chatMode"]?.jsonPrimitive?.content
            ?.let { ChatMode.fromString(it) }
            ?: currentChatMode
        if (messageChatMode != null) currentChatMode = messageChatMode
        if (!clientTurnId.isNullOrBlank()) currentTurnId = clientTurnId

        // 解析图片附件 (P5.4)
        val images: List<ImageAttachment> = parseImages(json)

        if (messageCallback == null) {
            logger.error("[JS→Kotlin] messageCallback is null")
        } else if (content.isBlank() && images.isEmpty()) {
            logger.warn("[JS→Kotlin] empty message ignored")
        } else {
            messageCallback?.invoke(content, images)
        }
    }

    /**
     * 从 send_message payload 中解析图片附件
     * 格式: images: [{ id, mime, dataUrl, name }]
     */
    private fun parseImages(json: kotlinx.serialization.json.JsonElement): List<ImageAttachment> {
        val arr = json.jsonObject["images"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val mime = obj["mime"]?.jsonPrimitive?.content ?: "image/png"
                val dataUrl = obj["dataUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: "image"
                if (dataUrl.isBlank()) return@mapNotNull null
                ImageAttachment(id = id, mime = mime, dataUrl = dataUrl, name = name)
            } catch (e: Exception) {
                logger.warn("[ImageParse] failed: ${e.message}")
                null
            }
        }
    }

    private fun handleContinueTask(json: kotlinx.serialization.json.JsonElement) {
        val extraIterations = json.jsonObject["extraIterations"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
        val turnId = json.jsonObject["turnId"]?.jsonPrimitive?.content ?: currentTurnId
        val callback = continueBudgetCallback
        if (callback == null) {
            sendToJS(mapOf("type" to "error", "turnId" to (turnId ?: ""), "message" to "继续执行功能未初始化"))
            return
        }
        currentCollectJob?.cancel()
        currentCollectJob = scope?.launch {
            try {
                callback(extraIterations).collect { event ->
                    routeEvent(event, turnId ?: "")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                logger.info("[ContinueCallback] cancelled")
            } catch (e: Throwable) {
                logger.error("[ContinueCallback] error", e)
                sendToJS(mapOf("type" to "error", "turnId" to (turnId ?: ""), "message" to (e.message ?: "未知错误")))
            }
        }
    }

    /**
     * 通过 EventRouter 转发一个事件
     * 对外暴露的统一入口(原 messageCallback 调用方也走这里)
     */
    private fun routeEvent(event: AgentStreamEvent, turnId: String) {
        if (!shouldEmit(event)) return

        // Thinking 事件特殊处理:首条发 thinking_start
        if (event is AgentStreamEvent.Thinking) {
            val first = thinkingStarted.putIfAbsent(turnId, true) == null
            if (first) {
                sendToJS(mapOf("type" to "thinking_start", "turnId" to turnId, "message" to event.message))
            } else {
                sendToJS(mapOf("type" to "thinking_update", "turnId" to turnId, "message" to event.message))
            }
            return
        }

        // Done 事件展开为 thinking_complete + turn_complete
        if (event is AgentStreamEvent.Done) {
            sendToJS(mapOf("type" to "thinking_complete", "turnId" to turnId, "elapsedMs" to 0))
            sendToJS(mapOf("type" to "turn_complete", "turnId" to turnId))
            thinkingStarted.remove(turnId)
            return
        }

        val msg = eventRouter.toMessage(event, turnId) ?: return
        sendToJS(msg)
    }

    private fun shouldEmit(event: AgentStreamEvent): Boolean {
        val key = event::class.simpleName ?: return true
        val now = System.currentTimeMillis()
        val last = recentEventCache[key]
        return if (last != null && now - last < dedupWindowMs) {
            false
        } else {
            recentEventCache[key] = now
            true
        }
    }

    // ===== Public API =====

    fun initialize(
        scope: CoroutineScope,
        onSendMessage: (String, List<ImageAttachment>) -> Flow<AgentStreamEvent>,
        onStop: () -> Unit,
        onSwitchModel: ((String) -> Unit)? = null,
        onSwitchChatMode: ((ChatMode) -> Unit)? = null,
        onSessionAction: ((String, Map<String, Any>) -> Unit)? = null,
        onContinueBudget: ((Int) -> Flow<AgentStreamEvent>)? = null
    ) {
        this.switchModelCallback = onSwitchModel
        this.switchChatModeCallback = onSwitchChatMode
        this.sessionActionHandler = onSessionAction
        this.continueBudgetCallback = onContinueBudget
        this.scope = scope
        this.stopCallback = onStop
        this.messageCallback = { rawMessage, images ->
            val turnId = currentTurnId ?: "turn_${System.currentTimeMillis()}".also { currentTurnId = it }
            logger.info("[MessageCallback] received, turnId=$turnId, length=${rawMessage.length}, images=${images.size}")

            val references = fileResolver.resolveReferences(rawMessage)
            val contextInjection = fileResolver.formatReferencesForContext(references)
            val cleanMessage = fileResolver.stripReferences(rawMessage)
            val baseMessage = if (contextInjection.isNotEmpty()) {
                "$cleanMessage\n$contextInjection"
            } else {
                cleanMessage
            }

            // 把图片附件追加到消息文本(多数多模态模型接受 markdown image 或 base64 inline)
            val message = if (images.isNotEmpty()) {
                val imageRefs = images.joinToString("\n") { img ->
                    if (img.dataUrl.startsWith("data:")) "![${img.name}](${img.dataUrl})" else "![${img.name}](${img.dataUrl})"
                }
                if (baseMessage.isBlank()) imageRefs else "$baseMessage\n\n$imageRefs"
            } else {
                baseMessage
            }

            if (references.isNotEmpty()) {
                sendToJS(
                    mapOf(
                        "type" to "file_references",
                        "turnId" to turnId,
                        "references" to references.map {
                            mapOf(
                                "name" to it.name,
                                "path" to it.relativePath,
                                "language" to it.language,
                            )
                        }
                    ))
            }

            currentCollectJob?.cancel()
            currentCollectJob = scope.launch {
                var turnStarted = false
                var meaningfulEventReceived = false
                val startTime = System.currentTimeMillis()
                try {
                    onSendMessage(message, images).collect { rawEvent ->
                        if (!turnStarted) {
                            turnStarted = true
                            logger.info("[MessageCallback] first event after ${System.currentTimeMillis() - startTime}ms, type=${rawEvent::class.simpleName}")
                        }
                        if (rawEvent !is AgentStreamEvent.Done) meaningfulEventReceived = true
                        routeEvent(rawEvent, turnId)
                    }
                    if (!turnStarted) {
                        sendToJS(
                            mapOf(
                                "type" to "error",
                                "turnId" to turnId,
                                "message" to "未收到AI响应。请检查:1) 是否已配置API Key;2) 网络连接是否正常。",
                            )
                        )
                    } else if (!meaningfulEventReceived) {
                        sendToJS(
                            mapOf(
                                "type" to "error",
                                "turnId" to turnId,
                                "message" to "AI 未返回有效内容,可能是上下文异常或请求被跳过",
                            )
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    logger.info("[MessageCallback] user cancelled")
                } catch (e: Throwable) {
                    logger.error("[MessageCallback] error", e)
                    sendToJS(
                        mapOf(
                            "type" to "error",
                            "turnId" to turnId,
                            "message" to (e.message ?: "Unknown error"),
                        )
                    )
                }
            }
        }
    }

    /**
     * 发送消息到前端
     * 用 EventRouter.toJsonString 把 Map 序列化为 JSON 字符串
     */
    fun sendToJS(message: Map<String, Any?>) {
        try {
            val json = mapToJsonString(message)
            val script = """
                (function() {
                    if (typeof window.onJavaMessage === 'function') {
                        window.onJavaMessage($json);
                    } else {
                        console.error('[JCEF] window.onJavaMessage is not defined');
                    }
                })();
            """.trimIndent()
            val cefBrowser = browser?.cefBrowser
            if (cefBrowser == null) {
                logger.warn("[sendToJS] browser not available")
                return
            }
            if (!isBridgeReady) {
                pendingMessages.add(script)
                return
            }
            cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
        } catch (e: Exception) {
            logger.error("[sendToJS] failed to serialize: $message", e)
        }
    }

    fun setModelLabel(model: String, provider: String = "") {
        sendToJS(mapOf("type" to "set_model", "model" to model, "provider" to provider))
    }

    fun setAvailableModels(models: List<Map<String, Any>>) {
        sendToJS(mapOf("type" to "set_models", "models" to models))
    }

    fun setTheme(theme: String) {
        sendToJS(mapOf("type" to "set_theme", "theme" to theme))
    }

    fun addArtifact(artifactId: String, title: String, language: String, content: String) {
        sendToJS(
            mapOf(
                "type" to "artifact",
                "artifactId" to artifactId,
                "title" to title,
                "language" to language,
                "content" to content,
            )
        )
    }

    fun clear() {
        sendToJS(mapOf("type" to "clear"))
    }

    fun getCurrentChatMode(): ChatMode? = currentChatMode
    fun setCurrentChatMode(mode: ChatMode?) {
        currentChatMode = mode
    }

    fun sendSessions(sessions: List<Map<String, Any>>) {
        sendToJS(mapOf("type" to "set_sessions", "sessions" to sessions))
    }

    fun notifySessionCreated(session: Map<String, Any>) {
        sendToJS(mapOf("type" to "session_created", "session" to session))
    }

    fun notifySessionSwitched(sessionId: String) {
        sendToJS(mapOf("type" to "session_switched", "sessionId" to sessionId))
    }

    fun notifySessionDeleted(sessionId: String) {
        sendToJS(mapOf("type" to "session_deleted", "sessionId" to sessionId))
    }

    fun notifySessionRenamed(sessionId: String, name: String) {
        sendToJS(mapOf("type" to "session_renamed", "sessionId" to sessionId, "name" to name))
    }

    fun loadHistory(messages: List<Map<String, String>>) {
        sendToJS(mapOf("type" to "load_history", "messages" to messages))
    }

    fun requestSessionsFromJS() {
        val script = """
            (function() {
                if (typeof window.requestSessions === 'function') window.requestSessions();
            })();
        """.trimIndent()
        val cefBrowser = browser?.cefBrowser
        if (cefBrowser != null && isBridgeReady) {
            cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
        }
    }

    fun dispose() {
        scope?.cancel()
        browser?.dispose()
    }

    // ===== IDE Integration =====

    private fun applyArtifactToEditor(artifactId: String, content: String) {
        project?.let { p ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(p).selectedTextEditor
                editor?.document?.let { doc ->
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(p) {
                        val caret = editor.caretModel.primaryCaret
                        doc.insertString(caret.offset, content)
                    }
                }
            }
        }
    }

    private fun createFileFromArtifact(title: String, content: String) {
        project?.let { p ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                logger.info("Create file from artifact: $title")
            }
        }
    }

    // ===== Offline health check =====

    private fun scheduleLibraryCheck(cefBrowser: org.cef.browser.CefBrowser) {
        Timer(5000) {
            cefBrowser.executeJavaScript(
                """
                (function() {
                    if (typeof window.checkCriticalLibraries === 'function' && !window.checkCriticalLibraries()) {
                        if (typeof window.showOfflineWarning === 'function') window.showOfflineWarning();
                    }
                })();
                """.trimIndent(),
                cefBrowser.url ?: "",
                0
            )
        }.apply { isRepeats = false; start() }
    }

    private fun reloadBrowser() {
        browser?.cefBrowser?.reload()
    }

    // ===== Fallback =====

    private fun createFallbackPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(
                javax.swing.JLabel(
                    "JCEF is not supported. Please use JetBrains Runtime with JCEF.",
                    javax.swing.SwingConstants.CENTER,
                ),
                BorderLayout.CENTER,
            )
        }
    }

    private fun createFallbackHTML(): String {
        return """
            <html>
            <body style="display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:sans-serif;">
                <div style="text-align:center;color:#666;">
                    <h2>CodeSage</h2>
                    <p>Failed to load chat interface.</p>
                    <p>Please restart with JetBrains Runtime (JCEF).</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
