package com.codesage.agent.core

import com.intellij.openapi.project.Project

/**
 * 轻量级 Project 代理：只覆盖 [getBasePath]，其它所有方法委托给原始 Project。
 *
 * 用途：让子 Agent 在 git worktree 等隔离目录中运行时，文件/命令类工具通过
 * [project.basePath] 解析出的路径自动指向 worktree，而不是主项目目录。
 *
 * 实现采用 Kotlin 接口委托（by original），避免动态代理带来的 default 方法、
 * 类型转换和 IDE 内部调用兼容性问题。
 */
class ProjectProxy private constructor(
    original: Project,
    private val basePathOverride: String
) : Project by original {

    override fun getBasePath(): String = basePathOverride

    companion object {
        /**
         * 若 [basePathOverride] 非空，则返回覆盖后的代理；否则返回原 [original]。
         */
        fun create(original: Project?, basePathOverride: String?): Project? {
            if (original == null || basePathOverride.isNullOrBlank()) return original
            return ProjectProxy(original, basePathOverride)
        }
    }
}
