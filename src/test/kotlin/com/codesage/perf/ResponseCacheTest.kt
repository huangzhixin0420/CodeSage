package com.codesage.perf

import com.codesage.model.dto.ChatRequest
import com.codesage.model.dto.ChatResponse
import com.codesage.model.dto.Message
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ResponseCacheTest {

    @Test
    fun `cache miss returns null`() {
        val cache = ResponseCache()
        val request = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello"))
        )
        assertNull(cache.get(request))
    }

    @Test
    fun `cache hit returns response`() {
        val cache = ResponseCache()
        val request = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello"))
        )
        val response = ChatResponse(
            id = "1",
            choices = listOf(
                com.codesage.model.dto.Choice(
                    index = 0,
                    message = Message.assistantMessage("Hello")
                )
            ),
            model = "test"
        )

        cache.put(request, response)
        val cached = cache.get(request)

        assertNotNull(cached)
        assertEquals(response.id, cached?.id)
    }

    @Test
    fun `stream request is not cached`() {
        val cache = ResponseCache()
        val request = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello")),
            stream = true
        )
        val response = ChatResponse(id = "1", choices = emptyList(), model = "test")

        cache.put(request, response)
        assertNull(cache.get(request))
    }

    @Test
    fun `invalidate clears cache`() {
        val cache = ResponseCache()
        val request = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello"))
        )
        cache.put(
            request, ChatResponse(
                id = "1",
                choices = listOf(com.codesage.model.dto.Choice(index = 0, message = Message.assistantMessage("Hi"))),
                model = "test"
            )
        )
        cache.invalidate()

        assertNull(cache.get(request))
    }

    @Test
    fun `cache stats are accurate`() {
        val cache = ResponseCache()
        val request = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello"))
        )
        cache.put(
            request, ChatResponse(
                id = "1",
                choices = listOf(com.codesage.model.dto.Choice(index = 0, message = Message.assistantMessage("Hi"))),
                model = "test"
            )
        )

        val stats = cache.getStats()
        assertEquals(1, stats.totalEntries)
    }

    @Test
    fun `different requests have different keys`() {
        val cache = ResponseCache()
        val request1 = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("Hello"))
        )
        val request2 = ChatRequest(
            model = "test",
            messages = listOf(Message.userMessage("World"))
        )

        assertNotEquals(
            cache.generateKey(request1),
            cache.generateKey(request2)
        )
    }
}
