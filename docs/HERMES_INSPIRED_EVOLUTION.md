# CodeSage 智能化演进方案 — 学习 Hermes Agent 实现真正智能的 AI Agent

> 本文档基于对 [NousResearch/hermes-agent](https://github.com/nousresearch/hermes-agent) 核心源码（`conversation_loop.py` 4244行、`memory_manager.py`、`context_engine.py`、`context_compressor.py`、`tools/registry.py` 等）的深度分析，结合 CodeSage 当前架构，提出从「基础对话助手」进化为「真正智能化 Agent」的完整路线图。

---

## 一、核心差距诊断：CodeSage 当前在哪里？Hermes 已经走到哪里？

### 1.1 Agent 对话循环（Conversation Loop）

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **循环复杂度** | `chatWithTools()` 约 100 行，简单 `while (iteration < maxIterations)` | `run_conversation()` 4244 行，状态机驱动的完整闭环 | 🔴 严重 |
| **错误恢复** | 无分类，try-catch 统一包裹，失败即终止 | 16+ 种错误分类（`FailoverReason`），每种有专属恢复策略：rate limit → 后备模型、image too large → 自动裁剪、auth 401 → 凭证刷新、Ollama ctx 不足 → 配置提示 | 🔴 严重 |
| **重试策略** | 无 | 多层重试：`invalid_tool_retries`、`invalid_json_retries`、`empty_content_retries`、`incomplete_scratchpad_retries`（最多2次）、`thinking_prefill_retries`、jittered backoff | 🔴 严重 |
| **迭代预算** | 固定 `maxIterations = 10` | `IterationBudget` 对象，支持 refund、consume、预算耗尽优雅降级 | 🟡 中等 |
| **流式处理** | `chatStream()` 纯文本流；`chatWithTools()` 非流式 | **优先流式路径**：即使无消费者也用流式做健康检测（90s stale-stream detection）；流式 context scrubber 过滤 memory-context 标签 | 🟡 中等 |
| **中断机制** | 无 | 线程级中断信号（`_execution_thread_id` scoped），支持 `Ctrl+C` 安全终止，工具级中断同步 | 🟡 中等 |
| **Plugin Hooks** | 无 | `pre_llm_call`、`post_api_request`、`on_session_start` 等完整 hook 体系 | 🟡 中等 |
| **Context 预压缩** | 无 | Preflight 压缩：在进主循环前检测 token 数，超过阈值自动摘要中间消息，保护首尾 | 🔴 严重 |
| **系统提示缓存** | 每轮重建 | 首次构建后缓存（`_cached_system_prompt`），持久化到 SQLite，Anthropic prefix cache 优化 | 🟡 中等 |

**核心问题**：CodeSage 的 Agent Loop 是一个「演示级」实现，一旦遇到 API 波动、模型返回异常、工具调用失败，缺乏恢复能力，用户体验会断崖式下跌。

---

### 1.2 记忆系统（Memory System）

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **持久化记忆** | ❌ 完全缺失 | `MemoryProvider` 抽象 + `MemoryManager` 统一编排；内置 MEMORY.md/USER.md + 外部插件（Honcho、Hindsight、Mem0） | 🔴 严重 |
| **跨会话召回** | ❌ 每次对话从零开始 | 每轮 `prefetch(query)` 召回相关记忆；`queue_prefetch()` 后台预取下一轮 | 🔴 严重 |
| **记忆写入** | ❌ 无 | `sync_turn(user, assistant)` 每轮结束后异步写入；支持 `on_session_end` 全会话提取 | 🔴 严重 |
| **记忆 Nudge** | ❌ 无 | `_turns_since_memory` 计数器，每 N 轮主动提示 Agent 回顾和整理记忆 | 🟡 中等 |
| **用户画像** | ❌ 无 | Honcho dialectic user modeling — 跨会话构建用户深度模型 | 🟡 中等 |
| **FTS5 搜索** | ❌ 无 | 内置 SQLite FTS5 全文搜索，支持跨会话历史检索 + LLM 摘要 | 🟡 中等 |
| **记忆工具** | ❌ 无 | `memory_search`、`memory_add`、`memory_update` 等工具暴露给模型自主调用 | 🔴 严重 |

**核心问题**：CodeSage 是一个「无状态」Agent。用户昨天教它的偏好、讨论过的架构决策、踩过的坑，今天全部丢失。真正的智能 Agent 必须有记忆。

---

### 1.3 Context 上下文管理

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **Context Engine** | `ContextManager` 181 行，简单列表操作 | `ContextEngine` 抽象基类，支持插件化替换（Compressor、LCM 等） | 🔴 严重 |
| **自动压缩** | 只有 `KEEP_RECENT` 真正实现；`SUMMARIZE`/`RAG` 是空实现占位符 | `ContextCompressor`：结构化摘要模板（Resolved/Pending/Active Task）、迭代摘要更新、token 预算 tail 保护 | 🔴 严重 |
| **Token 预算** | 无估算，按消息数截断 | `estimate_messages_tokens_rough()` + `estimate_request_tokens_rough()`；图像按 1600 tokens/张估算；多模态 content length 计算 | 🔴 严重 |
| **压缩策略** | 固定保留最近 N 条 | 保护头部（system + first N）和尾部（last N），压缩中间；`_SUMMARY_RATIO = 0.20` 动态摘要长度 | 🟡 中等 |
| **图像清理** | 无 | 自动裁剪旧 screenshot、替换为占位文本，节省 context | 🟡 中等 |
| **压缩后 Session 迁移** | 无 | 压缩创建新 session ID，自动迁移历史到 SQLite 新行，重置重试计数器 | 🟡 中等 |

**核心问题**：CodeSage 的 context 管理是「消息计数器」，不是「token 预算管理器」。在工具调用密集的场景（如读取多个文件、执行多次命令），很快会撞上下文上限，且毫无预警。

---

### 1.4 Skill 技能系统

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **Skill 数量** | 8 个内置技能 | 691 个技能（89 内置 + 81 可选 + 521 社区） | 🔴 严重 |
| **Skill 发现** | 代码硬编码 + 配置文件监听 | 动态发现：`discover_builtin_tools()` AST 扫描 `registry.register()` 调用；Skill Hub 远程注册表 | 🟡 中等 |
| **Skill 自我改进** | ❌ 无 | Background review fork：Agent 在后台 fork 自我审视，发现技能不足 → 自动创建/改进技能 | 🔴 严重 |
| **Skill Provenance** | ❌ 无 | `skill_provenance.py` ContextVar 追踪写入来源：区分用户主动创建 vs Agent 自主创建；Curator 只管理 Agent 创建的技能 | 🟡 中等 |
| **Skill 策展** | ❌ 无 | `curator.py` 定期 consolidates/prunes 技能，防止技能膨胀 | 🟡 中等 |
| **Skill 标准** | 自定义接口 | 兼容 `agentskills.io` 开放标准 | 🟡 中等 |
| **Skill 作为工具** | `SkillToolAdapter` 简单桥接 | Skill 本身就是一等工具，通过 `get_tool_schemas()` 自动暴露；支持运行时 schema 覆盖 | 🟡 中等 |

**核心问题**：CodeSage 的 Skill 是「静态能力清单」，Hermes 的 Skill 是「自我进化的程序生态系统」。没有自我改进闭环，Agent 的能力天花板由开发者决定，而非由使用经验驱动增长。

---

### 1.5 工具系统（Tool System）

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **工具数量** | 6 个 IDE 工具 | 40+ 内置工具，覆盖 terminal、browser、git、file、search、image_gen、delegate 等 | 🔴 严重 |
| **动态注册** | 硬编码 `createDefault()` | `ToolRegistry` 支持运行时 `register()`/`deregister()`；MCP 动态刷新；generation 计数器支持缓存失效 | 🟡 中等 |
| **Toolset 分组** | ❌ 无 | 工具按 toolset 分组（`computer_use`、`dev`、`web` 等），支持按组启用/禁用 | 🟡 中等 |
| **可用性检查** | ❌ 无 | `check_fn` + TTL 缓存（30s）：运行时探测外部依赖（Docker、Playwright 等），不可用工具自动隐藏 | 🟡 中等 |
| **Schema 动态覆盖** | ❌ 无 | `dynamic_schema_overrides`：零参数 callable，运行时调整 schema（如 `delegate_task` 的并发限制） | 🟡 中等 |
| **MCP 集成** | 有基础 `MCPClient`/`MCPServerManager` | 完整 MCP 桥接：`MCPDelegatingSkill`、自动工具列表同步、server 生命周期管理 | 🟢 接近 |
| **工具调用 Guardrails** | ❌ 无 | `_tool_guardrails`：工具调用频率限制、危险操作确认、halt 决策 | 🟡 中等 |
| **结果截断** | 无 | `max_result_size_chars`：超长工具结果自动截断，避免撑爆 context | 🟡 中等 |

**核心问题**：工具数量少且静态，无法根据任务类型动态加载相关工具集。例如做前端开发时应该自动加载 `browser_use`、`computer_use`；做数据分析时加载 `python_exec`、`csv_tool`。

---

### 1.6 多 Agent / 子 Agent 协作

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **执行模式** | `MultiAgentOrchestrator` 串行执行（Planner → Coder → Reviewer） | `delegate_task` 工具：任意 Agent 可 spawn 隔离子 Agent，并行工作流 | 🔴 严重 |
| **环境隔离** | 共享同一个 `AgentCore` 实例 | 子 Agent 有独立 session、独立 context、独立工具集；通过文件状态注册表共享结果 | 🔴 严重 |
| **Kanban 模式** | ❌ 无 | `kanban_orchestrator` + `kanban_worker`：Orchestrator 只调度不干活，Worker 专注执行；生命周期自动注入 system prompt | 🟡 中等 |
| **任务生命周期** | 简单 `Task`/`SubTask` 数据结构 | 完整的 todo store：从对话历史中 hydrate、状态追踪、完成检测 | 🟡 中等 |
| **结果聚合** | 字符串拼接 | 父 Agent 通过 `tool_progress_callback` 实时观察子 Agent 推理过程 | 🟡 中等 |

**核心问题**：CodeSage 的「多 Agent」是角色扮演（不同 system prompt），不是真正的协作。没有子 Agent 委派、没有并行执行、没有任务状态机。

---

### 1.7 任务规划（Task Planning）

| 维度 | CodeSage 现状 | Hermes 实现 | 差距等级 |
|------|--------------|------------|---------|
| **分解方式** | 启发式：按逗号/顿号分割字符串 | LLM 驱动：通过 system prompt 指导模型输出结构化计划；支持复杂依赖图 | 🔴 严重 |
| **依赖分析** | 关键词匹配（"after"/"then"） | 模型自主识别，生成 DAG | 🔴 严重 |
| **执行追踪** | 无状态 | `_todo_store` 持久化到对话历史，支持 hydrate | 🟡 中等 |
| **人机协作** | 无 | Planner 输出计划后询问用户确认，支持 `/approve` `/modify` `/reject` | 🟡 中等 |

---

## 二、演进路线图：从「助手」到「智能体」的四阶段跃迁

### Phase 1：Agent Loop 健壮化（2-3 周）— 让 Agent 「不死」

**目标**：让 CodeSage 的 conversation loop 具备生产级容错能力。

#### 2.1.1 错误分类与恢复引擎 (`AgentErrorRecovery`)

```kotlin
// 新建文件：agent/core/AgentErrorRecovery.kt

enum class FailoverReason {
    RATE_LIMIT,          // 429
    AUTH_EXPIRED,        // 401
    CONTEXT_TOO_LONG,    // 413 / context limit
    IMAGE_TOO_LARGE,     // 400 image size
    MULTIMODAL_UNSUPPORTED, // provider 不支持多模态 tool content
    TIMEOUT,             // 网络超时
    EMPTY_RESPONSE,      // 模型返回空内容
    INCOMPLETE_SCRATCHPAD, // 推理标签未闭合
    INVALID_TOOL_CALL,   // 工具名/参数错误
    PROVIDER_UNAVAILABLE // 服务完全不可用
}

data class ClassifiedError(
    val reason: FailoverReason,
    val retryable: Boolean,
    val shouldCompress: Boolean,
    val shouldFallback: Boolean,
    val statusCode: Int?
)

class AgentErrorRecovery {
    fun classify(error: Throwable, provider: String, model: String, approxTokens: Int): ClassifiedError
    
    suspend fun recover(agent: AgentCore, classified: ClassifiedError): RecoveryAction
    
    // 具体恢复策略
    suspend fun fallbackToBackupModel(agent: AgentCore)
    suspend fun compressContextAndRetry(agent: AgentCore)
    suspend fun refreshCredentials(agent: AgentCore)
    suspend fun shrinkImageParts(messages: MutableList<Message>)
}
```

**关键行为**：
- 遇到 `RATE_LIMIT` → 激活后备模型（fallback model），而非直接报错
- 遇到 `CONTEXT_TOO_LONG` → 触发 context 压缩，重置重试计数器，继续
- 遇到 `EMPTY_RESPONSE` → 最多重试 3 次，每次带 thinking prefill
- 遇到 `INCOMPLETE_SCRATCHPAD` → 检测到 `<REASONING_SCRATCHPAD>` 未闭合，最多重试 2 次

#### 2.1.2 迭代预算管理 (`IterationBudget`)

```kotlin
// 新建文件：agent/core/IterationBudget.kt

class IterationBudget(private val maxIterations: Int) {
    private var consumed = 0
    private var refunded = 0
    
    fun consume(): Boolean {
        if (consumed - refunded >= maxIterations) return false
        consumed++
        return true
    }
    
    fun refund() { refunded++ } // context 压缩后退还预算
    fun remaining(): Int = maxIterations - (consumed - refunded)
}
```

#### 2.1.3 增强型 Agent Loop (`EnhancedAgentLoop`)

重构 `AgentCore.chatWithTools()`，从当前的「简单 while 循环」进化为「状态机驱动的完整闭环」：

```kotlin
enum class ConversationPhase {
    INIT,              // 初始化：恢复系统提示、预压缩
    PREFETCH_MEMORY,   // 预取记忆
    LLM_CALL,          // 调用模型
    STREAM_PROCESS,    // 处理流式响应
    TOOL_DISPATCH,     // 分发工具调用
    TOOL_EXECUTE,      // 执行工具
    RESULT_INTEGRATE,  // 整合工具结果
    POST_TURN_HOOK,    // 后处理钩子
    COMPLETE,          // 完成
    ERROR_RECOVERY,    // 错误恢复
    INTERRUPTED        // 被中断
}

class EnhancedAgentLoop {
    suspend fun run(userMessage: String): Flow<AgentStreamEvent> = flow {
        val phase = ConversationPhase.INIT
        val budget = IterationBudget(15)
        
        while (budget.consume()) {
            when (phase) {
                INIT -> {
                    // 1. 恢复缓存的系统提示（prefix cache 优化）
                    // 2. 预压缩检查
                    // 3. Hydrate todo store
                }
                PREFETCH_MEMORY -> {
                    // 从 MemoryManager 预取相关记忆
                }
                LLM_CALL -> {
                    // 带完整错误恢复策略的 API 调用
                }
                // ... 其他阶段
            }
        }
    }
}
```

#### 2.1.4 流式工具调用支持

当前 `chatWithTools()` 使用 `stream = false`。应改为：
- **默认优先流式**：即使无 UI 消费者也用流式做健康检测
- 流式中检测 `tool_calls` 的 `function.name` 和 `function.arguments`（逐步累积 JSON）
- 流式 context scrubber：过滤 `<memory-context>` 等内部标签

---

### Phase 2：记忆与 Context 引擎（3-4 周）— 让 Agent 「记得」

#### 2.2.1 记忆提供者抽象 (`MemoryProvider`)

```kotlin
// 新建文件：agent/memory/MemoryProvider.kt

interface MemoryProvider {
    val name: String
    
    fun isAvailable(): Boolean
    fun initialize(sessionId: String, hermesHome: String, platform: String)
    
    /** 返回要注入系统提示的静态文本 */
    fun systemPromptBlock(): String = ""
    
    /** 每轮预取相关记忆（快路径，返回缓存结果） */
    fun prefetch(query: String, sessionId: String): String = ""
    
    /** 后台排队预取下一轮 */
    fun queuePrefetch(query: String, sessionId: String) {}
    
    /** 每轮结束后异步写入 */
    fun syncTurn(userContent: String, assistantContent: String, sessionId: String)
    
    /** 暴露给模型的记忆工具 */
    fun getToolSchemas(): List<Tool> = emptyList()
    fun handleToolCall(toolName: String, args: Map<String, Any>): String
    
    fun shutdown()
    
    // 可选钩子
    fun onTurnStart(turnNumber: Int, message: String)
    fun onSessionEnd(messages: List<Message>)
    fun onSessionSwitch(newSessionId: String, parentSessionId: String, reset: Boolean)
}
```

#### 2.2.2 内置记忆实现 (`BuiltInMemoryProvider`)

基于 SQLite（IntelliJ 内置 `jdbc:sqlite`）：

```kotlin
class BuiltInMemoryProvider : MemoryProvider {
    // 表结构
    // sessions: id, created_at, system_prompt, summary
    // memories: id, session_id, content, type(fact/preference/pattern), created_at
    // turns: id, session_id, user_msg, assistant_msg, tokens, created_at
    // fts_search: 虚拟表，FTS5 全文索引
    
    override fun syncTurn(userContent: String, assistantContent: String, sessionId: String) {
        // 1. 写入 turns 表
        // 2. 异步提取关键事实（使用轻量级模型或规则引擎）
        // 3. 写入 memories 表
    }
    
    override fun prefetch(query: String, sessionId: String): String {
        // 1. FTS5 搜索相关记忆
        // 2. 取 top-5
        // 3. 格式化为 <memory-context> 块
    }
}
```

#### 2.2.3 MemoryManager 统一编排

```kotlin
class MemoryManager {
    private val providers = mutableListOf<MemoryProvider>()
    
    // 只允许一个外部 provider（防止 schema 膨胀）
    fun addProvider(provider: MemoryProvider) {
        if (provider !is BuiltInMemoryProvider && providers.any { it !is BuiltInMemoryProvider }) {
            logger.warn("Only one external memory provider allowed")
            return
        }
        providers.add(provider)
    }
    
    fun buildSystemPrompt(): String = providers.joinToString("\n") { it.systemPromptBlock() }
    fun prefetchAll(query: String, sessionId: String): String = providers.joinToString("\n") { it.prefetch(query, sessionId) }
    fun syncAll(userMsg: String, assistantMsg: String, sessionId: String) = providers.forEach { it.syncTurn(userMsg, assistantMsg, sessionId) }
}
```

#### 2.2.4 Context 引擎抽象 (`ContextEngine`)

```kotlin
abstract class ContextEngine {
    abstract val name: String
    
    // Token 状态（run_agent.py 直接读取）
    var lastPromptTokens: Int = 0
    var lastCompletionTokens: Int = 0
    var contextLength: Int = 128000
    var compressionCount: Int = 0
    
    // 压缩参数
    open val thresholdPercent: Double = 0.75
    open val protectFirstN: Int = 3      // 保护头部 N 条非系统消息
    open val protectLastN: Int = 6       // 保护尾部 N 条消息
    
    abstract fun updateFromResponse(usage: Usage)
    abstract fun shouldCompress(promptTokens: Int? = null): Boolean
    abstract fun compress(
        messages: List<Message>,
        currentTokens: Int? = null,
        focusTopic: String? = null
    ): List<Message>
    
    // 可选工具（如 LCM 的 lcm_grep）
    open fun getToolSchemas(): List<Tool> = emptyList()
}
```

#### 2.2.5 ContextCompressor 实现

```kotlin
class ContextCompressor(
    private val auxiliaryClient: ModelGateway,  // 廉价/快速模型用于摘要
    private val contextLength: Int = 128000
) : ContextEngine() {
    
    override fun shouldCompress(promptTokens: Int?): Boolean {
        val tokens = promptTokens ?: estimateTokens(messages)
        thresholdTokens = (contextLength * thresholdPercent).toInt()
        return tokens >= thresholdTokens
    }
    
    override fun compress(messages: List<Message>, currentTokens: Int?, focusTopic: String?): List<Message> {
        // 1. 分离：系统消息 + 头部（protectFirstN）+ 尾部（protectLastN）
        // 2. 中间部分：清理旧 tool output → 替换为 "[Old tool output cleared]"
        // 3. 调用辅助模型生成结构化摘要：
        //    ## Resolved Decisions
        //    ## Pending Questions
        //    ## Active Task
        //    ## Files Modified
        // 4. 合并：系统 + 摘要（带 SUMMARY_PREFIX）+ 尾部
        // 5. 创建新 session，重置重试计数器
    }
}
```

**关键设计点**：
- **结构化摘要模板**：不是简单的一句话总结，而是分章节（Resolved/Pending/Active Task/Files），让模型准确恢复上下文
- **摘要预算动态计算**：`min(compressedContentTokens * 0.20, 12000)`
- **图像清理**：旧 screenshot 替换为 `[screenshot removed to save context]`
- **Tool 参数截断**：使用 JSON 解析后裁剪长字符串叶子节点，保持 JSON 有效性

---

### Phase 3：Skill 自我进化系统（3-4 周）— 让 Agent 「成长」

#### 2.3.1 Skill 运行时注册表增强

```kotlin
class DynamicSkillRegistry : SkillRegistry() {
    private val _generation = AtomicInteger(0)
    private val toolsetChecks = ConcurrentHashMap<String, () -> Boolean>()
    private val checkFnCache = ConcurrentHashMap<() -> Boolean, Pair<Long, Boolean>>()
    
    // 工具集（toolset）概念
    fun registerToolset(name: String, checkFn: () -> Boolean) {
        toolsetChecks[name] = checkFn
    }
    
    // 动态 schema 覆盖
    fun registerWithDynamicSchema(
        skill: Skill,
        dynamicOverrides: () -> Map<String, Any>
    )
    
    // TTL 缓存的可用性检查
    fun isToolsetAvailable(toolset: String): Boolean {
        val check = toolsetChecks[toolset] ?: return true
        return checkFnCache.getOrPut(check) {
            System.currentTimeMillis() to check()
        }.let { (ts, value) ->
            if (System.currentTimeMillis() - ts < 30000) value
            else check().also { checkFnCache[check] = System.currentTimeMillis() to it }
        }
    }
}
```

#### 2.3.2 Skill 自我改进闭环 (`SkillCurator`)

```kotlin
class SkillCurator(
    private val agentCore: AgentCore,
    private val skillRegistry: SkillRegistry
) {
    /**
     * 后台审查 fork：在独立协程中运行
     * 触发条件：
     * 1. 复杂任务使用了 >5 个 tool iterations
     * 2. 每 N 轮对话（memory nudge interval）
     * 3. 用户显式触发 /curate
     */
    suspend fun runBackgroundReview(sessionId: String, conversationHistory: List<Message>) {
        // 1. Fork 独立 context，设置 write_origin = "background_review"
        // 2. 分析对话历史，识别重复模式
        // 3. 判断是否需要新 skill：
        //    - "用户频繁要求做 X，但没有对应 skill"
        //    - "现有 skill 的输入/输出 schema 不够灵活"
        // 4. 生成新 skill 定义（JSON/YAML）
        // 5. 写入 ~/.codesage/skills/auto/
        // 6. 标记 provenance = "agent_created"
    }
    
    /**
     * 定期策展：合并重复技能、删除未使用的技能
     */
    suspend fun consolidate() {
        // 1. 按功能相似度聚类
        // 2. 合并重复技能
        // 3. 删除 30 天未调用的 agent_created 技能
        // 4. 保留用户创建的（provenance = "user_created"）
    }
}
```

#### 2.3.3 Skill Provenance 追踪

```kotlin
// 类似 Python ContextVar 的实现
object SkillProvenance {
    private val writeOrigin = ThreadLocal.withInitial { "foreground" }
    
    const val BACKGROUND_REVIEW = "background_review"
    const val FOREGROUND = "foreground"
    const val USER_CREATED = "user_created"
    
    fun set(origin: String) = writeOrigin.set(origin)
    fun get() = writeOrigin.get()
    fun isBackgroundReview() = get() == BACKGROUND_REVIEW
}
```

---

### Phase 4：子 Agent 与并行协作（2-3 周）— 让 Agent 「分工」

#### 2.4.1 `delegate_task` 工具

```kotlin
// 新增为 ToolRegistry 中的工具

val delegateTaskTool = Tool(
    name = "delegate_task",
    description = "Spawn an isolated sub-agent to handle a specific workstream in parallel. " +
                  "Use when a task can be decomposed into independent sub-tasks.",
    parameters = ToolParameters(
        properties = mapOf(
            "task_description" to ToolProperty("string", "Detailed description of what the sub-agent should do"),
            "toolset" to ToolProperty("string", "Which toolset to give the sub-agent (dev, research, test)"),
            "max_iterations" to ToolProperty("integer", "Budget for the sub-agent, default 10"),
            "context_files" to ToolProperty("array", "Files the sub-agent needs access to")
        ),
        required = listOf("task_description")
    )
)
```

#### 2.4.2 子 Agent 执行器 (`SubAgentExecutor`)

```kotlin
class SubAgentExecutor {
    suspend fun spawn(
        parentSessionId: String,
        taskDescription: String,
        toolset: String,
        maxIterations: Int
    ): SubAgentResult {
        // 1. 创建新 AgentCore 实例（隔离的 context）
        // 2. 加载指定 toolset 的工具（而非全部工具）
        // 3. 继承父 agent 的 memory provider，但独立 session
        // 4. 运行任务
        // 5. 通过 tool_progress_callback 实时汇报进度给父 agent
        // 6. 返回最终结果
    }
}
```

#### 2.4.3 Kanban 模式（可选高级特性）

```kotlin
// KanbanOrchestrator: 只做调度，不执行具体工作
class KanbanOrchestrator {
    val systemPrompt = """
        You are a Kanban Orchestrator. Your rules:
        1. NEVER do the work yourself — only delegate to workers
        2. Maintain a todo list of all pending tasks
        3. Track worker progress via delegate_task results
        4. Reconcile and hand off between workers
        5. Own the task lifecycle: create → assign → verify → close
    """
}

// KanbanWorker: 专注执行，不自作主张
class KanbanWorker {
    val systemPrompt = """
        You are a Kanban Worker. Your rules:
        1. ONLY execute tasks assigned by the orchestrator
        2. Report progress and blockers clearly
        3. Do NOT create new tasks — escalate to orchestrator
        4. Complete assigned task fully before returning
    """
}
```

---

## 三、与 AI 沟通实现目标的策略

### 3.1 向 AI 描述需求的最佳实践（基于 Hermes 的教训）

Hermes 的 system prompt 设计有几个值得学习的模式：

#### 模式 A：Active Task 锚定

在 context 压缩后的摘要中，始终保留一个 `## Active Task` 章节。这让模型在压缩后恢复时，不会迷失当前任务。

```markdown
[CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted into the summary below...

## Active Task
Implement the `UserAuthentication` class with OAuth2 support. Current status:
- ✅ Created `AuthConfig.kt`
- ⏳ Writing `OAuth2Provider.kt` (in progress)
- ⏳ Pending: `TokenManager.kt`

## Resolved Decisions
- Use kotlinx.serialization for JSON parsing
- Token refresh interval: 5 minutes before expiry

## Files Modified
- `src/main/kotlin/auth/AuthConfig.kt` (created)
```

#### 模式 B：Reasoning Scratchpad

在 system prompt 中要求模型使用 `<REASONING_SCRATCHPAD>` 标签展示推理过程。这不仅提升输出质量，还能检测「推理被截断」（incomplete scratchpad），自动触发重试。

```markdown
Before answering, wrap your step-by-step reasoning in <REASONING_SCRATCHPAD>...</REASONING_SCRATCHPAD>.
This helps verify your reasoning is complete.
```

#### 模式 C：Tool Guardrails 提示

在 system prompt 中嵌入工具使用约束，防止模型滥用：

```markdown
Tool Usage Rules:
1. NEVER call the same tool more than 3 times in a row without new information
2. ALWAYS verify file existence before write_file
3. When searching, prefer grep over read_file for finding occurrences
4. run_command: prefer gradle tasks over raw shell scripts
5. If a tool returns error, analyze the error before retrying — do NOT blindly repeat
```

#### 模式 D：Memory Nudge

每 N 轮自动在 system prompt 中注入：

```markdown
[SYSTEM NOTE: You have persistent memory. Consider reviewing what you've learned about this user 
and this project. Use memory_search if you need to recall past decisions.]
```

---

### 3.2 代码生成时的 AI 协作流程

当用 AI（如 Kimi、Claude）生成 CodeSage 的改进代码时，建议按以下流程：

```
1. 【给 Context】提供当前代码文件 + ARCHITECTURE.md + 本方案的相关章节
2. 【给约束】明确 Kotlin 版本、IntelliJ SDK 版本、协程模型
3. 【给例子】提供 Hermes 中对应功能的 Python 代码片段作为参考
4. 【分模块】一次只请求一个模块（如只重构 AgentLoop，不动 Skill）
5. 【要求测试】每个模块必须附带单元测试
6. 【迭代验证】生成后编译运行，把错误回传给 AI 修复
```

---

## 四、实施优先级与里程碑

### 里程碑 M1（2 周）：Agent 不死
- ✅ 重构 `AgentCore.chatWithTools()` 为 `EnhancedAgentLoop`
- ✅ 实现 `AgentErrorRecovery`（分类 + 基础恢复策略）
- ✅ 实现 `IterationBudget`
- ✅ 添加流式工具调用支持

### 里程碑 M2（3 周）：Agent 记得
- ✅ `MemoryProvider` 接口 + `BuiltInMemoryProvider`（SQLite FTS5）
- ✅ `MemoryManager` 统一编排
- ✅ `ContextEngine` 抽象 + `ContextCompressor` 实现
- ✅ 集成到 `AgentCore`，每轮自动 prefetch/sync

### 里程碑 M3（3 周）：Agent 成长
- ✅ `DynamicSkillRegistry`（toolset、动态 schema、TTL check）
- ✅ `SkillCurator`（背景审查、自动创建技能）
- ✅ `SkillProvenance` 追踪
- ✅ Skill 持久化到 `~/.codesage/skills/auto/`

### 里程碑 M4（2 周）：Agent 分工
- ✅ `delegate_task` 工具
- ✅ `SubAgentExecutor`（隔离执行环境）
- ✅ 工具集（toolset）分组加载
- ✅ 基础 Kanban 模式支持

### 里程碑 M5（持续）：生态建设
- 🔄 扩充内置工具到 20+
- 🔄 社区 Skill Hub（远程注册表）
- 🔄 MCP 生态深度集成
- 🔄 轨迹压缩与训练数据生成

---

## 五、关键代码重构建议

### 5.1 AgentCore 拆分

当前 `AgentCore.kt` 538 行承载了太多职责。按 Hermes 的模式拆分为：

```
agent/core/
├── AgentCore.kt              # 门面，保持向后兼容
├── AgentSessionManager.kt    # 会话生命周期（原 session 管理）
├── EnhancedAgentLoop.kt      # 增强对话循环（核心）
├── AgentErrorRecovery.kt     # 错误分类与恢复
├── IterationBudget.kt        # 迭代预算
├── AgentConfig.kt            # 配置（从 AgentCore 底部提取）
├── AgentHooks.kt             # 钩子接口
└── AgentStreamEvent.kt       # 已有
```

### 5.2 ContextManager 升级

```
agent/context/
├── ContextManager.kt          # 保持兼容，委托给 engine
├── ContextEngine.kt           # 抽象基类
├── ContextCompressor.kt       # 默认实现
├── TokenEstimator.kt          # token 估算
└── MessagePruner.kt           # 消息裁剪（tool output、image）
```

### 5.3 新增 Memory 模块

```
agent/memory/
├── MemoryProvider.kt          # 接口
├── MemoryManager.kt           # 统一编排
├── BuiltinMemoryProvider.kt   # SQLite 实现
├── MemoryTools.kt             # 暴露给模型的工具
└── MemoryNudger.kt            # 定期提醒逻辑
```

### 5.4 Skill 系统增强

```
skill/
├── Skill.kt                   # 已有，扩展 provenance 字段
├── registry/
│   ├── SkillRegistry.kt       # 已有
│   └── DynamicSkillRegistry.kt # 新增：动态、toolset、schema 覆盖
├── curator/
│   ├── SkillCurator.kt        # 新增：自我审查与改进
│   └── SkillProvenance.kt     # 新增：来源追踪
├── discovery/
│   ├── ConfigFileWatcher.kt   # 已有
│   └── RemoteSkillHub.kt      # 新增：远程注册表
└── builtin/
    └── ...                    # 已有
```

---

## 六、总结：从「工具调用」到「智能体」的本质区别

| | 当前 CodeSage | 目标 CodeSage |
|---|---|---|
| **心智模型** | LLM + 工具调用包装器 | 有记忆、能学习、会分工的智能体 |
| **Context** | 消息列表，按数量截断 | Token 预算驱动的智能压缩，结构化摘要 |
| **错误处理** | try-catch，失败即终止 | 分类恢复，自动降级，无缝切换 |
| **记忆** | 无 | 跨会话持久记忆，FTS5 召回，定期自我回顾 |
| **技能** | 8 个静态技能 | 动态发现，自我进化，社区生态 |
| **协作** | 串行角色扮演 | 真正的子 Agent 委派，并行工作流 |
| **与 AI 沟通** | 单轮指令 | 多轮闭环，Active Task 锚定，Reasoning Scratchpad |

Hermes Agent 的核心哲学：**Agent 不是一个函数调用序列，而是一个有状态、有记忆、能自我改进的自治系统。** CodeSage 要实现真正的智能化，必须从「对话循环」这个心脏开始，逐步植入记忆、压缩、恢复、进化这四套器官。
