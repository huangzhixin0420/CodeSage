package com.codesage.analysis

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.lang.reflect.Proxy

class CodeInsightExecutorTest {

    private fun createStubProject(): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "TestProject"
                "toString" -> "TestProject"
                "getBasePath" -> "/test"
                else -> null
            }
        } as Project
    }

    @Test
    fun `analyze_symbol should return symbol info from index`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/UserService.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "UserService",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = "com.example.UserService",
                    filePath = "/test/UserService.kt",
                    lineNumber = 1,
                    docComment = "Service for user operations",
                    modifiers = listOf("public", "open"),
                    superTypes = listOf("BaseService")
                ),
                PSIAnalyzer.SymbolInfo(
                    name = "getUserById",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/UserService.kt",
                    lineNumber = 5,
                    docComment = "Get user by id",
                    modifiers = listOf("public"),
                    parameters = listOf(PSIAnalyzer.ParameterInfo("id", "Long")),
                    returnType = "User"
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.analyzeSymbol(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "symbol_name" to kotlinx.serialization.json.JsonPrimitive("UserService")
                )
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data
        val jsonObj = data as kotlinx.serialization.json.JsonObject
        assertEquals("UserService", jsonObj["symbol_name"]?.jsonPrimitive?.content)
        val matches = jsonObj["matches"]?.jsonArray
        assertNotNull(matches)
        assertTrue(matches!!.size >= 1)
    }

    @Test
    fun `analyze_symbol should filter by file_path hint`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/A.kt", listOf(
                PSIAnalyzer.SymbolInfo("Foo", PSIAnalyzer.SymbolType.CLASS, null, "/test/A.kt", 1, null, emptyList())
            )
        )
        symbolIndex.updateFileSymbolsForTest(
            "/test/B.kt", listOf(
                PSIAnalyzer.SymbolInfo("Foo", PSIAnalyzer.SymbolType.CLASS, null, "/test/B.kt", 1, null, emptyList())
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.analyzeSymbol(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "symbol_name" to kotlinx.serialization.json.JsonPrimitive("Foo"),
                    "file_path" to kotlinx.serialization.json.JsonPrimitive("A.kt")
                )
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success)
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        val matches = data["matches"]?.jsonArray
        assertEquals(1, matches?.size)
        assertTrue(matches?.get(0)?.jsonObject?.get("file_path")?.jsonPrimitive?.content?.contains("A.kt") == true)
    }

    @Test
    fun `analyze_symbol should return error when symbol not found`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        val executor = CodeInsightExecutor(project, symbolIndex, null, null)

        val result = executor.analyzeSymbol(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("NonExistent"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        val error = result as com.codesage.agent.tools.ToolResult.Error
        assertTrue(error.message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `get_inheritance_chain should return full chain and implementations`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Circle.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Circle",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = "com.example.Circle",
                    filePath = "/test/Circle.kt",
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList(),
                    superTypes = listOf("Shape")
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.getInheritanceChain(
            kotlinx.serialization.json.JsonObject(
                mapOf("class_name" to kotlinx.serialization.json.JsonPrimitive("Shape"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success)
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        val implementations = data["implementations"]?.jsonArray
        assertNotNull(implementations)
        assertEquals(1, implementations?.size)
        assertEquals("Circle", implementations?.get(0)?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `semantic_search should return results for natural language query`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Calculator.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Calculator",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = null,
                    filePath = "/test/Calculator.kt",
                    lineNumber = 1,
                    docComment = "Utility for mathematical calculations",
                    modifiers = emptyList()
                ),
                PSIAnalyzer.SymbolInfo(
                    name = "add",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/Calculator.kt",
                    lineNumber = 5,
                    docComment = "Add two numbers",
                    modifiers = emptyList(),
                    parameters = listOf(PSIAnalyzer.ParameterInfo("a", "Int"), PSIAnalyzer.ParameterInfo("b", "Int")),
                    returnType = "Int"
                )
            )
        )

        val semanticSearch = SemanticSearch(project, symbolIndex)
        val executor = CodeInsightExecutor(project, symbolIndex, null, semanticSearch)

        val result = executor.semanticSearch(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "query" to kotlinx.serialization.json.JsonPrimitive("math calculation utility"),
                    "limit" to kotlinx.serialization.json.JsonPrimitive(5)
                )
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success)
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        val results = data["results"]?.jsonArray
        assertNotNull(results)
        assertTrue(results!!.isNotEmpty(), "Semantic search should return at least one result")
    }

    @Test
    fun `get_project_stats should return index statistics`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Demo.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    "Demo",
                    PSIAnalyzer.SymbolType.CLASS,
                    null,
                    "/test/Demo.kt",
                    1,
                    null,
                    emptyList()
                ),
                PSIAnalyzer.SymbolInfo(
                    "run",
                    PSIAnalyzer.SymbolType.METHOD,
                    null,
                    "/test/Demo.kt",
                    5,
                    null,
                    emptyList()
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.getProjectStats(kotlinx.serialization.json.JsonObject(emptyMap()))

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success)
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        assertEquals(2, data["total_symbols"]?.jsonPrimitive?.int)
        assertEquals(1, data["class_count"]?.jsonPrimitive?.int)
        assertEquals(1, data["method_count"]?.jsonPrimitive?.int)
        assertTrue(data["index_version"]?.jsonPrimitive?.long!! > 0)
    }

    @Test
    fun `get_file_summary should return error when file not found`() {
        val project = createStubProject()
        val executor = CodeInsightExecutor(project, null, null, null)

        val result = executor.getFileSummary(
            kotlinx.serialization.json.JsonObject(
                mapOf("file_path" to kotlinx.serialization.json.JsonPrimitive("/nonexistent/file.kt"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        val error = result as com.codesage.agent.tools.ToolResult.Error
        val msg = error.message
        assertTrue(
            msg.contains("not found", ignoreCase = true)
                    || msg.contains("No active project")
                    || msg.contains("failed", ignoreCase = true),
            "Unexpected error message: $msg"
        )
    }

    @Test
    fun `find_usages should return text references when no psi available`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Logger.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "Logger",
                    type = PSIAnalyzer.SymbolType.CLASS,
                    qualifiedName = "com.example.Logger",
                    filePath = "/test/Logger.kt",
                    lineNumber = 1,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.findUsages(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "symbol_name" to kotlinx.serialization.json.JsonPrimitive("Logger"),
                    "type" to kotlinx.serialization.json.JsonPrimitive("class")
                )
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        val refs = data["references"]?.jsonArray
        assertNotNull(refs)
        // 在 stub project 中，文本搜索找不到真实文件，但至少结构应该正确
    }

    @Test
    fun `analyze_symbol should return error when project is null`() {
        val executor = CodeInsightExecutor(null)
        val result = executor.analyzeSymbol(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("Foo"))
            )
        )
        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        assertTrue((result as com.codesage.agent.tools.ToolResult.Error).message.contains("No active project"))
    }

    @Test
    fun `get_project_stats should return error when project is null`() {
        val executor = CodeInsightExecutor(null)
        val result = executor.getProjectStats(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        assertTrue((result as com.codesage.agent.tools.ToolResult.Error).message.contains("No active project"))
    }

    @Test
    fun `analyze_symbol should include callers and callees arrays for methods`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/OrderService.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "processOrder",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/OrderService.kt",
                    lineNumber = 10,
                    docComment = null,
                    modifiers = listOf("public"),
                    parameters = emptyList(),
                    returnType = "Unit"
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.analyzeSymbol(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("processOrder"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        val matches = data["matches"]?.jsonArray
        assertNotNull(matches)
        assertEquals(1, matches!!.size)
        val first = matches[0].jsonObject
        assertTrue(first.containsKey("callers"), "Expected callers array")
        assertTrue(first.containsKey("callees"), "Expected callees array")
        assertTrue(first["callers"] is kotlinx.serialization.json.JsonArray)
        assertTrue(first["callees"] is kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun `CallGraphExtractor should extract simple callee names from Kotlin and Java call texts`() {
        assertEquals("map", CallGraphExtractor.extractCallName("list.map"))
        assertEquals("map", CallGraphExtractor.extractCallName("list.map<String>"))
        assertEquals("println", CallGraphExtractor.extractCallName("println"))
        assertEquals("doWork", CallGraphExtractor.extractCallName("service.doWork"))
        assertEquals("doWork", CallGraphExtractor.extractCallName("this.service.doWork"))
        assertNull(CallGraphExtractor.extractCallName(""))
        assertNull(CallGraphExtractor.extractCallName("1invalid"))
        assertNull(CallGraphExtractor.extractCallName("obj.1invalid"))
        assertTrue("if" in CallGraphExtractor.IGNORED_CALLEE_NAMES)
        assertTrue("println" in CallGraphExtractor.IGNORED_CALLEE_NAMES)
    }

    @Test
    fun `find_callers should return structured callers result`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Service.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "process",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/Service.kt",
                    lineNumber = 5,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.findCallers(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("process"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        assertEquals("process", data["symbol_name"]?.jsonPrimitive?.content)
        assertTrue(data.containsKey("callers"))
        assertTrue(data.containsKey("total"))
    }

    @Test
    fun `find_callees should return structured callees result`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Service.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "process",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/Service.kt",
                    lineNumber = 5,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.findCallees(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("process"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        assertEquals("process", data["symbol_name"]?.jsonPrimitive?.content)
        assertTrue(data.containsKey("callees"))
        assertTrue(data.containsKey("total"))
    }

    @Test
    fun `find_callees should return error when project is null`() {
        val executor = CodeInsightExecutor(null)
        val result = executor.findCallees(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("Foo"))
            )
        )
        assertTrue(result is com.codesage.agent.tools.ToolResult.Error)
        assertTrue((result as com.codesage.agent.tools.ToolResult.Error).message.contains("No active project"))
    }

    @Test
    fun `find_callees should return empty callees when symbol source is not resolvable`() {
        val project = createStubProject()
        val symbolIndex = SymbolIndex(project)
        symbolIndex.updateFileSymbolsForTest(
            "/test/Service.kt", listOf(
                PSIAnalyzer.SymbolInfo(
                    name = "process",
                    type = PSIAnalyzer.SymbolType.METHOD,
                    qualifiedName = null,
                    filePath = "/test/Service.kt",
                    lineNumber = 5,
                    docComment = null,
                    modifiers = emptyList()
                )
            )
        )

        val executor = CodeInsightExecutor(project, symbolIndex, null, null)
        val result = executor.findCallees(
            kotlinx.serialization.json.JsonObject(
                mapOf("symbol_name" to kotlinx.serialization.json.JsonPrimitive("process"))
            )
        )

        assertTrue(result is com.codesage.agent.tools.ToolResult.Success, "Expected Success but got $result")
        val data = (result as com.codesage.agent.tools.ToolResult.Success).data as kotlinx.serialization.json.JsonObject
        assertTrue(data.containsKey("callees"))
        assertEquals(0, data["callees"]?.jsonArray?.size, "未解析到 PSI 元素时应返回空 callee 列表")
        assertEquals(0, data["total"]?.jsonPrimitive?.int)
    }
}
