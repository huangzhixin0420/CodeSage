# CodeSage 针对性优化计划

> 编制依据：
> 1. `docs/CODE_REVIEW_REPORT.md` 中 6 个 Critical + 12 个 High 中尚未修复的问题
> 2. 上一轮架构审查发现的 10 个核心问题
> 3. `docs/OPTIMIZATION_PROGRESS.md` 已完成项（不重复规划）
>
> 编制时间：2026-06-01
> 适用版本：2026.1.2 → 2026.2.x

---

## 〇、问题映射总表

| 来源 | 问题 | 严重度 | 落到 Track |
|------|------|--------|----------|
| 审查 #1 | 多模型适配层单点故障（仅 1 自研 + 1 通用） | Critical 架构 | T1 |
| 审查 #2 | RAG/HYBRID 策略无真实向量后端，FTS5 占位 | Critical 架构 | T3 |
| 审查 #3 | WebSocketTransport 是空壳 | Critical | T2.1 |
| 审查 #4 | 多 Agent 协作是 keyword 路由 + 串行调用 | Critical 架构 | T4 |
| 审查 #5 | PSI 分析器基于类名字符串 + 反射，脆弱 | High 架构 | T5 |
| 审查 #6 | 工具系统双重声明（ToolRegistry 定义 + IDETools 实现 + 硬编码 when） | High | T6 |
| 审查 #7 | AI 技能全部走 LLM，无真实本地分析 | High 架构 | T5.4 |
| 审查 #8 | 工具集价值分布不均（uuid/timestamp 装饰性多，缺 linter/PR/debugger） | Medium | T6.5 |
| 审查 #9 | UI 一体性未解决（Swing sidebar 已重构但其它面板仍混搭） | Medium | T7 |
| 审查 #10 | 文档/代码现实差距（roadmap 画得很大但多数未实现） | Medium | — |
| CodeReview #1 | `AgentCore.getOrCreateSession` 并发竞态 | Critical | T0.1 |
| CodeReview #2 | `EventBatchEmitter` scope 永不取消 | Critical | T0.2 |
| CodeReview #3 | `ConversationPersistence` executor 不关闭、renameTo 静默失败 | Critical | T0.3 |
| CodeReview #4 | `retryCounters` 无界增长 | High | T0.4 |
| CodeReview #5 | `KanbanWorker.executeTasks` 顺序而非并行 | High | T0.5 |
| CodeReview #6 | `EventHistory.query` O(n) 全量扫描 | High | T7.1 |
| CodeReview #7 | `EventBatchEmitter` 缓冲区满抛异常 | High | T0.2 |
| CodeReview #8 | `EnhancedAgentLoop` 空指针风险 | High | T0.6 |
| CodeReview #9 | `SimpleDateFormat` 非线程安全 | High | T0.7 |
| CodeReview #10 | MCP 错误处理不足 | High | T2.2 |

---

## 一、Track 总览

| Track | 主题 | 工作量 | 依赖 | 优先级 |
|-------|------|--------|------|--------|
| **T0** | 基础设施安全与稳定性 | 5–7 天 | 无 | **P0 立即** |
| **T1** | 多模型适配层与智能路由 | 8–10 天 | 无 | **P0 立即** |
| **T2** | MCP 补完（WebSocket + 健康检查） | 4–5 天 | 无 | P0 |
| **T3** | 项目级 RAG 替换 FTS5 占位 | 12–15 天 | T1 | P1 |
| **T4** | 多 Agent 协作深化 | 8–10 天 | T1 | P1 |
| **T5** | PSI 与本地代码分析重构 | 8–10 天 | 无 | P1 |
| **T6** | 工具系统统一与扩充 | 6–8 天 | 无 | P0 并行 |
| **T7** | 可观测性完善（EventHistory + UI） | 4–5 天 | T0 | P2 |
| **T8** | 测试覆盖与 CI | 持续 | 全部 | P2 |

**资源建议**：2 名工程师，约 8–10 周可完成 T0–T6；T3/T4 是 P1 中最重的两项，需要专门留 2–3 周独立做。

---

## 二、T0 — 基础设施安全与稳定性（P0，立即）

> 目标：把所有"运行时可能让 IDE 卡死/数据丢失"的问题根除。这一 Track 是其它所有 Track 的前置（不稳定基座之上做的优化毫无意义）。

### T0.1 修复会话管理并发竞态

**文件**：
- `src/main/kotlin/com/codesage/agent/core/AgentCore.kt`（`getOrCreateSession`/`createSession`）
- 新增测试 `src/test/kotlin/com/codesage/agent/core/AgentCoreConcurrencyTest.kt`

**变更**：
```kotlin
// 当前（不安全）
fun getOrCreateSession(): AgentSession {
    val id = currentSessionId
    if (id != null) {
        val existing = sessions[id]
        if (existing != null) return existing
    }
    val session = createSession()  // 竞态
    return session
}

// 改为（原子）
fun getOrCreateSession(): AgentSession {
    val id = currentSessionId ?: return createSession()  // 提前返回避免双重检查
    return sessions.compute(id) { _, existing -> existing ?: createSessionInternal() }!!
}
```
- `currentSessionId` 改用 `AtomicReference<String?>`
- `sessions` 改用 `ConcurrentHashMap`（当前已是，但要明确为唯一存储）
- 增加 idempotent 创建：若 id 已存在则不覆盖

**验收**：
- [ ] 新增并发测试：1000 个线程并发 `getOrCreateSession` 同 id，仅产生 1 个 session
- [ ] `currentSessionId` 切换有 happens-before 关系（用 `AtomicReference` 语义）
- [ ] `deleteSession` 后 `currentSessionId` 切到下一个可用会话

---

### T0.2 修复 EventBatchEmitter 资源泄漏

**文件**：
- `src/main/kotlin/com/codesage/agent/core/EventBatchEmitter.kt`
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`（集成调用方）

**变更**：
- `shutdown()` 实际执行 `scope.cancel()` + `channel.close()` + 排空 buffer
- 缓冲区满时改为 `offer()`（失败即丢弃 + 计数）而非 `add()`（抛异常）
- 增加 `droppedCount` 指标写入 `MetricsCollector`
- 在 `AgentCore.shutdown()` 中调用所有 emitter 的 shutdown

**验收**：
- [ ] 单元测试：发射 10000 个事件到 10 buffer 的 emitter，零异常，shutdown 后无残留协程
- [ ] 缓冲区满时连续发射，验证不抛异常
- [ ] 关闭顺序：先 cancel scope，再 close channel

---

### T0.3 修复 ConversationPersistence 资源/数据问题

**文件**：
- `src/main/kotlin/com/codesage/persistence/ConversationPersistence.kt`

**变更**：
- `shutdown()` 中调用 `ioExecutor.shutdown()` + `awaitTermination(5, SECONDS)` + `shutdownNow()`
- `tempFile.renameTo(file)` 检查返回值，失败时**回滚到 tempFile**（不删除）+ 上报 `structuredLogger.error`
- 删除操作改为：先 `file.delete()` 同步完成，再 `sessionCache.remove`
- 增加 `sync()` 强制 flush 阻塞 API
- 增加 `pendingWrites` 计数器（shutdown 时 await 清零）

**验收**：
- [ ] 单元测试：跨只读文件系统 rename 时不静默丢失
- [ ] 单元测试：shutdown 后所有 pending 写入都完成
- [ ] 单元测试：删除 session 后，pending save 不会复活该 session

---

### T0.4 修复重试计数器无界增长

**文件**：
- `src/main/kotlin/com/codesage/agent/core/AgentErrorRecovery.kt`

**变更**：
- 使用 `Caffeine`（或手写）LRU：key 为 `model+errorType`，容量 100
- 每次 `resetAllCounters` 周期（默认 1 小时）清理过期键
- 提供 `prune(now)` 显式清理 API
- 暴露 `getRetryCount` 给 metrics

**验收**：
- [ ] 单元测试：10000 次错误注入，map 大小稳定 ≤ 100
- [ ] 显式 `prune` 后已 resolved 键被清理

---

### T0.5 修复 KanbanWorker 顺序执行

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/KanbanWorker.kt`
- `src/main/kotlin/com/codesage/agent/multiagent/KanbanOrchestrator.kt`

**变更**：
- `executeTasks(tasks)` 改为 `coroutineScope { tasks.map { async { executeTask(it) } }.awaitAll() }`
- 增加 `maxConcurrency` 参数（默认 3，复用 `SubAgentExecutor` 的 Semaphore 模式）
- 增加进度回调（每个任务完成时触发）
- 单元测试：6 个任务（每个 200ms 模拟），并发 3 时总耗时 < 600ms（应为 ~400ms）

**验收**：
- [ ] 并发执行时间符合预期
- [ ] 单个任务失败不阻塞其他任务
- [ ] 进度回调按完成顺序触发

---

### T0.6 修复空指针与空检查

**文件**：
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`
- `src/main/kotlin/com/codesage/agent/tools/SkillToolAdapter.kt`

**变更**：
- 所有 `?.` 后接 `!!` 的链式调用统一改为 `let { ... } ?: fallback`
- 引入 `AgentOptional` 工具方法 `safeGet(obj, fallback)` 减少重复
- Kotlin compiler 启用 `-Xexplicit-nullity`（如果可行）

**验收**：
- [ ] 静态扫描：无 `!!` 在 hot path
- [ ] 单元测试：所有 nullable 字段为 null 时不抛 NPE

---

### T0.7 替换非线程安全时间工具

**文件**：
- `src/main/kotlin/com/codesage/agent/core/AgentCore.kt`（`formatTime`）
- `src/main/kotlin/com/codesage/observability/StructuredLogger.kt`（`SimpleDateFormat`）

**变更**：
- 统一改用 `java.time.format.DateTimeFormatter`（线程安全）
- 或对 `SimpleDateFormat` 加 `ThreadLocal<DateFormat>`
- 引入 `Clock` 抽象便于测试时间

**验收**：
- [ ] 多线程并发 format 10000 次无错乱
- [ ] 单元测试用固定 Clock 验证时间输出

---

## 三、T1 — 多模型适配层与智能路由（P0）

> 目标：打破"单厂商绑定"风险，让 CodeSage 真正支持多模型。

### T1.1 统一 ModelAdapter 协议层

**文件**：
- `src/main/kotlin/com/codesage/model/adapter/ModelAdapter.kt`（已存在，强化）
- `src/main/kotlin/com/codesage/model/dto/Usage.kt`（增强：补 prompt_tokens_details、cache_hit 等）
- `src/main/kotlin/com/codesage/model/registry/ModelRegistry.kt`（已有，扩展）

**变更**：
- 在 `ModelAdapter` 引入 `val capabilities: ModelCapabilities`
- `ModelCapabilities` 显式声明：`streaming`、`functionCalling`、`vision`、`toolStreaming`、`systemPromptCache`、`maxContextTokens`、`pricePer1kInput`、`pricePer1kOutput`
- 移除 `ARCHITECTURE.md` 中"已支持 minimax/kimi/openai" 的夸大描述
- `ModelRegistry` 增加 `getAdapterForCapabilities(required: Set<Capability>): List<Adapter>` 用于按能力反查

**验收**：
- [ ] `ModelCapabilities` 数据类有完整单元测试
- [ ] `getAdapterForCapabilities(VISION)` 返回所有声明支持 vision 的 adapter

---

### T1.2 实现 Anthropic 原生适配器

**文件**：
- 新增 `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicAdapter.kt`
- 新增 `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicMessagesRequest.kt`（DTO）
- 新增 `src/main/kotlin/com/codesage/model/adapter/anthropic/AnthropicStreamParser.kt`（SSE 解析）
- 新增 `src/test/kotlin/com/codesage/model/adapter/anthropic/AnthropicAdapterTest.kt`（用 MockWebServer）

**变更**：
- 实现 `Anthropic Messages API`：流式 SSE、tool_use block、system 单独字段、prompt caching
- `toVendorRequest` 将 OpenAI 风格 tool_calls 转为 Anthropic tool_use
- `parseStreamChunk` 解析 `message_start` / `content_block_start` / `input_json_delta` / `content_block_stop` / `message_delta` / `message_stop`
- `supportsFunctionCalling` 返回 true，`supportsVision` 返回 true
- prompt cache：识别 `cache_control: { type: "ephemeral" }` 标记

**验收**：
- [ ] MockWebServer 测试：`stream=true` + 1 个 tool_use，能完整跑通
- [ ] 测试 prompt cache 标记正确出现在请求 body
- [ ] 测试 image content block 解析为 vision message

---

### T1.3 实现 Google Gemini 适配器

**文件**：同 T1.2 结构

**变更**：
- Gemini `generateContent` + `streamGenerateContent`
- systemInstruction 独立字段
- `function_calling` 字段（自动/强制/关闭）
- safetySettings 默认 permissive

**验收**：
- [ ] MockWebServer 测试：流式 + function calling 完整跑通
- [ ] 测试 safety settings 默认值

---

### T1.4 实现 ModelCapability 标签 + 智能路由

**文件**：
- `src/main/kotlin/com/codesage/model/gateway/SmartRouter.kt`（新）
- `src/main/kotlin/com/codesage/model/gateway/ModelRouter.kt`（新）
- `src/main/kotlin/com/codesage/agent/core/ChatMode.kt`（改造）

**变更**：
- `SmartRouter.selectModel(task: Task, available: List<Adapter>): Adapter`
  - 规则：`if (task.requiresVision) pick vision-capable`；`else if (task.estimatedTokens > 100k) pick long-context`；`else pick cheapest`；`fallback pick default`
- 提供 `RoutingStrategy` 接口支持自定义（成本优先、速度优先、质量优先）
- `ChatMode` 从"关键词猜测"改为"用户在 UI 显式选" + 默认 routing strategy 映射
- 增加健康检查：连续 N 次失败熔断 5 分钟

**验收**：
- [ ] 单元测试：5 种 routing strategy 各自选到正确 adapter
- [ ] 单元测试：vision 任务不会路由到无 vision 的 adapter
- [ ] 单元测试：熔断后 5 分钟内不重试该 adapter

---

### T1.5 修复 ChatMode 关键词路由

**文件**：
- `src/main/kotlin/com/codesage/agent/core/AgentCore.kt`（`detectChatMode`）
- `src/main/kotlin/com/codesage/ide/ui/web/chat.html`（UI 暴露给用户显式选择）

**变更**：
- 保留 `detectChatMode` 但**仅作为建议**，不在 backend 直接套用
- UI 暴露"对话模式"下拉（GENERAL/CODING/REASONING/VISION）
- 用户未选时回退到 GENERAL + 默认 routing

**验收**：
- [ ] UI 测试：用户可切换模式，模式信息随消息一起发送到 LLM
- [ ] Backend 单元测试：未指定模式时不使用任何关键词匹配逻辑

---

## 四、T2 — MCP 补完（P0）

### T2.1 实现真正的 WebSocketTransport

**文件**：
- `src/main/kotlin/com/codesage/mcp/transport/WebSocketTransport.kt`（重写）
- 新增 `src/main/kotlin/com/codesage/mcp/transport/WebSocketClient.kt`（轻量 ws 客户端，不引入额外依赖）

**变更**：
- 选择**自实现**而非引入 OkHttp WebSocket（保持零新增依赖原则）
- 实现 RFC 6455 客户端握手：`Sec-WebSocket-Key` + `Sec-WebSocket-Version: 13`
- 实现 frame 编码/解码：text/binary/close/ping/pong
- 实现 `send/recv` 协程桥接（Channel + 单独的 read 协程）
- 心跳 ping/pong（30 秒间隔）

**验收**：
- [ ] 单元测试：与 echo server 互发 100 条消息零丢失
- [ ] 单元测试：服务器主动 close 帧时，client 正确清理
- [ ] 单元测试：网络断开后重连（由 T2.2 提供）

---

### T2.2 MCP 健康检查与自动重连

**文件**：
- `src/main/kotlin/com/codesage/mcp/server/MCPServerManager.kt`（增强）
- 新增 `src/main/kotlin/com/codesage/mcp/server/MCPHealthMonitor.kt`

**变更**：
- 每个 server 注册时启动 health 协程：每 60 秒发 `ping`（MCP 暂无 ping，自定义 `tools/list` 调用验证）
- 失败重试：指数退避 1s/2s/4s/8s/16s 上限
- 状态机：CONNECTING → CONNECTED → UNHEALTHY → RECONNECTING → CONNECTED / FAILED
- `getAllServerStatuses()` 返回完整状态对象（含 lastError、reconnectAttempts、uptimeMs）

**验收**：
- [ ] 单元测试：杀死 server 进程后，监控器在 60s 内检测并尝试重连
- [ ] 单元测试：连续 5 次重连失败后标记 FAILED，不再尝试
- [ ] 测试恢复后自动转回 CONNECTED

---

### T2.3 MCP 工具市场（Skill Discovery 集成）

**文件**：
- `src/main/kotlin/com/codesage/mcp/marketplace/MCPMarketplaceClient.kt`（新）
- `src/main/kotlin/com/codesage/ide/settings/MCPMarketplaceUI.kt`（新）

**变更**：
- 内置一个 MCP 服务器 marketplace 列表（JSON 配置文件 `mcp_marketplace.json`）
- 用户可一键"安装"（生成配置 + 触发 `addServer`）
- 支持社区 MCP 服务器（按需扩展）

**验收**：
- [ ] UI：marketplace 标签页显示可用 servers + 一键安装
- [ ] 安装后立即出现在 `MCPServerManager` 列表中

---

## 五、T3 — 项目级 RAG 替换 FTS5 占位（P1）

> 目标：让 `ContextManager.HYBRID` 策略真的能语义检索，而不是 FTS5 关键词匹配。

### T3.1 抽象 VectorStore 接口

**文件**：
- `src/main/kotlin/com/codesage/rag/VectorStore.kt`（新接口）
- `src/main/kotlin/com/codesage/rag/EmbeddingProvider.kt`（新接口）
- `src/main/kotlin/com/codesage/rag/Chunk.kt`（新数据类）

**变更**：
- `VectorStore.add(chunks: List<Chunk>, embeddings: List<FloatArray>)`
- `VectorStore.search(query: FloatArray, topK: Int): List<SearchResult>`
- `VectorStore.delete(ids: List<String>)`
- `EmbeddingProvider.embed(text: String): FloatArray`
- `EmbeddingProvider.embedBatch(texts: List<String>): List<FloatArray>`

---

### T3.2 实现 SqliteVectorStore

**文件**：
- `src/main/kotlin/com/codesage/rag/sqlite/SqliteVectorStore.kt`（新）

**变更**：
- 表：`chunks(id, file_path, start_line, end_line, content, embedding BLOB, indexed_at)`
- 检索：先 SQL `WHERE content LIKE ?%` 粗筛，再内存余弦相似度精排（避免对几百万 chunk 跑全量）
- 后续可替换为 sqlite-vec / hnswlib（但当前**不引入新依赖**）

**验收**：
- [ ] 单元测试：1000 个 chunk 的插入 + 检索 < 200ms
- [ ] 单元测试：删除操作正确清理

---

### T3.3 实现 DocumentChunker（基于 AST）

**文件**：
- `src/main/kotlin/com/codesage/rag/chunker/AstChunker.kt`（新）
- `src/main/kotlin/com/codesage/rag/chunker/ChunkStrategy.kt`（接口）

**变更**：
- Kotlin/Java：按 PsiNamedElement（class/function/property）切分，每 chunk 包含完整签名 + doc comment
- Python/JS/TS：退化为基于缩进/花括号的近似 AST 切分
- 跨文件索引：每个文件可切出 10–200 个 chunks
- 元数据：filePath、startLine、endLine、symbolKind、symbolName

**验收**：
- [ ] 单元测试：典型 Kotlin 类文件切分出 1 个 class chunk + N 个 method chunks
- [ ] 单元测试：单文件 > 2000 行时分块（避免单 chunk 过大）

---

### T3.4 接入 ContextManager HYBRID 策略

**文件**：
- `src/main/kotlin/com/codesage/agent/context/ContextManager.kt`（`ragRetrieval` / `hybridTruncate`）
- `src/main/kotlin/com/codesage/agent/context/RagContextRetriever.kt`（新）

**变更**：
- 替换 `ragRetrieval` 中 placeholder：调用 `RagContextRetriever.retrieve(lastUserMsg)` 返回真实 chunks
- HYBRID 策略：head + summary + rag（按 budget 分配比例）
- 引入 `RagContextRetriever`：每次 pre-compress 时取 topK=5 chunks，注入到 system prompt 末尾 `<project-rag-context>` 块

**验收**：
- [ ] 集成测试：100 个类索引后，query "如何处理 JWT 鉴权" 返回相关 chunks
- [ ] ContextManager 单元测试：HYBRID 模式预算被正确分配

---

### T3.5 增量索引 + 自动重建

**文件**：
- `src/main/kotlin/com/codesage/rag/ProjectIndexer.kt`（新）
- 集成到 `src/main/kotlin/com/codesage/plugin/CodeSageStartupActivity.kt`

**变更**：
- 监听 `PsiTreeChangeListener`（文件增删改）
- 增量更新 VectorStore（删除旧 chunks + 添加新 chunks）
- 启动时：扫描项目 → 全量建索引（首次），后续走增量
- 进度反馈：`AgentStreamEvent.IndexingProgress`（新增事件类型）

**验收**：
- [ ] 启动时 1000 个 Kotlin 文件，索引完成 < 30s
- [ ] 文件修改后 < 1s 内更新索引
- [ ] 单元测试：删除文件后 chunks 被清理

---

## 六、T4 — 多 Agent 协作深化（P1）

> 目标：从"keyword 路由 + 串行 chatWithTools"升级到"LLM 驱动任务分配 + Agent 消息总线"。

### T4.1 引入 AgentMessageBus

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/AgentMessageBus.kt`（新）
- `src/main/kotlin/com/codesage/agent/multiagent/AgentMessage.kt`（新 sealed class）

**变更**：
- 类型化消息：`TaskAssigned` / `TaskCompleted` / `TaskBlocked` / `QuestionAsked` / `AnswerProvided` / `SharedContext` / `Escalation`
- `MessageBus.publish(channel, message)`、`subscribe(channel, handler)`
- 用 Kotlin Channel 或 `MutableSharedFlow` 实现
- TTL 30s，超时未 ack 视为 dead

**验收**：
- [ ] 单元测试：消息发布/订阅不丢失
- [ ] 单元测试：慢消费者不阻塞快消费者

---

### T4.2 LLM 驱动的角色选择

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/AgentSelector.kt`（新）
- `src/main/kotlin/com/codesage/agent/multiagent/MultiAgentOrchestrator.kt`（改造）

**变更**：
- `AgentSelector.selectAgents(task: Task, available: List<AgentRole>): List<AgentRole>`
- 用 LLM 一次性决策：传入任务描述 + 各角色能力清单，让 LLM 返回 JSON `[{"role": "CODER", "reason": "..."}, ...]`
- 移除 `determineParticipants` 的 keyword contains
- 提供"显式指定"模式作为旁路

**验收**：
- [ ] 单元测试（mock LLM）：不同 prompt 触发不同角色组合
- [ ] 集成测试：复杂任务至少涉及 2 个 Agent 协作

---

### T4.3 Planner 输出结构化解析验证

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/PlannerAgent.kt`（改造）
- `src/main/kotlin/com/codesage/agent/planner/StructuredPlanParser.kt`（新）

**变更**：
- 在 `PlannerAgent.process` 后增加 `parseAndValidate(planOutput): DagTaskPlan?`
- 解析失败时回退：再问 LLM 一次 "请重新输出严格 YAML"
- 二次失败：fallback 到 `TaskPlanner.decomposeToDagPlan` 的句法切分
- 解析时验证：所有 dependency 必须引用已知 step id、无循环

**验收**：
- [ ] 单元测试：合法 YAML 解析为正确 DagTaskPlan
- [ ] 单元测试：循环依赖的 YAML 被识别并拒绝
- [ ] 单元测试：LLM 输出 markdown 包裹的 yaml 时正确剥离

---

### T4.4 Agent 间共享 scratchpad

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/SharedScratchpad.kt`（新）
- `src/main/kotlin/com/codesage/agent/core/BaseAgent.kt`（改造，注入 scratchpad）

**变更**：
- `SharedScratchpad.put(key, value)` / `get(key)` / `list()`
- 每个 multi-agent 任务创建一个 scratchpad 注入到所有 sub-agents 的 system prompt
- 阶段产出：Planner 的"DAG 计划"、Coder 的"修改文件列表"、Reviewer 的"问题清单"、Tester 的"测试结果" 全部可被后续 Agent 读取

**验收**：
- [ ] 单元测试：scratchpad 跨 Agent 可见
- [ ] 集成测试：Planner 产出被 Coder 看到（mock chatWithTools，验证 system prompt 包含 scratchpad 内容）

---

### T4.5 Kanban 真实 LLM 分解

**文件**：
- `src/main/kotlin/com/codesage/agent/multiagent/KanbanOrchestrator.kt`（`decomposeToKanban` 改造）
- 新增 `LLMTaskDecomposer` 替代启发式 split

**变更**：
- 用 LLM 分解复杂任务为 KanbanTask 列表（5–20 个）
- 输出格式：JSON `{"tasks": [{"description": "...", "toolset": "dev|test|research", "estimated_minutes": 5}]}`
- 解析失败时降级到现有启发式
- 缓存：相同任务描述的分解结果 24h 缓存

**验收**：
- [ ] 单元测试：典型需求"重构用户登录模块"分解为 4–8 个 KanbanTask
- [ ] 性能：分解延迟 < 3s（单次 LLM 调用）

---

## 七、T5 — PSI 与本地代码分析重构（P1）

> 目标：把"基于类名字符串 + 反射"的分析器，升级为类型安全 + 真正产出本地静态分析结果。

### T5.1 PSI 类型安全重构

**文件**：
- `src/main/kotlin/com/codesage/analysis/PSIAnalyzer.kt`（重写）
- `src/main/kotlin/com/codesage/analysis/ElementClassifier.kt`（新）

**变更**：
- 引入 Kotlin/Java 显式 PSI 元素判断：`element is KtClass / KtFunction / KtProperty / PsiClass / PsiMethod / PsiField`
- 移除 `element.javaClass.simpleName.contains("Class")` 字符串匹配
- `ElementClassifier.classify(element: PsiElement): SymbolType` 用 `when` + type check
- 显式处理跨语言（Kotlin/Java/Scala/Groovy）

**验收**：
- [ ] 单元测试：5 种 PSI 元素（KtClass、KtFunction、Java PsiClass、Java PsiMethod、PsiField）分类正确
- [ ] 集成测试：混合 Kotlin + Java 项目，符号索引无重复/无遗漏

---

### T5.2 修复 superTypes 反射访问

**文件**：
- `src/main/kotlin/com/codesage/analysis/PSIAnalyzer.kt`（`extractClassSymbol`）

**变更**：
- 用 `KtClassOrObject.superTypeListEntries` / `PsiClass.interfaces` / `PsiClass.superClass` 替代反射
- 同时取 extends 和 implements，返回 `List<String>` 完整列表

**验收**：
- [ ] 单元测试：典型 Kotlin 类 `class Foo : Bar(), Baz` 提取出 `["Bar", "Baz"]`
- [ ] 单元测试：Java `class Foo extends Bar implements Baz` 同样正确

---

### T5.3 结构化 Code Insight 工具

**文件**：
- `src/main/kotlin/com/codesage/analysis/CodeInsightExecutor.kt`（已有，扩展）
- 新增 `src/main/kotlin/com/codesage/analysis/insights/CyclomaticComplexity.kt`
- 新增 `src/main/kotlin/com/codesage/analysis/insights/LongParameterListDetector.kt`
- 新增 `src/main/kotlin/com/codesage/analysis/insights/DeadCodeDetector.kt`

**变更**：
- `analyze_symbol` 返回结构化：`{kind, signature, visibility, complexity, parameterCount, callers, callees, docStatus}`
- `find_usages` 真实用 `ReferencesSearch.search()`
- `get_inheritance_chain` 真实递归 superClass（不再返回 `["unknown"]`）
- 新增工具：`detect_long_methods` (lines > 50)、`detect_long_params` (params > 5)、`estimate_complexity`（圈复杂度近似）

**验收**：
- [ ] 单元测试：圈复杂度计算对典型代码正确
- [ ] 集成测试：`analyze_symbol` 在项目内能列出真实 callers

---

### T5.4 本地代码审查引擎（替换 prompt-only Skill）

**文件**：
- `src/main/kotlin/com/codesage/analysis/insights/LocalCodeReviewer.kt`（新）
- 保留 `builtin_code_review` Skill，但底层实现切换到本地引擎 + LLM 润色

**变更**：
- 本地规则：security patterns（硬编码密码、SQL 注入、XSS）、performance patterns（N+1、循环 IO）、style（行长、命名）
- 规则以 YAML 配置（与规则引擎共用）
- 命中后产出结构化问题清单（line、severity、suggestion）
- LLM 仅做最终建议润色（可选）

**验收**：
- [ ] 单元测试：典型 Java 代码含 `Statement.execute("SELECT * FROM users WHERE id=" + id)` 命中 SQL 注入规则
- [ ] 单元测试：100 个文件的本地 review < 5s（无 LLM 调用）

---

## 八、T6 — 工具系统统一（P0，可与 T0/T1/T2 并行）

> 目标：消除"Tool schema + Handler 实现 + 硬编码 when"的三处声明混乱。

### T6.1 合并 Tool 定义与 Handler 到单一来源

**文件**：
- `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt`（移除顶层 tool 工厂函数）
- `src/main/kotlin/com/codesage/agent/tools/Tool.kt`（新：作为 tool 元数据 + handler 的统一载体）
- `src/main/kotlin/com/codesage/agent/tools/handlers/`（每个 handler 直接定义 tool schema）

**变更**：
- 重构为：`class ReadFileTool : ToolHandler { override val tool = Tool(...); override suspend fun execute(...) = ... }`
- `ToolRegistry.createDefault()` 自动发现 `handlers/` 下所有 `ToolHandler` 实现
- 消除 IDEFileHandlers.kt 末尾的反射 `resolvePath` hack（改为 `IDETools.resolvePath` 改为 `internal`）

**验收**：
- [ ] 单元测试：新增一个 tool 只需创建 1 个 class
- [ ] 集成测试：所有现有 50+ 工具仍可被发现和调用

---

### T6.2 移除 ToolExecutor 末尾硬编码 when

**文件**：
- `src/main/kotlin/com/codesage/agent/tools/ToolExecutor.kt`
- 6 个 `codeInsightExecutor.*` 工具迁移到独立 handler class

**验收**：
- [ ] 单元测试：`analyze_symbol` 仍可调用
- [ ] `ToolExecutor.executeToolOnce` 简化为：先查 registry，没找到就 throw `UnknownTool`

---

### T6.3 引入 ToolProvider SPI

**文件**：
- `src/main/kotlin/com/codesage/agent/tools/ToolProvider.kt`（已有，强化）
- `src/main/resources/META-INF/plugin.xml`（注册扩展点）

**变更**：
- 真正在 plugin.xml 注册 `<extensionPoint>`（已声明但未实现）
- 提供示例 `ExternalToolProvider` 用于测试
- 文档化 SPI 接入方式

**验收**：
- [ ] 新增示例 plugin 可注册 1 个 tool 并被 CodeSage 发现

---

### T6.4 工具分类/标签/检索 API

**文件**：
- `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt`（扩展）

**变更**：
- `Tool` 元数据加 `category: ToolCategory`（FILE/GIT/NETWORK/SYSTEM/CODE_ANALYSIS/BUILD）和 `tags: Set<String>`
- `ToolRegistry.findByCategory(category): List<ToolHandler>`
- `ToolRegistry.search(query): List<ToolHandler>`（name/description 模糊匹配）

**验收**：
- [ ] 单元测试：搜索"file"返回所有文件类工具

---

### T6.5 高价值工具扩充

**文件**：
- 新增工具：
  - `create_pull_request`（gh CLI 包装）
  - `run_linter`（按 build.gradle/pom.xml 检测并运行）
  - `start_debugger`（基于 `XDebuggerManager`）
  - `database_schema`（JDBC 探测 schema）
  - `git_worktree`（worktree 创建/切换）
  - `symbol_search`（基于 `SymbolIndex`，本地符号级搜索）

**验收**：
- [ ] 每个新工具至少 2 个单元测试
- [ ] 文档：每个工具一份 README

---

## 九、T7 — 可观测性完善（P2）

### T7.1 EventHistory 分页优化

**文件**：`src/main/kotlin/com/codesage/agent/core/EventHistory.kt`

**变更**：
- 内部用 ring buffer + offset 索引替代 `ConcurrentLinkedDeque`
- `query(offset, limit, type, sessionId)` 在索引层过滤，O(log n + limit)

**验收**：
- [ ] 10000 事件场景下 `query` 延迟 < 5ms

---

### T7.2 工具调用追踪关联

**文件**：
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`
- `src/main/kotlin/com/codesage/agent/tools/ToolExecutor.kt`

**变更**：
- 每次 `ToolCallStart` 创建子 span
- `ToolCallResult` 结束 span，记录 success/duration
- `tracer.endChildSpan` 事件写入 `EventHistory`

**验收**：
- [ ] UI 显示工具调用的 trace 树

---

### T7.3 Observability 面板 UI

**文件**：
- `src/main/resources/webui/observability.html`（新）
- `src/main/kotlin/com/codesage/ide/toolwindow/ObservabilityTab.kt`（新）

**变更**：
- 新增 "Observability" 标签页
- 显示：当前会话 trace 树、最近 100 事件、metrics 仪表盘

**验收**：
- [ ] 工具栏新增图标可打开面板

---

## 十、T8 — 测试覆盖与 CI（P2，持续）

### T8.1 并发/竞态单元测试

针对 T0 所有修复，编写 `*ConcurrencyTest.kt`：
- 1000 线程并发 getOrCreateSession
- 10000 事件并发 emit
- 100 个并发 session save 后无丢失

### T8.2 模型适配器 mock 测试

- AnthropicAdapterTest / GeminiAdapterTest 用 MockWebServer

### T8.3 端到端测试

- Playwright + JCEF：UI 集成测试（创建会话、发送消息、查看流式响应）

### T8.4 CI Pipeline

- GitHub Actions：lint + test + buildPlugin
- 必须通过的检查：compileKotlin 无 error、test 0 failure、plugin zip 可生成

---

## 十一、推荐执行顺序

```
第 1-2 周：T0（基础设施修复）— 全员
第 2-4 周：T1（多模型适配层）— 1 人
          T6（工具系统统一）— 1 人（可与 T1 并行）
第 4-5 周：T2（MCP 补完）— 1 人
第 5-7 周：T3（项目级 RAG）— 1-2 人（最重）
          T4（多 Agent 深化）— 1 人（与 T3 并行）
第 7-9 周：T5（PSI 重构 + 本地分析）— 1 人
第 9-10 周：T7（可观测性）— 1 人
持续：T8（测试 + CI）
```

**里程碑**：
- **M0（2 周）**：T0 完成，0 个 Critical 遗留
- **M1（4 周）**：Anthropic/Gemini 接入，ChatMode 改为用户显式
- **M2（7 周）**：项目级 RAG 上线，多 Agent 真实协作
- **M3（10 周）**：工具系统统一，本地代码分析上线，UI 一体性达成

---

## 十二、风险与决策点

| 风险 | 影响 | 决策建议 |
|------|------|---------|
| T1.2/1.3 实现 Anthropic/Gemini 协议 | 高 | 先做 mock server 验证流式与 tool calling，不接入真实 API 避免成本 |
| T3 引入向量存储 | 中 | 建议先用 SqliteVectorStore 走通端到端，再评估 sqlite-vec |
| T4 多 Agent 改造 | 中 | 保留 keyword 路由作为 fallback 模式，可配置启用 LLM 路由 |
| T5 重构 PSI 分析器 | 高 | 提供完整现有测试覆盖率，回归有保障 |
| 工具系统重构 T6 | 中 | 渐进式迁移：先合并定义，再迁移实现，最后移除 when |

---

*计划编制完毕。执行时建议建立周例会跟进，每完成一个 Track 跑一次全量回归。*
