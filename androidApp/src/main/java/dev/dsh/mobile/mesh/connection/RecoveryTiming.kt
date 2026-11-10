package dev.dsh.mobile.mesh.connection

import android.util.Log
import java.util.UUID

internal class RecoveryTiming(
    private val id: String = UUID.randomUUID().toString().take(8),
    private val tag: String = "RecoveryTiming",
) {
    private val startedAt = System.nanoTime()

    fun phase(name: String, phaseStartedAt: Long, result: String, detail: String = "") {
        val elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStartedAt)
        val total = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " detail=$it" } ?: ""
        Log.d(tag, "recoveryId=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result$suffix")
        // The phase durations are the first thing a "slow resume" report needs, and they must
        // survive beyond the logcat buffer; the journal keeps them with the rest of the timeline.
        ConnectionTrace.record(
            "timing",
            "recoveryId=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result$suffix",
        )
    }

    fun start(name: String): Long {
        val now = System.nanoTime()
        val total = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - startedAt)
        Log.d(tag, "recoveryId=$id phase=$name start totalMs=$total")
        ConnectionTrace.record("timing", "recoveryId=$id phase=$name start totalMs=$total")
        return now
    }
}
