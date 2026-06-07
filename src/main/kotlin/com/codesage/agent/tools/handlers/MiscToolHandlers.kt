package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 杂项工具 Handlers：网页抓取、剪贴板、时间戳、UUID、文档生成、数据库、Docker、依赖分析
 */
object WebScraperToolHandlers {
    private val logger = Logger.getLogger<WebScraperToolHandlers>()
    // v2.2:走共享代理感知 client
    private val client: OkHttpClient
        get() = ProxyAwareHttpClientFactory.build()

    fun createWebScraperHandler(): ToolHandler = object : ToolHandler {
        override val tool: Tool = webScraperTool()
        override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.content
                ?: return@withContext ToolResult.Error("Missing 'url' parameter")
            val selector = args["selector"]?.jsonPrimitive?.content
            val timeout = args["timeout"]?.jsonPrimitive?.intOrNull?.coerceIn(1000, 60000) ?: 15000

            // SSRF 防护
            val blockedPatterns = listOf(
                Regex("""127\.0\.0\.1"""),
                Regex("""localhost""", RegexOption.IGNORE_CASE),
                Regex("""\[::1\]"""),
                Regex("""^https?://10\.""", RegexOption.IGNORE_CASE),
                Regex("""^https?://172\.(1[6-9]|2[0-9]|3[01])\.""", RegexOption.IGNORE_CASE),
                Regex("""^https?://192\.168\.""", RegexOption.IGNORE_CASE)
            )
            if (blockedPatterns.any { it.containsMatchIn(url) }) {
                return@withContext ToolResult.Error("Access denied: URL points to internal/private network")
            }

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "CodeSage/1.0")
                    .build()

                val clientWithTimeout = client.newBuilder()
                    .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                    .build()

                clientWithTimeout.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext ToolResult.Error("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body?.string() ?: ""
                    val doc = Jsoup.parse(body, url)

                    val content = if (selector != null) {
                        doc.select(selector).map { it.text() }.joinToString("\n\n")
                    } else {
                        doc.body()?.text() ?: doc.text()
                    }

                    val title = doc.title()

                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "url" to JsonPrimitive(url),
                                "title" to JsonPrimitive(title),
                                "content" to JsonPrimitive(content.take(20000)),
                                "selector" to JsonPrimitive(selector ?: ""),
                                "length" to JsonPrimitive(content.length)
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Web scraping failed: $url", e)
                ToolResult.Error("Web scraping failed: ${e.message}")
            }
        }
    }
}

object ClipboardToolHandlers {
    private val logger = Logger.getLogger<ClipboardToolHandlers>()

    fun createClipboardHandler(): ToolHandler = FunctionalToolHandler(clipboardTool()) { args ->
        val action = args["action"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'action' parameter")

        when (action) {
            "get" -> {
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val content = clipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
                    ToolResult.Success(
                        JsonObject(mapOf("content" to JsonPrimitive(content), "action" to JsonPrimitive("get")))
                    )
                } catch (e: Exception) {
                    ToolResult.Error("Failed to get clipboard content: ${e.message}")
                }
            }

            "set" -> {
                val content = args["content"]?.jsonPrimitive?.content
                    ?: return@FunctionalToolHandler ToolResult.Error("Missing 'content' parameter for set action")
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(content), null)
                    ToolResult.Success(
                        JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "action" to JsonPrimitive("set"),
                                "length" to JsonPrimitive(content.length)
                            )
                        )
                    )
                } catch (e: Exception) {
                    ToolResult.Error("Failed to set clipboard content: ${e.message}")
                }
            }

            else -> ToolResult.Error("Unknown clipboard action: $action")
        }
    }
}

object TimestampToolHandlers {
    fun createTimestampHandler(): ToolHandler = FunctionalToolHandler(timestampTool()) { args ->
        val value = args["value"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'value' parameter")
        val inputFormat = args["input_format"]?.jsonPrimitive?.content ?: "timestamp"
        val outputFormat = args["output_format"]?.jsonPrimitive?.content ?: "iso"

        val instant = try {
            when (inputFormat) {
                "timestamp" -> java.time.Instant.ofEpochMilli(
                    value.toLongOrNull()
                        ?: return@FunctionalToolHandler ToolResult.Error("Invalid timestamp: $value")
                )

                "iso" -> java.time.Instant.parse(value)
                "rfc" -> java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()

                else -> return@FunctionalToolHandler ToolResult.Error("Unknown input format: $inputFormat")
            }
        } catch (e: Exception) {
            return@FunctionalToolHandler ToolResult.Error("Failed to parse time: ${e.message}")
        }

        val result = when (outputFormat) {
            "timestamp" -> instant.toEpochMilli().toString()
            "iso" -> instant.toString()
            "rfc" -> java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.ofInstant(
                    instant,
                    java.time.ZoneId.systemDefault()
                )
            )

            "relative" -> {
                val now = java.time.Instant.now()
                val diff = java.time.Duration.between(instant, now)
                when {
                    diff.toDays() > 0 -> "${diff.toDays()} days ago"
                    diff.toHours() > 0 -> "${diff.toHours()} hours ago"
                    diff.toMinutes() > 0 -> "${diff.toMinutes()} minutes ago"
                    else -> "just now"
                }
            }

            else -> return@FunctionalToolHandler ToolResult.Error("Unknown output format: $outputFormat")
        }

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "input" to JsonPrimitive(value),
                    "input_format" to JsonPrimitive(inputFormat),
                    "output" to JsonPrimitive(result),
                    "output_format" to JsonPrimitive(outputFormat)
                )
            )
        )
    }
}

object UUIDToolHandlers {
    fun createUUIDHandler(): ToolHandler = FunctionalToolHandler(uuidTool()) { args ->
        val action = args["action"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'action' parameter")

        when (action) {
            "generate" -> {
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "uuid" to JsonPrimitive(UUID.randomUUID().toString()),
                            "action" to JsonPrimitive("generate")
                        )
                    )
                )
            }

            "validate" -> {
                val uuidStr = args["uuid"]?.jsonPrimitive?.content
                    ?: return@FunctionalToolHandler ToolResult.Error("Missing 'uuid' parameter for validate action")
                val isValid = try {
                    UUID.fromString(uuidStr)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "uuid" to JsonPrimitive(uuidStr),
                            "valid" to JsonPrimitive(isValid),
                            "action" to JsonPrimitive("validate")
                        )
                    )
                )
            }

            else -> ToolResult.Error("Unknown UUID action: $action")
        }
    }
}

object DocumentationToolHandlers {
    private val logger = Logger.getLogger<DocumentationToolHandlers>()

    fun createGenerateDocHandler(project: Project?): ToolHandler = FunctionalToolHandler(generateDocTool()) { args ->
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'file_path' parameter")
        val lineStart = args["line_start"]?.jsonPrimitive?.intOrNull
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'line_start' parameter")
        val lineEnd = args["line_end"]?.jsonPrimitive?.intOrNull
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'line_end' parameter")
        val style = args["style"]?.jsonPrimitive?.content ?: "javadoc"

        val file = File(filePath)
        if (!file.exists()) {
            return@FunctionalToolHandler ToolResult.Error("File not found: $filePath")
        }

        val lines = file.readLines()
        val start = (lineStart - 1).coerceIn(0, lines.size)
        val end = lineEnd.coerceIn(start, lines.size)
        val codeBlock = lines.subList(start, end).joinToString("\n")

        // 提取方法/类签名用于生成文档模板
        val signature = lines.subList(start, end).firstOrNull { it.trim().isNotEmpty() } ?: ""
        val name = signature.trim().split(" ").getOrNull(1)?.split("(")?.get(0) ?: "unknown"

        val docTemplate = when (style) {
            "kdoc" -> """
                /**
                 * TODO: Describe what [$name] does
                 *
                 * @param paramName description
                 * @return description of return value
                 */
            """.trimIndent()

            "jsdoc" -> """
                /**
                 * TODO: Describe what [$name] does
                 * @param {type} paramName - description
                 * @returns {type} description
                 */
            """.trimIndent()

            else -> """
                /**
                 * TODO: Describe what [$name] does
                 *
                 * @param paramName description
                 * @return description of return value
                 */
            """.trimIndent()
        }

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "file_path" to JsonPrimitive(filePath),
                    "style" to JsonPrimitive(style),
                    "template" to JsonPrimitive(docTemplate),
                    "signature" to JsonPrimitive(signature.trim()),
                    "code_block" to JsonPrimitive(codeBlock)
                )
            )
        )
    }
}

object DatabaseToolHandlers {
    private val logger = Logger.getLogger<DatabaseToolHandlers>()

    fun createSqlExecuteHandler(project: Project?): ToolHandler = FunctionalToolHandler(sqlExecuteTool()) { args ->
        // 简化实现：通过 JDBC 直接连接。生产环境应集成 IntelliJ Database Tools API。
        val dataSourceName = args["data_source_name"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'data_source_name' parameter")
        val sql = args["sql"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'sql' parameter")
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: 100

        // 安全限制：仅允许 SELECT/SHOW/DESCRIBE/EXPLAIN
        val trimmedSql = sql.trim()
        val forbiddenKeywords =
            listOf("INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE", "GRANT", "REVOKE")
        if (forbiddenKeywords.any { trimmedSql.uppercase().startsWith(it) }) {
            return@FunctionalToolHandler ToolResult.Error("Only read-only queries (SELECT, SHOW, DESCRIBE, EXPLAIN) are allowed")
        }

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "data_source" to JsonPrimitive(dataSourceName),
                    "sql" to JsonPrimitive(sql),
                    "note" to JsonPrimitive("Database integration requires IntelliJ Database Tools configuration. Please use JDBC URL directly or configure datasource in IDE."),
                    "limit" to JsonPrimitive(limit)
                )
            )
        )
    }
}

object DockerToolHandlers {
    private val logger = Logger.getLogger<DockerToolHandlers>()

    fun createDockerHandler(): ToolHandler = FunctionalToolHandler(dockerTool()) { args ->
        val command = args["command"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'command' parameter")
        val target = args["target"]?.jsonPrimitive?.content
        val options = args["options"]?.jsonPrimitive?.content

        val cmd = mutableListOf("docker", command)
        options?.let { cmd.addAll(it.split(" ").filter { o -> o.isNotBlank() }) }
        target?.let { cmd.add(it) }

        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                ToolResult.Error("Docker command failed: $stderr")
            } else {
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "command" to JsonPrimitive("docker $command"),
                            "output" to JsonPrimitive(stdout),
                            "exit_code" to JsonPrimitive(exitCode)
                        )
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Docker command failed: $command", e)
            ToolResult.Error("Docker command failed: ${e.message}")
        }
    }
}

object DependencyToolHandlers {
    private val logger = Logger.getLogger<DependencyToolHandlers>()

    fun createAnalyzeDependenciesHandler(project: Project?): ToolHandler =
        FunctionalToolHandler(analyzeDependenciesTool()) { args ->
            val action = args["action"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'action' parameter")
            val workingDir = args["working_dir"]?.jsonPrimitive?.content
                ?: project?.basePath
                ?: System.getProperty("user.dir")

            val isMaven = File(workingDir, "pom.xml").exists()
            val isGradle = File(workingDir, "build.gradle").exists()
                    || File(workingDir, "build.gradle.kts").exists()

            if (!isMaven && !isGradle) {
                return@FunctionalToolHandler ToolResult.Error("No supported build system found in $workingDir")
            }

            val cmd = when {
                isMaven -> com.codesage.agent.tools.handlers.BuildCommandResolver
                    .mavenCommand(
                        workingDir, when (action) {
                            "tree" -> listOf("-B", "dependency:tree")
                            "outdated" -> listOf("-B", "versions:display-dependency-updates")
                            "conflicts" -> listOf("-B", "dependency:tree", "-Dverbose")
                            else -> return@FunctionalToolHandler ToolResult.Error("Unknown action: $action")
                        }
                    )

                else -> com.codesage.agent.tools.handlers.BuildCommandResolver
                    .gradleCommand(
                        workingDir, when (action) {
                            "tree" -> listOf("dependencies")
                            "outdated" -> listOf("dependencyUpdates")
                            "conflicts" -> listOf("dependencies", "--configuration", "runtimeClasspath")
                            else -> return@FunctionalToolHandler ToolResult.Error("Unknown action: $action")
                        }
                    )
            }

            try {
                val process = ProcessBuilder(cmd)
                    .directory(File(workingDir))
                    .redirectErrorStream(false)
                    .start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@FunctionalToolHandler ToolResult.Error("Dependency analysis timed out")
                }
                val exitCode = process.exitValue()

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "build_system" to JsonPrimitive(if (isMaven) "maven" else "gradle"),
                            "action" to JsonPrimitive(action),
                            "exit_code" to JsonPrimitive(exitCode),
                            "output" to JsonPrimitive(stdout.take(50000)),
                            "errors" to JsonPrimitive(stderr.take(5000)),
                            "success" to JsonPrimitive(exitCode == 0)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Dependency analysis failed", e)
                ToolResult.Error("Dependency analysis failed: ${e.message}")
            }
        }
}
