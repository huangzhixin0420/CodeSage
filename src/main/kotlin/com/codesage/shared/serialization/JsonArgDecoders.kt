package com.codesage.shared.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 把 [JsonElement] 安全反序列化为标量值。
 *
 * 历史背景（来自 code review C2）：
 * 之前 LLM 工具调用返回值都靠 `args["key"]?.toString()?.removeSurrounding("\"")` 这个 magic pattern
 * 反序列化。它有几个问题：
 * 1. `JsonElement.toString()` 返回的是 JSON 序列化表示 (`"foo"`、`true`、`42`)，不是原始字符串。
 * 2. LLM 返回 `"value":null` 时 `toString() = "null"`，`removeSurrounding` 后还是 `"null"`，
 *    会被当成字符串塞进 ProcessBuilder。
 * 3. 数字/布尔没有专门的解码路径，要靠 `toBoolean()` 字符串解析。
 *
 * 修法：所有反序列化走 [JsonPrimitive] 的类型安全方法：
 * - [stringArg]: 字符串
 * - [intArg] / [longArg] / [doubleArg]: 数值
 * - [boolArg]: 布尔
 * - [jsonObjectArg] / [jsonArrayArg]: 嵌套结构
 * - [stringListArg]: 字符串数组
 *
 * 所有方法都安全处理：
 * - 字段不存在 → 返回 default
 * - 字段是 JsonNull → 返回 default
 * - 类型不匹配（期待 int 拿到 string）→ 返回 default
 * - 解析失败 → 返回 default
 */
object JsonArgDecoders {

    /** 取字符串字段，缺省 [default] = "" */
    fun stringArg(args: JsonObject, key: String, default: String = ""): String {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val prim = el as? JsonPrimitive ?: return default
        // 优先用 contentOrNull（处理 isString 判断）
        prim.contentOrNull?.let { return it }
        // contentOrNull 可能在某些边界为 null（如显式 null literal）
        return prim.content.let { if (it == "null") default else it }
    }

    /** 可空字符串字段 */
    fun stringArgOrNull(args: JsonObject, key: String): String? {
        val el = args[key] ?: return null
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: return null
        return prim.contentOrNull
    }

    /** 取 int 字段，缺省 [default] = 0 */
    fun intArg(args: JsonObject, key: String, default: Int = 0): Int {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val prim = el as? JsonPrimitive ?: return default
        return prim.intOrNull ?: default
    }

    /** 取 long 字段 */
    fun longArg(args: JsonObject, key: String, default: Long = 0L): Long {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val prim = el as? JsonPrimitive ?: return default
        return prim.longOrNull ?: default
    }

    /** 取 double 字段 */
    fun doubleArg(args: JsonObject, key: String, default: Double = 0.0): Double {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val prim = el as? JsonPrimitive ?: return default
        return prim.doubleOrNull ?: default
    }

    /** 取 boolean 字段，缺省 [default] = false */
    fun boolArg(args: JsonObject, key: String, default: Boolean = false): Boolean {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val prim = el as? JsonPrimitive ?: return default
        // booleanOrNull 仅当 literal 是 true/false 时返回非 null
        return prim.booleanOrNull ?: default
    }

    /** 取 JsonObject 字段 */
    fun jsonObjectArg(args: JsonObject, key: String): JsonObject? {
        val el = args[key] ?: return null
        if (el is JsonNull) return null
        return el as? JsonObject
    }

    /** 取 JsonArray 字段 */
    fun jsonArrayArg(args: JsonObject, key: String): JsonArray? {
        val el = args[key] ?: return null
        if (el is JsonNull) return null
        return el as? JsonArray
    }

    /** 取字符串列表字段（[ "a", "b" ]） */
    fun stringListArg(args: JsonObject, key: String, default: List<String> = emptyList()): List<String> {
        val el = args[key] ?: return default
        if (el is JsonNull) return default
        val arr = el as? JsonArray ?: return default
        return arr.mapNotNull { item ->
            if (item is JsonNull) null
            else (item as? JsonPrimitive)?.contentOrNull
        }
    }
}
