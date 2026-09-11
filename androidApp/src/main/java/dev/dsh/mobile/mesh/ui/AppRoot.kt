package dev.dsh.mobile.mesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectScreen
import dev.dsh.mobile.mesh.ui.screens.connect.ConnectionsScreen
import dev.dsh.mobile.mesh.ui.screens.main.ChatListDrawer
import dev.dsh.mobile.mesh.ui.screens.main.MainScreen
import dev.dsh.mobile.mesh.ui.screens.settings.SettingsScreen
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import dev.dsh.mobile.mesh.ui.theme.DshTheme
import dev.dsh.mobile.mesh.ui.theme.ThemePreference
import dev.dsh.mobile.mesh.update.AvailableUpdate

/** Application root: theme + locale-aware shell, connect vs. main routing. */
@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
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
        val showMain = connection.hasConnected
        val showConnect = !connection.hasConnected || showConnectPage
        LaunchedEffect(connection.hasConnected) {
            if (connection.hasConnected) showConnectPage = false
        }
        when {
            showConnections -> ConnectionsScreen(
                onClose = { showConnections = false },
                onAdd = {
                    showConnections = false
                    showConnectPage = true
                },
            )
            showSettings -> SettingsScreen(
                onClose = { showSettings = false },
                onOpenConnections = {
                    showSettings = false
                    showConnections = true
                },
            )
            showConnect -> ConnectScreen(
                onOpenSettings = { showSettings = true },
                onClose = if (connection.hasConnected) ({ showConnectPage = false }) else null,
            )
            showSessionList -> ChatListDrawer(
                onClose = { showSessionList = false },
                onOpenSettings = { showSettings = true },
            )
            showMain -> MainScreen(
                connectionPhase = connection.phase,
                reconnectAttempt = connection.attempts,
                onReconnect = viewModel::reconnect,
                onOpenSessionList = { showSessionList = true },
            )
            else -> ConnectScreen(onOpenSettings = { showSettings = true })
        }

        // Offered over whatever is on screen, and only once per release: dismissing records the
        // version, so the next launch is quiet until there is a newer one.
        update?.let { UpdateDialog(it, onDismiss = { viewModel.dismissUpdate(it.version) }) }
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
