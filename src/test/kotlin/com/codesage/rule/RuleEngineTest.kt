package com.codesage.rule

import com.codesage.rule.matcher.RuleMatcher
import com.codesage.rule.matcher.RuleContext
import com.codesage.rule.parser.RuleParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RuleEngineTest {
    
    @Test
    fun `RuleMatcher should match EQUALS condition`() {
        val matcher = RuleMatcher()
        val rule = createTestRule(listOf(
            RuleCondition("status", ConditionOperator.EQUALS, "active")
        ))
        
        val context = RuleContext(
            event = EventType.TASK_STARTED,
            data = mapOf("status" to "active")
        )
        
        assertTrue(matcher.match(rule, context))
    }
    
    @Test
    fun `RuleMatcher should match NOT_EQUALS condition`() {
        val matcher = RuleMatcher()
        val rule = createTestRule(listOf(
            RuleCondition("status", ConditionOperator.NOT_EQUALS, "inactive")
        ))
        
        val context = RuleContext(
            event = EventType.TASK_STARTED,
            data = mapOf("status" to "active")
        )
        
        assertTrue(matcher.match(rule, context))
    }
    
    @Test
    fun `RuleMatcher should match CONTAINS condition`() {
        val matcher = RuleMatcher()
        val rule = createTestRule(listOf(
            RuleCondition("message", ConditionOperator.CONTAINS, "error")
        ))
        
        val context = RuleContext(
            event = EventType.TASK_FAILED,
            data = mapOf("message" to "An error occurred")
        )
        
        assertTrue(matcher.match(rule, context))
    }
    
    @Test
    fun `RuleMatcher should match GREATER_THAN condition`() {
        val matcher = RuleMatcher()
        val rule = createTestRule(listOf(
            RuleCondition("count", ConditionOperator.GREATER_THAN, 5)
        ))
        
        val context = RuleContext(
            event = EventType.TASK_STARTED,
            data = mapOf("count" to 10)
        )
        
        assertTrue(matcher.match(rule, context))
    }
    
    @Test
    fun `RuleMatcher should match REGEX_MATCH condition`() {
        val matcher = RuleMatcher()
        val rule = createTestRule(listOf(
            RuleCondition("email", ConditionOperator.REGEX_MATCH, ".*@example\\.com")
        ))
        
        val context = RuleContext(
            event = EventType.TASK_STARTED,
            data = mapOf("email" to "user@example.com")
        )
        
        assertTrue(matcher.match(rule, context))
    }
    
    @Test
    fun `RuleParser should parse valid rule file`() {
        val parser = RuleParser()
        val content = """
            rules:
              - id: "test_rule"
                name: "Test Rule"
                description: "A test rule"
                priority: 5
                enabled: true
                trigger:
                  type: "OnEvent"
                  eventType: "TASK_STARTED"
                conditions: []
                actions:
                  - actionType: "SEND_MESSAGE"
                    message: "Hello"
        """.trimIndent()
        
        val rules = parser.parseContent(content)
        
        assertEquals(1, rules.size)
        assertEquals("test_rule", rules[0].id)
        assertEquals(5, rules[0].priority)
    }
    
    @Test
    fun `RuleParser should validate required fields`() {
        val parser = RuleParser()
        val rule = Rule(
            id = "",
            name = "Test",
            description = "Test rule",
            trigger = RuleTrigger.Manual,
            actions = listOf(RuleAction.SendMessage("test"))
        )
        
        val errors = parser.validate(rule)
        
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { it.contains("ID") })
    }
    
    @Test
    fun `Rule should be disabled when not enabled`() {
        val rule = createTestRule(emptyList(), enabled = false)
        val matcher = RuleMatcher()
        
        val context = RuleContext(
            event = EventType.TASK_STARTED,
            data = emptyMap()
        )
        
        assertFalse(matcher.match(rule, context))
    }
    
    private fun createTestRule(
        conditions: List<RuleCondition>,
        enabled: Boolean = true
    ): Rule = Rule(
        id = "test_rule",
        name = "Test Rule",
        description = "A test rule",
        enabled = enabled,
        trigger = RuleTrigger.OnEvent(EventType.TASK_STARTED),
        conditions = conditions,
        actions = listOf(RuleAction.SendMessage("test"))
    )
}