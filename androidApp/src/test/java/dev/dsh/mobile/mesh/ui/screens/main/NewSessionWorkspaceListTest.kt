package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression guard: the new-session workspace picker must constrain long lists for scrolling. */
class NewSessionWorkspaceListTest {
    @Test
    fun `new session workspace picker uses a bounded lazy list`() {
        val source = java.io.File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val picker = source.substringAfter("private fun NewSessionDialog(").substringBefore("@Composable\nprivate fun NewWorkspaceDialog")

        assertEquals(true, picker.contains("LazyColumn("))
        assertEquals(true, picker.contains("heightIn(max = 400.dp)"))
        assertEquals(true, picker.contains("items(workspaces, key = { it.workspaceId })"))
        assertEquals(true, picker.contains("chatlist_home_directory"))
    }
}
