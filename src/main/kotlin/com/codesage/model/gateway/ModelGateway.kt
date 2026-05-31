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
     * 同步聊天请求
     */
    open suspend fun chat(request: ChatRequest): Result<ChatResponse> = withContext(Dispatchers.IO) {
        logger.info("[Gateway.chat] Request for model=${request.model}, messages=${request.messages.size}")
        try {
            val adapter = registry.getAdapterForModel(request.model)
            if (adapter == null) {
                logger.error("[Gateway.chat] No adapter registered for model: ${request.model}")
                logger.info("[Gateway.chat] Available models: ${registry.listAvailableModels().map { it.id }}")
                return@withContext Result.failure(
                    ModelNotFoundException("Model not found: ${request.model}")
                )
            }
            logger.info("[Gateway.chat] Using adapter: ${adapter.providerName}")

            val vendorRequest = adapter.toVendorRequest(request)
            logger.info("[HTTP] Request to ${adapter.getChatEndpoint()}: ${vendorRequest.take(2000)}")

            val response = executeRequest(adapter, vendorRequest, request.stream)
            logger.info("[HTTP] Response: ${response.take(2000)}")

            Result.success(adapter.fromVendorResponse(response))
        } catch (e: AppException) {
            logger.error("[HTTP] AppException: ${e.javaClass.name}: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            logger.error("[HTTP] Chat request failed: ${e.javaClass.name}: ${e.message}", e)
            Result.failure(NetworkException("Chat request failed: ${e.message}"))
        }
    }

    /**
     * 流式聊天请求
     * 支持 stream=true + tools 参数，若模型不支持流式则自动回退到非流式并包装为 Flow
     */
    open fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
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

        val req = Request.Builder()
            .url(adapter.getStreamEndpoint())
            .headers(buildHeaders(adapter.getHeaders()))
            .post(vendorRequest.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw NetworkException("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw NetworkException("Empty response body in stream")
            body.source().let { source ->
                var consecutiveNullChunks = 0
                val maxConsecutiveNullChunks = 1000

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) {
                        consecutiveNullChunks = 0
                        continue
                    }

                    val chunk = adapter.parseStreamChunk(line)
                    if (chunk != null) {
                        consecutiveNullChunks = 0
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
            }
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
