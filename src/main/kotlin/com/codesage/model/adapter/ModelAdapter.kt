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
     * T1.1 修复：模型能力声明
     */
    val capabilities: ModelCapabilities
        get() = ModelCapabilities()

    /**
     * 是否支持流式输出
     */
    fun supportsStreaming(): Boolean = capabilities.streaming

    /**
     * 是否支持函数调用
     */
    fun supportsFunctionCalling(): Boolean = capabilities.functionCalling

    /**
     * 是否支持视觉能力 (多模态)
     */
    fun supportsVision(): Boolean = capabilities.vision

    /**
     * 将统一请求转换为厂商特定请求
     */
    fun toVendorRequest(request: ChatRequest): String

    /**
     * 将厂商响应转换为统一响应
     */
    fun fromVendorResponse(response: String): ChatResponse

    /**
     * 2026-06: 解析一行 SSE 数据, 返回 0..N 个 [StreamEvent]。
     *
     * 替代旧的 `parseStreamChunk: List<StreamChunk>` 签名。一行可能产生多个事件
     * (典型: code block Delta + End), [com.codesage.model.gateway.ModelGateway]
     * 负责 for-loop 消费。
     *
     * Adapter 委托给 [StreamEventNormalizer] 实现具体映射逻辑。
     * 默认实现委托给 [streamNormalizer]。
     */
    fun parseStreamChunk(chunk: String): List<StreamEvent> {
        return streamNormalizer().normalize(chunk, StreamEventNormalizer.StreamState())
    }

    /**
     * 2026-06: 返回该 adapter 用的协议层 [StreamEventNormalizer]。
     *
     * 子类应该重写以返回对应的 provider-specific normalizer。
     * 默认返回 [OpenAIStreamNormalizer] (向后兼容: OpenAI 兼容协议是最常见的)。
     */
    fun streamNormalizer(): StreamEventNormalizer = OpenAIStreamNormalizer(providerName = providerName)

    /**
     * 获取模型信息
     */
    fun getModelInfo(modelId: String): ModelInfo = ModelInfo(
        id = modelId,
        provider = providerName,
        displayName = modelId,
        supportsStreaming = capabilities.streaming,
        supportsFunctionCalling = capabilities.functionCalling,
        supportsVision = capabilities.vision
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
