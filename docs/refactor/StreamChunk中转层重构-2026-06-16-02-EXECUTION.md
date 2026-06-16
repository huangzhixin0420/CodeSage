# StreamChunk 中转层重构 — 执行计划

- **创建日期**: 2026-06-17
- **配套设计文档**: [02 文档](./StreamChunk中转层重构-2026-06-16-02.md)
- **配套调研文档**: [协议调研](../../research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md)
- **配套测试清单**: [既有测试清单](./StreamChunk重构-既有测试清单.md)
- **配套未来任务**: [future-tasks/](./future-tasks/)
- **本版本工作量**: 4.5d

> **使用说明**: 本文档是"施工图纸"——按 §3.1 依赖图确定执行顺序，按 §3.2-§3.7 逐个 commit 实施，每个 commit 完成后对照 §3.8 启动检查清单验证。

---

## §3.1 Commit 依赖图与 PR 拆分

### 依赖图

```
[Commit 1: 契约基础]
       │
       ▼
[Commit 2: Normalizer]  ←── 必须等 Commit 1
       │
       ▼
[Commit 3: Gateway]      ←── 必须等 Commit 2
       │
       ▼
[Commit 4: Reducer]      ←── 必须等 Commit 3（依赖 Flow 类型已贯通）
       │
       ▼
[Commit 5: Loop + Hook]  ←── 必须等 Commit 4
       │
       ▼
[Commit 6: 验证清理]     ←── 必须等 Commit 5
```

**所有 commit 严格串行**——任意一步未通过验收，下一步不开工。

### PR 拆分建议

| PR 编号 | 包含 commit | 标题 | 是否可独立 review |
|--------|-----------|------|------------------|
| **PR-1** | Commit 1 + Commit 2 | "引入 StreamEvent 契约 + 3 个 Normalizer" | ✅（StreamChunk 兼容层保留） |
| **PR-2** | Commit 3 + Commit 4 | "Gateway 切到 StreamEvent + Reducer 实现" | ✅（内部接口变更） |
| **PR-3** | Commit 5 + Commit 6 | "EnhancedAgentLoop 接入 Reducer + Hook 拆分 + 验证" | ✅（端到端可观察） |

**为什么不拆 6 个 PR？**
- 拆太细导致 PR 间"无可见行为变化"，reviewer 难以判断意图
- 拆 3 个 PR 让每个 PR 有明确的"前后对比"价值

---

## §3.2 Commit 1: 契约基础（0.5d）

### Task 拆解

| # | 任务 | 文件 / 函数 | 预计时间 |
|---|------|------------|---------|
| 1.1 | 新建 `StreamEvent.kt`（分形 sealed tree + `ChoiceScoped`） | `src/main/kotlin/com/codesage/model/adapter/StreamEvent.kt` (新) | 1.5h |
| 1.2 | 新建 `FinishReason.kt` 枚举 | `src/main/kotlin/com/codesage/model/dto/FinishReason.kt` (新) | 0.5h |
| 1.3 | 改 `ModelAdapter.parseStreamChunk` 签名为 `List<StreamEvent>` | `src/main/kotlin/com/codesage/model/adapter/ModelAdapter.kt:78` | 0.5h |
| 1.4 | 删 `StreamChunk` / `StreamToolCallDelta` | `src/main/kotlin/com/codesage/model/dto/ChatModels.kt:74-137` | 0.5h |
| 1.5 | 改所有 `StreamChunk` 引用点（编译失败驱动） | 全工程 `rg -l "StreamChunk"` | 1h |

### 验收标准（DoD）

- [ ] `./gradlew compileKotlin` 通过（虽然有编译错误，是预期的）
- [ ] `StreamEvent.kt` 编译通过，包含 `Content` / `ToolCall` / `CodeBlock` / `Citation` / `Media` / `Flow` 6 个父类型
- [ ] `ChatModels.kt` 已删 `StreamChunk` / `StreamToolCallDelta`
- [ ] `git diff --stat` 显示的改动文件在预期范围内（不超过 30 个文件）
- [ ] **关键**: `git grep "StreamChunk"` 在 `src/main/kotlin/` 应有大量错误（说明所有引用都已标注待改造）

### 回退策略

- **如果中途卡住**: 不提交，保留 `StreamChunk` 兼容层（**注意**: 本 commit 的 1.4 删除步骤**必须可回退**——保留 `StreamChunk` 旧类作为 deprecated 标记）
- **回退成本**: `git revert` 1 个 commit

### PR 边界

- **本 commit 单独发 PR 不可**（编译失败）
- **必须与 Commit 2 合并为 PR-1**

---

## §3.3 Commit 2: L1 Normalizer（1.5d）

### Task 拆解

| # | 任务 | 文件 / 函数 | 预计时间 |
|---|------|------------|---------|
| 2.1 | 新建 `StreamEventNormalizer` 抽象类 | `src/main/kotlin/com/codesage/model/adapter/StreamEventNormalizer.kt` (新) | 1h |
| 2.2 | 新建 `OpenAIStreamNormalizer`（含 `FencedCodeSplitter` 迁移） | `src/main/kotlin/com/codesage/model/adapter/OpenAIStreamNormalizer.kt` (新) | 3h |
| 2.3 | 新建 `AnthropicStreamNormalizer` | `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicStreamNormalizer.kt` (新) | 2h |
| 2.4 | 新建 `GeminiStreamNormalizer` | `src/main/kotlin/com/codesage/model/adapter/google/GeminiStreamNormalizer.kt` (新) | 1h |
| 2.5 | 改 3 个 Adapter 委托给 Normalizer | `OpenAICompatibleAdapter.kt:148` / `AnthropicAdapter.kt:325` / `GeminiAdapter.kt:256` | 1h |
| 2.6 | 跑既有测试，按需更新 | 见 [既有测试清单](./StreamChunk重构-既有测试清单.md) | 2h |
| 2.7 | 新增 3 个 Normalizer 单元测试 | `src/test/kotlin/.../adapter/*NormalizerTest.kt` (新) | 2h |

### 既有代码引用

- `OpenAICompatibleAdapter.kt:137-253` 是 `parseStreamChunk` 完整实现，作为 OpenAIStreamNormalizer 的**对照参考**
- `AnthropicStreamParser.kt:43-167` 完整实现可作为 AnthropicStreamNormalizer 的**起点**（直接迁移而非重写）
- `GeminiAdapter.kt:256-307` 是 stateless 解析，最简单
- `FencedCodeSplitter.kt:29-155` 287 行状态机直接被 `OpenAIStreamNormalizer` 引用

### 验收标准（DoD）

- [ ] `StreamEventNormalizer` 抽象类编译通过
- [ ] 3 个 Normalizer 各自的 `normalize(line, state)` 实现完成
- [ ] 3 个 Adapter 改 `parseStreamChunk` 委托给 Normalizer（不再有原 `StreamChunk` 解析逻辑）
- [ ] **关键**: 既有 25 个 adapter 测试全部 PASS（详见 [既有测试清单](./StreamChunk重构-既有测试清单.md)）
- [ ] 新增 3 个 NormalizerTest 覆盖正常路径 + 错误路径
- [ ] `./gradlew test` 全绿
- [ ] `git grep "StreamChunk"` 在 `src/main/kotlin/` 应为 0 结果

### 回退策略

- **如果某 Normalizer 卡住**: 该 Normalizer 可以先返回 `emptyList()` 占位（不抛异常），其他 Normalizer 继续
- **如果 OpenAI 围栏迁移出问题**: 保留 `FencedCodeSplitter` 在原文件，作为独立工具类，不强行合并到 Normalizer
- **回退成本**: `git revert` 1 个 commit（commit 内细分不强求原子）

### PR 边界

- **与 Commit 1 合并为 PR-1**

---

## §3.4 Commit 3: L1.5 Gateway（0.5d）

### Task 拆解

| # | 任务 | 文件 / 函数 | 预计时间 |
|---|------|------------|---------|
| 3.1 | 改 `chatStream` 返回类型 `Flow<StreamChunk>` → `Flow<StreamEvent>` | `ModelGateway.kt:117` | 0.5h |
| 3.2 | 改非流式回退逻辑（`chat()` 包装为 Flow） | `ModelGateway.kt:129-167` | 1h |
| 3.3 | 改 SSE 主循环（`adapter.parseStreamChunk` 返回 `List<StreamEvent>` → emit 多个 event） | `ModelGateway.kt:245-301` | 1.5h |
| 3.4 | 改跨 chunk 累积状态提取（`chunk.usage` → `event is Flow.Finished`） | `ModelGateway.kt:243-301` | 0.5h |
| 3.5 | 跑既有测试 | - | 0.5h |

### 既有代码引用

- `ModelGateway.kt:117-336` 是 `chatStream` 完整实现，本次 commit 全部改写
- 关键行号:
  - L117 `fun chatStream` 签名
  - L141/L163/L166 非流式回退的 `emit(StreamChunk(...))`
  - L247 `val chunks = adapter.parseStreamChunk(line)` 循环消费
  - L277/L288/L301 done 兜底逻辑

### 验收标准（DoD）

- [ ] `chatStream` 返回 `Flow<StreamEvent>`
- [ ] 既有 5+ provider 真实对话数据测试 PASS（用 [fixtures/](../../fixtures/) 下的样例）
- [ ] `done` 兜底逻辑保留（MiniMax-M3 等不发送 [DONE] 的场景）
- [ ] 非流式回退逻辑正确转换
- [ ] `./gradlew test` 全绿

### 回退策略

- **如果某 provider 真实数据跑不通**: 临时回退到 `Flow<StreamChunk>` + adapter 层做一次转换（**不推荐**，但可救命）
- **回退成本**: `git revert` 1 个 commit

### PR 边界

- **与 Commit 4 合并为 PR-2**

---

## §3.5 Commit 4: L2 Reducer（0.5d）

### Task 拆解

| # | 任务 | 文件 / 函数 | 预计时间 |
|---|------|------------|---------|
| 4.1 | 新建 `TurnState.kt`（含 `assistantText` / `toolCalls` / `codeBlocks` / `roundReasoningStarted` / `finishedReason` / `usage`） | `src/main/kotlin/com/codesage/agent/core/TurnState.kt` (新) | 1h |
| 4.2 | 新建 `TurnReducer.kt`（含 10 个 case 的 when 分支） | `src/main/kotlin/com/codesage/agent/core/TurnReducer.kt` (新) | 2h |
| 4.3 | 写 TurnReducerTest（table-driven 覆盖所有 case） | `src/test/kotlin/.../agent/core/TurnReducerTest.kt` (新) | 1.5h |

### 既有代码引用

- `EnhancedAgentLoop.kt:301-450` 的 if/else 解包链作为 TurnReducer 的**逻辑参考**（按 case 拆分）
- `AgentStreamEvent` 的 30+ case 在 `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt` 是 emit 目标

### Reducer 占位 case（这些走 `state to emptyList()`）

```kotlin
is StreamEvent.Content.PlanStep -> state to emptyList()  // 协议层占位
is StreamEvent.Citation.Delta -> state to emptyList()
is StreamEvent.Media.ImageFragment,
is StreamEvent.Media.AudioFragment -> state to emptyList()
```

### 验收标准（DoD）

- [ ] TurnReducer 编译通过
- [ ] TurnReducerTest 100% 覆盖所有 10+ case
- [ ] 每个 case 至少 2 个测试（正常路径 + 边界）
- [ ] `./gradlew test` 全绿

### 回退策略

- **如果 Reducer 复杂度爆炸**: 简化为"只处理 Text / Reasoning / ToolCall.Delta / Flow.Finished 4 个 case"，其他 case 全留占位
- **回退成本**: `git revert` 1 个 commit

### PR 边界

- **与 Commit 3 合并为 PR-2**

---

## §3.6 Commit 5: L3 Loop + Hook（1d）

### Task 拆解

| # | 任务 | 文件 / 函数 | 预计时间 |
|---|------|------------|---------|
| 5.1 | 新建 `TurnLifecycleHook` 接口 | `src/main/kotlin/com/codesage/agent/core/TurnLifecycleHook.kt` (新) | 0.5h |
| 5.2 | 新建 5 个初始 Hook 类 | `src/main/kotlin/com/codesage/agent/core/hooks/{ToolConfirmation,SubAgentDispatch,ContextCompression,ModeSuggestion,SessionMigration}Hook.kt` (新) | 3h |
| 5.3 | 改 `EnhancedAgentLoop.collect` 块为 Reducer 派发 | `EnhancedAgentLoop.kt:301-450` | 2h |
| 5.4 | 改 `AgentCore.chatStream` 适配 `Flow<StreamEvent>` | `AgentCore.kt:712-770` | 1h |
| 5.5 | 跑既有测试，更新 mock 模式 | 见 [既有测试清单](./StreamChunk重构-既有测试清单.md) | 1.5h |

### 既有代码引用

- `EnhancedAgentLoop.kt:301-450` if/else 解包链 → 改为 `reducer.reduce(state, event)` 调用
- `AgentCore.kt:743-758` 改 `chunk.delta` → `event is StreamEvent.Content.Text`
- `EnhancedAgentLoopTest.kt:772/789/828/866/922` 4 处 `chatStream` mock 改 `Flow<StreamEvent>`

### 5 个初始 Hook 触发点

| Hook | 触发时机 | 业务事件 |
|------|---------|---------|
| `ToolConfirmationHook` | `onToolExecuted` | `ToolConfirmationNeeded` |
| `SubAgentDispatchHook` | `onTurnEnd` | `SubAgentStart/Progress/Complete` |
| `ContextCompressionHook` | `onTurnEnd` | `ContextCompressed` |
| `ModeSuggestionHook` | `onTurnEnd` | `ModeSuggestion` |
| `SessionMigrationHook` | `onTurnStart/End` | `SessionMigrated` |

### 验收标准（DoD）

- [ ] `TurnLifecycleHook` 接口 + 5 个初始 Hook 类编译通过
- [ ] EnhancedAgentLoop `collect` 块调用 Reducer（不写业务逻辑）
- [ ] AgentCore.chatStream 适配 `Flow<StreamEvent>`
- [ ] 既有 13 个 EnhancedAgentLoop/*Test 全部 PASS
- [ ] EnhancedAgentLoopTest 4 处 mock 改写完成
- [ ] 5 个 Hook 类各 1 个单测
- [ ] `./gradlew test` 全绿

### 回退策略

- **如果 Hook 拆分卡住**: 5 个 Hook 可合并为 1 个 `DefaultHook`，全部业务逻辑塞里面（不推荐但能 commit）
- **如果 EnhancedAgentLoop 改造太大**: 分 2 个 sub-commit——5.3a 改 collect 块、5.3b 改 AgentCore
- **回退成本**: `git revert` 1 个 commit

### PR 边界

- **与 Commit 6 合并为 PR-3**

---

## §3.7 Commit 6: 验证清理（0.5d）

### Task 拆解

| # | 任务 | 预计时间 |
|---|------|---------|
| 6.1 | 跑 5+ provider 真实对话验证（见 [§3.8 启动检查清单](#§38-启动检查清单具体化)） | 2h |
| 6.2 | 同步 `docs/EVENT_PROTOCOL.md` 文档 | 1h |
| 6.3 | 删 `docs/refactor/archive/` 下的 01 草稿 | 0.5h |

### 验收标准（DoD）

- [ ] §3.8 启动检查清单全部 ✅
- [ ] `EVENT_PROTOCOL.md` 同步
- [ ] `docs/refactor/archive/StreamChunk中转层重构-2026-06-16-01.md` 标记为归档
- [ ] `./gradlew test` 全绿

### PR 边界

- **与 Commit 5 合并为 PR-3**

---

## §3.8 启动检查清单（具体化）

### 执行前必读

- [ ] **02 文档评审通过**
- [ ] **本次 EXECUTION 文档评审通过**
- [ ] **协议调研文档**已确认（[调研文档](../../research/PlanStep-多候选-多选项-协议调研-2026-06-17-01.md)）
- [ ] **既有测试清单**已 read 一遍（[既有测试清单](./StreamChunk重构-既有测试清单.md)）
- [ ] **本地构建基线**：`./gradlew test` 当前 100% PASS

### Commit 6 完成时必验（"稳定运行 ≥ 1 周"的具体定义）

#### A. 测试覆盖

- [ ] `./gradlew test` 全绿
- [ ] 既有 25+ adapter 测试 PASS
- [ ] 既有 13+ EnhancedAgentLoop/*Test PASS
- [ ] 新增 TurnReducerTest + 3 个 NormalizerTest + 5 个 HookTest PASS
- [ ] **测试覆盖率**: 新代码（StreamEvent / TurnReducer / Hooks）≥ 80%

#### B. 真实对话验证（5+ provider 必跑）

| Provider | 必验场景 | 期望 |
|----------|---------|------|
| OpenAI (GPT-4) | 普通聊天 / 工具调用 | 流式渲染正常、Reasoning 段无丢失 |
| OpenAI (o3) | 推理链 | reasoning 段正确流式、RoundStart/End 配对 |
| Anthropic (Claude 4) | 普通聊天 / 工具调用 | content_block_* 三态正确 |
| Anthropic (Claude 4) | 推理链 | thinking_delta 正确 emit |
| Gemini (2.5 Pro) | 普通聊天 | 流式正常、done 兜底触发 |
| DeepSeek (R1) | 推理链 | reasoning_content 正确提取 |
| MiniMax (M3) | 普通聊天 | done 兜底触发（无 [DONE] sentinel） |
| MiniMax (M3) | 代码块生成 | FencedCodeSplitter 迁移无回归 |

**每场景必看日志**:
- `[Turn N] CHUNK #N` 日志正常输出
- `[Turn N] STREAM END` 日志在流结束时打印
- `usage=` 字段非 null

#### C. 业务行为不变性

- [ ] **对比重构前后的 AgentStreamEvent 序列**: 至少 3 个相同 prompt 的输出序列应一致
- [ ] **对比 EventBatchEmitter 输出**: 重构前后 EventBatchEmitter 产出事件一致
- [ ] **对比 UI 渲染**: 前端 `EventConsumer` 接收的事件名/字段一致

#### D. 性能不退化

- [ ] **流式响应延迟**: 端到端 TTFT (Time To First Token) 不超过重构前 110%
- [ ] **流式响应总时长**: 不超过重构前 110%
- [ ] **内存占用**: 不超过重构前 120%

---

## §3.9 风险点快速参考

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| **OpenAI 围栏迁移** | 中 | 高 | 保留 `FencedCodeSplitter` 独立，先迁不改逻辑 |
| **Anthropic 状态化测试** | 中 | 中 | `StreamState` 显式参数 + 测试 helper |
| **EnhancedAgentLoop collect 块改造** | 中 | 高 | 分 2 sub-commit 推进，5.3a 先改结构、5.3b 改 AgentCore |
| **5 个 Hook 类拆分漏业务** | 高 | 中 | 每个 Hook 配 1 个回归测试 |
| **公共 API 破坏** | 低 | 中 | 一次性迁移，验证全套调用方 |

---

## §3.10 不在本 PR 范围（避免 scope creep）

- ❌ PlanStep 跨协议适配 → [future-tasks/PlanStep-跨协议适配.md](./future-tasks/PlanStep-跨协议适配.md)
- ❌ 用户多选项交互 → [future-tasks/用户多选项交互.md](./future-tasks/用户多选项交互.md)
- ❌ 多候选 n>1 协议层暴露 → [future-tasks/多候选响应-n1协议层暴露.md](./future-tasks/多候选响应-n1协议层暴露.md)
- ❌ Hook 清单补全 → [future-tasks/Hook清单梳理.md](./future-tasks/Hook清单梳理.md)
- ❌ 任何新的 provider 接入（如果有需求，等本任务完成）

---

## §3.11 完成判定（"本任务完成"的硬性标准）

1. [ ] 3 个 PR 全部 merged
2. [ ] §3.8 启动检查清单全部 ✅
3. [ ] 1 周后无新增 P0/P1 bug
4. [ ] 02 文档 / 调研文档 / 本 EXECUTION 文档 / 既有测试清单全部归档
5. [ ] 4 份 future-tasks 文档状态更新为"前置条件已满足"
