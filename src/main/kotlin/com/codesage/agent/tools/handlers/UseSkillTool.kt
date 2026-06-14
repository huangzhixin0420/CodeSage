package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolCategory
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.skill.ExecutionContext
import com.codesage.skill.SkillInput
import com.codesage.skill.SkillResult
import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*

/**
 * 6.11.3 Skill 元工具：统一通过 `use_skill(skill_id, arguments)` 调用任意已注册技能。
 *
 * 该工具将动态 skill 的调用入口收敛为一个固定的 LLM 可见工具，避免技能数量增多时
 * 工具列表被大量 `skill_*` 工具污染。实际执行仍委托给 [SkillExecutor]。
 *
 * @param skillRegistry 技能注册表，用于构造可用 skill_id 枚举并查找技能。
 * @param skillExecutor 技能执行器，负责协程调度与异常处理。
 * @param project 当前 IntelliJ 项目上下文，为 null 时可在测试环境使用。
 */
class UseSkillTool(
    private val skillRegistry: SkillRegistry,
    private val skillExecutor: SkillExecutor,
    private val project: Project? = null
) : UnifiedTool(
    name = "use_skill",
    description = """
        Summary: 调用任意已注册的 Skill，统一替代直接调用大量 `skill_*` 工具。
        Args:
          - skill_id (string, required): 要执行的技能 ID。
          - arguments (object, optional): 传给技能的参数，按技能 schema 填写。
        Use: 当需要执行某个 Skill 但又不想在工具列表里看到单独的 `skill_<id>` 时使用。
        Returns: 技能执行结果，success=true 时 output 为技能输出，否则 error 携带错误信息。
    """.trimIndent(),
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "skill_id" to ToolProperty(
                type = "string",
                description = "要执行的技能 ID",
                enum = skillRegistry.getAllIds().sorted()
            ),
            "arguments" to ToolProperty(
                type = "object",
                description = "传给技能的参数对象"
            )
        ),
        required = listOf("skill_id")
    )
) {

    override suspend fun execute(args: JsonObject): ToolResult {
        val skillId = args["skill_id"]?.jsonPrimitive?.content
            ?: return ToolResult.Error("Missing required parameter: skill_id")

        val skill = skillRegistry.get(skillId)
            ?: return ToolResult.Error("Skill not found: $skillId")

        val arguments = args["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val input = SkillInput(jsonObjectToMap(arguments))
        val context = ExecutionContext(
            projectPath = project?.basePath,
            currentFile = project?.let { null },
            sessionId = null,
            metadata = mapOf("source" to "use_skill")
        )

        return when (val result = skillExecutor.execute(skillId, input, context)) {
            is SkillResult.Success -> ToolResult.Success(
                JsonObject(
                    mapOf(
                        "success" to JsonPrimitive(true),
                        "output" to mapToJsonElement(result.output)
                    )
                )
            )

            is SkillResult.Failure -> ToolResult.Error(result.error)
        }
    }

    companion object {
        /**
         * 将 [JsonObject] 转换为普通 Map<String, Any>，供 [SkillInput] 使用。
         */
        fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
            return jsonObject.mapValues { (_, value) -> jsonElementToValue(value) }
        }

        private fun jsonElementToValue(element: JsonElement): Any {
            return when (element) {
                is JsonPrimitive -> {
                    when {
                        element.isString -> element.content
                        element.booleanOrNull != null -> element.boolean
                        element.intOrNull != null -> element.int
                        element.longOrNull != null -> element.long
                        element.doubleOrNull != null -> element.double
                        else -> element.content
                    }
                }

                is JsonObject -> jsonObjectToMap(element)
                is JsonArray -> element.map { jsonElementToValue(it) }
            }
        }

        /**
         * 将普通 Map<String, Any> 转换为 [JsonElement]，用于包装技能输出。
         */
        @Suppress("UNCHECKED_CAST")
        fun mapToJsonElement(map: Map<String, Any>): JsonElement {
            return JsonObject(map.mapValues { (_, value) -> valueToJsonElement(value) })
        }

        private fun valueToJsonElement(value: Any?): JsonElement {
            return when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Map<*, *> -> mapToJsonElement(
                    value.mapKeys { it.key.toString() }
                        .mapValues { it.value as Any }
                )

                is List<*> -> JsonArray(value.map { valueToJsonElement(it) })
                else -> JsonPrimitive(value.toString())
            }
        }
    }
}
