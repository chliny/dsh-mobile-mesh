package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.PreviewState
import dev.dsh.mobile.mesh.ui.rememberWorkspaceFilesStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

@Composable
fun FilePreviewScreen(sessionId: String, path: String, title: String, onBack: () -> Unit) {
    val store = rememberWorkspaceFilesStore()
    val state by store.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LaunchedEffect(sessionId, path) { store.readText(sessionId, path) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
                tint = DsTheme.colors.labelSecondary,
                iconSize = 18.dp,
            )
            Text(title, style = DsType.large20, modifier = Modifier.padding(start = 4.dp))
        }
        when (val preview = state.preview) {
            null, PreviewState.Loading -> Text(stringResource(R.string.common_loading))
            is PreviewState.Text -> Text(
                preview.value.text,
                style = DsType.mdCode,
                color = DsTheme.colors.labelPrimary,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
            is PreviewState.Bytes -> Text(stringResource(R.string.workspace_files_binary), color = DsTheme.colors.labelSecondary)
            is PreviewState.Failed -> Text(preview.message, color = DsTheme.colors.labelSecondary)
        }
    }
}
