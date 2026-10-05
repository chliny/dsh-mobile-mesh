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
 * Ends the mesh readiness wait on whichever evidence arrives first.
 *
 * libzt only reports a node online after its root handshake completes. Measured on a Pixel 3 that
 * returned to the foreground with background retention disabled, the ZeroTier TCP path to the host
 * already carried a connection while `zts_node_is_online()` was still false, and the online report
 * landed 1.4s later. A completed dial is direct evidence that the mesh transports this connection
 * needs, so it ends the wait; the online report stays as the fallback when no dial can run.
 *
 * [scheduleDial] must start its work outside this caller's coroutine: a libzt connect blocks for up
 * to 30 seconds, and a wait that only ends when the dial returns would be slower than the online
 * report it is meant to replace. [onConnected] is invoked from whatever thread the dial used.
 *
 * Returns [ZERO_TIER_ONLINE_SIGNAL], [ZERO_TIER_REMOTE_SIGNAL] or [ZERO_TIER_TIMEOUT_SIGNAL].
 */
internal suspend fun awaitZeroTierTransportReady(
    awaitNodeOnline: suspend () -> Boolean,
    scheduleDial: (onConnected: () -> Unit) -> Unit,
    dialAttempts: Int,
    timeoutMillis: Long,
    retryIntervalMillis: Long,
    onSample: (String) -> Unit = {},
): String = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val signal = AtomicReference(ZERO_TIER_TIMEOUT_SIGNAL)
    fun publish(next: String) {
        signal.compareAndSet(ZERO_TIER_TIMEOUT_SIGNAL, next)
        ready.complete(Unit)
    }
    val onlineWatcher = launch(Dispatchers.IO) {
        if (awaitNodeOnline()) publish(ZERO_TIER_ONLINE_SIGNAL)
    }
    val dialWatcher = launch(Dispatchers.IO) {
        repeat(dialAttempts) { attempt ->
            if (ready.isCompleted) return@launch
            onSample("dial-attempt=${attempt + 1}")
            try {
                scheduleDial { publish(ZERO_TIER_REMOTE_SIGNAL) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                onSample("dial-schedule-failed=${error.javaClass.simpleName}")
            }
            delay(retryIntervalMillis)
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