package com.codesage.analysis

import com.codesage.agent.tools.ToolResult
import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets

/**
 * 代码洞察工具执行器
 * 将 PSI 分析能力封装为 ToolResult 返回格式，供 ToolExecutor 调用。
 *
 * 设计约束：
 * - 所有 PSI 操作必须在 read action 中执行。
 * - project 为 null 时返回友好错误，不自发抛异常。
 * - 每个工具独立处理超时/降级逻辑。
 */
class CodeInsightExecutor(
    private val project: Project?,
    symbolIndexOverride: SymbolIndex? = null,
    psiAnalyzerOverride: PSIAnalyzer? = null,
    semanticSearchOverride: SemanticSearch? = null
) {
    private val logger = Logger.getLogger<CodeInsightExecutor>()

    private val symbolIndex: SymbolIndex? = symbolIndexOverride ?: project?.let { SymbolIndex(it) }
    private val psiAnalyzer: PSIAnalyzer? = psiAnalyzerOverride ?: project?.let { PSIAnalyzer(it) }
    private val semanticSearch: SemanticSearch? =
        semanticSearchOverride ?: project?.let { SemanticSearch(it, symbolIndex ?: SymbolIndex(it)) }

    //region analyze_symbol

    fun analyzeSymbol(args: JsonObject): ToolResult {
        val symbolName = args["symbol_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'symbol_name' parameter")
        val filePathHint = args["file_path"]?.jsonPrimitive?.content

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                // 1. 优先从 SymbolIndex 查找（已有索引，快）
                val indexed = symbolIndex?.findByName(symbolName) ?: emptyList()

                // 2. 如果索引为空或结果不够详细，用 PSIAnalyzer 补全
                val psiResult = if (symbolName.contains(".")) {
                    psiAnalyzer?.findClass(symbolName)
                } else null

                // 合并结果
                val allSymbols = when {
                    indexed.isNotEmpty() && psiResult != null -> {
                        // 去重：以索引结果为主，PSI 结果补充 qualifiedName 等字段
                        val merged = indexed.map { idx ->
                            if (idx.name == psiResult.name) idx.copy(
                                qualifiedName = idx.qualifiedName ?: psiResult.qualifiedName,
                                docComment = idx.docComment ?: psiResult.docComment,
                                modifiers = if (idx.modifiers.isEmpty()) psiResult.modifiers else idx.modifiers
                            ) else idx
                        }
                        merged
                    }

                    indexed.isNotEmpty() -> indexed
                    psiResult != null -> listOf(psiResult)
                    else -> emptyList()
                }

                if (allSymbols.isEmpty()) {
                    return@runInReadAction ToolResult.Error("Symbol not found: $symbolName")
                }

                // 如果提供了 file_path 提示，进行过滤
                val filtered = if (filePathHint != null) {
                    allSymbols.filter { it.filePath.contains(filePathHint) }
                } else allSymbols

                val targetSymbols = filtered.ifEmpty { allSymbols }

                val jsonArray = targetSymbols.map { it.toJson() }
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "symbol_name" to JsonPrimitive(symbolName),
                            "matches" to JsonArray(jsonArray),
                            "count" to JsonPrimitive(jsonArray.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("analyze_symbol failed: $symbolName", e)
                ToolResult.Error("Symbol analysis failed: ${e.message}")
            }
        }
    }

    //endregion

    //region find_usages

    fun findUsages(args: JsonObject): ToolResult {
        val symbolName = args["symbol_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'symbol_name' parameter")
        val typeHint = args["type"]?.jsonPrimitive?.content

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                val references = mutableListOf<JsonObject>()

                // 1. 尝试使用 ReferencesSearch 进行语义级引用查找
                val psiReferences = findPsiReferences(symbolName, typeHint)
                references.addAll(psiReferences)

                // 2. 如果语义搜索未找到或结果太少，降级到文本搜索
                if (references.isEmpty()) {
                    val textMatches = findTextReferences(symbolName)
                    references.addAll(textMatches)
                }

                // 去重（按文件路径+行号）
                val uniqueRefs = references
                    .distinctBy { it["file_path"]?.jsonPrimitive?.content + "@" + it["line"]?.jsonPrimitive?.int }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "symbol_name" to JsonPrimitive(symbolName),
                            "type" to JsonPrimitive(typeHint ?: "unknown"),
                            "references" to JsonArray(uniqueRefs),
                            "total" to JsonPrimitive(uniqueRefs.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("find_usages failed: $symbolName", e)
                ToolResult.Error("Usage search failed: ${e.message}")
            }
        }
    }

    /**
     * 使用 ReferencesSearch（反射）查找符号引用
     */
    private fun findPsiReferences(symbolName: String, typeHint: String?): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        try {
            val scopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
            val scope = scopeClass.getMethod("projectScope", Project::class.java).invoke(null, project)

            // 查找 PsiElement
            val psiElement = when (typeHint) {
                "class" -> findPsiClass(symbolName, scope)
                "method", "field" -> findPsiMember(symbolName, scope)
                else -> findPsiClass(symbolName, scope) ?: findPsiMember(symbolName, scope)
            } ?: return emptyList()

            // 反射调用 ReferencesSearch.search(psiElement, scope)
            val refSearchClass = Class.forName("com.intellij.psi.search.searches.ReferencesSearch")
            val searchInstance =
                refSearchClass.getMethod("search", Class.forName("com.intellij.psi.PsiElement"), scopeClass)
                    .invoke(null, psiElement, scope)

            // 遍历查询结果
            val findAllMethod = searchInstance.javaClass.methods.find { it.name == "findAll" }
            val allRefs = findAllMethod?.invoke(searchInstance) as? Collection<*>

            allRefs?.forEach { ref ->
                val element = ref?.javaClass?.getMethod("getElement")?.invoke(ref) as? com.intellij.psi.PsiElement
                val file = element?.containingFile?.virtualFile
                val line = element?.let { getLineNumber(it) } ?: 0
                val text = element?.text?.take(200) ?: ""

                if (file != null) {
                    results.add(
                        JsonObject(
                            mapOf(
                                "file_path" to JsonPrimitive(file.path),
                                "line" to JsonPrimitive(line),
                                "text" to JsonPrimitive(text),
                                "type" to JsonPrimitive("reference")
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug("ReferencesSearch unavailable for $symbolName, falling back to text search", e)
        }
        return results
    }

    private fun findPsiClass(className: String, scope: Any?): Any? {
        return try {
            val facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            facadeClass.getMethod("findClass", String::class.java, scope?.javaClass)
                .invoke(facade, className, scope)
        } catch (e: Exception) {
            null
        }
    }

    private fun findPsiMember(symbolName: String, scope: Any?): Any? {
        // 尝试从 SymbolIndex 获取定义位置，再定位 PsiElement
        val symbols = symbolIndex?.findByName(symbolName) ?: emptyList()
        for (symbol in symbols) {
            val vf = LocalFileSystem.getInstance().findFileByPath(symbol.filePath) ?: continue
            val psiFile = PsiManager.getInstance(project!!).findFile(vf) ?: continue
            val found = findNamedElementInFile(psiFile, symbolName)
            if (found != null) return found
        }
        return null
    }

    private fun findNamedElementInFile(psiFile: com.intellij.psi.PsiFile, name: String): com.intellij.psi.PsiElement? {
        var result: com.intellij.psi.PsiElement? = null
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is com.intellij.psi.PsiNamedElement && element.name == name) {
                    result = element
                    return
                }
                super.visitElement(element)
            }
        })
        return result
    }

    /**
     * 降级：文本级引用搜索（遍历项目文件内容）
     */
    private fun findTextReferences(symbolName: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        try {
            val basePath = project?.basePath ?: return emptyList()
            val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
            val regex = Regex("""(?<!\w)${Regex.escape(symbolName)}(?!\w)""")
            searchVirtualFileForRefs(root, regex, results, 0, 100)
        } catch (e: Exception) {
            logger.debug("Text reference search failed for $symbolName", e)
        }
        return results
    }

    private fun searchVirtualFileForRefs(
        file: com.intellij.openapi.vfs.VirtualFile,
        regex: Regex,
        results: MutableList<JsonObject>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        if (file.name.startsWith(".")) return
        if (file.name in setOf("node_modules", "build", ".gradle", "target", "__pycache__", ".idea")) return

        if (file.isDirectory) {
            file.children?.forEach { child ->
                searchVirtualFileForRefs(child, regex, results, depth + 1, maxDepth)
            }
        } else {
            try {
                val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
                val lines = content.lines()
                lines.forEachIndexed { index, line ->
                    regex.findAll(line).forEach {
                        results.add(
                            JsonObject(
                                mapOf(
                                    "file_path" to JsonPrimitive(file.path),
                                    "line" to JsonPrimitive(index + 1),
                                    "text" to JsonPrimitive(line.trim().take(200)),
                                    "type" to JsonPrimitive("text_match")
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // 跳过二进制文件等
            }
        }
    }

    //endregion

    //region get_inheritance_chain

    fun getInheritanceChain(args: JsonObject): ToolResult {
        val className = args["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'class_name' parameter")

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                // 1. 获取继承链（父类、接口）
                val superChain = psiAnalyzer?.getInheritanceChain(className) ?: emptyList()

                // 2. 获取实现/子类（反向索引）
                val implementations = symbolIndex?.findImplementations(className) ?: emptyList()
                val subClasses = implementations.map {
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(it.name),
                            "qualified_name" to JsonPrimitive(it.qualifiedName),
                            "file_path" to JsonPrimitive(it.filePath),
                            "line" to JsonPrimitive(it.lineNumber)
                        )
                    )
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "class_name" to JsonPrimitive(className),
                            "inheritance_chain" to JsonArray(superChain.map { JsonPrimitive(it) }),
                            "implementations" to JsonArray(subClasses),
                            "implementation_count" to JsonPrimitive(subClasses.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("get_inheritance_chain failed: $className", e)
                ToolResult.Error("Inheritance chain query failed: ${e.message}")
            }
        }
    }

    //endregion

    //region semantic_search

    fun semanticSearch(args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'query' parameter")
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 10

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return try {
            val results = semanticSearch?.semanticQuery(query, limit) ?: emptyList()

            if (results.isEmpty()) {
                // 降级：尝试普通 search
                val fallback = semanticSearch?.search(query, limit) ?: emptyList()
                return ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "query" to JsonPrimitive(query),
                            "results" to JsonArray(fallback.map { it.toJson() }),
                            "count" to JsonPrimitive(fallback.size),
                            "match_type" to JsonPrimitive("fallback_search")
                        )
                    )
                )
            }

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "query" to JsonPrimitive(query),
                        "results" to JsonArray(results.map { it.toJson() }),
                        "count" to JsonPrimitive(results.size),
                        "match_type" to JsonPrimitive("semantic")
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("semantic_search failed: $query", e)
            ToolResult.Error("Semantic search failed: ${e.message}")
        }
    }

    //endregion

    //region get_file_summary

    fun getFileSummary(args: JsonObject): ToolResult {
        val filePath = args["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'file_path' parameter")

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                val resolvedPath = if (!java.io.File(filePath).isAbsolute && project.basePath != null) {
                    java.io.File(project.basePath, filePath).canonicalPath
                } else filePath

                val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: return@runInReadAction ToolResult.Error("File not found: $filePath")

                val summary = psiAnalyzer?.getFileSummary(virtualFile)
                    ?: return@runInReadAction ToolResult.Error("Failed to analyze file: $filePath")

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "file_path" to JsonPrimitive(summary.filePath),
                            "classes" to JsonArray(summary.classes.map { it.toJson() }),
                            "methods" to JsonArray(summary.methods.map { it.toJson() }),
                            "fields" to JsonArray(summary.fields.map { it.toJson() }),
                            "total_symbols" to JsonPrimitive(summary.totalSymbols)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("get_file_summary failed: $filePath", e)
                ToolResult.Error("File summary failed: ${e.message}")
            }
        }
    }

    //endregion

    //region get_project_stats

    fun getProjectStats(args: JsonObject): ToolResult {
        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return try {
            val stats = symbolIndex?.getStats()
                ?: return ToolResult.Error("Symbol index not initialized")

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "total_symbols" to JsonPrimitive(stats.totalSymbols),
                        "unique_names" to JsonPrimitive(stats.uniqueNames),
                        "indexed_files" to JsonPrimitive(stats.indexedFiles),
                        "class_count" to JsonPrimitive(stats.classCount),
                        "method_count" to JsonPrimitive(stats.methodCount),
                        "field_count" to JsonPrimitive(stats.fieldCount),
                        "cache_hit_rate" to JsonPrimitive(stats.cacheHitRate),
                        "index_version" to JsonPrimitive(stats.indexVersion)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("get_project_stats failed", e)
            ToolResult.Error("Project stats failed: ${e.message}")
        }
    }

    //endregion

    //region helpers

    private fun PSIAnalyzer.SymbolInfo.toJson(): JsonObject {
        return JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "type" to JsonPrimitive(type.name.lowercase()),
                "qualified_name" to JsonPrimitive(qualifiedName),
                "file_path" to JsonPrimitive(filePath),
                "line" to JsonPrimitive(lineNumber),
                "doc_comment" to JsonPrimitive(docComment),
                "modifiers" to JsonArray(modifiers.map { JsonPrimitive(it) }),
                "return_type" to JsonPrimitive(returnType),
                "parameters" to JsonArray(parameters.map {
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(it.name),
                            "type" to JsonPrimitive(it.type)
                        )
                    )
                }),
                "super_types" to JsonArray(superTypes.map { JsonPrimitive(it) })
            )
        )
    }

    private fun SemanticSearch.SearchResult.toJson(): JsonObject {
        return JsonObject(
            mapOf(
                "file_path" to JsonPrimitive(filePath),
                "match_type" to JsonPrimitive(matchType.name.lowercase()),
                "relevance_score" to JsonPrimitive(relevanceScore),
                "symbol" to (symbol?.toJson() ?: JsonNull),
                "context" to JsonArray(contextLines.map { JsonPrimitive(it) })
            )
        )
    }

    private fun getLineNumber(element: com.intellij.psi.PsiElement): Int {
        val project = element.project
        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        return document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
    }

    /**
     * 兼容测试环境的 read action 包装。
     * 当 ApplicationManager.getApplication() 为 null（无平台环境）时直接执行块。
     */
    private fun <T> runInReadAction(block: () -> T): T {
        val app = ApplicationManager.getApplication()
        return if (app != null) {
            app.runReadAction(Computable { block() })
        } else {
            block()
        }
    }

    //endregion
}
