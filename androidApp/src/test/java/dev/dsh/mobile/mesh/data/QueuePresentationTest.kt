package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePresentationTest {
    @Test
    fun `keeps locally accepted queue rows until authoritative echo arrives`() {
        val authoritative = listOf(queue("host-1", "first"))
        val pending = listOf(queue("local:req-2", "second"))

        assertEquals(
            listOf("host-1", "local:req-2"),
            mergePendingQueue(authoritative, pending).map { it.id },
        )
    }

    @Test
    fun `removes a local row when matching authoritative text is present`() {
        val authoritative = listOf(queue("host-1", "same"))
        val pending = listOf(queue("local:req-1", "same"))

        assertEquals(listOf("host-1"), mergePendingQueue(authoritative, pending).map { it.id })
    }

    private fun queue(id: String, text: String) = QueueItem(
        id = id,
        placement = "queued",
        previewText = text,
        messageText = text,
        content = JsonPrimitive(text),
    )
}
