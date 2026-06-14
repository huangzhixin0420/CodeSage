package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.IDETools
import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolCategory
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.serialization.JsonArgDecoders
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * P0 6.1.3：多模态文档读取工具。
 *
 * 支持读取图片（PNG/JPG/JPEG/WEBP/GIF/BMP）、PDF 与 Jupyter Notebook（.ipynb）。
 * - 图片：返回 base64 编码、`mime_type` 与尺寸。
 * - PDF：使用 Apache PDFBox 提取每页文本，支持单页指定与最大页数限制。
 * - ipynb：解析 cells 列表，返回结构化 JSON。
 *
 * 本工具作为独立 `read_document` 存在，避免污染 `read_file` 的纯文本语义。
 */
class ReadDocumentTool(private val ideTools: IDETools) : UnifiedTool(
    name = "read_document",
    description = """
        Read a multimodal document: image (PNG/JPG/JPEG/WEBP/GIF/BMP), PDF, or Jupyter Notebook (.ipynb).
        For images, returns base64-encoded data, mime_type, and dimensions.
        For PDFs, extracts text per page; supports optional page selection and max page limits.
        For .ipynb notebooks, returns the cell list with cell_type, source, and outputs.
    """.trimIndent(),
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "path" to ToolProperty("string", "文件路径（相对项目根目录或绝对路径）"),
            "page" to ToolProperty("integer", "PDF 专用：指定页码（1-based）。不传则返回前 max_pages 页。"),
            "max_pages" to ToolProperty("integer", "PDF 专用：最大返回页数，默认 10。"),
            "max_size_bytes" to ToolProperty("integer", "文件大小上限（字节），默认 20MB。超过返回错误。"),
            "include_image_data" to ToolProperty("boolean", "图片专用：是否返回 base64 数据，默认 true。"),
            "max_chars_per_page" to ToolProperty("integer", "PDF 专用：每页最大字符数，默认 10000。"),
        ),
        required = listOf("path")
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.FILE_OPERATION,
        tags = setOf("read", "document", "image", "pdf", "notebook", "multimodal")
    )

    companion object {
        const val DEFAULT_MAX_SIZE_BYTES = 20 * 1024 * 1024L // 20MB
        const val DEFAULT_MAX_PAGES = 10
        const val DEFAULT_MAX_CHARS_PER_PAGE = 10_000

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = JsonArgDecoders.stringArg(args, "path")
        val resolvedPath = ideTools.resolvePath(path)

        val page = JsonArgDecoders.intArgOrNull(args, "page")
        val maxPages = JsonArgDecoders.intArg(args, "max_pages", default = DEFAULT_MAX_PAGES)
        val maxSizeBytes = JsonArgDecoders.longArg(args, "max_size_bytes", default = DEFAULT_MAX_SIZE_BYTES)
        val includeImageData = JsonArgDecoders.boolArg(args, "include_image_data", default = true)
        val maxCharsPerPage = JsonArgDecoders.intArg(args, "max_chars_per_page", default = DEFAULT_MAX_CHARS_PER_PAGE)

        if (page != null && page < 1) {
            return ToolResult.Error("Parameter 'page' must be >= 1 (1-based).")
        }
        if (maxPages < 1) {
            return ToolResult.Error("Parameter 'max_pages' must be >= 1.")
        }

        val app = ApplicationManager.getApplication()
        val useVfs = app != null
        return try {
            if (useVfs) {
                app.runReadAction(Computable {
                    readDocumentCore(
                        resolvedPath,
                        path,
                        page,
                        maxPages,
                        maxSizeBytes,
                        includeImageData,
                        maxCharsPerPage,
                        useVfs = true
                    )
                })
            } else {
                // headless / 测试场景：无 IntelliJ Application，直接走本地文件
                readDocumentCore(
                    resolvedPath,
                    path,
                    page,
                    maxPages,
                    maxSizeBytes,
                    includeImageData,
                    maxCharsPerPage,
                    useVfs = false
                )
            }
        } catch (e: Exception) {
            ToolResult.Error("Failed to read document '$path': ${e.message}")
        }
    }

    /**
     * 实际的文档读取逻辑，供 [execute] 在 VFS read action 内部或 headless 环境调用。
     *
     * @param useVfs 为 true 时优先使用 IntelliJ VFS 定位文件；为 false 时直接走 [java.io.File]，避免 headless 环境下
     *               `LocalFileSystem` 依赖未初始化的 Application 服务。
     */
    private fun readDocumentCore(
        resolvedPath: String,
        originalPath: String,
        page: Int?,
        maxPages: Int,
        maxSizeBytes: Long,
        includeImageData: Boolean,
        maxCharsPerPage: Int,
        useVfs: Boolean
    ): ToolResult {
        val file = if (useVfs) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (virtualFile != null) {
                if (virtualFile.isDirectory) {
                    return ToolResult.Error("Path is a directory: $originalPath")
                }
                File(virtualFile.path)
            } else {
                File(resolvedPath).takeIf { it.isFile }
                    ?: return ToolResult.Error("File not found: $originalPath")
            }
        } else {
            File(resolvedPath).takeIf { it.isFile }
                ?: return ToolResult.Error("File not found: $originalPath")
        }

        if (file.length() > maxSizeBytes) {
            return ToolResult.Error(
                "File size ${file.length()} bytes exceeds max_size_bytes $maxSizeBytes."
            )
        }

        val bytes = file.readBytes()
        val extension = file.extension.lowercase()

        return when (extension) {
            in IMAGE_EXTENSIONS -> readImage(file, bytes, includeImageData, originalPath)
            "pdf" -> readPdf(file, page, maxPages, maxCharsPerPage, originalPath)
            "ipynb" -> readNotebook(bytes, originalPath)
            else -> ToolResult.Error(
                "Unsupported document format '.$extension'. " +
                        "Supported: ${IMAGE_EXTENSIONS.joinToString(", ") { ".$it" }}, .pdf, .ipynb."
            )
        }
    }

    /**
     * 读取图片并返回 base64 编码、mime_type 与尺寸信息。
     */
    private fun readImage(
        file: File,
        bytes: ByteArray,
        includeImageData: Boolean,
        originalPath: String
    ): ToolResult {
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }

        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
            ?: return ToolResult.Error("Unable to decode image: $originalPath")

        val dataUrl = if (includeImageData) {
            "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
        } else null

        return ToolResult.Success(
            buildJsonObject {
                put("path", originalPath)
                put("format", "image")
                put("mime_type", mimeType)
                put("size", file.length())
                put("width", image.width)
                put("height", image.height)
                put("dimensions", "${image.width}x${image.height}")
                if (dataUrl != null) put("base64", dataUrl)
                put("truncated", false)
                put("total_items", 1)
                put("returned_items", 1)
            }
        )
    }

    /**
     * 使用 Apache PDFBox 提取 PDF 文本。
     *
     * 当指定 [page] 时仅返回该页；否则按 [maxPages] 限制返回前 N 页。
     * 每页文本超过 [maxCharsPerPage] 时截断并标记 `truncated`。
     */
    private fun readPdf(
        file: File,
        page: Int?,
        maxPages: Int,
        maxCharsPerPage: Int,
        originalPath: String
    ): ToolResult {
        Loader.loadPDF(file).use { document ->
            val pageCount = document.numberOfPages

            if (page != null && page > pageCount) {
                return ToolResult.Error(
                    "Page $page exceeds total page count $pageCount."
                )
            }

            val startPage = page ?: 1
            val endPage = if (page != null) {
                page
            } else {
                minOf(maxPages, pageCount)
            }

            val pagesArray = buildJsonArray {
                val stripper = PDFTextStripper()
                for (p in startPage..endPage) {
                    stripper.startPage = p
                    stripper.endPage = p
                    val text = stripper.getText(document)
                    val (clipped, wasTruncated) = safeTruncate(text, maxCharsPerPage)
                    addJsonObject {
                        put("page_number", p)
                        put("text", clipped)
                        put("truncated", wasTruncated)
                    }
                }
            }

            val returnedPages = endPage - startPage + 1
            return ToolResult.Success(
                buildJsonObject {
                    put("path", originalPath)
                    put("format", "pdf")
                    put("mime_type", "application/pdf")
                    put("size", file.length())
                    put("page_count", pageCount)
                    put("returned_pages", returnedPages)
                    put("start_page", startPage)
                    put("end_page", endPage)
                    put("pages", pagesArray)
                    put("truncated", returnedPages < pageCount)
                    put("total_items", pageCount)
                    put("returned_items", returnedPages)
                }
            )
        }
    }

    /**
     * 解析 Jupyter Notebook（.ipynb）并返回结构化 cells 列表。
     *
     * 只提取对 Agent 有用的字段：cell_type、source、outputs（简化）、execution_count、metadata.language。
     */
    private fun readNotebook(bytes: ByteArray, originalPath: String): ToolResult {
        val json = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return ToolResult.Error("Invalid JSON in notebook: $originalPath")
        }

        val root = json as? JsonObject
            ?: return ToolResult.Error("Notebook root must be a JSON object: $originalPath")

        val cellsArray = root["cells"] as? JsonArray
        val metadata = root["metadata"] as? JsonObject
        val language = metadata?.get("kernelspec")?.let { kernel ->
            (kernel as? JsonObject)?.get("language")?.jsonPrimitive?.content
        } ?: metadata?.get("language_info")?.let { lang ->
            (lang as? JsonObject)?.get("name")?.jsonPrimitive?.content
        }

        val cells = if (cellsArray != null) {
            buildJsonArray {
                cellsArray.forEach { cell ->
                    if (cell !is JsonObject) return@forEach
                    val cellType = cell["cell_type"]?.jsonPrimitive?.content ?: "unknown"
                    val source = extractNotebookSource(cell["source"])
                    val executionCount = cell["execution_count"]?.jsonPrimitive?.intOrNull
                    val outputs = when (val outs = cell["outputs"]) {
                        is JsonArray -> JsonArray(outs.map { simplifyNotebookOutput(it) })
                        else -> JsonArray(emptyList())
                    }
                    addJsonObject {
                        put("cell_type", cellType)
                        put("source", source)
                        if (executionCount != null) put("execution_count", executionCount)
                        put("outputs", outputs)
                    }
                }
            }
        } else {
            JsonArray(emptyList())
        }

        return ToolResult.Success(
            buildJsonObject {
                put("path", originalPath)
                put("format", "jupyter_notebook")
                put("mime_type", "application/x-ipynb+json")
                put("size", bytes.size.toLong())
                put("nbformat", root["nbformat"]?.jsonPrimitive?.intOrNull ?: -1)
                put("nbformat_minor", root["nbformat_minor"]?.jsonPrimitive?.intOrNull ?: -1)
                if (language != null) put("language", language)
                put("cells", cells)
                put("truncated", false)
                put("total_items", cells.size)
                put("returned_items", cells.size)
            }
        )
    }

    /**
     * 将 notebook cell 的 source 字段（字符串或字符串数组）统一为字符串。
     */
    private fun extractNotebookSource(sourceElement: JsonElement?): String {
        return when (sourceElement) {
            is JsonArray -> sourceElement.joinToString("") {
                it.jsonPrimitive.content
            }

            is JsonPrimitive -> sourceElement.content
            else -> ""
        }
    }

    /**
     * 简化 notebook cell output，仅保留 output_type 与文本/图像摘要，避免单个 cell 输出过大。
     */
    private fun simplifyNotebookOutput(output: JsonElement): JsonElement {
        if (output !is JsonObject) return output
        val outputType = output["output_type"]?.jsonPrimitive?.content ?: "unknown"
        val text = when (val data = output["text"]) {
            is JsonArray -> data.joinToString("") { it.jsonPrimitive.content }
            is JsonPrimitive -> data.content
            else -> null
        }
        val data = output["data"] as? JsonObject
        val imageTypes = data?.keys?.filter { it.startsWith("image/") } ?: emptyList()

        return buildJsonObject {
            put("output_type", outputType)
            if (text != null) put("text", text.take(DEFAULT_MAX_CHARS_PER_PAGE))
            if (imageTypes.isNotEmpty()) {
                put("image_mime_types", JsonArray(imageTypes.map { JsonPrimitive(it) }))
            }
        }
    }

    /**
     * 安全截断文本，返回截断后的字符串与是否发生截断。
     */
    private fun safeTruncate(text: String, maxChars: Int): Pair<String, Boolean> {
        return if (text.length > maxChars) {
            text.take(maxChars) + "\n... [truncated]" to true
        } else {
            text to false
        }
    }
}
