package com.codesage.agent.context

import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.model.dto.ToolCall
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TokenEstimatorTest {

    @Test
    fun `should estimate text tokens for English`() {
        val text = "Hello world, this is a test message."
        val tokens = TokenEstimator.estimateTextTokens(text)
        // English: ~ 1 token per 4 chars
        assertTrue(tokens > 0)
        assertTrue(tokens < text.length)
    }

    @Test
    fun `should estimate text tokens for Chinese`() {
        val text = "你好世界，这是一个测试消息。"
        val tokens = TokenEstimator.estimateTextTokens(text)
        // Chinese: ~ 1 token per char
        assertTrue(tokens >= text.length * 0.8)
    }

    @Test
    fun `should estimate text tokens for mixed content`() {
        val text = "Hello 世界，this is 测试."
        val tokens = TokenEstimator.estimateTextTokens(text)
        assertTrue(tokens > 0)
    }

    @Test
    fun `should estimate image tokens`() {
        val text =
            "Here is an image: data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        val tokens = TokenEstimator.estimateTextTokens(text)
        // Should include 1600 tokens for the image
        assertTrue(tokens >= 1600)
    }

    @Test
    fun `should estimate message tokens`() {
        val message = Message.userMessage("Hello, how are you?")
        val tokens = TokenEstimator.estimateMessageTokens(message)
        // Role overhead (4) + content tokens
        assertTrue(tokens >= 4)
    }

    @Test
    fun `should estimate message with tool calls`() {
        val message = Message(
            role = Role.ASSISTANT,
            content = "I'll help you with that.",
            toolCalls = listOf(
                ToolCall("call_1", "read_file", "{\"path\":\"/test.txt\"}")
            )
        )
        val tokens = TokenEstimator.estimateMessageTokens(message)
        // Role + content + tool call overhead + name + arguments
        assertTrue(tokens > 20)
    }

    @Test
    fun `should estimate messages list`() {
        val messages = listOf(
            Message.systemMessage("You are helpful"),
            Message.userMessage("Hello"),
            Message.assistantMessage("Hi there!")
        )
        val tokens = TokenEstimator.estimateMessagesTokens(messages)
        assertTrue(tokens >= 12) // 3 * 4 role overhead minimum
    }

    @Test
    fun `rough estimate should be fast approximation`() {
        val text = "A".repeat(300)
        val tokens = TokenEstimator.roughEstimate(text)
        assertEquals(100, tokens) // 300 / 3
    }

    @Test
    fun `should handle empty text`() {
        assertEquals(0, TokenEstimator.estimateTextTokens(""))
        assertEquals(0, TokenEstimator.roughEstimate(""))
    }
}
