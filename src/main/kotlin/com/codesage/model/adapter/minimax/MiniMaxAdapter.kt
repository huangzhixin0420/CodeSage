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
        "MiniMax-M2.7",
        "MiniMax-M2.7-highspeed",
        "MiniMax-M2.5",
        "MiniMax-M2.5-highspeed",
        "MiniMax-M2.1",
        "MiniMax-M2.1-highspeed",
        "MiniMax-M2"
    )

    override val chatEndpointPath: String = "/v1/chat/completions"

    // T1.1 修复：使用 ModelCapabilities 集中声明能力
    override val capabilities: com.codesage.model.dto.ModelCapabilities =
        com.codesage.model.dto.ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = false,
            toolStreaming = true,
            maxContextTokens = 128_000,
            maxOutputTokens = 8_192
        )

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
