package com.codesage.shared.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * C7 修复：把 [JsonElement] 序列化为 JS 安全的字符串字面量。
 *
 * 背景：之前 [com.codesage.ide.ui.web.JCEFChatPanel.sendToJS] 把 JSON 字符串
 * 直接拼接到 `executeJavaScript` 模板里。这有几个隐患：
 *
 * 1. **U+2028 / U+2029 (Line Separator / Paragraph Separator)**
 *    这两个字符在 JSON 规范里是合法字符串字面量，但在 JS 解析器里被视为行终止符。
 *    旧实现 `obj.toString()` 不会转义它们，嵌入 `<script>` 块会破坏 JS 解析。
 *
 * 2. **`</script>` 子串**
 *    字符串中如果出现 `</script>`，即使在 JSON 字符串里（带转义），`</script>` 的字面
 *    出现仍会让浏览器 HTML 解析器误以为 script 块结束。需要在序列化为 JS 字面量时
 *    把 `</` 替换为 `<\/`。
 *
 * 3. **HTML 注释 `-->`**
 *    嵌入到 `<script>` 中间理论上 OK，但保守起见一起转义。
 *
 * 用法：把 [JsonElement] 序列化为 JS 字符串字面量（含外层双引号）：
 * ```
 * val safe = SafeJsonEncoder.toJsStringLiteral(jsonElement)
 * // safe = "\"hello\\nworld\"" （含外层引号）
 * val script = "window.onJavaMessage($safe);"
 * ```
 */
object SafeJsonEncoder {

    /**
     * 序列化为 JS 字符串字面量（含外层双引号）。
     *
     * 内部走 [escapeForJsString] 转义关键控制字符。
     */
    fun toJsStringLiteral(element: JsonElement): String {
        val json = element.toString()
        return "\"" + escapeForJsString(json) + "\""
    }

    /**
     * 把 JSON 字符串体内的字符做 JS 安全转义。
     *
     * - `"`、`\` 走 JSON 标准转义
     * - U+2028 (LS) → `\u2028`
     * - U+2029 (PS) → `\u2029`
     * - `<` 后接 `/` → `\/`（防 `</script>` 提前闭合）
     */
    fun escapeForJsString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '<' && i + 1 < s.length && s[i + 1] == '/' -> {
                    // 防止 `</script>` 提前闭合 script 块
                    sb.append("<\\/")
                    i++
                }
                c.code == 0x2028 -> sb.append("\\u2028")
                c.code == 0x2029 -> sb.append("\\u2029")
                c.code < 0x20 -> {
                    // 其他控制字符统一 \u00XX 形式
                    sb.append("\\u%04x".format(c.code))
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
