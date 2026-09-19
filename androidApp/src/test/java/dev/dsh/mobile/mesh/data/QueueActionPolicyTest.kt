package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueActionPolicyTest {
    private fun item(id: String) = QueueItem(
        id = id,
        placement = "queued",
        previewText = "queued",
        messageText = "queued",
        content = JsonPrimitive("queued"),
    )

    @Test
    fun `optimistic queue echo cannot be mutated before authoritative echo`() {
        assertFalse(canMutateQueueItem(item("local:req-1")))
        assertFalse(canSteerQueueItem(item("local:req-1"), running = true))
    }

    @Test
    fun `authoritative queue can steer only while running`() {
        assertTrue(canMutateQueueItem(item("message-1")))
        assertTrue(canSteerQueueItem(item("message-1"), running = true))
        assertFalse(canSteerQueueItem(item("message-1"), running = false))
    }
}
