package com.codesage.rule.matcher

import com.codesage.rule.*
import com.codesage.shared.utils.Logger
import java.util.regex.Pattern

/**
 * 规则匹配器
 * 评估条件是否满足
 */
class RuleMatcher {
    private val logger = Logger.getLogger<RuleMatcher>()
    
    /**
     * 检查规则条件是否满足
     */
    fun match(rule: Rule, context: RuleContext): Boolean {
        if (!rule.enabled) return false
        
        return rule.conditions.all { condition ->
            evaluateCondition(condition, context)
        }
    }
    
    /**
     * 评估单个条件
     */
    private fun evaluateCondition(condition: RuleCondition, context: RuleContext): Boolean {
        val fieldValue = resolveField(condition.field, context)
        
        return when (condition.operator) {
            ConditionOperator.EQUALS -> equals(fieldValue, condition.value)
            ConditionOperator.NOT_EQUALS -> !equals(fieldValue, condition.value)
            ConditionOperator.CONTAINS -> contains(fieldValue, condition.value)
            ConditionOperator.NOT_CONTAINS -> !contains(fieldValue, condition.value)
            ConditionOperator.STARTS_WITH -> startsWith(fieldValue, condition.value)
            ConditionOperator.ENDS_WITH -> endsWith(fieldValue, condition.value)
            ConditionOperator.GREATER_THAN -> greaterThan(fieldValue, condition.value)
            ConditionOperator.LESS_THAN -> lessThan(fieldValue, condition.value)
            ConditionOperator.REGEX_MATCH -> regexMatch(fieldValue, condition.value)
            ConditionOperator.IN_LIST -> inList(fieldValue, condition.value)
            ConditionOperator.EXISTS -> exists(fieldValue)
        }
    }
    
    /**
     * 解析字段路径
     */
    private fun resolveField(field: String, context: RuleContext): Any? {
        val parts = field.split(".")
        var current: Any? = context.data
        
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current?.get(part)
                is RuleContext -> {
                    when (part) {
                        "event" -> current.event
                        "data" -> current.data
                        "variables" -> current.variables
                        else -> null
                    }
                }
                else -> null
            }
        }
        
        return current
    }
    
    private fun equals(fieldValue: Any?, target: Any?): Boolean {
        return fieldValue?.toString() == target?.toString()
    }
    
    private fun contains(fieldValue: Any?, target: Any?): Boolean {
        return fieldValue?.toString()?.contains(target?.toString() ?: "") == true
    }
    
    private fun startsWith(fieldValue: Any?, target: Any?): Boolean {
        return fieldValue?.toString()?.startsWith(target?.toString() ?: "") == true
    }
    
    private fun endsWith(fieldValue: Any?, target: Any?): Boolean {
        return fieldValue?.toString()?.endsWith(target?.toString() ?: "") == true
    }
    
    private fun greaterThan(fieldValue: Any?, target: Any?): Boolean {
        val numValue = (fieldValue as? Number)?.toDouble() ?: return false
        val numTarget = (target as? Number)?.toDouble() ?: return false
        return numValue > numTarget
    }
    
    private fun lessThan(fieldValue: Any?, target: Any?): Boolean {
        val numValue = (fieldValue as? Number)?.toDouble() ?: return false
        val numTarget = (target as? Number)?.toDouble() ?: return false
        return numValue < numTarget
    }
    
    private fun regexMatch(fieldValue: Any?, pattern: Any?): Boolean {
        if (fieldValue == null || pattern == null) return false
        return try {
            Pattern.matches(pattern.toString(), fieldValue.toString())
        } catch (e: Exception) {
            logger.warn("Invalid regex pattern: $pattern", e)
            false
        }
    }
    
    private fun inList(fieldValue: Any?, target: Any?): Boolean {
        if (fieldValue == null || target == null) return false
        val list = when (target) {
            is List<*> -> target
            is String -> target.split(",").map { it.trim() }
            else -> listOf(target)
        }
        return fieldValue.toString() in list.map { it.toString() }
    }
    
    private fun exists(fieldValue: Any?): Boolean {
        return fieldValue != null && fieldValue.toString().isNotEmpty()
    }
}

/**
 * 规则上下文
 */
data class RuleContext(
    val event: EventType,
    val data: Map<String, Any> = emptyMap(),
    val variables: Map<String, Any> = emptyMap(),
    val metadata: Map<String, Any> = emptyMap()
)