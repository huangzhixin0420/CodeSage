package com.codesage.ide.ui.components.chat

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * CodeSage 聊天 UI 主题常量
 * 集中管理所有颜色，支持亮/暗色双主题
 */
object ChatTheme {

    // ===== 面板背景 =====
    val panelBackground = JBColor(Color(0xFF_FF_FF), Color(0x1E_1E_1E))
    val panelBackgroundSecondary = JBColor(Color(0xF8_F8_F8), Color(0x22_22_22))

    // ===== 消息气泡 =====
    val bubbleUserBg = JBColor(Color(0xE3_F2_FD), Color(0x1E_3A_5F))
    val bubbleUserText = JBColor(Color(0x15_65_C0), Color(0x90_CA_F9))
    val bubbleAssistantBg = JBColor(Color(0xF5_F5_F5), Color(0x2D_2D_2D))
    val bubbleAssistantText = JBColor(Color(0x33_33_33), Color(0xE0_E0_E0))
    val bubbleSystemBg = JBColor(Color(0xFF_F8_E1), Color(0x3D_3D_1A))
    val bubbleErrorBg = JBColor(Color(0xFF_EB_EE), Color(0x3D_1A_1A))
    val bubbleToolBg = JBColor(Color(0xF3_E5_F5), Color(0x2D_1A_3D))

    // ===== 边框与分隔线 =====
    val borderPrimary = JBColor(Color(0xE0_E0_E0), Color(0x33_33_33))
    val borderSecondary = JBColor(Color(0xD8_D8_D8), Color(0x3D_3D_3D))
    val divider = JBColor(Color(0xE8_E8_E8), Color(0x33_33_33))

    // ===== 文字 =====
    val textPrimary = JBColor(Color(0x22_22_22), Color(0xCC_CC_CC))
    val textSecondary = JBColor(Color(0x33_33_33), Color(0xAA_AA_AA))
    val textMuted = JBColor(Color(0x88_88_88), Color(0x77_77_77))
    val textTimestamp = JBColor(Color(0x99_99_99), Color(0x66_66_66))

    // ===== 交互色 =====
    val accentBlue = JBColor(Color(0x00_66_CC), Color(0x4D_A6_FF))
    val accentGreen = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
    val accentRed = JBColor(Color(0xD3_2F_2F), Color(0xFF_8A_80))
    val accentOrange = JBColor(Color(0xED_6C_02), Color(0xFF_A0_00))

    // ===== 代码块 =====
    val codeBlockBg = JBColor(Color(0xF8_F8_F8), Color(0x25_25_25))
    val codeBlockHeaderBg = JBColor(Color(0xF0_F0_F0), Color(0x2A_2A_2A))
    val codeLineNumberBg = JBColor(Color(0xF0_F0_F0), Color(0x22_22_22))
    val codeLineNumberText = JBColor(Color(0x99_99_99), Color(0x66_66_66))

    // ===== Thinking 面板 =====
    val thinkingBg = JBColor(Color(0xF5_F5_F5), Color(0x2A_2A_2A))
    val thinkingBorder = JBColor(Color(0xE0_E0_E0), Color(0x3D_3D_3D))
    val thinkingText = JBColor(Color(0x66_66_66), Color(0xAA_AA_AA))
    val thinkingSuccess = JBColor(Color(0x2E_7D_32), Color(0x66_BB_6A))
}
