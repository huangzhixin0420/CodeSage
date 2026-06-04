package com.codesage.ide.ui.web

import com.codesage.shared.utils.Logger
import java.util.Base64

/**
 * JS Bundler — 把多文件 ES module 打成单一非模块脚本
 *
 * 关键设计 — IIFE 包裹 + window.__bundle__ 命名空间:
 *  1. 每个源文件被独立 IIFE 包裹
 *  2. 顶层 const/let/class 都在 IIFE 局部 scope,同名 const 互不冲突
 *     (e.g. cs-toast.js 和 cs-inline-alert.js 都定义 VARIANT_ICONS 不再撞)
 *  3. export 的标识符挂到 window.__bundle__.X(消费侧可见)
 *  4. import { X } from "..." 在消费侧改成 `const X = window.__bundle__.X`
 *  5. 用 <script> 直接加载(非 module),file:// 下可正常跑
 *
 * 同时内联 Font Awesome CSS + 字体(woff2 → data: URI),
 * 解决 file:// 字体加载被拦截、图标显示空白的问题。
 */
object JsBundler {
    private val logger = Logger.getLogger<JsBundler>()

    data class Bundle(val js: String, val faCss: String)

    fun bundle(): Bundle {
        val classLoader = JsBundler::class.java.classLoader
        val order = topologicalSort("webui/js/main.js", classLoader)
        logger.info("[JsBundler] bundling ${order.size} JS files in order: $order")

        val js = StringBuilder()
        js.appendLine("/* CodeSage JS bundle — auto-generated at runtime. */")
        js.appendLine("/* Source: webui/js/* (${order.size} modules) */")
        js.appendLine("window.__bundle__ = window.__bundle__ || {};")
        js.appendLine("(function () {")
        js.appendLine("'use strict';")
        for (path in order) {
            val content = classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: continue
            js.appendLine()
            js.appendLine("/* ============================================================ */")
            js.appendLine("/* $path */")
            js.appendLine("/* ============================================================ */")
            js.append(processFile(content))
        }
        js.appendLine("})();")
        return Bundle(js = js.toString(), faCss = inlineFontAwesome(classLoader))
    }

    /**
     * 处理一个 JS 文件:
     *  1. 抹掉 import/export 语法
     *  2. 包成 IIFE(避免跨文件同名 const 冲突)
     *  3. export 名挂到 window.__bundle__
     *  4. import 名从 window.__bundle__ 取
     */
    private fun processFile(content: String): String {
        // 1) 收集 import 名 (在 strip 之前, 行首锚定避免误伤 JSDoc 里的 import 注释)
        val importNames = mutableListOf<String>()
        val importPattern = Regex("""(?m)^import\s*\{([^}]+)\}\s*from\s*["'][^"']+["'];?""")
        importPattern.findAll(content).forEach { m ->
            m.groupValues[1].split(",").forEach { part ->
                val name = part.trim().split(" as ").last().trim()
                if (name.isNotEmpty()) importNames.add(name)
            }
        }
        // 2) 收集 export 名
        val exportNames = mutableListOf<String>()
        Regex("""(?m)^export\s+(const|let|var)\s+(\w+)""").findAll(content).forEach { m ->
            exportNames.add(m.groupValues[2])
        }
        Regex("""(?m)^export\s+(class|function)\s+(\w+)""").findAll(content).forEach { m ->
            exportNames.add(m.groupValues[2])
        }
        Regex("""(?m)^export\s*\{([^}]+)\};?\s*$""").findAll(content).forEach { m ->
            m.groupValues[1].split(",").forEach { part ->
                val name = part.trim().split(" as ").last().trim()
                if (name.isNotEmpty()) exportNames.add(name)
            }
        }

        // 3) Strip import/export
        val stripped = stripModuleSyntax(content)

        // 4) 包成 IIFE + 注入 import/export 桥接
        val sb = StringBuilder()
        sb.appendLine("(function () {")
        for (name in importNames.distinct()) {
            sb.appendLine("    var $name = window.__bundle__.$name;")
        }
        sb.append(stripped)
        if (!stripped.endsWith("\n")) sb.appendLine()
        for (name in exportNames.distinct()) {
            sb.appendLine("    window.__bundle__.$name = $name;")
        }
        sb.appendLine("})();")
        return sb.toString()
    }

    /** 仅供测试用:跑一个文件过 stripModuleSyntax 后的结果 */
    @JvmStatic
    fun stripForTest(content: String): String = stripModuleSyntax(content)

    private fun stripModuleSyntax(content: String): String {
        var s = content
        // **重要: 所有 import/export 必须在行首 (^),MULTILINE 模式**
        // ES module 声明都在文件顶部的行首,模板字符串/HTML 属性里的 `import`/`export`
        // 子串不应被错误匹配(否则会吃掉模板字符串里的内容)。

        // 1) `import { X } from "Y"`  (多行支持, 必须从行首)
        s = s.replace(
            Regex(
                pattern = """(?m)^import\s*\{[^}]*\}\s*from\s*["'][^"']+["'];?\s*$""",
                option = RegexOption.DOT_MATCHES_ALL,
            ),
            "",
        )
        // 2) `import NAME from "Y"`
        s = s.replace(
            Regex(
                pattern = """(?m)^import\s+\w+\s+from\s*["'][^"']+["'];?\s*$""",
            ),
            "",
        )
        // 3) `import "Y"`  (side-effect)
        s = s.replace(
            Regex(
                pattern = """(?m)^import\s*["'][^"']+["'];?\s*$""",
            ),
            "",
        )
        // 4) 动态 import("Y") — 在非 module <script> 里 import() 是 SyntaxError
        s = s.replace(
            Regex(
                pattern = """\bimport\s*\(\s*["'][^"']+["']\s*\)""",
            ),
            "",
        )
        // 5) `export { X };`
        s = s.replace(
            Regex(
                pattern = """(?m)^export\s*\{[^}]*\};?\s*$""",
            ),
            "",
        )
        // 6) `export const|let|var|function|class NAME`
        s = s.replace(
            Regex(
                pattern = """(?m)^export\s+(const|let|var|function|class)\s+""",
            ),
            "$1 ",
        )
        // 7) `export default ...`
        s = s.replace(
            Regex(
                pattern = """(?m)^export\s+default\s+""",
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
                    """import\s*(?:\{[^}]*\}|\w+)?\s*from\s*["']([^"']+)["'];?""",
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
