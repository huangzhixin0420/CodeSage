package com.codesage.rule.parser

import com.codesage.rule.*
import com.codesage.shared.utils.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 规则解析器
 * 从YAML文件解析规则
 */
class RuleParser {
    private val logger = Logger.getLogger<RuleParser>()
    private val yaml = Yaml()

    /**
     * 解析规则文件
     */
    fun parseFile(filePath: String): List<Rule> {
        val file = File(filePath)
        if (!file.exists()) {
            logger.warn("Rule file not found: $filePath")
            return emptyList()
        }

        return try {
            parseContent(file.readText())
        } catch (e: Exception) {
            logger.error("Failed to parse rule file: $filePath", e)
            emptyList()
        }
    }

    /**
     * 解析规则内容
     */
    fun parseContent(content: String): List<Rule> {
        return try {
            val rulesFile = yaml.load<MutableMap<String, Any>>(content)
            val rulesList = rulesFile["rules"] as? List<MutableMap<String, Any>> ?: emptyList()
            rulesList.mapNotNull { parseRuleDefinition(it).toRule() }
        } catch (e: Exception) {
            logger.error("Failed to parse rule content", e)
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRuleDefinition(map: Map<String, Any>): RuleDefinition {
        val trigger = map["trigger"] as? Map<String, Any> ?: emptyMap()
        val conditions = (map["conditions"] as? List<Map<String, Any>>) ?: emptyList()
        val actions = (map["actions"] as? List<Map<String, Any>>) ?: emptyList()

        return RuleDefinition(
            id = map["id"] as? String ?: "",
            name = map["name"] as? String ?: "",
            description = map["description"] as? String ?: "",
            priority = (map["priority"] as? Number)?.toInt() ?: 0,
            enabled = map["enabled"] as? Boolean ?: true,
            trigger = parseTriggerDefinition(trigger),
            conditions = conditions.map { parseConditionDefinition(it) },
            actions = actions.map { parseActionDefinition(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTriggerDefinition(map: Map<String, Any>): TriggerDefinition {
        return TriggerDefinition(
            type = map["type"] as? String ?: "Manual",
            eventType = map["eventType"] as? String,
            cronExpression = map["cronExpression"] as? String,
            condition = (map["condition"] as? Map<String, Any>)?.let { parseConditionDefinition(it) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConditionDefinition(map: Map<String, Any>): ConditionDefinition {
        return ConditionDefinition(
            field = map["field"] as? String ?: "",
            operator = map["operator"] as? String ?: "EQUALS",
            value = map["value"]
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseActionDefinition(map: Map<String, Any>): ActionDefinition {
        return ActionDefinition(
            actionType = map["actionType"] as? String ?: "",
            message = map["message"] as? String,
            title = map["title"] as? String,
            skillId = map["skillId"] as? String,
            parameters = (map["parameters"] as? Map<String, Any>) ?: emptyMap(),
            name = map["name"] as? String,
            value = map["value"],
            delayMs = (map["delayMs"] as? Number)?.toLong()
        )
    }

    /**
     * 验证规则
     */
    fun validate(rule: Rule): List<String> {
        val errors = mutableListOf<String>()

        if (rule.id.isBlank()) errors.add("Rule ID cannot be blank")
        if (rule.name.isBlank()) errors.add("Rule name cannot be blank")
        if (rule.actions.isEmpty()) errors.add("Rule must have at least one action")

        return errors
    }
}

/**
 * 规则定义辅助类
 */
data class RuleDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    val trigger: TriggerDefinition,
    val conditions: List<ConditionDefinition> = emptyList(),
    val actions: List<ActionDefinition> = emptyList()
) {
    fun toRule(): Rule = Rule(
        id = id,
        name = name,
        description = description,
        priority = priority,
        enabled = enabled,
        trigger = trigger.toTrigger(),
        conditions = conditions.map { it.toCondition() },
        actions = actions.map { it.toAction() }
    )
}

data class TriggerDefinition(
    val type: String,
    val eventType: String? = null,
    val cronExpression: String? = null,
    val condition: ConditionDefinition? = null
) {
    fun toTrigger(): RuleTrigger = when (type) {
        "OnEvent" -> RuleTrigger.OnEvent(
            eventType = eventType?.let {
                EventType.valueOf(it.uppercase())
            } ?: EventType.TASK_STARTED
        )
        "OnSchedule" -> RuleTrigger.OnSchedule(
            cronExpression = cronExpression ?: "0 * * * *"
        )
        "OnCondition" -> RuleTrigger.OnCondition(
            condition?.toCondition() ?: RuleCondition("true", ConditionOperator.EQUALS, null)
        )
        "Manual" -> RuleTrigger.Manual
        else -> RuleTrigger.Manual
    }
}

data class ConditionDefinition(
    val field: String,
    val operator: String,
    val value: Any? = null
) {
    fun toCondition(): RuleCondition = RuleCondition(
        field = field,
        operator = ConditionOperator.valueOf(operator.uppercase()),
        value = value
    )
}

data class ActionDefinition(
    val actionType: String,
    val message: String? = null,
    val title: String? = null,
    val skillId: String? = null,
    val parameters: Map<String, Any> = emptyMap(),
    val name: String? = null,
    val value: Any? = null,
    val delayMs: Long? = null
) {
    fun toAction(): RuleAction = when (actionType.uppercase()) {
        "SEND_MESSAGE" -> RuleAction.SendMessage(message ?: "")
        "SEND_NOTIFICATION" -> RuleAction.SendNotification(
            title = title ?: "Notification",
            message = message ?: ""
        )
        "RUN_SKILL" -> RuleAction.RunSkill(
            skillId = skillId ?: "",
            parameters = parameters
        )
        "SET_VARIABLE" -> RuleAction.SetVariable(
            name = name ?: "",
            value = value ?: ""
        )
        "RETRY_TASK" -> RuleAction.RetryTask(delayMs ?: 1000)
        else -> RuleAction.SendMessage("Unknown action: $actionType")
    }
}
