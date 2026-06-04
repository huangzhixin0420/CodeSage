package com.codesage.ide.ui.web

import com.codesage.shared.utils.Logger
import java.util.Base64

/**
 * JS Bundler — 把多文件 ES module 打成单一非模块脚本
 *
 * 为什么需要这个:
 *   - JCEF 是基于 Chromium 内核,加载 file:// 页面时默认禁止 ES module 的 import
 *     (即便是 same-folder import 也会因为 "null origin" 被拦截)
 *   - 旧 index.html 用 type="module" 加载 js/main.js,导致整个 chat.js 没跑
 *   - 所有 onclick="CodeSage.chat.xxx()" 因为 CodeSage.chat 是 undefined 静默失败
 *   - 用户看到:enter 换行、按钮无反应、字数不更新、mode 下拉不出
 *
 * 解决方案:
 *   1. 在 extraction 时把 js/main.js + 所有 import 递归合成一个 bundle.js
 *   2. 抹掉 import/export 语法
 *   3. 用 IIFE 包裹,避免变量污染 window
 *   4. HTML 改为 <script src="js/bundle.js">(非 module),file:// 下可正常加载
 *
 * 同时还会内联 Font Awesome CSS + 字体(woff2 → data: URI),
 * 解决 file:// 字体加载被拦截、图标显示空白的问题。
 */
object JsBundler {
    private val logger = Logger.getLogger<JsBundler>()

    fun bundle(): Bundle {
        val classLoader = JsBundler::class.java.classLoader
        val order = topologicalSort("webui/js/main.js", classLoader)
        logger.info("[JsBundler] bundling ${order.size} JS files in order: $order")

        val js = StringBuilder()
        js.appendLine("/* CodeSage JS bundle — auto-generated at runtime. */")
        js.appendLine("/* Source: webui/js/* (${order.size} modules) */")
        js.appendLine("(function () {")
        js.appendLine("'use strict';")
        for (path in order) {
            val content = classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: continue
            js.appendLine()
            js.appendLine("/* ============================================================ */")
            js.appendLine("/* $path */")
            js.appendLine("/* ============================================================ */")
            js.append(stripModuleSyntax(content))
            if (!content.endsWith("\n")) js.appendLine()
        }
        js.appendLine("})();")
        return Bundle(js = js.toString(), faCss = inlineFontAwesome(classLoader))
    }

    data class Bundle(val js: String, val faCss: String)

    private fun stripModuleSyntax(content: String): String {
        var s = content
        // 1) `import { X } from "Y"`  (多行支持)
        s = s.replace(
            Regex(
                pattern = """import\s*\{[^}]*\}\s*from\s*["'][^"']+["'];?\s*\n?""",
                option = RegexOption.DOT_MATCHES_ALL,
            ),
            "",
        )
        // 2) `import NAME from "Y"`
        s = s.replace(
            Regex(
                pattern = """import\s+\w+\s+from\s*["'][^"']+["'];?\s*\n?""",
            ),
            "",
        )
        // 3) `import "Y"`  (side-effect)
        s = s.replace(
            Regex(
                pattern = """import\s*["'][^"']+["'];?\s*\n?""",
            ),
            "",
        )
        // 4) `export { X };`
        s = s.replace(
            Regex(
                pattern = """^export\s*\{[^}]*\};?\s*$""",
                option = RegexOption.MULTILINE,
            ),
            "",
        )
        // 5) `export const|let|var|function|class NAME`
        s = s.replace(
            Regex(
                pattern = """^export\s+(const|let|var|function|class)\s+""",
                option = RegexOption.MULTILINE,
            ),
            "$1 ",
        )
        // 6) `export default ...`
        s = s.replace(
            Regex(
                pattern = """^export\s+default\s+""",
                option = RegexOption.MULTILINE,
            ),
            "",
        )
        return s
    }

    private fun topologicalSort(entry: String, classLoader: ClassLoader): List<String> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<String>()
        val inProgress = mutableSetOf<String>()

        fun visit(path: String) {
            if (path in visited) return
            if (path in inProgress) {
                logger.warn("[JsBundler] circular import at $path, breaking cycle")
                return
            }
            inProgress.add(path)
            val content = classLoader.getResourceAsStream(path)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            if (content != null) {
                val importRegex = Regex(
                    pattern = """import\s*(?:\{[^}]*\}|\w+)?\s*from\s*["']([^"']+)["'];?""",
                )
                for (match in importRegex.findAll(content)) {
                    val importPath = match.groupValues[1]
                    val resolved = resolveImport(path, importPath)
                    if (resolved.startsWith("webui/")) {
                        visit(resolved)
                    }
                }
            }
            inProgress.remove(path)
            visited.add(path)
            order.add(path)
        }

        visit(entry)
        return order
    }

    private fun resolveImport(from: String, importPath: String): String {
        if (!importPath.startsWith(".")) return importPath
        val fromDir = from.substringBeforeLast('/')
        val parts = (fromDir + "/" + importPath).split("/").toMutableList()
        val stack = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "" -> {}
                "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else stack.add(part)
                else -> stack.add(part)
            }
        }
        val resolved = stack.joinToString("/")
        return if (resolved.endsWith(".js")) resolved else "$resolved.js"
    }

    private fun inlineFontAwesome(classLoader: ClassLoader): String {
        val cssBytes = classLoader.getResourceAsStream("webui/lib/font-awesome/all.min.css")?.use { it.readBytes() }
            ?: return ""
        var css = String(cssBytes, Charsets.UTF_8)

        val ttfPattern = Regex(
            pattern = """,url\((['"]?)\.\./webfonts/[^'"\)]+\.ttf\1\)\s*format\(["']?truetype["']?\)""",
        )
        css = ttfPattern.replace(css, "")

        val woff2Pattern = Regex(
            pattern = """url\((['"]?)\.\./webfonts/([^'"\)]+\.woff2)\1\)""",
        )
        val uniqueFonts = woff2Pattern.findAll(css).map { it.groupValues[2] }.toSortedSet()
        val fontDataUris = mutableMapOf<String, String>()
        for (fontFile in uniqueFonts) {
            val fontBytes =
                classLoader.getResourceAsStream("webui/lib/font-awesome/webfonts/$fontFile")?.use { it.readBytes() }
            if (fontBytes != null) {
                val base64 = Base64.getEncoder().encodeToString(fontBytes)
                fontDataUris[fontFile] = "data:font/woff2;base64,$base64"
            }
        }
        css = woff2Pattern.replace(css) { match ->
            val quote = match.groupValues[1]
            val fontFile = match.groupValues[2]
            val dataUri = fontDataUris[fontFile]
            if (dataUri != null) "url(${quote}$dataUri${quote})" else match.value
        }
        return css
    }
}
