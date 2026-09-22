package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.dto.ContentBlock
import dev.dsh.mobile.mesh.core.wire.dto.InboxMessageSource
import dev.dsh.mobile.mesh.core.wire.dto.InboxMessageView
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameParserQueueTest {
    @Test
    fun `maps inbox user source rpc id and queue placement`() {
        val item = InboxMessageView(
            id = "queue-1",
            content = listOf(ContentBlock.Text("hello")),
            source = InboxMessageSource(kind = "user", rpcId = "rpc-1"),
        )

        val queue = inboxMessageToQueueItem(item, "queued")

        assertEquals("queued", queue.placement)
        assertEquals("rpc-1", queue.rpcId)
        assertEquals("hello", queue.messageText)
    }

    @Test
    fun `maps next step user and nonuser messages to their authoritative placements`() {
        val user = InboxMessageView(
            id = "steer-1",
            content = listOf(ContentBlock.Text("steer")),
            source = InboxMessageSource(kind = "user", rpcId = "rpc-2"),
        )
        val context = InboxMessageView(
            id = "context-1",
            content = listOf(ContentBlock.Text("context")),
            source = InboxMessageSource(kind = "system"),
        )

        assertEquals("steering", inboxMessageToQueueItem(user, "steering").placement)
        assertEquals("rpc-2", inboxMessageToQueueItem(user, "steering").rpcId)
        assertEquals("context", inboxMessageToQueueItem(context, "context").placement)
        assertEquals(null, inboxMessageToQueueItem(context, "context").rpcId)
    }
}
