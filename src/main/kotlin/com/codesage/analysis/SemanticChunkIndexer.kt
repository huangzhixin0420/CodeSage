package com.codesage.analysis

import com.codesage.agent.memory.EmbeddingProvider
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 6.3.3 语义搜索 chunk 构建器。
 *
 * 扫描项目文件，将其切分为 chunk 并生成 embedding，写入 [SemanticIndexRepository]。
 *
 * 切分策略：
 * - 优先复用 [SymbolIndex] 已索引的符号边界（类/方法），每个符号对应一段 chunk。
 * - 无符号或符号不足时，按固定 50 行窗口切分。
 * - 跳过二进制/生成目录（node_modules、build、.git 等）。
 */
class SemanticChunkIndexer(
    private val project: Project?,
    private val repository: SemanticIndexRepository,
    private val provider: EmbeddingProvider,
    private val rootPath: String? = project?.basePath,
    private val symbolIndex: SymbolIndex? = project?.let { SymbolIndex(it) }
) {

    private val logger = Logger.getLogger<SemanticChunkIndexer>()

    /**
     * 索引结果统计。
     */
    data class IndexResult(
        val filesIndexed: Int,
        val chunksIndexed: Int,
        val durationMs: Long,
        val errors: List<String>
    )


    /**
     * 执行索引。
     *
     * @param force 为 true 时清空已有索引后重建；否则跳过已索引文件。
     */
    fun buildIndex(force: Boolean = false): IndexResult {
        val root = rootPath?.let { File(it) } ?: return IndexResult(0, 0, 0, listOf("No project root path available"))
        if (!root.exists() || !root.isDirectory) {
            return IndexResult(
                0,
                0,
                0,
                listOf("Project root does not exist or is not a directory: ${root.absolutePath}")
            )
        }

        val start = System.currentTimeMillis()
        if (force) {
            repository.clearAll()
        }

        val errors = mutableListOf<String>()
        val files = collectFiles(root)
        val alreadyIndexed = if (force) emptySet() else repository.getIndexedFilePaths()

        var filesIndexed = 0
        var chunksIndexed = 0

        for (file in files) {
            if (file.path in alreadyIndexed) continue
            try {
                val chunks = createChunksForFile(file)
                if (chunks.isEmpty()) continue

                val contents = chunks.map { it.content }
                val vectors = embedInBatches(contents)

                chunks.forEachIndexed { index, chunk ->
                    val id = repository.insertChunk(chunk, vectors[index])
                    if (id > 0) chunksIndexed++
                }
                filesIndexed++
            } catch (e: Exception) {
                val msg = "Failed to index ${file.path}: ${e.message}"
                logger.warn(msg, e)
                errors.add(msg)
            }
        }

        val duration = System.currentTimeMillis() - start
        logger.info("Semantic index built: files=$filesIndexed, chunks=$chunksIndexed, duration=${duration}ms, errors=${errors.size}")
        return IndexResult(filesIndexed, chunksIndexed, duration, errors)
    }

    private fun collectFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                name !in EXCLUDED_DIRS && !name.startsWith(".")
            }
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .take(MAX_FILES)
            .toCollection(result)
        return result
    }

    private fun createChunksForFile(file: File): List<SemanticChunk> {
        val content = try {
            if (file.length() > MAX_FILE_BYTES) {
                file.readLines(StandardCharsets.UTF_8).take(1000).joinToString("\n")
            } else {
                file.readText(StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            logger.debug("Cannot read file as text: ${file.path}")
            return emptyList()
        }

        val lines = content.lines()
        if (lines.isEmpty()) return emptyList()

        val symbols = symbolIndex?.getFileSymbols(file.path).orEmpty()
        return if (symbols.isNotEmpty()) {
            createSymbolBasedChunks(file.path, lines, symbols)
        } else {
            createWindowChunks(file.path, lines)
        }
    }

    private fun createSymbolBasedChunks(
        filePath: String,
        lines: List<String>,
        symbols: List<PSIAnalyzer.SymbolInfo>
    ): List<SemanticChunk> {
        val sorted = symbols.sortedBy { it.lineNumber }
        val chunks = mutableListOf<SemanticChunk>()

        sorted.forEachIndexed { index, symbol ->
            val startLine = (symbol.lineNumber - 1).coerceAtLeast(0)
            val nextLine = sorted.getOrNull(index + 1)?.lineNumber?.let { it - 1 } ?: lines.size
            val endLine = minOf(startLine + MAX_CHUNK_LINES, nextLine, lines.size)
            val chunkContent = lines.subList(startLine, endLine).joinToString("\n")

            if (chunkContent.isNotBlank()) {
                chunks.add(
                    SemanticChunk(
                        filePath = filePath,
                        startLine = startLine + 1,
                        endLine = endLine,
                        content = chunkContent,
                        symbolName = symbol.name,
                        symbolType = symbol.type.name
                    )
                )
            }
        }

        // 为没有符号覆盖的文件尾部补一个窗口 chunk，避免遗漏配置/纯文本内容
        val lastSymbolEnd = sorted.maxOfOrNull { it.lineNumber } ?: 0
        if (lastSymbolEnd < lines.size) {
            val startLine = lastSymbolEnd.coerceAtLeast(0)
            val endLine = minOf(startLine + MAX_CHUNK_LINES, lines.size)
            val tailContent = lines.subList(startLine, endLine).joinToString("\n")
            if (tailContent.isNotBlank()) {
                chunks.add(
                    SemanticChunk(
                        filePath = filePath,
                        startLine = startLine + 1,
                        endLine = endLine,
                        content = tailContent
                    )
                )
            }
        }

        return chunks
    }

    private fun createWindowChunks(filePath: String, lines: List<String>): List<SemanticChunk> {
        val chunks = mutableListOf<SemanticChunk>()
        var startLine = 0
        while (startLine < lines.size) {
            val endLine = minOf(startLine + MAX_CHUNK_LINES, lines.size)
            val content = lines.subList(startLine, endLine).joinToString("\n")
            if (content.isNotBlank()) {
                chunks.add(
                    SemanticChunk(
                        filePath = filePath,
                        startLine = startLine + 1,
                        endLine = endLine,
                        content = content
                    )
                )
            }
            startLine = endLine
        }
        return chunks
    }

    private fun embedInBatches(contents: List<String>): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        contents.chunked(BATCH_SIZE).forEach { batch ->
            result.addAll(provider.embed(batch))
        }
        return result
    }

    companion object {
        /** 单行 chunk 最大行数。 */
        private const val MAX_CHUNK_LINES = 50

        /** 单文件大小上限（字节），超大文件仅索引前 N 行。 */
        private const val MAX_FILE_BYTES = 200_000

        /** 批量 embedding 大小，控制 ONNX/Hash 推理内存。 */
        private const val BATCH_SIZE = 32

        private const val MAX_FILES = 10_000

        private val EXCLUDED_DIRS = setOf(
            "node_modules", "build", ".gradle", "target", "__pycache__", ".git", ".idea", "out", "dist"
        )

        private val SUPPORTED_EXTENSIONS = SymbolIndex.CODE_EXTENSIONS +
                SymbolIndex.CONFIG_EXTENSIONS +
                SymbolIndex.TEXT_EXTENSIONS
    }
}
