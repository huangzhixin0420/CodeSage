# CodeSage Inline Chat / Editor 深度集成 — 详细技术设计

> **设计目标**：对标 Cursor Inline Edit 体验，在 IntelliJ 编辑器内直接嵌入 AI 对话与 Diff 审核能力  
> **设计原则**：不做 MVP，直接实现最终最佳体验  
> **版本**：v1.0  
> **日期**：2026-05-31

---

## 目录

1. [设计总览](#一设计总览)
2. [核心架构](#二核心架构)
3. [组件详细设计](#三组件详细设计)
4. [UI/UX 交互设计](#四uiux-交互设计)
5. [数据流与状态机](#五数据流与状态机)
6. [Prompt 工程](#六prompt-工程)
7. [安全与异常处理](#七安全与异常处理)
8. [性能设计](#八性能设计)
9. [测试策略](#九测试策略)
10. [执行计划](#十执行计划)

---

## 一、设计总览

### 1.1 最终效果定义

用户在编辑器中选中一段代码（或让光标停留在某行），按下 `Alt+Enter` 或输入快捷键，编辑器内直接弹出一个**浮动交互面板**：

```
┌─────────────────────────────────────────────┐
│  fun processData(items: List<Item>): Result {│  ← 原始代码（正常显示）
│      val filtered = items.filter { it.active }│
│      val mapped = filtered.map { it.toDto() } │
│      return Result(mapped)                    │
│  }                                            │
│                                               │
│  ┌─────────────────────────────────────────┐  │  ← Inline Chat 面板（Inlay）
│  │ Explain  Refactor  Fix  Test  │ GPT-4o ▼│  │
│  ├─────────────────────────────────────────┤  │
│  │ 提取公共逻辑到独立方法                    │  │  ← 用户输入
│  ├─────────────────────────────────────────┤  │
│  │ ▓▓▓ 思考中...                           │  │  ← Thinking 指示器
│  ├─────────────────────────────────────────┤  │
│  │ - val filtered = items.filter { it.active }│  │  ← Diff: 删除行（红色背景）
│  │ + val filtered = filterActive(items)    │  │  ← Diff: 新增行（绿色背景）
│  │ - val mapped = filtered.map { it.toDto() }│  │
│  │ + val mapped = mapToDto(filtered)       │  │
│  │   return Result(mapped)                 │  │  ← 上下文行（正常）
│  ├─────────────────────────────────────────┤  │
│  │ [✓ 接受]  [✗ 拒绝]  [↻ 重新生成]         │  │  ← 操作按钮
│  └─────────────────────────────────────────┘  │
│                                               │
│  fun filterActive(items: List<Item>) = ...    │  ← 新增方法（绿色背景）
│  fun mapToDto(items: List<Item>) = ...        │  ← 新增方法（绿色背景）
└─────────────────────────────────────────────┘
```

**关键体验**：
- **零上下文切换**：用户眼睛始终不离开编辑器
- **即时 Diff 预览**：AI 建议以行级 Diff 直接渲染在代码中
- **一键接受/拒绝**：Gutter 图标或悬浮按钮操作
- **Undo 原生支持**：所有修改都是 `WriteCommandAction`，Ctrl+Z 直接撤销
- **流式渲染**：AI 生成过程中 Diff 逐步出现，无需等待完整响应

### 1.2 与竞品的差异化

| 维度 | Cursor | Copilot (JetBrains) | **CodeSage** |
|------|--------|---------------------|-------------|
| 集成深度 | Fork 级（改内核） | 插件级（受限） | **插件级 + PSI 深度** |
| Diff 展示 | Inline diff（最佳） | Ghost text / Sidebar | **Inline diff（红绿背景）** |
| 代码分析 | 文本级 | 文本级 | **AST/PSI 级** |
| MCP 工具 | ❌ | ❌ | **✅ Inline Chat 可调 MCP** |
| 安全控制 | 基础 | 基础 | **ToolGuardrails 分级** |
| 多 Agent | ❌ | ❌ | **SubAgent 自动委派** |

---

## 二、核心架构

### 2.1 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ InlineChat   │  │ EditorInline │  │ GutterAction │      │
│  │ InputPanel   │  │ DiffRenderer │  │ Renderer     │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                    Session Layer                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ InlineChatSession（单次 Inline Chat 生命周期管理）    │   │
│  │ - 状态机: IDLE → REQUESTING → STREAMING → REVIEWING │   │
│  │ - Diff 累积与增量渲染                               │   │
│  │ - 用户操作路由（Accept/Reject/Retry）               │   │
│  └─────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                    Controller Layer                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ InlineChat   │  │ EditorAction │  │ CodeLens     │      │
│  │ Controller   │  │ Dispatcher   │  │ Provider     │      │
│  │ (单例)       │  │              │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                    Agent Layer（复用）                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │ AgentCore.   │  │ InlineChat   │  │ ToolGuardrails│     │
│  │ chatWithTools│  │ PromptAssembler│ │ (限制工具集)  │     │
│  │ (独立session)│  │ (专用prompt) │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────────────┤
│                    Platform Layer（IntelliJ）                 │
│  InlayModel / MarkupModel / WriteCommandAction / Editor API  │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Diff 渲染方式 | **RangeHighlighter + 自定义背景色** | 原生支持，性能最好，与编辑器主题融合 |
| Inline Chat 面板位置 | **Inlay（行间嵌入）** | 不遮挡代码，随滚动移动，定位精准 |
| 输入框实现 | **JBTextArea + 自定义 border** | 支持多行、JetBrains 风格、主题自适应 |
| 操作按钮位置 | **Gutter 图标 + 悬浮工具栏** | 不占用编辑器宽度，Cursor 同款体验 |
| 代码应用方式 | **WriteCommandAction + groupId** | 原生 Undo/Redo 支持，批量操作 |
| AI 会话隔离 | **独立的 AgentSession** | Inline Chat 不影响侧边栏主会话历史 |
| 工具权限 | **白名单：read_file / edit_file / search_code** | 禁止执行命令、删除文件等危险操作 |

---

## 三、组件详细设计

### 3.1 InlineChatController（控制器单例）

**职责**：管理所有 Inline Chat 会话的全局生命周期，防止多个 Inline Chat 同时活跃。

```kotlin
package com.codesage.ide.inline

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class InlineChatController(private val project: Project) {

    /** 当前活跃的 Inline Chat 会话（每个 Editor 最多一个） */
    private val activeSessions = ConcurrentHashMap<Editor, InlineChatSession>()

    /**
     * 启动一个新的 Inline Chat 会话
     * 如果该 Editor 已有活跃会话，先关闭旧的
     */
    fun startSession(
        editor: Editor,
        selectedText: String?,
        startLine: Int,
        endLine: Int,
        initialMode: InlineChatMode = InlineChatMode.CHAT
    ): InlineChatSession

    /** 获取指定 Editor 的活跃会话 */
    fun getActiveSession(editor: Editor): InlineChatSession?

    /** 关闭指定 Editor 的 Inline Chat */
    fun closeSession(editor: Editor)

    /** 关闭所有 Inline Chat（项目关闭时调用） */
    fun closeAllSessions()

    companion object {
        fun getInstance(project: Project): InlineChatController =
            project.getService(InlineChatController::class.java)
    }
}

enum class InlineChatMode {
    CHAT,       // 自由输入
    EXPLAIN,    // 解释代码（预填 prompt）
    REFACTOR,   // 重构代码（预填 prompt）
    FIX,        // 修复错误（自动带入诊断信息）
    TEST        // 生成测试（预填 prompt）
}
```

**状态管理**：
- `activeSessions` 使用 `ConcurrentHashMap` 保证线程安全
- 每个 `Editor` 实例最多一个活跃 `InlineChatSession`
- 当用户点击编辑器其他位置或按 `Escape` 时，关闭当前 Inline Chat（但保留 Diff 高亮直到用户操作）

---

### 3.2 InlineChatSession（会话核心）

**职责**：管理单次 Inline Chat 的完整生命周期，从用户输入到 Diff 应用到关闭。

```kotlin
package com.codesage.ide.inline

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentStreamEvent
import com.intellij.openapi.editor.Editor
import kotlinx.coroutines.flow.Flow

/**
 * Inline Chat 会话状态机
 */
class InlineChatSession(
    val editor: Editor,
    val project: Project,
    private val controller: InlineChatController,
    val context: InlineChatContext
) {
    private val agentCore: AgentCore = CodeSageProjectService.getInstance(project).agentCore

    /** 当前状态 */
    val state: StateFlow<State> = MutableStateFlow(State.IDLE)

    /** Diff 累积器 */
    private val diffAccumulator: DiffAccumulator = DiffAccumulator()

    /** UI 组件引用 */
    private var inputInlay: Inlay<*>? = null
    private var diffHighlighters: MutableList<RangeHighlighter> = mutableListOf()
    private var gutterComponents: MutableList<GutterIconRenderer> = mutableListOf()

    /**
     * 用户发送消息，启动 AI 对话
     */
    fun sendRequest(userMessage: String)

    /**
     * 流式事件处理（从 AgentCore.chatWithTools 接收）
     */
    fun onStreamEvent(event: AgentStreamEvent)

    /**
     * 用户接受所有 Diff
     */
    fun acceptAllChanges()

    /**
     * 用户拒绝所有 Diff，恢复原状
     */
    fun rejectAllChanges()

    /**
     * 重新生成（保留上下文，重新发送请求）
     */
    fun retryRequest()

    /**
     * 关闭会话，清理所有 UI 状态
     */
    fun dispose()

    // ========== 状态机 ==========
    sealed class State {
        object IDLE : State()           // 等待用户输入
        object REQUESTING : State()     // 已发送请求，等待首字节
        object STREAMING : State()      // AI 正在生成，Diff 逐步渲染
        object REVIEWING : State()      // 生成完成，等待用户审核
        object APPLYING : State()       // 正在应用修改
        object ERROR : State()          // 发生错误
    }
}
```

**状态机流转图**：

```
                    ┌─────────┐
         start      │  IDLE   │ ◄────────────────────────┐
     ┌─────────────►│(等待输入)│                          │
     │              └────┬────┘                          │
     │                   │ sendRequest()                  │
     │                   ▼                                │
     │              ┌─────────┐    首字节到达              │
     │              │REQUESTING│ ─────────────────────►   │
     │              │(请求中)  │                          │
     │              └────┬────┘                          │
     │                   │                                │
     │                   │ onStreamEvent(TextDelta)       │
     │                   ▼                                │
     │              ┌─────────┐    生成完成               │
     │              │STREAMING │ ─────────────────────►   │
     │              │(生成中)  │    onStreamEvent(Done)   │
     │              └────┬────┘                          │
     │                   │                                │
     │                   │                                │
     │                   ▼                                │
     │              ┌─────────┐    acceptAllChanges()    │
     └──────────────│REVIEWING │ ─────────────────────►   │
     retryRequest() │(审核中)  │    rejectAllChanges()    │
                    └────┬────┘                          │
                         │                               │
                         │ dispose()                     │
                         ▼                               │
                    ┌─────────┐                          │
                    │  CLOSED │ ─────────────────────────┘
                    └─────────┘
```

---

### 3.3 InlineChatInputPanel（输入面板）

**职责**：渲染在编辑器内的浮动输入面板，包含快捷操作、输入框、发送按钮。

**实现方式**：使用 IntelliJ `Inlay` 机制，在选区结束行下方插入自定义渲染器。

```kotlin
package com.codesage.ide.inline.ui

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Inline Chat 输入面板渲染器
 * 在编辑器行间渲染一个浮动面板
 */
class InlineChatInputRenderer(
    private val session: InlineChatSession,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit
) : InlineEditorRenderer {

    private val panel: InlineChatInputPanel = InlineChatInputPanel(onSend, onClose)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        panel.bounds = targetRegion
        panel.paint(g)
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.contentComponent.width - 40 // 左右各留 20px 边距
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return panel.preferredSize.height
    }
}

/**
 * 实际的 Swing 面板
 */
class InlineChatInputPanel(
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit
) : JPanel(BorderLayout()) {

    // 快捷操作栏
    private val quickActions = listOf("Explain", "Refactor", "Fix", "Test")

    // 模型选择下拉框
    private val modelSelector: ComboBox<String>

    // 多行输入框
    private val inputArea: JBTextArea

    // 发送按钮
    private val sendButton: JButton

    init {
        // JetBrains 风格样式
        background = JBColor(Color(0xF5_F5_F5), Color(0x2D_2D_2D))
        border = JBUI.Borders.empty(8)

        // 圆角 + 阴影边框
        border = BorderFactory.createCompoundBorder(
            RoundedBorder(8, JBColor(Color(0xCC_CC_CC), Color(0x55_55_55))),
            JBUI.Borders.empty(8)
        )
    }
}
```

**关键实现细节**：

1. **Inlay 插入位置**：
   ```kotlin
   val inlayModel = editor.inlayModel
   val offset = editor.document.getLineEndOffset(selectionEndLine)
   val properties = InlayProperties().apply {
       isRelatedToPrecedingText = false
   }
   val inlay = inlayModel.addBlockElement(offset, properties, renderer)
   ```

2. **面板宽度**：占满编辑器宽度（减去左右边距），避免遮挡代码

3. **快捷操作点击**：点击 "Explain" 等按钮时，自动填充预设 prompt 并发送

4. **模型选择**：下拉框展示当前配置的所有可用模型，可实时切换

---

### 3.4 EditorInlineDiffRenderer（行内 Diff 渲染器）

**职责**：将 AI 生成的代码修改以 Diff 形式直接渲染在编辑器中。

**这是整个系统最核心的体验组件。**

#### 3.4.1 渲染策略

使用 **RangeHighlighter + 自定义背景色 + Gutter 图标**，而非独立的 Diff 窗口。

```kotlin
package com.codesage.ide.inline.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * 编辑器行内 Diff 渲染器
 * 直接在编辑器中使用背景色高亮变更行
 */
class EditorInlineDiffRenderer(private val editor: Editor) {

    private val markupModel = editor.markupModel
    private val highlighters = mutableListOf<RangeHighlighter>()

    /**
     * 渲染一组 Diff 行
     */
    fun renderDiff(diffLines: List<DiffLine>) {
        clearHighlighters()

        for (line in diffLines) {
            when (line.type) {
                DiffType.REMOVED -> highlightRemoved(line.lineNumber, line.content)
                DiffType.ADDED -> highlightAdded(line.lineNumber, line.content)
                DiffType.MODIFIED -> highlightModified(line.lineNumber, line.oldContent, line.newContent)
            }
        }

        // 在 Gutter 添加 Accept/Reject 图标
        addGutterActions()
    }

    /**
     * 删除行：红色背景 + 删除线
     */
    private fun highlightRemoved(lineNumber: Int, content: String) {
        val startOffset = editor.document.getLineStartOffset(lineNumber)
        val endOffset = editor.document.getLineEndOffset(lineNumber)

        val attributes = TextAttributes().apply {
            backgroundColor = REMOVED_BG
            effectType = EffectType.STRIKEOUT
            effectColor = REMOVED_FG
            fontType = Font.ITALIC
        }

        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.LAST,  // 确保在最上层
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE
        )

        // 在左侧 gutter 添加 "-" 标记
        highlighter.gutterIconRenderer = RemovedLineGutterIcon()

        highlighters.add(highlighter)
    }

    /**
     * 新增行：绿色背景
     */
    private fun highlightAdded(lineNumber: Int, content: String) {
        val startOffset = editor.document.getLineStartOffset(lineNumber)
        val endOffset = editor.document.getLineEndOffset(lineNumber)

        val attributes = TextAttributes().apply {
            backgroundColor = ADDED_BG
            foregroundColor = ADDED_FG
        }

        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.LAST,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE
        )

        // 在左侧 gutter 添加 "+" 标记
        highlighter.gutterIconRenderer = AddedLineGutterIcon()

        highlighters.add(highlighter)
    }

    /**
     * 修改行：黄色背景（行内字符级 diff）
     */
    private fun highlightModified(lineNumber: Int, oldContent: String, newContent: String) {
        // 先高亮整行（黄色背景表示修改）
        val startOffset = editor.document.getLineStartOffset(lineNumber)
        val endOffset = editor.document.getLineEndOffset(lineNumber)

        val lineAttributes = TextAttributes().apply {
            backgroundColor = MODIFIED_BG
        }

        val lineHighlighter = markupModel.addRangeHighlighter(
            startOffset, endOffset, HighlighterLayer.LAST,
            lineAttributes, HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighters.add(lineHighlighter)

        // 字符级 diff：精确标记变更的字符范围
        val charDiffs = computeCharDiff(oldContent, newContent)
        for (diff in charDiffs) {
            val charAttributes = TextAttributes().apply {
                backgroundColor = if (diff.isDeletion) REMOVED_BG else ADDED_BG
            }
            val charHighlighter = markupModel.addRangeHighlighter(
                startOffset + diff.start,
                startOffset + diff.end,
                HighlighterLayer.LAST + 1, // 比行级更高
                charAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighters.add(charHighlighter)
        }
    }

    /**
     * 在变更区域附近的 Gutter 添加操作图标
     */
    private fun addGutterActions() {
        // 在变更块的第一行 gutter 添加 "Accept/Reject" 图标组
        // 使用 CustomGutterIconRenderer
    }

    /**
     * 清除所有高亮
     */
    fun clearHighlighters() {
        for (highlighter in highlighters) {
            markupModel.removeHighlighter(highlighter)
        }
        highlighters.clear()
    }

    companion object {
        // 亮色主题
        val REMOVED_BG_LIGHT = Color(0xFF_EB_EE)
        val ADDED_BG_LIGHT = Color(0xE8_F5_E9)
        val MODIFIED_BG_LIGHT = Color(0xFF_F8_E1)
        val REMOVED_FG_LIGHT = Color(0xD3_2F_2F)
        val ADDED_FG_LIGHT = Color(0x2E_7D_32)

        // 暗色主题
        val REMOVED_BG_DARK = Color(0x4A_1E_1E)
        val ADDED_BG_DARK = Color(0x1B_3A_1E)
        val MODIFIED_BG_DARK = Color(0x3D_30_1B)
        val REMOVED_FG_DARK = Color(0xFF_8A_80)
        val ADDED_FG_DARK = Color(0x66_BB_6A)

        val REMOVED_BG = JBColor(REMOVED_BG_LIGHT, REMOVED_BG_DARK)
        val ADDED_BG = JBColor(ADDED_BG_LIGHT, ADDED_BG_DARK)
        val MODIFIED_BG = JBColor(MODIFIED_BG_LIGHT, MODIFIED_BG_DARK)
        val REMOVED_FG = JBColor(REMOVED_FG_LIGHT, REMOVED_FG_DARK)
        val ADDED_FG = JBColor(ADDED_FG_LIGHT, ADDED_FG_DARK)
    }
}
```

#### 3.4.2 Gutter 操作图标

在变更行的左侧 gutter 区域添加 Accept/Reject 图标：

```kotlin
package com.codesage.ide.inline.diff

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.ui.JBColor
import javax.swing.Icon

/**
 * Accept/Reject Gutter 图标渲染器
 * 渲染在变更块第一行的 gutter 中
 */
class InlineDiffGutterRenderer(
    private val session: InlineChatSession,
    private val changeBlock: ChangeBlock
) : GutterIconRenderer() {

    override fun getIcon(): Icon = CodeSageIcons.INLINE_DIFF_ACTION

    override fun getTooltipText(): String? = "CodeSage: ${changeBlock.description}"

    override fun getClickAction(): AnAction? = object : AnAction("查看操作") {
        override fun actionPerformed(e: AnActionEvent) {
            // 弹出悬浮工具栏：Accept / Reject / 逐行查看
            showFloatingToolbar(e, changeBlock)
        }
    }

    override fun equals(other: Any?): Boolean = other is InlineDiffGutterRenderer
            && other.changeBlock.id == changeBlock.id

    override fun hashCode(): Int = changeBlock.id.hashCode()

    override fun isNavigateAction(): Boolean = false
}
```

**悬浮工具栏**（鼠标悬停或点击 gutter 图标时弹出）：

```
┌─────────────────────┐
│ ✓ 接受   ✗ 拒绝     │
│ ⤓ 接受此行          │
│ ↻ 重新生成          │
└─────────────────────┘
```

#### 3.4.3 字符级 Diff 算法

对修改行进行更精细的字符级比较，精确标记变更字符：

```kotlin
package com.codesage.ide.inline.diff

/**
 * 字符级 Diff
 */
fun computeCharDiff(oldText: String, newText: String): List<CharDiff> {
    // 使用 Myers Diff 算法或简单的 LCS
    // 返回变更的字符范围列表
}

data class CharDiff(
    val start: Int,
    val end: Int,
    val isDeletion: Boolean,  // true=删除, false=新增
    val replacement: String?  // 新增时的替换文本
)
```

---

### 3.5 InlineChatCodeModifier（代码修改器）

**职责**：将 Diff 安全地应用到编辑器文档中，支持 Undo/Redo。

```kotlin
package com.codesage.ide.inline.modification

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Inline Chat 代码修改器
 * 所有修改通过 WriteCommandAction 执行，确保 Undo/Redo 原生支持
 */
class InlineChatCodeModifier(
    private val project: Project,
    private val editor: Editor
) {
    private val document: Document = editor.document

    /**
     * 应用一组 Diff 修改
     * 使用单个 WriteCommandAction，用户按一次 Ctrl+Z 即可撤销全部
     */
    fun applyDiff(diffResult: DiffResult) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Apply CodeSage Inline Chat Changes")
            .withGroupId("CodeSage.InlineChat")
            .run<Throwable> {
                // 从后往前应用修改，避免行号偏移问题
                val sortedChanges = diffResult.changes.sortedByDescending { it.startLine }

                for (change in sortedChanges) {
                    when (change.type) {
                        ChangeType.REPLACE -> replaceLines(change.startLine, change.endLine, change.newContent)
                        ChangeType.INSERT -> insertLines(change.startLine, change.newContent)
                        ChangeType.DELETE -> deleteLines(change.startLine, change.endLine)
                    }
                }
            }
    }

    /**
     * 替换指定行范围的内容
     */
    private fun replaceLines(startLine: Int, endLine: Int, newContent: String) {
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        document.replaceString(startOffset, endOffset, newContent)
    }

    /**
     * 在指定行后插入内容
     */
    private fun insertLines(afterLine: Int, content: String) {
        val offset = document.getLineEndOffset(afterLine)
        document.insertString(offset, "\n$content")
    }

    /**
     * 删除指定行范围
     */
    private fun deleteLines(startLine: Int, endLine: Int) {
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = if (endLine + 1 < document.lineCount) {
            document.getLineStartOffset(endLine + 1)
        } else {
            document.textLength
        }
        document.deleteString(startOffset, endOffset)
    }
}
```

**Undo 策略**：
- 整个 Inline Chat 的修改作为一个 `WriteCommandAction` 事务
- 用户按 **一次 Ctrl+Z** 即可撤销所有修改
- 支持 **Ctrl+Shift+Z** 重做（如果用户误撤销）
- 在 `Local History` 中也可看到变更记录

---

### 3.6 DiffAccumulator（Diff 累积器）

**职责**：在 AI 流式生成过程中，实时解析文本中的代码块，计算 Diff，驱动增量渲染。

```kotlin
package com.codesage.ide.inline.diff

/**
 * Diff 累积器
 * 接收 AI 的流式文本输出，逐步构建 Diff 结果
 */
class DiffAccumulator(private val originalCode: String) {

    private val buffer = StringBuilder()
    private var parsedBlocks = mutableListOf<CodeBlock>()
    private var currentState = ParseState.TEXT

    /**
     * 追加新的文本片段（流式输出）
     */
    fun append(text: String): DiffUpdate? {
        buffer.append(text)

        // 尝试解析代码块
        val newBlocks = tryParseCodeBlocks(buffer.toString())
        if (newBlocks.size > parsedBlocks.size) {
            // 检测到新代码块
            val newBlock = newBlocks.last()
            parsedBlocks = newBlocks

            // 计算 Diff
            val diffResult = computeDiff(originalCode, newBlock.content)
            return DiffUpdate(
                isComplete = false,
                diffResult = diffResult,
                newBlock = newBlock
            )
        }

        return null
    }

    /**
     * 标记流式输出完成
     */
    fun finalize(): DiffResult {
        val finalBlocks = tryParseCodeBlocks(buffer.toString())
        val codeContent = if (finalBlocks.isNotEmpty()) {
            finalBlocks.last().content
        } else {
            buffer.toString() // 没有代码块标记，视为纯代码
        }
        return computeDiff(originalCode, codeContent)
    }

    private enum class ParseState {
        TEXT,           // 普通文本
        CODE_BLOCK,     // 在 ``` 代码块中
    }
}

/**
 * Diff 增量更新（用于驱动 UI 刷新）
 */
data class DiffUpdate(
    val isComplete: Boolean,
    val diffResult: DiffResult,
    val newBlock: CodeBlock? = null
)
```

**流式 Diff 渲染策略**：

1. AI 开始生成时，面板显示 "思考中..."
2. 当检测到第一个完整的代码块（```...```）时，立即计算 Diff 并开始渲染
3. 后续每次检测到代码块内容更新，重新计算 Diff 并增量刷新高亮
4. 使用 `diff-match-patch` 算法优化增量 Diff 计算，避免全量重算

---

### 3.7 InlineChatActionGroup（编辑器 Action）

**职责**：注册编辑器右键菜单和快捷键 Action。

```kotlin
package com.codesage.ide.inline.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Inline Chat 主 Action
 * 触发方式：
 * - 右键菜单: "CodeSage Inline Chat"
 * - 快捷键: Alt+Enter（IntelliJ 通用的 Quick Fix 快捷键）
 */
class OpenInlineChatAction : AnAction(
    "CodeSage Inline Chat",
    "在编辑器内打开 AI 对话",
    CodeSageIcons.INLINE_CHAT
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        val (startLine, endLine) = if (selectionModel.hasSelection()) {
            val start = editor.document.getLineNumber(selectionModel.selectionStart)
            val end = editor.document.getLineNumber(selectionModel.selectionEnd)
            start to end
        } else {
            val caretLine = editor.caretModel.logicalPosition.line
            caretLine to caretLine
        }

        val controller = InlineChatController.getInstance(project)
        controller.startSession(
            editor = editor,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            initialMode = InlineChatMode.CHAT
        )
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }
}

/**
 * Explain 代码 Action
 */
class ExplainCodeAction : AnAction("Explain Code", "解释选中代码", AllIcons.Actions.Preview) {
    override fun actionPerformed(e: AnActionEvent) {
        startInlineChat(e, InlineChatMode.EXPLAIN, "解释这段代码的功能、潜在问题和优化建议")
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }
}

/**
 * Refactor 代码 Action
 */
class RefactorCodeAction : AnAction("Refactor Selection", "重构选中代码", AllIcons.Actions.RefactoringBulb) {
    override fun actionPerformed(e: AnActionEvent) {
        startInlineChat(e, InlineChatMode.REFACTOR, "重构这段代码，提高可读性和性能")
    }
}

/**
 * Fix Error Action（Intention Action，灯泡提示）
 */
class FixErrorIntention : IntentionAction, PriorityAction {
    override fun getText(): String = "Fix with CodeSage"
    override fun getFamilyName(): String = "CodeSage"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        // 检查当前行是否有错误诊断
        val caretLine = editor.caretModel.logicalPosition.line
        return hasErrorOnLine(file, caretLine)
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val caretLine = editor.caretModel.logicalPosition.line
        val errorMessage = getErrorMessage(file, caretLine)

        val controller = InlineChatController.getInstance(project)
        controller.startSession(
            editor = editor,
            selectedText = null,
            startLine = caretLine,
            endLine = caretLine,
            initialMode = InlineChatMode.FIX
        ).apply {
            sendRequest("修复以下错误：$errorMessage")
        }
    }

    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = PriorityAction.Priority.TOP
}
```

**plugin.xml 注册**：

```xml
<!-- 编辑器右键菜单 -->
<action id="CodeSage.OpenInlineChat"
        class="com.codesage.ide.inline.actions.OpenInlineChatAction"
        text="CodeSage Inline Chat"
        description="在编辑器内打开 AI 对话">
    <add-to-group group-id="EditorPopupMenu" anchor="first"/>
    <keyboard-shortcut keymap="$default" first-keystroke="alt ENTER"/>
</action>

<action id="CodeSage.ExplainCode"
        class="com.codesage.ide.inline.actions.ExplainCodeAction"
        text="Explain with CodeSage">
    <add-to-group group-id="EditorPopupMenu" anchor="after" relative-to-action="CodeSage.OpenInlineChat"/>
</action>

<action id="CodeSage.RefactorCode"
        class="com.codesage.ide.inline.actions.RefactorCodeAction"
        text="Refactor with CodeSage">
    <add-to-group group-id="EditorPopupMenu" anchor="after" relative-to-action="CodeSage.ExplainCode"/>
</action>

<!-- Intention Action（灯泡提示） -->
<extensions defaultExtensionNs="com.intellij">
    <intentionAction>
        <className>com.codesage.ide.inline.actions.FixErrorIntention</className>
        <category>CodeSage</category>
    </intentionAction>
</extensions>
```

---

## 四、UI/UX 交互设计

### 4.1 交互流程图

```
用户选中代码 ──► 右键/Alt+Enter ──► Inline Chat 面板弹出
                                      │
                                      ▼
                          ┌───────────────────────┐
                          │ 快捷操作 / 自由输入    │
                          │ Explain/Refactor/Fix  │
                          └───────────┬───────────┘
                                      │ 点击发送
                                      ▼
                          ┌───────────────────────┐
                          │ Thinking 指示器        │
                          │ ▓▓▓ 分析代码中...     │
                          └───────────┬───────────┘
                                      │ 收到首字节
                                      ▼
                          ┌───────────────────────┐
                          │ 流式 Diff 渲染         │
                          │ 红色=删除 绿色=新增    │
                          │ 逐步出现，实时更新     │
                          └───────────┬───────────┘
                                      │ 生成完成
                                      ▼
                          ┌───────────────────────┐
                          │ 审核状态               │
                          │ [✓ 接受] [✗ 拒绝]     │
                          │ [↻ 重新生成]           │
                          └───────────┬───────────┘
                      ┌───────────────┼───────────────┐
                      ▼               ▼               ▼
                [接受修改]      [拒绝修改]      [重新生成]
                      │               │               │
                      ▼               ▼               ▼
                WriteCommand    清除高亮        保留上下文
                应用修改        恢复原状        重新请求
                      │               │
                      ▼               ▼
                清除所有 UI       关闭面板
                保留修改结果
```

### 4.2 视觉规范

#### 4.2.1 Inline Chat 面板

| 属性 | 亮色主题 | 暗色主题 |
|------|---------|---------|
| 背景色 | `#F5F5F5` | `#2D2D2D` |
| 边框色 | `#CCCCCC` | `#555555` |
| 圆角 | 8px | 8px |
| 内边距 | 8px | 8px |
| 阴影 | 0 2px 8px rgba(0,0,0,0.15) | 0 2px 8px rgba(0,0,0,0.3) |

#### 4.2.2 Diff 高亮色

| 类型 | 亮色背景 | 亮色前景 | 暗色背景 | 暗色前景 |
|------|---------|---------|---------|---------|
| 删除行 | `#FFEBEE` | `#D32F2F` | `#4A1E1E` | `#FF8A80` |
| 新增行 | `#E8F5E9` | `#2E7D32` | `#1B3A1E` | `#66BB6A` |
| 修改行 | `#FFF8E1` | `#F57C00` | `#3D301B` | `#FFB74D` |
| 删除字符 | `#FFCDD2` | - | `#5C2B2B` | - |
| 新增字符 | `#C8E6C9` | - | `#2D5A31` | - |

#### 4.2.3 Gutter 图标

- 删除行 gutter：红色 "−" 图标
- 新增行 gutter：绿色 "+" 图标
- 修改块 gutter：悬浮 "Accept / Reject" 工具栏图标

### 4.3 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Alt+Enter` | 打开 Inline Chat（选中代码时） |
| `Ctrl+Shift+S` | 打开 CodeSage 侧边栏（已有） |
| `Escape` | 关闭当前 Inline Chat 面板 |
| `Ctrl+Enter` | 发送消息（在输入框聚焦时） |
| `Ctrl+Z` | 撤销最近一次 Inline Chat 的修改 |

---

## 五、数据流与状态机

### 5.1 数据流

```
┌─────────────┐    sendRequest()    ┌─────────────────┐
│  InputPanel │ ──────────────────► │  InlineChatSession│
└─────────────┘                     └────────┬────────┘
                                             │
                                             │ chatWithTools()
                                             ▼
                                    ┌─────────────────┐
                                    │   AgentCore     │
                                    │ (独立session)   │
                                    └────────┬────────┘
                                             │ Flow<AgentStreamEvent>
                                             ▼
                                    ┌─────────────────┐
                                    │ DiffAccumulator │
                                    │  - 解析代码块   │
                                    │  - 计算 Diff    │
                                    └────────┬────────┘
                                             │ DiffResult
                                             ▼
                                    ┌─────────────────┐
                                    │EditorInlineDiff │
                                    │   Renderer      │
                                    │  - 高亮背景     │
                                    │  - Gutter图标   │
                                    └────────┬────────┘
                                             │ 用户点击
                                             ▼
                                    ┌─────────────────┐
                                    │InlineChatCode   │
                                    │   Modifier      │
                                    │  - WriteCommand │
                                    └─────────────────┘
```

### 5.2 上下文组装

Inline Chat 发送给 AI 的上下文与侧边栏 Chat 不同，需要精确控制：

```kotlin
fun buildInlineChatContext(
    editor: Editor,
    selectedText: String?,
    startLine: Int,
    endLine: Int,
    userMessage: String,
    mode: InlineChatMode
): InlineChatRequest {

    val document = editor.document
    val file = editor.virtualFile

    // 1. 选中代码（高优先级）
    val selectedCode = selectedText ?: document.getText(
        TextRange.create(
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine)
        )
    )

    // 2. 当前文件上下文（光标前 50 行 + 后 20 行）
    val fileContext = extractFileContext(document, startLine, beforeLines = 50, afterLines = 20)

    // 3. 诊断信息（当前行的错误/警告）
    val diagnostics = extractDiagnostics(editor, startLine, endLine)

    // 4. 语言/框架信息
    val language = file?.fileType?.name ?: "Unknown"

    // 5. 组装系统提示（Inline Chat 专用）
    val systemPrompt = buildInlineSystemPrompt(mode, language)

    // 6. 组装用户消息
    val fullMessage = buildUserMessage(mode, selectedCode, fileContext, diagnostics, userMessage)

    return InlineChatRequest(
        systemPrompt = systemPrompt,
        userMessage = fullMessage,
        selectedRange = TextRange(
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine)
        )
    )
}
```

**上下文限制**：
- 总 Token 数不超过 6K（比侧边栏 Chat 少，因为响应需要更快）
- 选中代码优先保留完整内容
- 文件上下文可截断，但保留类/方法签名

---

## 六、Prompt 工程

### 6.1 Inline Chat 专用系统提示

```kotlin
object InlineChatPrompts {

    val INLINE_SYSTEM_PROMPT = """
        你是 CodeSage，一个集成在 IntelliJ IDEA 中的 AI 编程助手。
        你正在通过 Inline Chat 模式与用户交互，这意味着：
        - 用户选中了编辑器中的一段代码，并请求你修改或解释
        - 你的回复将直接以 Diff 形式展示在编辑器中
        - 用户可以在代码行内直接接受或拒绝你的修改

        规则：
        1. **只输出修改后的完整代码块**，用 ```language 包裹
        2. 不要输出解释性文字（除非用户明确要求 Explain）
        3. 保持代码的缩进、格式和原有风格一致
        4. 如果用户要求 Refactor，只修改必要部分，不要重写整个文件
        5. 如果无法修改，输出 ```\n// 无法完成：原因\n```
        6. 对于 Fix 模式，修复后代码必须消除原始错误
        7. 对于 Test 模式，生成完整的测试方法，使用项目已有的测试框架
    """.trimIndent()

    fun buildExplainPrompt(selectedCode: String, language: String): String = """
        请解释以下 $language 代码：
        ```$language
        $selectedCode
        ```
        解释其功能、关键逻辑、潜在问题和优化建议。
    """.trimIndent()

    fun buildRefactorPrompt(selectedCode: String, language: String, userInstruction: String): String = """
        请重构以下 $language 代码：
        ```$language
        $selectedCode
        ```
        用户要求：$userInstruction
        只输出重构后的代码块，保持原有缩进风格。
    """.trimIndent()

    fun buildFixPrompt(selectedCode: String, language: String, errorMessage: String): String = """
        以下 $language 代码存在错误，请修复：
        ```$language
        $selectedCode
        ```
        错误信息：$errorMessage
        只输出修复后的代码块。
    """.trimIndent()

    fun buildTestPrompt(selectedCode: String, language: String, className: String): String = """
        为以下 $language 代码生成单元测试：
        ```$language
        $selectedCode
        ```
        类名：$className
        生成完整的测试方法，覆盖正常路径和边界条件。
        只输出测试代码块。
    """.trimIndent()
}
```

### 6.2 Prompt 与现有架构融合

- 复用 `PromptAssembler`，但注入 `INLINE_SYSTEM_PROMPT`
- 复用 `PromptRole` 枚举，新增 `INLINE_CHAT` 角色
- 不修改现有侧边栏 Chat 的 prompt 体系

---

## 七、安全与异常处理

### 7.1 工具权限白名单

Inline Chat 只允许使用以下工具：

| 工具 | 用途 | 限制 |
|------|------|------|
| `read_file` | 读取相关文件 | 只能读取项目内文件 |
| `search_code` | 搜索代码上下文 | - |
| `edit_file` | 修改当前文件 | 只能修改当前打开的文件 |
| `get_file_info` | 获取文件信息 | - |

**禁止使用的工具**：
- `run_command`（禁止执行命令）
- `delete_file`（禁止删除文件）
- `write_file`（禁止创建新文件，只能编辑现有文件）
- `delegate_task`（Inline Chat 内不启用子 Agent，避免复杂化）

### 7.2 Guardrails 集成

```kotlin
class InlineChatGuardrails(projectRoot: String?) : ToolGuardrails.ConfirmationCallback {

    override fun shouldConfirm(toolName: String, arguments: Map<String, String>): Boolean {
        // Inline Chat 内所有 edit_file 都需要确认
        if (toolName == "edit_file") return true
        return false
    }

    override fun onConfirmationRequested(
        toolName: String,
        arguments: Map<String, String>,
        reason: String
    ): Boolean {
        // 在 Inline Chat 面板内展示确认提示，而非弹窗
        // 返回 true = 允许执行
        return true
    }
}
```

### 7.3 异常处理

| 异常场景 | 处理方式 |
|---------|---------|
| AI 响应超时 | 显示 "请求超时，请重试"，保留输入内容 |
| AI 返回非代码内容 | 尝试提取代码块，失败则显示原始文本 |
| Diff 解析失败 | 显示 "无法解析修改建议"，提供 "查看原始回复" 选项 |
| 应用修改时文档已变更 | 检测文档版本，提示 "文件已修改，是否重新生成 Diff" |
| 用户关闭编辑器 | 自动拒绝所有未接受的 Diff，清理资源 |
| 项目关闭 | 关闭所有 Inline Chat 会话，保存未完成状态 |

---

## 八、性能设计

### 8.1 渲染性能

| 指标 | 目标 | 策略 |
|------|------|------|
| Inlay 插入延迟 | < 50ms | 预创建渲染器，异步计算尺寸 |
| Diff 高亮刷新 | < 16ms（60fps） | 增量更新，只刷新变更行 |
| 大文件（>1000行） | 流畅 | 只高亮可视区域，虚拟滚动 |
| 流式渲染 | 逐字符出现 | 200ms 批处理，避免过于频繁刷新 |

### 8.2 内存管理

- `InlineChatSession` 持有 `Editor` 引用，必须在 dispose 时释放
- `RangeHighlighter` 在会话关闭时必须全部清除
- 使用 `Disposable` 接口与 IDE 生命周期绑定

```kotlin
class InlineChatSession : Disposable {
    override fun dispose() {
        // 清理所有高亮
        diffRenderer.clearHighlighters()
        // 移除 Inlay
        inputInlay?.dispose()
        // 取消协程
        scope.cancel()
        // 释放引用
        activeSessions.remove(editor)
    }
}
```

---

## 九、测试策略

### 9.1 单元测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `DiffAccumulatorTest` | 流式文本解析、代码块提取、增量 Diff |
| `EditorInlineDiffRendererTest` | RangeHighlighter 创建/清除、颜色正确性 |
| `InlineChatCodeModifierTest` | 行替换/插入/删除、Undo 支持 |
| `InlineChatPromptsTest` | 各模式 prompt 组装正确性 |

### 9.2 集成测试

| 测试类 | 覆盖范围 |
|--------|---------|
| `InlineChatSessionTest` | 完整状态机流转 |
| `InlineChatControllerTest` | 多会话管理、冲突处理 |
| `InlineChatActionTest` | Action 注册、快捷键触发 |

### 9.3 UI 测试

| 测试场景 | 验证点 |
|---------|--------|
| 打开 Inline Chat | Inlay 正确插入、面板尺寸合适 |
| 发送 Explain 请求 | Thinking 状态、Diff 正确渲染 |
| 接受修改 | WriteCommandAction 执行、Ctrl+Z 可撤销 |
| 拒绝修改 | 高亮清除、代码恢复原状 |
| 暗色主题 | 颜色正确切换 |
| 多文件同时编辑 | 各 Editor 的 Inline Chat 互不干扰 |

---

## 十、执行计划

### 10.1 文件清单

| 文件 | 说明 |
|------|------|
| `ide/inline/InlineChatController.kt` | 全局控制器单例 |
| `ide/inline/InlineChatSession.kt` | 会话核心与状态机 |
| `ide/inline/InlineChatContext.kt` | 上下文数据类 |
| `ide/inline/ui/InlineChatInputRenderer.kt` | Inlay 渲染器 |
| `ide/inline/ui/InlineChatInputPanel.kt` | 输入面板 Swing UI |
| `ide/inline/ui/QuickActionBar.kt` | 快捷操作栏 |
| `ide/inline/diff/EditorInlineDiffRenderer.kt` | 行内 Diff 渲染 |
| `ide/inline/diff/DiffAccumulator.kt` | 流式 Diff 累积 |
| `ide/inline/diff/CharDiffComputer.kt` | 字符级 Diff 算法 |
| `ide/inline/diff/InlineDiffGutterRenderer.kt` | Gutter 图标渲染 |
| `ide/inline/modification/InlineChatCodeModifier.kt` | 代码修改器 |
| `ide/inline/modification/DiffResult.kt` | Diff 结果数据类 |
| `ide/inline/actions/OpenInlineChatAction.kt` | 主 Action |
| `ide/inline/actions/ExplainCodeAction.kt` | Explain Action |
| `ide/inline/actions/RefactorCodeAction.kt` | Refactor Action |
| `ide/inline/actions/FixErrorIntention.kt` | Fix Intention |
| `ide/inline/actions/GenerateTestAction.kt` | Test Action |
| `ide/inline/prompt/InlineChatPrompts.kt` | Inline 专用 Prompt |
| `ide/inline/InlineChatGuardrails.kt` | 安全护栏 |

### 10.2 执行步骤

| 阶段 | 任务 | 工时 | 依赖 |
|------|------|------|------|
| 1 | InlineChatController + InlineChatSession 框架 | 2天 | 无 |
| 2 | InlineChatInputPanel UI + Inlay 渲染 | 2天 | 阶段1 |
| 3 | EditorInlineDiffRenderer（背景色 + Gutter） | 3天 | 无 |
| 4 | DiffAccumulator + 流式增量渲染 | 2天 | 阶段3 |
| 5 | InlineChatCodeModifier + Undo 支持 | 1天 | 阶段3 |
| 6 | Action 注册 + 快捷键 + Intention | 1天 | 无 |
| 7 | Prompt 工程 + AgentCore 集成 | 2天 | 阶段1 |
| 8 | Guardrails + 异常处理 | 1天 | 阶段7 |
| 9 | 单元测试 + 集成测试 | 2天 | 全部 |
| 10 | UI 打磨 + 暗色主题适配 | 1天 | 全部 |

**总计：17 个工作日**

### 10.3 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| Inlay 在大量 Diff 时性能下降 | 中 | 限制同时高亮的最大行数（如 200 行），超出时降级为侧边栏 Diff |
| RangeHighlighter 与编辑器主题冲突 | 低 | 使用 JBColor 自动适配亮/暗主题 |
| 大文件 Diff 计算耗时 | 中 | 异步计算 Diff，显示 loading 指示器 |
| AI 返回格式不一致 | 中 | 灵活的代码块解析，支持多种 fence 格式 |

---

*本文档为 Inline Chat 最终版详细设计，所有组件按此设计直接实现，不做 MVP 过渡。*
