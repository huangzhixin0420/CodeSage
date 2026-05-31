# 目标 A：ContextCompressor 真正的摘要与 RAG 检索实现

## 角色定义

你是一位**资深 Kotlin 工程专家 + AI Agent 系统架构师**。你需要深入阅读 CodeSage 项目现有代码，识别当前实现与架构设计之间的 Gap，设计最小侵入性的实现方案，编写/修改代码并确保所有现有测试通过，为新增功能补充单元测试。

## 项目背景

**CodeSage** 是一款基于 IntelliJ Platform 的 AI Agent IDE 插件（Kotlin 语言）。`ContextManager` 负责管理对话历史和上下文截断，`ContextCompressor` 是 `ContextEngine` 的默认实现，当前已具备规则驱动的结构化摘要能力（提取决策、文件修改、待办问题等）。但 `ContextManager` 中的 `SUMMARIZE`、`RAG检索`、`HYBRID` 三种策略均为空实现，直接退化到 `keepRecent()`。

## 当前代码状态与 Gap 分析

### 已具备的基础能力

- **`ContextCompressor.compress()`**（`src/main/kotlin/com/codesage/agent/context/ContextCompressor.kt`）：已实现基于规则引擎的结构化摘要，保护头尾消息，清理图像占位、截断工具输出。
- **`BuiltInMemoryProvider`**（`src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt`）：基于 SQLite + FTS5 的持久化记忆系统，支持 `prefetch(query)` 做全文检索，`searchMemories()` 返回 `MemoryRecord` 列表。表结构已包含 `fts_search` 虚拟表及增删改触发器。
- **`ModelGateway`**（`src/main/kotlin/com/codesage/model/gateway/ModelGateway.kt`）：统一的模型调用入口，提供 `chat(request)` 和 `chatStream(request)` 方法，支持 `ChatRequest`/`ChatResponse`/`Message` 标准数据结构。
- **`TokenEstimator`**（`src/main/kotlin/com/codesage/agent/context/TokenEstimator.kt`）：提供 `estimateMessagesTokens()` 方法估算 token 数。

### 当前 Gap

1. **`ContextManager.summarize()`**（第 184-188 行）：注释明确说明"需要外部 LLM 配合实现"，当前直接调用 `keepRecent()`，未真正生成摘要。
2. **`ContextManager.ragRetrieval()`**（第 193-196 行）：注释说明"需要向量数据库和嵌入模型"，当前直接调用 `keepRecent()`，未接入 `BuiltInMemoryProvider` 的 FTS5 检索。
3. **`ContextManager.hybridTruncate()`**（第 201-219 行）：当前仅做简单的头尾保护 + 中间截取，未融合规则摘要、LLM 摘要、RAG 检索，未按 token 预算分配比例（头:摘要:RAG:尾 = 20%:40%:20%:20%）。
4. **`ContextManager.getInstance()`** 单例（第 255-263 行）：与 `ContextEngine` 的"每会话独立实例"设计存在矛盾。单例可能导致多会话上下文串扰。

## 具体任务要求

### A1. 真正的 `summarize()` —— LLM 驱动的中间消息摘要

当历史消息超过 `config.summarizeThreshold`（默认 30 条）时：

- 将中间部分消息（排除头尾保护区和系统消息）提交给 `ModelGateway.chat()` 请求轻量级模型（优先使用当前默认模型的较小版本，如 `moonshot-v1-8k`，可通过 `config` 传入 `auxiliaryModel`）生成结构化摘要。
- 摘要格式必须兼容后续重新注入，包含以下区块（与现有 `ContextCompressor` 的摘要格式一致）：
  ```
  ## Active Task
  ## Resolved Decisions
  ## Files Modified
  ## Pending Questions
  ## Tool Calls Summary
  ```
- 摘要消息以 `Message.systemMessage("[CONTEXT SUMMARY] ...")` 形式插入系统消息之后、头部消息之前。
- LLM 调用失败时降级为 `ContextCompressor` 的规则摘要（已有实现），再失败才降级为 `keepRecent()`。

### A2. 真正的 `ragRetrieval()` —— 基于 `BuiltInMemoryProvider` 的记忆召回

- `ContextManager` 构造函数新增可选参数 `memoryProvider: MemoryProvider? = null`。
- 当触发 `ragRetrieval()` 时，取最近一条 `USER` 消息作为查询 query，调用 `memoryProvider.prefetch(query, sessionId)` 召回相关记忆片段。
- 将召回的记忆片段格式化为 `Message.systemMessage("[RELEVANT CONTEXT] ...")` 插入系统消息之后。
- 若 `memoryProvider` 为 null 或召回为空，降级为 `keepRecent()`。

### A3. `HYBRID` 策略闭环 —— 保护头尾 + 中间摘要 + RAG 片段注入

实现完整流水线，按 **token 预算分配**：

| 区域 | 预算占比 | 内容 |
|------|---------|------|
| 头部保护区 | 20% | 最早的 `protectFirstN` 条对话（含首条用户意图） |
| 结构化摘要 | 40% | A1 的 LLM 摘要（或规则摘要降级） |
| RAG 片段 | 20% | A2 的 `BuiltInMemoryProvider` 召回 Top-K |
| 尾部保护区 | 20% | 最近的 `protectLastN` 条对话 |

实现细节：
- 先计算各区域的预估 token 数，若摘要超出预算则截断摘要内容（保留关键决策和工具调用）。
- RAG 片段按相关性排序，超出预算时截断尾部记忆。
- 最终消息列表顺序：`系统消息` → `RAG片段` → `结构化摘要` → `头部` → `尾部`。

### A4. 设计矛盾清理 —— `ContextManager` 单例移除

- **方案选择（推荐方案 B）**：
  - **方案 A**：完全移除 `getInstance()`，所有调用方改为 `new ContextManager(config)`。
  - **方案 B（推荐）**：保留 `getInstance()` 但明确仅用于"共享默认配置"，返回的实例不再缓存为单例，而是每次创建新实例。在 `getInstance()` 上标注 `@Deprecated` 并逐步迁移。
- 采用方案 B，在 `getInstance()` 上添加 `@Deprecated("Use per-session instance instead")`，内部不再缓存 `instance`，每次返回 `ContextManager()`。修改 `CodeSageAppService` 和 `AgentToolWindowFactory` 中可能使用单例的地方，确保不破坏现有行为。
- 在 `ContextManager` 类文档中明确说明："每个对话会话应持有独立的 ContextManager 实例，避免多会话上下文串扰。"

## 验收标准

- [ ] `ContextCompressorTest` 新增测试：`summarize()` 能将 30 条消息压缩为 1 条摘要且保留关键工具调用记录（通过 Mock `ModelGateway` 验证请求体包含中间消息）。
- [ ] `ContextCompressorTest` 新增测试：`ragRetrieval()` 能根据查询召回相关记忆（通过 Mock `MemoryProvider` 验证 `prefetch()` 被调用并注入上下文）。
- [ ] `ContextManagerTest` 新增测试：`HYBRID` 策略下，80 条消息的 token 数 < `KEEP_RECENT` 下同量消息的 token 数。
- [ ] `./gradlew test` 全部通过，无回归（允许已存在的 `ConversationPersistenceTest` flaky 失败）。
- [ ] 架构文档 `docs/ARCHITECTURE.md` 中 Context Truncation 章节同步更新，说明 LLM 摘要、RAG 检索、HYBRID 预算分配的实现机制。

## 通用约束与规范

1. **最小侵入性**：不修改现有公共 API 签名（`ContextEngine.compress()`、`Message` 数据类等），新增功能通过扩展函数/新增方法实现。
2. **线程安全**：`ContextManager` 的 `history` 当前为 `mutableListOf<Message>()`，若可能被多线程访问（如 AgentStream），需改为 `CopyOnWriteArrayList` 或显式 `synchronized`。
3. **资源管理**：所有 `CoroutineScope`、`Closeable` 必须有对应的 `shutdown/close/cancel`。
4. **日志规范**：使用 `Logger.getLogger<T>()`，禁止 `e.printStackTrace()`；错误级别用 `logger.error`，调试用 `logger.debug`。
5. **测试覆盖**：新增公共方法必须有对应的 `@Test`，边界条件（空值、LLM 调用失败、memoryProvider 为 null）需覆盖。
6. **降级策略**：LLM 摘要失败 → 规则摘要；RAG 失败 → keepRecent；确保任何外部依赖故障都不导致对话中断。

## 输出格式要求

完成代码修改后，请按以下格式回复：

```markdown
## 完成报告：目标 A

### 修改文件清单
1. `src/main/kotlin/...` - 修改说明
2. ...

### 关键设计决策
- 决策 A：原因...
- 决策 B：原因...

### 测试验证
```bash
./gradlew test
# 结果：BUILD SUCCESSFUL / X tests completed, Y failed
```
```
