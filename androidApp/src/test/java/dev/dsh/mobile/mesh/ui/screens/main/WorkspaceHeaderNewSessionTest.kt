package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the direct workspace-header shortcut for creating and opening a session. */
class WorkspaceHeaderNewSessionTest {
    @Test
    fun `workspace header exposes plus action wired to workspace session creation`() {
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val header = source.substringAfter("private fun WorkspaceHeader(").substringBefore("@Composable\nprivate fun WorkspaceMenu")

        assertTrue(header.contains("Icons.Filled.Add"))
        assertTrue(header.contains("onClick = onNewSession"))
        assertTrue(source.contains("store.createSession(workspaceId = workspace.workspaceId)"))
    }
}
