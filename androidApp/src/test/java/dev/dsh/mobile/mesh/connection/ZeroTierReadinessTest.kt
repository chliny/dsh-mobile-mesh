package dev.dsh.mobile.mesh.connection

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroTierReadinessTest {
    @Test
    fun `online offline online waits for actual online state on retained node`() = runBlocking {
        val readiness = ZeroTierReadiness()
        val online = AtomicBoolean(true)
        readiness.nodeOnline()
        readiness.nodeOffline()
        online.set(false)
        assertFalse(readiness.awaitNodeOnline(10, TimeUnit.MILLISECONDS, online::get))
        val result = async { readiness.awaitNodeOnline(1, TimeUnit.SECONDS, online::get) }
        yield()
        online.set(true)
        readiness.nodeOnline()
        assertTrue(withTimeout(1_000) { result.await() })
    }

    @Test
    fun `network ready callback without assigned address cannot reuse stale readiness`() = runBlocking {
        val readiness = ZeroTierReadiness()
        val networkId = 123L
        val assigned = AtomicBoolean(false)
        readiness.networkReady(networkId)
        assertFalse(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
        readiness.networkUnavailable(networkId)
        readiness.networkReady(networkId)
        assertFalse(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
        val result = async { readiness.awaitNetworkAddress(networkId, 1, TimeUnit.SECONDS, assigned::get) }
        yield()
        assigned.set(true)
        readiness.networkReady(networkId)
        assertTrue(withTimeout(1_000) { result.await() })
    }

    @Test
    fun `network denial can recover after network down and ready address`() = runBlocking {
        val readiness = ZeroTierReadiness()
        val networkId = 123L
        readiness.networkError(networkId, MeshAuthorizationPending("denied"))
        readiness.networkUnavailable(networkId)
        val assigned = AtomicBoolean(true)
        readiness.networkReady(networkId)
        assertTrue(readiness.awaitNetworkAddress(networkId, 10, TimeUnit.MILLISECONDS, assigned::get))
    }

    @Test
    fun `cancel waiting online without blocking callback or next waiter`() = runBlocking {
        val readiness = ZeroTierReadiness()
        val online = AtomicBoolean(false)
        val waiter = async { readiness.awaitNodeOnline(30, TimeUnit.SECONDS, online::get) }
        yield()
        waiter.cancel()
        withTimeout(1_000) { waiter.join() }
        assertTrue(waiter.isCancelled)
        online.set(true)
        readiness.nodeOnline()
        assertTrue(readiness.awaitNodeOnline(1, TimeUnit.SECONDS, online::get))
    }

    @Test
    fun `stop invalidates suspended start and cannot publish a relay after readiness`() = runBlocking { supervisorScope {
        val lock = Any()
        val generation = ZeroTierGeneration()
        val readiness = ZeroTierReadiness()
        val assigned = AtomicBoolean(false)
        var published = false
        val token = synchronized(lock) { generation.next() }
        val start = async {
            readiness.awaitNetworkAddress(123, 15, TimeUnit.SECONDS, assigned::get)
            synchronized(lock) {
                generation.checkCurrent(token)
                published = true
            }
        }
        yield() // start is suspended in its cancellable address wait, outside the ownership lock.
        synchronized(lock) { generation.next() } // stop can acquire the same lock immediately.
        assigned.set(true)
        readiness.networkReady(123)
        val failure = runCatching { withTimeout(1_000) { start.await() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(published)
    } }

    @Test
    fun `cancel waiting address leaves denial and future waiters intact`() = runBlocking {
        val readiness = ZeroTierReadiness()
        val assigned = AtomicBoolean(false)
        val waiter = async { readiness.awaitNetworkAddress(123, 15, TimeUnit.SECONDS, assigned::get) }
        yield()
        waiter.cancel()
        withTimeout(1_000) { waiter.join() }
        assertTrue(waiter.isCancelled)
        readiness.networkError(123, MeshAuthorizationPending("denied"))
        val failure = runCatching { readiness.awaitNetworkAddress(123, 1, TimeUnit.SECONDS, assigned::get) }.exceptionOrNull()
        assertTrue(failure is MeshAuthorizationPending)
    }
}
