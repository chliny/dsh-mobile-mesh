package dev.dsh.mobile.mesh.core.forge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCommentTest {
    @Test
    fun `file content comment keeps file range and selected excerpt`() {
        val text = formatFileLineComment(
            FileLineComment(
                path = "src/App.kt",
                startLine = 12,
                endLine = 14,
                excerpt = "val name = input.name\nrequire(name.isNotBlank())",
                body = "Handle the blank name here.",
            ),
            index = 1,
        )

        assertTrue(text.startsWith("## File Comment 1\nFile: src/App.kt\nLines: 12-14\nContent:\n```text\n"))
        assertTrue(text.contains("val name = input.name\nrequire(name.isNotBlank())\n```\nComment:\nHandle the blank name here."))
    }

    @Test
    fun `excerpt markdown fences stay inside the quoted block`() {
        val excerpt = "```kotlin\nval fence = \"```\"\n```"
        val text = formatFileLineComment(
            FileLineComment("docs/example.md", 7, 7, excerpt, "Keep the sample."),
            index = 3,
        )

        assertEquals(
            "## File Comment 3\nFile: docs/example.md\nLines: 7\nContent:\n````text\n" +
                "$excerpt\n````\nComment:\nKeep the sample.",
            text,
        )
    }
}
