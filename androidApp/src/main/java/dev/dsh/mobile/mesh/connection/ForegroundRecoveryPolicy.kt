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

/**
 * Brief task switches keep the live generation without a visible verification cycle. Longer
 * background stays and network handovers actively verify or recover before accepting user input.
 */
internal fun foregroundRecoveryAction(facts: ForegroundRecoveryFacts): ForegroundRecoveryAction = when {
    !facts.hasActiveHost || facts.recoveryInFlight || facts.foregroundCheckPending -> ForegroundRecoveryAction.NONE
    facts.networkChanged -> ForegroundRecoveryAction.RECOVER
    facts.phase != ConnectionPhase.CONNECTED -> ForegroundRecoveryAction.RECOVER
    facts.backgroundDurationMs < FOREGROUND_VERIFY_AFTER_MS -> ForegroundRecoveryAction.NONE
    else -> ForegroundRecoveryAction.VERIFY
}

/** A live generation must be published before the UI can claim an end-to-end connection. */
internal fun shouldPublishConnectedGeneration(
    generationReady: Boolean,
    lifecycleCurrent: Boolean,
): Boolean = generationReady && lifecycleCurrent

internal const val FOREGROUND_VERIFY_AFTER_MS = 1_000L

internal const val FOREGROUND_RECOVERY_RETRY_DELAY_MS = 1_500L
/** Slower but unbounded cadence used once the quick retries are exhausted. */
internal const val FOREGROUND_RECOVERY_STEADY_RETRY_DELAY_MS = 8_000L
/** Number of fast retries before a stranded foreground recovery keeps trying more slowly. */
internal const val FOREGROUND_RECOVERY_FAST_ATTEMPTS = 3

/**
 * A replacement network is the event that unblocks a stranded recovery: the quick attempts usually
 * run while the old carrier is still the only one the process can see. Re-arm whenever a desired
 * host exists and the session is not currently connected, so the app does not stay disconnected
 * until the user taps something. `previous == null` counts as a change: the network can disappear
 * entirely (Wi-Fi off with no cellular), and its return is still the moment recovery becomes possible.
 */
internal fun shouldReArmOnReplacementNetwork(
    connected: Boolean,
    networkChanged: Boolean,
    hasDesiredHost: Boolean,
): Boolean = !connected && networkChanged && hasDesiredHost

internal fun recoveryRetryDelayMs(attempt: Int): Long =
    if (attempt < FOREGROUND_RECOVERY_FAST_ATTEMPTS) FOREGROUND_RECOVERY_RETRY_DELAY_MS
    else FOREGROUND_RECOVERY_STEADY_RETRY_DELAY_MS

/**
 * A non-connected foreground state must always re-arm recovery when no job is active.
 * Time-based cooldowns are unsafe here: backgrounding cancels delayed retries, so suppressing the
 * resume signal can otherwise leave the UI permanently blocked in RECONNECTING.
 */
internal fun shouldStartForegroundRecovery(
    action: ForegroundRecoveryAction,
    recoveryInFlight: Boolean,
): Boolean = action == ForegroundRecoveryAction.RECOVER && !recoveryInFlight
