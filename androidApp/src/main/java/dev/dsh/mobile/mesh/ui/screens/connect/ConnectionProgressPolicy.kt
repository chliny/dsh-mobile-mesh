package dev.dsh.mobile.mesh.ui.screens.connect

import dev.dsh.mobile.mesh.connection.ConnectStage

/** The connection list deliberately keeps progress on the selected row only. */
internal fun shouldShowConnectionProgress(stage: ConnectStage): Boolean =
    stage != ConnectStage.Idle && stage != ConnectStage.Connected
