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

internal const val FOREGROUND_VERIFY_AFTER_MS = 1_000L

/**
 * A non-connected foreground state must always re-arm recovery when no job is active.
 * Time-based cooldowns are unsafe here: backgrounding cancels delayed retries, so suppressing the
 * resume signal can otherwise leave the UI permanently blocked in RECONNECTING.
 */
internal fun shouldStartForegroundRecovery(
    action: ForegroundRecoveryAction,
    recoveryInFlight: Boolean,
): Boolean = action == ForegroundRecoveryAction.RECOVER && !recoveryInFlight
