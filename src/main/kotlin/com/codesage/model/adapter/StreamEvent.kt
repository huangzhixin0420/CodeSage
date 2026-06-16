package com.codesage.model.adapter

import com.codesage.model.dto.FinishReason
import com.codesage.model.dto.Usage

/**
 * 2026-06: 多候选标识 — 所有内容类/工具调用/代码块/引用/多模态/流控制事件携带
 * `choiceIndex`,作为协议层就绪位。n=1 时所有 event.choiceIndex = 0。
 *
 * 本版本协议层强制 n=1,Normalizer 映射时一律填 0。`ChatRequest.n` 不开放,
 * 应用层当前不调用 n>1。详见 [docs/refactor/future-tasks/多候选响应-n1协议层暴露.md]。
 */
interface ChoiceScoped {
    val choiceIndex: Int
}

/**
 * 2026-06: 流式响应事件树(分形 sealed tree)。
 *
 * 替代之前 8 字段 union bag `StreamChunk`,把"一个 SSE 行解析出的所有可能信息"
 * 拆为互斥的 case 节点。下游 handler 可以用父类型统一处理一类业务:
 *
 *   onContent(event: StreamEvent.Content)       // 父类型入口
 *   onToolCall(event: StreamEvent.ToolCall)     // 父类型入口
 *   onFlow(event: StreamEvent.Flow)             // 父类型入口
 *
 * 加新事件类型(如 Citation / PlanStep / 多模态)只动:
 *   1) 在对应子树下加一个 case
 *   2) Normalizer 加映射
 *   3) Reducer 加 when 分支
 *   4) 测 3 个新测试
 *
 * 设计依据: docs/refactor/StreamChunk中转层重构-2026-06-16-02.md §2.3
 */
sealed interface StreamEvent {

    /** 文本类内容(正文 / 推理 / 计划步骤,统一抽象) */
    sealed interface Content : StreamEvent, ChoiceScoped {
        val delta: String

        /** 普通正文片段 */
        data class Text(
            override val choiceIndex: Int = 0,
            override val delta: String,
        ) : Content

        /** 思考链片段(OpenAI reasoning_content / Anthropic thinking_delta / think 标签) */
        data class Reasoning(
            override val choiceIndex: Int = 0,
            override val delta: String,
        ) : Content

        /**
         * 计划步骤片段 —— 协议层占位(本版本 Normalizer 不实现, 树里留位置)。
         *
         * 来源: 协议支持 Anthropic 4.x Beta `content_block` (type=plan),
         *       OpenAI o-series 在 system prompt 引导下可产生。
         * 下游: Reducer 累积到 state.planSteps, emit AgentStreamEvent.PlanStep 给 UI。
         *
         * 未来任务: docs/refactor/future-tasks/PlanStep-跨协议适配.md
         */
        data class PlanStep(
            override val choiceIndex: Int = 0,
            override val delta: String,
            val stepIndex: Int? = null,
        ) : Content
    }

    /** 工具调用(独立通道,可能与 Content 并行) */
    sealed interface ToolCall : StreamEvent, ChoiceScoped {
        val toolCallId: String
        val toolName: String?

        data class Delta(
            override val choiceIndex: Int = 0,
            override val toolCallId: String,
            override val toolName: String?,
            val argumentsFragment: String,
        ) : ToolCall
    }

    /** 代码块(带生命周期) */
    sealed interface CodeBlock : StreamEvent, ChoiceScoped {
        val codeBlockId: String

        data class Started(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
            val language: String? = null,
        ) : CodeBlock

        data class Delta(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
            val delta: String,
        ) : CodeBlock

        data class Ended(
            override val choiceIndex: Int = 0,
            override val codeBlockId: String,
        ) : CodeBlock
    }

    /** 引用/检索结果(RAG 场景,本版本预留占位不实现) */
    sealed interface Citation : StreamEvent, ChoiceScoped {
        val sourceId: String

        data class Delta(
            override val choiceIndex: Int = 0,
            override val sourceId: String,
            val snippetFragment: String,
            val title: String? = null,
            val url: String? = null,
        ) : Citation
    }

    /** 多模态内容(独立通道,本版本预留占位不实现) */
    sealed interface Media : StreamEvent, ChoiceScoped {
        val mimeType: String

        data class ImageFragment(
            override val choiceIndex: Int = 0,
            override val mimeType: String,
            val data: ByteArray,
        ) : Media {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is ImageFragment) return false
                if (choiceIndex != other.choiceIndex) return false
                if (mimeType != other.mimeType) return false
                if (!data.contentEquals(other.data)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = choiceIndex
                result = 31 * result + mimeType.hashCode()
                result = 31 * result + data.contentHashCode()
                return result
            }
        }

        data class AudioFragment(
            override val choiceIndex: Int = 0,
            override val mimeType: String,
            val data: ByteArray,
        ) : Media {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is AudioFragment) return false
                if (choiceIndex != other.choiceIndex) return false
                if (mimeType != other.mimeType) return false
                if (!data.contentEquals(other.data)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = choiceIndex
                result = 31 * result + mimeType.hashCode()
                result = 31 * result + data.contentHashCode()
                return result
            }
        }
    }

    /** 流控制事件(与"内容"互斥) */
    sealed interface Flow : StreamEvent, ChoiceScoped {
        data class Started(override val choiceIndex: Int = 0) : Flow

        data class Finished(
            override val choiceIndex: Int = 0,
            val finishReason: FinishReason,
            val usage: Usage? = null,
        ) : Flow

        data class Cancelled(override val choiceIndex: Int = 0) : Flow

        data class Error(
            override val choiceIndex: Int = 0,
            val message: String,
            val code: String? = null,
        ) : Flow
    }
}
