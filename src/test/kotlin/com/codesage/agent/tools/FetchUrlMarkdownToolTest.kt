package com.codesage.agent.tools

import com.codesage.agent.tools.handlers.FetchUrlMarkdownTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 6.7.2 `fetch_url_markdown` 工具测试
 */
class FetchUrlMarkdownToolTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        FetchUrlMarkdownTool.ssrfProtectionEnabled = false
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
        FetchUrlMarkdownTool.ssrfProtectionEnabled = true
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `static extraction converts article HTML to markdown`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html>
                    <head><title>Test Article</title></head>
                    <body>
                      <nav><a href="/home">Home</a></nav>
                      <article class="post-content">
                        <h1>Hello World</h1>
                        <p>This is the <strong>first</strong> paragraph with <a href="/page">a link</a>.</p>
                        <ul>
                          <li>Item one</li>
                          <li>Item two</li>
                        </ul>
                        <pre><code>val x = 1</code></pre>
                      </article>
                      <footer>Footer noise</footer>
                    </body>
                    </html>
                    """.trimIndent()
                )
        )

        val url = server.url("/article").toString()
        val tool = FetchUrlMarkdownTool()
        val result = tool.execute(JsonObject(mapOf("url" to JsonPrimitive(url))))

        assertTrue(result is ToolResult.Success, "Expected success but got $result")
        val data = (result as ToolResult.Success).data as JsonObject
        assertEquals("Test Article", data["title"]?.jsonPrimitive?.content)
        assertEquals(url, data["url"]?.jsonPrimitive?.content)
        assertEquals("static", data["extraction_method"]?.jsonPrimitive?.content)

        val markdown = data["markdown"]?.jsonPrimitive?.content ?: ""
        assertTrue(markdown.contains("# Hello World"), "Should contain h1 heading: $markdown")
        assertTrue(markdown.contains("**first**"), "Should contain bold text: $markdown")
        assertTrue(markdown.contains("[a link](${server.url("/page")})"), "Should contain resolved link: $markdown")
        assertTrue(markdown.contains("- Item one"), "Should contain list item: $markdown")
        assertTrue(markdown.contains("`val x = 1`") || markdown.contains("val x = 1"), "Should contain code: $markdown")
        assertFalse(markdown.contains("Footer noise"), "Should strip footer noise: $markdown")
        assertFalse(markdown.contains("Home"), "Should strip nav noise: $markdown")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `truncates markdown when max_length is exceeded`() = runBlocking {
        val body = buildString {
            append("<html><head><title>Long</title></head><body><article>")
            append("<p>")
            repeat(500) { append("word ") }
            append("</p>")
            append("</article></body></html>")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(body)
        )

        val url = server.url("/long").toString()
        val tool = FetchUrlMarkdownTool()
        val result = tool.execute(
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive(url),
                    "max_length" to JsonPrimitive(1000)
                )
            )
        )

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data as JsonObject
        val markdown = data["markdown"]?.jsonPrimitive?.content ?: ""
        val length = data["length"]?.jsonPrimitive?.int ?: 0
        assertEquals(true, data["truncated"]?.jsonPrimitive?.boolean)
        assertTrue(markdown.length <= 1000)
        assertTrue(length > 1000)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `returns error for HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        val url = server.url("/error").toString()
        val tool = FetchUrlMarkdownTool()
        val result = tool.execute(JsonObject(mapOf("url" to JsonPrimitive(url))))

        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("500"))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `blocks private URLs when SSRF protection enabled`() = runBlocking {
        FetchUrlMarkdownTool.ssrfProtectionEnabled = true
        val tool = FetchUrlMarkdownTool()
        val result = tool.execute(JsonObject(mapOf("url" to JsonPrimitive("http://127.0.0.1:8080/secret"))))

        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("SSRF blocked"))
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `use_browser returns error when Playwright is not installed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body><p>ignored</p></body></html>")
        )

        val url = server.url("/dynamic").toString()
        val tool = FetchUrlMarkdownTool()
        val result = tool.execute(
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive(url),
                    "use_browser" to JsonPrimitive(true)
                )
            )
        )

        assertTrue(result is ToolResult.Error)
        val message = (result as ToolResult.Error).message
        assertTrue(
            message.contains("Playwright", ignoreCase = true) || message.contains("Browser rendering failed"),
            "Expected Playwright-related error, got: $message"
        )
    }
}
