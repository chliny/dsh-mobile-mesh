package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the menu anchor contract used by session-list sort and add actions. */
class DsMenuContractTest {
    @Test
    fun `menu delegates opening to its interactive anchor`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/Overlays.kt").readText()

        assertTrue(source.contains("anchor: @Composable (onOpen: () -> Unit) -> Unit"))
        assertTrue(source.contains("Box { anchor { expanded = true } }"))
        assertTrue(!source.contains("Box(Modifier.clickable { expanded = true }) { anchor() }"))
    }

    @Test
    fun `session list view options anchor opens its own menu and lists the picks`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()

        // The tune button owns the state: no wrapper swallowing the tap, no menu that never opens.
        assertTrue(source.contains("IconButton(onClick = { optionsMenuOpen = true })"))
        assertTrue(source.contains("R.string.chatlist_sort_manual"))
        assertTrue(source.contains("R.string.chatlist_new_session"))
    }
}
