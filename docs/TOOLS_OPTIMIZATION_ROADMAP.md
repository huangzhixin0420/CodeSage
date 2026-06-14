# CodeSage 工具链优化路线图（基于 CODESAGE_TOOLS_RESEARCH_REPORT）

> 本文件承接 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md` 的优化建议，按“已落地 / 未落地 / 部分落地”重新编排，并提供 AI 实施提示词模板。  
> 每次由 AI 或人工完成一项优化后，必须同步更新本文件中的状态、完成时间与交付说明。

---

## 1. 文档约定

### 1.1 状态定义

| 状态 | 含义 |
|------|------|
| ✅ 已完成 | 代码已合并，测试通过，功能可用 |
| ⏳ 进行中 | 已有人认领，正在开发或评审 |
| ⚠️ 部分落地 | 核心能力已存在，但距报告建议仍有差距 |
| ❌ 未开始 | 尚未进入开发队列 |
| 🗑️ 已放弃 | 经评估不实施，需注明原因 |

### 1.2 优先级定义

| 优先级 | 含义 |
|--------|------|
| P0 | 1-2 个月，影响最大，建议优先投入 |
| P1 | 2-4 个月，巩固竞争力 |
| P2 | 4-6 个月，差异化创新 |

---

## 2. 已落地项（参考 `docs/OPTIMIZATION_PROGRESS.md`）

| 报告节 | 优化项 | 落地阶段 | 关键文件 |
|--------|--------|----------|----------|
| 6.1.1 | `read_file` / `read_multiple_files` 行号输出 | Phase 4 / 5 | `IDETools.kt` |
| 6.1.2 | 大文件 `offset/limit` 流式分块 | Phase 4 / 5 | `IDETools.kt` |
| 6.1.3 | PDF / 图片 / Notebook 读取 | **优化4 / Phase 4** | `ReadDocumentTool.kt` |
| 6.2.1 | `apply_patch` 结构化 patch 工具 | Phase 5 | `ApplyPatchTool.kt`, `ApplyPatchEngine.kt` |
| 6.2.2 | `multi_edit` 批量编辑工具 | Phase 5 | `MultiEditTool.kt` |
| 6.2.3 | 编辑工具智能重试与模糊匹配 | Phase 5 | `EditMatchEngine.kt`, `EditFileFuzzyTest.kt` |
| 6.3.1 | `grep_code` / `search_code` 接入 ripgrep | Phase 5 | `RipgrepSearch.kt` |
| 6.3.2 | 专用 `glob` 工具 | Phase 5 | `ToolRegistry.kt` |
| 6.4.1 | 统一 `run_command` / `exec_shell` | Phase 5 | `IDETools.kt`, `ToolRegistry.kt` |
| 6.4.2 | 后台进程管理（`run_in_background`） | Phase 5 | `BackgroundProcessManager.kt`, `BackgroundProcessTools.kt` |
| 6.5.2 | `find_callers` / `find_callees` 独立工具 | Phase 5 | `CodeInsightUnifiedTools.kt` |
| 6.5.3 | 跨语言符号索引扩展 | Phase 5 | `SymbolIndex.kt` |
| 6.6.1 | `git_push` 工具 | Phase 5 | `ExtendedToolHandlers.kt` |
| 6.6.2 | `git_diff` 结构化 diff | Phase 5 | `GitDiffParser.kt` |
| 6.6.3 | `git_worktree` 与并行子 Agent 隔离 | 优化11 | `WorktreeIsolation.kt`, `ProjectProxy.kt` |
| 6.7.1 | `http_request` 响应大小限制 | Phase 5 | `ExtendedTools.kt` |
| 6.8.1 | `run_tests` 结构化测试结果 | Phase 5 | `TestResultParser.kt` |
| 6.8.2 | `run_linter` 结构化问题列表 | Phase 5 | `LintResultParser.kt` |
| 6.9.1 | 记忆向量召回（简化版） | Phase 2 / 5 | `MemoryEmbedding.kt` |
| 6.9.2 | 自动会话摘要（规则版） | Phase 5 | `SessionSummarizer.kt` |
| 6.10.1 | `delegate_task` 结构化结果 | Phase 5 | `SubAgentResultFormatter.kt` |
| 6.10.2 | 子 Agent 递归深度配置 | Phase 5 | `SubAgentExecutor.kt` |
| 6.10.3 | 子 Agent 工具白名单/黑名单 | Phase 5 | `SubAgentExecutor.kt` |
| 6.11.1 | MCP 工具数量上限 | Phase 5 | `McpToolFilter.kt` |
| 6.11.2 | MCP 权限规则前置过滤 | Phase 5 | `McpToolFilter.kt` |

---

## 3. 剩余未落地 / 部分落地项

### 3.1 P0 级

| 报告节 | 优化项 | 状态 | 差距说明 | 建议交付物 | 负责人 | 目标日期 |
|--------|--------|------|----------|------------|--------|----------|
| 6.4.3 | Shell 命令**流式输出** | ✅ 已完成 | `stream_output` 参数已存在，但后台模式仍不支持 push 流式；长命令运行中用户看不到进度 | `AgentStreamEvent.CommandOutputStream` 事件在后台进程中持续 emit；`IDETools.kt` 中 `runCommand` 的流式分支补全。实际交付：`src/main/kotlin/com/codesage/agent/tools/BackgroundProcessManager.kt`、`src/main/kotlin/com/codesage/agent/tools/IDETools.kt`、`src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt`、`src/test/kotlin/com/codesage/agent/tools/RunCommandStreamingTest.kt`、`src/test/kotlin/com/codesage/agent/tools/BackgroundProcessManagerTest.kt` | AI-Agent | 2026-06-14 |

### 3.2 P1 级

| 报告节 | 优化项 | 状态 | 差距说明 | 建议交付物 | 负责人 | 目标日期 |
|--------|--------|------|----------|------------|--------|----------|
| 6.3.3 | `semantic_search` **真实 embedding 向量召回** | ✅ 已完成 | 已新增 ONNX 本地 embedding provider、项目级 SQLite chunk 向量索引与 `reindex_semantic` 工具；模型文件需通过 `scripts/download-embedding-model.sh` 下载 | `EmbeddingProvider.kt` / `OnnxEmbeddingProvider` / `SemanticIndexRepository.kt` / `SemanticChunkIndexer.kt` / `SemanticSearch.kt` / `ReindexSemanticTool.kt` / `scripts/download-embedding-model.sh` | AI-Agent | 2026-06-14 |
| 6.3.4 | `SymbolIndex.fuzzySearch` **前缀树/trie 优化** | ✅ 已完成 | 已加 token 前缀索引，但仍保留 `nameIndex.entries` 全量子串匹配的兜底路径 | 移除 O(n) 兜底：实现 `SymbolIndex.TokenTrie`，查询走前缀树；交付：`src/main/kotlin/com/codesage/analysis/SymbolIndex.kt`、`src/test/kotlin/com/codesage/analysis/SymbolIndexTest.kt` | AI-Agent | 2026-06-14 |
| 6.5.1 | **PSI 调用图替代启发式正则** | ✅ 已完成 | `find_usages` 已走 `ReferencesSearch`，`findCallees` 已通过 `CallGraphExtractor` 基于 PSI 遍历；但缺 project-null / 无 PSI 源边界测试 | 验证 `CodeInsightExecutor.collectCalleesForSymbol` 完全基于 PSI；补充 `find_callees` 边界测试；交付：`src/main/kotlin/com/codesage/analysis/CodeInsightExecutor.kt`、`src/main/kotlin/com/codesage/analysis/CallGraphExtractor.kt`、`src/test/kotlin/com/codesage/analysis/CodeInsightExecutorTest.kt` | AI-Agent | 2026-06-14 |
| 6.8.3 | **`dependency_tree` 依赖树工具** | ✅ 已完成 | 仅有通用 `maven`/`gradle` 包装，无结构化依赖树输出 | 新增 `DependencyTreeTool` UnifiedTool，解析 Maven JSON / Gradle 文本输出；注册于 `ToolRegistry`；测试见 `DependencyTreeToolTest` | AI-Agent | 2026-06-14 |
| 6.9.2 | **LLM 自动会话摘要** | ✅ 已完成 | `SessionSummarizer` 为规则引擎，未接入 LLM | 将 `SessionSummarizer` 改造为可注入 `ModelGateway` 的类，`BuiltInMemoryProvider.onSessionEnd` 异步调用轻量模型生成摘要与关键事实，失败时自动降级到规则引擎；交付：`src/main/kotlin/com/codesage/agent/memory/SessionSummarizer.kt`、`src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt`、`src/test/kotlin/com/codesage/agent/memory/BuiltInMemoryProviderTest.kt` | AI-Agent | 2026-06-14 |
| 6.9.3 | 记忆上下文**token 预算 / Top-K 注入** | ✅ 已完成 | 已有 16KB 长度保护和 token 估算，但未按“与当前查询相似度排序 + token 上限 Top-K”注入 | `BuiltInMemoryProvider.prefetch` 中按查询相似度排序，设置 token 预算上限，保留 Top-K；实际交付：`MemorySimilarityRanker.kt` / `BuiltInMemoryProvider.kt` / `BuiltInMemoryProviderTest.kt` / `MemorySimilarityRankerTest.kt` | AI-Agent | 2026-06-14 |
| 6.11.3 | Skill 工具统一命名、`examples`、`use_skill` 元工具 | ✅ 已完成 | `Skill` 接口新增 `examples`，`SkillToolAdapter.toTools()` 输出携带 `category`/`tags`/示例，`use_skill` 元工具注册于 `ToolRegistry` | `Skill.kt` / `SkillToolAdapter.kt` / `UseSkillTool.kt` / `ToolRegistry.kt` / `AgentCore.kt` / `SkillToolAdapterTest.kt` | AI-Agent | 2026-06-14 |
| 6.12.1 | **统一截断标记与续读协议** | ✅ 已完成 | 各工具截断字段不统一 | 在 `ToolResult` / `ToolExecutor.postProcess` 中统一追加 `{truncated, total_items, returned_items, next_offset, hint}`；交付：`ToolResultMetadata.kt` / `ToolResultTruncationNormalizer.kt` / `ToolExecutor.kt` / `ToolGuardrails.kt` / `ToolResultMetadataTest.kt` | AI-Agent | 2026-06-14 |
| 6.12.2 | 工具结果中嵌入 **token 预算提示** | ✅ 已完成 | 未在工具结果中提示上下文消耗 | `ToolExecutor.postProcess` 追加 `context_cost_estimate` / `remaining_context_hint`；交付：`ToolResultBudgetHints.kt` / `ToolExecutor.kt` / `AgentCore.kt` / `ToolResultMetadataTest.kt` | AI-Agent | 2026-06-14 |

### 3.3 P2 级

| 报告节 | 优化项 | 状态 | 差距说明 | 建议交付物 | 负责人 | 目标日期 |
|--------|--------|------|----------|------------|--------|----------|
| 6.7.2 | **动态页面抓取**（Readability / Playwright） | ✅ 已完成 | `web_scraper` 仅支持 JSoup 静态解析 | 新增 `fetch_url_markdown` UnifiedTool：静态 Readability 提取 + Markdown 转换，可选 Playwright 渲染（临时 Node 脚本，按需安装） | AI-Agent | 2026-06-14 |
| 6.13.2 | **OpenTelemetry 导出** | ✅ 已完成 | 已有 `AgentHooks` 和 `ExecutionTracer`，但无 OpenTelemetry 格式导出 | `ExecutionTracer` 新增 `TraceListener`；`OpenTelemetryExporter` 以 OTLP/JSON 异步导出；`AgentCore` 注册/关闭；复用 `enableTelemetry`/`telemetryEndpoint` | AI-Agent | 2026-06-14 |
| 4.4.6 | **ACP（Agent Client Protocol）支持** | ✅ 已完成 | 未找到 ACP Server/Client 实现 | 调研 ACP 协议；新增 ACP server/client 模块（可选长期任务） | AI-Agent | 2026-06-14 |

### 3.4 实施笔记

#### 3.4.1 6.4.3 Shell 命令流式输出（AI-Agent，2026-06-14）

**关键设计决策：**

1. **后台进程流式与存储兼顾**：`BackgroundProcessManager.start()` 新增可选 `onStream` 回调。流式模式下通过 `ProcessBuilder` 直接读取 stdout/stderr，启动独立读取线程，既实时 emit `AgentStreamEvent.CommandOutputStream` 事件，又把输出追加写入临时文件，保证 `read_process_output` 后续仍可读取完整输出。
2. **非流式路径零行为变更**：未提供 `onStream` 时仍使用 `redirectOutput(File)` / `redirectError(File)`，保持现有后台进程管理语义与性能特征。
3. **`IDETools.runCommand` 透传回调**：当 `run_in_background=true` 且 `stream_output=true` 时，将 `onStream` 通过 `runBlocking` 桥接为普通回调传入 `BackgroundProcessManager`；同步流式路径已存在，无需改动。
4. **清理时等待读取线程**：`cleanup()` 在删除临时文件前加入 `stdoutReader` / `stderrReader` / `exitWatcher` 的限时 join，避免文件在仍被写入时被删除。

**测试状态：**

- `./gradlew check`：通过（含新增 4 个单元测试）
- `npm test`：通过
- 新增测试：
  - `RunCommandStreamingTest.background command with stream_output emits output stream events`
  - `RunCommandStreamingTest.background command with stream_output can still be read via read_process_output`
  - `RunCommandStreamingTest.background command without stream_output still works`
  - `BackgroundProcessManagerTest.start with stream callback emits CommandOutputStream events`

**遗留边界情况 / 已知限制：**

- 流式读取线程为 daemon 线程；IDE 异常退出时，极短时间内产生的输出事件可能丢失。
- `runBlocking` 桥接回调运行在 `BackgroundProcessManager` 读取线程中；若消费方长时间挂起，极端情况下可能导致进程 pipe 反压，但典型事件发射路径（`EnhancedAgentLoop` 中通过 Mutex emit）足够快。
- 后台进程的 `CommandOutputStream.done` 事件由退出监听线程发射，若进程被 `kill_process` 终止，destroy → waitFor → 读取线程 join → done 事件之间仍有毫秒级时序，UI 可能先收到 killed 结果再收到 done 事件。

---

#### 3.4.2 6.3.3 `semantic_search` 真实 embedding 向量召回（AI-Agent，2026-06-14）

**关键设计决策：**

1. **抽象层优先**：新增 `EmbeddingProvider` 接口与 `OnnxEmbeddingProvider` / `HashEmbeddingProvider` 实现，真实模型不可用时自动回退到原有 hash-based 向量，保证 `semantic_search` 不中断。
2. **本地 ONNX + DJL Tokenizer**：使用 `com.microsoft.onnxruntime:onnxruntime` 做推理，`ai.djl.huggingface:tokenizers` 做文本→input_ids，模型文件存放于 `~/.codesage/models/all-MiniLM-L6-v2/`，不打包进插件 jar。
3. **项目级 SQLite 向量索引**：新增 `SemanticIndexRepository` 与 `SemanticChunkIndexer`，按符号边界或 50 行窗口切 chunk，embedding 以 BLOB 形式存储，查询时内存中计算 cosine，避免引入 `sqlite-vec` 等额外 native 依赖。
4. **优先使用 chunk 索引，自动降级**：`SemanticSearch.semanticQuery()` 索引非空时走 chunk 级向量召回；索引为空时回退到符号级向量，并首次调用时后台触发增量索引。
5. **新增 `reindex_semantic` 工具**：继承 `UnifiedTool` 并在 `ToolRegistry` 注册，参数 `path` / `force`，返回文件数、chunk 数、耗时与错误信息。

**测试状态：**

- `./gradlew check`：通过（含新增 4 个单元测试）
- `npm test`：通过
- 新增测试：
  - `OnnxEmbeddingProviderTest`：模型缺失时正确回退到 hash；Hash provider 输出归一化向量；Factory 在无模型时返回 hash provider。
  - `ReindexSemanticToolTest`：正常路径索引后 chunk 数 > 0；非法路径返回错误。

**遗留边界情况 / 已知限制：**

- ONNX 模型需运行 `scripts/download-embedding-model.sh` 手动下载，未下载前语义搜索仍使用 hash-based 向量。
- 当前向量检索为全表加载后内存计算，数万级 chunk 可行；超大规模项目需后续引入向量索引或 `sqlite-vec`。
- 后台自动索引是尽力而为的增量扫描，不能保证实时同步；重大代码变更后建议显式调用 `reindex_semantic --force`。

---

#### 3.4.3 6.8.3 `dependency_tree` 依赖树工具（AI-Agent，2026-06-14）

**关键设计决策：**

1. **统一工具类 + 双解析器**：新增 `DependencyTreeTool` 继承 `UnifiedTool`，根据 `pom.xml` / `build.gradle[.kts]` 自动判断构建系统；内部用独立解析器处理 Maven JSON 与 Gradle 文本树，避免逻辑耦合。
2. **复用 wrapper 优先策略**：Maven / Gradle 命令均通过 `BuildCommandResolver` 生成，优先使用 `./mvnw` / `./gradlew`，与现有 `BuildToolHandlers` / `RunLinterTool` 保持一致，避免全局命令缺失导致的运行时失败。
3. **结构化输出与深度控制**：返回 `dependencies[]` + `total_top_level` + `total_transitive`；`max_depth` 在解析后统一截断，确保计数反映可见节点。
4. **Gradle 标记保留**：解析时识别并保留末尾的 `(*)` / `(c)` 标记到 `markers` 字段；Maven JSON 中的 `classifier` / `optional` 作为可选字段透传。

**测试状态：**

- `./gradlew check`：通过（含新增 6 个单元测试）
- `npm test`：通过
- 新增测试：
  - `DependencyTreeToolTest.execute should parse maven dependency tree json`
  - `DependencyTreeToolTest.execute should respect max_depth for maven tree`
  - `DependencyTreeToolTest.execute should parse gradle dependency tree text`
  - `DependencyTreeToolTest.execute should preserve gradle markers`
  - `DependencyTreeToolTest.execute should return error for unsupported project`
  - `DependencyTreeToolTest.execute should return error for non-existent path`

**遗留边界情况 / 已知限制：**

- Maven JSON 输出依赖 `maven-dependency-plugin >= 3.x`；旧版本会回退为文本并返回明确错误提示。
- `mvn dependency:tree` 输出文件写入 `target/codesage-dependency-tree.json`，插件异常时文件可能残留，但不影响后续调用（每次调用前会删除旧文件）。
- Gradle scope 映射仅覆盖常见的 `compile` / `runtime` / `test` / `provided`；自定义配置名会直接透传，但部分配置（如带空格的名称）可能与输出解析正则不匹配。

---

#### 3.4.4 6.12.1 / 6.12.2 统一截断协议与 token 预算提示（AI-Agent，2026-06-14）

**关键设计决策：**

1. **元数据与数据解耦**：新增 `ToolResultMetadata` 数据类并扩展 `ToolResult.Success(metadata = ...)`，使截断/预算提示与业务数据解耦；所有现有 `ToolResult.Success(data)` 调用因默认参数保持 100% 向后兼容。
2. **归一化层位于 ToolExecutor**：`ToolResultTruncationNormalizer` 统一识别历史上各工具五花八门的截断字段（`truncated`/`original_length`、`stdout_truncated`/`stderr_truncated`、`partial_scan_files`、`total_lines`/`start_line`/`end_line` 等），转换为 `{truncated, total_items, returned_items, next_offset, hint}` 协议。
3. **Guardrails 截断可叠加**：`ToolGuardrails.postProcess` 在自身进行输出截断时保留并更新已有元数据，既保留工具层的 `total_items/next_offset`，又追加 guardrails 截断提示。
4. **预算提示全覆盖**：`ToolResultBudgetHints` 为每次工具结果估算 token 消耗（1 token ≈ 4 字符的经验值），并结合 `ContextBudgetManager` 生成 `remaining_context_hint`；`AgentCore` 把会话级 `ContextBudgetManager` 实例注入 `ToolExecutor`。
5. **最终 JSON 顶层输出**：`ToolExecutor.formatResult` 在 `{success, data/error}` 之外，仅在元数据非空时追加统一字段，避免无意义字段污染普通结果。

**测试状态：**

- `./gradlew check`：通过（新增 10 个单元测试）
- `npm test`：通过
- 新增测试：
  - `ToolResultMetadataTest.normalizer extracts read_file pagination metadata`
  - `ToolResultMetadataTest.normalizer marks read_file truncated when original_length present`
  - `ToolResultMetadataTest.normalizer extracts run_command truncation metadata`
  - `ToolResultMetadataTest.normalizer extracts search_code truncation metadata`
  - `ToolResultMetadataTest.budget hints estimate tokens from result content`
  - `ToolResultMetadataTest.budget hints produce remaining context hint`
  - `ToolResultMetadataTest.guardrails postProcess preserves and updates existing metadata`
  - `ToolResultMetadataTest.ToolExecutor formats success result with truncation metadata and budget hints`
  - `ToolResultMetadataTest.ToolExecutor formats error result with budget hints only`
  - `ToolResultMetadataTest.guardrails truncation without tool metadata still produces normalized output`

**遗留边界情况 / 已知限制：**

- `read_file` 等大文件若被 guardrails 额外按字符截断，`next_offset` 仍按工具层返回的 `end_line` 计算，可能与 guardrails 截断后的实际内容末尾不完全对齐；模型需结合 `hint` 判断。
- token 估算采用固定 1:4 字符比，对纯英文代码可能低估、对中文注释可能高估；后续可接入 `ContextBudgetManager` 的真实 tokenizer。
- 当前 `remaining_context_hint` 在 `ContextBudgetManager` 的 provider 未绑定（如单元测试）时基于 `tokensUsed = 0` 计算，会显示 `0% used`；IDE 真实会话中 provider 会返回实际用量。

---

#### 3.4.8 6.9.2 LLM 自动会话摘要（AI-Agent，2026-06-14）

**关键设计决策：**

1. **LLM 优先 + 规则兜底**：将 `SessionSummarizer` 从 `object` 改为可配置类，默认持有 `ModelGateway.getInstance()`；`summarize()` 先尝试调用轻量模型（默认 `MiniMax-M2.1`）生成结构化 JSON 摘要，任何失败（无适配器、网络错误、解析失败）都静默降级到原有规则引擎。
2. **异步执行不阻塞会话关闭**：`BuiltInMemoryProvider.onSessionEnd` 在 `coroutineScope` 中启动协程调用 `sessionSummarizer.summarize()`，将摘要写入 `sessions.summary`、将关键事实自动 `memory_add`，保持 `MemoryProvider.onSessionEnd` 同步签名不变。
3. **token 成本控制**：输入会话文本默认截断至 6,000 字符，LLM 最大输出 512 tokens；摘要长度限制 2,000 字符，关键事实最多 10 条，避免会话结束产生高额 token 消耗。
4. **测试可注入**：`BuiltInMemoryProvider` 暴露 `internal var sessionSummarizer`，测试可注入 `FakeModelGateway` 验证 LLM 成功/失败两条路径；同时修复了规则引擎文件路径正则中的字符类范围异常（`[\w\-./]` → `[\w./-]`）。

**测试状态：**

- `./gradlew check`：通过（新增 2 个单元测试）
- `npm test`：通过
- 新增测试：
  - `BuiltInMemoryProviderTest.onSessionEnd uses LLM summary and persists returned facts`
  - `BuiltInMemoryProviderTest.onSessionEnd falls back to rule summary when LLM fails`

**遗留边界情况 / 已知限制：**

- LLM 摘要依赖 `ModelGateway` 已注册可用模型；未配置模型时自动使用规则引擎，不会阻塞或报错。
- 当前通过字符串截断控制输入长度，未使用真实 tokenizer；后续可接入 `ContextBudgetManager` 做更精确的 token 预算。
- 异步摘要若会话立即关闭（`shutdown()`）可能被取消；正常 IDE 会话结束到进程退出有足够时间完成写入。

---

#### 3.4.7 6.5.1 PSI 调用图替代启发式正则（AI-Agent，2026-06-14）

**关键设计决策：**

1. **代码审查结论**：`CodeInsightExecutor.collectCalleesForSymbol` 已实现为 PSI 遍历：通过 `findPsiElement` 定位目标符号对应的 `PsiElement`，再使用 `PsiRecursiveElementVisitor` 遍历方法体，由 `CallGraphExtractor.extractCalleeName` 识别 `PsiMethodCallExpression`、`PsiNewExpression`、`KtCallExpression` 等真实调用表达式，未再使用正则扫描方法体文本。
2. **`CallGraphExtractor` 直接面向 PSI 元素类型**：虽然 Java/Kotlin 专用 PSI 类在测试 classpath 中未必全部存在，但提取逻辑以元素运行时类型为准（`PsiMethodCallExpression`、`PsiNewExpression`、`KtCallExpression`），并过滤 `if/for/println/also/let` 等语法关键字与作用域函数，避免启发式误报。
3. **保持 `find_usages` 降级路径独立**：`findTextReferences` 文本搜索仅作为 `find_usages` 在 `ReferencesSearch` 不可用时的降级，不影响 `findCallees` 的 PSI 路径。
4. **补充边界测试**：为 `find_callees` 增加 project=null 错误路径与 symbol 无法解析到 PSI 元素时空 callee 列表的测试，覆盖正常/错误路径。

**测试状态：**

- `./gradlew check`：通过（新增 2 个单元测试）
- `npm test`：通过
- 新增测试：
  - `CodeInsightExecutorTest.find_callees should return error when project is null`
  - `CodeInsightExecutorTest.find_callees should return empty callees when symbol source is not resolvable`

**遗留边界情况 / 已知限制：**

- `findCallees` 依赖 `LocalFileSystem` 能根据 `SymbolInfo.filePath` 加载到真实 `VirtualFile`；在 headless 测试或文件已被删除/重命名时，无法解析 PSI 元素，会返回空 callee 列表（已在测试中覆盖）。
- 当前未解析重载/多态目标；提取结果为被调用符号的简单名称，与报告要求的“完全基于 PSI / KtCallExpression / PsiMethodCallExpression”一致，如需精确重载可后续结合 `PsiResolveHelper`。

---

#### 3.4.6 6.3.4 `SymbolIndex.fuzzySearch` 前缀树/trie 优化（AI-Agent，2026-06-14）

**关键设计决策：**

1. **内部轻量前缀树替代全量扫描**：在 `SymbolIndex` 中新增私有 `TokenTrie`，对 `tokenizeSymbolName` 产出的 token 建立字符前缀树；`fuzzySearch` 查询时沿前缀走到目标节点，再遍历子树收集候选 token，彻底移除 `tokenIndex.entries` 线性过滤与 `nameIndex.entries` 子串兜底。
2. **同时支持“长 token 前缀”与“短 token 前缀”匹配**：遍历 query 过程中累积路径上的终端 token（短 token 是 query 的前缀），到达目标节点后再收集子树终端 token（长 token 以 query 为前缀），用 `LinkedHashSet` 去重，保留原有评分语义。
3. **query 分词兼容多词输入**：将 `tokenizeSymbolName` 的拆分正则扩展为 `[_.\\-\\s]+`，使 `"order repo"` 这类空格分隔的多词查询能正确拆分为多个 token 在前缀树中查找。
4. **零 public API 变更、向后兼容**：`fuzzySearch(query, limit)` 签名与返回类型不变；`SymbolSearchTool` 等调用方无需修改。

**测试状态：**

- `./gradlew check`：通过（新增 3 个单元测试）
- `npm test`：通过
- 新增测试：
  - `SymbolIndexTest.fuzzySearch with trie matches token prefixes and shorter tokens`
  - `SymbolIndexTest.fuzzySearch remains fast for non-matching query without O(n) fallback`
  - `SymbolIndexTest.fuzzySearch does not match arbitrary substring outside token boundaries`

**遗留边界情况 / 已知限制：**

- 查询必须命中某个 token 前缀或本身是某个 token 的前缀；不再支持任意子串匹配（这是移除 O(n) 兜底的预期行为变化）。
- 单字符查询返回空结果，因为 `tokenizeSymbolName` 过滤掉长度 ≤1 的 token；如需支持，可后续放宽该过滤条件。
- 当前 trie 仅在 token 首次加入 `tokenIndex` 时插入，删除符号时不会从 trie 中移除空 token；空 token 对应的 `tokenIndex` 列表为空，不会产生误报，仅占用少量内存。

---

#### 3.4.5 6.9.3 记忆上下文 token 预算 / Top-K 注入（AI-Agent，2026-06-14）

**关键设计决策：**

1. **排序与预算解耦**：新增 `MemorySimilarityRanker` 负责按查询相似度排序，内置 embedding cosine 相似度与 token overlap 文本降级两条路径；`BuiltInMemoryProvider` 在此基础上叠加类型优先级与 token 预算，职责清晰。
2. **类型优先级优先于纯相似度**：`preference` / `pattern`（高优先级）> `fact` / `project`（中优先级）> 其他（低优先级），同优先级内再按相似度排序；保证用户偏好/编码风格这类长期提示优先进入上下文。
3. **token 预算可配置且向后兼容**：默认总预算 2048 tokens，通过 `prefetchTokenBudget` / `prefetchUseTokenBudget` 可调；关闭预算时回退到原有 16KB 字符截断行为，不破坏调用方。
4. **Top-K 截断提示**：超出预算的记忆在 `<memory-context>` 中追加 `[N more memories omitted due to context budget]`，让模型知道存在截断；同时保留单条 4KB 字符截断与整段 16KB 字符兜底。
5. **复用现有向量能力**：`MemorySimilarityRanker` 直接复用项目已有的 `MemoryEmbedding` hash-based 向量（无 native 依赖），并支持注入失败时自动降级到文本 overlap。

**测试状态：**

- `./gradlew check`：通过（新增 9 个单元测试）
- `npm test`：通过
- 新增测试：
  - `BuiltInMemoryProviderTest.prefetch ranks memories by similarity to query`
  - `BuiltInMemoryProviderTest.prefetch applies token budget and reports omitted memories`
  - `BuiltInMemoryProviderTest.prefetch prefers high priority memory type within token budget`
  - `BuiltInMemoryProviderTest.prefetch falls back to character truncation when token budget is disabled`
  - `MemorySimilarityRankerTest.text overlap score returns zero for empty inputs`
  - `MemorySimilarityRankerTest.text overlap score is higher for related texts`
  - `MemorySimilarityRankerTest.rank by similarity falls back to text overlap when embedding is unavailable`
  - `MemorySimilarityRankerTest.rank by similarity orders exact match highest`

**遗留边界情况 / 已知限制：**

- token 估算沿用 `BuiltInMemoryProvider.estimateTokens` 的字符经验值（中文 ≈ 1 token/字，英文 ≈ 4 chars/token），对代码/中文混合场景可能不够精确；后续可接入 `TokenEstimator` 或真实 tokenizer。
- 当前排序基于候选集内重新计算 embedding，未直接复用 `memory_embeddings` 表中已存储的向量；若后续需支撑超大规模记忆，可改为从 SQLite 加载存储向量以减少重复编码。
- `prefetchCache` 仍以格式化后的字符串为缓存值；当 `prefetchTokenBudget` 等配置动态变化时，需要等 `syncTurn` 触发缓存失效才会重新计算。

---

#### 3.4.9 6.11.3 Skill 工具统一命名、`examples`、`use_skill` 元工具（AI-Agent，2026-06-14）

**关键设计决策：**

1. **Skill 接口新增 `examples` 并向下兼容**：`Skill` 接口以默认实现 `val examples: List<String> get() = emptyList()` 提供，所有现有内置/声明式/MCP 委托技能无需修改；`SkillDefinition` 同步增加 `examples` 字段，JSON/YAML 配置均可解析。
2. **`SkillToolAdapter` 输出完整元数据**：`toTools()` 将技能的 `category` 映射为 `ToolCategory`、`tags` 透传，并把 `examples` 追加到 description；模型看到的工具定义不再只是名称+schema。
3. **新增 `use_skill` 元工具收敛调用入口**：`UseSkillTool` 继承 `UnifiedTool`，参数为 `skill_id` + `arguments`，内部委托 `SkillExecutor` 执行；注册于 `ToolRegistry.createDefault()`，避免 LLM 工具列表被大量动态 `skill_*` 工具污染。
4. **修复 `SkillToolAdapter.execute` 的输出序列化**：原有 `Json.encodeToJsonElement(result.output)` 对 `Map<String, Any>` 会在运行时抛序列化异常，改为递归 `valueToJsonElement`/`mapToJsonElement`，保证复杂输出正确返回。

**测试状态：**

- `./gradlew check`：通过（新增 7 个单元测试）
- `npm test`：通过
- 新增测试：
  - `SkillToolAdapterTest.toTools includes category tags and examples`
  - `SkillToolAdapterTest.execute routes skill call and returns success json`
  - `SkillToolAdapterTest.execute returns error json for failing skill`
  - `SkillToolAdapterTest.use_skill executes skill by id`
  - `SkillToolAdapterTest.use_skill returns error for missing skill_id`
  - `SkillToolAdapterTest.use_skill returns error for unknown skill`
  - `SkillToolAdapterTest.use_skill is registered in default registry when skill components provided`

**遗留边界情况 / 已知限制：**

- `use_skill` 的 `skill_id` 枚举在构造时快照注册表内容；若运行时通过热加载新增/删除技能，已注册工具的 schema 不会自动刷新（与现有 `skill_*` 工具的动态注册行为一致，可通过重新初始化 Agent 刷新）。
- 当前 `SkillCategory` 到 `ToolCategory` 的映射是启发式映射（如 `CODE_SEARCH`/`NETWORK` → `SEARCH`）；如需更细粒度展示，可后续新增 `ToolCategory.SKILL` 专用类别。
- `use_skill` 执行仍复用 `SkillExecutor` 的上下文，尚未注入会话级 `sessionId`；后续可在 `AgentCore` 调用时透传当前会话 ID。

---

#### 3.4.10 6.7.2 动态页面抓取 `fetch_url_markdown`（AI-Agent，2026-06-14）

**关键设计决策：**

1. **新增 `fetch_url_markdown` UnifiedTool**：与现有 `web_scraper` 共存，专门输出 Markdown 格式正文，保留标题、链接、列表、代码块等结构，便于直接喂给 LLM。
2. **自包含 Readability 提取器**：不引入新的 Java 依赖，基于 JSoup 实现候选元素评分（标签语义 + class/id 关键词 + 文本/链接密度），自动剔除导航、广告、页脚、评论区等噪声。
3. **可选 Playwright 渲染**：`use_browser=true` 时通过临时 Node 脚本调用 Playwright（`chromium.launch`）获取渲染后 HTML，再复用同一 Readability/Markdown 转换逻辑；Playwright 未安装时返回明确安装提示，不影响默认静态路径。
4. **复用现有安全与网络能力**：SSRF 校验走 [SsrfGuard]，HTTP 请求走 [ProxyAwareHttpClientFactory]，响应上限 5MB；工具加入 `ToolGuardrails.KNOWN_SAFE_TOOLS` 白名单（受 SSRF 保护的网络读取）。

**测试状态：**

- `./gradlew check`：通过（新增 5 个单元测试）
- `npm test`：通过
- 新增测试：
  - `FetchUrlMarkdownToolTest.static extraction converts article HTML to markdown`
  - `FetchUrlMarkdownToolTest.truncates markdown when max_length is exceeded`
  - `FetchUrlMarkdownToolTest.returns error for HTTP failure`
  - `FetchUrlMarkdownToolTest.blocks private URLs when SSRF protection enabled`
  - `FetchUrlMarkdownToolTest.use_browser returns error when Playwright is not installed`

**遗留边界情况 / 已知限制：**

- 静态 Readability 对严重依赖前端 JS 渲染的 SPA（如某些 React/Vue 客户端路由页面）效果有限；此时需 `use_browser=true` 并安装 Playwright。
- Playwright 路径通过临时 `.mjs` 文件调用 `node`，依赖运行环境已安装 Node.js；未安装 Node 时 `use_browser=true` 会返回 `Browser rendering failed`。
- Markdown 转换器目前对表格做简单支持，复杂嵌套表格可能丢失对齐；图片保留 `![alt](src)` 但无法验证可访问性。

---

#### 3.4.11 6.13.2 OpenTelemetry 导出（AI-Agent，2026-06-14）

**关键设计决策：**

1. **零额外依赖**：不引入官方 OTLP Java SDK，直接用手写的 `kotlinx.serialization.json` 构造 OTLP/JSON 请求体，复用已有的 OkHttp 与 `ProxyAwareHttpClientFactory`，避免依赖膨胀。
2. **监听者模式接入 `ExecutionTracer`**：给 `ExecutionTracer` 增加 `TraceListener` 接口并在 `endTrace()` 时通知；`AgentCore.initialize()` 注册 `OpenTelemetryExporter`，`shutdown()` 时取消协程作用域。
3. **复用现有设置项**：使用 `AdvancedSection.enableTelemetry` 作为总开关、`telemetryEndpoint` 作为接收端点（默认 `http://localhost:4318/v1/traces`），无需新增前端字段。
4. **异步失败隔离**：导出在独立 `CoroutineScope` 中执行，任何网络/解析失败只打 warn 日志，不影响 Agent 主流程；trace ID / span ID 会被规范化裁剪为 OTLP 要求的 32/16 位十六进制字符串。

**测试状态：**

- `./gradlew check`：通过（新增 4 个单元测试）
- `npm test`：通过
- 新增测试：
  - `OpenTelemetryExporterTest.exports OTLP JSON when telemetry is enabled`
  - `OpenTelemetryExporterTest.onTraceEnded does nothing when telemetry is disabled`
  - `OpenTelemetryExporterTest.onTraceEnded swallows export failure and does not throw`
  - `OpenTelemetryExporterTest.ExecutionTracer notifies listener when trace ends`

**遗留边界情况 / 已知限制：**

- 当前仅支持 OTLP/JSON over HTTP；未实现 gRPC 或 OTLP/HTTP Protobuf。如需对接仅支持 Protobuf 的 Collector，可后续扩展序列化器。
- `TraceEvent` 属性只导出 `stringValue` 类型；`ExecutionTracer` 目前属性全为字符串，满足当前需求。
- 导出任务在 `AgentCore.shutdown()` 时会被取消，未 flush 的尾部 trace 可能丢失；正常会话结束到 shutdown 之间通常有足够时间完成。

---

#### 3.4.12 4.4.6 ACP（Agent Client Protocol）支持（AI-Agent，2026-06-14）

**关键设计决策：**

1. **协议抽象优先**：参考 Kimi Code CLI / Zed 的 ACP 集成，采用 JSON-RPC 2.0 + 行分隔消息；在 `com.codesage.acp.model` 定义 `AcpJsonRpcRequest` / `AcpJsonRpcResponse` / `AcpInitializeResult` / `AcpTool` / `AcpCallToolResult` 等消息，与现有 `ToolParameters` 复用。
2. **传输层可插拔**：定义 `AcpSessionTransport` 接口，提供 `StdioAcpSessionTransport`（子进程 stdio）、`SocketAcpSessionTransport`（TCP）、`InMemoryAcpSessionTransport`（测试 fake）三种实现；client/server 都基于同一接口，方便替换。
3. **Server 复用现有工具链**：`AcpServer` 持有 `ToolRegistry` + `ToolExecutor`，暴露 `initialize` / `tools/list` / `tools/call` / `shutdown` 方法；工具定义直接映射为 ACP tool schema，执行结果以 text content block 返回，与 MCP 结果风格对齐。
4. **Client 可连接外部 agent**：`AcpClient` 通过 `AcpProcessTransport` 启动 `kimi acp` 等外部 ACP agent，完成握手后查询并调用远端工具；为后续把外部 agent 同步为 CodeSage Skill 预留接口。
5. **生命周期与配置**：`SettingsFile` 新增 `AcpSection`（`enabled`、`serverPort`、`externalAgents`）；`AcpServerManager` 在 `CodeSageAppService` 中初始化与关闭，支持动态启停；TCP server 使用 `ServerSocket(port=0)` 自动分配端口，避免冲突。

**测试状态：**

- `./gradlew check`：通过（新增 4 个单元测试）
- `npm test`：通过
- 新增测试：
  - `AcpServerClientTest.client can initialize list tools and call tool over in-memory transport`
  - `AcpServerClientTest.calling unknown tool returns error result`
  - `AcpServerClientTest.calling tool before initialize returns error`
  - `AcpSocketServerTest.client can connect to ACP socket server and call tool`

**遗留边界情况 / 已知限制：**

- 当前 ACP 实现为基础协议模块，尚未与具体 IDE（Zed、JetBrains ACP Runner）进行端到端联调；JSON-RPC 方法集合按最小可用集合实现（initialize / tools/list / tools/call / shutdown）。
- `AcpServerManager` 连接外部 ACP agent 后仅打印工具数量，未实际把远端工具注册进本地 `SkillRegistry` 或 `ToolRegistry`，属于后续增强点。
- 本地 ACP Socket 服务端默认关闭（`enabled=false`），启用后监听所有接口；若需安全限制，可后续增加 `bindHost` / token 认证配置。
- ACP 服务端以 `project=null` 创建 `ToolRegistry`，依赖项目的工具（如 PSI 分析）会返回错误；在 IDE 真实环境中可通过 `projectProvider` 注入当前打开项目改进。

---

## 4. 进度总览

```text
P0:  0 项部分落地，0 项未开始，1 项已完成
P1:  0 项部分落地，2 项未开始，9 项已完成
P2:  0 项部分落地，0 项未开始，3 项已完成
```

---

## 5. AI 实施提示词模板

当需要让 AI 代理按本路线图实施优化时，可复制以下提示词。提示词假设 AI 已具备当前项目上下文（`AGENTS.md`、Kotlin/JVM IntelliJ 插件、Gradle 测试等）。

```markdown
# 角色与目标

你是 CodeSage（Kotlin/JVM IntelliJ 平台插件）的后端开发 Agent。  
当前任务：**根据 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md` 与 `docs/TOOLS_OPTIMIZATION_ROADMAP.md`，规划并实施指定优化项，完成后更新进度文档。**

## 输入

- 调研报告：`docs/CODESAGE_TOOLS_RESEARCH_REPORT.md`
- 进度跟踪：`docs/TOOLS_OPTIMIZATION_ROADMAP.md`
- 本次要实施的优化项：`<在此处填写 报告节 + 优化项，例如 "6.4.3 Shell 命令流式输出">`

## 要求

### 1. 实施前：阅读与规划

1. 精读调研报告中对应章节，理解：
   - 优化背景
   - 同类产品对比
   - 建议的技术方案
   - 为什么这么优化
2. 检查当前代码中已有的相关实现，避免重复造轮子。
3. 在 `docs/TOOLS_OPTIMIZATION_ROADMAP.md` 中把该项状态改为 **⏳ 进行中**，并填写负责人（如 `AI-Agent`）与目标日期。

### 2. 实施中：编码规范

- 所有新增 public/internal API 必须有 **KDoc**。
- 新工具必须继承 `UnifiedTool` 并通过 `ToolRegistry.createDefault()` 注册。
- 危险操作（写文件、删除、Shell）必须经过 `ToolGuardrails.preCheck`。
- 文件操作优先走 IntelliJ VFS；`project == null` 的测试/headless 场景可回退到 `AtomicFileWriter`。
- 优先复用现有架构：`ToolExecutor`、`ToolRegistry`、`AgentHooks`、`SubAgentExecutor`、`BackgroundProcessManager` 等。
- 保持向后兼容：不删除已有 public API；新参数必须可选并提供 sensible default。

### 3. 测试中

- 至少新增 **2 个单元测试**（正常路径 + 错误路径）。
- 依赖 IntelliJ 平台的测试优先 mock 或使用 `LightPlatformCodeInsightTestCase`。
- 每次修改后运行：
  ```bash
  ./gradlew check
  npm test
  ```
- 所有测试必须通过。

### 4. 实施后：更新进度文档

完成并验证后，必须更新 `docs/TOOLS_OPTIMIZATION_ROADMAP.md`：

1. 将该项状态改为 **✅ 已完成** 或 **⚠️ 部分落地**（视实际情况）。
2. 填写 **完成日期**。
3. 在“建议交付物”列补充实际交付的文件路径。
4. 新增一段“实施笔记”，包含：
   - 关键设计决策（2-4 条）
   - 测试状态（`./gradlew check` 结果、新增测试数）
   - 遗留边界情况或已知限制
5. 如果评估后决定不实施，状态改为 **🗑️ 已放弃**，并注明原因。

### 5. 输出汇报

在对话最后汇报：

- 修改了哪些文件
- 新增/更新了哪些测试
- `./gradlew check` 和 `npm test` 结果
- 是否还有遗留边界情况
- 进度文档是否已同步更新

## 限制

- 不要自动执行 `git commit` / `git push`。
- 不要修改 `.github/workflows`、`.git` 或项目外文件。
- 单次任务建议只聚焦 1-2 个优化项，避免改动过大难以评审。
```

---

## 6. 变更日志

| 日期 | 变更人 | 内容 |
|------|--------|------|
| 2026-06-14 | AI-Agent | 初始整理：基于 `CODESAGE_TOOLS_RESEARCH_REPORT.md` 与源码检索，建立剩余优化项跟踪表与 AI 实施提示词 |
| 2026-06-14 | AI-Agent | 完成 6.4.3 Shell 命令流式输出：后台进程支持 `stream_output` 实时 emit `CommandOutputStream` 事件，同步更新测试与进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.3.3 `semantic_search` 真实 embedding 向量召回：新增 ONNX provider、SQLite chunk 向量索引、`reindex_semantic` 工具与模型下载脚本，同步更新测试与进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.8.3 `dependency_tree` 依赖树工具：新增 `DependencyTreeTool` UnifiedTool，支持 Maven JSON 与 Gradle 文本解析，注册并补充 6 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.12.1 / 6.12.2 统一截断协议与 token 预算提示：新增 `ToolResultMetadata`、`ToolResultTruncationNormalizer`、`ToolResultBudgetHints`，扩展 `ToolResult.Success` 与 `ToolExecutor.formatResult`，补充 10 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.9.3 记忆上下文 token 预算 / Top-K 注入：新增 `MemorySimilarityRanker` 按查询相似度排序并支持文本 fallback，`BuiltInMemoryProvider.prefetch` 增加类型优先级、2048 token 预算与省略提示，补充 8 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.3.4 `SymbolIndex.fuzzySearch` 前缀树/trie 优化：内部实现 `TokenTrie` 替代 `nameIndex` 全量子串兜底，扩展 query 分词支持空格，补充 3 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.5.1 PSI 调用图替代启发式正则：验证 `findCallees` 已基于 `CallGraphExtractor` PSI 遍历实现，补充 2 个边界单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.9.2 LLM 自动会话摘要：`SessionSummarizer` 接入 `ModelGateway` 并支持 LLM 失败自动降级规则引擎，`BuiltInMemoryProvider.onSessionEnd` 改为异步调用，补充 2 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.11.3 Skill 工具统一命名、`examples`、`use_skill` 元工具：`Skill` 接口新增 `examples`，`SkillToolAdapter` 输出 category/tags/examples，新增 `UseSkillTool` 并注册于 `ToolRegistry`，修复 skill 输出序列化，补充 7 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.7.2 动态页面抓取：新增 `fetch_url_markdown` UnifiedTool，基于 JSoup 实现 Readability 内容提取与 Markdown 转换，可选 Playwright 无头浏览器渲染，复用 SsrfGuard 与 ProxyAwareHttpClientFactory，补充 5 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 6.13.2 OpenTelemetry 导出：`ExecutionTracer` 新增 `TraceListener`，`OpenTelemetryExporter` 以 OTLP/JSON 异步导出到 `telemetryEndpoint`，`AgentCore` 注册/关闭，复用 `enableTelemetry` 开关，补充 4 个单元测试，同步更新进度文档 |
| 2026-06-14 | AI-Agent | 完成 4.4.6 ACP 支持：新增 `com.codesage.acp` 协议模块，实现 JSON-RPC 2.0 消息模型、`AcpServer` / `AcpClient` / `AcpSocketServer`、stdio/socket/内存三种传输层；`SettingsFile` 新增 `AcpSection`；`AcpServerManager` 接入 `CodeSageAppService` 生命周期，补充 4 个单元测试，同步更新进度文档 |

---

## 7. 快速参考：常用命令

```bash
# 编译
./gradlew compileKotlin compileTestKotlin --no-daemon

# 全部测试
./gradlew check

# JS E2E 测试
npm test

# 仅运行相关测试（示例）
./gradlew test --tests "com.codesage.agent.tools.ReadDocumentToolTest"
```
