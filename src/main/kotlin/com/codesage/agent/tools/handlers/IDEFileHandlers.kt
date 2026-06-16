package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.model.dto.Tool
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * IDE 文件操作工具的 Handler 适配器
 * 将 IDETools 中的方法包装为 ToolHandler，支持动态注册
 */
object IDEFileHandlers {

    fun createReadFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(readFileTool()) { ideTools.readFile(it) }

    fun createWriteFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(writeFileTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS) { ideTools.writeFile(it) }

    fun createListDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(listDirectoryTool()) { ideTools.listDirectory(it) }

    fun createFindFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(findFileTool()) { ideTools.findFile(it) }

    /**
     * 6.3.2 新增：glob 模式批量定位文件/目录。
     *
     * 支持 `**` 递归匹配；默认排除常见生成目录；返回 `matches[]` 与 `truncated` 标记。
     */
    fun createGlobHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(globTool()) { args ->
            val pattern = args["pattern"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'pattern' parameter")
            val path = args["path"]?.jsonPrimitive?.content
            val maxResults = (args["max_results"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(1, 1000)
            val includeDirs = args["include_dirs"]?.jsonPrimitive?.booleanOrNull ?: false
            val includeHidden = args["include_hidden"]?.jsonPrimitive?.booleanOrNull ?: false
            val excludeDirs = args["exclude_dirs"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                ?: setOf("node_modules", ".git", "build", ".gradle", "target", "__pycache__", ".idea")

            val searchPath = ideTools.resolveWorkingDir(path)

            val rootFile = File(searchPath)
            if (!rootFile.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Path not found: $searchPath")
            }

            try {
                val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
                val rootPath = rootFile.toPath()
                val matches = mutableListOf<JsonObject>()
                var truncated = false

                Files.walk(rootPath).use { stream ->
                    stream.forEach { p ->
                        if (matches.size >= maxResults) {
                            truncated = true
                            return@forEach
                        }

                        val file = p.toFile()
                        val relative = rootPath.relativize(p).toString().replace("\\", "/")
                        if (relative.isEmpty()) return@forEach

                        if (!includeHidden && (file.name.startsWith(".") || relative.split("/")
                                .any { it.startsWith(".") })
                        ) {
                            return@forEach
                        }
                        if (excludeDirs.any { dir -> relative.split("/").contains(dir) }) {
                            return@forEach
                        }

                        val isDirectory = Files.isDirectory(p)
                        if (!includeDirs && isDirectory) return@forEach

                        val fileNamePath = p.fileName ?: return@forEach
                        if (matcher.matches(fileNamePath) || matcher.matches(Paths.get(relative))) {
                            matches.add(
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive(fileNamePath.toString()),
                                        "path" to JsonPrimitive(p.toString().replace("\\", "/")),
                                        "is_directory" to JsonPrimitive(isDirectory)
                                    )
                                )
                            )
                        }
                    }
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "pattern" to JsonPrimitive(pattern),
                            "path" to JsonPrimitive(searchPath),
                            "matches" to JsonArray(matches),
                            "total" to JsonPrimitive(matches.size),
                            "truncated" to JsonPrimitive(truncated),
                            "max_results" to JsonPrimitive(maxResults)
                        )
                    )
                )
            } catch (e: Exception) {
                ToolResult.Error("glob failed: ${e.message}")
            }
        }

    fun createGrepCodeHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(grepCodeTool()) { ideTools.grepCode(it) }

    fun createGetFileInfoHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(getFileInfoTool()) { ideTools.getFileInfo(it) }

    fun createReadMultipleFilesHandler(ideTools: IDETools): ToolHandler =
        object : ToolHandler {
            override val tool: Tool = readMultipleFilesTool()
            override suspend fun execute(args: JsonObject): ToolResult =
                ideTools.readMultipleFiles(args)
        }

    fun createEditFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(editFileTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS) { ideTools.editFile(it) }

    fun createDeleteFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(deleteFileTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS) { ideTools.deleteFile(it) }

    fun createCopyFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(copyFileTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS) { ideTools.copyFile(it) }

    fun createMoveFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(moveFileTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS) { ideTools.moveFile(it) }

    fun createSearchCodeHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(searchCodeTool()) { ideTools.searchCode(it) }

    fun createRunCommandHandler(ideTools: IDETools): ToolHandler =
        object : ToolHandler {
            override val tool: Tool = runCommandTool()
            override val riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
            override suspend fun execute(args: JsonObject): ToolResult =
                ideTools.runCommand(args)

            override suspend fun execute(
                args: JsonObject,
                onStream: suspend (com.codesage.agent.core.AgentStreamEvent) -> Unit
            ): ToolResult = ideTools.runCommand(args, onStream = onStream)
        }

    fun createGetProjectStructureHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(getProjectStructureTool()) { ideTools.getProjectStructure(it) }

    // region 新增文件操作工具

    fun createCreateDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(createDirectoryTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val path = args["path"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'path' parameter")
            val resolved = ideTools.resolvePath(path)
            val dir = File(resolved)
            if (dir.exists()) {
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive(path),
                            "created" to JsonPrimitive(false),
                            "exists" to JsonPrimitive(true)
                        )
                    )
                )
            } else {
                val success = dir.mkdirs()
                ToolResult.Success(
                    JsonObject(mapOf("path" to JsonPrimitive(path), "created" to JsonPrimitive(success)))
                )
            }
         })

    fun createZipDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(zipDirectoryTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val source = args["source"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'source' parameter")
            val destination = args["destination"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'destination' parameter")
            val srcDir = File(ideTools.resolvePath(source))
            val dstFile = File(ideTools.resolvePath(destination))
            if (!srcDir.exists() || !srcDir.isDirectory) {
                return@FunctionalToolHandler ToolResult.Error("Source directory not found: $source")
            }
            dstFile.parentFile?.mkdirs()
            ZipOutputStream(dstFile.outputStream()).use { zos ->
                srcDir.walkTopDown().forEach { file ->
                    val entryName = file.relativeTo(srcDir).path.replace("\\", "/")
                    if (file.isDirectory) {
                        if (entryName.isNotEmpty()) {
                            zos.putNextEntry(ZipEntry("$entryName/"))
                            zos.closeEntry()
                        }
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "size" to JsonPrimitive(dstFile.length())
                    )
                )
            )
         })

    fun createUnzipArchiveHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(unzipArchiveTool(), riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS, executor = {  args ->
            val source = args["source"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'source' parameter")
            val destination = args["destination"]?.jsonPrimitive?.content
                ?: return@FunctionalToolHandler ToolResult.Error("Missing 'destination' parameter")
            val srcFile = File(ideTools.resolvePath(source))
            val dstDir = File(ideTools.resolvePath(destination))
            if (!srcFile.exists()) {
                return@FunctionalToolHandler ToolResult.Error("Archive not found: $source")
            }
            dstDir.mkdirs()
            var count = 0
            ZipFile(srcFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val outFile = File(dstDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        count++
                    }
                }
            }
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "source" to JsonPrimitive(source),
                        "destination" to JsonPrimitive(destination),
                        "files_extracted" to JsonPrimitive(count)
                    )
                )
            )
         })

    // endregion
}

// T6.1 修复：原以为 IDETools.resolvePath 是 private，所以用反射访问。
// 实际上该方法在某个修复点已经改为 internal（在同一 module 内可见），
// 反射扩展是冗余的。删除后 IDEFileHandlers 直接调用 IDETools.resolvePath
// （internal 在同包/module 内可见，IDEFileHandlers 在同 module）。
