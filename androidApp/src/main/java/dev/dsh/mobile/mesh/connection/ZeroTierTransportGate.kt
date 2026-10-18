package dev.dsh.mobile.mesh.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal const val ZERO_TIER_ONLINE_SIGNAL = "node-online"
internal const val ZERO_TIER_REMOTE_SIGNAL = "remote-reachable"
internal const val ZERO_TIER_TIMEOUT_SIGNAL = "timeout"

/**
 * When a target is configured, only a completed TCP dial proves end-to-end readiness.
 * Without a resolvable target, the node-online event remains the fallback.
 *
 * libzt only reports a node online after its root handshake completes. Measured on a Pixel 3 that
 * returned to the foreground with background retention disabled, the ZeroTier TCP path to the host
 * already carried a connection while `zts_node_is_online()` was still false, and the online report
 * landed 1.4s later. A completed dial is direct evidence that the mesh transports this connection
 * needs, so it ends the wait. The online report is insufficient when a known target is black-holed:
 * otherwise each false-positive causes an 8-second SSH banner timeout before another attempt.
 *
 * [scheduleDial] must start its work outside this caller's coroutine: a libzt connect blocks for
 * up to its cold/recovery budget, and waiting for that call to return could be slower than the
 * online report this gate can use. [onConnected] is invoked from whatever thread the dial used.
 *
 * Returns [ZERO_TIER_ONLINE_SIGNAL], [ZERO_TIER_REMOTE_SIGNAL] or [ZERO_TIER_TIMEOUT_SIGNAL].
 */
internal suspend fun awaitZeroTierTransportReady(
    awaitNodeOnline: suspend () -> Boolean,
    scheduleDial: (onConnected: () -> Unit) -> Unit,
    dialAttempts: Int,
    timeoutMillis: Long,
    retryIntervalMillis: Long,
    requireTargetDial: Boolean = false,
    sampleIntervalMillis: Long = 1_000L,
    onSample: (String) -> Unit = {},
): String = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val signal = AtomicReference(ZERO_TIER_TIMEOUT_SIGNAL)
    fun publish(next: String) {
        signal.compareAndSet(ZERO_TIER_TIMEOUT_SIGNAL, next)
        ready.complete(Unit)
    }
    val onlineWatcher = launch(Dispatchers.IO) {
        if (awaitNodeOnline() && !requireTargetDial) publish(ZERO_TIER_ONLINE_SIGNAL)
    }
    val dialWatcher = launch(Dispatchers.IO) {
        var attempt = 0
        while (!ready.isCompleted) {
            attempt++
            if (attempt <= dialAttempts) {
                onSample("dial-attempt=$attempt")
                try {
                    scheduleDial { publish(ZERO_TIER_REMOTE_SIGNAL) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    onSample("dial-schedule-failed=${error.javaClass.simpleName}")
                }
                delay(retryIntervalMillis)
            } else {
                // Keep probing after the initial burst: a black-holed target may become reachable
                // while the app is foregrounded. Permits bound the number of concurrent native dials.
                onSample("wait-pending")
                if (requireTargetDial) {
                    try {
                        scheduleDial { publish(ZERO_TIER_REMOTE_SIGNAL) }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        onSample("dial-schedule-failed=${error.javaClass.simpleName}")
                    }
                }
                delay(sampleIntervalMillis)
            }
        }
    }
    try {
        withTimeoutOrNull(timeoutMillis) { ready.await() }
        signal.get()
    } finally {
        onlineWatcher.cancel()
        dialWatcher.cancel()
    }
}