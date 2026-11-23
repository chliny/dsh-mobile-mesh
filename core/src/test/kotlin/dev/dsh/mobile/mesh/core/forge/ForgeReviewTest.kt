package dev.dsh.mobile.mesh.core.forge

import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommit
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffFile
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffHunk
import dev.dsh.mobile.mesh.core.wire.dto.ForgeAnchor
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommentAuthor
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewComment
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgeReviewTest {

    @Test
    fun `diff rows advance old and new line numbers independently`() {
        val hunk = ForgeDiffHunk(
            oldStart = 10,
            oldLines = 3,
            newStart = 20,
            newLines = 4,
            lines = listOf(
                " context",
                "-removed",
                "+added one",
                "+added two",
                " context two",
                "\\ No newline at end of file",
            ),
        )

        val rows = forgeDiffRows(listOf(hunk))

        assertEquals(listOf(10, 11, null, null, 12, null), rows.map { it.oldLine })
        assertEquals(listOf(20, null, 21, 22, 23, null), rows.map { it.newLine })
        assertEquals(listOf(' ', '-', '+', '+', ' ', '\\'), rows.map { it.marker })
        assertEquals("context", rows[0].text)
        assertEquals("added two", rows[3].text)
    }

    @Test
    fun `comment selection accepts only one side of one hunk`() {
        val hunk = ForgeDiffHunk(
            oldStart = 4,
            oldLines = 3,
            newStart = 4,
            newLines = 4,
            lines = listOf(" one", "-two", "+two updated", " three", " four"),
        )
        val file = ForgeDiffFile(path = "src/App.kt", hunks = listOf(hunk))

        val comment = selectForgeComment(file, "R", 6, 5)!!
        assertEquals(5, comment.startLine)
        assertEquals(6, comment.endLine)
        assertTrue(comment.hunk.startsWith("@@ -4,3 +4,4 @@"))
        assertTrue(comment.hunk.contains("+two updated"))

        // The old-side counter only covers lines 4..6; line 7 does not exist on that side.
        assertNull(selectForgeComment(file, "L", 4, 7))
        // Both ends must be on the same displayed hunk.
        val second = ForgeDiffHunk(7, 1, 8, 1, listOf(" later"))
        assertNull(selectForgeComment(file.copy(hunks = listOf(hunk, second)), "R", 6, 8))
    }

    @Test
    fun `frozen reference keeps hunk markdown fences inside the fenced block`() {
        val hunk = ForgeDiffHunk(
            oldStart = 1,
            oldLines = 1,
            newStart = 1,
            newLines = 2,
            lines = listOf(" before", "+````kotlin", "+````"),
        )
        val comment = selectForgeComment(ForgeDiffFile(path = "docs/Example.md", hunks = listOf(hunk)), "R", 1, 2)!!
            .copy(body = "Keep this snippet.")
        val text = formatForgeReviewComment(
            ForgeReviewSelection(
                ForgeReviewSource.Local(
                    scope = "branch",
                    baseLabel = "origin/main",
                    baseRef = "refs/remotes/origin/main",
                    commits = ForgeCommitRange("a", "a"),
                ),
                comment,
            ),
            index = 2,
        )

        assertTrue(text.startsWith("## User Comment 2\nFile: docs/Example.md\nSide: R\nLines: 1-2\n"))
        assertTrue(text.contains("Source: Local (branch)\nBase: origin/main\nCommits: a\n"))
        assertTrue(text.contains("Diff hunk:\n`````diff\n@@ -1,1 +1,2 @@\n"))
        assertTrue(text.contains("\n`````\nComment:\nKeep this snippet."))
    }

    @Test
    fun `change request reference names the URL and commit range`() {
        val comment = ForgeReviewComment(
            path = "src/App.kt",
            side = "R",
            startLine = 8,
            endLine = 8,
            hunk = "@@ -8,1 +8,1 @@\n-old\n+new",
        )
        val text = formatForgeReviewComment(
            ForgeReviewSelection(
                ForgeReviewSource.Change(
                    url = "https://github.com/owner/repo/pull/7",
                    headSha = "acdd92c7",
                    commits = ForgeCommitRange("1111111", "acdd92c7"),
                ),
                comment,
            ),
            index = 1,
        )

        assertTrue(text.contains("Source: https://github.com/owner/repo/pull/7\nCommit: acdd92c7\nCommits: 1111111^..acdd92c7\n"))
        assertTrue(text.contains("Lines: 8\n"))
    }

    @Test
    fun `review thread quote keeps comments inside the quoted block`() {
        val thread = ForgeReviewThread(
            id = "t1",
            path = "src/App.kt",
            side = "R",
            current = ForgeAnchor(commit = "abc", startLine = 3, endLine = 4),
            diffHunk = "@@ -3,2 +3,2 @@\n-old\n+new",
            comments = listOf(
                ForgeReviewComment(
                    id = "c1",
                    author = ForgeCommentAuthor(login = "alice"),
                    body = "hello\n\n> quoted",
                    createdAt = "2026-01-02T03:04:05Z",
                    url = "https://github.com/owner/repo/pull/7#discussion_r1",
                ),
            ),
        )

        val text = formatForgeThreadQuote(thread, "https://github.com/owner/repo/pull/7", index = 2)

        assertTrue(text.startsWith("## Review Thread 2\nFile: src/App.kt\nSide: R\nLines: 3-4\nCommit: abc\nState: unresolved\n"))
        assertTrue(text.contains("Source: https://github.com/owner/repo/pull/7#discussion_r1\n"))
        assertTrue(text.contains("Diff hunk:\n```diff\n@@ -3,2 +3,2 @@\n-old\n+new\n```\n"))
        assertTrue(text.endsWith("Comments:\n@alice (2026-01-02T03:04:05Z):\n> hello\n>\n> > quoted"))
    }

    @Test
    fun `commit range must follow the first-parent line`() {
        val commits = listOf(
            ForgeCommit(sha = "a", parent = null),
            ForgeCommit(sha = "b", parent = "a"),
            ForgeCommit(sha = "c", parent = "b"),
            ForgeCommit(sha = "d", parent = "c"),
        )

        assertEquals(ForgeCommitRangeCheck.Accepted("b"), checkForgeCommitRange(commits, "b", "d"))
        assertEquals(ForgeCommitRangeCheck.Missing, checkForgeCommitRange(commits, "c", "b"))
        val branched = commits.toMutableList().apply {
            this[3] = ForgeCommit(sha = "d", parent = "b")
        }
        assertEquals(ForgeCommitRangeCheck.NotLinear, checkForgeCommitRange(branched, "b", "d"))
        assertEquals(ForgeCommitRangeCheck.Missing, checkForgeCommitRange(commits, "a", "missing"))
    }
}
