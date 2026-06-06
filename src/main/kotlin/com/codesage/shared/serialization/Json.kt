package com.codesage.shared.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 共享 [Json] 实例。
 *
 * 之前项目里散落着 13+ 个 `private val json = Json { ignoreUnknownKeys = true }`，
 * 每次都创建新实例既浪费内存也容易因配置不一致导致序列化行为差异。
 *
 * 这里集中提供 3 个常用 preset：
 * - [default]: 通用，容错模式（ignoreUnknownKeys + isLenient）
 * - [pretty]: prettyPrint + ignoreUnknownKeys
 * - [strict]: 严格模式，序列化时拒收未知字段
 *
 * 用法：直接 import `com.codesage.shared.serialization.json` 或
 * `com.codesage.shared.serialization.SharedJson`。
 *
 * 注意：kotlinx.serialization 的 Json 是线程安全的（immutable config + 无状态 codec），
 * 所以单例共享是安全的。
 */
object SharedJson {
    /** 通用容错 Json：忽略未知字段、lenient 模式（允许非标准 JSON） */
    val default: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 带 prettyPrint 的调试用 Json */
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** 严格模式：用于 DTO 边界 */
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }
}

/**
 * 短别名，习惯性 import 后直接用 `json.parseToJsonElement(...)`。
 */
val json: Json get() = SharedJson.default
val prettyJson: Json get() = SharedJson.pretty
val strictJson: Json get() = SharedJson.strict
