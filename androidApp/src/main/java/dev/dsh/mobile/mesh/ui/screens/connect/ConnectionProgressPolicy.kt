package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectStage

/** The connection list deliberately keeps progress on the selected row only. */
internal fun shouldShowConnectionProgress(stage: ConnectStage): Boolean =
    stage != ConnectStage.Idle && stage != ConnectStage.Connected

/** System back must not leave the list while a connect/reconnect attempt is still owned by it. */
internal fun shouldAllowConnectionListBack(connecting: Boolean): Boolean = !connecting
