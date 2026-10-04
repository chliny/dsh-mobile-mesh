package dev.dsh.mobile.mesh.ui.screens.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleTickerTest {
    @Test
    fun `display timer does not wake in background and catches up immediately on return`() = runTest {
        val visible = MutableStateFlow(true)
        val renderedSeconds = mutableListOf<Long>()
        backgroundScope.launch {
            tickWhileVisible(visible, intervalMs = 1_000) {
                renderedSeconds += testScheduler.currentTime / 1_000
            }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(0L, 1L), renderedSeconds)

        visible.value = false
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(0L, 1L), renderedSeconds)

        visible.value = true
        runCurrent()
        assertEquals(listOf(0L, 1L, 61L), renderedSeconds)
    }
}
