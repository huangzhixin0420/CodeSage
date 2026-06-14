package com.codesage.agent.tools

import com.codesage.agent.context.ContextBudgetManager
import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolCall
import com.codesage.model.dto.ToolParameters
import com.codesage.tools.guardrails.OutputTruncator
import com.codesage.tools.guardrails.ToolGuardrails
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 6.12.1 / 6.12.2：统一截断协议与 token 预算提示的单元测试。
 */
class ToolResultMetadataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `normalizer extracts read_file pagination metadata`() {
        val data = JsonObject(
            mapOf(
                "path" to JsonPrimitive("foo.kt"),
                "content" to JsonPrimitive("line1\nline2\nline3\n"),
                "total_lines" to JsonPrimitive(10),
                "start_line" to JsonPrimitive(0),
                "end_line" to JsonPrimitive(3)
            )
        )
        val args = JsonObject(mapOf("offset" to JsonPrimitive(0), "limit" to JsonPrimitive(3)))

        val metadata = ToolResultTruncationNormalizer.extract("read_file", ToolResult.Success(data), args)

        assertFalse(metadata.truncated) // 有 offset/limit 但未到末尾不算截断
        assertEquals(10, metadata.totalItems)
        assertEquals(3, metadata.returnedItems)
        assertEquals(3, metadata.nextOffset)
    }

    @Test
    fun `normalizer marks read_file truncated when original_length present`() {
        val data = JsonObject(
            mapOf(
                "path" to JsonPrimitive("foo.kt"),
                "content" to JsonPrimitive("short"),
                "total_lines" to JsonPrimitive(100),
                "truncated" to JsonPrimitive(true),
                "original_length" to JsonPrimitive(50000)
            )
        )
        val args = JsonObject(emptyMap())

        val metadata = ToolResultTruncationNormalizer.extract("read_file", ToolResult.Success(data), args)

        assertTrue(metadata.truncated)
        assertEquals(100, metadata.totalItems)
        assertNotNull(metadata.hint)
        assertTrue(metadata.hint!!.contains("offset/limit"))
    }

    @Test
    fun `normalizer extracts run_command truncation metadata`() {
        val data = JsonObject(
            mapOf(
                "stdout" to JsonPrimitive("a".repeat(100)),
                "stderr" to JsonPrimitive(""),
                "exit_code" to JsonPrimitive(0),
                "stdout_truncated" to JsonPrimitive(true),
                "max_output_chars" to JsonPrimitive(IDETools.MAX_COMMAND_OUTPUT_CHARS)
            )
        )
        val metadata = ToolResultTruncationNormalizer.extract(
            "run_command",
            ToolResult.Success(data),
            JsonObject(emptyMap())
        )

        assertTrue(metadata.truncated)
        assertNull(metadata.totalItems)
        assertNull(metadata.returnedItems)
        assertNotNull(metadata.hint)
        assertTrue(metadata.hint!!.contains("head"))
    }

    @Test
    fun `normalizer extracts search_code truncation metadata`() {
        val data = JsonObject(
            mapOf(
                "query" to JsonPrimitive("foo"),
                "matches" to JsonArray(List(200) { JsonObject(mapOf("file" to JsonPrimitive("f.kt"))) }),
                "total" to JsonPrimitive(200),
                "truncated" to JsonPrimitive(true),
                "max_results" to JsonPrimitive(200),
                "partial_scan_files" to JsonPrimitive(2)
            )
        )
        val metadata = ToolResultTruncationNormalizer.extract(
            "search_code",
            ToolResult.Success(data),
            JsonObject(emptyMap())
        )

        assertTrue(metadata.truncated)
        assertEquals(200, metadata.totalItems)
        assertEquals(200, metadata.returnedItems)
        assertNotNull(metadata.hint)
        assertTrue(metadata.hint!!.contains("max_results"))
        assertTrue(metadata.hint.contains("partially scanned"))
    }

    @Test
    fun `budget hints estimate tokens from result content`() {
        val content = "a".repeat(400)
        assertEquals(100, ToolResultBudgetHints.estimateTokens(content))

        val empty = ""
        assertEquals(1, ToolResultBudgetHints.estimateTokens(empty))
    }

    @Test
    fun `budget hints produce remaining context hint`() {
        val manager = ContextBudgetManager(contextLength = 10000, contextManagerProvider = { null })
        val hint = ToolResultBudgetHints.remainingHint(manager, 500)

        assertNotNull(hint)
        assertTrue(hint!!.contains("tokens left"))
        assertTrue(hint.contains("0% used"))
        assertTrue(hint.contains("500 tokens"))
    }

    @Test
    fun `guardrails postProcess preserves and updates existing metadata`() {
        val truncator = OutputTruncator(defaultMaxLength = 1000, defaultMaxLines = 200)
        val guardrails = ToolGuardrails(truncator = truncator)
        val longContent = "A\n".repeat(5000) // 5000 行，触发按行截断
        val initialMetadata = ToolResultMetadata(
            truncated = true,
            totalItems = 1000,
            returnedItems = 200,
            nextOffset = 200,
            hint = "tool hint"
        )
        val result = guardrails.postProcess(
            "read_file",
            ToolResult.Success(JsonPrimitive(longContent), initialMetadata)
        )

        assertTrue(result is ToolResult.Success)
        val success = result as ToolResult.Success
        assertNotNull(success.metadata)
        val metadata = success.metadata!!
        assertTrue(metadata.truncated)
        assertEquals(1000, metadata.totalItems)
        assertEquals(200, metadata.returnedItems)
        assertEquals(200, metadata.nextOffset)
        assertTrue(metadata.hint!!.contains("tool hint"))
        assertTrue(metadata.hint.contains("Guardrails truncated"))
    }

    @Test
    fun `ToolExecutor formats success result with truncation metadata and budget hints`() = runBlocking {
        val registry = ToolRegistry()
        registry.register(
            FunctionalToolHandler(
                Tool(
                    name = "read_file",
                    description = "mock",
                    parameters = ToolParameters(properties = emptyMap(), required = emptyList())
                )
            ) { _ ->
                ToolResult.Success(
                    JsonObject(
                        mapOf(
                            "path" to JsonPrimitive("foo.kt"),
                            "content" to JsonPrimitive("line1\nline2\n"),
                            "total_lines" to JsonPrimitive(100),
                            "start_line" to JsonPrimitive(0),
                            "end_line" to JsonPrimitive(2),
                            "truncated" to JsonPrimitive(true),
                            "original_length" to JsonPrimitive(5000)
                        )
                    )
                )
            }
        )

        val budgetManager = ContextBudgetManager(contextLength = 10000, contextManagerProvider = { null })
        val executor = ToolExecutor(
            project = null,
            toolRegistry = registry,
            contextBudgetManager = budgetManager
        )

        val toolCall = ToolCall(
            id = "1",
            name = "read_file",
            arguments = "{\"path\":\"foo.kt\"}"
        )

        val output = executor.execute(toolCall)
        val parsed = json.parseToJsonElement(output).jsonObject

        assertEquals(true, parsed["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, parsed["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(100, parsed["total_items"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, parsed["returned_items"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, parsed["next_offset"]?.jsonPrimitive?.intOrNull)
        assertNotNull(parsed["hint"])
        assertNotNull(parsed["context_cost_estimate"])
        assertNotNull(parsed["remaining_context_hint"])
        assertTrue(parsed["hint"]!!.jsonPrimitive.content.contains("offset/limit"))
    }

    @Test
    fun `ToolExecutor formats error result with budget hints only`() = runBlocking {
        val registry = ToolRegistry()
        registry.register(
            FunctionalToolHandler(
                Tool(
                    name = "mock_fail",
                    description = "mock",
                    parameters = ToolParameters(properties = emptyMap(), required = emptyList())
                )
            ) { _ ->
                ToolResult.Error("something went wrong")
            }
        )

        val budgetManager = ContextBudgetManager(contextLength = 10000, contextManagerProvider = { null })
        val executor = ToolExecutor(
            project = null,
            toolRegistry = registry,
            contextBudgetManager = budgetManager
        )

        val toolCall = ToolCall(
            id = "1",
            name = "mock_fail",
            arguments = "{}"
        )

        val output = executor.execute(toolCall)
        val parsed = json.parseToJsonElement(output).jsonObject

        assertEquals(false, parsed["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("something went wrong", parsed["error"]?.jsonPrimitive?.content)
        assertNull(parsed["truncated"])
        assertNotNull(parsed["context_cost_estimate"])
        assertNotNull(parsed["remaining_context_hint"])
    }

    @Test
    fun `guardrails truncation without tool metadata still produces normalized output`() = runBlocking {
        val truncator = OutputTruncator(defaultMaxLength = 1000, defaultMaxLines = 50)
        val guardrails = ToolGuardrails(truncator = truncator)
        val registry = ToolRegistry()
        registry.register(
            FunctionalToolHandler(
                Tool(
                    name = "read_file",
                    description = "mock",
                    parameters = ToolParameters(properties = emptyMap(), required = emptyList())
                )
            ) { _ ->
                ToolResult.Success(JsonPrimitive("A\n".repeat(5000)))
            }
        )

        val executor = ToolExecutor(
            project = null,
            guardrails = guardrails,
            toolRegistry = registry
        )

        val toolCall = ToolCall(id = "1", name = "read_file", arguments = "{}")
        val output = executor.execute(toolCall)
        val parsed = json.parseToJsonElement(output).jsonObject

        assertEquals(true, parsed["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, parsed["truncated"]?.jsonPrimitive?.booleanOrNull)
        assertNotNull(parsed["hint"])
        assertTrue(parsed["hint"]!!.jsonPrimitive.content.contains("Guardrails truncated"))
    }
}
