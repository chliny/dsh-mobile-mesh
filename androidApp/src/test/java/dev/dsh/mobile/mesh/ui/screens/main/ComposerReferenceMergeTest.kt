package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A forge comment is queued while the changes page owns the screen and merged into the composer
 * when ChatScreen returns. The merge must not drop an existing draft or introduce stray blank
 * lines between multiple references.
 */
class ComposerReferenceMergeTest {

    @Test
    fun `pending references join an existing draft with one blank line`() {
        assertEquals(
            "Explain this:\n\n## User Comment 1\nFile: a.kt",
            mergePendingComposerText("Explain this:\n", "## User Comment 1\nFile: a.kt"),
        )
    }

    @Test
    fun `a blank draft becomes the pending reference`() {
        assertEquals("## User Comment 1", mergePendingComposerText("", "## User Comment 1"))
        assertEquals("  ", mergePendingComposerText("  ", ""))
    }
}
