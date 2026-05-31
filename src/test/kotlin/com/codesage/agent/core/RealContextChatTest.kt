package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RealContextChatTest {

    @Test
    fun `two calls with real context should both produce text`() = runBlocking {
        var callCount = 0
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                println("[Gateway] call #$callCount")
                println("  Messages: ${request.messages.size}")
                request.messages.forEachIndexed { i, msg ->
                    println("  [$i] ${msg.role}: ${msg.content?.take(60)?.replace("\n", " ")}")
                }
                return Result.success(
                    ChatResponse(
                        id = "test_$callCount",
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = Message.assistantMessage("Answer $callCount"),
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

        val events1 = agent.chatWithTools("What is Kotlin?").toList()
        val text1 = events1.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Call 1 text: '$text1'")
        assertTrue(text1.contains("Answer 1"))

        val events2 = agent.chatWithTools("What is Kotlin?").toList()
        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Call 2 text: '$text2'")
        assertTrue(text2.contains("Answer 2"), "Call 2 should have Answer 2 but was: '$text2'")
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
            override fun parseStreamChunk(chunk: String): StreamChunk? = null
            override fun getStreamEndpoint(): String = "http://fake"
            override fun getChatEndpoint(): String = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }
    }
}
