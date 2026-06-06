package com.codesage.shared.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * C2 修复验证：安全的 JsonElement 反序列化。
 *
 * 旧的 `args["key"]?.toString()?.removeSurrounding("\"")` 模式在以下场景出错：
 * - LLM 返回 null：toString() = "null"，removeSurrounding 仍是 "null"
 * - LLM 返回带前导空格/换行：toString() = "  hello" 不会被 removeSurrounding 处理
 * - 数字/布尔：toString() = "true"/"42"，要靠额外转换
 */
class JsonArgDecodersTest {

    @Test
    fun `stringArg returns string for valid string`() {
        val args = buildJsonObject { put("name", "hello") }
        assertEquals("hello", JsonArgDecoders.stringArg(args, "name"))
    }

    @Test
    fun `stringArg returns default for missing key`() {
        val args = buildJsonObject { put("other", "value") }
        assertEquals("", JsonArgDecoders.stringArg(args, "name"))
        assertEquals("default", JsonArgDecoders.stringArg(args, "name", default = "default"))
    }

    @Test
    fun `stringArg returns empty for null value`() {
        val args = buildJsonObject { put("name", JsonNull) }
        // null 时 content="null"，被 stringArg 显式替换为 default (空字符串)
        assertEquals("", JsonArgDecoders.stringArg(args, "name"))
    }

    @Test
    fun `stringArgOrNull returns null for null value`() {
        val args = buildJsonObject { put("name", JsonNull) }
        assertNull(JsonArgDecoders.stringArgOrNull(args, "name"))
    }

    @Test
    fun `stringArgOrNull returns null for missing key`() {
        val args = buildJsonObject { }
        assertNull(JsonArgDecoders.stringArgOrNull(args, "name"))
    }

    @Test
    fun `intArg returns int for numeric primitive`() {
        val args = buildJsonObject { put("count", 42) }
        assertEquals(42, JsonArgDecoders.intArg(args, "count"))
    }

    @Test
    fun `intArg accepts string-encoded number (tolerant)`() {
        // kotlinx.serialization 的 intOrNull 会把 "42" 解析为 42 (tolerant)
        // LLM 经常把数字当作字符串传
        val args = buildJsonObject { put("count", "42") }
        assertEquals(42, JsonArgDecoders.intArg(args, "count"))
    }

    @Test
    fun `intArg returns default for non-numeric string`() {
        val args = buildJsonObject { put("count", "not-a-number") }
        assertEquals(0, JsonArgDecoders.intArg(args, "count"))
    }

    @Test
    fun `intArg returns default for missing key`() {
        val args = buildJsonObject { }
        assertEquals(0, JsonArgDecoders.intArg(args, "count"))
        assertEquals(99, JsonArgDecoders.intArg(args, "count", default = 99))
    }

    @Test
    fun `boolArg returns true for true literal`() {
        val args = buildJsonObject { put("flag", true) }
        assertTrue(JsonArgDecoders.boolArg(args, "flag"))
    }

    @Test
    fun `boolArg returns false for false literal`() {
        val args = buildJsonObject { put("flag", false) }
        assertFalse(JsonArgDecoders.boolArg(args, "flag"))
    }

    @Test
    fun `boolArg accepts string-encoded boolean (tolerant)`() {
        // kotlinx.serialization 的 booleanOrNull 会把 "true"/"false" 解析为 bool
        val args = buildJsonObject { put("flag", "true") }
        assertTrue(JsonArgDecoders.boolArg(args, "flag"))
    }

    @Test
    fun `boolArg returns default for non-boolean string`() {
        val args = buildJsonObject { put("flag", "not-a-bool") }
        assertFalse(JsonArgDecoders.boolArg(args, "flag"))
    }

    @Test
    fun `stringListArg returns list of strings`() {
        val args = buildJsonObject {
            put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))))
        }
        assertEquals(listOf("a", "b", "c"), JsonArgDecoders.stringListArg(args, "tags"))
    }

    @Test
    fun `stringListArg filters nulls but keeps numbers as their string form`() {
        // contentOrNull 在 kotlinx.serialization 中对 number/boolean 也会返回字符串
        val args = buildJsonObject {
            put("tags", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(42), JsonNull, JsonPrimitive("c"))))
        }
        assertEquals(listOf("a", "42", "c"), JsonArgDecoders.stringListArg(args, "tags"))
    }

    @Test
    fun `handles previously broken toString removeSurrounding pattern`() {
        // 模拟 LLM 返回 {"value": null} 的旧 toString() 行为
        // 旧：args["value"]?.toString()?.removeSurrounding("\"") = "null"
        // 新：JsonArgDecoders.stringArgOrNull 返回 null（让调用方决定如何处理 null）
        val args = buildJsonObject { put("value", JsonNull) }
        assertNull(JsonArgDecoders.stringArgOrNull(args, "value"))
    }

    @Test
    fun `stringArg with leading whitespace is preserved (not truncated)`() {
        // 旧 toString().removeSurrounding("\"") 在带前导空格时会破坏数据
        val args = buildJsonObject { put("text", "  hello") }
        assertEquals("  hello", JsonArgDecoders.stringArg(args, "text"))
    }
}
