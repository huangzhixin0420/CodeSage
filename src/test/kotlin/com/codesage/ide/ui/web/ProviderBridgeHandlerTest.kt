package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * ProviderBridgeHandler 单元测试
 *
 * 由于 PluginConfig 强依赖 IntelliJ Platform PasswordSafe,
 * 完整集成测试需要 Plugin 测试环境。这里只测纯逻辑路径。
 */
class ProviderBridgeHandlerTest {

    @Test
    fun `handle returns false for unrelated types`() {
        var captured: Map<String, Any?>? = null
        val handler = ProviderBridgeHandler { msg -> captured = msg }
        assertFalse(handler.handle("send_message", emptyMap()))
        assertFalse(handler.handle("settings_get", emptyMap()))
        assertNull(captured)
    }

    @Test
    fun `handle returns true for set_api_key and test_provider`() {
        val handler = ProviderBridgeHandler { _ -> }
        assertTrue(handler.handle("set_api_key", mapOf("providerId" to "")))
        assertTrue(handler.handle("test_provider", mapOf("providerId" to "")))
    }

    @Test
    fun `set_api_key with missing providerId returns error`() {
        var captured: Map<String, Any?>? = null
        val handler = ProviderBridgeHandler { msg -> captured = msg }
        handler.handle("set_api_key", mapOf("apiKey" to "sk-test"))
        assertNotNull(captured)
        assertEquals("set_api_key_result", captured!!["type"])
        assertEquals(false, captured!!["success"])
        assertNotNull(captured!!["error"])
    }

    @Test
    fun `test_provider with blank baseUrl returns error`() {
        var captured: Map<String, Any?>? = null
        val handler = ProviderBridgeHandler { msg -> captured = msg }
        handler.handle(
            "test_provider",
            mapOf(
                "providerId" to "test",
                "baseUrl" to "",
                "apiKey" to "sk-test",
                "model" to "gpt-4",
                "requestId" to "req-1",
            ),
        )
        assertNotNull(captured)
        assertEquals("test_provider_result", captured!!["type"])
        assertEquals(false, captured!!["ok"])
        assertNotNull(captured!!["error"])
    }

    @Test
    fun `test_provider with unreachable host returns ok=false with error`() {
        var captured: Map<String, Any?>? = null
        val handler = ProviderBridgeHandler { msg -> captured = msg }
        // 用一个不可达的 host(测试环境保证连不上)
        handler.handle(
            "test_provider",
            mapOf(
                "providerId" to "test",
                "baseUrl" to "http://codesage-test-nonexistent-host-12345.invalid",
                "apiKey" to "sk-test",
                "model" to "gpt-4",
                "requestId" to "req-2",
            ),
        )
        assertNotNull(captured)
        assertEquals(false, captured!!["ok"])
        // 错误信息应非空
        val err = captured!!["error"] as? String
        assertNotNull(err)
        assertTrue(err!!.isNotBlank())
    }

    @Test
    fun `test_provider response always has requestId echoed back`() {
        var captured: Map<String, Any?>? = null
        val handler = ProviderBridgeHandler { msg -> captured = msg }
        handler.handle(
            "test_provider",
            mapOf(
                "providerId" to "test",
                "baseUrl" to "http://codesage-test-nonexistent-host-67890.invalid",
                "requestId" to "my-unique-req-id-123",
            ),
        )
        assertNotNull(captured)
        assertEquals("my-unique-req-id-123", captured!!["requestId"])
    }
}
