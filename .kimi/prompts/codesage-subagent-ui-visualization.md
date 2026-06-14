# 提示词：实现 6.10.4 子 Agent 进度卡片可视化递归深度与工具白名单

## 1. 身份与目标

你是 CodeSage（Kotlin/JVM IntelliJ 平台插件）的核心开发 Agent。

当前任务：**把 6.10.2/6.10.3 新增的 `max_depth`、`allowed_tools`、`denied_tools` 在子 Agent 进度卡片中可视化**，让用户能直观看到子 Agent 的递归深度预算和可用工具范围。

## 2. 项目上下文

- 技术栈：Kotlin 2.3.20、IntelliJ Platform Gradle Plugin 2.16.0、Java 17、Swing UI + JCEF Web UI。
- 6.10.2/6.10.3 已完成后端改造：
  - `SubAgentExecutor.spawn(..., maxDepth, allowedTools, deniedTools)`
  - `EnhancedAgentLoop.executeDelegateTask()` 已解析并透传这些参数
- 当前 UI 基线：
  - `AgentStreamEvent.SubAgentStart(sessionId, taskDescription, toolset)` 仅携带基础信息
  - `SubAgentProgressPanel(sessionId, taskDescription, toolset)` 用 `metaLabel` 显示 `Toolset: $toolset | Session: ...`
  - `ChatPanel` 在收到 `SubAgentStart` 时创建 `SubAgentProgressPanel`

## 3. 具体需求

### 3.1 扩展事件协议

在 `AgentStreamEvent.SubAgentStart` 中新增字段（全部带默认值，保证向后兼容）：

- `maxDepth: Int = SubAgentExecutor.DEFAULT_MAX_RECURSION_DEPTH`
- `allowedTools: List<String> = emptyList()`
- `deniedTools: List<String> = emptyList()`

可选：同时新增 `depth: Int = 0`，用于展示“当前深度 / 最大深度”。

### 3.2 后端透传

在 `EnhancedAgentLoop.executeDelegateTask()` 中，`emit(SubAgentStart(...))` 时把解析到的 `maxDepth`、`allowedTools`、`deniedTools` 填入事件。

如果 `deniedTools` 包含 `delegate_task`，事件里可附加一个 `delegationForbidden: Boolean = true` 标志（可选，用于 UI 警示）。

### 3.3 UI 展示

修改 `SubAgentProgressPanel` 构造函数，接收上述新字段。

在卡片上增加或扩展元信息展示，例如：

- `Depth budget: 2` 或 `Depth: 1 / 2`
- `Allowed: read_file, edit_file`（仅在非空时显示）
- `Denied: delete_file`（仅在非空时显示）
- 若 `delegate_task` 被拒绝，显示 ⚠️ “Delegation forbidden” 红色提示

保持 UI 简洁：当 `allowedTools` 和 `deniedTools` 都为空时，不显示工具权限行。

所有 Swing 更新必须走 `SwingUtilities.invokeLater`。

### 3.4 事件路由

修改 `ChatPanel` 中创建 `SubAgentProgressPanel` 的位置，把 `SubAgentStart` 的新字段传入。

确保 `SubAgentComplete` / `SubAgentProgress` 的 sessionId 匹配逻辑不受影响。

## 4. 文件清单

- `src/main/kotlin/com/codesage/agent/core/AgentStreamEvent.kt`
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`
- `src/main/kotlin/com/codesage/ide/ui/components/chat/SubAgentProgressPanel.kt`
- `src/main/kotlin/com/codesage/ide/ui/components/chat/ChatPanel.kt`
- 测试文件：
  - 新建或更新 `src/test/kotlin/com/codesage/ide/ui/components/chat/SubAgentProgressPanelTest.kt`
  - 更新 `src/test/kotlin/com/codesage/agent/core/EnhancedAgentLoopDelegateTaskTest.kt`

## 5. 测试要求

至少新增/更新 4 个测试：

1. `SubAgentStart` 默认字段向后兼容（不传新字段时行为不变）。
2. `EnhancedAgentLoop` 在 `delegate_task` 调用时发出的 `SubAgentStart` 携带正确的 `maxDepth` / `allowedTools` / `deniedTools`。
3. `SubAgentProgressPanel` 在传入非空限制时能正确渲染文本（可用 `panel.components` 或反射读取 label 文本断言）。
4. 当 `deniedTools` 包含 `delegate_task` 时，面板显示警告文本。

## 6. 验收标准

- `./gradlew check` 全部通过。
- 旧 `SubAgentStart` 调用方（不传新字段）编译和行为均不变。
- 新增 public/internal API 必须带 KDoc。
- UI 线程安全，无 `SwingUtilities` 外直接修改组件。
- 不修改 `.github/workflows`、`.git` 或项目外文件。

## 7. 输出

完成后在对话中汇报：

- 修改了哪些文件；
- 新增/更新了哪些测试；
- `./gradlew check` 结果；
- 是否还有遗留的边界情况。
