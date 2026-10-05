package dev.dsh.mobile.mesh.core.wire

import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFollowFrame
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFollowFrameSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePinProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `new baseline and pinned increment keep authoritative server ordering`() {
        val baseline = json.decodeFromString(WorkspaceFollowFrameSerializer,
            """{"type":"baseline","value":{"items":[],"archivedSessionIds":[],"pinnedSessionIds":["b","a"]}}""")
        val delta = json.decodeFromString(WorkspaceFollowFrameSerializer,
            """{"type":"pinned","pinnedSessionIds":["a","b"]}""")
        assertEquals(listOf("b", "a"), (baseline as WorkspaceFollowFrame.Baseline).value.pinnedSessionIds)
        assertEquals(listOf("a", "b"), (delta as WorkspaceFollowFrame.Pinned).pinnedSessionIds)
    }

    @Test fun `old baseline without pin capability still decodes`() {
        val baseline = json.decodeFromString(WorkspaceFollowFrameSerializer,
            """{"type":"baseline","value":{"items":[],"archivedSessionIds":[]}}""")
        assertTrue((baseline as WorkspaceFollowFrame.Baseline).value.pinnedSessionIds.isEmpty())
    }
}
