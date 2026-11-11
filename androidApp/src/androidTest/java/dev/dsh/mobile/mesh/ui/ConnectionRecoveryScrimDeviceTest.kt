package dev.dsh.mobile.mesh.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the global recovery scrim's system-Back blocker.
 *
 * A page registers its BackHandler when it enters composition, which for settings, the connect form
 * or a session's transcript is long after the application root first composed. An always-present
 * root-level callback therefore loses the gesture to those pages (`OnBackPressedDispatcher`
 * dispatches the most recently added enabled callback first), and Back navigated behind the scrim.
 * This runs on the device because the behavior is the framework dispatcher's, not a pure function.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryScrimDeviceTest {
    @get:Rule val compose = createComposeRule()

    private var scrimVisible by mutableStateOf(false)
    private var pagePresent by mutableStateOf(false)
    private var pageInstance by mutableIntStateOf(0)
    private var pageBackPresses = 0
    private lateinit var backDispatcher: OnBackPressedDispatcher

    private fun render() {
        compose.setContent {
            val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            DisposableEffect(owner) {
                backDispatcher = owner.onBackPressedDispatcher
                onDispose { }
            }
            if (pagePresent) {
                // Stands in for SettingsScreen/MainScreen: its handler is registered on the
                // composition pass that opens the page, not at application startup.
                key(pageInstance) {
                    BackHandler { pageBackPresses++ }
                }
            }
            ConnectionRecoveryScrim(
                visible = scrimVisible,
                pageIdentity = pageInstance to pagePresent,
            )
        }
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnIdle { backDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aPageOpenedAfterTheRootCannotTakeBackFromTheScrim() {
        render()
        compose.runOnIdle { pagePresent = true }
        compose.runOnIdle { scrimVisible = true }
        pressBack()
        assertEquals("Back must be swallowed by the scrim, not by the page under it", 0, pageBackPresses)
    }

    @Test
    fun aPageSwappedInBehindTheScrimCannotTakeBackFromTheScrim() {
        render()
        compose.runOnIdle {
            pagePresent = true
            scrimVisible = true
        }
        pressBack()
        assertEquals(0, pageBackPresses)
        compose.runOnIdle { pageInstance++ }
        pressBack()
        assertEquals("The scrim must re-register after the page behind it changes", 0, pageBackPresses)
    }

    @Test
    fun backReachesThePageAgainOnceTheScrimIsGone() {
        render()
        compose.runOnIdle {
            pagePresent = true
            scrimVisible = true
        }
        pressBack()
        assertEquals(0, pageBackPresses)
        compose.runOnIdle { scrimVisible = false }
        pressBack()
        assertEquals(1, pageBackPresses)
    }
}
