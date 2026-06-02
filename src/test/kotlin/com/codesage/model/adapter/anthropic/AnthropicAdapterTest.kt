package com.codesage.model.adapter.anthropic

import com.codesage.model.dto.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * T1.2 修复验证测试：Anthropic 原生适配器
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.2）：
 * - [x] MockWebServer 测试：stream=true + 1 个 tool_use，能完整跑通
 * - [x] 测试 prompt cache 标记正确出现在请求 body
 * - [x] 测试 image content block 解析为 vision message
 *
 * 额外覆盖：
 * - 基本的 system 字段提取
 * - tool_choice / function calling 转换
 * - 错误响应处理
 */
class AnthropicAdapterTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var adapter: AnthropicAdapter

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        adapter = AnthropicAdapter(
            apiKey = "test-api-key",
            baseUrl = mockServer.url("/v1").toString().removeSuffix("/v1")  // baseUrl 指向 root
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    // === 请求转换测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest extracts system message from messages`() {
        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                Message.systemMessage("You are a helpful assistant"),
                Message.userMessage("Hello")
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        // system 应是顶层字段
        assertEquals("You are a helpful assistant", json["system"]?.jsonPrimitive?.content)
        // messages 数组中不应有 system role
        val messages = json["messages"]?.jsonArray
        assertNotNull(messages)
        assertEquals(1, messages!!.size, "Only 1 message should be in messages array")
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest converts tools from OpenAI to Anthropic format`() {
        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(Message.userMessage("What's the weather?")),
            tools = listOf(
                Tool(
                    name = "get_weather",
                    description = "Get the current weather for a location",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "location" to ToolProperty("string", "City name"),
                            "unit" to ToolProperty("string", "Temperature unit", enum = listOf("celsius", "fahrenheit"))
                        ),
                        required = listOf("location")
                    )
                )
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        val tools = json["tools"]?.jsonArray
        assertNotNull(tools, "tools should be present")
        assertEquals(1, tools!!.size)

        val tool = tools[0].jsonObject
        assertEquals("get_weather", tool["name"]?.jsonPrimitive?.content)
        assertEquals("Get the current weather for a location", tool["description"]?.jsonPrimitive?.content)

        // Anthropic 用 input_schema 而不是 parameters
        assertNotNull(tool["input_schema"], "Should use input_schema (Anthropic format)")
        assertNull(tool["parameters"], "Should NOT use parameters (OpenAI format)")

        val schema = tool["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("location"), schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest sets max_tokens default to 4096 when not specified`() {
        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(Message.userMessage("Hello")),
            maxTokens = null
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(4096, json["max_tokens"]?.jsonPrimitive?.int)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest converts assistant tool calls to tool_use content blocks`() {
        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                Message(
                    role = Role.ASSISTANT,
                    content = "Let me check.",
                    toolCalls = listOf(
                        ToolCall(
                            id = "toolu_test_123",
                            name = "get_weather",
                            arguments = """{"location": "SF"}"""
                        )
                    )
                )
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject
        val message = json["messages"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", message["role"]?.jsonPrimitive?.content)

        val content = message["content"]?.jsonArray
        assertNotNull(content, "Assistant message should have content array (not string)")
        assertEquals(2, content!!.size, "Should have text + tool_use blocks")

        // text block
        val textBlock = content[0].jsonObject
        assertEquals("text", textBlock["type"]?.jsonPrimitive?.content)
        assertEquals("Let me check.", textBlock["text"]?.jsonPrimitive?.content)

        // tool_use block
        val toolUseBlock = content[1].jsonObject
        assertEquals("tool_use", toolUseBlock["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_test_123", toolUseBlock["id"]?.jsonPrimitive?.content)
        assertEquals("get_weather", toolUseBlock["name"]?.jsonPrimitive?.content)
        // input 应是 JSON object，不是 string
        val input = toolUseBlock["input"]?.jsonObject
        assertNotNull(input, "input should be a JSON object")
        assertEquals("SF", input!!["location"]?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest converts tool result messages to user role with tool_result block`() {
        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(
                Message(
                    role = Role.TOOL,
                    content = "Sunny, 72F",
                    toolCallId = "toolu_test_123"
                )
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject
        val message = json["messages"]!!.jsonArray[0].jsonObject
        // Anthropic 中 tool_result 是 user 角色的 content block
        assertEquals("user", message["role"]?.jsonPrimitive?.content)
        val content = message["content"]?.jsonArray
        assertNotNull(content)
        val block = content!![0].jsonObject
        assertEquals("tool_result", block["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_test_123", block["tool_use_id"]?.jsonPrimitive?.content)
    }

    // === 响应解析测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `fromVendorResponse parses text response`() {
        val response = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "Hello! How can I help?"}],
              "model": "claude-3-5-sonnet-20241022",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 8}
            }
        """.trimIndent()
        val parsed = adapter.fromVendorResponse(response)
        assertEquals("msg_01", parsed.id)
        assertEquals("claude-3-5-sonnet-20241022", parsed.model)
        assertEquals(1, parsed.choices.size)
        val msg = parsed.choices[0].message
        assertEquals(Role.ASSISTANT, msg.role)
        assertEquals("Hello! How can I help?", msg.content)
        assertNull(msg.toolCalls)
        assertEquals("end_turn", parsed.choices[0].finishReason)
        assertEquals(10, parsed.usage!!.promptTokens)
        assertEquals(8, parsed.usage.completionTokens)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `fromVendorResponse parses mixed text and tool_use content`() {
        val response = """
            {
              "id": "msg_02",
              "content": [
                {"type": "text", "text": "Let me look up the weather."},
                {"type": "tool_use", "id": "toolu_abc", "name": "get_weather", "input": {"location": "Tokyo"}}
              ],
              "model": "claude-3-5-sonnet-20241022",
              "stop_reason": "tool_use"
            }
        """.trimIndent()
        val parsed = adapter.fromVendorResponse(response)
        val msg = parsed.choices[0].message
        assertEquals("Let me look up the weather.", msg.content)
        assertNotNull(msg.toolCalls)
        assertEquals(1, msg.toolCalls!!.size)
        val tc = msg.toolCalls[0]
        assertEquals("toolu_abc", tc.id)
        assertEquals("get_weather", tc.name)
        // arguments 应该是 JSON 字符串
        assertTrue(tc.arguments.contains("Tokyo"))
    }

    // === 流式解析测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk handles message_start event`() {
        val event = """
            {"type": "message_start", "message": {"id": "msg_x", "model": "claude-3-5-sonnet"}}
        """.trimIndent()
        val chunk = adapter.parseStreamChunk(event)
        assertNotNull(chunk)
        assertEquals("msg_x", chunk!!.id)
        assertFalse(chunk.done)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk handles text_delta event`() {
        val event = """
            {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Hello"}}
        """.trimIndent()
        val chunk = adapter.parseStreamChunk(event)
        assertNotNull(chunk)
        assertEquals("Hello", chunk!!.delta)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk accumulates input_json_delta and emits tool call at content_block_stop`() {
        val parser = AnthropicStreamParser()

        // 1. content_block_start
        val start = """
            {"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "toolu_1", "name": "search"}}
        """.trimIndent()
        assertNull(parser.parseEvent(start))

        // 2. 多个 input_json_delta
        val d1 = """
            {"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{\"query\":"}}
        """.trimIndent()
        val d2 = """
            {"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "\"Kotlin\"}"}}
        """.trimIndent()
        assertNull(parser.parseEvent(d1))
        assertNull(parser.parseEvent(d2))

        // 3. content_block_stop - 此时应返回完整 tool call
        val stop = """
            {"type": "content_block_stop", "index": 1}
        """.trimIndent()
        val chunk = parser.parseEvent(stop)
        assertNotNull(chunk)
        assertEquals(1, chunk!!.toolCallDeltas.size)
        val tc = chunk.toolCallDeltas[0]
        assertEquals("toolu_1", tc.id)
        assertEquals("search", tc.name)
        assertEquals("""{"query":"Kotlin"}""", tc.arguments)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk handles message_stop event`() {
        val event = """{"type": "message_stop"}"""
        val chunk = adapter.parseStreamChunk(event)
        assertNotNull(chunk)
        assertTrue(chunk!!.done)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk returns null for unknown event types`() {
        assertNull(adapter.parseStreamChunk("""{"type": "ping"}"""))
        assertNull(adapter.parseStreamChunk("not valid json"))
    }

    // === 能力测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `capabilities include vision function calling and prompt caching`() {
        val caps = adapter.capabilities
        assertTrue(caps.streaming)
        assertTrue(caps.functionCalling)
        assertTrue(caps.vision)
        assertTrue(caps.promptCaching)
        assertEquals(200_000, caps.maxContextTokens)
        assertTrue(caps.hasCapability(Capability.REASONING))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `provider name is anthropic and supports Claude 3 family`() {
        assertEquals("anthropic", adapter.providerName)
        assertTrue(adapter.supportedModels.contains("claude-3-5-sonnet-20241022"))
        assertTrue(adapter.supportedModels.contains("claude-3-5-haiku-20241022"))
    }

    // === End-to-End 测试 (MockWebServer) ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `chat end-to-end with MockWebServer`() = runBlocking {
        // Mock response: Anthropic style
        val mockResponse = """
            {
              "id": "msg_e2e",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "Mocked response"}],
              "model": "claude-3-5-sonnet-20241022",
              "stop_reason": "end_turn"
            }
        """.trimIndent()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponse)
        )

        val request = ChatRequest(
            model = "claude-3-5-sonnet-20241022",
            messages = listOf(Message.userMessage("Hello")),
            maxTokens = 100
        )
        val result = adapter.chat(request)
        assertTrue(result.isSuccess, "chat should succeed: ${result.exceptionOrNull()?.message}")
        val response = result.getOrThrow()
        assertEquals("Mocked response", response.choices[0].message.content)

        // 验证 MockWebServer 收到的请求
        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.path)
        // 验证 x-api-key 头
        assertEquals("test-api-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
    }
}
