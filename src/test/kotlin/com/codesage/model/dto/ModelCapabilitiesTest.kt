package com.codesage.model.dto

import com.codesage.model.adapter.StreamEvent

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.adapter.OpenAICompatibleAdapter
import com.codesage.model.registry.ModelRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T1.1 修复验证测试：ModelCapabilities 抽象
 *
 * 验证：
 * 1. ModelCapabilities 数据类字段正确
 * 2. hasCapability / meets 语义正确
 * 3. 预设的 factory（openAiGpt4o / claude35Sonnet / geminiPro / minimal）能力正确
 * 4. ModelAdapter.capabilities 必须被实现
 * 5. ModelRegistry.getAdaptersForCapabilities 按能力反查正确
 */
class ModelCapabilitiesTest {

    @Test
    fun `default capabilities are streaming-only`() {
        val caps = ModelCapabilities()
        assertTrue(caps.streaming)
        assertFalse(caps.functionCalling)
        assertFalse(caps.vision)
        assertFalse(caps.toolStreaming)
        assertEquals(128_000, caps.maxContextTokens)
        assertEquals(4_096, caps.maxOutputTokens)
    }

    @Test
    fun `hasCapability returns correct values`() {
        val caps = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = true,
            toolStreaming = true,
            maxContextTokens = 200_000
        )
        assertTrue(caps.hasCapability(Capability.STREAMING))
        assertTrue(caps.hasCapability(Capability.FUNCTION_CALLING))
        assertTrue(caps.hasCapability(Capability.VISION))
        assertTrue(caps.hasCapability(Capability.TOOL_STREAMING))
        assertTrue(caps.hasCapability(Capability.LONG_CONTEXT))  // >= 100k
        assertTrue(caps.hasCapability(Capability.REASONING))  // >= 200k
        assertFalse(caps.hasCapability(Capability.PROMPT_CACHING))
    }

    @Test
    fun `meets returns true only when all required capabilities are present`() {
        val caps = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = false
        )
        assertTrue(caps meets setOf(Capability.STREAMING))
        assertTrue(caps meets setOf(Capability.STREAMING, Capability.FUNCTION_CALLING))
        assertFalse(caps meets setOf(Capability.VISION))
        assertFalse(caps meets setOf(Capability.STREAMING, Capability.VISION))
    }

    @Test
    fun `presets have correct capabilities`() {
        val gpt4o = ModelCapabilities.openAiGpt4o()
        assertTrue(gpt4o.streaming)
        assertTrue(gpt4o.functionCalling)
        assertTrue(gpt4o.vision)
        assertEquals(128_000, gpt4o.maxContextTokens)

        val claude = ModelCapabilities.claude35Sonnet()
        assertTrue(claude.functionCalling)
        assertTrue(claude.promptCaching)
        assertEquals(200_000, claude.maxContextTokens)
        assertTrue(claude.hasCapability(Capability.REASONING))

        val gemini = ModelCapabilities.geminiPro()
        assertTrue(gemini.hasCapability(Capability.LONG_CONTEXT))
        assertEquals(1_000_000, gemini.maxContextTokens)

        val minimal = ModelCapabilities.minimal()
        assertTrue(minimal.streaming)
        assertFalse(minimal.functionCalling)
        assertFalse(minimal.vision)
    }

    @Test
    fun `CODE capability requires function calling and 32k+ context`() {
        val smallModel = ModelCapabilities(
            functionCalling = true,
            maxContextTokens = 8_000
        )
        assertFalse(smallModel.hasCapability(Capability.CODE))

        val bigEnough = ModelCapabilities(
            functionCalling = true,
            maxContextTokens = 32_000
        )
        assertTrue(bigEnough.hasCapability(Capability.CODE))

        val noTools = ModelCapabilities(
            functionCalling = false,
            maxContextTokens = 100_000
        )
        assertFalse(noTools.hasCapability(Capability.CODE))
    }

    // === 适配器集成测试 ===

    /**
     * 测试用 adapter：声明自定义 capabilities
     */
    private class TestAdapter(
        override val providerName: String,
        override val capabilities: ModelCapabilities
    ) : ModelAdapter {
        override val supportedModels: List<String> = listOf("test-model")
        override fun toVendorRequest(request: com.codesage.model.dto.ChatRequest): String = ""
        override fun fromVendorResponse(response: String): com.codesage.model.dto.ChatResponse =
            com.codesage.model.dto.ChatResponse("", "", emptyList(), null)

        override fun parseStreamChunk(chunk: String): List<StreamEvent> = emptyList()
        override fun getStreamEndpoint(): String = "http://test"
        override fun getChatEndpoint(): String = "http://test"
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun `ModelAdapter must provide capabilities`() {
        val caps = ModelCapabilities.openAiGpt4o()
        val adapter = TestAdapter("test_caps", caps)
        assertEquals(caps, adapter.capabilities)
    }

    @Test
    fun `ModelRegistry filters by required capabilities`() {
        val registry = ModelRegistry()
        val visionAdapter = TestAdapter("vision_provider", ModelCapabilities.openAiGpt4o())  // vision=true
        val textOnlyAdapter = TestAdapter("text_provider", ModelCapabilities.minimal())  // vision=false
        registry.register(visionAdapter)
        registry.register(textOnlyAdapter)

        // 找支持 vision 的
        val visionResults = registry.getAdaptersForCapabilities(setOf(Capability.VISION))
        assertEquals(1, visionResults.size)
        assertEquals("vision_provider", visionResults[0].providerName)

        // 找支持 function_calling 的（两个都支持）
        val functionResults = registry.getAdaptersForCapabilities(setOf(Capability.FUNCTION_CALLING))
        assertEquals(1, functionResults.size, "只有 visionAdapter 支持 function calling")

        // 找支持 streaming 的（两个都支持）
        val streamingResults = registry.getAdaptersForCapabilities(setOf(Capability.STREAMING))
        assertEquals(2, streamingResults.size, "Both adapters should support streaming")
    }

    @Test
    fun `ModelRegistry getFirstAdapterForCapabilities returns first match`() {
        val registry = ModelRegistry()
        val adapter1 = TestAdapter("first_provider", ModelCapabilities.openAiGpt4o())
        val adapter2 = TestAdapter("second_provider", ModelCapabilities.geminiPro())  // 也支持 vision
        registry.register(adapter1)
        registry.register(adapter2)

        val first = registry.getFirstAdapterForCapabilities(setOf(Capability.VISION))
        assertNotNull(first)
        assertEquals("first_provider", first!!.providerName)
    }

    @Test
    fun `ModelRegistry returns empty list when no adapter matches`() {
        val registry = ModelRegistry()
        registry.register(TestAdapter("text_provider", ModelCapabilities.minimal()))

        val results = registry.getAdaptersForCapabilities(setOf(Capability.VISION))
        assertTrue(results.isEmpty(), "Should return empty list when no adapter matches")
    }

    @Test
    fun `backward compat - legacy supports methods delegate to capabilities`() {
        val caps = ModelCapabilities(
            streaming = true,
            functionCalling = true,
            vision = true
        )
        val adapter = TestAdapter("legacy_test", caps)
        assertTrue(adapter.supportsStreaming())
        assertTrue(adapter.supportsFunctionCalling())
        assertTrue(adapter.supportsVision())
    }
}
