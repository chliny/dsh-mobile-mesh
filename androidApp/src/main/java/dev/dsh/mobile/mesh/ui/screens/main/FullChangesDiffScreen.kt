package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

@Composable
internal fun FullChangesDiffScreen(
    store: SessionStore,
    sessionId: String,
    seq: Long,
    index: Int,
    path: String,
    title: String,
    added: Int,
    deleted: Int,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp)) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
                tint = DsTheme.colors.labelSecondary,
                iconSize = 18.dp,
            )
            Text(title, style = DsType.large20, modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text("+$added -$deleted", style = DsType.caption11, color = DsTheme.colors.labelTertiary, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            ChangedFileRow(
                store = store,
                sessionId = sessionId,
                seq = seq,
                index = index,
                path = path,
                display = title,
                added = added,
                deleted = deleted,
                onOpenFile = null,
                full = true,
            )
        }
    }
}
