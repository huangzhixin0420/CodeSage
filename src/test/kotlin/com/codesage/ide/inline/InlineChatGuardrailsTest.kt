package com.codesage.ide.inline

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class InlineChatGuardrailsTest {

    private val guardrails = InlineChatGuardrails()

    @Test
    fun `allowed tools should be permitted`() {
        assertTrue(guardrails.isToolAllowed("read_file"))
        assertTrue(guardrails.isToolAllowed("search_code"))
        assertTrue(guardrails.isToolAllowed("grep_code"))
        assertTrue(guardrails.isToolAllowed("get_file_info"))
        assertTrue(guardrails.isToolAllowed("read_multiple_files"))
    }

    @Test
    fun `forbidden tools should not be allowed`() {
        assertFalse(guardrails.isToolAllowed("run_command"))
        assertFalse(guardrails.isToolAllowed("delete_file"))
        assertFalse(guardrails.isToolAllowed("move_file"))
        assertFalse(guardrails.isToolAllowed("copy_file"))
        assertFalse(guardrails.isToolAllowed("delegate_task"))
        assertFalse(guardrails.isToolAllowed("edit_file"))
        assertFalse(guardrails.isToolAllowed("write_file"))
    }

    @Test
    fun `forbidden tools should be detected by isToolForbidden`() {
        assertTrue(guardrails.isToolForbidden("run_command"))
        assertTrue(guardrails.isToolForbidden("delete_file"))
        assertTrue(guardrails.isToolForbidden("move_file"))
        assertTrue(guardrails.isToolForbidden("copy_file"))
        assertTrue(guardrails.isToolForbidden("delegate_task"))
        assertTrue(guardrails.isToolForbidden("edit_file"))
        assertTrue(guardrails.isToolForbidden("write_file"))
    }

    @Test
    fun `unknown tools should not be allowed or forbidden`() {
        assertFalse(guardrails.isToolAllowed("unknown_tool"))
        assertFalse(guardrails.isToolForbidden("unknown_tool"))
    }

    @Test
    fun `should provide forbidden messages`() {
        assertTrue(guardrails.getForbiddenMessage("run_command").contains("不支持执行命令"))
        assertTrue(guardrails.getForbiddenMessage("delete_file").contains("不支持删除文件"))
        assertTrue(guardrails.getForbiddenMessage("move_file").contains("不支持文件移动"))
        assertTrue(guardrails.getForbiddenMessage("delegate_task").contains("不启用子 Agent"))
        assertTrue(guardrails.getForbiddenMessage("edit_file").contains("不支持直接修改文件"))
    }
}
