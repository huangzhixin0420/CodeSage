package com.codesage.ide.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * CodeSage 设置根分组节点。
 *
 * 作为父分组，不显示具体内容；子节点在 plugin.xml 中注册。
 */
class PluginSettingsConfigurable : Configurable {
    override fun createComponent(): JComponent? = null
    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}
    override fun getDisplayName(): String = "CodeSage"
}
