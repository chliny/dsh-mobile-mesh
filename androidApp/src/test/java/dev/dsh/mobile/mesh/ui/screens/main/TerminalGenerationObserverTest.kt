package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalGenerationObserverTest {
    @Test fun `new generation refreshes while initial terminal read is suspended`() = runTest {
        val generations = MutableSharedFlow<Unit>()
        val initialGate = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        val worker = backgroundScope.launch {
            observeTerminalGenerations(generations, initial = { initialGate.await() }, refresh = {
                refreshed.complete(Unit)
            })
        }
        runCurrent()
        generations.emit(Unit)
        runCurrent()
        assertTrue(refreshed.isCompleted)
        initialGate.complete(Unit)
        worker.cancel()
    }

    @Test fun `old environment result cannot update new host terminal controls`() {
        val old = Any()
        val current = Any()
        assertFalse(sameTerminalGeneration(old, current))
        assertTrue(sameTerminalGeneration(current, current))
    }
}
