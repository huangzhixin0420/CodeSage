# CodeSage 待优化点落地计划

> 编制时间：2026-06-02
> 适用版本：2026.1.2 → 2026.2.x
>
> 来源：`docs/TARGETED_OPTIMIZATION_PLAN.md` 中尚未完成/需要加固的项。

## 〇、当前状态盘点

### 已完成（无需重做）
- T0.1 AgentCore 并发竞态 → `BoundedConcurrentMap`、`AgentCoreConcurrencyTest`
- T0.2 EventBatchEmitter 资源 → `EventBatchEmitterResourceTest`
- T0.3 ConversationPersistence 资源 → `ConversationPersistenceResourceTest`
- T0.4 retry counter LRU → `AgentErrorRecovery` + 测试
- ~~T0.5 KanbanWorker 并行 → `KanbanWorkerConcurrencyTest`~~（2026-06 移除）
- T0.6 空指针修复 → `NullSafetyTest`
- T0.7 时间线程安全 → `ThreadSafeTimeTest`、`DateTimeFormatter`
- T1.1 ModelCapabilities → `ModelCapabilities`、`ModelCapabilitiesTest`
- T1.2 Anthropic 适配器 → `AnthropicAdapterTest`
- T1.4 智能路由 → `SmartRouterTest`
- T2.1 WebSocketTransport → `WebSocketTransportTest`
- T2.2 健康监控 → `MCPHealthMonitorTest`
- T3 RAG 替换 → `SqliteVectorStoreAndChunkerTest`
- T5.1 / T5.2 PSI 类型安全 + 反射 → `ElementClassifierTest`
- T6.1 / T6.3 / T6.4 / T6.5 → `UnifiedToolTest`、`ToolClassificationAndHighValueTest`
- T6.2 末尾硬编码 when（实际已删，注释保留为 fallback doc）

### 待做（本计划范围）
- **T1.3** Google Gemini 适配器（**文件已损坏，需要先修复**）
- **T1.5** ChatMode 关键词路由修复（保留为"建议"、UI 暴露"对话模式"下拉）
- **T2.3** MCP 工具市场（marketplace）
- **T4** 多 Agent 协作深化（AgentMessageBus + LLM 角色选择 + 共享 scratchpad）
- **T5.3 / T5.4** 结构化 Code Insight 工具 + 本地代码审查引擎
- **T7** 可观测性完善（EventHistory 索引 + 工具调用追踪关联）
- **T8** CI Pipeline

## 一、执行顺序

```
Step 1  T1.3 修复 GeminiAdapter（blocking — 当前无法编译）
Step 2  T1.5 ChatMode 关键词路由修复 + UI 暴露
Step 3  T2.3 MCP 工具市场
Step 4  T4   多 Agent 协作深化
Step 5  T5.3 / T5.4 结构化 Code Insight + 本地审查
Step 6  T7   可观测性
Step 7  T8   CI
```

每步交付：
- 业务代码
- 至少 2 个单元测试覆盖新功能 / 回归保护
- `./gradlew compileKotlin` 通过
- `./gradlew test --tests "新测试类"` 通过

## 二、详细设计

### Step 1 — T1.3 Gemini 适配器修复
**问题**：`GeminiAdapter.kt` 中 `parseStreamChunk` 函数嵌套了 `chat` 函数 + 越界 `return`，编译失败。
**方案**：重写为正确结构：
- `parseStreamChunk` 独立完成解析（流式 delta + toolCall 提取）
- `chat` 作为顶层 `suspend fun`（与 AnthropicAdapter 对齐）
- `fetchModels` 顶层 `override suspend fun`
- 增加 SSE 解析（SSE 协议中 Gemini 流式以 `data: {json}\n\n` 形式）
- 增加 MockWebServer 单元测试（流式 + 工具调用 + 非流式错误路径）

### Step 2 — T1.5 ChatMode 路由
**问题**：`detectChatMode` 仍在 AgentCore 内被 `chat` 调用（搜索了 backend 套用）
**方案**：
- 保留 `detectChatMode` 函数但仅作为 `fun suggestChatMode(message): ChatMode?`（不强制使用）
- 新增 `ChatModeRouter`：仅在 `mode` 字段为 null 时才回退到建议
- `chat / chatStream / chatWithTools` 增加 `modeOverride: ChatMode?` 形参（null = 用户未选）
- UI 层 `chat.html` 增加模式选择器 + 切换时通过 websocket 发送 `chat.mode` 消息
- 测试：默认不调用 detectChatMode（验证 mock 调用次数）

### Step 3 — T2.3 MCP 工具市场
**方案**：
- `McpMarketplaceEntry` data class (id, name, description, command, args, env, tags)
- `McpMarketplaceRegistry` 读取 `mcp_marketplace.json`（src/main/resources/mcp/）
- `McpMarketplaceService.install(entry)` → 生成 `MCPServerConfig` + 调 `MCPServerManager.addServer`
- 单元测试：mock registry + 验证 install 流程不写磁盘（使用 stub config）

### Step 4 — T4 多 Agent 深化
**方案**（保留 keyword 路由作为 fallback，可配置）：
- `AgentMessageBus`（sealed `AgentMessage` + Channel）
- `AgentRoleSelector`：LLM 驱动的角色选择（注入 gateway）+ 显式指定旁路
- `SharedScratchpad`（ConcurrentHashMap + TTL 30min）
- 测试：scratchpad 跨 agent 可见、bus publish 不丢失、selector 解析回退

### Step 5 — T5.3 / T5.4 Code Insight + 本地审查
**方案**：
- `LocalCodeReviewer`（pattern 库，YAML 兼容）+ `ReviewFinding` data class
- 集成到 `builtin_code_review` skill 底层（替换 prompt-only）
- 单元测试：典型 bad code 命中规则
- 圈复杂度近似（节点 + 1 算子；不引入外部库）

### Step 6 — T7 可观测性
**方案**：
- `EventHistory` 增加 ring buffer 实现 + 按 sessionId 索引
- `ToolCallTraceSpan`：`ToolExecutor.execute` 增加 startChildSpan / endChildSpan
- 测试：10000 事件 query 延迟 < 50ms（断言 < 50ms；老实现 O(n) 验证通过 + 新实现更快）

### Step 7 — T8 CI
**方案**：
- `.github/workflows/ci.yml`：JDK 17 + gradle build + test
- 不强制要求 platform 测试通过（保留 sandbox 例外配置）

## 三、风险与决策

| 风险 | 处理 |
|------|------|
| Gemini 修复可能引入新 bug | 用 AnthropicAdapterTest 同款 MockWebServer 测试 |
| T4 改动太大 | 仅做骨架（Bus + Scratchpad + selector 接口），LLM 路由实现可后置 |
| T5.4 规则引擎可能与 rule/ 重叠 | LocalCodeReviewer 独立模块，避免与 rule.engine 冲突 |
| T7 改 EventHistory 风险大 | 保持原 ConcurrentLinkedDeque 实现 + 新增 RingBuffer 索引；query 走索引 |
| T8 网络受限 | CI workflow 模板 + 注释说明本地调试步骤 |

---

*计划编制完毕。开始执行 Step 1。*
