package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.session.ToolResultNode
import dev.dsh.mobile.mesh.ui.components.DisclosureState
import dev.dsh.mobile.mesh.ui.components.ToolCardView

/** A successful transport response can still report a failing terminal exit status. */
internal fun toolDisclosureState(card: ToolCardView, result: ToolResultNode?, running: Boolean): DisclosureState = when {
    result?.isError == true -> DisclosureState.Error
    card is ToolCardView.TerminalCard && result != null &&
        (card.exitCode != null && card.exitCode != 0 || card.signal != null) -> DisclosureState.Error
    result == null && running -> DisclosureState.Running
    else -> DisclosureState.Idle
}
