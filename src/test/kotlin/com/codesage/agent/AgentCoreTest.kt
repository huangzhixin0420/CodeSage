package com.codesage.agent

import com.codesage.agent.context.ContextManager
import com.codesage.agent.core.AgentConfig
import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.planner.TaskPlanner
import com.codesage.agent.planner.TaskPriority
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.exceptions.ContextTooLongException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentCoreTest {

    @Test
    fun `should compress context and retry on context too long`() = runBlocking {
        val fakeAdapter = object : com.codesage.model.adapter.ModelAdapter {
            override val providerName: String = "fake"
            override val supportedModels: List<String> = listOf("MiniMax-Text-01")
            override fun supportsStreaming(): Boolean = false
            override fun supportsFunctionCalling(): Boolean = true
            override fun supportsVision(): Boolean = false
            override fun toVendorRequest(request: ChatRequest): String = "{}"
            override fun fromVendorResponse(response: String): ChatResponse =
                ChatResponse("", "", emptyList(), null)

            override fun parseStreamChunk(chunk: String): StreamChunk? = null
            override fun getStreamEndpoint(): String = "http://fake"
            override fun getChatEndpoint(): String = "http://fake"
            override fun getHeaders(): Map<String, String> = emptyMap()
        }

        val fakeGateway = object : ModelGateway() {
            var callCount = 0
            override fun getCurrentAdapter(model: String): com.codesage.model.adapter.ModelAdapter? = fakeAdapter
            override suspend fun chat(request: ChatRequest): Result<ChatResponse> {
                callCount++
                return if (callCount == 1) {
                    Result.failure(ContextTooLongException("Context length exceeded"))
                } else {
                    Result.success(
                        ChatResponse(
                            id = "resp_$callCount",
                            model = request.model,
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message.assistantMessage("Hello after compression"),
                                    finishReason = "stop"
                                )
                            ),
                            usage = null
                        )
                    )
                }
            }
        }

        val agent = AgentCore(gateway = fakeGateway)
        agent.initialize(AgentConfig())

        val events = agent.chatWithTools("Hello").toList()

        // Should trigger compression thinking event
        assertTrue(
            events.any { it is AgentStreamEvent.Thinking && it.message.contains("压缩") },
            "Should emit compression thinking event"
        )

        // Should eventually succeed with text delta
        val fullText = events.filterIsInstance<AgentStreamEvent.TextDelta>().joinToString("") { it.delta }
        assertTrue(
            fullText.contains("Hello after compression"),
            "Should emit successful response after retry. Actual text: $fullText"
        )

        // Gateway should be called twice (first fail, second success)
        assertEquals(2, fakeGateway.callCount, "Should retry after context compression")
    }

    @Test
    fun `ContextManager should add and retrieve messages`() {
        val contextManager = ContextManager()

        contextManager.addMessage(Message.systemMessage("You are helpful"))
        contextManager.addMessage(Message.userMessage("Hello"))

        val context = contextManager.getContext()
        assertEquals(2, context.size)
        assertEquals(Role.SYSTEM, context[0].role)
        assertEquals(Role.USER, context[1].role)
    }

    @Test
    fun `ContextManager should truncate old messages`() {
        val config = com.codesage.agent.context.ContextManagementConfig(
            maxHistoryMessages = 5,
            truncationStrategy = com.codesage.agent.context.TruncationStrategy.KEEP_RECENT
        )
        val contextManager = ContextManager(config)

        repeat(10) { i ->
            contextManager.addMessage(Message.userMessage("Message $i"))
        }

        // 保留系统消息 + 最近的5条
        assertTrue(contextManager.size() <= 5)
    }

    @Test
    fun `ContextManager should preserve system messages`() {
        val contextManager = ContextManager()

        contextManager.addMessage(Message.systemMessage("System prompt"))
        repeat(20) { i ->
            contextManager.addMessage(Message.userMessage("Message $i"))
        }

        val systemMessages = contextManager.getSystemMessages()
        assertEquals(1, systemMessages.size)
        assertEquals("System prompt", systemMessages[0].content)
    }

    @Test
    fun `TaskPlanner should create task with ID`() {
        val planner = TaskPlanner()

        val task = planner.createTask(
            description = "Build a feature",
            goal = "Complete feature",
            priority = TaskPriority.HIGH
        )

        assertNotNull(task.id)
        assertTrue(task.id.startsWith("task_"))
        assertEquals(TaskPriority.HIGH, task.priority)
    }

    @Test
    fun `TaskPlanner should decompose task into subTasks`() {
        val planner = TaskPlanner()

        val task = planner.createTask(
            description = "Step 1, Step 2, Step 3",
            goal = "Complete all steps"
        )

        val plan = planner.decomposeTask(task, emptyList())

        // 按逗号拆分后得到有效步骤: "Step 1"、"Step 2"、"Step 3"
        assertEquals(3, plan.totalSubTasks)
        assertNotNull(plan.executionOrder)
    }

    @Test
    fun `TaskPlanner should respect dependencies`() {
        val planner = TaskPlanner()

        val task = planner.createTask(
            description = "Main task",
            goal = "Complete task"
        )

        val subTasks = listOf(
            com.codesage.agent.planner.SubTask(
                id = "subtask_0",
                description = "First step",
                dependencies = emptyList()
            ),
            com.codesage.agent.planner.SubTask(
                id = "subtask_1",
                description = "after first step",
                dependencies = listOf("subtask_0")
            )
        )

        subTasks.forEach { task.addSubTask(it) }

        // 初始状态: subtask_0未完成, subtask_1有前置依赖, canExecute应为false
        assertFalse(planner.canExecute(task))

        // 更新第一个任务为完成状态后,所有子任务都可以执行
        task.updateSubTaskStatus("subtask_0", com.codesage.agent.planner.TaskStatus.COMPLETED)
        assertTrue(planner.canExecute(task))
    }

}
