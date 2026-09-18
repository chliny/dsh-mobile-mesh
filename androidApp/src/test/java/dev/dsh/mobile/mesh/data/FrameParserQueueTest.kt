package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.ContentBlock
import dev.dsh.mobile.mesh.core.wire.dto.MessageData
import dev.dsh.mobile.mesh.core.wire.dto.MessageSource
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
            message = MessageData(
                id = "message-1",
                role = "user",
                content = listOf(ContentBlock.Text("hello")),
                source = MessageSource(kind = "user"),
            ),
        )

        assertEquals("rpc-1", queuedInboxItemToQueueItem(item).rpcId)
    }
}
