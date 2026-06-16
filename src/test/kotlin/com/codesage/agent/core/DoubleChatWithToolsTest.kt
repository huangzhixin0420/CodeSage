package com.codesage.agent.core
import com.codesage.model.adapter.StreamEvent

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

class DoubleChatWithToolsTest {

    @Test
    fun `first call with tools then second call should both work`() = runBlocking {
        var callCount = 0
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                println("[Gateway] call #$callCount, messages=${request.messages.size}, roles=${request.messages.map { it.role }}")
                request.messages.forEachIndexed { i, msg ->
                    println("  [$i] ${msg.role}: ${msg.content?.take(100)}")
                }
                return if (callCount == 1) {
                    // First call: tool call
                    Result.success(
                        ChatResponse(
                            id = "test_$callCount",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = Role.ASSISTANT,
                                        content = "",
                                        toolCalls = listOf(
                                            ToolCall(
                                                id = "tool_1",
                                                name = "list_directory",
                                                arguments = "{\"path\": \"src\"}"
                                            )
                                        )
                                    ),
                                    finishReason = "tool_calls"
                                )
                            ),
                            usage = null
                        )
                    )
                } else if (callCount == 2) {
                    // Second call: after tool result
                    Result.success(
                        ChatResponse(
                            id = "test_$callCount",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("I found the src directory."),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                } else {
                    // Third call: second user message
                    Result.success(
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
        }

        val agent = AgentCore(gateway = gateway)
        agent.initialize(AgentConfig())

        // First call - triggers tool call then final answer
        val events1 = agent.chatWithTools("List files").toList()
        println("[Test] First call events: ${events1.map { it::class.simpleName }}")
        val text1 = events1.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] First call text: $text1")
        assertTrue(text1.contains("I found the src directory."), "First call should have final answer")

        // Second call - simple message
        val events2 = agent.chatWithTools("What next?").toList()
        println("[Test] Second call events: ${events2.map { it::class.simpleName }}")
        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Second call text: $text2")
        assertTrue(text2.contains("Response 3"), "Second call should have Response 3")

        assertEquals(3, callCount, "Gateway should be called 3 times")
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
            override fun parseStreamChunk(chunk: String): List<StreamEvent> = emptyList()
            override fun getStreamEndpoint(): String = "http://fake"
            override fun getChatEndpoint(): String = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }
    }
}
