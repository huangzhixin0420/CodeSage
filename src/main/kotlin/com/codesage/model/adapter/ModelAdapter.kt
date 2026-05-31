package com.codesage.model.adapter

import com.codesage.model.dto.*

/**
 * 模型适配器接口
 * 统一不同模型提供商的API格式
 */
interface ModelAdapter {
    /**
     * 提供商名称 (minimax, kimi, openai)
     */
    val providerName: String

    /**
     * 支持的模型列表
     */
    val supportedModels: List<String>

    /**
     * 是否支持流式输出
     */
    fun supportsStreaming(): Boolean

    /**
     * 是否支持函数调用
     */
    fun supportsFunctionCalling(): Boolean

    /**
     * 是否支持视觉能力 (多模态)
     */
    fun supportsVision(): Boolean

    /**
     * 将统一请求转换为厂商特定请求
     */
    fun toVendorRequest(request: ChatRequest): String

    /**
     * 将厂商响应转换为统一响应
     */
    fun fromVendorResponse(response: String): ChatResponse

    /**
     * 解析流式响应片段
     */
    fun parseStreamChunk(chunk: String): StreamChunk?

    /**
     * 获取模型信息
     */
    fun getModelInfo(modelId: String): ModelInfo = ModelInfo(
        id = modelId,
        provider = providerName,
        displayName = modelId,
        supportsStreaming = supportsStreaming(),
        supportsFunctionCalling = supportsFunctionCalling(),
        supportsVision = supportsVision()
    )

    /**
     * 获取流式请求端点
     */
    fun getStreamEndpoint(): String

    /**
     * 获取聊天请求端点
     */
    fun getChatEndpoint(): String

    /**
     * 获取请求头
     */
    fun getHeaders(): Map<String, String>

    /**
     * 获取模型列表端点
     */
    fun getModelsEndpoint(): String? = null

    /**
     * 从提供商 API 动态获取可用模型列表
     * @return 模型 ID 列表，如果提供商不支持或请求失败则返回空列表
     */
    suspend fun fetchModels(): List<String> = emptyList()
}
