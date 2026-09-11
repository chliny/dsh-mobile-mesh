package dev.dsh.mobile.mesh.ui.screens.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.HostConfig
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.rememberHostsStore
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

@Composable
fun ConnectionsScreen(
    onClose: () -> Unit,
    onAdd: () -> Unit,
) {
    val hostsStore = rememberHostsStore()
    val hosts by hostsStore.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
    val colors = DsTheme.colors
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    items(hosts, key = HostConfig::id) { host ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(host.name, style = DsType.std14Strong, color = colors.labelPrimary)
                                Text(host.displayAddress, style = DsType.caption11, color = colors.labelTertiary)
                            }
                            Text(
                                if (host.isLoopback) stringResource(R.string.connect_same_device) else "",
                                style = DsType.caption11,
                                color = colors.labelCaption,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            DsButton(
                text = stringResource(R.string.connect_add_connection),
                onClick = onAdd,
                variant = DsButtonVariant.Outline,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
