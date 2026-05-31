package com.codesage.persistence

import com.codesage.agent.core.AgentSession
import com.codesage.model.dto.Message
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class ConversationPersistenceTest {

    private lateinit var testDir: File

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        testDir = File(
            System.getProperty("java.io.tmpdir"),
            "codesage_test_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
        )
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun `save and load session`() {
        val persistence = ConversationPersistence(testDir)
        val session = AgentSession(id = "test_1", name = "Test Session")
        val messages = listOf(
            Message.systemMessage("System prompt"),
            Message.userMessage("Hello"),
            Message.assistantMessage("Hi there!")
        )

        persistence.saveSession(session, messages)
        val loaded = persistence.loadSession("test_1")

        assertNotNull(loaded)
        assertEquals("test_1", loaded?.id)
        assertEquals("Test Session", loaded?.name)
        assertEquals(3, loaded?.messages?.size)
    }

    @Test
    fun `load non-existent session returns null`() {
        val persistence = ConversationPersistence(testDir)
        assertNull(persistence.loadSession("non_existent"))
    }

    @Test
    fun `delete session removes file`() {
        val persistence = ConversationPersistence(testDir)
        val session = AgentSession(id = "to_delete")
        persistence.saveSession(session, emptyList())

        assertTrue(persistence.deleteSession("to_delete"))
        assertNull(persistence.loadSession("to_delete"))
    }

    @Test
    fun `load all sessions returns sorted list`() {
        val persistence = ConversationPersistence(testDir)
        persistence.saveSession(AgentSession(id = "old", lastActivityAt = 1000), emptyList())
        persistence.saveSession(AgentSession(id = "new", lastActivityAt = 2000), emptyList())

        val all = persistence.loadAllSessions()
        assertEquals(2, all.size)
        assertEquals("new", all[0].id)
    }

    @Test
    fun `cleanup removes old sessions`() = runBlocking {
        val persistence = ConversationPersistence(testDir)
        repeat(10) { i ->
            persistence.saveSessionSync(AgentSession(id = "session_$i"), emptyList())
        }

        persistence.cleanupOldSessions(keepCount = 5)
        val remaining = persistence.loadAllSessions()
        assertTrue(remaining.size <= 5)
    }

    @Test
    fun `persisted message converts correctly`() {
        val persistence = ConversationPersistence(testDir)
        val msg = Message.userMessage("Test content")
        // Test via save/load roundtrip
        val session = AgentSession(id = "roundtrip_test")
        persistence.saveSession(session, listOf(msg))
        val loaded = persistence.loadSession("roundtrip_test")

        assertNotNull(loaded)
        assertEquals(1, loaded?.messages?.size)
        assertEquals("USER", loaded!!.messages[0].role)
        assertEquals("Test content", loaded.messages[0].content)
    }
}
