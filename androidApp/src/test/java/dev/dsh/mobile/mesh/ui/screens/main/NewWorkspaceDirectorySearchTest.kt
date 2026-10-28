package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Selecting a filtered folder must leave the next level ready for a fresh search. */
class NewWorkspaceDirectorySearchTest {
    @Test
    fun `selecting a directory clears search before loading its children`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/screens/main/ChatListDrawer.kt").readText()
        val dialog = source.substringAfter("private fun NewWorkspaceDialog(").substringBefore("// Helpers")
        val entryClick = dialog.substringAfter("items(entries, key = { it.path })")
            .substringAfter("onClick = {").substringBefore("},")

        assertTrue("directory selection must clear the old search", entryClick.contains("search = \"\""))
        assertTrue("the search must be cleared before loading the next level",
            entryClick.indexOf("search = \"\"") < entryClick.indexOf("load(entry.path)"))
    }
}
