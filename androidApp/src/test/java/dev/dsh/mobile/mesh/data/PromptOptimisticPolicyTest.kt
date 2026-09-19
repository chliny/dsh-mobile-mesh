package dev.dsh.mobile.mesh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptOptimisticPolicyTest {
    @Test
    fun `idle turn renders accepted prompt in transcript`() {
        assertEquals(PromptOptimisticDisplay.TRANSCRIPT, promptOptimisticDisplay(running = false))
    }

    @Test
    fun `running turn renders accepted prompt in queue`() {
        assertEquals(PromptOptimisticDisplay.QUEUE, promptOptimisticDisplay(running = true))
    }

    @Test
    fun `new queue submission starting an idle turn is not counted as pending`() {
        assertEquals(false, shouldShowOptimisticQueue("queue", runningAtSubmission = false))
        assertEquals(true, shouldShowOptimisticQueue("queue", runningAtSubmission = true))
    }
}
