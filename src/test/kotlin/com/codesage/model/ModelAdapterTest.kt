package com.codesage.model

import com.codesage.model.adapter.kimi.KimiAdapter
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
        assertTrue(adapter.supportedModels.contains("MiniMax-Text-01"))
        assertTrue(adapter.supportedModels.contains("abab6.5s-chat"))
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
            model = "MiniMax-Text-01",
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
    fun `KimiAdapter should have correct provider name`() {
        val adapter = KimiAdapter("test-api-key")
        assertEquals("kimi", adapter.providerName)
    }

    @Test
    fun `KimiAdapter should support required models`() {
        val adapter = KimiAdapter("test-api-key")
        assertTrue(adapter.supportedModels.contains("moonshot-v1-8k"))
        assertTrue(adapter.supportedModels.contains("moonshot-v1-32k"))
    }

    @Test
    fun `ModelRegistry should register adapters`() {
        val registry = ModelRegistry()
        val minimax = registry.createMiniMaxAdapter("test-key")
        val kimi = registry.createKimiAdapter("test-key")

        assertEquals(minimax, registry.getAdapter("minimax"))
        assertEquals(kimi, registry.getAdapter("kimi"))
    }

    @Test
    fun `ModelRegistry should get adapter for model`() {
        val registry = ModelRegistry()
        registry.createMiniMaxAdapter("test-key")

        val adapter = registry.getAdapterForModel("MiniMax-Text-01")
        assertNotNull(adapter)
        assertEquals("minimax", adapter?.providerName)
    }

    @Test
    fun `MiniMaxAdapter should convert request to vendor format`() {
        val adapter = MiniMaxAdapter("test-api-key")

        val request = ChatRequest(
            model = "MiniMax-Text-01",
            messages = listOf(
                Message.userMessage("Hello"),
                Message.assistantMessage("Hi there!")
            ),
            temperature = 0.7
        )

        val vendorRequest = adapter.toVendorRequest(request)
        assertTrue(vendorRequest.contains("\"model\":\"MiniMax-Text-01\""))
        assertTrue(vendorRequest.contains("\"temperature\":0.7"))
    }
}
