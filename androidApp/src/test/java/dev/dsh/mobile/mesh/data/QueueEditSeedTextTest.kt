package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Editing a queued turn used to open the editor on `previewText`, the dock row's one-line summary
 * capped at 200 characters, and OK sent that string straight back as the item's new content. A turn
 * longer than the cap therefore opened short and then lost everything past the mark on save.
 */
class QueueEditSeedTextTest {
    @Test
    fun `a turn longer than the preview cap opens whole, not cut at the cap`() {
        val full = "L".repeat(200) + "TAIL THAT MUST SURVIVE"
        val item = queue(full, preview = full.take(200))

        val seed = queueEditSeedText(item)

        assertEquals(full, seed)
        assertEquals("TAIL THAT MUST SURVIVE", seed.substring(200))
    }

    @Test
    fun `a short turn is unaffected`() {
        assertEquals("short turn", queueEditSeedText(queue("short turn", preview = "short turn")))
    }

    @Test
    fun `an item carrying only a preview still opens on it`() {
        // Older decoders defaulted `messageText` to `previewText`; an empty message text must not
        // leave the editor blank when a preview is present.
        val item = QueueItem(
            id = "server-1",
            placement = "queued",
            previewText = "only a preview",
            messageText = "",
            content = JsonPrimitive("only a preview"),
        )
        assertEquals("only a preview", queueEditSeedText(item))
    }

    private fun queue(message: String, preview: String) = QueueItem(
        id = "server-1",
        placement = "queued",
        previewText = preview,
        messageText = message,
        content = JsonPrimitive(message),
    )
}