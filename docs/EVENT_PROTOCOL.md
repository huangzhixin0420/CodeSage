# CodeSage Kotlin ↔ JS 事件协议 v2

> 协议用于 `JCEFChatPanel` (Kotlin) ↔ `chat.js` (JS) 双向通信。

---

## 总览

```
┌────────────────┐  window.javaBridge.sendMessage  ┌──────────────────┐
│   JS (WebView) │ ──────────────────────────────>│ Kotlin (JCEF)   │
│   chat.js      │                                  │ JCEFChatPanel  │
│                │  window.onJavaMessage(json)      │                  │
│                │ <──────────────────────────────│                  │
└────────────────┘                                  └──────────────────┘
```

桥接:`JBCefJSQuery` (单函数调用),所有消息是 JSON 字符串。

---

## Kotlin → JS(27 种消息类型)

| `type` | 必填字段 | 可选字段 | 含义 |
|---|---|---|---|
| `text_delta` | `turnId`, `delta` | | 文本增量(流式) |
| `thinking_start` | `turnId`, `message` | | 思考开始(首条) |
| `thinking_update` | `turnId`, `message` | | 思考追加 |
| `thinking_complete` | `turnId` | `elapsedMs` | 思考完成 |
| `tool_call_start` | `turnId`, `toolId`, `toolName` | `summary`, `arguments`, `startTimeMs` | 工具开始 |
| `tool_call_delta` | `turnId`, `toolId`, `toolName`, `delta` | | 工具流式输出 |
| `tool_call_complete` | `turnId`, `toolId`, `success`, `result` | | 工具完成 |
| `tool_call_error` | `turnId`, `toolId`, `error` | | 工具失败 |
| `tool_confirmation_needed` | `turnId`, `toolId`, `toolName`, `arguments`, `reason` | | 需确认 |
| `turn_complete` | `turnId` | | 整个 turn 完成 |
| `error` | `turnId`, `message` | | 错误 |
| `artifact` | `artifactId`, `title`, `language`, `content` | | 产物 |
| `set_theme` | `theme` | | 主题设置 |
| `set_model` | `model` | `provider` | 当前模型 |
| `set_models` | `models` | | 可用模型列表 |
| `add_user_message` | `content` | | 添加用户消息 |
| `clear` | | | 清空对话 |
| `file_suggestions` | `query`, `suggestions` | | 文件搜索结果 |
| `file_references` | `turnId`, `references` | | 文件引用解析 |
| `budget_status` | `turnId`, `status`, `remainingIterations`, `remainingTokens`, `remainingSeconds`, `usagePercent` | | 预算状态 |
| `budget_exhausted` | `turnId`, `reason`, `consumedIterations`, `consumedTokens`, `elapsedSeconds`, `allowContinue` | | 预算耗尽 |
| `budget_extended` | `turnId`, `extraIterations`, `newRemainingIterations` | | 预算追加 |
| `plan_generated` | `turnId`, `planId`, `description`, `steps[]` | | 计划生成 |
| `plan_approved` | `turnId`, `planId` | | 计划批准 |
| `plan_rejected` | `turnId`, `planId`, `reason` | | 计划拒绝 |
| `plan_modified` | `turnId`, `planId`, `steps[]` | | 计划修改 |
| `context_compressed` | `turnId`, `originalTokens`, `compressedTokens`, `strategy` | | 上下文压缩 |
| `session_migrated` | `turnId`, `oldSessionId`, `newSessionId`, `messageCount` | | 会话迁移 |
| `set_sessions` | `sessions[]` | | 会话列表 |
| `session_created` | `session` | | 会话创建 |
| `session_switched` | `sessionId` | | 会话切换 |
| `session_deleted` | `sessionId` | | 会话删除 |
| `session_renamed` | `sessionId`, `name` | | 会话重命名 |
| `load_history` | `messages[]` | | 加载历史 |
| `mode_suggestion` | `turnId`, `effective`, `suggestion`, `userExplicit` | | T1.5 ChatMode 自动建议 |
| `settings_data` | `settings`, `path` | | Settings 推送 |
| `settings_saved` | | | Settings 保存成功 |
| `settings_error` | `message` | | Settings 错误 |
| `set_api_key_result` | `requestId`, `providerId`, `success` | `error` | P5.1 API Key 保存结果 |
| `test_provider_result` | `requestId`, `providerId`, `ok` | `latencyMs`, `httpStatus`, `error` | P5.1 连通性测试结果 |
| `legacy_migration_preview` | `requestId`, `hasData` | `preview`, `providers`, `newSettings` | P5.2 旧配置检测结果 |
| `legacy_migration_done` | `requestId`, `success`, `providerCount` | | P5.2 迁移完成 |
| `legacy_migration_skipped` | `requestId` | | P5.2 用户跳过 |
| `legacy_migration_error` | `requestId`, `message` | | P5.2 迁移错误 |
| `__client_ready__` | | | 前端就绪 |

### 关键消息示例

```json
// tool_call_start
{
  "type": "tool_call_start",
  "turnId": "t_123",
  "toolId": "tc_456",
  "toolName": "edit_file",
  "summary": "Edit src/main.kt",
  "arguments": {
    "path": "src/main.kt",
    "oldText": "...",
    "newText": "..."
  }
}

// tool_call_complete with diff
{
  "type": "tool_call_complete",
  "turnId": "t_123",
  "toolId": "tc_456",
  "success": true,
  "result": {
    "kind": "diff",
    "diff": [
      { "oldLine": 10, "newLine": 10, "type": "context", "text": "..." },
      { "oldLine": 11, "newLine": 11, "type": "remove", "text": "old" },
      { "oldLine": null, "newLine": 12, "type": "add", "text": "new" }
    ],
    "summary": "1 file changed, 12 insertions(+), 5 deletions(-)"
  }
}
```

---

## JS → Kotlin(34 种消息类型)

| `type` | 必填字段 | 可选字段 | 含义 |
|---|---|---|---|
| `send_message` | `content` | `turnId`, `chatMode`, `images[]` | 发送消息(images[] 为 P5.4 图片附件) |
| `stop_generation` | | | 停止生成 |
| `clear_session` | | | 清空会话 |
| `apply_artifact` | `artifactId`, `content` | | 应用产物到编辑器 |
| `create_file_from_artifact` | `artifactId`, `title`, `content` | | 创建新文件 |
| `regenerate` | `turnId` | | 重新生成 |
| `file_search` | `query` | | 搜索文件 |
| `switch_model` | `model` | | 切换模型 |
| `switch_chat_mode` | `mode` | | 切换 ChatMode(GENERAL/CODING/REASONING/VISION) |
| `theme_changed` | `theme` | | 主题变更 |
| `reload_browser` | | | 重载 WebView |
| `new_session` | | | 新会话 |
| `switch_session` | `sessionId` | | 切换会话 |
| `delete_session` | `sessionId` | | 删除会话 |
| `rename_session` | `sessionId`, `name` | | 重命名会话 |
| `request_sessions` | | | 请求会话列表 |
| `continue_task` | `turnId`, `extraIterations` | | 预算耗尽后继续 |
| `settings_get` | | | 获取当前 settings |
| `settings_update` | `settings` | | 更新 settings |
| `settings_reload` | | | 从磁盘重载 |
| `settings_open_folder` | | | 打开 settings 目录 |
| `settings_open_file` | | | 在 IDE 编辑器打开 settings.json |
| `set_api_key` | `providerId`, `apiKey` | `requestId` | **P5.1** 设置 Provider API Key(写入 PasswordSafe) |
| `test_provider` | `providerId`, `baseUrl` | `apiKey`, `model`, `requestId` | **P5.1** 探测 Provider 连通性 |
| `legacy_migration_check` | | `requestId` | **P5.2** 检查旧 IDE 配置 |
| `legacy_migration_run` | | `requestId` | **P5.2** 执行迁移 |
| `legacy_migration_skip` | | `requestId` | **P5.2** 跳过迁移 |
| `__client_error__` | `message`, `source` | `stack` | 前端错误上报 |
| `__client_ready__` | | | 前端就绪(启动时) |

---

## 实现细节

### Kotlin 侧(执行计划 P1.5)

- 事件路由抽到 `EventRouter.kt`,26 个事件用 `register<T> { e, turnId -> Map }` 模式
- `JCEFChatPanel` 内 Thinking 事件首/续状态管理(`thinkingStarted` map)
- `Done` 事件展开为 `thinking_complete` + `turn_complete`
- 事件去重:同类型 500ms 内丢弃

### JS 侧

- 入口:`bridge.onMessage = (data) => chat._handleBridgeMessage(data)`
- 路由:`chat._handleBridgeMessage` 巨 switch 分发
- 高频事件(text_delta / thinking_update)经 EventBus rAF 批合并
- 错误隔离:handler 抛错不影响其他 handler

### 消息序列化

- Kotlin `Map<String, Any?>` → `mapToJsonString` → JSON 字符串
- JS `JSON.parse(msg)` → 业务对象
- 嵌套 Map / List / String / Number / Boolean / null 自动处理

---

## 扩展示例

新增一个事件类型 "agent_retry":

**Kotlin** (`EventRouter.kt`):
```kotlin
register<AgentStreamEvent.AgentRetry> { e, turnId ->
    mapOf(
        "type" to "agent_retry",
        "turnId" to turnId,
        "attempt" to e.attempt,
        "delayMs" to e.delayMs,
    )
}
```

**JS** (`chat.js`):
```js
case "agent_retry":
    this._onAgentRetry(turnId, data.attempt, data.delayMs);
    break;
```

仅此 2 处,无其他改动。

---

## P5.4 图片附件协议

Web 端拖拽或粘贴图片后,`send_message` payload 包含 `images[]`:

```json
{
  "type": "send_message",
  "content": "看这张图",
  "turnId": "t-123",
  "images": [
    {
      "id": "img-1",
      "mime": "image/png",
      "dataUrl": "data:image/png;base64,iVBORw0KGgo...",
      "name": "screenshot.png"
    }
  ]
}
```

Kotlin 端 `ImageAttachment(id, mime, dataUrl, name)` 解析后:
- 注入到消息文本(markdown image 引用:`![](dataUrl)`)
- 适配多数多模态模型(GPT-4o、Claude-3、Gemini-1.5+、Kimi-VL、MiniMax-VL)
- 不支持视觉的模型会显示 toast 警告(根据 model 名字启发式判断)

## P5.1 API Key 与连通性测试

```json
// 设置 API Key(写入 IntelliJ PasswordSafe,不进 settings.json)
{
  "type": "set_api_key",
  "requestId": "req-1",
  "providerId": "minimax-default",
  "apiKey": "sk-xxx"
}

// 测试 Provider 连通性
{
  "type": "test_provider",
  "requestId": "req-2",
  "providerId": "minimax-default",
  "baseUrl": "https://api.minimaxi.com",
  "apiKey": "sk-xxx",       // 可选,留空则用 PasswordSafe 已存的 key
  "model": "MiniMax-M2.7"
}

// 返回
{
  "type": "test_provider_result",
  "requestId": "req-2",
  "ok": true,
  "latencyMs": 234,
  "httpStatus": 200
}
```
