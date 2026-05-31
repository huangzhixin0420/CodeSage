# CodeSage 会话侧边栏重构设计方案

> 角色：Kotlin 开发专家  
> 目标：重构新建会话的交互方式，解决当前 Swing 侧边栏与 Web UI 风格割裂、两栏布局不美观的问题  
> 日期：2026-05-31

---

## 一、问题分析

### 1.1 现状痛点

| 痛点 | 说明 |
|------|------|
| **风格割裂** | 左侧 `SessionSidebarPanel` 是 Swing 原生组件（`JBList` + `DefaultListCellRenderer`），右侧 `JCEFChatPanel` 是现代 Web UI，两者视觉风格、字体、间距、动画完全不统一 |
| **布局臃肿** | `AgentToolWindowPanel` 使用 `JBSplitter` 强行分割为 22%/78% 两栏，侧边栏固定 200px，大屏下空间利用率低 |
| **交互原始** | 新建会话是一个 JLabel "+ New"；重命名用 `JOptionPane.showInputDialog` 弹窗；删除用 `JOptionPane.showConfirmDialog`；无动画、无 hover 反馈 |
| **信息密度低** | 会话列表仅显示名称，无时间分组、无最后消息预览、无未读标识 |

### 1.2 用户操作习惯分析

参考 Claude / ChatGPT / Cursor 的主流设计：
- 侧边栏**内嵌**在主界面内，而非外部 Swing 面板
- 侧边栏可**折叠/展开**，默认折叠节省空间
- 新建会话按钮在侧边栏**顶部显眼位置**
- 会话项支持**点击切换、右键菜单、inline 重命名**
- 会话按**时间分组**（Today / Yesterday / Previous 7 Days）

---

## 二、设计目标

1. **视觉统一**：会话侧边栏与主对话界面同为 Web UI，使用同一套 CSS 变量和设计系统
2. **布局简洁**：移除 Swing `JBSplitter`，Web UI 内部自包含侧边栏，支持折叠展开
3. **交互现代**：平滑动画、hover 反馈、inline 编辑、无弹窗确认
4. **信息丰富**：时间分组、最后活动时间、当前会话高亮

---

## 三、架构方案

### 3.1 整体架构变更

**Before:**
```
AgentToolWindowPanel
├── JBSplitter (22% / 78%)
│   ├── SessionSidebarPanel (Swing, 200px, JBList)
│   └── JTabbedPane
│       ├── JCEFChatPanel (Web UI)
│       └── KanbanBoardPanel (Swing)
```

**After:**
```
AgentToolWindowPanel
├── JTabbedPane
│   ├── JCEFChatPanel (Web UI，内部自带 Session Sidebar)
│   └── KanbanBoardPanel (Swing)
```

### 3.2 Web UI 内部布局

```
app-container
├── session-sidebar（可折叠，260px 展开 / 0px 折叠）
│   ├── sidebar-header（Logo + 新建会话按钮）
│   ├── sidebar-search（搜索过滤，可选）
│   ├── sidebar-content（会话列表，按时间分组）
│   └── sidebar-footer（设置入口，可选）
├── sidebar-toggle（固定悬浮按钮，用于展开/折叠侧边栏）
└── main-area（原有主区域，消息 + 输入框）
```

### 3.3 通信协议

**Kotlin → JS（已有 `sendToJS` 通道）**

| 消息类型 |  payload  | 说明 |
|---------|----------|------|
| `set_sessions` | `{sessions: [{id, name, createdAt, lastActivityAt}]}` | 推送完整会话列表 |
| `session_created` | `{session: {id, name, ...}}` | 通知前端新会话已创建 |
| `session_switched` | `{sessionId}` | 通知前端当前会话已切换 |
| `session_deleted` | `{sessionId}` | 通知前端会话已删除 |
| `session_renamed` | `{sessionId, name}` | 通知前端会话已重命名 |

**JS → Kotlin（已有 `javaBridge.sendMessage` 通道）**

| 消息类型 | payload | 说明 |
|---------|---------|------|
| `new_session` | `{}` | 请求创建新会话 |
| `switch_session` | `{sessionId}` | 请求切换会话 |
| `delete_session` | `{sessionId}` | 请求删除会话 |
| `rename_session` | `{sessionId, name}` | 请求重命名会话 |
| `request_sessions` | `{}` | 请求获取会话列表（初始化时） |

---

## 四、交互设计

### 4.1 侧边栏状态

| 状态 | 宽度 | 交互 |
|------|------|------|
| 折叠（默认） | 0px | 显示悬浮 toggle 按钮（≡），点击展开 |
| 展开 | 260px | 显示完整会话列表，点击外部或再次点击 toggle 可折叠 |

### 4.2 会话列表项

```
┌─────────────────────────────┐
│  💬 会话名称                  │ ← 点击切换会话
│     2 小时前 · 3 条消息       │ ← 副标题（相对时间）
│  [⋯]                         │ ← hover 显示操作菜单（重命名/删除）
└─────────────────────────────┘
```

- **当前会话**：左侧 3px accent 色指示条，背景略深
- **hover**：背景变浅灰，显示操作菜单按钮（⋯）
- **操作菜单**：inline 下拉，包含 Rename / Delete
- **删除确认**：inline 二次确认（红色 "Confirm Delete" 按钮），不弹窗

### 4.3 新建会话

- 按钮位于侧边栏顶部：「+ New Chat」
- 点击后立即创建并切换到新会话
- 新会话默认名："New Session" + 当前时间

### 4.4 空状态

- 当会话列表为空时，侧边栏显示空状态提示
- "No conversations yet. Start a new chat!"

---

## 五、开发任务分解

### Step 1：Web UI 添加会话侧边栏（chat.html）

**文件**：`src/main/resources/webui/chat.html`

**内容**：
1. CSS：侧边栏布局、折叠/展开动画、会话项样式、操作菜单、响应式适配
2. HTML：侧边栏 DOM 结构（header、content、footer、toggle 按钮）
3. JS：
   - `renderSessions(sessions)` — 按时间分组渲染
   - `selectSession(sessionId)` — 切换会话高亮
   - `newSession()` — 发送 `new_session` 消息
   - `deleteSession(sessionId)` — 发送 `delete_session` + inline 确认
   - `renameSession(sessionId, name)` — inline 编辑发送 `rename_session`
   - `toggleSidebar()` — 展开/折叠
   - `onJavaMessage` 中新增 `set_sessions` / `session_created` / `session_switched` / `session_deleted` / `session_renamed` 处理

**检查项**：
- [ ] CSS 无泄漏，所有新选择器带 `.session-` 前缀
- [ ] 侧边栏展开/折叠动画 300ms 流畅
- [ ] 会话项 hover 显示操作菜单
- [ ] inline 重命名输入框按 Enter 确认，Escape 取消
- [ ] inline 删除确认：点击 Delete 后变为红色 Confirm Delete
- [ ] 空状态正确显示

### Step 2：JCEFChatPanel 添加会话消息处理

**文件**：`src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`

**内容**：
1. 在 `handleJSMessage` 中新增处理 `new_session`、`switch_session`、`delete_session`、`rename_session`、`request_sessions`
2. 新增回调接口：`var onSessionAction: ((SessionAction) -> Unit)? = null`
3. 新增 `SessionAction` sealed class 封装各类会话操作
4. 新增 `sendSessions(sessions)` 方法推送会话列表到前端

**检查项**：
- [ ] `handleJSMessage` 新增 case 不破坏原有消息处理
- [ ] 消息反序列化异常被捕获
- [ ] 回调为 null 时优雅降级（记录日志）

### Step 3：AgentToolWindowPanel 移除 Swing 侧边栏并集成

**文件**：`src/main/kotlin/com/codesage/ide/toolwindow/AgentToolWindowPanel.kt`

**内容**：
1. 移除 `SessionSidebarPanel` 相关代码（字段、setupUI 中的创建、所有回调）
2. 移除 `JBSplitter`，直接添加 `tabbedPane` 到面板
3. 在 `chatPanel.initialize` 之后设置 `chatPanel.onSessionAction` 回调
4. `createNewSession`、`switchSession`、`refreshSessionList` 等方法适配新架构
5. 初始化时主动推送会话列表到前端：`chatPanel.sendSessions(...)`

**检查项**：
- [ ] 编译无错误
- [ ] `SessionSidebarPanel` import 已移除
- [ ] `JBSplitter` import 已移除（若无其他用途）
- [ ] 新建会话、切换会话、删除会话、重命名会话功能完整
- [ ] 会话状态变更后前端列表自动刷新

### Step 4：编译验证 + 打包 + 验收

**内容**：
1. `./gradlew compileKotlin test`
2. `./gradlew buildPlugin -x buildSearchableOptions`
3. 按验收标准逐项检查

---

## 六、验收标准

### 6.1 功能验收

| # | 验收项 | 通过标准 |
|---|--------|---------|
| 1 | 侧边栏展开/折叠 | 点击 toggle 按钮，侧边栏以 300ms 动画平滑展开/折叠，无跳动 |
| 2 | 会话列表渲染 | 初始化时从 Kotlin 获取列表并按时间分组正确显示 |
| 3 | 新建会话 | 点击 "+ New Chat"，Kotlin 端创建新会话，前端列表自动添加并高亮新项 |
| 4 | 切换会话 | 点击会话项，Kotlin 端切换会话，主对话区域清空并加载新会话历史 |
| 5 | 重命名会话 | hover 会话项点击 ⋯ → Rename，inline 输入框出现，Enter 确认，Escape 取消 |
| 6 | 删除会话 | hover 会话项点击 ⋯ → Delete，inline 红色 Confirm Delete 出现，点击后删除 |
| 7 | 当前会话指示 | 当前会话左侧有 3px accent 色指示条，背景色与其他项区分 |
| 8 | 空状态 | 当无会话时，侧边栏显示空状态提示文案 |
| 9 | 主题适配 | 侧边栏在亮/暗主题下颜色协调，使用 CSS 变量 |
| 10 | 响应式 | 侧边栏展开时主内容区自动缩进，折叠时主内容区占满 |

### 6.2 回归验收

| # | 验收项 | 通过标准 |
|---|--------|---------|
| 11 | 消息发送 | 输入文字后 Enter / 点击发送按钮，消息正常发送并流式输出 |
| 12 | 模型选择 | 模型下拉菜单正常加载、切换 |
| 13 | 主题切换 | 亮/暗主题切换正常，侧边栏同步 |
| 14 | Kanban 面板 | Kanban 标签页正常显示，不受布局变更影响 |

### 6.3 性能验收

| # | 验收项 | 通过标准 |
|---|--------|---------|
| 15 | 首屏加载 | 侧边栏 HTML/JS 增量 < 10KB，首屏加载增加 < 50ms |
| 16 | 动画帧率 | 侧边栏展开/折叠动画 ≥ 55fps |
| 17 | 大数据量 | 50+ 会话列表滚动无卡顿 |

---

## 七、风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| JCEF 中 CSS `transition` 性能差 | 中 | 使用 `transform` 替代 `width` 过渡（若需要），或降低动画复杂度 |
| 会话列表频繁刷新导致闪烁 | 低 | 前端做 diff 更新，只变更 DOM 节点而非全量 innerHTML |
| 移除 Swing 侧边栏后部分用户不适应 | 低 | 保持 toggle 按钮始终可见，折叠态占用 0 空间 |

---

*设计方案完毕，进入执行阶段。*
