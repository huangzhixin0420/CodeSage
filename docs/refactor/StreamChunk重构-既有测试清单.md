# StreamChunk 重构 — 既有测试清单

- **创建日期**: 2026-06-17
- **配套文档**: [02 文档](./StreamChunk中转层重构-2026-06-16-02.md) / [EXECUTION.md](./StreamChunk中转层重构-2026-06-16-02-EXECUTION.md)
- **目的**: 列出本任务涉及的**所有既有测试**，标注每个测试在 6 个 commit 中需要做的改动

---

## 1. Adapter 测试（直接影响）

### 1.1 OpenAI 兼容协议

| 文件 | 涉及行 | 涉及的 case | 改动 commit | 改动内容 |
|------|--------|-----------|------------|---------|
| `src/test/kotlin/com/codesage/model/OpenAICompatibleAdapterTest.kt` | 38/48/57 | 3 个 `parseStreamChunk` 测试 | Commit 2 | 断言对象 `StreamChunk` → `StreamEvent.Content.Text/Reasoning` 等 |
| `src/test/kotlin/com/codesage/model/adapter/OpenAICompatibleCodeBlockParseTest.kt` | 28-37/47/106 | 12+ 个围栏识别测试 | Commit 2 | 断言 `chunk.codeBlock` → `StreamEvent.CodeBlock.*` |
| `src/test/kotlin/com/codesage/model/adapter/OpenAICompatibleThinkTagParseTest.kt` | 32/48/216 | 10+ 个 think 标签累积测试 | Commit 2 | 断言 `chunk.delta` / `chunk.reasoningDelta` 分桶 → `Content.Text` / `Content.Reasoning` |

### 1.2 Anthropic

| 文件 | 涉及行 | 涉及的 case | 改动 commit | 改动内容 |
|------|--------|-----------|------------|---------|
| `src/test/kotlin/com/codesage/model/adapter/anthropic/AnthropicAdapterTest.kt` | 258/270/281/315/326/353/367/457 | 11 个 `parseStreamChunk` 测试 | Commit 2 | 断言 `StreamChunk` → `StreamEvent.*`；Anthropic 状态化测试需带 `StreamState` 显式参数 |

### 1.3 Gemini

| 文件 | 涉及行 | 涉及的 case | 改动 commit | 改动内容 |
|------|--------|-----------|------------|---------|
| `src/test/kotlin/com/codesage/model/adapter/google/GeminiAdapterTest.kt` | 284/295/306/318/334/341/347 | 7 个 `parseStreamChunk` 测试 | Commit 2 | 断言 `StreamChunk` → `StreamEvent.*` |

**小计**: ~43 个 adapter 测试需更新

---

## 2. EnhancedAgentLoop 测试（强影响）

| 文件 | 涉及行 | 改动 commit | 改动内容 |
|------|--------|------------|---------|
| `src/test/kotlin/com/codesage/agent/core/EnhancedAgentLoopTest.kt` | 288/429/772/789/828/866/922 | Commit 5 | L288 mock 改 `parseStreamChunk: List<StreamEvent>`；L772/789/828/866/922 4 处 `chatStream` mock 改为 `Flow<StreamEvent>` + 直接 emit StreamEvent |
| `src/test/kotlin/com/codesage/agent/core/EnhancedAgentLoopCancellationTest.kt` | - | Commit 5 | 涉及 collect 块的中断逻辑，断言需要更新 |
| `src/test/kotlin/com/codesage/agent/core/EnhancedAgentLoopDelegateTaskTest.kt` | - | Commit 5 | 子 agent 派发走 SubAgentDispatchHook，断言需更新 |
| `src/test/kotlin/com/codesage/agent/core/StreamingToolCallTest.kt` | - | Commit 5 | 工具调用流式测试，ToolCall.Delta 事件断言 |
| `src/test/kotlin/com/codesage/agent/core/SubAgentExecutorTest.kt` | - | Commit 5 | SubAgentDispatchHook 单测新增 |

**小计**: ~5 个 EnhancedAgentLoop/*Test 文件需更新

---

## 3. AgentCore + 集成测试（弱影响）

| 文件 | 改动 commit | 改动内容 |
|------|------------|---------|
| `src/test/kotlin/com/codesage/agent/AgentCoreTest.kt` | Commit 5 | `chatStream` 改 `Flow<StreamEvent>` 后断言更新 |
| `src/test/kotlin/com/codesage/agent/core/AgentCoreConcurrencyTest.kt` | Commit 5 | 并发 chatStream 测试 |
| `src/test/kotlin/com/codesage/agent/core/AsyncFollowUpChatTest.kt` | Commit 5 | 异步 follow-up 涉及 collect 块 |
| `src/test/kotlin/com/codesage/agent/core/ConcurrentChatTest.kt` | Commit 5 | 并发 chat 测试 |
| `src/test/kotlin/com/codesage/agent/core/DoubleChatTest.kt` | Commit 5 | 双 chat 测试 |
| `src/test/kotlin/com/codesage/agent/core/DoubleChatWithToolsTest.kt` | Commit 5 | 双 chat + 工具测试 |
| `src/test/kotlin/com/codesage/agent/core/RealContextChatTest.kt` | Commit 5 | 真实 context 测试 |
| `src/test/kotlin/com/codesage/e2e/AgentCoreEndToEndTest.kt` | Commit 5 | E2E 测试 |
| `src/test/kotlin/com/codesage/model/gateway/SmartRouterTest.kt` | Commit 3 | 涉及 Gateway 切到 StreamEvent |

**小计**: ~9 个 AgentCore / Gateway 测试需更新

---

## 4. 弱相关测试（不需改但需验证）

| 文件 | 改动 commit | 备注 |
|------|------------|------|
| `src/test/kotlin/com/codesage/agent/core/EventSystemTest.kt` | - | 事件系统测试，StreamEvent 变更不影响 |
| `src/test/kotlin/com/codesage/agent/core/EventBatchEmitterResourceTest.kt` | - | EventBatchEmitter 测试，emit 目标不变 |
| `src/test/kotlin/com/codesage/agent/core/EventHistoryRingBufferTest.kt` | - | 事件历史 ring buffer |
| `src/test/kotlin/com/codesage/observability/ThreadSafeTimeTest.kt` | - | 时间工具 |
| `src/test/kotlin/com/codesage/tools/guardrails/*Test.kt` | - | 工具 guardrails |
| `src/test/kotlin/com/codesage/ide/**/*Test.kt` | - | IDE UI 测试 |

**这些测试不需要改**，但在每个 commit 完成后应跑一次确认无 regression。

---

## 5. 总计

| 类别 | 数量 | 改动 commit |
|------|------|------------|
| **强影响** | ~43 个 adapter 测试 + 5 个 EnhancedAgentLoop/*Test | Commit 2 / 5 |
| **弱影响** | ~9 个 AgentCore / Gateway 测试 | Commit 3 / 5 |
| **不需改** | ~20+ 个其他测试 | 每次 commit 后跑回归 |

**总计**: **约 60 个测试文件** 与本次重构相关，其中 **~50 个需更新**。

---

## 6. 既有测试改动的执行顺序

```
Commit 1 完成后:
  - 跑全工程测试 → 预期: 大面积失败（StreamChunk 已删）
  - 不修,等 Commit 2 解决

Commit 2 完成后:
  - 跑 adapter 测试 → 预期: 25+ PASS
  - 跑其他测试 → 预期: 部分仍失败（EnhancedAgentLoop 等下游）

Commit 3 完成后:
  - 跑 gateway 测试 → 预期: PASS
  - 跑其他测试 → 预期: 部分仍失败

Commit 4 完成后:
  - 跑 TurnReducerTest → 预期: PASS
  - 跑其他测试 → 预期: 部分仍失败（EnhancedAgentLoop 未接 Reducer）

Commit 5 完成后:
  - 跑全工程测试 → 预期: 100% PASS
  - 关键: EnhancedAgentLoopTest 4 处 mock 改写

Commit 6 完成后:
  - 跑全工程测试 → 预期: 100% PASS
  - 跑真实 provider 验证
```

---

## 7. 注意事项

1. **测试断言模式**: 既有测试用 `assertNotNull(chunk)` / `assertEquals("...", chunk.delta)`，新契约下要改为 `assertIs<StreamEvent.Content.Text>(event)` / `assertEquals("...", event.delta)`
2. **Anthropic 状态化测试**: 需要在同一个 normalizer 实例上多次 `normalize()` 调用并带 `StreamState` 参数——见 [EXECUTION.md §3.3 Task 2.6](./StreamChunk中转层重构-2026-06-16-02-EXECUTION.md)
3. **Mock 风格**: 既有 `Flow<StreamChunk>` mock 改为 `Flow<StreamEvent>`——直接 emit StreamEvent，**不再有 chunk wrapper**
4. **不要改测试断言对象名**: 既有测试里 `chunk.codeBlock` 等字段名要改为 `event is CodeBlockEvent.*` 等更精确的断言
