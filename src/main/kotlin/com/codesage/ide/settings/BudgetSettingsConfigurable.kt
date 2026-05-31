package com.codesage.ide.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * 预算与轮次设置页面
 */
class BudgetSettingsConfigurable : Configurable {
    private var panel: BudgetSettingsPanel? = null

    override fun createComponent(): JComponent {
        panel = BudgetSettingsPanel()
        return panel!!
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() = panel?.apply() ?: Unit

    override fun reset() = panel?.reset() ?: Unit

    override fun getDisplayName(): String = "Budget & Rounds"

    override fun disposeUIResources() {
        panel = null
    }
}
