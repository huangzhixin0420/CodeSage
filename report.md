# 2025-2026 主流 AI 编程助手视觉设计趋势调研报告

> 调研对象: Cursor、Claude Code、GitHub Copilot、Windsurf (Cognition Devin)、Continue.dev、Sourcegraph Cody、JetBrains AI Assistant / Junie
> 时间窗口: 2025 中 - 2026 中
> 调研日期: 2026-06-13

---

## 1. 视觉设计语言综述(2025-2026)

主流 AI 编程助手的 UI 在 2025-2026 年间呈现高度趋同的设计语言,核心特征可归纳为以下四点:

**(1) 配色:暗色优先 + 单一品牌强调色**
所有头部产品(Cursor、Claude Code、Devin Desktop、Cody、Copilot)都默认提供 Dark/Light 双向主题,并以「近黑背景 + 单一高饱和品牌色」为基底。Cursor 用紫色 `#8B5CF6` 系作主色,Anthropic Claude 用暖橙/琥珀色 `#DA7756` 作 brand accent,GitHub Copilot 用渐变紫蓝 `#8957E5 → #BC8AFF`,Devin Desktop 的 Inter 文档站则将深色模式作为默认(`html.dark`)。这些选择都倾向于「深色低噪 + 高对比品牌色」,与 IDE 整体工作环境融合。

**(2) 布局:侧栏 / Dock + 浮动面板 + Inline 三段式**
- **侧栏 Chat**(Cursor 右侧栏、JetBrains AI 工具窗、VS Code Copilot Chat 面板、Claude Code IDE 扩展)— 提供完整对话与工具调用。
- **浮动 Inline Chat**(JetBrains Inline Chat、VS Code Quick Chat、Cursor Ctrl+K)— 在光标位置弹出紧凑输入框。
- **主编辑区 Diff/Canvas**(Cursor 2.0 的 Apply、Windsurf/Devin 的 Canvas)— 用行内 Diff 高亮(绿/红)叠加在源码上。

**(3) 交互:Streaming 优先、Plan → Act → Verify 显式化**
所有产品都使用 token-by-token streaming,加载态几乎都改用「行内脉动圆点 + 步骤标签」(Thinking / Planning / Editing / Testing)而不是旋转 spinner。Cursor 2.0 引入「Plan Mode + Diff Apply」三步式;JetBrains AI 的 Agent Mode 把"分析—计划—执行—验证"显式分步展示。

**(4) 动效:克制、120-180ms 缓动**
跨产品的 hover、展开、消息入场动效都收敛到 120-200ms 的 ease-out 曲线,弃用过场遮罩。JetBrains 2025.2 主题切换采用淡入淡出,VS Code 1.96+ Copilot 引入消息滑入 + 行高变化。

## 2. Cursor 2.0 / Claude Code 最新 UI 描述

**Cursor 2.0(2025-10 至今)**
- 主对话区采用右侧 **Agent Sidebar**,默认宽 ~360px,可拖拽。消息气泡**无明显气泡边框**,而是靠背景块(浅灰/深灰)区分,用户/助手块等宽对齐。
- 代码块使用 **Shiki 双主题**(light/dark 同步切换),行号、文件名、复制按钮位于代码块右上角。
- **Diff 展示**改用 `@` 标记的 inline patch 块(`+`/`-` 着色,绿/红 line-level highlight),可逐 hunk 接受/拒绝。
- **Thinking 折叠**:每个工具调用前有可折叠的「Reasoning」区块,默认折叠,展开时用低饱和紫色边框。
- **工具调用展示**为水平卡片条,显示图标 + 工具名 + 耗时,失败有红色 badge。
- **Loading 态**为三色脉动圆点(紫/灰/白)。Cursor 自有字体 **Cursor Sans**(界面) + **Berkeley Mono**(代码回退,2025 后期由 JetBrains Mono 替换)。
- 文档:https://docs.cursor.com/welcome; 更新日志:https://www.cursor.com/changelog

**Claude Code(Anthropic,2025-09 至今)**
- 终端 TUI 默认使用 **JetBrains Mono** 渲染,文档站使用 **Inter** + **JetBrains Mono** 组合(`<html class="inter_... jetbrains_mono_...">`)。
- IDE 扩展(在 VS Code/JetBrains)采用**侧边栏 Chat** 模式,主对话区背景为 `bg-claude-bg-100` 近黑(#262624)。
- **消息气泡**不分块,采用左对齐纯文本 + 工具调用 inline 卡片(Run command、Edit file、MCP call)。
- **Thinking 折叠**用 `<think>...</think>` 块,IDE 扩展将其渲染为可点击折叠的灰色条。
- **Diff 展示**与系统主题无关,固定使用红/绿行级高亮(类似 GitHub)。
- **Loading 态**为底部小圆点 + "Thinking" 标签,streaming 时显示 token 计数。
- 文档:https://docs.anthropic.com/en/docs/claude-code/overview; 模型:https://www.anthropic.com/news/claude-sonnet-4-5

## 3. JetBrains AI Assistant / Junie 视觉风格

JetBrains AI Assistant 与 Junie(Coding Agent) 在 2025.2 已统一到 **New UI** 设计语言,核心特征:
- **Material 3 化**:圆角从 6px 提升到 8-12px,使用 `MaterialTheme` token,色彩与 IntelliJ 主品牌蓝 `#307FFF` 保持一致(`data-primary-color="#307FFF"`)。
- **新工具窗口 "AI Chat"**:独立侧边工具窗,顶部 mode 切换(Chat / Agent / Edit / Custom),中间消息流,底部多功能输入框(支持 `@file` 上下文、自动补全提示、模型下拉)。
- **Inline Chat 重做**:在编辑器中按 `⌥⏎` 弹出顶部 inline 提示条,带 Accept / Reject / Copy / Refine 按钮,使用半透明背景+毛玻璃阴影(blur 8-12px)而非传统 dialog。
- **Junie Coding Agent**:独立 "Junie" 工具窗,Plan 步骤以可勾选 checklist 形式展示,执行过程以 terminal 风格实时日志流呈现,带行内 Apply/Reject。
- 文档:https://www.jetbrains.com/help/idea/ai-assistant-in-jetbrains-ides.html

## 4. JetBrains 2025.2 / 2025.3 / 2026.1 新 UI 元素

- **Islands 主题**(2025.2 引入):一种**单色 + 浮动块** 的极简主题,工具栏/选项卡用"岛屿"形式(独立圆角卡片)漂浮在纯色背景上,无明显边框。仅在 New UI 下可用。
- **紧凑工具栏**(Compact Mode):2025.2 提供 toolbar 高度 28px 的紧凑选项,主菜单/工具栏图标尺寸缩小到 16px,适合小屏幕。
- **Light+ with Light Header / Dark+ with Light Header**:新增主题,允许深色主区域 + 浅色顶部 chrome 组合,适合长代码阅读。
- **New UI 2.0**(2025.3 稳定):顶部 navbar 进一步扁平,主窗口菜单移入汉堡,Run widget 用浮动 chip,Search Everywhere 改用全屏遮罩 + 居中搜索框。
- 主题文档:https://www.jetbrains.com/help/idea/2025.2/user-interface-themes.html
- Release notes:https://blog.jetbrains.com/idea/2025/07/22/intellij-idea-2025-2/

## 5. GitHub Copilot Chat — VS Code vs JetBrains 差异

| 维度 | VS Code | JetBrains |
|---|---|---|
| 入口 | 左侧 Activity Bar Chat 图标 + ⌃⌘I / Ctrl+I | 右侧 AI Chat 工具窗 + 行内 Inline Chat |
| 主题契合 | 使用完整 VS Code theme tokens | 复刻 IntelliJ UI 主题,可跟随 IDE Dark/Light |
| 模型选择 | 顶部下拉(GPT-4o、Claude 3.5、Sonnet 4.5、Gemini) | 顶部下拉 + MCP 工具配置 |
| Diff 展示 | 行内 patch(类似 Cursor)+ CodeLens 操作 | 独立 Diff 工具窗(用 IDEA 标准 Diff Viewer,带箭头 + 三向合并) |
| Inline Chat | `Ctrl+I` 浮动框,Tab 切换位置 | `⌥⏎` 顶部 inline 提示条 |
| 字体 | 系统等宽(默认 Menlo/Consolas/Cascadia) | JetBrains Mono(IDE 默认) |
| 加载态 | 旋转 spinner + "Working on it..." | 三个蓝色脉动圆点 |
| 文档 | https://code.visualstudio.com/docs/copilot/chat/copilot-chat | https://www.jetbrains.com/help/idea/ai-assistant-in-jetbrains-ides.html |

## 6. Windsurf (Cognition Devin) — Canvas 浮动工具栏 / Diff

2025-09 Cognition 收购 Windsurf 并将其整合为 **Devin Desktop**。原 Windsurf Cascade 风格保留:
- **Canvas 浮动工具栏**:在编辑器光标上方浮动水平 chip 工具条(Apply / Reject / Diff / Comment),半透明深色背景 + 8px 圆角 + 阴影。
- **Diff 视图**支持「split / unified」切换,默认 split,行级红/绿高亮,顶部有「Accept All / Reject All」按钮。
- **Cascade 模式**:Code/Chat 双标签,工作流含 checkpoints(可回退到任意对话节点)与 worktrees(并行任务隔离)。
- 字体:**Inter**(UI) + **IBM Plex Mono**(代码)— 见 `Inter-latin.woff2` 与 `IBMPlexMono-Regular.woff2` preloads。
- 页面:https://codeium.com/windsurf 与 https://devin.ai/desktop/

## 7. 2026 IDEA 插件市场审美趋势

- **扁平 > 拟物**:扁平化已是绝对主流,几乎所有头部插件(Copilot、Junie、Cody、Continue)弃用拟物,转而使用 1-2px 描边 + 轻阴影 + 单色填充。
- **玻璃拟态(Glassmorphism)局部使用**:JetBrains 2025.2+ 在 popover、Inline Chat、Search Everywhere 使用半透明 + 模糊(8-12px blur),但仅在浮层,主面板仍为纯色。
- **主题策略 = 跟随 IDE**:几乎所有 JetBrains 插件都跟随 IDE 主题(支持 `LaF` 监听),浅/深/高对比三种必备,不主动"覆盖"主题色。Cursor、Claude Code 桌面端提供独立浅深主题。
- **配色克制**:主流插件使用 ≤ 3 种强调色,差异主要在主色相(紫/橙/蓝/绿),其余用 IDE 同色系 token。

## 8. 字体选择(行业实际应用)

- **JetBrains Mono** — IDE 插件事实标准。JetBrains 全家桶默认、Claude Code 终端、Anthropic 文档站、Cursor 代码块、Cody 渲染、Continue 默认。
- **Inter** — 现代 AI 助手的 UI 字体首选。Devin Desktop 显式 preload(`Inter-latin.woff2`),Cody、Cline、Continue 的 settings/侧栏使用。
- **IBM Plex Sans / Mono** — 偏向工程/企业风,Devin Desktop preloads `IBMPlexMono-Regular.woff2` 作等宽字体,Anthropic 部分项目文档使用。
- **SF Pro** — macOS 平台原生,Cursor macOS 客户端早期使用,后被 Berkeley Mono / JetBrains Mono 替代。
- **Cursor Sans + Berkeley Mono** — Cursor 自研,体现"品牌独立字体"趋势。
- **JetBrains Sans** — JetBrains UI 字体,2024-2025 在 IDE 标题/工具栏推广,2026 大部分 JetBrains 插件改用它做侧边栏标题。

## 参考来源(URL)

- Cursor 文档:https://docs.cursor.com/welcome
- Cursor 更新日志:https://www.cursor.com/changelog
- Claude Code 文档:https://docs.anthropic.com/en/docs/claude-code/overview
- Claude Sonnet 4.5 发布:https://www.anthropic.com/news/claude-sonnet-4-5
- GitHub Copilot Chat 文档:https://code.visualstudio.com/docs/copilot/chat/copilot-chat
- VS Code Chat Overview:https://code.visualstudio.com/docs/chat/chat-overview
- JetBrains AI Assistant 文档:https://www.jetbrains.com/help/idea/ai-assistant-in-jetbrains-ides.html
- JetBrains UI 主题文档:https://www.jetbrains.com/help/idea/2025.2/user-interface-themes.html
- IntelliJ IDEA 2025.2 发布:https://blog.jetbrains.com/idea/2025/07/22/intellij-idea-2025-2/
- IntelliJ IDEA 2025.2 EAP:https://blog.jetbrains.com/idea/2025/06/10/intellij-idea-2025-2-eap/
- Windsurf (Codeium):https://codeium.com/windsurf
- Devin Desktop:https://devin.ai/desktop/
- Sourcegraph Cody 重设计:https://sourcegraph.com/blog/cody-vscode-redesign
- vscode-copilot-release:https://github.com/microsoft/vscode-copilot-release

---

*报告基于公开文档/页面抓取,部分信息以最新 changelog 为准。如需视觉对比截图,建议结合上述官方 changelog 的图示。*
