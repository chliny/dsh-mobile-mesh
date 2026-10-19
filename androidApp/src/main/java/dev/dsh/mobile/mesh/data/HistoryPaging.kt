package dev.dsh.mobile.mesh.data

/** One page request is fenced to the selected transcript and its follow generation. */
internal data class HistoryPageFence(
    val sessionId: String,
    val generation: Long,
    val throughSeq: Int,
    val beforeSeq: Long?,
)

/** Only the generation that owns the spinner may clear it when its request settles. */
internal fun shouldReleaseHistoryPageLoading(requestGeneration: Long, loadingGeneration: Long?): Boolean =
    requestGeneration == loadingGeneration

/** Reject a delayed page after selection, opening cursor, or oldest loaded event changes. */
internal fun shouldApplyHistoryPage(
    request: HistoryPageFence,
    currentSessionId: String?,
    currentGeneration: Long,
    currentCursor: Int?,
    currentOldestSeq: Long?,
): Boolean = request.sessionId == currentSessionId &&
    request.generation == currentGeneration &&
    request.throughSeq == currentCursor &&
    request.beforeSeq == currentOldestSeq

/** Whether an older-page response still belongs to the selected transcript. */
internal fun shouldSchedulePageRebuild(pageSessionId: String?, currentSessionId: String?): Boolean =
    pageSessionId != null && pageSessionId == currentSessionId

/** The former 8s application deadline interrupted slow durable reads before the carrier budget. */
internal const val SESSION_PAGE_TIMEOUT_MS = 60_000L

/**
 * How long a reader-driven pull waits for the follow opening to publish its pinning cursor.
 *
 * The opening snapshot normally lands in milliseconds. Waiting is what keeps a pull made right
 * after opening a session (or a subagent) from silently doing nothing until the reader taps the
 * paging button again.
 */
internal const val FOLLOW_OPENING_WAIT_MS = 5_000L

/**
 * A backward page is pinned to the follow opening cursor. When that cursor is not published yet the
 * request is recoverable — wait for the opening instead of dropping the deliberate pull.
 */
internal fun shouldWaitForFollowOpening(
    hasMore: Boolean,
    hasSession: Boolean,
    hasAddress: Boolean,
    hasCursor: Boolean,
    pageRequestInFlight: Boolean,
): Boolean = hasMore && hasSession && hasAddress && !hasCursor && !pageRequestInFlight

/** Do not install an application deadline shorter than the unary carrier's own read timeout. */
internal fun pageTimeoutForTransport(requestedTimeoutMs: Long): Long =
    requestedTimeoutMs.coerceAtLeast(30_000L)
