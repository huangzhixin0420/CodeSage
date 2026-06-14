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

    init {
        // 项目打开 / 工具实例化时 fire-and-forget 预热符号索引, 让 LLM
        // 第一次调 get_project_stats / search_symbol 等时能拿到非 0 数据
        // (SymbolIndex.getStats() 自身也有 3s 阻塞等兜底)。
        symbolIndex?.buildIndex()
    }

    //region analyze_symbol

    /**
     * T5.3 增强：analyze_symbol 返回结构化字段
     *
     * 附加字段（基于已有 SymbolInfo）：
     * - `complexity`: 圈复杂度近似（节点 + 1）
     * - `parameter_count`: 参数数量
     * - `callers`: 调用此符号的位置列表（最多 50 个）
     * - `callees`: 此符号调用的位置列表（最多 50 个）
     * - `doc_status`: DOCUMENTED / PARTIAL / MISSING
     * - `visibility`: PUBLIC / INTERNAL / PRIVATE
     */
    fun analyzeSymbol(args: JsonObject): ToolResult {
        val symbolName = args["symbol_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'symbol_name' parameter")
        val filePathHint = args["file_path"]?.jsonPrimitive?.content
        val includeCallGraph = args["include_call_graph"]?.jsonPrimitive?.content?.toBoolean() ?: true

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

                // T5.3 增强：补充结构化字段
                val jsonArray = targetSymbols.map { sym -> enrichSymbolJson(sym, includeCallGraph) }
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

    /**
     * T5.3：为 symbol 输出添加结构化字段
     */
    private fun enrichSymbolJson(sym: PSIAnalyzer.SymbolInfo, includeCallGraph: Boolean): JsonObject {
        val baseMap = sym.toJsonJsonObject()
        // 圈复杂度近似：parameters.count + 1（启发式；METHOD 类型才有意义）
        val complejidad = if (sym.type == PSIAnalyzer.SymbolType.METHOD) {
            // 启发式：参数越多 + 嵌套越深，复杂度越高
            // 这里用简化公式：1 + parameters + modifiers中含 "suspend"/"operator" 等
            1 + sym.parameters.size + sym.modifiers.count { it in setOf("suspend", "operator", "inline") }
        } else {
            1
        }
        // 文档状态
        val docStatus = when {
            sym.docComment == null -> "MISSING"
            sym.docComment.length < 20 -> "PARTIAL"
            else -> "DOCUMENTED"
        }
        // 可见性
        val visibility = when {
            "public" in sym.modifiers || sym.modifiers.isEmpty() -> "PUBLIC"
            "private" in sym.modifiers -> "PRIVATE"
            "internal" in sym.modifiers -> "INTERNAL"
            "protected" in sym.modifiers -> "PROTECTED"
            else -> "UNKNOWN"
        }
        // 调用者
        val callers = if (includeCallGraph) findPsiReferences(sym.name, "method") + findPsiReferences(sym.name, "class")
        else emptyList()
        val callees = if (includeCallGraph) collectCalleesForSymbol(sym)
        else emptyList()

        val extras = mutableMapOf<String, JsonElement>(
            "complexity" to JsonPrimitive(complejidad),
            "parameter_count" to JsonPrimitive(sym.parameters.size),
            "doc_status" to JsonPrimitive(docStatus),
            "visibility" to JsonPrimitive(visibility)
        )
        if (includeCallGraph) {
            extras["callers"] = JsonArray(callers.take(50))
            extras["callees"] = JsonArray(callees.take(50))
        }
        // 合并到 baseMap
        val merged = baseMap.toMutableMap()
        merged.putAll(extras)
        return JsonObject(merged)
    }

    /**
     * T5.3 辅助：把 SymbolInfo 转 JsonObject（之前 toJson 转 JsonElement）
     */
    private fun PSIAnalyzer.SymbolInfo.toJsonJsonObject(): Map<String, JsonElement> = mapOf(
        "name" to JsonPrimitive(name),
        "type" to JsonPrimitive(type.name),
        "qualified_name" to JsonPrimitive(qualifiedName ?: ""),
        "file_path" to JsonPrimitive(filePath),
        "line_number" to JsonPrimitive(lineNumber),
        "doc_comment" to JsonPrimitive(docComment ?: ""),
        "modifiers" to JsonArray(modifiers.map { JsonPrimitive(it) }),
        "parameters" to JsonArray(parameters.map {
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(it.name),
                    "type" to JsonPrimitive(it.type),
                    "default_value" to JsonPrimitive(it.defaultValue ?: "")
                )
            )
        }),
        "return_type" to JsonPrimitive(returnType ?: ""),
        "super_types" to JsonArray(superTypes.map { JsonPrimitive(it) })
    )

    /**
     * 6.5.1 / 6.5.2：使用 PSI 遍历方法体，精确提取该符号调用的所有 callee。
     * 替代原先基于正则的启发式扫描（避免把 if/for/println 等误判为调用目标，
     * 并能正确处理扩展函数、带接收者调用、重载等场景）。
     */
    private fun collectCalleesForSymbol(sym: PSIAnalyzer.SymbolInfo): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        try {
            val element = findPsiElement(sym.name, filePathHint = sym.filePath, typeHint = "method")
                ?: return emptyList()

            val seen = mutableSetOf<String>()
            element.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
                override fun visitElement(child: com.intellij.psi.PsiElement) {
                    val callee = CallGraphExtractor.extractCalleeName(child)
                    if (callee != null && callee != sym.name && callee !in CallGraphExtractor.IGNORED_CALLEE_NAMES) {
                        val file = child.containingFile?.virtualFile
                        val path = file?.path ?: sym.filePath
                        val line = getLineNumber(child)
                        val key = "$path:$line:$callee"
                        if (seen.add(key)) {
                            results.add(
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive(callee),
                                        "file_path" to JsonPrimitive(path),
                                        "line" to JsonPrimitive(line),
                                        "column" to JsonPrimitive(getColumn(child)),
                                        "type" to JsonPrimitive("call")
                                    )
                                )
                            )
                        }
                    }
                    super.visitElement(child)
                }
            })
        } catch (e: Exception) {
            logger.debug("findCallees failed for ${sym.name}: ${e.message}")
        }
        return results
    }

    /**
     * 在项目中定位与 symbol 定义对应的 PsiElement。
     * 优先根据 typeHint 匹配 class/method/field 等 PSI 类型，提高后续
     * ReferencesSearch / 调用图分析的准确性。
     */
    private fun findPsiElement(
        symbolName: String,
        filePathHint: String? = null,
        typeHint: String? = null
    ): com.intellij.psi.PsiElement? {
        val symbols = symbolIndex?.findByName(symbolName) ?: emptyList()
        val candidates = if (filePathHint != null) {
            symbols.filter { it.filePath.contains(filePathHint) }
        } else symbols

        for (symbol in candidates.ifEmpty { symbols }) {
            val vf = LocalFileSystem.getInstance().findFileByPath(symbol.filePath) ?: continue
            val psiFile = PsiManager.getInstance(project!!).findFile(vf) ?: continue
            val found = findNamedElementInFile(psiFile, symbolName, typeHint)
            if (found != null) return found
        }
        return null
    }

    /**
     * 在 PsiFile 中查找指定名称的元素；若给出 typeHint，优先返回类型匹配的候选。
     */
    private fun findNamedElementInFile(
        psiFile: com.intellij.psi.PsiFile,
        name: String,
        typeHint: String? = null
    ): com.intellij.psi.PsiElement? {
        val matches = mutableListOf<com.intellij.psi.PsiElement>()
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if ((element as? com.intellij.psi.PsiNamedElement)?.name == name) {
                    matches.add(element)
                    if (typeHint != null && isTypeHintMatch(element, typeHint)) {
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        return if (typeHint != null) {
            matches.find { isTypeHintMatch(it, typeHint) } ?: matches.firstOrNull()
        } else {
            matches.firstOrNull()
        }
    }

    /**
     * 判断 PSI 元素是否符合 typeHint 暗示的类型（class / method / field / property）。
     * 使用 fully-qualified class name 匹配，避免测试 classpath 中缺少具体 PSI 类。
     */
    private fun isTypeHintMatch(element: com.intellij.psi.PsiElement, typeHint: String): Boolean {
        val qName = element::class.qualifiedName ?: return false
        return when (typeHint) {
            "class" -> qName.endsWith(".PsiClass") || qName.endsWith(".KtClass") || qName.contains("ClassImpl")
            "method" -> qName.endsWith(".PsiMethod") ||
                    qName.endsWith(".KtNamedFunction") ||
                    qName.contains("MethodImpl") ||
                    qName.contains("Function")

            "field", "property" -> qName.endsWith(".PsiField") ||
                    qName.endsWith(".KtProperty") ||
                    qName.contains("FieldImpl")

            else -> false
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
     * 6.5.2：查找调用/引用目标符号的所有位置（callers）。
     */
    fun findCallers(args: JsonObject): ToolResult {
        val symbolName = args["symbol_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'symbol_name' parameter")
        val filePathHint = args["file_path"]?.jsonPrimitive?.content
        val typeHint = args["type"]?.jsonPrimitive?.content
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 50

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                var locations = findReferenceLocations(symbolName, typeHint)
                if (filePathHint != null) {
                    locations = locations.filter { it.filePath.contains(filePathHint) }
                }

                val callers = locations.take(limit).map { loc ->
                    JsonObject(
                        mapOf(
                            "file_path" to JsonPrimitive(loc.filePath),
                            "line" to JsonPrimitive(loc.line),
                            "column" to JsonPrimitive(getColumn(loc.element)),
                            "caller_symbol" to JsonPrimitive(getContainingSymbolName(loc.element) ?: ""),
                            "reference_type" to JsonPrimitive(loc.referenceType)
                        )
                    )
                }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "symbol_name" to JsonPrimitive(symbolName),
                            "callers" to JsonArray(callers),
                            "total" to JsonPrimitive(callers.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("find_callers failed: $symbolName", e)
                ToolResult.Error("Find callers failed: ${e.message}")
            }
        }
    }

    /**
     * 6.5.2：查找目标符号调用的所有 callee。
     */
    fun findCallees(args: JsonObject): ToolResult {
        val symbolName = args["symbol_name"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing 'symbol_name' parameter")
        val filePathHint = args["file_path"]?.jsonPrimitive?.content
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 50

        if (project == null) {
            return ToolResult.Error("No active project")
        }

        return runInReadAction {
            try {
                var targets = symbolIndex?.findByName(symbolName) ?: emptyList()
                if (filePathHint != null) {
                    targets = targets.filter { it.filePath.contains(filePathHint) }
                }

                val allCallees = targets
                    .flatMap { collectCalleesForSymbol(it) }
                    .distinctBy {
                        "${it["file_path"]?.jsonPrimitive?.content}:" +
                                "${it["line"]?.jsonPrimitive?.int}:" +
                                "${it["name"]?.jsonPrimitive?.content}"
                    }
                    .take(limit)
                    .map { cal ->
                        JsonObject(
                            mapOf(
                                "file_path" to cal["file_path"]!!,
                                "line" to cal["line"]!!,
                                "column" to cal["column"]!!,
                                "callee_symbol" to cal["name"]!!,
                                "call_type" to cal["type"]!!
                            )
                        )
                    }

                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "symbol_name" to JsonPrimitive(symbolName),
                            "callees" to JsonArray(allCallees),
                            "total" to JsonPrimitive(allCallees.size)
                        )
                    )
                )
            } catch (e: Exception) {
                logger.error("find_callees failed: $symbolName", e)
                ToolResult.Error("Find callees failed: ${e.message}")
            }
        }
    }

    /**
     * 内部数据结构：保存一次 PSI 引用的元素与位置信息。
     */
    private data class ReferenceLocation(
        val element: com.intellij.psi.PsiElement,
        val filePath: String,
        val line: Int,
        val text: String,
        val referenceType: String
    )

    /**
     * 使用 ReferencesSearch（反射）查找符号引用，返回带 PsiElement 的位置列表。
     */
    private fun findReferenceLocations(symbolName: String, typeHint: String?): List<ReferenceLocation> {
        val results = mutableListOf<ReferenceLocation>()
        try {
            val scopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
            val scope = scopeClass.getMethod("projectScope", Project::class.java).invoke(null, project)

            // 查找 PsiElement
            val psiElement = when (typeHint) {
                "class" -> findPsiClass(symbolName, scope)
                "method", "field", "property" -> findPsiElement(symbolName, typeHint = typeHint)
                else -> findPsiClass(symbolName, scope) ?: findPsiElement(symbolName, typeHint = typeHint)
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

                if (file != null && element != null) {
                    results.add(
                        ReferenceLocation(
                            element = element,
                            filePath = file.path,
                            line = line,
                            text = text,
                            referenceType = "reference"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug("ReferencesSearch unavailable for $symbolName", e)
        }
        return results
    }

    /**
     * 兼容旧 find_usages 的 JsonObject 返回格式。
     */
    private fun findPsiReferences(symbolName: String, typeHint: String?): List<JsonObject> {
        return findReferenceLocations(symbolName, typeHint).map { loc ->
            JsonObject(
                mapOf(
                    "file_path" to JsonPrimitive(loc.filePath),
                    "line" to JsonPrimitive(loc.line),
                    "text" to JsonPrimitive(loc.text),
                    "type" to JsonPrimitive(loc.referenceType)
                )
            )
        }
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
     * 6.5.2：计算元素在所在行中的列号（1-based）。
     */
    private fun getColumn(element: com.intellij.psi.PsiElement): Int {
        return try {
            val project = element.project
            val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
            if (document != null) {
                val line = document.getLineNumber(element.textOffset)
                element.textOffset - document.getLineStartOffset(line) + 1
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 6.5.2：向上查找包含该引用的方法/函数/类符号名。
     */
    private fun getContainingSymbolName(element: com.intellij.psi.PsiElement): String? {
        var current = element.parent
        while (current != null) {
            val qName = current::class.qualifiedName ?: ""
            val named = current as? com.intellij.psi.PsiNamedElement
            if (named?.name != null && isCallableOrClassLike(qName)) {
                return named.name
            }
            current = current.parent
        }
        return null
    }

    private fun isCallableOrClassLike(qName: String): Boolean {
        return qName.endsWith(".PsiMethod") ||
                qName.endsWith(".KtNamedFunction") ||
                qName.contains("MethodImpl") ||
                qName.contains("Function") ||
                qName.endsWith(".PsiClass") ||
                qName.endsWith(".KtClass") ||
                qName.contains("ClassImpl")
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
