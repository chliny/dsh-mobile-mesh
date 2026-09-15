package dev.dsh.mobile.mesh.connection

/**
 * Single-writer intent model for connection lifecycle decisions.
 *
 * Resource work happens outside this class, tagged with [Token]. A completion may publish only
 * while [accepts] remains true. Backgrounding with retention disabled preserves the latest target
 * but changes the run epoch, making every in-flight startup/recovery completion stale.
 */
internal class ConnectionLifecycleCoordinator<T> {
    data class Token(val targetRevision: Long, val runEpoch: Long)
    data class Target<T>(val value: T, val token: Token)

    private var targetRevision = 0L
    private var runEpoch = 0L
    private var foreground = false
    private var retainInBackground = false
    private var desired: T? = null

    @Synchronized
    fun request(value: T): Target<T> {
        desired = value
        targetRevision += 1
        runEpoch += 1
        return target(value)
    }

    @Synchronized
    fun disconnect(): Token {
        desired = null
        targetRevision += 1
        runEpoch += 1
        return Token(targetRevision, runEpoch)
    }

    @Synchronized
    fun foreground(): Target<T>? {
        if (!foreground) {
            foreground = true
            if (!retainInBackground) runEpoch += 1
        }
        return desired?.let(::target)
    }

    @Synchronized
    fun background(): Target<T>? {
        if (foreground) {
            foreground = false
            if (!retainInBackground) runEpoch += 1
        }
        return desired?.let(::target)
    }

    @Synchronized
    fun isForeground(): Boolean = foreground

    @Synchronized
    fun setRetainInBackground(retain: Boolean): Target<T>? {
        if (retainInBackground == retain) return desired?.let(::target)
        retainInBackground = retain
        if (!mayRunLocked()) runEpoch += 1
        return desired?.let(::target)
    }

    @Synchronized
    fun retryToken(): Target<T>? {
        val value = desired ?: return null
        runEpoch += 1
        return target(value)
    }

    @Synchronized
    fun current(): Target<T>? = desired?.let(::target)

    @Synchronized
    fun mayRun(): Boolean = mayRunLocked()

    @Synchronized
    fun accepts(token: Token): Boolean =
        mayRunLocked() && desired != null &&
            token.targetRevision == targetRevision && token.runEpoch == runEpoch

    private fun target(value: T) = Target(value, Token(targetRevision, runEpoch))
    private fun mayRunLocked(): Boolean = foreground || retainInBackground
}
