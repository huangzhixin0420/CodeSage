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
class IDETools(private val project: Project?) {
    private val logger = Logger.getLogger<IDETools>()

    companion object {
        const val LARGE_FILE_THRESHOLD = 100_000 // 100KB 视为大文件
        const val CHUNK_LINES = 1000
        const val MAX_CONTENT_LENGTH = 10_000
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

                val content = if (virtualFile.length > LARGE_FILE_THRESHOLD && offset == null && limit == null) {
                    // 大文件使用 memory-mapped 读取
                    readLargeFile(virtualFile)
                } else {
                    var raw = String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    if (offset != null || limit != null) {
                        val lines = raw.lines()
                        val start = offset ?: 0
                        val end = if (limit != null) (start + limit).coerceAtMost(lines.size) else lines.size
                        raw = lines.subList(start.coerceAtLeast(0), end.coerceAtMost(lines.size)).joinToString("\n")
                    }
                    raw
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(path),
                            "content" to JsonPrimitive(content),
                            "size" to JsonPrimitive(virtualFile.length)
                        )
                    )
                )
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
                collectDirectoryEntries(dir, entries, recursive, 0, 3)

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(path ?: resolvedPath),
                            "entries" to JsonArray(entries)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Failed to list directory", e)
                ToolResult.Error("Failed to list directory: ${e.message}")
            }
        })
    }

    private fun collectDirectoryEntries(
        dir: VirtualFile,
        entries: MutableList<JsonObject>,
        recursive: Boolean,
        depth: Int,
        maxDepth: Int
    ) {
        val children = dir.children ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue // 跳过隐藏文件

            val entry = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(child.name),
                    "type" to JsonPrimitive(if (child.isDirectory) "directory" else "file"),
                    "path" to JsonPrimitive(child.path)
                )
            )
            entries.add(entry)

            if (recursive && child.isDirectory && depth < maxDepth) {
                collectDirectoryEntries(child, entries, true, depth + 1, maxDepth)
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

                searchInVirtualFile(root, regex, filePattern, matches, 0, 100)

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "query" to JsonPrimitive(query),
                            "matches" to JsonArray(matches),
                            "total" to JsonPrimitive(matches.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("Search failed", e)
                ToolResult.Error("Search failed: ${e.message}")
            }
        })
    }

    private fun searchInVirtualFile(
        file: VirtualFile,
        regex: Regex,
        filePattern: String?,
        matches: MutableList<JsonObject>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        if (file.name.startsWith(".")) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                searchInVirtualFile(child, regex, filePattern, matches, depth + 1, maxDepth)
            }
        } else {
            if (filePattern != null && !matchPattern(file.name, filePattern)) return

            try {
                val content = if (file.length > LARGE_FILE_THRESHOLD) {
                    // 大文件只搜索前 CHUNK_LINES 行
                    val raw = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    raw.lines().take(CHUNK_LINES).joinToString("\n")
                } else {
                    String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                }
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
                    regex.findAll(line).forEach { match ->
                        matches.add(
                            JsonObject(
                                mapOf(
                                    "file" to JsonPrimitive(file.path),
                                    "line" to JsonPrimitive(index + 1),
                                    "column" to JsonPrimitive(match.range.first + 1),
                                    "text" to JsonPrimitive(match.value),
                                    "context" to JsonPrimitive(line.trim())
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
                return@withContext ToolResult.Error("Command timed out after ${timeout}ms")
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
            logger.error("Command execution failed: $command", e)
            ToolResult.Error("Command execution failed: ${e.message}")
        }
    }

    /**
     * 获取项目结构概览
     */
    fun getProjectStructure(args: JsonObject): ToolResult {
        val depth = args["depth"]?.jsonPrimitive?.intOrNull ?: 2

        val proj = project ?: return ToolResult.Error("No active project")
        val baseDir = proj.guessProjectDir()
            ?: return ToolResult.Error("Cannot determine project root")

        return ApplicationManager.getApplication().runReadAction(Computable {
            try {
                val structure = collectStructure(baseDir, 0, depth)

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

    private fun collectStructure(file: VirtualFile, currentDepth: Int, maxDepth: Int): JsonObject {
        val children = mutableListOf<JsonObject>()
        if (currentDepth < maxDepth) {
            file.children?.forEach { child ->
                if (child.name.startsWith(".") || child.name in setOf(
                        "node_modules",
                        "build",
                        ".gradle",
                        "target",
                        "__pycache__",
                        ".idea"
                    )
                ) {
                    return@forEach
                }
                val childObj = if (child.isDirectory) {
                    collectStructure(child, currentDepth + 1, maxDepth)
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
        val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 50

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

                findFilesRecursive(root, regex, results, maxResults)

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "pattern" to JsonPrimitive(pattern),
                            "matches" to JsonArray(results),
                            "total" to JsonPrimitive(results.size)
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
        maxResults: Int
    ) {
        if (results.size >= maxResults) return
        if (file.name.startsWith(".")) return
        if (file.name in setOf("node_modules", "build", ".gradle", "target", "__pycache__", ".idea")) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                findFilesRecursive(child, regex, results, maxResults)
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
        val contextLines = args["context_lines"]?.jsonPrimitive?.intOrNull ?: 2

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

                grepInFile(root, regex, filePattern, matches, contextLines, 0, 100)

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "query" to JsonPrimitive(query),
                            "matches" to JsonArray(matches),
                            "total" to JsonPrimitive(matches.size)
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
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        if (file.name.startsWith(".")) return
        if (file.name in setOf("node_modules", "build", ".gradle", "target", "__pycache__", ".idea")) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                grepInFile(child, regex, filePattern, matches, contextLines, depth + 1, maxDepth)
            }
        } else {
            if (filePattern != null && !matchPattern(file.name, filePattern)) return
            try {
                val content = if (file.length > LARGE_FILE_THRESHOLD) {
                    val raw = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                    raw.lines().take(CHUNK_LINES).joinToString("\n")
                } else {
                    String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                }
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
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
                                    "context" to JsonPrimitive(context)
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

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(path),
                            "name" to JsonPrimitive(file.name),
                            "size" to JsonPrimitive(file.length),
                            "is_directory" to JsonPrimitive(file.isDirectory),
                            "extension" to JsonPrimitive(file.extension ?: ""),
                            "last_modified" to JsonPrimitive(file.timeStamp)
                        )
                    )
                )
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
                            JsonObject(
                                mapOf(
                                    "path" to JsonPrimitive(path),
                                    "content" to JsonPrimitive(content.take(MAX_CONTENT_LENGTH)),
                                    "success" to JsonPrimitive(true)
                                )
                            )
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
                content.replaceFirst(oldString, newString)
            } else if (startLine != null && endLine != null && newString != null) {
                val lines = content.lines().toMutableList()
                val s = (startLine - 1).coerceAtLeast(0)
                val e = endLine.coerceAtMost(lines.size)
                repeat(e - s) { lines.removeAt(s) }
                lines.addAll(s, newString.lines())
                lines.joinToString("\n")
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
        val resolvedPath = resolvePath(path)

        return try {
            val file = File(resolvedPath)
            if (!file.exists()) {
                return ToolResult.Error("File not found: $path")
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

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(path),
                        "deleted" to JsonPrimitive(true)
                    )
                )
            )
        } catch (e: Exception) {
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

        return try {
            val srcFile = File(srcPath)
            if (!srcFile.exists()) {
                return ToolResult.Error("Source file not found: $source")
            }

            val dstFile = File(dstPath)
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)

            LocalFileSystem.getInstance().refreshAndFindFileByPath(dstPath)

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "copied" to JsonPrimitive(true)
                    )
                )
            )
        } catch (e: Exception) {
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

        return try {
            val srcFile = File(srcPath)
            if (!srcFile.exists()) {
                return ToolResult.Error("Source file not found: $source")
            }

            val dstFile = File(dstPath)
            dstFile.parentFile?.mkdirs()
            srcFile.renameTo(dstFile)

            LocalFileSystem.getInstance().refreshAndFindFileByPath(dstPath)

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "moved" to JsonPrimitive(true)
                    )
                )
            )
        } catch (e: Exception) {
            ToolResult.Error("Move failed: ${e.message}")
        }
    }
}
