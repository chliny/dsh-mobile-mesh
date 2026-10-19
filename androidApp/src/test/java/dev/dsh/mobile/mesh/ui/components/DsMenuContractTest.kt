package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the menu anchor contract used by session-list sort and add actions. */
class DsMenuContractTest {
    @Test
    fun `menu delegates opening to its interactive anchor`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/Overlays.kt").readText()

        assertTrue(source.contains("anchor: @Composable (onOpen: () -> Unit) -> Unit"))
        assertTrue(source.contains("Box { anchor { expanded = true } }"))
        assertFalse(source.contains("Box(Modifier.clickable { expanded = true }) { anchor() }"))
    }

    @Test
    fun `session list sort and add anchors invoke menu opener`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()

        assertTrue(source.contains("anchor = { onOpen ->"))
        assertTrue(source.contains("icon = Icons.Filled.Tune,"))
        assertTrue(source.contains("onClick = onOpen,"))
        assertTrue(source.contains("MenuItem(stringResource(R.string.chatlist_sort_manual), Icons.Filled.SwapVert,"))
        assertTrue(source.contains("MenuItem(stringResource(R.string.chatlist_new_session), Icons.Filled.Add,"))
    }
}
