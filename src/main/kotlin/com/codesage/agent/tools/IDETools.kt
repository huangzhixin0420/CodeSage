package com.codesage.agent.tools

import com.codesage.shared.security.ShellInjectionDetector
import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.util.ThrowableRunnable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.TextOccurenceProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.indexing.FileBasedIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * 基于 IntelliJ Platform API 的 IDE 工具实现
 * 所有文件操作都通过 VirtualFileSystem，确保与 IDE 状态同步
 *
 * 性能优化：
 * - 大文件使用 memory-mapped I/O
 * - 自动分块读取（每块 1000 行）
 * - 并行读取多文件
 * - 增量更新检测
 */
class IDETools(
    private val project: Project?,
    // L3: 可选审计日志。ToolRegistry.createDefault() / ToolExecutor 透传，
    // 不接时为 null（向后兼容测试和未配置审计的环境）。
    private val auditLog: com.codesage.tools.guardrails.ToolAuditLog? = null,
) {
    private val logger = Logger.getLogger<IDETools>()

    companion object {
        const val LARGE_FILE_THRESHOLD = 100_000 // 100KB 视为大文件
        const val CHUNK_LINES = 1000
        const val MAX_CONTENT_LENGTH = 10_000
        // M2: 单条命令单流输出上限（字符数）。超过则截断并标 truncated。
        // 设大点是为了应对 `cat README` / `git log` 之类的常见场景；
        // 真的超大（`find /`）让 LLM 用 `| head` 自行控制。
        const val MAX_COMMAND_OUTPUT_CHARS = 1_000_000

        // M4: 搜索/结构工具默认跳过的目录名（不递归进去）。LLM 想搜这些目录
        // 里的内容必须显式传 exclude_dirs=[] 覆盖。
        val DEFAULT_EXCLUDED_DIRS = setOf(
            "node_modules", "build", ".gradle", "target", "__pycache__", ".idea"
        )
    }

    internal fun resolvePath(path: String): String {
        val base = project?.basePath
        return if (base != null && !File(path).isAbsolute) {
            File(base, path).canonicalPath
        } else {
            path
        }
    }

    internal fun resolveWorkingDir(path: String?): String {
        return when {
            path == null -> project?.basePath ?: System.getProperty("user.dir")
            File(path).isAbsolute -> path
            else -> File(project?.basePath ?: ".", path).canonicalPath
        }
    }

    /**
     * 读取文件内容
     * 优化：大文件使用 memory-mapped I/O，支持分块读取
     */
    fun readFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")

        val resolvedPath = resolvePath(path)
        val offset = args["offset"]?.jsonPrimitive?.intOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: return@Computable ToolResult.Error("File not found: $path")

                if (virtualFile.isDirectory) {
                    return@Computable ToolResult.Error("Path is a directory: $path")
                }

                val responseFields = mutableMapOf<String, JsonElement>(
                    "path" to JsonPrimitive(path),
                    "size" to JsonPrimitive(virtualFile.length),
                )

                val content: String = if (virtualFile.length > LARGE_FILE_THRESHOLD && offset == null && limit == null) {
                    // 大文件使用 memory-mapped 读取
                    readLargeFile(virtualFile)
                } else {
                    val raw = String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    val allLines = raw.lines()
                    val totalLines = allLines.size
                    responseFields["total_lines"] = JsonPrimitive(totalLines)

                    // offset 越界时显式报错（"offset 1000 out of range: file has
                    // 986 lines"），比静默返回空字符串更能引导 LLM 自我纠错。
                    // offset == totalLines 视为合法 EOF，仍走分页路径并返回空
                    // content。
                    if (offset != null && offset > totalLines) {
                        return@Computable ToolResult.Error(
                            "offset $offset out of range: file has $totalLines lines"
                        )
                    }

                    if (offset != null || limit != null) {
                        val start = (offset ?: 0).coerceIn(0, totalLines)
                        val end = if (limit != null) (start + limit).coerceIn(start, totalLines) else totalLines
                        responseFields["start_line"] = JsonPrimitive(start)
                        responseFields["end_line"] = JsonPrimitive(end)
                        allLines.subList(start, end).joinToString("\n")
                    } else {
                        raw
                    }
                }

                ToolResult.Success(JsonObject(responseFields))
            } catch (e: Exception) {
                logger.error("Failed to read file: $path", e)
                ToolResult.Error("Failed to read file: ${e.message}")
            }
        })
    }

    /**
     * 使用 memory-mapped I/O 读取大文件
     */
    private fun readLargeFile(virtualFile: VirtualFile): String {
        val file = File(virtualFile.path)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            // 只读取前 CHUNK_LINES 行，避免一次性加载超大文件
            val sb = StringBuilder()
            var lineCount = 0
            var byteBuffer = ByteArray(8192)
            var bufPos = 0

            while (buffer.hasRemaining() && lineCount < CHUNK_LINES) {
                val b = buffer.get()
                if (b == '\n'.code.toByte()) {
                    sb.append(String(byteBuffer, 0, bufPos, StandardCharsets.UTF_8))
                    sb.append('\n')
                    bufPos = 0
                    lineCount++
                } else {
                    if (bufPos >= byteBuffer.size) {
                        // 扩展缓冲区
                        byteBuffer = byteBuffer.copyOf(byteBuffer.size * 2)
                    }
                    byteBuffer[bufPos++] = b
                }
            }
            if (bufPos > 0 && lineCount < CHUNK_LINES) {
                sb.append(String(byteBuffer, 0, bufPos, StandardCharsets.UTF_8))
            }
            if (buffer.hasRemaining()) {
                sb.append("\n... [文件过大，已截断。共 ${file.length()} 字节] ...")
            }
            return sb.toString()
        }
    }

    /**
     * 安全截断字符串，避免在 surrogate pair 中间断开（C1）。
     *
     * String.take() 按 UTF-16 code unit 切，遇到 high surrogate（D800-DBFF）
     * 后面紧跟 low surrogate（DC00-DFFF）组成的字符（如 emoji、CJK 扩展区）
     * 时会产生 unpaired surrogate，导致 JSON 序列化失败或 mojibake。
     *
     * 返回 (截断后内容, 是否实际截断)。截断策略：
     * 1. 内容未超长 → 原样返回
     * 2. 切点前一个字符是 high surrogate → 回退 1 格避免留下 unpaired high surrogate
     */
    private fun safeTruncate(content: String, maxChars: Int): Pair<String, Boolean> {
        if (content.length <= maxChars) return content to false
        var end = maxChars
        if (content[end - 1].isHighSurrogate()) end--
        return content.substring(0, end) to true
    }

    private data class BoundedRead(val content: String, val truncated: Boolean)

    /**
     * M4: 解析 exclude_dirs 参数。
     *  - 不传 → 用默认集合
     *  - 传空数组 → 不过滤（用户明确要求搜所有目录）
     *  - 传非空数组 → 用用户列表覆盖默认
     */
    private fun parseExcludeDirs(args: JsonObject): Set<String> {
        val arr = args["exclude_dirs"]?.jsonArray ?: return DEFAULT_EXCLUDED_DIRS
        return arr.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.toSet()
    }

    private fun parseIncludeHidden(args: JsonObject): Boolean =
        args["include_hidden"]?.jsonPrimitive?.booleanOrNull ?: false

    /**
     * 有界读取 Reader（M2）。
     *
     * 读到 maxChars 字符就停；剩余流被排干（避免进程因 pipe 满而阻塞）。
     * 输出再过 safeTruncate 保证不在 surrogate pair 中间断开。
     */
    private fun readBounded(reader: java.io.Reader, maxChars: Int): BoundedRead {
        val sb = StringBuilder(maxChars + 1024)
        val buf = CharArray(8192)
        var hitCap = false
        while (true) {
            val n = reader.read(buf)
            if (n == -1) break
            sb.append(buf, 0, n)
            if (sb.length > maxChars) {
                hitCap = true
                break
            }
        }
        // 排干剩余输出让进程 pipe 不阻塞
        if (hitCap) {
            while (reader.read(buf) != -1) { /* drain */ }
        }
        val (final, wasSurrogate) = safeTruncate(sb.toString(), maxChars)
        return BoundedRead(final, wasSurrogate || hitCap)
    }

    /**
     * 写入文件内容
     */
    fun writeFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'content' parameter")
        val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false

        val resolvedPath = resolvePath(path)
        val file = File(resolvedPath)

        return try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }

            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
                ?: return ToolResult.Error("Failed to locate created file: $path")

            if (append) {
                val existing = ApplicationManager.getApplication().runReadAction(Computable {
                    String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                })
                val newContent = existing + content
                writeVirtualFile(virtualFile, newContent)
            } else {
                writeVirtualFile(virtualFile, content)
            }

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "bytes_written" to JsonPrimitive(content.toByteArray(StandardCharsets.UTF_8).size)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to write file: $path", e)
            ToolResult.Error("Failed to write file: ${e.message}")
        }
    }

    private fun writeVirtualFile(virtualFile: VirtualFile, content: String) {
        if (project != null) {
            val app = ApplicationManager.getApplication()
            val writeAction = Runnable {
                WriteCommandAction.writeCommandAction(project)
                    .withName("Write File")
                    .withGroupId("CodeSage")
                    .run(object : ThrowableRunnable<Throwable> {
                        override fun run() {
                            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                            if (document != null) {
                                document.setText(content)
                                FileDocumentManager.getInstance().saveDocument(document)
                            } else {
                                // 无 Document 路径：走 AtomicFileWriter 保证原子写，
                                // 再 refresh VFS 触发修改事件（绕开 VFS 内部非原子
                                // setBinaryContent 写脏文件的风险）
                                AtomicFileWriter.write(File(virtualFile.path), content)
                                virtualFile.refresh(false, false)
                            }
                        }
                    })
            }
            if (app.isDispatchThread) {
                WriteIntentReadAction.run(writeAction)
            } else {
                app.invokeAndWait({
                    WriteIntentReadAction.run(writeAction)
                }, ModalityState.defaultModalityState())
            }
        } else {
            // 无 project 路径：原子写 + VFS refresh
            AtomicFileWriter.write(File(virtualFile.path), content)
            virtualFile.refresh(false, false)
        }
    }

    /**
     * 列出目录内容
     */
    fun listDirectory(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
        val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        // H3: 暴露 max_depth 给 LLM 控制。硬上限 20 防止误传大值。
        val maxDepth = (args["max_depth"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(0, 20)
        // M4
        val excludeDirs = parseExcludeDirs(args)
        val includeHidden = parseIncludeHidden(args)

        val resolvedPath = if (path != null) resolvePath(path) else project?.basePath
            ?: return ToolResult.Error("No project path available")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val dir = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: return@Computable ToolResult.Error("Directory not found: ${path ?: resolvedPath}")

                if (!dir.isDirectory) {
                    return@Computable ToolResult.Error("Path is not a directory: ${path ?: resolvedPath}")
                }

                val entries = mutableListOf<JsonObject>()
                val state = DirectoryState()
                collectDirectoryEntries(dir, entries, recursive, 0, maxDepth, state, excludeDirs, includeHidden)

                // H3: 显式告知 LLM 是否撞到 maxDepth
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(path ?: resolvedPath),
                            "entries" to JsonArray(entries),
                            "truncated" to JsonPrimitive(state.hitMaxDepth),
                            "max_depth" to JsonPrimitive(maxDepth)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to list directory", e)
                ToolResult.Error("Failed to list directory: ${e.message}")
            }
        })
    }

    private class DirectoryState {
        // H3: 递归到 maxDepth 时还有子目录没展开 → 标记 truncated
        var hitMaxDepth: Boolean = false
    }

    private fun collectDirectoryEntries(
        dir: VirtualFile,
        entries: MutableList<JsonObject>,
        recursive: Boolean,
        depth: Int,
        maxDepth: Int,
        state: DirectoryState,
        excludeDirs: Set<String>,
        includeHidden: Boolean
    ) {
        val children = dir.children ?: return
        for (child in children) {
            // M4: 统一过滤规则
            if (!includeHidden && child.name.startsWith(".")) continue
            if (child.isDirectory && child.name in excludeDirs) continue

            val entry = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(child.name),
                    "type" to JsonPrimitive(if (child.isDirectory) "directory" else "file"),
                    "path" to JsonPrimitive(child.path)
                )
            )
            entries.add(entry)

            if (recursive && child.isDirectory) {
                if (depth < maxDepth) {
                    collectDirectoryEntries(child, entries, true, depth + 1, maxDepth, state, excludeDirs, includeHidden)
                } else {
                    // 子目录存在但 depth 到了 maxDepth，标记截断
                    state.hitMaxDepth = true
                }
            }
        }
    }

    /**
     * 搜索代码
     */
    fun searchCode(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'query' parameter")
        val filePattern = args["file_pattern"]?.jsonPrimitive?.content
        val path = args["path"]?.jsonPrimitive?.content
        // H2: 加 max_results 兜底，避免 LLM 传宽泛 query 触发 OOM
        val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 1000)
        // M4
        val excludeDirs = parseExcludeDirs(args)
        val includeHidden = parseIncludeHidden(args)

        val searchPath = if (path != null) resolvePath(path) else project?.basePath
            ?: return ToolResult.Error("No project path available")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val matches = mutableListOf<JsonObject>()
                val root = LocalFileSystem.getInstance().findFileByPath(searchPath)
                    ?: return@Computable ToolResult.Error("Search path not found: $searchPath")

                val regex = try {
                    Regex(query)
                } catch (e: Exception) {
                    Regex(Regex.escape(query))
                }

                val state = SearchState()
                searchInVirtualFile(root, regex, filePattern, matches, 0, 100, maxResults, state, excludeDirs, includeHidden)

                // H2: 透出 truncated + partial_scan_files，让 LLM 知道匹配被
                // 截在哪一种上限上（results 上限 vs 大文件前 N 行扫描）
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "query" to JsonPrimitive(query),
                            "matches" to JsonArray(matches),
                            "total" to JsonPrimitive(matches.size),
                            "truncated" to JsonPrimitive(matches.size >= maxResults),
                            "max_results" to JsonPrimitive(maxResults),
                            "partial_scan_files" to JsonPrimitive(state.partialScanFiles)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Search failed", e)
                ToolResult.Error("Search failed: ${e.message}")
            }
        })
    }

    private class SearchState {
        // H2: 记录大文件被部分扫描的文件数（仅前 CHUNK_LINES 行）
        var partialScanFiles: Int = 0
    }

    private fun searchInVirtualFile(
        file: VirtualFile,
        regex: Regex,
        filePattern: String?,
        matches: MutableList<JsonObject>,
        depth: Int,
        maxDepth: Int,
        maxResults: Int,
        state: SearchState,
        excludeDirs: Set<String>,
        includeHidden: Boolean
    ) {
        if (matches.size >= maxResults) return
        if (depth > maxDepth) return
        // M4: 统一过滤规则
        if (!includeHidden && file.name.startsWith(".")) return
        if (file.isDirectory && file.name in excludeDirs) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                searchInVirtualFile(child, regex, filePattern, matches, depth + 1, maxDepth, maxResults, state, excludeDirs, includeHidden)
            }
        } else {
            if (filePattern != null && !matchPattern(file.name, filePattern)) return

            try {
                val partialScan = file.length > LARGE_FILE_THRESHOLD
                val content = if (partialScan) {
                    // 大文件只搜索前 CHUNK_LINES 行
                    state.partialScanFiles++
                    val raw = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    raw.lines().take(CHUNK_LINES).joinToString("\n")
                } else {
                    String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                }
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
                    if (matches.size >= maxResults) return@forEachIndexed
                    regex.findAll(line).forEach { match ->
                        if (matches.size >= maxResults) return@forEach
                        matches.add(
                            JsonObject(
                                mapOf(
                                    "file" to JsonPrimitive(file.path),
                                    "line" to JsonPrimitive(index + 1),
                                    "column" to JsonPrimitive(match.range.first + 1),
                                    "text" to JsonPrimitive(match.value),
                                    "context" to JsonPrimitive(line.trim()),
                                    "partial_scan" to JsonPrimitive(partialScan)
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // 跳过二进制文件等无法读取的文件
            }
        }
    }

    private fun matchPattern(fileName: String, pattern: String): Boolean {
        val regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex()
        return regex.matches(fileName)
    }

    /**
     * 执行系统命令
     */
    suspend fun runCommand(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.Error("Missing 'command' parameter")
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        val timeout = args["timeout"]?.jsonPrimitive?.longOrNull ?: 30000L

        // C6 修复：检测 shell 注入意图（Base64-eval / curl|sh / printf / 反弹 shell 等）
        val injectionReason = ShellInjectionDetector.detect(command)
        if (injectionReason != null) {
            return@withContext ToolResult.Error("Shell injection blocked: $injectionReason")
        }

        try {
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

            // M2: 用有界读取替代 readText()，单流最多 MAX_COMMAND_OUTPUT_CHARS
            // 字符。L4: 读失败时用 null 区分（而不是让 get() 抛 ExecutionException
            // 被外层 catch 吞成"命令失败"）。
            val stdoutFuture = java.util.concurrent.CompletableFuture<BoundedRead?>()
            val stderrFuture = java.util.concurrent.CompletableFuture<BoundedRead?>()

            val stdoutThread = Thread {
                try {
                    stdoutFuture.complete(
                        readBounded(process.inputStream.bufferedReader(), MAX_COMMAND_OUTPUT_CHARS)
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to read command stdout: ${e.message}")
                    stdoutFuture.complete(null)
                }
            }
            stdoutThread.isDaemon = true
            stdoutThread.start()

            val stderrThread = Thread {
                try {
                    stderrFuture.complete(
                        readBounded(process.errorStream.bufferedReader(), MAX_COMMAND_OUTPUT_CHARS)
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to read command stderr: ${e.message}")
                    stderrFuture.complete(null)
                }
            }
            stderrThread.isDaemon = true
            stderrThread.start()

            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                return@withContext ToolResult.Error("Command timed out after ${timeout}ms")
            }

            val exitCode = process.exitValue()
            // L4: 区分命令完成 vs 流读取失败。null 表示读取异常，content 字段填
            // 错误占位串，避免 LLM 看到空字符串误以为进程没产生输出。
            val stdoutRead = stdoutFuture.get()
            val stderrRead = stderrFuture.get()
            val stdoutReadError = if (stdoutRead == null) "<stdout read failed>" else null
            val stderrReadError = if (stderrRead == null) "<stderr read failed>" else null

            ToolResult.Success(
                JsonObject(
                    buildMap {
                        put("stdout", JsonPrimitive(stdoutRead?.content ?: stdoutReadError ?: ""))
                        put("stderr", JsonPrimitive(stderrRead?.content ?: stderrReadError ?: ""))
                        put("exit_code", JsonPrimitive(exitCode))
                        // M2: 透出截断标记 + 上限
                        if (stdoutRead?.truncated == true) put("stdout_truncated", JsonPrimitive(true))
                        if (stderrRead?.truncated == true) put("stderr_truncated", JsonPrimitive(true))
                        put("max_output_chars", JsonPrimitive(MAX_COMMAND_OUTPUT_CHARS))
                        // L4: 读异常明确告知
                        if (stdoutReadError != null) put("stdout_read_error", JsonPrimitive(stdoutReadError))
                        if (stderrReadError != null) put("stderr_read_error", JsonPrimitive(stderrReadError))
                    }
                )
            )
        } catch (e: Exception) {
            logger.error("Command execution failed: $command", e)
            ToolResult.Error("Command execution failed: ${e.message}")
        }
    }

    /**
     * 获取项目结构概览
     */
    fun getProjectStructure(args: JsonObject): ToolResult {
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2
        // M4
        val excludeDirs = parseExcludeDirs(args)
        val includeHidden = parseIncludeHidden(args)

        val proj = project ?: return ToolResult.Error("No active project")
        val baseDir = proj.guessProjectDir()
            ?: return ToolResult.Error("Cannot determine project root")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val structure = collectStructure(baseDir, 0, depth, excludeDirs, includeHidden)

                // 收集模块信息
                val modules = ProjectRootManager.getInstance(proj).contentSourceRoots.map {
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(it.path),
                            "type" to JsonPrimitive("source_root")
                        )
                    )
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(proj.name),
                            "root" to JsonPrimitive(baseDir.path),
                            "structure" to structure,
                            "source_roots" to JsonArray(modules)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to get project structure", e)
                ToolResult.Error("Failed to get project structure: ${e.message}")
            }
        })
    }

    private fun collectStructure(
        file: VirtualFile,
        currentDepth: Int,
        maxDepth: Int,
        excludeDirs: Set<String>,
        includeHidden: Boolean
    ): JsonObject {
        val children = mutableListOf<JsonObject>()
        if (currentDepth < maxDepth) {
            file.children?.forEach { child ->
                // M4: 统一过滤规则
                if (!includeHidden && child.name.startsWith(".")) return@forEach
                if (child.isDirectory && child.name in excludeDirs) return@forEach
                val childObj = if (child.isDirectory) {
                    collectStructure(child, currentDepth + 1, maxDepth, excludeDirs, includeHidden)
                } else {
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(child.name),
                            "type" to JsonPrimitive("file")
                        )
                    )
                }
                children.add(childObj)
            }
        }
        return JsonObject(
            mapOf(
                "name" to JsonPrimitive(file.name),
                "type" to JsonPrimitive(if (file.isDirectory) "directory" else "file"),
                "children" to JsonArray(children)
            )
        )
    }

    /**
     * 按名称模式查找文件
     */
    fun findFile(args: JsonObject): ToolResult {
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'pattern' parameter")
        val path = args["path"]?.jsonPrimitive?.content
        // H1: 硬上限 1000，避免 LLM 误传大值导致 OOM
        val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 1000)
        // M4
        val excludeDirs = parseExcludeDirs(args)
        val includeHidden = parseIncludeHidden(args)

        val searchPath = if (path != null) resolvePath(path) else project?.basePath
            ?: return ToolResult.Error("No project path available")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val root = LocalFileSystem.getInstance().findFileByPath(searchPath)
                    ?: return@Computable ToolResult.Error("Path not found: $searchPath")

                val results = mutableListOf<JsonObject>()
                val regex = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    Regex(Regex.escape(pattern))
                }

                findFilesRecursive(root, regex, results, maxResults, excludeDirs, includeHidden)

                // H1: 把截断信息显式回传给 LLM
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "pattern" to JsonPrimitive(pattern),
                            "matches" to JsonArray(results),
                            "total" to JsonPrimitive(results.size),
                            "truncated" to JsonPrimitive(results.size >= maxResults),
                            "max_results" to JsonPrimitive(maxResults)
                        )
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error("Find file failed: ${e.message}")
            }
        })
    }

    private fun findFilesRecursive(
        file: VirtualFile,
        regex: Regex,
        results: MutableList<JsonObject>,
        maxResults: Int,
        excludeDirs: Set<String>,
        includeHidden: Boolean
    ) {
        if (results.size >= maxResults) return
        if (!includeHidden && file.name.startsWith(".")) return
        if (file.isDirectory && file.name in excludeDirs) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                findFilesRecursive(child, regex, results, maxResults, excludeDirs, includeHidden)
            }
        } else if (regex.containsMatchIn(file.name)) {
            results.add(
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(file.name),
                        "path" to JsonPrimitive(file.path)
                    )
                )
            )
        }
    }

    /**
     * Grep 风格代码搜索（增强版，支持行上下文）
     */
    fun grepCode(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'query' parameter")
        val path = args["path"]?.jsonPrimitive?.content
        val filePattern = args["file_pattern"]?.jsonPrimitive?.content
        // M1: 负数 / 极大 contextLines 都会触发 subList 越界（与 readFile
        // subList bug 同源）。硬夹到 [0, 50]。
        val contextLines = (args["context_lines"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(0, 50)
        val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 1000)
        // M4
        val excludeDirs = parseExcludeDirs(args)
        val includeHidden = parseIncludeHidden(args)

        val searchPath = if (path != null) resolvePath(path) else project?.basePath
            ?: return ToolResult.Error("No project path available")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val root = LocalFileSystem.getInstance().findFileByPath(searchPath)
                    ?: return@Computable ToolResult.Error("Path not found: $searchPath")

                val matches = mutableListOf<JsonObject>()
                val regex = try {
                    Regex(query)
                } catch (e: Exception) {
                    Regex(Regex.escape(query))
                }

                val state = SearchState()
                grepInFile(root, regex, filePattern, matches, contextLines, 0, 100, maxResults, state, excludeDirs, includeHidden)

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "query" to JsonPrimitive(query),
                            "matches" to JsonArray(matches),
                            "total" to JsonPrimitive(matches.size),
                            "truncated" to JsonPrimitive(matches.size >= maxResults),
                            "max_results" to JsonPrimitive(maxResults),
                            "partial_scan_files" to JsonPrimitive(state.partialScanFiles)
                        )
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error("Grep failed: ${e.message}")
            }
        })
    }

    private fun grepInFile(
        file: VirtualFile,
        regex: Regex,
        filePattern: String?,
        matches: MutableList<JsonObject>,
        contextLines: Int,
        depth: Int,
        maxDepth: Int,
        maxResults: Int,
        state: SearchState,
        excludeDirs: Set<String>,
        includeHidden: Boolean
    ) {
        if (matches.size >= maxResults) return
        if (depth > maxDepth) return
        // M4: 统一过滤规则
        if (!includeHidden && file.name.startsWith(".")) return
        if (file.isDirectory && file.name in excludeDirs) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                grepInFile(child, regex, filePattern, matches, contextLines, depth + 1, maxDepth, maxResults, state, excludeDirs, includeHidden)
            }
        } else {
            if (filePattern != null && !matchPattern(file.name, filePattern)) return
            try {
                val partialScan = file.length > LARGE_FILE_THRESHOLD
                val content = if (partialScan) {
                    state.partialScanFiles++
                    val raw = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    raw.lines().take(CHUNK_LINES).joinToString("\n")
                } else {
                    String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                }
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
                    if (matches.size >= maxResults) return@forEachIndexed
                    if (regex.find(line) != null) {
                        val start = (index - contextLines).coerceAtLeast(0)
                        val end = (index + contextLines + 1).coerceAtMost(lines.size)
                        val context = lines.subList(start, end).joinToString("\n")
                        matches.add(
                            JsonObject(
                                mapOf(
                                    "file" to JsonPrimitive(file.path),
                                    "line" to JsonPrimitive(index + 1),
                                    "text" to JsonPrimitive(line.trim()),
                                    "context" to JsonPrimitive(context),
                                    "partial_scan" to JsonPrimitive(partialScan)
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    /**
     * 获取文件元数据
     */
    fun getFileInfo(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val resolvedPath = resolvePath(path)

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val file = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: return@Computable ToolResult.Error("File not found: $path")

                // L1: line_count 仅对 < 1MB 的常规文件计算；大文件用 readFile
                // 的 offset/limit 自取。
                val lineCount = if (!file.isDirectory && file.length < 1_000_000) {
                    String(file.contentsToByteArray(), StandardCharsets.UTF_8).lines().size
                } else null

                // L2: ISO 8601 时间戳，与原始 long 并存（不破坏现有调用方）
                val lastModifiedIso = try {
                    java.time.Instant.ofEpochMilli(file.timeStamp).toString()
                } catch (_: Exception) {
                    null
                }

                val fields = mutableMapOf<String, JsonElement>(
                    "path" to JsonPrimitive(path),
                    "name" to JsonPrimitive(file.name),
                    "size" to JsonPrimitive(file.length),
                    "is_directory" to JsonPrimitive(file.isDirectory),
                    "extension" to JsonPrimitive(file.extension ?: ""),
                    "last_modified" to JsonPrimitive(file.timeStamp),
                    // L1
                    "is_readable" to JsonPrimitive(File(file.path).canRead()),
                    "is_writable" to JsonPrimitive(file.isWritable),
                )
                if (lineCount != null) fields["line_count"] = JsonPrimitive(lineCount)
                if (lastModifiedIso != null) fields["last_modified_iso"] = JsonPrimitive(lastModifiedIso)

                ToolResult.Success(JsonObject(fields))
            } catch (e: Exception) {
                ToolResult.Error("Failed to get file info: ${e.message}")
            }
        })
    }

    /**
     * 批量读取多个文件（优化：并行读取）
     */
    suspend fun readMultipleFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val paths = args["paths"]?.jsonArray
            ?: return@withContext ToolResult.Error("Missing 'paths' parameter")

        val results = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()

        // 并行读取（所有VFS访问必须在ReadAction中执行）
        val deferreds = paths.map { element ->
            async {
                val path = element.jsonPrimitive.content
                val resolvedPath = resolvePath(path)
                try {
                    ApplicationManager.getApplication().runReadAction(Computable {
                        val file = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                        if (file != null && !file.isDirectory) {
                            val content = if (file.length > LARGE_FILE_THRESHOLD) {
                                // 大文件使用分块读取
                                readLargeFile(file)
                            } else {
                                String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                            }
                            val (sliced, wasTruncated) = safeTruncate(content, MAX_CONTENT_LENGTH)
                            val fields = mutableMapOf<String, JsonElement>(
                                "path" to JsonPrimitive(path),
                                "content" to JsonPrimitive(sliced),
                                "success" to JsonPrimitive(true),
                            )
                            // H4: 让 LLM 知道哪些文件被截断，而不是读到不完整
                            // content 却以为读全了。original_length 帮助 LLM
                            // 决定是否需要换 readFile + offset 续读。
                            if (wasTruncated) {
                                fields["truncated"] = JsonPrimitive(true)
                                fields["original_length"] = JsonPrimitive(content.length)
                            }
                            JsonObject(fields)
                        } else {
                            errors.add("File not found or is directory: $path")
                            null
                        }
                    })
                } catch (e: Exception) {
                    errors.add("Error reading $path: ${e.message}")
                    null
                }
            }
        }

        deferreds.awaitAll().filterNotNull().forEach { results.add(it) }

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "files" to JsonArray(results),
                    "errors" to JsonArray(errors.map { JsonPrimitive(it) }),
                    "total" to JsonPrimitive(results.size)
                )
            )
        )
    }

    /**
     * 基于行范围精确编辑文件
     */
    fun editFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val oldString = args["old_string"]?.jsonPrimitive?.content
        val newString = args["new_string"]?.jsonPrimitive?.content
        val startLine = args["start_line"]?.jsonPrimitive?.intOrNull
        val endLine = args["end_line"]?.jsonPrimitive?.intOrNull

        if (oldString == null && startLine == null) {
            return ToolResult.Error("Must provide either 'old_string' or 'start_line'")
        }

        val resolvedPath = resolvePath(path)

        return try {
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
                ?: return ToolResult.Error("File not found: $path")

            val content = ApplicationManager.getApplication().runReadAction(Computable {
                String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
            })
            val newContent = if (oldString != null && newString != null) {
                if (!content.contains(oldString)) {
                    return ToolResult.Error("old_string not found in file")
                }
                // C2: old_string 多次出现时只替第一处会误导 LLM。强制要求
                // 唯一匹配，提示用户提供更多上下文。
                val occurrences = Regex.escape(oldString).toRegex().findAll(content).count()
                if (occurrences > 1) {
                    return ToolResult.Error(
                        "old_string appears $occurrences times in file; " +
                            "provide more surrounding context to make it unique"
                    )
                }
                content.replaceFirst(oldString, newString)
            } else if (startLine != null && endLine != null && newString != null) {
                // C5: 与 readFile 对齐——startLine/endLine 越界时显式报错，
                // 避免静默 clamp 把 'endLine=9999' 当成 '删到末尾'。
                if (startLine < 1 || endLine < startLine) {
                    return ToolResult.Error(
                        "Invalid line range: $startLine..$endLine " +
                            "(start must be >= 1, end must be >= start)"
                    )
                }
                val lines = content.lines()
                if (endLine > lines.size) {
                    return ToolResult.Error(
                        "end_line $endLine out of range: file has ${lines.size} lines"
                    )
                }
                val s = startLine - 1
                val e = endLine
                val mutable = lines.toMutableList()
                repeat(e - s) { mutable.removeAt(s) }
                mutable.addAll(s, newString.lines())
                mutable.joinToString("\n")
            } else {
                return ToolResult.Error("Invalid edit parameters")
            }

            writeVirtualFile(virtualFile, newContent)

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "bytes_changed" to JsonPrimitive(kotlin.math.abs(content.length - newContent.length))
                    )
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Edit failed: ${e.message}")
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        // C4: 默认拒绝删除目录——LLM 传错一个路径就递归清盘风险太高。
        // 必须显式 recursive=true 才放行。
        val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val resolvedPath = resolvePath(path)
        val deleteStart = System.currentTimeMillis()

        return try {
            val file = File(resolvedPath)
            if (!file.exists()) {
                return ToolResult.Error("File not found: $path")
            }

            if (file.isDirectory && !recursive) {
                return ToolResult.Error(
                    "Refusing to delete directory: $path. " +
                        "Pass recursive=true to confirm deletion of directory and all contents."
                )
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (project != null && virtualFile != null) {
                val app = ApplicationManager.getApplication()
                val deleteAction = Runnable {
                    WriteCommandAction.writeCommandAction(project)
                        .withName("Delete File")
                        .withGroupId("CodeSage")
                        .run(object : ThrowableRunnable<Throwable> {
                            override fun run() {
                                virtualFile.delete(this)
                            }
                        })
                }
                if (app.isDispatchThread) {
                    WriteIntentReadAction.run(deleteAction)
                } else {
                    app.invokeAndWait({
                        WriteIntentReadAction.run(deleteAction)
                    }, ModalityState.defaultModalityState())
                }
            } else {
                file.deleteRecursively()
            }

            // L3: 审计破坏性操作成功
            auditLog?.log(
                toolName = "delete_file",
                arguments = mapOf("path" to path, "recursive" to recursive),
                resultStatus = "success",
                durationMs = System.currentTimeMillis() - deleteStart
            )
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "deleted" to JsonPrimitive(true),
                        "recursive" to JsonPrimitive(recursive)
                    )
                )
            )
        } catch (e: Exception) {
            // L3: 失败也记
            auditLog?.log(
                toolName = "delete_file",
                arguments = mapOf("path" to path, "recursive" to recursive),
                resultStatus = "error",
                durationMs = System.currentTimeMillis() - deleteStart
            )
            ToolResult.Error("Delete failed: ${e.message}")
        }
    }

    /**
     * 复制文件
     */
    fun copyFile(args: JsonObject): ToolResult {
        val source = args["source"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'source' parameter")
        val destination = args["destination"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'destination' parameter")

        val srcPath = resolvePath(source)
        val dstPath = resolvePath(destination)
        val copyStart = System.currentTimeMillis()

        return try {
            val srcFile = File(srcPath)
            if (!srcFile.exists()) {
                return ToolResult.Error("Source file not found: $source")
            }

            val dstFile = File(dstPath)
            dstFile.parentFile?.mkdirs()

            // M3: 区分文件和目录——copyTo 对目录抛 IOException
            val entryType = if (srcFile.isDirectory) "directory" else "file"
            if (srcFile.isDirectory) {
                if (dstFile.exists() && !dstFile.isDirectory) {
                    return ToolResult.Error("Destination exists and is not a directory: $destination")
                }
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
            }

            LocalFileSystem.getInstance().refreshAndFindFileByPath(dstPath)

            // L3: 审计
            auditLog?.log(
                toolName = "copy_file",
                arguments = mapOf("source" to source, "destination" to destination, "type" to entryType),
                resultStatus = "success",
                durationMs = System.currentTimeMillis() - copyStart
            )
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "copied" to JsonPrimitive(true),
                        "type" to JsonPrimitive(entryType)
                    )
                )
            )
        } catch (e: Exception) {
            auditLog?.log(
                toolName = "copy_file",
                arguments = mapOf("source" to source, "destination" to destination),
                resultStatus = "error",
                durationMs = System.currentTimeMillis() - copyStart
            )
            ToolResult.Error("Copy failed: ${e.message}")
        }
    }

    /**
     * 移动文件
     */
    fun moveFile(args: JsonObject): ToolResult {
        val source = args["source"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'source' parameter")
        val destination = args["destination"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'destination' parameter")

        val srcPath = resolvePath(source)
        val dstPath = resolvePath(destination)
        val moveStart = System.currentTimeMillis()

        return try {
            val srcFile = File(srcPath)
            if (!srcFile.exists()) {
                return ToolResult.Error("Source file not found: $source")
            }

            val dstFile = File(dstPath)
            dstFile.parentFile?.mkdirs()

            // C3: renameTo 在跨文件系统时返回 false 而不抛异常（典型场景：
            // /tmp → 项目目录）。原代码不检查返回值直接 success，LLM 以为
            // 移动完成。降级到 copy + delete，并报告降级方式。
            val renameSucceeded = srcFile.renameTo(dstFile)
            val method: String
            if (renameSucceeded) {
                method = "rename"
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
                if (!srcFile.delete()) {
                    return ToolResult.Error(
                        "Cross-device move partially failed: copied to $destination " +
                            "but failed to delete source $source. Source still exists."
                    )
                }
                method = "copy_and_delete"
            }

            LocalFileSystem.getInstance().refreshAndFindFileByPath(dstPath)

            // L3: 审计
            auditLog?.log(
                toolName = "move_file",
                arguments = mapOf("source" to source, "destination" to destination, "method" to method),
                resultStatus = "success",
                durationMs = System.currentTimeMillis() - moveStart
            )
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "moved" to JsonPrimitive(true),
                        "method" to JsonPrimitive(method)
                    )
                )
            )
        } catch (e: Exception) {
            auditLog?.log(
                toolName = "move_file",
                arguments = mapOf("source" to source, "destination" to destination),
                resultStatus = "error",
                durationMs = System.currentTimeMillis() - moveStart
            )
            ToolResult.Error("Move failed: ${e.message}")
        }
    }
}
