package com.codesage.shared.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * C7 修复验证：JS 安全的 JSON 字符串字面量。
 */
class SafeJsonEncoderTest {

    @Test
    fun `escapes U+2028 line separator`() {
        val obj = buildJsonObject { put("text", "line1\u2028line2") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("\\u2028"), "Should escape U+2028: $literal")
    }

    @Test
    fun `escapes U+2029 paragraph separator`() {
        val obj = buildJsonObject { put("text", "para1\u2029para2") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("\\u2029"), "Should escape U+2029: $literal")
    }

    @Test
    fun `escapes closing script tag`() {
        val obj = buildJsonObject { put("text", "evil </script><script>alert(1)</script>") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("<\\/script>"), "Should escape </script>: $literal")
    }

    @Test
    fun `escapes backslash and quote`() {
        val obj = buildJsonObject { put("text", "He said \"hi\"\\") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("\\\\"), "Should escape backslash: $literal")
        assertTrue(literal.contains("\\\""), "Should escape quote: $literal")
    }

    @Test
    fun `escapes newline and tab`() {
        val obj = buildJsonObject { put("text", "a\nb\tc") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("\\n"), "Should escape newline: $literal")
        assertTrue(literal.contains("\\t"), "Should escape tab: $literal")
    }

    @Test
    fun `normal text passes through`() {
        val obj = buildJsonObject { put("text", "hello world") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertEquals("\"{\\\"text\\\":\\\"hello world\\\"}\"", literal)
    }

    @Test
    fun `escapes control characters`() {
        val obj = buildJsonObject { put("text", "\u0001\u0002\u0003") }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("\\u0001"), "Should escape \\u0001: $literal")
        assertTrue(literal.contains("\\u0002"), "Should escape \\u0002: $literal")
    }

    @Test
    fun `handles nested object`() {
        val inner = buildJsonObject { put("evil", "</script>") }
        val outer = buildJsonObject { put("data", inner) }
        val literal = SafeJsonEncoder.toJsStringLiteral(outer)
        assertTrue(literal.contains("<\\/script>"), "Nested </script> should be escaped: $literal")
    }

    @Test
    fun `handles array`() {
        val arr = JsonArray(listOf(JsonPrimitive("</script>"), JsonPrimitive("\u2028")))
        val obj = buildJsonObject { put("list", arr) }
        val literal = SafeJsonEncoder.toJsStringLiteral(obj)
        assertTrue(literal.contains("<\\/script>"))
        assertTrue(literal.contains("\\u2028"))
    }
}
