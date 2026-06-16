package com.codesage.model

import com.codesage.model.adapter.OpenAICompatibleAdapter
import com.codesage.model.dto.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OpenAICompatibleAdapterTest {

    private val adapter = TestAdapter("test-key")

    @Test
    fun `should convert ChatRequest to vendor JSON`() {
        val request = ChatRequest(
            model = "test-model",
            messages = listOf(
                Message.userMessage("Hello"),
                Message.assistantMessage("Hi!")
            ),
            temperature = 0.7,
            maxTokens = 100,
            stream = true
        )

        val json = adapter.toVendorRequest(request)

        assertTrue(json.contains("\"model\":\"test-model\""))
        assertTrue(json.contains("\"temperature\":0.7"))
        assertTrue(json.contains("\"max_tokens\":100"))
        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `should parse stream chunk correctly`() {
        val chunk = "data: {\"id\":\"1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        val result = adapter.parseStreamChunk(chunk)

        assertNotNull(result)
        assertEquals("Hello", result?.firstOrNull()?.delta)
        assertFalse(result?.firstOrNull()?.done ?: true)
    }

    @Test
    fun `should parse stream done signal`() {
        val chunk = "data: [DONE]"
        val result = adapter.parseStreamChunk(chunk)

        assertNotNull(result)
        assertTrue(result?.firstOrNull()?.done ?: false)
    }

    @Test
    fun `should return null for non-data lines`() {
        val chunk = "event: message"
        val result = adapter.parseStreamChunk(chunk)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `should provide correct headers`() {
        val headers = adapter.getHeaders()
        assertEquals("Bearer test-key", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `should provide correct endpoints`() {
        assertEquals("https://api.test.com/v1/chat", adapter.getChatEndpoint())
        assertEquals("https://api.test.com/v1/chat", adapter.getStreamEndpoint())
    }

    @Test
    fun `should throw NetworkException when response contains error field`() {
        val errorResponse = """
            {"error":{"message":"Context length exceeded","type":"invalid_request_error"}}
        """.trimIndent()

        val exception = assertThrows(com.codesage.shared.exceptions.NetworkException::class.java) {
            adapter.fromVendorResponse(errorResponse)
        }
        assertTrue(exception.message?.contains("Context length exceeded") == true)
    }

    @Test
    fun `should throw NetworkException when choices is empty`() {
        val emptyResponse = """
            {"id":"test","model":"test-model","choices":[]}
        """.trimIndent()

        val exception = assertThrows(com.codesage.shared.exceptions.NetworkException::class.java) {
            adapter.fromVendorResponse(emptyResponse)
        }
        assertTrue(exception.message?.contains("empty choices") == true)
    }

    private class TestAdapter(apiKey: String) : OpenAICompatibleAdapter(
        apiKey = apiKey,
        baseUrl = "https://api.test.com"
    ) {
        override val providerName: String = "test"
        override val supportedModels: List<String> = listOf("test-model")
        override val chatEndpointPath: String = "/v1/chat"
    }
}
