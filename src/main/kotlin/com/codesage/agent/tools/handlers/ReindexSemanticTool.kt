package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.analysis.CodeInsightExecutor
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.JsonObject

/**
 * 6.3.3 `reindex_semantic` 工具：手动触发项目语义向量索引重建。
 *
 * 该工具会扫描项目源码文件，按符号或固定行窗口切分 chunk，计算 embedding 后写入
 * 项目级 SQLite（`.codesage/semantic_index.db`），供 `semantic_search` 做真实向量召回。
 *
 * 注意：此工具仅读取源码并写入索引缓存数据库，不修改业务代码文件。
 */
class ReindexSemanticTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "reindex_semantic",
    description = """
        Summary: Rebuild the semantic vector index for the project codebase.
        Args: path (string, optional): root directory to index, defaults to project root; force (boolean, optional): clear existing index and rebuild, default false.
        Do: Run after major codebase changes or before important semantic_search queries to ensure the vector index is up-to-date.
        Don't: Don't index directories outside the project; don't assume the index is auto-updated for every file change (incremental updates are best-effort).
        Parallel: No, indexing is I/O and CPU intensive and should not run concurrently with itself.
        Cap: Skips binary/generation directories (node_modules, build, .gradle, target, etc.); very large files are truncated.
    """.trimIndent(),
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "Root directory to index. Defaults to project root. Relative paths are resolved against project root."
            ),
            "force" to ToolProperty(
                type = "boolean",
                description = "If true, clears the existing semantic index and rebuilds from scratch."
            )
        ),
        required = emptyList()
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.reindexSemantic(args)
}
