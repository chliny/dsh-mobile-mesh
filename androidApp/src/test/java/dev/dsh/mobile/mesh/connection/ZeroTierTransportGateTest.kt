package dev.dsh.mobile.mesh.connection

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ZeroTierTransportGateTest {
    @Test
    fun `reachable target ends the wait while libzt still reports the node offline`() = runBlocking {
        // Pixel 3 evidence: the ZeroTier path to the host answered 1.4s before zts_node_is_online.
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { false },
                scheduleDial = { onConnected -> onConnected() },
                dialAttempts = 6,
                timeoutMillis = 30_000,
                retryIntervalMillis = 300,
            )
        }
        assertEquals(ZERO_TIER_REMOTE_SIGNAL, signal)
    }

    @Test
    fun `online report still ends the wait when no dial can run`() = runBlocking {
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { true },
                scheduleDial = { },
                dialAttempts = 6,
                timeoutMillis = 30_000,
                retryIntervalMillis = 300,
            )
        }
        assertEquals(ZERO_TIER_ONLINE_SIGNAL, signal)
    }

    @Test
    fun `a dial that connects on a later attempt still ends the wait`() = runBlocking {
        val scheduled = AtomicInteger()
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { false },
                scheduleDial = { onConnected ->
                    if (scheduled.incrementAndGet() == 3) onConnected()
                },
                dialAttempts = 6,
                timeoutMillis = 30_000,
                retryIntervalMillis = 10,
            )
        }
        assertEquals(ZERO_TIER_REMOTE_SIGNAL, signal)
        assertEquals(3, scheduled.get())
    }

    @Test
    fun `a dial that cannot be scheduled does not replace the online fallback`() = runBlocking {
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { true },
                scheduleDial = { throw IOException("mesh executor rejected the dial") },
                dialAttempts = 3,
                timeoutMillis = 30_000,
                retryIntervalMillis = 10,
            )
        }
        assertEquals(ZERO_TIER_ONLINE_SIGNAL, signal)
    }

    @Test
    fun `an unreachable mesh times out instead of publishing`() = runBlocking {
        val samples = mutableListOf<String>()
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { false },
                scheduleDial = { },
                dialAttempts = 2,
                timeoutMillis = 150,
                retryIntervalMillis = 10,
                onSample = { samples.add(it) },
            )
        }
        assertEquals(ZERO_TIER_TIMEOUT_SIGNAL, signal)
        assertEquals(listOf("dial-attempt=1", "dial-attempt=2"), samples)
    }

    @Test
    fun `no target leaves the online report as the only readiness evidence`() = runBlocking {
        var scheduled = 0
        val signal = withTimeout(2_000) {
            awaitZeroTierTransportReady(
                awaitNodeOnline = { true },
                scheduleDial = { scheduled++ },
                dialAttempts = 0,
                timeoutMillis = 30_000,
                retryIntervalMillis = 10,
            )
        }
        assertEquals(ZERO_TIER_ONLINE_SIGNAL, signal)
        assertEquals(0, scheduled)
    }
}