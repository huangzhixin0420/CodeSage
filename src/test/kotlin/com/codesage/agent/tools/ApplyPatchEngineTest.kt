package com.codesage.agent.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * apply_patch 解析与应用引擎单元测试。
 *
 * 覆盖正常路径、多文件 patch、边界条件与错误路径。
 */
class ApplyPatchEngineTest {

    @Test
    fun `single file update applies hunk correctly`() {
        val patch = """
            *** Begin Patch
            *** Update File: src/Foo.kt
            @@ class Foo {
            -    val x = 1
            +    val x = 2
            *** End Patch
        """.trimIndent()

        val original = "class Foo {\n    val x = 1\n}\n"
        val result = parseAndApply(patch, mapOf("src/Foo.kt" to original))

        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Success, "Expected success: $result")
        val files = (result as ApplyPatchEngine.PatchApplyResult.Success).files
        assertEquals("class Foo {\n    val x = 2\n}\n", files["src/Foo.kt"])
    }

    @Test
    fun `multi-file patch updates and adds files`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@ hello
            -world
            +CodeSage
            *** Add File: b.txt
            new line 1
            new line 2
            *** End Patch
        """.trimIndent()

        val result = parseAndApply(patch, mapOf("a.txt" to "hello\nworld\n"))
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Success, "Expected success: $result")
        val files = (result as ApplyPatchEngine.PatchApplyResult.Success).files
        assertEquals("hello\nCodeSage\n", files["a.txt"])
        assertEquals("new line 1\nnew line 2", files["b.txt"])
    }

    @Test
    fun `delete file removes it from result`() {
        val patch = """
            *** Begin Patch
            *** Delete File: old.txt
            *** End Patch
        """.trimIndent()

        val result = parseAndApply(patch, mapOf("old.txt" to "content"))
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Success, "Expected success: $result")
        val success = result as ApplyPatchEngine.PatchApplyResult.Success
        assertTrue(success.files.isEmpty(), "Deleted file should not appear in files")
        assertEquals(listOf("old.txt"), success.deletedFiles)
    }

    @Test
    fun `mismatch hunk returns error`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@ not present
            - old
            + new
            *** End Patch
        """.trimIndent()

        val result = parseAndApply(patch, mapOf("a.txt" to "hello\nworld\n"))
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Error, "Expected apply error: $result")
    }

    @Test
    fun `ambiguous hunk returns error`() {
        val patch = """
            *** Begin Patch
            *** Update File: a.txt
            @@ repeat
            - old
            + new
            *** End Patch
        """.trimIndent()

        val original = "repeat\nold\nrepeat\nold\n"
        val result = parseAndApply(patch, mapOf("a.txt" to original))
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Error, "Expected ambiguous apply error: $result")
    }

    @Test
    fun `missing begin marker returns parse error`() {
        val result = ApplyPatchEngine.parse("just some text")
        assertTrue(result is ApplyPatchEngine.PatchParseResult.Error, "Expected parse error: $result")
        assertTrue(
            (result as ApplyPatchEngine.PatchParseResult.Error).message.contains("Begin Patch", ignoreCase = true)
        )
    }

    @Test
    fun `patch without operations returns parse error`() {
        val patch = """
            *** Begin Patch
            *** End Patch
        """.trimIndent()

        val result = ApplyPatchEngine.parse(patch)
        assertTrue(result is ApplyPatchEngine.PatchParseResult.Error, "Expected parse error: $result")
    }

    @Test
    fun `add file strips leading plus signs when all lines are prefixed`() {
        val patch = """
            *** Begin Patch
            *** Add File: c.txt
            +line one
            +line two
            *** End Patch
        """.trimIndent()

        val result = parseAndApply(patch, emptyMap())
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Success, "Expected success: $result")
        val files = (result as ApplyPatchEngine.PatchApplyResult.Success).files
        assertEquals("line one\nline two", files["c.txt"])
    }

    @Test
    fun `multiple hunks on same file apply sequentially`() {
        val patch = """
            *** Begin Patch
            *** Update File: d.txt
            @@ first
            -1
            +one
            @@ second
            -2
            +two
            *** End Patch
        """.trimIndent()

        val original = "first\n1\nsecond\n2\n"
        val result = parseAndApply(patch, mapOf("d.txt" to original))
        assertTrue(result is ApplyPatchEngine.PatchApplyResult.Success, "Expected success: $result")
        val files = (result as ApplyPatchEngine.PatchApplyResult.Success).files
        assertEquals("first\none\nsecond\ntwo\n", files["d.txt"])
    }

    private fun parseAndApply(
        patch: String,
        originals: Map<String, String>
    ): ApplyPatchEngine.PatchApplyResult {
        val parseResult = ApplyPatchEngine.parse(patch)
        assertTrue(parseResult is ApplyPatchEngine.PatchParseResult.Success, "Parse failed: $parseResult")
        val plan = (parseResult as ApplyPatchEngine.PatchParseResult.Success).plan
        return ApplyPatchEngine.apply(plan, originals)
    }
}
