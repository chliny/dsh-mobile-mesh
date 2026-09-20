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

/** A failed generation means the physical carrier may be stale, even if the loop can retry its mux. */
internal fun shouldRenewCarrierAfterGenerationFailure(
    hasActiveHost: Boolean,
    appInForeground: Boolean,
    retainInBackground: Boolean,
    recoveryInFlight: Boolean,
): Boolean = hasActiveHost && (appInForeground || retainInBackground) && !recoveryInFlight

/**
 * Brief task switches keep the live generation without a visible verification cycle. Longer
 * background stays and network handovers actively verify or recover before accepting user input.
 */
internal fun foregroundRecoveryAction(facts: ForegroundRecoveryFacts): ForegroundRecoveryAction = when {
    !facts.hasActiveHost || facts.recoveryInFlight || facts.foregroundCheckPending -> ForegroundRecoveryAction.NONE
    facts.networkChanged -> ForegroundRecoveryAction.RECOVER
    facts.phase != ConnectionPhase.CONNECTED -> ForegroundRecoveryAction.RECOVER
    facts.backgroundDurationMs >= FOREGROUND_DIRECT_RECOVERY_AFTER_MS -> ForegroundRecoveryAction.RECOVER
    facts.backgroundDurationMs < FOREGROUND_VERIFY_AFTER_MS -> ForegroundRecoveryAction.NONE
    else -> ForegroundRecoveryAction.VERIFY
}

/** A stale pending latch must not keep a healthy connected screen covered forever. */
internal fun shouldClearConnectedRecoveryPresentation(
    phase: ConnectionPhase,
    recoveryInFlight: Boolean,
    networkChanged: Boolean,
): Boolean = phase == ConnectionPhase.CONNECTED && !recoveryInFlight && !networkChanged

/** A live generation must be published before the UI can claim an end-to-end connection. */
internal fun shouldPublishConnectedGeneration(
    generationReady: Boolean,
    lifecycleCurrent: Boolean,
): Boolean = generationReady && lifecycleCurrent

/** Any completed background transition gets a foreground liveness fence; short switches are not exempt. */
internal const val FOREGROUND_VERIFY_AFTER_MS = 0L
/** A long-suspended retained carrier is more likely stale; rebuild it directly instead of spending
 * another probe timeout before starting the same transport recovery. */
internal const val FOREGROUND_DIRECT_RECOVERY_AFTER_MS = 30_000L
internal const val FOREGROUND_RECOVERY_RETRY_DELAY_MS = 1_500L
/** Slower but unbounded cadence used once the quick retries are exhausted. */
internal const val FOREGROUND_RECOVERY_STEADY_RETRY_DELAY_MS = 8_000L
/** Number of fast retries before a stranded foreground recovery keeps trying more slowly. */
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
