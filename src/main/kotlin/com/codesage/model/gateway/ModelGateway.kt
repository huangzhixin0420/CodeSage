package com.codesage.model.gateway

import com.codesage.model.adapter.ModelAdapter
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
    open fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
        val startMs = System.currentTimeMillis()
        logger.info(
            "[Gateway.chatStream] → ${request.model} | " +
                    "messages=${request.messages.size}, " +
                    "tools=${request.tools?.size ?: 0}, " +
                    "promptChars=${request.messages.sumOf { it.content.length }}"
        )
        val adapter = getCurrentAdapter(request.model)
            ?: throw ModelNotFoundException("Model not found: ${request.model}")

        if (!adapter.supportsStreaming()) {
            // 非流式回退：调用 chat() 并将完整响应包装为 StreamChunk 序列
            logger.info("[Gateway.chatStream] Adapter does not support streaming, falling back to sync chat for model=${request.model}")
            val syncRequest = request.copy(stream = false)
            val result = chat(syncRequest)
            result.fold(
                onSuccess = { response ->
                    val choice = response.choices.firstOrNull()
                    val message = choice?.message
                    val responseId = response.id
                    if (message != null) {
                        // 文本内容
                        if (!message.content.isNullOrBlank()) {
                            emit(StreamChunk(id = responseId, delta = message.content))
                        }
                        // 工具调用（以增量形式发出，确保下游统一处理）
                        message.toolCalls?.forEach { toolCall ->
                            emit(
                                StreamChunk(
                                    id = responseId,
                                    delta = "",
                                    toolCallDeltas = listOf(
                                        StreamToolCallDelta(
                                            index = 0,
                                            id = toolCall.id,
                                            name = toolCall.name,
                                            arguments = toolCall.arguments
                                        )
                                    )
                                )
                            )
                        }
                        // finish reason
                        if (choice.finishReason != null) {
                            emit(StreamChunk(id = responseId, delta = "", finishReason = choice.finishReason))
                        }
                    }
                    emit(StreamChunk(id = responseId, delta = "", done = true, usage = response.usage))
                },
                onFailure = { error ->
                    throw error
                }
            )
            return@flow
        }

        val vendorRequest = adapter.toVendorRequest(request)
        logger.info(
            "[Gateway.chatStream] → ${request.model} | " +
                    "requestSize=${vendorRequest.length}B, " +
                    "bodyPreview=${vendorRequest.take(500)}"
        )
        // 子 Agent 触发的请求 > 8KB 视为可疑（独立 prompt ~1.2KB + 任务 ~1KB + 工具 schema ~2-4KB = 4-6KB）
        // 历史上曾因父 Agent 历史被 restore 进子 Agent 导致 requestSize 膨胀到 39KB+ 触发 MiniMax 2013 错误
        if (vendorRequest.length > 8 * 1024) {
            logger.warn(
                "[Gateway.chatStream] Suspiciously large request " +
                    "size=${vendorRequest.length}B, " +
                    "firstMessageRoles=${request.messages.take(3).map { it.role }}; " +
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
        var lastFinishReason: String? = null

        try {
            // 绑定 Call 句柄，让 cancelCurrentRequest() 能跨线程中断阻塞 IO
            val call = httpClient.newCall(req)
            currentCall.set(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // 重要：必须读出 body！LLM API 返 4xx/5xx 时的 body 通常是 JSON
                        // {"error":{"message":"tools[0].function.name is required","type":"..."}}，
                        // 不读 body 调试时只能看到 "HTTP 400: " 干入栈。
                        val errorBody = response.body?.string()?.take(2000) ?: "(empty body)"
                        logger.error(
                            "[Gateway.chatStream] ✗ ${request.model} | " +
                                    "status=${response.code} requestSize=${vendorRequest.length}B " +
                                    "durationMs=${System.currentTimeMillis() - startMs} | " +
                                    "body=$errorBody"
                        )
                        throw NetworkException("HTTP ${response.code} (requestSize=${vendorRequest.length}B): $errorBody")
                    }

                    val body = response.body ?: throw NetworkException("Empty response body in stream")
                    body.source().let { source ->
                        var consecutiveNullChunks = 0
                        val maxConsecutiveNullChunks = 1000
                        var emittedAnyChunk = false

                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            bytesRead += line.length + 1  // 近似估算
                            if (line.isBlank()) {
                                consecutiveNullChunks = 0
                                continue
                            }

                            val chunk = adapter.parseStreamChunk(line)
                            if (chunk != null) {
                                consecutiveNullChunks = 0
                                emittedAnyChunk = true
                                chunkCount++
                                if (chunk.usage != null) lastUsage = chunk.usage
                                if (chunk.finishReason != null) lastFinishReason = chunk.finishReason
                                emit(chunk)
                                if (chunk.done) break
                            } else {
                                consecutiveNullChunks++
                                if (consecutiveNullChunks > maxConsecutiveNullChunks) {
                                    throw NetworkException(
                                        "Stream parsing failed: too many consecutive unparseable lines"
                                    )
                                }
                            }
                        }

                        // 兜底：如果整个响应体没有 emit 任何 chunk，至少 emit done
                        if (!emittedAnyChunk) {
                            emit(StreamChunk(id = "", delta = "", done = true, usage = null))
                        }
                    }
                }
            } finally {
                // 无论成功 / 失败 / 取消，都把 Call 句柄清掉
                currentCall.compareAndSet(call, null)
            }
            // 流式成功后汇总统计
            logger.info(
                "[Gateway.chatStream] ← ${request.model} | " +
                        "status=200 chunks=$chunkCount " +
                        "bytes~${bytesRead} " +
                        "finishReason=$lastFinishReason " +
                        "usage=${lastUsage?.totalTokens ?: "?"}tok " +
                        "durationMs=${System.currentTimeMillis() - startMs}"
            )
        } catch (e: java.io.InterruptedIOException) {
            // OkHttp Call.cancel() 会抛这个，单独 catch 上报为 info
            logger.info("[Gateway.chatStream] request cancelled mid-stream after ${chunkCount} chunks")
            throw e
        } catch (e: Exception) {
            logger.error(
                "[Gateway.chatStream] ✗ ${request.model} | " +
                        "${e.javaClass.simpleName}: ${e.message?.take(200)} | " +
                        "chunksBeforeFail=$chunkCount durationMs=${System.currentTimeMillis() - startMs}",
                e
            )
            throw e
        }
    }.flowOn(Dispatchers.IO)

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
