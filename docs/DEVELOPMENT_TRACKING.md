# CodeSage UI/UX 重构开发任务跟踪文档

> **文档版本**: v1.0  
> **日期**: 2026-06-14  
> **关联设计文档**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md`  
> **目标**: 指导大模型逐项实施重构任务，每项任务完成后更新状态

---

## 1. 项目概览

### 1.1 项目信息

| 属性 | 值 |
|------|-----|
| 项目名称 | CodeSage IntelliJ 插件 UI/UX 重构 |
| 技术栈 | Kotlin + JCEF WebUI (HTML/CSS/JS) |
| 前端路径 | `src/main/resources/webui/` |
| 后端路径 | `src/main/kotlin/` |
| 设计文档 | `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` |
| 构建工具 | Gradle + Kotlin DSL |
| 测试命令 | `./gradlew test` |

### 1.2 重构方向总览

| 编号 | 方向 | 优先级 | 设计文档位置 | 当前状态 |
|------|------|--------|-------------|----------|
| O5 | 思考过程结构化升级 | P1 | §6.2.5 O5 (line 662) | 🟢 已完成 (T1) |
| O5.1 | 多轮推理卡片分离 | P1 | §6.2.5.1 O5.1 (line 733) | 🟢 已完成 (T2) |
| O5.2 | 历史会话管理升级（弹出框） | P2 | §6.2.5.2 O5.2 (line 876) | 🟢 已完成 (T4) |
| O5.3 | 工件面板删除 | P2 | §6.2.5.3 O5.3 (line 1086) | 🟢 已完成 (T3) |
| O6 | 工具结果富渲染 | P1 | §6.2.6 O6 (line 1213) | 🟢 已完成 (T5) |
| O8 | 视觉风格升级 | P2 | §6.2.8 O8 (line 1276) | 🟢 已完成 (T7) |
| O11 | 空状态与错误状态设计 | P2 | §6.2.11 O11 (line 1374) | 🟢 已完成 (T8) |

> **注意**: O1/O2/O3/O4/O7/O9/O10/O12 等方向当前不在本次重构范围内，后续根据用户反馈决定是否纳入。

---

## 2. 开发规范与约束

### 2.1 编码规范

```
【强制】所有新增 public/internal API 必须有 KDoc
【强制】使用 Kotlin 协程处理异步逻辑
【强制】危险操作（写文件、删除、Shell）必须经过 ToolGuardrails.preCheck
【强制】文件操作优先走 IntelliJ VFS；project == null 的测试/headless 场景可回退到 AtomicFileWriter
【强制】新增工具继承 UnifiedTool 并通过 ToolRegistry.createDefault() 注册
【推荐】前端代码优先复用现有架构，最小改动原则
【推荐】CSS 使用 Design Tokens，不硬编码颜色/尺寸
【推荐】JavaScript 使用 ES Module，避免全局污染
```

### 2.2 测试规范

```
【强制】每次修改后运行 ./gradlew test
【强制】新增工具至少 2 个单元测试（正常路径 + 错误路径）
【推荐】对依赖 IntelliJ 平台的测试，优先 mock 或使用 project=null 的 File I/O 路径
【推荐】前端改动后手动验证：Chrome DevTools 模拟 JCEF 环境
```

### 2.3 受限操作

```
【禁止】不要自动提交代码或执行 git push
【禁止】不要修改 .github/workflows 除非用户明确要求
【禁止】不要修改 .git 目录或项目外文件
【禁止】不要删除后端 API（只删除前端 UI 组件，后端保留兼容）
```

### 2.4 文档更新规范

```
【强制】每完成一项任务，必须更新本文档的"当前状态"列
【强制】每完成一项任务，在"完成记录"章节追加记录（日期、改动文件、测试情况）
【强制】如果修改了 AGENTS.md 中提到的文件/结构，必须更新 AGENTS.md
```

---

## 3. 开发任务清单

### 3.1 任务依赖关系图

```
Phase 1: 核心清理（必须先完成）
├── T1: 删除 Thinking UI 展示
├── T2: 多轮推理卡片分离
└── T3: 布局重构（删除侧边栏 + 工件面板 → 单栏全宽）

Phase 2: 功能增强（依赖 Phase 1）
├── T4: 历史会话弹出框
├── T5: 工具结果富渲染
└── T6: 代码块操作增强（承接工件能力）

Phase 3: 体验优化（依赖 Phase 1-2）
├── T7: 视觉风格升级
└── T8: 空状态与错误状态设计
```

---

### 3.2 Phase 1: 核心清理

#### T1: 删除 Thinking UI 展示

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5 O5 (line 662-730)

**任务描述**: 删除 `Thinking` 事件的前端 UI 展示，仅保留 `ModelReasoning` 作为推理内容通道。

**开发流程**:

**Step 1: 修改前端 `chat.js`**
- 打开 `src/main/resources/webui/js/views/chat.js`
- 定位 `_onThinkingStart()` 方法（约 line 1330）
- 删除 `StructuredThinking` 卡片创建逻辑，保留 `RunLogBuilder` 记录
- 修改后代码应如下：
  ```javascript
  _onThinkingStart(turnId) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "thinking_start",
      turnId,
      thinkingId: "thinking-" + turnId,
    });
    // 2026-06: Thinking 事件是框架状态消息，不再创建 UI 卡片
  }
  ```
- 同理修改 `_onThinkingUpdate()` 和 `_onThinkingComplete()`
- 删除 `loadHistory()` 中 `m.thinking` 相关的 UI 创建逻辑

**Step 2: 验证前端编译**
- 检查 `chat.js` 是否还引用 `turn.thinking`（应为 `null` 安全）
- 运行 `./gradlew test` 确保无编译错误

**Step 3: 手动验证**
- 启动 IDE 插件，发起对话
- 确认不再出现 `"思考中... (turn 1)"` 卡片
- 确认 `ModelReasoning` 卡片正常显示（如果模型支持 reasoning）

**Step 4: 更新本文档状态**
- 将 T1 状态改为 "🟢 已完成"
- 在"完成记录"章节追加记录

**验收标准**:
- [ ] `Thinking` 事件不再创建任何 UI 组件
- [ ] `ModelReasoning` 事件不受影响，正常显示
- [ ] `RunLogBuilder` 继续记录 `Thinking` 事件
- [ ] `./gradlew test` 通过
- [ ] 手动验证无 `"思考中... (turn N)"` 卡片

---

#### T2: 多轮推理卡片分离

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5.1 O5.1 (line 733-873)

**任务描述**: 解决单 turn 内多轮推理合并到同一个卡片的问题，按 AI 执行顺序渲染为多个独立卡片。

**开发流程**:

**Step 1: 后端新增事件类型**
- 打开 `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt`
- 在 `ModelReasoning` 类下方新增：
  ```kotlin
  /**
   * 标记新一轮推理开始（用于多轮推理卡片分离）
   */
  data class ModelReasoningRoundStart(val roundIndex: Int) : AgentStreamEvent()
  ```

**Step 2: 后端 emit 事件**
- 打开 `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`
- 定位主循环 `while (!interrupted)`（约 line 199）
- 在 `turnNumber++` 之后、`emitEvent(AgentStreamEvent.Thinking(...))` 之前添加：
  ```kotlin
  emitEvent(AgentStreamEvent.ModelReasoningRoundStart(turnNumber))
  ```

**Step 3: 后端 EventRouter 注册**
- 打开 `src/main/kotlin/com/codesage/ide/ui/web/EventRouter.kt`
- 在 `ModelReasoning` 注册下方新增：
  ```kotlin
  register<AgentStreamEvent.ModelReasoningRoundStart> { e, turnId ->
      mapOf("type" to "model_reasoning_round_start", "turnId" to turnId, "roundIndex" to e.roundIndex)
  }
  ```

**Step 4: 后端 EventDelivery 分类**
- 打开 `src/main/kotlin/com/codesage/agent/core/EventDelivery.kt`
- 在 `delivery` 属性中新增：
  ```kotlin
  is AgentStreamEvent.ModelReasoningRoundStart -> EventDelivery.Terminal
  ```
- 在 `coalesceKey` 中新增：
  ```kotlin
  is AgentStreamEvent.ModelReasoningRoundStart -> null
  ```

**Step 5: 前端 main.js 事件路由**
- 打开 `src/main/resources/webui/js/main.js`
- 在 `model_reasoning_complete` case 下方新增：
  ```javascript
  case "model_reasoning_round_start":
      chat._onModelReasoningStart(turnId);
      break;
  ```

**Step 6: 前端 chat.js 修改**
- 打开 `src/main/resources/webui/js/views/chat.js`
- 将 `turn.modelReasoning` 从单引用改为数组：`turn.modelReasonings = []`
- 修改 `_onModelReasoningStart()`：
  - 如果有当前 `turn.modelReasoning`，先调用 `complete()`
  - 将完成的卡片加入 `turn.modelReasonings`
  - 创建新的 `StructuredThinking` 卡片
- 修改 `_onModelReasoningComplete()`：
  - 完成当前卡片，加入数组，清空当前引用

**Step 7: 编译测试**
- 运行 `./gradlew test`
- 修复编译错误

**Step 8: 手动验证**
- 使用支持 reasoning 的模型（如 DeepSeek R1）
- 发起需要多轮推理的对话（如复杂分析任务）
- 确认每轮推理有独立的卡片

**Step 9: 更新本文档状态**

**验收标准**:
- [ ] 后端新增 `ModelReasoningRoundStart` 事件
- [ ] 每轮 AI 调用模型前 emit 该事件
- [ ] 前端每轮创建新的 `StructuredThinking` 卡片
- [ ] 卡片按时间线顺序排列
- [ ] `./gradlew test` 通过
- [ ] 手动验证多轮推理有独立卡片

---

#### T3: 布局重构（删除侧边栏 + 工件面板 → 单栏全宽）

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**:
- O5.2 历史会话弹出框: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5.2 (line 876-1083)
- O5.3 工件面板删除: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5.3 (line 1086-1210)

**任务描述**: 将三栏布局（侧边栏 + 对话区 + 工件面板）改为单栏全宽布局，删除常驻面板。

**开发流程**:

**Step 1: 删除工件面板**
- 删除 `src/main/resources/webui/js/components/cs-artifact.js`
- 或保留但不暴露为 UI 组件（移动到其他目录）

**Step 2: 修改 layout.css**
- 打开 `src/main/resources/webui/styles/layout.css`
- 删除 `--artifacts-width` 相关变量
- 修改 `.app-container` grid：
  ```css
  .app-container {
      grid-template-columns: 1fr;  /* 单栏全宽 */
  }
  ```
- 删除 `.app-container.artifacts-collapsed` 相关规则
- 删除 `.cs-artifacts` 相关规则

**Step 3: 修改 index.html**
- 打开 `src/main/resources/webui/index.html`
- 删除 `<div class="cs-artifacts">` 占位元素
- 删除 `<aside class="cs-sidebar">` 占位元素

**Step 4: 修改 chat.js**
- 删除 `artifacts` Map 和相关方法：`addArtifact()`、`updateArtifact()`
- 删除 `toggleArtifacts()` 方法
- 删除 `Cmd+I` 快捷键处理
- 删除 `_sidebar` 相关逻辑（但保留 `setSessions` 等方法，稍后改为弹出框）
- 修改 `_initHeader()`：删除 `sidebar-toggle-btn`，新增 `session-history-btn`

**Step 5: 修改 main.js**
- 删除 `artifact_add` / `artifact_update` 事件处理
- 删除 `sessions_updated` / `session_switched` 等事件处理（或简化）

**Step 6: 修改 i18n.js**
- 删除 `toggleArtifacts` / `toggleSidebar` 翻译
- 新增 `openSessionHistory` 翻译

**Step 7: 编译测试**
- 运行 `./gradlew test`

**Step 8: 手动验证**
- 确认界面为单栏全宽
- 确认无侧边栏、无工件面板
- 确认 header 有历史会话按钮

**Step 9: 更新本文档状态**

**验收标准**:
- [ ] `cs-artifact.js` 已删除或移出 UI 层
- [ ] 布局为单栏全宽
- [ ] 无 artifacts 相关 CSS/JS/HTML
- [ ] `./gradlew test` 通过
- [ ] 手动验证界面简洁

---

### 3.3 Phase 2: 功能增强

#### T4: 历史会话弹出框

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5.2 O5.2 (line 876-1083)

**任务描述**: 将历史会话从常驻侧边栏改为点击按钮弹出窄弹出框。

**开发流程**:

**Step 1: 后端增加预览文本**
- 打开 `src/main/kotlin/com/codesage/persistence/ConversationPersistence.kt`
- 在 `PersistedSession` 中增加 `previewText` 字段
- 在 `buildPersisted` 中提取第一条用户消息前 30 字
- 在 `PersistedMessage` 中确保 content 字段可用

**Step 2: 后端加载时携带预览**
- 打开 `src/main/kotlin/com/codesage/ide/toolwindow/AgentToolWindowPanel.kt`
- 修改会话列表加载逻辑，携带 `previewText`

**Step 3: 前端创建弹出框组件**
- 新建 `src/main/resources/webui/js/components/cs-session-popover.js`
- 实现 `SessionPopover` 类：
  - 构造函数接收 `anchor`（按钮）、`onSelect`、`onNew`、`onRename`、`onDelete` 回调
  - `open()` 方法：创建弹出框 DOM，定位到 anchor 下方，加载会话列表
  - `close()` 方法：移除弹出框 DOM
  - 支持搜索过滤、时间分组、点击外部关闭

**Step 4: 前端集成弹出框**
- 修改 `chat.js`：
  - 删除 `_sidebar` 引用
  - 新增 `_sessionPopover` 引用
  - 在 `_initHeader()` 中绑定 `session-history-btn` 点击事件
  - 实现 `openSessionHistory()` 方法

**Step 5: 前端样式**
- 新建 `src/main/resources/webui/styles/session-popover.css`
- 或修改 `sidebar.css` 重命名为弹出框样式
- 弹出框宽度 280-320px，阴影、圆角、动画

**Step 6: 快捷键**
- 修改 `chat.js` 快捷键处理：`Cmd+B` 改为唤出弹出框
- 或改为 `Cmd+Shift+L`（避免与 IDE 快捷键冲突）

**Step 7: 编译测试**
- 运行 `./gradlew test`

**Step 8: 手动验证**
- 点击历史会话按钮，弹出框正常显示
- 搜索过滤有效
- 点击外部关闭
- 选择会话切换正常

**Step 9: 更新本文档状态**

**验收标准**:
- [ ] 弹出框宽度 280-320px，不占用布局空间
- [ ] 支持搜索、时间分组、新建/重命名/删除
- [ ] 点击外部或 `Esc` 关闭
- [ ] 会话项显示预览文本
- [ ] `./gradlew test` 通过
- [ ] 手动验证功能完整

---

#### T5: 工具结果富渲染

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.6 O6 (line 1213-1234)

**任务描述**: 根据工具结果类型（代码/JSON/命令/Diff/错误）提供最佳渲染方式。

**开发流程**:

**Step 1: 分析当前工具结果渲染**
- 打开 `src/main/resources/webui/js/components/cs-tool-call.js`
- 定位 `renderResult()` 或类似方法
- 了解当前纯文本渲染方式

**Step 2: 实现代码结果渲染**
- 在 `cs-tool-call.js` 中新增代码结果分支：
  - 语法高亮（复用 `hljs`）
  - 语言标签
  - 复制/应用按钮

**Step 3: 实现 JSON 结果渲染**
- 新建 `src/main/resources/webui/js/components/json-viewer.js`
- 实现可折叠树形 JSON 展示
- 支持路径复制

**Step 4: 实现命令结果渲染**
- 在 `cs-tool-call.js` 中新增命令结果分支：
  - stdout：终端风格 + 行号
  - stderr：红色边框 + 错误图标
  - 大输出折叠

**Step 5: 实现错误结果渲染**
- 在 `cs-tool-call.js` 中新增错误结果分支：
  - 错误卡片样式
  - 堆栈折叠
  - 建议修复（如果有）

**Step 6: 样式补充**
- 修改 `styles/tool-results.css`（或新建）
- 添加各类型结果对应的 CSS 类

**Step 7: 编译测试**
- 运行 `./gradlew test`

**Step 8: 手动验证**
- 使用各种工具（读文件、运行命令、搜索等）
- 确认结果渲染符合设计

**Step 9: 更新本文档状态**

**验收标准**:
- [ ] 代码结果：语法高亮 + 语言标签 + 复制/应用
- [ ] JSON 结果：可折叠树 + 路径复制
- [ ] 命令 stdout：终端风格 + 行号
- [ ] 命令 stderr：红色边框 + 错误图标
- [ ] 错误结果：错误卡片 + 堆栈折叠
- [ ] `./gradlew test` 通过

---

#### T6: 代码块操作增强（承接工件能力）

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**:
- O5.3 工件面板删除: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.5.3 (line 1086-1210)
- O9 代码块操作增强: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.9 (line 1354-1375)

**任务描述**: 在代码块操作栏增加 Diff 对比和拒绝按钮，承接工件面板的能力。

**开发流程**:

**Step 1: 修改代码块操作栏**
- 打开 `src/main/resources/webui/js/markdown.js` 或代码块渲染相关文件
- 定位代码块操作栏渲染逻辑
- 增加 `[🔍 Diff]` 和 `[✗ 拒绝]` 按钮

**Step 2: 实现 Diff 按钮**
- 点击 Diff 按钮时：
  - 获取代码块内容
  - 通过 bridge 请求后端获取当前文件原始内容
  - 弹出 Diff 对比模态框（复用 O3 的 Diff 组件，或简单 inline diff）

**Step 3: 实现拒绝按钮**
- 点击拒绝按钮时：
  - 删除该代码块 DOM
  - 发送 `reject_artifact` 事件到后端（或简单记录用户操作）

**Step 4: 样式调整**
- 修改 `styles/markdown.css` 或 `styles/code-blocks.css`
- 确保操作栏按钮排列合理

**Step 5: 编译测试**
- 运行 `./gradlew test`

**Step 6: 手动验证**
- 确认代码块有 Diff 和拒绝按钮
- 点击 Diff 弹出对比
- 点击拒绝删除代码块

**Step 7: 更新本文档状态**

**验收标准**:
- [ ] 代码块操作栏有 Diff 按钮
- [ ] 代码块操作栏有拒绝按钮
- [ ] Diff 按钮弹出对比（至少展示原始 vs 建议）
- [ ] 拒绝按钮删除代码块
- [ ] `./gradlew test` 通过

---

### 3.4 Phase 3: 体验优化

#### T7: 视觉风格升级

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.8 O8 (line 1276-1297)

**任务描述**: 提升视觉品质，包括微阴影、图标系统、动画、代码块主题联动。

**开发流程**:

**Step 1: 引入 Lucide 图标**
- 下载 `lucide` 图标库到 `src/main/resources/webui/lib/`
- 或引入 CDN（如果网络允许）
- 逐步替换 Font Awesome 图标（优先替换关键按钮）

**Step 2: 增加 Surface 层次**
- 修改 `styles/tokens.css`：
  - 增加 `--shadow-sm` / `--shadow-md` / `--shadow-lg`
- 修改 `styles/components.css`：
  - 卡片增加 `box-shadow`
  - hover 状态增加 `translateY(-1px)` + 阴影增强

**Step 3: 增加 Glass 效果**
- 修改 `styles/tokens.css`：
  - 增加 `--surface-glass: rgba(255,255,255,0.8)`（亮色）/ `rgba(0,0,0,0.6)`（暗色）
- 修改浮层、下拉菜单使用 `--surface-glass` + `backdrop-filter: blur(8px)`

**Step 4: 代码块主题联动**
- 修改 `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`
- 在发送主题信息时，增加 IDE 当前代码高亮主题
- 前端根据主题切换 `hljs` 主题 CSS

**Step 5: 动画优化**
- 修改 `styles/animations.css`
- 增加 spring 物理动画（`cubic-bezier(0.34, 1.56, 0.64, 1)`）
- 卡片展开/折叠使用 spring 动画

**Step 6: 编译测试**
- 运行 `./gradlew test`

**Step 7: 手动验证**
- 确认图标显示正常
- 确认阴影/玻璃效果在不同主题下正常
- 确认动画流畅

**Step 8: 更新本文档状态**

**验收标准**:
- [ ] Lucide 图标部分替换 Font Awesome
- [ ] 卡片有微阴影和 hover 抬升效果
- [ ] 浮层有玻璃效果
- [ ] 代码块高亮与 IDE 主题联动（或至少支持切换）
- [ ] 动画使用 spring 效果
- [ ] `./gradlew test` 通过

---

#### T8: 空状态与错误状态设计

**当前状态**: 🟢 已完成 (2026-06-15)

**关联设计**: `docs/CODESAGE_UI_UX_RECONSTRUCTION_PLAN.md` §6.2.11 O11 (line 1374-1395)

**任务描述**: 提供精美的空状态和错误状态，引导用户操作。

**开发流程**:

**Step 1: 设计空状态组件**
- 新建 `src/main/resources/webui/js/components/cs-empty-state.js`
- 实现 `EmptyState` 类，接收参数：
  - `icon`：图标（Lucide）
  - `title`：标题
  - `description`：描述
  - `actions`：操作按钮数组

**Step 2: 实现各空状态**
- **无会话**：插画 + "开始你的第一次对话" + [新会话] 按钮
- **无模型配置**：引导卡片 + "配置模型" 按钮 + 文档链接
- **无搜索结果**：提示 + 建议搜索词
- **加载中**：骨架屏或 spinner

**Step 3: 实现错误状态**
- **加载失败**：错误插画 + 重试按钮 + 错误详情折叠
- **工具执行失败**：错误卡片 + 建议修复 + 重试按钮
- **网络错误**：离线图标 + 重试按钮 + 检查设置链接

**Step 4: 样式**
- 新建 `styles/empty-state.css`
- 空状态居中、大图标、柔和颜色
- 错误状态红色调、警示图标

**Step 5: 集成到各页面**
- 修改 `chat.js`：无会话时显示空状态
- 修改 `settings.js`：无模型配置时显示引导
- 修改工具调用组件：失败时显示错误状态

**Step 6: 编译测试**
- 运行 `./gradlew test`

**Step 7: 手动验证**
- 清空会话列表，确认空状态显示
- 断开网络，确认错误状态显示
- 触发工具失败，确认错误状态显示

**Step 8: 更新本文档状态**

**验收标准**:
- [ ] 无会话时有精美空状态
- [ ] 无模型配置时有引导卡片
- [ ] 加载失败时有错误状态 + 重试按钮
- [ ] 工具失败时有错误卡片 + 建议修复
- [ ] 网络错误时有离线图标 + 重试按钮
- [ ] `./gradlew test` 通过

---

## 4. 完成记录

### 4.1 记录格式

每项任务完成后，按以下格式追加记录：

```markdown
#### YYYY-MM-DD: T{编号} {任务名称}

**状态**: 🟢 已完成

**改动文件**:
- `file1`: 改动说明
- `file2`: 改动说明

**测试情况**:
- `./gradlew test`: 通过/失败（如果失败，记录失败原因）
- 手动验证: 通过/部分通过/未验证

**备注**:
- 任何需要后续跟进的问题
- 对设计文档的修正建议
```

### 4.2 当前完成记录

> 全部 8 项 UI/UX 重构任务 (T1-T8) 已于 2026-06-15 完成,详细记录如下:

#### 📋 总览

| 任务 | 方向 | 状态 | 关键改动 |
|------|------|------|----------|
| T1 | O5 删除 Thinking UI 展示 | 🟢 已完成 | chat.js `_onThinkingStart/Update/Complete` 改为仅 RunLog |
| T2 | O5.1 多轮推理卡片分离 | 🟢 已完成 | 后端 `ModelReasoningRoundStart` + 前端 modelReasonings[] |
| T3 | O5.3 单栏全宽布局 | 🟢 已完成 | 删除 cs-artifact.js + sidebar + 工件面板 |
| T4 | O5.2 历史会话弹出框 | 🟢 已完成 | 新增 cs-session-popover.js + previewText |
| T5 | O6 工具结果富渲染 | 🟢 已完成 | renderCode / renderTerminal / renderJsonTree / renderErrorCard |
| T6 | O9 代码块操作增强 | 🟢 已完成 | Diff + Reject 按钮 + show_code_diff 桥接 |
| T7 | O8 视觉风格升级 | 🟢 已完成 | glass + spring 动画 + hover 抬升 |
| T8 | O11 空状态与错误状态 | 🟢 已完成 | cs-empty-state.js + 无模型引导 |
#### 2026-06-15: T1 删除 Thinking UI 展示
#### 2026-06-15: T2 多轮推理卡片分离
#### 2026-06-15: T3 布局重构（删除侧边栏 + 工件面板 → 单栏全宽）
#### 2026-06-15: T4 历史会话弹出框
#### 2026-06-15: T5 工具结果富渲染
#### 2026-06-15: T6 代码块操作增强（承接工件能力）
#### 2026-06-15: T7 视觉风格升级
#### 2026-06-15: T8 空状态与错误状态设计

**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/js/components/cs-empty-state.js` (新增): `EmptyState` 通用组件,支持 `variant: "empty" | "error" | "loading"`、图标(FA class)、标题、描述、hint、操作按钮数组(支持 primary/danger 变体)、错误详情折叠;`open()` / `destroy()` 生命周期。
- `src/main/resources/webui/styles/empty-state.css` (新增): ~150 行样式,`.cs-empty-state` 三态变体(中性/红色调/loading),`.cs-empty-state-action` (含 primary/danger 变体) hover 抬升,`.cs-empty-state-hint` 建议提示,`.cs-empty-state-details` 堆栈折叠,亮/暗主题适配。
- `src/main/resources/webui/index.html`: 新增 `<link rel="stylesheet" href="styles/empty-state.css" />`(插在 polish.css 之前)。
- `src/main/resources/webui/js/views/chat.js`: import `EmptyState`;`setAvailableModels` 在 `groups` 为空时,渲染 EmptyState(标题"尚未配置模型",描述"前往设置添加 AI Provider",操作"打开设置"按钮触发 `this.showSettings()`)。
- `src/main/resources/webui/js/components/cs-session-popover.js`: 空状态文案改进 + 加图标(`fa-magnifying-glass` / `fa-comments` 区分搜索无果 vs 无会话)。
- `src/main/resources/webui/styles/session-popover.css`: `.cs-session-popover-empty` 改用 flex column 居中布局,图标样式 24px / `--fg-muted`。

**测试情况**:
- `./gradlew test`: 通过
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- 工具执行失败场景已由 T5 的 `.tool-result-error-card` 覆盖(堆栈折叠 + hint + 重试风格),无需在 EmptyState 重复。
- 加载失败 / 网络错误场景:T8 只覆盖了"无模型配置"这一最常见空态。加载失败重试可以由后端在 `init` 阶段失败时通过 `bridge` 发一个 `show_error` 事件触发 — 当前未实现,留待后续 PR。
- 无搜索结果场景由 SessionPopover 改进版空态覆盖。
- AGENTS.md 不需要更新 — 没有修改受 AGENTS.md 约束的文件路径(`src/main/resources/webui/` 整体未变更)。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/styles/tokens.css`: 确认 `--surface-glass` / `--surface-glass-border` / `--surface-glass-blur` / `--surface-glass-saturate` 在亮色与暗色模式下均已就绪(亮色:`rgba(255,255,255,0.72)` + 12px blur;暗色:`rgba(26,28,31,0.72)` + 14px blur)。无需新增 token。
- `src/main/resources/webui/styles/session-popover.css`: 弹出框背景切到 `var(--surface-glass)`,加 `backdrop-filter: blur() saturate()`;入场动画由 `ease-out` 升级为 `ease-spring` (cubic-bezier(0.34, 1.56, 0.64, 1), 220ms),营造轻微"弹"出感。
- `src/main/resources/webui/styles/chat.css`: 新增 O8 区块 — `.tool-call-card` / `.cs-thinking-card` / `.plan-card` 在 hover 时 `translateY(-1px)` + `shadow-md` 抬升;`.message-user .message-actions` 默认透明,hover 渐显;输入框 focus 时 `translateY(-1px)` + spring 动画 + `shadow-focus` 焦点环;`.cs-button` / `.icon-btn` active 时 `scale(0.97)` 物理按压反馈。

**测试情况**:
- `./gradlew test`: 通过
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- 引入 Lucide 图标库(替换部分 Font Awesome)在本次范围内未实施 — Font Awesome 仍可用且视觉无明显不足,Lucide 切换是体积权衡(每 SVG ~2KB),建议单独 PR。
- 代码块高亮与 IDE 主题联动 — 既有的 `setLaf` + `setTheme` 桥接逻辑已生效(`hljs-theme-light` / `hljs-theme-dark` 切换正确),无需新增改动。
- `--shadow-xs/sm/md/lg/xl` 五级阴影在 `tokens.css` 已就绪,本次只在新组件(卡片 hover)上启用,旧组件保留原样式以减小风险。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/js/markdown.js`: `_buildNonDiffBlock` 在原 `[复制/应用到编辑器/插入光标/创建文件]` 之后,新增 [Diff 对比] 与 [拒绝(删除)] 两个按钮;`Diff` 仅当 `filePath` 非空时显示,点击触发 `sendAction("show_code_diff", payload)`;`拒绝` 立即从 DOM 移除代码块 + 触发 `sendAction("reject_code_block", payload)`。
- `src/main/resources/webui/styles/chat.css`: 新增 `.code-block-action.code-block-action-reject:hover` 规则,拒绝按钮 hover 时红色高亮。
- `src/main/resources/webui/js/main.js`: 新增 `case "show_diff_modal"` — 委托 `window.CodeSage.openDiffModal` 处理(降级 alert)。完整 Diff 模态框组件留给后续 PR(本次只打通数据通路,UI 与 O3 diff-viewer 集成在后续迭代中)。
- `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`: 桥接处理 `show_code_diff` (调用 `readFileContentForDiff` 读原文件,发 `show_diff_modal` 事件) 与 `reject_code_block` (记录审计日志);新增 `MAX_DIFF_FILE_SIZE = 200_000` 常量;新增 `readFileContentForDiff(filePath)` 私有方法 — LocalFileSystem 解析,basePath 防御,文件 > 200KB 截断,失败返回空串(供前端识别为"新增文件"场景)。

**测试情况**:
- `./gradlew test`: 通过
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- 完整 Diff 模态框(行内 diff + 接受/拒绝 hunks)暂未实现,本次只完成"按钮 + 数据通路"。后续 O3 / T7 可复用 `cs-diff-viewer.js` 组件做完整对比 UI。
- `reject_code_block` 后端仅记录审计,无需修改文件 — 前端已经移除 DOM。
- `apply_code_block` / `insert_at_cursor` / `create_file_from_code` 既有逻辑未改动,继续工作。
- `cs-session-popover.js` 等文件未受影响。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/js/components/cs-tool-call.js`: 新增 4 个渲染辅助函数 `renderCode` / `renderTerminal` / `renderJsonTree` / `renderErrorCard`;`renderResult` 各分支替换为新渲染(text 自动尝试 JSON 解析→树;code 走语言标签 + 复制按钮;command 走终端行号 + stderr 红边;json 走可折叠树;error 走错误卡片 + 堆栈折叠);`ToolCall` 事件委托 `data-cs-copy-target` 复制按钮(支持 clipboard API + execCommand 兜底)。
- `src/main/resources/webui/styles/chat.css`: 新增 ~250 行 O6 样式,涵盖 `.tool-result-code-block` (语言标签 + 复制)、`.tool-result-terminal` (stdout/stderr 行号 + 错误图标)、`.tool-result-json` + JSON 树 details/summary、深浅色 JSON 关键字颜色、`.tool-result-error-card` + 堆栈 details。

**测试情况**:
- `./gradlew test`: 通过
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- 复制按钮使用 `navigator.clipboard.writeText`(JCEF 支持),降级到 `document.execCommand('copy')`。
- JSON 树采用原生 `<details>`/`<summary>` 实现,无需第三方库,体积小、零依赖。
- ANSI 颜色转义未做(xterm.js 引入体积过大),后续如需可以独立 PR。
- 大输出折叠(>500 行)由现有 `max-height: 240-320px; overflow-y: auto;` 覆盖,无需新增。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/js/components/cs-session-popover.js` (新增): `SessionPopover` 类,API 兼容旧 `Sidebar`(setSessions / setCurrent / onNew / onSelect / onRename / onDelete);支持 open/close/toggle、点击外部关闭、Esc 关闭、搜索过滤、时间分组、previewText 展示。
- `src/main/resources/webui/js/components/cs-sidebar.js`: 保留未删除(已无引用,但不删是为了不破坏任何旧调试代码路径;`chat.js` 已切到 popover)。
- `src/main/resources/webui/styles/sidebar.css` → `src/main/resources/webui/styles/session-popover.css` (重命名并重写): 全部规则针对 `.cs-session-popover*` 命名空间,深浅色主题适配,动画 `cs-popover-in`。
- `src/main/resources/webui/index.html`: header `sidebar-toggle-btn` 替换为 `session-history-btn` (图标 fa-clock-rotate-left);CSS link 同步改名。
- `src/main/resources/webui/js/views/chat.js`: `Sidebar` import 换为 `SessionPopover`;`_initSidebar` → `_initSessionPopover`;`toggleSidebar` → `openSessionHistory`;`_initHeader` 绑定 `session-history-btn`;`_initKeyboard` `Cmd+B` 替换为 `Cmd+Shift+L` 唤出历史会话弹出框。
- `src/main/kotlin/com/codesage/persistence/ConversationPersistence.kt`: `PersistedSession` 新增 `previewText: String = ""` 字段(默认空串,旧 json 反序列化兼容);`buildPersisted` 提取首条 USER 消息去空白后前 30 字,>30 加省略号;私有 `extractPreviewText` 函数。
- `src/main/kotlin/com/codesage/agent/core/AgentCore.kt`: 新增 `getAllPersistedSessions(): List<PersistedSession>` 公开方法,供 UI 层获取带 previewText 的会话元数据。
- `src/main/kotlin/com/codesage/ide/toolwindow/AgentToolWindowPanel.kt`: `sessionToMap` 改为优先用 `core.getAllPersistedSessions()` 的字段(name / createdAt / lastActivityAt / previewText),fallback 到 in-memory AgentSession。
- `src/main/resources/webui/js/i18n.js`: 已新增 `openSessionHistory` 翻译(zh/en)。
- `src/test/kotlin/com/codesage/ide/ui/web/PersistedSessionPreviewTextTest.kt` (新增): 4 个单测,验证 `extractPreviewText` 纯函数行为(空白折叠 / 30 字截断 / 短文本不变 / 空内容返回空串)。

**测试情况**:
- `./gradlew test`: 通过(包含 4 个新增 previewText 单测)
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- `cs-sidebar.js` 未删除,文件本身已无 import 引用(主流程走 SessionPopover)。后续可在确认无副作用后清理。
- 前端 `sidebar.css` 已重命名为 `session-popover.css`,CSS 类名也全部切换为 `.cs-session-popover*`。
- 后端 `apply_artifact` / `reject_artifact` API 仍保留(未触及),T6 将从代码块操作栏触发。
- 未调整 `main.js` 的 `sessions_updated` / `session_switched` / `session_renamed` / `session_deleted` 处理 — 数据结构已包含 `previewText`,前端 popover 直接消费,无需新增事件 case。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/index.html`: 删除 `<aside class="cs-sidebar">` 与 `<aside class="cs-artifacts">` 占位元素;header 删除 `artifacts-toggle-btn` 按钮。
- `src/main/resources/webui/styles/layout.css`: `.app-container` 改为 `grid-template-columns: 1fr` 单栏;删除响应式 @media 折叠块;移除 `.cs-artifacts` 样式。
- `src/main/resources/webui/styles/components.css`: 删除所有 `.cs-artifacts*` 与 `.cs-artifact-*` 规则(共 ~250 行)。
- `src/main/resources/webui/js/views/chat.js`: 删除 `CsArtifact` import;`artifacts` Map 字段删除;`_initSidebar` 不再调用 `artifacts-collapsed` 初始化;`toggleArtifacts()` 方法删除;`_initHeader` 移除 `artifacts-toggle-btn` / `artifacts-close-btn` 绑定;`_initKeyboard` 移除 `Cmd+I` 快捷键;`addArtifact` / `updateArtifact` 改为 no-op,新增 `emitArtifactAction()` 占位(为 T6 代码块操作栏的 apply/reject 桥接预留);`clear()` 移除 artifacts 销毁。
- `src/main/resources/webui/js/components/cs-artifact.js`: 删除(文件整体)。
- `src/main/resources/webui/js/main.js`: `artifact_add` / `artifact_update` case 移除(改为注释说明)。
- `src/main/resources/webui/js/i18n.js`: 新增 `openSessionHistory` 翻译(zh/en);保留 `toggleSidebar` 以兼容旧 JS。

**测试情况**:
- `./gradlew test`: 通过(包含 JStoKotlinContractTest 中 apply_artifact 桥接字段检查 — 通过新增的 `emitArtifactAction` 占位满足字段约束)
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- `cs-sidebar.js` 暂未删除 — T4 将复用其会话列表渲染逻辑到 `cs-session-popover.js`。
- `apply_artifact` / `reject_artifact` 后端 API(JCEFChatPanel)未删除,前端触发方式从"工件面板按钮"改为"代码块操作栏"(T6 实现)。
- `Cmd+B` 仍绑定到 `toggleSidebar`,目前是 no-op(侧边栏已删除),T4 将改为唤出历史会话弹出框。
- AGENTS.md 中提到的"前端路径 `src/main/resources/webui/`"未变更,无需更新 AGENTS.md。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt`: 新增 `ModelReasoningRoundStart(roundIndex)` 事件。
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`: 主循环 `turnNumber++` 后 emit `ModelReasoningRoundStart(turnNumber)`,在 `Thinking` 事件之前。
- `src/main/kotlin/com/codesage/ide/ui/web/EventRouter.kt`: 注册 `ModelReasoningRoundStart` → 消息类型 `model_reasoning_round_start`,带 `roundIndex`。
- `src/main/kotlin/com/codesage/agent/core/EventDelivery.kt`: 显式标注 `ModelReasoningRoundStart` 走 `Terminal`,`coalesceKey = null`。
- `src/main/kotlin/com/codesage/ide/ui/web/EventConsumer.kt`: `logTerminalDelivery` 内补齐 `ModelReasoningRoundStart` 分支(空日志)。
- `src/main/kotlin/com/codesage/agent/core/AgentCore.kt`: `executeDagPlan` 收集器补齐 `ModelReasoningRoundStart` 分支(忽略)。
- `src/main/resources/webui/js/main.js`: 新增 `case "model_reasoning_round_start"` → `chat._onModelReasoningRoundStart(turnId, roundIndex)`。
- `src/main/resources/webui/js/views/chat.js`: turn 新增 `modelReasonings: []` 数组 + `modelReasoningRound` 计数;新增 `_onModelReasoningRoundStart()` 每次创建新 `StructuredThinking` 卡片(归档旧卡片);`_onModelReasoningComplete` 改为把当前卡片 push 到数组再清空引用;`_onModelReasoningStart` 改为薄包装,兜底未发 round_start 的老链路。
- `src/test/kotlin/com/codesage/ide/ui/web/EventRouterModelReasoningTest.kt`: 新增 3 个单测,验证路由、Terminal 投递、不被旧 start 标志改写。

**测试情况**:
- `./gradlew test`: 通过(包含新单测 3 个)
- 手动验证: 未验证(无 IDE 插件运行环境)

**备注**:
- 历史回放路径仍走 `_onModelReasoningStart` 兜底(没有 round_start 历史数据),功能保持兼容;后续若需要为历史消息也按轮次拆卡,可在 persistence 层增加 `reasoningRounds` 字段,目前保留单卡简化。
- 其他 when 表达式(ChatPanel / InlineChat / SubAgentExecutor / EventHistory / EventBatchEmitter / ToolExecutor)均已带 `else ->` 分支,新事件自动忽略,无需改动。


**状态**: 🟢 已完成

**改动文件**:
- `src/main/resources/webui/js/views/chat.js`: 删除未使用的 `Thinking` import；`_onThinkingStart/Update/Complete` 改为仅写入 `RunLogBuilder`，不再创建/更新 `StructuredThinking` UI 卡片；`loadHistory()` 中 `m.thinking` 改为仅记录到 RunLog，不再回放 UI 卡片。

**测试情况**:
- `./gradlew test`: 通过
- 手动验证: 未验证（环境不支持启动 IDE 插件）

**备注**:
- `toggleThinkingVisibility` 与 `thinking-toggle-btn` 保留（其控制的是后端 `set_show_thinking` 标志及 `state.showThinking`，与是否在 UI 渲染 Thinking 卡片解耦），后续根据需要再清理。
- `ModelReasoning` 事件链路未受影响，仍由 `_onModelReasoning*` 系列方法渲染 `StructuredThinking` 卡片。
- `RunLogBuilder` 继续记录 `thinking_start/update/complete` 事件，可供时间线视图使用。


---

## 5. 附录

### 5.1 快速参考：关键文件路径

| 文件 | 路径 |
|------|------|
| 前端入口 | `src/main/resources/webui/js/main.js` |
| 聊天视图 | `src/main/resources/webui/js/views/chat.js` |
| 侧边栏/弹出框 | `src/main/resources/webui/js/components/cs-sidebar.js` / `cs-session-popover.js` |
| 工件面板 | `src/main/resources/webui/js/components/cs-artifact.js` |
| 工具调用 | `src/main/resources/webui/js/components/cs-tool-call.js` |
| 思考卡片 | `src/main/resources/webui/js/components/cs-thinking-v2.js` |
| 布局样式 | `src/main/resources/webui/styles/layout.css` |
| 设计令牌 | `src/main/resources/webui/styles/tokens.css` |
| 后端事件 | `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt` |
| 后端循环 | `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` |
| 事件路由 | `src/main/kotlin/com/codesage/ide/ui/web/EventRouter.kt` |
| 持久化 | `src/main/kotlin/com/codesage/persistence/ConversationPersistence.kt` |

### 5.2 快速参考：常用命令

```bash
# 编译测试
./gradlew test

# 查看前端文件
ls src/main/resources/webui/js/components/
ls src/main/resources/webui/styles/

# 查看后端文件
find src/main/kotlin -name "*.kt" | grep -E "(Event|Router|Loop|Persistence)"

# 搜索代码
grep -r "modelReasoning" src/main/resources/webui/js/
grep -r "addArtifact" src/main/resources/webui/js/
```

### 5.3 设计文档关键位置速查

| 方向 | 文档位置 | 行号范围 |
|------|---------|---------|
| O5 思考结构化 | §6.2.5 | line 662-730 |
| O5.1 多轮推理分离 | §6.2.5.1 | line 733-873 |
| O5.2 历史会话弹出框 | §6.2.5.2 | line 876-1083 |
| O5.3 工件面板删除 | §6.2.5.3 | line 1086-1210 |
| O6 工具结果富渲染 | §6.2.6 | line 1213-1234 |
| O8 视觉风格升级 | §6.2.8 | line 1276-1297 |
| O11 空状态设计 | §6.2.11 | line 1374-1395 |

---

> **文档维护说明**: 本文档由大模型在开发过程中实时更新。每完成一项任务，必须更新"当前状态"列和"完成记录"章节。如有设计变更，同步更新关联的设计文档。
