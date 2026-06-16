package com.codesage.agent.core

import com.codesage.model.dto.ToolCall

/**
 * Agent 流式事件
 * 用于向 UI 传递对话过程中的各类事件
 */
sealed class AgentStreamEvent {
    /**
     * 文本增量（模拟流式输出）
     */
    data class TextDelta(val delta: String) : AgentStreamEvent()

    /**
     * 开始执行工具调用
     */
    data class ToolCallStart(val toolCall: ToolCall) : AgentStreamEvent()

    /**
     * 工具调用执行中的增量输出（用于长时间运行的工具，如命令执行）
     */
    data class ToolCallDelta(
        val toolCallId: String,
        val toolName: String,
        val delta: String
    ) : AgentStreamEvent()

    /**
     * 命令执行期间的流式输出增量。
     *
     * 与 [ToolCallDelta] 分离：后者用于 LLM 工具参数的 JSON 片段累积；
     * 本事件承载结构化命令输出（stdout / stderr / exitCode / done）。
     *
     * @param toolCallId 关联的工具调用 ID（可能为空，由外层 ToolExecutor 注入）
     * @param stdout 本批次 stdout 增量
     * @param stderr 本批次 stderr 增量
     * @param exitCode 进程最终退出码；仅在 [done] 为 true 时有效
     * @param processId 后台进程 ID（同步命令为空）
     * @param done 命令是否已结束
     */
    data class CommandOutputStream(
        val toolCallId: String = "",
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val processId: String = "",
        val done: Boolean = false
    ) : AgentStreamEvent()

    /**
     * 工具调用执行完成
     */
    data class ToolCallResult(
        val toolCallId: String,
        val toolName: String,
        val result: String,
        val success: Boolean
    ) : AgentStreamEvent()

    /**
     * 工具调用执行出错（流式解析或执行阶段）
     */
    data class ToolCallError(
        val toolCallId: String,
        val error: String
    ) : AgentStreamEvent()

    /**
     * 需要用户确认才能执行的工具调用
     */
    data class ToolConfirmationNeeded(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val reason: String
    ) : AgentStreamEvent()

    /**
     * 思考中/状态更新（Agent 状态消息，如"思考中..."）
     */
    data class Thinking(val message: String) : AgentStreamEvent()

    /**
     * 模型推理内容增量（与 Thinking 分离，用于承载模型实际的 reasoning/thinking 输出）
     */
    data class ModelReasoning(val delta: String) : AgentStreamEvent()

    /**
     * O5.1: 标记新一轮模型推理开始（用于多轮推理卡片分离）
     *
     * Agent 单次 turn 内部可能经历多轮"调用模型 → 工具调用 → 再调用模型"循环,
     * 每次重新调用模型前都应发出本事件,前端据此创建独立的 [StructuredThinking] 卡片。
     * 若仅依赖 `model_reasoning_start`(由 EventConsumer 在首条 ModelReasoning
     * 时改写 type 得到),后续轮次推理将无法触发新卡片,所有内容会被并入第一个卡片。
     *
     * 修正 2026-06:不在每轮循环开始无条件 emit — 仅当本轮真的产生
     * [ModelReasoning] delta 时,由 [EnhancedAgentLoop] 在首条 delta 之前
     * 懒发射。这样:
     *   - 不支持 reasoning 的模型完全不会创建空卡片
     *   - 同一 round 内只发一次(配合 roundReasoningStarted 标志)
     *   - 必须配对 [ModelReasoningRoundEnd] 使用,前端才能正确 complete 卡片
     *
     * 前端契约:收到本事件时创建空 [StructuredThinking] 卡片并设为 turn.modelReasoning;
     * 收到 [ModelReasoning] delta 时调用 card.appendContent(delta);
     * 收到 [ModelReasoningRoundEnd] 时调用 card.complete(elapsedMs) 并归档。
     * 严格按此配对,卡片一定有内容。
     *
     * @param roundIndex 从 1 开始的轮次编号(与 [EnhancedAgentLoop.turnNumber] 对齐)
     */
    data class ModelReasoningRoundStart(val roundIndex: Int) : AgentStreamEvent()

    /**
     * O5.1: 标记新一轮模型推理结束,与 [ModelReasoningRoundStart] 配对。
     *
     * 触发时机:在一次模型调用流中,最后一个 [ModelReasoning] delta 之后的
     * 下一个 chunk(可能是文本/工具调用/流结束)。前端收到后,折叠/归档当前
     * 推理卡片,推进 stream anchor。
     *
     * 之前 O5.1 实现里只发 [ModelReasoningRoundStart] 不发 end,导致:
     *   - 单轮推理时由 EventConsumer.sendDoneExpansion 在 Done 时补发
     *     `model_reasoning_complete` (elapsedMs=0),但只发一次
     *   - 多轮推理时,第二轮起再也不会触发 Complete(per-turn firstModelReasoningSent
     *     标志已置 true),卡片永远停留在 "思考中…" 状态
     *
     * 现在改为每个 round 都发,前端处理逻辑保持向后兼容(`model_reasoning_complete`
     * 仍能 complete 当前 modelReasoning 引用)。
     */
    data class ModelReasoningRoundEnd(val roundIndex: Int) : AgentStreamEvent()

    /**
     * 2026-06: 代码块开始事件。
     *
     * 后端 LLM adapter(`OpenAICompatibleAdapter` 等)在解析到 fenced code block
     * 开围栏时 emit,前端据此创建独立的 [CodeBlockCard] 组件,后续 [CodeBlockDelta]
     * 增量推入内容,流结束时由 [CodeBlockEnd] 折叠/挂工具栏。
     *
     * 与 [ModelReasoning] / [ModelReasoningRoundStart] 同构,后端识别 + 前端组件
     * 实时渲染,避免"流结束才看到代码块增强"的延迟问题(也消除 enhanceCodeBlocks
     * 误伤自管组件的边界问题)。
     *
     * 调研依据: `docs/research/CodeBlock围栏格式调研-2026-06-16-01.md`
     *
     * @param codeBlockId 唯一 ID(`cb-N`,N 从 1 开始;同 turn 内多代码块并行时区分)
     * @param language info string(trim 后),常见值 `kotlin` / `python` / `text`;
     *                 围栏无 info string 时为 null,前端 fallback 为 "text"
     * @param filePath info string 中可能的文件路径(暂由前端解析;本次实现先原样透传)
     */
    data class CodeBlockStart(
        val codeBlockId: String,
        val language: String? = null,
        val filePath: String? = null,
    ) : AgentStreamEvent()

    /**
     * 2026-06: 代码块内容增量。
     *
     * 适配器在开围栏之后,每段代码字符流式 emit Delta,前端增量推入 [CodeBlockCard]。
     * 多个代码块并行时按 [codeBlockId] 分桶(coalesceKey 包含 ID),
     * 互不覆盖。
     *
     * @param codeBlockId 与对应 [CodeBlockStart.codeBlockId] 相同
     * @param delta 本 chunk 内的代码字符增量
     */
    data class CodeBlockDelta(
        val codeBlockId: String,
        val delta: String,
    ) : AgentStreamEvent()

    /**
     * 2026-06: 代码块结束事件,与 [CodeBlockStart] 配对。
     *
     * 触发时机:
     *   1) 适配器解析到闭围栏(同类型 + 长度 ≥ 开围栏)
     *   2) 流正常结束但仍在代码块内(`done=true` 兜底,符合 CommonMark 规范)
     *
     * 前端收到后,complete 当前 [CodeBlockCard] 并挂 5 按钮工具栏
     * (应用/插入/创建/复制/拒绝/diff,视 lang 和 filePath 决定显示哪些)。
     *
     * @param codeBlockId 与对应 [CodeBlockStart.codeBlockId] 相同
     * @param filePath 同 CodeBlockStart(允许补全/修正)
     */
    data class CodeBlockEnd(
        val codeBlockId: String,
        val filePath: String? = null,
    ) : AgentStreamEvent()

    /**
     * 发生错误
     */
    data class Error(val message: String) : AgentStreamEvent()

    /**
     * 子 Agent 开始执行
     *
     * @param sessionId 子 Agent 会话 ID，用于 SubAgentComplete / SubAgentProgress 路由
     * @param taskDescription 子任务描述
     * @param toolset 子 Agent 使用的工具集名称
     * @param maxDepth 6.10.4: 子 Agent 允许的最大递归深度，默认 [SubAgentExecutor.DEFAULT_MAX_RECURSION_DEPTH]
     * @param allowedTools 6.10.4: 显式允许的工具白名单（空表示未限制）
     * @param deniedTools 6.10.4: 显式拒绝的工具黑名单
     * @param depth 6.10.4: 当前递归深度（用于 UI 展示 "当前/最大"）
     * @param delegationForbidden 6.10.4: 当 [deniedTools] 包含 "delegate_task" 时为 true，UI 可显示红色警示
     */
    data class SubAgentStart(
        val sessionId: String,
        val taskDescription: String,
        val toolset: String,
        val maxDepth: Int = SubAgentExecutor.DEFAULT_MAX_RECURSION_DEPTH,
        val allowedTools: List<String> = emptyList(),
        val deniedTools: List<String> = emptyList(),
        val depth: Int = 0,
        val delegationForbidden: Boolean = false
    ) : AgentStreamEvent()

    /**
     * 子 Agent 进度更新
     */
    data class SubAgentProgress(
        val sessionId: String,
        val message: String
    ) : AgentStreamEvent()

    /**
     * 子 Agent 执行完成
     */
    data class SubAgentComplete(
        val sessionId: String,
        val success: Boolean,
        val output: String,
        /**
         * 子 Agent 实际执行的轮次数（tool call 触发 +1）。UI 展示用，**不**进父 LLM context。
         * 可选字段：老调用方传 0 / emptyList 即可。
         */
        val iterationsUsed: Int = 0,
        /**
         * 子 Agent 实际调用的工具名列表（去重）。UI 展示用，**不**进父 LLM context。
         */
        val toolsUsed: List<String> = emptyList(),
        /**
         * **P2**: 用户在子 Agent 跑到一半时按了 Cancel。父 LLM 看到 output 里的
         * "Cancelled by user." marker 后**不**应自动 retry（见 buildSubAgentPrompt 的
         * Cancellation Semantics 段）。
         */
        val cancelled: Boolean = false,
        /**
         * **P2**: 取消前子 Agent 已完成的 tool call 列表（带 filePath 摘要）。
         * 进父 LLM context 让它知道"哪些文件改过了"（避免重复改 / 误以为没动）。
         */
        val completedToolCalls: List<ToolCallRecord> = emptyList()
    ) : AgentStreamEvent()

    /**
     * 计划步骤定义
     */
    data class PlanStep(
        val id: String,
        val description: String,
        val dependsOn: List<String> = emptyList()
    )

    /**
     * 计划已生成（用于任务规划场景）
     */
    data class PlanGenerated(
        val planId: String,
        val description: String,
        val steps: List<PlanStep>
    ) : AgentStreamEvent()

    /**
     * 计划已批准
     */
    data class PlanApproved(val planId: String) : AgentStreamEvent()

    /**
     * 计划已修改
     */
    data class PlanModified(
        val planId: String,
        val steps: List<PlanStep>
    ) : AgentStreamEvent()

    /**
     * 计划已拒绝
     */
    data class PlanRejected(
        val planId: String,
        val reason: String
    ) : AgentStreamEvent()

    /**
     * 上下文已压缩
     */
    data class ContextCompressed(
        val originalTokens: Int,
        val compressedTokens: Int,
        val strategy: String
    ) : AgentStreamEvent()

    /**
     * T1.5 修复：ChatMode 自动建议
     *
     * 当用户未显式选择 mode 时，backend 通过 `ChatModeRouter.suggestChatMode` 推断出建议值，
     * 并以本事件 emit。UI 收到后可在 UI 上弹出轻提示（如 "已自动切换为编程模式"），
     * 让用户对 mode 选择有知情权。
     */
    data class ModeSuggestion(
        val effective: ChatMode,
        val suggestion: ChatMode,
        val userExplicit: Boolean
    ) : AgentStreamEvent()

    /**
     * 会话已迁移（如从旧会话恢复或切换）
     */
    data class SessionMigrated(
        val oldSessionId: String,
        val newSessionId: String,
        val messageCount: Int
    ) : AgentStreamEvent()

    /**
     * 对话完成
     */
    object Done : AgentStreamEvent()
}
