package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** Poll only while the screen is visible; a return to foreground checks the cache immediately. */
internal suspend fun refreshWorkspaceFilesWhileActive(
    active: Flow<Boolean>,
    intervalMs: Long,
    isStale: () -> Boolean,
    refresh: () -> Unit,
) {
    require(intervalMs > 0)
    active.collectLatest { started ->
        if (started) {
            while (true) {
                if (isStale()) refresh()
                delay(intervalMs)
            }
        }
    }
}
