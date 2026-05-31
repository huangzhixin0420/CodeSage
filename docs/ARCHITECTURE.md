# CodeSage - AI Agent IDE Plugin Architecture

## 1. Project Overview

### 1.1 Goal
Develop an IntelliJ plugin with Claude Code Agent capabilities, supporting:
- Task planning and decomposition
- Smart coding and code generation
- Skill extension system (dual-track: code-based + declarative)
- Rule engine with hook mechanisms
- MCP (Model Context Protocol) support
- Multi-model support (MiniMax, Kimi)

### 1.2 Tech Stack
- Language: Kotlin
- Platform: IntelliJ IDEA (Community/Ultimate)
- License: MIT
- Models: MiniMax, Kimi (OpenAI-compatible)

---

## 2. Overall Architecture

```
+------------------------------------------------------------------+
|                     Presentation Layer                           |
|         (IDE UI: Tool Window, Editor, Status Bar, Shortcuts)      |
+------------------------------------------------------------------+
|                     Skill System Layer                           |
|    (Skill Registry, Discovery, Executor, Result Aggregator)       |
+------------------------------------------------------------------+
|                     Agent Core Layer                             |
|  (Task Planner, Multi-Agent Orchestrator, Context Manager)        |
+------------------------------------------------------------------+
|                     Rule Engine Layer                            |
|      (Rule Parser, Matcher, Hook Mechanism, Action Executor)     |
+------------------------------------------------------------------+
|                     MCP Protocol Layer                           |
|        (MCP Server, Transport, Protocol Bridge)                   |
+------------------------------------------------------------------+
|                     Model Adapter Layer                          |
|            (MiniMax, Kimi, OpenAI-compatible)                    |
+------------------------------------------------------------------+
|                  Platform Interface Layer                        |
|              (IntelliJ SDK: File, PSI, Run, Notification)         |
+------------------------------------------------------------------+
```

---

## 3. Multi-Model Unified Interface (Model Adapter)

### 3.1 Core Interface

```kotlin
interface ModelAdapter {
    val providerName: String  // "minimax", "kimi"
    val supportedModels: List<String>
    
    fun supportsStreaming(): Boolean
    fun supportsFunctionCalling(): Boolean
    fun supportsVision(): Boolean
    
    fun toVendorRequest(request: ChatRequest): VendorRequest
    fun toVendorResponse(vendorResponse: VendorResponse): ChatResponse
    fun parseStreamChunk(chunk: String): StreamChunk?
    fun mapError(error: VendorError): AppException
}

class ModelRegistry {
    private val adapters = mutableMapOf<String, ModelAdapter>()
    
    fun register(adapter: ModelAdapter) {
        adapter.supportedModels.forEach { model ->
            adapters[model] = adapter
        }
    }
    
    fun getAdapterForModel(model: String): ModelAdapter?
    fun listAvailableModels(): List<ModelInfo>
}
```

### 3.2 Unified Data Structures

```kotlin
data class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any>
)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: Message,
    val finishReason: String?
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)
```

### 3.3 Configuration Management

- API Key storage: IntelliJ Password Safe
- Multi-model configuration switching
- Per-model parameters (temperature, maxTokens, etc.)

---

## 4. Agent Core Layer

### 4.1 Sub-modules

```
AgentCore
+-- TaskPlanner           // Task decomposition and planning
+-- ContextManager        // Hierarchical context management
+-- MultiAgentOrchestrator // Multi-agent parallel collaboration
+-- ToolOrchestrator      // Tool call orchestration
+-- AgentSession          // Session lifecycle management
```

### 4.2 Multi-Agent Collaboration

```kotlin
enum class AgentRole {
    PLANNER,    // Task decomposition
    CODER,      // Code writing
    REVIEWER,   // Code review
    TESTER,     // Test generation
    RESEARCHER  // Information gathering
}

interface Agent {
    val name: String
    val role: AgentRole
    val capabilities: List<Capability>
    val systemPrompt: String
    
    suspend fun process(task: Task): AgentResult
}

data class Capability(
    val name: String,
    val description: String,
    val toolAccess: List<String>
)

class MultiAgentOrchestrator {
    suspend fun executeTask(task: Task): TaskResult {
        // 1. Planner analyzes and decomposes task
        // 2. Coder/Reviewer/Tester execute in parallel
        // 3. Aggregator collects and summarizes results
    }
}
```

### 4.3 Collaboration Flow

```
User Input: "Refactor this module and add tests"
                    |
                    v
            +-------------+
            |   Planner   |
            |(decompose) |
            +------+------+
                   |
    +------+------+------+
    v      v      v
+------+ +------+ +------+
| Coder| |Review| |Tester|
+--+---+ +--+---+ +--+---+
   +------+------+------+
                   |
                   v
            +-------------+
            | Aggregator  |
            +------+------+
                   |
                   v
               Output
```

### 4.4 Error Recovery & Resilience

```kotlin
enum class FailoverReason {
    RATE_LIMIT, AUTH_EXPIRED, CONTEXT_TOO_LONG,
    IMAGE_TOO_LARGE, MULTIMODAL_UNSUPPORTED,
    TIMEOUT, EMPTY_RESPONSE, INCOMPLETE_SCRATCHPAD,
    INVALID_TOOL_CALL, INVALID_JSON, PROVIDER_UNAVAILABLE, UNKNOWN
}

sealed class RecoveryAction {
    data class RetryWithModel(val model: String, val delayMs: Long = 0) : RecoveryAction()
    data class CompressAndRetry(val auxiliaryModel: String? = null) : RecoveryAction()
    data class RefreshAndRetry(val delayMs: Long = 1000) : RecoveryAction()
    data class SimpleRetry(val delayMs: Long, val prefill: String? = null) : RecoveryAction()
    data class Abort(val message: String) : RecoveryAction()
}
```

**设计要点：**

1. **错误分类引擎 (`AgentErrorRecovery.classify`)**
   - 将原始异常映射为 12 种 `FailoverReason`
   - 提取 HTTP 状态码，结合错误消息做语义匹配
   - 运算符优先级已显式括号化，确保 `413 context too long` 与 `429 rate limit` 正确区分

2. **恢复动作与 Agent 状态联动 (`recover`)**
   - `recover()` 直接修改传入的 `AgentCore` 实例状态：
     - `RetryWithModel` → 调用 `agent.switchModel(...)`
     - `CompressAndRetry` → 调用 `agent.compressContext()`，触发 `ContextCompressor`
   - `EnhancedAgentLoop` 持有 `agentCore` 引用，错误恢复时同步更新本地模型变量

3. **上下文压缩重试 (`CompressAndRetry`)**
   - 当 `CONTEXT_TOO_LONG` 发生时，`EnhancedAgentLoop` 进入 `ERROR_RECOVERY` 阶段
   - 调用 `ContextManager.compressContext()` 主动压缩历史消息（保留头尾、生成结构化摘要）
   - 退还迭代预算，最多重试 2 次（由 `maxRetries[CONTEXT_TOO_LONG]` 控制）

4. **重试计数器隔离 (`retryCounters`)**
   - 使用 `ConcurrentHashMap<String, AtomicInteger>` 保证线程安全
   - Key 格式：`"${FailoverReason.name}:${modelName}"`
   - 同一错误原因在不同模型间切换时，计数器独立，避免跨模型过早 abort

---

## 5. Context Truncation Strategy

### 5.1 Configurable Strategies

```kotlin
enum class TruncationStrategy {
    KEEP_RECENT,  // Keep recent messages
    SUMMARIZE,    // Generate summary压缩)
    RAG检索,     // Vector retrieval
    HYBRID       // Combined approach
}

data class ContextManagementConfig(
    val truncationStrategy: TruncationStrategy = TruncationStrategy.HYBRID,
    val maxHistoryMessages: Int = 50,
    val summarizeThreshold: Int = 30,
    val preserveSystemMessages: Boolean = true,
    val preserveLastNMessages: Int = 3,
    val enableContextEngine: Boolean = true,
    val contextLength: Int = ContextEngine.DEFAULT_CONTEXT_LENGTH,
    val auxiliaryModel: String? = null  // 轻量级 LLM 用于摘要（如 moonshot-v1-8k）
)
```

### 5.2 Strategy Comparison

| Strategy    | Scenario      | Pros            | Cons          |
|-------------|---------------|-----------------|---------------|
| Keep Recent | Short dialog  | Simple, efficient | Loses early context |
| Summarize   | Medium length | Preserves topic | Information loss |
| RAG         | Complex project| Precise recall | Complex |
| Hybrid      | General       | Balanced        | Most complex |

### 5.3 LLM 驱动的结构化摘要（SUMMARIZE）

当历史消息超过 `summarizeThreshold`（默认 30 条）时，`ContextCompressor` 提供两种摘要能力：

1. **规则摘要（默认）**：`compress()` 使用正则引擎提取关键信息，无需外部 LLM：
   - `## Active Task`：从最近用户消息推断当前任务
   - `## Resolved Decisions`：检测 "decided/chosen/use" 等决策模式
   - `## Files Modified`：提取文件路径和工具调用中的文件操作
   - `## Pending Questions`：检测未回答的问句
   - `## Tool Calls Summary`：汇总工具调用记录

2. **LLM 摘要（增强）**：`summarizeWithLLM()` 调用轻量级模型（通过 `ModelGateway`）生成结构化摘要。请求体包含中间消息的完整内容，LLM 返回与规则摘要兼容的格式。LLM 调用失败时自动降级为规则摘要，再失败降级为 `keepRecent()`。

摘要消息以 `Message.systemMessage("[CONTEXT SUMMARY] ...")` 形式插入系统消息之后、头部保护区之前。

### 5.4 RAG 记忆召回（RAG检索）

`ContextManager` 构造函数可注入 `MemoryProvider`（如 `BuiltInMemoryProvider`）。当触发 `ragRetrieval()` 时：

1. 取最近一条 `USER` 消息作为查询 `query`
2. 调用 `memoryProvider.prefetch(query, sessionId)`，通过 SQLite + FTS5 全文检索召回相关记忆片段
3. 将召回结果格式化为 `Message.systemMessage("[RELEVANT CONTEXT] ...")` 插入系统消息之后
4. 若 `memoryProvider` 为 null 或召回为空，降级为 `keepRecent()`

`BuiltInMemoryProvider` 基于本地 SQLite，无需网络调用，召回延迟 < 10ms。

### 5.5 HYBRID 混合策略 — Token 预算分配

HYBRID 策略实现完整流水线，按 token 预算比例分配上下文窗口：

| 区域 | 预算占比 | 内容 |
|------|---------|------|
| 头部保护区 | 20% | 最早的 `protectFirstN` 条对话（默认 3 对） |
| 结构化摘要 | 40% | LLM/规则摘要，保留关键决策和工具调用 |
| RAG 片段 | 20% | `BuiltInMemoryProvider` 召回 Top-K 记忆 |
| 尾部保护区 | 20% | 最近的 `protectLastN` 条对话（默认 6 对） |

实现细节：
- 先计算各区域预估 token 数，若摘要超出 40% 预算则截断摘要（优先保留 `## Resolved Decisions` 和 `## Tool Calls Summary`）
- RAG 片段按相关性排序，超出 20% 预算时截断尾部记忆
- 最终消息列表顺序：`系统消息` → `RAG片段` → `结构化摘要` → `头部` → `尾部`
- 所有区域通过 `TokenEstimator` 做中文/英文/代码混合估算

降级链路：LLM 摘要失败 → 规则摘要 → `keepRecent()`；RAG 失败 → `keepRecent()`。确保任何外部依赖故障都不中断对话。

### 5.6 线程安全与会话隔离

- `ContextManager.history` 使用 `CopyOnWriteArrayList`，读操作无锁
- 所有写操作（`addMessage`、`truncate`、`compress` 等）通过 `synchronized(historyLock)` 保护，防止多线程竞争导致消息丢失
- **`getInstance()` 已标记 `@Deprecated("Use per-session instance instead")`**，内部不再缓存单例，每次返回新实例
- `AgentCore` 中每个会话持有独立的 `ContextManager`，避免多会话上下文串扰

---

## 6. Skill System

### 6.1 Dual-Track Design

```
+------------------------------------------+
|           Skill Registry                 |
+------------------+-----------------------+
|  Built-in Skills |   External Skills     |
|  (Code-based)    |   (Declarative JSON)  |
+------------------+-----------------------+
| - file_reader    | - MCP tools           |
| - file_writer    | - HTTP APIs           |
| - command_exec   | - Scripts             |
| - git_operation  |                       |
+------------------+-----------------------+
```

### 6.2 Core Interface

```kotlin
interface Skill {
    val id: String
    val name: String
    val description: String
    val version: String
    val category: SkillCategory
    val tags: Set<String>
    val inputSchema: JsonSchema
    val outputSchema: JsonSchema
    
    fun canExecute(context: ExecutionContext): CanExecuteResult
    suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult
}

enum class SkillCategory {
    FILE_OPERATION, CODE_SEARCH, EXECUTION,
    NETWORK, GIT, AI_INTEGRATION, CUSTOM
}

data class SkillInput(val arguments: Map<String, Any>) {
    fun get<T>(key: String): T = arguments[key] as T
    fun toMap(): Map<String, Any> = arguments
}

sealed class SkillResult {
    data class Success(val output: Map<String, Any>) : SkillResult()
    data class Failure(val error: Throwable) : SkillResult()
    val isSuccess: Boolean get() = this is Success
}
```

### 6.3 Hot Reload Mechanism

```kotlin
interface SkillHotReloadable {
    fun getVersion(): String
    fun needsReload(otherVersion: String): Boolean
    suspend fun reload(newSkill: Skill)
}

class HotReloadableSkillRegistry : SkillRegistry() {
    private val versionCache = ConcurrentHashMap<String, String>()
    
    fun update(skill: Skill) {
        synchronized(this) {
            skills[skill.id] = skill
            versionCache[skill.id] = UUID.randomUUID().toString()
        }
        notifyChange(SkillChangeEvent.Updated(skill))
    }
}

class ConfigFileWatcher {
    // File system watcher for YAML/JSON skill definitions
    // Automatic reload on file changes
}

class MCPServiceReloader {
    // Periodic check for MCP tool updates
    // Auto-sync tool list from MCP servers
}
```

### 6.4 Built-in Skills

| Skill ID                  | Name              | Category       |
|---------------------------|-------------------|----------------|
| builtin_file_reader       | File Reader       | FILE_OPERATION |
| builtin_file_writer       | File Writer       | FILE_OPERATION |
| builtin_file_search       | File Search       | FILE_OPERATION |
| builtin_command_execution | Command Execution | EXECUTION      |
| builtin_git_operation     | Git Operation     | GIT            |
| builtin_web_request       | HTTP Request      | NETWORK        |
| builtin_code_search       | Code Search       | CODE_SEARCH    |
| builtin_project_analysis  | Project Analysis  | CODE_SEARCH    |

---

## 7. Rule Engine

### 7.1 Rule Structure

```kotlin
data class Rule(
    val id: String,
    val name: String,
    val description: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition>,
    val actions: List<RuleAction>,
    val metadata: RuleMetadata
)

sealed class RuleTrigger {
    data class OnEvent(val eventType: EventType) : RuleTrigger()
    data class OnSchedule(val cronExpression: String) : RuleTrigger()
    data class OnCondition(val condition: RuleCondition) : RuleTrigger()
    object Manual : RuleTrigger()
}

enum class EventType {
    TASK_STARTED, TASK_COMPLETED, TASK_FAILED,
    SKILL_EXECUTED, SKILL_FAILED,
    AGENT_MESSAGE, USER_MESSAGE,
    FILE_CHANGED, PROJECT_OPENED
}

enum class ConditionOperator {
    EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS,
    STARTS_WITH, ENDS_WITH, GREATER_THAN, LESS_THAN,
    REGEX_MATCH, IN_LIST, EXISTS
}

enum class ActionType {
    SEND_MESSAGE, SEND_NOTIFICATION,
    TRANSFORM_CONTEXT, APPEND_CONTEXT,
    SET_VARIABLE, CLEAR_VARIABLE,
    RUN_SKILL, RUN_AGENT,
    PRE_HOOK, POST_HOOK,
    TERMINATE_TASK, RETRY_TASK
}
```

### 7.2 Rule Example (YAML)

```yaml
rules:
  - id: "auto_retry_on_failure"
    name: "Failure Auto Retry"
    description: "Auto retry when skill execution fails"
    priority: 5
    enabled: true
    trigger:
      type: "OnEvent"
      eventType: "SKILL_FAILED"
    conditions:
      - field: "skill.retryCount"
        operator: "LESS_THAN"
        value: 3
    actions:
      - actionType: "RETRY_TASK"
        parameters:
          delayMs: 1000

  - id: "long_task_warning"
    name: "Long Task Warning"
    description: "Notify when task exceeds 5 minutes"
    priority: 8
    enabled: true
    trigger:
      type: "OnCondition"
      condition:
        field: "context.task.durationMs"
        operator: "GREATER_THAN"
        value: 300000
    actions:
      - actionType: "SEND_NOTIFICATION"
        parameters:
          title: "Task Taking Long Time"
          message: "Task has been running for {context.task.durationMs}ms"
```

### 7.3 Hook Mechanism

```kotlin
class AgentHooks {
    suspend fun beforeAgentRun(agent: Agent, task: Task): Task
    suspend fun afterAgentRun(agent: Agent, result: AgentResult): AgentResult
}

class SkillHooks {
    suspend fun beforeSkillExecute(skill: Skill, input: Map<String, Any>): Map<String, Any>
    suspend fun afterSkillExecute(skill: Skill, result: SkillResult): SkillResult
}
```

---

## 8. MCP Protocol

### 8.1 Transport Types

```kotlin
sealed class TransportType {
    data class StdIO(
        val command: String,
        val args: List<String> = emptyList()
    ) : TransportType()
    
    data class HTTP(
        val url: String,
        val headers: Map<String, String> = emptyMap()
    ) : TransportType()
    
    data class WebSocket(val url: String) : TransportType()
}

data class MCPServerConfig(
    val id: String,
    val name: String,
    val transportType: TransportType,
    val auth: MCPAuthConfig? = null,
    val timeout: Long = 30000,
    val autoReconnect: Boolean = true
)
```

### 8.2 MCP Client

```kotlin
class MCPClient(private val config: MCPServerConfig) {
    suspend fun connect(): MCPConnection?
    suspend fun disconnect()
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(toolName: String, args: Map<String, Any>): MCPResource?
    fun isConnected(): Boolean
}

class MCPConnection(val config: MCPServerConfig, private val transport: MCPTransport) {
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun initialize(): Boolean        // MCP Spec 2024-11-05 握手
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(toolName: String, arguments: Map<String, Any>): MCPResource?
    fun isActive(): Boolean
}
```

**Initialize 握手流程：**
1. `transport.connect()` 建立传输层连接
2. 发送 `initialize` request（protocolVersion = "2024-11-05"）
3. 接收 `initialize` response，提取 `serverInfo`
4. 发送 `notifications/initialized` notification
5. 握手失败时自动断开传输层

**请求 ID 管理：**
- `MCPConnection` 内部维护 `AtomicInteger` 计数器
- `listTools()`、`callTool()` 均使用单调递增的 JSON-RPC `id`

### 8.3 MCP Server Manager

```kotlin
class MCPServerManager(private val skillRegistry: SkillRegistry = SkillRegistry.getInstance()) {
    suspend fun addServer(config: MCPServerConfig): MCPServerStatus
    suspend fun removeServer(serverId: String)
    suspend fun callTool(serverId: String, toolName: String, args: Map<String, Any>): SkillResult
    suspend fun listTools(serverId: String): List<McpTool>
    fun getAllServerStatuses(): Map<String, MCPServerStatus>
    suspend fun disconnectAll()
    fun syncToolsToRegistry(serverId: String, registry: SkillRegistry, tools: List<McpTool>)
}
```

**自动同步机制：**
- `addServer()` 成功后自动调用 `listTools()`
- 通过 `syncToolsToRegistry()` 将每个 `McpTool` 包装为 `MCPDelegatingSkill`
- 注册到指定的 `SkillRegistry`（默认全局实例）

### 8.4 MCP to Skill Bridge

```kotlin
class MCPDelegatingSkill(
    override val id: String,           // mcp_${serverId}_${toolName}
    private val toolName: String,
    private val serverId: String,
    private val tool: McpTool,
    private val serverManager: MCPServerManager
) : Skill {
    override val name: String = tool.name
    override val description: String = tool.description
    override val category: SkillCategory = SkillCategory.CUSTOM
    override val tags: Set<String> = setOf("mcp", "external")
    override val inputSchema: Map<String, Any> = tool.inputSchema
    
    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return serverManager.callTool(serverId, toolName, input.arguments)
    }
}
```

### 8.5 配置持久化与自动连接

`PluginConfig` 提供 `MCPServerPersistentConfig` 支持 UI 配置的保存/加载。`CodeSageAppService.init()` 自动遍历所有启用的 MCP 服务器配置并完成连接：

```kotlin
// 自动连接流程
scope.launch {
    config.mcpServerConfigs
        .filter { it.enabled }
        .forEach { persistentConfig ->
            val transportType = when (persistentConfig.transportType.lowercase()) {
                "stdio" -> TransportType.StdIO(command, args)
                "http" -> TransportType.HTTP(url)
                "websocket" -> TransportType.WebSocket(url)
            }
            val serverConfig = MCPServerConfig(id, name, transportType)
            mcpServerManager.addServer(serverConfig)
        }
}
```
```

---

## 9. IDE Integration

### 9.1 Core Components

| Component                    | Responsibility                    |
|------------------------------|-----------------------------------|
| AgentToolWindowFactory       | Main tool window UI               |
| ConversationPanel            | Chat interaction interface        |
| TaskPanel                    | Task status tree                  |
| EditorNotificationProvider   | In-editor notifications           |
| AICodeCompletionContributor  | Code completion                   |
| ProjectIntegration           | Project/file/PSI integration      |
| PluginSettingsConfigurable   | Settings page                     |
| NotificationService          | Notification feedback             |
| AgentActionGroup             | Shortcuts and menus               |
| AgentStatusWidget            | Status bar widget                 |

### 9.2 UI Layout

```
+-----------------------------------------------------------+
| Project Explorer      |       AI Agent Window              |
|                       | +--------------------------------+ |
|                       | | Conversation Area              | |
|                       | |                                | |
|                       | +--------------------------------+ |
|                       | | Task Status                    | |
|                       | | [] Step 1                      | |
|                       | | [x] Step 2                     | |
|                       | +--------------------------------+ |
|                       | | Input: ...              [Send] | |
+-----------------------------------------------------------+
| [AI] Agent - Idle                           Ln 1, Col 1   |
+-----------------------------------------------------------+
```

---

## 9.3 Symbol Index & Semantic Search

### 9.3.1 SymbolIndex 设计

`SymbolIndex` 是 CodeSage 的代码符号缓存层，基于 IntelliJ PSI 构建，提供快速符号查找与继承关系索引。核心设计如下：

**增量索引（Incremental Indexing）**
- 维护 `indexedFileHashes: ConcurrentHashMap<String, Long>`，记录每个已索引文件的 `modificationStamp`。
- `buildIndex()` 时，仅当文件_stamp 发生变更或新增时才调用 `PSIAnalyzer.analyzeFileDeep()`；未变更文件直接跳过。
- 同时清理 `indexedFileHashes` 中已不在项目文件集合中的条目，移除对应符号，避免幽灵索引。
- 性能目标：修改单个文件后重新构建，分析耗时 < 首次构建的 10%。

**原子更新（Atomic Update）**
- 使用 `ReentrantReadWriteLock` 保护所有可变索引状态：
  - 写操作（`buildIndex` / `updateFile`）持有写锁，确保 `remove` + `analyze` + `add` 三步原子化。
  - 读操作（`findByName`、`fuzzySearch`、`findImplementations` 等）持有读锁，支持并发查询。
- 消除 `updateFile` 旧实现中 "remove 后、add 前" 的空窗期，避免并发搜索看到符号丢失。

**继承反向索引（Inheritance Index）**
- 新增 `inheritanceIndex: ConcurrentHashMap<String, CopyOnWriteArrayList<SymbolInfo>>`：
  - Key = `superType` 全限定名或简单名（如 `"MyInterface"`、`"java.lang.Runnable"`）。
  - Value = 实现/继承该类型的所有 `SymbolInfo` 列表。
- 在 `buildIndex` / `updateFile` 维护索引时，自动将符号注册到其 `superTypes` 对应桶中。
- `findImplementations(interfaceName)` 从 O(n) 全表扫描优化为 O(1) Map 查询，10k 符号场景下 < 1ms。

**PSI 解耦（PSI Decoupling）**
- `SymbolInfo` 为纯数据类，所有字段均为 `String` / `Int` / `List<String>` 等基本类型，不持有任何 `PsiElement` 或 `Project` 引用。
- 分析完成后即与 PSI 树解耦，杜绝内存泄漏风险。

### 9.3.2 SemanticSearch 设计

`SemanticSearch` 基于 `SymbolIndex` 提供多策略智能搜索（精确匹配、模糊匹配、签名匹配、注释匹配）。

**查询结果缓存（Query Cache）**
- 采用简单 LRU Map（`LinkedHashMap` 子类），容量 100 条，TTL 60 秒，无外部依赖。
- 缓存 Key 格式：`"${method}:${query.lowercase().trim()}:$limit:${symbolIndex.version}"`
- 纳入 `symbolIndex.version`：当 `SymbolIndex` 发生 `buildIndex()` 或 `updateFile()` 导致 `version` 递增时，旧缓存 Key 自动失效，无需显式通知。
- 缓存命中时返回结果副本（`results.map { it.copy() }`），防止外部修改污染缓存。
- 已缓存方法：`search()`、`semanticQuery()`、`findRelatedClasses()`。
- 未缓存方法：`findDefinition()`（O(1) 精确查询）、`findMethodCalls()`（结果集小、实时性要求高）。

---

## 10. Development Phases

### Phase 1: Core Skeleton (4-6 weeks)

| Module             | Tasks                              | Priority |
|--------------------|------------------------------------|----------|
| Project Init       | Gradle config, plugin structure   | P0       |
| Model Adapter      | MiniMax/Kimi adapter              | P0       |
| Agent Core         | Basic chat loop, session management| P0       |
| Skill Registry     | Built-in skill registration        | P1       |
| IDE Integration    | Tool window, basic UI              | P1       |
| Config Management  | Settings page, API key storage    | P2       |

### Phase 2: Capability Extension (6-8 weeks)

| Module             | Tasks                              | Priority |
|--------------------|------------------------------------|----------|
| Task Planner       | Multi-round planning, task breakdown| P0      |
| Multi-Agent        | Agent collaboration orchestration  | P0       |
| Context Manager    | Hierarchical context, truncation  | P1       |
| MCP Client         | MCP protocol support               | P1       |
| Rule Engine        | Rule parsing, hook mechanism       | P2       |
| Hot Reload         | Skill/MCP hot update               | P2       |

### Phase 3: Experience Optimization (4-6 weeks)

| Module             | Tasks                              | Priority |
|--------------------|------------------------------------|----------|
| Editor Integration  | Completion, highlighting           | P1       |
| Shortcuts/Menus    | Custom shortcuts, context menus    | P1       |
| Notification System| Status bar, progress notifications | P1       |
| Skill Extension    | Declarative skills, plugin ext points| P2     |
| Documentation      | User guide, API documentation      | P2       |

---

## 11. Technical Decisions Summary

| Decision Point       | Choice                   | Reason                               |
|----------------------|--------------------------|--------------------------------------|
| Open Source License  | MIT                      | Low barrier, maximum adoption        |
| Skill Extension      | Dual-track (code+declarative) | Flexibility and security balance |
| Context Strategy     | Configurable (default Hybrid)| Adaptable to different scenarios |
| Tool Call Protocol   | JSON Schema + MCP ready  | Good compatibility, future extensible|
| Key Storage          | IntelliJ Password Safe   | Native, secure, familiar to users    |
| MCP Transport        | StdIO + HTTP             | Covers local and remote scenarios    |

---

## 12. Directory Structure

```
CodeSage/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── src/main/
│   ├── kotlin/com/codesage/
│   │   ├── plugin/
│   │   │   ├── CodeSageAppService.kt         # 应用级服务
│   │   │   ├── CodeSageProjectService.kt     # 项目级服务
│   │   │   └── CodeSageStartupActivity.kt    # 启动活动
│   │   ├── model/
│   │   │   ├── adapter/
│   │   │   │   ├── ModelAdapter.kt           # 适配器接口
│   │   │   │   ├── OpenAICompatibleAdapter.kt # OpenAI兼容基类
│   │   │   │   ├── kimi/
│   │   │   │   │   └── KimiAdapter.kt
│   │   │   │   └── minimax/
│   │   │   │       └── MiniMaxAdapter.kt
│   │   │   ├── gateway/
│   │   │   │   └── ModelGateway.kt
│   │   │   ├── registry/
│   │   │   │   └── ModelRegistry.kt
│   │   │   └── dto/
│   │   │       ├── ChatModels.kt
│   │   │       └── Message.kt
│   │   ├── agent/
│   │   │   ├── core/
│   │   │   │   └── AgentCore.kt
│   │   │   ├── planner/
│   │   │   │   └── TaskPlanner.kt
│   │   │   ├── context/
│   │   │   │   └── ContextManager.kt
│   │   │   └── multiagent/
│   │   │       └── MultiAgentOrchestrator.kt
│   │   ├── skill/
│   │   │   ├── Skill.kt                      # 核心接口
│   │   │   ├── registry/
│   │   │   │   └── SkillRegistry.kt
│   │   │   ├── executor/
│   │   │   │   └── SkillExecutor.kt
│   │   │   ├── discovery/
│   │   │   │   ├── ConfigFileWatcher.kt
│   │   │   │   └── DeclarativeSkill.kt       # 声明式技能
│   │   │   └── builtin/
│   │   │       ├── BuiltInSkills.kt
│   │   │       ├── GitAndCommandSkills.kt
│   │   │       └── NetworkAndSearchSkills.kt
│   │   ├── rule/
│   │   │   ├── Rule.kt
│   │   │   ├── parser/
│   │   │   │   └── RuleParser.kt
│   │   │   ├── matcher/
│   │   │   │   └── RuleMatcher.kt
│   │   │   ├── engine/
│   │   │   │   └── RuleEngine.kt
│   │   │   └── actions/
│   │   │       └── RuleActionExecutor.kt
│   │   ├── mcp/
│   │   │   ├── client/
│   │   │   │   └── MCPClient.kt
│   │   │   ├── transport/
│   │   │   │   ├── TransportType.kt
│   │   │   │   └── StdIOTransport.kt
│   │   │   └── server/
│   │   │       └── MCPServerManager.kt
│   │   ├── ide/
│   │   │   ├── toolwindow/
│   │   │   │   ├── AgentToolWindowFactory.kt
│   │   │   │   └── AgentToolWindowPanel.kt
│   │   │   ├── settings/
│   │   │   │   └── PluginSettingsConfigurable.kt
│   │   │   └── actions/
│   │   │       └── AgentActionGroup.kt
│   │   └── shared/
│   │       ├── config/
│   │       │   └── PluginConfig.kt
│   │       ├── utils/
│   │       │   └── Logger.kt
│   │       └── exceptions/
│   │           └── AppException.kt
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml
│       ├── rules/
│       │   └── example-rules.yaml
│       └── skills/
│           └── builtin-skills.yaml
├── src/test/
│   └── kotlin/com/codesage/
│       ├── agent/
│       │   └── AgentCoreTest.kt
│       ├── model/
│       │   ├── ModelAdapterTest.kt
│       │   └── OpenAICompatibleAdapterTest.kt
│       ├── skill/
│       │   └── SkillExecutorTest.kt
│       ├── rule/
│       │   └── RuleEngineTest.kt
│       └── shared/
│           └── HtmlEscapeTest.kt
└── docs/
    └── ARCHITECTURE.md
```

---

End of Architecture Document
