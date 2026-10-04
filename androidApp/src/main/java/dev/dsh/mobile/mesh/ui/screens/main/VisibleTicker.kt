package dev.dsh.mobile.mesh.ui.screens.main

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Stop display-only clock wakeups while the screen is stopped; update immediately on return. */
internal fun Lifecycle.startedStates(): Flow<Boolean> =
    currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()

internal suspend fun tickWhileVisible(
    active: Flow<Boolean>,
    intervalMs: Long = 1_000L,
    onTick: () -> Unit,
) {
    require(intervalMs > 0)
    active.collectLatest { started ->
        if (started) {
            while (true) {
                onTick()
                delay(intervalMs)
            }
        }
    }
}
