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
| 6.3.4 | `SymbolIndex.fuzzySearch` **前缀树/trie 优化** | ⚠️ 部分落地 | 已加 token 前缀索引，但仍保留 `nameIndex.entries` 全量子串匹配的兜底路径 | 移除 O(n) 兜底，或改用 `PsiShortNamesCache` / `StubIndex` 平台索引 | 待分配 | - |
| 6.5.1 | **PSI 调用图替代启发式正则** | ⚠️ 部分落地 | `find_usages` 已走 `ReferencesSearch`，但 `findCallees` 仍有 regex 扫描兜底 | `CodeInsightExecutor.findCallees` 完全基于 PSI / `KtCallExpression` / `PsiMethodCallExpression` | 待分配 | - |
| 6.8.3 | **`dependency_tree` 依赖树工具** | ✅ 已完成 | 仅有通用 `maven`/`gradle` 包装，无结构化依赖树输出 | 新增 `DependencyTreeTool` UnifiedTool，解析 Maven JSON / Gradle 文本输出；注册于 `ToolRegistry`；测试见 `DependencyTreeToolTest` | AI-Agent | 2026-06-14 |
| 6.9.2 | **LLM 自动会话摘要** | ⚠️ 部分落地 | `SessionSummarizer` 为规则引擎，未接入 LLM | `BuiltInMemoryProvider.onSessionEnd` 异步调用轻量模型生成摘要与关键事实 | 待分配 | - |
| 6.9.3 | 记忆上下文**token 预算 / Top-K 注入** | ⚠️ 部分落地 | 已有 16KB 长度保护和 token 估算，但未按“与当前查询相似度排序 + token 上限 Top-K”注入 | `BuiltInMemoryProvider.prefetch` 中按查询相似度排序，设置 token 预算上限，保留 Top-K | 待分配 | - |
| 6.11.3 | Skill 工具统一命名、`examples`、`use_skill` 元工具 | ⚠️ 部分落地 | `Skill` 接口有 `category`/`tags`/`metadata`，但无 `examples` 字段和统一 `use_skill` 元工具 | `Skill.kt` 增加 `examples`；`SkillToolAdapter` 转换时增强 schema；新增 `use_skill` 元工具 | 待分配 | - |
| 6.12.1 | **统一截断标记与续读协议** | ❌ 未开始 | 各工具截断字段不统一 | 在 `ToolResult` / `ToolExecutor.postProcess` 中统一追加 `{truncated, total_items, returned_items, next_offset, hint}` | 待分配 | - |
| 6.12.2 | 工具结果中嵌入 **token 预算提示** | ❌ 未开始 | 未在工具结果中提示上下文消耗 | `ToolExecutor.postProcess` 追加 `context_cost_estimate` / `remaining_context_hint` | 待分配 | - |

### 3.3 P2 级

| 报告节 | 优化项 | 状态 | 差距说明 | 建议交付物 | 负责人 | 目标日期 |
|--------|--------|------|----------|------------|--------|----------|
| 6.7.2 | **动态页面抓取**（Readability / Playwright） | ❌ 未开始 | `web_scraper` 仅支持 JSoup 静态解析 | 新增 `fetch_url_markdown` 工具：先集成 Readability 算法，可选 Playwright 无头浏览器 | 待分配 | - |
| 6.13.2 | **OpenTelemetry 导出** | ❌ 未开始 | 已有 `AgentHooks` 和 `ExecutionTracer`，但无 OpenTelemetry 格式导出 | `ObservabilityService` 增加 OpenTelemetry span/span exporter；提供配置开关 | 待分配 | - |
| 4.4.6 | **ACP（Agent Client Protocol）支持** | ❌ 未开始 | 未找到 ACP Server/Client 实现 | 调研 ACP 协议；新增 ACP server/client 模块（可选长期任务） | 待分配 | - |

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

## 4. 进度总览

```text
P0:  0 项部分落地，0 项未开始，1 项已完成
P1:  5 项部分落地，2 项未开始，2 项已完成
P2:  0 项部分落地，3 项未开始，0 项已完成
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
