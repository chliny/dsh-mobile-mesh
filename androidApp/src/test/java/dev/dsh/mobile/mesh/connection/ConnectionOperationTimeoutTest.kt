package dev.dsh.mobile.mesh.connection

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOperationTimeoutTest {
    @Test
    fun `owned operation timeout becomes a retryable connection failure`() = runBlocking {
        val failure = try {
            withConnectionOperationTimeout(timeoutMs = 20) {
                delay(1_000)
                "connected"
            }
            null
        } catch (error: ConnectionOperationTimeoutException) {
            error
        }

        assertTrue(failure != null)
        assertTrue(
            shouldRetryConnectionOperation(
                reconnect = true,
                hasConnected = true,
                lifecycleMayRun = true,
                failure = failure!!,
            ),
        )
    }

    @Test
    fun `parent cancellation remains cancellation and is not converted into a retryable timeout`() = runBlocking {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val failure = AtomicReference<Throwable?>()
        val job = CoroutineScope(Dispatchers.Default + parent).launch {
            started.complete(Unit)
            try {
                withConnectionOperationTimeout(timeoutMs = 60_000) { awaitCancellation() }
            } catch (error: Throwable) {
                failure.set(error)
                throw error
            }
        }

        started.await()
        parent.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(failure.get() is CancellationException)
        assertFalse(failure.get() is ConnectionOperationTimeoutException)
    }
}
