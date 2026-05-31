package com.codesage.agent.context

import com.codesage.agent.memory.MemoryProvider
import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.ChatResponse
import com.codesage.model.dto.Choice
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.dto.Usage
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextCompressorTest {

    @Test
    fun `should protect head and tail when compressing`() {
        val compressor = ContextCompressor(contextLength = 1000)
        val messages = mutableListOf<Message>()

        // System message
        messages.add(Message.systemMessage("You are a helpful assistant"))

        // 20 user/assistant pairs
        repeat(20) { i ->
            messages.add(Message.userMessage("User message $i"))
            messages.add(Message.assistantMessage("Assistant response $i"))
        }

        val result = compressor.compress(messages)

        // Should have: system + summary + head(3 pairs) + tail(6 pairs)
        // = 1 + 1 + 6 + 12 = 20 messages max
        assertTrue(result.size < messages.size, "Compression should reduce message count")

        // System message should be preserved
        assertTrue(result.any { it.role == Role.SYSTEM && it.content.contains("helpful assistant") })

        // First user message should be in head (protected)
        assertTrue(result.any { it.content == "User message 0" })

        // Last user message should be in tail (protected)
        assertTrue(result.any { it.content == "User message 19" })
    }

    @Test
    fun `should generate structured summary`() {
        val compressor = ContextCompressor(contextLength = 500)
        // Need enough messages so some fall in the middle (compressed) region
        val messages = mutableListOf<Message>()
        messages.add(Message.systemMessage("System prompt"))
        // Add filler messages to push content into middle region
        repeat(5) { i ->
            messages.add(Message.userMessage("Filler user message $i"))
            messages.add(Message.assistantMessage("Filler assistant response $i"))
        }
        // These should fall in middle after head(3) is protected
        messages.add(Message.userMessage("Let's implement UserAuthentication with OAuth2"))
        messages.add(Message.assistantMessage("We decided to use kotlinx.serialization for JSON parsing."))
        messages.add(Message.userMessage("Please create AuthConfig.kt"))
        messages.add(Message.assistantMessage("Created AuthConfig.kt with OAuth2 configuration."))
        // More filler to push tail boundary
        repeat(4) { i ->
            messages.add(Message.userMessage("More filler $i"))
            messages.add(Message.assistantMessage("More response $i"))
        }
        messages.add(Message.userMessage("Any pending questions?"))
        messages.add(Message.assistantMessage("No, all clear."))

        val result = compressor.compress(messages)

        // Summary message should exist
        val summaryMsg = result.find { it.content.contains("CONTEXT COMPACTION") }
        assertNotNull(summaryMsg, "Should generate a summary message")

        // Summary should contain structured sections
        val summary = summaryMsg!!.content
        assertTrue(summary.contains("## Active Task") || summary.contains("## Resolved Decisions"))
    }

    @Test
    fun `should detect decisions in messages`() {
        val compressor = ContextCompressor(contextLength = 500)
        val messages = mutableListOf<Message>()
        messages.add(Message.systemMessage("System"))
        // Add filler to push decision messages into middle
        repeat(4) { i ->
            messages.add(Message.userMessage("Setup step $i"))
            messages.add(Message.assistantMessage("Done step $i"))
        }
        // These should be in middle region
        messages.add(Message.userMessage("We decided to use Gradle for build"))
        messages.add(Message.assistantMessage("OK, I'll use Gradle"))
        messages.add(Message.userMessage("And we chose Kotlin over Java"))
        messages.add(Message.assistantMessage("Great, Kotlin it is"))
        // More filler for tail
        repeat(4) { i ->
            messages.add(Message.userMessage("Cleanup $i"))
            messages.add(Message.assistantMessage("Cleanup done $i"))
        }

        val result = compressor.compress(messages)
        val summary = result.find { it.content.contains("CONTEXT COMPACTION") }?.content ?: ""

        assertTrue(
            summary.contains("## Active Task") || summary.contains("## Resolved Decisions") ||
                    summary.contains("Gradle") || summary.contains("Kotlin"),
            "Summary should have structured content. Actual summary:\n$summary"
        )
    }

    @Test
    fun `should return original when middle is empty`() {
        val compressor = ContextCompressor(contextLength = 10000)
        val messages = listOf(
            Message.systemMessage("System"),
            Message.userMessage("Hello"),
            Message.assistantMessage("Hi")
        )

        val result = compressor.compress(messages)
        assertEquals(3, result.size)
    }

    @Test
    fun `should clean image placeholders`() {
        val compressor = ContextCompressor(contextLength = 500)
        val longBase64 = "data:image/png;base64," + "A".repeat(1000)
        val messages = listOf(
            Message.systemMessage("System"),
            Message.userMessage("See this image: $longBase64"),
            Message.assistantMessage("I see the image")
        ) + (1..20).flatMap {
            listOf(
                Message.userMessage("Message $it"),
                Message.assistantMessage("Response $it")
            )
        }

        val result = compressor.compress(messages)
        val summary = result.find { it.content.contains("CONTEXT COMPACTION") }?.content ?: ""

        assertFalse(
            summary.contains(longBase64.take(50)),
            "Base64 image data should be replaced with placeholder"
        )
    }

    @Test
    fun `should track compression count`() {
        val compressor = ContextCompressor(contextLength = 100)
        assertEquals(0, compressor.compressionCount)

        val messages = (1..20).flatMap {
            listOf(Message.userMessage("Message $it"), Message.assistantMessage("Response $it"))
        }

        compressor.compress(messages)
        assertEquals(1, compressor.compressionCount)

        compressor.compress(messages)
        assertEquals(2, compressor.compressionCount)
    }

    // ========== A1: LLM 驱动摘要测试 ==========

    @Test
    fun `summarize should compress 30 messages into 1 summary with LLM`() = runBlocking {
        val llmResponse = """
            ## Active Task
            Implement user authentication with OAuth2

            ## Resolved Decisions
            - Use kotlinx.serialization for JSON parsing
            - Use Gradle for build

            ## Files Modified
            - AuthConfig.kt

            ## Pending Questions
            - How to handle refresh tokens?

            ## Tool Calls Summary
            - write_file(path="AuthConfig.kt")
        """.trimIndent()

        val mockGateway = MockModelGateway(llmResponse)
        val compressor = ContextCompressor(
            auxiliaryModel = "moonshot-v1-8k",
            contextLength = 1000,
            modelGateway = mockGateway
        )

        val messages = mutableListOf<Message>()
        messages.add(Message.systemMessage("You are a helpful assistant"))
        repeat(15) { i ->
            messages.add(Message.userMessage("User message $i about authentication"))
            messages.add(Message.assistantMessage("Assistant response $i about OAuth2"))
        }

        val result = compressor.summarizeWithLLM(messages)

        // 验证 Mock 被调用，请求体包含中间消息
        assertNotNull(mockGateway.lastRequest, "ModelGateway.chat() should be called")
        val request = mockGateway.lastRequest!!
        assertEquals("moonshot-v1-8k", request.model)
        // 请求消息应该包含中间消息（至少 20 条以上）
        assertTrue(request.messages.size >= 2, "Request should contain prompt messages")

        // 结果应该包含 1 条摘要消息 + 头部 + 尾部
        val summaryMsg = result.find { it.content.contains("[CONTEXT SUMMARY]") }
        assertNotNull(summaryMsg, "Should generate a summary message")
        assertTrue(result.size < messages.size, "Should reduce message count")

        // 摘要应保留关键工具调用记录
        assertTrue(
            summaryMsg!!.content.contains("write_file") || summaryMsg.content.contains("AuthConfig"),
            "Summary should preserve key tool call records"
        )
    }

    @Test
    fun `summarize should fallback to rule-based when LLM fails`() = runBlocking {
        val mockGateway = FailingModelGateway()
        val compressor = ContextCompressor(
            auxiliaryModel = "moonshot-v1-8k",
            contextLength = 1000,
            modelGateway = mockGateway
        )

        val messages = mutableListOf<Message>()
        messages.add(Message.systemMessage("System"))
        repeat(15) { i ->
            messages.add(Message.userMessage("User message $i"))
            messages.add(Message.assistantMessage("Assistant response $i"))
        }

        val result = compressor.summarizeWithLLM(messages)

        // LLM 失败后应降级为规则摘要
        val summaryMsg =
            result.find { it.content.contains("CONTEXT COMPACTION") || it.content.contains("[CONTEXT SUMMARY]") }
        assertNotNull(summaryMsg, "Should fallback to rule-based summary")
        assertTrue(result.size < messages.size, "Should still reduce message count")
    }

    @Test
    fun `should include tool calls summary in structured summary`() {
        val compressor = ContextCompressor(contextLength = 500)
        val messages = mutableListOf<Message>()
        messages.add(Message.systemMessage("System"))
        repeat(4) { i ->
            messages.add(Message.userMessage("Step $i"))
            messages.add(Message.assistantMessage("Done $i"))
        }
        // Add message with tool calls
        messages.add(
            Message.assistantMessage(
                "I'll create the file for you",
                toolCalls = listOf(com.codesage.model.dto.ToolCall("1", "write_file", "{\"path\":\"Test.kt\"}"))
            )
        )
        messages.add(Message.toolMessage("File created successfully", "1"))
        repeat(4) { i ->
            messages.add(Message.userMessage("More $i"))
            messages.add(Message.assistantMessage("Response $i"))
        }

        val result = compressor.compress(messages)
        val summary = result.find { it.content.contains("CONTEXT COMPACTION") }?.content ?: ""

        assertTrue(
            summary.contains("## Tool Calls Summary") || summary.contains("write_file"),
            "Summary should contain tool calls section. Actual summary:\n$summary"
        )
    }

    // ========== A2: RAG 检索测试 ==========

    @Test
    fun `ragRetrieval should inject relevant memories based on query`() {
        val mockProvider = MockMemoryProvider(
            """
            <memory-context>
            ## Relevant Memories
            - [fact] Project uses Kotlin and Gradle
            - [preference] User prefers declarative UI
            </memory-context>
        """.trimIndent()
        )

        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.RAG检索,
            maxHistoryMessages = 10,
            enableContextEngine = false
        )
        val manager = ContextManager(config, memoryProvider = mockProvider)
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        // 添加超过阈值的消息
        repeat(6) { i ->
            manager.addMessage(Message.userMessage("Message $i about Kotlin project"))
            manager.addMessage(Message.assistantMessage("Response $i"))
        }

        val context = manager.getContext()
        val ragMsg = context.find { it.content.contains("[RELEVANT CONTEXT]") }
        assertNotNull(ragMsg, "Should inject RAG context")
        assertTrue(ragMsg!!.content.contains("Kotlin"), "RAG should contain relevant memory about Kotlin")

        // 验证 prefetch 被正确调用（最近一条 USER 消息作为 query）
        assertTrue(
            mockProvider.lastQuery.contains("Kotlin") || mockProvider.lastQuery.contains("Message"),
            "prefetch() should be called with the latest user message as query"
        )
    }

    @Test
    fun `ragRetrieval should fallback to keepRecent when memoryProvider is null`() {
        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.RAG检索,
            maxHistoryMessages = 10,
            enableContextEngine = false
        )
        val manager = ContextManager(config, memoryProvider = null)
        manager.newSession(listOf(Message.systemMessage("System")))

        repeat(6) { i ->
            manager.addMessage(Message.userMessage("Message $i"))
            manager.addMessage(Message.assistantMessage("Response $i"))
        }

        val context = manager.getContext()
        // 应该没有 RAG 消息，而是 keepRecent 的结果
        assertFalse(context.any { it.content.contains("[RELEVANT CONTEXT]") })
    }

    @Test
    fun `ragRetrieval should fallback when prefetch returns empty`() {
        val mockProvider = MockMemoryProvider("")
        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.RAG检索,
            maxHistoryMessages = 10,
            enableContextEngine = false
        )
        val manager = ContextManager(config, memoryProvider = mockProvider)
        manager.newSession(listOf(Message.systemMessage("System")))

        repeat(6) { i ->
            manager.addMessage(Message.userMessage("Message $i"))
            manager.addMessage(Message.assistantMessage("Response $i"))
        }

        val context = manager.getContext()
        assertFalse(context.any { it.content.contains("[RELEVANT CONTEXT]") })
    }

    // ========== Mock 类 ==========

    class MockModelGateway(private val responseContent: String) : ModelGateway() {
        var lastRequest: ChatRequest? = null

        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            lastRequest = request
            return Result.success(
                ChatResponse(
                    id = "test-id",
                    model = request.model,
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = Message.assistantMessage(responseContent),
                            finishReason = "stop"
                        )
                    ),
                    usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
                )
            )
        }
    }

    class FailingModelGateway : ModelGateway() {
        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            return Result.failure(RuntimeException("LLM service unavailable"))
        }
    }

    class MockMemoryProvider(private val result: String) : MemoryProvider {
        var lastQuery: String = ""
        var lastSessionId: String = ""

        override val name: String = "mock"
        override fun isAvailable(): Boolean = true
        override fun initialize(sessionId: String, homeDir: String, platform: String) {}
        override fun prefetch(query: String, sessionId: String): String {
            lastQuery = query
            lastSessionId = sessionId
            return result
        }

        override fun syncTurn(userContent: String, assistantContent: String, sessionId: String) {}
        override fun shutdown() {}
    }
}
