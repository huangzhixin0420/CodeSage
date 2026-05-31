# CodeSage UI/UX 优化计划

> 基于《CodeSage UI/UX 审查报告》制定  
> 版本：v1.0  
> 日期：2026-05-31

---

## 一、执行摘要

本计划针对 CodeSage 主对话界面（Web UI / JCEF）与配置页面（IDE Settings / Swing）中发现的 21 项 UI/UX 问题进行系统性优化。优化按 **高、中、低** 三级优先级分 **3 个阶段** 实施，预计总工时 **14 个工作日**。每阶段配套完整的测试验证与验收检查点，确保质量可控、风险可测。

---

## 二、现状速览与澄清

| 报告提及项 | 实际现状 | 结论 |
|-----------|---------|------|
| 设置页使用 `TitledSeparator` 分组 | `ProviderSettingsPanel` 已使用 `TitledSeparator`；`BudgetSettingsPanel` 也有但较简陋 | **部分完成**，`BudgetSettingsPanel` 需增强 |
| 删除提供商二次确认 | `ProviderSettingsPanel.removeProvider()` 已有 `Messages.showYesNoDialog` | **已完成**，无需改动 |
| 异步按钮 loading 状态 | `ProviderSettingsPanel` 已禁用按钮并变更文本；`BudgetSettingsPanel` 无异步按钮 | **部分完成**，需增加 spinner 图标 |
| CheckBox 与字段联动 | `ProviderSettingsPanel` 已有 `updateFormEnabledState`；`BudgetSettingsPanel` 无联动 | **部分完成** |
| 配置变更即时广播 | `ProviderSettingsPanel.apply()` 已调用 `broadcastSettingsChanged()` | **已完成** |
| API Key 密码字段 | `ProviderSettingsPanel` 已使用 `JBPasswordField` | **已完成**，报告要求增加"显示/隐藏"切换 |
| 数字输入框校验 | `BudgetSettingsPanel.syncFromUI()` 已有 `toIntOrNull` 静默容错 | **部分完成**，需增加红色 UI 提示 |

---

## 三、优化计划（分阶段）

### 阶段一：高优先级修复（P0）—— 3 个工作日

**目标：消除影响稳定性和用户体验的核心缺陷。**

| 编号 | 模块 | 优化项 | 对应报告问题 |
|------|------|--------|------------|
| P0-W1 | Web UI | 输入框字符计数与上限警告 | 输入框无字符计数提示 |
| P0-W2 | Web UI | 思考过程默认折叠 + 全局显示开关 | Thinking 默认展开遮挡内容 |
| P0-S1 | IDE Settings | `BudgetSettingsPanel` CheckBox 与字段启用/禁用联动 | 取消勾选后相关字段未变灰 |
| P0-S2 | IDE Settings | `BudgetSettingsPanel` 数字输入框实时校验与红色错误提示 | 非数字输入无 UI 反馈 |

### 阶段二：中优先级增强（P1）—— 7 个工作日

**目标：显著提升新用户体验、交互流畅度和信息可发现性。**

| 编号 | 模块 | 优化项 | 对应报告问题 |
|------|------|--------|------------|
| P1-W3 | Web UI | 空状态引导页面（Empty State） | 对话为空时无引导 |
| P1-W4 | Web UI | 流式输出加载动画（骨架屏 shimmer / 脉冲条） | 仅靠光标闪烁易误以为卡死 |
| P1-W5 | Web UI | 工具卡片展开/折叠 CSS 过渡动画 | 展开折叠无动画 |
| P1-W6 | Web UI | 代码块操作按钮 Tooltip + "在新窗口打开" | 用户不知道复制按钮存在 |
| P1-W7 | Web UI | 快捷键支持（Ctrl+Enter 发送、Escape 停止等） | 缺少快捷键 |
| P1-W8 | Web UI | 文件引用标签样式调整（上移、缩小字号） | 标签与消息气泡间距不够 |
| P1-W9 | Web UI | 预算状态醒目化（加粗 + 颜色高亮） | 预算状态字体过小 |
| P1-W10 | Web UI | 模型下拉菜单宽度动态适配 | 长模型名被截断 |
| P1-W11 | Web UI | 消息气泡大屏最大宽度调整（>1200px 时 70%） | 大屏下内容单薄 |
| P1-S3 | IDE Settings | 设置页"恢复默认"按钮 | 缺少一键重置 |
| P1-S4 | IDE Settings | API Key 显示/隐藏切换按钮 | 报告要求增强 |
| P1-S5 | IDE Settings | `BudgetSettingsPanel` 分组标题增强（图标 + 说明） | 字段可读性差 |
| P1-S6 | IDE Settings | 异步操作按钮 spinner loading 动画 | 仅文本变化不够直观 |

### 阶段三：低优先级打磨（P2）—— 4 个工作日

**目标：主题适配与边缘体验打磨。**

| 编号 | 模块 | 优化项 | 对应报告问题 |
|------|------|--------|------------|
| P2-W12 | Web UI | Web UI 独立主题切换（亮/暗） | 深色模式仅依赖 IDE 主题 |
| P2-W13 | Web UI | 输入框工具栏图标 Tooltip | 图标无悬停说明 |
| P2-S7 | IDE Settings | 配置变更即时预览增强（Budget 相关广播） | 修改预算设置后 UI 即时感知 |

---

## 四、开发任务详细规划

### 4.1 阶段一：高优先级修复

#### P0-W1：输入框字符计数与上限警告

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 在 `.input-box` 右下角新增字符计数标签（如 `<span class="char-count">0 / 4000</span>`）。
  2. 为 `input-textarea` 绑定 `input` 事件监听器，实时更新计数。
  3. 当字符数 ≥ 3600 时，计数文字变为橙色（`warning-color`）；≥ 4000 时变为红色（`error-color`），并阻止继续输入或发送。
- **工时**：0.5 天

#### P0-W2：思考过程默认折叠 + 全局显示开关

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 修改 `.thinking-body` 默认 `display: none`，`.thinking-card` 默认折叠。
  2. 在 `.chat-header` 或 `.input-area-wrapper` 增加小型 toggle switch（如"显示思考过程"）。
  3. toggle 状态通过 `localStorage` 持久化，并控制所有 `.thinking-body` 的展开/折叠。
  4. 对应 JavaScript 中 `renderThinking` 逻辑读取该开关状态。
- **工时**：0.5 天

#### P0-S1：`BudgetSettingsPanel` CheckBox 与字段联动

- **文件**：`src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`
- **改动点**：
  1. 为每个预算 CheckBox（`enableIterationCheck`、`enableTokenCheck`、`enableTimeCheck`）添加 `ActionListener`。
  2. 当 CheckBox 未选中时，对应输入框（`maxIterationsField`、`maxTokensField`、`maxDurationField`）设置 `isEnabled = false`、文字变灰。
  3. 重新勾选时恢复 `isEnabled = true`。
- **工时**：0.5 天

#### P0-S2：`BudgetSettingsPanel` 数字输入框实时校验

- **文件**：`src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`
- **改动点**：
  1. 为 `maxIterationsField`、`maxTokensField`、`maxDurationField` 添加 `DocumentListener`。
  2. 输入非数字或超出合理范围（如 `maxIterations` ≤ 0 或 > 1000）时，在字段右侧显示红色警告图标 + 提示文字（如"请输入 1-1000 的整数"）。
  3. `apply()` 中保持 `toIntOrNull` 容错，若校验未通过则弹窗提示并阻止保存。
- **工时**：1.5 天

### 4.2 阶段二：中优先级增强

#### P1-W3：空状态引导页面

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 新增 `.empty-state` 容器，当 `messages-container` 无子元素时显示。
  2. 内容包含：品牌 Logo、欢迎语、3-4 个快捷示例问题（如"解释这段代码"、"帮我重构"）、快捷命令提示。
  3. 点击示例问题自动填入输入框并发送。
- **工时**：0.5 天

#### P1-W4：流式输出加载动画

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 在 `.assistant-content` 底部新增 `.stream-loading` 元素（骨架屏 shimmer 或三个脉冲点）。
  2. 流式输出期间显示，流式结束后隐藏。
  3. 使用 CSS `@keyframes shimmer` 或 `.thinking-dot` 同款动画。
- **工时**：0.5 天

#### P1-W5：工具卡片展开/折叠动画

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 修改 `.tool-content` CSS：初始 `max-height: 0` + `overflow: hidden` + `transition: max-height 0.3s ease`。
  2. 展开时通过 JS 计算内容高度并设置 `max-height`，折叠时恢复 `0`。
  3. 注意处理 `pre` 标签内容动态变化时的高度重算。
- **工时**：0.5 天

#### P1-W6：代码块操作按钮 Tooltip + 新窗口打开

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 为 `.code-action-btn` 添加 `title` 属性或自定义 tooltip 组件（如"复制代码"、"在新窗口打开"）。
  2. 新增"在新窗口打开"按钮，点击后创建 `window.open('about:blank')` 并写入代码内容。
- **工时**：0.5 天

#### P1-W7：快捷键支持

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 全局监听 `keydown` 事件：
     - `Ctrl+Enter` / `Cmd+Enter`：触发发送（等同于点击 send-btn）。
     - `Escape`：触发停止生成（等同于点击 stop-btn）。
     - `Ctrl+Shift+C`：复制最后一条 assistant 消息全文。
  2. 在 `.input-hint` 区域更新快捷键提示文本。
- **工时**：0.5 天

#### P1-W8：文件引用标签样式调整

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. `.file-reference-tags` 的 `margin-bottom` 调整为 `4px`（更紧凑）。
  2. `.file-reference-tag` 字号缩小至 `11px`。
  3. 确保标签在消息气泡内部顶部展示，而非外部。
- **工时**：0.25 天

#### P1-W9：预算状态醒目化

- **文件**：`src/main/resources/webui/chat.html`、`src/main/kotlin/com/codesage/ide/ui/components/chat/ChatPanel.kt`
- **改动点**：
  1. Web UI 中预算状态标签使用 `font-weight: 600`，预警时背景加橙色/红色高亮（`.badge-warning`、`.badge-danger`）。
  2. ChatPanel 中完善 `AgentStreamEvent.BudgetStatus` 的处理逻辑，将状态数据渲染到 header 或 turn panel 中。
- **工时**：0.5 天

#### P1-W10：模型下拉菜单宽度动态适配

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 将 `.model-dropdown` 的 `min-width` 改为 `max-content` 或基于最长选项动态计算 `width`。
  2. 确保 `.model-option` 支持 `white-space: nowrap` 和适当的 `max-width` + 溢出省略。
- **工时**：0.25 天

#### P1-W11：消息气泡大屏最大宽度调整

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 新增媒体查询：`@media (min-width: 1200px) { .message-user-inner, .assistant-body { max-width: 70%; } }`
  2. 在 `>1600px` 时可进一步降至 `65%`。
- **工时**：0.25 天

#### P1-S3：设置页"恢复默认"按钮

- **文件**：`src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`、`ProviderSettingsPanel.kt`
- **改动点**：
  1. 在两个 Panel 底部添加 `JButton("恢复默认值")`。
  2. 点击后调用 `PluginConfig.getInstance()` 的默认值方法或直接重置临时变量到硬编码默认值。
  3. 同步刷新 UI 控件状态。
- **工时**：0.5 天

#### P1-S4：API Key 显示/隐藏切换按钮

- **文件**：`src/main/kotlin/com/codesage/ide/settings/ProviderSettingsPanel.kt`
- **改动点**：
  1. 将 `apiKeyField` 的包装面板改为包含一个切换按钮（👁 / 🙈 图标）。
  2. 切换时调用 `apiKeyField.echoChar = if (visible) 0 else '\u2022'`。
- **工时**：0.5 天

#### P1-S5：`BudgetSettingsPanel` 分组标题增强

- **文件**：`src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`
- **改动点**：
  1. 为每组字段前添加小图标（通过 `JBLabel(AllIcons.General.Settings)` 或 FontAwesome 字符）。
  2. 增加 `TitledSeparator` 的副标题说明文字（如"配置 Agent 执行时的资源消耗上限"）。
- **工时**：0.5 天

#### P1-S6：异步操作按钮 spinner loading 动画

- **文件**：`src/main/kotlin/com/codesage/ide/settings/ProviderSettingsPanel.kt`
- **改动点**：
  1. 将 `testButton` / `fetchModelsButton` 的 loading 状态从"仅改文字"升级为"改文字 + 左侧小 spinner 图标"。
  2. 使用 `AsyncProcessIcon` 或简单的 `JBLabel` + 旋转 CSS/gif。
- **工时**：0.5 天

### 4.3 阶段三：低优先级打磨

#### P2-W12：Web UI 独立主题切换

- **文件**：`src/main/resources/webui/chat.html`、`src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`
- **改动点**：
  1. 在 `.chat-header` 增加主题切换图标按钮（☀️/🌙）。
  2. 点击切换 `html` 标签的 `data-theme` 属性（`light` / `dark`）。
  3. 同步切换 hljs 主题（禁用/启用对应 `<link>`）。
  4. 主题偏好通过 JCEF 的 `CefMessageRouter` 回传并持久化到 `PluginConfig`。
- **工时**：2 天

#### P2-W13：输入框工具栏图标 Tooltip

- **文件**：`src/main/resources/webui/chat.html`
- **改动点**：
  1. 为所有 `.icon-btn`（附件、附件等）添加 `title` 属性。
- **工时**：0.25 天

#### P2-S7：配置变更即时预览增强

- **文件**：`src/main/kotlin/com/codesage/ide/settings/BudgetSettingsPanel.kt`
- **改动点**：
  1. 在 `apply()` 中广播预算相关变更事件（复用 `SettingsChangeListener` 或新增 `BudgetSettingsChangeListener`）。
  2. `ChatPanel` / `AgentTurnPanel` 订阅该事件，实时更新预算状态展示。
- **工时**：1.75 天

---

## 五、测试验证设计

### 5.1 测试策略

| 测试类型 | 范围 | 工具/方法 |
|---------|------|----------|
| 单元测试 | Kotlin Swing UI 逻辑（校验、联动、广播） | JUnit 5 + AssertJ-Swing |
| 集成测试 | Web UI ↔ Kotlin 桥接（主题切换、消息传递） | 手动 + JCEF DevTools |
| 视觉回归测试 | Web UI 各状态下的样式一致性 | 手动截图对比 |
| 交互测试 | 动画流畅度、快捷键响应 | 手动测试 |
| 可访问性测试 | Tooltip、键盘导航、颜色对比度 | 手动 + DevTools 对比度检测 |

### 5.2 各任务测试用例

#### P0-W1 / P1-W7：输入框与快捷键

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-W1-01 | 在输入框输入 3500 字符 | 计数显示 "3500 / 4000"，文字为正常色 |
| TC-W1-02 | 继续输入至 3600 字符 | 计数变为橙色 |
| TC-W1-03 | 继续输入至 4000 字符 | 计数变为红色，无法继续输入 |
| TC-W1-04 | 粘贴 4500 字符文本 | 文本被截断至 4000 字符，计数红色 |
| TC-W7-01 | 输入内容后按 `Ctrl+Enter` | 消息发送成功 |
| TC-W7-02 | 流式输出中按 `Escape` | 输出停止，按钮恢复为发送状态 |

#### P0-W2：思考过程折叠

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-W2-01 | 打开全新对话，发送消息触发 thinking | Thinking 卡片默认折叠，仅显示标题行 |
| TC-W2-02 | 点击 thinking 标题行 | 内容展开，显示思考详情 |
| TC-W2-03 | 打开"显示思考过程"全局开关后发送消息 | Thinking 卡片默认展开 |
| TC-W2-04 | 关闭开关，刷新页面后发送消息 | 开关状态从 `localStorage` 恢复，卡片默认折叠 |

#### P0-S1 / P0-S2：预算设置联动与校验

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-S1-01 | 取消勾选"启用 Token 预算" | `maxTokensField` 变灰、不可编辑 |
| TC-S1-02 | 重新勾选 | `maxTokensField` 恢复可编辑 |
| TC-S2-01 | 在 `maxIterationsField` 输入 "abc" | 右侧出现红色提示"请输入 1-1000 的整数" |
| TC-S2-02 | 输入 "5000" | 红色提示"请输入 1-1000 的整数" |
| TC-S2-03 | 输入 "20" | 红色提示消失 |
| TC-S2-04 | 保持错误输入，点击 Apply | 弹窗提示校验失败，配置未保存 |

#### P1-W4 / P1-W5：动画相关

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-W4-01 | 发送消息后观察流式输出区域 | 消息底部出现 shimmer/脉冲动画，结束后消失 |
| TC-W5-01 | 点击工具卡片标题行展开 | 内容以 `max-height` 过渡动画展开，时长约 300ms |
| TC-W5-02 | 再次点击折叠 | 内容以同样动画平滑折叠 |

#### P1-S3 / P1-S4 / P1-S6：设置页按钮

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-S3-01 | 修改多个预算字段后点击"恢复默认值" | 所有字段恢复为初始默认值 |
| TC-S4-01 | 在 API Key 输入框旁点击"显示"按钮 | 密码明文显示，按钮变为"隐藏" |
| TC-S4-02 | 点击"隐藏"按钮 | 密码恢复为掩码显示 |
| TC-S6-01 | 点击"测试连接" | 按钮禁用，左侧出现旋转 spinner，文字为"测试中..." |
| TC-S6-02 | 测试完成后 | spinner 消失，按钮恢复为"测试连接" |

#### P2-W12：主题切换

| 用例 ID | 步骤 | 预期结果 |
|--------|------|---------|
| TC-W12-01 | 点击主题切换按钮（当前亮色） | 页面整体切换为暗色主题，hljs 高亮同步切换为 dark |
| TC-W12-02 | 关闭 IDE 重新打开 Web UI | 主题偏好从持久化配置中恢复，仍为暗色 |
| TC-W12-03 | 在暗色主题下发送代码块消息 | 代码块背景、边框与暗色主题协调一致 |

### 5.3 自动化测试补充

- **Swing UI 单元测试**：在 `src/test/kotlin/com/codesage/ide/settings/` 下新增：
  - `BudgetSettingsPanelTest.kt`：测试 CheckBox 联动、数字校验逻辑。
  - `ProviderSettingsPanelTest.kt`：测试默认值恢复、API Key 显隐切换。
- **Web UI 端到端测试**（可选，如需长期维护）：使用 Playwright 录制 `chat.html` 的快捷键、字数限制、主题切换场景。

---

## 六、验收标准

### 6.1 通用验收标准

1. **功能完整性**：所有规划任务的功能点均实现，与报告中的"优化建议"和"预期效果"一致。
2. **无回归**：现有核心功能（消息发送、流式输出、工具调用、配置保存/加载）不受影响。
3. **代码质量**：
   - Kotlin 代码通过 `ktlint` / IDE 代码检查，无编译警告。
   - Web UI CSS 无样式泄漏，所有新选择器均以特定 class 为前缀。
4. **性能指标**：
   - Web UI 首次加载时间增加 < 50ms（新增资源大小 < 5KB gzipped）。
   - 动画帧率 ≥ 55fps（Chrome DevTools Performance Panel）。
   - Swing UI 面板打开延迟 < 100ms。
5. **可访问性**：
   - 所有新增按钮/图标具有 `title` 或 `aria-label`。
   - 警告/错误文字颜色对比度 ≥ 4.5:1。

### 6.2 各阶段准入/准出条件

| 阶段 | 准入条件 | 准出条件 |
|------|---------|---------|
| 阶段一 | 开发环境就绪，分支从 `main` 拉出 | 全部 4 个 P0 任务完成并通过单元测试 + 手动冒烟测试 |
| 阶段二 | 阶段一已合并至 `main` | 全部 14 个 P1 任务完成，视觉回归测试通过 |
| 阶段三 | 阶段二已合并至 `main` | 全部 3 个 P2 任务完成，全量验收检查表通过 |

---

## 七、验收流程

### 7.1 验收角色

| 角色 | 职责 |
|------|------|
| 开发工程师 | 按任务清单完成编码、自测、提交 PR |
| QA / 测试工程师 | 执行测试用例、记录缺陷、回归验证 |
| 产品经理 / UX 设计师 | 确认视觉效果与交互体验符合预期 |
| 技术负责人 | 代码审查、性能审查、最终合并决策 |

### 7.2 验收步骤

```
Step 1: 代码审查（Code Review）
  └─ 审查人：技术负责人
  └─ 检查点：是否符合 Kotlin / HTML 编码规范、是否有硬编码、是否有内存泄漏风险
  └─ 产出：PR 批准或修改意见

Step 2: 自动化测试验证
  └─ 执行：CI Pipeline
  └─ 检查点：./gradlew test 全量通过，ktlint 无报错
  └─ 产出：Build 报告

Step 3: 功能测试（Functional QA）
  └─ 执行：QA 工程师
  └─ 检查点：按"测试验证设计"章节逐项执行测试用例
  └─ 产出：测试报告（含通过/失败清单、截图/录屏证据）

Step 4: 视觉与交互走查（UX Review）
  └─ 执行：产品经理 / UX 设计师
  └─ 检查点：与《CodeSage UI/UX 审查报告》中的"预期效果"逐项对比
  └─ 产出：UX 验收签字

Step 5: 性能与回归验证
  └─ 执行：技术负责人 + QA
  └─ 检查点：FPS 达标、无功能回归、内存稳定
  └─ 产出：性能测试截图

Step 6: 合并与发布
  └─ 执行：技术负责人
  └─ 检查点：CHANGELOG 更新、版本号标记
  └─ 产出：合并 Commit、Release Note
```

### 7.3 验收检查清单（Checklist）

- [ ] 所有 21 项优化已按优先级分阶段完成
- [ ] 全部测试用例（TC-W1-01 ~ TC-W12-03、TC-S1-01 ~ TC-S6-02）通过
- [ ] 代码审查无阻塞性问题
- [ ] CI 构建通过（`./gradlew build test`）
- [ ] 无已知 P0/P1 级别 Bug
- [ ] UX 走查签字确认
- [ ] 性能指标达标
- [ ] 文档（CHANGELOG、本计划）已更新

---

## 八、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| JCEF Web UI 调试困难 | 中 | 使用 JCEF DevTools（`--remote-debugging-port`）+ 浏览器直接打开 chat.html 进行大部分调试 |
| Swing UI 测试自动化成本高 | 低 | 核心逻辑写单元测试，UI 交互以手动测试为主 |
| 主题切换跨平台样式差异 | 低 | 在 Windows / macOS 双平台 IDE 中各验证一次 |
| 阶段二任务量大导致延期 | 中 | 允许将 P1-W11（大屏宽度）、P1-W13（工具栏 Tooltip）降级至阶段三 |

---

## 九、附录

### 9.1 术语表

| 术语 | 说明 |
|------|------|
| JCEF | Java Chromium Embedded Framework，IntelliJ 平台内嵌浏览器 |
| Swing | Java GUI 工具包，IDE Settings 页面的 UI 框架 |
| Turn | CodeSage 对话中的一个完整轮次（用户消息 + AI 响应） |
| Shimmer | 一种骨架屏加载动画效果，通过渐变扫过高亮区域 |

### 9.2 参考文档

- [原始审查报告](/Users/leo/temp_files/CodeSage_UI_UX_Review_Report.md)
- [项目架构文档](./ARCHITECTURE.md)
- [IntelliJ Platform UI Guidelines](https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html)

---

*本文档由 Kimi Code CLI 根据审查报告自动生成，后续迭代请同步更新本计划。*
