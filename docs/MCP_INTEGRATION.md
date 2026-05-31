# MCP 集成指南

## 支持的协议版本

- **MCP Spec**: 2024-11-05
- **JSON-RPC**: 2.0

## 传输类型

| 类型 | 说明 | 配置字段 |
|------|------|----------|
| StdIO | 本地进程标准输入输出 | `command`, `args` |
| HTTP | 远程 HTTP 端点 | `url`, `headers` |
| WebSocket | WebSocket 连接 | `url` |

## 配置持久化

MCP 服务器配置保存在 IntelliJ 的 `CodeSagePlugin.xml` 中，通过 `PluginConfig.MCPServerPersistentConfig` 管理。

### 配置字段

```kotlin
class MCPServerPersistentConfig {
    var id: String = ""           // 唯一标识
    var name: String = ""         // 显示名称
    var transportType: String = "stdio"  // stdio | http | websocket
    var command: String = ""      // StdIO 命令
    var args: MutableList<String> = ArrayList()  // StdIO 参数
    var url: String = ""          // HTTP/WebSocket URL
    var enabled: Boolean = true   // 是否启用
}
```

## 自动连接

`CodeSageAppService` 初始化时，会自动连接所有 `enabled = true` 的 MCP 服务器：

1. 读取 `PluginConfig.mcpServerConfigs`
2. 根据 `transportType` 创建对应的 `TransportType`
3. 调用 `MCPServerManager.addServer()`
4. 成功后自动同步 `tools/list` 到 `SkillRegistry`

## JSON-RPC 通信

### Initialize 握手

所有 MCP 连接必须先完成 initialize 握手：

```
Client → Server: { "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "protocolVersion": "2024-11-05", ... } }
Server → Client: { "jsonrpc": "2.0", "id": 1, "result": { "protocolVersion": "2024-11-05", "serverInfo": { ... } } }
Client → Server: { "jsonrpc": "2.0", "method": "notifications/initialized" }
```

握手失败时，客户端会自动断开底层传输连接。

### 请求 ID 管理

`MCPConnection` 使用 `AtomicInteger` 原子计数器生成单调递增的 `id`，确保请求-响应正确对应。`listTools()` 和 `tools/call` 均遵守此规则。

### StdIO 传输

- 使用 `ProcessBuilder` 启动 MCP 服务器子进程
- `redirectErrorStream(true)` 合并 stderr 到 stdout，便于统一日志采集
- 消息格式：**单行 JSON + `\n`**（NDJSON）
- 优雅关闭流程：
  1. 关闭 `writer` 和 `reader`
  2. 调用 `process.destroy()`
  3. 等待 2 秒
  4. 若进程仍未退出，调用 `process.destroyForcibly()`

### 通知发送

`MCPTransport` 接口提供 `sendNotification(message)` 方法，默认实现调用 `send()`。`StdIOTransport` 覆盖此方法，仅写入不等待响应，避免 `notifications/initialized` 等无响应通知阻塞。

## MCP → Skill 桥接

每个 MCP 工具自动映射为 `MCPDelegatingSkill`：

- **Skill ID**: `mcp_${serverId}_${toolName}`
- **Skill 分类**: `CUSTOM`
- **标签**: `["mcp", "external"]`
- **输入/输出 Schema**: 直接复用 MCP 工具的 `inputSchema`
- **执行**: 通过 `MCPServerManager.callTool()` 转发到对应服务器，结果包装为 `SkillResult.Success` 或 `SkillResult.Failure`

注册后，`SkillToolAdapter` 会自动将 MCP 技能转换为 OpenAI Function Calling 格式的 `Tool` 定义，供 Agent 调用。

## 编程示例

### 手动添加 MCP 服务器

```kotlin
import com.codesage.mcp.server.MCPServerManager
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.TransportType

val config = MCPServerConfig(
    id = "filesystem",
    name = "File System Server",
    transportType = TransportType.StdIO(
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp")
    )
)

val manager = MCPServerManager(skillRegistry)
val status = manager.addServer(config)
// status: CONNECTED | ERROR
```

### 调用 MCP 工具

```kotlin
val result = manager.callTool(
    serverId = "filesystem",
    toolName = "read_file",
    args = mapOf("path" to "/tmp/example.txt")
)

when (result) {
    is SkillResult.Success -> println(result.output["content"])
    is SkillResult.Failure -> println("Error: ${result.error}")
}
```

### 同步工具到自定义注册表

```kotlin
val customRegistry = DynamicSkillRegistry()
val tools = manager.listTools("filesystem")
manager.syncToolsToRegistry("filesystem", customRegistry, tools)
```

## 故障排查

| 现象 | 可能原因 | 解决方案 |
|------|----------|----------|
| `ERROR` 状态 | 进程启动失败或命令不存在 | 检查 `command` 是否在 PATH 中 |
| 握手失败 | MCP 服务器未正确实现 initialize | 查看服务器日志，确认协议版本兼容 |
| 工具未注册 | `addServer` 后 `listTools` 为空 | 确认服务器支持 `tools/list` 方法 |
| 调用超时 | 服务器处理时间过长 | 调整 `MCPServerConfig.timeout` |
