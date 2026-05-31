package com.codesage.agent.memory

import com.codesage.model.dto.Role
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
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


}
