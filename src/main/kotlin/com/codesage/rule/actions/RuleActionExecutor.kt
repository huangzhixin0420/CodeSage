package com.codesage.rule.actions

import com.codesage.rule.*
import com.codesage.rule.matcher.RuleContext
import com.codesage.skill.*
import com.codesage.skill.executor.SkillExecutor
import com.codesage.shared.utils.Logger

/**
 * 规则动作执行器
 */
class RuleActionExecutor {
    private val logger = Logger.getLogger<RuleActionExecutor>()
    private val skillExecutor = SkillExecutor()
    
    /**
     * 执行动作
     */
    suspend fun execute(action: RuleAction, context: RuleContext): ActionResult {
        return when (action) {
            is RuleAction.SendMessage -> executeSendMessage(action)
            is RuleAction.SendNotification -> executeSendNotification(action, context)
            is RuleAction.RunSkill -> executeRunSkill(action, context)
            is RuleAction.SetVariable -> executeSetVariable(action, context)
            is RuleAction.RetryTask -> executeRetryTask(action, context)
        }
    }
    
    private fun executeSendMessage(action: RuleAction.SendMessage): ActionResult {
        return ActionResult(
            actionType = "SendMessage",
            success = true,
            output = action.message
        )
    }
    
    private fun executeSendNotification(
        action: RuleAction.SendNotification,
        context: RuleContext
    ): ActionResult {
        // 替换消息中的变量占位符
        val message = replaceVariables(action.message, context)
        val title = replaceVariables(action.title, context)
        
        logger.info("Notification: $title - $message")
        
        // 实际通知发送通过IDE的NotificationService
        // NotificationService.showInfo(project, title, message)
        
        return ActionResult(
            actionType = "SendNotification",
            success = true,
            output = mapOf("title" to title, "message" to message)
        )
    }
    
    private suspend fun executeRunSkill(
        action: RuleAction.RunSkill,
        context: RuleContext
    ): ActionResult {
        return try {
            val input = SkillInput(
                action.parameters.mapValues { (_, value) ->
                    replaceVariables(value.toString(), context)
                }
            )
            
            val execContext = ExecutionContext(
                metadata = context.data
            )
            
            val result = skillExecutor.execute(action.skillId, input, execContext)
            
            ActionResult(
                actionType = "RunSkill",
                success = result.isSuccess,
                output = result.let {
                    when (it) {
                        is SkillResult.Success -> it.output
                        is SkillResult.Failure -> null
                    }
                },
                error = (result as? SkillResult.Failure)?.error
            )
        } catch (e: Exception) {
            ActionResult(
                actionType = "RunSkill",
                success = false,
                error = e.message
            )
        }
    }
    
    private fun executeSetVariable(
        action: RuleAction.SetVariable,
        context: RuleContext
    ): ActionResult {
        // 变量存储需要外部实现
        return ActionResult(
            actionType = "SetVariable",
            success = true,
            output = mapOf("name" to action.name, "value" to action.value)
        )
    }
    
    private fun executeRetryTask(
        action: RuleAction.RetryTask,
        context: RuleContext
    ): ActionResult {
        // 重试逻辑需要外部实现
        return ActionResult(
            actionType = "RetryTask",
            success = true,
            output = mapOf("delayMs" to action.delayMs)
        )
    }
    
    /**
     * 替换变量占位符 {variable.name}
     */
    private fun replaceVariables(text: String, context: RuleContext): String {
        var result = text
        val pattern = Regex("\\{([^}]+)\\}")
        
        pattern.findAll(text).forEach { match ->
            val varName = match.groupValues[1]
            val value = resolveVariable(varName, context)
            result = result.replace(match.value, value?.toString() ?: "")
        }
        
        return result
    }
    
    private fun resolveVariable(name: String, context: RuleContext): Any? {
        return when {
            name.startsWith("context.") -> {
                val field = name.removePrefix("context.")
                context.data[field]
            }
            name.startsWith("event.") -> {
                val field = name.removePrefix("event.")
                when (field) {
                    "type" -> context.event.name
                    else -> null
                }
            }
            name.startsWith("var.") -> {
                val varName = name.removePrefix("var.")
                context.variables[varName]
            }
            else -> context.variables[name] ?: context.data[name]
        }
    }
}