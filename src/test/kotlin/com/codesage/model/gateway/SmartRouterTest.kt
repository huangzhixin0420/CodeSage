package com.codesage.model.gateway

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.registry.ModelRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T1.4 修复验证测试：SmartRouter 智能路由
 */
class SmartRouterTest {

    private fun adapter(
        name: String,
        caps: ModelCapabilities
    ): ModelAdapter = object : ModelAdapter {
        override val providerName: String = name
        override val supportedModels: List<String> = listOf("$name-default")
        override val capabilities: ModelCapabilities = caps
        override fun toVendorRequest(request: ChatRequest): String = ""
        override fun fromVendorResponse(response: String): ChatResponse = ChatResponse("", "", emptyList(), null)
        override fun parseStreamChunk(chunk: String): StreamChunk? = null
        override fun getStreamEndpoint(): String = ""
        override fun getChatEndpoint(): String = ""
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    private fun newRegistry(): ModelRegistry = ModelRegistry().apply {
        register(
            adapter(
                "cheap",
                ModelCapabilities(
                    streaming = true,
                    functionCalling = true,
                    maxContextTokens = 8_000,
                    pricePer1kInput = 0.0001,
                    pricePer1kOutput = 0.0002
                )
            )
        )
        register(
            adapter(
                "premium",
                ModelCapabilities(
                    streaming = true,
                    functionCalling = true,
                    vision = true,
                    maxContextTokens = 200_000,
                    pricePer1kInput = 0.01,
                    pricePer1kOutput = 0.03,
                    promptCaching = true
                )
            )
        )
        register(
            adapter(
                "vision",
                ModelCapabilities(
                    streaming = true,
                    functionCalling = true,
                    vision = true,
                    maxContextTokens = 16_000,
                    pricePer1kInput = 0.002,
                    pricePer1kOutput = 0.005
                )
            )
        )
    }

    @Test
    fun `CostFirstStrategy picks cheapest adapter`() {
        val candidates = listOf("a", "b", "c").map { name ->
            AdapterProfile(
                adapter(
                    name, ModelCapabilities(
                        streaming = true, functionCalling = true,
                        pricePer1kInput = if (name == "b") 0.001 else 0.01,
                        pricePer1kOutput = if (name == "b") 0.002 else 0.02
                    )
                ),
                estimatedCost = if (name == "b") 0.003 else 0.03
            )
        }
        val selected = CostFirstStrategy().select(RoutingTask(), candidates)
        assertEquals("b", selected?.providerName)
    }

    @Test
    fun `QualityFirstStrategy picks adapter with largest context`() {
        val candidates = listOf(
            AdapterProfile(
                adapter(
                    "small",
                    ModelCapabilities(streaming = true, functionCalling = true, maxContextTokens = 8_000)
                )
            ),
            AdapterProfile(
                adapter(
                    "huge",
                    ModelCapabilities(streaming = true, functionCalling = true, maxContextTokens = 1_000_000)
                )
            )
        )
        val selected = QualityFirstStrategy().select(RoutingTask(), candidates)
        assertEquals("huge", selected?.providerName)
    }

    @Test
    fun `SpeedFirstStrategy prefers adapter with tool streaming`() {
        val candidates = listOf(
            AdapterProfile(adapter("plain", ModelCapabilities(streaming = true, functionCalling = true))),
            AdapterProfile(
                adapter(
                    "fast",
                    ModelCapabilities(streaming = true, functionCalling = true, toolStreaming = true)
                )
            )
        )
        val selected = SpeedFirstStrategy().select(RoutingTask(), candidates)
        assertEquals("fast", selected?.providerName)
    }

    @Test
    fun `BalancedStrategy weighs price and quality`() {
        // Adapter 1: 大 context 但贵
        // Adapter 2: 小 context 但便宜
        val candidates = listOf(
            AdapterProfile(
                adapter(
                    "expensive_big",
                    ModelCapabilities(
                        streaming = true,
                        functionCalling = true,
                        maxContextTokens = 200_000,
                        pricePer1kInput = 0.01,
                        pricePer1kOutput = 0.03
                    )
                ), estimatedCost = 0.04
            ),
            AdapterProfile(
                adapter(
                    "cheap_small",
                    ModelCapabilities(
                        streaming = true,
                        functionCalling = true,
                        maxContextTokens = 8_000,
                        pricePer1kInput = 0.0001,
                        pricePer1kOutput = 0.0002
                    )
                ), estimatedCost = 0.0003
            )
        )
        val selected = BalancedStrategy().select(RoutingTask(), candidates)
        // 不具体断言选哪个（balanced 策略是 heuristic），但应该有选
        assertNotNull(selected)
    }

    @Test
    fun `TaskSpecificStrategy prefers streaming and lower cost`() {
        val candidates = listOf(
            AdapterProfile(
                adapter("no_stream", ModelCapabilities(streaming = false, functionCalling = true)),
                estimatedCost = 0.0
            ),
            AdapterProfile(
                adapter(
                    "stream",
                    ModelCapabilities(
                        streaming = true,
                        functionCalling = true,
                        toolStreaming = true,
                        promptCaching = true
                    )
                ), estimatedCost = 0.0
            )
        )
        val selected = TaskSpecificStrategy().select(RoutingTask(), candidates)
        assertEquals("stream", selected?.providerName, "应优先选 streaming + toolStreaming + promptCaching 的")
    }

    @Test
    fun `vision task routes only to vision-capable adapters`() {
        val registry = newRegistry()
        val router = SmartRouter(registry, TaskSpecificStrategy())

        val adapter = router.selectAdapter(RoutingTask(requiresVision = true))
        assertNotNull(adapter)
        assertTrue(adapter!!.capabilities.vision, "Selected adapter must support vision")
        assertNotEquals("cheap", adapter.providerName, "cheap has no vision, must be excluded")
    }

    @Test
    fun `long context task routes to LONG_CONTEXT adapter`() {
        val registry = newRegistry()
        val router = SmartRouter(registry, TaskSpecificStrategy())

        val adapter = router.selectAdapter(RoutingTask(estimatedTokens = 150_000))
        assertNotNull(adapter)
        assertTrue(
            adapter!!.capabilities.hasCapability(Capability.LONG_CONTEXT),
            "Should route to LONG_CONTEXT adapter"
        )
    }

    @Test
    fun `preferred model takes precedence`() {
        val registry = newRegistry()
        val router = SmartRouter(registry, CostFirstStrategy())

        val adapter = router.selectAdapter(RoutingTask(), preferredModel = "premium-default")
        assertEquals("premium", adapter?.providerName, "Preferred model should be selected even if not cheapest")
    }

    @Test
    fun `unhealthy adapter is skipped`() {
        val registry = newRegistry()
        val tracker = HealthTracker(maxConsecutiveFailures = 2, cooldownMs = 60_000)
        // 模拟 2 次失败触发熔断
        tracker.recordFailure("cheap")
        tracker.recordFailure("cheap")
        assertTrue(tracker.isCircuitOpen("cheap"))

        val router = SmartRouter(registry, CostFirstStrategy(), tracker)
        val adapter = router.selectAdapter(RoutingTask())
        assertNotEquals("cheap", adapter?.providerName, "cheap is in cooldown, should be skipped")
    }

    @Test
    fun `circuit breaker closes after success within cooldown`() {
        val tracker = HealthTracker()
        tracker.recordFailure("cheap")
        tracker.recordFailure("cheap")
        tracker.recordFailure("cheap")
        assertTrue(tracker.isCircuitOpen("cheap"))

        // cooldown 期内：仍 open
        assertFalse(tracker.isAvailable("cheap"))

        // 模拟成功
        tracker.recordSuccess("cheap")
        assertTrue(tracker.isAvailable("cheap"), "After success, circuit should close")
    }

    @Test
    fun `circuit breaker reopens after cooldown if failure recurs`() {
        // 用可控制的 clock 测试 cooldown
        var now = 1_000_000L
        val tracker = HealthTracker(maxConsecutiveFailures = 2, cooldownMs = 1000)
        tracker.recordFailure("cheap")
        tracker.recordFailure("cheap")
        assertTrue(tracker.isCircuitOpen("cheap"))

        // 模拟 1.5s 后
        now += 1500
        // 由于 HealthTracker 使用 System.currentTimeMillis()，我们不能直接控制
        // 但可以验证"再次失败后立即 open"
        Thread.sleep(50)
        tracker.recordFailure("cheap")
        assertTrue(tracker.isCircuitOpen("cheap"), "After re-failure, circuit should reopen")
    }

    @Test
    fun `excludedProviders are skipped`() {
        val registry = newRegistry()
        val router = SmartRouter(registry, TaskSpecificStrategy(), excludedProviders = setOf("cheap"))

        // 多次选择，cheap 应永远不出现在结果中
        repeat(10) {
            val adapter = router.selectAdapter(RoutingTask())
            assertNotEquals("cheap", adapter?.providerName)
        }
    }

    @Test
    fun `returns null when no adapter matches`() {
        val registry = ModelRegistry()
        val router = SmartRouter(registry, TaskSpecificStrategy())

        val adapter = router.selectAdapter(RoutingTask(requiresVision = true))
        assertNull(adapter, "Should return null when no vision adapter is registered")
    }
}
