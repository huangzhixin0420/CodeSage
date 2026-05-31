package com.codesage.model.adapter.minimax

import com.codesage.model.adapter.OpenAICompatibleAdapter
import com.codesage.model.adapter.*
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role

/**
 * MiniMax 模型适配器
 * MiniMax API 兼容 OpenAI 格式
 */
class MiniMaxAdapter(
    apiKey: String,
    baseUrl: String = "https://api.minimaxi.com",
    customModels: List<String>? = null
) : OpenAICompatibleAdapter(apiKey, baseUrl) {

    override val providerName: String = "minimax"

    override val supportedModels: List<String> = customModels ?: listOf(
        "MiniMax-Text-01",
        "abab6.5s-chat",
        "abab6.5g-chat",
        "abab5.5s-chat"
    )

    override val chatEndpointPath: String = "/v1/chat/completions"

    override fun supportsFunctionCalling(): Boolean = true

    override fun supportsVision(): Boolean = false

    /**
     * MiniMax 兼容性处理：
     * 1. 将 system role 转为 user role，并在 content 前加 [System] 标记
     *    （MiniMax /v1/chat/completions 端点不支持 role=system）
     */
    override fun convertMessage(message: Message): VendorMessage {
        val vendorRole = when (message.role) {
            Role.SYSTEM -> "user"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        }
        val vendorContent = if (message.role == Role.SYSTEM) {
            "[System]\n${message.content}"
        } else {
            message.content
        }
        return VendorMessage(
            role = vendorRole,
            content = vendorContent,
            name = message.name,
            toolCalls = message.toolCalls?.map { tc ->
                VendorToolCall(
                    id = tc.id,
                    type = "function",
                    function = VendorFunctionCall(
                        name = tc.name,
                        arguments = tc.arguments
                    )
                )
            },
            toolCallId = message.toolCallId
        )
    }
}
