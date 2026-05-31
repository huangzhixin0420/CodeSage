package com.codesage.model.adapter.kimi

import com.codesage.model.adapter.OpenAICompatibleAdapter

/**
 * Kimi (Moonshot) 模型适配器
 * Kimi API 兼容 OpenAI 格式
 */
class KimiAdapter(
    apiKey: String,
    baseUrl: String = "https://api.moonshot.cn",
    customModels: List<String>? = null
) : OpenAICompatibleAdapter(apiKey, baseUrl) {

    override val providerName: String = "kimi"

    override val supportedModels: List<String> = customModels ?: listOf(
        "moonshot-v1-8k",
        "moonshot-v1-32k",
        "moonshot-v1-128k"
    )

    override val chatEndpointPath: String = "/v1/chat/completions"

    override fun supportsVision(): Boolean = false
}
