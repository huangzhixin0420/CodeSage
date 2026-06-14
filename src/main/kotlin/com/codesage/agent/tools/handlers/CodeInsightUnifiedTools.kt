package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.analysis.CodeInsightExecutor
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import kotlinx.serialization.json.JsonObject

/**
 * T6.1 修复：Code Insight 工具的 UnifiedTool 实现
 *
 * 替换原来的模式：
 * - `CodeInsightTools.kt` 中只提供 Tool 元数据
 * - `ToolExecutor` 中硬编码 when 路由到 CodeInsightExecutor
 *
 * 新模式：每个工具一个类，metadata + logic 在一起。
 *
 * 保留 `CodeInsightTools` object 作为旧 API 的兼容性入口（标记 @Deprecated），
 * 新代码应使用这些类。
 */
class AnalyzeSymbolTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "analyze_symbol",
    description = "Analyze a class, method, or field symbol. Returns type information, signature, documentation, modifiers, and inheritance info.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "symbol_name" to ToolProperty("string", "Fully qualified or simple symbol name to analyze"),
            "file_path" to ToolProperty("string", "Optional file path hint for disambiguation")
        ),
        required = listOf("symbol_name")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.analyzeSymbol(args)
}

class FindUsagesTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "find_usages",
    description = "Find all usages/callers of a symbol within the project. Returns file paths and line numbers.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "symbol_name" to ToolProperty("string", "Symbol name to find usages for"),
            "type" to ToolProperty(
                "string",
                "Symbol type: class, method, field",
                enum = listOf("class", "method", "field")
            )
        ),
        required = listOf("symbol_name")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.findUsages(args)
}

class FindCallersTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "find_callers",
    description = "6.5.2: Find all callers of a method, function, class, or field. Returns structured locations with file_path, line, column, and caller_symbol.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "symbol_name" to ToolProperty("string", "Symbol name to find callers for"),
            "file_path" to ToolProperty("string", "Optional file path hint for disambiguation"),
            "type" to ToolProperty(
                "string",
                "Symbol type: class, method, field, property",
                enum = listOf("class", "method", "field", "property")
            ),
            "limit" to ToolProperty("integer", "Maximum number of callers to return, default 50")
        ),
        required = listOf("symbol_name")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.findCallers(args)
}

class FindCalleesTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "find_callees",
    description = "6.5.2: Find all callees (methods/functions called) by a target method or function. Returns structured locations with file_path, line, column, and callee_symbol.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "symbol_name" to ToolProperty("string", "Symbol name to find callees for"),
            "file_path" to ToolProperty("string", "Optional file path hint for disambiguation"),
            "limit" to ToolProperty("integer", "Maximum number of callees to return, default 50")
        ),
        required = listOf("symbol_name")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.findCallees(args)
}

class GetInheritanceChainTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "get_inheritance_chain",
    description = "Get the full inheritance chain for a class (superclasses and interfaces).",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "class_name" to ToolProperty("string", "Fully qualified class name")
        ),
        required = listOf("class_name")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.getInheritanceChain(args)
}

class SemanticSearchTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "semantic_search",
    description = "Search for code symbols using natural language descriptions. Finds classes, methods, and fields matching the description. Uses a local chunk-level vector index when available; run `reindex_semantic` to build or rebuild the index.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "query" to ToolProperty("string", "Natural language description of what you're looking for"),
            "limit" to ToolProperty("integer", "Maximum results to return, default 10")
        ),
        required = listOf("query")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.semanticSearch(args)
}

class GetFileSummaryTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "get_file_summary",
    description = "Get a structured summary of a file: classes, methods, fields, and their signatures.",
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "file_path" to ToolProperty("string", "Path to the file (relative to project root)")
        ),
        required = listOf("file_path")
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.getFileSummary(args)
}

class GetProjectStatsTool(private val executor: CodeInsightExecutor) : UnifiedTool(
    name = "get_project_stats",
    description = "Get statistics about the indexed project: total classes, methods, files, etc.",
    parameters = ToolParameters(
        type = "object",
        properties = emptyMap(),
        required = emptyList()
    )
) {
    override suspend fun execute(args: JsonObject): ToolResult = executor.getProjectStats(args)
}

/**
 * 便利函数：批量注册所有 Code Insight 工具
 */
fun List<UnifiedTool>.addAllCodeInsightTools(executor: CodeInsightExecutor): List<UnifiedTool> =
    this + listOf(
        AnalyzeSymbolTool(executor),
        FindUsagesTool(executor),
        FindCallersTool(executor),
        FindCalleesTool(executor),
        GetInheritanceChainTool(executor),
        SemanticSearchTool(executor),
        ReindexSemanticTool(executor),
        GetFileSummaryTool(executor),
        GetProjectStatsTool(executor)
    )
