# Changelog

## [Unreleased] — Agent 工具能力优化

### P0 工具能力优化（基于 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md`）

- `read_file` / `read_multiple_files` 新增 `line_numbers` 参数，可额外返回 `cat -n` 风格的 `content_with_line_numbers`，便于模型直接引用真实行号。
- 新增 `apply_patch` 工具：支持 Codex 风格的结构化 patch，一次调用可完成多文件、多位置的 Update / Add / Delete 操作；解析或应用失败时不写盘，避免半成品。
- 修复大文件带 `offset/limit` 分页时的全量加载问题：`read_file` 对超过阈值且带分页参数的文件走 memory-mapped 流式分块，避免一次性 `String(contentsToByteArray())` 导致 OOM；默认大文件读取也额外返回 `total_lines`。
- `grep_code` / `search_code` 优先接入 ripgrep（`rg --json`），解析后返回统一格式；当 `rg` 不可用、执行失败或用户显式传入 `exclude_dirs`/`include_hidden` 时回退到原有 VFS 扫描。
- 统一 `run_command` 与 `exec_shell`：`run_command` 默认超时 120s、最大 600s；`exec_shell` 标记为 deprecated 并内部转发到 `run_command`。
- `run_command` 新增 `run_in_background` 参数，可启动长期进程并返回 `process_id`；新增 `kill_process` 与 `read_process_output` 工具管理后台进程。
- `run_command` 新增 `stream_output` 参数（6.4.3）：同步命令执行期间实时发射 `command_output_delta` 事件，前端可实时渲染 stdout/stderr；最终仍返回完整结果给模型。后台命令与沙箱命令暂保持非流式。
- 新增 `git_push` 工具：自动检测当前分支，无上游跟踪分支时使用 `git push -u`，成功返回 `pushed`/`branch`/`remote`/`upstream_set`。
- `run_tests` 返回结构化测试结果：执行后扫描 Gradle `build/test-results/test/*.xml` 与 Maven `target/surefire-reports/*.xml`，返回 `tests[]` 列表（含 `classname`/`name`/`status`/`time`/`message`/`details`）与汇总计数；无 XML 时退回 stdout 摘要。
- `run_linter` 返回结构化问题列表：执行后解析 Checkstyle XML、`eslint-report.json`、flake8 JSON，返回 `issues[]`（含 `file`/`line`/`column`/`severity`/`message`/`rule`）及 `issue_count`/`error_count`/`warning_count`；无报告且退出码非零时返回错误输出。
- 新增 `glob` 工具（P1 6.3.2）：按 glob 模式（如 `src/**/*.kt`）批量定位文件/目录，支持 `**` 递归、`include_dirs`、`exclude_dirs`、`max_results` 截断，默认排除 node_modules/.git/build 等生成目录。
- `delegate_task` 返回结构化元数据（P1 6.10.1）：tool result 从纯文本改为 JSON，包含 `success`/`cancelled`/`result`/`files`/`blockers`/`iterations_used`/`tools_used`/`completed_tool_calls`/`session_id`/`raw_output`；UI 仍通过 `SubAgentComplete` 事件展示自然语言总结。
- `delegate_task` 递归深度可配置（P1 6.10.2）：新增 `max_depth` 参数（范围 1-5，默认 2），`SubAgentExecutor` 使用实例级 `maxDepth` 做拦截，prompt 中显示实际深度/上限；越界时直接返回结构化错误。
- `delegate_task` 工具白名单（P1 6.10.3）：新增 `allowed_tools` / `denied_tools` 参数，`toolset` 过滤后再取交集/去黑名单，`delegate_task` 默认始终保留（除非显式 denied）；过滤为空或禁用委托时返回明确错误；prompt 注入实际可用工具名与限制说明。
- 符号搜索增强（P1 6.3.3 / 6.3.4）：
  - 6.3.3 `semantic_search` 向量语义召回：复用本地 128 维 embedding，比较查询与符号名称/文档的向量相似度，与关键词分融合排序。
  - 6.3.4 `SymbolIndex.fuzzySearch` 前缀索引优化：按 camelCase/下划线构建 token→symbols 索引，支持多 token 前缀匹配与评分排序；保留子串匹配兜底。
- 记忆系统增强（P1 6.9.1 / 6.9.2）：
  - 6.9.1 向量记忆与语义召回：为每条记忆生成 128 维本地 embedding 并落盘 SQLite；`memory_search` 与 `prefetch` 融合 FTS5 关键词排名和向量余弦相似度，提升语义相关记忆召回。
  - 6.9.2 自动会话摘要与关键事实提取：`onSessionEnd` 通过 `SessionSummarizer` 提取偏好/决策/技术栈/文件路径等关键事实，自动写入记忆表；会话摘要改为结构化文本。
- 代码分析 PSI 调用图（P1 6.5.1 / 6.5.2）：
  - 6.5.1 `CodeInsightExecutor.collectCalleesForSymbol` 改为基于 PSI 树遍历（`PsiMethodCallExpression` / `KtCallExpression` / `PsiNewExpression`），通过反射提取被调用符号名与精确行号，替代原先扫描 50 行文本的正则启发式；`find_usages` 的 PSI 元素定位增加 `typeHint` 偏好匹配，降低误定位概率。
  - 6.5.2 新增独立 `find_callers` / `find_callees` 工具：`CodeInsightExecutor` 暴露 `findCallers()` / `findCallees()`，返回结构化列表（`file_path` / `line` / `column` / `caller_symbol` / `callee_symbol`），并在 `ToolRegistry` / `CodeInsightUnifiedTools` / `ToolGuardrails` 安全白名单中注册。
- MCP 工具治理（P1 6.11.1 / 6.11.2）：
  - 6.11.1 工具数量上限与动态发现：`MCPServerConfig` / `McpSection` 新增 `maxToolsPerServer`（默认 40）与 `McpServerEntry.maxTools`，`MCPServerManager` 按 allow/deny 过滤后再截断到上限；被隐藏的工具通过新增 `mcp_tool_search` 工具按 serverId/query 动态查询。
  - 6.11.2 权限规则前置过滤：`McpServerEntry` 支持 `allowedTools` / `deniedTools`（`*` / `?` 通配符），在工具进入 `SkillRegistry` 与 LLM 视野前完成过滤，deny 优先于 allow。
- Git 工具结构化（P1 6.6.2）：`git_diff` 返回结构化 diff（`files[]` / `hunks[]` / 行级 `add/remove/context` / `old_line_number` / `new_line_number` / 统计），新增可选 `include_raw` 参数保留原始 diff；新增 `GitDiffParser` 处理 modified / added / deleted / renamed / copied / binary 等 diff 类型。
- HTTP 工具安全增强（P1 6.7.1）：`http_request` 新增 `max_size_bytes`（默认 1MB）限制内存中响应体大小，超出时 `truncated=true` 并提示用 `output_file`；新增 `output_file` 参数可把完整响应流式写入磁盘，避免大文件撑爆内存与上下文。
- 跨语言符号索引（P1 6.5.3）：`SymbolIndex` 扩展索引文件类型至 Vue/Svelte/JSX/TSX/JSON/YAML/SQL/Markdown；新增 `CrossLanguageSymbolExtractor` 对配置文件提取顶层 key、SQL 提取 CREATE 对象、Markdown 提取标题、Vue/Svelte 提取组件名，提升多语言项目覆盖率。
- 新增 `multi_edit` 工具（P1 6.2.2）：一次调用对同一文件提交多个 `old_string`/`new_string` 编辑，先批量校验唯一性与存在性，全部通过后再原子写回；任一失败整体回滚，避免半成品文件。
- 编辑工具智能重试与模糊匹配（P1 6.2.3）：`edit_file` 与 `multi_edit` 新增可选 `fuzzy_match` 参数；当 `old_string` 不唯一时，自动用前后最多 2 行上下文去歧，仍失败则返回候选位置（行号 + 片段）辅助模型修正；同时忽略行首/行尾空白差异，降低缩进变化导致的失败率。
- 新增 `read_document` 工具（P0 6.1.3）：支持多模态文档读取，包括图片（PNG/JPG/JPEG/WEBP/GIF/BMP，返回 base64 + mime_type + 尺寸）、PDF（Apache PDFBox 提取每页文本，支持 `page` 单页与 `max_pages` 限制）、Jupyter Notebook（解析 cells 列表与元数据）。文件大小受 `max_size_bytes` 限制，PDF 每页文本受 `max_chars_per_page` 限制；headless/测试场景自动绕过 VFS，走本地文件路径。

## [Unreleased] — 预算/轮次管理下线

预算/轮次管理相关代码已整体下线(方案尚未成熟,等待重新设计)。

**删除的文件**
- `src/main/kotlin/com/codesage/agent/core/TaskBudget.kt`
- `src/main/kotlin/com/codesage/agent/core/IterationBudget.kt`
- `src/main/kotlin/com/codesage/ide/settings/BudgetSettingsConfigurable.kt`
- `src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`
- `src/test/kotlin/com/codesage/agent/core/TaskBudgetTest.kt`
- `src/test/kotlin/com/codesage/agent/core/IterationBudgetTest.kt`
- `docs/BUDGET_ROUND_REDESIGN.md`

**主要变更**
- `AgentStreamEvent` 移除 `BudgetStatus` / `BudgetExhausted` / `BudgetExtended` 事件
- `PluginConfig` 移除 `maxIterationsPerTask` / `maxTokensPerTask` / `maxDurationSecondsPerTask` /
  `enableIterationBudget` / `enableTokenBudget` / `enableTimeBudget` / `budgetWarningThreshold` /
  `subAgentBudgetRatio` / `allowContinueOnExhaustion` 配置
- `SettingsSchema.AgentSection` 移除 `maxIterations` / `maxTokens` / `maxDurationSeconds` /
  `budgetWarningThreshold` / `subAgentBudgetRatio` / `allowContinueOnExhaustion` 字段
- `AgentCore` 移除 `currentBudgetConfig` / `lastExhaustedBudget` 字段和 `continueConversation` /
  `canContinue` 方法
- `AgentConfig` 移除 `budgetConfig` 字段
- `EnhancedAgentLoop` 移除 `TaskBudget` 注入和分层预算状态机,主循环简化为 `while (!interrupted)`
- `SubAgentExecutor` 移除 `parentBudget` 参数和子 Agent 预算配置(保留 `maxIterations` 作为 LLM 工具调用参数)
- `JCEFChatPanel` 移除 `continue_task` 消息处理和 `onContinueBudget` 回调
- `AgentToolWindowPanel` 移除 `onContinueBudget` 注入
- `EventRouter` / `EventHistory` / `ChatPanel` / `AgentTurnPanel` 移除所有 `Budget*` 事件分发
- 前端 `chat.html` / `styles/chat.css` / `js/views/chat.js` 移除预算状态徽标、预算耗尽面板、继续执行按钮
- 前端 `js/views/settings.js` / `js/i18n.js` Agent 设置页移除预算相关表单字段
- `SettingsBridgeHandler` / `MigrationBridgeHandler` / `SettingsMigrations` 移除相关字段映射

---
# CodeSage UI/UX 重构 — CHANGELOG

> 完整执行了 `docs/UI_UX_REDESIGN_PROPOSAL.md` + `docs/UI_UX_REDESIGN_EXECUTION_PLAN.md` 的全部 5 个 Phase。

---

## 🎯 总体成果

| 指标 | 重构前 | 重构后 |
|---|---|---|
| chat.html 单文件 | 3407 行 | 已拆分为多文件 |
| Web 组件 | 0 | 12 个(自研,无 React 依赖) |
| 设计 token | 散落 | 200+ CSS 变量集中 |
| 主题 | 仅手动切换 | auto/light/dark + 跟随系统 |
| 工具调用展示 | 仅名字 | 入参 + 7 种 kind 结果 + diff + Apply |
| Plan 展示 | `<pre>` 文本 | 5 态 Todo + 进度 + 折叠 |
| 配置存储 | IDE XML 捆绑 | `~/codesage/settings.json` 独立 |
| 设置入口 | IDE 设置 3 个 Tab | WebView 内 7 大分组 |
| 命令面板 | 无 | Cmd+K 14 个命令 |
| 国际化 | 0 字符串外提 | zh-CN / en-US 完整 |
| 响应式 | 0 断点 | 1024 / 768 / 480 三档 |

---

## 📋 Phase 1 — 基础(4 天)

### 拆分 chat.html(3407 行 → 多文件结构)

```
src/main/resources/webui/
├── index.html                 (167 行 — 极简骨架)
├── styles/                    (10 个 CSS 文件,~2200 行)
│   ├── tokens.css            设计 token + 亮/暗主题
│   ├── base.css              重置 + 滚动条
│   ├── animations.css        14 类动画 keyframes
│   ├── layout.css            header / mode / 模型选择
│   ├── chat.css              消息 / 思考 / 工具 / Plan
│   ├── input.css             输入区 / 自动完成
│   ├── markdown.css          Markdown 渲染 + diff
│   ├── sidebar.css           会话侧边栏
│   ├── settings.css          Settings 视图
│   ├── components.css         cs-toast / cs-spinner / cs-modal / cs-palette
│   └── polish.css            虚拟滚动 / 骨架 / walkthrough / a11y
├── js/                        (15+ 文件,~3500 行)
│   ├── main.js               入口 + 错误边界
│   ├── bridge.js             Java 桥
│   ├── event-bus.js          事件总线(rAF 批合并)
│   ├── state.js              状态 + localStorage
│   ├── i18n.js               zh-CN / en-US 完整
│   ├── utils.js              工具函数
│   ├── markdown.js           marked 懒加载 + 代码块增强
│   ├── views/chat.js         主对话编排
│   ├── views/settings.js     Settings 视图
│   └── components/
│       ├── cs-toast.js
│       ├── cs-spinner.js
│       ├── cs-thinking.js    (Phase 2 — 三态思考)
│       ├── cs-tool-call.js   (Phase 2 — 工具卡片)
│       ├── cs-plan.js        (Phase 2 — Plan)
│       ├── cs-inline-alert.js
│       ├── cs-modal.js
│       ├── cs-command-palette.js
│       ├── cs-sidebar.js
│       └── cs-walkthrough.js
└── lib/                      (highlight.js / marked / font-awesome 自托管)
```

### JCEFChatPanel 事件路由重构

- **Before**:100+ 行 `when` 块在 `JCEFChatPanel.kt`
- **After**:抽出 `EventRouter.kt`(293 行),用 `register<T> { e, turnId -> ... }` 模式
- JCEFChatPanel 从 1121 行 → 388 行
- HTML 入口切换到 `index.html`
- 26 个事件类型映射,新增事件只需 1 行

### 其他
- 错误边界:`window.error` + `unhandledrejection` → toast + Kotlin 日志
- 自托管 vendor(消除 CDN)
- 全局快捷键:Esc / Cmd+Shift+C

---

## 📋 Phase 2 — 核心体验升级(5 天)

### 思考过程可视化(三态)

- **running**:三点呼吸 + 计时,默认展开
- **completed**:绿勾 + 自动 1.5s 后折叠,留 1 行摘要
- **collapsed**:一行 chip,点击展开

### 工具调用卡片(`cs-tool-call`)

- **8 种 kind**:text / code / diff / command / json / list / error / subagent
- 状态机:queued / running / completed / failed / confirm
- 入参 + 结果展示(支持 diff 红绿行、命令 exit code)
- Apply to editor 按钮(edit_file 类)

### Plan 列表(`cs-plan`)

- 5 态 step:pending / running / completed / failed / blocked
- 进度 `1/4 · 25%` 显示
- 整体可折叠,默认展开
- Approve / Edit / Reject 按钮(可选)

### 其他
- Token 估算
- 文件引用 tag
- 思考完成 + turn_complete 自动展开
- 工具 delta 流式追加
- 时间计数器(每个 turn 独立)

---

## 📋 Phase 3 — 输入与会话(3 天)

### 命令面板(`cs-command-palette`)

- Cmd+K 唤起 / Cmd+/ 唤起
- 14 个内置命令
- 模型子面板(动态生成)
- 模糊匹配(label / hint / keywords)
- 上下方向键 + Tab + Enter + Esc

### 主题 auto/follow system

- `auto | light | dark` 三态
- 切换循环:auto → light → dark → auto
- `prefers-color-scheme` 监听自动切换
- 图标反映实际主题

### 会话侧边栏(`cs-sidebar`)

- 默认折叠,260px 展开
- 顶栏:Logo + 新建按钮 + 折叠 toggle
- 搜索框
- 时间分组:今天 / 昨天 / 近 7 天 / 更早
- hover 显示操作菜单
- inline 重命名(Enter 确认,Esc 取消)
- inline 删除确认(无需弹窗)
- 底栏:设置 / 命令面板

### 输入区增强

- 拖拽文件 → 图片预览 / 自动 `@` 引用
- 粘贴图片
- 草稿持久化(per session,localStorage)

### 模型选择器升级

- 顶部搜索框
- Provider 分组 + 状态点
- 选中态 ✓ 标记
- 切换 toast 反馈

### 响应式

- 1024:sidebar 变 overlay
- 768:紧凑模式
- 480:全宽 sidebar

### 全局快捷键

| 键 | 动作 |
|---|---|
| Cmd/Ctrl+K | 命令面板 |
| Cmd/Ctrl+/ | 命令面板(同 K) |
| Cmd/Ctrl+N | 新会话 |
| Cmd/Ctrl+B | 折叠侧边栏 |
| Cmd/Ctrl+Shift+T | 切换思考 |
| Cmd/Ctrl+Shift+C | 复制最后 turn |
| Esc | 停止生成 / 关闭 modal |

---

## 📋 Phase 4 — 配置体系重构(4 天)

### settings.json v1 Schema

```json
{
  "$schema": "https://codesage.dev/schemas/settings/v1.json",
  "version": 1,
  "providers": [ProviderEntry, ...],
  "defaults": { providerId, model, mode, ... },
  "agent": { maxIterations, maxDurationSeconds, ... },
  "ui": { theme, showThinking, fontSize, ... },
  "editor": { autoAttachSelection, maxContextFiles, ... },
  "shortcuts": { send, commandPalette, ... },
  "mcp": { servers: [...] },
  "advanced": { logLevel, telemetryEndpoint, customCss }
}
```

### SettingsRepository

- 文件路径:`~/.codesage/settings.json`(跨平台)
- **原子写**:`.tmp` + `Files.move(ATOMIC_MOVE)`
- **损坏恢复**:解析失败 → 自动 `.bak.<ts>` + 回退默认
- **文件监听**:Java NIO `WatchService`,150ms debounce
- **实时保存**:UI 修改 500ms debounce 自动写
- **`SharedFlow<SettingsFile>`** 广播变更

### SettingsMigrations

- 旧 `CodeSagePlugin.xml` → 新 settings.json
- API Key 引用保留(`keychain:<providerId>`)
- 默认 Provider / Model / Mode 完整迁移
- 模型 context size 启发式推测

### Settings 视图(7 大分组)

| 分组 | 内容 |
|---|---|
| ⚡ 通用 | 语言 / 自动更新 / 遥测 |
| 🤖 Models | Provider 卡片 + 默认模型 |
| ⚙ 预算 & Agent | 5 滑块 + 2 开关 |
| 🎨 UI | 主题 / 字号 / 动画 |
| ⌨ 快捷键 | 8 个快捷键 + 录制重绑 |
| 🔌 MCP | (Phase 5 占位) |
| 🛠 高级 | 日志 / 遥测端点 / 自定义 CSS |

### IDE 菜单

- Tools → CodeSage → 打开设置文件夹
- Tools → CodeSage → 编辑 settings.json
- Tools → CodeSage → 重载设置

### 协议(Kotlin ↔ JS)

```
JS → Kotlin:
  settings_get
  settings_update { settings }
  settings_reload
  settings_open_folder
  settings_open_file

Kotlin → JS:
  settings_data { settings, path }
  settings_saved
  settings_error { message }
```

---

## 📋 Phase 5 — 打磨与回归(3-5 天)

### i18n 框架

- 完整 zh-CN + en-US 字符串(140+ key)
- 嵌套 key + 插值 `t("key", { name: "Alice" })`
- 运行时切换
- `<html lang="...">` 自动同步

### 虚拟滚动(简单但有效)

- `content-visibility: auto` + `contain-intrinsic-size`
- 浏览器原生支持,>200 消息仍 60fps
- 无需 JS 虚拟列表实现

### 骨架屏

- shimmer 动画(1.5s 循环)
- line / circle / card 3 种骨架
- 整页 app-skeleton 容器

### 首次启动 Walkthrough

- 4 步引导:智能对话 / 工具与文件 / 快捷键 / 设置
- 键盘可达(→/←/Space/Esc)
- localStorage 记忆
- 任意时刻通过命令面板重启

### ARIA / 无障碍

- skip link(`跳到主内容`)
- `:focus-visible` 全局焦点环
- `prefers-reduced-motion: reduce` 退化
- `prefers-contrast: more` 加粗焦点
- ARIA labels on modals / lists / inputs

### 14 类微交互(已全部落地)

- 消息进入 / 思考折叠 / 工具卡片展开 / Sidebar 展开
- 主题切换(防白闪) / Toast / 按钮按下
- 预算 % 数字滚动 / 光标闪烁 / 加载 shimmer
- 错误 shake / Sub-agent 完成 / 复制反馈 / 拖拽文件

### 性能

- `transform: translateZ(0)` 触发 GPU
- `will-change: transform` on 动画元素
- `scroll-behavior: smooth` on 消息容器
- 流式事件 rAF 批合并(EventBus)

---

## 🏗️ 新增 / 变更文件统计

| 类别 | 新增 | 修改 |
|---|---|---|
| Web UI 文件 | 28 | 0 |
| Web UI 总行数 | ~4500 | — |
| Kotlin 新增 | 6 | — |
| Kotlin 修改 | 3 | — |
| Kotlin 新增行数 | ~1100 | — |
| 文档 | 4 | — |
| `chat.html` | 备份为 `docs/archive/chat.html.v0.bak` | — |

---

## ⚠️ 已知限制（留给未来）

> 2026-06-03 P5.1-P5.5 全部完成，以下 5 项已从限制列表中移除：

1. ~~**API Key 编辑 UI**~~ — **P5.1 已完成**：新增 `set_api_key` / `test_provider` 协议，`ProviderBridgeHandler.kt` 负责处理；`settings.js` 提供完整 modal（name / type / baseUrl / apiKey / models），API Key 存 PasswordSafe，其余字段存 settings.json；"测试连接" 按钮发 GET {baseUrl}/v1/models，返回延迟 / HTTP 状态。
2. ~~**旧 IDE Configurable 迁移向导 UI**~~ — **P5.2 已完成**：`MigrationBridgeHandler.kt` 处理 `legacy_migration_check` / `_run` / `_skip`；启动 1.5s 后自动检测，弹 cs-modal 向导（预览、跳过、迁移三按钮），完成后推送 `settings_data` 立即刷新 UI。
3. ~~**MCP 视图**~~ — **P5.3 已完成**：`settings.js` 的 mcp group 替换为完整 CRUD（增 / 删 / 改 / 启用切换 / inline 确认删除），add/edit modal 支持 stdio / http / websocket 三种 transport 及环境变量。
4. ~~**图片附件真实通道**~~ — **P5.4 已完成**：`ImageAttachment(id, mime, dataUrl, name)` 数据类；`send_message` payload 新增 `images[]`；Kotlin 端解析后注入到消息文本（markdown image 引用），适配多模态模型；`AgentToolWindowPanel` 检查 model 是否支持 vision 并 toast 提示。
5. ~~**cs-sub-agent 嵌套 turn**~~ — **P5.5 已完成（Plan A）**：`EventRouter` 的 `SubAgentComplete` 转为结构化 `result: { kind: "subagent", subagent: { task, toolset, elapsedMs, output, success } }`；`cs-tool-call.js` 渲染增强：meta 信息（toolset / task / 状态 / 耗时）+ 输出预览 + "查看完整输出" 展开 / 收起。

---

## 🚀 升级建议

```bash
# 1. 拉取 feature 分支
git checkout feature/uiux-redesign

# 2. 编译验证
./gradlew compileKotlin test

# 3. 打包
./gradlew buildPlugin

# 4. IDE 安装
#    Settings → Plugins → ⚙ → Install Plugin from Disk → 选择 build/distributions/*.zip

# 5. 首次启动会展示 4 步 walkthrough
# 6. 顶栏齿轮 → Settings 查看新配置中心
# 7. Cmd+K 试试命令面板
```

---

## 📦 0.1.0 发布说明（v0.1.0-uiux-p5）

**代号**: CodeSage UI/UX 重构完整版
**发布日期**: 2026-06-03
**Tag**: `v0.1.0-uiux-p5`
**Git**: `feature/uiux-redesign` @ `67d5e48`

### 🌟 亮点

- **全面重构的 Web UI**:chat.html (3407 行) 拆为 28 个文件 / 10 个 CSS / 15 个 JS,自研 cs-* 组件库
- **设计 token 体系**:200+ CSS 变量,亮/暗主题 + 跟随系统
- **配置独立化**:`~/codesage/settings.json` 替代 IDE XML 捆绑,SettingsRepository 提供原子写 / 损坏恢复 / 实时监听
- **13 类 cs-* 组件**:thinking / tool-call (8 kind) / plan (5 态) / modal / toast / command-palette / sidebar / walkthrough 等
- **完整 i18n**:zh-CN + en-US 140+ key,运行时切换
- **14 类微交互**:消息进入 / 思考折叠 / 工具卡片展开 / 主题切换防白闪 / 拖拽反馈 等

### 📊 数据

| 指标 | 重构前 | 0.1.0 |
|---|---|---|
| `chat.html` 单文件 | 3407 行 | 拆为多文件 |
| `JCEFChatPanel.kt` | 1121 行 | 388 行 |
| Web 组件 | 0 | 13 个自研 |
| 设计 token | 散落 | 200+ CSS 变量 |
| 测试用例 | ~600 | 929 |
| 已知限制(P5) | 5 项 | **0** ✓ |

### ✅ 商业闭环能力

- **API Key 可在 UI 改**:不再依赖 IDE 旧 Configurable 或 PasswordSafe 工具
- **连通性测试**:一键验证 Provider 配置,带延迟 / HTTP 状态 / 错误信息
- **配置迁移向导**:新用户从旧 IDE 配置无痛升级
- **MCP 完整 CRUD**:stdio / http / websocket 三种 transport,inline 确认防误删
- **多模态就绪**:拖拽图片即发,Kotlin 端解析后适配 GPT-4o / Claude-3 / Gemini 等

### 🚀 升级路径

从旧版本升级 CodeSage:

1. 升级前:旧 `CodeSagePlugin.xml` 中的 providers / 模型 / 预算 都会被自动检测
2. 启动后 1.5s:自动弹迁移向导(可跳过),点 "迁移" 后所有数据写入 `~/codesage/settings.json`
3. 旧 XML 保留作 fallback,新设置生效优先级最高
4. API Key 自动从 PasswordSafe 读取,无需重新输入

### 🐛 已知问题(下个迭代)

- `legacy_migration_run` 完成后 settings.json 写入但旧 PluginConfig 未清理(预留回退窗口)
- 图片附件走 markdown 引用,极少数模型可能因 base64 长度超限拒绝
- SubAgent 嵌套 turn 仅 Plan A(无实时子 turn 嵌套渲染),需升级到 Plan B

### 🙏 致谢

- Phase 1-5 的所有参与同事
- IntelliJ Platform Plugin SDK
- kotlinx.serialization / OkHttp / marked / highlight.js

— 0.1.0 发布,CodeSage 团队

---

## 📋 P5.1-P5.5 增量变更（2026-06-03）

### 新增 Kotlin 文件

| 文件 | 行数 | 职责 |
|---|---|---|
| `ProviderBridgeHandler.kt` | ~200 | set_api_key / test_provider 协议处理 |
| `MigrationBridgeHandler.kt` | ~270 | 旧 IDE 配置迁移向导协议 |
| `ImageAttachment.kt` | ~25 | 图片附件数据类 |

### 新增测试

| 文件 | 测试数 | 覆盖 |
|---|---|---|
| `ProviderBridgeHandlerTest.kt` | 6 | set_api_key 错误路径、test_provider host 不可达、requestId 透传 |
| `MigrationBridgeHandlerTest.kt` | 2 | handle 路由正确性 |
| `ImageAttachmentTest.kt` | 3 | data class 行为 |
| `EventRouterSubAgentTest.kt` | 3 | SubAgentStart/Complete 转换、elapsedMs 计时 |

总计 **929 个测试,0 失败**(全量)。

### 修改文件

- `JCEFChatPanel.kt`:`handleJSMessage` 加 `set_api_key` / `test_provider` / `legacy_migration_*` 分支;`messageCallback` 签名扩到 `(String, List<ImageAttachment>)`;新增 `parseImages()`
- `EventRouter.kt`:`SubAgentComplete` 转结构化 result(携带 task/toolset/elapsedMs)
- `SettingsActions.kt`:`OpenFileDescriptor` 修正参数顺序
- `SettingsBridgeHandler.kt`:`openFile` 修正,JsonElement 解析修复
- `PluginConfig.kt`:暴露 `promptRole` / `autoSaveEnabled` getter
- `AgentToolWindowPanel.kt`:适配新 `onSendMessage` 签名;加 `isCurrentModelSupportsVision()`

### 修改 Web 文件

- `js/views/settings.js`:Provider 编辑 modal、MCP CRUD、迁移向导、消息桥接
- `js/views/chat.js`:消息桥接委派、`_pendingImages` 图片附件流
- `js/components/cs-tool-call.js`:`_renderSubagent` 增强(Plan A)
- `js/main.js`:启动时 `settings_get` + `legacy_migration_check`
- `styles/settings.css`:Provider 表单 + 密码框眼睛切换 + 测试结果样式
- `styles/chat.css`:SubAgent 结果布局

### 新增消息类型

**JS→Kotlin**:`set_api_key` / `test_provider` / `legacy_migration_check` / `legacy_migration_run` / `legacy_migration_skip`

**Kotlin→JS**:`set_api_key_result` / `test_provider_result` / `legacy_migration_preview` / `legacy_migration_done` / `legacy_migration_skipped` / `legacy_migration_error`

### 手动测试场景

1. **P5.1 API Key 编辑**:`Settings → Models` 点编辑 Provider → 改 name/baseUrl/apiKey → 保存 → 重启 IDE 后 API Key 仍在(因存 PasswordSafe)
2. **P5.1 测试连接**:点 "测试连接" → 几秒后看到延迟 / 错误信息
3. **P5.2 迁移向导**:`Tools → CodeSage → 打开设置文件夹`,在 IDE 设置中添加任意 Provider → 重启 CodeSage → 启动 1.5s 后弹迁移向导
4. **P5.3 MCP CRUD**:`Settings → MCP` → 点添加 → 选 stdio / http → 填好 → 保存
5. **P5.4 图片附件**:拖拽图片到输入框 → 看到预览 → 发送 → Kotlin 端 message 包含 markdown image 引用
6. **P5.5 SubAgent 视图**:触发 SubAgent → 卡片显示 toolset / task / 状态 / 耗时 → 点 "查看完整输出" 展开
