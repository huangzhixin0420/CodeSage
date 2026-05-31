package com.codesage.agent.core

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentChatTest {

    @Test
    fun `cancel first job then start second should work`() = runBlocking {
        val callCount = AtomicInteger(0)
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                val c = callCount.incrementAndGet()
                println("[Gateway] call #$c, messages=${request.messages.size}")
                if (c == 1) {
                    delay(5000) // Simulate long-running first request
                }
                return Result.success(
                    ChatResponse(
                        id = "test_$c",
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = Message.assistantMessage("Response $c"),
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

        // Start first call
        val job1 = launch {
            val events = agent.chatWithTools("First message").toList()
            println("[Test] First call events: ${events.map { it::class.simpleName }}")
        }

        delay(100) // Let first call start
        job1.cancelAndJoin()

        // Second call
        val events2 = agent.chatWithTools("Second message").toList()
        println("[Test] Second call events: ${events2.map { it::class.simpleName }}")
        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Second call text: $text2")
        assertTrue(text2.contains("Response 2"), "Second call should have Response 2. Actual: $text2")
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
