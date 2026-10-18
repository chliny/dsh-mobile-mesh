package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.WireJson
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FileReferenceDecodeTest {
    @Test
    fun `malformed reference rows do not hide valid server candidates`() {
        val response = WireJson.parseToJsonElement(
            """{"items":[{"path":{"unexpected":"object"}},
                {"path":" docs/valid.md ","kind":{"unexpected":"object"}},
                {"path":"src/main.kt","kind":"file"}]}""",
        )
        assertEquals(
            listOf(WorkspaceDirectoryEntry(" docs/valid.md ", "file"), WorkspaceDirectoryEntry("src/main.kt", "file")),
            decodeFileReferenceRows(response),
        )
    }
}
