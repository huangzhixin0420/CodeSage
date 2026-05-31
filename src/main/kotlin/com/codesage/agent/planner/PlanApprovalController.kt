package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 计划审批状态
 */
enum class PlanApprovalStatus {
    PENDING,    // 等待用户审批
    APPROVED,   // 已批准
    MODIFIED,   // 已修改
    REJECTED,   // 已拒绝
    EXPIRED     // 已超时
}

/**
 * 用户审批决策
 */
sealed class ApprovalDecision {
    data class Approve(val planId: String) : ApprovalDecision()
    data class Modify(val planId: String, val modifiedPlan: DagTaskPlan) : ApprovalDecision()
    data class Reject(val planId: String, val reason: String) : ApprovalDecision()
}

/**
 * 计划审批请求
 */
data class PlanApprovalRequest(
    val planId: String,
    val originalPlan: DagTaskPlan,
    val status: PlanApprovalStatus = PlanApprovalStatus.PENDING,
    val requestedAt: Long = System.currentTimeMillis()
)

/**
 * 计划审批控制器
 * 管理任务计划的人机协作交互：approve / modify / reject
 * 支持超时自动取消
 */
class PlanApprovalController(
    private val defaultTimeout: Duration = 5.minutes
) {
    private val pendingApprovals = ConcurrentHashMap<String, PlanApprovalRequest>()
    private val approvalContinuations = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision?>>()
    private val mutex = Mutex()

    /**
     * 提交计划并等待用户审批
     * 返回 Flow，先 emit PlanGenerated，然后阻塞等待用户决策
     *
     * @param plan 生成的 DAG 任务计划
     * @param timeout 超时时间，默认 5 分钟
     * @return Flow<AgentStreamEvent> 包含计划生成事件和最终的审批结果事件
     */
    fun requestApproval(
        plan: DagTaskPlan,
        timeout: Duration = defaultTimeout
    ): Flow<AgentStreamEvent> = flow {
        val request = PlanApprovalRequest(planId = plan.taskId, originalPlan = plan)
        pendingApprovals[plan.taskId] = request

        // Emit 计划生成事件
        emit(
            AgentStreamEvent.PlanGenerated(
                planId = plan.taskId,
                description = plan.description,
                steps = plan.steps.map {
                    AgentStreamEvent.PlanStep(
                        id = it.id,
                        description = it.description,
                        dependsOn = it.dependencies
                    )
                }
            )
        )

        // 等待用户决策（可挂起）
        val deferred = CompletableDeferred<ApprovalDecision?>()
        approvalContinuations[plan.taskId] = deferred

        val decision = try {
            withTimeout(timeout) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            pendingApprovals[plan.taskId] = request.copy(status = PlanApprovalStatus.EXPIRED)
            null
        } finally {
            approvalContinuations.remove(plan.taskId)
        }

        when (decision) {
            is ApprovalDecision.Approve -> {
                pendingApprovals[plan.taskId] = request.copy(status = PlanApprovalStatus.APPROVED)
                emit(AgentStreamEvent.PlanApproved(planId = plan.taskId))
            }

            is ApprovalDecision.Modify -> {
                pendingApprovals[plan.taskId] = request.copy(status = PlanApprovalStatus.MODIFIED)
                emit(
                    AgentStreamEvent.PlanModified(
                        planId = plan.taskId,
                        steps = decision.modifiedPlan.steps.map {
                            AgentStreamEvent.PlanStep(
                                id = it.id,
                                description = it.description,
                                dependsOn = it.dependencies
                            )
                        }
                    )
                )
            }

            is ApprovalDecision.Reject -> {
                pendingApprovals[plan.taskId] = request.copy(status = PlanApprovalStatus.REJECTED)
                emit(
                    AgentStreamEvent.PlanRejected(
                        planId = plan.taskId,
                        reason = decision.reason
                    )
                )
            }

            null -> {
                pendingApprovals[plan.taskId] = request.copy(status = PlanApprovalStatus.EXPIRED)
                emit(
                    AgentStreamEvent.PlanRejected(
                        planId = plan.taskId,
                        reason = "Approval timeout after ${timeout.inWholeMinutes} minutes"
                    )
                )
            }
        }
    }

    /**
     * 用户批准计划
     */
    suspend fun approve(planId: String): Boolean {
        val deferred = approvalContinuations[planId] ?: return false
        deferred.complete(ApprovalDecision.Approve(planId))
        return true
    }

    /**
     * 用户修改计划
     */
    suspend fun modify(planId: String, modifiedPlan: DagTaskPlan): Boolean {
        val deferred = approvalContinuations[planId] ?: return false
        deferred.complete(ApprovalDecision.Modify(planId, modifiedPlan))
        return true
    }

    /**
     * 用户拒绝计划
     */
    suspend fun reject(planId: String, reason: String = "User rejected"): Boolean {
        val deferred = approvalContinuations[planId] ?: return false
        deferred.complete(ApprovalDecision.Reject(planId, reason))
        return true
    }

    /**
     * 检查计划是否处于待审批状态
     */
    fun isPending(planId: String): Boolean {
        return pendingApprovals[planId]?.status == PlanApprovalStatus.PENDING
    }

    /**
     * 获取所有待审批的计划
     */
    fun getPendingApprovals(): List<PlanApprovalRequest> {
        return pendingApprovals.values.filter { it.status == PlanApprovalStatus.PENDING }
    }

    /**
     * 获取指定计划的当前状态
     */
    fun getApprovalStatus(planId: String): PlanApprovalStatus? {
        return pendingApprovals[planId]?.status
    }

    /**
     * 取消指定计划的审批请求
     */
    fun cancel(planId: String) {
        val deferred = approvalContinuations[planId]
        deferred?.cancel()
        approvalContinuations.remove(planId)
        pendingApprovals.remove(planId)
    }

    /**
     * 清理所有已完成的审批记录
     */
    suspend fun cleanupCompleted() {
        mutex.withLock {
            val completedStatuses = setOf(
                PlanApprovalStatus.APPROVED,
                PlanApprovalStatus.REJECTED,
                PlanApprovalStatus.EXPIRED
            )
            pendingApprovals.entries.removeAll { it.value.status in completedStatuses }
        }
    }
}
