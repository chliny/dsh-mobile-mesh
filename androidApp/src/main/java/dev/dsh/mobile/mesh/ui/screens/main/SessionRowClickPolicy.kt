package dev.dsh.mobile.mesh.ui.screens.main

internal fun shouldExpandChildrenOnSessionClick(
    childCount: Int,
    childrenExpanded: Boolean,
): Boolean = childCount > 0 && !childrenExpanded

internal fun shouldOpenSessionOnSessionClick(
    childCount: Int,
    isSubagent: Boolean,
): Boolean = childCount >= 0 && isSubagent

/** The drawer must remain alive until the address lookup/session switch has been queued. */
internal fun shouldCloseDrawerAfterSessionOpen(): Boolean = true
