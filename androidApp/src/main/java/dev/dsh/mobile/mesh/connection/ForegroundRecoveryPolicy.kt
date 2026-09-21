package dev.dsh.mobile.mesh.connection

/** Immutable foreground-resume facts used to choose the cheapest safe recovery action. */
internal data class ForegroundRecoveryFacts(
    val phase: ConnectionPhase,
    val hasActiveHost: Boolean,
    val recoveryInFlight: Boolean,
    val backgroundDurationMs: Long,
    val networkChanged: Boolean,
    val foregroundCheckPending: Boolean = false,
)

internal enum class ForegroundRecoveryAction {
    NONE,
    VERIFY,
    RECOVER,
}

internal fun shouldHandleLifecycleTransition(isForeground: Boolean, targetForeground: Boolean): Boolean =
    isForeground != targetForeground

internal fun shouldRenewCarrierAfterGenerationFailure(
    hasActiveHost: Boolean,
    appInForeground: Boolean,
    retainInBackground: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasActiveHost && (appInForeground || retainInBackground) && !recoveryInFlight

internal fun foregroundRecoveryAction(facts: ForegroundRecoveryFacts): ForegroundRecoveryAction = when {
    !facts.hasActiveHost || facts.recoveryInFlight || facts.foregroundCheckPending -> ForegroundRecoveryAction.NONE
    facts.networkChanged -> ForegroundRecoveryAction.RECOVER
    facts.phase != ConnectionPhase.CONNECTED -> ForegroundRecoveryAction.RECOVER
    facts.backgroundDurationMs >= FOREGROUND_DIRECT_RECOVERY_AFTER_MS -> ForegroundRecoveryAction.RECOVER
    facts.backgroundDurationMs < FOREGROUND_VERIFY_AFTER_MS -> ForegroundRecoveryAction.NONE
    else -> ForegroundRecoveryAction.VERIFY
}

internal fun shouldClearConnectedRecoveryPresentation(
    phase: ConnectionPhase,
    recoveryInFlight: Boolean,
    networkChanged: Boolean,
): Boolean = phase == ConnectionPhase.CONNECTED && !recoveryInFlight && !networkChanged

internal fun shouldProbePublishedGeneration(
    hasConnected: Boolean,
    recoveryOverlayVisible: Boolean,
    generationAlreadyNeedsProbe: Boolean,
): Boolean = hasConnected && (recoveryOverlayVisible || generationAlreadyNeedsProbe)

internal fun shouldPublishConnectedGeneration(
    generationReady: Boolean,
    lifecycleCurrent: Boolean,
): Boolean = generationReady && lifecycleCurrent

/** Avoid paying a probe RTT for short task switches; network changes still recover immediately. */
internal const val FOREGROUND_VERIFY_AFTER_MS = 5_000L
internal const val FOREGROUND_DIRECT_RECOVERY_AFTER_MS = 30_000L
internal const val FOREGROUND_RECOVERY_RETRY_DELAY_MS = 500L
internal const val FOREGROUND_RECOVERY_STEADY_RETRY_DELAY_MS = 5_000L
internal const val FOREGROUND_RECOVERY_FAST_ATTEMPTS = 3

internal fun shouldReArmOnReplacementNetwork(
    connected: Boolean,
    networkChanged: Boolean,
    hasDesiredHost: Boolean,
): Boolean = !connected && networkChanged && hasDesiredHost

internal fun recoveryRetryDelayMs(attempt: Int): Long =
    if (attempt < FOREGROUND_RECOVERY_FAST_ATTEMPTS) FOREGROUND_RECOVERY_RETRY_DELAY_MS
    else FOREGROUND_RECOVERY_STEADY_RETRY_DELAY_MS

internal fun shouldStartForegroundRecovery(
    action: ForegroundRecoveryAction,
    recoveryInFlight: Boolean,
): Boolean = action == ForegroundRecoveryAction.RECOVER && !recoveryInFlight

internal fun shouldDeferCarrierRecovery(
    operationInFlight: Boolean,
    recoveryInFlight: Boolean,
): Boolean = operationInFlight || recoveryInFlight

/** Presentation state must not block a new recovery unless a probe/operation is actually running. */
internal fun effectiveForegroundCheckPending(
    presentationPending: Boolean,
    recoveryInFlight: Boolean,
): Boolean = presentationPending && recoveryInFlight

/** Keep the recovery fence visible when backgrounding during a retained reconnect. */
internal fun shouldPreserveRecoveryPresentationOnBackground(
    keepConnectedInBackground: Boolean,
    phase: ConnectionPhase,
    operationInFlight: Boolean,
    retryScheduled: Boolean,
): Boolean = keepConnectedInBackground &&
    phase == ConnectionPhase.RECONNECTING &&
    (operationInFlight || retryScheduled)

/** Retained connected carriers must be probed once after every background hop. */
internal fun shouldRearmPublishedGenerationAfterBackground(
    keepConnectedInBackground: Boolean,
    hasConnected: Boolean,
): Boolean = keepConnectedInBackground && hasConnected

/** A failed probe during a rebuild must leave a retry request for the operation boundary. */
internal fun shouldArmRecoveryAfterProbeFailure(
    appInForeground: Boolean,
    operationInFlight: Boolean,
): Boolean = appInForeground && operationInFlight
