# StreamChunk 中转层重构 (ARCHIVED — 草稿,已被 02 文档替代)

- **创建日期**: 2026-06-16
- **递增 ID**: 01
- **状态**: 🗄️ ARCHIVED — 已被 [02 文档](../StreamChunk中转层重构-2026-06-16-02.md) 替代
- **归档日期**: 2026-06-17
- **归档原因**: 02 文档基于 01 草稿的反馈迭代到收敛方案,本任务按 02 文档执行完成
- **本任务范围**: 仅供历史参考,新设计/讨论请查阅 02 文档
- **触发来源**: CodeBlock 事件化重构(2026-06-16)过程中识别
- **影响范围**: OpenAICompatibleAdapter / AnthropicStreamParser / GeminiAdapter / ModelGateway / EnhancedAgentLoop / 单元测试

---

## 1. 问题背景

### 1.1 当前架构

LLM 流式响应在 CodeSage 中经过三层:

```
LLM provider (SSE)
  → adapter.parseStreamChunk(line: String)            [第一步:解析单行]
       → StreamChunk(id, delta, reasoningDelta, done, toolCallDeltas, finishReason, usage, ...)
  → ModelGateway chatStream (collect StreamChunk)      [第二步:中转]
  → EnhancedAgentLoop collect { chunk: StreamChunk }  [第三步:解包分发]
       → if (chunk.reasoningDelta != null) emit ModelReasoning
         else if (chunk.toolCallDeltas.isNotEmpty()) handle toolCalls
         else if (chunk.done) handle done
         else if (chunk.delta.isNotEmpty()) emit TextDelta
         else if (chunk.codeBlock != null) emit CodeBlock*   (CodeBlock 改造后)
         else if (chunk.finishReason != null) record
```

### 1.2 StreamChunk 的"中转袋"本质

`StreamChunk` 是一个 8 字段的 union bag,设计目的是让 adapter 把"一个 SSE 行解析出的所有可能信息"塞进同一个对象,EnhancedAgentLoop 再解包。

```
val codeBlock: CodeBlockEvent? = null  // 新增第 8 字段
```

加 CodeBlock 事件后从 7 字段涨到 8 字段。

### 1.3 已暴露的问题

1. **臃肿**:`StreamChunk` 字段只增不减,新事件类型(CodeBlock / 未来 Plan / 未来 RAG)都要往里塞
2. **类型不安全**:adapter 写 `StreamChunk(delta = "...", reasoningDelta = "...")` 时,两者本应互斥但**没有**编译期保证
3. **职责模糊**:adapter 只"识别",EnhancedAgentLoop 才"分发" — 任何一方改动都要追另一方
4. **测试绕弯**:单元测试断言 StreamChunk 各字段,而不是"adapter 识别出什么事件" — 概念不直接
5. **Anthropic 已经是反例**:`AnthropicStreamParser` 内部直接 emit 不同 StreamChunk,但 Anthropic 协议本身有结构化事件(`content_block_start/delta/stop`),理论上**可以**直接 emit `AgentStreamEvent`,不走 StreamChunk 中转

---

## 2. 重构方向

### 2.1 核心思路

**让 adapter 直接 emit `AgentStreamEvent`,删掉 StreamChunk 中转层。**

把"adapter 解析单行 → emit 结构化事件"作为唯一约定,`StreamChunk` 降级为内部类型或删除。

### 2.2 新数据契约

```kotlin
// 在 com.codesage.model.adapter.StreamEvent.kt (新文件)

sealed interface StreamEvent {
    data class Text(val delta: String) : StreamEvent
    data class Reasoning(val delta: String) : StreamEvent
    data class CodeBlockStart(
        val codeBlockId: String,
        val language: String?,
        val filePath: String? = null,
    ) : StreamEvent
    data class CodeBlockDelta(
        val codeBlockId: String,
        val delta: String,
    ) : StreamEvent
    data class CodeBlockEnd(
        val codeBlockId: String,
        val filePath: String? = null,
    ) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    ) : StreamEvent
    data class Finish(val reason: String?) : StreamEvent
    data class Usage(val usage: com.codesage.model.dto.Usage) : StreamEvent
    data class Done(val usage: com.codesage.model.dto.Usage? = null) : StreamEvent
}

// ModelAdapter.kt
abstract fun parseStreamChunk(chunk: String): List<StreamEvent>
```

### 2.3 改动后的三层

```
LLM provider (SSE)
  → adapter.parseStreamChunk(line)                     [第一步:解析单行,直接 emit 事件]
       → List<StreamEvent>  (Text / Reasoning / CodeBlock* / ToolCallDelta / Finish / Usage / Done)
  → ModelGateway chatStream (collect StreamEvent)      [第二步:透传,只关心 Done / Usage 收尾]
  → EnhancedAgentLoop collect { event: StreamEvent }   [第三步:each event → 1 个 AgentStreamEvent]
       → 1:1 直接映射,不再解包
```

EnhancedAgentLoop 内部**直接**根据 sealed type 派发:

```kotlin
when (event) {
    is StreamEvent.Text -> emitEvent(TextDelta(event.delta))
    is StreamEvent.Reasoning -> emitEvent(ModelReasoning(event.delta))
    is StreamEvent.CodeBlockStart -> emitEvent(CodeBlockStart(event.codeBlockId, event.language))
    is StreamEvent.CodeBlockDelta -> emitEvent(CodeBlockDelta(event.codeBlockId, event.delta))
    is StreamEvent.CodeBlockEnd -> emitEvent(CodeBlockEnd(event.codeBlockId))
    is StreamEvent.ToolCallDelta -> 累积参数 + emit ToolCallStart/Delta
    is StreamEvent.Finish -> record finishReason
    is StreamEvent.Usage -> record usage
    is StreamEvent.Done -> emit Done 触发收尾
}
```

### 2.4 兼容 Anthropic 的天然优势

Anthropic SSE 协议本身就是结构化的(`content_block_start` / `content_block_delta` / `content_block_stop`),**直接**透传为 `StreamEvent.ContentBlockStart/Delta/Stop`,无需字符串解析。这是 OpenAI 系没有的优势。

---

## 3. 拆分建议

### 3.1 预计 commit 拆分(3-4 个)

| Commit | 范围 | 涉及文件 | 工作量 |
|--------|------|---------|--------|
| 1: 新契约 | 加 `sealed interface StreamEvent` + 新 `parseStreamChunk` 签名 | `StreamEvent.kt`(新) / `ModelAdapter.kt` | 0.5d |
| 2: OpenAI 适配 | `OpenAICompatibleAdapter` 改成返回 `List<StreamEvent>` | `OpenAICompatibleAdapter.kt` / 测试 | 1d |
| 3: Anthropic 适配 | `AnthropicStreamParser` 改成返回 `List<StreamEvent>`(透传 content_block_*) | `AnthropicStreamParser.kt` / 测试 | 0.5d |
| 4: Gemini 适配 | `GeminiAdapter` 改成返回 `List<StreamEvent>` | `GeminiAdapter.kt` / 测试 | 0.5d |
| 5: Gateway & Loop 改造 | `ModelGateway` / `EnhancedAgentLoop` 直接消费 `StreamEvent` | 2 文件 / 测试 | 1d |
| 6: 删旧类型 | 删 `StreamChunk` / `StreamToolCallDelta` | 1-2 文件 | 0.5d |

**总计**:约 4 个工作日

### 3.2 风险点

1. **大量 adapter 测试需要重写**:`StreamChunk` 是测试断言目标,改成 `StreamEvent` 后所有相关测试都要更新
2. **ModelGateway 的 chunk-level 元数据丢失**:`chunkCount` / `lastUsage` / `lastFinishReason` / `lastChunkDone` 这些"跨 chunk 累积"的状态需要重新设计挂载点(挂在 `chatStream` 协程局部变量,而不是 StreamChunk 字段)
3. **done 兜底逻辑**:当前在 ModelGateway 里用 `lastChunkDone` 跟踪,需要改为跟踪 `Done` 事件是否出现过
4. **EventConsumer / EventRouter 配合**:EnhancedAgentLoop 输出的 `AgentStreamEvent` 体系不变,下游不受影响

### 3.3 收益

1. **类型安全**:`StreamEvent` 是 sealed,adapter 编译期必须穷举所有事件
2. **新事件扩展零成本**:加 `StreamEvent.PlanStart` 只需要在 `parseStreamChunk` 里 emit,不动其他文件
3. **测试直观**:断言 `assertEquals(listOf(Text("..."), CodeBlockStart("cb-1", "kotlin"), ...), parseStreamChunk(...))`
4. **Anthropic 零成本**:`content_block_*` 直接透传,不再走"字符串解析 + StreamChunk 中转"
5. **职责清晰**:adapter 是"事件识别器",EnhancedAgentLoop 是"事件分发器",中间无中转袋

---

## 4. 后续观察

### 4.1 关联任务

- 2026-06-16: CodeBlock 事件化重构(本次主线,产生本文档)
  - `StreamChunk.codeBlock: CodeBlockEvent?` 字段是过渡方案
  - 后续 2026-XX-XX: 执行本文档的重构

### 4.2 决策记录

- 2026-06-16: 决定**先**做 CodeBlock 事件化(保留 StreamChunk 中转),**后**做本任务(删除 StreamChunk)
- 理由:CodeBlock 改造独立、影响面可控;本任务是更大的架构清理,需独立排期

### 4.3 涉及文件清单

| 文件 | 改动类型 |
|------|---------|
| `src/main/kotlin/com/codesage/model/adapter/StreamEvent.kt` | **新建** |
| `src/main/kotlin/com/codesage/model/adapter/ModelAdapter.kt` | 修改签名 |
| `src/main/kotlin/com/codesage/model/dto/ChatModels.kt` | 删 `StreamChunk` / `StreamToolCallDelta` |
| `src/main/kotlin/com/codesage/model/adapter/OpenAICompatibleAdapter.kt` | 改写 |
| `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicStreamParser.kt` | 改写(简化) |
| `src/main/kotlin/com/codesage/model/adapter/google/GeminiAdapter.kt` | 改写 |
| `src/main/kotlin/com/codesage/model/gateway/ModelGateway.kt` | 改写 |
| `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` | 改写 |
| `src/test/kotlin/.../adapter/*.kt` | 单元测试重写 |
| `src/test/kotlin/.../agent/core/EnhancedAgentLoopTest.kt` | 部分断言更新 |

---

## 5. 启动检查清单(下次接手时用)

- [ ] 确认本次 CodeBlock 事件化重构已稳定运行 ≥ 1 周
- [ ] 评估 `EventConsumer` / `EventRouter` 是否需要同步简化
- [ ] 评估 `run-log.js` 前端事件名是否需要同步更新
- [ ] 评估 `EVENT_PROTOCOL.md` 文档是否需要同步更新
- [ ] 拆分 PR:建议每个 adapter 一个 PR,ModelGateway+EnhancedAgentLoop 一个 PR
- [ ] 全套单元测试 + 集成测试通过
- [ ] 手动触发 5+ provider 的真实对话(MiniMax-M3 / Claude / GPT / Gemini / DeepSeek),验证流式增强体验
