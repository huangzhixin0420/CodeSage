package com.codesage.ide.ui.web

import java.util.Base64

/**
 * 将 chat.html 中的外部 CDN 依赖替换为本地资源的 Data URI 或直接内联内容。
 *
 * 由于 JCEFChatPanel 使用 loadHTML() 内联加载 HTML，相对路径无法解析到 resources 目录。
 * 本工具类在运行时读取 resources/webui/lib/ 下的静态文件：
 * - JS 文件编码为 Base64 Data URI
 * - CSS 文件编码为 Base64 Data URI
 * - Font Awesome CSS 直接内联为 `<style>` 标签，其字体文件引用替换为 Base64 Data URI，
 *   避免 Chromium 安全策略阻止从 http:// 页面加载 file:// 字体资源。
 *
 * 若某个本地资源缺失，则保留原始 CDN URL 作为 fallback，确保在线环境仍可正常工作。
 */
object ResourceInliner {
    private val encoder = Base64.getEncoder()

    private data class ResourceMapping(
        val cdnUrl: String,
        val resourcePath: String,
        val mimeType: String,
        val isCssWithFonts: Boolean = false,
        val isTailwind: Boolean = false
    )

    private val mappings = listOf(
        // TailwindCSS (script tag -> link tag with data URI)
        ResourceMapping(
            "https://cdn.tailwindcss.com",
            "webui/lib/tailwind.generated.css",
            "text/css",
            isTailwind = true
        ),

        // Highlight.js themes
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css",
            "webui/lib/github-dark.min.css",
            "text/css"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css",
            "webui/lib/github.min.css",
            "text/css"
        ),

        // Highlight.js core
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js",
            "webui/lib/highlight.min.js",
            "text/javascript"
        ),

        // Highlight.js languages
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/kotlin.min.js",
            "webui/lib/languages/kotlin.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/java.min.js",
            "webui/lib/languages/java.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/python.min.js",
            "webui/lib/languages/python.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/javascript.min.js",
            "webui/lib/languages/javascript.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/typescript.min.js",
            "webui/lib/languages/typescript.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/go.min.js",
            "webui/lib/languages/go.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/rust.min.js",
            "webui/lib/languages/rust.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/xml.min.js",
            "webui/lib/languages/xml.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/css.min.js",
            "webui/lib/languages/css.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/json.min.js",
            "webui/lib/languages/json.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/yaml.min.js",
            "webui/lib/languages/yaml.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js",
            "webui/lib/languages/bash.min.js",
            "text/javascript"
        ),
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/sql.min.js",
            "webui/lib/languages/sql.min.js",
            "text/javascript"
        ),

        // Marked.js
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/marked/9.1.6/marked.min.js",
            "webui/lib/marked.min.js",
            "text/javascript"
        ),

        // Font Awesome (CSS with file:// font references, inlined as <style>)
        ResourceMapping(
            "https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css",
            "webui/lib/font-awesome/all.min.css",
            "text/css",
            isCssWithFonts = true
        )
    )

    /**
     * 将 HTML 内容中的所有 CDN URL 替换为本地资源。
     *
     * @param htmlContent 原始的 chat.html 内容
     * @return 替换后的 HTML 内容
     */
    fun inlineResources(htmlContent: String): String {
        val classLoader = javaClass.classLoader
        var result = htmlContent
        val missingResources = mutableListOf<String>()

        for (mapping in mappings) {
            val bytes = classLoader.getResourceAsStream(mapping.resourcePath)?.use { it.readBytes() }
            if (bytes == null) {
                missingResources.add(mapping.resourcePath)
                continue
            }

            result = when {
                mapping.isTailwind -> {
                    val base64 = encoder.encodeToString(bytes)
                    val dataUri = "data:${mapping.mimeType};base64,$base64"
                    result.replace(
                        """<script src="https://cdn.tailwindcss.com"></script>""",
                        """<link rel="stylesheet" href="$dataUri" />"""
                    )
                }

                mapping.isCssWithFonts -> {
                    val css = generateFontAwesomeInlineCss(bytes)
                    result.replace(
                        Regex(
                            """<link\s[^>]*href=["'']?""" + Regex.escape(mapping.cdnUrl) + """["'']?[^>]*/?>""",
                            RegexOption.IGNORE_CASE
                        )
                    ) { """<style>$css</style>""" }
                }

                else -> {
                    val base64 = encoder.encodeToString(bytes)
                    val dataUri = "data:${mapping.mimeType};base64,$base64"
                    result.replace(mapping.cdnUrl, dataUri)
                }
            }
        }

        if (missingResources.isNotEmpty()) {
            println("[ResourceInliner] Warning: Missing local resources, falling back to CDN: $missingResources")
        }

        return result
    }

    /**
     * 处理 Font Awesome CSS，将其引用的字体文件路径替换为 Base64 Data URI。
     * 由于 JCEF 页面使用 http://codesage.local 作为 base URL，从 HTTP 页面加载
     * file:// 字体文件会被 Chromium 安全策略阻止，因此必须内联为 Data URI。
     *
     * 为控制 HTML 体积在 2MB 以内，仅保留 woff2 格式（现代浏览器/Chromium 均支持），
     * 并删除 ttf 引用，避免同一字体被重复嵌入多次。
     */
    private fun generateFontAwesomeInlineCss(cssBytes: ByteArray): String {
        var css = cssBytes.toString(Charsets.UTF_8)
        val classLoader = javaClass.classLoader

        // 第一步：删除所有 ttf 引用（woff2 已足够支持现代浏览器）
        val ttfPattern = Regex(""",url\((['"]?)\.\./webfonts/[^'"\)]+\.ttf\1\)\s*format\(["']?truetype["']?\)""")
        css = ttfPattern.replace(css, "")

        // 第二步：将 woff2 字体引用替换为 Base64 Data URI
        val woff2Pattern = Regex("""url\((['"]?)\.\./webfonts/([^'"\)]+\.woff2)\1\)""")
        val uniqueFonts = woff2Pattern.findAll(css).map { it.groupValues[2] }.toSortedSet()
        val fontDataUris = mutableMapOf<String, String>()

        for (fontFile in uniqueFonts) {
            val fontBytes =
                classLoader.getResourceAsStream("webui/lib/font-awesome/webfonts/$fontFile")?.use { it.readBytes() }
            if (fontBytes != null) {
                val base64 = encoder.encodeToString(fontBytes)
                fontDataUris[fontFile] = "data:font/woff2;base64,$base64"
            }
        }

        css = woff2Pattern.replace(css) { match ->
            val quote = match.groupValues[1]
            val fontFile = match.groupValues[2]
            val dataUri = fontDataUris[fontFile]
            if (dataUri != null) {
                "url(${quote}$dataUri${quote})"
            } else {
                match.value
            }
        }

        return css
    }
}
