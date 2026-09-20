package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueActionPolicyTest {
    @Test
    fun `authoritative queued rows keep actions for one or many messages`() {
        assertTrue(canMutateQueueItem(queue("server-1")))
        assertTrue(canMutateQueueItem(queue("server-2")))
    }

    private fun queue(id: String) = QueueItem(
        id = id,
        placement = "queued",
        previewText = "message",
        messageText = "message",
        content = JsonPrimitive("message"),
    )
}
