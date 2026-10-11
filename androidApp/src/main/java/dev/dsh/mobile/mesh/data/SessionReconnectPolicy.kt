package dev.dsh.mobile.mesh.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Restore the live transcript first; optional host catalogs must not hold up this collector. */
internal suspend fun recoverConnectedSession(
    scope: CoroutineScope,
    reopen: suspend () -> Unit,
    refreshCatalog: suspend () -> Unit,
) {
    reopen()
    scope.launch { refreshCatalog() }
}

/** Host-scoped catalogs are fetched at most once per generation unless the request failed. */
internal fun shouldRefreshPermissionCatalog(generationId: String, requestedGenerationId: String?): Boolean =
    generationId != requestedGenerationId

/** A reconnect must reopen the selected session's generation-bound follow stream. */
internal fun shouldReopenSessionAfterReconnect(
    hasSelectedSession: Boolean,
    hasConnectionGeneration: Boolean,
): Boolean = hasSelectedSession && hasConnectionGeneration
