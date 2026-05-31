package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel

/**
 * 圆角面板 — 支持抗锯齿圆角背景和可选边框
 */
class RoundedPanel(
    private val backgroundColor: JBColor,
    private val cornerRadius: Int = 12,
    private val borderColor: JBColor? = null,
    private val borderWidth: Float = 1f
) : JPanel() {

    init {
        isOpaque = false
        background = backgroundColor
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        val width = width
        val height = height
        val arc = cornerRadius * 2

        g2.color = backgroundColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)

        borderColor?.let { color ->
            g2.color = color
            g2.stroke = BasicStroke(borderWidth)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }

        super.paintComponent(g)
    }
}
