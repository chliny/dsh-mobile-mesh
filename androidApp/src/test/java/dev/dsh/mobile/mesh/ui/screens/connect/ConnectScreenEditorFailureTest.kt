package dev.dsh.mobile.mesh.ui.screens.connect

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.ConnectionDraft
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A connection attempt started from the remembered-connection list reports its failure below the
 * list. Opening that entry's editor is a navigation to a page that only saves settings; the list's
 * failure used to be rendered there as well, attached to the wrong screen and possibly the wrong
 * host. These tests render the real screen, so they fail if the failure block is ever unconditional
 * again or is dropped from the form that does own the attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ConnectScreenEditorFailureTest {
    @get:Rule
    val compose = createComposeRule()

    private val attempted = "192.168.1.20:3080"
    private val failureText: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.connect_fail_timeout, attempted, 3080)

    @Test
    fun `editing a remembered connection does not show the failure raised by the connection list`() {
        render(
            initialHost = HostConfig(id = "host-1", name = "Home", host = "192.168.1.20", port = 3080),
        )

        compose.onNodeWithText(failureText).assertDoesNotExist()
    }

    @Test
    fun `new connection form still shows the failure of its own attempt`() {
        render(initialHost = null)

        compose.onNodeWithText(failureText).assertExists()
    }

    @Test
    fun `editing a remembered connection does not show pending authorization from the list`() {
        val message = "Waiting for authorization on another connection"
        render(
            initialHost = HostConfig(id = "host-1", name = "Home", host = "192.168.1.20", port = 3080),
            state = ConnectUiState(authorizationPending = message),
        )

        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun `new connection form still shows its pending authorization`() {
        val message = "Waiting for authorization on this connection"
        render(initialHost = null, state = ConnectUiState(authorizationPending = message))

        compose.onNodeWithText(message).assertExists()
    }

    private fun render(
        initialHost: HostConfig?,
        state: ConnectUiState = ConnectUiState(failure = ConnectFailure.Timeout, attempted = attempted),
    ) {
        val flow = MutableStateFlow(state)
        val viewModel = Mockito.mock(ConnectViewModel::class.java)
        Mockito.`when`(viewModel.state).thenReturn(flow)
        Mockito.`when`(viewModel.draft).thenReturn(MutableStateFlow(ConnectionDraft()))
        Mockito.`when`(viewModel.savedSshCredentials(Mockito.anyString())).thenReturn(null)
        compose.setContent {
            DshTheme {
                ConnectScreen(
                    onOpenSettings = {},
                    initialHost = initialHost,
                    restoreDraft = false,
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
    }
}
