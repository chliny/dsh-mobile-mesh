package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal fun shouldExpandChildrenOnSessionClick(
    childCount: Int,
    childrenExpanded: Boolean,
): Boolean = childCount > 0 && !childrenExpanded

internal fun shouldOpenSessionOnSessionClick(
    childCount: Int,
    isSubagent: Boolean,
): Boolean = childCount >= 0 && isSubagent

/** Queuing an open is not selection: a slow address lookup or an occupied switch worker
 * can leave the previous conversation selected. Keep the list visible until the requested
 * session actually becomes current, rather than presenting the old cache as the new chat.
 */
internal suspend fun awaitSessionSelected(selectedSessionId: StateFlow<String?>, requestedSessionId: String) {
    selectedSessionId.first { it == requestedSessionId }
}
