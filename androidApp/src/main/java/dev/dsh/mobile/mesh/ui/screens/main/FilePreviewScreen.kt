package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.PreviewState
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.KodeViewCode
import dev.dsh.mobile.mesh.ui.components.TextMateCodeHighlighter
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAsset
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAssetForScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.components.markdownImagePath
import dev.dsh.mobile.mesh.ui.rememberWorkspaceFilesStore
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

private suspend fun resolveMarkdownImage(source: String, markdownPath: String, store: dev.dsh.mobile.mesh.data.WorkspaceFilesStore, sessionId: String): String? {
    val cleanSource = markdownImagePath(source) ?: return null
    if (cleanSource.startsWith("http://") || cleanSource.startsWith("https://") || cleanSource.startsWith("data:")) return cleanSource
    val parent = markdownPath.substringBeforeLast('/', "")
    val imagePath = listOf(parent, cleanSource).filter { it.isNotBlank() }.joinToString("/").replace("\\", "/")
    val contents = store.readTextContent(sessionId, imagePath) ?: return null
    if (!imagePath.lowercase().endsWith(".svg")) return null
    return "data:image/svg+xml;charset=utf-8,${android.util.Base64.encodeToString(contents.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)}"
}

internal fun isMarkdownPath(path: String): Boolean = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown", "mdown", "mkd", "rmd" -> true
    else -> false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(workspaceKey: String, sessionId: String, path: String, title: String, onBack: () -> Unit) {
    val store = rememberWorkspaceFilesStore()
    val state by store.state.collectAsStateWithLifecycle()
    val previewListState = rememberLazyListState()
    val context = LocalContext.current
    val assets = context.applicationContext.assets
    val grammarAsset = textMateGrammarAsset(path)
    val themeAsset = if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES) "textmate-dark.json" else "textmate-light.json"
    val preview = state.preview
    val firstChunk = (preview as? PreviewState.Text)?.chunks?.firstOrNull()
    val version = (preview as? PreviewState.Text)?.value?.version
    val highlightIdentity = listOf(workspaceKey, sessionId, path, themeAsset, version, firstChunk)
    val loaded by produceState<Pair<List<String?>, TextMateCodeHighlighter?>?>(null, workspaceKey, sessionId, path, themeAsset, version, firstChunk) {
        value = highlightIdentity to if (firstChunk != null && grammarAsset != null) withContext(Dispatchers.Default) {
            runCatching { TextMateCodeHighlighter(assets.open(grammarAsset), assets.open(themeAsset)) { scope ->
                textMateGrammarAssetForScope(scope)?.let(assets::open)
            } }
                .onFailure { Log.w("CodeHighlight", "Failed to load TextMate grammar for $path", it) }
                .getOrNull()
        } else null
    }
    val textMate = loaded?.takeIf { it.first == highlightIdentity }?.second
    var refreshing by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    LaunchedEffect(workspaceKey, sessionId, path) {
        store.reset(workspaceKey)
        store.readText(workspaceKey, sessionId, path)
    }
    LaunchedEffect(preview, workspaceKey, sessionId, path) {
        snapshotFlow {
            val layout = previewListState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.index to layout.totalItemsCount
        }.distinctUntilChanged().filter { (last, total) ->
            last != null && total > 0 && last >= total - 3
        }.collectLatest {
            if (preview is PreviewState.Text && !preview.value.eof) {
                store.loadNextPreviewPage(workspaceKey, sessionId, path)
            }
        }
    }
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
                is PreviewState.Text -> LazyColumn(
                    state = previewListState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    if (isMarkdownPath(path)) {
                        item {
                            MarkdownText(
                                preview.value.text,
                                modifier = Modifier.fillMaxWidth(),
                                imageResolver = { source ->
                                    resolveMarkdownImage(source, path, store, sessionId)
                                },
                            )
                        }
                    } else {
                        itemsIndexed(preview.chunks, key = { index, _ -> index }) { index, chunk ->
                            SelectionContainer {
                                KodeViewCode(
                                    code = chunk,
                                    pathOrLanguage = path,
                                    textMate = textMate,
                                    chunks = preview.chunks,
                                    chunkIndex = index,
                                    sourceVersion = version,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                is PreviewState.Bytes -> Text(stringResource(R.string.workspace_files_binary), color = DsTheme.colors.labelSecondary)
                is PreviewState.Failed -> Text(preview.message, color = DsTheme.colors.labelSecondary)
            }
        }
    }
}
