package com.codesage.ide.settings

import com.codesage.shared.config.PluginConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * 预算与轮次配置面板 —— 独立 Tab
 */
class BudgetSettingsPanel : JPanel(BorderLayout()) {

    // 预算 UI 控件
    private val maxIterationsField = JBTextField("15", 5)
    private val maxTokensField = JBTextField("0", 8)
    private val maxDurationField = JBTextField("300", 6)
    private val enableIterationCheck = JBCheckBox("启用迭代次数预算", true)
    private val enableTokenCheck = JBCheckBox("启用 Token 预算", false)
    private val enableTimeCheck = JBCheckBox("启用时间预算", true)
    private val warningThresholdCombo = ComboBox((10..90 step 10).map { "$it%" }.toTypedArray())
    private val subAgentRatioCombo = ComboBox((1..10).map { "${it * 10}%" }.toTypedArray())
    private val allowContinueCheck = JBCheckBox("预算耗尽后允许继续执行", true)
    private val resetButton = JButton("恢复默认值")

    // 校验错误标签
    private val maxIterationsError = JBLabel("").apply { foreground = JBColor.RED; font = JBUI.Fonts.smallFont() }
    private val maxTokensError = JBLabel("").apply { foreground = JBColor.RED; font = JBUI.Fonts.smallFont() }
    private val maxDurationError = JBLabel("").apply { foreground = JBColor.RED; font = JBUI.Fonts.smallFont() }

    // 临时数据副本
    private var tempMaxIterations = 15
    private var tempMaxTokens = 0
    private var tempMaxDuration = 300
    private var tempEnableIterationBudget = true
    private var tempEnableTokenBudget = false
    private var tempEnableTimeBudget = true
    private var tempBudgetWarningThreshold = 70
    private var tempSubAgentBudgetRatio = 50
    private var tempAllowContinueOnExhaustion = true

    init {
        border = JBUI.Borders.empty(12)
        setupUI()
        reset()
    }

    private fun setupUI() {
        val budgetTypeForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("最大迭代次数:"),
                wrapField(maxIterationsField, maxIterationsError),
                JBUI.scale(8),
                false
            )
            .addComponentToRightColumn(enableIterationCheck, JBUI.scale(4))
            .addLabeledComponent(
                JBLabel("最大 Token 数:"),
                wrapField(maxTokensField, maxTokensError),
                JBUI.scale(8),
                false
            )
            .addComponentToRightColumn(enableTokenCheck, JBUI.scale(4))
            .addLabeledComponent(
                JBLabel("最大耗时(秒):"),
                wrapField(maxDurationField, maxDurationError),
                JBUI.scale(8),
                false
            )
            .addComponentToRightColumn(enableTimeCheck, JBUI.scale(4))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val warningForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("预警阈值:"), warningThresholdCombo, JBUI.scale(8), false)
            .addLabeledComponent(JBLabel("子Agent预算比例:"), subAgentRatioCombo, JBUI.scale(8), false)
            .addComponentToRightColumn(allowContinueCheck, JBUI.scale(4))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val budgetTypePanel = JPanel(BorderLayout(0, JBUI.scale(8)))
        budgetTypePanel.add(TitledSeparator("\u26A1 预算类型"), BorderLayout.NORTH)
        budgetTypePanel.add(budgetTypeForm, BorderLayout.CENTER)

        val warningPanel = JPanel(BorderLayout(0, JBUI.scale(8)))
        warningPanel.add(TitledSeparator("\u26A0 预警设置"), BorderLayout.NORTH)
        warningPanel.add(warningForm, BorderLayout.CENTER)

        val content = JPanel(BorderLayout(0, JBUI.scale(12)))
        content.add(budgetTypePanel, BorderLayout.NORTH)
        content.add(warningPanel, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        resetButton.addActionListener { resetToDefaults() }
        buttonPanel.add(resetButton)
        content.add(buttonPanel, BorderLayout.SOUTH)

        add(content, BorderLayout.NORTH)

        setupCheckboxLinkages()
        setupValidation()
    }

    private fun wrapField(field: JComponent, errorLabel: JBLabel? = null): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        panel.add(field)
        errorLabel?.let { panel.add(it) }
        return panel
    }

    private fun setupCheckboxLinkages() {
        enableIterationCheck.addActionListener { updateFieldEnabledState() }
        enableTokenCheck.addActionListener { updateFieldEnabledState() }
        enableTimeCheck.addActionListener { updateFieldEnabledState() }
        updateFieldEnabledState()
    }

    private fun updateFieldEnabledState() {
        maxIterationsField.isEnabled = enableIterationCheck.isSelected
        maxTokensField.isEnabled = enableTokenCheck.isSelected
        maxDurationField.isEnabled = enableTimeCheck.isSelected
    }

    private fun setupValidation() {
        addNumberValidation(maxIterationsField, maxIterationsError, 1, 1000, "请输入 1-1000 的整数")
        addNumberValidation(maxTokensField, maxTokensError, 0, 1_000_000, "请输入 0-1000000 的整数")
        addNumberValidation(maxDurationField, maxDurationError, 1, 86_400, "请输入 1-86400 的整数")
    }

    private fun addNumberValidation(field: JBTextField, errorLabel: JBLabel, min: Int, max: Int, message: String) {
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = validate()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = validate()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = validate()

            private fun validate() {
                val text = field.text.trim()
                if (text.isEmpty()) {
                    errorLabel.text = ""
                    field.foreground = UIManager.getColor("TextField.foreground") ?: JBColor.BLACK
                    return
                }
                val value = text.toIntOrNull()
                if (value == null || value < min || value > max) {
                    errorLabel.text = message
                    field.foreground = JBColor.RED
                } else {
                    errorLabel.text = ""
                    field.foreground = UIManager.getColor("TextField.foreground") ?: JBColor.BLACK
                }
            }
        })
    }

    private fun hasValidationErrors(): Boolean {
        return maxIterationsError.text.isNotEmpty()
                || maxTokensError.text.isNotEmpty()
                || maxDurationError.text.isNotEmpty()
    }

    private fun resetToDefaults() {
        maxIterationsField.text = "15"
        maxTokensField.text = "0"
        maxDurationField.text = "300"
        enableIterationCheck.isSelected = true
        enableTokenCheck.isSelected = false
        enableTimeCheck.isSelected = true
        warningThresholdCombo.selectedItem = "70%"
        subAgentRatioCombo.selectedItem = "50%"
        allowContinueCheck.isSelected = true
        updateFieldEnabledState()
    }

    private fun syncFromUI() {
        tempMaxIterations = maxIterationsField.text.toIntOrNull() ?: tempMaxIterations
        tempMaxTokens = maxTokensField.text.toIntOrNull() ?: tempMaxTokens
        tempMaxDuration = maxDurationField.text.toIntOrNull() ?: tempMaxDuration
        tempEnableIterationBudget = enableIterationCheck.isSelected
        tempEnableTokenBudget = enableTokenCheck.isSelected
        tempEnableTimeBudget = enableTimeCheck.isSelected
        tempBudgetWarningThreshold =
            (warningThresholdCombo.selectedItem as? String ?: "${tempBudgetWarningThreshold}%")
                .removeSuffix("%").toIntOrNull() ?: tempBudgetWarningThreshold
        tempSubAgentBudgetRatio =
            ((subAgentRatioCombo.selectedItem as? String ?: "${tempSubAgentBudgetRatio}%")
                .removeSuffix("%").toIntOrNull() ?: tempSubAgentBudgetRatio)
        tempAllowContinueOnExhaustion = allowContinueCheck.isSelected
    }

    fun isModified(): Boolean {
        syncFromUI()
        val config = PluginConfig.getInstance()
        return tempMaxIterations != config.maxIterationsPerTask ||
                tempMaxTokens != config.maxTokensPerTask ||
                tempMaxDuration != config.maxDurationSecondsPerTask ||
                tempEnableIterationBudget != config.enableIterationBudget ||
                tempEnableTokenBudget != config.enableTokenBudget ||
                tempEnableTimeBudget != config.enableTimeBudget ||
                tempBudgetWarningThreshold != config.budgetWarningThreshold ||
                tempSubAgentBudgetRatio != (config.subAgentBudgetRatio * 100).toInt() ||
                tempAllowContinueOnExhaustion != config.allowContinueOnExhaustion
    }

    fun apply() {
        if (hasValidationErrors()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(this, "请修正输入错误后再保存", "校验失败")
            return
        }
        syncFromUI()
        val config = PluginConfig.getInstance()
        config.maxIterationsPerTask = tempMaxIterations
        config.maxTokensPerTask = tempMaxTokens
        config.maxDurationSecondsPerTask = tempMaxDuration
        config.enableIterationBudget = tempEnableIterationBudget
        config.enableTokenBudget = tempEnableTokenBudget
        config.enableTimeBudget = tempEnableTimeBudget
        config.budgetWarningThreshold = tempBudgetWarningThreshold
        config.subAgentBudgetRatio = tempSubAgentBudgetRatio / 100.0
        config.allowContinueOnExhaustion = tempAllowContinueOnExhaustion

        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangeListener.TOPIC)
            .onSettingsApplied()
    }

    fun reset() {
        val config = PluginConfig.getInstance()
        tempMaxIterations = config.maxIterationsPerTask
        tempMaxTokens = config.maxTokensPerTask
        tempMaxDuration = config.maxDurationSecondsPerTask
        tempEnableIterationBudget = config.enableIterationBudget
        tempEnableTokenBudget = config.enableTokenBudget
        tempEnableTimeBudget = config.enableTimeBudget
        tempBudgetWarningThreshold = config.budgetWarningThreshold
        tempSubAgentBudgetRatio = (config.subAgentBudgetRatio * 100).toInt()
        tempAllowContinueOnExhaustion = config.allowContinueOnExhaustion

        maxIterationsField.text = tempMaxIterations.toString()
        maxTokensField.text = tempMaxTokens.toString()
        maxDurationField.text = tempMaxDuration.toString()
        enableIterationCheck.isSelected = tempEnableIterationBudget
        enableTokenCheck.isSelected = tempEnableTokenBudget
        enableTimeCheck.isSelected = tempEnableTimeBudget
        warningThresholdCombo.selectedItem = "${tempBudgetWarningThreshold}%"
        subAgentRatioCombo.selectedItem = "${tempSubAgentBudgetRatio}%"
        allowContinueCheck.isSelected = tempAllowContinueOnExhaustion
        updateFieldEnabledState()
    }
}
