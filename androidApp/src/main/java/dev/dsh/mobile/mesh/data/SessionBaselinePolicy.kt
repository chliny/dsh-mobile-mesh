package dev.dsh.mobile.mesh.data

/** A newly published connected host gets its own session-list baseline; same-host reconnects reuse cache. */
internal fun shouldRefreshForConnectedGeneration(
    generationHostId: String?,
    baselineHostId: String?,
): Boolean = generationHostId != null && generationHostId != baselineHostId

/** Reject responses from an earlier request generation when a connection handover races it. */
internal fun isCurrentHostResult(
    expectedGenerationId: String,
    currentGenerationId: String?,
    expectedHostId: String?,
    currentHostId: String?,
): Boolean = expectedGenerationId == currentGenerationId && expectedHostId == currentHostId
