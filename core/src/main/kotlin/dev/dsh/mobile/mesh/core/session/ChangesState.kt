package dev.dsh.mobile.mesh.core.session

import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiff
import dev.dsh.mobile.mesh.core.wire.dto.ChangesSummary

/** One changed-files review associated with a durable workspace/changes event. */
data class ChangesReviewState(
    val sessionId: String,
    val seq: Long,
    val summary: ChangesSummary,
    val selectedIndex: Int? = null,
    val diff: ChangesDiff? = null,
)
