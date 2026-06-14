package com.codesage.ide.ui.web

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 主聊天输入区字符计数与上限警告回归测试。
 *
 * 通过检查源码契约,确保 HTML/CSS/JS 三层实现一致,
 * 不依赖 JCEF 运行时,可在单元测试阶段快速发现回归。
 */
class InputCharCountTest {

    @Test
    fun `HTML contains input-char-count inside textarea wrap`() {
        val html = readFile("src/main/resources/webui/index.html")
        val wrap = html.substringAfter("<div class=\"input-textarea-wrap\"")
            .substringBefore("</div>")
        assertTrue(
            wrap.contains("id=\"input-char-count\""),
            ".input-textarea-wrap 内应包含 id=input-char-count 的元素"
        )
        assertTrue(
            wrap.contains("class=\"input-char-count\""),
            "计数元素应使用 .input-char-count 类"
        )
        assertTrue(
            wrap.contains(">0 / 4000<") || wrap.contains(">0 / 4000</span>"),
            "计数元素默认文本应为 \"0 / 4000\""
        )
    }

    @Test
    fun `CSS defines input-char-count styles with warning and error states`() {
        val css = readFile("src/main/resources/webui/styles/input.css")
        assertTrue(
            css.contains(".input-char-count"),
            "input.css 应定义 .input-char-count"
        )
        assertTrue(
            css.contains(".input-char-count.warning"),
            "input.css 应定义 .input-char-count.warning"
        )
        assertTrue(
            css.contains(".input-char-count.error"),
            "input.css 应定义 .input-char-count.error"
        )
        val rule = extractCssRule(css, ".input-char-count")
        assertTrue(
            rule.contains("font-size:") && rule.contains("var(--text-2xs)"),
            ".input-char-count 应使用 var(--text-2xs) 字体大小"
        )
        assertTrue(
            rule.contains("color:") && rule.contains("var(--fg-tertiary)"),
            ".input-char-count 默认颜色应为 var(--fg-tertiary)"
        )
        assertTrue(
            extractCssRule(css, ".input-char-count.warning").contains("var(--warning)"),
            ".input-char-count.warning 应使用 var(--warning)"
        )
        assertTrue(
            extractCssRule(css, ".input-char-count.error").contains("var(--error)"),
            ".input-char-count.error 应使用 var(--error)"
        )
    }

    @Test
    fun `JS defines max input length constant and references it consistently`() {
        val js = readFile("src/main/resources/webui/js/views/chat.js")
        assertTrue(
            js.contains("const MAX_INPUT_LENGTH = 4000"),
            "chat.js 应定义 MAX_INPUT_LENGTH = 4000"
        )
        val count = Regex("MAX_INPUT_LENGTH").findAll(js).count()
        assertTrue(
            count >= 3,
            "MAX_INPUT_LENGTH 应在多处被引用(截断/提交/计数),实际 $count 处"
        )
    }

    @Test
    fun `JS binds charCountEl and updates counter on input`() {
        val js = readFile("src/main/resources/webui/js/views/chat.js")
        assertTrue(
            js.contains("this.charCountEl = document.getElementById(\"input-char-count\")"),
            "init() 应缓存 #input-char-count 引用"
        )
        assertTrue(
            js.contains("_updateCharCount = updateCharCount"),
            "应把 updateCharCount 暴露给实例方法 _updateCharCount"
        )
        assertTrue(
            js.contains("addEventListener(\"input\"") && js.contains("_updateCharCount"),
            "应为 textarea 注册 input 事件并更新计数"
        )
        assertTrue(
            js.contains("textContent = `${'$'}{len} / ${'$'}{MAX_INPUT_LENGTH}`"),
            "计数文本应格式化为 \"当前 / 4000\""
        )
        assertTrue(
            js.contains("\"warning\"") && js.contains("len >= 3600 && len < MAX_INPUT_LENGTH"),
            "3600-3999 字符时应切换 warning 类"
        )
        assertTrue(
            js.contains("classList.toggle(\"error\", len >= MAX_INPUT_LENGTH)"),
            "4000 字符时应切换 error 类"
        )
    }

    @Test
    fun `JS submit blocks sending when at max length`() {
        val js = readFile("src/main/resources/webui/js/views/chat.js")
        assertTrue(
            js.contains("v.length >= MAX_INPUT_LENGTH"),
            "submit 应检查 v.length >= MAX_INPUT_LENGTH"
        )
        assertTrue(
            js.contains("已达到") && js.contains("字符上限"),
            "submit 应在达到上限时弹出提示"
        )
    }

    @Test
    fun `JS input listener truncates overflow and warns on paste`() {
        val js = readFile("src/main/resources/webui/js/views/chat.js")
        assertTrue(
            js.contains("ta.value.substring(0, MAX_INPUT_LENGTH)"),
            "input 监听器应将超长内容截断到 MAX_INPUT_LENGTH"
        )
        assertTrue(
            js.contains("insertFromPaste"),
            "input 监听器应识别粘贴输入类型"
        )
        assertTrue(
            js.contains("已自动截断至") && js.contains("字符"),
            "粘贴截断时应提示用户"
        )
    }

    // ==================== helpers ====================

    private fun readFile(path: String): String {
        return try {
            java.io.File(path).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            fail<String>("Cannot read $path: ${e.message}")
            ""
        }
    }

    private fun extractCssRule(css: String, selector: String): String {
        val idx = css.indexOf(selector)
        if (idx < 0) return ""
        var i = idx + selector.length
        while (i < css.length && css[i] != '{') i++
        if (i >= css.length) return ""
        var depth = 1
        var end = i + 1
        while (end < css.length && depth > 0) {
            if (css[end] == '{') depth++
            else if (css[end] == '}') depth--
            end++
        }
        return css.substring(idx, end)
    }
}
