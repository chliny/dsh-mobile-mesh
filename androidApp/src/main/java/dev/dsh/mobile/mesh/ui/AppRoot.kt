package dev.dsh.mobile.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsDialog
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectViewModel
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectUiState
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectScreen
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectionsScreen
import dev.dsh.mobile.mesh.ui.screens.main.ChatListDrawer
import dev.dsh.mobile.mesh.ui.screens.main.MainScreen
import dev.dsh.mobile.mesh.ui.screens.settings.SettingsScreen
import dev.dsh.mobile.mesh.ui.rememberHostsStore
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import dev.dsh.mobile.mesh.ui.theme.ThemePreference
import dev.dsh.mobile.mesh.update.AvailableUpdate

internal fun shouldShowStartupConnections(hasConnected: Boolean, editingConnection: Boolean): Boolean =
    !hasConnected && !editingConnection

internal fun shouldRouteToSessionListAfterConnection(hasConnected: Boolean, editingConnection: Boolean): Boolean =
    hasConnected && !editingConnection

internal fun shouldRouteSelectedConnection(
    phaseConnected: Boolean,
    selectedAuthority: String?,
    activeAuthority: String?,
    editing: Boolean,
    awaitingSelectedConnection: Boolean,
): Boolean = (awaitingSelectedConnection || selectedAuthority != null) &&
    phaseConnected && !editing && selectedAuthority != null && selectedAuthority == activeAuthority

internal fun shouldShowConnectionTokenPrompt(
    showingConnections: Boolean,
    startupConnections: Boolean,
    signInOpen: Boolean,
): Boolean = signInOpen && (showingConnections || startupConnections)

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val hostsStore = rememberHostsStore()
    val hosts by hostsStore.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val connectViewModel: ConnectViewModel = hiltViewModel()
    val connectUiState by connectViewModel.state.collectAsStateWithLifecycle()
    val sessionStore = rememberSessionStore()
    val themePreference = remember(settings.themePreference) {
        runCatching { ThemePreference.valueOf(settings.themePreference.uppercase()) }
            .getOrDefault(ThemePreference.SYSTEM)
    }

    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkForUpdate(BuildConfig.VERSION_NAME) }

    DshTheme(preference = themePreference) {
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showSessionList by rememberSaveable { mutableStateOf(true) }
        var showConnectPage by rememberSaveable { mutableStateOf(false) }
        var showConnections by rememberSaveable { mutableStateOf(false) }
        var connectionListOrigin by rememberSaveable { mutableStateOf(ConnectionListOrigin.STARTUP) }
        var returnToConnections by rememberSaveable { mutableStateOf(false) }
        var awaitingSelectedConnection by rememberSaveable { mutableStateOf(false) }
        var editingHost by remember { mutableStateOf<HostConfig?>(null) }
        var connectFormInstance by rememberSaveable { mutableIntStateOf(0) }
        val showMain = connection.hasConnected
        val showConnect = showConnectPage
        val showStartupConnections = shouldShowStartupConnections(connection.hasConnected, editingHost != null) && !showSettings && !showConnectPage
        // The native login URL is the authoritative signal for showing authorization. A fast
        // state emission must not leave the user on the connection form with a valid URL pending.
        val showTailscaleLogin = connectUiState.tailscaleLoginUrl?.isNotBlank() == true
        LaunchedEffect(connection.phase, connection.host?.id, connectUiState.attempted) {
            val selectedConnectionIsReady = shouldRouteSelectedConnection(
                phaseConnected = connection.phase == ConnectionPhase.CONNECTED,
                selectedAuthority = connectUiState.attempted,
                activeAuthority = connection.host?.authority,
                editing = editingHost != null,
                awaitingSelectedConnection = awaitingSelectedConnection || showConnectPage,
            )
            if (selectedConnectionIsReady && editingHost == null) {
                awaitingSelectedConnection = false
                showConnectPage = false
                showConnections = false
                showSessionList = true
            }
        }
        when {
            showConnections || showStartupConnections -> ConnectionsScreen(
                onClose = {
                    showConnections = false
                    showConnectPage = false
                    editingHost = null
                    when (connectionListBackTarget(connectionListOrigin)) {
                        ConnectionListOrigin.SETTINGS -> showSettings = true
                        ConnectionListOrigin.SESSION -> showSessionList = true
                        ConnectionListOrigin.CONNECT_FORM, ConnectionListOrigin.STARTUP -> Unit
                    }
                },
                onConnectAttempt = { connectViewModel.cancelConnect() },
                onConnectHost = { host ->
                    if (connection.phase == ConnectionPhase.CONNECTED && connection.host?.id == host.id) {
                        // Settings -> connection list is also a navigation surface. Selecting the
                        // already-live host must not tear down its carrier and start a duplicate
                        // handshake; return directly to the session list.
                        showConnections = false
                        showConnectPage = false
                        showSessionList = true
                    } else {
                        awaitingSelectedConnection = true
                        showSessionList = false
                        showConnections = true
                        sessionStore.prepareForConnection(host.id)
                        // Selecting another row is an explicit handover: forget the old active
                        // connection immediately, even if the replacement later fails.
                        connectViewModel.selectHost(host)
                    }
                },
                onUpdateToken = connectViewModel::requestTokenUpdate,
                onEditHost = { host ->
                    editingHost = host
                    returnToConnections = true
                    connectFormInstance++
                    showConnectPage = true
                    showConnections = false
                },
                onDeleteHost = { host ->
                    connectViewModel.forget(host)
                    if (host.id == connection.host?.id) viewModel.reconnect()
                },
                onAdd = {
                    connectViewModel.cancelConnect()
                    editingHost = null
                    returnToConnections = true
                    connectFormInstance++
                    showConnectPage = true
                    showConnections = false
                },
                connectedHostId = connection.host?.id,
                connectingHostId = connection.host?.takeIf { connection.phase == ConnectionPhase.CONNECTING || connection.phase == ConnectionPhase.RECONNECTING }?.id,
                connectionPhase = connection.phase,
                connectionState = connectUiState,
                onCancelConnection = connectViewModel::cancelConnect,
                onRequestToken = { connectViewModel.setSignInOpen(true) },
                onDismissToken = { connectViewModel.setSignInOpen(false) },
                onSubmitToken = connectViewModel::signIn,
            )
            showSettings -> SettingsScreen(
                onClose = { showSettings = false },
                onOpenConnections = {
                    connectionListOrigin = ConnectionListOrigin.SETTINGS
                    showSettings = false
                    showConnections = true
                    showSessionList = false
                },
            )
            showConnect -> key(editingHost?.id ?: "new", connectFormInstance) {
                ConnectScreen(
                    onOpenSettings = { showSettings = true },
                    onClose = if (connection.hasConnected) ({ showConnectPage = false }) else null,
                    onOpenConnections = if (returnToConnections) ({
                        showConnectPage = false
                        editingHost = null
                        showConnections = true
                    }) else null,
                    initialHost = editingHost,
                    connectedHostId = connection.host?.id,
                    restoreDraft = false,
                )
            }
            showSessionList -> ChatListDrawer(
                connectionPhase = connection.phase,
                onClose = { showSessionList = false },
                onOpenSettings = {
                    connectionListOrigin = ConnectionListOrigin.SESSION
                    showSettings = true
                },
            )
            showMain -> MainScreen(
                connectionPhase = connection.phase,
                reconnectAttempt = connection.attempts,
                onReconnect = viewModel::reconnect,
                onOpenSessionList = { showSessionList = true },
            )
            else -> Unit
        }

        // The remembered-connection list has no form on screen, so it owns the token-update dialog
        // for a host selected from its long-press menu. The same ViewModel then saves the token and
        // reconnects that exact host.
        if (shouldShowConnectionTokenPrompt(showConnections, showStartupConnections, connectUiState.signInOpen)) {
            dev.dsh.mobile.mesh.ui.screens.connect.LaunchTokenDialog(
                signingIn = connectUiState.signingIn,
                error = connectUiState.signInError,
                onDismiss = { connectViewModel.setSignInOpen(false) },
                onSubmit = connectViewModel::signIn,
            )
        }

        if (showTailscaleLogin) {
            dev.dsh.mobile.mesh.ui.screens.connect.TailscaleLoginDialog(
                loginUrl = connectUiState.tailscaleLoginUrl!!,
                onDismiss = connectViewModel::cancelTailscaleLogin,
            )
        }

        // Offered over whatever is on screen, and only once per release: dismissing records the
        // version, so the next launch is quiet until there is a newer one.
        update?.let { UpdateDialog(it, onDismiss = { viewModel.dismissUpdate(it.version) }) }

        // A connected session must not accept taps while its carrier is being verified or rebuilt.
        // The scrim consumes input at the root, preventing session switches and writes from racing
        // ConnectionManager's transport teardown/reconnect sequence.
        if (shouldBlockForConnectionRecovery(
                connection.hasConnected,
                connection.phase,
                connection.foregroundCheckPending,
            )) {
            ConnectionRecoveryOverlay()
        }
    }
}

@Composable
private fun ConnectionRecoveryOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** "There is a newer release" — a link out, not an installer; the app cannot update itself. */
@Composable
private fun UpdateDialog(update: AvailableUpdate, onDismiss: () -> Unit) {
    val colors = DsTheme.colors
    val uriHandler = LocalUriHandler.current
    DsDialog(title = stringResource(R.string.update_available_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.update_available_body, update.version, BuildConfig.VERSION_NAME),
            style = DsType.std14,
            color = colors.labelSecondary,
            modifier = Modifier.padding(bottom = DsSpacing.medium),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
            DsButton(
                text = stringResource(R.string.update_open),
                onClick = {
                    runCatching { uriHandler.openUri(update.url) }
                    onDismiss()
                },
                variant = DsButtonVariant.Info,
            )
            DsButton(
                text = stringResource(R.string.update_later),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
        }
    }
}
