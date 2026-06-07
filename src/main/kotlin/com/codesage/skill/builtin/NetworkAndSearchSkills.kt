package com.codesage.skill.builtin

import com.codesage.skill.*
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 内置技能 - HTTP请求
 */
class WebRequestSkill : Skill {
    override val id = "builtin_web_request"
    override val name = "Web Request"
    override val description = "发送HTTP请求"
    override val version = "1.0.0"
    override val category = SkillCategory.NETWORK
    override val tags = setOf("http", "request", "network", "api", "rest")
    override val inputSchema = mapOf(
        "url" to mapOf("type" to "string", "description" to "请求URL"),
        "method" to mapOf(
            "type" to "string",
            "description" to "HTTP方法",
            "enum" to listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        ),
        "headers" to mapOf("type" to "object", "description" to "请求头"),
        "body" to mapOf("type" to "string", "description" to "请求体"),
        "timeout" to mapOf("type" to "integer", "description" to "超时时间(毫秒)")
    )
    override val outputSchema = mapOf(
        "statusCode" to mapOf("type" to "integer"),
        "headers" to mapOf("type" to "object"),
        "body" to mapOf("type" to "string"),
        "duration" to mapOf("type" to "integer")
    )

    private val logger = Logger.getLogger<WebRequestSkill>()
    // v2.2:走共享代理感知 client
    private val client: OkHttpClient
        get() = ProxyAwareHttpClientFactory.build()

    // Blocked URL patterns to prevent SSRF
    private val BLOCKED_URL_PATTERNS = listOf(
        Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:|/|$)", RegexOption.IGNORE_CASE),
        Regex("^https?://10\\.", RegexOption.IGNORE_CASE),
        Regex("^https?://172\\.(1[6-9]|2[0-9]|3[01])\\.", RegexOption.IGNORE_CASE),
        Regex("^https?://192\\.168\\.", RegexOption.IGNORE_CASE),
        Regex("^https?://169\\.254\\.", RegexOption.IGNORE_CASE), // Link-local
        Regex("^file://", RegexOption.IGNORE_CASE),
        Regex("^ftp://", RegexOption.IGNORE_CASE),
        Regex("^http://0\\.0\\.0\\.0", RegexOption.IGNORE_CASE),
        Regex("^http://\\[?::", RegexOption.IGNORE_CASE)
    )

    private val MAX_RESPONSE_SIZE = 5 * 1024 * 1024 // 5MB

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return CanExecuteResult(true)
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = input.getString("url")
                    ?: return@withContext SkillResult.Failure("Missing URL parameter")

                // SSRF protection
                if (isBlockedUrl(url)) {
                    return@withContext SkillResult.Failure("Access denied: URL points to internal/private network")
                }

                val method = input.getString("method")?.uppercase() ?: "GET"
                val body = input.getString("body")
                val timeout = input.getInt("timeout")?.coerceIn(1000, 60000) ?: 30000

                val headers = (input.getMap("headers") as? Map<String, String>) ?: emptyMap()

                val startTime = System.currentTimeMillis()
                val result = executeRequest(url, method, headers, body, timeout)
                val duration = System.currentTimeMillis() - startTime

                if (result.isSuccess) {
                    SkillResult.Success(
                        mapOf(
                            "statusCode" to result.statusCode,
                            "headers" to result.headers,
                            "body" to result.body,
                            "duration" to duration
                        )
                    )
                } else {
                    SkillResult.Failure("HTTP ${result.statusCode}: ${result.body}")
                }
            } catch (e: Exception) {
                logger.error("Web request failed", e)
                SkillResult.Failure("Web request failed: ${e.message}", e)
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { it.matches(url) }
    }

    private fun executeRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        timeout: Int
    ): HttpResult {
        val requestBuilder = Request.Builder().url(url)

        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        when (method) {
            "GET" -> requestBuilder.get()
            "DELETE" -> requestBuilder.delete()
            "HEAD" -> requestBuilder.head()
            "POST" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType())
                    ?: "".toRequestBody(null)
                requestBuilder.post(requestBody)
            }

            "PUT" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType())
                    ?: "".toRequestBody(null)
                requestBuilder.put(requestBody)
            }

            "PATCH" -> {
                val requestBody = body?.toRequestBody("application/json".toMediaType())
                    ?: "".toRequestBody(null)
                requestBuilder.patch(requestBody)
            }

            "OPTIONS" -> requestBuilder.method("OPTIONS", null)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body
                val bodyString = if (responseBody != null) {
                    val contentLength = responseBody.contentLength()
                    if (contentLength > MAX_RESPONSE_SIZE) {
                        "Error: Response body exceeds ${MAX_RESPONSE_SIZE} bytes limit"
                    } else {
                        responseBody.string()
                    }
                } else ""

                val responseHeaders = response.headers.toMultimap()

                HttpResult(
                    isSuccess = response.isSuccessful,
                    statusCode = response.code,
                    headers = responseHeaders,
                    body = bodyString
                )
            }
        } catch (e: Exception) {
            HttpResult(isSuccess = false, statusCode = 0, headers = emptyMap(), body = e.message ?: "Unknown error")
        }
    }

    data class HttpResult(
        val isSuccess: Boolean,
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: String
    )
}

/**
 * 内置技能 - 代码搜索
 */
class CodeSearchSkill : Skill {
    override val id = "builtin_code_search"
    override val name = "Code Search"
    override val description = "在项目中搜索代码"
    override val version = "1.0.0"
    override val category = SkillCategory.CODE_SEARCH
    override val tags = setOf("search", "code", "find", "grep", "regex")
    override val inputSchema = mapOf(
        "pattern" to mapOf("type" to "string", "description" to "搜索模式(支持正则)"),
        "filePattern" to mapOf("type" to "string", "description" to "文件匹配模式 (如: *.kt, *.java)"),
        "rootPath" to mapOf("type" to "string", "description" to "搜索根目录"),
        "caseSensitive" to mapOf("type" to "boolean", "description" to "是否区分大小写"),
        "wholeWord" to mapOf("type" to "boolean", "description" to "是否全词匹配"),
        "maxResults" to mapOf("type" to "integer", "description" to "最大结果数")
    )
    override val outputSchema = mapOf(
        "matches" to mapOf("type" to "array"),
        "totalMatches" to mapOf("type" to "integer"),
        "filesSearched" to mapOf("type" to "integer")
    )

    private val logger = Logger.getLogger<CodeSearchSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        val path = context.projectPath
        return if (path != null) {
            CanExecuteResult(true)
        } else {
            CanExecuteResult(false, "No project path available")
        }
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return withContext(Dispatchers.IO) {
            try {
                val pattern = input.getString("pattern")
                    ?: return@withContext SkillResult.Failure("Missing pattern parameter")

                val filePattern = input.getString("filePattern") ?: "*.*"
                val rootPath = input.getString("rootPath") ?: context.projectPath
                ?: return@withContext SkillResult.Failure("No root path")

                val caseSensitive = input.getBoolean("caseSensitive") ?: true
                val wholeWord = input.getBoolean("wholeWord") ?: false
                val maxResults = input.getInt("maxResults") ?: 100

                val result = searchCode(
                    pattern = pattern,
                    filePattern = filePattern,
                    rootPath = rootPath,
                    caseSensitive = caseSensitive,
                    wholeWord = wholeWord,
                    maxResults = maxResults
                )

                SkillResult.Success(
                    mapOf(
                        "matches" to result.matches,
                        "totalMatches" to result.totalMatches,
                        "filesSearched" to result.filesSearched
                    )
                )
            } catch (e: Exception) {
                logger.error("Code search failed", e)
                SkillResult.Failure("Code search failed: ${e.message}", e)
            }
        }
    }

    private fun searchCode(
        pattern: String,
        filePattern: String,
        rootPath: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        maxResults: Int
    ): SearchResult {
        val matches = mutableListOf<MatchResult>()
        var filesSearched = 0
        val root = java.io.File(rootPath)

        val regexPattern = if (wholeWord) "\\b${Regex.escape(pattern)}\\b" else Regex.escape(pattern)
        val regex = try {
            Regex(regexPattern, if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            // 如果正则解析失败，作为普通字符串搜索
            Regex(Regex.escape(pattern), if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
        }

        val fileMatcher = root.toPath().fileSystem.getPathMatcher("glob:$filePattern")

        java.nio.file.Files.walk(root.toPath())
            .filter { java.nio.file.Files.isRegularFile(it) }
            .filter { !it.toString().contains("/.") } // 跳过隐藏文件
            .filter { fileMatcher.matches(it.fileName) }
            .forEach { path ->
                filesSearched++
                try {
                    val content = String(java.nio.file.Files.readAllBytes(path))
                    regex.findAll(content).forEach { match ->
                        if (matches.size < maxResults) {
                            matches.add(
                                MatchResult(
                                    file = path.toString(),
                                line = content.substring(0, match.range.first).count { it == '\n' } + 1,
                                column = match.range.first - content.lastIndexOf('\n', match.range.first),
                                text = match.value,
                                context = extractContext(content, match.range.first, match.range.last)
                            ))
                        }
                    }
                } catch (e: Exception) {
                    // 跳过无法读取的文件
                }
            }

        return SearchResult(
            matches = matches,
            totalMatches = matches.size,
            filesSearched = filesSearched
        )
    }

    private fun extractContext(content: String, start: Int, end: Int): String {
        val lineStart = content.lastIndexOf('\n', start) + 1
        val lineEnd = content.indexOf('\n', end).takeIf { it > 0 } ?: content.length
        return content.substring(lineStart, lineEnd).trim()
    }

    data class MatchResult(
        val file: String,
        val line: Int,
        val column: Int,
        val text: String,
        val context: String
    )

    data class SearchResult(
        val matches: List<MatchResult>,
        val totalMatches: Int,
        val filesSearched: Int
    )
}
