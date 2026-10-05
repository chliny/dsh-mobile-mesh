package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An edit replaces every block, so a warning must follow the authoritative structured content. */
class QueueEditAttachmentWarningTest {
    @Test
    fun `inbox projection warns for mixed text and image`() {
        assertTrue(queueEditLosesNonText(item("""{"id":"q","content":[{"type":"text","text":"hello"},{"type":"image","attachment":{"id":"a"}}]}""")))
    }

    @Test
    fun `legacy control snapshot warns for file and unknown blocks`() {
        assertTrue(queueEditLosesNonText(item("""{"id":"q","content":[{"type":"file","attachment":{}}]}""")))
        assertTrue(queueEditLosesNonText(item("""{"id":"q","content":[{"type":"future","payload":{}}]}""")))
    }

    @Test
    fun `literal attachment placeholder in plain text does not warn`() {
        assertFalse(queueEditLosesNonText(item("""{"id":"q","content":[{"type":"text","text":"[image] [file]"}]}""")))
        assertFalse(queueEditLosesNonText(item("""{"id":"q","content":[]}""")))
    }

    private fun item(content: String) = QueueItem(
        id = "q", placement = "queued", previewText = "[image]", messageText = "[image]",
        content = Json.parseToJsonElement(content),
    )
}
