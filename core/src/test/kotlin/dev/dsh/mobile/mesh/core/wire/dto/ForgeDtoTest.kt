package dev.dsh.mobile.mesh.core.wire.dto

import dev.dsh.mobile.mesh.core.wire.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes captured from a Host with the forge plugin enabled. */
class ForgeDtoTest {

    @Test
    fun `panel state decodes checkout worktree and bound change requests`() {
        val json = """
            {
              "directory": "/work/repo",
              "checkout": {
                "state": "ready",
                "checkout": { "root": "/work/repo", "branch": "main", "linked": false, "commit": "abc1234" }
              },
              "repository": { "forge": "github", "host": "github.com", "owner": "owner", "name": "repo" },
              "readiness": { "state": "ready", "message": null },
              "changeRequests": [{
                "url": "https://github.com/owner/repo/pull/7",
                "origin": "created",
                "ref": { "repository": { "forge": "github", "host": "github.com", "owner": "owner", "name": "repo" }, "number": 7 },
                "update": {
                  "key": "github:github.com/owner/repo#7",
                  "fetchedAt": 1795422585951,
                  "snapshot": {
                    "url": "https://github.com/owner/repo/pull/7",
                    "title": "Add forge panel",
                    "state": "open",
                    "draft": true,
                    "headBranch": "feature/forge",
                    "headSha": "acdd92c7",
                    "baseBranch": "main",
                    "additions": 445,
                    "deletions": 31,
                    "mergeability": "mergeable",
                    "reviewDecision": "none",
                    "checks": [{ "name": "build", "state": "failure", "url": "https://example.test/check" }],
                    "checksRollup": "failure",
                    "unresolvedThreads": 2,
                    "updatedAt": "2026-11-22T07:52:56Z",
                    "closedAt": null
                  }
                },
                "snapshot": {
                  "ref": { "repository": { "forge": "github", "host": "github.com", "owner": "owner", "name": "repo" }, "number": 7 },
                  "url": "https://github.com/owner/repo/pull/7",
                  "title": "Add forge panel",
                  "state": "open",
                  "draft": true,
                  "headBranch": "feature/forge",
                  "headSha": "acdd92c7",
                  "baseBranch": "main",
                  "additions": 445,
                  "deletions": 31,
                  "mergeability": "mergeable",
                  "reviewDecision": "none",
                  "checks": [{ "name": "build", "state": "failure", "url": "https://example.test/check" }],
                  "checksRollup": "failure",
                  "unresolvedThreads": 2,
                  "updatedAt": "2026-11-22T07:52:56Z",
                  "closedAt": null
                }
              }],
              "worktree": {
                "editable": true,
                "blocker": null,
                "request": { "from": "main" },
                "created": null,
                "creation": { "phase": "prepare", "branch": "dsh-abc-1234", "from": "main", "held": true },
                "failure": null,
                "missing": null,
                "restoration": null,
                "waiting": "creation"
              },
              "local": { "base": "origin/main", "files": [{ "path": "src/App.kt", "additions": 3, "deletions": 1, "binary": false }] }
            }
        """.trimIndent()

        val state = decodeFromString<ForgePanelState>(json)
        assertEquals("ready", state.checkout.state)
        assertEquals("main", state.checkout.checkout?.branch)
        assertEquals(7, state.changeRequests.single().ref?.number)
        val snapshot = state.changeRequests.single().snapshot!!
        assertEquals("Add forge panel", snapshot.title)
        assertTrue(snapshot.draft)
        assertEquals("failure", snapshot.checksRollup)
        assertEquals(1, snapshot.checks.size)
        assertEquals(2, snapshot.unresolvedThreads)
        assertEquals("main", state.worktree.request?.from)
        assertEquals("prepare", state.worktree.creation?.phase)
        assertEquals("creation", state.worktree.waiting)
        assertEquals("src/App.kt", state.local?.files?.single()?.path)
    }

    @Test
    fun `a snapshot nested only in the poll update remains readable`() {
        val request = decodeFromString<ForgePanelChangeRequest>(
            """
            {
              "url": "https://github.com/owner/repo/pull/7",
              "ref": { "repository": { "forge": "github", "host": "github.com", "owner": "owner", "name": "repo" }, "number": 7 },
              "update": { "snapshot": { "url": "https://github.com/owner/repo/pull/7", "title": "Legacy shape", "state": "open", "headSha": "abc" } }
            }
            """.trimIndent(),
        )
        assertEquals("Legacy shape", request.latestSnapshot()?.title)
    }

    @Test
    fun `review data and session-list states decode the fields the app renders`() {
        val review = decodeFromString<ForgeReviewData>(
            """
            {
              "headSha": "head",
              "viewer": "me",
              "threads": [{
                "id": "t1",
                "path": "src/App.kt",
                "side": "R",
                "subject": "line",
                "current": { "commit": "head", "startLine": 3, "endLine": 4 },
                "original": { "commit": "old", "startLine": 3, "endLine": 4 },
                "diffHunk": "@@ -3,2 +3,2 @@\n-a\n+b",
                "resolved": false,
                "viewerCanReply": true,
                "viewerCanResolve": true,
                "comments": [{
                  "id": "c1",
                  "author": { "login": "alice", "bot": false },
                  "body": "Looks good",
                  "createdAt": "2026-01-01T00:00:00Z",
                  "pending": true,
                  "viewerCanEdit": true,
                  "viewerCanDelete": false
                }]
              }],
              "pendingReview": { "id": "r1", "comments": 1 },
              "viewerCanReview": true
            }
            """.trimIndent(),
        )
        assertEquals("me", review.viewer)
        assertEquals(1, review.pendingReview?.comments)
        val thread = review.threads.single()
        assertEquals(3, thread.current?.startLine)
        assertEquals("alice", thread.comments.single().author?.login)
        assertTrue(thread.comments.single().pending)

        val frame = decodeFromString<ForgeChangeRequestStatesFrame>(
            """{"type":"changed","states":[{"url":"https://github.com/o/r/pull/1","number":1,"state":"open","checksRollup":"failure"}]}""",
        )
        assertEquals("changed", frame.type)
        assertEquals("failure", frame.states.single().checksRollup)
    }

    @Test
    fun `local diff and commit list decode the fields the app reads`() {
        val diff = decodeFromString<ForgeLocalDiff>(
            """
            {
              "base": "origin/main",
              "files": [{
                "path": "src/App.kt",
                "previousPath": null,
                "status": "modified",
                "binary": false,
                "oversized": false,
                "untracked": false,
                "additions": 2,
                "deletions": 1,
                "hunks": [{ "oldStart": 10, "oldLines": 2, "newStart": 10, "newLines": 3,
                            "lines": [" context", "-old", "+new"] }],
                "layer": "implementation",
                "digest": "sha"
              }],
              "omitted": 1
            }
            """.trimIndent(),
        )
        assertEquals("origin/main", diff.base)
        assertEquals("src/App.kt", diff.files.single().path)
        assertEquals("+new", diff.files.single().hunks.single().lines.last())
        assertEquals(1, diff.omitted)

        val commits = decodeFromString<ForgeLocalCommits>(
            """{"headSha":"c","commits":[{"sha":"a","parent":null,"title":"root","author":"me","authoredAt":"2026-01-01T00:00:00Z"}],"truncated":false}""",
        )
        assertEquals("a", commits.commits.single().sha)
        assertEquals(null, commits.commits.single().parent)
    }
}
