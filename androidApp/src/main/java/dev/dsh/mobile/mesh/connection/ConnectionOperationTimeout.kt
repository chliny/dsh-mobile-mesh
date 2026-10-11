package dev.dsh.mobile.mesh.connection

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A connection-owned deadline expired; unlike caller cancellation, this is a retryable failure. */
internal class ConnectionOperationTimeoutException(
    timeoutMs: Long,
    cause: TimeoutCancellationException,
) : Exception("Connection operation exceeded ${timeoutMs}ms", cause)

/** Convert only this operation's timeout into failure; preserve cancellation from its parent. */
internal suspend fun <T> withConnectionOperationTimeout(
    timeoutMs: Long,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (timeout: TimeoutCancellationException) {
    // An enclosing lifecycle/job timeout must remain cancellation, not start a stale retry.
    currentCoroutineContext().ensureActive()
    throw ConnectionOperationTimeoutException(timeoutMs, timeout)
}
