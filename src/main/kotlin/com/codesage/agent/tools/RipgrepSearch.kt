package com.codesage.agent.tools

import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P0 优化 6.3.1：基于 ripgrep 的高速代码搜索。
 *
 * 当系统可用 `rg` 时，`grep_code` / `search_code` 优先走 ripgrep；
 * 解析其 `--json` 输出并转换为现有工具的统一结果格式；
 * 当 `rg` 不可用、返回错误、或用户显式要求自定义目录过滤时回退到 VFS 扫描。
 */
object RipgrepSearch {

    enum class Mode {
        Grep,   // 带上下文
        Search  // 不带上下文，纯匹配列表
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 尝试用 ripgrep 执行搜索。返回 null 表示应回退到旧实现。
     */
    fun execute(args: JsonObject, mode: Mode, searchPath: String): ToolResult? {
        if (!isAvailable()) return null
        if (shouldUseFallback(args)) return null

        val command = buildCommand(args, mode, searchPath) ?: return null
        val workingDir = File(searchPath).parentFile ?: File(searchPath)
        val (stdout, exitCode) = runCommand(command, workingDir) ?: return null

        // rg 退出码 1 仅表示无匹配，不算错误；退出码 2 才是错误。
        if (exitCode == 2) return null

        val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 1000)
        val contextLines = if (mode == Mode.Grep) {
            (args["context_lines"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 50)
        } else 0

        val matches = parse(stdout, searchPath, contextLines, maxResults)
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "query" to JsonPrimitive(args["query"]?.jsonPrimitive?.content ?: ""),
                    "matches" to JsonArray(matches),
                    "total" to JsonPrimitive(matches.size),
                    "truncated" to JsonPrimitive(matches.size >= maxResults),
                    "max_results" to JsonPrimitive(maxResults),
                    "partial_scan_files" to JsonPrimitive(0),
                    "engine" to JsonPrimitive("ripgrep")
                )
            )
        )
    }

    private fun isAvailable(): Boolean {
        return try {
            ProcessBuilder("rg", "--version")
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 当用户显式传入自定义过滤条件时回退到旧实现，以保持行为一致性。
     */
    private fun shouldUseFallback(args: JsonObject): Boolean {
        if (args["exclude_dirs"] != null) return true
        if (args["include_hidden"]?.jsonPrimitive?.booleanOrNull == true) return true
        return false
    }

    private fun buildCommand(args: JsonObject, mode: Mode, searchPath: String): List<String>? {
        val query = args["query"]?.jsonPrimitive?.content ?: return null
        val filePattern = args["file_pattern"]?.jsonPrimitive?.content

        val cmd = mutableListOf("rg", "--json", "-n", "--no-heading")

        if (mode == Mode.Grep) {
            val contextLines = (args["context_lines"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 50)
            if (contextLines > 0) {
                cmd.add("-C")
                cmd.add(contextLines.toString())
            }
        }

        if (filePattern != null) {
            cmd.add("-g")
            cmd.add(filePattern)
        }

        cmd.add("-e")
        cmd.add(query)
        cmd.add(searchPath)
        return cmd
    }

    private fun runCommand(command: List<String>, workingDir: File): Pair<String, Int>? {
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            stdout to process.exitValue()
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(
        stdout: String,
        searchPath: String,
        contextLines: Int,
        maxResults: Int
    ): List<JsonObject> {
        val contextMap = mutableMapOf<String, MutableMap<Int, String>>()
        val matchInfos = mutableListOf<Triple<String, Int, JsonObject>>()

        for (line in stdout.lines()) {
            if (line.isBlank()) continue
            val msg = try {
                json.parseToJsonElement(line).jsonObject
            } catch (_: Exception) {
                continue
            }

            when (msg["type"]?.jsonPrimitive?.content) {
                "match" -> {
                    val data = msg["data"]?.jsonObject ?: continue
                    val relativePath = data["path"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: continue
                    val absolutePath = resolvePath(searchPath, relativePath)
                    val lineNum = data["line_number"]?.jsonPrimitive?.int ?: continue
                    val lineText = data["lines"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                    val submatches = data["submatches"]?.jsonArray ?: JsonArray(emptyList())
                    val sub = submatches.firstOrNull()?.jsonObject
                    val matchText = sub?.get("match")?.jsonObject?.get("text")?.jsonPrimitive?.content ?: lineText
                    val start = sub?.get("start")?.jsonPrimitive?.int ?: 0

                    contextMap.getOrPut(absolutePath) { mutableMapOf() }[lineNum] = lineText
                    val matchObj = JsonObject(
                        mapOf(
                            "file" to JsonPrimitive(absolutePath),
                            "line" to JsonPrimitive(lineNum),
                            "column" to JsonPrimitive(start + 1),
                            "text" to JsonPrimitive(lineText.trim()),
                            "match" to JsonPrimitive(matchText),
                            "partial_scan" to JsonPrimitive(false)
                        )
                    )
                    matchInfos.add(Triple(absolutePath, lineNum, matchObj))
                }

                "context" -> {
                    val data = msg["data"]?.jsonObject ?: continue
                    val relativePath = data["path"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: continue
                    val absolutePath = resolvePath(searchPath, relativePath)
                    val lineNum = data["line_number"]?.jsonPrimitive?.int ?: continue
                    val text = data["lines"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                    contextMap.getOrPut(absolutePath) { mutableMapOf() }[lineNum] = text
                }
            }
        }

        val result = mutableListOf<JsonObject>()
        for ((path, lineNum, matchObj) in matchInfos) {
            val context = (lineNum - contextLines..lineNum + contextLines)
                .mapNotNull { contextMap[path]?.get(it) }
                .joinToString("\n")
            val fields = matchObj.toMutableMap().apply {
                put("context", JsonPrimitive(context))
            }
            result.add(JsonObject(fields))
            if (result.size >= maxResults) break
        }
        return result
    }

    private fun resolvePath(searchPath: String, relativePath: String): String {
        val file = File(relativePath)
        return if (file.isAbsolute) {
            file.canonicalPath
        } else {
            File(searchPath, relativePath).canonicalPath
        }
    }
}
