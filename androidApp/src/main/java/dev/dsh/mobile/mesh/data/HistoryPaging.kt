package dev.dsh.mobile.mesh.data

/** Whether an older-page response still belongs to the selected transcript. */
internal fun shouldSchedulePageRebuild(pageSessionId: String?, currentSessionId: String?): Boolean =
    pageSessionId != null && pageSessionId == currentSessionId
