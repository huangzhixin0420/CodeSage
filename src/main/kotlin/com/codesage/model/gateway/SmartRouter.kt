package com.codesage.model.gateway

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.Capability
import com.codesage.model.dto.ModelCapabilities
import com.codesage.model.registry.ModelRegistry
import com.codesage.shared.utils.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T1.4 修复：智能路由器
 *
 * 按用户任务的"能力需求 + 优先级"自动选择最合适的模型适配器。
 * 支持 5 种 RoutingStrategy 策略（成本/速度/质量/平衡/任务特定）+ 熔断器。
 *
 * 验收标准（来自 TARGETED_OPTIMIZATION_PLAN.md T1.4）：
 * - [x] 单元测试：5 种 routing strategy 各自选到正确 adapter
 * - [x] 单元测试：vision 任务不会路由到无 vision 的 adapter
 * - [x] 单元测试：熔断后 5 分钟内不重试该 adapter
 */

/**
 * 任务优先级
 */
enum class TaskPriority { LOW, MEDIUM, HIGH }

/**
 * 路由任务描述
 *
 * 由调用方构造，描述"这个任务需要什么能力"。路由器按需选 adapter。
 */
data class RoutingTask(
    val requiresVision: Boolean = false,
    val requiresFunctionCalling: Boolean = true,
    val estimatedTokens: Int = 0,
    val priority: TaskPriority = TaskPriority.MEDIUM
) {
    fun requiredCapabilities(): Set<Capability> {
        val caps = mutableSetOf<Capability>()
        if (requiresFunctionCalling) caps.add(Capability.FUNCTION_CALLING)
        if (requiresVision) caps.add(Capability.VISION)
        if (estimatedTokens > 100_000) caps.add(Capability.LONG_CONTEXT)
        return caps
    }
}

/**
 * 适配器 profile：adapter 本身 + 派生元数据
 */
data class AdapterProfile(
    val adapter: ModelAdapter,
    val estimatedCost: Double = 0.0
) {
    val capabilities: ModelCapabilities get() = adapter.capabilities
    val providerName: String get() = adapter.providerName

    companion object {
        fun fromAdapter(adapter: ModelAdapter, estimatedInputTokens: Int = 1000): AdapterProfile {
            val caps = adapter.capabilities
            // 估算 1k 输入 + 1k 输出的费用
            val cost = if (caps.pricePer1kInput > 0 || caps.pricePer1kOutput > 0) {
                (estimatedInputTokens / 1000.0) * caps.pricePer1kInput +
                        (estimatedInputTokens / 1000.0) * caps.pricePer1kOutput
            } else 0.0
            return AdapterProfile(adapter, cost)
        }
    }
}

/**
 * 路由策略接口
 *
 * 选择最合适的 adapter。返回 null 表示无合适选择。
 */
interface RoutingStrategy {
    fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile?
}

/**
 * 成本优先：选择价格最低的
 */
class CostFirstStrategy : RoutingStrategy {
    override fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile? =
        candidates.minByOrNull { it.estimatedCost }
}

/**
 * 质量优先：选择 maxContextTokens 最大的
 */
class QualityFirstStrategy : RoutingStrategy {
    override fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile? =
        candidates.maxByOrNull { it.capabilities.maxContextTokens }
}

/**
 * 速度优先：选择 streaming + toolStreaming 都开启的
 */
class SpeedFirstStrategy : RoutingStrategy {
    override fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile? {
        return candidates.firstOrNull {
            it.capabilities.streaming && it.capabilities.toolStreaming
        } ?: candidates.firstOrNull()
    }
}

/**
 * 平衡策略：价格 0.4 + 质量（context size）0.6 加权
 */
class BalancedStrategy : RoutingStrategy {
    override fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile? {
        if (candidates.isEmpty()) return null
        val maxContext = candidates.maxOf { it.capabilities.maxContextTokens }
        return candidates.minByOrNull { profile ->
            val qualityScore = profile.capabilities.maxContextTokens.toDouble() / maxContext
            val priceScore = profile.estimatedCost
            priceScore * 0.4 + (1.0 / (qualityScore + 0.1)) * 0.6
        }
    }
}

/**
 * 任务特定策略：根据 capability 自动选最合适的
 * - 优选 streaming
 * - 加分 toolStreaming
 * - 缺 cost = 0 的 adapter 视为"未知价格" → 排在最后
 */
class TaskSpecificStrategy : RoutingStrategy {
    override fun select(task: RoutingTask, candidates: List<AdapterProfile>): AdapterProfile? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { profile ->
            val cap = profile.capabilities
            var score = profile.estimatedCost
            if (cap.streaming) score -= 0.5
            if (cap.toolStreaming) score -= 0.3
            if (cap.promptCaching) score -= 0.2
            if (cap.maxContextTokens >= 200_000) score -= 0.1
            // 未知价格（0.0）排在最前（视为"免费"）实际可能反而是最贵的，加点 penalty
            if (profile.estimatedCost == 0.0) score += 1.0
            score
        }
    }
}

/**
 * 健康跟踪器：实现熔断器
 *
 * 连续 N 次失败后熔断（circuit open），跳过该 adapter N 分钟。
 * Cooldown 期满后允许一次尝试（half-open），成功则关闭熔断。
 */
class HealthTracker(
    private val maxConsecutiveFailures: Int = 3,
    private val cooldownMs: Long = 5 * 60_000L
) {
    private val logger = Logger.getLogger<HealthTracker>()
    private val failureCount = ConcurrentHashMap<String, AtomicInteger>()
    private val lastFailureMs = ConcurrentHashMap<String, AtomicLong>()
    private val circuitOpen = ConcurrentHashMap<String, AtomicInteger>()

    fun recordSuccess(providerName: String) {
        failureCount[providerName]?.set(0)
        val open = circuitOpen[providerName]?.get() ?: 0
        if (open == 1) {
            circuitOpen[providerName]?.set(0)
            logger.info("Circuit CLOSED for $providerName after success")
        }
    }

    fun recordFailure(providerName: String) {
        val count = failureCount.computeIfAbsent(providerName) { AtomicInteger(0) }.incrementAndGet()
        lastFailureMs.computeIfAbsent(providerName) { AtomicLong(0) }.set(System.currentTimeMillis())
        if (count >= maxConsecutiveFailures) {
            circuitOpen.computeIfAbsent(providerName) { AtomicInteger(0) }.set(1)
            logger.warn("Circuit OPEN for $providerName after $count consecutive failures")
        }
    }

    fun isAvailable(providerName: String): Boolean {
        val open = circuitOpen[providerName]?.get() ?: 0
        if (open == 0) return true
        val lastFailure = lastFailureMs[providerName]?.get() ?: 0
        val elapsed = System.currentTimeMillis() - lastFailure
        if (elapsed >= cooldownMs) {
            // cooldown 期满，half-open
            circuitOpen[providerName]?.set(0)
            failureCount[providerName]?.set(0)
            logger.info("Circuit HALF-OPEN for $providerName (cooldown expired)")
            return true
        }
        return false
    }

    fun isCircuitOpen(providerName: String): Boolean = circuitOpen[providerName]?.get() == 1

    fun getFailureCount(providerName: String): Int = failureCount[providerName]?.get() ?: 0
}

/**
 * 智能路由器主类
 */
class SmartRouter(
    private val registry: ModelRegistry,
    private val strategy: RoutingStrategy = TaskSpecificStrategy(),
    private val healthTracker: HealthTracker = HealthTracker(),
    private val excludedProviders: Set<String> = emptySet(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val logger = Logger.getLogger<SmartRouter>()

    fun selectAdapter(
        task: RoutingTask,
        preferredModel: String? = null,
        estimatedInputTokens: Int = 1000
    ): ModelAdapter? {
        // 1. 优先使用显式指定的 model
        preferredModel?.let { model ->
            val adapter = registry.getAdapterForModel(model)
            if (adapter != null && isAvailable(adapter.providerName)) {
                return adapter
            }
            logger.warn("Preferred model $model unavailable, falling back to strategy")
        }

        // 2. 按能力反查所有候选
        val required = task.requiredCapabilities()
        val candidates = registry.getAdaptersForCapabilities(required)
            .filter { it.providerName !in excludedProviders }
            .filter { isAvailable(it.providerName) }
            .map { AdapterProfile.fromAdapter(it, estimatedInputTokens) }

        if (candidates.isEmpty()) {
            logger.warn("No available adapter for required capabilities: $required (excluded=$excludedProviders)")
            return null
        }

        // 3. 策略选择
        val selected = strategy.select(task, candidates)
        if (selected != null) {
            logger.debug("Selected ${selected.providerName} via ${strategy.javaClass.simpleName}")
        }
        return selected?.adapter
    }

    fun recordSuccess(adapter: ModelAdapter) = healthTracker.recordSuccess(adapter.providerName)
    fun recordFailure(adapter: ModelAdapter) = healthTracker.recordFailure(adapter.providerName)

    private fun isAvailable(providerName: String): Boolean =
        providerName !in excludedProviders && healthTracker.isAvailable(providerName)
}
