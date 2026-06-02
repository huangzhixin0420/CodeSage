package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
        FunctionalToolHandler(writeFileTool()) { ideTools.writeFile(it) }

    fun createListDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(listDirectoryTool()) { ideTools.listDirectory(it) }

    fun createFindFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(findFileTool()) { ideTools.findFile(it) }

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
        FunctionalToolHandler(editFileTool()) { ideTools.editFile(it) }

    fun createDeleteFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(deleteFileTool()) { ideTools.deleteFile(it) }

    fun createCopyFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(copyFileTool()) { ideTools.copyFile(it) }

    fun createMoveFileHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(moveFileTool()) { ideTools.moveFile(it) }

    fun createSearchCodeHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(searchCodeTool()) { ideTools.searchCode(it) }

    fun createRunCommandHandler(ideTools: IDETools): ToolHandler =
        object : ToolHandler {
            override val tool: Tool = runCommandTool()
            override suspend fun execute(args: JsonObject): ToolResult =
                ideTools.runCommand(args)
        }

    fun createGetProjectStructureHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(getProjectStructureTool()) { ideTools.getProjectStructure(it) }

    // region 新增文件操作工具

    fun createCreateDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(createDirectoryTool()) { args ->
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
        }

    fun createZipDirectoryHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(zipDirectoryTool()) { args ->
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
        }

    fun createUnzipArchiveHandler(ideTools: IDETools): ToolHandler =
        FunctionalToolHandler(unzipArchiveTool()) { args ->
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
        }

    // endregion
}

// T6.1 修复：原以为 IDETools.resolvePath 是 private，所以用反射访问。
// 实际上该方法在某个修复点已经改为 internal（在同一 module 内可见），
// 反射扩展是冗余的。删除后 IDEFileHandlers 直接调用 IDETools.resolvePath
// （internal 在同包/module 内可见，IDEFileHandlers 在同 module）。
