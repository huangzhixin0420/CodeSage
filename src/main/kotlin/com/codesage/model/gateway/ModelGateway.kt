package com.codesage.model.gateway

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.adapter.StreamEvent
import com.codesage.model.adapter.StreamEventNormalizer
import com.codesage.model.dto.*
import com.codesage.model.registry.ModelRegistry
import com.codesage.shared.exceptions.AppException
import com.codesage.shared.exceptions.NetworkException
import com.codesage.shared.exceptions.ModelNotFoundException
import com.codesage.shared.exceptions.UnsupportedFeatureException
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 模型网关
 * 统一的模型调用入口
 */
open class ModelGateway(
    private val registry: ModelRegistry = ModelRegistry.getInstance()
) {
    private val logger = Logger.getLogger<ModelGateway>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 当前 in-flight HTTP Call 句柄（线程安全）。
     * 用户按 Cancel 时，AgentCore.interrupt() 调 cancelCurrentRequest() 关掉这个 Call，
     * 阻塞中的 OkHttp read 立刻抛 InterruptedIOException，子 agent / 父 turn 立即感知。
     */
    private val currentCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)

    /**
     * 取消当前 in-flight HTTP 请求。
     * 线程安全：可以跨线程调用（UI 线程 cancel 协程中的请求）。
     * 没在 flight 时为 no-op。
     */
    fun cancelCurrentRequest() {
        currentCall.getAndSet(null)?.cancel()
    }

    /**
     * 同步聊天请求
     */
    open suspend fun chat(request: ChatRequest): Result<ChatResponse> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        logger.info(
            "[Gateway.chat] → ${request.model} | " +
                    "messages=${request.messages.size}, " +
                    "tools=${request.tools?.size ?: 0}, " +
                    "stream=${request.stream}, " +
                    "promptChars=${request.messages.sumOf { it.content.length }}"
        )
        try {
            val adapter = registry.getAdapterForModel(request.model)
            if (adapter == null) {
                logger.error("[Gateway.chat] No adapter registered for model: ${request.model}")
                logger.info("[Gateway.chat] Available models: ${registry.listAvailableModels().map { it.id }}")
                return@withContext Result.failure(
                    ModelNotFoundException("Model not found: ${request.model}")
                )
            }
            logger.info("[Gateway.chat] adapter=${adapter.providerName} endpoint=${adapter.getChatEndpoint()}")

            val vendorRequest = adapter.toVendorRequest(request)
            logger.info(
                "[Gateway.chat] → ${request.model} | " +
                        "requestSize=${vendorRequest.length}B, " +
                        "bodyPreview=${vendorRequest.take(500)}"
            )

            val response = executeRequest(adapter, vendorRequest, request.stream)
            logger.info(
                "[Gateway.chat] ← ${request.model} | " +
                        "status=200, " +
                        "responseSize=${response.length}B, " +
                        "durationMs=${System.currentTimeMillis() - startMs}, " +
                        "bodyPreview=${response.take(500)}"
            )

            Result.success(adapter.fromVendorResponse(response))
        } catch (e: AppException) {
            logger.error(
                "[Gateway.chat] ✗ ${request.model} | " +
                        "${e.javaClass.name}: ${e.message} | " +
                        "durationMs=${System.currentTimeMillis() - startMs}"
            )
            Result.failure(e)
        } catch (e: Exception) {
            logger.error(
                "[Gateway.chat] ✗ ${request.model} | " +
                        "${e.javaClass.name}: ${e.message} | " +
                        "durationMs=${System.currentTimeMillis() - startMs}",
                e
            )
            Result.failure(NetworkException("Chat request failed: ${e.message}"))
        }
    }

    /**
     * 流式聊天请求
     * 支持 stream=true + tools 参数，若模型不支持流式则自动回退到非流式并包装为 Flow
     */
    /**
     * 2026-06: 旧 API — 返回 Flow<StreamChunk> 的版本,供既有调用方使用(向后兼容)。
     * 新代码应使用 [chatStream](返回 Flow<StreamEvent>)。
     */
    open fun chatStreamLegacy(request: ChatRequest): Flow<StreamChunk> = flow {
        chatStream(request).collect { event ->
            for (chunk in streamEventToChunk(event)) {
                emit(chunk)
            }
        }
    }

    /**
     * 2026-06: 切到 [Flow]<[StreamEvent]> 的新签名(替代旧的 `Flow<StreamChunk>`)。
     *
     * 一次 SSE 行可能产生多个 StreamEvent(典型:code block Delta + Ended),
     * for-loop 消费整个列表,语义无变化。
     *
     * 内部结构:
     *   1. 非流式回退(若 provider 不支持流式)
     *   2. 流式循环: read SSE 行 → adapter.parseStreamChunk(line) → emit StreamEvent
     *   3. 兜底: onStreamEnd 兜底(代码块未闭合等)
     */
    open fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow {
        val startMs = System.currentTimeMillis()
        logger.info(
            "[Gateway.chatStream] → ${'$'}{request.model} | " +
                    "messages=${'$'}{request.messages.size}, " +
                    "tools=${'$'}{request.tools?.size ?: 0}, " +
                    "promptChars=${'$'}{request.messages.sumOf { it.content.length }}"
        )
        val adapter = getCurrentAdapter(request.model)
            ?: throw ModelNotFoundException("Model not found: ${'$'}{request.model}")

        if (!adapter.supportsStreaming()) {
            logger.info("[Gateway.chatStream] Adapter does not support streaming, falling back to sync chat for model=${'$'}{request.model}")
            val syncRequest = request.copy(stream = false)
            val result = chat(syncRequest)
            result.fold(
                onSuccess = { response ->
                    val choice = response.choices.firstOrNull()
                    val message = choice?.message
                    val responseId = response.id
                    if (message != null) {
                        if (!message.content.isNullOrBlank()) {
                            emit(StreamEvent.Content.Text(delta = message.content))
                        }
                        message.toolCalls?.forEachIndexed { idx, toolCall ->
                            emit(StreamEvent.ToolCall.Delta(
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                                argumentsFragment = toolCall.arguments,
                            ))
                        }
                        if (choice.finishReason != null) {
                            emit(StreamEvent.Flow.Finished(
                                finishReason = com.codesage.model.dto.FinishReason.from(choice.finishReason),
                                usage = response.usage,
                            ))
                        }
                    }
                    if (response.usage != null) {
                        emit(StreamEvent.Flow.Finished(
                            finishReason = com.codesage.model.dto.FinishReason.STOP,
                            usage = response.usage,
                        ))
                    }
                },
                onFailure = { error -> throw error }
            )
            return@flow
        }

        val vendorRequest = adapter.toVendorRequest(request)
        logger.info(
            "[Gateway.chatStream] → ${'$'}{request.model} | " +
                    "requestSize=${'$'}{vendorRequest.length}B, " +
                    "bodyPreview=${'$'}{vendorRequest.take(500)}"
        )
        if (vendorRequest.length > 8 * 1024) {
            logger.warn(
                "[Gateway.chatStream] Suspiciously large request " +
                        "size=${'$'}{vendorRequest.length}B, " +
                        "firstMessageRoles=${'$'}{request.messages.take(3).map { it.role }}; " +
                        "this may indicate session contamination from parent agent"
            )
        }

        val req = Request.Builder()
            .url(adapter.getStreamEndpoint())
            .headers(buildHeaders(adapter.getHeaders()))
            .post(vendorRequest.toRequestBody(jsonMediaType))
            .build()

        var chunkCount = 0
        var bytesRead = 0L
        var lastUsage: Usage? = null
        var lastFinishReason: com.codesage.model.dto.FinishReason? = null
        var emittedAnyEvent = false
        var seenFinished = false

        try {
            // 重置 adapter 跨 turn 流式状态(<think> 状态机 + 累积 buffer)
            if (adapter is com.codesage.model.adapter.OpenAICompatibleAdapter) {
                adapter.resetStreamNormalizer()
            }
            val call = httpClient.newCall(req)
            currentCall.set(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()?.take(2000) ?: "(empty body)"
                        logger.error(
                            "[Gateway.chatStream] ✗ ${'$'}{request.model} | " +
                                    "status=${'$'}{response.code} requestSize=${'$'}{vendorRequest.length}B " +
                                    "durationMs=${'$'}{System.currentTimeMillis() - startMs} | " +
                                    "body=$errorBody"
                        )
                        throw NetworkException("HTTP ${'$'}{response.code} (requestSize=${'$'}{vendorRequest.length}B): $errorBody")
                    }

                    val body = response.body ?: throw NetworkException("Empty response body in stream")
                    val normalizer = adapter.streamNormalizer()
                    val state = StreamEventNormalizer.StreamState()

                    body.source().let { source ->
                        var consecutiveNullChunks = 0
                        val maxConsecutiveNullChunks = 1000

                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            bytesRead += line.length + 1
                            if (line.isBlank()) {
                                consecutiveNullChunks = 0
                                continue
                            }

                            val events = normalizer.normalize(line, state)
                            if (events.isNotEmpty()) {
                                consecutiveNullChunks = 0
                                emittedAnyEvent = true
                                chunkCount += events.size
                                for (event in events) {
                                    when (event) {
                                        is StreamEvent.Flow.Finished -> {
                                            seenFinished = true
                                            if (event.usage != null) lastUsage = event.usage
                                            lastFinishReason = event.finishReason
                                        }
                                        else -> {}
                                    }
                                    emit(event)
                                    if (event is StreamEvent.Flow.Finished) {
                                        // finished 信号,流结束
                                        @Suppress("UNREACHABLE_CODE")
                                        break
                                    }
                                }
                                if (seenFinished) break
                            } else {
                                consecutiveNullChunks++
                                if (consecutiveNullChunks > maxConsecutiveNullChunks) {
                                    throw NetworkException(
                                        "Stream parsing failed: too many consecutive unparseable lines"
                                    )
                                }
                            }
                        }

                        // 兜底: 流结束前调 onStreamEnd 拿残余 events
                        val pendingEvents = normalizer.onStreamEnd(state)
                        for (event in pendingEvents) {
                            emittedAnyEvent = true
                            chunkCount++
                            emit(event)
                        }

                        // 兜底:如果整个响应体没有 emit 任何 event,emit Flow.Finished
                        if (!emittedAnyEvent) {
                            emit(StreamEvent.Flow.Finished(
                                finishReason = com.codesage.model.dto.FinishReason.STOP,
                                usage = null,
                            ))
                        } else if (!seenFinished) {
                            // 关键日志:某些 OpenAI 兼容 provider(MiniMax-M3 等)不发送
                            // `[DONE]` sentinel,而是直接关闭 SSE 连接。
                            logger.info(
                                "[Gateway.chatStream] SSE closed without Finished, " +
                                "emitting synthetic Finished(STOP) for ${'$'}{request.model}"
                            )
                            emit(StreamEvent.Flow.Finished(
                                finishReason = com.codesage.model.dto.FinishReason.STOP,
                                usage = lastUsage,
                            ))
                        }
                    }
                }
            } finally {
                currentCall.compareAndSet(call, null)
            }
            logger.info(
                "[Gateway.chatStream] ← ${'$'}{request.model} | " +
                        "status=200 events=$chunkCount " +
                        "bytes~${'$'}{bytesRead} " +
                        "finishReason=${'$'}{lastFinishReason} " +
                        "usage=" + (lastUsage?.totalTokens?.toString() ?: "?") + "tok " +
                        "durationMs=${'$'}{System.currentTimeMillis() - startMs}"
            )
        } catch (e: java.io.InterruptedIOException) {
            logger.info("[Gateway.chatStream] request cancelled mid-stream after ${'$'}{chunkCount} events")
            throw e
        } catch (e: Exception) {
            logger.error(
                "[Gateway.chatStream] ✗ ${'$'}{request.model} | " +
                        "${'$'}{e.javaClass.simpleName}: ${'$'}{e.message?.take(200)} | " +
                        "eventsBeforeFail=$chunkCount durationMs=${'$'}{System.currentTimeMillis() - startMs}",
                e
            )
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 2026-06: 把 [StreamEvent] 转换为 [StreamChunk] 的辅助 — 用于 [chatStream] 兼容层。
     * 这是一个临时的桥接,后续 commit 5 + 6 完成后 [chatStream] 这个旧 API 会被删除。
     */
    private var toolCallIndexCounter: Int = 0

    private fun streamEventToChunk(event: StreamEvent): List<StreamChunk> = when (event) {
        is StreamEvent.Content.Text -> listOf(StreamChunk(id = "", delta = event.delta))
        is StreamEvent.Content.Reasoning -> listOf(StreamChunk(id = "", delta = "", reasoningDelta = event.delta))
        is StreamEvent.Content.PlanStep -> listOf(StreamChunk(id = "", delta = event.delta))
        is StreamEvent.ToolCall.Delta -> listOf(StreamChunk(
            id = "",
            delta = "",
            toolCallDeltas = listOf(StreamToolCallDelta(
                index = toolCallIndexCounter++,
                id = event.toolCallId,
                name = event.toolName,
                arguments = event.argumentsFragment,
            )),
        ))
        is StreamEvent.CodeBlock.Started -> listOf(StreamChunk(
            id = "",
            delta = "",
            codeBlock = CodeBlockEvent.Start(event.codeBlockId, event.language),
        ))
        is StreamEvent.CodeBlock.Delta -> listOf(StreamChunk(
            id = "",
            delta = "",
            codeBlock = CodeBlockEvent.Delta(event.codeBlockId, event.delta),
        ))
        is StreamEvent.CodeBlock.Ended -> listOf(StreamChunk(
            id = "",
            delta = "",
            codeBlock = CodeBlockEvent.End(event.codeBlockId),
        ))
        is StreamEvent.Flow.Started -> emptyList()
        is StreamEvent.Flow.Finished -> listOf(StreamChunk(
            id = "",
            delta = "",
            done = true,
            finishReason = event.finishReason.name.lowercase(),
            usage = event.usage,
        ))
        is StreamEvent.Flow.Cancelled -> listOf(StreamChunk(id = "", delta = "", done = true))
        is StreamEvent.Flow.Error -> listOf(StreamChunk(id = "", delta = "", done = true))
        is StreamEvent.Citation.Delta -> emptyList()
        is StreamEvent.Media.ImageFragment -> emptyList()
        is StreamEvent.Media.AudioFragment -> emptyList()
    }
    /**
     * 获取可用模型列表
     */
    fun listModels(): List<ModelInfo> = registry.listAvailableModels()

    /**
     * 获取当前使用的适配器
     */
    open fun getCurrentAdapter(model: String): ModelAdapter? = registry.getAdapterForModel(model)

    private suspend fun executeRequest(
        adapter: ModelAdapter,
        vendorRequest: String,
        stream: Boolean
    ): String {
        val endpoint = if (stream) adapter.getStreamEndpoint() else adapter.getChatEndpoint()

        val request = Request.Builder()
            .url(endpoint)
            .headers(buildHeaders(adapter.getHeaders()))
            .post(vendorRequest.toRequestBody(jsonMediaType))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                logger.error("[HTTP] Error response ${response.code}: ${body?.take(1000) ?: "empty"}")
                throw NetworkException("HTTP ${response.code}: $body")
            }
            body ?: throw NetworkException("Empty response body")
        }
    }

    private fun buildHeaders(headers: Map<String, String>): Headers {
        val builder = Headers.Builder()
        headers.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    companion object {
        @Volatile
        private var instance: ModelGateway? = null

        fun getInstance(): ModelGateway {
            return instance ?: synchronized(this) {
                instance ?: ModelGateway().also { instance = it }
            }
        }
    }
}
