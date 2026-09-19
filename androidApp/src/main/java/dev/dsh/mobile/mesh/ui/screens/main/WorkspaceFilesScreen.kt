package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.DirectoryLevel
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.rememberWorkspaceFilesStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

internal fun normalizeWorkspaceFilesPath(path: String): String = path.trim().ifBlank { "." }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFilesScreen(
    workspaceKey: String,
    sessionId: String,
    initialPath: String = ".",
    rootTitle: String? = null,
    onBack: () -> Unit,
    onOpenFile: (String, String, String) -> Unit,
) {
    val store = rememberWorkspaceFilesStore()
    val state by store.state.collectAsStateWithLifecycle()
    var currentPath by remember { mutableStateOf(initialPath) }
    val level = state.levels[currentPath]
    val refreshing = level is DirectoryLevel.Loading

    fun reload() = store.list(workspaceKey, sessionId, currentPath, reload = true)

    BackHandler {
        if (currentPath == ".") onBack()
        else currentPath = currentPath.substringBeforeLast('/', ".")
    }
    LaunchedEffect(workspaceKey, sessionId, initialPath) {
        store.reset(workspaceKey)
        currentPath = initialPath
        // The workspace-files Remote accepts a workspace-relative path, but the root is
        // represented by "." rather than an empty file_path. Keep the UI's root sentinel
        // separate from the wire request so opening this screen never sends a blank path.
        store.list(workspaceKey, sessionId, normalizeWorkspaceFilesPath(initialPath))
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = {
                    if (currentPath == ".") onBack()
                    else currentPath = currentPath.substringBeforeLast('/', ".")
                },
                tint = DsTheme.colors.labelSecondary,
                iconSize = 18.dp,
            )
            Text(
                currentPath.trimEnd('/').takeIf { it.isNotBlank() && it != "." }?.substringAfterLast('/')
                    ?: rootTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.workspace_files_title),
                style = DsType.large20,
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = ::reload,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (level) {
                null, DirectoryLevel.Loading -> Text(stringResource(R.string.common_loading), modifier = Modifier.padding(16.dp))
                is DirectoryLevel.Failed -> Text(level.message, color = DsTheme.colors.labelSecondary, modifier = Modifier.padding(16.dp))
                is DirectoryLevel.Ready -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(level.listing.entries, key = { it.name }) { entry ->
                        val path = if (currentPath == ".") entry.name else "$currentPath/${entry.name}"
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (entry.type == "directory") {
                                    currentPath = path
                                    store.list(workspaceKey, sessionId, path)
                                } else onOpenFile(path, entry.name, currentPath)
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (entry.type == "directory") Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = DsTheme.colors.labelSecondary,
                            )
                            Text(entry.name, modifier = Modifier.padding(start = 12.dp), style = DsType.std14)
                        }
                    }
                }
            }
        }
    }
}
