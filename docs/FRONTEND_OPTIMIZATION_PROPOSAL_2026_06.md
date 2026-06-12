# CodeSage 前端交互优化方案（2026.06）

> 基于 `docs/FRONTEND_INTERACTION_REVIEW_2026_06.md` 的 review 结论，结合行业主流实践、开源项目与协议标准，输出可落地的优化方案。

---

## 一、调研方法与参考资料

### 1.1 调研范围

| 方向 | 搜索关键词 | 主要收获 |
|------|-----------|---------|
| Agent UI 设计原则 | AI agent design principles reasoning planning tool calling 2025 | Anthropic "Building effective agents"、IBM/Microsoft survey paper、ReAct paradigm |
| 开源聊天 UI | OpenWebUI / LibreChat / LobeChat / Chatbot UI | Artifacts、forking、prompt library、multi-modal、side-by-side model comparison |
| IDE AI 助手 | Cursor Composer / Copilot Chat / Continue.dev | inline diff、@context、tool call timeline、per-change accept/reject |
| Agent-UI 协议 | AG-UI protocol / Agent User Interaction Protocol | 事件家族、state snapshot/delta、reasoning events、tool call events |
| 推理过程可视化 | structured thinking / reasoning UI / `<think>` parsing | 阶段化解析、XML/Markdown 标签、Open WebUI reasoning models |
| 性能优化 | virtual scrolling chat messages DOM windowing | IntersectionObserver、overscan、可变高度、长对话性能 |
| Diff 渲染 | react-diff-view / git-diff-view / inline diff | unified/split view、token system、syntax highlight、hunk actions |
| 国内产品 | Kimi / 通义灵码 / Tongyi Lingma | `#` 上下文标签、智能问答、代码解释流程图 |

### 1.2 核心参考项目/协议

1. **Anthropic — Building Effective Agents**（2024/2025）
   - 核心观点：Agent = LLM + tool calling + feedback loop；显式展示 planning steps 保证透明；先简单 workflow，必要时再引入 agent。
   - 启示：CodeSage 的 Plan/Tool/Reasoning 应该向用户透明，但不要让用户被细节淹没。

2. **AG-UI Protocol（CopilotKit, 2025）**
   - 事件分类：Lifecycle / Text / Tool Call / State / Reasoning / Activity / Custom。
   - 关键事件：`STATE_SNAPSHOT` / `STATE_DELTA`（JSON Patch）、`REASONING_*`、`TOOL_CALL_START/ARGS/END/RESULT`。
   - 启示：CodeSage 当前事件协议可逐步向 AG-UI 对齐，降低未来接入多框架的成本。

3. **Cursor Composer / Windsurf Cascade**
   - 共同模式：左侧对话、右侧文件/差异/命令面板；Agent 改代码后产生可接受/拒绝的 diff hunk；工具调用 inline badge 化。
   - 启示：CodeSage 应把"聊天"和"代码变更"两条线解耦，右侧 Artifacts 面板承担 diff/apply 工作流。

4. **LibreChat / Open WebUI**
   - LibreChat：ChatGPT-like UI、Artifacts（HTML/React 实时渲染）、fork 对话、prompt library。
   - Open WebUI：pipeline 架构、reasoning 模型支持、`<think>` 标签解析、RAG、MCP。
   - 启示：原生 ESM 架构下同样可以实现模块化插件化，但需先补齐"数据模型"和"渲染协议"。

5. **Continue.dev**
   - 开源、本地优先、context providers（`@codebase`, `@file`, `@terminal`）、slash commands。
   - 启示：输入区 `@` 提及不只是文件选择器，而是一套上下文变量系统。

6. **react-diff-view / git-diff-view**
   - 支持 split/unified、token system（高亮+inline diff+注释）、hunk actions、web worker。
   - 启示：CodeSage 的 diff 不应自己写 pre 拼接，应引入成熟的 diff 渲染模型。

---

## 二、总体设计原则

基于调研，提炼出 6 条指导后续改造的原则：

1. **过程透明但密度可控**
   - 用户需要知道 Agent 在做什么，但不需要每个工具调用都占满一屏。
   - running 状态要轻量，completed 状态可折叠，失败/危险状态要醒目。

2. **数据与渲染解耦**
   - 后端事件协议向 AG-UI 靠拢，前端渲染层消费统一的事件流。
   - 避免把渲染细节（如 HTML 字符串）耦合进后端事件。

3. **上下文即 UI**
   - 用户输入的 `@file`、`#selection`、图片、代码块引用，都应该是可见、可编辑、可删除的 UI 元素。

4. **代码变更闭环**
   - 生成的代码必须能 diff、预览、apply、reject、版本回滚，而不是只能复制。

5. **渐进式披露**
   - 默认展示摘要，用户点击/悬停再展开详情。减少认知负荷。

6. **性能先行**
   - 长对话必须引入虚拟滚动或 DOM windowing，避免消息数增长后卡顿。

---

## 三、核心方案：对话过程数据模型重构（P0）

### 3.1 现状问题

当前 `chat.js` 直接在渲染层处理事件，维护 `turns`、`toolCalls`、`plans` 三个 Map，事件与 DOM 强耦合：
- `text_delta` 直接修改 DOM。
- `thinking_start/update/complete` 直接创建 `Thinking` 组件。
- `tool_call_start/complete` 直接创建 `ToolCall` 组件。

这导致：
- 历史回放困难（`loadHistory` 只能伪造事件）。
- 无法支持对话分支、重新生成对比。
- 工具/计划/思考之间没有关联。

### 3.2 目标架构：引入"运行记录（Run Log）"数据层

在现有事件流与渲染层之间增加一个不可变的**运行记录（Run Log）**数据层：

```
Kotlin EventStream
    │
    ▼
RunLogBuilder（按 turn/run 累积事件，生成结构化记录）
    │
    ▼
ChatView（根据 RunLog 渲染/更新 DOM）
    │
    ▼
DOM
```

RunLog 的核心数据结构：

```typescript
interface RunLog {
  runId: string;
  turnId: string;
  status: "running" | "completed" | "failed" | "stopped";
  stages: Stage[];        // 思考、工具调用、文本生成等阶段
  textSegments: TextSegment[];
  toolCalls: ToolCallRecord[];
  plan?: PlanRecord;
  metrics: {
    thinkingMs: number;
    toolMs: number;
    generationMs: number;
    tokensIn: number;
    tokensOut: number;
  };
}

interface Stage {
  id: string;
  type: "thinking" | "tool_call" | "plan" | "text" | "confirmation";
  status: "running" | "completed" | "failed";
  startTime: number;
  endTime?: number;
  // 关联到具体组件数据
  thinkingId?: string;
  toolCallId?: string;
  planId?: string;
}
```

### 3.3 为什么这样设计

| 对比维度 | 旧架构（事件→DOM） | 新架构（事件→RunLog→DOM） |
|---------|------------------|-------------------------|
| 历史回放 | 需要伪造事件序列 | 直接复用 RunLog 渲染 |
| 对话分支 | 难以实现 | 复制 RunLog 后修改即可 |
| 重新生成对比 | 只能替换 DOM | 保留多个 RunLog 版本 |
| 工具/计划/思考关联 | 无关联 | 统一 stage 时间线 |
| 状态仪表盘 | 需要从 DOM 反推 | 直接从 RunLog 聚合 |
| 测试 | 难测试 | 可单元测试纯数据转换 |

### 3.4 向 AG-UI 协议对齐

建议逐步把 Kotlin → JS 的事件协议映射到 AG-UI 事件家族：

| CodeSage 当前事件 | AG-UI 对应 | 备注 |
|------------------|-----------|------|
| `thinking_start/update/complete` | `REASONING_START/MESSAGE_CONTENT/END` | 结构化思考 |
| `tool_call_start/delta/complete/error` | `TOOL_CALL_START/ARGS/END/RESULT` | 工具调用 |
| `plan_generated/approved/rejected/modified` | `STATE_SNAPSHOT/STATE_DELTA` + custom | 计划状态 |
| `text_delta` | `TEXT_MESSAGE_CONTENT` | 文本流 |
| `context_compressed` | `ACTIVITY_DELTA` / custom | 上下文压缩 |
| `error` | `RUN_ERROR` | 错误 |

**优势**：未来如果 CodeSage 要接入 LangGraph/Mastra/CrewAI 等框架，前端事件层几乎不用改。

---

## 四、核心方案：Thinking 结构化展示（P0）

### 4.1 目标

把 `cs-thinking` 从"可折叠的纯文本"升级为"结构化推理地图"。

### 4.2 实现方案

#### 方案 A：后端直接输出结构化 reasoning（推荐）

修改后端 Agent 的 prompt，让模型按固定格式输出 reasoning：

```xml
<think>
## 目标理解
...

## 分析
...

## 尝试与修正
...

## 结论
...
</think>
```

前端 `cs-thinking` 解析 heading，渲染成可折叠段落：

```html
<div class="thinking-card">
  <div class="thinking-header">...</div>
  <div class="thinking-body">
    <div class="thinking-section" data-section="goal">
      <div class="thinking-section-title">目标理解</div>
      <div class="thinking-section-content">...</div>
    </div>
    <div class="thinking-section" data-section="analysis">...</div>
    ...
  </div>
</div>
```

**优势**：结构化最准确，可精确控制阶段。
**劣势**：需要后端配合，对非 reasoning 模型需要兼容。

#### 方案 B：前端启发式解析（兜底）

如果后端无法输出结构化格式，前端在 `appendContent` 时实时解析文本：

```javascript
const SECTION_RE = /^(?:##?\s+|\*\*|\[|\()?\s*(目标|分析|尝试|修正|错误|结论|计划|所以|因此|决定|注意)\b/mi;
```

识别到阶段关键词后，自动插入 section 边界。

**优势**：不依赖后端，可立即落地。
**劣势**：解析不完美，需要持续调优。

#### 最终推荐：A + B 混合

- 后端尽量输出 Markdown heading 或 XML 标签。
- 前端同时做兜底解析，对未结构化内容按段落/换行拆分。
- 提供配置开关，让用户选择"简洁/详细/原始"三种模式。

### 4.3 关键交互

1. **阶段进度条**：thinking header 显示当前阶段（如"分析中…"）。
2. **关键词高亮**：对 `"错误"` `"修正"` `"所以"` `"决定"` 等词加语义色。
3. **与正文联动**：最终回答引用 thinking 时，hover 显示对应段落 tooltip。
4. **搜索框**：长 thinking 支持内部搜索。

### 4.4 对比

| 方案 | 实现成本 | 结构化准确度 | 兼容性 | 推荐度 |
|------|---------|------------|--------|--------|
| A. 后端结构化 | 中 | 高 | 中 | ⭐⭐⭐⭐⭐ |
| B. 前端解析 | 低 | 中 | 高 | ⭐⭐⭐⭐ |
| C. 只保留纯文本 | 无 | 低 | 高 | ⭐⭐ |

---

## 五、核心方案：Tool Calls 轻量时间线（P0）

### 5.1 目标

减少工具调用卡片对正文的割裂，建立"请求→动作→结果"的因果链。

### 5.2 实现方案

#### 5.2.1 Running 状态视觉降级

把 running 工具调用从完整卡片改为 inline badge：

```html
<span class="tool-badge running">
  <i class="tool-icon tool-icon-read"></i>
  <span class="tool-badge-text">Reading src/main/kotlin/Agent.kt</span>
  <span class="tool-badge-time">0.3s</span>
</span>
```

样式：
- 背景半透明、无边框、与正文行高一致。
- 图标按工具语义映射（读文件📄、写文件✏️、命令⚡、搜索🔍、MCP🔌）。

#### 5.2.2 工具链分组

同一轮次内连续调用的工具，完成后折叠为汇总条：

```html
<div class="tool-group-summary">
  <i class="fas fa-check-double"></i> 已执行 5 个工具 · 1.2s
  <button class="tool-group-expand">展开</button>
</div>
```

展开后显示轻量列表，再次点击才显示完整详情。

#### 5.2.3 结果展示增强

**diff 结果**：
- 不直接渲染为 `pre` 文本，而是调用 diff 渲染器。
- 支持 unified / split 切换。
- 显示 old/new 文件名、行号范围。
- 每个 hunk 支持 Apply / Reject。

**命令结果**：
- stdout/stderr 默认折叠，显示前 5 行 + "展开 N 行"。
- 支持复制单行、复制全部。
- exit code 非零时高亮。

**文件读取结果**：
- 显示文件路径 + 读取行号范围。
- 代码块支持语言高亮。

### 5.3 工具图标映射表

| 工具名模式 | 图标 | 颜色 | 语义 |
|-----------|------|------|------|
| `read_file`, `view_file` | 📄 | blue | 读取 |
| `edit_file`, `write_file`, `apply_diff` | ✏️ | green | 写入 |
| `run_command`, `exec_shell`, `bash` | ⚡ | yellow | 执行 |
| `search_code`, `grep`, `find_files` | 🔍 | purple | 搜索 |
| `mcp__*` | 🔌 | orange | MCP |
| `delegate_task`, `subagent` | 🤖 | teal | 子任务 |

### 5.4 对比

| 方案 | 视觉噪音 | 信息密度 | 实现成本 | 推荐度 |
|------|---------|---------|----------|--------|
| 保持当前重卡片 | 高 | 低 | 低 | ⭐⭐ |
| inline badge + 分组 | 低 | 高 | 中 | ⭐⭐⭐⭐⭐ |
| 右侧独立时间线面板 | 低 | 高 | 高 | ⭐⭐⭐⭐ |

---

## 六、核心方案：Plan 可交互执行图（P0）

### 6.1 目标

把 Plan 从 checklist 升级为可交互执行图，表达依赖关系，联动工具调用。

### 6.2 实现方案

#### 6.2.1 依赖关系可视化

利用 `dependsOn` 字段，渲染为简单的层级/树状结构：

```
□ 1. 分析代码结构
  └─ □ 2. 定位 bug 位置
       └─ □ 3. 编写修复
            └─ □ 4. 运行测试
```

样式：
- 使用 CSS pseudo-element 画竖线连接。
- 同级步骤用水平排列，依赖步骤用缩进。

#### 6.2.2 Plan 与 Tool 联动

每个 step 增加 `toolCallIds` 字段（后端提供），前端点击 step 时：
1. 高亮对应的 tool call badge/card。
2. 滚动到对应位置。
3. 显示 step 执行摘要（"通过 read_file 读取了 src/Agent.kt"）。

#### 6.2.3 Inline 编辑

Modify 按钮点击后，进入 inline 编辑模式：
- 步骤文字变 textarea。
- 支持拖拽排序。
- 新增/删除步骤。
- 保存后发送 `plan_modified` 事件。

### 6.3 对比

| 方案 | 依赖表达 | 交互性 | 实现成本 | 推荐度 |
|------|---------|--------|----------|--------|
| 当前 checklist | 无 | 低 | 低 | ⭐⭐ |
| 层级树 + 联动 | 中 | 高 | 中 | ⭐⭐⭐⭐⭐ |
| 完整图形化 DAG | 高 | 中 | 高 | ⭐⭐⭐ |

---

## 七、核心方案：Code Artifacts 工作流（P1）

### 7.1 目标

让代码产物从"可复制代码块"变成"可应用、可对比、可预览的工作流"。

### 7.2 实现方案

#### 7.2.1 后端接入 `addArtifact`

当前 `addArtifact` 没有调用点。需要：
- 当模型生成代码（尤其是 `edit_file`/`write_file`）时，同时发送 `artifact` 事件。
- Artifact 数据结构扩展：

```typescript
interface Artifact {
  id: string;
  title: string;        // 文件名
  language: string;
  content: string;      // 当前内容
  originalContent?: string; // 原始内容（用于 diff）
  version: number;
  versions: ArtifactVersion[];
  kind: "create" | "edit" | "preview" | "diff";
  status: "pending" | "applied" | "rejected";
}
```

#### 7.2.2 Artifact 面板升级

右侧 Artifacts 面板支持：
- 多 tab（类似 Claude Artifacts）。
- 版本历史（可查看 v1 → v2 → v3）。
- diff 对比（当前 vs 上一版本 / 当前 vs 磁盘文件）。
- Apply / Reject / Create File 按钮。
- 对 HTML/Markdown/SVG 提供 iframe 预览。

#### 7.2.3 消息内代码块增强

每个代码块增加上下文 action bar：
- 显示文件路径（如果后端提供）。
- Apply to Editor / Insert at Cursor / Create File / Copy。
- 如果是 diff，显示 Accept / Reject hunk。

### 7.3 推荐 diff 库

| 库 | 特点 | 是否适合 CodeSage |
|----|------|------------------|
| `react-diff-view` | 功能最全，支持 token system、web worker、inline diff | 不适合（无 React） |
| `git-diff-view` | React/Vue/Solid/Svelte 多端，GitHub 风格 | 不适合（依赖框架） |
| `diff-match-patch` + 自研渲染 | 轻量，可生成 character/line diff | 适合（纯 JS） |
| `jsdiff` + 自研 DOM | 成熟，可生成 patch，自定义渲染 | 适合（纯 JS） |

**推荐**：引入 `jsdiff`（Google 的 `diff-match-patch` 也可）做 diff 计算，自研 DOM 渲染，保持无框架依赖。

### 7.4 对比

| 方案 | 闭环程度 | 实现成本 | 与 IDE 集成 | 推荐度 |
|------|---------|---------|------------|--------|
| 仅代码块复制 | 低 | 低 | 弱 | ⭐⭐ |
| Artifact 面板 + apply | 中 | 中 | 强 | ⭐⭐⭐⭐ |
| 消息内 diff + 逐 hunk 接受 | 高 | 高 | 强 | ⭐⭐⭐⭐⭐ |

---

## 八、核心方案：输入区上下文编排器（P1）

### 8.1 目标

把输入区从"带附件的文本框"升级为"富上下文编排器"。

### 8.2 实现方案

#### 8.2.1 @ 提及自动补全

在 textarea 上方/下方浮动一个 autocomplete 面板：

```html
<div class="mention-popup">
  <div class="mention-group">文件</div>
  <div class="mention-item" data-type="file" data-path="src/Agent.kt">
    <i class="fas fa-file-code"></i>
    <span>src/Agent.kt</span>
    <span class="mention-hint">最近打开</span>
  </div>
  <div class="mention-group">上下文</div>
  <div class="mention-item" data-type="context" data-value="selection">
    <i class="fas fa-i-cursor"></i>
    <span>#selection</span>
    <span class="mention-hint">当前选中的代码</span>
  </div>
</div>
```

触发规则：
- 输入 `@` 或 `#` 触发。
- 支持 `↑/↓/Enter/Esc` 导航。
- 搜索时调用后端 `file_search`。
- 选中后插入 chip，而不是纯文本。

#### 8.2.2 上下文 chip

已挂载的上下文以 pill/chip 形式显示在 textarea 上方：

```html
<div class="context-pill">
  <i class="fas fa-file-code"></i>
  <span>src/Agent.kt</span>
  <button class="context-pill-remove"><i class="fas fa-xmark"></i></button>
</div>
```

- 文件 chip 可点击预览前 N 行。
- 选区 chip 可 hover 查看代码片段。
- 超出 token 限制时变红并提示。

#### 8.2.3 拖拽/粘贴增强

- 拖拽文件到输入区时，整个输入容器高亮为 drop zone。
- 粘贴图片后立即显示大图预览（而非 28px 小缩略图）。
- 支持直接从 IDE 编辑器拖拽选区到输入区，自动插入 `#selection`。

### 8.3 对比

| 方案 | 上下文可见性 | 输入效率 | 实现成本 | 推荐度 |
|------|------------|---------|----------|--------|
| 当前文本 @ | 低 | 低 | 低 | ⭐⭐ |
| chip + autocomplete | 高 | 高 | 中 | ⭐⭐⭐⭐⭐ |
| 独立上下文面板 | 高 | 中 | 高 | ⭐⭐⭐⭐ |

---

## 九、核心方案：Agent 状态仪表盘（P1）

### 9.1 目标

让用户实时感知 Agent 当前阶段、耗时、tokens。

### 9.2 实现方案

在 header 或输入区上方增加 mini 仪表盘：

```html
<div class="agent-dashboard">
  <span class="agent-stage">
    <i class="fas fa-spinner spin"></i> 执行命令
  </span>
  <span class="agent-metric" title="本轮输入 tokens">↑ 1.2k</span>
  <span class="agent-metric" title="本轮输出 tokens">↓ 3.4k</span>
  <span class="agent-metric" title="总耗时">⏱ 12.3s</span>
  <span class="agent-progress">
    <span class="agent-progress-fill" style="width: 45%"></span>
  </span>
</div>
```

数据来源：RunLog 聚合。
- 当前阶段：取最后一个 running stage 的类型。
- tokens：后端在 `turn_complete` 或阶段性事件中提供。
- 耗时：前端计时 + 后端校准。

### 9.3 长任务后台运行

当 Agent 执行长命令时：
- 提供"后台运行"按钮，收起对话窗口但保持运行。
- 完成后通过 IDE notification / toast 提醒。
- IDE 任务栏或 tab badge 显示进度。

### 9.4 对比

| 方案 | 状态感知 | 实现成本 | 对用户体验提升 | 推荐度 |
|------|---------|---------|--------------|--------|
| 仅状态栏文字 | 低 | 低 | 低 | ⭐⭐ |
| mini 仪表盘 | 中 | 中 | 高 | ⭐⭐⭐⭐⭐ |
| 完整进度页面 | 高 | 高 | 中 | ⭐⭐⭐ |

---

## 十、性能优化：虚拟滚动 / DOM Windowing（P2）

### 10.1 目标

解决长对话（>100 条消息）后的滚动卡顿、内存占用问题。

### 10.2 实现方案

#### 方案 A：IntersectionObserver + DOM 回收（推荐）

保持消息数据全量，但只保留可见消息在 DOM 中：

```javascript
class MessageVirtualizer {
  constructor(container, renderFn) {
    this.container = container;
    this.renderFn = renderFn;
    this.visibleRange = [0, 0];
    this.pool = []; // DOM 节点池
    this.observer = new IntersectionObserver(
      (entries) => this._onIntersection(entries),
      { root: container, threshold: 0 }
    );
  }
  // ...
}
```

关键逻辑：
1. 顶部和底部各放一个占位 div，总高度 = 所有消息估算高度之和。
2. 可见区域内的消息渲染到占位 div 之间。
3. 消息滚出视野后，DOM 节点回收到 pool，数据保留在内存。
4. 高度估算：根据消息类型（文本/工具/计划）预设默认高度，实际渲染后通过 ResizeObserver 校准。

#### 方案 B：只保留最近 N 条 + "加载更早消息"

简单实现：
- 默认只渲染最近 50 条。
- 滚动到顶部时显示"加载更早消息"按钮。
- 点击后加载前 50 条。

**优势**：实现简单，风险低。
**劣势**：不是真正的虚拟滚动，滚动条不连续。

#### 最终推荐：先 B 后 A

- 第一阶段用方案 B 快速解决内存问题。
- 第二阶段用方案 A 实现真正的虚拟滚动。

### 10.3 对比

| 方案 | 滚动体验 | 内存占用 | 实现复杂度 | 推荐度 |
|------|---------|---------|-----------|--------|
| 当前全量 DOM | 差 | 高 | 低 | ⭐⭐ |
| 截断 + 手动加载 | 中 | 低 | 低 | ⭐⭐⭐⭐ |
| IntersectionObserver 虚拟滚动 | 优 | 低 | 高 | ⭐⭐⭐⭐⭐ |

---

## 十一、视觉与品牌升级（P2）

### 11.1 目标

提升界面精致度、品牌识别度、暗色主题一致性。

### 11.2 具体方向

1. **图标系统升级**
   - 为工具/计划/思考设计 SVG 图标（不依赖 Font Awesome 单色图标）。
   - 图标支持动态状态（running 旋转、completed 打勾、failed 警告）。

2. **暗色主题压暗**
   - `surface-base` 从 `#1a1c1f` 降到 `#0f1012` 或 `#121418`。
   - 增加 surface 层级对比（base/raised/elevated 差值从 6% 提到 10%）。

3. **微交互**
   - 消息入场 stagger animation。
   - 工具 badge running 时的 shimmer 效果。
   - 按钮 hover 的 scale/elevation 变化。

4. **品牌签名**
   - 设计 CodeSage 专属 loading/spinner（如旋转的 sage leaf）。
   - thinking 完成后的图标用品牌色脉冲。

5. **响应式增强**
   - 1024px 以下：sidebar 变 overlay。
   - 768px 以下：隐藏模型选择器文字，只保留图标。
   - 480px 以下：全屏输入框、简化 header。

---

## 十二、方案对比总表

| 问题域 | 推荐方案 | 备选方案 | 不推荐的方案 | 关键依赖 |
|--------|---------|---------|-------------|---------|
| 数据模型 | 引入 RunLog 层 | 直接扩展现有 Map | 保持事件→DOM | 后端事件协议扩展 |
| Thinking | 后端结构化 + 前端兜底解析 | 仅前端解析 | 纯文本无处理 | prompt 工程、解析器 |
| Tool Calls | inline badge + 分组 + 语义图标 | 右侧时间线面板 | 保持重卡片 | 工具语义映射表 |
| Plan | 层级树 + tool 联动 + inline 编辑 | 完整 DAG 图 | 当前 checklist | 后端 step→tool 关联 |
| Artifacts | 启用 addArtifact + diff + 版本 | 仅代码块增强 | 不启用 | 后端 artifact 事件 |
| 输入上下文 | chip + autocomplete | 独立上下文面板 | 当前文本 @ | 文件搜索接口 |
| Agent 状态 | mini 仪表盘 | 完整进度页 | 仅状态栏文字 | RunLog 聚合 |
| 性能 | IntersectionObserver 虚拟滚动 | 截断 + 手动加载 | 全量 DOM | 高度估算算法 |
| 视觉 | 图标系统 + 暗色压暗 + 微交互 | 换肤系统 | 保持现状 | 设计资源 |

---

## 十三、实施路线与里程碑

### 阶段一：数据层与核心呈现重构（4 周）

| 周 | 任务 | 产出 |
|----|------|------|
| 1 | 引入 RunLog 数据层；重构 chat.js 事件处理 | `run-log.js`、chat.js 适配 |
| 2 | Thinking 结构化；Tool Call inline badge 化 | `cs-thinking-v2.js`、`cs-tool-badge.js` |
| 3 | Plan 层级树 + tool 联动；工具图标映射 | `cs-plan-v2.js`、工具映射表 |
| 4 | diff 渲染升级；Artifact 面板激活 | `cs-diff-viewer.js`、后端 artifact 事件 |

### 阶段二：输入与上下文（2 周）

| 周 | 任务 | 产出 |
|----|------|------|
| 5 | @/# autocomplete；上下文 chip | `cs-mention.js`、上下文 pill |
| 6 | 拖拽/粘贴反馈；prompt 模板 | drop zone 动画、prompt library |

### 阶段三：状态与性能（2 周）

| 周 | 任务 | 产出 |
|----|------|------|
| 7 | Agent 仪表盘；tokens/耗时显示 | `cs-agent-dashboard.js` |
| 8 | 虚拟滚动 / DOM windowing | `message-virtualizer.js` |

### 阶段四：视觉与高级功能（持续）

| 周 | 任务 | 产出 |
|----|------|------|
| 9-10 | 对话分支、重新生成对比 | branching UI |
| 11-12 | 品牌图标、暗色压暗、微交互 | design tokens 更新 |
| 13+ | 无障碍、性能持续优化 | a11y audit、benchmark |

---

## 十四、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| RunLog 层引入导致短期 bug 增多 | 高 | 先在一个独立分支跑 e2e，逐步回滚机制 |
| 后端无法输出结构化 reasoning | 中 | 前端兜底解析，后端 prompt 逐步优化 |
| 工具调用视觉降级后信息丢失 | 中 | 保留完整详情可展开，用户可切换"紧凑/详细"模式 |
| 虚拟滚动与流式更新冲突 | 高 | 单独处理 streaming 消息，确保最新消息始终渲染 |
| IDE 主题变化与暗色调整冲突 | 低 | 增加 theme sync 测试 |

---

## 十五、成功指标

| 指标 | 当前 | 目标（3 个月后） | 测量方式 |
|------|------|----------------|---------|
| 长对话（100+ 消息）滚动 FPS | <30 | >55 | Chrome DevTools Performance |
| 工具调用视觉占用高度 | 1 个卡片 ~80px | inline badge ~24px | UI 截图测量 |
| 代码 apply 完成率 | 低（Artifacts 未启用） | >60% 的代码块可直接 apply | 后端埋点 |
| 用户输入 @ 使用频率 | 低 | 提升 3 倍 | 前端埋点 |
| 暗色主题用户满意度 | 中 | 提升 20% | 用户反馈/问卷 |

---

## 十六、结论

本方案的核心思路是：**先把"对话过程数据"从 DOM 中解耦出来，建立结构化的 RunLog 层；再围绕 RunLog 重新设计 Thinking、Tool Calls、Plan、Artifacts 的呈现方式；最后补齐输入上下文、Agent 仪表盘、性能和视觉短板。**

这不是一次简单的前端样式改造，而是一次**从"聊天 UI"到"Agent 工作区"的架构升级**。借鉴 AG-UI 协议、Cursor/Claude/Windsurf 的交互范式、以及 OpenWebUI/LibreChat 的开源实践，CodeSage 可以在保持无框架、原生 ESM 优势的前提下，显著缩小与头部产品的体验差距。

建议优先落地 **P0 的三项改造**：
1. RunLog 数据层（一切后续优化的基础）。
2. Thinking 结构化 + Tool Call 轻量时间线（用户感知最明显的提升）。
3. Plan 与 Tool 联动（Agent 透明度的核心）。

这三项完成后，CodeSage 的对话过程数据呈现将从"可用"进入"好用"的第一梯队。
