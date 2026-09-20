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
        Log.d(tag, "recoveryId=$id phase=$name elapsedMs=$elapsed totalMs=$total result=$result${detail.takeIf { it.isNotBlank() }?.let { " detail=$it" } ?: ""}")
    }

    fun start(name: String): Long {
        val now = System.nanoTime()
        Log.d(tag, "recoveryId=$id phase=$name start totalMs=${java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - startedAt)}")
        return now
    }
}
