# CodeSage 优化改进进度记录

> 本文件记录各阶段优化任务的执行进度，方便后续恢复任务时间点。

---

## Phase 5: P1 `delegate_task` 子 Agent 递归深度与工具白名单 — ✅ 已完成

**完成时间**: 2026-06-14

### 已交付组件

| 文件 | 说明 |
|------|------|
| `src/main/kotlin/com/codesage/agent/core/SubAgentExecutor.kt` | 新增 `maxDepth` 实例属性；`spawn()` 支持 `max_depth` / `allowed_tools` / `denied_tools`；`createToolRegistryForToolset()` 按 allow/deny 过滤并返回 `Result<ToolRegistry>`；prompt 动态注入深度、白名单、黑名单、实际可用工具名 |
| `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` | `executeDelegateTask()` 解析并校验 `max_depth`（1-5）、`allowed_tools`、`denied_tools`；透传给 `SubAgentExecutor.spawn()`；越界直接返回 JSON 错误 |
| `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt` | `delegateTaskTool()` schema 新增 `max_depth` / `allowed_tools` / `denied_tools` 及描述 |
| `src/test/kotlin/com/codesage/agent/core/SubAgentExecutorTest.kt` | 新增 6.10.2/6.10.3 单元测试：动态深度、maxDepth=0 校验、白名单过滤、黑名单优先、禁用 delegate_task 错误、非存在工具保留 delegate_task、prompt 注入限制说明 |
| `src/test/kotlin/com/codesage/agent/core/EnhancedAgentLoopDelegateTaskTest.kt` | 新增测试：`max_depth` 透传、越界不 spawn、`allowed_tools`/`denied_tools` 透传 |

### 关键设计决策

1. **默认行为不变**：未传新参数时 `maxDepth` 默认 2，`allowedTools`/`deniedTools` 为空，行为与旧实现完全一致。
2. **深度范围校验**：`max_depth` 在 `[1, 5]` 范围内才允许执行，越界在 `EnhancedAgentLoop` 层直接返回结构化 JSON 错误，不创建子 Agent。
3. **最小权限 + 汇报能力平衡**：`allowed_tools` 白名单会与 `toolset` 取交集，但始终保留 `delegate_task`（除非显式加入 `denied_tools`），保证子 Agent 既能汇报也能继续委托；显式拒绝 `delegate_task` 时返回中文错误“子 Agent 被禁止再委托”。
4. **黑名单优先于白名单**：过滤顺序为 `toolset → allowed_tools 交集 → denied_tools 移除`。
5. **Prompt 自我约束**：子 Agent system prompt 的 Recursion 段显示实际 `maxDepth`；当存在 allow/deny 限制时，prompt 列出 Available/Allowed/Denied tools，帮助模型自我约束。

### 测试状态

```
1243 tests completed, 0 failed ✅
./gradlew check ✅
```

---

## Phase 4: P0 多模态文档读取（read_document）— ✅ 已完成

**完成时间**: 2026-06-14

### 已交付组件

| 文件 | 说明 |
|------|------|
| `src/main/kotlin/com/codesage/agent/tools/handlers/ReadDocumentTool.kt` | 新增 `read_document` UnifiedTool：支持图片 base64、PDF 文本提取、Jupyter Notebook 解析 |
| `src/main/kotlin/com/codesage/shared/serialization/JsonArgDecoders.kt` | 新增 `intArgOrNull` 可空整型参数解码器 |
| `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt` | 注册 `ReadDocumentTool` |
| `src/main/kotlin/com/codesage/tools/guardrails/ToolGuardrails.kt` | 将 `read_document` 加入已知安全工具白名单 |
| `src/test/kotlin/com/codesage/agent/tools/ReadDocumentToolTest.kt` | 7 个单元测试：图片、PDF 分页、ipynb、错误路径 |
| `build.gradle.kts` | 引入 `org.apache.pdfbox:pdfbox:3.0.2` 并加入打包清单 |

### 关键设计决策

1. **独立工具语义**：不扩展 `read_file`，新增 `read_document` 专门处理多模态/复杂文档，避免污染纯文本读取语义。
2. **格式支持**：
   - 图片：JDK `ImageIO` 解码，返回 `data:image/*;base64,...` 数据 URL、`mime_type`、宽高。
   - PDF：Apache PDFBox 3.0.2，支持 `page` 单页（1-based）与 `max_pages` 批量返回，每页文本可截断。
   - `.ipynb`：`kotlinx.serialization.json` 解析，提取 `cells`（cell_type / source / execution_count / outputs）。
3. ** headless 兼容**：无 IntelliJ `Application` 时绕过 VFS，直接走 `java.io.File`，便于单元测试与无 IDE 环境。
4. **安全与预算**：默认 20MB 文件大小上限、PDF 每页 10k 字符上限、`max_pages` 默认 10；工具为只读，已加入 Guardrails 安全白名单。

### 测试状态

```
8 tests completed, 0 failed ✅
./gradlew check ✅
```

---

## Phase 1: Agent Loop 健壮化 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `src/main/kotlin/com/codesage/shared/exceptions/AppException.kt` | 扩展异常体系：新增 `RateLimitException`, `AuthExpiredException`, `ContextTooLongException`, `EmptyResponseException`, `InvalidToolCallException`, `ProviderUnavailableException` |
| `src/main/kotlin/com/codesage/agent/core/IterationBudget.kt` | 迭代预算管理器：支持 consume/refund/forceConsume/remaining/isExhausted/reset |
| `src/main/kotlin/com/codesage/agent/core/AgentErrorRecovery.kt` | 错误分类与恢复引擎：11 种 `FailoverReason`，多层重试策略，指数退避+jitter，后备模型切换 |
| `src/main/kotlin/com/codesage/agent/core/AgentHooks.kt` | 钩子接口体系：`AgentHooks` + `CompositeAgentHooks`，覆盖会话/轮次/LLM/工具全生命周期 |
| `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` | 增强型对话循环：状态机驱动（`ConversationPhase`），集成 IterationBudget + AgentErrorRecovery，支持中断 |
| `src/main/kotlin/com/codesage/agent/core/AgentCore.kt` | 门面模式重构：`chatWithTools` 委托给 `EnhancedAgentLoop`，保留所有原有 API 向后兼容 |
| `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt` | 新增 `Thinking` 流式事件 |
| `src/main/kotlin/com/codesage/ide/ui/components/chat/ChatPanel.kt` | 处理新的 `Thinking` 事件 |
| `src/main/kotlin/com/codesage/model/adapter/minimax/MiniMaxAdapter.kt` | 修复 `supportsFunctionCalling()` 返回值（false → true） |
| `src/test/kotlin/com/codesage/agent/core/IterationBudgetTest.kt` | 迭代预算单元测试（8 个 case） |
| `src/test/kotlin/com/codesage/agent/core/AgentErrorRecoveryTest.kt` | 错误恢复单元测试（13 个 case） |

### 关键设计决策

1. **AgentCore 门面模式**: 保留所有公共 API（`chat()`, `chatStream()`, `chatWithTools()`, `executeTask()` 等），内部将 `chatWithTools` 委托给 `EnhancedAgentLoop`。
2. **状态机驱动**: `EnhancedAgentLoop` 使用 `ConversationPhase` 枚举管理对话生命周期，而非简单的 `while` 循环。
3. **错误恢复隔离**: 按 `FailoverReason` 隔离重试计数器，避免一种错误耗尽所有重试配额。
4. **系统提示缓存**: `EnhancedAgentLoop` 缓存系统提示到 `cachedSystemPrompt`，支持 prefix cache 优化。

### 测试状态

```
63 tests completed, 0 failed ✅
```

---

## Phase 2: 记忆与 Context 引擎 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `agent/memory/MemoryProvider.kt` | 记忆提供者抽象接口，支持多后端 |
| `agent/memory/BuiltInMemoryProvider.kt` | SQLite + FTS5 内置实现；含 sessions/turns/memories/fts_search 表；自动事实提取；FTS5 不可用时自动回退 LIKE |
| `agent/memory/MemoryManager.kt` | 统一编排多 provider；只允许一个外部 provider；聚合 prefetch/sync/systemPrompt |
| `agent/memory/MemoryNudger.kt` | 每 N 轮（默认8轮）自动注入记忆回顾提醒 |
| `agent/context/ContextEngine.kt` | Context 引擎抽象基类；token 预算管理；protectFirstN/protectLastN |
| `agent/context/ContextCompressor.kt` | 结构化摘要压缩：Active Task / Resolved Decisions / Files Modified / Pending Questions；图像清理；工具输出截断 |
| `agent/context/TokenEstimator.kt` | Token 估算器：中英文混合估算、图像 1600 tokens/张、消息列表估算 |
| `agent/context/ContextManager.kt` | 升级：集成 ContextEngine（token 预算驱动压缩）、injectMemoryContext |
| `agent/core/AgentCore.kt` | 集成 MemoryManager：createSession 初始化记忆、chatWithTools 每轮 prefetch/sync、注册记忆工具 |
| `agent/core/EnhancedAgentLoop.kt` | 集成 MemoryManager + MemoryNudger：INIT 注入系统提示、LLM_CALL 前 prefetch、POST_TURN 后 sync |

### 关键设计决策

1. **FTS5 自动回退**：检测 FTS5 可用性，不可用时自动回退到 `LIKE` 查询，确保在任意 SQLite 环境下工作。
2. **ContextEngine 插件化**：`ContextManager` 通过 `setContextEngine()` 支持替换压缩策略，保持向后兼容。
3. **记忆工具暴露给模型**：`memory_search` / `memory_add` / `memory_update` 三个工具自动注册到 ToolRegistry，模型可自主调用。
4. **最小摘要预算**：`calculateSummaryBudget` 保证至少 800 字符，避免小 context 下摘要过短失去意义。

### 测试状态

```
86 tests completed, 0 failed ✅
```

---

## Phase 3: Skill 自我进化系统 — ⏳ 待开始

### 计划交付组件

| 文件 | 说明 |
|------|------|
| `agent/memory/MemoryProvider.kt` | 记忆提供者抽象接口 |
| `agent/memory/BuiltInMemoryProvider.kt` | SQLite + FTS5 内置记忆实现 |
| `agent/memory/MemoryManager.kt` | 统一编排多 provider |
| `agent/memory/MemoryTools.kt` | 暴露给模型的记忆工具 |
| `agent/memory/MemoryNudger.kt` | 定期提醒逻辑 |
| `agent/context/ContextEngine.kt` | Context 引擎抽象基类 |
| `agent/context/ContextCompressor.kt` | 结构化摘要压缩实现 |
| `agent/context/TokenEstimator.kt` | Token 估算器 |

---

## Phase 3: Skill 自我进化系统 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `skill/registry/DynamicSkillRegistry.kt` | 动态技能注册表：toolset 分组、TTL 可用性检查（30s 缓存）、动态 schema 覆盖、生成计数器、使用统计、来源追踪 |
| `skill/curator/SkillCurator.kt` | 技能策展器：后台审查 fork（>5 tool iterations 触发）、对话模式分析、自动技能生成、定期策展（合并重复/删除未使用）、字符串相似度聚类 |
| `skill/curator/SkillProvenance.kt` | 来源追踪：ThreadLocal 实现，区分 foreground/background_review/user_created/agent_created |
| `~/.codesage/skills/auto/` | 自动生成技能的持久化目录（JSON 格式） |

### 关键设计决策

1. **DynamicSkillRegistry 向后兼容**：继承自现有 `SkillRegistry`，所有原有 API 保持不变。
2. **Toolset 可用性缓存**：`isToolsetAvailable()` 使用 30s TTL，避免频繁探测外部依赖。
3. **轻量级模式分析**：`SkillCurator` 使用规则引擎（高频工具检测、前缀匹配）而非 LLM，降低复杂性和延迟。
4. **并发安全**：后台审查使用 `AtomicBoolean` 锁，防止并发执行。

### 测试状态

```
99 tests completed, 0 failed ✅
```

---

## Phase 4: 子 Agent 与并行协作 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `agent/tools/ToolRegistry.kt` | 新增 `delegate_task` 工具（task_description, toolset, max_iterations, context_files） |
| `agent/core/SubAgentExecutor.kt` | 子 Agent 执行器：隔离 context、按 toolset 加载工具、进度回调、并行 spawn |
| ~~`agent/multiagent/KanbanOrchestrator.kt`~~ | ~~Kanban 调度器~~（2026-06 移除） |
| ~~`agent/multiagent/KanbanWorker.kt`~~ | ~~Kanban 执行器~~（2026-06 移除） |
| `agent/core/EnhancedAgentLoop.kt` | 集成 `delegate_task` 工具路由到 SubAgentExecutor |
| `agent/core/AgentCore.kt` | 自动初始化 SubAgentExecutor 并注入到对话循环 |

### 关键设计决策

1. **子 Agent 完全隔离**：`SubAgentExecutor.spawn()` 创建新的 `AgentCore` 实例，独立 session ID，独立上下文，避免污染父 Agent。
2. **Toolset 分组加载**：`createToolRegistryForToolset()` 支持 dev/research/test/browser 等工具集，子 Agent 只加载所需工具。
3. ~~**Kanban 只做调度**：~~（Kanban 整体移除，此条作废）
4. **delegate_task 是一等工具**：模型可自主决定何时 spawn 子 Agent，增强 Agent 的自主协作能力。

### 测试状态

```
115 tests completed, 0 failed ✅
```

---

## 优化1: 子Agent进度可视化 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `agent/core/AgentStreamEvent.kt` | 新增 `SubAgentStart`, `SubAgentProgress`, `SubAgentComplete` 事件 |
| `ide/ui/components/chat/ChatPanel.kt` | 处理 SubAgent 事件流，动态创建进度面板 |
| `ide/ui/components/chat/SubAgentProgressPanel.kt` | 子 Agent 进度面板：任务描述、进度条、状态标签、取消按钮 |

### 测试状态
```
115 tests completed, 0 failed ✅
```

---

## ~~优化2: Kanban看板面板~~ — 🗑️ 已撤回 (2026-06)

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| ~~`ide/ui/components/kanban/KanbanBoardPanel.kt`~~ | ~~看板面板~~（已删除） |
| ~~`ide/ui/components/kanban/KanbanTaskCard.kt`~~ | ~~任务卡片~~（已删除） |
| `ide/ui/AgentToolWindowPanel.kt` | 集成看板标签页，支持刷新和清空操作 |

### 测试状态
```
115 tests completed, 0 failed ✅
```

---

## 优化3: 扩充内置工具（6→24） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `agent/tools/IDETools.kt` | 新增 8 个工具：findFile, grepCode, getFileInfo, readMultipleFiles, editFile, deleteFile, copyFile, moveFile |
| `agent/tools/ToolRegistry.kt` | 注册全部 24 个工具定义 |
| `agent/tools/ToolExecutor.kt` | 分发逻辑扩展支持新工具 |

### 工具清单（24个）

| # | 工具名 | 说明 |
|---|--------|------|
| 1 | read_file | 读取文件内容 |
| 2 | write_file | 写入文件 |
| 3 | list_directory | 列出目录 |
| 4 | search_code | 代码搜索 |
| 5 | run_command | 运行命令 |
| 6 | get_project_structure | 项目结构 |
| 7 | find_file | 按文件名查找 |
| 8 | grep_code | 按内容搜索 |
| 9 | get_file_info | 文件元信息 |
| 10 | read_multiple_files | 批量读取 |
| 11 | edit_file | 行级编辑（替换/插入/删除） |
| 12 | delete_file | 删除文件 |
| 13 | copy_file | 复制文件 |
| 14 | move_file | 移动文件 |
| 15 | memory_search | 记忆搜索 |
| 16 | memory_add | 添加记忆 |
| 17 | memory_update | 更新记忆 |
| 18 | delegate_task | 子Agent委派 |
| 19 | skill_list | 列出技能 |
| 20 | skill_execute | 执行技能 |
| 21 | skill_create | 创建技能 |
| 22 | skill_update | 更新技能 |
| 23 | skill_delete | 删除技能 |
| 24 | skill_info | 技能详情 |

### 测试状态
```
121 tests completed, 0 failed ✅
```

---

## 优化4: MCP生态集成 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `mcp/client/MCPClient.kt` | MCP客户端（已有）：StdIO/HTTP/WebSocket传输，工具调用 |
| `mcp/server/MCPServerManager.kt` | MCP服务器管理器（已有）：多服务器管理，工具同步为技能 |
| `shared/config/PluginConfig.kt` | 扩展：MCPServerPersistentConfig持久化配置，MCP服务器增删改查 |
| `agent/core/AgentCore.kt` | 集成：`getMCPServerManager()`暴露MCP管理能力 |

### 关键设计决策
1. **已有MCP框架充分利用**：MCPClient/MCPServerManager/Transport层已有完整实现，本次完成与AgentCore和PluginConfig的集成。
2. **配置持久化**：MCP服务器配置通过IntelliJ `PersistentStateComponent`持久化到`CodeSagePlugin.xml`。

---

## 优化5: Prompt工程 — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `prompt/engine/PromptTemplate.kt` | 模板引擎：变量插值`{{var}}`、条件块`{{#if}}`、循环块`{{#each}}`、TemplateBuilder |
| `prompt/engine/PromptAssembler.kt` | 动态组装器：根据角色/项目上下文/工具列表/能力动态组装系统提示 |
| `prompt/presets/PromptPresets.kt` | 6种预设角色：Assistant, CodeReviewer, Debugger, Architect, Explainer, Refactorer |
| `prompt/version/PromptVersionManager.kt` | 版本管理：版本注册/激活/A-B测试/指标追踪/持久化 |

### 关键设计决策
1. **向后兼容**：AgentConfig.systemPrompt为空时自动使用PromptAssembler动态组装，自定义提示仍可覆盖。
2. **角色驱动**：PromptRole枚举定义6种专业角色，每种角色有专属提示模板和行为准则。

---

## 优化6: 工具增强（Guardrails） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `tools/guardrails/SensitiveActionPolicy.kt` | 敏感策略：删除/写入/命令/移动的权限评估，保护`.git`/`.env`/敏感文件 |
| `tools/guardrails/OutputTruncator.kt` | 输出截断：HEAD/TAIL/MIDDLE/SMART策略，结构化截断，列表截断 |
| `tools/guardrails/ToolGuardrails.kt` | 工具防护栏：preCheck（权限确认）+ postProcess（截断），ConfirmationCallback接口 |
| `agent/tools/ToolExecutor.kt` | 集成Guardrails到工具执行流程 |

### 关键设计决策
1. **分层防护**：preCheck在执行前拦截危险操作，postProcess在执行后截断过长输出。
2. **可配置确认**：通过ConfirmationCallback接口支持UI确认弹窗或自动拒绝。
3. **智能截断**：SMART策略优先按行截断保留完整性，再按字符截断。

---

## 优化7: 智能代码分析（AST感知） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `analysis/PSIAnalyzer.kt` | PSI分析器：基于IntelliJ PSI的符号提取，支持Java/Kotlin通用接口 |
| `analysis/SymbolIndex.kt` | 符号索引：名称索引/文件索引/类型索引，增量更新 |
| `analysis/SemanticSearch.kt` | 语义搜索：精确/模糊/签名/文档/自然语言多策略搜索 |
| `analysis/CodeInsightTools.kt` | 6个代码洞察工具定义：analyze_symbol, find_usages, get_inheritance_chain, semantic_search, get_file_summary, get_project_stats |
| `agent/tools/ToolRegistry.kt` | 注册代码洞察工具到工具列表 |

### 关键设计决策
1. **PSI反射兼容**：使用通用`PsiNamedElement`接口和反射访问Java特定API，兼容不同语言插件。
2. **懒加载索引**：SymbolIndex首次访问时自动构建，避免启动时阻塞。

---

## 优化8: 对话历史管理（持久化/导出/恢复） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `persistence/ConversationPersistence.kt` | 对话持久化：JSON格式保存/加载/删除/清理会话，内存缓存 |
| `persistence/ConversationExporter.kt` | 导出导入：Markdown/JSON/HTML/TXT四种格式导出，JSON导入 |
| `persistence/SessionRestore.kt` | 会话恢复：启动时恢复最近会话，自动保存定时器（30秒间隔） |
| `agent/core/AgentCore.kt` | 集成：自动保存、会话导出接口、恢复接口 |

### 关键设计决策
1. **自动保存**：后台协程每30秒自动保存当前会话，避免数据丢失。
2. **多格式导出**：支持Markdown（可读）、JSON（机器）、HTML（分享）、TXT（简单）。

---

## 优化9: 性能优化（缓存/并发/预热） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `perf/ResponseCache.kt` | 响应缓存：基于请求哈希的TTL缓存，命中统计，模型级失效 |
| `perf/ConcurrentRequestLimiter.kt` | 并发限制：全局+按提供商双层信号量，队列上限，超时控制 |
| `perf/ConnectionWarmup.kt` | 连接预热：IDE启动时预热LLM连接、预加载工具Schema、符号索引、网络DNS |
| `agent/core/AgentCore.kt` | 集成ResponseCache、Metrics、自动保存协程作用域 |

### 关键设计决策
1. **智能缓存**：不缓存流式请求和错误响应，基于SHA-256哈希键。
2. **双层限流**：全局最大并发 + 按提供商独立限流，防止单一提供商拥塞。

---

## 优化10: 可观测性（日志/指标/追踪） — ✅ 已完成

**完成时间**: 2026-05-24

### 已交付组件

| 文件 | 说明 |
|------|------|
| `observability/StructuredLogger.kt` | 结构化日志：JSON/NDJSON格式，组件/事件/追踪ID维度，自动刷新 |
| `observability/MetricsCollector.kt` | 指标收集：Counter/Timer/Gauge三类指标，快照导出 |
| `observability/ExecutionTracer.kt` | 执行追踪：分布式追踪风格，Trace/Span/Event层级，TraceTree可视化 |
| `agent/core/AgentCore.kt` | 集成：全生命周期追踪（create_session/chat_with_tools），指标收集 |

### 关键设计决策
1. **双轨日志**：结构化日志写入NDJSON文件便于分析，同时通过SLF4J输出到IDEA日志。
2. **低开销追踪**：ExecutionTracer使用ConcurrentHashMap和CopyOnWriteArrayList保证线程安全且低竞争。

---

## 优化11: git_worktree 与子 Agent 隔离（6.6.3） — ✅ 已完成

**完成时间**: 2026-06-13

### 已交付组件

| 文件 | 说明 |
|------|------|
| `agent/core/ProjectProxy.kt` | 轻量级 Project 代理：仅覆盖 `getBasePath`，其余方法委托给原 Project，避免动态代理兼容性问题 |
| `agent/core/WorktreeIsolation.kt` | worktree 生命周期管理：创建分支/worktree、基于 base commit 收集 diff、清理 worktree 与分支 |
| `agent/core/SubAgentExecutor.kt` | `spawn()` / `spawnParallel()` / `SubTaskConfig` 新增 `isolated_worktree`；worktree 创建、子 Agent 在 worktree 中运行、diff 收集、finally 清理 |
| `agent/core/EnhancedAgentLoop.kt` | `delegate_task` 解析 `isolated_worktree` 并透传给 `SubAgentExecutor` |
| `agent/core/SubAgentResultFormatter.kt` | 结构化结果新增 `worktree_diff` / `worktree_changes` |
| `agent/tools/ToolRegistry.kt` | `delegate_task` schema 新增 `isolated_worktree` 参数 |
| `test/agent/core/WorktreeIsolationTest.kt` | worktree 创建/diff/清理/分支保留策略测试 |
| `test/agent/core/SubAgentResultFormatterTest.kt` | worktree 字段 JSON 输出测试 |
| `test/agent/core/EnhancedAgentLoopDelegateTaskTest.kt` | `isolated_worktree` 参数透传测试 |
| `test/agent/core/SubAgentExecutorTest.kt` | `isolated_worktree=true` 但无 project 时错误处理测试 |

### 关键设计决策
1. **Project 代理而非替换**：用 Kotlin 接口委托实现 `ProjectProxy`，只覆盖 `getBasePath()`，子 Agent 的文件/命令工具无需修改即可在 worktree 中运行。
2. **worktree 外置**：worktree 创建在 `<repoRoot>/../.codesage-worktrees/<repoName>/sub-<sessionId>`，避免嵌套在主 worktree 内部导致 git 限制与未跟踪文件污染。
3. **base commit 快照**：创建 worktree 时记录 HEAD commit，diff 以此为基准，准确捕获子 Agent 在 worktree 中的所有变更。
4. **结构化 + 原始 diff 双输出**：父 Agent 可直接消费 `worktree_changes` 中的文件/hunk/行级结构，也可查看 `worktree_diff` 原始文本。
5. **默认自动清理**：子 Agent 完成后在 `finally` 中移除 worktree 并删除临时分支，避免磁盘泄漏。

### 测试状态
```
./gradlew check 通过 ✅
```

---

## 总结

| 阶段 | 目标 | 核心交付 |
|------|------|---------|
| **Phase 1** | Agent 不死 | EnhancedAgentLoop + AgentErrorRecovery + IterationBudget |
| **Phase 2** | Agent 记得 | MemoryProvider + ContextCompressor + TokenEstimator |
| **Phase 3** | Agent 成长 | DynamicSkillRegistry + SkillCurator + SkillProvenance |
| **Phase 4** | Agent 分工 | SubAgentExecutor + delegate_task |
| **优化1** | 进度可视 | SubAgentProgressPanel + 流式事件 |
| **优化2** | 看板管理 | _（2026-06 撤回）_ |
| **优化3** | 工具扩充 | 6 → 24 个 IDE 工具 |
| **优化4** | MCP生态 | MCPServerManager + 配置持久化集成 |
| **优化5** | Prompt工程 | PromptTemplate + PromptAssembler + 6种角色 |
| **优化6** | Guardrails | SensitiveActionPolicy + OutputTruncator + ToolGuardrails |
| **优化7** | AST分析 | PSIAnalyzer + SymbolIndex + SemanticSearch |
| **优化8** | 对话持久化 | ConversationPersistence + Exporter + SessionRestore |
| **优化9** | 性能优化 | ResponseCache + ConcurrentLimiter + ConnectionWarmup |
| **优化10** | 可观测性 | StructuredLogger + MetricsCollector + ExecutionTracer |
| **优化11** | Worktree 隔离 | ProjectProxy + WorktreeIsolation + delegate_task 集成 |

**总测试数：全部通过（`./gradlew check`）。**

---

## 编译命令

```bash
# 编译
./gradlew compileKotlin compileTestKotlin --no-daemon

# 测试
./gradlew test --no-daemon
```
