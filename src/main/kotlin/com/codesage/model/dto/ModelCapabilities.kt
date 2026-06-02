package com.codesage.model.dto

/**
 * T1.1 修复：模型能力声明
 *
 * 替代原 `ModelAdapter.supportsStreaming()` / `supportsFunctionCalling()` / `supportsVision()`
 * 三个零散方法。集中到一个 immutable 数据类里，便于：
 * 1. 智能路由按能力反查（参见 T1.4 SmartRouter）
 * 2. UI 展示模型能力标签
 * 3. 测试断言模型能力
 *
 * @property streaming 是否支持 SSE 流式响应
 * @property functionCalling 是否支持 OpenAI 风格的 function/tool calling
 * @property vision 是否支持图像输入
 * @property toolStreaming 是否支持工具调用的流式输出
 * @property systemPromptCache 是否支持 system prompt 缓存（用于 prefix cache 优化）
 * @property promptCaching 是否支持 prompt 内容缓存（Claude prompt caching）
 * @property maxContextTokens 最大上下文 token 数
 * @property maxOutputTokens 最大输出 token 数
 * @property pricePer1kInput 每 1k 输入 token 的价格（美元）
 * @property pricePer1kOutput 每 1k 输出 token 的价格（美元）
 */
data class ModelCapabilities(
    val streaming: Boolean = true,
    val functionCalling: Boolean = false,
    val vision: Boolean = false,
    val toolStreaming: Boolean = false,
    val systemPromptCache: Boolean = false,
    val promptCaching: Boolean = false,
    val maxContextTokens: Int = 128_000,
    val maxOutputTokens: Int = 4_096,
    val pricePer1kInput: Double = 0.0,
    val pricePer1kOutput: Double = 0.0
) {
    /**
     * 是否满足 required 的能力集合。
     * 例如：`capabilities.meets(setOf(VISION))` 在 vision=true 时返回 true
     */
    infix fun meets(required: Set<Capability>): Boolean = required.all { hasCapability(it) }

    fun hasCapability(capability: Capability): Boolean = when (capability) {
        Capability.STREAMING -> streaming
        Capability.FUNCTION_CALLING -> functionCalling
        Capability.VISION -> vision
        Capability.TOOL_STREAMING -> toolStreaming
        Capability.SYSTEM_PROMPT_CACHE -> systemPromptCache
        Capability.PROMPT_CACHING -> promptCaching
        Capability.LONG_CONTEXT -> maxContextTokens >= 100_000
        Capability.REASONING -> maxContextTokens >= 200_000  // 简单启发：更大上下文通常意味着更强推理
        Capability.CODE -> functionCalling && maxContextTokens >= 32_000
    }

    companion object {
        /**
         * OpenAI GPT-4o 默认能力
         */
        fun openAiGpt4o() = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = true,
            toolStreaming = true,
            maxContextTokens = 128_000,
            maxOutputTokens = 4_096,
            pricePer1kInput = 0.005,
            pricePer1kOutput = 0.015
        )

        /**
         * Anthropic Claude 3.5 Sonnet 默认能力
         */
        fun claude35Sonnet() = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = true,
            toolStreaming = true,
            promptCaching = true,
            maxContextTokens = 200_000,
            maxOutputTokens = 8_192,
            pricePer1kInput = 0.003,
            pricePer1kOutput = 0.015
        )

        /**
         * Google Gemini Pro 默认能力
         */
        fun geminiPro() = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = true,
            toolStreaming = true,
            maxContextTokens = 1_000_000,
            maxOutputTokens = 8_192,
            pricePer1kInput = 0.00125,
            pricePer1kOutput = 0.005
        )

        /**
         * 极简模型（仅流式，无 function calling）
         */
        fun minimal() = ModelCapabilities(
            streaming = true,
            functionCalling = false,
            vision = false,
            maxContextTokens = 4_096,
            maxOutputTokens = 1_024
        )
    }
}

/**
 * 模型能力标签
 *
 * 用于智能路由和 UI 展示。详细的 boolean 值在 [ModelCapabilities] 中。
 */
enum class Capability {
    STREAMING,            // 支持流式响应
    FUNCTION_CALLING,     // 支持函数/工具调用
    VISION,               // 支持图像输入
    TOOL_STREAMING,       // 支持工具调用的流式输出
    SYSTEM_PROMPT_CACHE,  // 支持 system prompt 缓存
    PROMPT_CACHING,       // 支持 prompt 内容缓存
    LONG_CONTEXT,         // 长上下文（>= 100k tokens）
    REASONING,            // 强推理能力（>= 200k tokens）
    CODE                  // 代码任务（function calling + 32k+ 上下文）
}
