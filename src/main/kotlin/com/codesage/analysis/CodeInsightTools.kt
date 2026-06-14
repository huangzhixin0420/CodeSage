package com.codesage.analysis

import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty

/**
 * 代码洞察工具定义
 * 将AST分析能力暴露为LLM可调用的工具
 */
object CodeInsightTools {

    fun analyzeSymbolTool() = Tool(
        name = "analyze_symbol",
        description = "Analyze a class, method, or field symbol. Returns type information, signature, documentation, modifiers, and inheritance info.",
        parameters = ToolParameters(
            properties = mapOf(
                "symbol_name" to ToolProperty("string", "Fully qualified or simple symbol name to analyze"),
                "file_path" to ToolProperty("string", "Optional file path hint for disambiguation")
            ),
            required = listOf("symbol_name")
        )
    )

    fun findUsagesTool() = Tool(
        name = "find_usages",
        description = "Find all usages/callers of a symbol within the project. Returns file paths and line numbers.",
        parameters = ToolParameters(
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
    )

    fun findCallersTool() = Tool(
        name = "find_callers",
        description = "6.5.2: Find all callers of a symbol. Returns structured locations with file_path, line, column, and caller_symbol.",
        parameters = ToolParameters(
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
    )

    fun findCalleesTool() = Tool(
        name = "find_callees",
        description = "6.5.2: Find all callees of a method or function. Returns structured locations with file_path, line, column, and callee_symbol.",
        parameters = ToolParameters(
            properties = mapOf(
                "symbol_name" to ToolProperty("string", "Symbol name to find callees for"),
                "file_path" to ToolProperty("string", "Optional file path hint for disambiguation"),
                "limit" to ToolProperty("integer", "Maximum number of callees to return, default 50")
            ),
            required = listOf("symbol_name")
        )
    )

    fun getInheritanceChainTool() = Tool(
        name = "get_inheritance_chain",
        description = "Get the full inheritance chain for a class (superclasses and interfaces).",
        parameters = ToolParameters(
            properties = mapOf(
                "class_name" to ToolProperty("string", "Fully qualified class name")
            ),
            required = listOf("class_name")
        )
    )

    fun semanticSearchTool() = Tool(
        name = "semantic_search",
        description = "Search for code symbols using natural language descriptions. Combines local vector semantic similarity, keyword matching, and fuzzy token indexing to find classes, methods, and fields matching the description.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty("string", "Natural language description of what you're looking for"),
                "limit" to ToolProperty("integer", "Maximum results to return, default 10")
            ),
            required = listOf("query")
        )
    )

    fun getFileSummaryTool() = Tool(
        name = "get_file_summary",
        description = "Get a structured summary of a file: classes, methods, fields, and their signatures.",
        parameters = ToolParameters(
            properties = mapOf(
                "file_path" to ToolProperty("string", "Path to the file (relative to project root)")
            ),
            required = listOf("file_path")
        )
    )

    fun getProjectStatsTool() = Tool(
        name = "get_project_stats",
        description = "Get statistics about the indexed project: total classes, methods, files, etc.",
        parameters = ToolParameters(
            properties = emptyMap(),
            required = emptyList()
        )
    )

    fun getAllTools(): List<Tool> = listOf(
        analyzeSymbolTool(),
        findUsagesTool(),
        findCallersTool(),
        findCalleesTool(),
        getInheritanceChainTool(),
        semanticSearchTool(),
        getFileSummaryTool(),
        getProjectStatsTool()
    )
}
