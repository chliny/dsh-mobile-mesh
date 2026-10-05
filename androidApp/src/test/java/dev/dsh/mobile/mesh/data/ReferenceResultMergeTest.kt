package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SessionReferenceCandidate
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceResultMergeTest {
    @Test fun `concurrent server lists preserve both answers whichever completes first`() {
        val initial = WorkspaceFilesState(workspaceKey = "w", referenceSessionId = "s", referenceQuery = "")
        val files = listOf(WorkspaceDirectoryEntry("notes.md", "file"))
        val sessions = listOf(SessionReferenceCandidate("s2", "Notes", mention = "@[Notes](dsh-session:s2)"))
        val first = initial.withReferenceResult("w", "s", "", files = files)
            .withReferenceResult("w", "s", "", sessions = sessions)
        val second = initial.withReferenceResult("w", "s", "", sessions = sessions)
            .withReferenceResult("w", "s", "", files = files)
        assertEquals(files, first.fileReferences)
        assertEquals(sessions, first.sessionReferences)
        assertEquals(first, second)
    }

    @Test fun `late response for an old session or query cannot replace the visible list`() {
        val state = WorkspaceFilesState(workspaceKey = "w", referenceSessionId = "new", referenceQuery = "a")
        val files = listOf(WorkspaceDirectoryEntry("old.txt", "file"))
        assertEquals(state, state.withReferenceResult("w", "old", "a", files = files))
        assertEquals(state, state.withReferenceResult("w", "new", "", files = files))
    }
}
