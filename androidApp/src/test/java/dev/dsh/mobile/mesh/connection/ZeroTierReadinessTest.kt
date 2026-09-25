package dev.dsh.mobile.mesh.connection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierReadinessTest {
    @Test
    fun `online offline online waits for actual online state on retained node`() {
        val readiness = ZeroTierReadiness()
        val online = AtomicBoolean(true)
        readiness.nodeOnline()
        readiness.nodeOffline()
        online.set(false)
        assertFalse(readiness.awaitNodeOnline(10, TimeUnit.MILLISECONDS, online::get))

        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        try {
            val result = executor.submit<Boolean> {
                waiting.countDown()
                readiness.awaitNodeOnline(1, TimeUnit.SECONDS, online::get)
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            online.set(true)
            readiness.nodeOnline()
            assertTrue(result.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `network ready callback without assigned address cannot reuse stale readiness`() {
        val readiness = ZeroTierReadiness()
        val networkId = 123L
        val assigned = AtomicBoolean(false)
        readiness.networkReady(networkId)
        assertFalse(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
        readiness.networkUnavailable(networkId)
        readiness.networkReady(networkId)
        assertFalse(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
        val executor = Executors.newSingleThreadExecutor()
        val waiting = CountDownLatch(1)
        try {
            val result = executor.submit<Boolean> {
                waiting.countDown()
                readiness.awaitNetworkAddress(networkId, 1, TimeUnit.SECONDS, assigned::get)
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assigned.set(true)
            readiness.networkReady(networkId)
            assertTrue(result.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `network denial can recover after network down and ready address`() {
        val readiness = ZeroTierReadiness()
        val networkId = 123L
        readiness.networkError(networkId, MeshAuthorizationPending("denied"))
        readiness.networkUnavailable(networkId)
        val assigned = AtomicBoolean(true)
        readiness.networkReady(networkId)
        assertTrue(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
    }
}
