package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolCategory
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.security.SsrfGuard
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 6.7.2 动态页面抓取工具：`fetch_url_markdown`
 *
 * 将任意网页转换为 Markdown，核心能力：
 * 1. **静态 Readability 提取**：基于 JSoup 实现类 Mozilla Readability 的候选评分算法，
 *    自动剔除导航、广告、评论区等噪声，保留正文并转成 Markdown。
 * 2. **可选 Playwright 渲染**：`use_browser=true` 时通过临时 Node 脚本调用 Playwright
 *    获取渲染后的 HTML，再交给同样的 Readability 提取器。Playwright 未安装时返回明确错误。
 *
 * 网络请求复用 [ProxyAwareHttpClientFactory]，SSRF 防护复用 [SsrfGuard]。
 */
class FetchUrlMarkdownTool : UnifiedTool(
    name = "fetch_url_markdown",
    description = """
        Summary: 抓取网页并把正文提取为 Markdown，适合读取文档、博客、API 说明。
        Args:
          - url (string, required): 目标网页 URL（http/https）。
          - use_browser (boolean, optional): 是否使用 Playwright 无头浏览器先渲染页面；默认 false。
          - max_length (integer, optional): 返回 Markdown 的最大字符数，默认 20000，范围 1000-100000。
          - timeout (integer, optional): 请求/渲染超时（毫秒），默认 15000，范围 1000-60000。
        Do: 获取文章正文、教程、参考文档时使用；复杂动态页面可尝试 use_browser=true。
        Don't: 不要抓取需要登录或受保护的页面；不要频繁请求同一站点。
        Returns: { url, title, markdown, length, truncated, extraction_method }。
    """.trimIndent(),
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "url" to ToolProperty(
                type = "string",
                description = "目标网页 URL（http/https）"
            ),
            "use_browser" to ToolProperty(
                type = "boolean",
                description = "是否使用 Playwright 无头浏览器先渲染页面；默认 false"
            ),
            "max_length" to ToolProperty(
                type = "integer",
                description = "返回 Markdown 的最大字符数，默认 20000"
            ),
            "timeout" to ToolProperty(
                type = "integer",
                description = "请求/渲染超时（毫秒），默认 15000"
            )
        ),
        required = listOf("url")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.SEARCH,
        tags = setOf("web", "markdown", "readability", "browser")
    )

    companion object {
        /**
         * SSRF 防护开关，默认为 true。
         * 仅用于测试环境绕过本地地址拦截。
         */
        var ssrfProtectionEnabled: Boolean = true

        private const val DEFAULT_MAX_LENGTH = 20_000
        private const val DEFAULT_TIMEOUT_MS = 15_000
        private const val ABSOLUTE_MAX_RESPONSE_BYTES = 5_242_880L // 5MB

        private val logger = Logger.getLogger<FetchUrlMarkdownTool>()
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.Error("Missing 'url' parameter")
        val useBrowser = args["use_browser"]?.jsonPrimitive?.booleanOrNull ?: false
        val maxLength = args["max_length"]?.jsonPrimitive?.intOrNull?.coerceIn(1_000, 100_000)
            ?: DEFAULT_MAX_LENGTH
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull?.coerceIn(1_000, 60_000)
            ?: DEFAULT_TIMEOUT_MS

        // 1. SSRF 防护
        if (ssrfProtectionEnabled) {
            when (val check = SsrfGuard.check(url)) {
                is SsrfGuard.CheckResult.Blocked -> {
                    return@withContext ToolResult.Error("SSRF blocked: ${check.reason}")
                }

                is SsrfGuard.CheckResult.Allowed -> { /* continue */
                }
            }
        }

        try {
            // 2. 获取 HTML（静态或浏览器渲染）
            val (html, title, extractionMethod) = if (useBrowser) {
                fetchWithBrowser(url, timeout)
            } else {
                fetchWithHttp(url, timeout)
            }

            // 3. Readability 提取 + Markdown 转换
            val doc = Jsoup.parse(html, url)
            val extracted = ReadabilityExtractor.extract(doc, fallbackTitle = title)
            val markdown = extracted.markdown
            val truncated = markdown.length > maxLength
            val outputMarkdown = if (truncated) markdown.take(maxLength) else markdown

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "url" to JsonPrimitive(url),
                        "title" to JsonPrimitive(extracted.title),
                        "markdown" to JsonPrimitive(outputMarkdown),
                        "length" to JsonPrimitive(markdown.length),
                        "truncated" to JsonPrimitive(truncated),
                        "extraction_method" to JsonPrimitive(extractionMethod)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("fetch_url_markdown failed: $url", e)
            ToolResult.Error("Fetch failed: ${e.message}")
        }
    }

    /**
     * 通过 OkHttp 发起静态请求。
     */
    private fun fetchWithHttp(url: String, timeoutMs: Int): Triple<String, String, String> {
        val client = ProxyAwareHttpClientFactory.build()
            .newBuilder()
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CodeSage/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }
            val body = response.body
            val contentType = body?.contentType()
            val bytes = body?.bytes() ?: ByteArray(0)
            if (bytes.size > ABSOLUTE_MAX_RESPONSE_BYTES) {
                throw IllegalStateException("Response exceeds ${ABSOLUTE_MAX_RESPONSE_BYTES} bytes")
            }
            val charset = contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val html = String(bytes, charset)
            return Triple(html, "", "static")
        }
    }

    /**
     * 通过临时 Node 脚本调用 Playwright 渲染页面。
     *
     * 若 Playwright 未安装，脚本会返回错误，工具再将其透传给调用方。
     */
    private fun fetchWithBrowser(url: String, timeoutMs: Int): Triple<String, String, String> {
        val tempScript = File.createTempFile("codesage_fetch_url_markdown_", ".mjs")
        tempScript.writeText(BROWSER_SCRIPT, Charsets.UTF_8)
        tempScript.deleteOnExit()

        val process = ProcessBuilder("node", tempScript.absolutePath, url, timeoutMs.toString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs.toLong() + 5_000, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            tempScript.delete()
            throw IllegalStateException("Browser rendering timed out")
        }
        tempScript.delete()

        if (process.exitValue() != 0) {
            throw IllegalStateException("Browser rendering failed: $output")
        }

        val json = Json.parseToJsonElement(output).jsonObject
        val html = json["html"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Browser rendering returned no HTML")
        val title = json["title"]?.jsonPrimitive?.content ?: ""
        return Triple(html, title, "browser")
    }

    /**
     * 内嵌 Playwright 临时脚本，避免项目依赖 Playwright（按需安装即可）。
     */
    private val BROWSER_SCRIPT = """
        const url = process.argv[2];
        const timeout = parseInt(process.argv[3] || "30000", 10);
        (async () => {
          let playwright;
          try {
            playwright = await import('playwright');
          } catch (e) {
            console.error(JSON.stringify({ error: 'Playwright not installed. Install it with: npm install playwright && npx playwright install chromium' }));
            process.exit(1);
          }
          let browser;
          try {
            browser = await playwright.chromium.launch();
            const page = await browser.newPage();
            await page.goto(url, { waitUntil: 'networkidle', timeout });
            const html = await page.content();
            const title = await page.title();
            console.log(JSON.stringify({ html, title, url }));
          } finally {
            if (browser) await browser.close();
          }
        })().catch(e => {
          console.error(JSON.stringify({ error: e.message }));
          process.exit(1);
        });
    """.trimIndent()
}

/**
 * 类 Readability 内容提取器。
 *
 * 基于标签语义、class/id 关键词、文本密度与链接密度对候选元素打分，
 * 选出最可能是正文的元素后，再剔除内部噪声节点。
 */
internal object ReadabilityExtractor {

    data class Result(val title: String, val markdown: String)

    fun extract(doc: Document, fallbackTitle: String = ""): Result {
        val title = doc.title()
            .ifBlank { doc.select("meta[property=og:title]").attr("content") }
            .ifBlank { fallbackTitle }

        // 在克隆文档上操作，避免污染原始解析结果
        val working = doc.clone()
        removeGlobalNoise(working)

        val candidate = findBestElement(working) ?: working.body()

        val cleaned = candidate.clone()
        removeLocalNoise(cleaned)

        val markdown = HtmlToMarkdown.convert(cleaned, doc.baseUri())
        return Result(title, markdown)
    }

    /**
     * 移除全局噪声标签：脚本、样式、导航、页头页脚、广告等。
     */
    private fun removeGlobalNoise(doc: Document) {
        doc.select(
            "script, style, noscript, nav, header, footer, aside, form, button, " +
                    "iframe, svg, canvas, audio, video, figure, figcaption, " +
                    "[class~=\\b(comment|comments|disqus|sidebar|widget|advert|ads|ad|sponsor|" +
                    "share|sharing|social|menu|navigation|navbar|breadcrumb|pagination|" +
                    "tag|tags|related|popular|recommend|subscribe|newsletter|rating)\\b], " +
                    "[id~=\\b(comment|comments|disqus|sidebar|widget|advert|ads|ad|sponsor|" +
                    "share|sharing|social|menu|navigation|navbar|breadcrumb|pagination|" +
                    "tag|tags|related|popular|recommend|subscribe|newsletter|rating)\\b]"
        ).remove()
    }

    /**
     * 在候选内容内部再次清理低价值节点。
     */
    private fun removeLocalNoise(root: Element) {
        root.select("nav, header, footer, aside, form, button, script, style, noscript, iframe").remove()

        val iterator = root.select("*").iterator()
        while (iterator.hasNext()) {
            val el = iterator.next()
            val text = el.text().trim()
            if (text.isEmpty()) continue

            val linkLen = el.select("a").sumOf { it.text().length }
            val linkDensity = linkLen.toDouble() / text.length.coerceAtLeast(1)
            val tag = el.tagName()

            // 移除短文本且链接密度过高的 div/section/p
            if (tag in setOf("div", "section", "p") && text.length < 25 && linkDensity > 0.3) {
                iterator.remove()
            }
        }
    }

    /**
     * 寻找最佳正文容器元素。
     */
    private fun findBestElement(doc: Document): Element? {
        // 1. 优先使用语义明确的 article/main
        doc.select("article").firstOrNull { readableTextLength(it) >= 200 }?.let { return it }
        doc.select("main, [role=main]").firstOrNull { readableTextLength(it) >= 200 }?.let { return it }

        // 2. 对所有块级候选打分
        val candidates = doc.select("div, section, article, td")
        var best: Element? = null
        var bestScore = -999.0

        for (el in candidates) {
            val textLen = readableTextLength(el)
            if (textLen < 100) continue

            var score = tagBaseScore(el.tagName())
            score += classIdScore(el)
            score += textLen / 100.0
            score += el.text().count { it == ',' }.toDouble()

            val linkDensity = linkTextLength(el).toDouble() / textLen.coerceAtLeast(1)
            if (linkDensity > 0.3) {
                score *= (1 - linkDensity)
            }

            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }

        return best
    }

    private fun readableTextLength(el: Element): Int = el.text().length

    private fun linkTextLength(el: Element): Int = el.select("a").sumOf { it.text().length }

    private fun tagBaseScore(tag: String): Double = when (tag) {
        "article" -> 20.0
        "main" -> 15.0
        "section" -> 5.0
        "div" -> 3.0
        "td" -> -2.0
        else -> 0.0
    }

    private fun classIdScore(el: Element): Double {
        val marker = "${el.attr("class")} ${el.attr("id")}".lowercase()
        val positive = listOf(
            "article", "content", "entry", "post", "text", "body", "column",
            "main", "markdown", "articletext", "articlebody", "entry-content",
            "post-content", "page-content", "story-body"
        )
        val negative = listOf(
            "comment", "comments", "meta", "footer", "footnote", "sidebar",
            "share", "widget", "header", "caption", "menu", "nav", "advert",
            "sponsor", "rating", "related", "popular", "pagination", "tag",
            "tags", "recommend", "subscribe", "newsletter"
        )

        var score = 0.0
        for (p in positive) {
            if (marker.contains(p)) score += 10.0
        }
        for (n in negative) {
            if (marker.contains(n)) score -= 15.0
        }
        return score
    }
}

/**
 * 将 HTML 元素递归转换为 Markdown。
 */
internal object HtmlToMarkdown {

    fun convert(element: Element, baseUri: String): String {
        val sb = StringBuilder()
        for (node in element.childNodes()) {
            sb.append(convertNode(node, baseUri))
        }
        return sb.toString()
            .trim()
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun convertNode(node: Node, baseUri: String): String = when (node) {
        is TextNode -> node.text()
        is Element -> convertElement(node, baseUri)
        else -> ""
    }

    private fun convertElement(el: Element, baseUri: String): String {
        val tag = el.tagName()
        val inlineChildren = { inlineChildren(el, baseUri) }

        return when (tag) {
            "h1" -> "# ${inlineChildren()}\n\n"
            "h2" -> "## ${inlineChildren()}\n\n"
            "h3" -> "### ${inlineChildren()}\n\n"
            "h4" -> "#### ${inlineChildren()}\n\n"
            "h5" -> "##### ${inlineChildren()}\n\n"
            "h6" -> "###### ${inlineChildren()}\n\n"
            "p" -> "${inlineChildren()}\n\n"
            "br" -> "\n"
            "a" -> {
                val text = inlineChildren()
                val href = el.absUrl("href").ifBlank { el.attr("href") }
                if (href.isNotBlank() && href != text) "[$text]($href)" else text
            }

            "img" -> {
                val src = el.absUrl("src").ifBlank { el.attr("src") }
                val alt = el.attr("alt")
                if (src.isNotBlank()) "![$alt]($src)" else ""
            }

            "strong", "b" -> "**${inlineChildren()}**"
            "em", "i" -> "*${inlineChildren()}*"
            "code" -> "`${inlineChildren()}`"
            "pre" -> {
                val code = el.select("code").firstOrNull()?.text() ?: el.text()
                "\n```\n${code.trim()}\n```\n\n"
            }

            "ul" -> el.children()
                .filter { it.tagName() == "li" }
                .joinToString("\n") { "- ${inlineChildren(it, baseUri)}" } + "\n\n"

            "ol" -> el.children()
                .filter { it.tagName() == "li" }
                .mapIndexed { index, li -> "${index + 1}. ${inlineChildren(li, baseUri)}" }
                .joinToString("\n") + "\n\n"

            "li" -> "- ${inlineChildren()}\n"
            "blockquote" -> "> ${inlineChildren().replace("\n", "\n> ")}\n\n"
            "hr" -> "---\n\n"
            "table" -> convertTable(el, baseUri)
            else -> {
                val childText = inlineChildren()
                if (childText.isNotBlank()) "$childText\n\n" else ""
            }
        }
    }

    private fun inlineChildren(el: Element, baseUri: String): String {
        return el.childNodes().joinToString("") { convertNode(it, baseUri) }.trim()
    }

    private fun convertTable(table: Element, baseUri: String): String {
        val rows = table.select("tr")
        if (rows.isEmpty()) return ""
        val lines = mutableListOf<String>()
        rows.forEachIndexed { index, row ->
            val cells = row.select("th, td").map { inlineChildren(it, baseUri) }
            lines.add("| " + cells.joinToString(" | ") + " |")
            if (index == 0) {
                lines.add("|" + cells.joinToString("|") { " --- " } + "|")
            }
        }
        return lines.joinToString("\n") + "\n\n"
    }
}
