package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.session.QueueItem
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptQueueReconciliationTest {
    private fun queued(requestId: String) = QueueItem(
        id = "inbox:$requestId",
        placement = "queued",
        previewText = "pending",
        content = JsonNull,
        rpcId = requestId,
    )

    @Test
    fun `queue arriving after RPC identifies local transcript echo for removal`() {
        val reconciliation = PromptQueueReconciliation()
        reconciliation.begin("request-1")
        assertTrue(reconciliation.shouldRenderTranscript("request-1", "queue", false, emptyList()))
        assertTrue("request-1" in reconciliation.observe(listOf(queued("request-1"))))
    }

    @Test
    fun `queue arriving before RPC response still prevents transcript echo`() {
        val reconciliation = PromptQueueReconciliation()
        reconciliation.begin("request-1")
        assertTrue("request-1" in reconciliation.observe(listOf(queued("request-1"))))
        // The pending item may already have been consumed by the time the slow RPC returns.
        reconciliation.observe(emptyList())
        assertFalse(reconciliation.shouldRenderTranscript("request-1", "queue", false, emptyList()))
        assertTrue(reconciliation.shouldRenderTranscript("request-1", "queue", false, emptyList()))
    }

    @Test
    fun `other clients pending input cannot suppress this requests transcript`() {
        val reconciliation = PromptQueueReconciliation()
        reconciliation.begin("request-1")
        reconciliation.observe(listOf(queued("request-2")))
        assertTrue(reconciliation.shouldRenderTranscript("request-1", "queue", false, listOf(queued("request-2"))))
    }

    @Test
    fun `current server queue prevents transcript when request began before subscription`() {
        val reconciliation = PromptQueueReconciliation()
        reconciliation.begin("request-1")
        assertFalse(reconciliation.shouldRenderTranscript("request-1", "queue", false, listOf(queued("request-1"))))
    }

    @Test
    fun `running queue submission never creates a transcript echo`() {
        val reconciliation = PromptQueueReconciliation()
        reconciliation.begin("request-1")
        assertFalse(reconciliation.shouldRenderTranscript("request-1", "queue", true, emptyList()))
    }
}
