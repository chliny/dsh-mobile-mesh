package dev.dsh.mobile.mesh.ui.screens.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.DsDialog
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.rememberHostsStore
import dev.dsh.mobile.mesh.ui.screens.main.SheetRow
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import dev.dsh.mobile.mesh.connection.ConnectionPhase

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ConnectionsScreen(
    onClose: () -> Unit,
    onConnectAttempt: () -> Unit = {},
    onConnectHost: (HostConfig) -> Unit,
    onUpdateToken: (HostConfig) -> Unit,
    onEditHost: (HostConfig) -> Unit,
    onDeleteHost: (HostConfig) -> Unit,
    onAdd: () -> Unit,
    connectedHostId: String?,
    connectingHostId: String? = null,
    connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    connectionState: ConnectUiState = ConnectUiState(),
    onCancelConnection: () -> Unit = {},
    onRequestToken: () -> Unit = {},
    onDismissToken: () -> Unit = {},
    onSubmitToken: (String) -> Unit = {},
) {
    val hostsStore = rememberHostsStore()
    val hosts by hostsStore.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = DsTheme.colors
    var tokenHost by remember { mutableStateOf<HostConfig?>(null) }
    var menuHost by remember { mutableStateOf<HostConfig?>(null) }
    var deleteHost by remember { mutableStateOf<HostConfig?>(null) }
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DsIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onClose,
                )
                Text(
                    stringResource(R.string.connect_connections),
                    style = DsType.large20,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                DsButton(
                    text = stringResource(R.string.connect_add_connection),
                    onClick = onAdd,
                    variant = DsButtonVariant.Info,
                )
            }
            SectionHeader(stringResource(R.string.connect_remembered))
            if (hosts.isEmpty()) {
                Text(
                    stringResource(R.string.connect_remembered_empty),
                    style = DsType.std14,
                    color = colors.labelCaption,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
                ) {
                    items(hosts, key = HostConfig::id) { host ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (shouldResetBeforeSelectingHost(connectionState.connecting)) onConnectAttempt()
                                        onConnectHost(host)
                                    },
                                    onLongClick = { menuHost = host },
                                )
                                .padding(vertical = DsSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host.name, style = DsType.std14Strong, color = colors.labelPrimary)
                                Text(host.displayAddress, style = DsType.caption11, color = colors.labelTertiary)
                            }
                            if (host.id == connectingHostId) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = DsSpacing.small),
                                    strokeWidth = 2.dp,
                                )
                            }
                            when (connectionRowStatus(host.id, connectedHostId, connectingHostId, connectionPhase, host.isLoopback)) {
                                ConnectionRowStatus.CONNECTING -> Text(
                                    stringResource(R.string.connect_connecting),
                                    style = DsType.caption11,
                                    color = colors.labelCaption,
                                )
                                ConnectionRowStatus.CONNECTED -> Text(
                                    stringResource(R.string.connect_current),
                                    style = DsType.caption11,
                                    color = colors.labelCaption,
                                )
                                ConnectionRowStatus.SAME_DEVICE -> Text(
                                    stringResource(R.string.connect_same_device),
                                    style = DsType.caption11,
                                    color = colors.labelCaption,
                                )
                                ConnectionRowStatus.NONE -> Unit
                            }
                        }
                    }
                }
            }
            if (connectionState.connecting) {
                ConnectProgressRow(connectionState.stage, connectionState.attempted)
            }
            connectionState.authorizationPending?.let { message -> ConnectAuthorizationPendingBlock(message) }
            connectionState.failure?.let { failure ->
                ConnectFailureBlock(
                    failure = failure,
                    attempted = connectionState.attempted,
                    retrying = connectionState.retrying,
                    onCancel = onCancelConnection,
                    onSignIn = onRequestToken,
                )
            }
        }
    }
    connectionState.tailscaleLoginUrl?.takeIf {
        shouldShowTailscaleLogin(it, connectionState.authorizationPending != null)
    }?.let { loginUrl ->
        TailscaleLoginDialog(loginUrl = loginUrl, onDismiss = onCancelConnection)
    }
    menuHost?.let { host ->
        DsDialog(title = host.name, onDismiss = { menuHost = null }) {
            SheetRow(
                title = stringResource(R.string.connect_update_token),
                onClick = { menuHost = null; onUpdateToken(host) },
            )
            SheetRow(
                title = stringResource(R.string.common_edit),
                onClick = { menuHost = null; onEditHost(host) },
            )
            SheetRow(
                title = stringResource(R.string.common_delete),
                onClick = { menuHost = null; deleteHost = host },
            )
        }
    }
    if (connectionState.signInOpen) {
        LaunchTokenDialog(
            signingIn = connectionState.signingIn,
            error = connectionState.signInError,
            onDismiss = { onDismissToken() },
            onSubmit = onSubmitToken,
        )
    }
    deleteHost?.let { host ->
        DsDialog(
            title = stringResource(R.string.connect_delete_title),
            onDismiss = { deleteHost = null },
        ) {
            Text(
                stringResource(R.string.connect_delete_message),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deleteHost = null },
                    variant = DsButtonVariant.Ghost,
                )
                DsButton(
                    text = stringResource(R.string.common_delete),
                    onClick = { deleteHost = null; onDeleteHost(host) },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}
