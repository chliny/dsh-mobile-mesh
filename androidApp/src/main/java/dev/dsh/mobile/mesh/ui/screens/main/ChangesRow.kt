package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.ChangesNode
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiff
import dev.dsh.mobile.mesh.core.wire.dto.ChangesSummary
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

@Composable
internal fun ChangesRow(node: ChangesNode, context: ChatNodeContext) {
    val store = context.store ?: return
    val sessionId = context.sessionId ?: return
    var summary by remember(node.seq) { mutableStateOf<ChangesSummary?>(null) }
    var error by remember(node.seq) { mutableStateOf<String?>(null) }
    var expanded by remember(node.seq) { mutableStateOf(false) }
    LaunchedEffect(sessionId, node.seq) {
        when (val result = store.loadChangesSummary(sessionId, node.seq)) {
            is RpcResult.Ok -> summary = result.value
            is RpcResult.Err -> error = result.error.message
        }
    }
    val title = stringResource(R.string.chat_changes)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        DisclosureRow(
            title = title,
            summary = summary?.let { stringResource(R.string.chat_changes_count, it.total, it.added, it.deleted) }
                ?: error ?: stringResource(R.string.chat_changes_loading),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
            summary?.files?.forEachIndexed { index, file ->
                ChangedFileRow(store, sessionId, node.seq, index, file.path, file.display, file.added, file.deleted, context.onOpenFile)
            }
        }
    }
}

@Composable
private fun ChangedFileRow(store: dev.dsh.mobile.mesh.data.SessionStore, sessionId: String, seq: Long, index: Int, path: String, display: String, added: Int, deleted: Int, onOpenFile: ((String, String) -> Unit)?) {
    var diff by remember(sessionId, seq, index) { mutableStateOf<ChangesDiff?>(null) }
    var loading by remember(sessionId, seq, index) { mutableStateOf(false) }
    LaunchedEffect(sessionId, seq, index) {
        loading = true
        when (val result = store.loadChangesDiff(sessionId, seq, index)) {
            is RpcResult.Ok -> diff = result.value
            is RpcResult.Err -> Unit
        }
        loading = false
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = onOpenFile != null) { onOpenFile?.invoke(path, display) }) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = DsTheme.colors.labelSecondary)
            Text(display, modifier = Modifier.weight(1f).padding(start = 8.dp), style = DsType.small13, color = DsTheme.colors.labelPrimary)
            Text("+$added -$deleted", style = DsType.caption11, color = DsTheme.colors.labelTertiary)
        }
        when (val value = diff) {
            is ChangesDiff.Text -> value.hunks.take(3).flatMap { it.lines }.take(12).forEach {
                Text(it, style = DsType.caption11, color = DsTheme.colors.labelSecondary, modifier = Modifier.padding(start = 24.dp))
            }
            is ChangesDiff.Unavailable -> Text(value.kind, style = DsType.caption11, color = DsTheme.colors.labelTertiary, modifier = Modifier.padding(start = 24.dp))
            null -> if (loading) Text(stringResource(R.string.chat_changes_loading), style = DsType.caption11, modifier = Modifier.padding(start = 24.dp))
        }
    }
}
