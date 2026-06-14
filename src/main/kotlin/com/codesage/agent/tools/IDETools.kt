package com.codesage.agent.tools

import com.codesage.shared.security.CommandSandbox
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
import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
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
    // Phase 3: OS 级命令沙箱。ToolRegistry 注入真实沙箱；null 时回退到旧版
    // ProcessBuilder（保持测试和直接实例化的兼容性）。
    private val commandSandbox: CommandSandbox? = null,
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
     *
     * P0 优化 6.1.1：支持可选行号输出。当 `line_numbers=true` 时，
     * 额外返回 `content_with_line_numbers`，格式与 `cat -n` 一致
     *（行号右对齐 + tab + 内容），便于模型直接引用真实行号。
     */
    fun readFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")

        val resolvedPath = resolvePath(path)
        val offset = args["offset"]?.jsonPrimitive?.intOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull
        val lineNumbersRequested = args["line_numbers"]?.jsonPrimitive?.booleanOrNull ?: false

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

                val (content, contentStartLine) =
                    if (virtualFile.length > LARGE_FILE_THRESHOLD && offset == null && limit == null) {
                        // 大文件使用 memory-mapped 读取(内部已截断到 CHUNK_LINES)
                        responseFields["total_lines"] = JsonPrimitive(countLines(virtualFile))
                        readLargeFile(virtualFile) to 0
                    } else if (virtualFile.length > LARGE_FILE_THRESHOLD && (offset != null || limit != null)) {
                        // P0 优化 6.1.2：带 offset/limit 的大文件走流式分块，避免全量加载
                        val chunk = readFileChunk(virtualFile, offset ?: 0, limit ?: Int.MAX_VALUE)
                        if (offset != null && offset > chunk.totalLines) {
                            return@Computable ToolResult.Error(pagedErrorMessage(offset, chunk.totalLines))
                        }
                        responseFields["total_lines"] = JsonPrimitive(chunk.totalLines)
                        responseFields["start_line"] = JsonPrimitive(chunk.startLine)
                        responseFields["end_line"] = JsonPrimitive(chunk.endLine)
                        chunk.content to chunk.startLine
                    } else {
                        val raw = String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                        val paged = computePagedContent(raw, offset, limit)
                        if (paged == null) {
                            // offset 越界 → 明确错误,引导 LLM 自我纠错
                            return@Computable ToolResult.Error(pagedErrorMessage(offset!!, raw))
                        }
                        responseFields["total_lines"] = JsonPrimitive(paged.totalLines)
                        if (paged.startLine != null) responseFields["start_line"] = JsonPrimitive(paged.startLine)
                        if (paged.endLine != null) responseFields["end_line"] = JsonPrimitive(paged.endLine)
                        paged.content to (paged.startLine ?: 0)
                    }

                // 与 readMultipleFiles 对齐: 全文读取路径用 MAX_CONTENT_LENGTH
                // 截断,避免 10MB 源文件一次性塞给 LLM。offset/limit 分页时 LLM
                // 自己控制大小,不再二次截断; 大文件路径内部已截断到 CHUNK_LINES。
                val isPaged = offset != null || limit != null
                val (finalContent, wasTruncated) = if (isPaged || virtualFile.length > LARGE_FILE_THRESHOLD) {
                    content to false
                } else {
                    safeTruncate(content, MAX_CONTENT_LENGTH)
                }
                responseFields["content"] = JsonPrimitive(finalContent)
                if (lineNumbersRequested) {
                    responseFields["content_with_line_numbers"] =
                        JsonPrimitive(addLineNumbers(finalContent, contentStartLine))
                }
                if (wasTruncated) {
                    responseFields["truncated"] = JsonPrimitive(true)
                    responseFields["original_length"] = JsonPrimitive(content.length)
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
     *
     * 行为: 读取前 CHUNK_LINES 行(默认 1000),剩余行不解析; 末尾追加一行
     * 截断提示,让 LLM 知道文件未读全,可以用 offset 续读。处理 UTF-8 多字节
     * 字符不会跨行撕开; 末行(可能是 EOF, 也可能因 hit CHUNK_LINES 而被砍)
     * 一定会 flush 到结果。
     */
    private fun readLargeFile(virtualFile: VirtualFile): String {
        val file = File(virtualFile.path)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            return readLargeFileFromBuffer(buffer, file.length())
        }
    }

    /**
     * 测试友好入口: 在已 map 好的 buffer 上跑分块解析逻辑。
     * 行读取 → 满 CHUNK_LINES 跳出 → flush 末行 → 追加截断提示。
     */
    internal fun readLargeFileFromBuffer(buffer: ByteBuffer, fileLength: Long): String {
        val sb = StringBuilder()
        var lineCount = 0
        var lineBuf = ByteArray(8192)
        var linePos = 0
        var hitChunkCap = false

        while (buffer.hasRemaining()) {
            if (lineCount >= CHUNK_LINES) {
                hitChunkCap = true
                break
            }
            val b = buffer.get()
            if (b == '\n'.code.toByte()) {
                sb.append(String(lineBuf, 0, linePos, StandardCharsets.UTF_8))
                sb.append('\n')
                linePos = 0
                lineCount++
            } else {
                if (linePos >= lineBuf.size) {
                    lineBuf = lineBuf.copyOf(lineBuf.size * 2)
                }
                lineBuf[linePos++] = b
            }
        }
        // flush 末行(EOF 或 hit cap 都可能有残留)
        if (linePos > 0) {
            sb.append(String(lineBuf, 0, linePos, StandardCharsets.UTF_8))
            sb.append('\n')
        }
        if (hitChunkCap) {
            sb.append("... [文件过大, 已截断到前 $CHUNK_LINES 行, 共 $fileLength 字节。请用 offset 续读] ...\n")
        } else if (buffer.position() < buffer.limit()) {
            // 兜底
            sb.append("... [文件过大, 已截断。共 $fileLength 字节] ...\n")
        }
        return sb.toString()
    }

    /**
     * P0 优化 6.1.2：流式分页读取结果。
     */
    internal data class ChunkResult(
        val content: String,
        val totalLines: Int,
        val startLine: Int,
        val endLine: Int,
    )

    /**
     * 统计 ByteBuffer 中的行数（按换行符分割，与 Kotlin String.lines() 语义一致）。
     */
    internal fun countLines(buffer: ByteBuffer): Int {
        if (!buffer.hasRemaining()) return 0
        var count = 0
        while (buffer.hasRemaining()) {
            if (buffer.get() == '\n'.code.toByte()) count++
        }
        // 与 Kotlin String.lines() 对齐：非空文件总有一行“最后一行”，
        // 无论末尾是否有换行；末尾有换行时额外产生一个空行。
        return count + 1
    }

    /**
     * 在已 map 的 buffer 上读取 [offset, offset + limit) 行，不一次性加载整个文件。
     *
     * 行号约定：offset 为 0-based 行索引；返回的 [startLine, endLine) 亦为 0-based。
     */
    internal fun readChunkFromBuffer(buffer: ByteBuffer, offset: Int, limit: Int): ChunkResult {
        val totalLines = countLines(buffer).also { buffer.rewind() }
        if (offset >= totalLines) {
            return ChunkResult("", totalLines, totalLines, totalLines)
        }

        val sb = StringBuilder()
        var lineCount = 0
        var collected = 0
        var lineBuf = ByteArray(8192)
        var linePos = 0
        val target = limit.coerceAtLeast(0)

        while (buffer.hasRemaining() && collected < target) {
            val b = buffer.get()
            if (b == '\n'.code.toByte()) {
                lineCount++
                if (lineCount > offset) {
                    sb.append(String(lineBuf, 0, linePos, StandardCharsets.UTF_8))
                    sb.append('\n')
                    collected++
                }
                linePos = 0
            } else {
                if (linePos >= lineBuf.size) {
                    lineBuf = lineBuf.copyOf(lineBuf.size * 2)
                }
                lineBuf[linePos++] = b
            }
        }

        // EOF 且末行没有换行符
        if (linePos > 0 && collected < target) {
            lineCount++
            if (lineCount > offset) {
                sb.append(String(lineBuf, 0, linePos, StandardCharsets.UTF_8))
                sb.append('\n')
                collected++
            }
        }

        val start = offset.coerceIn(0, totalLines)
        val end = (start + collected).coerceIn(start, totalLines)
        return ChunkResult(sb.toString(), totalLines, start, end)
    }

    private fun countLines(virtualFile: VirtualFile): Int {
        val file = File(virtualFile.path)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            return countLines(buffer)
        }
    }

    private fun readFileChunk(virtualFile: VirtualFile, offset: Int, limit: Int): ChunkResult {
        val file = File(virtualFile.path)
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            return readChunkFromBuffer(buffer, offset, limit)
        }
    }

    /**
     * 分页读取 raw 文本内容(offset / limit → content)。
     * 返回 null 表示 offset 越界(由调用方构造明确错误信息)。
     *
     * 行为契约:
     *  - offset == null && limit == null → 返回 raw 全文
     *  - offset == totalLines → 合法 EOF,返回空 content + start_line == end_line == totalLines
     *  - offset > totalLines → 返回 null(越界)
     *  - offset < 0 → coerce 到 0
     *  - limit 越界 → 截到 totalLines
     */
    internal data class PagedContent(
        val content: String,
        val totalLines: Int,
        val startLine: Int?,
        val endLine: Int?,
    )

    internal fun pagedErrorMessage(offset: Int, raw: String): String {
        val totalLines = raw.lines().size
        return "offset $offset out of range: file has $totalLines lines"
    }

    internal fun pagedErrorMessage(offset: Int, totalLines: Int): String {
        return "offset $offset out of range: file has $totalLines lines"
    }

    internal fun computePagedContent(raw: String, offset: Int?, limit: Int?): PagedContent? {
        val allLines = raw.lines()
        val totalLines = allLines.size
        if (offset != null && offset > totalLines) return null
        if (offset == null && limit == null) {
            return PagedContent(raw, totalLines, null, null)
        }
        val start = (offset ?: 0).coerceIn(0, totalLines)
        val end = if (limit != null) (start + limit).coerceIn(start, totalLines) else totalLines
        return PagedContent(
            content = allLines.subList(start, end).joinToString("\n"),
            totalLines = totalLines,
            startLine = start,
            endLine = end,
        )
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

    /**
     * 为文本内容添加 `cat -n` 风格的行号。
     *
     * @param content 原始文本（可能已被截断）
     * @param startLine 起始行的 0-based 索引，用于 offset/limit 分页时行号对齐
     * @return 带行号的文本，每行格式为 `<padding><line_number>\t<content>`
     */
    internal fun addLineNumbers(content: String, startLine: Int = 0): String {
        if (content.isEmpty()) return content
        val lines = content.lines()
        val totalDisplayLines = startLine + lines.size
        val width = kotlin.math.max(6, totalDisplayLines.toString().length)
        return lines.mapIndexed { index, line ->
            String.format("%${width}d\t%s", startLine + index + 1, line)
        }.joinToString("\n")
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
            while (reader.read(buf) != -1) { /* drain */
            }
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
            // 必须在 write action(含 EDT 上的 WriteIntentReadAction)中:
            //   - 创建新文件 → refreshAndFindFileByPath 会触发 VFS RefreshQueue
            //   - 读已有内容 → 需要 VFS 一致快照
            // writeVirtualFile 内部已包 WriteCommandAction,但外层这些 VFS/IO
            // 不在其范围内,EDT 调用会撞 ThreadingAssertions (T9.1 修复)。
            runWriteIntent {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }

                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
                    ?: return@runWriteIntent ToolResult.Error("Failed to locate created file: $path")

                if (append) {
                    val existing = String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    writeVirtualFile(virtualFile, existing + content)
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
            }
        } catch (e: Exception) {
            logger.error("Failed to write file: $path", e)
            ToolResult.Error("Failed to write file: ${e.message}")
        }
    }

    /**
     * 在 write action 中执行 body。统一处理:
     *   - EDT: WriteIntentReadAction.run (走 VFS 写入/刷新/读 snapshot 时需要)
     *   - 后台线程: invokeAndWait + WriteIntentReadAction.run (后台协程回 EDT 等结果)
     *
     * 适用于所有同时涉及 VFS 写、文件创建和读 snapshot 的工具路径。
     * (T9.1 修复:writeFile 在 EDT 上调用 refreshAndFindFileByPath 触发断言)
     */
    private fun <T> runWriteIntent(body: () -> T): T {
        val app = ApplicationManager.getApplication()
        val task = Computable<T> { body() }
        if (app.isDispatchThread) {
            // EDT: 平台要求 VFS 写/读 snapshot 在 WriteIntentReadAction 内。
            return WriteIntentReadAction.compute(task)
        }
        // 后台协程: 切回 EDT + ModalityState.defaultModalityState,等任务完成。
        // WriteCommandAction 不允许跨 EDT 线程启动,invokeAndWait 是统一做法。
        var result: T? = null
        var error: Throwable? = null
        app.invokeAndWait({
            try {
                result = WriteIntentReadAction.compute(task)
            } catch (t: Throwable) {
                error = t
            }
        }, ModalityState.defaultModalityState())
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
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
                    collectDirectoryEntries(
                        child,
                        entries,
                        true,
                        depth + 1,
                        maxDepth,
                        state,
                        excludeDirs,
                        includeHidden
                    )
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

        // P0 优化 6.3.1：优先尝试 ripgrep；失败或条件不满足时回退到 VFS 扫描。
        RipgrepSearch.execute(args, RipgrepSearch.Mode.Search, searchPath)?.let { return it }

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
                searchInVirtualFile(
                    root,
                    regex,
                    filePattern,
                    matches,
                    0,
                    100,
                    maxResults,
                    state,
                    excludeDirs,
                    includeHidden
                )

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
                searchInVirtualFile(
                    child,
                    regex,
                    filePattern,
                    matches,
                    depth + 1,
                    maxDepth,
                    maxResults,
                    state,
                    excludeDirs,
                    includeHidden
                )
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
     * 执行系统命令（非流式，向后兼容）。
     */
    suspend fun runCommand(args: JsonObject): ToolResult = runCommand(args, onStream = {})

    /**
     * 执行系统命令，可选流式输出。
     *
     * P0 优化 6.4.1：统一 `run_command` 与 `exec_shell` 的超时语义：
     * - 默认超时 120s，最大 600s，与 Claude Code Bash 对齐。
     *
     * P0 优化 6.4.2：支持 `run_in_background=true` 启动长期运行进程，
     * 返回 `process_id`，可通过 `read_process_output` / `kill_process` 管理。
     *
     * Phase 3: 优先使用 OS 级沙箱执行；未注入沙箱时回退到旧版 ProcessBuilder。
     * 后台命令当前不走 OS 级沙箱（沙箱不支持异步生命周期），但仍经过 ShellInjectionDetector。
     *
     * 6.4.3：当 `stream_output=true` 且 [onStream] 不为空时，同步命令会实时发射
     * [AgentStreamEvent.CommandOutputStream] 事件。沙箱与后台路径暂保持非流式。
     */
    suspend fun runCommand(
        args: JsonObject,
        onStream: suspend (AgentStreamEvent) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.Error("Missing 'command' parameter")
        val workingDir = resolveWorkingDir(args["working_dir"]?.jsonPrimitive?.content)
        val timeout = args["timeout"]?.jsonPrimitive?.longOrNull?.coerceIn(1000L, 600_000L) ?: 120_000L
        val runInBackground = args["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        val streamOutput = onStream !== {} && (args["stream_output"]?.jsonPrimitive?.booleanOrNull ?: false)

        // C6 修复：检测 shell 注入意图（Base64-eval / curl|sh / printf / 反弹 shell 等）
        val injectionReason = ShellInjectionDetector.detect(command)
        if (injectionReason != null) {
            return@withContext ToolResult.Error("Shell injection blocked: $injectionReason")
        }

        if (runInBackground) {
            val processId = BackgroundProcessManager.start(command, workingDir)
            if (streamOutput) {
                // 后台模式目前不支持 push 流式；发射一个携带 process_id 的 done 事件作为提示
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        stdout = "",
                        stderr = "",
                        processId = processId,
                        done = true
                    )
                )
            }
            return@withContext ToolResult.Success(
                JsonObject(
                    mapOf(
                        "process_id" to JsonPrimitive(processId),
                        "command" to JsonPrimitive(command),
                        "working_dir" to JsonPrimitive(workingDir),
                        "status" to JsonPrimitive("running")
                    )
                )
            )
        }

        val sandbox = commandSandbox
        if (sandbox != null) {
            // 沙箱路径暂不支持流式；保持原行为
            return@withContext runCommandWithSandbox(command, workingDir, timeout, sandbox)
        }

        if (streamOutput) {
            return@withContext runCommandLegacyStreaming(command, workingDir, timeout, onStream)
        }

        runCommandLegacy(command, workingDir, timeout)
    }

    private fun runCommandWithSandbox(
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

    private fun runCommandLegacy(
        command: String,
        workingDir: String,
        timeout: Long
    ): ToolResult {
        return runCommandBlocking(command, workingDir, timeout).toToolResult()
    }

    /**
     * 同步阻塞执行命令，返回原始输出与退出码。
     * 供非流式路径与流式路径最终汇总共用。
     */
    private fun runCommandBlocking(
        command: String,
        workingDir: String,
        timeout: Long
    ): CommandRunData {
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
                return CommandRunData.Error("Command timed out after ${timeout}ms")
            }

            val exitCode = process.exitValue()
            // L4: 区分命令完成 vs 流读取失败。null 表示读取异常，content 字段填
            // 错误占位串，避免 LLM 看到空字符串误以为进程没产生输出。
            val stdoutRead = stdoutFuture.get()
            val stderrRead = stderrFuture.get()

            CommandRunData.Success(
                exitCode = exitCode,
                stdout = stdoutRead?.content ?: "",
                stderr = stderrRead?.content ?: "",
                stdoutTruncated = stdoutRead?.truncated == true,
                stderrTruncated = stderrRead?.truncated == true,
                stdoutReadError = if (stdoutRead == null) "<stdout read failed>" else null,
                stderrReadError = if (stderrRead == null) "<stderr read failed>" else null
            )
        } catch (e: Exception) {
            logger.error("Command execution failed: $command", e)
            CommandRunData.Error("Command execution failed: ${e.message}")
        }
    }

    /**
     * 命令流式执行路径。
     *
     * 通过两个独立协程实时读取 stdout/stderr，并通过 [onStream] 发送增量事件。
     * 同时累积完整输出，用于最后返回 [ToolResult]（保持 LLM context 仍能得到完整结果）。
     */
    private suspend fun runCommandLegacyStreaming(
        command: String,
        workingDir: String,
        timeout: Long,
        onStream: suspend (AgentStreamEvent) -> Unit
    ): ToolResult = coroutineScope {
        val processBuilder = ProcessBuilder(
            if (System.getProperty("os.name").contains("Windows")) {
                listOf("cmd", "/c", command)
            } else {
                listOf("/bin/bash", "-c", command)
            }
        )
        processBuilder.directory(File(workingDir))
        processBuilder.redirectErrorStream(false)

        val process = runInterruptible(Dispatchers.IO) { processBuilder.start() }

        val stdoutChannel = Channel<String>(Channel.UNLIMITED)
        val stderrChannel = Channel<String>(Channel.UNLIMITED)

        val stdoutCollector = StringBuilder()
        val stderrCollector = StringBuilder()

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val chunk = line + "\n"
                        stdoutCollector.append(chunk)
                        stdoutChannel.trySend(chunk)
                    }
                }
            } catch (_: InterruptedException) {
                // 取消或超时，正常结束
            } catch (e: Exception) {
                logger.warn("Streaming stdout reader failed: ${e.message}")
            } finally {
                stdoutChannel.close()
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val chunk = line + "\n"
                        stderrCollector.append(chunk)
                        stderrChannel.trySend(chunk)
                    }
                }
            } catch (_: InterruptedException) {
                // 取消或超时，正常结束
            } catch (e: Exception) {
                logger.warn("Streaming stderr reader failed: ${e.message}")
            } finally {
                stderrChannel.close()
            }
        }

        // 发射器：从两个 channel 轮询，批量合并后发送 CommandOutputStream
        val emitterJob = launch {
            var stdoutClosed = false
            var stderrClosed = false
            var batchStdout = StringBuilder()
            var batchStderr = StringBuilder()

            suspend fun flushBatch() {
                if (batchStdout.isNotEmpty() || batchStderr.isNotEmpty()) {
                    onStream(
                        AgentStreamEvent.CommandOutputStream(
                            stdout = batchStdout.toString(),
                            stderr = batchStderr.toString()
                        )
                    )
                    batchStdout = StringBuilder()
                    batchStderr = StringBuilder()
                }
            }

            while (!stdoutClosed || !stderrClosed) {
                val stdoutChunk = if (!stdoutClosed) stdoutChannel.tryReceive().getOrNull() else null
                val stderrChunk = if (!stderrClosed) stderrChannel.tryReceive().getOrNull() else null

                if (stdoutChunk == null && !stdoutClosed && stdoutChannel.isClosedForReceive) {
                    stdoutClosed = true
                } else if (stdoutChunk != null) {
                    batchStdout.append(stdoutChunk)
                }

                if (stderrChunk == null && !stderrClosed && stderrChannel.isClosedForReceive) {
                    stderrClosed = true
                } else if (stderrChunk != null) {
                    batchStderr.append(stderrChunk)
                }

                // 任意一端有关闭或累积到一定量时 flush，保证实时性
                if ((stdoutClosed || stderrClosed || batchStdout.length + batchStderr.length >= 1024)) {
                    flushBatch()
                }

                if ((!stdoutClosed || !stderrClosed) && stdoutChunk == null && stderrChunk == null) {
                    delay(16)
                }
            }
            flushBatch()
        }

        try {
            val finished = runInterruptible(Dispatchers.IO) {
                process.waitFor(timeout, TimeUnit.MILLISECONDS)
            }

            stdoutJob.cancel()
            stderrJob.cancel()
            emitterJob.join()

            if (!finished) {
                runInterruptible(Dispatchers.IO) { process.destroyForcibly() }
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        stderr = "Command timed out after ${timeout}ms",
                        done = true
                    )
                )
                return@coroutineScope ToolResult.Error("Command timed out after ${timeout}ms")
            }

            val exitCode = runInterruptible(Dispatchers.IO) { process.exitValue() }

            // 截断保护：若超过上限则截断收集器，避免返回给 LLM 时过大
            val stdoutResult = safeTruncate(stdoutCollector.toString(), MAX_COMMAND_OUTPUT_CHARS).first
            val stderrResult = safeTruncate(stderrCollector.toString(), MAX_COMMAND_OUTPUT_CHARS).first
            val stdoutTruncated = stdoutCollector.length > MAX_COMMAND_OUTPUT_CHARS
            val stderrTruncated = stderrCollector.length > MAX_COMMAND_OUTPUT_CHARS

            onStream(
                AgentStreamEvent.CommandOutputStream(
                    stdout = "",
                    stderr = "",
                    exitCode = exitCode,
                    done = true
                )
            )

            return@coroutineScope ToolResult.Success(
                JsonObject(
                    buildMap {
                        put("stdout", JsonPrimitive(stdoutResult))
                        put("stderr", JsonPrimitive(stderrResult))
                        put("exit_code", JsonPrimitive(exitCode))
                        if (stdoutTruncated) put("stdout_truncated", JsonPrimitive(true))
                        if (stderrTruncated) put("stderr_truncated", JsonPrimitive(true))
                        put("max_output_chars", JsonPrimitive(MAX_COMMAND_OUTPUT_CHARS))
                        put("streamed", JsonPrimitive(true))
                    }
                )
            )
        } catch (e: Exception) {
            logger.error("Streaming command execution failed: $command", e)
            stdoutJob.cancel()
            stderrJob.cancel()
            emitterJob.cancelAndJoin()
            onStream(
                AgentStreamEvent.CommandOutputStream(
                    stderr = "Command execution failed: ${e.message}",
                    done = true
                )
            )
            return@coroutineScope ToolResult.Error("Command execution failed: ${e.message}")
        }
    }

    /**
     * 内部 API：按行/按块流式执行任意 shell 命令，供其它工具复用。
     *
     * 与 [runCommand] 的区别：本方法直接接收已解析好的 command/workingDir/timeout，
     * 不经过参数解析、沙箱选择、后台模式分支；调用方自行保证注入检测等前置检查。
     *
     * @param onStream 每产生一段 stdout/stderr 时被调用；最终也会收到 done 事件
     * @return 命令结果，包含完整 stdout/stderr/exit_code
     */
    internal suspend fun runCommandStreamingInternal(
        command: String,
        workingDir: String,
        timeout: Long,
        onStream: suspend (AgentStreamEvent) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        runCommandLegacyStreaming(command, workingDir, timeout, onStream)
    }

    /**
     * 命令执行原始结果内部表示。
     */
    private sealed class CommandRunData {
        data class Success(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val stdoutTruncated: Boolean,
            val stderrTruncated: Boolean,
            val stdoutReadError: String?,
            val stderrReadError: String?
        ) : CommandRunData()

        data class Error(val message: String) : CommandRunData()

        fun toToolResult(): ToolResult = when (this) {
            is Success -> ToolResult.Success(
                JsonObject(
                    buildMap {
                        put("stdout", JsonPrimitive(stdout))
                        put("stderr", JsonPrimitive(stderr))
                        put("exit_code", JsonPrimitive(exitCode))
                        if (stdoutTruncated) put("stdout_truncated", JsonPrimitive(true))
                        if (stderrTruncated) put("stderr_truncated", JsonPrimitive(true))
                        put("max_output_chars", JsonPrimitive(MAX_COMMAND_OUTPUT_CHARS))
                        if (stdoutReadError != null) put("stdout_read_error", JsonPrimitive(stdoutReadError))
                        if (stderrReadError != null) put("stderr_read_error", JsonPrimitive(stderrReadError))
                    }
                )
            )

            is Error -> ToolResult.Error(message)
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

        // P0 优化 6.3.1：优先尝试 ripgrep；失败或条件不满足时回退到 VFS 扫描。
        RipgrepSearch.execute(args, RipgrepSearch.Mode.Grep, searchPath)?.let { return it }

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
                grepInFile(
                    root,
                    regex,
                    filePattern,
                    matches,
                    contextLines,
                    0,
                    100,
                    maxResults,
                    state,
                    excludeDirs,
                    includeHidden
                )

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
                grepInFile(
                    child,
                    regex,
                    filePattern,
                    matches,
                    contextLines,
                    depth + 1,
                    maxDepth,
                    maxResults,
                    state,
                    excludeDirs,
                    includeHidden
                )
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
     *
     * P0 优化 6.1.1：支持 `line_numbers=true`，为每个成功读取的文件额外返回
     * `content_with_line_numbers`。
     */
    suspend fun readMultipleFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val paths = args["paths"]?.jsonArray
            ?: return@withContext ToolResult.Error("Missing 'paths' parameter")
        val lineNumbersRequested = args["line_numbers"]?.jsonPrimitive?.booleanOrNull ?: false

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
                            if (lineNumbersRequested) {
                                fields["content_with_line_numbers"] =
                                    JsonPrimitive(addLineNumbers(sliced, startLine = 0))
                            }
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
     * 基于字符串或行范围编辑文件。
     *
     * P1 6.2.3：当 `old_string` 不唯一时，若传入 `fuzzy_match=true`，
     * 会自动尝试：
     * 1. 忽略行首/行尾空白差异进行匹配。
     * 2. 用前后最多 2 行上下文去歧；若仍无法确定，返回所有候选位置。
     */
    fun editFile(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val oldString = args["old_string"]?.jsonPrimitive?.content
        val newString = args["new_string"]?.jsonPrimitive?.content
        val startLine = args["start_line"]?.jsonPrimitive?.intOrNull
        val endLine = args["end_line"]?.jsonPrimitive?.intOrNull
        val fuzzyMatch = args["fuzzy_match"]?.jsonPrimitive?.booleanOrNull ?: false

        if (oldString == null && startLine == null) {
            return ToolResult.Error("Must provide either 'old_string' or 'start_line'")
        }

        val resolvedPath = resolvePath(path)

        return try {
            val content = readFileText(resolvedPath)
                ?: return ToolResult.Error("File not found: $path")

            val newContent = if (oldString != null && newString != null) {
                when (val result = EditMatchEngine.findReplacementRegion(content, oldString, fuzzyMatch)) {
                    is EditMatchEngine.FindResult.NotFound ->
                        return ToolResult.Error("old_string not found in file")

                    is EditMatchEngine.FindResult.Ambiguous ->
                        return ToolResult.Error(EditMatchEngine.formatAmbiguousMessage(result.candidates))

                    is EditMatchEngine.FindResult.Unique ->
                        EditMatchEngine.applyReplacement(content, result.match, newString)
                }
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

            when (val writeResult = writeFileText(resolvedPath, newContent)) {
                is ToolResult.Error -> return writeResult
                is ToolResult.Success -> { /* continue */
                }
            }

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
     * P1 6.2.2：一次调用对同一文件做多个 `old_string` -> `new_string` 替换。
     *
     * 参数：
     * - path (string, required): 目标文件路径
     * - edits (array of objects, required): 每个对象包含 `old_string` 和 `new_string`
     *
     * 行为：
     * - 先校验所有 `old_string` 在文件中存在且唯一（支持 `fuzzy_match=true` 忽略空白差异并用上下文去歧）。
     * - 在内存中按顺序全部替换。
     * - 任一校验失败立即返回错误，不写入磁盘；全部成功才写回文件。
     *
     * 与 `edit_file` 的差异：
     * - `edit_file` 单点替换；`multi_edit` 批量替换，减少同一文件多位置修改时的往返。
     */
    fun multiEdit(args: JsonObject): ToolResult {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'path' parameter")
        val editsArray = args["edits"]?.jsonArray
            ?: return ToolResult.Error("Missing 'edits' parameter")
        val fuzzyMatch = args["fuzzy_match"]?.jsonPrimitive?.booleanOrNull ?: false

        if (editsArray.isEmpty()) {
            return ToolResult.Error("'edits' array is empty")
        }

        val resolvedPath = resolvePath(path)
        return try {
            // 复用 readFileText / writeFileText，它们在 Application 不可用时自动回退到 File IO，
            // 与 applyPatch 行为保持一致，避免无 IDE 上下文时 LocalFileSystem 初始化失败。
            val originalContent = readFileText(resolvedPath)
                ?: return ToolResult.Error("File not found: $path")

            // 第一阶段：校验每个 edit
            data class Edit(
                val index: Int,
                val oldString: String,
                val newString: String,
                val match: EditMatchEngine.MatchCandidate
            )

            val edits = mutableListOf<Edit>()
            for ((index, element) in editsArray.withIndex()) {
                val obj = element as? JsonObject
                    ?: return ToolResult.Error("Edit at index $index is not an object")
                val oldString = obj["old_string"]?.jsonPrimitive?.content
                    ?: return ToolResult.Error("Missing 'old_string' in edit at index $index")
                val newString = obj["new_string"]?.jsonPrimitive?.content
                    ?: return ToolResult.Error("Missing 'new_string' in edit at index $index")

                when (val result = EditMatchEngine.findReplacementRegion(originalContent, oldString, fuzzyMatch)) {
                    is EditMatchEngine.FindResult.NotFound ->
                        return ToolResult.Error("old_string not found in file at edit index $index")

                    is EditMatchEngine.FindResult.Ambiguous ->
                        return ToolResult.Error(
                            "at edit index $index: ${EditMatchEngine.formatAmbiguousMessage(result.candidates)}"
                        )

                    is EditMatchEngine.FindResult.Unique ->
                        edits.add(Edit(index, oldString, newString, result.match))
                }
            }

            // 第二阶段：顺序应用替换（避免互相影响时按提交顺序处理）
            var currentContent = originalContent
            for (edit in edits) {
                // 前一次替换可能改变后续候选的索引位置，因此每次都在当前内容中重新定位。
                val currentResult = EditMatchEngine.findReplacementRegion(
                    currentContent,
                    edit.oldString,
                    fuzzyMatch
                )
                when (currentResult) {
                    is EditMatchEngine.FindResult.NotFound ->
                        return ToolResult.Error(
                            "old_string not found at apply time at edit index ${edit.index}; " +
                                    "previous edits may have changed this region"
                        )

                    is EditMatchEngine.FindResult.Ambiguous ->
                        return ToolResult.Error(
                            "at apply time edit index ${edit.index} became ambiguous; " +
                                    "provide more context"
                        )

                    is EditMatchEngine.FindResult.Unique ->
                        currentContent = EditMatchEngine.applyReplacement(
                            currentContent,
                            currentResult.match,
                            edit.newString
                        )
                }
            }

            when (val writeResult = writeFileText(resolvedPath, currentContent)) {
                is ToolResult.Error -> return writeResult
                is ToolResult.Success -> { /* continue */
                }
            }

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "edits_applied" to JsonPrimitive(edits.size),
                        "bytes_changed" to JsonPrimitive(
                            kotlin.math.abs(originalContent.length - currentContent.length)
                        )
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("multiEdit failed: $path", e)
            ToolResult.Error("multiEdit failed: ${e.message}")
        }
    }

    /**
     * P0 优化 6.2.1：应用 Codex 风格的结构化 patch。
     *
     * 支持 Update / Add / Delete 三种文件操作，先完整解析并应用到内存，
     * 全部成功后再写回磁盘，避免半成品状态。
     *
     * 参数：
     * - patch (string, required): patch 文本
     * - allow_overwrite (boolean, optional): Add File 时若目标已存在是否覆盖，默认 false
     */
    fun applyPatch(args: JsonObject): ToolResult {
        val patch = args["patch"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'patch' parameter")
        val allowOverwrite = args["allow_overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

        val parseResult = ApplyPatchEngine.parse(patch)
        if (parseResult is ApplyPatchEngine.PatchParseResult.Error) {
            return ToolResult.Error("Patch parse error: ${parseResult.message}")
        }
        val plan = (parseResult as ApplyPatchEngine.PatchParseResult.Success).plan

        // 收集 Update 操作所需的原始内容，并校验 Add/Delete 的前置条件。
        val originals = mutableMapOf<String, String>()
        for (op in plan.operations) {
            when (op) {
                is ApplyPatchEngine.PatchOperation.UpdateFile -> {
                    val resolved = resolvePath(op.path)
                    val content = readFileText(resolved)
                        ?: return ToolResult.Error("File not found for update: ${op.path}")
                    originals[op.path] = content
                }

                is ApplyPatchEngine.PatchOperation.AddFile -> {
                    val resolved = resolvePath(op.path)
                    val file = File(resolved)
                    if (file.exists() && !allowOverwrite) {
                        return ToolResult.Error(
                            "File already exists: ${op.path} (pass allow_overwrite=true to replace)"
                        )
                    }
                    originals[op.path] = ""
                }

                is ApplyPatchEngine.PatchOperation.DeleteFile -> {
                    // 实际删除在应用成功后执行
                }
            }
        }

        val applyResult = ApplyPatchEngine.apply(plan, originals)
        if (applyResult is ApplyPatchEngine.PatchApplyResult.Error) {
            return ToolResult.Error("Patch apply error: ${applyResult.message}")
        }
        val success = applyResult as ApplyPatchEngine.PatchApplyResult.Success

        // 写回变更
        val changedFiles = mutableListOf<JsonObject>()
        for ((path, content) in success.files) {
            val resolved = resolvePath(path)
            when (val writeResult = writeFileText(resolved, content)) {
                is ToolResult.Error -> return writeResult
                is ToolResult.Success -> { /* continue */
                }
            }
            val opType = if (plan.operations.any { it.path == path && it is ApplyPatchEngine.PatchOperation.AddFile }) {
                "added"
            } else {
                "updated"
            }
            changedFiles.add(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "type" to JsonPrimitive(opType)
                    )
                )
            )
        }

        for (path in success.deletedFiles) {
            val resolved = resolvePath(path)
            when (val deleteResult = deleteFilePath(resolved)) {
                is ToolResult.Error -> return deleteResult
                is ToolResult.Success -> { /* continue */
                }
            }
            changedFiles.add(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "type" to JsonPrimitive("deleted")
                    )
                )
            )
        }

        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "changed_files" to JsonArray(changedFiles),
                    "count" to JsonPrimitive(changedFiles.size)
                )
            )
        )
    }

    private fun readFileText(resolvedPath: String): String? {
        val app = ApplicationManager.getApplication()
        return if (app != null) {
            app.runReadAction(Computable {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
                if (virtualFile != null && !virtualFile.isDirectory) {
                    String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                } else {
                    File(resolvedPath).takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
                }
            })
        } else {
            File(resolvedPath).takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)
        }
    }

    private fun writeFileText(resolvedPath: String, content: String): ToolResult {
        val app = ApplicationManager.getApplication()
        return if (app != null) {
            writeFile(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(resolvedPath),
                        "content" to JsonPrimitive(content)
                    )
                )
            )
        } else {
            AtomicFileWriter.write(File(resolvedPath), content)
            ToolResult.Success(JsonObject(mapOf("path" to JsonPrimitive(resolvedPath))))
        }
    }

    private fun deleteFilePath(resolvedPath: String): ToolResult {
        val app = ApplicationManager.getApplication()
        return if (app != null) {
            deleteFile(JsonObject(mapOf("path" to JsonPrimitive(resolvedPath))))
        } else {
            val deleted = File(resolvedPath).delete()
            if (deleted) {
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(resolvedPath),
                            "deleted" to JsonPrimitive(true)
                        )
                    )
                )
            } else {
                ToolResult.Error("Failed to delete file: $resolvedPath")
            }
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
