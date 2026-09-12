package dev.dsh.mobile.mesh.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(source.contains("icon = Icons.Filled.Add,"))
        assertTrue(source.contains("icon = Icons.Filled.SwapVert,"))
        assertTrue(source.contains("onClick = onOpen,"))
    }
}
