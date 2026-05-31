package com.codesage.rule

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * 规则执行结果
 */
data class ActionResult(
    val actionType: String,
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null
)

/**
 * 规则定义
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val description: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition> = emptyList(),
    val actions: List<RuleAction> = emptyList(),
    val metadata: RuleMetadata = RuleMetadata()
)

/**
 * 规则触发器
 */
@Serializable
sealed class RuleTrigger {
    @Serializable
    data class OnEvent(
        val eventType: EventType
    ) : RuleTrigger()

    @Serializable
    data class OnSchedule(
        val cronExpression: String
    ) : RuleTrigger()

    @Serializable
    data class OnCondition(
        val condition: RuleCondition
    ) : RuleTrigger()

    @Serializable
    object Manual : RuleTrigger()
}

/**
 * 事件类型
 */
@Serializable
enum class EventType {
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_FAILED,
    SKILL_EXECUTED,
    SKILL_FAILED,
    AGENT_MESSAGE,
    USER_MESSAGE,
    FILE_CHANGED,
    PROJECT_OPENED
}

/**
 * 规则条件
 */
@Serializable
data class RuleCondition(
    val field: String,
    val operator: ConditionOperator,
    val value: @Contextual Any? = null
)

/**
 * 条件操作符
 */
@Serializable
enum class ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    GREATER_THAN,
    LESS_THAN,
    REGEX_MATCH,
    IN_LIST,
    EXISTS
}

/**
 * 规则动作
 */
@Serializable
sealed class RuleAction {
    @Serializable
    data class SendMessage(
        val message: String
    ) : RuleAction()

    @Serializable
    data class SendNotification(
        val title: String,
        val message: String
    ) : RuleAction()

    @Serializable
    data class RunSkill(
        val skillId: String,
        val parameters: Map<String, @Contextual Any> = emptyMap()
    ) : RuleAction()

    @Serializable
    data class SetVariable(
        val name: String,
        val value: @Contextual Any
    ) : RuleAction()

    @Serializable
    data class RetryTask(
        val delayMs: Long = 1000
    ) : RuleAction()
}

/**
 * 规则元数据
 */
@Serializable
data class RuleMetadata(
    val author: String = "",
    val tags: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)
