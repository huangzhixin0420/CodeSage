# CodeSage 商业级 UI/UX & 配置体系重构设计方案

> 角色:业务专家 + 设计专家 + 前端开发专家
> 范围:`/Users/leo/Projects/CodeSage` 与用户直接打交道的全部界面
> 目标:将 CodeSage 从「功能能跑」打磨到「商业级 2C 标准」,对齐 Kimi CLI / Zed / Claude Code / Cursor 的最佳实践
> 版本:v1.0(待评审)
> 日期:2026-06-03

---

## 〇、目录

1. 现状盘点与问题诊断
2. 业界最佳实践调研
3. 设计原则与目标
4. 总体架构调整
5. 主对话面板重构(主战场)
6. 思考过程(Thinking)渲染升级
7. 工具调用(Tool Call)展示重构
8. 子 Agent 展示重构
9. 计划/Todo 列表新增
10. 模型切换器重构
11. 输入区(Input)打磨
12. 会话侧边栏与历史(对齐 SESSION_SIDEBAR_REDESIGN)
13. 配置体系重构(核心痛点)
14. 动画 & 微交互规范
15. 视觉设计系统(Design Tokens)
16. 实施路线图
17. 验收标准
18. 风险与回滚
19. 立即可落地的 7 个「下周一就能见效」的项
20. 文档与同步

---

## 一、现状盘点与问题诊断

### 1.1 资产清单

| 维度 | 资产 | 评价 |
|---|---|---|
| Web UI(主) | `src/main/resources/webui/chat.html`(3407 行,单文件 HTML+CSS+JS) | 现代感强,Tailwind+highlight.js,设计系统初具雏形 |
| Web UI 桥 | `JCEFChatPanel.kt`(1121 行) | 协议完善,有 JCEF fallback 机制 |
| Swing UI(备) | `ChatPanel.kt`、`AgentTurnPanel.kt`、`ChatMessage.kt`、`InputPanel.kt`、`ThinkingIndicator.kt`、`ToolCallPanel.kt`、`SubAgentProgressPanel.kt`、`SessionSidebarPanel.kt`、`KanbanBoardPanel.kt`、`RoundedPanel.kt` | 自行实现圆角、动画、tooltip,无设计系统,实现成本高但质感参差 |
| 配置面板 | `ProviderSettingsConfigurable.kt`(686 行)、`BudgetSettingsPanel.kt`、`PluginSettingsConfigurable.kt` | 嵌在 IDE Settings → Tools 下的 3 个 Tab,模型列表/预算配置混在一个页面 |
| 配置存储 | `PluginConfig.kt`(JCEF `PersistentStateComponent`,存 `CodeSagePlugin.xml`)+ IntelliJ `PasswordSafe` | API Key 走系统密钥链,其余走 IDE 配置,无法脱离 IDE 使用 |
| 数据模型 | `AgentStreamEvent.kt`(26 个事件,涵盖 text/thinking/tool/subagent/budget/plan/context/mode/migration) | 流式事件协议完备,**但 UI 端消费不充分** |
| 主题 | `ChatTheme.kt` + chat.html 的 CSS 变量 | 双主题有基础,但暗色对比度/一致性有改进空间 |

### 1.2 核心痛点(用户视角)

| # | 痛点 | 证据 | 影响 |
|---|---|---|---|
| P0-1 | **配置体验分裂** | 3 个分散的 IDE Settings Tab,且「Provider & General」一锅烩(686 行) | 新用户上手成本极高;非 IDE 场景(无 Settings)无法配置 |
| P0-2 | **配置数据被 IDE 绑架** | `CodeSagePlugin.xml` + PasswordSafe,无法直接编辑、备份、跨机器同步 | 与 Kimi CLI 的 `~/.kimi/config.toml` 体验差距明显 |
| P0-3 | **工具调用缺参数/缺 diff** | `ToolCallPanel`/`onToolCallStart` 只显示 `toolName + summary`,没有入参和结果对比 | 用户看不见 AI 干了什么,信任感缺失 |
| P0-4 | **子 Agent 展示降级** | `SubAgentProgressPanel` 仅当外部传 `SubAgent*` 事件才显示;`JCEFChatPanel` 中 `SubAgentProgress` 被并入 `updateThinking("[子Agent] ...")` | 子 Agent 是 CodeSage 卖点,却用一行文本糊弄 |
| P0-5 | **Todo/Plan 渲染简陋** | 虽有 `PlanGenerated/Approved/Modified/Rejected` 完整事件,但 `onPlanGenerated` 把步骤渲染成 `<pre>` 普通文本 | 计划是 Agent 能力的核心可视化,目前形同虚设 |
| P0-6 | **思考过程不可控** | `CollapsibleThinkingPanel` 默认展开,长思考时全屏被刷屏 | Kimi CLI 的「详情/折叠」开关值得借鉴 |
| P0-7 | **Swing 与 Web 风格割裂** | `AgentTurnPanel` 558 行 Swing 组件与 chat.html 同一时刻可能在不同位置渲染 | Kanban / 未来扩展难以复用 |
| P1-1 | 模型下拉长名截断(`length>14 ? substring(0,12)+'...'`),且分组后无搜索 | `renderModelDropdown` | 模型多时无法快速定位 |
| P1-2 | 流式渲染:Web UI 用纯文本 `streamSpan` 累积,Markdown 需等 `onTurnComplete` 才解析,中间过程无格式化 | `onTextDelta` | 长代码/表格体验差 |
| P1-3 | 工具卡片最大 500px 高度截断,长输出被吞 | `.tool-content { max-height: 500px }` | |
| P1-4 | 预算耗尽后是 `Continue` 按钮硬塞进流末尾,无模态 | `onBudgetExhausted` | 用户不知道发生了什么 |
| P1-5 | `@文件` 引用只有图标 chip,无 hover 预览、无跳转 | `file-reference-tag` | |
| P1-6 | 输入框无草稿持久化,刷新会丢 | `message-input` | |
| P1-7 | 错误/异常 UI 不统一:有的是 inline 文本,有的是气泡 | `onError` | |
| P1-8 | 快捷键只有 Enter/Esc/Ctrl+Shift+C,缺少 `/` 命令面板、`Cmd+K` 召唤、`@` 之外无 `//` 行引用 | `handleKeydown` | |
| P1-9 | 主题切换未持久化到「跟随系统」 | `setTheme` | |
| P1-10 | 无响应式:`window < 600px` 直接错位 | 无 media query | |
| P2-1 | 启动画面空白,无骨架屏 | `welcome-state` 仅在空时显示 | |
| P2-2 | 没有打字机光标动画,只有 ▌ 闪烁 | `cursor-blink` | |
| P2-3 | 无消息回到底部按钮 | 无 | |
| P2-4 | 无消息搜索 | 无 | |
| P2-5 | 复制反馈(Toast)位置在底部中央,与输入框冲突 | `showToast` | |
| P2-6 | Kanban 面板与对话分离,无法在对话内嵌 todo | `KanbanBoardPanel` | |

### 1.3 代码/架构问题

| # | 问题 | 后果 |
|---|---|---|
| A-1 | `chat.html` 单文件 3407 行,无 module 化、无构建,纯 CDN 依赖(`cdn.tailwindcss.com`、`cdnjs.cloudflare.com`) | 离线/受限网络直接不可用;无法 tree-shake |
| A-2 | `JCEFChatPanel` 中 26+ 事件类型使用 100+ 行 `when` 嵌套,可读性差,扩展示范缺失 | 后续加新事件成本高 |
| A-3 | `JCEFChatPanel.injectJSBridge` 直接把 `javaBridge.sendMessage` 暴露给 `window`,无 namespace | 长期可维护性差,易命名冲突 |
| A-4 | Swing 端 `AgentTurnPanel` 778 行,JLabel 当按钮用(自定义 hover 颜色切换) | 大量样板代码,难统一 |
| A-5 | Provider 配置存于 IDE 状态,无法在 CLI / Webview 之外访问 | 跨工具数据壁垒 |
| A-6 | API Key 走 `PasswordSafe`,无法 export | |

---

## 二、业界最佳实践调研

### 2.1 Kimi CLI(命令行标杆)

| 借鉴点 | 落地方式 |
|---|---|
| `~/.kimi/config.toml` 人类可读、版本可托管 | CodeSage 用 `~/codesage/settings.json`(兼容 JSON5) |
| 顶部 status bar 显示当前 model + 模式 | Web UI 顶栏强化,徽章化 |
| 工具调用行内化:`[tool] read_file(src/main.kt)` | 工具卡片在流中呈现,有入参/出参 |
| Plan first:大任务先生成计划,用户确认 | Todo 列表成为 Agent 必选步骤 |
| 输入支持多行(`"""` 三引号)、`/命令`、图片粘贴 | 输入区升级 |

### 2.2 Zed(编辑器标杆)

| 借鉴点 | 落地方式 |
|---|---|
| **Threads Sidebar + Agent Panel** 双面板:左侧多 thread,右侧当前 thread | 会话侧边栏 + 主对话 |
| Settings 语义化分组(AI / Editor / Terminal)+ 顶部常用项快捷入口 | 配置页分 6 大区,顶部「快速配置」卡片 |
| 滑块 + 数值双模式控件;`Tab Size` 实时预览 | 关键设置带可视化反馈 |
| GPU 加速原生 UI | Web UI 用 CSS `transform`/`opacity`,JCEF 启用 GPU 合成 |
| Plan 渲染为有序步骤(可勾选进度) | Todo 列表组件 |
| AI 配置页把 `API Key`、`Provider`、`Model`、`Context Size` 一屏呈现 | CodeSage 配置页同样按「Provider 一张卡片」组织 |

### 2.3 Claude Code & Cursor

| 借鉴点 | 落地方式 |
|---|---|
| 工具调用:圆角卡片 + 状态图标(Running/Completed/Failed)+ 可折叠 details | 复用现有 `tool-card`,增加入参/出参 |
| Sub-Agent:可点击展开看子任务流 | Sub-Agent 卡片 = 嵌套的子 Turn 视图 |
| 计划 = Todo 列表(可勾选,可标记 blocked) | 新增 `PlanPanel` 组件 |
| 错误不破坏流,append 在流末尾的 inline alert | 统一 `InlineAlert` 组件 |
| 复制成功/重生成/反馈(👍/👎) hover 显示 | 消息 action bar 强化 |
| 输入区支持拖拽文件、`@` 之外支持 `#` 选模型、`/` 命令 | 增强输入区 |

---

## 三、设计原则

1. **统一设计语言**:全界面 Web UI 化(主对话、配置、Plan),Swing 仅保留不可避免的 IDE 集成层(Kanban、Tool Window 容器)
2. **流即结构**:AI 响应 = 一棵树(Thinking → Tool → SubAgent → Todo → Text),不是平铺字符串
3. **可解释性优先**:每个工具调用、每个子 Agent、每条计划都要让用户「看得见在做什么」
4. **可恢复性**:所有破坏性操作(删除、清空、超出预算)都有 inline 确认,无系统弹窗
5. **离线优先**:配置存本地 JSON、关键资源打包(消除 CDN 依赖)
6. **键盘可达**:一切高频操作都有快捷键 + 可见 hint
7. **配置独立**:配置不再嵌进 IDE,有自己的入口、自己的文件、自己的 schema

---

## 四、总体架构调整

### 4.1 Before / After

```
Before                                    After
─────────────────────────────────────     ─────────────────────────────────────
ToolWindow                                ToolWindow
├── JBSplitter (22%/78%)                  ├── SidebarShell (新组件)
│   ├── SessionSidebarPanel (Swing)       │   ├── 顶栏:Logo / +新会话 / 搜索
│   │                                      │   ├── 会话列表(时间分组)
│   └── JCEFChatPanel (Web)               │   └── 底栏:Settings 入口
│       └── chat.html                      └── TabbedPane
└── KanbanBoardPanel (Swing)                   ├── ChatPanel (Web,统一)
                                                └── [扩展] PlanPanel (Web)
                                                 [扩展] KanbanPanel (迁移到 Web 或保持 Swing)

Settings (IDE)                            Settings (独立)
├── CodeSage                              ├── ~/codesage/settings.json (主)
│   ├── Providers & General (686 行)       ├── 在 ChatPanel 顶栏齿轮入口
│   └── Budget & Rounds                    ├── 可选:IDE 菜单保留「Open Settings」跳转
└── ... (IDE 其他项)                        └── API Key 仍在 PasswordSafe(安全)但 key 引用存 JSON
```

### 4.2 配置存储方案

```
~/codesage/
├── settings.json          # 主配置(JSON5,带注释,git 友好)
├── sessions/              # 会话存档(可选,默认 ~/.codesage/sessions/)
│   └── <session-id>.jsonl
├── logs/                  # 运行日志
└── cache/                 # 缓存(模型列表、文件搜索索引)
```

`settings.json` schema(初版):

```json5
{
  "$schema": "https://codesage.dev/schemas/settings/v1.json",
  "version": 1,

  "providers": [
    {
      "id": "minimax-default",
      "name": "MiniMax",
      "type": "minimax",
      "baseUrl": "https://api.minimaxi.com",
      "apiKeyRef": "keychain:minimax-default",
      "enabled": true,
      "models": [
        { "id": "MiniMax-M2.7", "label": "M2.7", "contextSize": 128000, "supportsTools": true, "supportsVision": false }
      ]
    }
  ],

  "defaults": {
    "providerId": "minimax-default",
    "model": "MiniMax-M2.7",
    "mode": "agent"
  },

  "agent": {
    "maxIterations": 30,
    "maxTokens": 0,
    "maxDurationSeconds": 600,
    "budgetWarningThreshold": 70,
    "subAgentBudgetRatio": 0.5,
    "allowContinueOnExhaustion": true,
    "enablePlanning": true,
    "enableParallelSubAgents": false,
    "maxParallelSubAgents": 3
  },

  "ui": {
    "theme": "auto",
    "showThinking": true,
    "compactMode": false,
    "fontSize": 14,
    "codeBlockTheme": "auto",
    "streamMarkdownLive": true
  },

  "editor": {
    "autoAttachSelection": true,
    "autoAttachFileContext": true,
    "maxContextFiles": 10
  },

  "shortcuts": {
    "send": "Enter",
    "newLine": "Shift+Enter",
    "stop": "Escape",
    "commandPalette": "Cmd+K",
    "toggleThinking": "Cmd+Shift+T",
    "switchModel": "Cmd+/"
  },

  "telemetry": {
    "enabled": false,
    "endpoint": null
  }
}
```

API Key 仍存 IntelliJ `PasswordSafe`,`settings.json` 只存 `apiKeyRef`,启动时解析。CLI 模式可用环境变量 `OPENAI_API_KEY` 兼容(类似 kimi-cli)。

### 4.3 文件改动总览(高层)

```
新增:
  src/main/resources/webui/
    ├── index.html
    ├── styles/
    │   ├── tokens.css
    │   ├── base.css
    │   ├── components.css
    │   ├── chat.css
    │   ├── settings.css
    │   └── themes.css
    ├── js/
    │   ├── bridge.js
    │   ├── event-bus.js
    │   ├── components/
    │   │   ├── turn.js
    │   │   ├── thinking.js
    │   │   ├── tool-call.js
    │   │   ├── sub-agent.js
    │   │   ├── plan.js
    │   │   ├── code-block.js
    │   │   ├── budget-meter.js
    │   │   ├── inline-alert.js
    │   │   ├── toast.js
    │   │   └── ...
    │   ├── views/
    │   │   ├── chat.js
    │   │   ├── settings.js
    │   │   └── plan.js
    │   ├── utils/
    │   └── main.js
    └── vendor/
        ├── marked.min.js
        ├── hljs/
        ├── dompurify.min.js
        └── font-awesome/

  src/main/kotlin/com/codesage/
    ├── shared/config/
    │   ├── PluginConfig.kt              # 改为:序列化到 settings.json
    │   ├── SettingsRepository.kt        # 新,文件 IO + watch
    │   ├── SettingsSchema.kt            # 新,data classes
    │   └── SettingsMigrations.kt        # 新
    ├── ide/ui/
    │   ├── web/JCEFChatPanel.kt         # 重写,事件路由化
    │   ├── settings/SettingsWindowFactory.kt   # 新
    │   └── ...
    └── ...

修改:
  src/main/resources/META-INF/plugin.xml
  src/main/kotlin/com/codesage/ide/toolwindow/

废弃(保留兼容但不再使用):
  src/main/kotlin/com/codesage/ide/ui/components/chat/
  src/main/kotlin/com/codesage/ide/ui/components/kanban/
  src/main/kotlin/com/codesage/ide/settings/
```

---

## 五、主对话面板重构

### 5.1 布局

```
┌──────────────────────────────────────────────────────────────────────────┐
│ TopBar (高度 48px)                                                        │
│  [≡] [CodeSage logo]    [mode-pills]   [●模型名 ▾]   [⏱历史▾] [+][☰][⚙] │
├────────┬─────────────────────────────────────────────────────────────────┤
│        │                                                                  │
│ Side   │   Messages (滚动区,自上而下)                                     │
│ bar    │   ┌──────────────────────────────────────────────────────┐     │
│ (可折叠│   │  助手头像  CodeSage · 9:42 · 4.2s                  │     │
│  280p) │   │  ┌── Thinking (折叠) · 3.4s ──────────────┐        │     │
│        │   │  │  已规划方案...                            │        │     │
│ Today  │   │  └────────────────────────────────────────┘        │     │
│  ●会话A│   │  ┌── Tool: read_file · 1.2s ───────[✓]──────┐     │     │
│  会话B │   │  │  args: {path:"src/main.kt"}                 │     │     │
│        │   │  │  result: <200 行代码预览>                 │     │     │
│ Yesterd│   │  └────────────────────────────────────────┘     │     │     │
│  会话C │   │  ┌── Plan (Todo List) ─────────────────────┐     │     │
│        │   │  │  ✓ 1. 分析需求                            │     │     │
│ Older  │   │  │  ● 2. 实现核心逻辑 (进行中,40%)          │     │     │
│  ...   │   │  │  ○ 3. 编写测试                            │     │     │
│        │   │  │  ○ 4. 提交                                │     │     │
│        │   │  └────────────────────────────────────────┘     │     │     │
│        │   │  这段代码的核心逻辑是...(Markdown 流)            │     │     │
│        │   │  ```kotlin                                     │     │     │
│        │   │  fun main() {...}                              │     │     │
│        │   │  ```                                            │     │     │
│        │   │  [复制] [重新生成] [👍] [👎]   ← hover 显示    │     │     │
│        │   └──────────────────────────────────────────────────┘     │
│  [⚙]   │                                                                  │
├────────┴─────────────────────────────────────────────────────────────────┤
│  [Plan: 显示中]  ◯ Ask ● Agent ◯ Manual     @ file · / command · 📎      │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────┐ [⏵]    │
│  │ 输入框,多行,自动 resize,@ 引用,拖拽文件,/ 命令,图片       │        │
│  │                                                            │        │
│  └────────────────────────────────────────────────────────────┘        │
│  0/4000 · 模型 · 主题 · 快捷键提示                                       │
└──────────────────────────────────────────────────────────────────────────┘
```

### 5.2 关键设计决策

| 决策 | 方案 | 理由 |
|---|---|---|
| 顶栏 Mode | 4 个 segmented button(GENERAL/CODING/REASONING/VISION) | 与现有 chat.html 一致,无大改 |
| 顶栏模型选择 | 见 §10 | |
| 顶栏齿轮入口 | 跳转到独立 Settings 视图(同 WebView 内,见 §13) | 替代 IDE Settings |
| 顶栏 ☰(侧边栏) | 同 Zed,展开/折叠 SessionSidebar | |
| 消息气泡宽度 | 中屏 78%,大屏 64%,小屏 100% | 现有 `assistant-body { max-width: 85/70/65% }` 调整 |
| 用户消息 | 仍然右对齐胶囊,渐变 `linear-gradient(135deg, #4f46e5, #6366f1)` | 保留 |
| 助手消息 | 不再使用「圆角气泡」,改为「左头像 + 居左段落」,无背景色 | 商业级 AI 客户端(Cursor/Claude/ChatGPT)主流,信息密度更高 |
| 助手标识 | avatar = 26×26 渐变方块 + 微 Logo | |

---

## 六、思考过程(Thinking)渲染升级

### 6.1 三态可视化

| 状态 | 视觉 | 交互 |
|---|---|---|
| 思考中 | 三点呼吸 + 「思考中 · 0.8s」淡显 | 默认展开,内容实时追加,灰底单色等宽 |
| 思考完成 | 圆点变绿勾 + 「思考完成 · 3.4s」+ 自动收起,留下 1 行摘要 | 1.5s 后折叠;点击展开看完整 reasoning |
| 折叠 | 一行 chip:`🧠 已思考 3.4s · 共 124 步 · 查看 ›` | 点击展开 |

### 6.2 行为规则

- 全局开关 `Cmd+Shift+T`(已存在 `toggleThinkingVisibility` 增强)
- 思考区与正文之间有 `12px` gap 和 1px 浅色 divider
- 思考内容 mono font,`font-size: 12.5px`,色 `--text-tertiary`
- 思考区最大高度 240px,超出折叠为「查看完整思考」

### 6.3 组件 API

```html
<cs-thinking status="running|complete" duration-ms="3400" collapsed>
  <div class="cs-thinking-summary">已分析 3 个文件,锁定根因为...</div>
  <div class="cs-thinking-detail">{详细 reasoning}</div>
</cs-thinking>
```

### 6.4 与 sub-agent 思考的区分

见 §8,sub-agent 的思考自带「子」标识,缩进 16px。

---

## 七、工具调用(Tool Call)展示重构

### 7.1 工具卡片信息架构(关键升级)

```
┌────────────────────────────────────────────────────────────────┐
│  ● read_file · src/main/kotlin/.../ChatPanel.kt · 0.8s   [▾] │
├────────────────────────────────────────────────────────────────┤
│  Inputs:                                                       │
│  { path: "src/main/kotlin/.../ChatPanel.kt" }                 │
│                                                                │
│  Output:                                                       │
│  ┌─ 318 lines · 9.4 KB ───────────────────── [Copy][Apply] ┐ │
│  │ 1  package com.codesage.ide.ui.components.chat           │ │
│  │ 2                                                          │ │
│  │ 3  class ChatPanel(                                        │ │
│  │ 4      private val project: Project?,                      │ │
│  │ ...                                                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                │
│  Diff (if modified):                                           │
│  - 3 │ old line                                                │
│  + 3 │ new line                                                │
│                                                                │
│  [↻ Retry] [📋 Copy] [⤴ Apply to editor] [⏱ Show in log]      │
└────────────────────────────────────────────────────────────────┘
```

### 7.2 不同工具类型的差异化展示

| 工具类型 | 差异化展示 |
|---|---|
| `read_file` / `grep` / `glob` | Output 区显示带行号预览;超出截断 + 「查看完整 N 行」 |
| `write_file` / `edit_file` / `apply_patch` | **Diff 视图**(红绿行),可直接 Apply/Reject 到编辑器 |
| `run_command` / `bash` | **双区**:`$ command` + 输出(可滚动),长输出折叠;非零退出码高亮 |
| `mcp__*` | 工具名前缀 MCP server 徽章(如 `MCP: filesystem`) |
| `delegate_task` / `subagent` | 见 §8,展开后是子 turn 视图 |
| `web_search` / `web_fetch` | Output 区渲染成卡片(标题/摘要/链接) |
| 用户自定义 skill | 通用卡片(同 read_file) |

### 7.3 状态机

| 状态 | 图标 | 颜色 | 行为 |
|---|---|---|---|
| Queued | ⊙ 灰 | `--text-tertiary` | 仅 Pending,30s 后超时显 ⏱ |
| Running | ⟳ 蓝(自旋) | `--accent` | 可点击强制取消 |
| Completed | ✓ 绿 | `--success` | 默认折叠,1 行摘要 |
| Failed | ✕ 红 | `--error` | 默认展开看错误 |
| Needs Confirmation | ⚠ 黄 | `--warning` | 顶部出现「Allow once / Always allow / Deny」三按钮 |

### 7.4 关键交互

- **折叠/展开**:点 header 或 `Space`
- **应用到编辑器**(`edit_file` 类):点击 Apply 跳到对应编辑器位置高亮
- **复制**:Output 整体复制(默认),或选中行复制
- **长 Output**:默认显示前 50 行,`Cmd+L` 切换「全部」
- **并发展示**:并行 tool call 在视觉上「上下堆叠,同一时间轴对齐」,而非简单并列(借鉴 Claude Code 平行时间轴)

### 7.5 数据契约(Kotlin → JS)

```json
{
  "type": "tool_call_start",
  "turnId": "t_123",
  "toolCallId": "tc_456",
  "toolCall": {
    "name": "edit_file",
    "serverName": null,
    "arguments": {
      "path": "src/main.kt",
      "oldText": "...",
      "newText": "..."
    }
  },
  "summary": "Edit src/main.kt"
}

{
  "type": "tool_call_result",
  "turnId": "t_123",
  "toolCallId": "tc_456",
  "success": true,
  "result": {
    "kind": "diff",
    "diff": [
      { "oldLine": 10, "newLine": 10, "type": "context", "text": "..." },
      { "oldLine": 11, "newLine": 11, "type": "remove", "text": "old" },
      { "oldLine": null, "newLine": 12, "type": "add", "text": "new" }
    ],
    "summary": "1 file changed, 12 insertions(+), 5 deletions(-)",
    "artifacts": [{ "id": "art_789", "title": "src/main.kt", "language": "kotlin" }]
  }
}
```

`result.kind`: `text` | `code` | `diff` | `command` | `json` | `list` | `error` | `subagent`(嵌套)。前端按 `kind` 选择渲染器。

---

## 八、子 Agent 展示重构

### 8.1 现状

`SubAgent*` 事件在 Kotlin 已发,但 Web UI 把它当成普通 tool call,甚至用 `updateThinking("[子Agent] ...")` 凑数。Sprint 重做。

### 8.2 设计

子 Agent **就是一个嵌套的 Turn 视图**。外层显示任务描述,点击展开看子 Agent 自己的 thinking / tool / 步骤。

```
┌──────────────────────────────────────────────────────────────┐
│  ◐ subagent · dev · "为 ChatPanel 添加图片引用解析" · 12.3s │
├──────────────────────────────────────────────────────────────┤
│  (展开后)                                                     │
│  ├── 🧠 思考(0.8s)                                          │
│  │   我需要先查找 ChatPanel 类...                            │
│  ├── ● read_file · ChatPanel.kt · 0.3s                      │
│  ├── ● run_command · find . -name "*.kt" · 0.5s            │
│  ├── ┌── Plan ──────────────────────────────────────────┐   │
│  │  │ ✓ 1. 定位 ChatPanel.kt                           │   │
│  │  │ ● 2. 实现图片引用解析                            │   │
│  │  │ ○ 3. 测试                                         │   │
│  │  └──────────────────────────────────────────────────┘   │
│  └── 结果: ...(子 Agent 最终回复)                            │
└──────────────────────────────────────────────────────────────┘
```

### 8.3 视觉规则

- 外层卡片左边线 2px `--accent-color`,与普通 tool call 区分
- 标题前加 `subagent · {toolset}`(`dev`/`test`/`research`/`docs`/`browser`)
- 可独立计时
- 嵌套深度上限 3 层,超过折叠为「(嵌套层级过深,点击查看)」
- 子 Agent 完成时,外层显示 ✓ 绿勾 + 摘要(默认)

### 8.4 数据契约

复用 `tool_call_start/result`,`toolCall.name = "subagent"`,`result.kind = "subagent"`,新增字段:

```json
{
  "type": "tool_call_result",
  "toolCallId": "tc_xxx",
  "result": {
    "kind": "subagent",
    "subagent": {
      "sessionId": "sub_789",
      "toolset": "dev",
      "task": "...",
      "events": [ /* 与父级一致的事件流快照 */ ]
    }
  }
}
```

或在 `tool_call_result` 前,子 Agent 自身所有 `Thinking/ToolCall/...` 事件带 `parentToolCallId`,前端自动 nest。

---

## 九、计划 / Todo 列表新增

### 9.1 触发

- 用户消息包含明显「多步/计划/分步/任务」语义时
- 命中 `DagTaskPlan` 生成(已有 `PlanGenerated` 事件)
- 用户在输入框 `/plan` 命令强制启用

### 9.2 组件

```
┌── Plan · 4 steps · 1/3 done ────────────────────── [▾] ─┐
│  ✓ 1.  分析现有 ChatPanel 渲染管线                       │
│  ● 2.  设计统一 Web UI 模块拆分 (running)                 │
│       ▸ 探索 3 个文件                                    │
│       ▸ 写出 components 列表                             │
│  ○ 3.  实现主面板重构                                    │
│  ○ 4.  写测试 + 截图                                     │
└──────────────────────────────────────────────────────────┘
```

### 9.3 状态

| Step 状态 | 视觉 |
|---|---|
| Pending | ○ 灰圈 + 浅色文字 |
| Running | ● 蓝点 + 粗体 + 下方 sub-event 缩进(实时滚动) |
| Completed | ✓ 绿勾 + 划线 |
| Failed | ✕ 红圈 + 红色文字 + 错误信息 inline |
| Blocked | ⏸ 黄圈 + 「等待 X 完成」 |

### 9.4 交互

- 整块默认展开(因为是 Agent 进度的核心)
- 点 step 可「展开/收起 sub-events」
- 「Approve Plan」(在 `PlanApprovalController` 触发的场景):顶部 3 按钮 `Approve` `Edit` `Reject`
- 进度条:4 步 1 完成 → `1/4 · 25%`,在 header 显示
- 全部完成时,Plan 卡片淡出,折叠为 `Plan completed · 4/4 · 18.2s`

### 9.5 与 Kanban 的关系

- Plan = 会话内临时 todo(轻量,自动产生)
- Kanban = 跨会话任务(重量,需手动管理)
- 不再让两者功能重叠;Kanban 面板保留作为高级视图

---

## 十、模型切换器重构

### 10.1 现状问题

- 模型名长时截断到 12 字符
- 多 Provider 多模型时无搜索/分组折叠
- 切换无确认,无「我刚切了模型」提示

### 10.2 新版

```
┌────────────────────────────────────────────────┐
│ 🔍 搜索模型...                                  │
├────────────────────────────────────────────────┤
│ ▼ MiniMax                                       │
│   ● MiniMax-M2.7  ★ 默认  128K  · tools        │
│     MiniMax-M2.7-highspeed  128K  · fast        │
│     MiniMax-M2.5  128K  · tools                │
│ ▼ Kimi (Moonshot)                              │
│   kimi-k2.6  256K                              │
│   moonshot-v1-128k  128K                       │
│ ▼ OpenAI 兼容  (1)                             │
│   自定义模型                                   │
├────────────────────────────────────────────────┤
│ ⚙ 配置 Provider / 添加模型                       │
└────────────────────────────────────────────────┘
```

### 10.3 交互

- 点击顶栏模型名展开 dropdown(替代方案:点击展开为 modal)
- 搜索框自动 focus,支持中/英模糊匹配
- 选中模型后 dropdown 关闭 + toast「已切换到 MiniMax-M2.7」
- `Cmd+/` 快捷键直接打开模型选择器
- 长模型名不截断,dropdown 自适应宽度(`max-width: 480px`),顶栏显示 ellipsis

### 10.4 顶栏视觉

```
[●]  MiniMax-M2.7  ▾
```

- 状态点:Provider enabled = 绿,disabled = 灰,error = 红
- 文字:一行,过长 ellipsis,tooltip 显示完整名 + context size

---

## 十一、输入区打磨

### 11.1 升级项

| 项 | 当前 | 升级 |
|---|---|---|
| 多行 resize | 手写 | 保持,但加最大 8 行限制 |
| 拖拽文件 | 无 | 支持拖拽文件 → 自动 `@path` 引用 |
| 粘贴图片 | 无 | 支持粘贴/拖拽图片(走 `agent.prompt.images` 通道) |
| 草稿持久化 | 无 | `localStorage` per session,刷新恢复 |
| `/` 命令 | 无 | 命令面板: `/model`、`/mode`、`/plan`、`/clear`、`/help`、`/settings` |
| `@` 引用 | 文件 | 扩展: `@file`、`@symbol`、`@web`(检索) |
| `#` 选模型 | 无 | 输入 `#kimi` 快速指代模型 |
| 字符计数 | 已有 | 加输入前 token 估算(`agent.prompt.estimate`) |
| 发送中状态 | 已有 | 进度条:「正在准备请求...」→「正在接收...」 |
| 快捷键提示 | 已有 | 折叠为可展开的 hint |

### 11.2 工具栏

输入框上方一行 chip(可隐藏):

```
[Plan: ON]  [Mode: Agent ▾]  [Selected: ChatPanel.kt]  [📎]
```

- `Plan: ON/OFF` 开关(开关大任务自动 plan)
- `Mode: Agent/Ask/Manual` 与顶栏 mode 联动
- `Selected:` 当前编辑器选区(若有,可点击移除)

### 11.3 命令面板

`Cmd+K` 唤起:

```
┌──────────────────────────────────────┐
│ 🔍 输入命令或搜索...                  │
├──────────────────────────────────────┤
│ 模型      /model <name>              │
│ 模式      /mode <agent|ask|manual>   │
│ 计划      /plan [on|off]             │
│ 清空      /clear                     │
│ 新会话    /new                       │
│ 历史      /history                   │
│ 设置      /settings                  │
│ 帮助      /help                      │
│ 主题      /theme <auto|light|dark>   │
└──────────────────────────────────────┘
```

---

## 十二、会话侧边栏(对齐 `SESSION_SIDEBAR_REDESIGN.md`)

完全采纳已有设计,补充:

- **多 Agent 标识**:每个会话可标 `agent` / `ask` / `manual` 模式图标
- **未读/进行中标识**:进行中的会话显示 `●` 蓝点
- **拖拽排序**:支持
- **搜索**:顶栏搜索框
- **Pinned**:右键 Pin 到顶部
- **导出**:右键 Export → 复制为 Markdown

---

## 十三、配置体系重构(核心痛点)

### 13.1 入口

| 入口 | 说明 |
|---|---|
| **主**:ChatPanel 顶栏齿轮 → 内嵌 Settings 视图(同 WebView) | 一站式,不离 IDE |
| **次**:Tools 菜单 → CodeSage → Open Settings Folder | 打开 `~/codesage/` 文件夹 |
| **次**:Tools 菜单 → CodeSage → Reload Settings | 重新读取 settings.json |
| **保留(可选)**:IDE Settings → Tools → CodeSage | 标记 Deprecated,显示 banner「请使用新配置中心」 |

### 13.2 配置视图(同 WebView 内的独立 Tab)

```
┌──────────────────────────────────────────────────────────────────────┐
│ CodeSage Settings · ~/codesage/settings.json  [↻ Reload] [📁 Open]  │
├──────────┬───────────────────────────────────────────────────────────┤
│          │                                                            │
│ Sidebar  │   # Providers                                              │
│          │                                                            │
│ ⚡ General│   ┌── MiniMax ───────────────────[⋮]───────────────┐     │
│ 🤖 Models│   │ Type: minimax    Base URL: https://... [Test]  │     │
│ ⚙ Budget │   │ API Key: sk-***...  [Show] [Reveal]              │     │
│ 🎨 UI    │   │ ☑ Enabled                                          │     │
│ ⌨ Short  │   │                                                     │     │
│ 🔌 MCP   │   │ Models:                                             │     │
│ 📋 Promp │   │ ● MiniMax-M2.7  ★ default                         │     │
│ 🛠 Adv   │   │ ○ MiniMax-M2.7-highspeed  [context: 128K]          │     │
│          │   │ ○ MiniMax-M2.5  [context: 128K]                    │     │
│          │   │ [+ Add model]                                       │     │
│          │   └─────────────────────────────────────────────────────┘     │
│          │   ┌── Kimi (Moonshot) ───────────[+ Add provider]──────┐     │
│          │   │ ...                                                 │     │
│          │   └─────────────────────────────────────────────────────┘     │
│          │                                                            │
└──────────┴───────────────────────────────────────────────────────────┘
```

### 13.3 六大分组(语义化,借鉴 Zed)

| 分组 | 内容 |
|---|---|
| **⚡ General** | 显示语言、用户名、匿名遥测开关、是否自动检查更新 |
| **🤖 Models** | 上面 13.2 的核心;Provider 列表 + 每 Provider 的 API Key / Base URL / Models |
| **⚙ Budget & Agent** | 迭代/Token/时间预算、子 Agent 比例、Continue 策略、Plan 默认开关、Sub-agent 并行 |
| **🎨 UI** | 主题(自动/亮/暗)、字号、紧凑模式、代码块主题、流式 Markdown 开关、动画速度 |
| **⌨ Shortcuts** | 13.4 节 |
| **🔌 MCP / Skills / 🛠 Advanced** | 折叠,放高级项 |

### 13.4 快捷键页(借鉴 Zed 滑块+预览)

- 表格列出每个动作 + 当前键 + 「录制新快捷键」按钮
- 点击录制后,「请按下新快捷键...」,实时显示按键
- 冲突检测:与其他 IDE 快捷键冲突时高亮警告

### 13.5 控件规范(对齐 Zed 调研结论)

| 控件类型 | 适用 | 示例 |
|---|---|---|
| **Toggle 开关** | 布尔 | `Enable streaming` |
| **下拉(带预览)** | 枚举 | Theme、Code block theme |
| **滑块+数值** | 范围整数 | Font size、Max iterations |
| **滑块+百分比** | 比例 | Sub-agent budget ratio |
| **Combo(可输入)** | 字符串/路径 | Base URL、Model id |
| **密码框(可显示)** | 密钥 | API Key |
| **Picker(可浏览)** | 文件/目录 | Custom rules path |
| **卡片折叠列表** | 集合 | Providers、MCP servers、Skills |

所有控件统一来自 `cs-form-field` 组件,包含 label / description / error / hint。

### 13.6 实时保存 vs 显式保存

- 顶层 settings.json **每次修改即写**(`SettingsRepository.watch` debounce 500ms)
- 重要变更(API Key、Provider 增删)给 toast「已保存 · 需重启插件生效」
- 单一「Save」按钮作为兜底(在 `[Advanced]` 折叠区)

### 13.7 Schema & 校验

- `SettingsSchema.kt` 用 kotlinx.serialization 定义 data classes
- 启动时校验,失败则:
  - 自动备份为 `settings.json.bak.<timestamp>`
  - 回退到默认配置
  - Toast 提示用户

### 13.8 与 IDE 旧配置的迁移

- 首次启动检测到 `CodeSagePlugin.xml` 中已有 Provider/Budget 配置
- 弹一次性迁移向导:
  ```
  我们检测到您有旧配置,是否迁移到 ~/codesage/settings.json?
  [Preview diff]  [Migrate]  [Skip(保留 IDE 配置)]
  ```
- 迁移完成前 IDE 旧 Configurable 仍可用,保证可回退

### 13.9 API Key 存储

- 默认走 `PasswordSafe`(IDE 内),`apiKeyRef = "keychain:<provider-id>"`
- 设置页提供「Export to environment variable」按钮 → 写入 `~/.codesage/.env`(权限 600),CLI 模式可用
- 提供「Import from env」反向

---

## 十四、动画 & 微交互规范

### 14.1 时长

| 场景 | 时长 | 缓动 |
|---|---|---|
| 消息进入 | 280ms | `cubic-bezier(0.16, 1, 0.3, 1)` |
| 思考折叠/展开 | 240ms | `cubic-bezier(0.4, 0, 0.2, 1)` |
| 工具卡片展开 | 240ms | `ease-out` |
| Sidebar 展开/折叠 | 320ms | `cubic-bezier(0.16, 1, 0.3, 1)` |
| 主题切换 | 200ms | `ease` |
| Toast 进入/离开 | 200ms / 160ms | `ease-out` / `ease-in` |
| 按钮按下 | 80ms | `ease-out` |
| 数字滚动(预算 %) | 600ms | `ease-out` |
| 光标闪烁 | 1.1s | `step-end` |
| 加载 shimmer | 1.5s | `linear` infinite |

### 14.2 微交互清单

- 发送按钮:长按显示「松开取消」提示
- Stop 按钮:按下时按住的圆环 progress
- 模型切换:成功后顶栏模型名短暂高亮(0.6s)
- 工具完成:绿色脉冲 1 次(200ms)
- 错误:浅红 shake(±4px, 200ms)
- Sub-agent 完成:外层卡片从蓝渐变到灰(400ms)
- 复制成功:icon 从 📋 → ✓ 0.4s 渐变 + 文字「Copied」1.2s 后还原
- 拖拽文件到输入区:输入区边框 dashed + scale(1.01)
- 主题切换:全屏 `background-color` 200ms 渐变,避免白闪
- 流式 Markdown:每收到一段(代码块/段落)用 `IntersectionObserver` + `view-transition-name` 淡入

### 14.3 性能预算

| 指标 | 目标 |
|---|---|
| 首屏渲染 | < 100ms(JCEF 缓存命中)/ < 600ms(冷启动) |
| 流式 delta 帧率 | ≥ 55fps(在 4K 屏) |
| 消息进入动画 | ≤ 280ms,GPU 加速(`transform`+`opacity`) |
| 列表 1000+ 消息 | 滚动 < 16ms/frame(virtual scroll) |
| 内存 | 200+ 消息后 < 300MB |

### 14.4 减少动画(无障碍)

`@media (prefers-reduced-motion: reduce)` 全部退化为 0ms。

---

## 十五、视觉设计系统(Design Tokens)

### 15.1 颜色

```css
:root {
  /* Neutral */
  --bg-0: #ffffff;
  --bg-1: #fafafa;
  --bg-2: #f4f4f5;
  --bg-3: #e4e4e7;
  --fg-0: #18181b;
  --fg-1: #3f3f46;
  --fg-2: #71717a;
  --fg-3: #a1a1aa;
  --border: #e4e4e7;

  /* Accent */
  --accent: #6366f1;
  --accent-hover: #4f46e5;
  --accent-soft: rgba(99,102,241,0.10);
  --accent-fg: #ffffff;

  /* Status */
  --success: #10b981;
  --success-soft: rgba(16,185,129,0.10);
  --warning: #f59e0b;
  --warning-soft: rgba(245,158,11,0.10);
  --error: #ef4444;
  --error-soft: rgba(239,68,68,0.10);
  --info: #3b82f6;

  /* Diff */
  --diff-add-bg: #e6ffec;
  --diff-add-fg: #1a7f37;
  --diff-remove-bg: #ffebe9;
  --diff-remove-fg: #cf222e;

  /* User message (渐变) */
  --user-bubble: linear-gradient(135deg, #4f46e5 0%, #6366f1 100%);

  /* Code */
  --code-bg: #1e1e2e;
  --code-header-bg: rgba(255,255,255,0.04);
  --code-fg: #cdd6f4;
}

[data-theme="dark"] {
  --bg-0: #0f0f10;
  --bg-1: #1a1a1c;
  --bg-2: #252528;
  --bg-3: #34343a;
  --fg-0: #fafafa;
  --fg-1: #d4d4d8;
  --fg-2: #a1a1aa;
  --fg-3: #71717a;
  --border: #2a2a2e;

  --accent: #818cf8;
  --accent-hover: #a5b4fc;
  --accent-soft: rgba(129,140,248,0.15);

  --diff-add-bg: rgba(46,160,67,0.15);
  --diff-add-fg: #7ee787;
  --diff-remove-bg: rgba(248,81,73,0.15);
  --diff-remove-fg: #ffa198;
}
```

### 15.2 字体

```
Sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
      "Microsoft YaHei", Roboto, Helvetica, Arial, sans-serif
Mono: "JetBrains Mono", "Fira Code", "SF Mono", Menlo, Monaco, Consolas, monospace
Sizes: 11/12/13/14/15/16/18/22/28
Line-height: 1.5 (body) / 1.6 (prose) / 1.4 (compact)
Letter-spacing: -0.01em (titles)
```

### 15.3 间距 / 圆角 / 阴影

```
space-1: 4px
space-2: 8px
space-3: 12px
space-4: 16px
space-5: 20px
space-6: 24px
space-8: 32px
space-10: 40px

radius-sm: 6px
radius-md: 10px
radius-lg: 14px
radius-xl: 20px
radius-full: 9999px

shadow-sm: 0 1px 2px rgba(0,0,0,0.04)
shadow-md: 0 4px 12px rgba(0,0,0,0.08)
shadow-lg: 0 12px 32px rgba(0,0,0,0.12)
shadow-pop: 0 16px 48px rgba(0,0,0,0.16)
```

### 15.4 组件库组件清单

```
cs-button       (primary | secondary | ghost | danger; sm | md | lg)
cs-icon-button
cs-input        (text | password | number | search)
cs-textarea
cs-select
cs-combobox     (searchable select)
cs-slider       (with value input)
cs-toggle       (switch)
cs-checkbox
cs-radio
cs-segmented
cs-chip
cs-tag
cs-avatar
cs-card
cs-tooltip
cs-popover
cs-modal
cs-toast
cs-dropdown
cs-tabs
cs-empty-state
cs-skeleton
cs-spinner
cs-progress
cs-inline-alert
cs-divider
```

---

## 十六、实施路线图

> 总投入:约 **18-22 工作日**(单人全职),分 **5 个阶段**,每阶段可独立发布。

### Phase 1:基础(4 天)——「先把设计系统搭起来」

- [ ] 拆分 `chat.html` → 多文件 `index.html` + `styles/` + `js/`
- [ ] 引入 `tokens.css`,统一颜色 / 字体 / 间距
- [ ] 实现基础组件:`cs-button` `cs-input` `cs-textarea` `cs-toggle` `cs-tooltip` `cs-toast` `cs-spinner`
- [ ] 引入并自托管 `marked`、`dompurify`
- [ ] `JCEFChatPanel` 重构:事件路由化(类 EventEmitter 模式),消除 100+ 行 `when`
- [ ] 错误边界:任何 JS 异常不阻塞 UI,显示 toast

**验证**:聊天功能不退化,`./gradlew test` 全通过

### Phase 2:核心体验升级(5 天)——「让 AI 响应更有结构」

- [ ] `cs-turn` 组件(统一一个 turn 的渲染)
- [ ] `cs-thinking` 升级(三态、计时、可折叠)
- [ ] `cs-tool-call` 重做(参数/结果/diff/状态机)
- [ ] diff 渲染器(轻量自研 or 引入 `diff2html` 自托管)
- [ ] `cs-sub-agent` 子 turn 嵌套
- [ ] `cs-plan` Todo 列表
- [ ] `cs-budget-meter` 顶栏实时预算条
- [ ] `cs-inline-alert` 统一错误/警告/预算耗尽

**验证**:在样本对话下(包含 thinking/3 工具/2 sub-agent/计划),UI 结构清晰

### Phase 3:输入与会话(3 天)——「让用户用得更顺手」

- [ ] 输入区升级:命令面板 `/`、粘贴图片、拖拽文件、草稿持久化
- [ ] 模型选择器:搜索 + Provider 分组 + 状态点
- [ ] 会话侧边栏:按 SESSION_SIDEBAR_REDESIGN 实现
- [ ] 主题切换:`auto/light/dark`
- [ ] 快捷键系统:命令面板 + 可视化编辑
- [ ] 响应式:1280 / 1024 / 768 三档断点

**验证**:在 1280×800 屏幕下与同尺寸 Cursor 截图主观打分

### Phase 4:配置体系重构(4 天)——「让配置脱离 IDE」

- [ ] `SettingsRepository` 文件 IO + watch + 校验 + 迁移
- [ ] `settings.json` schema + 默认值 + 备份
- [ ] Settings 视图(同 WebView 内,6 大分组)
- [ ] Provider 卡片化(增删/编辑/启用)
- [ ] API Key 走 PasswordSafe + 可导出
- [ ] 迁移向导:旧 IDE 配置 → settings.json

**验证**:卸载 IDE 配置,纯靠 `settings.json` 完成全部配置

### Phase 5:打磨与回归(3-5 天)——「商业级标准」

- [ ] 动画 14 节全量落地
- [ ] 加载状态(骨架屏、断网重连、JCEF 失败 fallback)
- [ ] 暗色主题对比度审计(目标 AA+)
- [ ] 无障碍:`prefers-reduced-motion`、键盘可达、ARIA
- [ ] 性能:长消息 virtual scroll
- [ ] 错误:JS/Kotlin 异常统一处理 + Sentry-like 上报(可选)
- [ ] 国际化(中/英)
- [ ] 用户文档 + 引导动画(首次启动 walkthrough)

**验证**:可用性测试 5 个外部开发者,目标 SUS ≥ 80

---

## 十七、验收标准

### 17.1 功能验收

| 模块 | 验收项 |
|---|---|
| 思考 | 三态切换流畅;全局开关可关闭;折叠后留 1 行摘要 |
| 工具 | 入参可见;结果按 `kind` 区分(diff/code/text/command);失败有错误信息;可 Apply 到编辑器 |
| 子 Agent | 嵌套显示;与父级工具调用视觉区分;子事件实时滚动 |
| Plan | 步骤状态实时;全部完成自动折叠;Approve/Edit/Reject 正常 |
| 模型 | 多 Provider 分组;搜索可用;切换后有反馈;无截断 |
| 输入 | 命令面板可用;`@` 引用流畅;拖拽文件 OK;草稿持久化 |
| 配置 | 6 大分组齐全;改 Provider 不需重启;迁移向导通过;旧配置可恢复 |
| 主题 | auto/light/dark 三态;暗色对比度 AA+;CSS 变量集中管理 |

### 17.2 体验验收

| 项 | 标准 |
|---|---|
| 首次冷启动 | < 1.5s |
| 发送 → 首字 | < 300ms(本地 mock) |
| 流式渲染帧率 | ≥ 55fps |
| 1000 消息列表滚动 | 帧时间 < 16ms |
| 动画 60fps | 90% 动画不卡顿 |
| 主题切换白闪 | 0 |

### 17.3 兼容性

- macOS / Windows / Linux 三平台视觉一致
- IntelliJ 2024.1+ / 2025.1+
- JCEF 不可用时降级到 Swing(保留兼容层,但功能裁剪)
- IDE 旧配置自动迁移

---

## 十八、风险与回滚

| 风险 | 应对 |
|---|---|
| JCEF 性能/兼容性问题 | 保留 `createFallbackHTML`,有完整 Swing fallback |
| 拆分 `chat.html` 引入构建复杂度 | 暂不引入 webpack/vite;先 ESM `<script type="module">` + 文件拆分 |
| Web 组件库不成熟 | 不引入 React/Vue,自研轻量 custom element(50 个内) |
| `settings.json` 与 IDE 配置双向同步 | 单一 source of truth = `settings.json`;IDE 旧 Configurable 标记 deprecated |
| 用户大量旧配置 | 迁移向导 + 备份;失败回退 |
| 大消息列表性能 | virtual scroll(IntersectionObserver)+ 流式渲染不重排 |
| Sub-agent 嵌套过深 | 限 3 层,UI 自动折叠 |

回滚策略:
- Phase 1-3 不影响 Settings 持久化(`PluginConfig` 还在),回滚只需还原 `chat.html` 单文件
- Phase 4 加 schema 迁移,任意版本可向前/向后回滚
- 全程保留 `git tag`:`v0.x-uiux-p1` ... `v0.x-uiux-p5`

---

## 十九、立即可落地的 7 个「下周一就能见效」的项

如果无法一次性推到 Phase 5,下面 7 项是商业级感提升最高、风险最低的切入点:

1. **统一顶栏**:把 chat.html 顶栏的 4 个 mode + 模型 + 历史 + 主题 + 齿轮 重排成设计稿
2. **助手消息去掉气泡**:改为「左头像 + 居左段落」,商业级 AI 产品标准
3. **工具卡片加 Inputs / Diff**:在 `onToolCallStart` 显示 `arguments`,在 `onToolCallComplete` 按 `kind` 渲染
4. **Plan 组件**:`onPlanGenerated` 从 `<pre>` 改为 `cs-plan` 组件
5. **Sub-agent 卡片**:`onSubAgentStart/Complete` 渲染成嵌套卡片,不再混入 thinking
6. **配置入口**:顶栏齿轮 → 新开 Webview 显示 `~/codesage/settings.json` 路径 + Reload 按钮
7. **命令面板**:`Cmd+K` 唤起,列出 10 个常用命令

---

## 二十、文档与同步

- 本次设计产出于 `docs/UI_UX_REDESIGN_PROPOSAL.md`
- 实施时按 Phase 拆为子文档(`docs/UI_UX_REDESIGN_PHASE1.md` 等)
- 配套在 `docs/` 下补:
  - `DESIGN_TOKENS.md` —— 设计系统 token
  - `COMPONENT_LIBRARY.md` —— 组件库 API
  - `EVENT_PROTOCOL.md` —— Kotlin ↔ JS 事件协议 v2
  - `SETTINGS_SCHEMA.md` —— settings.json 完整 schema
  - `MIGRATION_GUIDE.md` —— 旧 IDE 配置迁移指南

---

**总结**:本次重构的核心哲学是「**让 AI 在做什么这件事,变得完全可见**」——Thinking / Tool / Sub-Agent / Plan / Budget / Diff,所有中间过程都以结构化卡片呈现,而不是平铺文本。配置脱离 IDE 走 `~/codesage/settings.json`,对齐 Kimi CLI / Zed 的最佳实践,与商业级 2C 应用(ChatGPT、Claude、Cursor)对齐。最终目标:用户打开 CodeSage,第一感觉是「这个工具值得信赖」。
