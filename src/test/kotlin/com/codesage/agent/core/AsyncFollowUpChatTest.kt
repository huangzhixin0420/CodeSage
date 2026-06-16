package com.codesage.agent.core
import com.codesage.model.adapter.StreamEvent

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * 测试异步流收集场景下的多轮对话
 * 模拟 JCEFChatPanel 中的 scope.launch { flow.collect { ... } } 模式
 */
class AsyncFollowUpChatTest {

    @Test
    fun `async collection first call then second call should both work`() = runBlocking {
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

        // 模拟 JCEFChatPanel 的异步收集模式
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val events1 = mutableListOf<AgentStreamEvent>()
        val job1 = scope.launch {
            agent.chatWithTools("First message").collect { event ->
                events1.add(event)
            }
        }
        job1.join()

        val text1 = events1.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] First call events: ${events1.map { it::class.simpleName }}")
        println("[Test] First call text: '$text1'")
        assertTrue(text1.contains("Answer 1"), "First call should have Answer 1")
        assertTrue(events1.any { it == AgentStreamEvent.Done }, "First call should have Done")

        // 第二轮：模拟用户发送跟进消息
        val events2 = mutableListOf<AgentStreamEvent>()
        val job2 = scope.launch {
            agent.chatWithTools("Second message").collect { event ->
                events2.add(event)
            }
        }
        job2.join()

        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Second call events: ${events2.map { it::class.simpleName }}")
        println("[Test] Second call text: '$text2'")
        assertTrue(text2.contains("Answer 2"), "Second call should have Answer 2")
        assertTrue(events2.any { it == AgentStreamEvent.Done }, "Second call should have Done")

        assertEquals(2, callCount, "Gateway should be called exactly 2 times")
        scope.cancel()
    }

    @Test
    fun `cancelling first collection then second call should work`() = runBlocking {
        var callCount = 0
        val gateway = object : ModelGateway() {
            override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                // 模拟慢速 LLM 响应
                delay(500)
                println("[Gateway] call #$callCount")
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

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // 第一轮：启动但很快取消（模拟用户快速发送第二条消息）
        val job1 = scope.launch {
            agent.chatWithTools("First message").collect { event ->
                println("[Test] First call event: ${event::class.simpleName}")
            }
        }
        // 给一点时间让 flow 启动
        delay(50)
        job1.cancelAndJoin()

        // 第二轮：正常收集
        val events2 = mutableListOf<AgentStreamEvent>()
        val job2 = scope.launch {
            agent.chatWithTools("Second message").collect { event ->
                events2.add(event)
            }
        }
        job2.join()

        val text2 = events2.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        println("[Test] Second call events: ${events2.map { it::class.simpleName }}")
        println("[Test] Second call text: '$text2'")
        assertTrue(text2.contains("Answer 2"), "Second call should have Answer 2")
        assertTrue(events2.any { it == AgentStreamEvent.Done }, "Second call should have Done")

        scope.cancel()
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
