package dev.dsh.mobile.mesh.ui.screens.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.ConnectStage
import dev.dsh.mobile.mesh.connection.ConnectionDraft
import dev.dsh.mobile.mesh.connection.DiscoveredHost
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.connection.MeshTransport
import dev.dsh.mobile.mesh.connection.SshAuthentication
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.DsCard
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.DsSegment
import dev.dsh.mobile.mesh.ui.components.DsSegmented
import dev.dsh.mobile.mesh.ui.components.ToggleRow
import dev.dsh.mobile.mesh.ui.components.MeshMark
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.components.DsDialog
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.relativeTime
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.launch

/**
 * Choose how to reach a harness, then reach one.
 *
 */
@Composable
fun ConnectScreen(
    onOpenSettings: () -> Unit,
    onClose: (() -> Unit)? = null,
    onOpenConnections: (() -> Unit)? = null,
    initialHost: HostConfig? = null,
    connectedHostId: String? = null,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val persistedDraft by viewModel.draft.collectAsStateWithLifecycle(initialValue = null)
    val colors = DsTheme.colors
    // Saveable: a rotation mid-connect used to wipe a hand-typed address.
    var connectionName by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("3080") }
    var meshTransportKey by rememberSaveable { mutableStateOf("direct") }
    val meshTransport = MeshTransport.of(meshTransportKey)
    var zeroTierNetworkId by rememberSaveable { mutableStateOf("") }
    var zeroTierPlanetId by rememberSaveable { mutableStateOf<String?>(null) }
    var zeroTierPlanetBase64 by rememberSaveable { mutableStateOf("") }
    var zeroTierPlanetError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tailscaleHostname by rememberSaveable { mutableStateOf("") }
    var sshEnabled by rememberSaveable { mutableStateOf(true) }
    var sshPort by rememberSaveable { mutableStateOf("22") }
    var sshUsername by rememberSaveable { mutableStateOf("") }
    var sshAuthenticationKey by rememberSaveable { mutableStateOf("password") }
    val sshAuthentication = if (sshAuthenticationKey == "key") SshAuthentication.PRIVATE_KEY else SshAuthentication.PASSWORD
    var sshPassword by rememberSaveable { mutableStateOf("") }
    var sshPrivateKey by rememberSaveable { mutableStateOf("") }
    var sshPrivateKeyPassphrase by rememberSaveable { mutableStateOf("") }
    var launchToken by rememberSaveable { mutableStateOf("") }
    var sshDshHost by rememberSaveable { mutableStateOf("127.0.0.1") }
    var restoredForKey by remember { mutableStateOf<String?>(null) }
    val editingHost = initialHost
    val formKey = editingHost?.id ?: "new"
    val connectedEditing = editingHost?.id == connectedHostId
    val fieldsEnabled = !connectedEditing
    onOpenConnections?.let { openConnections ->
        BackHandler { openConnections() }
    }

    LaunchedEffect(persistedDraft, editingHost, formKey) {
        if (restoredForKey != formKey) {
            val saved = editingHost
            if (saved == null && persistedDraft == null) return@LaunchedEffect
            if (saved != null) {
                connectionName = saved.name
                host = saved.host
                port = saved.port.toString()
                meshTransportKey = saved.meshTransport?.storedValue ?: "direct"
                zeroTierNetworkId = saved.zeroTierNetworkId.orEmpty()
                zeroTierPlanetId = saved.zeroTierPlanetId
                zeroTierPlanetBase64 = saved.zeroTierPlanetBase64.orEmpty()
                viewModel.savedSshCredentials(saved.id)?.let { credentials ->
                    sshPassword = credentials.password.orEmpty()
                    sshPrivateKey = credentials.privateKey.orEmpty()
                    sshPrivateKeyPassphrase = credentials.privateKeyPassphrase.orEmpty()
                }
                tailscaleHostname = saved.tailscaleHostname.orEmpty()
                sshEnabled = saved.sshEnabled
                sshPort = saved.sshPort.toString()
                sshUsername = saved.sshUsername.orEmpty()
                sshAuthenticationKey = if (saved.sshAuthentication == SshAuthentication.PRIVATE_KEY) "key" else "password"
                sshDshHost = saved.sshDshHost
                launchToken = saved.launchToken
            } else if (persistedDraft != null) {
                val draft = persistedDraft ?: return@LaunchedEffect
                connectionName = draft.name
                host = draft.host
                port = draft.port
                meshTransportKey = draft.meshTransport
                zeroTierNetworkId = draft.zeroTierNetworkId
                zeroTierPlanetId = draft.zeroTierPlanetId
                zeroTierPlanetBase64 = draft.zeroTierPlanetBase64
                tailscaleHostname = draft.tailscaleHostname
                sshEnabled = draft.sshEnabled
                sshPort = draft.sshPort
                sshUsername = draft.sshUsername
                sshAuthenticationKey = if (draft.sshAuthentication == SshAuthentication.PRIVATE_KEY) "key" else "password"
                sshPassword = draft.sshPassword
                sshPrivateKey = draft.sshPrivateKey
                sshPrivateKeyPassphrase = draft.sshPrivateKeyPassphrase
                launchToken = draft.launchToken
                sshDshHost = draft.sshDshHost
            }
            restoredForKey = formKey
        }
    }

    LaunchedEffect(
        restoredForKey, connectionName, host, port, meshTransportKey, zeroTierNetworkId, zeroTierPlanetId, zeroTierPlanetBase64,
        tailscaleHostname, sshEnabled, sshPort, sshUsername, sshAuthentication,
        sshPassword, sshPrivateKey, sshPrivateKeyPassphrase, launchToken,
        sshDshHost,
    ) {
        if (restoredForKey == formKey) viewModel.saveDraft(
            ConnectionDraft(
                name = connectionName,
                host = host,
                port = port,
                meshTransport = meshTransportKey,
                zeroTierNetworkId = zeroTierNetworkId,
                zeroTierPlanetId = zeroTierPlanetId,
                zeroTierPlanetBase64 = zeroTierPlanetBase64,
                tailscaleHostname = tailscaleHostname,
                sshEnabled = sshEnabled,
                sshPort = sshPort,
                sshUsername = sshUsername,
                sshAuthentication = sshAuthentication,
                sshPassword = sshPassword,
                sshPrivateKey = sshPrivateKey,
                sshPrivateKeyPassphrase = sshPrivateKeyPassphrase,
                launchToken = launchToken,
                sshDshHost = sshDshHost,
            ),
        )
    }
    val scope = rememberCoroutineScope()
    val planetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            viewModel.importZeroTierPlanet(uri).fold(
                onSuccess = { zeroTierPlanetId = it; zeroTierPlanetError = null },
                onFailure = { zeroTierPlanetError = it.message },
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.bgBase) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DsSpacing.xlarge, vertical = DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.comfortable),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onOpenConnections?.let { openConnections ->
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = openConnections,
                    )
                } ?: onClose?.let { close ->
                    DsIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = close,
                    )
                }
                Spacer(Modifier.weight(1f))
                DsIconButton(
                    icon = FeatherIcons.Tool,
                    contentDescription = stringResource(R.string.settings_title),
                    onClick = onOpenSettings,
                    tint = colors.labelTertiary,
                )
            }

            ConnectHeader()

            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    TextField(
                        value = connectionName,
                        onValueChange = { connectionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.connect_name_hint), style = DsType.std14) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.connect_name_label)) },
                        colors = connectFieldColors(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(stringResource(R.string.connect_host_hint), style = DsType.std14)
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.connect_host_label)) },
                             enabled = fieldsEnabled,
                           colors = connectFieldColors(),
                        )
                        Spacer(Modifier.width(DsSpacing.compact))
                        TextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.width(92.dp),
                            singleLine = true,
                            label = { Text(stringResource(R.string.connect_port_label)) },
                             enabled = fieldsEnabled,
                           colors = connectFieldColors(),
                        )
                    }
                    DsSegmented(
                        segments = listOf(
                            DsSegment("direct", stringResource(R.string.connect_transport_direct)),
                            DsSegment(MeshTransport.ZERO_TIER.storedValue, stringResource(R.string.connect_transport_zerotier)),
                            DsSegment(MeshTransport.TAILSCALE.storedValue, stringResource(R.string.connect_transport_tailscale)),
                        ),
                        selectedKey = meshTransport?.storedValue ?: "direct",
                        onSelect = { meshTransportKey = it },
                        enabled = fieldsEnabled,
                        role = Role.Tab,
                        stretch = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (meshTransport) {
                        MeshTransport.ZERO_TIER -> Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                            TextField(
                                value = zeroTierNetworkId,
                                onValueChange = { zeroTierNetworkId = it.filter(Char::isLetterOrDigit) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.connect_zerotier_network_id)) },
                                singleLine = true,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                             DsButton(
                                 text = if (zeroTierPlanetId == null) stringResource(R.string.connect_zerotier_planet_import)
                                 else stringResource(R.string.connect_zerotier_planet_replace),
                                 onClick = { planetPicker.launch(arrayOf("*/*")) },
                                  enabled = fieldsEnabled,
                                 variant = DsButtonVariant.Info,
                             )
                            TextField(
                                value = zeroTierPlanetBase64,
                                onValueChange = { zeroTierPlanetBase64 = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.connect_zerotier_planet_base64)) },
                                minLines = 3,
                                maxLines = 5,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                            DsButton(
                                text = stringResource(R.string.connect_zerotier_planet_base64_import),
                                onClick = {
                                    scope.launch {
                                        viewModel.importZeroTierPlanetBase64(zeroTierPlanetBase64).fold(
                                            onSuccess = {
                                                zeroTierPlanetId = it
                                                zeroTierPlanetError = null
                                            },
                                            onFailure = { zeroTierPlanetError = it.message },
                                        )
                                    }
                                },
                                enabled = fieldsEnabled && zeroTierPlanetBase64.isNotBlank(),
                                variant = DsButtonVariant.Info,
                            )
                            zeroTierPlanetId?.let {
                                Text(
                                    stringResource(R.string.connect_zerotier_planet_id, it.take(12)),
                                    style = DsType.small13,
                                    color = colors.labelCaption,
                                )
                            }
                            zeroTierPlanetError?.let {
                                Text(it, style = DsType.small13, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        MeshTransport.TAILSCALE -> TextField(
                            value = tailscaleHostname,
                            onValueChange = { tailscaleHostname = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.connect_tailscale_hostname)) },
                             enabled = fieldsEnabled,
                            singleLine = true,
                           colors = connectFieldColors(),
                        )
                        null -> Unit
                    }
                    if (meshTransport != null) Text(
                        stringResource(R.string.connect_mesh_hint),
                        style = DsType.small13,
                        color = colors.labelCaption,
                    )
                    ToggleRow(
                        label = stringResource(R.string.connect_ssh_enabled),
                        checked = sshEnabled,
                         enabled = fieldsEnabled,
                    ) { if (fieldsEnabled) sshEnabled = !sshEnabled }
                    if (sshEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = sshUsername,
                                onValueChange = { sshUsername = it },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.connect_ssh_username)) },
                                singleLine = true,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                            Spacer(Modifier.width(DsSpacing.compact))
                            TextField(
                                value = sshPort,
                                onValueChange = { sshPort = it.filter(Char::isDigit) },
                                modifier = Modifier.width(92.dp),
                                label = { Text(stringResource(R.string.connect_ssh_port)) },
                                singleLine = true,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                        }
                        DsSegmented(
                            segments = listOf(
                                DsSegment("password", stringResource(R.string.connect_ssh_password_auth)),
                                DsSegment("key", stringResource(R.string.connect_ssh_key_auth)),
                            ),
                            selectedKey = sshAuthenticationKey,
                            onSelect = { if (fieldsEnabled) sshAuthenticationKey = it },
                             enabled = fieldsEnabled,
                            role = Role.Tab,
                            stretch = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (sshAuthentication == SshAuthentication.PASSWORD) {
                            TextField(
                                value = sshPassword,
                                onValueChange = { sshPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.connect_ssh_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                        } else {
                            TextField(
                                value = sshPrivateKey,
                                onValueChange = { sshPrivateKey = it },
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                label = { Text(stringResource(R.string.connect_ssh_private_key)) },
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                            TextField(
                                value = sshPrivateKeyPassphrase,
                                onValueChange = { sshPrivateKeyPassphrase = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.connect_ssh_key_passphrase)) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                               enabled = fieldsEnabled,
                            colors = connectFieldColors(),
                            )
                        }
                        TextField(
                            value = sshDshHost,
                            onValueChange = { sshDshHost = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.connect_ssh_dsh_host)) },
                             enabled = fieldsEnabled,
                            singleLine = true,
                           colors = connectFieldColors(),
                        )
                    }
                    TextField(
                        value = launchToken,
                        onValueChange = { launchToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.connect_launch_token)) },
                        placeholder = { Text(stringResource(R.string.connect_launch_token_hint)) },
                         enabled = fieldsEnabled,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = connectFieldColors(),
                    )
                    if (editingHost != null) {
                        DsButton(
                            text = stringResource(R.string.common_delete),
                            onClick = { confirmDelete = true },
                            variant = DsButtonVariant.Danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    DsButton(
                        text = stringResource(R.string.connect_save),
                        onClick = {
                            viewModel.saveConnection(
                                existing = editingHost, name = connectionName, host = host, port = port,
                                transport = meshTransport, networkId = zeroTierNetworkId,
                                planetId = zeroTierPlanetId, planetBase64 = zeroTierPlanetBase64,
                                tailscaleHostname = tailscaleHostname, sshEnabled = sshEnabled,
                                sshPort = sshPort, sshUsername = sshUsername,
                                sshAuthentication = sshAuthentication, sshPassword = sshPassword,
                                sshPrivateKey = sshPrivateKey, sshPrivateKeyPassphrase = sshPrivateKeyPassphrase,
                                sshDshHost = sshDshHost, launchToken = launchToken,
                            ) { /* Stay on this form so Save is immediately followed by Connect. */ }
                        },
                        variant = DsButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DsButton(
                        text = stringResource(R.string.connect_button),
                        onClick = {
                            val transport = meshTransport
                            if (transport == null) viewModel.connectManual(
                                connectionName, host, port, sshEnabled, sshPort, sshUsername, sshAuthentication,
                                sshPassword, sshPrivateKey, sshPrivateKeyPassphrase, sshDshHost, launchToken,
                            ) else viewModel.connectMesh(
                                connectionName, host, port, transport, zeroTierNetworkId, tailscaleHostname,
                                sshEnabled, sshPort, sshUsername, sshAuthentication,
                                sshPassword, sshPrivateKey, sshPrivateKeyPassphrase,
                                sshDshHost, zeroTierPlanetId, launchToken,
                            )
                        },
                        enabled = editingHost?.id != connectedHostId &&
                            !state.connecting &&
                            (!sshEnabled || sshPassword.isNotBlank() || sshPrivateKey.isNotBlank()),
                        variant = DsButtonVariant.Info,
                        modifier = Modifier.fillMaxWidth(),
                    )
            }

            // Progress and failure are shared: an attempt reports the same way whichever mode
            // started it, and duplicating the block per mode is how the two drift apart.
            if (state.connecting) ConnectProgressRow(state.stage, state.attempted)
            state.authorizationPending?.let { message -> ConnectAuthorizationPendingBlock(message) }
            state.tailscaleLoginUrl?.let { loginUrl ->
                TailscaleLoginDialog(
                    loginUrl = loginUrl,
                    onDismiss = viewModel::cancelTailscaleLogin,
                )
            }
            state.failure?.let { failure ->
                ConnectFailureBlock(
                    failure = failure,
                    attempted = state.attempted,
                    retrying = state.retrying,
                    onCancel = viewModel::cancelConnect,
                    onSignIn = { viewModel.setSignInOpen(true) },
                )
            }

            if (state.signInOpen) {
                LaunchTokenDialog(
                    signingIn = state.signingIn,
                    error = state.signInError,
                    onDismiss = { viewModel.setSignInOpen(false) },
                    onSubmit = viewModel::signIn,
                )
            }

            Spacer(Modifier.height(DsSpacing.large))
        }
    }
    val pendingDeletion = editingHost
    if (confirmDelete && pendingDeletion != null) {
        DsDialog(
            title = stringResource(R.string.connect_delete_title),
            onDismiss = { confirmDelete = false },
        ) {
            Text(stringResource(R.string.connect_delete_message), style = DsType.std14, color = colors.labelSecondary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DsButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { confirmDelete = false },
                    variant = DsButtonVariant.Ghost,
                )
                Spacer(Modifier.width(DsSpacing.small))
                DsButton(
                    text = stringResource(R.string.common_delete),
                    onClick = {
                        viewModel.forget(pendingDeletion)
                        confirmDelete = false
                        onOpenConnections?.invoke() ?: onClose?.invoke()
                    },
                    variant = DsButtonVariant.Danger,
                )
            }
        }
    }
}

/**
 * The screen's own masthead.
 *
 * Deliberately not [EmptyHero], which is the *chat's* empty state: it carries a "Preview" pill that
 * means nothing here, centres 32dp of padding around a 64dp mark, and pushed the mode chooser and
 * the one button on this screen below the fold on a normal phone.
 */
@Composable
private fun ConnectHeader() {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.medium),
    ) {
        MeshMark(Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.app_long_name),
                style = DsType.large20,
                color = colors.labelPrimary,
            )
            Text(
                stringResource(R.string.connect_subtitle),
                style = DsType.small13,
                color = colors.labelTertiary,
            )
        }
    }
}


@Composable
private fun connectFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = DsTheme.colors.bgLayer1,
    unfocusedContainerColor = DsTheme.colors.bgLayer1,
    focusedIndicatorColor = DsTheme.colors.accent,
    unfocusedIndicatorColor = DsTheme.colors.borderL2,
    cursorColor = DsTheme.colors.accent,
)

@Composable
private fun RecentHarnessCard(
    host: HostConfig,
    probe: HostProbe?,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = DsTheme.colors
    val reachable = probe as? HostProbe.Reachable
    val title = if (host.isLoopback) stringResource(R.string.connect_same_device) else host.name
    // 0.1.2 publishes only the host home. The harness version, its working directory and its
    // attached-session count all came from `host.describe`, which no longer exists.
    val home = reachable?.description?.home ?: host.lastHome

    DsCard(onClick = onConnect) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(
                when (probe) {
                    is HostProbe.Reachable -> StateDotState.Done
                    HostProbe.Probing -> StateDotState.Running
                    HostProbe.Unreachable -> StateDotState.Idle
                    null -> StateDotState.Idle
                },
                size = 8.dp,
            )
            Spacer(Modifier.width(DsSpacing.compact))
            Text(
                title,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (host.lastConnectedAt > 0L) {
                    relativeTime(host.lastConnectedAt)
                } else {
                    stringResource(R.string.connect_never)
                },
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
        Text(
            listOfNotNull(
                host.displayAddress,
                home?.let { basename(it) },
            ).joinToString(" · "),
            style = DsType.caption11,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLine(probe, home),
                style = DsType.caption11,
                color = if (probe is HostProbe.Unreachable) colors.labelCaption else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.common_delete),
                onClick = onForget,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
    }
}

/**
 * The third line: what the harness is, or why it has nothing to say.
 *
 * Through 0.1.1 this named the harness version and its attached-session count, both from
 * `host.describe`. Neither is published any more, so a reachable host shows the home directory it
 * reported — the only host fact 0.1.2 carries — and says so plainly when even that is unknown.
 */
@Composable
private fun statusLine(probe: HostProbe?, home: String?): String = when {
    probe is HostProbe.Probing -> stringResource(R.string.connect_checking)
    probe is HostProbe.Unreachable -> stringResource(R.string.connect_unreachable)
    home != null -> stringResource(R.string.connect_harness_home, home)
    probe is HostProbe.Reachable -> stringResource(R.string.connect_reachable)
    else -> stringResource(R.string.common_loading)
}

/**
 * What the connect attempt is doing, named.
 *
 * A greyed-out button is the same picture whether the handshake is a second from finishing or the
 * packets are being dropped by a firewall. Naming the stage costs one line and turns a wait into a
 * progress report — and when it stops, the stage it stopped on is itself a clue.
 */
@Composable
private fun ConnectProgressRow(stage: ConnectStage, attempted: String?) {
    val colors = DsTheme.colors
    val label = when (stage) {
        ConnectStage.Validating -> stringResource(R.string.connect_stage_validating)
        ConnectStage.Reaching -> stringResource(R.string.connect_stage_reaching, attempted.orEmpty())
        ConnectStage.OpeningStreams -> stringResource(R.string.connect_stage_streams)
        ConnectStage.Verifying -> stringResource(R.string.connect_stage_verifying)
        ConnectStage.Connected -> stringResource(R.string.connect_stage_connected)
        ConnectStage.Idle -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(StateDotState.Running, size = 8.dp)
            Spacer(Modifier.width(DsSpacing.xsmall))
            Text(label, style = DsType.std14, color = colors.labelTertiary)
        }
        LinearProgressIndicator(
            progress = { stage.ordinal / (ConnectStage.entries.size - 1).toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accent,
            trackColor = colors.hoverSolid,
        )
    }
}

/**
 * Why it did not connect, and what to do about it.
 *
 * Deliberately one sentence of cause and one of action, with no commands: the device that failed is
 * the phone, and the fix almost always happens on the computer. `harness/README.md` carries the
 * PowerShell.
 */
@Composable
private fun ConnectFailureBlock(
    failure: ConnectFailure,
    attempted: String?,
    retrying: Boolean,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = DsTheme.colors
    val authority = attempted.orEmpty()
    val port = authority.substringAfterLast(':', "").toIntOrNull() ?: 0
    // `connect_failed` is formatted from the two halves so it reads as one address; feeding it the
    // whole authority plus an empty port left a trailing colon. Blank means there was nothing to
    // attempt (bad input), and a headline naming no address would say nothing.
    val title = when {
        failure is ConnectFailure.TrustFence -> stringResource(R.string.connect_fail_fence_title)
        authority.isBlank() -> null
        else -> stringResource(
            R.string.connect_failed,
            authority.substringBeforeLast(':', authority),
            port.toString(),
        )
    }
    val body = when (failure) {
        ConnectFailure.InvalidInput -> stringResource(R.string.connect_fail_invalid)
        is ConnectFailure.DifferentSubnet -> stringResource(
            R.string.connect_fail_subnet,
            authority,
            failure.localPrefix ?: stringResource(R.string.connect_unreachable),
        )
        ConnectFailure.Timeout -> stringResource(R.string.connect_fail_timeout, authority, port)
        ConnectFailure.Refused -> stringResource(R.string.connect_fail_refused, authority)
        ConnectFailure.TrustFence -> stringResource(R.string.connect_failed_fence)
        ConnectFailure.Unauthenticated -> stringResource(R.string.connect_fail_unauthenticated, authority)
        ConnectFailure.DnsFailure -> stringResource(R.string.connect_fail_dns, authority)
        ConnectFailure.NotAHarness -> stringResource(R.string.connect_fail_not_harness, authority)
        ConnectFailure.TlsFailure -> stringResource(R.string.connect_fail_tls, authority)
        ConnectFailure.StreamsBlocked -> stringResource(R.string.connect_fail_streams, authority)
        is ConnectFailure.Other -> stringResource(R.string.connect_fail_other, authority, failure.detail)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Column(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(StateDotState.Error, size = 8.dp)
                Spacer(Modifier.width(DsSpacing.xsmall))
                Text(
                    title ?: body,
                    style = DsType.std14,
                    color = colors.warnLabel,
                )
            }
            // With no headline the body has already been shown beside the dot.
            if (title != null) Text(body, style = DsType.small13, color = colors.warnLabel)
            // A harness that has not signed this phone in is fixed here rather than on the
            // harness: it wants a launch token, and retrying without one only repeats the 401.
            if (failure is ConnectFailure.Unauthenticated) {
                DsButton(
                    text = stringResource(R.string.connect_sign_in),
                    onClick = onSignIn,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
            // The loop backs off and retries forever; without this there is no way to stop it.
            if (retrying) {
                DsButton(
                    text = stringResource(R.string.connect_cancel),
                    onClick = onCancel,
                    variant = DsButtonVariant.Ghost,
                    size = DsButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun ConnectAuthorizationPendingBlock(message: String) {
    val colors = DsTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.warnTertiary,
    ) {
        Row(
            modifier = Modifier.padding(DsSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
        ) {
            StateDot(StateDotState.Warning, size = 8.dp)
            Text(message, style = DsType.small13, color = colors.warnLabel)
        }
    }
}

/**
 * The launch-token prompt.
 *
 * Harness 0.1.2 signs a browser in by exchanging a token it prints once per process, and accepts
 * that token only on its index route — so there is no way for a connection attempt to do this on
 * its own, and no way to skip it. The field takes the whole startup line as readily as the bare
 * token, because that is what people actually copy.
 */
@Composable
private fun LaunchTokenDialog(
    signingIn: Boolean,
    error: SignInError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = DsTheme.colors
    var input by remember { mutableStateOf("") }
    DsDialog(title = stringResource(R.string.connect_sign_in_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.connect_sign_in_body),
            style = DsType.small13,
            color = colors.labelSecondary,
        )
        TextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            enabled = !signingIn,
            label = { Text(stringResource(R.string.connect_sign_in_label)) },
            colors = connectFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        val message = when (error) {
            SignInError.Refused -> stringResource(R.string.connect_sign_in_refused)
            SignInError.Unreachable -> stringResource(R.string.connect_sign_in_unreachable)
            SignInError.NoHost -> stringResource(R.string.connect_sign_in_no_host)
            null -> null
        }
        if (message != null) {
            Text(message, style = DsType.caption11, color = colors.warnLabel)
        }
        DsButton(
            text = stringResource(R.string.connect_sign_in),
            onClick = { onSubmit(input) },
            enabled = !signingIn && input.isNotBlank(),
            variant = DsButtonVariant.Info,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * tsnet keeps the device identity alive while this page authenticates it. Polling the retained
 * node lets us close this dialog and continue the original connection as soon as it is running.
 */
@Composable
private fun TailscaleLoginDialog(
    loginUrl: String,
    onDismiss: () -> Unit,
) {
    DsDialog(title = "Sign in to Tailscale", onDismiss = onDismiss) {
        Text(
            "Complete Tailscale sign-in to finish connecting automatically.",
            style = DsType.small13,
            color = DsTheme.colors.labelSecondary,
        )
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(loginUrl)
                }
            },
        )
    }
}

/** Determinate sweep feedback: a /24 takes long enough that a static label reads as a hang. */
@Composable
private fun ScanProgressRow(progress: ScanProgress?, onCancel: () -> Unit) {
    val colors = DsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (progress == null) {
                    stringResource(R.string.connect_scanning)
                } else {
                    stringResource(R.string.connect_scan_progress, progress.probed, progress.total)
                },
                style = DsType.std14,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = DsButtonVariant.Ghost,
                size = DsButtonSize.Small,
            )
        }
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.probed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.hoverSolid,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(4.dp))
        }
    }
}

/** Last path segment of a host cwd, so a card can name the project rather than print a full path. */
private fun basename(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }
