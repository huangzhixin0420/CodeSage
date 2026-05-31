package com.codesage.agent.tools

import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.skill.Skill
import com.codesage.skill.SkillInput
import com.codesage.skill.SkillResult
import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * Skill 到 AI Tool 的适配器
 * 将 SkillRegistry 中的技能动态转换为 OpenAI Function Calling 格式的工具
 */
class SkillToolAdapter(
    private val skillRegistry: SkillRegistry,
    private val skillExecutor: SkillExecutor,
    private val project: Project? = null
) {
    /**
     * 将所有技能转换为 Tool 定义列表
     */
    fun toTools(): List<Tool> {
        return skillRegistry.getAll().map { skill ->
            Tool(
                name = sanitizeToolName(skill.id),
                description = "[Skill] ${skill.name}: ${skill.description}",
                parameters = convertSchema(skill.inputSchema)
            )
        }
    }

    /**
     * 执行技能调用
     */
    suspend fun execute(toolName: String, arguments: String): String {
        val skillId = toolName.replace("skill_", "")
        val skill = skillRegistry.get(skillId)
            ?: return jsonError("Skill not found: $skillId")

        return try {
            val argsMap = jsonObjectToMap(Json.parseToJsonElement(arguments).jsonObject)
            val input = SkillInput(argsMap)
            val context = com.codesage.skill.ExecutionContext(
                projectPath = project?.basePath,
                currentFile = project?.let { /* 可扩展：获取当前编辑器打开的文件 */ null },
                sessionId = null, // 可从调用方传入
                metadata = mapOf("source" to "ai_tool_call")
            )

            when (val result = skillExecutor.execute(skillId, input, context)) {
                is SkillResult.Success -> {
                    Json.encodeToString(
                        JsonObject.serializer(), JsonObject(
                            mapOf(
                                "success" to JsonPrimitive(true),
                                "output" to Json.encodeToJsonElement(result.output)
                            )
                        )
                    )
                }

                is SkillResult.Failure -> {
                    jsonError(result.error)
                }
            }
        } catch (e: Exception) {
            jsonError("Skill execution failed: ${e.message}")
        }
    }

    private fun sanitizeToolName(skillId: String): String {
        // OpenAI tool names must match ^[a-zA-Z0-9_-]{1,64}$
        val sanitized = skillId.replace(".", "_").replace(" ", "_")
        return if (sanitized.startsWith("builtin_")) "skill_$sanitized" else "skill_$sanitized"
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertSchema(inputSchema: Map<String, Any>): ToolParameters {
        val properties = mutableMapOf<String, ToolProperty>()
        val required = mutableListOf<String>()

        for ((key, value) in inputSchema) {
            if (value is Map<*, *>) {
                val propMap = value as Map<String, Any>
                val type = propMap["type"] as? String ?: "string"
                val description = propMap["description"] as? String
                val enumValues = (propMap["enum"] as? List<*>)?.map { it.toString() }

                properties[key] = ToolProperty(
                    type = type,
                    description = description,
                    enum = enumValues
                )

                // 默认将没有 default 的简单参数视为 required
                if (propMap.containsKey("required") && propMap["required"] == true) {
                    required.add(key)
                }
            }
        }

        return ToolParameters(
            type = "object",
            properties = properties,
            required = required
        )
    }

    private fun jsonError(message: String): String {
        return Json.encodeToString(
            JsonObject.serializer(), JsonObject(
                mapOf(
                    "success" to JsonPrimitive(false),
                    "error" to JsonPrimitive(message)
                )
            )
        )
    }

    /**
     * 将 JsonObject 递归转换为普通的 Map<String, Any>
     */
    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
        return jsonObject.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> {
                    when {
                        value.isString -> value.content
                        value.booleanOrNull != null -> value.boolean
                        value.intOrNull != null -> value.int
                        value.longOrNull != null -> value.long
                        value.doubleOrNull != null -> value.double
                        else -> value.content
                    }
                }

                is JsonObject -> jsonObjectToMap(value)
                is JsonArray -> value.map { element ->
                    when (element) {
                        is JsonPrimitive -> element.content
                        is JsonObject -> jsonObjectToMap(element)
                        else -> element.toString()
                    }
                }

                else -> value.toString()
            }
        }
    }
}
