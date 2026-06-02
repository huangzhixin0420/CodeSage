package com.codesage.rag.chunker

import com.codesage.rag.VectorStore

/**
 * T3.3 修复：Document Chunker 接口
 *
 * 把项目源代码切分成 VectorStore.Chunk 列表。
 *
 * 设计要点：
 * - 接口隔离具体实现（PSI-based / regex-based / token-based）
 * - 优先级：先按 AST 切（Kotlin/Java class、method），fallback 到按行切
 * - 单元大小限制：单 chunk 内容不超过 2000 字符（超出时切多块）
 * - chunk id 唯一性：filePath + symbolName + lineIndex
 */
interface DocumentChunker {

    /**
     * 把一段代码文本切分成多个 chunk
     *
     * @param filePath 用于生成 chunk id 和元数据
     * @param content 文件内容
     * @return 切分结果
     */
    fun chunk(filePath: String, content: String): List<VectorStore.Chunk>

    companion object {
        const val MAX_CHUNK_SIZE = 2000  // 字符
    }
}

/**
 * T3.3 修复：基于 AST 的 chunker（优先）
 *
 * 接受 [com.codesage.analysis.SymbolIndex] 提供的 PSI 符号信息，
 * 为每个 class/method/function 创建一个 chunk。
 */
class AstChunker(
    private val symbolProvider: SymbolProvider? = null
) : DocumentChunker {

    /**
     * 简化的符号提供接口：可以从 PSI (production) 或
     * 直接构造（test）传入符号信息
     */
    interface SymbolProvider {
        fun symbolsIn(filePath: String): List<SymbolInfo>
    }

    data class SymbolInfo(
        val name: String,
        val kind: String,  // CLASS / METHOD / FIELD / FUNCTION
        val startLine: Int,
        val endLine: Int,
        val text: String
    )

    override fun chunk(filePath: String, content: String): List<VectorStore.Chunk> {
        val symbols = symbolProvider?.symbolsIn(filePath) ?: emptyList()
        if (symbols.isEmpty()) {
            // Fallback: 把整文件作为一个 chunk
            return listOf(fileChunk(filePath, content, 0, content.lines().size, content))
        }
        val chunks = mutableListOf<VectorStore.Chunk>()
        for (sym in symbols) {
            val symText = sym.text.take(DocumentChunker.MAX_CHUNK_SIZE)
            chunks.add(
                VectorStore.Chunk(
                    id = chunkId(filePath, sym),
                    filePath = filePath,
                    startLine = sym.startLine,
                    endLine = sym.endLine,
                    content = symText,
                    symbolKind = sym.kind,
                    symbolName = sym.name,
                    embedding = FloatArray(0)  // embedding 由外部模型计算后调用 upsertWithEmbeddings 注入
                )
            )
        }
        return chunks
    }

    private fun fileChunk(
        filePath: String,
        content: String,
        startLine: Int,
        endLine: Int,
        text: String
    ): VectorStore.Chunk {
        return VectorStore.Chunk(
            id = "$filePath:file:$startLine",
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            content = text.take(DocumentChunker.MAX_CHUNK_SIZE),
            symbolKind = "FILE",
            symbolName = null,
            embedding = FloatArray(0)
        )
    }

    private fun chunkId(filePath: String, sym: SymbolInfo): String =
        "$filePath:${sym.kind}:${sym.name}:${sym.startLine}"
}

/**
 * T3.3 修复：基于行的 chunker（fallback）
 *
 * 当没有 PSI 时使用：按固定行数切分，文件大于 MAX_CHUNK_SIZE 时切多块。
 */
class LineBasedChunker(
    private val linesPerChunk: Int = 50
) : DocumentChunker {

    override fun chunk(filePath: String, content: String): List<VectorStore.Chunk> {
        val lines = content.lines()
        if (lines.isEmpty()) return emptyList()

        val chunks = mutableListOf<VectorStore.Chunk>()
        var i = 0
        while (i < lines.size) {
            val endIdx = (i + linesPerChunk).coerceAtMost(lines.size)
            val chunkText = lines.subList(i, endIdx).joinToString("\n")
            chunks.add(
                VectorStore.Chunk(
                    id = "$filePath:lines:$i-${endIdx - 1}",
                    filePath = filePath,
                    startLine = i,
                    endLine = endIdx - 1,
                    content = chunkText.take(DocumentChunker.MAX_CHUNK_SIZE),
                    symbolKind = "FILE",
                    symbolName = null,
                    embedding = FloatArray(0)
                )
            )
            i = endIdx
        }
        return chunks
    }
}
