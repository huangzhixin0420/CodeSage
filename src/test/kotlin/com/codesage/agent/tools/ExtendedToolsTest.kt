package com.codesage.agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExtendedToolsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun makeArgs(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private lateinit var mockServer: MockWebServer

    @BeforeEach
    fun setupMockServer() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterEach
    fun teardownMockServer() {
        mockServer.shutdown()
    }

    // ===== Git Tools =====

    @Test
    fun `git_status should return changed files`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        val tools = ExtendedTools(project = null)
        val args = makeArgs("working_dir" to JsonPrimitive(tempDir.absolutePath))
        val result = tools.gitStatus(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data.containsKey("branch"))
        assertTrue(data.containsKey("changed_files"))
        assertTrue(data.containsKey("count"))
    }

    @Test
    fun `git_status should fail for non git repo`() {
        val tools = ExtendedTools(project = null)
        val args = makeArgs("working_dir" to JsonPrimitive(System.getProperty("java.io.tmpdir")))
        val result = tools.gitStatus(args)

        assertTrue(result is ToolResult.Error, "Expected error for non-git directory")
        assertTrue((result as ToolResult.Error).message.contains("Not a Git repository", ignoreCase = true))
    }

    @Test
    fun `git_diff should return structured diff output`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        File(tempDir, "test.txt").writeText("hello")
        exec(tempDir, "git", "add", ".")
        exec(tempDir, "git", "commit", "-m", "initial")
        File(tempDir, "test.txt").writeText("hello world")

        val tools = ExtendedTools(project = null)
        val args = makeArgs("working_dir" to JsonPrimitive(tempDir.absolutePath))
        val result = tools.gitDiff(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data.containsKey("files"), "Expected structured files array")
        assertTrue(data.containsKey("has_changes"))
        assertTrue(data.containsKey("total_changes"))
        assertTrue(data["has_changes"]?.jsonPrimitive?.booleanOrNull == true)

        val files = data["files"]?.jsonArray
        assertNotNull(files)
        assertTrue(files!!.isNotEmpty())
        val firstFile = files[0].jsonObject
        assertEquals("test.txt", firstFile["new_path"]?.jsonPrimitive?.content)
        assertTrue(firstFile.containsKey("hunks"))
        assertTrue(firstFile.containsKey("additions"))
        assertTrue(firstFile.containsKey("deletions"))
    }

    @Test
    fun `git_diff should include raw diff when include_raw is true`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        File(tempDir, "test.txt").writeText("hello")
        exec(tempDir, "git", "add", ".")
        exec(tempDir, "git", "commit", "-m", "initial")
        File(tempDir, "test.txt").writeText("hello world")

        val tools = ExtendedTools(project = null)
        val args = makeArgs(
            "working_dir" to JsonPrimitive(tempDir.absolutePath),
            "include_raw" to JsonPrimitive(true)
        )
        val result = tools.gitDiff(args)

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data.containsKey("raw_diff"))
        assertTrue(data["raw_diff"]?.jsonPrimitive?.content?.contains("diff --git") == true)
    }

    @Test
    fun `git_log should return commits`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        File(tempDir, "test.txt").writeText("a")
        exec(tempDir, "git", "add", ".")
        exec(tempDir, "git", "commit", "-m", "first")

        val tools = ExtendedTools(project = null)
        val args = makeArgs(
            "working_dir" to JsonPrimitive(tempDir.absolutePath),
            "limit" to JsonPrimitive(5)
        )
        val result = tools.gitLog(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        val commits = data["commits"]?.jsonArray
        assertNotNull(commits)
        assertTrue(commits!!.size >= 1)
        assertTrue(commits[0].jsonObject.containsKey("hash"))
        assertTrue(commits[0].jsonObject.containsKey("message"))
    }

    @Test
    fun `git_branch should identify current branch`(@TempDir tempDir: File) {
        initGitRepo(tempDir)
        val tools = ExtendedTools(project = null)
        val args = makeArgs("working_dir" to JsonPrimitive(tempDir.absolutePath))
        val result = tools.gitBranch(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data.containsKey("current"))
        assertTrue(data.containsKey("branches"))
        val branches = data["branches"]?.jsonArray
        assertNotNull(branches)
        assertTrue(branches!!.size > 0)
        val hasCurrent = branches.any {
            it.jsonObject["current"]?.jsonPrimitive?.booleanOrNull == true
        }
        assertTrue(hasCurrent, "Should have a current branch marked")
    }

    private fun initGitRepo(dir: File) {
        exec(dir, "git", "init")
        exec(dir, "git", "config", "user.email", "test@test.com")
        exec(dir, "git", "config", "user.name", "Test")
        File(dir, "init.txt").writeText("init")
        exec(dir, "git", "add", ".")
        exec(dir, "git", "commit", "-m", "init")
    }

    private fun exec(dir: File, vararg cmd: String) {
        ProcessBuilder(*cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    // ===== Shell Tool =====

    @Test
    fun `exec_shell should return command output`() = runBlocking {
        val tools = ExtendedTools(project = null)
        val args = makeArgs("command" to JsonPrimitive("echo HelloWorld"))
        val result = tools.execShell(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(0, data["exit_code"]?.jsonPrimitive?.int)
        assertTrue(data["stdout"]?.jsonPrimitive?.content?.contains("HelloWorld") == true)
    }

    @Test
    fun `exec_shell should respect timeout`() = runBlocking {
        val tools = ExtendedTools(project = null)
        val args = makeArgs(
            "command" to JsonPrimitive("sleep 5"),
            "timeout" to JsonPrimitive(500)
        )
        val start = System.currentTimeMillis()
        val result = tools.execShell(args)
        val duration = System.currentTimeMillis() - start

        assertTrue(result is ToolResult.Error, "Expected timeout error")
        assertTrue((result as ToolResult.Error).message.contains("timed out", ignoreCase = true))
        assertTrue(duration < 3000, "Timeout should occur quickly, but took ${duration}ms")
    }

    @Test
    fun `exec_shell should delegate security to guardrails`() = runBlocking {
        val tools = ExtendedTools(project = null)
        // ExtendedTools 不再执行独立的命令拦截，安全策略统一由 ToolGuardrails 处理。
        // 此处验证 exec_shell 本身不会直接拒绝命令，而是正常执行（或返回命令执行结果）。
        val args = makeArgs("command" to JsonPrimitive("echo hello"))
        val result = tools.execShell(args)
        assertTrue(result is ToolResult.Success, "exec_shell should execute command via guardrails delegation")
    }

    @Test
    fun `exec_shell should allow safe commands`() = runBlocking {
        val tools = ExtendedTools(project = null)
        val args = makeArgs("command" to JsonPrimitive("pwd"))
        val result = tools.execShell(args)

        assertTrue(result is ToolResult.Success, "Safe command should succeed: $result")
    }

    // ===== HTTP Tool =====

    @Test
    fun `http_request should support GET`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"message":"hello"}""")
                .addHeader("Content-Type", "application/json")
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val args = makeArgs(
            "url" to JsonPrimitive(mockServer.url("/get").toString()),
            "method" to JsonPrimitive("GET")
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(200, data["status_code"]?.jsonPrimitive?.int)
        assertTrue(data["is_successful"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `http_request should support POST with body`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"received":"ok"}""")
                .addHeader("Content-Type", "application/json")
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val args = makeArgs(
            "url" to JsonPrimitive(mockServer.url("/post").toString()),
            "method" to JsonPrimitive("POST"),
            "body" to JsonPrimitive("{\"test\":\"value\"}"),
            "headers" to JsonObject(mapOf("Content-Type" to JsonPrimitive("application/json")))
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(200, data["status_code"]?.jsonPrimitive?.int)

        // 验证请求确实发送了 POST body
        val request = mockServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("{\"test\":\"value\"}", request.body.readUtf8())
    }

    @Test
    fun `http_request should truncate response larger than max_size_bytes`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("abcdefghij")
                .addHeader("Content-Type", "text/plain")
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val args = makeArgs(
            "url" to JsonPrimitive(mockServer.url("/large").toString()),
            "max_size_bytes" to JsonPrimitive(5)
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertTrue(data["truncated"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(5, data["max_size_bytes"]?.jsonPrimitive?.long)
        assertTrue((data["body"]?.jsonPrimitive?.content?.length ?: 100) <= 5)
        assertTrue((data["body_size"]?.jsonPrimitive?.long ?: 0L) >= 10L)
    }

    @Test
    fun `http_request should stream full response to output_file`() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("hello world via file")
                .addHeader("Content-Type", "text/plain")
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val outputFile = File.createTempFile("codesage_http_test", ".txt")
        outputFile.deleteOnExit()

        val args = makeArgs(
            "url" to JsonPrimitive(mockServer.url("/download").toString()),
            "output_file" to JsonPrimitive(outputFile.absolutePath)
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(outputFile.absolutePath, data["saved_to"]?.jsonPrimitive?.content)
        assertEquals("hello world via file".length.toLong(), data["size"]?.jsonPrimitive?.long)
        assertFalse(data.containsKey("body"))
        assertEquals("hello world via file", outputFile.readText())
    }

    @Test
    fun `http_request should block internal URLs`() = runBlocking {
        val tools = ExtendedTools(project = null)
        val blockedUrls = listOf(
            "http://127.0.0.1:8080/api",
            "http://localhost:3000",
            "http://192.168.1.1",
            "file:///etc/passwd"
        )

        for (url in blockedUrls) {
            val args = makeArgs("url" to JsonPrimitive(url))
            val result = tools.httpRequest(args)
            assertTrue(result is ToolResult.Error, "URL should be blocked: $url")
            assertTrue(
                (result as ToolResult.Error).message.contains("Access denied", ignoreCase = true),
                "Expected access denied for: $url but got: ${result.message}"
            )
        }
    }

    /**
     * 连接超时(模拟 SOCKS 代理连不上、目标无响应等场景)。
     * 之前只 catch (Exception),返回的 message 是裸的 "timeout" — 用户不知道
     * 是连接阶段还是读取阶段、是代理问题还是目标问题。
     * v2.2:返回带 timeout 时长 + 调试建议的友好消息。
     */
    @Test
    fun `http_request should return friendly timeout error`() = runBlocking {
        // MockWebServer 不响应 — OkHttp 会触发 connect / read timeout
        // 把 body 故意 bodyLength=0 但不 setBody,客户端 read 会卡住
        mockServer.enqueue(
            MockResponse()
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE),
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        // 强制短超时(1.5s),测试不会等满 15s
        val args = makeArgs(
            "url" to JsonPrimitive(mockServer.url("/hang").toString()),
            "timeout" to JsonPrimitive(1500),
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Error, "Expected error but got: $result")
        val msg = (result as ToolResult.Error).message
        assertTrue(
            msg.contains("timed out", ignoreCase = true) || msg.contains("timeout", ignoreCase = true),
            "应明确提到超时,实际: $msg",
        )
        // v2.2:错误消息应包含 URL 便于排查
        assertTrue(msg.contains(mockServer.url("/hang").host), "错误应包含 URL host,实际: $msg")
    }

    /**
     * 重定向循环 — 目标服务器反复重定向同一个 URL。
     * OkHttp 默认 maxRedirects=20,超过抛 ProtocolException("Too many follow-up requests: 21")。
     * v2.2:catch 住并归类为"重定向循环",而不是裸露的 ProtocolException。
     */
    @Test
    fun `http_request should detect redirect loop and return clear error`() = runBlocking {
        // 队列 25 个 302,全部指回自己,确保 OkHttp 触发 maxRedirects 异常
        repeat(25) {
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", mockServer.url("/loop").toString()),
            )
        }

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val args = makeArgs("url" to JsonPrimitive(mockServer.url("/loop").toString()))
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Error, "Expected error but got: $result")
        val msg = (result as ToolResult.Error).message
        assertTrue(
            msg.contains("重定向", ignoreCase = true) || msg.contains(
                "redirect",
                ignoreCase = true
            ) || msg.contains("follow", ignoreCase = true),
            "应明确提到重定向循环,实际: $msg",
        )
        // 错误消息应包含 URL
        assertTrue(msg.contains(mockServer.url("/loop").host), "错误应包含 URL host,实际: $msg")
    }

    /**
     * 连接失败 — 目标主机拒绝连接(端口未监听 / 防火墙拒绝)。
     * 用 127.0.0.1 的随机端口模拟,关闭 SSRF 防护确保不被提前拦截。
     * v2.2:catch ConnectException,告诉用户"目标拒绝连接"。
     */
    @Test
    fun `http_request should return clear error on connect refused`() = runBlocking {
        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        // 选一个肯定不会监听的端口 — 1 通常保留,马上 connection refused
        val args = makeArgs(
            "url" to JsonPrimitive("http://127.0.0.1:1/should-fail"),
            "timeout" to JsonPrimitive(2000),
        )
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Error, "Expected error but got: $result")
        val msg = (result as ToolResult.Error).message
        // 我们的新 catch 会归类为"连接失败"或"超时"等(具体看 OS 返回哪种异常)
        assertTrue(
            msg.contains("连接失败", ignoreCase = true) ||
                    msg.contains("拒绝", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true) ||
                    msg.contains("connect", ignoreCase = true) ||
                    msg.contains("不可达", ignoreCase = true),
            "应明确说明连接失败,实际: $msg",
        )
    }

    /**
     * v2.2:HTTP 客户端默认配置应开启 followRedirects / followSslRedirects。
     * (OkHttp 默认就是 true,这里是回归测试,免得以后有人误关。)
     */
    @Test
    fun `http_request should follow 3xx redirects automatically`() = runBlocking {
        // 先 302 到 /target,再 200
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", mockServer.url("/target").toString()),
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("redirected!"),
        )

        val tools = ExtendedTools(project = null)
        tools.ssrfProtectionEnabled = false
        val args = makeArgs("url" to JsonPrimitive(mockServer.url("/start").toString()))
        val result = tools.httpRequest(args)

        assertTrue(result is ToolResult.Success, "Expected success after redirect, got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(200, data["status_code"]?.jsonPrimitive?.int)
        assertEquals("redirected!", data["body"]?.jsonPrimitive?.content)
    }

    // ===== Data Processing Tools =====

    @Test
    fun `parse_json should parse and query JSON`() {
        val tools = ExtendedTools(project = null)
        val jsonString = """{"user":{"name":"Alice","age":30},"tags":["dev","ops"]}"""
        val args = makeArgs(
            "json" to JsonPrimitive(jsonString),
            "query" to JsonPrimitive("user.name")
        )
        val result = tools.parseJson(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("\"Alice\"", data["result"].toString())
        assertEquals("string", data["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse_json should support array index query`() {
        val tools = ExtendedTools(project = null)
        val jsonString = """{"items":["a","b","c"]}"""
        val args = makeArgs(
            "json" to JsonPrimitive(jsonString),
            "query" to JsonPrimitive("items.1")
        )
        val result = tools.parseJson(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("\"b\"", data["result"].toString())
    }

    @Test
    fun `encode_base64 and decode_base64 should work`() {
        val tools = ExtendedTools(project = null)
        val original = "Hello, 世界!"

        val encoded = tools.encodeBase64(makeArgs("input" to JsonPrimitive(original)))
        assertTrue(encoded is ToolResult.Success)
        val encodedValue = (encoded as ToolResult.Success).data.jsonObject["encoded"]?.jsonPrimitive?.content!!

        val decoded = tools.decodeBase64(makeArgs("input" to JsonPrimitive(encodedValue)))
        assertTrue(decoded is ToolResult.Success)
        val decodedValue = (decoded as ToolResult.Success).data.jsonObject["decoded"]?.jsonPrimitive?.content!!

        assertEquals(original, decodedValue)
    }

    @Test
    fun `format_json should prettify JSON`() {
        val tools = ExtendedTools(project = null)
        val compactJson = """{"a":1,"b":{"c":2}}"""
        val args = makeArgs("json" to JsonPrimitive(compactJson))
        val result = tools.formatJson(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val formatted = (result as ToolResult.Success).data.jsonObject["formatted"]?.jsonPrimitive?.content!!
        assertTrue(formatted.contains("\n"), "Formatted JSON should contain newlines")
        assertTrue(formatted.contains("  "), "Formatted JSON should contain indentation")
    }

    @Test
    fun `format_json should compact when requested`() {
        val tools = ExtendedTools(project = null)
        val prettyJson = """{"a":1}"""
        val args = makeArgs(
            "json" to JsonPrimitive(prettyJson),
            "compact" to JsonPrimitive(true)
        )
        val result = tools.formatJson(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val formatted = (result as ToolResult.Success).data.jsonObject["formatted"]?.jsonPrimitive?.content!!
        assertFalse(formatted.contains("\n  "), "Compact JSON should not contain indentation newlines")
    }

    @Test
    fun `hash_md5 should produce correct hash`() {
        val tools = ExtendedTools(project = null)
        val args = makeArgs("input" to JsonPrimitive("test"))
        val result = tools.hashMd5(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val hash = (result as ToolResult.Success).data.jsonObject["hash"]?.jsonPrimitive?.content!!
        assertEquals("098f6bcd4621d373cade4e832627b4f6", hash)
    }

    @Test
    fun `hash_sha256 should produce correct hash`() {
        val tools = ExtendedTools(project = null)
        val args = makeArgs("input" to JsonPrimitive("test"))
        val result = tools.hashSha256(args)

        assertTrue(result is ToolResult.Success, "Expected success but got: $result")
        val hash = (result as ToolResult.Success).data.jsonObject["hash"]?.jsonPrimitive?.content!!
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", hash)
    }

    // ===== ToolRegistry Integration =====

    @Test
    fun `all new tools should be registered in ToolRegistry`() {
        val registry = ToolRegistry.createDefault()
        val expectedTools = listOf(
            "git_status", "git_diff", "git_log", "git_branch",
            "exec_shell", "http_request",
            "parse_json", "encode_base64", "decode_base64",
            "format_json", "hash_md5", "hash_sha256"
        )

        for (toolName in expectedTools) {
            assertNotNull(registry.get(toolName), "Tool '$toolName' should be registered")
        }
    }
}
