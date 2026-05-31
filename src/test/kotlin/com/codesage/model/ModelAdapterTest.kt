package com.codesage.model

import com.codesage.model.adapter.OpenAICompatibleAdapter
import com.codesage.model.adapter.minimax.MiniMaxAdapter
import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.registry.ModelRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelAdapterTest {

    @Test
    fun `MiniMaxAdapter should have correct provider name`() {
        val adapter = MiniMaxAdapter("test-api-key")
        assertEquals("minimax", adapter.providerName)
    }

    @Test
    fun `MiniMaxAdapter should support required models`() {
        val adapter = MiniMaxAdapter("test-api-key")
        assertTrue(adapter.supportedModels.contains("MiniMax-M2.7"))
        assertTrue(adapter.supportedModels.contains("MiniMax-M2.5"))
    }

    @Test
    fun `MiniMaxAdapter should support streaming and function calling`() {
        val adapter = MiniMaxAdapter("test-api-key")
        assertTrue(adapter.supportsStreaming())
        assertTrue(adapter.supportsFunctionCalling())  // MiniMax supports tools via /v1/chat/completions
        assertFalse(adapter.supportsVision())
    }

    @Test
    fun `MiniMaxAdapter should use openai compatible chat completions endpoint`() {
        val adapter = MiniMaxAdapter("test-api-key")
        assertEquals("https://api.minimaxi.com/v1/chat/completions", adapter.getChatEndpoint())
    }

    @Test
    fun `MiniMaxAdapter should convert system role to user with system marker`() {
        val adapter = MiniMaxAdapter("test-api-key")

        val request = ChatRequest(
            model = "MiniMax-M2.5",
            messages = listOf(
                Message.systemMessage("You are a helpful assistant."),
                Message.userMessage("Hello")
            ),
            temperature = 0.7
        )

        val vendorRequest = adapter.toVendorRequest(request)
        assertTrue(vendorRequest.contains("\"role\":\"user\""))
        assertTrue(vendorRequest.contains("[System]\\nYou are a helpful assistant."))
    }

    @Test
    fun `OpenAI compatible adapter should work for kimi-like providers`() {
        val adapter = object : OpenAICompatibleAdapter("test-api-key", "https://api.moonshot.cn") {
            override val providerName: String = "kimi"
            override val supportedModels: List<String> = listOf("kimi-k2.6", "moonshot-v1-8k")
            override val chatEndpointPath: String = "/v1/chat/completions"
        }
        assertEquals("kimi", adapter.providerName)
        assertTrue(adapter.supportedModels.contains("moonshot-v1-8k"))
        assertEquals("https://api.moonshot.cn/v1/chat/completions", adapter.getChatEndpoint())
    }

    @Test
    fun `ModelRegistry should register adapters`() {
        val registry = ModelRegistry()
        val minimax = registry.createMiniMaxAdapter("test-key")
        val kimi = registry.registerOpenAICompatibleAdapter(
            name = "kimi", apiKey = "test-key",
            baseUrl = "https://api.moonshot.cn",
            models = listOf("kimi-k2.6", "moonshot-v1-8k")
        )

        assertEquals(minimax, registry.getAdapter("minimax"))
        assertEquals(kimi, registry.getAdapter("kimi"))
    }

    @Test
    fun `ModelRegistry should get adapter for model`() {
        val registry = ModelRegistry()
        registry.createMiniMaxAdapter("test-key")

        val adapter = registry.getAdapterForModel("MiniMax-M2.5")
        assertNotNull(adapter)
        assertEquals("minimax", adapter?.providerName)
    }

    @Test
    fun `MiniMaxAdapter should convert request to vendor format`() {
        val adapter = MiniMaxAdapter("test-api-key")

        val request = ChatRequest(
            model = "MiniMax-M2.5",
            messages = listOf(
                Message.userMessage("Hello"),
                Message.assistantMessage("Hi there!")
            ),
            temperature = 0.7
        )

        val vendorRequest = adapter.toVendorRequest(request)
        assertTrue(vendorRequest.contains("\"model\":\"MiniMax-M2.5\""))
        assertTrue(vendorRequest.contains("\"temperature\":0.7"))
    }
}
