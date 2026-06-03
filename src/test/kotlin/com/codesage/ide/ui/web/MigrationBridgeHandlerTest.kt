package com.codesage.ide.ui.web

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * MigrationBridgeHandler 单元测试
 *
 * 涉及 PluginConfig / SettingsRepository 的完整集成测试需要 IDE 平台,
 * 这里只测纯逻辑路径:handle 返回值、错误处理。
 */
class MigrationBridgeHandlerTest {

    @Test
    fun `handle returns false for unrelated types`() {
        val handler = MigrationBridgeHandler { _ -> }
        assertFalse(handler.handle("send_message", emptyMap()))
        assertFalse(handler.handle("settings_get", emptyMap()))
    }

    @Test
    fun `handle returns true for legacy_migration types`() {
        val handler = MigrationBridgeHandler { _ -> }
        assertTrue(handler.handle("legacy_migration_check", emptyMap()))
        assertTrue(handler.handle("legacy_migration_run", emptyMap()))
        assertTrue(handler.handle("legacy_migration_skip", emptyMap()))
    }
}
