package com.codesage.agent.tools

import com.codesage.shared.security.CommandSandbox
import com.codesage.shared.security.ShellInjectionDetector
import com.codesage.shared.security.SsrfGuard
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 扩展工具集 - Git / Shell / HTTP / 数据处理
 */
class ExtendedTools(
    private val project: Project?,
    // Phase 3: OS 级命令沙箱。ToolRegistry 注入真实沙箱；null 时回退到旧版行为。
    private val commandSandbox: CommandSandbox? = null,
) {
    private val logger = Logger.getLogger<ExtendedTools>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    companion object {
        // Phase 3: 与 IDETools 对齐的单条命令输出上限
        const val MAX_COMMAND_OUTPUT_CHARS = 1_000_000
    }

    /**
     * SSRF 防护开关，默认为 true。
     * 仅用于测试环境绕过本地地址拦截。
     */
    var ssrfProtectionEnabled: Boolean = true

    internal fun resolveWorkingDir(path: String?): String {
        return when {
            path == null -> project?.basePath ?: System.getProperty("user.dir")
            File(path).isAbsolute -> path
            else -> File(project?.basePath ?: ".", path).canonicalPath
        }
    }

    // === Git Tools ===

    fun gitStatus(args: JsonObject): ToolResult {
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        return executeGitCommand(listOf("git", "status", "--porcelain", "-b"), workingDir) { stdout, _, exitCode ->
            if (exitCode != 0) return@executeGitCommand ToolResult.Error("git status failed")

            val lines = stdout.lines().filter { it.isNotBlank() }
            val branchLine = lines.firstOrNull { it.startsWith("##") }?.substring(2)?.trim() ?: "unknown"
            val files = lines.filterNot { it.startsWith("##") }.map { line ->
                val status = line.take(2)
                val file = line.drop(3)
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive(status.trim()),
                        "file" to JsonPrimitive(file)
                    )
                )
            }

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "branch" to JsonPrimitive(branchLine),
                        "changed_files" to JsonArray(files),
                        "count" to JsonPrimitive(files.size)
                    )
                )
            )
        }
    }

    fun gitDiff(args: JsonObject): ToolResult {
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        val cached = args["cached"]?.jsonPrimitive?.booleanOrNull ?: false
        val file = args["file"]?.jsonPrimitive?.content

        val cmd = mutableListOf("git", "diff")
        if (cached) cmd.add("--cached")
        if (file != null) cmd.add(file)

        return executeGitCommand(cmd, workingDir) { stdout, stderr, exitCode ->
            if (exitCode != 0 && stderr.isNotBlank()) {
                ToolResult.Error("git diff failed: $stderr")
            } else {
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "diff" to JsonPrimitive(stdout),
                            "has_changes" to JsonPrimitive(stdout.isNotBlank())
                        )
                    )
                )
            }
        }
    }

    fun gitLog(args: JsonObject): ToolResult {
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 20

        return executeGitCommand(
            listOf("git", "log", "--oneline", "-n", limit.toString()),
            workingDir
        ) { stdout, _, exitCode ->
            if (exitCode != 0) return@executeGitCommand ToolResult.Error("git log failed")

            val commits = stdout.lines().filter { it.isNotBlank() }.map { line ->
                val hash = line.takeWhile { it != ' ' }
                val message = line.drop(hash.length).trim()
                JsonObject(
                    mapOf(
                        "hash" to JsonPrimitive(hash),
                        "message" to JsonPrimitive(message)
                    )
                )
            }

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "commits" to JsonArray(commits),
                        "count" to JsonPrimitive(commits.size)
                    )
                )
            )
        }
    }

    fun gitBranch(args: JsonObject): ToolResult {
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)

        return executeGitCommand(listOf("git", "branch", "-a"), workingDir) { stdout, _, exitCode ->
            if (exitCode != 0) return@executeGitCommand ToolResult.Error("git branch failed")

            val branches = stdout.lines().filter { it.isNotBlank() }.map { line ->
                val current = line.startsWith("* ")
                val name = line.removePrefix("* ").trim()
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "current" to JsonPrimitive(current)
                    )
                )
            }

            val currentBranch = branches.firstOrNull { it["current"]?.jsonPrimitive?.booleanOrNull == true }
                ?.get("name")?.jsonPrimitive?.content ?: "unknown"

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "current" to JsonPrimitive(currentBranch),
                        "branches" to JsonArray(branches),
                        "count" to JsonPrimitive(branches.size)
                    )
                )
            )
        }
    }

    private fun executeGitCommand(
        command: List<String>,
        workingDir: String,
        transform: (String, String, Int) -> ToolResult
    ): ToolResult {
        val gitDir = File(workingDir, ".git")
        if (!gitDir.exists()) {
            return ToolResult.Error("Not a Git repository: $workingDir")
        }
        return try {
            val process = ProcessBuilder(command)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            transform(stdout, stderr, exitCode)
        } catch (e: Exception) {
            logger.error("Git command failed: $command", e)
            ToolResult.Error("Git command failed: ${e.message}")
        }
    }

    // === Shell Tool ===

    suspend fun execShell(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.Error("Missing 'command' parameter")
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        val timeout = args["timeout"]?.jsonPrimitive?.longOrNull?.coerceIn(1000L, 300000L) ?: 60000L

        // C6 修复：检测 shell 注入意图（在 ToolGuardrails 之外多一层防御）
        val injectionReason = ShellInjectionDetector.detect(command)
        if (injectionReason != null) {
            return@withContext ToolResult.Error("Shell injection blocked: $injectionReason")
        }

        // 注意：exec_shell 的安全检查已统一委托给 ToolGuardrails。
        // ExtendedTools 不再执行独立的命令拦截，避免双重标准导致的不一致用户体验。
        // 绝对危险的操作（如 rm -rf /）由 SensitiveActionPolicy 统一评估为 BLOCKED 并拒绝，
        // 高风险操作（如网络命令）由 ToolGuardrails 触发用户确认流程。

        val sandbox = commandSandbox
        if (sandbox != null) {
            return@withContext execShellWithSandbox(command, workingDir, timeout, sandbox)
        }

        execShellLegacy(command, workingDir, timeout)
    }

    private fun execShellWithSandbox(
        command: String,
        workingDir: String,
        timeout: Long,
        sandbox: CommandSandbox
    ): ToolResult {
        val result = sandbox.execute(command, File(workingDir), timeout, MAX_COMMAND_OUTPUT_CHARS)
        if (result.error != null && result.exitCode == -1) {
            return ToolResult.Error(result.error)
        }
        return ToolResult.Success(
            JsonObject(
                buildMap {
                    put("stdout", JsonPrimitive(result.stdout))
                    put("stderr", JsonPrimitive(result.stderr))
                    put("exit_code", JsonPrimitive(result.exitCode))
                    put("sandboxed", JsonPrimitive(result.sandboxed))
                }
            )
        )
    }

    private fun execShellLegacy(
        command: String,
        workingDir: String,
        timeout: Long
    ): ToolResult {
        return try {
            val processBuilder = ProcessBuilder(
                if (System.getProperty("os.name").contains("Windows")) {
                    listOf("cmd", "/c", command)
                } else {
                    listOf("/bin/bash", "-c", command)
                }
            )
            processBuilder.directory(File(workingDir))
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()

            // 异步读取 stdout/stderr，避免阻塞导致超时失效
            val stdoutFuture = java.util.concurrent.CompletableFuture<String>()
            val stderrFuture = java.util.concurrent.CompletableFuture<String>()

            val stdoutThread = Thread {
                try {
                    stdoutFuture.complete(process.inputStream.bufferedReader().readText())
                } catch (e: Exception) {
                    stdoutFuture.completeExceptionally(e)
                }
            }
            stdoutThread.isDaemon = true
            stdoutThread.start()

            val stderrThread = Thread {
                try {
                    stderrFuture.complete(process.errorStream.bufferedReader().readText())
                } catch (e: Exception) {
                    stderrFuture.completeExceptionally(e)
                }
            }
            stderrThread.isDaemon = true
            stderrThread.start()

            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                return ToolResult.Error("Command timed out after ${timeout}ms")
            }

            val exitCode = process.exitValue()
            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "stdout" to JsonPrimitive(stdout),
                        "stderr" to JsonPrimitive(stderr),
                        "exit_code" to JsonPrimitive(exitCode)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Shell execution failed: $command", e)
            ToolResult.Error("Shell execution failed: ${e.message}")
        }
    }

    // === HTTP Tool ===

    // v2.2:走共享的代理感知 OkHttpClient(ProxyAwareHttpClientFactory),
    // 行为受 settings.json 的 network.proxy 控制(系统默认 / 手动 / 直连)
    private val httpClient: OkHttpClient
        get() = ProxyAwareHttpClientFactory.build()

    // C5 修复：SSRF 防护已迁移到 [com.codesage.shared.security.SsrfGuard]。
    // 旧实现用 11 个 Regex 拼凑，可被以下绕过：十进制/八进制 IP、子域名欺骗、DNS rebinding、
    // 危险 scheme、URL 片段注入等。改用 InetAddress 解析 + 段位白名单。
    @Deprecated("已迁移到 SsrfGuard")
    private val blockedUrlPatterns: List<Regex> = emptyList()

    suspend fun httpRequest(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.Error("Missing 'url' parameter")
        val method = args["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val body = args["body"]?.jsonPrimitive?.content
        val timeout = args["timeout"]?.jsonPrimitive?.intOrNull?.coerceIn(1000, 60000) ?: 30000
        val headers = args["headers"]?.jsonObject

        // C5 修复：使用 SsrfGuard 做 DNS 解析 + 段位检查，错误消息携带具体原因
        // ssrfProtectionEnabled 关闭时跳过（保持原有测试可绕过本地地址拦截的能力）
        if (ssrfProtectionEnabled) {
            val ssrfCheck = SsrfGuard.check(url)
            if (ssrfCheck is SsrfGuard.CheckResult.Blocked) {
                return@withContext ToolResult.Error("Access denied: ${ssrfCheck.reason}")
            }
        }

        val requestBuilder = Request.Builder().url(url)

        headers?.forEach { (key, value) ->
            if (value is JsonPrimitive) {
                requestBuilder.addHeader(key, value.content)
            }
        }

        when (method) {
            "GET" -> requestBuilder.get()
            "DELETE" -> requestBuilder.delete()
            "HEAD" -> requestBuilder.head()
            "POST" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType()) ?: "".toRequestBody(null)
                requestBuilder.post(requestBody)
            }

            "PUT" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType()) ?: "".toRequestBody(null)
                requestBuilder.put(requestBody)
            }

            "PATCH" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType()) ?: "".toRequestBody(null)
                requestBuilder.patch(requestBody)
            }

            "OPTIONS" -> requestBuilder.method("OPTIONS", null)
            else -> return@withContext ToolResult.Error("Unsupported HTTP method: $method")
        }

        // 共享 client 已配 15s 超时;工具用户可传 timeout(1-60s)覆盖
        val client = httpClient.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val responseHeaders = JsonObject(response.headers.toMultimap().map { (k, v) ->
                    k to JsonPrimitive(v.joinToString(", "))
                }.toMap())

                // 自动 JSON 格式化
                val formattedBody = if (responseBody.isNotBlank() && response.header("Content-Type")
                        ?.contains("application/json") == true
                ) {
                    try {
                        json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(responseBody))
                    } catch (_: Exception) {
                        responseBody
                    }
                } else {
                    responseBody
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "status_code" to JsonPrimitive(response.code),
                            "status_text" to JsonPrimitive(response.message),
                            "headers" to responseHeaders,
                            "body" to JsonPrimitive(formattedBody),
                            "is_successful" to JsonPrimitive(response.isSuccessful)
                        )
                    )
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            // connect / read / write 三种 timeout 都会到这里
            logger.error("HTTP request timed out: $url (timeout=${timeout}ms)", e)
            ToolResult.Error(
                "HTTP request timed out (${timeout}ms): $url — " +
                        "可能是目标无响应、代理(SOCKS/HTTP)不可达、或网络被防火墙屏蔽。"
            )
        } catch (e: java.net.UnknownHostException) {
            logger.error("HTTP unknown host: $url", e)
            ToolResult.Error(
                "HTTP 域名无法解析: $url — ${e.message}。" +
                        "检查 DNS 设置,或确认目标域名拼写正确。"
            )
        } catch (e: java.net.ConnectException) {
            logger.error("HTTP connect failed: $url", e)
            ToolResult.Error(
                "HTTP 连接失败: $url — ${e.message}。" +
                        "目标主机拒绝连接(可能服务已下线、端口被防火墙拦截、或代理配置错误)。"
            )
        } catch (e: java.net.ProtocolException) {
            // OkHttp 抛 "Too many follow-up requests: N" — 重定向循环
            logger.error("HTTP redirect loop: $url (${e.message})", e)
            ToolResult.Error(
                "HTTP 重定向循环: $url — ${e.message}。" +
                        "服务器反复重定向,可能是配置错误、登录墙、或 CDN 配置异常。" +
                        "如需绕过重定向,可在请求里设 headers 携带认证 Cookie。"
            )
        } catch (e: javax.net.ssl.SSLException) {
            logger.error("HTTP SSL error: $url", e)
            ToolResult.Error(
                "HTTP SSL/TLS 错误: $url — ${e.message}。" +
                        "证书过期、不被信任、或协议版本不匹配。考虑用 HTTP 而非 HTTPS,或更新本地证书。"
            )
        } catch (e: Exception) {
            logger.error("HTTP request failed: $url", e)
            ToolResult.Error("HTTP request failed ($url): ${e.message}")
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        if (!ssrfProtectionEnabled) return false
        return !SsrfGuard.isSafe(url)
    }

    // === Data Processing Tools ===

    fun parseJson(args: JsonObject): ToolResult {
        val jsonString = args["json"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'json' parameter")
        val query = args["query"]?.jsonPrimitive?.content

        return try {
            val element = json.parseToJsonElement(jsonString)
            val result = if (query != null) {
                queryJson(element, query)
            } else {
                element
            }
            val typeName = when (result) {
                is JsonPrimitive -> when {
                    result.isString -> "string"
                    result.booleanOrNull != null -> "boolean"
                    result.intOrNull != null || result.longOrNull != null || result.doubleOrNull != null || result.floatOrNull != null -> "number"
                    else -> "primitive"
                }

                is JsonObject -> "object"
                is JsonArray -> "array"
                is JsonNull -> "null"
            }
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "result" to result,
                        "type" to JsonPrimitive(typeName)
                    )
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("JSON parse error: ${e.message}")
        }
    }

    private fun queryJson(element: JsonElement, query: String): JsonElement {
        var current = element
        val parts = query.split(".")
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part] ?: return JsonNull
                is JsonArray -> {
                    val index = part.toIntOrNull()
                    if (index != null && index in current.indices) current[index] else JsonNull
                }

                else -> return JsonNull
            }
        }
        return current
    }

    fun encodeBase64(args: JsonObject): ToolResult {
        val input = args["input"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'input' parameter")
        val encoded = Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
        return ToolResult.Success(JsonObject(mapOf("encoded" to JsonPrimitive(encoded))))
    }

    fun decodeBase64(args: JsonObject): ToolResult {
        val input = args["input"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'input' parameter")
        return try {
            val decoded = Base64.getDecoder().decode(input).toString(Charsets.UTF_8)
            ToolResult.Success(JsonObject(mapOf("decoded" to JsonPrimitive(decoded))))
        } catch (e: Exception) {
            ToolResult.Error("Base64 decode error: ${e.message}")
        }
    }

    fun formatJson(args: JsonObject): ToolResult {
        val jsonString = args["json"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'json' parameter")
        val compact = args["compact"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            val element = json.parseToJsonElement(jsonString)
            val formatter = if (compact) {
                Json { ignoreUnknownKeys = true }
            } else {
                Json { ignoreUnknownKeys = true; prettyPrint = true }
            }
            val formatted = formatter.encodeToString(JsonElement.serializer(), element)
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "formatted" to JsonPrimitive(formatted)
                    )
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("JSON format error: ${e.message}")
        }
    }

    fun hashMd5(args: JsonObject): ToolResult {
        val input = args["input"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'input' parameter")
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return ToolResult.Success(JsonObject(mapOf("hash" to JsonPrimitive(hex))))
    }

    fun hashSha256(args: JsonObject): ToolResult {
        val input = args["input"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'input' parameter")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return ToolResult.Success(JsonObject(mapOf("hash" to JsonPrimitive(hex))))
    }
}
