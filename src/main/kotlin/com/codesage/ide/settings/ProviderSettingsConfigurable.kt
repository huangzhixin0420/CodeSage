package com.codesage.ide.settings

import com.codesage.model.adapter.minimax.MiniMaxAdapter

import com.codesage.model.adapter.OpenAICompatibleAdapter
import com.codesage.model.gateway.ModelGateway
import com.codesage.model.registry.ModelRegistry
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.config.ProviderConfig
import com.codesage.shared.config.ProviderTemplate
import com.codesage.shared.config.ProviderTypes
import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.util.*
import javax.swing.*

/**
 * 提供商与通用设置页面
 */
class ProviderSettingsConfigurable : Configurable {
    private val logger = Logger.getLogger<ProviderSettingsConfigurable>()
    private var settingsPanel: ProviderSettingsPanel? = null

    override fun createComponent(): JComponent {
        settingsPanel = ProviderSettingsPanel()
        return settingsPanel!!
    }

    override fun isModified(): Boolean = settingsPanel?.isModified() ?: false

    override fun apply() {
        settingsPanel?.apply()
        logger.info("Provider settings applied")
    }

    override fun reset() = settingsPanel?.reset() ?: Unit
    override fun getDisplayName(): String = "Providers & General"
    override fun disposeUIResources() {
        settingsPanel = null
    }
}

/* ==================== 主面板 ==================== */

class ProviderSettingsPanel : JPanel(BorderLayout()) {
    private val logger = Logger.getLogger<ProviderSettingsPanel>()

    companion object {
        private var instanceCount = 0
    }

    private val panelId = ++instanceCount

    // 临时数据副本
    private var providerData = mutableListOf<ProviderEditData>()
    private var tempDefaultProviderId = ""
    private var tempDefaultModel = ""
    private var tempEnableStreaming = true

    // 当前正在编辑的 provider 索引（用于解决切换时的保存竞态）
    private var editingProviderIndex: Int = -1

    // 左侧列表
    private val listModel = CollectionListModel<ProviderEditData>()
    private val providerList = JBList(listModel)

    // 右侧详情面板
    private val detailCards = JPanel(CardLayout())
    private val emptyPanel = JPanel(BorderLayout())
    private val editPanel = JPanel(BorderLayout())

    // 全局模型设置面板（从 provider 详情中独立出来）
    private lateinit var generalSection: JPanel

    // 编辑表单
    private val nameField = JBTextField()
    private val typeCombo = ComboBox(ProviderTemplate.TEMPLATES.map { it.name }.toTypedArray())
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val modelsField = JBTextField()
    private val enabledCheckBox = JCheckBox("启用此提供商")

    // 通用设置
    private val defaultModelCombo = ComboBox<String>()
    private val streamingCheckBox = JCheckBox("启用流式输出")
    private val testButton = JButton("测试连接")
    private val fetchModelsButton = JButton("获取模型列表")
    private val apiKeyToggleBtn = JButton("显示").apply {
        preferredSize = Dimension(JBUI.scale(52), JBUI.scale(24))
        addActionListener {
            if (apiKeyField.echoChar == '\u0000') {
                apiKeyField.echoChar = '\u2022'
                text = "显示"
            } else {
                apiKeyField.echoChar = '\u0000'
                text = "隐藏"
            }
        }
    }
    private val testSpinner = com.intellij.util.ui.AsyncProcessIcon("test-connection")
    private val fetchSpinner = com.intellij.util.ui.AsyncProcessIcon("fetch-models")
    private val resetGeneralButton = JButton("恢复默认值").apply {
        addActionListener {
            streamingCheckBox.isSelected = true
            refreshModelCombos()
        }
    }

    init {
        border = JBUI.Borders.empty(12)
        setupUI()
        logger.info("ProviderSettingsPanel #$panelId created")
        reset()
    }

    /* ---------- UI 构建 ---------- */

    private fun setupUI() {
        // 左侧列表
        providerList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        providerList.cellRenderer = ProviderListCellRenderer()
        providerList.addListSelectionListener { onProviderSelected() }

        val decorator = ToolbarDecorator.createDecorator(providerList)
            .setAddAction { addProvider() }
            .setRemoveAction { removeProvider() }
            .disableUpDownActions()
            .createPanel()

        val leftPanel = JPanel(BorderLayout())
        leftPanel.border = JBUI.Borders.emptyRight(8)
        leftPanel.add(JBLabel("提供商列表").apply {
            font = JBUI.Fonts.label().biggerOn(1.0f)
            border = JBUI.Borders.emptyBottom(8)
        }, BorderLayout.NORTH)
        leftPanel.add(decorator, BorderLayout.CENTER)
        leftPanel.preferredSize = Dimension(JBUI.scale(180), 0)

        // 右侧详情
        setupEmptyPanel()
        setupEditPanel()
        detailCards.add(emptyPanel, "empty")
        detailCards.add(editPanel, "edit")
        (detailCards.layout as CardLayout).show(detailCards, "empty")

        // 右侧面板
        val rightPanel = JPanel(BorderLayout(0, JBUI.scale(12)))
        rightPanel.border = JBUI.Borders.emptyLeft(4)
        rightPanel.add(detailCards, BorderLayout.CENTER)

        // 全局模型设置（从 provider 详情中独立出来，避免用户误以为是 per-provider 的）
        val generalWrapper = JPanel(BorderLayout(0, JBUI.scale(4)))
        val generalHint = JBLabel("以下模型配置为全局设置，所有对话共用，不绑定特定提供商")
        generalHint.foreground = UIManager.getColor("Label.disabledForeground")
        generalHint.font = JBUI.Fonts.miniFont()
        generalWrapper.add(generalSection, BorderLayout.CENTER)
        generalWrapper.add(generalHint, BorderLayout.SOUTH)
        rightPanel.add(generalWrapper, BorderLayout.SOUTH)

        // 分栏
        val splitter = JBSplitter(false, 0.22f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
            dividerWidth = 1
        }
        add(splitter, BorderLayout.CENTER)
    }

    private fun wrapField(field: javax.swing.JComponent): javax.swing.JPanel {
        val panel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0))
        panel.add(field)
        return panel
    }

    private fun setupEmptyPanel() {
        emptyPanel.layout = GridBagLayout()
        val gbc = GridBagConstraints()
        val label = JBLabel("请选择一个提供商进行编辑，或点击 + 添加新提供商", SwingConstants.CENTER)
        label.foreground = UIManager.getColor("Label.disabledForeground")
        emptyPanel.add(label, gbc)
    }

    private fun setupEditPanel() {
        nameField.columns = 20
        baseUrlField.columns = 40
        apiKeyField.columns = 40
        modelsField.columns = 40
        modelsField.toolTipText = "逗号分隔的模型列表，如: gpt-4o, gpt-4o-mini"

        val apiKeyPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        apiKeyPanel.add(apiKeyField)
        apiKeyPanel.add(apiKeyToggleBtn)

        val modelsFieldPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        modelsFieldPanel.add(modelsField)
        fetchModelsButton.toolTipText = "从提供商 API 自动获取支持的模型列表"
        modelsFieldPanel.add(fetchModelsButton)
        modelsFieldPanel.add(fetchSpinner)
        fetchSpinner.isVisible = false

        // 提供商配置表单
        val providerForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("显示名称:"), wrapField(nameField), JBUI.scale(8), false)
            .addLabeledComponent(JBLabel("提供商类型:"), typeCombo, JBUI.scale(8), false)
            .addLabeledComponent(JBLabel("Base URL:"), wrapField(baseUrlField), JBUI.scale(8), false)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyPanel, JBUI.scale(8), false)
            .addLabeledComponent(JBLabel("模型列表:"), modelsFieldPanel, JBUI.scale(8), false)
            .addComponentToRightColumn(enabledCheckBox, JBUI.scale(4))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val testButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        testButtonPanel.add(testButton)
        testButtonPanel.add(testSpinner)
        testSpinner.isVisible = false

        // 通用设置表单
        val generalForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("默认模型:"), defaultModelCombo, JBUI.scale(8), false)
            .addComponentToRightColumn(streamingCheckBox, JBUI.scale(4))
            .addComponentToRightColumn(testButtonPanel, JBUI.scale(12))
            .addComponentToRightColumn(resetGeneralButton, JBUI.scale(4))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        // 组装 provider 详情（仅包含提供商配置，通用设置已独立到右侧面板底部）
        val providerSection = JPanel(BorderLayout())
        providerSection.add(TitledSeparator("提供商配置"), BorderLayout.NORTH)
        providerSection.add(providerForm, BorderLayout.CENTER)
        providerSection.border = JBUI.Borders.emptyBottom(8)
        editPanel.add(providerSection, BorderLayout.CENTER)

        // 全局模型设置面板（独立出来，避免用户误以为是 per-provider 的）
        generalSection = JPanel(BorderLayout())
        generalSection.add(TitledSeparator("全局模型设置"), BorderLayout.NORTH)
        generalSection.add(generalForm, BorderLayout.CENTER)

        // 事件
        typeCombo.addActionListener { onTemplateSelected() }
        testButton.addActionListener { testCurrentProvider() }
        fetchModelsButton.addActionListener { fetchModelsForCurrentProvider() }
        enabledCheckBox.addActionListener { onEnabledChanged() }
    }

    /* ---------- 列表操作 ---------- */

    private fun addProvider() {
        val options = ProviderTemplate.TEMPLATES.map { it.name }.toTypedArray()
        val choice = Messages.showChooseDialog(
            null, "选择要添加的提供商类型：", "添加提供商",
            Messages.getQuestionIcon(), options, options[0]
        )
        if (choice < 0) return

        val template = ProviderTemplate.TEMPLATES[choice]
        val data = ProviderEditData(
            provider = ProviderConfig().apply {
                id = UUID.randomUUID().toString()
                name = template.name
                providerType = template.providerType
                baseUrl = template.baseUrl
                models = ArrayList(template.defaultModels)
                isEnabled = true
            },
            apiKey = ""
        )

        saveCurrentEditToData()
        providerData.add(data)
        listModel.add(data)

        val listeners = providerList.listSelectionListeners
        listeners.forEach { providerList.removeListSelectionListener(it) }
        providerList.selectedIndex = listModel.size - 1
        listeners.forEach { providerList.addListSelectionListener(it) }

        editingProviderIndex = providerData.size - 1
        loadDataToEdit(data)
        (detailCards.layout as CardLayout).show(detailCards, "edit")
        refreshModelCombos()
    }

    private fun removeProvider() {
        val index = providerList.selectedIndex
        if (index < 0) return
        val data = listModel.getElementAt(index)
        val confirm = Messages.showYesNoDialog(
            this, "确定要删除提供商 \"${data.provider.name}\" 吗？", "删除提供商", Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        providerData.removeAt(index)
        listModel.remove(index)
        if (editingProviderIndex == index) {
            editingProviderIndex = -1
        } else if (editingProviderIndex > index) {
            editingProviderIndex--
        }
        if (listModel.isEmpty()) {
            (detailCards.layout as CardLayout).show(detailCards, "empty")
            editingProviderIndex = -1
        }
        refreshModelCombos()
    }

    private fun onProviderSelected() {
        saveCurrentEditToData()
        val index = providerList.selectedIndex
        if (index < 0) {
            (detailCards.layout as CardLayout).show(detailCards, "empty")
            return
        }
        val data = listModel.getElementAt(index)
        loadDataToEdit(data)
        (detailCards.layout as CardLayout).show(detailCards, "edit")
    }

    /* ---------- 编辑与数据同步 ---------- */

    private fun loadDataToEdit(data: ProviderEditData) {
        editingProviderIndex = providerData.indexOf(data)
        val p = data.provider
        nameField.text = p.name
        typeCombo.selectedItem = ProviderTemplate.TEMPLATES.find { it.providerType == p.providerType }?.name
            ?: ProviderTemplate.TEMPLATES.last().name
        baseUrlField.text = p.baseUrl
        apiKeyField.text = data.apiKey
        modelsField.text = p.models.joinToString(", ")
        enabledCheckBox.isSelected = p.isEnabled
        updateFormEnabledState(p.isEnabled)
    }

    private fun saveCurrentEditToData() {
        val index = editingProviderIndex
        if (index >= 0 && index < listModel.size) {
            val data = listModel.getElementAt(index)
            data.provider.name = nameField.text
            data.provider.baseUrl = baseUrlField.text
            data.apiKey = String(apiKeyField.password)
            data.provider.models = parseModelsField(modelsField.text).toMutableList()
            data.provider.isEnabled = enabledCheckBox.isSelected
            val templateName = typeCombo.selectedItem as? String
            if (templateName != null) {
                val template = ProviderTemplate.TEMPLATES.find { it.name == templateName }
                if (template != null) data.provider.providerType = template.providerType
            }
            listModel.contentsChanged(data)
        }
    }

    private fun onTemplateSelected() {
        val templateName = typeCombo.selectedItem as? String ?: return
        val template = ProviderTemplate.TEMPLATES.find { it.name == templateName } ?: return
        if (baseUrlField.text.isBlank()) baseUrlField.text = template.baseUrl
        if (modelsField.text.isBlank()) modelsField.text = template.defaultModels.joinToString(", ")
        val index = providerList.selectedIndex
        if (index >= 0) listModel.getElementAt(index).provider.providerType = template.providerType
    }

    private fun onEnabledChanged() {
        val enabled = enabledCheckBox.isSelected
        updateFormEnabledState(enabled)
        saveCurrentEditToData()
        refreshModelCombos()
    }

    private fun updateFormEnabledState(enabled: Boolean) {
        nameField.isEnabled = enabled
        typeCombo.isEnabled = enabled
        baseUrlField.isEnabled = enabled
        apiKeyField.isEnabled = enabled
        modelsField.isEnabled = enabled
    }

    private fun parseModelsField(text: String): MutableList<String> {
        return text.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    /* ---------- 默认模型 ---------- */

    private fun refreshModelCombos() {
        val allModels = mutableListOf<String>()
        providerData.filter { it.provider.isEnabled }.forEach { data ->
            data.provider.models.forEach { model ->
                val display = "${model}  (${data.provider.name})"
                allModels.add(display)
            }
        }

        fun refreshCombo(combo: ComboBox<String>, currentSelection: String?) {
            combo.removeAllItems()
            allModels.forEach { combo.addItem(it) }
            if (currentSelection != null && allModels.contains(currentSelection)) {
                combo.selectedItem = currentSelection
            } else if (allModels.isNotEmpty()) {
                combo.selectedIndex = 0
            }
        }

        refreshCombo(defaultModelCombo, defaultModelCombo.selectedItem as? String)
        refreshCombo(codingModelCombo, codingModelCombo.selectedItem as? String)
        refreshCombo(reasoningModelCombo, reasoningModelCombo.selectedItem as? String)
    }

    private fun extractModelFromDisplay(display: String): String = display.substringBeforeLast(" (").trim()
    private fun findDisplayForModel(model: String): String? {
        for (i in 0 until defaultModelCombo.itemCount) {
            val item = defaultModelCombo.getItemAt(i)
            if (extractModelFromDisplay(item) == model) return item
        }
        return null
    }

    /* ---------- 连接测试 ---------- */

    private fun testCurrentProvider() {
        saveCurrentEditToData()
        val index = providerList.selectedIndex
        if (index < 0) {
            Messages.showWarningDialog(this, "请先选择一个提供商", "测试连接")
            return
        }
        val data = listModel.getElementAt(index)
        val provider = data.provider
        val apiKey = data.apiKey
        if (apiKey.isBlank()) {
            Messages.showWarningDialog(this, "请先填写 API Key", "测试连接")
            return
        }
        val selectedDisplay = defaultModelCombo.selectedItem as? String ?: ""
        val selectedModel = extractModelFromDisplay(selectedDisplay)
        val testModel =
            if (selectedModel.isNotBlank() && selectedModel in provider.models) selectedModel else provider.models.firstOrNull()
                ?: ""
        if (testModel.isBlank()) {
            Messages.showWarningDialog(this, "未配置模型，无法测试", "测试连接")
            return
        }
        testButton.isEnabled = false
        testButton.text = "测试中..."
        testSpinner.isVisible = true
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val result = testProviderConnection(provider, apiKey, testModel)
            SwingUtilities.invokeLater {
                testSpinner.isVisible = false
                testButton.isEnabled = true
                testButton.text = "测试连接"
                Messages.showInfoMessage(this@ProviderSettingsPanel, result, "连接测试结果")
            }
        }
    }

    private suspend fun testProviderConnection(provider: ProviderConfig, apiKey: String, testModel: String): String {
        return try {
            val registry = ModelRegistry.getInstance()
            val adapter = when (provider.providerType) {
                ProviderTypes.MINIMAX -> MiniMaxAdapter(apiKey, provider.baseUrl, provider.models)
                else -> createCustomAdapter(provider.name, apiKey, provider.baseUrl, provider.models)
            }
            registry.register(adapter)
            val gateway = ModelGateway.getInstance()
            val response = gateway.chat(
                com.codesage.model.dto.ChatRequest(
                    model = testModel,
                    messages = listOf(com.codesage.model.dto.Message.userMessage("Hi")),
                    maxTokens = 5
                )
            )
            if (response.isSuccess) "✅ ${provider.name} 连接成功\n模型: $testModel"
            else "❌ ${provider.name} 连接失败:\n${response.exceptionOrNull()?.message}"
        } catch (e: Exception) {
            "❌ ${provider.name} 连接失败:\n${e.message}"
        }
    }

    private fun fetchModelsForCurrentProvider() {
        saveCurrentEditToData()
        val index = providerList.selectedIndex
        if (index < 0) {
            Messages.showWarningDialog(null, "请先选择一个提供商", "获取模型列表")
            return
        }
        val data = listModel.getElementAt(index)
        val provider = data.provider
        val apiKey = data.apiKey
        if (apiKey.isBlank()) {
            Messages.showWarningDialog(null, "请先填写 API Key", "获取模型列表")
            return
        }
        if (provider.baseUrl.isBlank()) {
            Messages.showWarningDialog(null, "请先填写 Base URL", "获取模型列表")
            return
        }
        fetchModelsButton.isEnabled = false
        fetchModelsButton.text = "获取中..."
        fetchSpinner.isVisible = true
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val result = tryFetchModels(provider, apiKey)
            SwingUtilities.invokeLater {
                fetchSpinner.isVisible = false
                fetchModelsButton.isEnabled = true
                fetchModelsButton.text = "获取模型列表"
                if (result.isNotEmpty()) {
                    data.provider.models = result.toMutableList()
                    modelsField.text = result.joinToString(", ")
                    refreshModelCombos()
                    Messages.showInfoMessage(
                        this@ProviderSettingsPanel as java.awt.Component,
                        "成功获取 ${result.size} 个模型:\n${
                            result.take(10).joinToString(", ")
                        }${if (result.size > 10) " ..." else ""}",
                        "获取模型列表成功"
                    )
                } else {
                    Messages.showWarningDialog(
                        null,
                        "未能从该提供商获取模型列表。\n可能原因：\n1. 该提供商不支持 /v1/models 接口\n2. API Key 无效\n3. Base URL 错误",
                        "获取模型列表失败"
                    )
                }
            }
        }
    }

    private suspend fun tryFetchModels(provider: ProviderConfig, apiKey: String): List<String> {
        return try {
            val adapter = when (provider.providerType) {
                ProviderTypes.MINIMAX -> MiniMaxAdapter(apiKey, provider.baseUrl, provider.models)
                else -> createCustomAdapter(provider.name, apiKey, provider.baseUrl, provider.models)
            }
            adapter.fetchModels()
        } catch (e: Exception) {
            logger.warn("Failed to fetch models for ${provider.name}", e)
            emptyList()
        }
    }

    private fun createCustomAdapter(
        name: String,
        apiKey: String,
        baseUrl: String,
        models: List<String>
    ): OpenAICompatibleAdapter {
        return object : OpenAICompatibleAdapter(apiKey, baseUrl) {
            override val providerName: String = name.lowercase().replace(" ", "_")
            override val supportedModels: List<String> = models
            override val chatEndpointPath: String = "/v1/chat/completions"
        }
    }

    /* ---------- Configurable 接口 ---------- */

    fun isModified(): Boolean {
        saveCurrentEditToData()
        val config = PluginConfig.getInstance()
        if (tempDefaultModel != config.defaultModel) return true
        if (tempCodingModel != config.codingModel) return true
        if (tempReasoningModel != config.reasoningModel) return true
        if (tempDefaultProviderId != config.defaultProviderId) return true
        if (tempEnableStreaming != config.enableStreaming) return true
        val validEditData = providerData.filter { it.provider.isValid() }
        val currentProviders = config.providers
        if (validEditData.size != currentProviders.size) return true
        return validEditData.zip(currentProviders).any { (edit, current) ->
            edit.provider.id != current.id ||
                    edit.provider.name != current.name ||
                    edit.provider.providerType != current.providerType ||
                    edit.provider.baseUrl != current.baseUrl ||
                    edit.provider.models != current.models ||
                    edit.provider.isEnabled != current.isEnabled ||
                    edit.apiKey != (config.getProviderApiKey(current.id) ?: "")
        }
    }

    fun apply() {
        saveCurrentEditToData()
        val config = PluginConfig.getInstance()
        logger.info("apply called, panelId=$panelId")

        val validProviderData = providerData.filter { it.provider.isValid() }
        val existingIds = config.providers.map { it.id }.toSet()
        val newIds = validProviderData.map { it.provider.id }.toSet()

        // 删除已移除的 provider
        existingIds.filter { it !in newIds }.forEach { config.removeProvider(it) }

        // 写入配置
        validProviderData.forEach { data ->
            val copy = data.provider.copy()
            if (data.provider.id in existingIds) config.updateProvider(copy)
            else config.addProvider(copy)
            config.setProviderApiKey(data.provider.id, data.apiKey.takeIf { it.isNotBlank() })
        }

        // 默认模型
        val selectedDisplay = defaultModelCombo.selectedItem as? String ?: ""
        tempDefaultModel = extractModelFromDisplay(selectedDisplay)
        tempDefaultProviderId =
            validProviderData.find { it.provider.models.contains(tempDefaultModel) }?.provider?.id ?: ""
        config.defaultModel = tempDefaultModel
        config.defaultProviderId = tempDefaultProviderId

        // 专用模式模型
        val codingDisplay = codingModelCombo.selectedItem as? String ?: ""
        tempCodingModel = extractModelFromDisplay(codingDisplay)
        config.codingModel = tempCodingModel

        val reasoningDisplay = reasoningModelCombo.selectedItem as? String ?: ""
        tempReasoningModel = extractModelFromDisplay(reasoningDisplay)
        config.reasoningModel = tempReasoningModel

        config.enableStreaming = streamingCheckBox.isSelected

        // 同步临时变量
        tempEnableStreaming = config.enableStreaming

        // 重新注册模型适配器
        refreshModelRegistry(config)

        // 广播配置变更事件
        broadcastSettingsChanged()
    }

    private fun refreshModelRegistry(config: PluginConfig) {
        val registry = ModelRegistry.getInstance()
        config.enabledProviders.forEach { provider ->
            val apiKey = config.getProviderApiKey(provider.id) ?: return@forEach
            when (provider.providerType) {
                ProviderTypes.MINIMAX -> registry.createMiniMaxAdapter(apiKey, provider.baseUrl, provider.models)
                else -> {
                    val adapter = createCustomAdapter(provider.name, apiKey, provider.baseUrl, provider.models)
                    registry.register(adapter)
                }
            }
        }
    }

    private fun broadcastSettingsChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SettingsChangeListener.TOPIC)
            .onSettingsApplied()
        // Also broadcast default model change
        if (tempDefaultModel.isNotBlank()) {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(SettingsChangeListener.TOPIC)
                .onDefaultModelChanged(tempDefaultModel, tempDefaultProviderId)
        }
    }

    fun reset() {
        val config = PluginConfig.getInstance()
        logger.info("reset called, panelId=$panelId")
        providerData = config.providers.filter { it.isValid() }.map {
            ProviderEditData(provider = it.copy(), apiKey = config.getProviderApiKey(it.id) ?: "")
        }.toMutableList()
        tempDefaultProviderId = config.defaultProviderId
        tempDefaultModel = config.defaultModel
        tempCodingModel = config.codingModel
        tempReasoningModel = config.reasoningModel
        tempEnableStreaming = config.enableStreaming
        listModel.removeAll()
        listModel.add(providerData)
        streamingCheckBox.isSelected = tempEnableStreaming
        refreshModelCombos()
        val display = findDisplayForModel(tempDefaultModel)
        if (display != null) defaultModelCombo.selectedItem = display
        findDisplayForModel(tempCodingModel)?.let { codingModelCombo.selectedItem = it }
        findDisplayForModel(tempReasoningModel)?.let { reasoningModelCombo.selectedItem = it }
        val listeners = providerList.listSelectionListeners
        listeners.forEach { providerList.removeListSelectionListener(it) }
        if (listModel.size > 0) {
            providerList.selectedIndex = 0
            editingProviderIndex = 0
        } else {
            (detailCards.layout as CardLayout).show(detailCards, "empty")
            editingProviderIndex = -1
        }
        listeners.forEach { providerList.addListSelectionListener(it) }
        if (listModel.size > 0) {
            val firstData = listModel.getElementAt(0)
            loadDataToEdit(firstData)
            (detailCards.layout as CardLayout).show(detailCards, "edit")
        }
    }
}

/* ==================== 列表渲染器 ==================== */

private class ProviderListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val data = value as? ProviderEditData
        if (data != null) {
            text = (if (data.provider.isEnabled) "\u25CF " else "\u25CB ") + data.provider.name
            isEnabled = data.provider.isEnabled
        }
        return component
    }
}

/* ==================== 数据包装 ==================== */

private data class ProviderEditData(
    val provider: ProviderConfig,
    var apiKey: String
)
