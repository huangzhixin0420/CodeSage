package com.codesage.ide.inline

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.ide.inline.diff.DiffResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Inline Chat 状态机核心
 *
 * 纯 Kotlin 实现，不依赖 IntelliJ 平台 API，可独立单元测试。
 * 负责：
 * - 状态流转管理
 * - 消息记录
 * - Diff 结果累积
 * - 流式事件处理
 */
class InlineChatStateMachine(
    val sessionId: String,
    val context: InlineChatContext
) {

    // ===== 状态 =====
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // ===== 消息记录 =====
    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    // ===== Diff 结果 =====
    private val _diffResult = MutableStateFlow<DiffResult>(DiffResult.EMPTY)
    val diffResult: StateFlow<DiffResult> = _diffResult.asStateFlow()

    // ===== 活跃标记 =====
    private var active = true

    // ===== 事件回调 =====
    var onStateChanged: ((State, State) -> Unit)? = null
    var onDiffUpdated: ((DiffResult) -> Unit)? = null
    var onTextDelta: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    // ===== 公共 API =====

    /**
     * 用户发送请求
     */
    fun sendRequest(userMessage: String): Boolean {
        if (!active) return false
        if (!canSendRequest()) return false

        _messages.add(ChatMessage.User(userMessage))
        transitionTo(State.Requesting)
        return true
    }

    /**
     * 处理流式事件
     */
    fun onStreamEvent(event: AgentStreamEvent): Boolean {
        if (!active) return false

        when (event) {
            is AgentStreamEvent.TextDelta -> {
                if (_state.value == State.Requesting) {
                    transitionTo(State.Streaming)
                }
                if (_state.value == State.Streaming) {
                    onTextDelta?.invoke(event.delta)
                }
            }

            is AgentStreamEvent.Thinking -> {
                // 状态不变，思考过程由上层展示
            }

            is AgentStreamEvent.ToolCallStart,
            is AgentStreamEvent.ToolCallResult,
            is AgentStreamEvent.ToolCallDelta -> {
                // Inline Chat 内跟踪工具调用状态
            }

            is AgentStreamEvent.ToolConfirmationNeeded -> {
                // 需要用户确认
            }

            is AgentStreamEvent.Error -> {
                transitionTo(State.Error(event.message))
                onError?.invoke(event.message)
            }

            is AgentStreamEvent.Done -> {
                if (_state.value == State.Streaming || _state.value == State.Requesting) {
                    transitionTo(State.Reviewing)
                    onDone?.invoke()
                }
            }

            else -> { /* 忽略其他事件 */
            }
        }

        return true
    }

    /**
     * 更新 Diff 结果
     */
    fun updateDiffResult(result: DiffResult) {
        if (!active) return
        _diffResult.value = result
        onDiffUpdated?.invoke(result)
    }

    /**
     * 接受所有变更
     */
    fun acceptAllChanges(): Boolean {
        if (!active) return false
        if (_state.value != State.Reviewing) return false

        transitionTo(State.Applying)
        // 应用完成后关闭
        transitionTo(State.Closed)
        active = false
        return true
    }

    /**
     * 拒绝所有变更
     */
    fun rejectAllChanges(): Boolean {
        if (!active) return false

        transitionTo(State.Closed)
        active = false
        return true
    }

    /**
     * 重新生成
     */
    fun retryRequest(): Boolean {
        if (!active) return false

        // 清除 Diff，重新请求
        _diffResult.value = DiffResult.EMPTY
        onDiffUpdated?.invoke(DiffResult.EMPTY)

        val lastUserMessage = _messages.findLast { it is ChatMessage.User }
        return if (lastUserMessage != null) {
            transitionTo(State.Requesting)
            true
        } else {
            false
        }
    }

    /**
     * 取消当前请求
     */
    fun cancelRequest(): Boolean {
        if (!active) return false
        if (_state.value != State.Requesting && _state.value != State.Streaming) return false

        transitionTo(State.Idle)
        return true
    }

    /**
     * 关闭状态机
     */
    fun close() {
        if (!active) return
        active = false
        if (_state.value != State.Closed) {
            _state.value = State.Closed
        }
    }

    /**
     * 是否活跃
     */
    fun isActive(): Boolean = active

    /**
     * 当前状态
     */
    fun currentState(): State = _state.value

    /**
     * 是否可以发送请求
     */
    fun canSendRequest(): Boolean {
        return _state.value == State.Idle || _state.value == State.Reviewing
    }

    /**
     * 是否可以接受/拒绝
     */
    fun canReview(): Boolean {
        return _state.value == State.Reviewing
    }

    /**
     * 是否可以取消
     */
    fun canCancel(): Boolean {
        return _state.value == State.Requesting || _state.value == State.Streaming
    }

    // ===== 内部方法 =====

    private fun transitionTo(newState: State) {
        val oldState = _state.value
        if (oldState == newState) return
        _state.value = newState
        onStateChanged?.invoke(oldState, newState)
    }

    // ===== 状态定义 =====

    sealed class State {
        object Idle : State()
        object Requesting : State()
        object Streaming : State()
        object Reviewing : State()
        object Applying : State()
        data class Error(val message: String) : State()
        object Closed : State()
    }

    // ===== 消息定义 =====

    /**
     * Inline Chat 内部消息类型。
     *
     * **为什么不复用 [com.codesage.model.dto.Message]：**
     * - [ChatMessage] 是纯 Kotlin sealed class，无外部依赖，状态机可独立测试
     * - [Message] 是网络传输 DTO（含 `@Serializable`、toolCalls 等），引入会增加耦合
     * - Phase 7 AgentCore 集成时，可通过扩展函数 `toMessage()` 单向转换
     */
    sealed class ChatMessage {
        abstract val content: String

        data class User(override val content: String) : ChatMessage()
        data class Assistant(override val content: String) : ChatMessage()
        data class System(override val content: String) : ChatMessage()
    }
}
