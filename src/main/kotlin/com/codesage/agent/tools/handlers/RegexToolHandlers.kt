package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import kotlinx.serialization.json.*

/**
 * 正则表达式工具 Handler
 */
object RegexToolHandlers {

    fun createRegexTestHandler(): ToolHandler = FunctionalToolHandler(regexTestTool()) { args ->
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'pattern' parameter")
        val text = args["text"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'text' parameter")
        val flags = args["flags"]?.jsonPrimitive?.content ?: ""

        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('d' in flags) add(RegexOption.DOT_MATCHES_ALL)
            if ('l' in flags) add(RegexOption.LITERAL)
        }

        val regex = try {
            Regex(pattern, options)
        } catch (e: Exception) {
            return@FunctionalToolHandler ToolResult.Error("Invalid regex pattern: ${e.message}")
        }

        val matchResult = regex.find(text)
        if (matchResult == null) {
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "matched" to JsonPrimitive(false),
                        "pattern" to JsonPrimitive(pattern)
                    )
                )
            )
        } else {
            val groups = matchResult.groups.mapIndexed { index, group ->
                JsonObject(
                    mapOf(
                        "index" to JsonPrimitive(index),
                        "value" to JsonPrimitive(group?.value ?: ""),
                        "range_start" to JsonPrimitive(group?.range?.first ?: -1),
                        "range_end" to JsonPrimitive(group?.range?.last?.plus(1) ?: -1)
                    )
                )
            }
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "matched" to JsonPrimitive(true),
                        "pattern" to JsonPrimitive(pattern),
                        "full_match" to JsonPrimitive(matchResult.value),
                        "groups" to JsonArray(groups)
                    )
                )
            )
        }
    }

    fun createRegexExtractHandler(): ToolHandler = FunctionalToolHandler(regexExtractTool()) { args ->
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'pattern' parameter")
        val text = args["text"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'text' parameter")
        val flags = args["flags"]?.jsonPrimitive?.content ?: ""

        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('d' in flags) add(RegexOption.DOT_MATCHES_ALL)
            if ('l' in flags) add(RegexOption.LITERAL)
        }

        val regex = try {
            Regex(pattern, options)
        } catch (e: Exception) {
            return@FunctionalToolHandler ToolResult.Error("Invalid regex pattern: ${e.message}")
        }

        val matches = regex.findAll(text).map { match ->
            val groups = (1 until match.groups.size).mapNotNull { index ->
                match.groups[index]?.let { group ->
                    JsonObject(
                        mapOf(
                            "index" to JsonPrimitive(index),
                            "value" to JsonPrimitive(group.value)
                        )
                    )
                }
            }
            JsonObject(
                mapOf(
                    "value" to JsonPrimitive(match.value),
                    "range_start" to JsonPrimitive(match.range.first),
                    "range_end" to JsonPrimitive(match.range.last + 1),
                    "groups" to JsonArray(groups)
                )
            )
        }.toList()

        ToolResult.Success(
            JsonObject(
                mapOf(
                    "pattern" to JsonPrimitive(pattern),
                    "match_count" to JsonPrimitive(matches.size),
                    "matches" to JsonArray(matches)
                )
            )
        )
    }
}
