package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DoubleChatTest {

    @Test
    fun `two consecutive chatWithTools calls should both produce events`() = runBlocking {
        var callCount = 0
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                println("[Gateway] call #$callCount, messages=${request.messages.size}")
                return Result.success(
                    ChatResponse(
                        id = "test_$callCount",
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = Message.assistantMessage("Response $callCount"),
                                finishReason = "stop"
                            )
                        ),
                        usage = null
                    )
                )
            }
        }

        val agent = AgentCore(gateway = gateway)
        agent.initialize(AgentConfig())

        // First call
        val events1 = agent.chatWithTools("First message").toList()
        println("[Test] First call events: ${events1.map { it::class.simpleName }}")
        val text1 = events1.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertTrue(text1.contains("Response 1"), "First call should have Response 1")

        // Second call
        val events2 = agent.chatWithTools("Second message").toList()
        println("[Test] Second call events: ${events2.map { it::class.simpleName }}")
        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertTrue(text2.contains("Response 2"), "Second call should have Response 2")

        assertEquals(2, callCount, "Gateway should be called twice")
    }

    private fun createFakeAdapter(): ModelAdapter {
        return object : ModelAdapter {
            override val providerName: String = "fake"
            override val supportedModels: List<String> = listOf("test-model")
            override fun supportsStreaming(): Boolean = false
            override fun supportsFunctionCalling(): Boolean = true
            override fun supportsVision(): Boolean = false
            override fun toVendorRequest(request: ChatRequest): String = "{}"
            override fun fromVendorResponse(response: String): ChatResponse =
                ChatResponse("", "", emptyList(), null)
            override fun parseStreamChunk(chunk: String): List<StreamChunk> = emptyList()
            override fun getStreamEndpoint(): String = "http://fake"
            override fun getChatEndpoint(): String = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }
    }
}
