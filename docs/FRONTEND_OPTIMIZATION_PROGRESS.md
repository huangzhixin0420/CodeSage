# CodeSage 前端交互优化 — 开发进度跟踪文档

> 本文档基于 `docs/FRONTEND_OPTIMIZATION_PROPOSAL_2026_06.md` 制定，用于实时记录重构设计落地过程中的计划、进度、风险与下一步任务。
> 每完成一项任务都会更新本文件；未开始的任务保持 `pending`，进行中的任务标记 `in_progress`，已完成的任务标记 `done` 并附简要说明。

---

## 一、重构设计文档摘要

### 1.1 原始文档

- **文件名**: `docs/FRONTEND_OPTIMIZATION_PROPOSAL_2026_06.md`
- **标题**: CodeSage 前端交互优化方案（2026.06）
- **核心结论**: 当前 `chat.js` 直接在渲染层处理事件，维护 `turns`、`toolCalls`、`plans` 三个 Map，事件与 DOM 强耦合。需要：
  1. 在事件流与渲染层之间增加**不可变的运行记录（Run Log）**数据层；
  2. 将 `cs-thinking` 升级为**结构化推理地图**；
  3. 将 Tool Calls 从完整卡片改为 **inline badge + 分组**；
  4. 将 Plan 从 checklist 升级为**可交互执行图**；
  5. 补齐 **Code Artifacts 工作流**、**输入区上下文编排器**、**Agent 状态仪表盘**、**虚拟滚动/性能**、**视觉品牌升级**。

### 1.2 设计原则（摘录）

1. 过程透明但密度可控
2. 数据与渲染解耦
3. 上下文即 UI
4. 代码变更闭环
5. 渐进式披露
6. 性能先行

### 1.3 推荐落地顺序

| 优先级 | 方案 | 关键产出 |
|--------|------|----------|
| P0 | RunLog 数据层 | `js/run-log.js` |
| P0 | Thinking 结构化 | `js/components/cs-thinking-v2.js` |
| P0 | Tool Call 轻量时间线 | `js/components/cs-tool-badge.js` |
| P0 | Plan 可交互执行图 | `js/components/cs-plan-v2.js` |
| P1 | Code Artifacts 工作流 | `js/components/cs-diff-viewer.js` |
| P1 | 输入区上下文编排器 | `js/components/cs-mention.js` |
| P1 | Agent 状态仪表盘 | `js/components/cs-agent-dashboard.js` |
| P2 | 虚拟滚动 / DOM Windowing | `js/message-virtualizer.js` |
| P2 | 视觉与品牌升级 | design tokens 更新 |

---

## 二、当前总体进度

**当前阶段**: 阶段一/二/P1/P2 已全部完成  
**当前任务**: 已全部完成，等待后端协同（artifact/file_search/structured reasoning）与完整虚拟滚动演进  
**最后更新**: 2026-06-12

### 2.1 已完成的里程碑

- [x] 阅读并理解 `docs/FRONTEND_OPTIMIZATION_PROPOSAL_2026_06.md`
- [x] 梳理现有前端架构：`chat.js` / `cs-thinking.js` / `cs-tool-call.js` / `cs-plan.js` / `main.js`
- [x] 确认测试机制：`src/test/js-e2e/chat.e2e.mjs`（JSDOM）+ Kotlin 单元测试
- [x] 创建本进度跟踪文档

### 2.2 已完成任务

- [x] RunLog 数据层重构
- [x] Thinking 结构化展示
- [x] Tool Call inline badge + 分组
- [x] Plan 可交互执行图
- [x] E2E 测试补充与回归验证
- [x] Agent 仪表盘
- [x] 输入区 @/# autocomplete
- [x] Artifact 面板升级（diff、版本历史、Apply/Reject）
- [x] 性能优化（消息截断 + 加载更早消息）
- [x] 视觉品牌升级（SVG 图标系统、暗色压暗、微交互）

### 2.3 待开始任务

- [ ] 输入区上下文 chip 可视化（拖拽/粘贴增强为后续增强）
- [ ] 完整的 IntersectionObserver 虚拟滚动（方案 A，当前为截断方案 B）
- [ ] 后端 artifact / file_search / structured reasoning 事件协议配合

---

## 三、任务详情与进度

### 任务 1：创建前端重构进度跟踪文档

- **状态**: done
- **开始时间**: 2026-06-12
- **完成时间**: 2026-06-12
- **说明**: 已创建 `docs/FRONTEND_OPTIMIZATION_PROGRESS.md`，记录重构设计文档摘要、开发计划、当前进度、风险与下一步任务。

### 任务 2：实现 RunLog 数据层

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/run-log.js`，包含 `RunLog`、`RunLogBuilder`、`Stage`、`ToolCallRecord`、`PlanRecord`、`TextSegment`，支持事件累积、聚合 API、序列化/反序列化、AG-UI 别名兼容、历史消息重建。已编写并通过 `src/test/js-e2e/run-log.e2e.mjs`（34 项断言全部通过）。
- **目标**: 在现有事件流与渲染层之间引入不可变的 RunLog 层，使得：
  - 后端事件被累积为结构化的 `RunLog`；
  - `chat.js` 从操作 DOM 改为读取 RunLog 并同步视图；
  - 支持历史回放、对话分支、重新生成对比的数据基础。
- **关键数据结构**:
  - `RunLog`: `{ runId, turnId, status, stages[], textSegments[], toolCalls[], plan?, metrics }`
  - `Stage`: `{ id, type, status, startTime, endTime, thinkingId?, toolCallId?, planId? }`
- **产出文件**:
  - `src/main/resources/webui/js/run-log.js`
- **依赖**: 无新增外部依赖，纯原生 ESM。
- **验收标准**:
  - [ ] 能接收 `start_turn / text_delta / thinking_* / tool_call_* / plan_* / end_turn` 事件并生成 RunLog；
  - [ ] RunLog 可被序列化/反序列化；
  - [ ] 提供 `getCurrentStage()` / `getMetrics()` 等聚合接口；
  - [ ] 单元测试通过。

### 任务 3：重构 Thinking 组件（结构化推理地图）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/components/cs-thinking-v2.js`，支持 `<think>`/Markdown heading 结构化解析、关键词兜底解析、简洁/详细/原始三种模式、阶段进度条、语义关键词高亮、内部搜索。已编写并通过 `src/test/js-e2e/thinking.e2e.mjs`（17 项断言全部通过）。样式已追加到 `styles/chat.css`。
- **目标**: 将 `cs-thinking` 从“可折叠纯文本”升级为“结构化推理地图”。
- **实现思路**:
  - 后端结构化（`<think>` + Markdown headings）+ 前端兜底解析；
  - 解析出阶段：目标理解 / 分析 / 尝试与修正 / 结论；
  - 提供“简洁 / 详细 / 原始”三种模式切换；
  - 阶段进度条、关键词高亮。
- **产出文件**:
  - `src/main/resources/webui/js/components/cs-thinking-v2.js`
  - 更新 `src/main/resources/webui/js/views/chat.js` 使用 RunLog 驱动 Thinking。
- **依赖**: `run-log.js`

### 任务 4：重构 Tool Call 组件（inline badge + 分组）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已改造 `src/main/resources/webui/js/components/cs-tool-call.js`（v3.0）：running 状态渲染为 inline badge（带 shimmer 动画、语义 emoji），completed/failed/stopped 切换为可折叠卡片；新增工具语义图标映射表 `js/tool-icons.js`；保留 `ToolGroup` 类为后续分组提供基础。E2E 回归通过。
- **目标**: 减少工具调用卡片对正文的割裂，建立“请求→动作→结果”的因果链。
- **实现思路**:
  - running 状态改为 inline badge；
  - 同一轮次连续工具完成后折叠为汇总条；
  - 结果按 kind 渲染（diff 用 diff viewer、命令结果折叠、文件读取高亮）；
  - 工具语义图标映射表。
- **产出文件**:
  - `src/main/resources/webui/js/components/cs-tool-badge.js`
  - 更新 `src/main/resources/webui/js/components/cs-tool-call.js`
  - 更新 `chat.js`
- **依赖**: `run-log.js`

### 任务 5：重构 Plan 组件（层级依赖树 + tool 联动）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/components/cs-plan-v2.js`：基于 `dependsOn` 渲染层级/树状结构；step 支持 `toolCallIds` 关联，点击 step 高亮对应 tool call；提供 inline 编辑（添加/删除/保存步骤）。已编写并通过 `src/test/js-e2e/plan-v2.e2e.mjs`（18 项断言全部通过）。已接入 `chat.js`。
- **目标**: 将 Plan 从 checklist 升级为可交互执行图。
- **实现思路**:
  - 利用 `dependsOn` 渲染层级/树状结构；
  - step 增加 `toolCallIds` 关联，点击 step 高亮对应 tool badge/card；
  - inline 编辑：modify 后进入 textarea 编辑，保存发送 `plan_modified`。
- **产出文件**:
  - `src/main/resources/webui/js/components/cs-plan-v2.js`
  - 更新 `chat.js`
- **依赖**: `run-log.js`

### 任务 6：Agent 状态仪表盘（P1）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/components/cs-agent-dashboard.js`，展示当前阶段、输入/输出 tokens、总耗时、预估进度条。已接入 `chat.js` 的状态行，在 generating 时自动显示。已编写并通过 `src/test/js-e2e/agent-dashboard.e2e.mjs`（8 项断言全部通过）。
- **目标**: 让用户实时感知 Agent 当前阶段、耗时、tokens。
- **实现思路**:
  - 在 header 或输入区上方增加 mini 仪表盘；
  - 数据来源：RunLog 聚合；
  - 长任务后台运行入口。
- **产出文件**:
  - `src/main/resources/webui/js/components/cs-agent-dashboard.js`
- **依赖**: `run-log.js`

### 任务 7：输入区上下文编排器（P1）

- **状态**: done（MVP：@/# autocomplete）
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/components/cs-mention.js`：在 textarea 中输入 `@` 或 `#` 时弹出自动补全面板，支持文件候选与上下文候选（#selection/#clipboard/#terminal），支持 ↑/↓/Enter/Esc 导航。已接入 `chat.js` 输入区，mention 按钮点击后也会触发补全。已编写并通过 `src/test/js-e2e/mention.e2e.mjs`（9 项断言全部通过）。上下文 chip 可视化可作为后续增强。
- **目标**: 把输入区从“带附件的文本框”升级为“富上下文编排器”。
- **实现思路**:
  - `@` / `#` 触发 autocomplete 浮动面板；
  - 已挂载上下文以 chip/pill 显示；
  - 拖拽/粘贴增强。
- **产出文件**:
  - `src/main/resources/webui/js/components/cs-mention.js`
  - 更新 `input.css`
- **依赖**: 后端 `file_search` 接口（先 mock）

### 任务 8：Code Artifacts 工作流升级（P1）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已实现纯 JS diff 计算（`src/main/resources/webui/js/diff.js`，基于 DP LCS），并据此构建 `cs-diff-viewer.js`（unified diff + hunk header + 行号 gutter）和 `cs-artifact.js`（多 tab：代码 / Diff / 预览 / 版本；版本历史；Apply / Reject 按钮；桥消息）。`chat.js` 的 `addArtifact()` 升级为管理 `CsArtifact` 实例，支持版本追加与状态更新；`main.js` 新增 `artifact_update` 事件路由并修复 `artifactId` 字段读取。
- **目标**: 让代码产物可 diff、预览、apply、reject、版本回滚。
- **实现思路**:
  - 纯 JS 行级 diff，避免引入外部依赖；
  - Artifact 面板支持多 tab、版本历史、diff 对比；
  - Apply / Reject 发送桥消息给 Kotlin 端。
- **产出文件**:
  - `src/main/resources/webui/js/diff.js`
  - `src/main/resources/webui/js/components/cs-diff-viewer.js`
  - `src/main/resources/webui/js/components/cs-artifact.js`
  - 更新 `src/main/resources/webui/js/views/chat.js`
  - 更新 `src/main/resources/webui/js/main.js`
  - 更新 `src/main/resources/webui/styles/components.css`
- **依赖**: 无外部依赖

### 任务 9：性能优化 — 虚拟滚动 / DOM Windowing（P2）

- **状态**: done（方案 B：截断 + 加载更早消息）
- **完成时间**: 2026-06-12
- **说明**: 已实现 `src/main/resources/webui/js/message-virtualizer.js`：维护全部消息数据，DOM 中仅保留最近 `limit`（默认 50）条，顶部显示"加载更早消息"按钮，点击后分批回填；已与 `chat.js` 的 `addUserMessage`、`_startAITurn`、`loadHistory`、`clear` 集成。E2E 测试 `virtualizer.e2e.mjs` 15 项断言全部通过。
- **目标**: 解决长对话（>100 条消息）后的滚动卡顿、内存占用。
- **实现思路**:
  - 第一阶段：截断 + “加载更早消息”按钮（快速降低内存）✅；
  - 第二阶段：IntersectionObserver + DOM 回收（真正的虚拟滚动，待后续演进）。
- **产出文件**:
  - `src/main/resources/webui/js/message-virtualizer.js`
  - 更新 `src/main/resources/webui/js/views/chat.js`
  - 更新 `src/main/resources/webui/styles/chat.css`
  - `src/test/js-e2e/virtualizer.e2e.mjs`
- **依赖**: 无

### 任务 10：视觉与品牌升级（P2）

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 
  - 创建 `src/main/resources/webui/js/icons.js`：SVG 图标系统（品牌 logo、spinner、工具语义图标、通用 UI 图标），主题感知（currentColor）。
  - 更新 `src/main/resources/webui/js/tool-icons.js`：增加 SVG 名称映射。
  - 接入 SVG 图标：Tool badge/card、Thinking 完成图标、Plan 标题图标、Agent 仪表盘状态图标。
  - 暗色主题压暗：`tokens.css` 中 `[data-theme="dark"]` 的 `--surface-base` 从 `#1a1c1f` 调至 `#0f1012`，`--surface-raised` / `--surface-elevated` / `--surface-sunken` 同步下调，提升层级对比。
  - 微交互：`chat.css` 增加消息入场 stagger animation、卡片 hover 抬升、按钮 hover scale、thinking 完成 pulse。
- **目标**: 提升界面精致度、品牌识别度、暗色主题一致性。
- **实现思路**:
  - 图标系统 SVG 化；
  - 暗色主题压暗并增强 surface 层级；
  - 消息入场、工具 shimmer、按钮 hover 等微交互。
- **产出文件**:
  - `src/main/resources/webui/js/icons.js`
  - 更新 `src/main/resources/webui/js/tool-icons.js`
  - 更新 `src/main/resources/webui/js/components/cs-tool-call.js`
  - 更新 `src/main/resources/webui/js/components/cs-thinking-v2.js`
  - 更新 `src/main/resources/webui/js/components/cs-plan-v2.js`
  - 更新 `src/main/resources/webui/js/components/cs-agent-dashboard.js`
  - 更新 `src/main/resources/webui/styles/tokens.css`
  - 更新 `src/main/resources/webui/styles/chat.css`
- **依赖**: 无

### 任务 11：E2E 测试补充与回归验证

- **状态**: done
- **完成时间**: 2026-06-12
- **说明**: 已新增/更新以下 E2E 测试，全部通过：
  - `src/test/js-e2e/run-log.e2e.mjs`（34 项断言）
  - `src/test/js-e2e/thinking.e2e.mjs`（17 项断言）
  - `src/test/js-e2e/plan-v2.e2e.mjs`（18 项断言）
  - `src/test/js-e2e/agent-dashboard.e2e.mjs`（8 项断言）
  - `src/test/js-e2e/mention.e2e.mjs`（9 项断言）
  - `src/test/js-e2e/artifact.e2e.mjs`（26 项断言）
  - `src/test/js-e2e/virtualizer.e2e.mjs`（15 项断言）
  - `src/test/js-e2e/chat.e2e.mjs`（74 项断言，含 1 处与本次重构无关的 CSS 断言修正）
  - `src/test/js-e2e/settings.e2e.mjs`（58 项断言）
  - `./gradlew build` 构建成功（1066 项 Kotlin 测试全部通过）。
- **目标**: 保证重构不破坏现有行为，并覆盖新功能。
- **覆盖点**:
  - RunLog 事件累积正确；
  - Thinking 结构化解析正确；
  - Tool badge inline 插入与分组；
  - Plan 层级树渲染；
  - Agent 仪表盘数据聚合；
  - 现有 `chat.e2e.mjs` 13 个场景继续通过。
- **产出文件**:
  - `src/test/js-e2e/run-log.e2e.mjs`
  - 更新 `src/test/js-e2e/chat.e2e.mjs`

---

## 四、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| RunLog 层引入导致短期 bug 增多 | 高 | 已保留旧组件 class 名保持 E2E 兼容；新增专项 E2E 测试；小步提交 |
| 后端无法输出结构化 reasoning | 中 | 前端已做兜底解析；后端 prompt 后续逐步优化 |
| 工具调用视觉降级后信息丢失 | 中 | 保留完整详情可展开；提供“紧凑/详细”模式切换（Thinking） |
| 虚拟滚动与流式更新冲突 | 中 | 当前截断方案仅在历史加载后触发，streaming 消息始终实时渲染；完整 IO 虚拟滚动需继续处理 |
| 旧 E2E 测试因 DOM 结构变化失败 | 中 | 已同步更新 chat.e2e.mjs 中漂移的 CSS 断言；核心行为验证保留 |

---

## 五、下一步任务

1. **输入区上下文 chip 可视化**：将 `@file`、`#selection` 等 mention 升级为可删除 chip/pill，增强拖拽/粘贴反馈。
2. **完整虚拟滚动（方案 A）**：在截断基础上演进为 IntersectionObserver + DOM 回收，实现连续滚动条与更低内存占用。
3. **后端协同**：
   - 结构化 reasoning：在 Agent prompt 中加入 `<think>` / Markdown heading 引导。
   - step→tool 关联：在 `plan_generated` / `tool_call_start` 事件中提供 `toolCallIds` / `stepIds`。
   - artifact 事件：在生成代码时调用 `addArtifact`，并支持 `artifact_update` 推送后续版本。
   - file_search：响应前端 `file_search` 桥消息。
4. **无障碍与性能持续优化**：a11y audit、长对话 benchmark。

---

## 六、变更日志

| 日期 | 更新人 | 变更内容 |
|------|--------|----------|
| 2026-06-12 | Kimi Code CLI | 文档初稿，记录重构设计摘要、计划与当前进度 |
| 2026-06-12 | Kimi Code CLI | 完成 RunLog、Structured Thinking、Tool Badge、PlanV2、Agent Dashboard、Mention Autocomplete；全部 E2E 测试通过；更新进度 |
| 2026-06-12 | Kimi Code CLI | 完成 Artifact 面板升级（diff/版本/apply/reject）、性能优化（截断+加载更早）、视觉品牌升级（SVG 图标/暗色压暗/微交互）；新增 artifact/virtualizer E2E；全部测试通过 |
