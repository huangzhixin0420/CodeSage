package com.codesage.agent.context

import com.codesage.agent.memory.MemoryProvider
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextManagerTest {

    @Test
    fun `HYBRID strategy should use fewer tokens than KEEP_RECENT for 80 messages`() {
        val configHybrid = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.HYBRID,
            maxHistoryMessages = 50,
            summarizeThreshold = 30,
            enableContextEngine = false, // 禁用 token 压缩路径，确保 truncate() 被调用
            contextLength = 5000
        )
        val managerHybrid = ContextManager(configHybrid)
        managerHybrid.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(40) { i ->
            managerHybrid.addMessage(Message.userMessage("User message $i with some content about implementation and design patterns"))
            managerHybrid.addMessage(Message.assistantMessage("Assistant response $i with technical details and code examples"))
        }

        val configRecent = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.KEEP_RECENT,
            maxHistoryMessages = 50,
            enableContextEngine = false,
            contextLength = 5000
        )
        val managerRecent = ContextManager(configRecent)
        managerRecent.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(40) { i ->
            managerRecent.addMessage(Message.userMessage("User message $i with some content about implementation and design patterns"))
            managerRecent.addMessage(Message.assistantMessage("Assistant response $i with technical details and code examples"))
        }

        val hybridContext = managerHybrid.getContext()
        val recentContext = managerRecent.getContext()

        val hybridTokens = TokenEstimator.estimateMessagesTokens(hybridContext)
        val recentTokens = TokenEstimator.estimateMessagesTokens(recentContext)

        assertTrue(
            hybridTokens < recentTokens,
            "HYBRID should use fewer tokens than KEEP_RECENT. Hybrid=$hybridTokens, Recent=$recentTokens"
        )
    }

    @Test
    fun `HYBRID should include summary message`() {
        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.HYBRID,
            maxHistoryMessages = 50,
            summarizeThreshold = 30,
            enableContextEngine = false,
            contextLength = 5000
        )
        val manager = ContextManager(config)
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(20) { i ->
            manager.addMessage(Message.userMessage("User message $i"))
            manager.addMessage(Message.assistantMessage("Assistant response $i"))
        }

        val context = manager.getContext()
        val summaryMsg = context.find { it.content.contains("[CONTEXT SUMMARY]") }
        assertNotNull(summaryMsg, "HYBRID should include a structured summary message")
    }

    @Test
    fun `HYBRID should include RAG context when memoryProvider is available`() {
        val mockProvider = object : MemoryProvider {
            override val name: String = "mock"
            override fun isAvailable(): Boolean = true
            override fun initialize(sessionId: String, homeDir: String, platform: String) {}
            override fun prefetch(query: String, sessionId: String): String {
                return if (query.isNotBlank()) """
                    <memory-context>
                    ## Relevant Memories
                    - [fact] User prefers Kotlin coroutines
                    </memory-context>
                """.trimIndent() else ""
            }

            override fun syncTurn(userContent: String, assistantContent: String, sessionId: String) {}
            override fun shutdown() {}
        }

        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.HYBRID,
            maxHistoryMessages = 50,
            summarizeThreshold = 30,
            enableContextEngine = false,
            contextLength = 5000
        )
        val manager = ContextManager(config, memoryProvider = mockProvider)
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(20) { i ->
            manager.addMessage(Message.userMessage("User message $i about coroutines"))
            manager.addMessage(Message.assistantMessage("Assistant response $i"))
        }

        val context = manager.getContext()
        val ragMsg = context.find { it.content.contains("[RELEVANT CONTEXT]") }
        assertNotNull(ragMsg, "HYBRID should include RAG context when memoryProvider is available")
    }

    @Test
    fun `HYBRID message order should be system then RAG then summary then head then tail`() {
        val mockProvider = object : MemoryProvider {
            override val name: String = "mock"
            override fun isAvailable(): Boolean = true
            override fun initialize(sessionId: String, homeDir: String, platform: String) {}
            override fun prefetch(query: String, sessionId: String): String {
                return "<memory-context>## Relevant Memories\n- [fact] Test memory</memory-context>"
            }

            override fun syncTurn(userContent: String, assistantContent: String, sessionId: String) {}
            override fun shutdown() {}
        }

        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.HYBRID,
            maxHistoryMessages = 50,
            summarizeThreshold = 30,
            enableContextEngine = false,
            contextLength = 5000
        )
        val manager = ContextManager(config, memoryProvider = mockProvider)
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(20) { i ->
            manager.addMessage(Message.userMessage("User message $i"))
            manager.addMessage(Message.assistantMessage("Assistant response $i"))
        }

        val context = manager.getContext()
        val roles = context.map { it.role }

        // 第一个应该是 SYSTEM
        assertEquals(Role.SYSTEM, roles.first(), "First message should be system")

        // 检查顺序：SYSTEM → RAG(SYSTEM) → SUMMARY(SYSTEM) → USER/ASSISTANT
        val ragIndex = context.indexOfFirst { it.content.contains("[RELEVANT CONTEXT]") }
        val summaryIndex = context.indexOfFirst { it.content.contains("[CONTEXT SUMMARY]") }

        assertTrue(ragIndex > 0, "RAG should be after system messages")
        assertTrue(summaryIndex > ragIndex, "Summary should be after RAG")
    }

    @Test
    fun `getInstance should return new instance each time`() {
        @Suppress("DEPRECATION")
        val instance1 = ContextManager.getInstance()

        @Suppress("DEPRECATION")
        val instance2 = ContextManager.getInstance()

        instance1.addMessage(Message.userMessage("Message 1"))
        instance2.addMessage(Message.userMessage("Message 2"))

        assertEquals(1, instance1.size(), "Instances should be independent")
        assertEquals(1, instance2.size(), "Instances should be independent")
    }

    @Test
    fun `context manager should be thread safe`() {
        val config = ContextManagementConfig(
            maxHistoryMessages = 200,
            summarizeThreshold = 200,
            enableContextEngine = false
        )
        val manager = ContextManager(config)
        manager.newSession(listOf(Message.systemMessage("System")))

        val threads = (1..10).map { i ->
            Thread {
                repeat(10) { j ->
                    manager.addMessage(Message.userMessage("Thread $i message $j"))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // 100 user messages + 1 system message = 101
        assertEquals(101, manager.size(), "All messages should be added safely")
    }

    @Test
    fun `summarize strategy should compress using context engine`() {
        val config = ContextManagementConfig(
            truncationStrategy = TruncationStrategy.SUMMARIZE,
            maxHistoryMessages = 50,
            summarizeThreshold = 30,
            enableContextEngine = true,
            contextLength = 5000
        )
        val manager = ContextManager(config)
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        repeat(20) { i ->
            manager.addMessage(Message.userMessage("User message $i"))
            manager.addMessage(Message.assistantMessage("Assistant response $i"))
        }

        val context = manager.getContext()
        assertTrue(context.size < 41, "SUMMARIZE should reduce message count below original 41")
    }

    @Test
    fun `newSession should accept sessionId`() {
        val manager = ContextManager()
        manager.newSession(
            systemMessages = listOf(Message.systemMessage("System")),
            newSessionId = "test-session-123"
        )

        assertEquals(1, manager.size())
        val context = manager.getContext()
        assertEquals(Role.SYSTEM, context.first().role)
    }

    @Test
    fun `injectMemoryContext should not duplicate memory system messages`() {
        val manager = ContextManager()
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        // 注入多种格式的记忆上下文
        manager.injectMemoryContext("<memory-context>## Recent</memory-context>")
        manager.injectMemoryContext("<memory-context>## Recent Updated</memory-context>")
        manager.injectMemoryContext("<memory-context>## Final Version</memory-context>")

        val context = manager.getContext()
        val systemMessages = context.filter { it.role == Role.SYSTEM }

        // injectMemoryContext 会移除所有包含 <memory-context> 的旧系统消息，只保留最新的一个
        assertEquals(
            2, systemMessages.size,
            "Should have 2 system messages: 1 original + 1 latest memory context. Actual: ${
                systemMessages.map { it.content.take(30) }
            }"
        )

        // 验证最新的记忆上下文被保留
        assertTrue(
            systemMessages.any { it.content.contains("## Final Version") },
            "Latest memory-context should be preserved"
        )
    }

    @Test
    fun `injectMemoryContext should handle removal of all memory formats safely`() {
        val manager = ContextManager()
        manager.newSession(listOf(Message.systemMessage("System prompt")))

        // 先注入一个记忆上下文
        manager.injectMemoryContext("[MEMORY SYSTEM] Memory 1")
        manager.addMessage(Message.userMessage("Hello"))
        manager.addMessage(Message.assistantMessage("Hi"))

        // 再注入另一个，应该替换前面的
        manager.injectMemoryContext("[SYSTEM NOTE: Memory 2]")

        val context = manager.getContext()
        // 原始系统提示 + 1 个记忆 + user + assistant = 4
        assertEquals(4, context.size)
        assertTrue(context.any { it.content == "System prompt" })
        assertTrue(context.any { it.content == "[SYSTEM NOTE: Memory 2]" })
        assertFalse(context.any { it.content == "[MEMORY SYSTEM] Memory 1" })
    }
}
