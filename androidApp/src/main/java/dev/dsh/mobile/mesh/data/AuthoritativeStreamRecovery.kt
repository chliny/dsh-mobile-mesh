package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RemoteStreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement

/** Reopen a generation's authoritative stream after a transient logical-stream failure. */
internal suspend fun followAuthoritativeStream(
    current: () -> Boolean,
    open: () -> Flow<JsonElement>,
    onItem: (JsonElement) -> Unit,
    onFailure: (Throwable) -> Unit,
    waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    var retryMs = 1_000L
    while (current() && currentCoroutineContext().isActive) {
        var received = false
        try {
            open().collect { item ->
                if (!current()) return@collect
                received = true
                onItem(item)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!current()) break
            onFailure(failure)
            if (failure is RemoteStreamException && failure.error.code == "gateway/invocation-unavailable" && !failure.carrier) break
        }
        if (!current()) break
        waitBeforeRetry(retryMs)
        retryMs = if (received) 1_000L else (retryMs * 2).coerceAtMost(8_000L)
    }
}
