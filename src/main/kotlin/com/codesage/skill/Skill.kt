package com.codesage.skill

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * 技能类别
 */
@Serializable
enum class SkillCategory {
    FILE_OPERATION,
    CODE_SEARCH,
    EXECUTION,
    NETWORK,
    GIT,
    AI_INTEGRATION,
    CUSTOM
}

/**
 * 技能输入
 */
data class SkillInput(
    val arguments: Map<String, Any>
) {
    fun getString(key: String): String? = arguments[key] as? String
    fun getInt(key: String): Int? = (arguments[key] as? Number)?.toInt()
    fun getBoolean(key: String): Boolean? = arguments[key] as? Boolean
    fun getList(key: String): List<Any>? = (arguments[key] as? List<*>)?.filterIsInstance<Any>()
    fun getMap(key: String): Map<String, Any>? {
        @Suppress("UNCHECKED_CAST")
        return (arguments[key] as? Map<String, Any>)
            ?: (arguments[key] as? Map<*, *>)?.mapKeys { it.key.toString() } as? Map<String, Any>
    }

    fun get(key: String): Any? = arguments[key]
}

/**
 * 技能执行结果
 */
sealed class SkillResult {
    data class Success(val output: Map<String, Any>) : SkillResult()

    data class Failure(
        val error: String,
        val cause: Throwable? = null
    ) : SkillResult()

    val isSuccess: Boolean get() = this is Success
}

/**
 * 技能接口
 */
interface Skill {
    val id: String
    val name: String
    val description: String
    val version: String
    val category: SkillCategory
    val tags: Set<String>
    val inputSchema: Map<String, Any>
    val outputSchema: Map<String, Any>

    /**
     * 使用示例，用于帮助模型理解如何调用该技能。
     * 每个示例应为一段自然语言描述或 JSON 调用示例。
     */
    val examples: List<String> get() = emptyList()

    /**
     * 检查是否可以执行
     */
    fun canExecute(context: ExecutionContext): CanExecuteResult

    /**
     * 执行技能
     */
    suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult
}

/**
 * 执行上下文
 */
data class ExecutionContext(
    val projectPath: String? = null,
    val currentFile: String? = null,
    val selectedText: String? = null,
    val userId: String? = null,
    val sessionId: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * 执行检查结果
 */
data class CanExecuteResult(
    val canExecute: Boolean,
    val reason: String? = null
)

/**
 * 技能定义 (用于配置和声明式技能)
 */
@Serializable
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val category: SkillCategory,
    val tags: Set<String> = emptySet(),
    val examples: List<String> = emptyList(),
    val inputSchema: Map<String, @Contextual Any> = emptyMap(),
    val outputSchema: Map<String, @Contextual Any> = emptyMap(),
    val implementation: SkillImplementationType,
    val config: Map<String, @Contextual Any> = emptyMap()
)

/**
 * 技能实现类型
 */
@Serializable
sealed class SkillImplementationType {
    @Serializable
    data class BuiltIn(val className: String) : SkillImplementationType()

    @Serializable
    data class External(
        val type: String,  // "command", "http", "script"
        val command: String? = null,
        val url: String? = null,
        val script: String? = null
    ) : SkillImplementationType()
}

/**
 * 技能变更事件
 */
sealed class SkillChangeEvent {
    data class Added(val skill: Skill) : SkillChangeEvent()
    data class Updated(val skill: Skill) : SkillChangeEvent()
    data class Removed(val skillId: String) : SkillChangeEvent()
}
