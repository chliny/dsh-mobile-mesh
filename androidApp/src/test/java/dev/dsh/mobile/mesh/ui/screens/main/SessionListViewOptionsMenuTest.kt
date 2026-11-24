package dev.dsh.mobile.mesh.ui.screens.main

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Material dropdown wiring used by the session list's sort and add actions. */
class SessionListViewOptionsMenuTest {
    @Test
    fun `session list view options anchor opens its own menu and lists the picks`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()

        // The tune button owns the state: no wrapper swallowing the tap, no menu that never opens.
        assertTrue(source.contains("IconButton(onClick = { optionsMenuOpen = true })"))
        assertTrue(source.contains("R.string.chatlist_sort_manual"))
        assertTrue(source.contains("R.string.chatlist_new_session"))
    }
}
