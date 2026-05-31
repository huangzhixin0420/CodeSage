# 预算管理与轮次管理功能重新设计方案

> 角色：业务专家 + Kotlin 开发专家  
> 目标：解决当前预算/轮次绑定会话、配置不可见、体验差的问题，重新设计并实现可配置、按用户问题隔离的预算管理体系。

> **实现状态**：✅ 全部 10 个步骤已完成，所有测试通过（`./gradlew test` BUILD SUCCESSFUL）。

---

## 一、现状分析与问题定义

### 1.1 当前实现概况

| 维度 | 当前实现 | 问题 |
|------|---------|------|
| 预算单位 | `IterationBudget` 在 `EnhancedAgentLoop.run()` 中每次新建，理论上是**按用户请求级别** | 但用户感知为"会话级限制"，因为预算耗尽后只抛 Error，没有给用户继续追问或调整的机会 |
| 预算类型 | 仅有**迭代次数**（硬编码 15 轮） | 无 Token 预算、无时间预算 |
| 配置化 | `AgentConfig`、`PluginConfigState`、`PluginSettingsConfigurable` 均**无任何预算字段** | 用户无法调整，开发者只能改代码 |
| Token 追踪 | `TokenEstimator` 存在但未与预算挂钩；`maxTokens` 始终为 null | 无法基于实际 Token 消耗做预算控制 |
| 预算可视化 | Web UI / Swing UI 均无预算进度展示 | 用户不知道还剩多少轮，突然就被中断 |
| 子 Agent 预算 | `SubAgentExecutor.spawn()` 独立硬编码 10 轮 | 子 Agent 预算与父 Agent 无关，总体可能失控 |
| 错误恢复与预算 | `AgentErrorRecovery` 重试时 `IterationBudget` 仍消耗 | 重试是系统行为，不应占用用户任务预算 |
| 预算耗尽体验 | 只发送 `AgentStreamEvent.Error("达到最大迭代次数限制，请简化请求")` | 体验差：用户不知道实际用了多少轮，也不知道如何继续 |

### 1.2 用户核心痛点

1. **问题未解决就被中断**：一个复杂问题（如"重构整个模块"）可能需要 15+ 轮工具调用，15 轮硬编码上限很容易耗尽，中断后用户的上下文虽然保留，但再次发送消息也不会"继续"之前的任务，而是开始新任务，导致体验断裂。
2. **无法配置预算**：不同任务复杂度差异巨大（简单问答 vs 多文件重构），用户无法根据任务调整预算。
3. **配置页完全缺失预算选项**：当前设置页只有提供商、模型、流式开关，没有任何与预算/轮次相关的配置。

### 1.3 根因分析

- 预算虽然按请求创建，但**缺乏弹性**：没有"预算不足预警"、没有"预算耗尽后继续"的选项、没有让用户感知进度的 UI。
- **没有配置入口**：所有默认值写死在代码中。
- **没有多维度预算**：仅有迭代次数，没有 Token 和时间维度作为补充或替代。

---

## 二、业界调研总结

### 2.1 Claude Code（Anthropic）

- **预算维度**：Token-based（thinking budget + context budget）
- **分层预警**：70% / 85% / 90% 三级阈值，渐进式提示用户
- **预执行检查**：大操作前估算 Token 成本，超限则询问用户或自动压缩上下文
- **用户可控**：`compact` 命令让用户手动压缩上下文；effort level 控制 thinking budget
- **关键启示**：预算应该透明、可预警、用户有干预手段

### 2.2 Cursor

- **预算维度**：Credit-based（按模型消耗不同信用点）+ Max Turns / Budget Limit 配置
- **用户配置**：Settings 中可设置 `Budget Limit`（USD）、`Max Turns`（per generation）
- **关键启示**：预算必须是用户可配置的，且应该有金额/轮次两种表达

### 2.3 Burp AI Agent

- **预算维度**：消息数 + 字符数双限制（如 20 条消息 + 40000 字符）
- **历史压缩**：超限后自动截断保留最近 N 条
- **关键启示**：组合预算（迭代 + Token/字符）更鲁棒

### 2.4 nightwire / 其他 CLI Agent

- **预算维度**：`claude_max_turns: 25`（按调用配置）、`claude_timeout: 600`（秒）
- **关键启示**：时间预算对防止 Agent 卡死很重要

---

## 三、需求方案（重新设计）

### 3.1 设计原则

1. **任务级隔离（Task-Level Isolation）**：每个用户问题（一次 `chatWithTools` / `executeTask` 调用）拥有**独立预算**，预算耗尽仅影响当前任务，不影响会话后续任务。
2. **多维度预算（Multi-Dimensional Budget）**：迭代次数 + Token 消耗 + 时间，三维度组合控制，用户可自由开关。
3. **可配置化（User-Configurable）**：所有预算参数在 IDE 设置面板中暴露，支持默认值 + 运行时调整。
4. **透明可视化（Transparent & Visible）**：UI 实时显示当前任务的已用/剩余预算，分层预警。
5. **弹性耗尽处理（Graceful Exhaustion）**：预算耗尽时不直接 Error，而是给出清晰摘要，并提供"继续执行"选项（允许用户追加预算）。
6. **子 Agent 预算继承（Sub-Agent Budget Inheritance）**：子 Agent 从父 Agent 的**剩余预算**中分配比例，防止总体失控。

### 3.2 预算层级模型

```
Session（会话）
  └── 只累计统计，不做硬限制
      └── Task 1（用户问题1）→ TaskBudget（独立，可配置）
      └── Task 2（用户问题2）→ TaskBudget（独立，全新）
      └── Task 3（子Agent）  → 从父 Task 剩余预算分配
```

### 3.3 预算类型与默认配置

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `maxIterationsPerTask` | 15 | 1~100 | 每个任务最大 LLM 调用轮次 |
| `maxTokensPerTask` | 0（不限制） | 0~1,000,000 | 每个任务最大累计 Token 消耗；0=关闭 |
| `maxDurationSecondsPerTask` | 300 | 10~3600 | 每个任务最大执行时间（秒） |
| `enableIterationBudget` | true | true/false | 是否启用迭代次数预算 |
| `enableTokenBudget` | false | true/false | 是否启用 Token 预算 |
| `enableTimeBudget` | true | true/false | 是否启用时间预算 |
| `budgetWarningThreshold` | 70 | 10~90 | 预算预警阈值（百分比） |
| `subAgentBudgetRatio` | 0.5 | 0.1~1.0 | 子 Agent 可占用父 Agent 剩余预算的比例 |
| `allowContinueOnExhaustion` | true | true/false | 预算耗尽后是否允许用户选择继续 |

### 3.4 预算预警机制（分层阈值）

借鉴 Claude Code 的 progressive warning：

| 阈值 | 行为 |
|------|------|
| 预警阈值（默认 70%） | UI 显示黄色警告：`已用 11/15 轮，Token 8.2k/16k` |
| 85% | UI 显示橙色警告，Thinking 消息追加提示：`预算即将耗尽，建议简化请求或继续执行` |
| 100% | 不直接报错，发送 `BudgetExhausted` 事件，UI 展示摘要和"继续执行"按钮 |

### 3.5 预算耗尽处理流程

```
预算耗尽（如迭代次数用完）
  ├── 发送 BudgetExhausted 事件（含已用/总计、耗时、建议）
  ├── UI 展示：
  │   ├── 摘要：已执行 15 轮，耗时 45s，调用工具 8 个
  │   ├── 状态：任务被暂停（未失败）
  │   └── 操作：【继续执行（+10轮）】 【放弃】
  └── 用户点击"继续执行"
      ├── 追加预算（如 +10 轮或重置预算）
      ├── 发送 BudgetExtended 事件
      └── Agent 继续循环
```

### 3.6 错误恢复与预算协调

- **系统重试（AgentErrorRecovery）**：`RetryWithModel`、`CompressAndRetry`、`SimpleRetry` 等恢复动作**不消耗 TaskBudget** 的迭代次数。
- 理由：重试是系统对 API 失败的自动恢复，不是 Agent 的主动推理轮次，不应占用用户的任务预算。
- **实现方式**：在 `EnhancedAgentLoop` 中区分 `turnNumber`（实际 LLM 调用轮次）和 `budget.consume()` 的调用时机。仅当**成功的 LLM 响应且非重试**时才消耗预算。

### 3.7 子 Agent 预算分配

```kotlin
// 父 Agent 剩余预算
parentRemaining = taskBudget.remainingIterations()
// 子 Agent 可分配预算
subAgentBudget = (parentRemaining * subAgentBudgetRatio).toInt().coerceAtLeast(3)
```

- 子 Agent 的 `TaskBudget` 从父 Agent 的 `TaskBudget` 中**引用扣除**（共享计数器），确保父子预算总和不超过父预算上限。

---

## 四、详细实现方案

### 4.1 核心类变更总览

| # | 文件 | 变更类型 | 说明 |
|---|------|---------|------|
| 1 | `TaskBudget.kt` | **新增** | 统一预算管理器，替代 `IterationBudget` |
| 2 | `IterationBudget.kt` | 保留 | 保留但标记 `@Deprecated`，向后兼容 |
| 3 | `AgentConfig.kt`（内嵌于 AgentCore.kt） | 修改 | 添加预算配置字段 |
| 4 | `PluginConfigState.kt`（内嵌于 PluginConfig.kt） | 修改 | 添加持久化字段 |
| 5 | `PluginConfig.kt` | 修改 | 添加 getter/setter |
| 6 | `EnhancedAgentLoop.kt` | 修改 | 使用 TaskBudget，追踪 Token，时间检查 |
| 7 | `AgentCore.kt` | 修改 | 创建 TaskBudget，传递配置，读取 PluginConfig |
| 8 | `SubAgentExecutor.kt` | 修改 | 继承父预算，使用 subAgentBudgetRatio |
| 9 | `AgentStreamEvent.kt` | 修改 | 添加 BudgetStatus / BudgetExhausted / BudgetExtended 事件 |
| 10 | `AgentHooks.kt` | 读取确认 | 确认是否需要扩展 |
| 11 | `ChatPanel.kt` | 修改 | 处理预算事件，显示预算信息 |
| 12 | `JCEFChatPanel.kt` | 修改 | 传递预算事件到前端 |
| 13 | `chat.html` | 修改 | 预算状态显示、预警样式、继续执行按钮 |
| 14 | `PluginSettingsConfigurable.kt` | 修改 | 新增"预算与轮次"配置面板 |
| 15 | `AgentTurnPanel.kt` | 修改 | 显示当前 turn 的预算消耗 |
| 16 | 测试文件 | 新增/修改 | 覆盖 TaskBudget、配置持久化、预算事件 |

### 4.2 新增 `TaskBudget.kt`

```kotlin
package com.codesage.agent.core

/**
 * 统一任务预算管理器
 *
 * 管理单个用户任务（Task）的多维度预算：
 * - 迭代次数（LLM 调用轮次）
 * - Token 消耗（输入+输出累计）
 * - 执行时间（毫秒）
 *
 * 支持分层预警、预算退还、弹性耗尽。
 */
class TaskBudget(
    val config: BudgetConfig = BudgetConfig(),
    private val startTimeMs: Long = System.currentTimeMillis()
) {
    data class BudgetConfig(
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        val maxTokens: Int = 0, // 0 = 不限制
        val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        val enableIteration: Boolean = true,
        val enableToken: Boolean = false,
        val enableTime: Boolean = true,
        val warningThresholdPercent: Int = DEFAULT_WARNING_THRESHOLD
    )

    enum class BudgetStatus { OK, WARNING, CRITICAL, EXHAUSTED }

    private var consumedIterations = 0
    private var refundedIterations = 0
    private var consumedTokens = 0
    private var extendedIterations = 0 // 用户追加的预算

    /** 尝试消耗一次迭代 */
    fun consumeIteration(): Boolean {
        if (!config.enableIteration) return true
        return if (netConsumedIterations() < config.maxIterations + extendedIterations) {
            consumedIterations++
            true
        } else false
    }

    /** 退还迭代（如 context 压缩后重试不应占用预算） */
    fun refundIteration() {
        refundedIterations++
    }

    /** 追加预算（用户选择继续执行） */
    fun extendIterations(extra: Int) {
        extendedIterations += extra
    }

    /** 记录 Token 消耗 */
    fun recordTokens(tokens: Int) {
        consumedTokens += tokens
    }

    /** 检查时间预算 */
    fun checkTimeBudget(): Boolean {
        if (!config.enableTime) return true
        return (System.currentTimeMillis() - startTimeMs) < config.maxDurationMs
    }

    /** 综合状态 */
    fun status(): BudgetStatus {
        if (isExhausted()) return BudgetStatus.EXHAUSTED
        val pct = usagePercent()
        return when {
            pct >= 100 -> BudgetStatus.EXHAUSTED
            pct >= 85 -> BudgetStatus.CRITICAL
            pct >= config.warningThresholdPercent -> BudgetStatus.WARNING
            else -> BudgetStatus.OK
        }
    }

    fun remainingIterations(): Int = (config.maxIterations + extendedIterations) - netConsumedIterations()
    fun netConsumedIterations(): Int = consumedIterations - refundedIterations
    fun consumedTokens(): Int = consumedTokens
    fun remainingTokens(): Int = if (config.maxTokens > 0) config.maxTokens - consumedTokens else Int.MAX_VALUE
    fun elapsedMs(): Long = System.currentTimeMillis() - startTimeMs
    fun remainingMs(): Long = if (config.enableTime) (config.maxDurationMs - elapsedMs()).coerceAtLeast(0) else Long.MAX_VALUE

    fun isExhausted(): Boolean {
        if (config.enableIteration && netConsumedIterations() >= config.maxIterations + extendedIterations) return true
        if (config.enableToken && config.maxTokens > 0 && consumedTokens >= config.maxTokens) return true
        if (config.enableTime && elapsedMs() >= config.maxDurationMs) return true
        return false
    }

    fun exhaustedReason(): String = buildString {
        if (config.enableIteration && netConsumedIterations() >= config.maxIterations + extendedIterations)
            append("迭代次数已用尽 (${netConsumedIterations()}/${config.maxIterations + extendedIterations})")
        if (config.enableToken && config.maxTokens > 0 && consumedTokens >= config.maxTokens)
            append("; Token 预算已用尽 (${consumedTokens}/${config.maxTokens})")
        if (config.enableTime && elapsedMs() >= config.maxDurationMs)
            append("; 时间预算已用尽 (${elapsedMs()/1000}s/${config.maxDurationMs/1000}s)")
    }.trimStart(';').trim()

    fun usagePercent(): Int {
        val iterationPct = if (config.enableIteration && config.maxIterations > 0)
            (netConsumedIterations() * 100 / (config.maxIterations + extendedIterations)) else 0
        val tokenPct = if (config.enableToken && config.maxTokens > 0)
            (consumedTokens * 100 / config.maxTokens) else 0
        val timePct = if (config.enableTime && config.maxDurationMs > 0)
            (elapsedMs() * 100 / config.maxDurationMs).toInt() else 0
        return maxOf(iterationPct, tokenPct, timePct)
    }

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 15
        const val DEFAULT_MAX_DURATION_MS = 300_000L // 5分钟
        const val DEFAULT_WARNING_THRESHOLD = 70
    }
}
```

### 4.3 修改 `AgentConfig`（AgentCore.kt 内）

```kotlin
data class AgentConfig(
    val defaultModel: String = "MiniMax-Text-01",
    val systemPrompt: String = AgentConfig.DEFAULT_SYSTEM_PROMPT,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    // 新增：预算配置
    val budgetConfig: TaskBudget.BudgetConfig = TaskBudget.BudgetConfig()
)
```

### 4.4 修改 `PluginConfigState`（PluginConfig.kt 内）

```kotlin
class PluginConfigState {
    // ... 现有字段 ...

    // === 预算与轮次管理 ===
    @Tag("maxIterationsPerTask")
    var maxIterationsPerTask: Int = 15

    @Tag("maxTokensPerTask")
    var maxTokensPerTask: Int = 0

    @Tag("maxDurationSecondsPerTask")
    var maxDurationSecondsPerTask: Int = 300

    @Tag("enableIterationBudget")
    var enableIterationBudget: Boolean = true

    @Tag("enableTokenBudget")
    var enableTokenBudget: Boolean = false

    @Tag("enableTimeBudget")
    var enableTimeBudget: Boolean = true

    @Tag("budgetWarningThreshold")
    var budgetWarningThreshold: Int = 70

    @Tag("subAgentBudgetRatio")
    var subAgentBudgetRatio: Double = 0.5

    @Tag("allowContinueOnExhaustion")
    var allowContinueOnExhaustion: Boolean = true
}
```

### 4.5 修改 `PluginConfig.kt`

在 `PluginConfig` 类中添加对应的 getter/setter：

```kotlin
var maxIterationsPerTask: Int
    get() = state.maxIterationsPerTask
    set(value) { state.maxIterationsPerTask = value.coerceIn(1, 100) }

var maxTokensPerTask: Int
    get() = state.maxTokensPerTask
    set(value) { state.maxTokensPerTask = value.coerceAtLeast(0) }

var maxDurationSecondsPerTask: Int
    get() = state.maxDurationSecondsPerTask
    set(value) { state.maxDurationSecondsPerTask = value.coerceIn(10, 3600) }

var enableIterationBudget: Boolean
    get() = state.enableIterationBudget
    set(value) { state.enableIterationBudget = value }

var enableTokenBudget: Boolean
    get() = state.enableTokenBudget
    set(value) { state.enableTokenBudget = value }

var enableTimeBudget: Boolean
    get() = state.enableTimeBudget
    set(value) { state.enableTimeBudget = value }

var budgetWarningThreshold: Int
    get() = state.budgetWarningThreshold
    set(value) { state.budgetWarningThreshold = value.coerceIn(10, 90) }

var subAgentBudgetRatio: Double
    get() = state.subAgentBudgetRatio
    set(value) { state.subAgentBudgetRatio = value.coerceIn(0.1, 1.0) }

var allowContinueOnExhaustion: Boolean
    get() = state.allowContinueOnExhaustion
    set(value) { state.allowContinueOnExhaustion = value }
```

### 4.6 修改 `EnhancedAgentLoop.kt`

**关键变更点：**

1. **构造函数**接收 `TaskBudget` 而非自己创建 `IterationBudget`。
2. **成功 LLM 调用后**消耗迭代预算；**错误恢复重试时不消耗**预算。
3. **LLM 响应后**提取 `usage` 中的 Token 数，更新 `TaskBudget`。
4. **循环条件**增加时间预算检查：`while (budget.consumeIteration() && budget.checkTimeBudget() && !interrupted)`。
5. **分层预警**：每轮循环检查 `budget.status()`，状态变化时发送 `AgentStreamEvent.BudgetStatus`。
6. **预算耗尽**发送 `AgentStreamEvent.BudgetExhausted` 而非 `Error`。

```kotlin
class EnhancedAgentLoop(
    // ... 现有参数 ...
    private val budget: TaskBudget? = null, // 由 AgentCore 注入
    // ...
) {
    fun run(...): Flow<AgentStreamEvent> = channelFlow {
        // ...
        val taskBudget = budget ?: TaskBudget() // 如果没有传入则使用默认
        var phase = ConversationPhase.INIT
        var currentModelLocal = currentModel
        var turnNumber = 0
        var lastBudgetStatus = TaskBudget.BudgetStatus.OK

        // 主循环
        while (taskBudget.consumeIteration() && taskBudget.checkTimeBudget() && !interrupted) {
            turnNumber++
            // ...

            // 预算状态检查与预警
            val currentStatus = taskBudget.status()
            if (currentStatus != lastBudgetStatus) {
                lastBudgetStatus = currentStatus
                send(AgentStreamEvent.BudgetStatus(
                    status = currentStatus.name,
                    remainingIterations = taskBudget.remainingIterations(),
                    remainingTokens = taskBudget.remainingTokens(),
                    remainingSeconds = (taskBudget.remainingMs() / 1000).toInt(),
                    usagePercent = taskBudget.usagePercent()
                ))
            }

            try {
                // LLM_CALL
                val result = gateway.chat(request)
                result.fold(
                    onSuccess = { response ->
                        // 追踪 Token 消耗
                        response.usage?.let { usage ->
                            val totalTokens = (usage.promptTokens ?: 0) + (usage.completionTokens ?: 0)
                            taskBudget.recordTokens(totalTokens)
                        }
                        // ... 后续处理 ...
                    },
                    onFailure = { error ->
                        // 错误恢复 —— 不消耗预算（已消耗的在 refund 逻辑中处理）
                        val action = errorRecovery.recover(...)
                        when (action) {
                            is RecoveryAction.CompressAndRetry -> {
                                // context 压缩后退还预算
                                taskBudget.refundIteration()
                                // ...
                            }
                            // ...
                        }
                    }
                )
            } catch (e: Exception) { /* ... */ }
        }

        if (interrupted) {
            send(AgentStreamEvent.Error("对话被中断"))
        } else if (taskBudget.isExhausted()) {
            send(AgentStreamEvent.BudgetExhausted(
                reason = taskBudget.exhaustedReason(),
                consumedIterations = taskBudget.netConsumedIterations(),
                consumedTokens = taskBudget.consumedTokens(),
                elapsedSeconds = (taskBudget.elapsedMs() / 1000).toInt(),
                allowContinue = true
            ))
        }
        // ...
    }
}
```

### 4.7 修改 `AgentCore.kt`

**关键变更点：**

1. `chatWithTools()` 中从 `PluginConfig` 读取预算配置，创建 `TaskBudget`。
2. 将 `TaskBudget` 传入 `EnhancedAgentLoop`（通过构造函数或 `run()` 参数）。

```kotlin
fun chatWithTools(userMessage: String): Flow<AgentStreamEvent> {
    // ...
    val pluginConfig = PluginConfig.getInstance()
    val budgetConfig = TaskBudget.BudgetConfig(
        maxIterations = pluginConfig.maxIterationsPerTask,
        maxTokens = pluginConfig.maxTokensPerTask,
        maxDurationMs = pluginConfig.maxDurationSecondsPerTask * 1000L,
        enableIteration = pluginConfig.enableIterationBudget,
        enableToken = pluginConfig.enableTokenBudget,
        enableTime = pluginConfig.enableTimeBudget,
        warningThresholdPercent = pluginConfig.budgetWarningThreshold
    )
    val taskBudget = TaskBudget(budgetConfig)

    // 重新创建 enhancedLoop 以传入新的 TaskBudget
    val loop = EnhancedAgentLoop(
        gateway = gateway,
        toolRegistry = toolRegistry,
        toolExecutor = toolExecutor,
        skillToolAdapter = skillToolAdapter,
        errorRecovery = errorRecovery,
        hooks = hooks,
        stateFlow = _state,
        memoryManager = memoryManager,
        memoryNudger = memoryNudger,
        subAgentExecutor = subAgentExecutor,
        agentCore = this,
        budget = taskBudget
    )

    // ...
    val flow = loop.run(userMessage = userMessage, ...)
    // ...
}
```

### 4.8 修改 `SubAgentExecutor.kt`

**关键变更点：**

1. `spawn()` 增加 `parentBudget: TaskBudget?` 参数。
2. 如果传入父预算，则按比例创建子预算；否则使用独立默认值。

```kotlin
suspend fun spawn(
    parentSessionId: String,
    taskDescription: String,
    toolset: String = "dev",
    maxIterations: Int = 10,
    contextFiles: List<String> = emptyList(),
    progressCallback: suspend (String) -> Unit = {},
    parentBudget: TaskBudget? = null
): SubAgentResult {
    // ...
    val subBudgetConfig = if (parentBudget != null) {
        val parentRemaining = parentBudget.remainingIterations()
        val subMaxIterations = (parentRemaining * pluginConfig.subAgentBudgetRatio).toInt().coerceAtLeast(3)
        parentBudget.config.copy(maxIterations = subMaxIterations)
    } else {
        TaskBudget.BudgetConfig(maxIterations = maxIterations)
    }
    // 将子 budget 绑定到父 budget 的计数器... 或采用引用方式
    // 简化实现：子 Agent 独立运行，但限制其最大迭代数
}
```

### 4.9 扩展 `AgentStreamEvent.kt`

```kotlin
sealed class AgentStreamEvent {
    // ... 现有事件 ...

    /**
     * 预算状态更新（用于 UI 实时展示）
     */
    data class BudgetStatus(
        val status: String, // OK / WARNING / CRITICAL / EXHAUSTED
        val remainingIterations: Int,
        val remainingTokens: Int,
        val remainingSeconds: Int,
        val usagePercent: Int
    ) : AgentStreamEvent()

    /**
     * 预算耗尽（非错误，是可控暂停）
     */
    data class BudgetExhausted(
        val reason: String,
        val consumedIterations: Int,
        val consumedTokens: Int,
        val elapsedSeconds: Int,
        val allowContinue: Boolean
    ) : AgentStreamEvent()

    /**
     * 用户追加预算后恢复执行
     */
    data class BudgetExtended(
        val extraIterations: Int,
        val newRemainingIterations: Int
    ) : AgentStreamEvent()
}
```

### 4.10 UI 层修改

#### 4.10.1 Swing UI (`ChatPanel.kt`)

在 `sendMessage()` 的 `collect` 中处理新增事件：

```kotlin
is AgentStreamEvent.BudgetStatus -> {
    turn.updateBudgetStatus(
        remainingIterations = event.remainingIterations,
        usagePercent = event.usagePercent
    )
}
is AgentStreamEvent.BudgetExhausted -> {
    turn.showBudgetExhausted(
        reason = event.reason,
        consumedIterations = event.consumedIterations,
        allowContinue = event.allowContinue,
        onContinue = { /* 通知 AgentCore 追加预算 */ }
    )
}
```

#### 4.10.2 Web UI (`JCEFChatPanel.kt`)

在 `initialize()` 的 `collect` 中新增事件映射到 JS：

```kotlin
is AgentStreamEvent.BudgetStatus -> {
    sendToJS(mapOf(
        "type" to "budget_status",
        "turnId" to turnId,
        "status" to event.status,
        "remainingIterations" to event.remainingIterations,
        "remainingTokens" to event.remainingTokens,
        "remainingSeconds" to event.remainingSeconds,
        "usagePercent" to event.usagePercent
    ))
}
is AgentStreamEvent.BudgetExhausted -> {
    sendToJS(mapOf(
        "type" to "budget_exhausted",
        "turnId" to turnId,
        "reason" to event.reason,
        "consumedIterations" to event.consumedIterations,
        "consumedTokens" to event.consumedTokens,
        "elapsedSeconds" to event.elapsedSeconds,
        "allowContinue" to event.allowContinue
    ))
}
```

#### 4.10.3 `chat.html` 前端

新增 JS 处理函数：

```javascript
function onBudgetStatus(turnId, data) {
    const statusEl = document.getElementById(turnId + "-budget-status");
    if (!statusEl) return;
    statusEl.textContent = `轮次 ${data.remainingIterations} | Token ${data.remainingTokens} | ${data.usagePercent}%`;
    statusEl.className = "budget-status " + data.status.toLowerCase();
}

function onBudgetExhausted(turnId, data) {
    const contentEl = document.getElementById(turnId + "-content");
    if (contentEl) {
        contentEl.innerHTML = `
            <div class="budget-exhausted">
                <div class="budget-exhausted-title">⏸ 任务已暂停</div>
                <div class="budget-exhausted-reason">${escapeHtml(data.reason)}</div>
                <div class="budget-exhausted-summary">
                    已执行 ${data.consumedIterations} 轮，耗时 ${data.elapsedSeconds}s
                </div>
                ${data.allowContinue ? `
                <button class="continue-btn" onclick="continueTask('${turnId}')">
                    继续执行 (+10轮)
                </button>` : ''}
            </div>
        `;
    }
    isGenerating = false;
    document.getElementById("send-btn").classList.remove("hidden");
    document.getElementById("stop-btn").classList.add("hidden");
}

function continueTask(turnId) {
    if (window.javaBridge) {
        window.javaBridge.sendMessage(JSON.stringify({
            type: "continue_task",
            turnId: turnId,
            extraIterations: 10
        }));
    }
}
```

新增 CSS 样式：

```css
.budget-status {
    font-size: 11px;
    color: var(--text-tertiary);
    margin-left: auto;
    padding: 2px 8px;
    border-radius: var(--radius-full);
    background: var(--bg-secondary);
}
.budget-status.warning { color: var(--warning-color); background: rgba(245, 158, 11, 0.1); }
.budget-status.critical { color: var(--error-color); background: rgba(239, 68, 68, 0.1); }

.budget-exhausted {
    border: 1px dashed var(--border-color);
    border-radius: var(--radius-md);
    padding: 16px;
    text-align: center;
    background: var(--bg-secondary);
}
.budget-exhausted-title { font-size: 14px; font-weight: 600; color: var(--text-primary); margin-bottom: 8px; }
.budget-exhausted-reason { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
.budget-exhausted-summary { font-size: 11px; color: var(--text-tertiary); margin-bottom: 12px; }
.continue-btn {
    padding: 6px 16px;
    border-radius: var(--radius-sm);
    border: none;
    background: var(--accent-color);
    color: white;
    font-size: 12px;
    cursor: pointer;
}
```

### 4.11 设置面板修改 (`PluginSettingsConfigurable.kt`)

在右侧详情面板新增 **"预算与轮次"** 区块（位于"通用设置"下方或并列）：

```kotlin
// 预算设置控件
private val maxIterationsField = JBTextField("15", 5)
private val maxTokensField = JBTextField("0", 8) // 0=不限制
private val maxDurationField = JBTextField("300", 6)
private val enableIterationCheck = JCheckBox("启用迭代次数预算", true)
private val enableTokenCheck = JCheckBox("启用 Token 预算", false)
private val enableTimeCheck = JCheckBox("启用时间预算", true)
private val warningThresholdCombo = ComboBox((10..90 step 10).map { "$it%" }.toTypedArray())
private val subAgentRatioCombo = ComboBox((1..10).map { "${it * 10}%" }.toTypedArray())
private val allowContinueCheck = JCheckBox("预算耗尽后允许继续执行", true)

// 表单构建
val budgetForm = FormBuilder.createFormBuilder()
    .addComponent(JBLabel("任务级预算管理").apply { font = JBUI.Fonts.label().biggerOn(0.5f) })
    .addSeparator()
    .addLabeledComponent("最大迭代次数:", wrapField(maxIterationsField))
    .addComponentToRightColumn(enableIterationCheck)
    .addLabeledComponent("最大 Token 数:", wrapField(maxTokensField))
    .addComponentToRightColumn(enableTokenCheck)
    .addLabeledComponent("最大耗时(秒):", wrapField(maxDurationField))
    .addComponentToRightColumn(enableTimeCheck)
    .addLabeledComponent("预警阈值:", warningThresholdCombo)
    .addLabeledComponent("子Agent预算比例:", subAgentRatioCombo)
    .addComponentToRightColumn(allowContinueCheck)
    .addComponentFillVertically(JPanel(), 0)
    .panel
```

### 4.12 Token 追踪实现

需要在 LLM 响应 DTO 中确保 `usage` 字段被正确解析并传递到 `EnhancedAgentLoop`。

当前 `ChatResponse` 结构需要确认是否有 `usage` 字段。如果没有，需要补充：

```kotlin
// model/dto/ChatResponse.kt 或相关文件
data class ChatResponse(
    val choices: List<Choice>,
    val usage: TokenUsage? = null
)

data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)
```

如果当前响应结构没有 `usage`，需要先在模型适配器层补充解析（此工作可纳入实现步骤）。

---

## 五、实现步骤规划

### Step 1：基础设施 — TaskBudget 与配置扩展

**目标**：建立新预算核心和配置体系。

**任务清单**：
- [ ] 1.1 创建 `TaskBudget.kt`，实现多维度预算、预警、耗尽、追加逻辑
- [ ] 1.2 修改 `AgentConfig`（AgentCore.kt），添加 `budgetConfig` 字段
- [ ] 1.3 修改 `PluginConfigState`，添加 9 个预算相关持久化字段
- [ ] 1.4 修改 `PluginConfig`，添加对应的 getter/setter（含合法性校验）
- [ ] 1.5 运行 `./gradlew test` 确保现有测试不因此处修改编译失败

**检查点**：
- `TaskBudget` 单测通过：迭代消耗、退还、Token 记录、时间检查、状态转换、追加预算
- `PluginConfig` 单测：配置读写、持久化/反序列化正确

---

### Step 2：核心循环改造 — EnhancedAgentLoop 接入 TaskBudget

**目标**：让 Agent 对话循环使用新预算体系。

**任务清单**：
- [ ] 2.1 修改 `EnhancedAgentLoop` 构造函数，接收 `TaskBudget` 参数
- [ ] 2.2 替换 `IterationBudget` 使用为 `TaskBudget`
- [ ] 2.3 在 `LLM_CALL` 成功后提取并记录 Token 消耗
- [ ] 2.4 在循环条件中加入时间预算检查
- [ ] 2.5 实现分层预警：每轮检查 `budget.status()`，变化时发送 `BudgetStatus` 事件
- [ ] 2.6 预算耗尽时发送 `BudgetExhausted` 事件（替代原有 Error）
- [ ] 2.7 错误恢复（`CompressAndRetry` 等）时调用 `refundIteration()`，不占用预算
- [ ] 2.8 `IterationBudget.kt` 添加 `@Deprecated` 注解和迁移说明

**检查点**：
- `EnhancedAgentLoopTest` 通过：预算正常消耗、耗尽时发送正确事件、重试不消耗预算
- 模拟 15 轮后预算耗尽，验证收到 `BudgetExhausted` 而非 `Error`

---

### Step 3：AgentCore 与 SubAgent 集成

**目标**：上层调用方正确创建和传递预算，子 Agent 继承预算。

**任务清单**：
- [ ] 3.1 修改 `AgentCore.chatWithTools()`：从 `PluginConfig` 读取配置创建 `TaskBudget`，传入 `EnhancedAgentLoop`
- [ ] 3.2 修改 `AgentCore.executeTask()`：为每个子任务也创建独立 `TaskBudget`（或共享总预算）
- [ ] 3.3 修改 `SubAgentExecutor.spawn()`：接收父 `TaskBudget`，按比例计算子预算上限
- [ ] 3.4 `SubAgentExecutor` 中子 Agent 的 `EnhancedAgentLoop` 也传入计算后的预算
- [ ] 3.5 处理 `delegate_task` 工具调用时，传递当前剩余预算信息

**检查点**：
- `AgentCoreTest` 通过：`chatWithTools` 能正确运行，预算参数影响循环次数
- 子 Agent 测试：父预算 15 轮、ratio 0.5 时，子 Agent 最大 7 轮

---

### Step 4：事件流扩展 — AgentStreamEvent 与 UI 桥接

**目标**：预算状态能从后端流式传递到前端。

**任务清单**：
- [ ] 4.1 扩展 `AgentStreamEvent`：添加 `BudgetStatus`、`BudgetExhausted`、`BudgetExtended`
- [ ] 4.2 修改 `ChatPanel.kt`：处理新增事件，更新 `AgentTurnPanel` 预算显示
- [ ] 4.3 修改 `JCEFChatPanel.kt`：将预算事件序列化为 JS 消息
- [ ] 4.4 处理前端 `continue_task` 消息：从 JS 回调到 `AgentCore` 追加预算

**检查点**：
- 单元测试：事件序列化/反序列化正确
- 集成测试：`JCEFChatPanel` 收到 `BudgetStatus` 后能正确调用 `sendToJS`

---

### Step 5：Web UI 实现 — chat.html 预算展示与交互

**目标**：前端能展示预算状态、预警、耗尽后的继续执行。

**任务清单**：
- [ ] 5.1 `chat.html` 新增 `budget-status` 元素到 `assistant-meta`
- [ ] 5.2 新增 `onBudgetStatus()` / `onBudgetExhausted()` JS 函数
- [ ] 5.3 新增 `continueTask()` 函数，通过 `javaBridge` 发送继续请求
- [ ] 5.4 新增预算相关 CSS（状态色、耗尽面板、继续按钮）
- [ ] 5.5 `onJavaMessage` 分发器中添加 `budget_status` / `budget_exhausted` 分支

**检查点**：
- 手动验证：在浏览器中打开 `chat.html`，通过 console 模拟调用 `onBudgetStatus` 和 `onBudgetExhausted`，样式正确

---

### Step 6：Swing UI 实现 — AgentTurnPanel 预算显示

**目标**：Swing  fallback UI 也支持预算展示。

**任务清单**：
- [ ] 6.1 修改 `AgentTurnPanel`：添加预算状态标签（`JLabel`）
- [ ] 6.2 添加 `updateBudgetStatus()`、`showBudgetExhausted()` 方法
- [ ] 6.3 预算耗尽时显示"继续执行"按钮（`JButton`），点击回调到 `ChatPanel`

**检查点**：
- Swing UI 运行时不因预算相关修改崩溃
- 预算标签能正确更新

---

### Step 7：配置面板实现 — PluginSettingsConfigurable 新增预算页

**目标**：用户能在 IDE 设置中修改预算参数。

**任务清单**：
- [ ] 7.1 `PluginSettingsConfigurable.kt` 新增预算表单控件（见 4.11）
- [ ] 7.2 实现预算控件的 `loadDataToEdit()` / `saveCurrentEditToData()` 逻辑
- [ ] 7.3 `isModified()` 中加入预算字段的比较
- [ ] 7.4 `apply()` 中将预算配置写入 `PluginConfig`
- [ ] 7.5 `reset()` 中从 `PluginConfig` 读取预算配置恢复 UI

**检查点**：
- 打开 IDE Settings → CodeSage，预算字段正确显示当前值
- 修改预算数值后点击 Apply，重启 IDE 后值保持
- `isModified()` 正确识别预算字段变更

---

### Step 8：Token 消耗追踪补全

**目标**：确保 LLM 响应中的 Token 用量能被正确提取。

**任务清单**：
- [ ] 8.1 检查 `ChatResponse` 是否有 `usage` 字段；没有则添加 `TokenUsage`
- [ ] 8.2 检查各模型适配器（MiniMaxAdapter、KimiAdapter、OpenAICompatibleAdapter）是否正确解析 usage
- [ ] 8.3 在 `EnhancedAgentLoop` 中确认 `response.usage` 能取到值

**检查点**：
- Mock 测试：`gateway.chat()` 返回带 usage 的响应，`TaskBudget` 的 `consumedTokens` 正确累加

---

### Step 9：集成测试与端到端验证

**目标**：全链路验证预算管理功能。

**任务清单**：
- [ ] 9.1 编写端到端测试：设置 `maxIterationsPerTask=3`，发送消息，验证 3 轮后收到 `BudgetExhausted`
- [ ] 9.2 验证预算耗尽后，用户发送新消息能正常开始（新 TaskBudget）
- [ ] 9.3 验证子 Agent 预算继承：父 10 轮、ratio 0.5，子 Agent 最多 5 轮
- [ ] 9.4 验证配置变更实时生效（不重启 IDE）
- [ ] 9.5 运行完整测试套件 `./gradlew test`，全部通过

**检查点**：
- `./gradlew test` 0 failure
- `./gradlew build` 成功

---

### Step 10：文档与清理

**目标**：代码注释、用户文档、废弃标记。

**任务清单**：
- [ ] 10.1 `IterationBudget.kt` 添加 `@Deprecated` 和替换指引
- [ ] 10.2 `TaskBudget.kt` 添加完整 KDoc
- [ ] 10.3 更新 `docs/BUDGET_ROUND_REDESIGN.md` 中的实现状态
- [ ] 10.4 更新 `AGENTS.md`（如有必要）

---

## 六、检查点设计

| 步骤 | 检查点名称 | 检查方法 | 通过标准 |
|------|-----------|---------|---------|
| Step 1 | TaskBudget 核心逻辑 | `./gradlew test --tests "*TaskBudget*"` | 单测全部通过，覆盖消耗/退还/预警/耗尽/追加 |
| Step 1 | 配置持久化 | 修改配置 → 重启 IDE → 读取配置 | 值保持一致，非法输入被 coerce |
| Step 2 | 循环预算集成 | `./gradlew test --tests "*EnhancedAgentLoop*"` | 3轮预算耗尽测试通过，重试不消耗预算 |
| Step 3 | AgentCore 集成 | `./gradlew test --tests "*AgentCore*"` | chatWithTools 正常，子 Agent 预算比例正确 |
| Step 4 | 事件流扩展 | 事件序列化单元测试 | BudgetStatus/BudgetExhausted 序列化正确 |
| Step 5 | Web UI 展示 | 浏览器模拟 + IDE 实际运行 | 预算标签显示正确，预警变色，继续按钮可点击 |
| Step 6 | Swing UI 展示 | IDE 运行 fallback 模式 | 预算标签不报错，界面不崩溃 |
| Step 7 | 配置面板 | IDE Settings 手动测试 | 字段显示正确，apply/reset/modified 行为正确 |
| Step 8 | Token 追踪 | Mock gateway 测试 | usage 解析正确，Token 累加正确 |
| Step 9 | 全链路测试 | `./gradlew test` | 全部测试通过，0 failure |
| Step 9 | 构建验证 | `./gradlew build` | 构建成功，无编译错误 |

---

## 七、需求验收标准

### 7.1 功能验收（必须全部通过）

| # | 验收项 | 验收方法 |
|---|--------|---------|
| 1 | **任务级预算隔离**：用户问题 A 预算耗尽后，用户发送问题 B，问题 B 拥有全新独立预算 | 设置 maxIterations=3，发送消息触发耗尽，再发新消息，验证新消息能完整执行 3 轮 |
| 2 | **迭代预算可配置**：`maxIterationsPerTask` 修改后生效 | IDE Settings 改为 5，发送复杂请求，验证 5 轮后耗尽 |
| 3 | **Token 预算可开关**：`enableTokenBudget=true` + `maxTokensPerTask=1000` 时，Token 超限会触发耗尽 | Mock LLM 返回大 Token 响应，验证 Token 耗尽事件 |
| 4 | **时间预算可配置**：`maxDurationSecondsPerTask=5` 时，5 秒后任务暂停 | 设置 5 秒，发送请求，验证时间耗尽 |
| 5 | **配置页可见**：IDE Settings → CodeSage 页面有"预算与轮次"区块，所有 9 个字段可编辑 | 手动检查 UI |
| 6 | **预算预警**：预算使用达到 70% 时 UI 显示预警状态 | 设置 maxIterations=10，发送请求，观察第 7 轮预警 |
| 7 | **预算耗尽非错误**：预算耗尽时 UI 显示"暂停"而非"错误"，提供"继续执行"选项 | 观察耗尽后的 UI 样式和文案 |
| 8 | **继续执行可用**：点击"继续执行"后，Agent 追加预算并恢复执行 | 点击按钮，验证后续 LLM 调用继续进行 |
| 9 | **子 Agent 预算继承**：父 Agent 剩余 10 轮，子 Agent ratio=0.5，则子最多 5 轮 | 通过 delegate_task 工具触发子 Agent，验证迭代上限 |
| 10 | **错误恢复不占预算**：API 失败后的重试不消耗用户任务预算 | Mock gateway 前 2 次失败第 3 次成功，验证预算只扣 1 轮 |

### 7.2 兼容性验收

| # | 验收项 | 验收方法 |
|---|--------|---------|
| 11 | **向后兼容**：未升级配置的用户（旧 `PluginConfigState`）打开 IDE 不崩溃，使用默认值 | 删除/重命名旧配置 xml，重启 IDE，验证默认预算行为 |
| 12 | **旧 API 兼容**：`IterationBudget` 仍可用（标记废弃但不删除） | 编译引用 `IterationBudget` 的测试代码，验证通过 |
| 13 | **Web/Swing 双 UI 兼容**：JCEF 不可用时 fallback 到 Swing，两者都支持预算显示 | 分别在两套 UI 下运行，验证预算标签存在 |

### 7.3 性能与体验验收

| # | 验收项 | 验收方法 |
|---|--------|---------|
| 14 | **预算检查无显著性能损耗**：每轮预算状态检查耗时 < 1ms | 在 `EnhancedAgentLoop` 中加入计时日志 |
| 15 | **配置持久化可靠**：配置修改后保存、IDE 重启后读取，10 次循环无丢失 | 自动化脚本循环测试 |
| 16 | **用户体验**：预算耗尽提示文案清晰，用户知道发生了什么和能做什么 | 产品经理/用户走查 |

### 7.4 回归验收

| # | 验收项 | 验收方法 |
|---|--------|---------|
| 17 | **现有对话功能无损**：预算功能关闭（enableIteration=false, enableToken=false, enableTime=false）时，原有对话逻辑完全一致 | `./gradlew test` 全部通过 |
| 18 | **流式输出无损**：文本流式、工具调用、子 Agent 进度等事件正常 | 端到端手动测试 |
| 19 | **会话持久化无损**：预算不影响会话保存/恢复 | 测试会话保存后恢复，历史完整 |

### 7.5 验收结论处理

- **全部通过（P0 项 0 失败）**：功能验收完成，可以合并。
- **P0 项有失败**：暂停合并，回退到对应实现步骤重新审视方案，修复后重新执行该步骤及后续依赖步骤的验收。
- **P1/P2 项有失败**：记录为已知问题，评估是否阻塞发布；不阻塞则合并后跟进修复。

**P0（阻塞）项**：1, 2, 3, 5, 7, 10, 11, 17, 18  
**P1（重要）项**：4, 6, 8, 9, 12, 13, 14, 15  
**P2（优化）项**：16, 19

---

## 八、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| Token 用量解析不全：部分模型适配器未返回 usage | Token 预算失效 | Step 8 优先补齐；若某适配器不支持，则该模型下 Token 预算自动关闭并日志提示 |
| 用户升级后旧配置不兼容 | IDE 启动崩溃 | XmlSerializer 对新增字段自动用默认值；添加 `loadState` 兼容性处理 |
| 预算事件增加前端复杂度 | UI 性能下降 | 事件发送增加 throttle（状态变化时只发一次），前端减少重绘 |
| 子 Agent 预算共享实现复杂 | 延期 | MVP 阶段子 Agent 采用"比例限制最大迭代数"而非严格共享计数器，后续优化 |
| 继续执行需要保留循环状态 | 实现复杂 | MVP 阶段"继续执行"通过追加预算并重新调用 `chatWithTools` 实现（上下文保留在 session 中） |

---

## 九、附录：关键代码变更文件清单

```
src/main/kotlin/com/codesage/agent/core/
  + TaskBudget.kt                    [新增]
  ~ IterationBudget.kt               [标记废弃]
  ~ EnhancedAgentLoop.kt             [接入 TaskBudget]
  ~ AgentCore.kt                     [创建 TaskBudget，读取配置]
  ~ AgentStreamEvent.kt              [新增预算事件]
  ~ SubAgentExecutor.kt              [子 Agent 预算继承]

src/main/kotlin/com/codesage/shared/config/
  ~ PluginConfig.kt                  [新增预算字段 getter/setter]

src/main/kotlin/com/codesage/ide/settings/
  ~ PluginSettingsConfigurable.kt    [新增预算配置 UI]

src/main/kotlin/com/codesage/ide/ui/components/chat/
  ~ ChatPanel.kt                     [处理预算事件]
  ~ AgentTurnPanel.kt                [显示预算状态]

src/main/kotlin/com/codesage/ide/ui/web/
  ~ JCEFChatPanel.kt                 [预算事件桥接]

src/main/resources/webui/
  ~ chat.html                        [预算展示与交互]

src/test/kotlin/com/codesage/agent/core/
  + TaskBudgetTest.kt                [新增]
  ~ EnhancedAgentLoopTest.kt         [补充预算测试]
```
