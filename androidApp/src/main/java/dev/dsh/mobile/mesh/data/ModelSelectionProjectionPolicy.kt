package dev.dsh.mobile.mesh.data

/** Apply a live model-selection projection by session, even while the transcript fold is rebuilding. */
internal fun shouldApplyModelSelectionProjection(
    frameSessionId: String,
    currentSessionId: String?,
    currentSeq: Int?,
    incomingSeq: Int,
): Boolean = frameSessionId == currentSessionId && (currentSeq == null || incomingSeq >= currentSeq)
