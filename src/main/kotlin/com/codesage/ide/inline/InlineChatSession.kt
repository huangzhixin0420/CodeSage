package com.codesage.ide.inline

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.ide.inline.diff.DiffAccumulator
import com.codesage.ide.inline.diff.DiffResult
import com.codesage.ide.inline.diff.DiffType
import com.codesage.ide.inline.diff.EditorInlineDiffRenderer
import com.codesage.ide.inline.modification.CodeChange
import com.codesage.ide.inline.modification.CodeChanges
import com.codesage.ide.inline.modification.ChangeType
import com.codesage.ide.inline.modification.InlineChatCodeModifier
import com.codesage.ide.inline.prompt.InlineChatPrompts
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Inline Chat 会话（平台包装层）
 *
 * 包装 [InlineChatStateMachine]，添加 IntelliJ 平台相关生命周期管理。
 * 核心状态机逻辑在 [InlineChatStateMachine] 中，可独立测试。
 */
class InlineChatSession(
    val sessionId: String,
    val project: Project,
    val editor: Editor,
    val context: InlineChatContext,
    private val onDispose: () -> Unit = {}
) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Diff 累积器 */
    private val diffAccumulator = DiffAccumulator(originalCode = context.selectedText ?: "")

    /** Diff 渲染器 */
    private val diffRenderer = EditorInlineDiffRenderer(editor)

    /** 当前 Agent 对话 Job（用于取消） */
    private var chatJob: Job? = null

    /** 核心状态机 */
    val stateMachine: InlineChatStateMachine = InlineChatStateMachine(sessionId, context)

    /** 状态流（委托给状态机） */
    val state: StateFlow<InlineChatStateMachine.State> = stateMachine.state

    /** Diff 结果流（委托给状态机） */
    val diffResult: StateFlow<DiffResult> = stateMachine.diffResult

    /** 消息列表（委托给状态机） */
    val messages: List<InlineChatStateMachine.ChatMessage> get() = stateMachine.messages

    init {
        // 监听 diffResult 变化，实时渲染到编辑器
        scope.launch {
            diffResult.collect { result ->
                if (result != DiffResult.EMPTY) {
                    diffRenderer.renderDiff(result)
                }
            }
        }

        // 将状态机事件转发到外部回调
        stateMachine.onStateChanged = { _, newState ->
            onStateChanged?.invoke(newState)
            if (newState == InlineChatStateMachine.State.Closed) {
                dispose()
            }
        }
    }

    // ===== 外部回调 =====

    var onStateChanged: ((InlineChatStateMachine.State) -> Unit)? = null
    var onDiffUpdated: ((DiffResult) -> Unit)? = null
        set(value) {
            field = value
            stateMachine.onDiffUpdated = value
        }

    var onTextDelta: ((String) -> Unit)? = null
        set(value) {
            field = value
            stateMachine.onTextDelta = value
        }

    var onError: ((String) -> Unit)? = null
        set(value) {
            field = value
            stateMachine.onError = value
        }

    // ===== 公共 API =====

    /**
     * 发送用户请求：构建 Prompt，调用 AgentCore 流式对话，实时累积 Diff。
     */
    fun sendRequest(userMessage: String): Boolean {
        if (!stateMachine.sendRequest(userMessage)) return false

        val controller = InlineChatController.getInstance(project)
        val agentCore = controller.getAgentCore()

        // 每次 Inline Chat 使用干净的上下文，避免历史消息污染
        agentCore.clearSession()

        val prompt = InlineChatPrompts.buildPrompt(
            mode = context.mode,
            selectedCode = context.selectedText ?: "",
            language = context.language,
            userInstruction = userMessage,
            diagnostics = context.diagnostics.map { it.message }
        )

        chatJob = scope.launch(Dispatchers.Default) {
            try {
                agentCore.chatWithTools(prompt).collect { event ->
                    withContext(Dispatchers.Main) {
                        handleAgentEvent(event)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    stateMachine.onStreamEvent(AgentStreamEvent.Error(e.message ?: "Unknown error"))
                }
            }
        }

        return true
    }

    fun onStreamEvent(event: AgentStreamEvent): Boolean = stateMachine.onStreamEvent(event)

    fun updateDiffResult(result: DiffResult) = stateMachine.updateDiffResult(result)

    /**
     * 接受所有变更：将 AI 生成的代码实际应用到文档中
     */
    fun acceptAllChanges(): Boolean {
        if (!ensureEditorValid()) return false
        if (!stateMachine.canReview()) return false

        val newCode = extractNewCode(diffResult.value)
        val change = CodeChange(
            type = ChangeType.REPLACE,
            startLine = context.startLine,
            endLine = context.endLine,
            newContent = newCode
        )

        return try {
            val modifier = InlineChatCodeModifier(project, editor.document)
            modifier.applyChanges(CodeChanges(listOf(change)))
            diffRenderer.clearHighlighters()
            stateMachine.acceptAllChanges()
            true
        } catch (e: Exception) {
            dispose()
            false
        }
    }

    /**
     * 拒绝所有变更：清除 Diff 高亮并关闭会话
     */
    fun rejectAllChanges(): Boolean {
        if (!ensureEditorValid()) return false

        val result = stateMachine.rejectAllChanges()
        if (result) {
            diffRenderer.clearHighlighters()
        }
        return result
    }

    fun retryRequest(): Boolean = stateMachine.retryRequest()

    fun cancelRequest(): Boolean {
        chatJob?.cancel()
        chatJob = null
        InlineChatController.getInstance(project).getAgentCore().interrupt()
        return stateMachine.cancelRequest()
    }

    fun isActive(): Boolean = stateMachine.isActive()

    fun currentState(): InlineChatStateMachine.State = stateMachine.currentState()

    // ===== 生命周期 =====

    override fun dispose() {
        if (!stateMachine.isActive()) return

        chatJob?.cancel()
        chatJob = null
        stateMachine.close()
        diffRenderer.dispose()
        scope.cancel()
        onDispose()
    }

    /**
     * 处理 AgentCore 流式事件：转发状态机 + 累积 Diff。
     */
    private fun handleAgentEvent(event: AgentStreamEvent) {
        stateMachine.onStreamEvent(event)

        when (event) {
            is AgentStreamEvent.TextDelta -> {
                diffAccumulator.append(event.delta)?.let { diffResult ->
                    stateMachine.updateDiffResult(diffResult)
                }
            }

            is AgentStreamEvent.Done -> {
                val finalDiff = diffAccumulator.finalize()
                stateMachine.updateDiffResult(finalDiff)
            }

            is AgentStreamEvent.Error -> {
                stateMachine.onStreamEvent(event)
            }

            else -> {
                // ToolCallStart/Result/Thinking 等事件已由状态机处理
            }
        }
    }

    /**
     * 检查 Editor 是否仍然有效。如果已被释放，自动 dispose 当前 Session。
     */
    private fun ensureEditorValid(): Boolean {
        return try {
            @Suppress("UNUSED_EXPRESSION")
            editor.document
            true
        } catch (e: Exception) {
            dispose()
            false
        }
    }

    /**
     * 从 DiffResult 中提取新代码（排除被删除的行）
     */
    private fun extractNewCode(diffResult: DiffResult): String {
        return diffResult.lines
            .filter { it.type != DiffType.REMOVED }
            .joinToString("\n") { it.content }
    }
}
