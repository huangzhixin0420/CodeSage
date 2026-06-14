# CodeSage Agent 工具能力深度检视与优化建议报告

> 调研日期：2026-06-13  
> 调研范围：CodeSage `src/main/kotlin/com/codesage/agent/tools` 及相关代码；Claude Code、OpenAI Codex CLI、Cursor Agent、Kimi CLI 等主流 Agent 产品的公开实现/系统提示/文档。  
> 报告性质：只读调研 + 优化建议，未修改业务代码。

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [调研方法与范围](#2-调研方法与范围)
3. [CodeSage 工具全景](#3-codesage-工具全景)
   - 3.1 工具架构与注册机制
   - 3.2 工具清单与核心实现
   - 3.3 执行流程与性能特征
4. [同类 Agent 产品工具实现对比](#4-同类-agent-产品工具实现对比)
   - 4.1 Claude Code
   - 4.2 OpenAI Codex CLI
   - 4.3 Cursor Agent / Composer
   - 4.4 Kimi CLI / Kimi Code CLI
5. [CodeSage 工具能力评分](#5-codesage-工具能力评分)
6. [优化改进建议（逐项）](#6-优化改进建议逐项)
7. [结论与优先级路线图](#7-结论与优先级路线图)

---

## 1. 执行摘要

CodeSage 的 Agent 工具生态已覆盖**文件操作、Shell/命令、Git、HTTP、代码分析、构建测试、数据处理、记忆、子 Agent 委托、MCP/Skill 扩展**等 10 余大类，注册工具数量超过 50 个，具备较完整的工程化骨架。其亮点包括：

- **统一工具注册与执行框架**：`ToolRegistry` + `ToolHandler`/`UnifiedTool` + `ToolExecutor`，支持动态扩展、审计日志、速率限制、Guardrails 前后置处理。
- **IDE 原生集成**：文件读写走 IntelliJ VFS/PSI，保证与 IDE 状态同步；`SymbolIndex` 提供增量符号索引。
- **安全与沙箱**：`ShellInjectionDetector`、`CommandSandbox`（Seatbelt/bwrap）、`SsrfGuard` 构成多层防护。
- **并行执行**：`EnhancedAgentLoop` 对同一轮 tool_call 默认并发上限 6，并支持子 Agent `delegate_task`。

但与 Claude Code、Codex CLI、Cursor、Kimi 等头部产品相比，CodeSage 在以下方面存在明显差距：

1. **文件读取**：缺乏行号输出、多模态（PDF/图片）、可配置的 token 上限；大文件分页策略对 `offset/limit` 场景仍有全量读入内存的风险。
2. **代码编辑**：仅支持 `old_string` 替换和行范围替换，缺少 Codex 的 `apply_patch` 结构化 diff、Claude 的 `MultiEdit` 批量原子编辑，编辑失败后的重试成本较高。
3. **搜索**：`grep_code`/`search_code` 基于递归遍历 VFS 并逐行正则匹配，大项目性能远低于 ripgrep；无专用 `Glob` 工具；`semantic_search` 依赖外部向量模型，当前多为降级回退。
4. **命令执行**：输出截断后缺少“按页续读”机制；无后台/监控类命令（如 Claude `Bash` 的 `run_in_background`、Codex `container.exec`）。
5. **代码洞察**：调用图分析是启发式正则扫描，准确性不足；符号搜索 `fuzzySearch` 在大项目上是 O(n) 全量过滤。
6. **记忆与子 Agent**：记忆工具仅暴露简单 CRUD，缺乏自动会话摘要、向量检索；子 Agent 结果以纯文本返回，结构化元数据未进入父上下文。
7. **MCP/Skill 生态**：MCP 工具通过 `MCPDelegatingSkill` 包装，但缺少工具数量上限控制、动态工具发现、权限规则前置过滤等成熟机制。

本报告将对上述差距逐项给出**优化背景、同类产品对比、技术方案、调研依据与实施建议**。

---

## 2. 调研方法与范围

### 2.1 CodeSage 源码检视

- **工具定义与注册**：`src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt`、`ToolExecutor.kt`、`UnifiedTool.kt`、`ToolHandler.kt`
- **IDE 文件/命令工具**：`src/main/kotlin/com/codesage/agent/tools/IDETools.kt`、`IDEFileHandlers.kt`
- **Git/Shell/HTTP/数据处理**：`src/main/kotlin/com/codesage/agent/tools/ExtendedTools.kt`、`ExtendedToolHandlers.kt`
- **代码分析**：`src/main/kotlin/com/codesage/analysis/CodeInsightExecutor.kt`、`SymbolIndex.kt`、`SemanticSearch.kt`
- **增强工具**：`src/main/kotlin/com/codesage/agent/tools/handlers/*ToolHandlers.kt`
- **Agent 循环与子 Agent**：`src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`、`SubAgentExecutor.kt`
- **记忆**：`src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt`
- **Skill/MCP 适配**：`src/main/kotlin/com/codesage/agent/tools/SkillToolAdapter.kt`、`src/main/kotlin/com/codesage/mcp/server/MCPDelegatingSkill.kt`

### 2.2 同类产品调研

| 产品 | 调研来源 | 关键信息 |
|------|----------|----------|
| Claude Code | 官方系统提示 gist、Hook 文档、GitHub issues | 工具 schema、Read/Edit/Bash/Glob/Grep/Todo/Task/WebSearch/WebFetch、Hooks |
| OpenAI Codex CLI | GitHub 源码解析、OpenAI API 文档、Issue 讨论 | `apply_patch`、`shell`、`container.exec`、`update_plan`、沙箱 |
| Cursor Agent/Composer | 系统提示 gist、技术博客、官方文档 | `file_search`、`read_file`、`edit_file`、`terminal`、`web_search`、Composer 模型训练 |
| Kimi CLI / Kimi Code CLI | GitHub README、官方文档、技术博客 | `ReadFile`、`WriteFile`、`StrReplaceFile`、`Glob`、`Grep`、`Shell`、`SetTodoList`、`Task`、ACP |

---

## 3. CodeSage 工具全景

### 3.1 工具架构与注册机制

CodeSage 的工具架构可分为五层：

```
┌─────────────────────────────────────────┐
│ 入口层：ToolWindow / InlineChat / Actions │
├─────────────────────────────────────────┤
│ 编排层：EnhancedAgentLoop                │
│   - 流式响应、并行 tool_call、取消、子 Agent │
├─────────────────────────────────────────┤
│ 执行层：ToolExecutor + ToolRegistry      │
│   - 路由、重试、Guardrails、审计日志、Tracing│
├─────────────────────────────────────────┤
│ 扩展层：SkillToolAdapter / MCPDelegatingSkill│
│   - Skill → Tool、MCP Server → Tool      │
├─────────────────────────────────────────┤
│ 实现层：IDETools / ExtendedTools / ...   │
│   - VFS/PSI、Shell、HTTP、索引、SQLite   │
└─────────────────────────────────────────┘
```

关键实现：

- **注册中心**：`ToolRegistry.createDefault()` 手动注册约 50+ 工具（`ToolRegistry.kt:84-184`），同时支持 `ToolProvider`/`SkillProvider` 扩展点。
- **Handler 绑定**：新工具通过 `register(handler: ToolHandler)` 将 schema 与执行逻辑绑定，旧方式仅注册 schema 的已逐步废弃（`ToolRegistry.kt:34-41`）。
- **执行流程**：`ToolExecutor.execute()` 依次执行 rate limit → guardrails preCheck → 重试执行 → guardrails postProcess → audit log → tracing（`ToolExecutor.kt:49-187`）。
- **结果格式**：统一返回 `{"success": true, "data": ...}` 或 `{"success": false, "error": ...}`（`ToolExecutor.kt:288-312`）。

### 3.2 工具清单与核心实现

按功能分类的工具清单（仅列出主要工具，完整列表见 `ToolRegistry.kt`）：

| 类别 | 工具名 | 核心实现文件 | 关键能力 |
|------|--------|--------------|----------|
| 文件读写 | `read_file` | `IDETools.kt:100` | offset/limit 分页、大文件 memory-mapped 前 1000 行 |
| 文件读写 | `write_file` | `IDETools.kt:328` | 覆盖/追加、自动创建父目录、VFS 同步 |
| 文件读写 | `edit_file` | `IDETools.kt:1224` | old_string 替换 / 行范围替换，强制唯一匹配 |
| 文件读写 | `read_multiple_files` | `IDETools.kt:1160` | 并行读取多文件，单文件截断 10k |
| 目录/搜索 | `list_directory` | `IDETools.kt:446` | 递归、max_depth、exclude_dirs |
| 目录/搜索 | `find_file` | `IDETools.kt:902` | 文件名 glob/regex，上限 1000 |
| 目录/搜索 | `grep_code` | `IDETools.kt:978` | 内容搜索 + 上下文行，大文件前 1000 行 |
| 目录/搜索 | `search_code` | `IDETools.kt:542` | 正则搜索，上限 1000 |
| 目录/搜索 | `get_project_structure` | `IDETools.kt:823` | 项目结构树 |
| Shell | `run_command` | `IDETools.kt:689` | 默认 30s、输出上限 1M、OS 沙箱 |
| Shell | `exec_shell` | `ExtendedTools.kt:197` | 默认 60s、最大 300s、OS 沙箱 |
| Git | `git_status/diff/log/branch` | `ExtendedTools.kt:52-169` | 只读 Git 工具 |
| Git | `git_add/commit/stash/blame` | `ExtendedToolHandlers.kt:62-179` | 写操作 Git 工具 |
| HTTP/网络 | `http_request` | `ExtendedTools.kt:326` | OkHttp、SSRF 防护、JSON 自动格式化 |
| 数据处理 | `parse_json/format_json` | `ExtendedTools.kt:458-553` | 简单点号路径查询、格式化 |
| 数据处理 | `regex_test/extract` | `RegexToolHandlers.kt` | 正则测试/提取 |
| 数据处理 | `diff_files` | `DiffToolHandlers.kt:13` | 简化 Myers diff |
| 代码分析 | `analyze_symbol/find_usages/get_inheritance_chain` | `CodeInsightExecutor.kt:56-507` | PSI 符号分析、调用图、继承链 |
| 代码分析 | `semantic_search` | `CodeInsightExecutor.kt:513` | 语义搜索（无模型时降级） |
| 代码分析 | `symbol_search` | `HighValueTools.kt:311` | SymbolIndex 模糊搜索 |
| 构建/测试 | `maven/gradle/run_tests/run_linter` | `BuildToolHandlers.kt`/`TestToolHandlers.kt`/`HighValueTools.kt:91` | Maven/Gradle/npm 包装 |
| 记忆 | `memory_search/add/update` | `BuiltInMemoryProvider.kt:232` | SQLite + FTS5 |
| 子 Agent | `delegate_task` | `EnhancedAgentLoop.kt:869` | 子 Agent 委托，最大递归深度 2 |
| MCP/Skill | `skill_<id>` | `SkillToolAdapter.kt` / `MCPDelegatingSkill.kt` | 动态暴露 Skill/MCP 工具 |

### 3.3 执行流程与性能特征

#### 3.3.1 并行执行

`EnhancedAgentLoop.executeToolCallsParallel()` 使用 `Semaphore(6)` 限制并发，按原始 `toolCalls` 顺序返回结果，取消时未完成的工具标记为 `cancelled`（`EnhancedAgentLoop.kt:727-867`）。

#### 3.3.2 大文件与输出控制

- `read_file`：>100KB 文件默认只读前 1000 行（`IDETools.kt:123`），全文读取时截断到 10k 字符（`MAX_CONTENT_LENGTH = 10_000`）。
- `run_command`/`exec_shell`：单流输出上限 1M 字符，超限时 drain 剩余输出避免管道阻塞（`IDETools.kt:303-323`）。
- `search_code`/`grep_code`：大文件仅扫描前 1000 行（`IDETools.kt:645-651`）。

#### 3.3.3 主要性能隐患

- **分页读仍全量加载**：`computePagedContent` 调用 `raw.lines()`，对超大文件配合 `offset/limit` 仍会 O(n) 分配（`IDETools.kt:244-259`）。
- **搜索遍历递归 VFS**：`searchInVirtualFile`/`grepInFile` 递归遍历 `VirtualFile.children`，未使用 ripgrep 等外部索引，大项目慢（`IDETools.kt:608-677`）。
- **符号模糊搜索 O(n)**：`SymbolIndex.fuzzySearch` 在 `nameIndex.entries` 上全量过滤（`SymbolIndex.kt:223-232`）。
- **调用图启发式正则**：`findCallees` 用正则扫描方法体附近 50 行，误报率高（`CodeInsightExecutor.kt:201-269`）。
- **LCS diff 内存**：`DiffToolHandlers.computeLCS` 是 O(m·n) 动态规划，大文件 diff 会分配 `(m+1)*(n+1)` 的 `IntArray`（`DiffToolHandlers.kt:127-157`）。


---

## 4. 同类 Agent 产品工具实现对比

### 4.1 Claude Code

Claude Code 的核心工具集简洁但高度工程化，当前公开系统提示中暴露的工具包括：

`Bash, Glob, Grep, LS, Read, Edit, MultiEdit, Write, NotebookRead, NotebookEdit, WebFetch, TodoRead, TodoWrite, WebSearch, exit_plan_mode, Agent`

#### 4.1.1 Read 工具

- **参数**：`file_path`（必填，绝对路径）、`offset`（可选，行号）、`limit`（可选，行数）。
- **默认行为**：读取前 2000 行，每行截断到 2000 字符。
- **输出格式**：`cat -n` 风格，带行号，便于模型直接引用。
- **多模态**：支持图片、PDF、Jupyter Notebook。

对比 CodeSage：`read_file` 返回 JSON，不带行号，模型需自行计算行号；无 PDF/图片读取能力。

#### 4.1.2 Edit / MultiEdit 工具

- **Edit**：`old_string` + `new_string`，要求 `old_string` 在文件中唯一。
- **MultiEdit**：单次调用对同一文件执行多组替换，原子性更强。
- **Write**：直接创建/覆盖文件。

对比 CodeSage：`edit_file` 也要求唯一匹配，但无 `MultiEdit` 等价物，多文件编辑需多次调用。

#### 4.1.3 Bash 工具

- **参数**：`command`、`timeout`（最大 600000ms = 10 分钟）、`description`。
- **输出**：stdout/stderr/exit_code，输出超过 30k 字符截断。
- **策略**：明确禁止用 Bash 做 Read/Edit/Grep 能做的事，避免浪费 token。

对比 CodeSage：`run_command` 默认 30s、`exec_shell` 最大 300s，仍不及 Claude 的 10 分钟长任务支持。

#### 4.1.4 Glob / Grep

- **Glob**：快速文件模式匹配，返回按修改时间排序的路径列表。
- **Grep**：基于 ripgrep，支持正则、文件过滤、输出模式（content/files_with_matches/count）。

对比 CodeSage：`find_file` 是递归正则遍历 VFS，`grep_code` 也是逐行扫描，性能差距明显。

#### 4.1.5 Todo / Agent / Hooks

- **Todo**：会话级任务列表，模型可主动管理多步任务。
- **Agent**：子 Agent 工具，支持并发委派。
- **Hooks**：`PreToolUse`/`PostToolUse` 等生命周期钩子，支持策略拦截、参数重写、审计。

对比 CodeSage：有 `delegate_task` 但无 `Todo` 原生工具；有 `AgentHooks` 但公开资料中规则引擎不如 Claude 成熟。

### 4.2 OpenAI Codex CLI

Codex CLI 是开源项目，其核心工具设计围绕**结构化 patch** 和**沙箱执行**。

#### 4.2.1 apply_patch（最具特色）

Codex 的编辑不通过 `old_string` 替换，而是让模型生成统一 diff 风格的 patch：

```
*** Begin Patch
*** Update File: path/to/file.py
@@ def example():
-  pass
+  return 123
*** End Patch
```

- **优势**：
  - 一次 patch 可包含多个文件的多个修改（Multi-file atomic patch）。
  - 模型只需描述变更，patch 解析器负责应用到文件，降低 old_string 唯一性失败率。
  - 与 `git diff` 语义一致，便于审阅和回滚。
- **实现**：`src/utils/agent/apply-patch.ts` 解析 patch，`process_patch` 用 Node.js `fs` 写回。

对比 CodeSage：缺少结构化 patch 工具，多文件编辑依赖多次 `edit_file`，上下文占用高且容易失败。

#### 4.2.2 shell / container.exec

- `shell`：执行本地 shell 命令。
- `container.exec`：在隔离容器中执行命令（Linux）。
- **沙箱**：macOS Seatbelt、Linux Landlock/seccomp、网络限制。

对比 CodeSage：`CommandSandbox` 已有 Seatbelt/bwrap，但缺少容器化执行路径。

#### 4.2.3 update_plan

Codex 还有一个 `update_plan` 工具，让模型显式维护任务计划列表。该工具对长程任务非常有用，可减少模型“忘记目标”的问题。

对比 CodeSage：无原生计划/待办工具，仅在 UI 层可能有 todo，未通过工具暴露给模型。

### 4.3 Cursor Agent / Composer

Cursor Agent 的工具集与 Claude Code 类似，但围绕 Composer 模型做了深度定制。

#### 4.3.1 工具列表

主要工具：`codebase_search`（语义搜索）、`read_file`、`edit_file`、`file_search`、`run_terminal_cmd`、`list_dir`、`grep_search`、`web_search`、`fetch_rules`、`reapply`

#### 4.3.2 edit_file 设计

Cursor 的 `edit_file` 要求模型只写出要修改的精确代码行，**未变更行用 `// ... existing code ...` 注释占位**。这一设计：

- 减少模型输出 token。
- 降低模型复制大段无关代码导致的误改风险。
- 由“较弱的应用模型”将 sketch 应用到实际文件。

对比 CodeSage：`edit_file` 要求精确的 `old_string`，模型需输出完整被替换文本，token 消耗较大。

#### 4.3.3 codebase_search

Cursor 提供语义级代码库搜索，直接基于向量索引。Composer 模型在 RL 训练中大量使用该工具，形成“先搜索再编辑”的工作流。

对比 CodeSage：`semantic_search` 当前多降级为普通搜索，无稳定向量索引。

#### 4.3.4 并行 Agent

Cursor 2.0 支持最多 8 个并行 Agent，利用 git worktree 隔离，避免文件冲突。

对比 CodeSage：支持 `delegate_task` 子 Agent，但未与 git worktree 集成做物理隔离。

### 4.4 Kimi CLI / Kimi Code CLI

Kimi CLI 是 Moonshot AI 开源的 Python 终端 Agent，Kimi Code CLI 是其 TypeScript 后继版本。两者工具设计都高度贴近 Claude Code，但有自身特色。

#### 4.4.1 工具列表（Kimi CLI Python 版）

`ReadFile, WriteFile, StrReplaceFile, Glob, Grep, Shell, SearchWeb, FetchURL, SetTodoList, Task`

#### 4.4.2 ReadFile

- 参数：`path`（绝对路径）、`line_offset`、`n_lines`。
- 限制：文件大小 100KB，行数 1000 行，单行长度 2000 字符。

对比 CodeSage：限制更严格，但明确告知模型边界；CodeSage 的 100KB 大文件阈值与 Kimi 相同，但无绝对路径强制要求。

#### 4.4.3 StrReplaceFile

Kimi 的字符串替换工具与 CodeSage `edit_file` 类似，但 Kimi 强调**必须使用绝对路径**，并在失败时给出清晰错误。

#### 4.4.4 Shell

- 超时 1-300 秒，支持流式输出，需要用户批准（yolo 模式除外）。

对比 CodeSage：流式输出能力不足，命令结果是一次性返回。

#### 4.4.5 Task / SetTodoList / Checkpoint

- `Task`：子 Agent 工具，可指定 `subagent_name`（如 coder/explorer）。
- `SetTodoList`：模型可维护任务列表。
- **Checkpoint**：Kimi CLI 提供会话 checkpoint，支持回滚到之前状态。

对比 CodeSage：有 `delegate_task` 但无 `SetTodoList` 工具；无 checkpoint 机制。

#### 4.4.6 ACP（Agent Client Protocol）

Kimi Code CLI 支持 ACP，可作为 Agent Server 被 Zed、JetBrains 等 IDE 驱动。这是 CodeSage 作为 IntelliJ 插件可借鉴的方向。

---


## 5. CodeSage 工具能力评分

评分维度说明：

- **功能完整性（F）**：工具覆盖的场景是否全面，参数是否足够表达需求。
- **输出可用性（O）**：返回结构是否清晰、是否带元数据（行号、截断标记、错误信息）、是否便于模型下一步决策。
- **性能与资源（P）**：大文件、大项目、高并发下的表现，是否有截断/分页/索引。
- **安全与鲁棒性（S）**：危险操作拦截、沙箱、SSRF、错误处理、重试。
- **竞争力（C）**：与 Claude/Codex/Cursor/Kimi 同类产品相比的差距。

每项满分 10 分，总分 50 分。

### 5.1 文件操作类工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `read_file` | 7 | 6 | 6 | 7 | 5 | 31 | 无行号、无多模态、分页时仍全量读入 |
| `write_file` | 8 | 7 | 6 | 6 | 6 | 33 | 无显式大小限制、无写入前 diff/确认 |
| `edit_file` | 7 | 6 | 6 | 6 | 5 | 30 | 缺少 apply_patch/MultiEdit、old_string 失败后无智能重试 |
| `read_multiple_files` | 8 | 7 | 7 | 7 | 6 | 35 | 单文件 10k 截断较激进、无批量 offset |
| `list_directory` | 8 | 8 | 6 | 7 | 6 | 35 | 递归遍历、大目录慢 |
| `find_file` | 7 | 7 | 5 | 7 | 5 | 31 | 无 ripgrep/glob 支持、递归 VFS 慢 |
| `grep_code` | 7 | 7 | 5 | 7 | 5 | 31 | 无 ripgrep、上下文行实现简单 |
| `search_code` | 7 | 7 | 5 | 7 | 5 | 31 | 同上 |
| `get_file_info` | 7 | 7 | 7 | 7 | 6 | 34 | 行数仅 <1MB 计算 |
| `delete_file/copy_file/move_file` | 7 | 7 | 6 | 7 | 6 | 33 | 移动跨文件系统已处理、但缺少事务回滚 |

### 5.2 Shell / 系统命令类

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `run_command` | 7 | 7 | 7 | 8 | 6 | 35 | 默认 30s 偏短、无后台运行、流式输出弱 |
| `exec_shell` | 7 | 7 | 7 | 8 | 6 | 35 | 与 run_command 能力重叠，存在两个 Shell 工具 |
| `docker` | 5 | 5 | 5 | 5 | 4 | 24 | 仅简单包装 docker CLI |

### 5.3 Git 工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `git_status/diff/log/branch` | 7 | 7 | 7 | 7 | 6 | 34 | 功能基本够用，但缺少结构化 blame 行级数据 |
| `git_add/commit/stash/blame` | 7 | 7 | 7 | 7 | 6 | 34 | `git_commit` 与 `create_pull_request` 之间缺少 `git_push` |
| `create_pull_request` | 6 | 6 | 6 | 6 | 5 | 29 | 依赖 `gh` CLI，未校验分支是否已 push |
| `git_worktree` | 6 | 6 | 6 | 6 | 5 | 29 | 未与并行 Agent 深度集成 |

### 5.4 HTTP / 网络工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `http_request` | 7 | 7 | 6 | 8 | 6 | 34 | 无响应 body 大小限制、无分页/流式下载 |
| `web_scraper` | 6 | 6 | 5 | 7 | 5 | 29 | JSoup 解析、截断 20k、无法处理动态页面 |

### 5.5 代码分析类工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `analyze_symbol` | 7 | 7 | 6 | 6 | 5 | 31 | 调用图是启发式正则、复杂度公式简单 |
| `find_usages` | 7 | 7 | 5 | 6 | 5 | 30 | ReferencesSearch 反射调用脆弱、降级文本搜索慢 |
| `get_inheritance_chain` | 6 | 7 | 6 | 6 | 5 | 30 | 依赖 SymbolIndex 反向索引，但构建时机不确定 |
| `semantic_search` | 5 | 6 | 5 | 6 | 4 | 26 | 多为降级普通搜索，无稳定向量模型 |
| `symbol_search` | 6 | 7 | 4 | 6 | 4 | 27 | fuzzySearch O(n) 全量过滤 |
| `get_file_summary` | 7 | 7 | 6 | 6 | 5 | 31 | PSI 依赖强，headless 测试环境不可用 |

### 5.6 构建/测试/质量工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `maven` | 6 | 6 | 5 | 6 | 5 | 28 | 输出截断 50k，缺少结构化解析（失败测试定位） |
| `gradle` | 6 | 6 | 5 | 6 | 5 | 28 | 同上 |
| `run_tests` | 6 | 6 | 5 | 6 | 5 | 28 | 未区分编译失败 vs 测试失败 vs 具体失败用例 |
| `run_linter` | 6 | 6 | 5 | 6 | 5 | 28 | 自动检测构建系统，但输出截断 10k 且未解析为问题列表 |
| `analyze_dependencies` | 5 | 5 | 5 | 6 | 4 | 25 | 仅包装命令输出 |

### 5.7 数据处理类工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `parse_json` | 6 | 6 | 7 | 7 | 5 | 31 | 仅支持点号路径，无数组切片/JMESPath |
| `format_json` | 7 | 7 | 7 | 7 | 6 | 34 | 够用 |
| `regex_test/extract` | 7 | 7 | 7 | 7 | 6 | 34 | 够用 |
| `diff_files` | 6 | 6 | 4 | 6 | 4 | 26 | O(m·n) LCS，大文件内存爆炸 |
| `timestamp/uuid/clipboard` | 6 | 6 | 7 | 6 | 5 | 30 | 属于锦上添花类工具 |

### 5.8 记忆类工具

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `memory_search/add/update` | 6 | 6 | 6 | 6 | 5 | 29 | 缺少向量检索、自动摘要、跨会话关联 |

### 5.9 子 Agent / 委托类

| 工具 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| `delegate_task` | 7 | 5 | 6 | 6 | 5 | 29 | 结果纯文本返回，元数据未进父上下文；最大深度 2 偏保守 |

### 5.10 MCP / Skill 动态工具

| 能力 | F | O | P | S | C | 总分 | 主要问题 |
|------|---|---|---|---|---|------|----------|
| Skill → Tool | 7 | 6 | 6 | 6 | 5 | 30 | 工具名动态生成，模型可能混淆 |
| MCP 集成 | 6 | 6 | 5 | 6 | 5 | 28 | 缺少工具数量上限、权限规则前置过滤 |

---


## 6. 优化改进建议（逐项）

### 6.1 文件读取：增强输出格式与大文件处理

#### 6.1.1 为 `read_file` / `read_multiple_files` 增加行号输出

- **优化背景**：当前 `read_file` 返回 JSON 中的 `content` 是纯文本，不带行号。模型在后续调用 `edit_file` 时使用 `start_line/end_line` 需要自行计数，容易出错；Claude Code 的 `Read` 工具默认返回 `cat -n` 格式，可直接引用行号。
- **同类产品对比**：
  - Claude Code `Read`：`cat -n` 格式，行号前缀 `spaces + line_number + tab + content`。
  - Cursor `read_file`：同样返回带行号内容。
- **优化方案建议**：
  - 在 `read_file` 返回结构中添加可选 `line_numbers` 字段，或在 `content` 中直接嵌入行号（如 `001|\tcontent`）。
  - 保持现有 `content` 不变以兼容旧调用，新增 `content_with_line_numbers` 字段供模型选择。
  - 修改位置：`IDETools.kt:100-160` 的返回字段组装逻辑。
- **技术调研**：`cat -n` 格式已被 Claude/Cursor 验证对模型最友好；行号前缀使用 tab 分隔可避免与代码空格混淆。
- **为什么这么优化**：降低模型在“读取→编辑”链路中的认知负担，减少行号计算错误导致的编辑失败，提升 `edit_file` 成功率。

#### 6.1.2 引入真正的流式/分块大文件读取

- **优化背景**：当前 `read_file` 对 >100KB 文件使用 memory-mapped 读前 1000 行；但当用户传入 `offset/limit` 时，代码走 `computePagedContent(raw, offset, limit)`，先 `String(virtualFile.contentsToByteArray())` 再 `raw.lines()`，超大文件仍会一次性加载进内存（`IDETools.kt:244-259`）。
- **同类产品对比**：
  - Claude Code `Read`：支持 `offset` + `limit`，底层实现同样基于行号偏移，但对文件大小有明确 token 上限（CLI 25k tokens / Desktop 10k tokens），超限直接报错并提示分页。
  - Kimi CLI `ReadFile`：限制 100KB/1000 行，超限明确失败。
- **优化方案建议**：
  - 复用已有的 `readLargeFileFromBuffer` 逻辑，对带 `offset` 的请求也走 ByteBuffer 流式扫描到目标行，再读取 `limit` 行。
  - 或引入 `java.nio.file.Files.lines()` 的 lazy stream，避免全量分配。
  - 在返回中始终携带 `total_lines`（对大文件可用 VFS length 或快速扫描估算）。
- **技术调研**：已验证 `readLargeFileFromBuffer` 可正确解析 UTF-8 多字节字符并定位换行（`IDETools.kt:170-219`）；只需将其改造为支持起始偏移即可。
- **为什么这么优化**：避免 10MB+ 源文件在分页场景下 OOM，支撑大型代码库（如 Android、Spring 源码）的使用。

#### 6.1.3 支持 PDF / 图片 / Jupyter Notebook 读取

- **优化背景**：CodeSage 作为 IDE 插件，天然可以读取项目中的设计图、架构图、PDF 文档、Notebook，但当前 `read_file` 仅按 UTF-8 文本解析，会损坏二进制/多模态内容。
- **同类产品对比**：Claude Code `Read` 明确支持 PNG/JPG/PDF/ipynb，模型可直接分析图片和 PDF 页面。
- **优化方案建议**：
  - 在 `read_file` 中根据文件扩展名路由：
    - `.png/.jpg/.jpeg/.webp`：读取为 base64，返回 `content_image_base64` + `mime_type`。
    - `.pdf`：使用 Apache PDFBox 或 IDE 内置 PDF 解析提取文本/页面信息。
    - `.ipynb`：解析 cell 列表，返回 `cells` JSON 数组。
  - 新增 `read_document` 工具专门处理复杂文档，避免污染 `read_file` 语义。
- **技术调研**：IntelliJ 平台对图片/PDF 已有查看器，可复用 `ImageIO`、`JBRC` 或引入轻量依赖（如 `org.apache.pdfbox:pdfbox`）。
- **为什么这么优化**：多模态是前沿模型的标配能力，扩展后可让模型基于截图、设计稿、PDF 需求文档直接生成/修改代码，显著扩大适用场景。

### 6.2 代码编辑：引入结构化 Patch 与批量编辑

#### 6.2.1 新增 `apply_patch` 工具（Codex 风格）

- **优化背景**：当前 `edit_file` 依赖 `old_string` 精确匹配，失败场景多：
  - 模型只复制了部分上下文导致 `old_string` 不唯一。
  - 文件被其他工具修改后 `old_string` 失效。
  - 多文件修改需要多次调用，增加往返。
- **同类产品对比**：
  - Codex CLI 的 `apply_patch` 让模型输出统一 diff，一次可改多文件多位置，失败率低。
  - OpenAI Responses API 已原生支持 `apply_patch` 工具。
- **优化方案建议**：
  - 新增 `apply_patch` 工具，参数为 `patch: string`。
  - Patch 格式兼容 Codex 风格：
    ```
    *** Begin Patch
    *** Update File: path/to/File.kt
    @@ class Foo {
    -    val x = 1
    +    val x = 2
    *** End Patch
    ```
  - 实现 `ApplyPatchTool` 解析器：识别 `Update File`/`Add File`/`Delete File`，生成新内容后调用 `writeVirtualFile` 写入。
  - 先以原子方式应用到内存，全部解析成功后再写盘，避免半成状态。
- **技术调研**：Codex 的 `codex-apply-patch` crate 和 `src/utils/agent/apply-patch.ts` 已公开实现思路，可移植为 Kotlin 版本。
- **为什么这么优化**：
  - 降低模型编辑失败率，减少“读取→编辑失败→再读取”的循环。
  - 与 `git diff` 语义一致，便于人工审阅和回滚。
  - 一次调用可修改多文件，减少上下文占用和 API 调用次数。

#### 6.2.2 新增 `multi_edit` 工具

- **优化背景**：同一文件多处小修改时，当前需要多次 `edit_file`，每次都要重新读取/写入，效率低。
- **同类产品对比**：Claude Code 提供 `MultiEdit`，可一次提交多个 `old_string`/`new_string` 对，原子应用。
- **优化方案建议**：
  - ✅ 已完成：新增 `multi_edit` 工具，参数 `path` + `edits: [{old_string, new_string}]`。
  - 校验所有 `old_string` 唯一且存在后，一次性应用并写回。
  - 任意一个失败则整体回滚，返回详细错误。
- **技术调研**：实现可复用 `edit_file` 的校验逻辑，改为批量校验 + 批量替换。
- **为什么这么优化**：减少同一文件多位置修改时的往返次数，提升长文件重构效率。

#### 6.2.3 编辑工具智能重试与模糊匹配 ✅ 已完成

- **优化背景**：当前 `old_string` 多匹配直接报错（`IDETools.kt:1251-1256`），模型需自行增加上下文；对缩进变化也敏感。
- **同类产品对比**：Codex `apply_patch` 基于 diff 行号+内容，对缩进变化更鲁棒；Cursor `edit_file` 由应用模型处理 sketch，降低精确匹配要求。
- **优化方案建议**：
  - 当 `old_string` 不唯一时，自动尝试用更大上下文（前后各 2 行）定位。
  - 提供 `fuzzy_match` 选项，忽略首尾空白差异。
  - 失败后返回候选匹配位置（行号+片段），帮助模型修正。
- **实现要点**：
  - 新增 `EditMatchEngine` 纯函数匹配引擎，支持精确匹配、模糊空白归一化、上下文去歧、候选位置返回。
  - `edit_file` 与 `multi_edit` 均新增可选 `fuzzy_match` 参数。
  - 去歧失败时错误消息包含所有候选行号与片段，模型可直接引用修正。
- **技术调研**：可在 `edit_file` 中引入 `String.trimIndent()` 归一化后再匹配，或基于行号+内容双重校验。
- **为什么这么优化**：降低模型因缩进/上下文不足导致的编辑失败，提升自主任务完成率。

### 6.3 搜索：从递归扫描升级到索引与外部引擎

#### 6.3.1 引入基于 ripgrep 的 `grep_code` / `search_code`

- **优化背景**：当前 `search_code`/`grep_code` 递归遍历 `VirtualFile.children`，对每个文件调用 `contentsToByteArray()` 再逐行正则匹配（`IDETools.kt:608-677`）。在大型项目中：
  - 未利用多核 CPU。
  - 未利用文件系统索引。
  - 大文件反复读入内存。
- **同类产品对比**：
  - Claude Code `Grep`：明确基于 ripgrep，支持 `output_mode`（content/files_with_matches/count）。
  - Kimi CLI `Grep`：基于 ripgrep，可配置 `-n`、大小写敏感等。
- **优化方案建议**：
  - 在 `run_command` 沙箱内调用 `rg`（ripgrep）执行搜索，解析其 JSON 输出（`rg --json`）。
  - 回退逻辑：当 `rg` 不可用时再回退到当前 VFS 扫描。
  - 参数对齐：`query`（regex）、`path`、`file_pattern`、`-i`（忽略大小写）、`max_results`。
- **技术调研**：ripgrep 默认已预装在大多数开发者 macOS/Linux 环境；Windows 可通过 Git for Windows 附带。`rg --json` 输出包含行号、匹配文本、文件路径，便于解析。
- **为什么这么优化**：
  - 性能提升 10-100 倍，支撑百万行代码库搜索。
  - 自动尊重 `.gitignore`，减少无关结果。
  - 与 Claude Code/Kimi 行为一致，降低模型迁移成本。

#### 6.3.2 新增专用 `glob` 工具

- **优化背景**：当前 `find_file` 将用户输入当作 regex/glob 混合处理（`IDETools.kt:921-925`），对复杂 glob（如 `**/*.kt`）支持不佳，且递归慢。
- **同类产品对比**：Claude Code `Glob` 专门用于文件模式匹配，返回按修改时间排序的路径列表。
- **优化方案建议**：
  - 新增 `glob` 工具，参数 `pattern`（如 `src/**/*.kt`）、`path`。
  - 实现可使用 `java.nio.file.Files.walk()` 或调用外部 `rg --files -g pattern`。
  - 默认上限 1000，返回 `matches[]` + `truncated`。
- **技术调研**：IntelliJ 平台提供 `FilenameIndex` 可按扩展名快速查找，但对任意 glob 不够灵活；外部 `rg --files` 更快。
- **为什么这么优化**：让模型明确区分“按内容搜索”与“按路径搜索”，减少 `search_code` 的误用，提升文件定位效率。

#### 6.3.3 改进 `semantic_search` 的向量召回能力

- **优化背景**：当前 `semantic_search` 依赖 `SemanticSearch.semanticQuery()`，在无向量模型时降级为普通搜索（`CodeInsightExecutor.kt:525-537`），实际效果接近文本搜索。
- **同类产品对比**：Cursor `codebase_search`、Claude Code 内部（部分版本）均使用基于 embedding 的语义搜索。
- **优化方案建议**：
  - 接入本地轻量 embedding 模型（如 `sentence-transformers/all-MiniLM-L6-v2` 通过 ONNX/Java）或远程 embedding API。
  - 对项目代码做 chunk 级索引，存储到 SQLite 或向量数据库（如 LanceDB、Chroma）。
  - 提供 `reindex_semantic` 工具，允许用户手动触发重建。
- **技术调研**：Kotlin/Java 生态可用 `onnxruntime` 跑本地 embedding；也可用 `jina.ai` 等 HTTP API。SQLite 扩展 `sqlite-vec` 可做本地向量检索。
- **为什么这么优化**：
  - 让“找相关代码”从关键词匹配升级为语义匹配，显著提升模型对自然语言需求的理解。
  - 支撑 `analyze_symbol` 等工具更准确地定位相关符号。

#### 6.3.4 优化 `SymbolIndex.fuzzySearch`

- **优化背景**：`fuzzySearch` 对 `nameIndex.entries` 全量过滤（`SymbolIndex.kt:223-232`），符号数量巨大时线性扫描。
- **同类产品对比**：IntelliJ 自身的 `ChooseByName` 使用 trie/前缀树实现快速符号查找。
- **优化方案建议**：
  - 为符号名构建前缀树（Trie）或 n-gram 索引。
  - 或使用 IntelliJ 平台 API `NavigationItem`/`ChooseByNameContributor` 直接查询已有索引。
- **技术调研**：IntelliJ 的 `PsiShortNamesCache` 和 `StubIndex` 已维护类/方法/字段索引，可直接利用，避免自建 O(n) 扫描。
- **为什么这么优化**：符号搜索是代码分析高频路径，O(n) 扫描在大型项目会成为瓶颈。

### 6.4 Shell / 命令执行：统一、流式与后台任务

#### 6.4.1 统一 `run_command` 与 `exec_shell`

- **优化背景**：CodeSage 同时存在 `run_command`（默认 30s）和 `exec_shell`（默认 60s，最大 300s），两者能力高度重叠，容易让模型困惑该用哪个。
- **同类产品对比**：Claude Code 只有一个 `Bash`；Kimi CLI 只有一个 `Shell`。
- **优化方案建议**：
  - 保留一个主 Shell 工具（建议命名为 `bash` 或 `shell`），合并两者参数。
  - 另一个标记为 deprecated，内部转发到主工具，并在 schema description 中提示。
  - 统一默认超时为 120s，最大 600s（与 Claude 对齐）。
- **技术调研**：合并后减少工具数量，降低模型选择困难；可通过 `ToolRegistry.unregister()` 逐步下线旧工具。
- **为什么这么优化**：
  - 减少工具冗余，降低系统提示长度。
  - 统一安全策略和输出格式，减少维护成本。

#### 6.4.2 支持后台/长时间运行命令

- **优化背景**：当前所有命令都是同步等待，若启动 dev server、test watcher 等长期进程会立即超时。
- **同类产品对比**：Claude Code `Bash` 支持 `timeout` 最大 10 分钟，Codex CLI 有 `container.exec` 可后台运行。
- **优化方案建议**：
  - 为 Shell 工具新增 `run_in_background: boolean` 参数。
  - 后台命令启动后返回 `process_id`，提供 `kill_process` 工具终止。
  - 提供 `read_process_output` 工具按 process_id 读取最新输出。
- **技术调研**：可用 `ProcessBuilder.inheritIO()` 或重定向到临时日志文件，维护 `Map<String, Process>` 管理后台进程。
- **为什么这么优化**：支撑“启动测试 watcher→等待文件变更→自动运行测试”等高级 Agent 工作流。

#### 6.4.3 流式命令输出

- **优化背景**：当前命令输出是命令结束后一次性返回，长时间命令下用户看不到进度。
- **同类产品对比**：Kimi CLI `Shell` 支持流式输出，实时显示 stdout/stderr。
- **优化方案建议**：
  - 在 `EnhancedAgentLoop` 中为 Shell 工具增加流式事件 `CommandOutputStream`。
  - 命令运行时按行 emit 输出到 UI，同时累积到结果中返回给模型。
- **技术调研**：可在 `readBounded` 基础上改为生产者-消费者模式，边读边 emit。
- **为什么这么优化**：提升长命令（如构建、测试）的用户体验，让模型也能基于中间输出提前决策。

### 6.5 代码分析：提升 PSI 利用深度与调用图准确性

#### 6.5.1 用 PSI 直接解析调用图替代启发式正则

- **优化背景**：`findCallees` 用正则扫描方法体附近 50 行提取 `identifier(`，误报率高（会把 `if/for/println` 等过滤，但仍会漏掉扩展函数、中缀调用等），且无法解析重载（`CodeInsightExecutor.kt:201-269`）。
- **同类产品对比**：IntelliJ 自身的“Find Usages”基于 PSI `ReferencesSearch`，可精确解析方法调用、重载、多态。
- **优化方案建议**：
  - 使用 `ReferencesSearch.search(psiElement)` 找调用者，使用 `MethodReferencesSearch` 等方法找被调用者。
  - 已存在 `findPsiReferences` 使用反射调用 `ReferencesSearch`（`CodeInsightExecutor.kt:322-368`），但 `findCallees` 未复用，应统一走 PSI。
- **技术调研**：对 Kotlin 项目，可用 `KtFunction`、`KtCallExpression` 遍历方法体；对 Java 可用 `PsiMethod`、`PsiMethodCallExpression`。可避免正则的语法盲区。
- **为什么这么优化**：调用图准确性直接影响重构、理解、影响面分析等核心能力，是代码分析工具的分水岭。

#### 6.5.2 引入 `find_callers` / `find_callees` 独立工具

- **优化背景**：当前 `analyze_symbol` 的 callers/callees 是可选字段，输出混在一起，模型难以精确控制。
- **同类产品对比**：JetBrains IDE 自身提供“Find Usages”动作，可作为独立工具思路。
- **优化方案建议**：
  - 新增 `find_callers(symbol_name, file_path, type)` 和 `find_callees(symbol_name, file_path, type)` 工具。
  - 返回结构化引用列表，含 `file_path`、`line`、`column`、`caller_symbol`、`callee_symbol`。
- **技术调研**：复用 `ReferencesSearch` 和 `PsiTreeUtil` 即可实现。
- **为什么这么优化**：让模型按需查询调用关系，减少 `analyze_symbol` 输出体积，提升精确度。

#### 6.5.3 支持跨语言符号索引

- **优化背景**：当前 `SymbolIndex.collectProjectFiles` 仅索引 `kt/java/scala/py/js/ts/go/rs/cpp/c/h`（`SymbolIndex.kt:175-186`），对前端 Vue/Svelte、配置文件 JSON/YAML、SQL 等无索引。
- **同类产品对比**：Cursor Composer 训练时使用了跨语言代码库搜索，能处理前端模板、配置文件。
- **优化方案建议**：
  - 扩展索引文件类型，至少加入 `vue`、`svelte`、`jsx`、`tsx`、`json`、`yaml`、`yml`、`sql`、`md`。
  - 对非代码文件，索引文件名、关键字段（如 JSON 顶层 key）而非完整 AST。
- **技术调研**：IntelliJ `FilenameIndex.getAllFilesByExt` 支持任意扩展名；`PSIAnalyzer` 可对文本文件做轻量解析。
- **为什么这么优化**：现代项目是多语言混合，扩展索引类型可提升项目结构理解和搜索覆盖率。


### 6.6 Git 工具：补齐 push、diff 解析与 PR 工作流

#### 6.6.1 新增 `git_push` 工具

- **优化背景**：当前 `create_pull_request` 要求分支已 push 到 origin，但工具链中没有 `git_push`，模型只能用 `exec_shell` 执行，增加不安全感。
- **同类产品对比**：Claude Code 在创建 PR 的指引中明确要求使用 `gh` 命令，但其 `Bash` 工具足够通用；CodeSage 既然提供了高层次的 `create_pull_request`，应配套 `git_push`。
- **优化方案建议**：
  - 新增 `git_push` 工具，参数 `working_dir`、`remote`（默认 origin）、`branch`。
  - 先检查当前分支是否有上游跟踪分支，无则 `git push -u origin <branch>`。
- **技术调研**：可直接调用 `git` 进程，复用 `ExtendedTools.executeGitCommand`。
- **为什么这么优化**：完善“commit → push → create PR”的闭环，减少模型对通用 Shell 工具的依赖。

#### 6.6.2 `git_diff` 返回结构化 diff

- **优化背景**：当前 `git_diff` 返回纯文本 diff（`ExtendedTools.kt:82-105`），模型需要自行解析文件名、行号、变更类型。
- **同类产品对比**：Codex CLI 在 `apply_patch` 中使用结构化 diff；GitHub API 返回 structured diff。
- **优化方案建议**：
  - 新增可选输出格式：`format: "structured"` 返回 `files[]` -> `hunks[]` -> `lines[]`，每行带 `type`（added/removed/context）和行号。
  - 保留纯文本格式作为默认，避免破坏旧调用。
- **技术调研**：可用 `org.eclipse.jgit` 解析 diff，或调用 `git diff -U<num> --no-color` 后自行解析 unified diff 格式。
- **为什么这么优化**：结构化 diff 便于模型精确定位修改位置，提高“基于 diff 做 review/补充修改”的能力。

#### 6.6.3 将 `git_worktree` 与并行子 Agent 集成

- **优化背景**：Cursor 2.0 使用 git worktree 隔离并行 Agent，CodeSage 已有 `git_worktree` 工具和 `delegate_task`，但两者未打通。
- **同类产品对比**：Cursor 2.0 的 Background Agents 直接在 worktree 中运行。
- **优化方案建议**：
  - `delegate_task` 支持可选 `isolated_worktree: boolean`，为子 Agent 自动创建 worktree、指定分支。
  - 子 Agent 完成后，将 worktree diff 作为结果返回父 Agent。
- **技术调研**：`GitWorktreeTool`（`HighValueTools.kt:244`）已实现 add/list/remove，只需在 `SubAgentExecutor` 中集成。
- **为什么这么优化**：实现真正的并行 Agent 隔离，避免文件冲突，支撑大规模并行任务。

### 6.7 HTTP / 网络工具：限制、解析与动态页面

#### 6.7.1 为 `http_request` 增加响应大小限制与流式下载

- **优化背景**：当前 `http_request` 直接读取整个 response body（`ExtendedTools.kt:382`），对大型文件/JSON 可能导致 OOM。
- **同类产品对比**：Claude Code `WebFetch` 会处理并截断页面内容；Kimi `FetchURL` 也有大小限制。
- **优化方案建议**：
  - 默认响应 body 上限 1MB，超限时返回截断标记和 `content_length`。
  - 支持 `max_response_size` 参数。
  - 对 `application/octet-stream` 等大文件，返回保存到临时文件的路径，而非直接读入内存。
- **技术调研**：OkHttp `ResponseBody.source()` 可流式读取并限制字节数。
- **为什么这么优化**：防止模型访问大文件时导致 IDE 内存问题，提升稳定性。

#### 6.7.2 增强 `web_scraper` 对动态页面与 Markdown 的支持

- **优化背景**：当前 `web_scraper` 使用 JSoup 解析静态 HTML（`MiscToolHandlers.kt:26`），对现代 SPA、文档站（如 Docusaurus、VitePress）内容提取不完整。
- **同类产品对比**：Claude Code `WebFetch` 使用 AI 模型对页面做摘要；Codex CLI 的 web 搜索依赖 OpenAI 内部搜索服务。
- **优化方案建议**：
  - 集成 `Mozilla Readability` 算法提取正文，返回 Markdown 格式。
  - 可选接入无头浏览器（Playwright/Puppeteer）处理动态页面，但需权衡性能和安全。
  - 新增 `fetch_url_markdown` 工具，输出 clean markdown。
- **技术调研**：Readability 有 Java 移植版（如 `crux`）；Playwright 可通过 MCP 或外部进程调用。
- **为什么这么优化**：提升文档、博客、API 文档的抓取质量，让模型基于网页内容生成代码更准确。

### 6.8 构建/测试/质量工具：结构化输出与失败定位

#### 6.8.1 `run_tests` 返回结构化测试结果

- **优化背景**：当前 `run_tests` 仅返回 `exit_code` + `summary`（`TestToolHandlers.kt:17` 附近），模型无法直接知道哪些测试失败、失败栈位置。
- **同类产品对比**：Codex CLI 在执行测试后会解析输出并尝试修复；Cursor 可直接运行 terminal 命令并解析结果。
- **优化方案建议**：
  - 对 JUnit/TestNG，解析 surefire/gradle test XML 报告，返回 `tests[]` -> `name/status/duration/failure_message/stack_trace`。
  - 对失败用例，自动调用 `read_file` 定位到测试代码和被测代码。
- **技术调研**：JUnit XML 报告格式标准，可用 Kotlin 序列化解析；Gradle/Maven 测试任务结束后会在 `build/test-results` / `target/surefire-reports` 生成 XML。
- **为什么这么优化**：让模型能基于结构化失败信息直接定位 bug，减少人工干预。

#### 6.8.2 `run_linter` 返回问题列表

- **优化背景**：当前 `run_linter` 输出截断到 10k 字符且为纯文本（`HighValueTools.kt:91-138`），模型难以精确提取问题位置。
- **同类产品对比**：Cursor 在 Composer 训练中使用 lint 收集工具，模型可直接基于问题列表修复。
- **优化方案建议**：
  - 对 Maven Checkstyle：解析 XML 报告。
  - 对 Gradle：使用 `gradle checkstyleMain --checkstyleOutput xml`。
  - 对 ESLint：使用 `--format json`。
  - 返回 `issues[]` -> `file/line/column/rule/severity/message`。
- **技术调研**：各 linter 均有机器可读输出格式，解析成本低。
- **为什么这么优化**：让模型能批量、精确地修复 lint 问题，形成“lint → edit → verify”闭环。

#### 6.8.3 构建工具支持更细粒度任务

- **优化背景**：当前 `maven`/`gradle` 只是包装命令，缺少对常见任务（如 `dependency:tree`、`test --tests Class#method`）的友好参数。
- **同类产品对比**：Claude Code 让模型直接写完整命令，通用性更强；Codex CLI 也使用通用 shell。
- **优化方案建议**：
  - 保留通用 `maven`/`gradle` 工具。
  - 新增 `run_tests` 可接受 `test_class` + `test_method`，自动拼接 `--tests` 参数。
  - 新增 `dependency_tree` 工具，返回解析后的依赖树 JSON。
- **技术调研**：`mvn dependency:tree -DoutputType=json` 可直接输出 JSON；Gradle 有 `dependencies` 任务文本输出。
- **为什么这么优化**：减少模型拼接命令的负担，降低错误率。

### 6.9 记忆工具：从简单 CRUD 到主动记忆

#### 6.9.1 引入向量记忆与语义召回

- **优化背景**：当前 `memory_search` 基于 SQLite FTS5（`BuiltInMemoryProvider.kt:477-487`），只能做关键词匹配，无法召回“用户喜欢哪种代码风格”这类语义记忆。
- **同类产品对比**：Claude Code 的 Memory 功能使用模型自动提取事实；Kimi CLI 的记忆也是基于 embedding 的语义搜索。
- **优化方案建议**：
  - 对每条记忆生成 embedding，存储在 SQLite + `sqlite-vec` 或本地向量库。
  - `memory_search` 同时做 FTS5 关键词搜索和向量语义搜索，合并排序后返回。
- **技术调研**：`sqlite-vec` 是 SQLite 的向量扩展，零额外进程；也可使用 `LanceDB` 或 `Chroma`。
- **为什么这么优化**：提升长期记忆的召回质量，让 Agent 在多会话中保持一致偏好。

#### 6.9.2 自动会话摘要与关键事实提取

- **优化背景**：当前 `BuiltInMemoryProvider.onSessionEnd` 仅取最近 10 轮消息前 100 字拼接（`BuiltInMemoryProvider.kt:312-321`），摘要质量差。
- **同类产品对比**：Claude Code 会在会话结束时自动总结关键事实并写入记忆。
- **优化方案建议**：
  - 调用 LLM 生成会话摘要和关键事实（fact/preference/pattern）。
  - 将提取到的事实自动 `memory_add`，无需用户手动调用。
  - 控制摘要 token 预算，避免额外成本过高。
- **技术调研**：可在 `onSessionEnd` 中异步调用模型 API，使用轻量模型（如 moonshot 轻量版）降低成本。
- **为什么这么优化**：降低用户维护记忆的成本，让 Agent 真正“越用越懂用户”。

#### 6.9.3 记忆的上下文注入策略优化

- **优化背景**：当前 `prefetch` 将记忆直接拼接到 system prompt（`BuiltInMemoryProvider.kt:129-189`），若记忆过多会挤占上下文窗口。
- **同类产品对比**：Claude Code 的 memory 注入有严格预算控制，且支持按相关性排序。
- **优化方案建议**：
  - 对召回的记忆按与当前查询的相似度排序。
  - 设置记忆注入总 token 上限（如 2k tokens），超出时只保留 Top-K。
  - 区分“用户偏好”和“项目知识”，项目知识可注入到 `AGENTS.md` 类似的长期提示中。
- **技术调研**：已存在 `PREFETCH_TOTAL_MAX_LEN = 16KB` 总长度保护（`BuiltInMemoryProvider.kt`），可进一步按 token 估算。
- **为什么这么优化**：防止记忆挤占工作上下文，保证模型有足够 token 处理当前任务。

### 6.10 子 Agent / 委托：结构化结果与深度集成

#### 6.10.1 `delegate_task` 返回结构化元数据

- **优化背景**：当前 `delegate_task` 返回子 Agent 的纯文本摘要（`EnhancedAgentLoop.kt:982-986`），迭代数、使用工具等元数据只通过 UI 事件传递，未进入父模型上下文。
- **同类产品对比**：Claude Code `Task` 工具返回的结果包含结构化总结；Codex CLI 的 subagent 结果也包含 plan 和 summary。
- **优化方案建议**：
  - 在纯文本前增加 JSON 头或单独字段返回：`{ "success": true, "summary": "...", "iterations_used": N, "tools_used": [...], "changed_files": [...] }`。
  - 父模型可选择性读取结构化字段做下一步决策。
- **技术调研**：`SubAgentExecutor.spawn` 已返回 `SubAgentResult`（含 `iterationsUsed`、`toolsUsed`、`completedToolCalls`），只需在 `executeDelegateTask` 中序列化。
- **为什么这么优化**：让父 Agent 能基于子 Agent 的执行数据（如修改了哪些文件）做后续操作，而不是仅依赖自然语言总结。

#### 6.10.2 放宽递归深度并提供 budget 控制

- **优化背景**：当前 `delegate_task` 最大递归深度为 2（`EnhancedAgentLoop.kt` 附近配置），对复杂任务可能不够。
- **同类产品对比**：Claude Code 支持多层子 Agent；Kimi CLI `Task` 也支持嵌套。
- **优化方案建议**：
  - 将最大深度配置化（默认 3-5），并提供 `max_iterations` 预算。
  - 在子 Agent 结果中报告已用 budget，接近上限时提示父 Agent。
- **技术调研**：已在 `EnhancedAgentLoop` 中移除 `max_iterations` 参数（注释提到 budget system removed），可重新引入基于迭代数的软预算。
- **为什么这么优化**：支撑更复杂的分层任务（如“规划 → 实现 → 测试 → 文档”各自由子 Agent 完成）。

#### 6.10.3 子 Agent 工具集精细化

- **优化背景**：当前 `delegate_task` 的 `toolset` 枚举为 `coder/explorer/verifier/webfetcher`（`ToolRegistry.kt:363-400`），粒度较粗。
- **同类产品对比**：Claude Code 的 Agent 可继承父工具集并做细粒度过滤；Cursor 的并行 Agent 按任务类型隔离。
- **优化方案建议**：
  - 支持 `allowed_tools: ["read_file", "edit_file", "run_tests"]` 白名单。
  - 支持 `denied_tools` 黑名单。
  - 默认最小权限原则：只授予完成任务必需的工具。
- **技术调研**：`SubAgentExecutor` 中已有 toolset 过滤逻辑，可扩展为基于 allow/deny 列表。
- **为什么这么优化**：减少子 Agent 误操作风险，提升安全性和任务专注度。

### 6.11 MCP / Skill 动态工具：治理与发现

#### 6.11.1 增加 MCP 工具数量上限与动态发现

- **优化背景**：当前 MCP 工具通过 `MCPDelegatingSkill` 动态包装为 `skill_<id>`，但无数量上限。Cursor 明确限制 MCP 工具最多 40 个，避免上下文爆炸。
- **同类产品对比**：Cursor 对 MCP 工具有 40 个上限；Claude Code 的 MCP 工具也受模型上下文限制。
- **优化方案建议**：
  - 对 MCP 工具设置默认上限（如 40 个），按使用率/相关性排序。
  - 提供 `mcp_tool_search` 工具，让模型在需要时动态查询更多 MCP 工具。
- **技术调研**：Claude Code 的 `ToolSearch` 机制支持延迟加载工具；可参考实现。
- **为什么这么优化**：防止 MCP 工具过多导致模型选择困难和上下文浪费。

#### 6.11.2 支持权限规则前置过滤

- **优化背景**：Claude Code 支持在工具进入模型视野前按 deny 规则过滤（如 `Read(./.env.*)` 直接拒绝）。CodeSage 的 `ToolGuardrails` 在执行前检查，但模型仍能看到被禁止的工具/参数。
- **同类产品对比**：Claude Code `filterToolsByDenyRules()` 在 `assembleToolPool()` 阶段就剥离被禁工具。
- **优化方案建议**：
  - 在 `ToolRegistry.getAllTools()` 或 `EnhancedAgentLoop` 组装上下文时，应用 deny 规则过滤 schema。
  - 对敏感文件/目录，直接不将 `read_file`/`edit_file` 的对应参数暴露给模型。
- **技术调研**：可在 `ToolGuardrails` 中增加 `filterTools()` 方法，返回过滤后的工具列表。
- **为什么这么优化**：从“执行时拦截”升级为“上下文层面不可见”，更彻底地防止模型尝试敏感操作。

#### 6.11.3 统一 Skill 工具命名与文档

- **优化背景**：当前 Skill 暴露为 `skill_<id>`，模型可能不知道这些动态工具的语义；且 `builtin_file_reader` 与 `read_file` 功能重叠。
- **同类产品对比**：Claude Code 的 Skill 通过 `SkillTool` 元工具统一调用，避免污染工具列表；Kimi CLI 的技能通过 `/skill:name` 调用。
- **优化方案建议**：
  - 减少内置 Skill 与原生工具的功能重叠，避免模型困惑。
  - 对 Skill 工具增加 `category`、`tags`、`examples` 元数据，提升 schema 质量。
  - 考虑引入 `SkillTool` 元工具：模型调用 `use_skill(skill_id, args)`，由框架路由到具体 skill。
- **技术调研**：`SkillToolAdapter.kt` 可将 skill 转换为 tool，只需在转换时增强 schema。
- **为什么这么优化**：提升动态工具的可发现性和使用成功率，减少工具列表冗余。

### 6.12 工具输出格式与上下文管理

#### 6.12.1 统一截断标记与续读协议

- **优化背景**：不同工具的截断标记不一致：`read_file` 用 `truncated`/`original_length`，`run_command` 用 `stdout_truncated`/`stderr_truncated`，`grep_code` 用 `truncated` + `partial_scan_files`。
- **同类产品对比**：Claude Code 的截断通常提示“Use offset and limit to read specific portions”。
- **优化方案建议**：
  - 制定统一截断响应协议：`{ truncated: true, total_items?, returned_items?, next_offset?, hint? }`。
  - 对文本类工具，统一使用 `offset` + `limit` 续读。
- **技术调研**：可在 `ToolResult` 封装层中统一添加截断元数据。
- **为什么这么优化**：降低模型处理不同截断标记的认知负担，提升工具链一致性。

#### 6.12.2 在工具结果中嵌入 token 预算提示

- **优化背景**：当前 `get_context_remaining` 单独暴露上下文预算（`ContextToolHandlers.kt:17`），但其他工具结果不提示剩余预算。
- **同类产品对比**：Codex CLI 在状态栏显示 context-percent；Kimi CLI 也显示 context 使用百分比。
- **优化方案建议**：
  - 在 `ToolExecutor` 的 `postProcess` 阶段，若结果较大，追加 `context_cost_estimate` 和 `remaining_context_hint`。
  - 让模型感知“这次读取消耗了多少上下文”，从而主动分页。
- **技术调研**：可使用已有的 `ContextBudgetManager` 估算 token。
- **为什么这么优化**：帮助模型在长会话中更高效地分配上下文，避免早期浪费。

### 6.13 可观测性、测试与工程化

#### 6.13.1 为每个工具补充单元测试与性能基准

- **优化背景**：`HighValueTools.kt` 注释明确提到“每个新工具至少 2 个单元测试”是验收标准，但报告范围内未验证是否全部覆盖。
- **同类产品对比**：Codex CLI 作为开源项目有大量集成测试；Claude Code 虽闭源但内部有完整测试。
- **优化方案建议**：
  - 为 `read_file`、`edit_file`、`apply_patch`（新增）、`grep_code`、`run_command` 等核心工具编写覆盖正常、边界、错误场景的单元测试。
  - 使用 JUnit + IntelliJ 轻量平台测试框架（`PlatformTestCase`、`LightPlatformCodeInsightTestCase`）。
  - 为搜索、索引建立性能基准（如 10万行项目搜索耗时）。
- **技术调研**：项目已有 `src/test/kotlin`，可扩展；`SymbolIndex` 已提供 `testFileProvider` 等测试注入点。
- **为什么这么优化**：工具链是 Agent 的“手脚”，稳定性直接决定用户体验；性能基准可防止回归。

#### 6.13.2 强化工具调用 Tracing 与审计

- **优化背景**：`ToolExecutor` 已集成 `ExecutionTracer` 和 `ToolAuditLog`（`ToolExecutor.kt:53-65`、`ToolExecutor.kt:119-131`），但审计内容较简单。
- **同类产品对比**：Claude Code Hooks 提供 `PreToolUse`/`PostToolUse` 完整事件，可重写参数、注入上下文。
- **优化方案建议**：
  - 在审计日志中记录完整输入参数（脱敏后）、输出摘要、耗时、截断情况。
  - 提供 `AgentHooks` 扩展点，允许用户/企业自定义 `PreToolUse`/`PostToolUse` 回调。
  - 支持将 tracing 数据导出为 OpenTelemetry 格式。
- **技术调研**：已有 `AgentHooks.kt` 和 `ExecutionTracer`，可在此基础上扩展。
- **为什么这么优化**：满足企业级审计、合规、故障排查需求。

---


## 7. 结论与优先级路线图

### 7.1 核心结论

1. **CodeSage 工具链已具备工程化基础**，但在“编辑体验、搜索性能、代码分析深度、命令流式化、记忆智能化、MCP 治理”六个方面与头部产品存在代差。
2. **文件操作和 Shell 是用户最高频路径**，应优先补齐行号、apply_patch、ripgrep、流式输出等能力。
3. **代码分析是差异化竞争力所在**，应充分利用 IntelliJ PSI/索引，而非启发式正则，构建准确的调用图和语义搜索。
4. **子 Agent 与 MCP 是未来扩展重点**，需要更精细的权限、上下文隔离和工具发现机制。
5. **可观测性与测试是工程化底线**，应在快速补功能的同时建立单元测试和性能基准。

### 7.2 优先级路线图

#### P0（1-2 个月，影响最大）

| 优化项 | 对应节 | 预期收益 |
|--------|--------|----------|
| `read_file` 增加行号输出 | 6.1.1 | 降低编辑失败率 |
| 修复 `offset/limit` 全量加载问题 | 6.1.2 | 提升大文件稳定性 |
| 新增 `apply_patch` 工具 | 6.2.1 | 多文件编辑效率与成功率大幅提升 |
| `grep_code`/`search_code` 接入 ripgrep | 6.3.1 | 搜索性能 10-100 倍提升 |
| 统一 `run_command`/`exec_shell`，支持流式/后台 | 6.4.1 / 6.4.2 | 用户体验与长任务支持 |
| `run_tests`/`run_linter` 结构化输出 | 6.8.1 / 6.8.2 | 自动修复能力质变 |

#### P1（2-4 个月，巩固竞争力）

| 优化项 | 对应节 | 预期收益 |
|--------|--------|----------|
| 新增 `glob` 工具 | 6.3.2 | 文件定位效率 |
| PSI 调用图替代正则 | 6.5.1 | 代码分析准确性 |
| `semantic_search` 向量召回 | 6.3.3 | 语义搜索可用 |
| `SymbolIndex.fuzzySearch` 前缀树优化 | 6.3.4 | 符号搜索性能 |
| 记忆向量召回 + 自动摘要 | 6.9.1 / 6.9.2 | 长期记忆智能化 |
| `delegate_task` 结构化结果 | 6.10.1 | 子 Agent 可用性 |
| MCP 工具数量上限与权限过滤 | 6.11.1 / 6.11.2 | 安全与上下文控制 |

#### P2（4-6 个月，差异化创新）

| 优化项 | 对应节 | 预期收益 |
|--------|--------|----------|
| PDF/图片/Notebook 读取 | 6.1.3 | 多模态扩展 |
| `git_worktree` 与并行 Agent 集成 | 6.6.3 | 真正的并行 Agent 隔离 |
| 动态页面抓取 | 6.7.2 | 网页文档处理 |
| OpenTelemetry 导出与 Hooks | 6.13.2 | 企业级可观测性 |
| ACP（Agent Client Protocol）支持 | 4.4.6 | IDE 生态扩展 |

### 7.3 最终建议

CodeSage 不应简单复制某一家产品的工具集，而应发挥 **IntelliJ 平台原生集成**的优势：

- 用 PSI/索引做准确的代码分析（别人难以复刻）。
- 用 VFS 保证文件操作与 IDE 状态同步。
- 在此基础上，补齐头部产品在**编辑结构化、搜索性能、命令流式化、记忆智能化**方面的短板。

建议下一阶段成立“工具体验专项”，按 P0 优先级先打通行号输出 → apply_patch → ripgrep 搜索 → 结构化测试/ lint 这一主链路，这将直接决定用户在日常编码任务中的完成率和满意度。

---

*报告完成。所有引用均基于 2026-06-13 对 CodeSage 源码及公开资料的调研。*
