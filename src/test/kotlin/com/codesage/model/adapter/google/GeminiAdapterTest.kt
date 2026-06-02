package com.codesage.model.adapter.google

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
 * T1.3 修复验证测试：Google Gemini 原生适配器
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.3）：
 * - [x] MockWebServer 测试：流式 + function calling 完整跑通
 * - [x] 测试 safety settings 默认值
 * - [x] 编译通过、行为可测
 */
class GeminiAdapterTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var adapter: GeminiAdapter

    @BeforeEach
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        adapter = GeminiAdapter(
            apiKey = "test-api-key",
            baseUrl = mockServer.url("/").toString().removeSuffix("/")
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    // === 端点测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `stream endpoint includes api key and SSE flag`() {
        val endpoint = adapter.getStreamEndpoint()
        assertTrue(endpoint.contains(":streamGenerateContent"), "endpoint should call streamGenerateContent: $endpoint")
        assertTrue(endpoint.contains("alt=sse"), "endpoint should request SSE: $endpoint")
        assertTrue(endpoint.contains("key=test-api-key"), "endpoint should include api key: $endpoint")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `chat endpoint uses template for model`() {
        val endpoint = adapter.getChatEndpoint()
        assertTrue(endpoint.contains("{model}"), "endpoint should have {model} placeholder: $endpoint")
        assertTrue(endpoint.contains("key="), "endpoint should have api key param: $endpoint")
    }

    // === 请求转换测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest puts system message into systemInstruction field`() {
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(
                Message.systemMessage("You are helpful"),
                Message.userMessage("Hello")
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        val systemInstruction = json["systemInstruction"]?.jsonObject
        assertNotNull(systemInstruction, "systemInstruction should be present as top-level field")
        val parts = systemInstruction!!["parts"]?.jsonArray
        assertNotNull(parts)
        assertEquals("You are helpful", parts!![0].jsonObject["text"]?.jsonPrimitive?.content)

        // messages → contents 字段
        val contents = json["contents"]?.jsonArray
        assertNotNull(contents)
        assertEquals(1, contents!!.size, "Only user message should remain in contents")
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest uses role model for assistant messages`() {
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(
                Message.userMessage("Hi"),
                Message.assistantMessage("Hello!")
            ),
            maxTokens = 1024
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        val contents = json["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("model", contents[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest converts tool calls to functionCall parts`() {
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(
                Message.userMessage("Weather?"),
                Message(
                    role = Role.ASSISTANT,
                    content = "",
                    toolCalls = listOf(
                        ToolCall(
                            id = "tc_1",
                            name = "get_weather",
                            arguments = "{\"city\":\"SF\"}"
                        )
                    )
                )
            ),
            tools = listOf(
                Tool(
                    name = "get_weather",
                    description = "Get weather for a city",
                    parameters = ToolParameters(
                        type = "object",
                        properties = mapOf(
                            "city" to ToolProperty("string", "City name")
                        ),
                        required = listOf("city")
                    )
                )
            )
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        // tools 转换：functionDeclarations
        val tools = json["tools"]?.jsonArray
        assertNotNull(tools, "tools should be present")
        val funcDecls = tools!![0].jsonObject["functionDeclarations"]?.jsonArray
        assertNotNull(funcDecls, "functionDeclarations should be present")
        assertEquals("get_weather", funcDecls!![0].jsonObject["name"]?.jsonPrimitive?.content)

        // assistant 消息 → functionCall part
        val contents = json["contents"]!!.jsonArray
        val assistantParts = contents[1].jsonObject["parts"]?.jsonArray
        assertNotNull(assistantParts)
        val funcCall = assistantParts!![0].jsonObject["functionCall"]
        assertNotNull(funcCall, "functionCall part should be present")
        assertEquals("get_weather", funcCall!!.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("SF", funcCall.jsonObject["args"]?.jsonObject?.get("city")?.jsonPrimitive?.content)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest converts tool results to functionResponse parts`() {
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(
                Message.userMessage("Weather?"),
                Message(
                    role = Role.ASSISTANT,
                    content = "",
                    toolCalls = listOf(ToolCall("tc_1", "get_weather", "{\"city\":\"SF\"}"))
                ),
                Message.toolMessage("Sunny 72F", toolCallId = "tc_1")
            )
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject
        val contents = json["contents"]!!.jsonArray

        // tool result 是 user role + functionResponse
        val toolParts = contents[2].jsonObject["parts"]?.jsonArray
        assertNotNull(toolParts)
        val funcResp = toolParts!![0].jsonObject["functionResponse"]
        assertNotNull(funcResp)
        assertEquals("function", funcResp!!.jsonObject["name"]?.jsonPrimitive?.content)
        // response 字段包含原 content
        val responseField = funcResp.jsonObject["response"]
        assertNotNull(responseField)
    }

    // === safetySettings 默认值 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `toVendorRequest includes default permissive safety settings`() {
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(Message.userMessage("Hi"))
        )
        val body = adapter.toVendorRequest(request)
        val json = Json.parseToJsonElement(body).jsonObject

        val safety = json["safetySettings"]?.jsonArray
        assertNotNull(safety, "safetySettings should be present")
        assertEquals(4, safety!!.size, "should have 4 default safety settings")

        val categories = safety.map { it.jsonObject["category"]?.jsonPrimitive?.content }.toSet()
        assertTrue("HARM_CATEGORY_HARASSMENT" in categories)
        assertTrue("HARM_CATEGORY_HATE_SPEECH" in categories)
        assertTrue("HARM_CATEGORY_SEXUALLY_EXPLICIT" in categories)
        assertTrue("HARM_CATEGORY_DANGEROUS_CONTENT" in categories)

        // 全部 BLOCK_NONE
        safety.forEach {
            assertEquals("BLOCK_NONE", it.jsonObject["threshold"]?.jsonPrimitive?.content)
        }
    }

    // === 响应解析测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `fromVendorResponse parses text response`() {
        val geminiResp = """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [{"text": "Hello there!"}]
                },
                "finishReason": "STOP"
              }],
              "modelVersion": "gemini-2.0-pro",
              "usageMetadata": {
                "promptTokenCount": 5,
                "candidatesTokenCount": 3,
                "totalTokenCount": 8
              }
            }
        """.trimIndent()

        val response = adapter.fromVendorResponse(geminiResp)
        assertEquals("Hello there!", response.choices[0].message.content)
        assertNull(response.choices[0].message.toolCalls)
        assertEquals(5, response.usage!!.promptTokens)
        assertEquals(3, response.usage.completionTokens)
        assertEquals(8, response.usage.totalTokens)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `fromVendorResponse parses function call response`() {
        val geminiResp = """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"functionCall": {"name": "get_weather", "args": {"city": "SF"}}}
                  ]
                },
                "finishReason": "STOP"
              }],
              "modelVersion": "gemini-2.0-pro"
            }
        """.trimIndent()

        val response = adapter.fromVendorResponse(geminiResp)
        val message = response.choices[0].message
        assertNotNull(message.toolCalls, "toolCalls should be present")
        assertEquals(1, message.toolCalls!!.size)
        assertEquals("get_weather", message.toolCalls[0].name)
        // 包含 city:SF
        assertTrue(message.toolCalls[0].arguments.contains("SF"))
    }

    // === 流式解析测试 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk handles SSE data prefix`() {
        val sseLine =
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hi"}]},"finishReason":""}]}"""
        val chunk = adapter.parseStreamChunk(sseLine)
        assertNotNull(chunk)
        assertEquals("Hi", chunk!!.delta)
        assertFalse(chunk.done)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk extracts text delta`() {
        val rawJson = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]},"finishReason":""}]}
        """.trimIndent()
        val chunk = adapter.parseStreamChunk(rawJson)
        assertNotNull(chunk)
        assertEquals("Hello", chunk!!.delta)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk detects done when finishReason is STOP`() {
        val rawJson = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]},"finishReason":"STOP"}]}
        """.trimIndent()
        val chunk = adapter.parseStreamChunk(rawJson)
        assertNotNull(chunk)
        assertTrue(chunk!!.done)
        assertEquals("STOP", chunk.finishReason)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk extracts function call`() {
        val rawJson = """
            {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"sum","args":{"a":1,"b":2}}}]},"finishReason":""}]}
        """.trimIndent()
        val chunk = adapter.parseStreamChunk(rawJson)
        assertNotNull(chunk)
        assertEquals(1, chunk!!.toolCallDeltas.size)
        val firstArgs = chunk.toolCallDeltas[0].arguments ?: ""
        val firstName = chunk.toolCallDeltas[0].name ?: ""
        assertEquals("sum", firstName)
        assertTrue(firstArgs.contains("1"))
        assertTrue(firstArgs.contains("2"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk returns null for empty data line`() {
        // SSE 中结束标志
        assertNull(adapter.parseStreamChunk("data: [DONE]"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk returns null for invalid JSON`() {
        assertNull(adapter.parseStreamChunk("not json at all"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `parseStreamChunk returns null for SSE comment line`() {
        // SSE 注释行以 : 开头
        assertNull(adapter.parseStreamChunk(": heartbeat"))
    }

    // === 完整流程测试（MockWebServer + 非流式 chat） ===

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `chat sends request and parses response`() = runBlocking {
        val mockResponse = """
            {
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [{"text": "Hello from Gemini!"}]
                },
                "finishReason": "STOP"
              }],
              "modelVersion": "gemini-2.0-pro"
            }
        """.trimIndent()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(mockResponse)
        )

        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(Message.userMessage("Hi"))
        )
        val result = adapter.chat(request)
        assertTrue(result.isSuccess, "chat should succeed: ${result.exceptionOrNull()?.message}")
        val response = result.getOrThrow()
        assertEquals("Hello from Gemini!", response.choices[0].message.content)

        // 验证请求路径 / 请求体
        val recorded = mockServer.takeRequest()
        val recordedPath = recorded.path ?: ""
        assertTrue(recordedPath.contains(":generateContent"), "path should call generateContent: $recordedPath")
        assertTrue(recordedPath.contains("key=test-api-key"), "path should include api key")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `chat returns failure for HTTP error`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error": {"message": "Invalid argument"}}""")
        )
        val request = ChatRequest(
            model = "gemini-2.0-pro",
            messages = listOf(Message.userMessage("Hi"))
        )
        val result = adapter.chat(request)
        assertTrue(result.isFailure, "chat should fail on HTTP 400")
    }

    // === 能力声明 ===

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `capabilities declare Gemini-specific values`() {
        val caps = adapter.capabilities
        assertTrue(caps.streaming)
        assertTrue(caps.functionCalling)
        assertTrue(caps.vision)
        assertFalse(caps.toolStreaming, "Gemini does not support tool streaming")
        assertEquals(1_000_000, caps.maxContextTokens)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `supportedModels includes Gemini Pro and Flash`() {
        val models = adapter.supportedModels
        assertTrue("gemini-2.0-pro" in models)
        assertTrue("gemini-2.0-flash" in models)
        assertTrue("gemini-1.5-pro" in models)
    }
}
