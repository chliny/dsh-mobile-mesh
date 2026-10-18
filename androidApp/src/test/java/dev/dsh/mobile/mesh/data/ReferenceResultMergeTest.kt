package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.SessionReferenceCandidate
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceResultMergeTest {
    @Test fun `concurrent server lists preserve both answers whichever completes first`() {
        val initial = WorkspaceFilesState(workspaceKey = "w", referenceSessionId = "s", referenceQuery = "", referenceSerial = 1)
        val files = listOf(WorkspaceDirectoryEntry("notes.md", "file"))
        val sessions = listOf(SessionReferenceCandidate("s2", "Notes", mention = "@[Notes](dsh-session:s2)"))
        val first = initial.withReferenceResult("w", "s", "", serial = 1, files = files)
            .withReferenceResult("w", "s", "", serial = 1, sessions = sessions)
        val second = initial.withReferenceResult("w", "s", "", serial = 1, sessions = sessions)
            .withReferenceResult("w", "s", "", serial = 1, files = files)
        assertEquals(files, first.fileReferences)
        assertEquals(sessions, first.sessionReferences)
        assertEquals(first, second)
    }

    @Test fun `late same-query replies cannot replace a newer server result`() {
        val latest = listOf(WorkspaceDirectoryEntry("current.txt", "file"))
        val obsolete = listOf(WorkspaceDirectoryEntry("old.txt", "file"))
        val current = WorkspaceFilesState(workspaceKey = "w", referenceSessionId = "s", referenceQuery = "foo", referenceSerial = 2)
            .withReferenceResult("w", "s", "foo", serial = 2, files = latest)
        assertEquals(latest, current.fileReferences)
        assertEquals(current, current.withReferenceResult("w", "s", "foo", serial = 1, files = obsolete))
    }

    @Test fun `late response for an old session or query cannot replace the visible list`() {
        val state = WorkspaceFilesState(workspaceKey = "w", referenceSessionId = "new", referenceQuery = "a", referenceSerial = 2)
        val files = listOf(WorkspaceDirectoryEntry("old.txt", "file"))
        assertEquals(state, state.withReferenceResult("w", "old", "a", serial = 2, files = files))
        assertEquals(state, state.withReferenceResult("w", "new", "", serial = 2, files = files))
    }
}
