package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 6.10.4: 验证 [SubAgentProgressPanel] 能正确展示子 Agent 的
 * 递归深度预算、工具白名单/黑名单以及 delegation forbidden 警示。
 */
class SubAgentProgressPanelTest {

    @Test
    fun `panel should render depth budget and tool permissions`() {
        var panel: SubAgentProgressPanel? = null
        SwingUtilities.invokeAndWait {
            panel = SubAgentProgressPanel(
                sessionId = "sub_session_123",
                taskDescription = "Refactor user service",
                toolset = "coder",
                maxDepth = 4,
                allowedTools = listOf("read_file", "edit_file"),
                deniedTools = listOf("delete_file"),
                depth = 1
            )
        }

        val labels = collectLabels(panel!!)
        val texts = labels.map { it.text }

        assertTrue(
            texts.any { "Depth: 1 / 4" in it },
            "Should show current/max depth, got labels: $texts"
        )
        assertTrue(
            texts.any { "Allowed: read_file, edit_file" in it },
            "Should show allowed tools, got labels: $texts"
        )
        assertTrue(
            texts.any { "Denied: delete_file" in it },
            "Should show denied tools, got labels: $texts"
        )
    }

    @Test
    fun `panel should default to depth budget when depth is zero`() {
        var panel: SubAgentProgressPanel? = null
        SwingUtilities.invokeAndWait {
            panel = SubAgentProgressPanel(
                sessionId = "sub_session_456",
                taskDescription = "Simple task",
                toolset = "dev",
                maxDepth = 2
            )
        }

        val labels = collectLabels(panel!!)
        val texts = labels.map { it.text }

        assertTrue(
            texts.any { "Depth budget: 2" in it },
            "Should show depth budget when depth=0, got labels: $texts"
        )
    }

    @Test
    fun `panel should hide permission rows when no tool restrictions`() {
        var panel: SubAgentProgressPanel? = null
        SwingUtilities.invokeAndWait {
            panel = SubAgentProgressPanel(
                sessionId = "sub_session_789",
                taskDescription = "Unrestricted task",
                toolset = "dev",
                maxDepth = 2
            )
        }

        val labels = collectLabels(panel!!)
        val texts = labels.map { it.text }

        assertFalse(
            texts.any { it.startsWith("Allowed:") || it.startsWith("Denied:") },
            "Should not show empty permission rows, got labels: $texts"
        )
    }

    @Test
    fun `panel should show delegation forbidden warning when delegate_task is denied`() {
        var panel: SubAgentProgressPanel? = null
        SwingUtilities.invokeAndWait {
            panel = SubAgentProgressPanel(
                sessionId = "sub_session_no_delegate",
                taskDescription = "No recursion task",
                toolset = "dev",
                maxDepth = 1,
                deniedTools = listOf("delegate_task"),
                delegationForbidden = true
            )
        }

        val labels = collectLabels(panel!!)
        val warning = labels.find { "Delegation forbidden" in it.text }
        assertNotNull(warning, "Should show delegation forbidden warning")
        assertEquals(
            JBColor(Color(0xCC_00_00), Color(0xFF_4C_4C)).rgb,
            warning!!.foreground.rgb,
            "Warning should be red"
        )
    }

    /**
     * 递归收集 [panel] 内所有 [JLabel] 的文本，用于断言 UI 渲染内容。
     */
    private fun collectLabels(panel: JPanel): List<JLabel> {
        val result = mutableListOf<JLabel>()
        val stack = mutableListOf(panel)
        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            for (i in 0 until current.componentCount) {
                val child = current.getComponent(i)
                when (child) {
                    is JLabel -> result.add(child)
                    is JPanel -> stack.add(child)
                }
            }
        }
        return result
    }
}
