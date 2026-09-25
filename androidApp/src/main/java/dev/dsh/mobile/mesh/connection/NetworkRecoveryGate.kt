package dev.dsh.mobile.mesh.connection

/**
 * Keeps a network-handover recovery request armed until a transport operation can actually start.
 * Android may announce the replacement network while an older recovery is still unwinding; dropping
 * that event is what leaves the app retrying against the retired path indefinitely.
 */
internal class NetworkRecoveryGate {
    private var eventVersion = 0L
    private var acknowledgedVersion = 0L
    private var claimedVersion: Long? = null

    @Synchronized fun markPending() {
        eventVersion++
    }

    @Synchronized fun isPending(): Boolean =
        eventVersion > maxOf(acknowledgedVersion, claimedVersion ?: acknowledgedVersion)

    /** Reserve the events observed before an attempt, without acknowledging them until it finishes. */
    @Synchronized fun claimIfCanStart(canStart: Boolean): Long? {
        if (!canStart || claimedVersion != null || !isPending()) return null
        return eventVersion.also { claimedVersion = it }
    }

    /** Complete only the reservation for this attempt; newer callbacks remain armed. */
    @Synchronized fun acknowledge(version: Long) {
        if (claimedVersion != version) return
        acknowledgedVersion = maxOf(acknowledgedVersion, version)
        claimedVersion = null
    }

    /** Release a reservation if the caller failed to start its operation. */
    @Synchronized fun release(version: Long) {
        if (claimedVersion == version) claimedVersion = null
    }

    @Synchronized fun clear() {
        acknowledgedVersion = eventVersion
        claimedVersion = null
    }
}
