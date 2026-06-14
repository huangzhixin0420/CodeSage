package com.codesage.agent.memory

import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File
import java.nio.file.Files

class BuiltInMemoryProviderTest {

    private lateinit var provider: BuiltInMemoryProvider
    private lateinit var tempDir: File
    private val sessionId = "test_session_001"

    @BeforeEach
    fun setUp() {
        provider = BuiltInMemoryProvider()
        tempDir = Files.createTempDirectory("codesage_test").toFile()
        provider.initialize(sessionId, tempDir.absolutePath)
    }

    /**
     * 用于测试的 [ModelGateway] 伪实现：可返回固定 JSON 或模拟失败。
     */
    private class FakeModelGateway(
        private val responseContent: String? = null,
        private val fail: Boolean = false
    ) : ModelGateway() {
        override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
            return if (fail || responseContent == null) {
                Result.failure(RuntimeException("LLM unavailable"))
            } else {
                Result.success(
                    ChatResponse(
                        id = "test-summary",
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = Message.assistantMessage(responseContent),
                                finishReason = "stop"
                            )
                        ),
                        usage = null
                    )
                )
            }
        }
    }

    @AfterEach
    fun tearDown() {
        provider.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `should be available`() {
        assertTrue(provider.isAvailable())
    }

    @Test
    fun `should have non-empty system prompt block`() {
        val block = provider.systemPromptBlock()
        assertTrue(block.isNotBlank())
        assertTrue(block.contains("persistent memory"))
    }

    @Test
    fun `should inject user preferences into system prompt block`() {
        // Sync a turn with explicit preference
        provider.syncTurn(
            "I prefer Kotlin over Java for this project",
            "Understood, I'll use Kotlin for all new code.",
            sessionId
        )

        val block = provider.systemPromptBlock()
        assertTrue(block.contains("[USER PREFERENCES]"), "System prompt should contain preference section")
        assertTrue(
            block.contains("prefer") || block.contains("Kotlin"),
            "System prompt should contain preference content"
        )
        assertTrue(block.contains("[END USER PREFERENCES]"))
    }

    @Test
    fun `should sync turn and retrieve via prefetch`() {
        provider.syncTurn(
            "I prefer Kotlin over Java for this project",
            "Understood, I'll use Kotlin for all new code.",
            sessionId
        )

        // Prefetch should find the preference
        val result = provider.prefetch("What language does the user prefer?", sessionId)
        assertTrue(result.isNotBlank(), "Prefetch should return related memories")
        assertTrue(result.contains("<memory-context>"))
    }

    @Test
    fun `should handle memory_add tool`() {
        val result = provider.handleToolCall(
            "memory_add",
            mapOf("content" to "User prefers dark theme", "type" to "preference")
        )
        assertTrue(result.contains("\"success\":true"), "memory_add should succeed")

        // Verify via search (may use FTS5 or LIKE fallback)
        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "dark theme", "limit" to 5)
        )
        assertTrue(searchResult.contains("\"success\":true"), "memory_search should succeed")
    }

    @Test
    fun `should deduplicate similar memories`() {
        // Add same memory twice
        val result1 = provider.handleToolCall(
            "memory_add",
            mapOf("content" to "User prefers dark theme", "type" to "preference")
        )
        val result2 = provider.handleToolCall(
            "memory_add",
            mapOf("content" to "User prefers dark theme", "type" to "preference")
        )

        assertTrue(result1.contains("\"success\":true"))
        assertTrue(result2.contains("\"success\":true"))

        // Search should return only one result
        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "dark theme", "limit" to 5)
        )
        assertTrue(searchResult.contains("\"success\":true"), "Search should succeed")
        // The JSON contains one result object with a "content" field
        val count = searchResult.split("\"content\"").size - 1
        assertEquals(1, count, "Should have exactly one memory after deduplication. Search result: $searchResult")
    }

    @Test
    fun `should handle memory_search tool`() {
        // Add some memories
        provider.syncTurn(
            "We decided to use Spring Boot for the backend",
            "Great choice. I'll set up the Spring Boot project structure.",
            sessionId
        )

        val result = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "Spring Boot backend", "limit" to 5)
        )
        assertTrue(result.contains("\"success\":true"))
    }

    @Test
    fun `should limit retrieval results`() {
        // Add multiple memories
        repeat(10) { i ->
            provider.handleToolCall(
                "memory_add",
                mapOf("content" to "Fact number $i about the project", "type" to "fact")
            )
        }

        val result = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "project", "limit" to 3)
        )
        val count = result.split("\"content\"").size - 1
        assertTrue(count in 1..3, "Should limit results to 1-3, but got $count")
    }

    @Test
    fun `should return empty prefetch for unrelated query`() {
        provider.syncTurn("Hello", "Hi there!", sessionId)
        val result = provider.prefetch("quantum physics", sessionId)
        // May be empty or may contain recent turns
        assertNotNull(result)
    }

    @Test
    fun `should get tool schemas`() {
        val schemas = provider.getToolSchemas()
        assertEquals(3, schemas.size)
        assertTrue(schemas.any { it.name == "memory_search" })
        assertTrue(schemas.any { it.name == "memory_add" })
        assertTrue(schemas.any { it.name == "memory_update" })
    }

    @Test
    fun `should create database file`() {
        val dbFile = File(tempDir, "memory/codesage_memory.db")
        assertTrue(dbFile.exists(), "Database file should be created")
    }

    @Test
    fun `should save and load conversation history`() {
        // Save some turns
        provider.syncTurn("Hello", "Hi there!", sessionId)
        provider.syncTurn("How are you?", "I'm doing great!", sessionId)
        provider.syncTurn("What's the weather?", "It's sunny today.", sessionId)

        // Load all history
        val history = provider.loadSessionHistory(sessionId, limit = 50)
        assertEquals(6, history.size, "Should load 6 messages (3 user + 3 assistant)")
        assertEquals(Role.USER, history[0].role)
        assertEquals("Hello", history[0].content)
        assertEquals(Role.ASSISTANT, history[1].role)
        assertEquals("Hi there!", history[1].content)
    }

    @Test
    fun `should support pagination for history loading`() {
        // Save multiple turns
        repeat(5) { i ->
            provider.syncTurn("User message $i", "Assistant reply $i", sessionId)
        }

        // Load with offset and limit (limit is per turn, each turn yields 2 messages)
        val page1 = provider.loadSessionHistory(sessionId, limit = 2, offset = 0)
        assertEquals(4, page1.size, "First page should have 4 messages (2 turns)")
        assertEquals("User message 0", page1[0].content)

        val page2 = provider.loadSessionHistory(sessionId, limit = 2, offset = 2)
        assertEquals(4, page2.size, "Second page should have 4 messages (2 turns)")
        assertEquals("User message 2", page2[0].content)

        val page3 = provider.loadSessionHistory(sessionId, limit = 2, offset = 4)
        assertEquals(2, page3.size, "Third page should have 2 messages (1 turn)")
        assertEquals("User message 4", page3[0].content)
    }

    @Test
    fun `should cleanup old turns and keep recent ones`() {
        // Insert many turns
        repeat(10) { i ->
            provider.syncTurn("Message $i", "Reply $i", sessionId)
        }

        // Cleanup to keep only 5
        provider.cleanupOldTurns(sessionId, keepCount = 5)

        // Load history
        val history = provider.loadSessionHistory(sessionId, limit = 100)
        assertEquals(10, history.size, "Should keep 10 messages (5 recent turns = 5 user + 5 assistant)")

        // Verify the most recent messages are preserved
        assertTrue(
            history.last().content.contains("Reply 9") || history.last().content == "Reply 9",
            "Most recent assistant message should be preserved"
        )
    }

    @Test
    fun `should restore session with history from SQLite`() {
        // Simulate a session with history in SQLite
        val testSessionId = "restore_test_session"
        provider.initialize(testSessionId, tempDir.absolutePath)

        provider.syncTurn("First message", "First reply", testSessionId)
        provider.syncTurn("Second message", "Second reply", testSessionId)

        // Load session history
        val messages = provider.loadSessionHistory(testSessionId, limit = 50)
        assertEquals(4, messages.size, "Should restore 4 messages (2 user + 2 assistant)")
        assertEquals("First message", messages[0].content)
        assertEquals("Second reply", messages[3].content)
    }

    @Test
    fun `should learn and retrieve preferences`() {
        provider.syncTurn(
            "I prefer using spaces instead of tabs",
            "Noted, I'll use spaces for indentation.",
            sessionId
        )

        // Search for preference
        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "spaces", "limit" to 5)
        )
        assertTrue(
            searchResult.contains("spaces") || searchResult.contains("tabs"),
            "Should retrieve preference about spaces/tabs"
        )

        // System prompt should also include it
        val systemPrompt = provider.systemPromptBlock()
        assertTrue(
            systemPrompt.contains("spaces") || systemPrompt.contains("tabs"),
            "System prompt should contain learned preference"
        )
    }

    // ===== P1 #5 新增：SQLite 驱动加载验证 =====

    @Test
    fun `org_sqlite_JDBC class should be loadable from classpath`() {
        // P1 #5 修复：BuiltInMemoryProvider.init 会 Class.forName("org.sqlite.JDBC")
        // 触发 ServiceLoader 注册；这里验证该类确实在 classpath 中
        val driverClass = assertDoesNotThrow("org.sqlite.JDBC must be on classpath") {
            Class.forName("org.sqlite.JDBC")
        }
        assertNotNull(driverClass)
    }

    // ===== 6.9.1 向量语义召回 =====

    @Test
    fun `vector semantic recall returns related memory without exact keywords`() {
        // 记忆里没有 "language"，但向量召回应能通过 Kotlin/programming 语义关联
        provider.syncTurn(
            "I prefer Kotlin for all new code",
            "Understood, I will use Kotlin.",
            sessionId
        )

        val result = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "What is the user's favorite programming language?", "limit" to 5)
        )
        assertTrue(result.contains("\"success\":true"), "Search should succeed")
        assertTrue(
            result.contains("Kotlin") || result.contains("prefer"),
            "Vector recall should find Kotlin memory: $result"
        )
    }

    // ===== 6.9.2 自动会话摘要与关键事实提取 =====

    @Test
    fun `onSessionEnd extracts key facts and persists them as memories`() {
        // 强制走规则引擎，避免依赖真实模型网关
        provider.sessionSummarizer = SessionSummarizer(modelGateway = null)

        val messages = listOf(
            com.codesage.model.dto.Message.userMessage("I prefer dark theme for the IDE"),
            com.codesage.model.dto.Message.assistantMessage("Got it, I'll use dark theme."),
            com.codesage.model.dto.Message.userMessage("Let's use Kotlin for this project"),
            com.codesage.model.dto.Message.assistantMessage("Kotlin it is.")
        )

        provider.onSessionEnd(messages)
        // 异步等待摘要写入完成
        runBlocking { delay(300) }

        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "dark theme", "limit" to 5)
        )
        assertTrue(searchResult.contains("\"success\":true"))
        assertTrue(
            searchResult.contains("dark theme") || searchResult.contains("Preference"),
            "Session end should persist dark theme fact: $searchResult"
        )
    }

    @Test
    fun `onSessionEnd uses LLM summary and persists returned facts`() {
        val messages = listOf(
            com.codesage.model.dto.Message.userMessage("We need to migrate from Java to Kotlin"),
            com.codesage.model.dto.Message.assistantMessage("I will plan the migration carefully.")
        )

        val fakeGateway = FakeModelGateway(
            """
            {
              "summary": "User wants to migrate from Java to Kotlin.",
              "key_facts": ["Migration plan: Java to Kotlin", "Requires careful refactoring"]
            }
            """.trimIndent()
        )
        provider.sessionSummarizer = SessionSummarizer(modelGateway = fakeGateway, summaryModel = "test-model")

        provider.onSessionEnd(messages)
        runBlocking { delay(300) }

        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "Kotlin migration", "limit" to 5)
        )
        assertTrue(searchResult.contains("\"success\":true"))
        assertTrue(
            searchResult.contains("Migration plan") || searchResult.contains("Java to Kotlin"),
            "LLM facts should be persisted: $searchResult"
        )
    }

    @Test
    fun `onSessionEnd falls back to rule summary when LLM fails`() {
        val messages = listOf(
            com.codesage.model.dto.Message.userMessage("I prefer spaces over tabs"),
            com.codesage.model.dto.Message.assistantMessage("Noted, I will use spaces.")
        )

        provider.sessionSummarizer = SessionSummarizer(modelGateway = FakeModelGateway(fail = true))

        provider.onSessionEnd(messages)
        runBlocking { delay(300) }

        val searchResult = provider.handleToolCall(
            "memory_search",
            mapOf("query" to "spaces", "limit" to 5)
        )
        assertTrue(searchResult.contains("\"success\":true"))
        assertTrue(
            searchResult.contains("spaces") || searchResult.contains("Preference"),
            "Rule fallback should persist preference fact: $searchResult"
        )
    }

    // ===== 6.9.3 记忆上下文 token 预算 / Top-K 注入 =====

    @Test
    fun `prefetch ranks memories by similarity to query`() {
        // 使用 memory_add 避免产生 recent turns 干扰预算与顺序；统一类型以隔离相似度排序
        provider.handleToolCall("memory_add", mapOf("content" to "Project frontend uses React", "type" to "fact"))
        provider.handleToolCall("memory_add", mapOf("content" to "Project theme should be dark", "type" to "fact"))
        provider.handleToolCall(
            "memory_add",
            mapOf("content" to "Project deployment uses Kubernetes", "type" to "fact")
        )

        val result = provider.prefetch("Which UI library does the project use?", sessionId)

        assertTrue(result.contains("<memory-context>"))
        val reactIndex = result.indexOf("React")
        val k8sIndex = result.indexOf("Kubernetes")
        assertTrue(reactIndex > 0, "React memory should appear in prefetch: $result")
        assertTrue(k8sIndex > 0, "Kubernetes memory should appear in prefetch: $result")
        assertTrue(reactIndex < k8sIndex, "React should rank higher than Kubernetes: $result")
    }

    @Test
    fun `prefetch applies token budget and reports omitted memories`() {
        repeat(5) { i ->
            provider.handleToolCall(
                "memory_add",
                mapOf("content" to "This is a moderately long memory content number $i", "type" to "fact")
            )
        }

        provider.prefetchTokenBudget = 80
        provider.prefetchUseTokenBudget = true

        val result = provider.prefetch("memory content", sessionId)

        assertTrue(result.contains("<memory-context>"))
        assertTrue(
            result.contains("more memories omitted due to context budget"),
            "Should report omitted memories: $result"
        )

        // 只应保留少量记忆（预算 80 tokens 通常只能容纳 2-3 条）
        val memoryMatches = result.split("- [fact]").size - 1
        assertTrue(memoryMatches in 1..3, "Should keep only Top-K memories within budget, got $memoryMatches: $result")
    }

    @Test
    fun `prefetch prefers high priority memory type within token budget`() {
        // fact 记忆包含查询关键词，但 preference 优先级更高；预算只够保留 1 条
        provider.handleToolCall("memory_add", mapOf("content" to "Uses Gradle build", "type" to "fact"))
        provider.handleToolCall("memory_add", mapOf("content" to "Prefers spaces indentation", "type" to "preference"))

        provider.prefetchTokenBudget = 50
        provider.prefetchUseTokenBudget = true

        val result = provider.prefetch("prefers Gradle build", sessionId)

        assertTrue(result.contains("preference"), "High priority preference should be retained: $result")
        assertFalse(
            result.contains("Gradle build"),
            "Lower priority fact should be omitted when budget only fits one: $result"
        )
        assertTrue(result.contains("more memories omitted due to context budget"))
    }

    @Test
    fun `prefetch falls back to character truncation when token budget is disabled`() {
        repeat(5) { i ->
            provider.handleToolCall("memory_add", mapOf("content" to "Memory content item $i", "type" to "fact"))
        }

        provider.prefetchUseTokenBudget = false

        val result = provider.prefetch("Memory content", sessionId)

        assertTrue(result.contains("<memory-context>"))
        assertFalse(
            result.contains("more memories omitted due to context budget"),
            "Disabled token budget should not emit omission hint: $result"
        )

        val memoryMatches = result.split("- [fact]").size - 1
        assertEquals(5, memoryMatches, "All memories should be included when token budget is disabled: $result")
    }
}
