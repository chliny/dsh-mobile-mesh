package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.ContentBlock
import dev.dsh.mobile.mesh.core.wire.dto.QueuedMessage
import dev.dsh.mobile.mesh.core.wire.dto.QueuedInboxItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameParserQueueTest {
    @Test
    fun `uses queue frame rpc id when present`() {
        val item = QueuedInboxItem(
            id = "queue-1",
            placement = "queued",
            rpcId = "rpc-1",
            message = QueuedMessage(
                id = "message-1",
                content = listOf(ContentBlock.Text("hello")),
            ),
        )

        assertEquals("rpc-1", queuedInboxItemToQueueItem(item).rpcId)
    }
}
