package com.codesage.perf

import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * 并发请求限制器
 * 控制对LLM API的并发请求数量，防止触发速率限制
 */
class ConcurrentRequestLimiter(
    private val globalMaxConcurrent: Int = 3,
    private val perProviderMaxConcurrent: Int = 2,
    private val queueMaxSize: Int = 20
) {
    private val logger = Logger.getLogger<ConcurrentRequestLimiter>()

    // 全局信号量
    private val globalSemaphore = Semaphore(globalMaxConcurrent)

    // 每个提供商的信号量
    private val providerSemaphores = ConcurrentHashMap<String, Semaphore>()

    // 等待队列
    private val waitingQueue = ConcurrentHashMap<String, AtomicInteger>()

    // 活跃请求计数
    private val activeRequests = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 在限流控制下执行请求
     */
    suspend fun <T> execute(
        providerId: String = "default",
        timeoutMs: Long = 60000,
        block: suspend () -> T
    ): T {
        val providerSemaphore = providerSemaphores.getOrPut(providerId) {
            Semaphore(perProviderMaxConcurrent)
        }

        val waitKey = "$providerId:${coroutineContext[CoroutineName]?.name ?: "unknown"}"
        val waitingCounter = waitingQueue.getOrPut(waitKey) { AtomicInteger(0) }

        // 检查队列是否已满
        if (waitingCounter.incrementAndGet() > queueMaxSize) {
            waitingCounter.decrementAndGet()
            throw RequestQueueFullException("Request queue full for provider: $providerId (max: $queueMaxSize)")
        }

        val activeCounter = activeRequests.getOrPut(providerId) { AtomicInteger(0) }

        try {
            logger.debug("Request queued for $providerId, waiting: ${waitingCounter.get()}, active: ${activeCounter.get()}")

            return withTimeout(timeoutMs) {
                // 先获取提供商级别的许可
                providerSemaphore.withPermit {
                    // 再获取全局许可
                    globalSemaphore.withPermit {
                        waitingCounter.decrementAndGet()
                        val active = activeCounter.incrementAndGet()
                        logger.debug("Request started for $providerId, active: $active")

                        try {
                            block()
                        } finally {
                            activeCounter.decrementAndGet()
                            logger.debug("Request completed for $providerId")
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            waitingCounter.decrementAndGet()
            throw RequestTimeoutException("Request timed out after ${timeoutMs}ms for provider: $providerId")
        } catch (e: Exception) {
            waitingCounter.decrementAndGet()
            throw e
        }
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): LimiterStatus {
        return LimiterStatus(
            globalActive = globalMaxConcurrent - globalSemaphore.availablePermits,
            globalMax = globalMaxConcurrent,
            providerStats = activeRequests.mapValues { (_, counter) ->
                ProviderStatus(
                    active = counter.get(),
                    max = perProviderMaxConcurrent
                )
            },
            totalWaiting = waitingQueue.values.sumOf { it.get() }
        )
    }

    data class LimiterStatus(
        val globalActive: Int,
        val globalMax: Int,
        val providerStats: Map<String, ProviderStatus>,
        val totalWaiting: Int
    )

    data class ProviderStatus(
        val active: Int,
        val max: Int
    )
}

class RequestQueueFullException(message: String) : Exception(message)
class RequestTimeoutException(message: String) : Exception(message)
