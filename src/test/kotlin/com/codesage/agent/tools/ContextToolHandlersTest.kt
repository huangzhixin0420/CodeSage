package com.codesage.agent.tools

import com.codesage.agent.context.ContextBudgetManager
import com.codesage.agent.context.ContextManager
import com.codesage.agent.tools.handlers.ContextToolHandlers
import com.codesage.model.dto.Message
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ContextToolHandlersTest {

    @Test
    fun `get_context_remaining returns decreasing tokens_left as conversation grows`() = runBlocking {
        val contextManager = ContextManager()
        val budget = ContextBudgetManager(
            contextLength = 10000,
            contextManagerProvider = { contextManager }
        )
        val handler = ContextToolHandlers.createGetContextRemainingHandler(budget)

        val first = handler.execute(JsonObject(emptyMap())) as ToolResult.Success
        val firstLeft = first.data.jsonObject["tokens_left"]?.jsonPrimitive?.intOrNull
        assertNotNull(firstLeft)

        contextManager.addMessage(Message.userMessage("This is a user message that consumes tokens."))
        contextManager.addMessage(Message.assistantMessage("This is an assistant reply that also consumes tokens."))

        val second = handler.execute(JsonObject(emptyMap())) as ToolResult.Success
        val secondLeft = second.data.jsonObject["tokens_left"]?.jsonPrimitive?.intOrNull
        assertNotNull(secondLeft)

        assertTrue(secondLeft!! < firstLeft!!)
        assertTrue(second.data.jsonObject["percent"]?.jsonPrimitive?.doubleOrNull!! > 0.0)
        assertTrue(
            second.data.jsonObject.containsKey("recommended_max_output_length")
        )
        assertTrue(
            second.data.jsonObject.containsKey("recommended_max_output_lines")
        )
    }

    @Test
    fun `get_context_remaining tool schema is registered`() {
        val registry = ToolRegistry()
        val budget = ContextBudgetManager()
        registry.register(ContextToolHandlers.createGetContextRemainingHandler(budget))

        val tool = registry.get("get_context_remaining")
        assertNotNull(tool)
        assertTrue(tool!!.description.contains("tokens_used"))
        assertTrue(tool.description.contains("tokens_left"))
        assertTrue(registry.hasHandler("get_context_remaining"))
    }
}
