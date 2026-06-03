package com.codesage.ide.ui.web

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.core.ChatMode
import com.codesage.agent.core.EventHistory
import com.codesage.agent.core.EventBatchEmitter
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * JCEF Web UI 聊天面板
 *
 * 使用 JBCefBrowser 嵌入现代 Web 界面，替代传统 Swing UI。
 * 通过 JS Bridge 实现 Kotlin 与 JavaScript 的双向通信。
 */
class JCEFChatPanel(
    private val project: Project?
) : JPanel(BorderLayout()) {

    private val logger = Logger.getLogger<JCEFChatPanel>()
    private val fileResolver = FileReferenceResolver(project)

    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null
    private var scope: CoroutineScope? = null
    private var currentCollectJob: kotlinx.coroutines.Job? = null

    private var currentTurnId: String? = null

    /**
     * T1.5 修复：当前用户选中的 ChatMode。
     *
     * 初始为 `null` = 用户从未选过 → backend 会走 `ChatModeRouter.suggestChatMode` 推测。
     * 后续通过 `switch_chat_mode` JS 消息更新。
     */
    private var currentChatMode: ChatMode? = null
    private var messageCallback: ((String) -> Unit)? = null
    private var stopCallback: (() -> Unit)? = null
    private var switchModelCallback: ((String) -> Unit)? = null
    private var switchChatModeCallback: ((ChatMode) -> Unit)? = null
    private var sessionActionHandler: ((String, Map<String, Any>) -> Unit)? = null
    private var continueBudgetCallback: ((Int) -> Flow<AgentStreamEvent>)? = null

    private val pendingMessages = mutableListOf<String>()

    // 事件历史记录（调试用）
    val eventHistory = EventHistory()

    // 事件批量发射器（性能优化）
    private val batchEmitter = EventBatchEmitter()

    // 事件去重：最近 N 秒内已发送的事件类型缓存
    private val recentEventCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dedupWindowMs = 500L

    @Volatile
    private var isBridgeReady = false

    init {
        println("[CodeSage] JCEFChatPanel init started, JBCefApp.isSupported()=${JBCefApp.isSupported()}")
        if (JBCefApp.isSupported()) {
            initializeBrowser()
        } else {
            logger.warn("JCEF is not supported in this environment")
            add(createFallbackPanel(), BorderLayout.CENTER)
        }
        println("[CodeSage] JCEFChatPanel init completed")
    }

    private fun initializeBrowser() {
        println("[CodeSage] initializeBrowser started")
        try {
            val newBrowser = JBCefBrowser()
            browser = newBrowser
            println("[CodeSage] JBCefBrowser created")

            // 加载本地 HTML 文件
            // JCEF 不支持 jar: 协议，需要将资源读取为字符串后通过 loadHTML 加载
            val rawHtmlContent = javaClass.classLoader.getResourceAsStream("webui/chat.html")?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
            val htmlContent = rawHtmlContent?.let { ResourceInliner.inlineResources(it) }
            println("[CodeSage] HTML content loaded: ${htmlContent != null}")
            if (htmlContent != null) {
                newBrowser.loadHTML(htmlContent, "http://codesage.local/chat.html")
            } else {
                newBrowser.loadHTML(createFallbackHTML())
            }

            // 创建 JS Query 用于接收前端消息
            jsQuery = JBCefJSQuery.create(newBrowser).apply {
                addHandler { message ->
                    handleJSMessage(message)
                    JBCefJSQuery.Response("ok")
                }
            }
            println("[CodeSage] JBCefJSQuery created")

            // 注入 JS Bridge
            newBrowser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    cefBrowser: org.cef.browser.CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean
                ) {
                    if (!isLoading) {
                        injectJSBridge()
                    }
                }
            }, newBrowser.cefBrowser)

            add(newBrowser.component, BorderLayout.CENTER)
            println("[CodeSage] JCEF browser initialized")
            logger.info("JCEF browser initialized")
        } catch (e: Exception) {
            println("[CodeSage] Failed to initialize JCEF browser: ${e.message}")
            e.printStackTrace()
            logger.error("Failed to initialize JCEF browser", e)
            add(createFallbackPanel(), BorderLayout.CENTER)
        }
    }

    /**
     * 注入 JavaScript Bridge，让前端可以调用 Kotlin 代码
     */
    private fun injectJSBridge() {
        println("[CodeSage] injectJSBridge called")
        val query = jsQuery ?: run {
            println("[CodeSage] injectJSBridge: jsQuery is null")
            return
        }
        val cefBrowser = browser?.cefBrowser ?: run {
            println("[CodeSage] injectJSBridge: cefBrowser is null")
            return
        }
        println("[CodeSage] injectJSBridge: funcName=${query.funcName}")

        // 先检查函数是否存在
        val checkCode = """
            (function() {
                var fn = window['${query.funcName}'] || ${query.funcName};
                console.log('[CodeSage] JBCefJSQuery func check: ${query.funcName} exists=' + (typeof fn === 'function'));
            })();
        """.trimIndent()
        cefBrowser.executeJavaScript(checkCode, cefBrowser.url ?: "", 0)

        val bridgeCode = """
            (function() {
                var queryFunc = window['${query.funcName}'] || ${query.funcName};
                if (typeof queryFunc !== 'function') {
                    console.error('[CodeSage] JBCefJSQuery function not found: ${query.funcName}');
                    return;
                }
                window.javaBridge = {
                    sendMessage: function(json) {
                        console.log('[CodeSage] javaBridge.sendMessage called with: ' + json.substring(0, 100));
                        queryFunc({
                            request: '' + json,
                            onSuccess: function(response) {
                                console.log('[CodeSage] JBCefJSQuery onSuccess: ' + response);
                            },
                            onFailure: function(error_code, error_message) {
                                console.error('[JBCefJSQuery] Error ' + error_code + ': ' + error_message);
                            }
                        });
                    }
                };
                if (window.onBridgeReady) {
                    window.onBridgeReady();
                }
                console.log('[CodeSage] JS Bridge initialized, javaBridge=' + typeof window.javaBridge);
            })();
        """.trimIndent()

        cefBrowser.executeJavaScript(bridgeCode, cefBrowser.url ?: "", 0)
        println("[CodeSage] JS Bridge injected")
        logger.info("JS Bridge injected")

        isBridgeReady = true
        flushPendingMessages()

        // 注入完成后启动关键库加载检测
        scheduleLibraryCheck(cefBrowser)
    }

    private fun flushPendingMessages() {
        if (pendingMessages.isEmpty()) return
        val cefBrowser = browser?.cefBrowser ?: return
        logger.info("[JCEFChatPanel] Flushing ${pendingMessages.size} pending messages")
        val copy = pendingMessages.toList()
        pendingMessages.clear()
        copy.forEach { script ->
            cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
        }
    }

    /**
     * 处理来自 JavaScript 的消息
     */
    private fun handleJSMessage(message: String) {
        logger.info("[JS→Kotlin] Received message: $message")
        try {
            val json = Json.parseToJsonElement(message)
            val type = json.jsonObject["type"]?.jsonPrimitive?.content ?: return

            when (type) {
                "send_message" -> {
                    val content = json.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                    val clientTurnId = json.jsonObject["turnId"]?.jsonPrimitive?.content
                    // T1.5 修复：可从消息体中携带 chatMode（可选）。
                    // 如果 JS 发送了 chatMode 字段，优先使用该值，否则使用 currentChatMode。
                    val messageChatMode = json.jsonObject["chatMode"]?.jsonPrimitive?.content
                        ?.let { ChatMode.fromString(it) }
                        ?: currentChatMode
                    if (messageChatMode != null) {
                        currentChatMode = messageChatMode
                    }
                    if (!clientTurnId.isNullOrBlank()) {
                        currentTurnId = clientTurnId
                        logger.info("[JS→Kotlin] send_message callback invoked, content length=${content.length}, clientTurnId=$clientTurnId, chatMode=$currentChatMode")
                    } else {
                        logger.info("[JS→Kotlin] send_message callback invoked, content length=${content.length}, no clientTurnId, chatMode=$currentChatMode")
                    }
                    if (messageCallback == null) {
                        logger.error("[JS→Kotlin] messageCallback is null! Cannot process message.")
                    } else if (content.isBlank()) {
                        logger.warn("[JS→Kotlin] Received empty message content, ignoring")
                    } else {
                        messageCallback?.invoke(content)
                    }
                }

                "stop_generation" -> {
                    stopCallback?.invoke()
                }

                "clear_session" -> {
                    // 处理清空会话
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

                "regenerate" -> {
                    // 处理重新生成
                }

                "file_search" -> {
                    val query = json.jsonObject["query"]?.jsonPrimitive?.content ?: ""
                    logger.info("[JS→Kotlin] file_search query='$query'")
                    val suggestions = fileResolver.searchFiles(query)
                    logger.info("[JS→Kotlin] file_search returned ${suggestions.size} suggestions")
                    sendToJS(
                        mapOf(
                            "type" to "file_suggestions",
                            "query" to query,
                            "suggestions" to suggestions.map {
                                mapOf(
                                    "name" to it.name,
                                    "path" to it.path,
                                    "relativePath" to it.relativePath,
                                    "icon" to it.icon
                                )
                            }
                        ))
                }

                "switch_model" -> {
                    val model = json.jsonObject["model"]?.jsonPrimitive?.content ?: ""
                    if (model.isNotBlank()) {
                        logger.info("[JS→Kotlin] switch_model to $model")
                        switchModelCallback?.invoke(model)
                    }
                }

                "switch_chat_mode" -> {
                    val modeStr = json.jsonObject["mode"]?.jsonPrimitive?.content ?: ""
                    val mode = ChatMode.fromString(modeStr)
                    if (modeStr.isNotBlank() && mode != null) {
                        currentChatMode = mode
                        logger.info("[JS→Kotlin] switch_chat_mode to $mode")
                        switchChatModeCallback?.invoke(mode)
                    }
                }

                "reload_browser" -> {
                    reloadBrowser()
                }

                "new_session" -> {
                    sessionActionHandler?.invoke("new_session", emptyMap())
                }

                "switch_session" -> {
                    val sessionId = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    if (sessionId.isNotBlank()) {
                        sessionActionHandler?.invoke("switch_session", mapOf("sessionId" to sessionId))
                    }
                }

                "delete_session" -> {
                    val sessionId = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    if (sessionId.isNotBlank()) {
                        sessionActionHandler?.invoke("delete_session", mapOf("sessionId" to sessionId))
                    }
                }

                "rename_session" -> {
                    val sessionId = json.jsonObject["sessionId"]?.jsonPrimitive?.content ?: ""
                    val name = json.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    if (sessionId.isNotBlank() && name.isNotBlank()) {
                        sessionActionHandler?.invoke("rename_session", mapOf("sessionId" to sessionId, "name" to name))
                    }
                }

                "request_sessions" -> {
                    sessionActionHandler?.invoke("request_sessions", emptyMap())
                }

                "continue_task" -> {
                    val extraIterations =
                        json.jsonObject["extraIterations"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                    val turnId = json.jsonObject["turnId"]?.jsonPrimitive?.content ?: currentTurnId
                    logger.info("[JS→Kotlin] continue_task invoked, extraIterations=$extraIterations, turnId=$turnId")
                    val callback = continueBudgetCallback
                    if (callback == null) {
                        logger.error("[JS→Kotlin] continueBudgetCallback is null! Cannot continue task.")
                        sendToJS(
                            mapOf(
                                "type" to "error",
                                "turnId" to (turnId ?: ""),
                                "message" to "继续执行功能未初始化"
                            )
                        )
                    } else {
                        currentCollectJob?.cancel()
                        currentCollectJob = scope?.launch {
                            var turnStarted = false
                            var meaningfulEventReceived = false
                            var startTime = System.currentTimeMillis()
                            try {
                                callback(extraIterations).collect { event ->
                                    if (!turnStarted) {
                                        turnStarted = true
                                        logger.info("[ContinueCallback] First stream event received after ${System.currentTimeMillis() - startTime}ms")
                                    }
                                    if (event !is AgentStreamEvent.Done) {
                                        meaningfulEventReceived = true
                                    }
                                    when (event) {
                                        is AgentStreamEvent.TextDelta -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "text_delta",
                                                    "turnId" to (turnId ?: ""),
                                                    "delta" to event.delta
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.Thinking -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "thinking_update",
                                                    "turnId" to (turnId ?: ""),
                                                    "message" to event.message
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.ToolCallStart -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "tool_call_start",
                                                    "turnId" to (turnId ?: ""),
                                                    "toolId" to event.toolCall.id,
                                                    "toolName" to event.toolCall.name,
                                                    "summary" to "Running ${event.toolCall.name}..."
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.ToolCallResult -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "tool_call_complete",
                                                    "turnId" to (turnId ?: ""),
                                                    "toolId" to event.toolCallId,
                                                    "success" to event.success,
                                                    "result" to event.result
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.BudgetExhausted -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "budget_exhausted",
                                                    "turnId" to (turnId ?: ""),
                                                    "reason" to event.reason,
                                                    "consumedIterations" to event.consumedIterations,
                                                    "consumedTokens" to event.consumedTokens,
                                                    "elapsedSeconds" to event.elapsedSeconds,
                                                    "allowContinue" to event.allowContinue
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.BudgetExtended -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "budget_extended",
                                                    "turnId" to (turnId ?: ""),
                                                    "extraIterations" to event.extraIterations,
                                                    "newRemainingIterations" to event.newRemainingIterations
                                                )
                                            )
                                        }

                                        is AgentStreamEvent.Error -> {
                                            sendToJS(
                                                mapOf(
                                                    "type" to "error",
                                                    "turnId" to (turnId ?: ""),
                                                    "message" to event.message
                                                )
                                            )
                                        }

                                        AgentStreamEvent.Done -> {
                                            sendToJS(mapOf("type" to "turn_complete", "turnId" to (turnId ?: "")))
                                        }

                                        else -> { /* ignore other events in continuation */
                                        }
                                    }
                                }
                                if (!turnStarted) {
                                    sendToJS(
                                        mapOf(
                                            "type" to "error",
                                            "turnId" to (turnId ?: ""),
                                            "message" to "继续执行未收到任何响应"
                                        )
                                    )
                                }
                            } catch (e: CancellationException) {
                                logger.info("[ContinueCallback] User cancelled the continuation")
                            } catch (e: Throwable) {
                                logger.error("[ContinueCallback] Error in continuation flow", e)
                                sendToJS(
                                    mapOf(
                                        "type" to "error",
                                        "turnId" to (turnId ?: ""),
                                        "message" to (e.message ?: "继续执行时发生未知错误")
                                    )
                                )
                            }
                        }
                    }
                }

                else -> logger.debug("Unknown message type: $type")
            }
        } catch (e: Exception) {
            logger.error("Failed to handle JS message: $message", e)
        }
    }

    /**
     * 发送消息到 JavaScript 前端
     */
    fun sendToJS(message: Map<String, Any>) {
        try {
            val jsonObj = mapToJsonElement(message)
            val json = jsonObj.toString()
            logger.info("[sendToJS] Sending message type=${message["type"]}, json length=${json.length}")
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
                logger.warn("[sendToJS] Browser not available, message dropped")
                return
            }
            if (!isBridgeReady) {
                pendingMessages.add(script)
                logger.info("[sendToJS] Bridge not ready, message queued. Pending count=${pendingMessages.size}")
                return
            }
            cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
        } catch (e: Exception) {
            logger.error("[sendToJS] Failed to serialize message: $message", e)
        }
    }

    /**
     * 将 Kotlin Map 转换为 JsonElement（支持嵌套 Map 和 List）
     */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun mapToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            is Map<*, *> -> JsonObject(value.mapKeys { it.key.toString() }.mapValues { mapToJsonElement(it.value) })
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            null -> JsonPrimitive(null)
            else -> JsonPrimitive(value.toString())
        }
    }

    // ===== Public API =====

    fun initialize(
        scope: CoroutineScope,
        onSendMessage: (String) -> Flow<AgentStreamEvent>,
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
        this.messageCallback = { rawMessage ->
            val turnId = currentTurnId ?: "turn_${System.currentTimeMillis()}".also { currentTurnId = it }
            logger.info("[MessageCallback] Received rawMessage, turnId=$turnId, length=${rawMessage.length}")

            // 解析文件引用，注入上下文
            val references = fileResolver.resolveReferences(rawMessage)
            logger.info("[MessageCallback] Resolved ${references.size} file references")
            val contextInjection = fileResolver.formatReferencesForContext(references)
            val cleanMessage = fileResolver.stripReferences(rawMessage)

            val message = if (contextInjection.isNotEmpty()) {
                "$cleanMessage\n$contextInjection"
            } else {
                cleanMessage
            }
            logger.info("[MessageCallback] Final message length=${message.length}, calling onSendMessage...")

            // 通知前端显示已解析的引用文件
            if (references.isNotEmpty()) {
                sendToJS(
                    mapOf(
                        "type" to "file_references",
                        "turnId" to turnId,
                        "references" to references.map {
                            mapOf(
                                "name" to it.name,
                                "path" to it.relativePath,
                                "language" to it.language
                            )
                        }
                    ))
            }

            currentCollectJob?.cancel()
            currentCollectJob = scope.launch {
                var turnStarted = false
                var thinkingStarted = false
                var meaningfulEventReceived = false
                var startTime = System.currentTimeMillis()
                try {
                    logger.info("[MessageCallback] Launching coroutine to collect stream events...")

                    onSendMessage(message).collect { rawEvent ->
                        // 记录事件历史
                        eventHistory.record(rawEvent, turnId)

                        // 去重检查
                        val event = if (shouldDedup(rawEvent)) {
                            rawEvent
                        } else {
                            rawEvent
                        }

                        if (!turnStarted) {
                            turnStarted = true
                            logger.info("[MessageCallback] First stream event received after ${System.currentTimeMillis() - startTime}ms, eventType=${event::class.simpleName}")
                        }
                        if (event !is AgentStreamEvent.Done) {
                            meaningfulEventReceived = true
                        }
                        when (event) {
                            is AgentStreamEvent.TextDelta -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "text_delta",
                                        "turnId" to turnId,
                                        "delta" to event.delta
                                    )
                                )
                            }

                            // T1.5 修复：转发 ChatMode 建议事件给 UI
                            is AgentStreamEvent.ModeSuggestion -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "mode_suggestion",
                                        "turnId" to turnId,
                                        "effective" to event.effective.name,
                                        "suggestion" to event.suggestion.name,
                                        "userExplicit" to event.userExplicit
                                    )
                                )
                            }

                            is AgentStreamEvent.Thinking -> {
                                if (!thinkingStarted) {
                                    thinkingStarted = true
                                    sendToJS(
                                        mapOf(
                                            "type" to "thinking_start",
                                            "turnId" to turnId,
                                            "message" to event.message
                                        )
                                    )
                                } else {
                                    sendToJS(
                                        mapOf(
                                            "type" to "thinking_update",
                                            "turnId" to turnId,
                                            "message" to event.message
                                        )
                                    )
                                }
                            }

                            is AgentStreamEvent.ToolCallStart -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_start",
                                        "turnId" to turnId,
                                        "toolId" to event.toolCall.id,
                                        "toolName" to event.toolCall.name,
                                        "summary" to "Running ${event.toolCall.name}..."
                                    )
                                )
                            }

                            is AgentStreamEvent.ToolCallResult -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_complete",
                                        "turnId" to turnId,
                                        "toolId" to event.toolCallId,
                                        "success" to event.success,
                                        "result" to event.result
                                    )
                                )
                            }

                            is AgentStreamEvent.ToolCallError -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_error",
                                        "turnId" to turnId,
                                        "toolId" to event.toolCallId,
                                        "error" to event.error
                                    )
                                )
                            }

                            is AgentStreamEvent.SubAgentStart -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_start",
                                        "turnId" to turnId,
                                        "toolId" to event.sessionId,
                                        "toolName" to "subagent",
                                        "summary" to event.taskDescription
                                    )
                                )
                            }

                            is AgentStreamEvent.SubAgentComplete -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_complete",
                                        "turnId" to turnId,
                                        "toolId" to event.sessionId,
                                        "success" to event.success,
                                        "result" to event.output
                                    )
                                )
                            }

                            is AgentStreamEvent.SubAgentProgress -> {
                                // Progress updates are handled as thinking updates
                            }

                            is AgentStreamEvent.ToolCallDelta -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_call_delta",
                                        "turnId" to turnId,
                                        "toolId" to event.toolCallId,
                                        "toolName" to event.toolName,
                                        "delta" to event.delta
                                    )
                                )
                            }

                            is AgentStreamEvent.ToolConfirmationNeeded -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "tool_confirmation_needed",
                                        "turnId" to turnId,
                                        "toolId" to event.toolCallId,
                                        "toolName" to event.toolName,
                                        "arguments" to event.arguments,
                                        "reason" to event.reason
                                    )
                                )
                            }

                            is AgentStreamEvent.PlanGenerated -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "plan_generated",
                                        "turnId" to turnId,
                                        "planId" to event.planId,
                                        "description" to event.description,
                                        "steps" to event.steps.map {
                                            mapOf(
                                                "id" to it.id,
                                                "description" to it.description,
                                                "dependsOn" to it.dependsOn
                                            )
                                        }
                                    )
                                )
                            }

                            is AgentStreamEvent.PlanApproved -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "plan_approved",
                                        "turnId" to turnId,
                                        "planId" to event.planId
                                    )
                                )
                            }

                            is AgentStreamEvent.PlanModified -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "plan_modified",
                                        "turnId" to turnId,
                                        "planId" to event.planId,
                                        "steps" to event.steps.map {
                                            mapOf(
                                                "id" to it.id,
                                                "description" to it.description,
                                                "dependsOn" to it.dependsOn
                                            )
                                        }
                                    )
                                )
                            }

                            is AgentStreamEvent.PlanRejected -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "plan_rejected",
                                        "turnId" to turnId,
                                        "planId" to event.planId,
                                        "reason" to event.reason
                                    )
                                )
                            }

                            is AgentStreamEvent.ContextCompressed -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "context_compressed",
                                        "turnId" to turnId,
                                        "originalTokens" to event.originalTokens,
                                        "compressedTokens" to event.compressedTokens,
                                        "strategy" to event.strategy
                                    )
                                )
                            }

                            is AgentStreamEvent.SessionMigrated -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "session_migrated",
                                        "turnId" to turnId,
                                        "oldSessionId" to event.oldSessionId,
                                        "newSessionId" to event.newSessionId,
                                        "messageCount" to event.messageCount
                                    )
                                )
                            }

                            is AgentStreamEvent.BudgetStatus -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "budget_status",
                                        "turnId" to turnId,
                                        "status" to event.status,
                                        "remainingIterations" to event.remainingIterations,
                                        "remainingTokens" to event.remainingTokens,
                                        "remainingSeconds" to event.remainingSeconds,
                                        "usagePercent" to event.usagePercent
                                    )
                                )
                            }

                            is AgentStreamEvent.BudgetExhausted -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "budget_exhausted",
                                        "turnId" to turnId,
                                        "reason" to event.reason,
                                        "consumedIterations" to event.consumedIterations,
                                        "consumedTokens" to event.consumedTokens,
                                        "elapsedSeconds" to event.elapsedSeconds,
                                        "allowContinue" to event.allowContinue
                                    )
                                )
                            }

                            is AgentStreamEvent.BudgetExtended -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "budget_extended",
                                        "turnId" to turnId,
                                        "extraIterations" to event.extraIterations,
                                        "newRemainingIterations" to event.newRemainingIterations
                                    )
                                )
                            }

                            is AgentStreamEvent.Error -> {
                                sendToJS(
                                    mapOf(
                                        "type" to "error",
                                        "turnId" to turnId,
                                        "message" to event.message
                                    )
                                )
                            }

                            AgentStreamEvent.Done -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                logger.info("[MessageCallback] Stream complete, elapsed=${elapsed}ms")
                                sendToJS(
                                    mapOf(
                                        "type" to "thinking_complete",
                                        "turnId" to turnId,
                                        "elapsedMs" to elapsed
                                    )
                                )
                                sendToJS(
                                    mapOf(
                                        "type" to "turn_complete",
                                        "turnId" to turnId
                                    )
                                )
                            }
                        }
                    }
                    if (!turnStarted) {
                        logger.warn("[MessageCallback] No stream events received within ${System.currentTimeMillis() - startTime}ms, sending error to UI")
                        sendToJS(
                            mapOf(
                                "type" to "error",
                                "turnId" to turnId,
                                "message" to "未收到AI响应。请检查：1) 是否已配置API Key；2) 网络连接是否正常。"
                            )
                        )
                    } else if (!meaningfulEventReceived) {
                        logger.warn("[MessageCallback] Stream completed with only Done event (no text/error/tools) after ${System.currentTimeMillis() - startTime}ms")
                        sendToJS(
                            mapOf(
                                "type" to "error",
                                "turnId" to turnId,
                                "message" to "AI 未返回有效内容，可能是上下文异常或请求被跳过"
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    logger.info("[MessageCallback] User cancelled the turn")
                } catch (e: Throwable) {
                    logger.error("[MessageCallback] Error in message processing flow", e)
                    sendToJS(
                        mapOf(
                            "type" to "error",
                            "turnId" to turnId,
                            "message" to (e.message ?: "Unknown error")
                        )
                    )
                }
            }
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
                "content" to content
            )
        )
    }

    fun clear() {
        sendToJS(mapOf("type" to "clear"))
    }

    /**
     * T1.5 修复：获取当前选中的 ChatMode
     *
     * 返回 `null` 表示用户从未显式选择过任何 mode，后端会走 `ChatModeRouter.suggestChatMode` 推测。
     */
    fun getCurrentChatMode(): ChatMode? = currentChatMode

    /**
     * T1.5 修复：手动设置 ChatMode（供 UI 初始化或测试使用）
     */
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
                if (typeof window.requestSessions === 'function') {
                    window.requestSessions();
                }
            })();
        """.trimIndent()
        val cefBrowser = browser?.cefBrowser
        if (cefBrowser != null && isBridgeReady) {
            cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
        }
    }

    fun dispose() {
        scope?.cancel()
        batchEmitter.shutdown()
        browser?.dispose()
    }

    /**
     * 事件去重：对高频重复事件进行节流
     */
    private fun shouldDedup(event: AgentStreamEvent): Boolean {
        val key = event::class.simpleName ?: return true
        val now = System.currentTimeMillis()
        val last = recentEventCache[key]
        return if (last != null && now - last < dedupWindowMs) {
            false // 重复，跳过
        } else {
            recentEventCache[key] = now
            true
        }
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
                // 简化实现：在实际项目中应弹出对话框让用户选择路径
                logger.info("Create file from artifact: $title")
            }
        }
    }

    // ===== Offline Health Check =====

    private fun scheduleLibraryCheck(cefBrowser: org.cef.browser.CefBrowser) {
        javax.swing.Timer(5000) {
            cefBrowser.executeJavaScript(
                """
                (function() {
                    var ok = window.checkCriticalLibraries ? window.checkCriticalLibraries() : false;
                    if (!ok) {
                        window.showOfflineWarning();
                    }
                    return ok;
                })();
                """.trimIndent(),
                cefBrowser.url ?: "",
                0
            )
        }.apply { isRepeats = false; start() }
    }

    private fun showOfflineWarning() {
        val cefBrowser = browser?.cefBrowser ?: return
        cefBrowser.executeJavaScript(
            "if (window.showOfflineWarning) window.showOfflineWarning();",
            cefBrowser.url ?: "",
            0
        )
    }

    private fun reloadBrowser() {
        browser?.cefBrowser?.reload()
    }

    // ===== Fallback =====

    private fun createFallbackPanel(): JPanel {
        return javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(
                javax.swing.JLabel(
                    "JCEF is not supported. Please use JetBrains Runtime with JCEF.",
                    javax.swing.SwingConstants.CENTER
                ), java.awt.BorderLayout.CENTER
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
