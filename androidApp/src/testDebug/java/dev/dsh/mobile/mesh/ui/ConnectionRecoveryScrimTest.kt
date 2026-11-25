package dev.dsh.mobile.mesh.ui

import android.app.Application
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the global recovery scrim's system-Back handling.
 *
 * The scrim must intercept Back wherever it is shown: a page registers its BackHandler when it
 * enters composition, which for settings, the connect form or a session's transcript is long after
 * the application root first composed. An always-present root-level callback therefore loses the
 * gesture to those pages (`OnBackPressedDispatcher` dispatches the most recently added enabled
 * callback first), and Back navigated behind the scrim.
 *
 * The intercepted gesture is not a dismissal of the recovery: it asks the root for the connection
 * list, where the attempt keeps running and another host can be chosen or the attempt stopped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectionRecoveryScrimTest {
    @get:Rule val compose = createComposeRule()

    private var scrimVisible by mutableStateOf(false)
    private var pagePresent by mutableStateOf(false)
    private var pageInstance by mutableIntStateOf(0)
    private var pageBackPresses = 0
    private var scrimBackPresses = 0
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
                onBack = { scrimBackPresses++ },
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
        assertEquals("Back must be handled by the scrim, not by the page under it", 0, pageBackPresses)
        assertEquals("The scrim must ask for the connection list instead of swallowing Back", 1, scrimBackPresses)
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
        assertEquals(1, scrimBackPresses)
        compose.runOnIdle { pageInstance++ }
        pressBack()
        assertEquals("The scrim must re-register after the page behind it changes", 0, pageBackPresses)
        assertEquals(2, scrimBackPresses)
    }

    @Test
    fun anAutoRecoveredScrimLeavesBackToThePageItWasCovering() {
        render()
        compose.runOnIdle {
            pagePresent = true
            scrimVisible = true
        }
        // The recovery completes without the user touching Back. The scrim must disappear on its
        // own — never dismissing itself into the connection list — and the page it covered must be
        // exactly what it was, so Back is still that page's own normal Back to the previous page.
        compose.runOnIdle { scrimVisible = false }
        pressBack()
        assertEquals(1, pageBackPresses)
        assertEquals(0, scrimBackPresses)
    }
}
