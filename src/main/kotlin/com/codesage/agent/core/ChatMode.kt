package com.codesage.agent.core

/**
 * 对话模式枚举
 * 用于区分不同任务类型，自动路由到对应的模型配置。
 *
 * 设计思路：
 * - GENERAL: 通用对话、问答、文档处理
 * - CODING: 代码生成、重构、审查、调试（对应 Kimi Coding Plan / MiniMax M2.1 等编程模型）
 * - REASONING: 深度推理、复杂问题分析、数学/逻辑任务
 * - VISION: 多模态任务（图片理解、视觉分析）
 */
enum class ChatMode(val displayName: String, val description: String) {
    GENERAL("通用", "通用对话与问答"),
    CODING("编程", "代码生成、重构与审查"),
    REASONING("推理", "深度推理与复杂分析"),
    VISION("视觉", "图片理解与多模态分析");

    companion object {
        fun fromString(value: String): ChatMode = entries.find { it.name == value } ?: GENERAL
    }
}
