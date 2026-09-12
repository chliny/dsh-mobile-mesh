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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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

@Composable
fun WorkspaceFilesScreen(
    sessionId: String,
    initialPath: String = ".",
    onBack: () -> Unit,
    onOpenFile: (String, String) -> Unit,
) {
    val store = rememberWorkspaceFilesStore()
    val state by store.state.collectAsStateWithLifecycle()
    var currentPath by remember { mutableStateOf(initialPath) }
    BackHandler {
        if (currentPath == ".") onBack()
        else currentPath = currentPath.substringBeforeLast('/', ".")
    }
    LaunchedEffect(sessionId, initialPath) {
        store.reset(sessionId)
        currentPath = initialPath
        store.list(sessionId, initialPath)
    }
    val level = state.levels[currentPath]
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
            Text(stringResource(R.string.workspace_files_title), style = DsType.large20)
        }
        when (level) {
            null, DirectoryLevel.Loading -> Text(stringResource(R.string.common_loading), modifier = Modifier.padding(16.dp))
            is DirectoryLevel.Failed -> Text(level.message, color = DsTheme.colors.labelSecondary, modifier = Modifier.padding(16.dp))
            is DirectoryLevel.Ready -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(level.listing.entries, key = { it.name }) { entry ->
                    val path = if (currentPath == ".") entry.name else "$currentPath/${entry.name}"
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (entry.type == "directory") {
                                currentPath = path
                                store.list(sessionId, path)
                            }
                            else onOpenFile(path, entry.name)
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
