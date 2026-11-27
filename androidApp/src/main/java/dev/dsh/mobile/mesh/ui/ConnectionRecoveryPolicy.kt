package dev.dsh.mobile.mesh.ui

import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectFailure

internal fun shouldShowConnectionRecoveryOverlay(
    hasConnected: Boolean,
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
    recoveryOverlayVisible: Boolean = false,
    showingConnectionList: Boolean = false,
): Boolean = !showingConnectionList && hasConnected && (
    phase != ConnectionPhase.CONNECTED || recoveryOverlayVisible || foregroundCheckPending
)

internal fun shouldRearmConnectionRecoveryOverlay(
    hasConnected: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasConnected && recoveryInFlight

/**
 * Whether an attempt to verify or rebuild an established connection is still running.
 *
 * The recovery scrim is this state's visible form. A page emitted while it runs is behind the
 * scrim, and the connection list that Back opens during the scrim stays the place to wait: the
 * attempt keeps retrying in the background, so leaving the list must remain impossible until it
 * settles. This deliberately excludes `DISCONNECTED`: an interrupted or paused attempt is no
 * longer connecting, and an authorization pause is resolved by its own prompt rather than a scrim.
 */
internal fun isConnectionRecoveryInFlight(
    phase: ConnectionPhase,
    foregroundCheckPending: Boolean,
    recoveryOverlayVisible: Boolean,
): Boolean = phase == ConnectionPhase.CONNECTING ||
    phase == ConnectionPhase.RECONNECTING ||
    foregroundCheckPending ||
    recoveryOverlayVisible

/**
 * Whether a recovery attempt has to hand the screen to the connection list because it needs a
 * login the scrim cannot offer.
 *
 * A cookie that expires mid-recovery makes the readiness handshake answer 401, which the manager
 * reports as [ConnectFailure.Unauthenticated]. Both things that can resolve it — the failure block
 * with its Sign in action and the launch-token prompt — are owned by the connection list, while the
 * scrim covers every other page and consumes input. The retry loop keeps retrying on its own, so
 * without this the app would spin behind an opaque spinner with no way to enter a token. This is
 * the same navigation as Back during the scrim; the attempt stays owned by the list.
 */
internal fun shouldShowConnectionListForRecoveryLogin(
    recoveryOverlayVisible: Boolean,
    failure: ConnectFailure?,
): Boolean = recoveryOverlayVisible && failure is ConnectFailure.Unauthenticated
