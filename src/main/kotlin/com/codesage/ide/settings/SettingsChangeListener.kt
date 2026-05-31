package com.codesage.ide.settings

import com.intellij.util.messages.Topic

/**
 * CodeSage 配置变更监听器
 *
 * 通过 IntelliJ MessageBus 广播，实现 Settings 页面与工具窗口的实时联动。
 */
interface SettingsChangeListener {
    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<SettingsChangeListener> = Topic.create(
            "CodeSage Settings Changed",
            SettingsChangeListener::class.java
        )
    }

    /**
     * 配置已应用（用户点击了 OK / Apply）
     */
    fun onSettingsApplied()

    /**
     * 默认模型已变更
     */
    fun onDefaultModelChanged(model: String, providerId: String)
}
