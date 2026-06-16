# StreamChunk 中转层重构

- **创建日期**: 2026-06-16
- **递增 ID**: 02
- **前置版本**: [01](./archive/StreamChunk中转层重构-2026-06-16-01.md)（平铺 StreamEvent 草案）
- **状态**: 待启动(本任务输入,尚未排期)
- **触发来源**: CodeBlock 事件化重构(2026-06-16)过程中识别
- **影响范围**: ModelAdapter / OpenAICompatibleAdapter / AnthropicStreamParser / GeminiAdapter / ModelGateway / EnhancedAgentLoop / 单元测试

---

## 1. 问题背景

### 1.1 当前架构

LLM 流式响应在 CodeSage 中经过三层:

```
LLM provider (SSE)
  → adapter.parseStreamChunk(line: String)            [第一步:解析单行]
       → StreamChunk(id, delta, reasoningDelta, done, toolCallDeltas, finishReason, usage, codeBlock)
  → ModelGateway chatStream (collect StreamChunk)      [第二步:中转]
  → EnhancedAgentLoop collect { chunk: StreamChunk }  [第三步:解包分发]
       → if (chunk.reasoningDelta != null) emit ModelReasoning
         else if (chunk.toolCallDeltas.isNotEmpty()) handle toolCalls
         else if (chunk.done) handle done
         else if (chunk.delta.isNotEmpty()) emit TextDelta
         else if (chunk.codeBlock != null) emit CodeBlock*
         else if (chunk.finishReason != null) record
```

### 1.2 StreamChunk 的"中转袋"本质

`StreamChunk` 是一个 8 字段的 union bag,设计目的是让 adapter 把"一个 SSE 行解析出的所有可能信息"塞进同一个对象,EnhancedAgentLoop 再解包。

加 CodeBlock 事件后从 7 字段涨到 8 字段。

### 1.3 已暴露的问题

1. **臃肿**:`StreamChunk` 字段只增不减,新事件类型(检索引用 / 计划步骤 / 多模态)都要往里塞
2. **类型不安全**:adapter 写 `StreamChunk(delta = "...", reasoningDelta = "...")` 时,两者本应互斥但**没有**编译期保证
3. **职责模糊**:adapter 只"识别",EnhancedAgentLoop 才"分发" — 任何一方改动都要追另一方
4. **测试绕弯**:单元测试断言 StreamChunk 各字段,而不是"adapter 识别出什么事件" — 概念不直接
5. **Anthropic 已经是反例**:`AnthropicStreamParser` 内部已经维护 `toolInputs` / `toolMetas` 跨行累积状态,而 Anthropic 协议本身有结构化事件(`content_block_start/delta/stop`),理论上**可以**直接 emit `AgentStreamEvent`,不走 StreamChunk 中转

### 1.4 现实观察(本版本新增)

调研 `EnhancedAgentLoop.kt` 后发现两个**额外问题**,需并入本次重构:

- **EnhancedAgentLoop 同时承担两件事**:
  1. **模型流事件处理**(高频,每帧一次)— 应该走 Reducer 模式
  2. **业务编排事件处理**(低频,每 turn 几次)— 应该走 Hook 策略模式
  - 当前用 if/else 链混在一起,加新业务钩子需要改 loop 主函数
- **业务编排事件**(`PlanStep` / `SubAgentStart` / `ContextCompressed` / `ModeSuggestion` / `SessionMigrated` 等 15+ 类)不属于"模型流事件",应作为独立概念拆分

---

## 2. 核心设计

### 2.1 设计目标

1. **协议层与业务层分离** — adapter 只负责"识别",不关心下游怎么用
2. **类型安全** — sealed type 强制穷举,加新事件编译器逼你补全
3. **扩展性 0 成本** — 未来加 Citation / Plan / 多模态 / 多候选,只动 1 个 case + 1 个 when 分支 + 1 个测试
4. **可读性优先** — Reducer 集中可见,Hook 可独立测试
5. **Anthropic 优势利用** — `content_block_*` 协议直接透传,不再走"字符串解析 + StreamChunk 中转"

### 2.2 3 层架构

```
┌─────────────────────┐
│  LLM Provider       │
│  (OpenAI/Claude/    │
│   Gemini/...)       │
└──────────┬──────────┘
           │ SSE: data: {...}\n\n
           ▼
┌─────────────────────────────────────┐
│ L1: StreamEventNormalizer           │
│   - OpenAIStreamNormalizer          │
│   - AnthropicStreamNormalizer       │
│   - GeminiStreamNormalizer          │
│  (stateless + StreamState 累积)     │
└──────────┬──────────────────────────┘
           │ List<StreamEvent>
           ▼
┌─────────────────────────────────────┐
│ L1.5: ModelGateway.chatStream       │
│   (Flow<StreamEvent>, 协程局部      │
│    累积 chunkCount / lastUsage)     │
└──────────┬──────────────────────────┘
           │ Flow<StreamEvent>
           ▼
┌─────────────────────────────────────┐
│ L2: TurnReducer                     │
│   (state, event) → (state, effects) │
│   纯函数,易于测试                   │
└──────────┬──────────────────────────┘
           │ List<AgentStreamEvent>
           ▼
┌─────────────────────────────────────┐
│ L3: EnhancedAgentLoop + Hooks       │
│   - 模型流事件 → Reducer 派发       │
│   - 业务编排事件 → Hook 策略列表    │
└─────────────────────────────────────┘
```

**各层职责边界**:

| 层 | 输入 | 输出 | 模式 |
|----|------|------|------|
| L1 Normalizer | 一行 SSE | `List<StreamEvent>` | stateless + 显式 `StreamState` |
| L1.5 Gateway | SSE 流 | `Flow<StreamEvent>` | 协程局部累积 chunk 数/usage |
| L2 Reducer | `(state, event)` | `(state, effects)` | 纯函数 when 派发 |
| L3 Loop/Hooks | effects | `Flow<AgentStreamEvent>` | Reducer + OOP Hook 列表 |

### 2.3 核心数据契约:分形 Sealed Event Tree

**关键决策**(本版本相对 01 的核心升级):从平铺 case 改为**嵌套分形树**。理由:
- 平铺 case 没有父类型,下游 handler 要为每个 case 写一遍 if/else
- 分形后,下游可以用父类型统一处理一类业务:
  ```kotlin
  fun onContent(event: StreamEvent.Content) { ... }       // 父类型入口
  fun onToolCall(event: StreamEvent.ToolCall) { ... }     // 父类型入口
  fun onFlow(event: StreamEvent.Flow) { ... }             // 父类型入口
  ```
- 未来加 `PlanStep` / `Citation` 等同类内容,只需要在对应子树加 case,**不动**其他子树的 handler

```kotlin
// === 文件位置: src/main/kotlin/com/codesage/model/adapter/StreamEvent.kt ===

/**
 * 多候选标识:所有内容类事件携带 choiceIndex,流控制类事件携带 choiceIndex。
 * 协议层约定: n=1 时所有 event.choiceIndex = 0。
 * 详见 §2.9 多候选 state 模型决策。
 */
interface ChoiceScoped {
    val choiceIndex: Int
}

sealed interface StreamEvent {

    /** 文本类内容(正文 / 推理 / 计划步骤,统一抽象) */
    sealed interface Content : StreamEvent, ChoiceScoped {
        val delta: String
        /** 普通正文片段 */
        data class Text(
            override val choiceIndex: Int = 0,
            override val delta: String,
        ) : Content
        /** 思考链片段(OpenAI reasoning_content / Anthropic thinking_delta / think 标签) */
        data class Reasoning(
            override val choiceIndex: Int = 0,
            override val delta: String,
        ) : Content
        /**
         * 计划步骤片段 —— Agent 工具的标志性功能
         * 来源: 模型在 reasoning 之外显式输出"Step 1: ...\nStep 2: ...",
         *       协议支持 Anthropic 4 plan 事件, OpenAI o-series 在 system prompt 引导下可产生。
         * 下游: Reducer 累积到 state.planSteps, emit AgentStreamEvent.PlanStep 给 UI。
         */
        data class PlanStep(
            override val choiceIndex: Int = 0,
            override val delta: String,
            val stepIndex: Int? = null,
        ) : Content
    }

    /** 工具调用(独立通道,可能与 Content 并行) */
    sealed interface ToolCall : StreamEvent, ChoiceScoped {
        val toolCallId: String
        val toolName: String?
        data class Delta(
            override val choiceIndex: Int = 0,
            override val toolCallId: String,
            override val toolName: String?,
            val argumentsFragment: String,
        ) : ToolCall
    }

    /** 代码块(带生命周期) */
    sealed interface CodeBlock : StreamEvent, ChoiceScoped {
        val codeBlockId: String
        data class Started(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
            val language: String?,
        ) : CodeBlock
        data class Delta(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
            override val delta: String,
        ) : CodeBlock
        data class Ended(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
        ) : CodeBlock
    }

    /** 引用/检索结果(RAG 场景,本版本预留占位不实现) */
    sealed interface Citation : StreamEvent, ChoiceScoped {
        val sourceId: String
        data class Delta(
            override val choiceIndex: Int = 0,
            override val sourceId: String,
            val snippetFragment: String,
            val title: String? = null,
            val url: String? = null,
        ) : Citation
    }

    /** 多模态内容(独立通道,本版本预留占位不实现) */
    sealed interface Media : StreamEvent, ChoiceScoped {
        val mimeType: String
        data class ImageFragment(
            override val choiceIndex: Int = 0,
            override val mimeType: String,
            val data: ByteArray,
        ) : Media
        data class AudioFragment(
            override val choiceIndex: Int = 0,
            override val mimeType: String,
            val data: ByteArray,
        ) : Media
    }

    /** 流控制事件(与"内容"互斥) */
    sealed interface Flow : StreamEvent, ChoiceScoped {
        data class Started(override val choiceIndex: Int = 0) : Flow
        data class Finished(
            override val choiceIndex: Int = 0,
            val finishReason: FinishReason,
            val usage: Usage? = null,
        ) : Flow
        data class Cancelled(override val choiceIndex: Int = 0) : Flow
        data class Error(
            override val choiceIndex: Int = 0,
            val message: String,
            val code: String? = null,
        ) : Flow
    }
}

/** 独立枚举,避免 String 类型不安全 */
enum class FinishReason {
    STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, UNKNOWN;
    companion object {
        fun from(raw: String?): FinishReason = when (raw) {
            "stop" -> STOP
            "tool_calls" -> TOOL_CALLS
            "length" -> LENGTH
            "content_filter" -> CONTENT_FILTER
            null -> STOP  // 大多数 provider 流结束时不带 finishReason
            else -> UNKNOWN
        }
    }
}
```

**本版本必做(端到端可跑通)**:
- Content.Text / Content.Reasoning / Content.PlanStep
- ToolCall.Delta
- CodeBlock.Started / Delta / Ended
- Flow.Started / Finished / Cancelled / Error

**本版本预留占位(树结构有 case, Normalizer 与 Reducer 暂不实现)**:
- Citation.Delta
- Media.ImageFragment / Media.AudioFragment


### 2.4 L1: Normalizer 抽象

```kotlin
// === 文件位置: src/main/kotlin/com/codesage/model/adapter/StreamEventNormalizer.kt ===

/**
 * 协议归一器:把"上游 SSE 一行"归一为 0..N 个 StreamEvent。
 *
 * 此抽象的目标:
 * 1. 利用 Anthropic 协议的结构化(content_block_* 直接透传)
 * 2. 把 OpenAI 的"字段名不统一"问题收敛到一处(reasoning_content / reasoning / thinking)
 * 3. 累积状态显式化(Anthropic tool input 跨行累积 → StreamState)
 */
abstract class StreamEventNormalizer {
    /**
     * 协议层累积状态(Anthropic tool input 跨多行累积 等场景)。
     * 与业务状态(TurnState)解耦,Normalizer 不感知业务。
     */
    data class StreamState(
        val messageId: String? = null,
        val pendingToolInputs: MutableMap<Int, StringBuilder> = mutableMapOf(),
        val toolMetas: MutableMap<Int, Pair<String, String>> = mutableMapOf(),
        val openCodeBlocks: MutableSet<String> = mutableSetOf(),
        val firstChunkSeen: Boolean = false,
    )

    /** 把一行 SSE 数据归一化为 0..N 个 StreamEvent */
    abstract fun normalize(line: String, state: StreamState): List<StreamEvent>

    /** 流关闭时调用,产出兜底事件(典型:流中断时未闭合的 code block) */
    open fun onStreamEnd(state: StreamState): List<StreamEvent> = emptyList()
}
```

**Anthropic 切口的代码形态**(本版本相对 01 的关键升级):

```kotlin
class AnthropicStreamNormalizer : StreamEventNormalizer() {
    override fun normalize(line: String, state: StreamState): List<StreamEvent> {
        val event = parseSseEvent(line) ?: return emptyList()
        return when (event.type) {
            "content_block_delta" -> when (event.delta.type) {
                "text_delta" -> listOf(StreamEvent.Content.Text(event.delta.text))
                "thinking_delta" -> listOf(StreamEvent.Content.Reasoning(event.delta.thinking))
                "input_json_delta" -> {
                    state.pendingToolInputs.getOrPut(event.index) { StringBuilder() }
                        .append(event.delta.partialJson)
                    emptyList()  // 累积到 content_block_stop 一次性产出
                }
                else -> emptyList()
            }
            "content_block_stop" -> {
                val args = state.pendingToolInputs.remove(event.index)?.toString() ?: "{}"
                val meta = state.toolMetas.remove(event.index)
                if (meta != null) {
                    listOf(StreamEvent.ToolCall.Delta(
                        toolCallId = meta.first, toolName = meta.second, argumentsFragment = args
                    ))
                } else emptyList()
            }
            "message_delta" -> listOf(StreamEvent.Flow.Finished(
                finishReason = FinishReason.from(event.delta.stopReason),
                usage = event.usage?.toUnifiedUsage(),
            ))
            "message_stop" -> listOf(StreamEvent.Flow.Finished(finishReason = FinishReason.STOP))
            "error" -> listOf(StreamEvent.Flow.Error(message = event.error.message))
            else -> emptyList()
        }
    }
}
```

`OpenAIStreamNormalizer` 把 `FencedCodeSplitter` (287 行) 作为内部组件复用,不再跨抽象层泄露。

### 2.5 L2: TurnReducer 纯函数模式

**关键决策**:`when` on sealed 在 Kotlin 中**就是**策略模式——比传统 OOP Strategy 接口**更强**(编译期穷举保证)。所以 reducer 用集中式 when,而不是为每个 case 写一个 Handler 类。

**但** business 编排事件(`PlanStep` / `SubAgentStart` 等)不属于模型流,应该走 Hook 策略模式(详见 2.6)。

```kotlin
// === 文件位置: src/main/kotlin/com/codesage/agent/core/TurnReducer.kt ===

/** 当前 turn 的累积状态(纯数据,无逻辑) */
data class TurnState(
    val assistantText: StringBuilder = StringBuilder(),
    val toolCalls: MutableMap<String, ToolCallBuilder> = mutableMapOf(),
    val codeBlocks: MutableMap<String, CodeBlockBuilder> = mutableMapOf(),
    val planSteps: MutableList<PlanStepBuilder> = mutableListOf(),
    val roundReasoningStarted: Boolean = false,
    val finishedReason: FinishReason? = null,
    val usage: Usage? = null,
    // 未来扩展位
    // val citations: MutableMap<String, CitationBuilder> = mutableMapOf(),
)

/**
 * 状态机:输入 (states, event) → (newStates, sideEffects)
 *
 * 关键决策(2026-06-16):采用 **路线 A —— Map<choiceIndex, TurnState>**。
 * n=1 时 Map 退化到 1 元素,性能开销可忽略;n>1 时多候选自然分桶。
 * 详见 §2.9 多候选 state 模型决策。
 */
class TurnReducer {
    fun reduce(
        states: Map<Int, TurnState>,
        event: StreamEvent,
    ): Pair<Map<Int, TurnState>, List<AgentStreamEvent>> {
        val idx = event.choiceIndex
        val state = states[idx] ?: TurnState()
        val result = reduceOne(state, event)
        return states + (idx to result.first) to result.second
    }

    private fun reduceOne(
        state: TurnState,
        event: StreamEvent,
    ): Pair<TurnState, List<AgentStreamEvent>> = when (event) {

        is StreamEvent.Content.Text -> state to listOf(
            AgentStreamEvent.TextDelta(event.delta).also {
                state.assistantText.append(event.delta)
            }
        )

        is StreamEvent.Content.Reasoning -> {
            val effects = mutableListOf<AgentStreamEvent>()
            if (!state.roundReasoningStarted) {
                effects += AgentStreamEvent.ModelReasoningRoundStart(0)
                state.roundReasoningStarted = true
            }
            effects += AgentStreamEvent.ModelReasoning(event.delta)
            state to effects
        }

        is StreamEvent.Content.PlanStep -> {
            // 累积到 state.planSteps,emit AgentStreamEvent.PlanStep 给 UI
            val builder = state.planSteps.getOrNull(event.stepIndex ?: state.planSteps.size)
                ?: PlanStepBuilder(stepIndex = event.stepIndex ?: state.planSteps.size)
                    .also { state.planSteps.add(it) }
            builder.append(event.delta)
            state to listOf(AgentStreamEvent.PlanStep(
                stepIndex = builder.stepIndex,
                delta = event.delta,
            ))
        }

        is StreamEvent.ToolCall.Delta -> {
            val builder = state.toolCalls.getOrPut(event.toolCallId) {
                ToolCallBuilder(event.toolCallId, event.toolName.orEmpty())
            }
            event.toolName?.let { builder.name = it }
            builder.arguments.append(event.argumentsFragment)

            val effects = mutableListOf<AgentStreamEvent>()
            if (event.toolName != null && builder.id.isNotEmpty()) {
                effects += AgentStreamEvent.ToolCallStart(...)
            }
            effects += AgentStreamEvent.ToolCallDelta(...)
            state to effects
        }

        is StreamEvent.CodeBlock.Started -> {
            state.codeBlocks[event.codeBlockId] = CodeBlockBuilder(event.language)
            state to listOf(AgentStreamEvent.CodeBlockStart(event.codeBlockId, event.language))
        }
        is StreamEvent.CodeBlock.Delta -> {
            state.codeBlocks[event.codeBlockId]?.append(event.delta)
            state to listOf(AgentStreamEvent.CodeBlockDelta(event.codeBlockId, event.delta))
        }
        is StreamEvent.CodeBlock.Ended -> {
            state.codeBlocks.remove(event.codeBlockId)
            state to listOf(AgentStreamEvent.CodeBlockEnd(event.codeBlockId))
        }

        is StreamEvent.Flow.Started -> state to emptyList()

        is StreamEvent.Flow.Finished -> {
            val effects = mutableListOf<AgentStreamEvent>()
            // 兜底 1:reasoning round 关闭
            if (state.roundReasoningStarted) {
                effects += AgentStreamEvent.ModelReasoningRoundEnd(0)
            }
            // 兜底 2:open code block 关闭(流中断但未 Ended)
            state.codeBlocks.values.filter { it.isOpen }.forEach { openBlock ->
                effects += AgentStreamEvent.CodeBlockEnd(openBlock.id)
            }
            effects += AgentStreamEvent.Done(...)
            state.copy(
                finishedReason = event.finishReason,
                usage = event.usage ?: state.usage,
            ) to effects
        }

        is StreamEvent.Flow.Cancelled -> state to listOf(AgentStreamEvent.Done(...))
        is StreamEvent.Flow.Error -> state to listOf(AgentStreamEvent.Error(event.message))

        is StreamEvent.Citation.Delta -> { /* 未来扩展位,本版本 reducer 不实现 */ state to emptyList() }
        is StreamEvent.Media.ImageFragment,
        is StreamEvent.Media.AudioFragment -> { /* 未来扩展位 */ state to emptyList() }
    }
}
}
```

**收益**:
1. **纯函数 reducer** → table-driven test,`assertEquals(expectedEffects, reduce(state, event).second)`
2. **派生状态(兜底)显式化** — 不再藏在 if/else 链里
3. EnhancedAgentLoop `collect` 块退化成 ~10 行:
   ```kotlin
   var state = TurnState()
   gateway.chatStream(request).collect { event ->
       if (interrupted) return@collect
       val (newState, effects) = reducer.reduce(state, event)
       state = newState
       effects.forEach { emitEvent(it) }
   }
   ```

### 2.6 L3: Hook 策略模式(业务编排事件)

**关键决策**:把 EnhancedAgentLoop 里的**业务编排事件**处理逻辑拆出,作为独立 `TurnLifecycleHook` 列表注入:

```kotlin
// === 文件位置: src/main/kotlin/com/codesage/agent/core/TurnLifecycleHook.kt ===

interface TurnLifecycleHook {
    fun onTurnStart(state: TurnState): List<AgentStreamEvent> = emptyList()
    fun onTurnEnd(state: TurnState): List<AgentStreamEvent> = emptyList()
    fun onToolExecuted(result: ToolResult, state: TurnState): List<AgentStreamEvent> = emptyList()
    fun onError(error: Throwable, state: TurnState): List<AgentStreamEvent> = emptyList()
}

// === 具体实现,每个职责一个类 ===
class ContextCompressionHook : TurnLifecycleHook { ... }
class SessionMigrationHook : TurnLifecycleHook { ... }
class ModeSuggestionHook : TurnLifecycleHook { ... }
class ToolConfirmationHook : TurnLifecycleHook { ... }
class SubAgentDispatchHook : TurnLifecycleHook { ... }

// === EnhancedAgentLoop 不再写业务逻辑,只做组装 ===
class EnhancedAgentLoop(
    private val streamReducer: TurnReducer,
    private val turnHooks: List<TurnLifecycleHook>,  // 策略列表,运行时可替换
    // ...
) {
    suspend fun run(...): Flow<AgentStreamEvent> = flow {
        var state = TurnState()

        // 模型流:Reducer 派发
        gateway.chatStream(request).collect { event ->
            if (interrupted) return@collect
            val (newState, effects) = streamReducer.reduce(state, event)
            state = newState
            effects.forEach { emit(it) }
        }

        // 业务编排:Hook 列表
        turnHooks.forEach { hook ->
            hook.onTurnEnd(state).forEach { emit(it) }
        }
    }
}
```

**两种模式分工**:

| 模式 | 适用场景 | 原因 |
|------|---------|------|
| Reducer (when) | 模型流事件(~12 case,高频,固定) | 编译期穷举,集中可见 |
| Hook (OOP) | 业务编排事件(15+ 类,低频,可变) | 可插拔,可独立测试,运行时替换 |

### 2.7 公共 API 破坏性变更(决策点)

`ModelGateway.chatStream` 当前签名:
```kotlin
fun chatStream(request: ChatRequest): Flow<StreamChunk>
```

新签名:
```kotlin
fun chatStream(request: ChatRequest): Flow<StreamEvent>
```

**影响面**:AgentCore + 4 个 EnhancedAgentLoopTest mock + 其他可能调用方。

**方案选择**:
- **方案 A(推荐)**:破坏性变更一次到位,所有调用方同步迁移
- **方案 B**:保留 `Flow<StreamChunk>` 旧 API + 加 `Flow<StreamEvent>` 新 API 双轨过渡

**建议方案 A**:本任务工作量已足够大(2 个架构改造 + 14 个生产文件 + 19 个测试文件),不做双轨。

### 2.8 适配范围声明(基于协议调研确定)

**调研依据**: [docs/research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md](../../research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md)

**本版本必做(端到端可跑通, 协议层有强支持)**:
- ✅ **文本(正文)**: `Content.Text` → `AgentStreamEvent.TextDelta`
- ✅ **推理/思考**: `Content.Reasoning` → `ModelReasoningRoundStart/End` + `ModelReasoning`
  - 协议层支持: OpenAI `reasoning_content` / Anthropic `thinking_delta` / think 标签
- ✅ **工具调用增量**: `ToolCall.Delta` → `ToolCallStart/Delta`
  - 协议层支持: OpenAI `tool_calls` / Anthropic `input_json_delta` (累积到 `content_block_stop`)
- ✅ **代码块 Start/Delta/End**: OpenAI 围栏 → `CodeBlockStart/Delta/End`
  - 协议层: 无原生, 靠 `FencedCodeSplitter` (287 行已存在) 识别
- ✅ **Flow 生命周期**: `Started` / `Finished` / `Cancelled` / `Error`
  - 协议层支持: 全 provider 原生 (`[DONE]` / `message_stop` / `message_delta` `stop_reason`)
- ✅ **3 层架构骨架**: Normalizer / Reducer / Hook 分层
- ✅ **业务 Hook 拆分**: 5 个初始 Hook 类(ToolConfirmation / SubAgentDispatch / ContextCompression / ModeSuggestion / SessionMigration)

**本版本预留占位(树结构有 case, Normalizer 与 Reducer 暂不实现)**:
- ⚠️ **`Content.PlanStep`** — 协议层仅 Anthropic 4.x Beta 支持, OpenAI/Gemini 靠 prompt 引导
  - 调研结论: 协议不统一, 本版本不实现
  - 未来任务: [PlanStep-跨协议适配.md](future-tasks/PlanStep-跨协议适配.md)
- ⚠️ **`Content.Options`** — 无任何 LLM 协议层支持
  - 调研结论: 纯应用层概念, 当前走纯文本解析(前端实现)
  - 未来任务: [用户多选项交互.md](future-tasks/用户多选项交互.md)
- ⚠️ **`choiceIndex` 多候选字段** — OpenAI/Gemini/DeepSeek 协议层有 `n` 参数
  - 调研结论: 当前无真实业务需求, Agent 投票可走多次调用
  - Normalizer 强制 `n=1` 时 `choiceIndex=0` (本版本协议层就绪)
  - 未来任务: [多候选响应-n1协议层暴露.md](future-tasks/多候选响应-n1协议层暴露.md)
- ⚠️ **Citation.Delta** — 引用/检索片段, RAG 场景才需要
- ⚠️ **Media.ImageFragment** / **Media.AudioFragment** — 多模态, 要看产品方向

**本版本未纳入(树里也没有, 未来需要时再加)**:
- ❌ 安全/审核事件(`safety_ratings` / `content_filter` 中间事件) — provider 通常用 `finishReason` 兜底
- ❌ 流元数据(`system_fingerprint` / `created` / `service_tier`) — 调试用, 价值低
- ❌ 视频流 — OpenAI Sora / Google Veo 独立 endpoint, 不走 chat 通道
- ❌ 可恢复流控制(`previous_response_id` / `resume_from`) — 企业级特性
- ❌ 结构化输出(OpenAI `response_format` / JSON Schema 增量) — 让用户复用 `tool_calls` 通道

**本版本聚焦总工作量**: ~4.5d(详见 §3 拆分建议)

**未纳入项的"加进来"成本**: 每项 ~30-60 分钟(树加 case + Normalizer 映射 + Reducer when 分支 + 1 组测试), 详见 §5 未来扩展性验证。

### 2.9 多候选 state 模型决策(本版本新增)

**问题**: 当 `n>1` 时, Reducer 内部维护的 `TurnState`(assistantText / toolCalls / codeBlocks)需要按 `choiceIndex` 拆分, 不能把多个候选的文本混到同一个 `assistantText`。

**三条路线对比**:

| 路线 | 实现 | 优点 | 缺点 |
|------|------|------|------|
| **A. 单 Reducer + Map<choiceIndex, TurnState>** | `reduce(states: Map<Int, TurnState>, event)` | Reducer 仍单函数, 逻辑统一; n=1 退化到 1 元素开销可忽略 | 多 Map 略有结构成本 |
| B. TurnReducer 实例化(每个 choiceIndex 一份) | EnhancedAgentLoop 维护 `Map<Int, TurnReducer>` | n=1 时无开销 | "父类型"派生逻辑(reasoning round 关闭兜底)每个实例独立, 不一致风险 |
| C. 单 choiceIndex 时退化 | 协议层约定 n=1 时 `choiceIndex = 0`, Reducer 维持 TurnState 单实例; n>1 时升级到 Map | 简单路径性能最优 | 两条代码路径, 测试要覆盖两种 |

**决策(2026-06-16)**: **采用路线 A**

**理由**:
1. **Reducer 逻辑统一**: 所有 case 共享同一份 `reduceOne` 实现, 不会出现"n=1 走一条逻辑, n>1 走另一条"的不一致
2. **测试简单**: table-driven 测试固定 `states = mapOf(0 to TurnState())`, 不需要 mock "n=1 vs n>1" 双路径
3. **n=1 性能开销可忽略**: 单元素的 `HashMap` 与裸对象访问差异在纳秒级
4. **n>1 场景渐进增强**: 业务从 n=1 升级到 n=3 时, 协议层 + state 层都不需要改, 只需要 UI 渲染按 choiceIndex 分卡片

**协议层约定**(写进 Normalizer 实现约定):
- `n=1` 时: 所有 `event.choiceIndex = 0` (Normalizer 强制 default)
- `n>1` 时: Normalizer 从 `choices[i].delta` 提取 `choiceIndex = i`

**UI 渲染约定**(写进 EventConsumer 实现约定):
- 同 `choiceIndex` 的 effects 渲染到同一张卡片
- 跨 `choiceIndex` 渲染为并列卡片("方案 A / 方案 B / 方案 C")
- `Flow.Finished` 触发卡片状态切换为"完成"

---

## 3. 拆分建议(本版本调整)

按 **"层"** 拆分而非按"adapter"拆分,理由:reducer + hook 改造与 adapter 改造**强耦合**(EnhancedAgentLoop 改了才能用新 StreamEvent),单 adapter 切完无法端到端验证。

| Commit | 范围 | 涉及文件 | 工作量 |
|--------|------|---------|--------|
| 1: 契约基础 | `StreamEvent` (分形 tree, 含 `choiceIndex` 协议层占位) + `FinishReason` + `ChoiceScoped` + 新 `parseStreamChunk` 签名 + 删 `StreamChunk` | `StreamEvent.kt`(新) / `ModelAdapter.kt` / `ChatModels.kt` | 0.5d |
| 2: L1 Normalizer | 3 个 Normalizer 适配 (本版本不实现 PlanStep/Options 解析) | `StreamEventNormalizer.kt`(新) / `OpenAIStreamNormalizer.kt`(新) / `AnthropicStreamNormalizer.kt`(新) / `GeminiStreamNormalizer.kt`(新) / 3 个 Adapter 改签名 | 1.5d |
| 3: L1.5 Gateway | `ModelGateway.chatStream` 改返回 `Flow<StreamEvent>` + 跨 chunk 累积状态重挂载 | `ModelGateway.kt` | 0.5d |
| 4: L2 Reducer | `TurnState` + `TurnReducer` 纯函数实现 (本版本 Reducer 内 PlanStep/Options/Citation/Media 走 `state to emptyList()` 占位) + 全覆盖单测 | `TurnReducer.kt`(新) / `TurnState.kt`(新) | 0.5d |
| 5: L3 Loop + Hook | EnhancedAgentLoop collect 块改 Reducer 派发 + 5 个初始 Hook 类 + AgentCore.chatStream 迁移 | `EnhancedAgentLoop.kt` / `AgentCore.kt` / `TurnLifecycleHook.kt`(新) / 5 个 Hook 类(新) | 1d |
| 6: 验证清理 | 跑全套测试 + 5+ provider 真实对话验证 + 文档/EVENT_PROTOCOL 同步 | 测试 + 文档 | 0.5d |

**总计**: 约 4.5 个工作日 (聚焦骨架, 不做 PlanStep/Options/多候选/n>1 协议层暴露)

**本版本未做项** (调研后判定为单独立项):
- ❌ PlanStep 跨协议适配 → [future-tasks/PlanStep-跨协议适配.md](future-tasks/PlanStep-跨协议适配.md) (~2-3d)
- ❌ 用户多选项交互 → [future-tasks/用户多选项交互.md](future-tasks/用户多选项交互.md) (方案 A 纯前端 ~1-2d, 可独立推进)
- ❌ 多候选 n>1 协议层暴露 → [future-tasks/多候选响应-n1协议层暴露.md](future-tasks/多候选响应-n1协议层暴露.md) (~1.5-2d, 等真实业务场景)
- ❌ Hook 清单梳理 → [future-tasks/Hook清单梳理.md](future-tasks/Hook清单梳理.md) (~1-1.5d, 等 StreamChunk 重构稳定后)

---

## 4. 风险点

### 4.1 大量 adapter 测试需要重写

`StreamChunk` 是测试断言目标,改成 `StreamEvent` 后:
- 3 个 Normalizer 的 ~25 个 `parseStreamChunk` 测试 → 改为 `assertEquals(listOf(StreamEvent.Content.Text("..."), ...), normalize(line, state))`
- EnhancedAgentLoopTest 4 处 `chatStream` mock → 改为 `Flow<StreamEvent>`,直接 emit StreamEvent
- 7 个 EnhancedAgentLoop/*Test 涉及导入/断言更新

### 4.2 ModelGateway 跨 chunk 累积状态重设计

当前在 `chatStream` 的 `flow {}` body 内作为协程局部 `var`:
- `chunkCount`
- `lastUsage`
- `lastFinishReason`
- `lastChunkDone`
- `emittedAnyChunk`

新契约下依然在协程局部(因为是 `flow {}` body 内),代码改动只是**提取源**从 `chunk.usage` 改成 `event` 的 `is Flow.Finished` 提取。**结构不变,风险可控**。

### 4.3 done 兜底逻辑

当前在 ModelGateway 里用 `lastChunkDone` 跟踪。新契约下改为跟踪 `Flow.Finished` 事件是否出现过。

### 4.4 Anthropic parser 状态化测试

`AnthropicStreamNormalizer` 内部维护 `pendingToolInputs` / `toolMetas` map(跨多次 `normalize` 调用),需要在同一个 normalizer 实例上多次调用才能验证 `ToolCall.Delta`——**测试基础设施**要带 `StreamState` 显式参数,比 OpenAI 麻烦。

### 4.5 业务 Hook 拆分影响面

`TurnLifecycleHook` 是**新增抽象**,不替换既有行为(只是把 EnhancedAgentLoop 内的 if/else 块搬到独立类)。**没有运行时行为变化**,只是结构调整。风险低。

### 4.6 公共 API 破坏

`Flow<StreamChunk>` → `Flow<StreamEvent>` 是破坏性变更。一次性迁移,不留双轨(详见 2.7)。

### 4.7 未做项的实现路径(本版本)

**PlanStep 跨协议适配**: 本版本不实现, Normalizer 收到相关事件时丢弃。详见 [future-tasks/PlanStep-跨协议适配.md](future-tasks/PlanStep-跨协议适配.md)。

**用户多选项交互**: 本版本不实现协议层事件, 走前端纯文本解析。详见 [future-tasks/用户多选项交互.md](future-tasks/用户多选项交互.md)。

**多候选 n>1**: 本版本协议层 `choiceIndex` 字段就绪, Normalizer 强制 `n=1` 时 `choiceIndex=0` (Map 始终单元素, 不存在"全部完成判断"风险)。`ChatRequest.n` 不暴露, 应用层暂不调用 n>1。详见 [future-tasks/多候选响应-n1协议层暴露.md](future-tasks/多候选响应-n1协议层暴露.md)。

**Hook 清单补全**: 本版本只实现 5 个初始 Hook, 其他业务事件仍由 EnhancedAgentLoop 内部处理。详见 [future-tasks/Hook清单梳理.md](future-tasks/Hook清单梳理.md)。

---

## 5. 未来扩展性验证(本版本新增)

新 `StreamEvent` 树对未来事件的扩展成本:

| 未来事件 | 涉及改动 | 工作量 |
|---------|---------|--------|
| Citation/检索片段 | 1. `StreamEvent.Citation.Delta` 已存在,无需新增<br>2. Normalizer 加映射<br>3. Reducer 加 when 分支(本版本已预留位置)<br>4. 测 3 个新测试 | **30 分钟** |
| Plan/Step | 1. `StreamEvent.Content.PlanStep` 已存在,无需新增<br>2. Normalizer 加映射<br>3. Reducer 加 when 分支<br>4. 测 3 个新测试 | **30 分钟** |
| 多候选 (n>1) | 1. `Flow.choiceIndex` 字段已存在,无需新增<br>2. Normalizer 加映射(`event.choices[i].delta` → Content + `choiceIndex`)<br>3. Reducer 几乎不用改(已经接受 `choiceIndex`)<br>4. 测 3 个新测试 | **1 小时** |
| 图像/音频 | 1. `StreamEvent.Media.*` 已存在,无需新增<br>2. Normalizer 加映射(Gemini multimodal)<br>3. Reducer 加 when 分支<br>4. 测 3 个新测试 | **1 小时** |
| 安全/审核 | 1. 复用 `Flow.Finished(finishReason = CONTENT_FILTER)`,无需新增<br>2. Normalizer 提取 `safetyRatings`<br>3. Reducer 加 when 分支(可选,用于区分中间审核 vs 终止审核) | **1 小时** |

**总验证结论**:分形 sealed tree + 预留未来 case 后,**任何新事件类型的接入成本 = 1 个 normalizer 映射 + 1 个 reducer when 分支 + 1 组测试**,30-60 分钟/事件。

如果采用 StreamChunk 旧设计(8 字段扩到 13 字段),每个新事件的工作量是上述的 **3-5 倍**(要改 ChatModels.kt 的字段、EnhancedAgentLoop 的 if/else 链、所有 mock 的字段赋值)。

---

## 6. 后续观察

### 6.1 关联任务

- 2026-06-16: CodeBlock 事件化重构(本次主线,产生本文档)
  - `StreamChunk.codeBlock: CodeBlockEvent?` 字段是过渡方案
  - 后续 2026-XX-XX: 执行本文档的重构
- 2026-06-16: 业务 Hook 拆分识别(本版本新增)
  - `EnhancedAgentLoop` 内 15+ 类业务编排事件应拆为独立 Hook 列表
  - 拆出后 EnhancedAgentLoop 主体退化为"组装者",不写业务逻辑

### 6.2 决策记录

- 2026-06-16 (01 版本): 决定**先**做 CodeBlock 事件化(保留 StreamChunk 中转),**后**做本任务(删除 StreamChunk)
- 2026-06-16 (02 版本): 决定**升级**为分形 sealed tree(从 01 的平铺 8 case 升级到嵌套分形),**纳入**业务 Hook 拆分
- 理由:
  - 分形 tree 让"父类型统一处理"成为可能, reducer 主派发与未来事件扩展解耦
  - Hook 拆分让 EnhancedAgentLoop 从 1300 行 if/else 链变成 ~50 行组装者
  - 两者并入一次重构, 避免分两次做同样的上下文切换
- 2026-06-16 (02 版本 二次修订): 决定**升级 `Content.PlanStep` + 多候选 n>1 为本版本必做**
- 理由:
  - `Content.PlanStep` 是 Agent 工具的标志性功能(区别于普通聊天), Anthropic 4 协议层已支持, OpenAI o-series 在 system prompt 引导下可产生
  - 多候选 n>1 修当前 StreamChunk 的隐性 bug(`choices.firstOrNull()` 静默丢弃 `index > 0` 候选), 同时为"方案对比 / 子 agent 投票"场景提供基础
  - 两者接入成本 < 1.5d, 推迟会欠技术债(下次还得回头扩 StreamEvent 树)
- 2026-06-16 (02 版本 二次修订): 决定多候选 state 模型采用 **路线 A (单 Reducer + Map<choiceIndex, TurnState>)**
- 理由:
  - Reducer 逻辑统一, 不会出现 n=1 走一条逻辑 n>1 走另一条的不一致
  - table-driven 测试固定 `states = mapOf(0 to TurnState())`, 不需要 mock 双路径
  - n=1 性能开销可忽略(单元素 HashMap), n>1 渐进增强不需要改协议层和 state 层
- 2026-06-17 (02 版本 三次修订): 基于**协议调研** ([调研文档](../../research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md)) **收回** 二次修订中"PlanStep + 多候选必做"决策
- 理由:
  - **PlanStep**: 协议层仅 Anthropic 4.x Beta 支持, OpenAI/Gemini/DeepSeek 全靠 prompt 引导;协议不统一, 强行做要分 3 套实现
  - **多候选 n>1**: CodeSage 当前 99% 场景不用, Agent 内部投票可走多次串行/并发调用;修"firstOrNull 隐性 bug"在无业务需求时是过度修复
  - **Content.Options**: 无任何 LLM 协议层支持, 是应用层概念, 不该做协议事件
  - 三者都**单独立项** ([future-tasks/](../refactor/future-tasks/)) 比塞进本次重构更合理:
    - PlanStep-跨协议适配.md (~2-3d)
    - 用户多选项交互.md (方案 A 纯前端 ~1-2d, 不依赖本次重构)
    - 多候选响应-n1协议层暴露.md (~1.5-2d, 等真实业务场景)
    - Hook清单梳理.md (~1-1.5d, 等本次重构稳定)
- **本次重构聚焦 4.5d 骨架** (3 层架构 + 5 个初始 Hook + 协议层 choiceIndex 占位)

### 6.3 涉及文件清单

| 文件 | 改动类型 |
|------|---------|
| `src/main/kotlin/com/codesage/model/adapter/StreamEvent.kt` | **新建**(分形 sealed tree) |
| `src/main/kotlin/com/codesage/model/adapter/StreamEventNormalizer.kt` | **新建**(抽象类) |
| `src/main/kotlin/com/codesage/model/adapter/OpenAIStreamNormalizer.kt` | **新建** |
| `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicStreamNormalizer.kt` | **新建** |
| `src/main/kotlin/com/codesage/model/adapter/google/GeminiStreamNormalizer.kt` | **新建** |
| `src/main/kotlin/com/codesage/agent/core/TurnReducer.kt` | **新建**(纯函数 reducer, **Map<choiceIndex, TurnState>** 路线 A) |
| `src/main/kotlin/com/codesage/agent/core/TurnState.kt` | **新建**(状态数据, **含 `planSteps` 累积**) |
| `src/main/kotlin/com/codesage/agent/core/PlanStepBuilder.kt` | **新建**(PlanStep 累积 builder, 与 ToolCallBuilder / CodeBlockBuilder 同级) |
| `src/main/kotlin/com/codesage/agent/core/ChoiceScoped.kt` | **新建**(`choiceIndex` 公共接口) |
| `src/test/kotlin/.../agent/core/TurnReducerMultiChoiceTest.kt` | **新建**(n=1 / n=3 双场景 table-driven) |
| `src/test/kotlin/.../adapter/OpenAIStreamNormalizerNChoiceTest.kt` | **新建**(OpenAI `n=3` 回归测试, 修当前隐性 bug) |
| `src/test/kotlin/.../adapter/OpenAIStreamNormalizerPlanStepTest.kt` | **新建**(OpenAI PlanStep 解析 + system prompt 引导场景) |
| `src/test/kotlin/.../adapter/anthropic/AnthropicStreamNormalizerPlanStepTest.kt` | **新建**(Anthropic 协议层 plan 事件透传) |
| `src/main/kotlin/com/codesage/agent/core/TurnLifecycleHook.kt` | **新建**(OOP 策略接口) |
| `src/main/kotlin/com/codesage/agent/core/hooks/*Hook.kt` | **新建**(5+ 个具体 Hook) |
| `src/main/kotlin/com/codesage/model/adapter/ModelAdapter.kt` | 改 `parseStreamChunk` 签名 |
| `src/main/kotlin/com/codesage/model/dto/ChatModels.kt` | 删 `StreamChunk` / `StreamToolCallDelta` |
| `src/main/kotlin/com/codesage/model/dto/FinishReason.kt` | **新建**(枚举) |
| `src/main/kotlin/com/codesage/model/adapter/OpenAICompatibleAdapter.kt` | 改 `parseStreamChunk` 实现(委托给 Normalizer) |
| `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicStreamParser.kt` | 改写(委托给 Normalizer) |
| `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicAdapter.kt` | 改签名 |
| `src/main/kotlin/com/codesage/model/adapter/google/GeminiAdapter.kt` | 改 `parseStreamChunk` |
| `src/main/kotlin/com/codesage/model/gateway/ModelGateway.kt` | 改 `chatStream` 返回 `Flow<StreamEvent>` |
| `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` | collect 块改 Reducer 派发 + 业务拆 Hook |
| `src/main/kotlin/com/codesage/agent/core/AgentCore.kt` | `chatStream` 改 `chunk.delta` → `event is Content.Text` |
| `src/test/kotlin/.../adapter/*.kt` | 单元测试重写 |
| `src/test/kotlin/.../agent/core/EnhancedAgentLoopTest.kt` | 4 处 mock + 部分断言更新 |
| `src/test/kotlin/.../agent/core/TurnReducerTest.kt` | **新建**(纯函数 table-driven) |
| `src/test/kotlin/.../agent/core/hooks/*HookTest.kt` | **新建**(每个 Hook 独立测) |

---

## 7. 启动检查清单(下次接手时用)

- [ ] 确认本次 CodeBlock 事件化重构已稳定运行 ≥ 1 周
- [ ] 评估 `EventConsumer` / `EventRouter` 是否需要同步简化
- [ ] 评估 `run-log.js` 前端事件名是否需要同步更新
- [ ] 评估 `EVENT_PROTOCOL.md` 文档是否需要同步更新
- [ ] 拆分 PR:建议按"层"切(契约 → Normalizer → Gateway → Reducer → Loop+Hooks → 验证)
- [ ] 全套单元测试 + 集成测试通过
- [ ] 手动触发 5+ provider 的真实对话(MiniMax-M3 / Claude / GPT / Gemini / DeepSeek),验证流式增强体验
- [ ] 验证 `TurnLifecycleHook` 拆分后既有业务行为不变(对比重构前后的 AgentStreamEvent 序列)
- [ ] **手动验证多候选 n=3 场景**: 同 prompt 触发 3 个候选, 确认 3 张并列卡片分别渲染, choiceIndex 不会串数据
- [ ] **手动验证 PlanStep 场景**: Anthropic 4 走协议层 plan 事件, OpenAI 走 system prompt 引导, 两种来源 UI 渲染一致
- [ ] 评估各 provider 的 `n` 上限(OpenAI 默认 1, Anthropic 4 限 5, Gemini 限 8)写进文档
