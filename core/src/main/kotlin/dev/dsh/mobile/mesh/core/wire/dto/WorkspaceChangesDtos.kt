package dev.dsh.mobile.mesh.core.wire.dto

import kotlinx.serialization.Serializable

/** One file announced by a workspace/changes event. */
@Serializable
data class ChangedFile(
    val path: String,
    val display: String,
    val added: Int,
    val deleted: Int,
    val binary: Boolean = false,
    val oversized: Boolean = false,
)

/** Summary returned for one workspace/changes event. */
@Serializable
data class ChangesSummary(
    val turn: Int,
    val files: List<ChangedFile> = emptyList(),
    val total: Int,
    val added: Int,
    val deleted: Int,
)

/** One unified-diff hunk. */
@Serializable
data class ChangesDiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String> = emptyList(),
)

/** Wire response for changes.diff; optional fields are absent for binary/oversized files. */
@Serializable
data class ChangesDiffResponse(
    val kind: String,
    val path: String,
    val display: String,
    val before: Boolean? = null,
    val after: Boolean? = null,
    val coarse: Boolean? = null,
    val hunks: List<ChangesDiffHunk> = emptyList(),
)

/** Text, binary, or oversized result returned by changes.diff. */
sealed class ChangesDiff {
    abstract val kind: String
    abstract val path: String
    abstract val display: String

    data class Text(
        override val path: String,
        override val display: String,
        val before: Boolean,
        val after: Boolean,
        val coarse: Boolean,
        val hunks: List<ChangesDiffHunk> = emptyList(),
    ) : ChangesDiff() {
        override val kind: String = "text"
    }

    data class Unavailable(
        override val kind: String,
        override val path: String,
        override val display: String,
    ) : ChangesDiff()
}

/** One changed-file review loaded for the selected session turn. */
data class ChangesReview(
    val sessionId: String,
    val seq: Long,
    val summary: ChangesSummary,
    val selectedIndex: Int? = null,
    val diff: ChangesDiff? = null,
)
