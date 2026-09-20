package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePresentationTest {
    @Test
    fun `queue submission is pending until authoritative queued item exists`() {
        assertTrue(queueSubmissionPending(hasPendingPrompt = true, queue = emptyList()))
        assertFalse(queueSubmissionPending(hasPendingPrompt = true, queue = listOf("queued")))
        assertFalse(queueSubmissionPending(hasPendingPrompt = false, queue = emptyList()))
    }
}
