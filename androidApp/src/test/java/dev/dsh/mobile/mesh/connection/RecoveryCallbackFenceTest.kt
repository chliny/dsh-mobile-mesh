package dev.dsh.mobile.mesh.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCallbackFenceTest {
    @Test
    fun `replacement rejects every callback from previous owner`() {
        val fence = RecoveryCallbackFence()
        val old = fence.next()
        assertTrue(fence.accepts(old))
        val current = fence.next()
        assertFalse(fence.accepts(old))
        assertTrue(fence.accepts(current))
        fence.invalidate()
        assertFalse(fence.accepts(current))
    }

    @Test
    fun `invalidate waits for accepted callback before returning`() {
        val fence = RecoveryCallbackFence()
        val token = fence.next()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        val callback = thread {
            fence.runIfCurrent(token) {
                entered.countDown()
                release.await()
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        val invalidator = thread {
            fence.invalidate()
            invalidated.countDown()
        }
        assertFalse(invalidated.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        callback.join(1_000)
        invalidator.join(1_000)
        assertTrue(invalidated.await(1, TimeUnit.SECONDS))
        assertFalse(fence.accepts(token))
    }
}
