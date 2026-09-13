package dev.dsh.mobile.mesh.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the LazyColumn-safe KodeView adapter used by file previews and tool cards. */
class KodeViewCodeTest {
    @Test
    fun `adapter renders highlighted text without kodeview owned scrolling`() {
        val source = File("src/main/java/dev/dsh/mobile/mesh/ui/components/KodeViewCode.kt").readText()
        val body = source.substringAfter("fun KodeViewCode(").substringBefore("/** Maps a file path")
        assertTrue(body.contains("BasicText("))
        assertFalse(body.contains("CodeTextView("))
        assertTrue(body.contains("withContext(Dispatchers.Default)"))
    }
}
