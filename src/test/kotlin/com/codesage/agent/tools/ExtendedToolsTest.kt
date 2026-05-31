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
    fun `git_diff should return diff output`(@TempDir tempDir: File) {
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
        assertTrue(data.containsKey("diff"))
        assertTrue(data.containsKey("has_changes"))
        assertTrue(data["has_changes"]?.jsonPrimitive?.booleanOrNull == true)
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
