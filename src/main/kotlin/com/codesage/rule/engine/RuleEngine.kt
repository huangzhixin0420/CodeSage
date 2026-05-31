package com.codesage.rule.engine

import com.codesage.rule.*
import com.codesage.rule.actions.RuleActionExecutor
import com.codesage.rule.matcher.RuleContext
import com.codesage.rule.matcher.RuleMatcher
import com.codesage.rule.parser.RuleParser
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 规则引擎
 * 管理规则生命周期和执行
 */
class RuleEngine {
    private val logger = Logger.getLogger<RuleEngine>()

    private val rules = ConcurrentHashMap<String, Rule>()
    private val matcher = RuleMatcher()
    private val actionExecutor = RuleActionExecutor()
    private val parser = RuleParser()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 加载规则文件
     */
    fun loadRules(filePath: String) {
        val loadedRules = parser.parseFile(filePath)
        loadedRules.forEach { rule ->
            addRule(rule)
        }
        logger.info("Loaded ${loadedRules.size} rules from $filePath")
    }

    /**
     * 添加规则
     */
    fun addRule(rule: Rule) {
        val errors = parser.validate(rule)
        if (errors.isNotEmpty()) {
            logger.warn("Rule ${rule.id} validation errors: $errors")
        }
        rules[rule.id] = rule
    }

    /**
     * 移除规则
     */
    fun removeRule(ruleId: String) {
        rules.remove(ruleId)
        logger.info("Removed rule: $ruleId")
    }

    /**
     * 获取规则
     */
    fun getRule(ruleId: String): Rule? = rules[ruleId]

    /**
     * 获取所有规则
     */
    fun getAllRules(): List<Rule> = rules.values.toList()

    /**
     * 触发规则
     */
    suspend fun trigger(event: EventType, data: Map<String, Any> = emptyMap()): List<RuleResult> {
        val context = RuleContext(event = event, data = data)
        val matchingRules = rules.values.filter { rule ->
            val triggerMatch = matchesTrigger(rule.trigger, event)
            val conditionMatch = matcher.match(rule, context)
            triggerMatch && conditionMatch
        }.sortedByDescending { it.priority }

        val results = mutableListOf<RuleResult>()

        for (rule in matchingRules) {
            val result = executeRule(rule, context)
            results.add(result)
        }

        return results
    }

    /**
     * 检查触发器是否匹配事件
     */
    private fun matchesTrigger(trigger: RuleTrigger, event: EventType): Boolean {
        return when (trigger) {
            is RuleTrigger.OnEvent -> trigger.eventType == event
            is RuleTrigger.OnSchedule -> false  // 定时任务由调度器处理
            is RuleTrigger.OnCondition -> false // 条件触发器必须由外部显式触发，不应自动响应所有事件
            RuleTrigger.Manual -> false  // 手动触发器不自动触发
        }
    }

    /**
     * 执行规则
     */
    private suspend fun executeRule(rule: Rule, context: RuleContext): RuleResult {
        logger.info("Executing rule: ${rule.id}")

        val actionResults = mutableListOf<ActionResult>()

        for (action in rule.actions) {
            val result = actionExecutor.execute(action, context)
            actionResults.add(result)
        }

        return RuleResult(
            ruleId = rule.id,
            success = actionResults.all { it.success },
            actionResults = actionResults
        )
    }

    /**
     * 异步触发规则
     */
    fun triggerAsync(event: EventType, data: Map<String, Any> = emptyMap()): kotlinx.coroutines.Job {
        return scope.launch {
            try {
                trigger(event, data)
            } catch (e: Exception) {
                logger.error("Async rule trigger failed for event $event", e)
            }
        }
    }

    /**
     * 显式触发OnCondition类型的规则（用于外部条件评估后手动触发）
     */
    suspend fun triggerConditionRules(data: Map<String, Any> = emptyMap()): List<RuleResult> {
        val context = RuleContext(event = EventType.TASK_STARTED, data = data)
        val conditionRules = rules.values.filter { it.trigger is RuleTrigger.OnCondition && it.enabled }
            .sortedByDescending { it.priority }

        val results = mutableListOf<RuleResult>()
        for (rule in conditionRules) {
            val result = executeRule(rule, context)
            results.add(result)
        }
        return results
    }

    /**
     * 启用/禁用规则
     */
    fun setEnabled(ruleId: String, enabled: Boolean) {
        rules[ruleId]?.let { rule ->
            rules[ruleId] = rule.copy(enabled = enabled)
        }
    }

    /**
     * 清空所有规则
     */
    fun clearRules() {
        rules.clear()
    }

    /**
     * 关闭规则引擎
     */
    fun shutdown() {
        scope.cancel()
    }

    protected fun finalize() {
        shutdown()
    }
}

/**
 * 规则执行结果
 */
data class RuleResult(
    val ruleId: String,
    val success: Boolean,
    val actionResults: List<ActionResult>
)
