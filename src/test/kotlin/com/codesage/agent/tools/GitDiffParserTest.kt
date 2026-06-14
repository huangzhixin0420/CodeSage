package com.codesage.agent.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GitDiffParserTest {

    @Test
    fun `parse should handle empty diff`() {
        val diff = GitDiffParser.parse("")
        assertFalse(diff.hasChanges)
        assertEquals(0, diff.totalAdditions)
        assertEquals(0, diff.totalDeletions)
        assertTrue(diff.files.isEmpty())
    }

    @Test
    fun `parse should detect modified file with add and remove lines`() {
        val raw = """
            diff --git a/src/Main.kt b/src/Main.kt
            index 1234567..abcdefg 100644
            --- a/src/Main.kt
            +++ b/src/Main.kt
            @@ -1,3 +1,3 @@
             package com.example

            -fun main() = println("old")
            +fun main() = println("new")

        """.trimIndent()

        val parsed = GitDiffParser.parse(raw)
        assertTrue(parsed.hasChanges)
        assertEquals(1, parsed.files.size)

        val file = parsed.files[0]
        assertEquals("src/Main.kt", file.oldPath)
        assertEquals("src/Main.kt", file.newPath)
        assertEquals("modified", file.changeType)
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)
        assertFalse(file.isBinary)

        assertEquals(1, file.hunks.size)
        val hunk = file.hunks[0]
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldLines)
        assertEquals(1, hunk.newStart)
        assertEquals(3, hunk.newLines)
        assertEquals(5, hunk.lines.size)

        val removed = hunk.lines.find { it.type == "remove" }
        assertNotNull(removed)
        assertTrue(removed!!.content.contains("old"))

        val added = hunk.lines.find { it.type == "add" }
        assertNotNull(added)
        assertTrue(added!!.content.contains("new"))
    }

    @Test
    fun `parse should detect added file`() {
        val raw = """
            diff --git a/README.md b/README.md
            new file mode 100644
            index 0000000..1111111
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1,2 @@
            +# Title
            +body
        """.trimIndent()

        val parsed = GitDiffParser.parse(raw)
        val file = parsed.files.single()
        assertEquals("added", file.changeType)
        assertNull(file.oldPath)
        assertEquals("README.md", file.newPath)
        assertEquals(2, file.additions)
        assertEquals(0, file.deletions)
    }

    @Test
    fun `parse should detect deleted file`() {
        val raw = """
            diff --git a/Old.kt b/Old.kt
            deleted file mode 100644
            index 1111111..0000000
            --- a/Old.kt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -class Old
            -// end
        """.trimIndent()

        val parsed = GitDiffParser.parse(raw)
        val file = parsed.files.single()
        assertEquals("deleted", file.changeType)
        assertEquals("Old.kt", file.oldPath)
        assertNull(file.newPath)
        assertEquals(0, file.additions)
        assertEquals(2, file.deletions)
    }

    @Test
    fun `parse should detect renamed file`() {
        val raw = """
            diff --git a/old.txt b/new.txt
            similarity index 95%
            rename from old.txt
            rename to new.txt
            index 1111111..2222222 100644
            --- a/old.txt
            +++ b/new.txt
            @@ -1,2 +1,2 @@
             unchanged
            -old line
            +new line
        """.trimIndent()

        val parsed = GitDiffParser.parse(raw)
        val file = parsed.files.single()
        assertEquals("renamed", file.changeType)
        assertEquals("old.txt", file.oldPath)
        assertEquals("new.txt", file.newPath)
    }

    @Test
    fun `parse should handle multiple files`() {
        val raw = """
            diff --git a/a.txt b/a.txt
            index 111..222 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -a
            +A
            diff --git a/b.txt b/b.txt
            index 333..444 100644
            --- a/b.txt
            +++ b/b.txt
            @@ -1 +1 @@
            -b
            +B
        """.trimIndent()

        val parsed = GitDiffParser.parse(raw)
        assertEquals(2, parsed.files.size)
        assertEquals("a.txt", parsed.files[0].newPath)
        assertEquals("b.txt", parsed.files[1].newPath)
        assertEquals(2, parsed.totalAdditions)
        assertEquals(2, parsed.totalDeletions)
    }

    @Test
    fun `parse should handle paths with spaces`() {
        val raw = """diff --git "a/my file.txt" "b/my file.txt"
index 111..222 100644
--- "a/my file.txt"
+++ "b/my file.txt"
@@ -1 +1 @@
-old
+new
"""

        val parsed = GitDiffParser.parse(raw)
        val file = parsed.files.single()
        assertEquals("my file.txt", file.oldPath)
        assertEquals("my file.txt", file.newPath)
    }
}
