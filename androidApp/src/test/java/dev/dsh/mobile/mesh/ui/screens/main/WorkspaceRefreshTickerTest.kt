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
class WorkspaceRefreshTickerTest {
    @Test
    fun `background pauses cache checks and foreground checks immediately without waiting for interval`() = runTest {
        val visible = MutableStateFlow(true)
        var stale = true
        var checks = 0
        var refreshes = 0
        backgroundScope.launch {
            refreshWorkspaceFilesWhileActive(visible, 10_000, {
                checks++
                stale
            }, {
                refreshes++
                stale = false
            })
        }
        runCurrent()
        assertEquals(1, refreshes)
        visible.value = false
        runCurrent()
        stale = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, checks)
        assertEquals(1, refreshes)

        visible.value = true
        runCurrent()
        assertEquals(2, refreshes)
        assertEquals(2, checks)
    }
}
