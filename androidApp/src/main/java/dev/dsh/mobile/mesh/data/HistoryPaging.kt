package dev.dsh.mobile.mesh.data

/** Whether an older-page response still belongs to the selected transcript. */
internal fun shouldSchedulePageRebuild(pageSessionId: String?, currentSessionId: String?): Boolean =
    pageSessionId != null && pageSessionId == currentSessionId

/** The former 8s application deadline interrupted slow durable reads before the carrier budget. */
internal const val SESSION_PAGE_TIMEOUT_MS = 60_000L

/** Do not install an application deadline shorter than the unary carrier's own read timeout. */
internal fun pageTimeoutForTransport(requestedTimeoutMs: Long): Long =
    requestedTimeoutMs.coerceAtLeast(30_000L)
