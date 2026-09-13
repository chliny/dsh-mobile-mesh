package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.PreviewState
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.SyntaxHighlightedCode
import dev.dsh.mobile.mesh.ui.rememberWorkspaceFilesStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(workspaceKey: String, sessionId: String, path: String, title: String, onBack: () -> Unit) {
    val store = rememberWorkspaceFilesStore()
    val state by store.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(workspaceKey, sessionId, path) {
        store.reset(workspaceKey)
        store.readText(workspaceKey, sessionId, path)
    }
    val preview = state.preview
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
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
        PullToRefreshBox(
            isRefreshing = refreshing || preview is PreviewState.Loading,
            onRefresh = {
                refreshing = true
                store.readText(workspaceKey, sessionId, path)
                refreshing = false
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (preview) {
                null, PreviewState.Loading -> Text(stringResource(R.string.common_loading))
                is PreviewState.Text -> SyntaxHighlightedCode(
                    path = path,
                    code = preview.value.text,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
                is PreviewState.Bytes -> Text(stringResource(R.string.workspace_files_binary), color = DsTheme.colors.labelSecondary)
                is PreviewState.Failed -> Text(preview.message, color = DsTheme.colors.labelSecondary)
            }
        }
    }
}
