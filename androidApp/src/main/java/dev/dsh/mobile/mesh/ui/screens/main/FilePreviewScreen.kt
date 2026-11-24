package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.data.PreviewState
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.components.AppToastHost
import dev.dsh.mobile.mesh.ui.components.KodeViewCode
import dev.dsh.mobile.mesh.ui.components.MarkdownText
import dev.dsh.mobile.mesh.ui.components.TextMateCodeHighlighter
import dev.dsh.mobile.mesh.ui.components.markdownImagePath
import dev.dsh.mobile.mesh.ui.components.rememberAppToast
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAsset
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAssetForScope
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.rememberWorkspaceFilesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

internal fun svgDataUrl(bytes: ByteArray): String =
    "data:image/svg+xml;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"

internal suspend fun resolveMarkdownImage(source: String, baseDirectory: String?, store: dev.dsh.mobile.mesh.data.WorkspaceFilesStore, sessionId: String): String? {
    val cleanSource = markdownImagePath(source) ?: return null
    if (cleanSource.startsWith("http://", ignoreCase = true) || cleanSource.startsWith("https://", ignoreCase = true) ||
        cleanSource.startsWith("data:", ignoreCase = true)) return cleanSource
    val path = markdownWorkspaceLinkPath(cleanSource)?.replace("\\", "/") ?: return null
    val imagePath = if (path.startsWith('/') || path.matches(Regex("^[A-Za-z]:/.*"))) path
        else if (!baseDirectory.isNullOrBlank()) "$baseDirectory/$path" else path
    // The workspace file API returns bytes but no MIME type. WebView needs an image MIME for data URLs.
    val mime = when (path.substringAfterLast('.', "").lowercase()) {
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        else -> return null
    }
    val bytes = store.readImageBytes(sessionId, imagePath) ?: return null
    return if (mime == "image/svg+xml") svgDataUrl(bytes)
        else "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
}

internal fun resolveMarkdownFileTarget(markdownPath: String, target: String): String? {
    val link = markdownWorkspaceLinkPath(target) ?: return null
    if (link.startsWith("/") || link.matches(Regex("^[A-Za-z]:[/\\\\].*"))) return link
    val parent = markdownPath.substringBeforeLast('/', "")
    return java.nio.file.Paths.get(parent, link).normalize().toString()
}

internal fun renderMarkdownPreview(path: String, line: Int?): Boolean = isMarkdownPath(path) && line == null

internal fun isMarkdownPath(path: String): Boolean = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "markdown", "mdown", "mkd", "rmd" -> true
    else -> false
}

/** One selected run of displayed lines, kept as indices so appending a page does not move it. */
internal data class PreviewLineRange(val anchor: Int, val end: Int) {
    val start: Int get() = minOf(anchor, end)
    val last: Int get() = maxOf(anchor, end)
}

/** `12` or `12–14`, matching the range labels the forge diff uses. */
internal fun previewLineRangeLabel(start: Int, end: Int): String =
    if (start == end) start.toString() else "$start–$end"

/** One frozen comment target: the selected range plus the exact excerpt the reader saw. */
internal data class PreviewCommentTarget(
    val startLine: Int,
    val endLine: Int,
    val excerpt: String,
)

/**
 * Map a displayed selection to file line numbers and its exact excerpt.
 *
 * The first displayed line is [lineOffset], not line 1: a preview opened at `path#L401` must
 * cite 401 even though the list starts at index zero. Returns null for a stale range that no
 * longer fits the visible text.
 */
internal fun previewCommentTarget(
    displayLines: List<String>,
    lineOffset: Int,
    range: PreviewLineRange,
): PreviewCommentTarget? {
    if (range.start < 0 || range.last >= displayLines.size) return null
    return PreviewCommentTarget(
        startLine = lineOffset + range.start,
        endLine = lineOffset + range.last,
        excerpt = displayLines.subList(range.start, range.last + 1).joinToString("\n"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    workspaceKey: String,
    sessionId: String,
    path: String,
    title: String,
    line: Int? = null,
    commentEnabled: Boolean = false,
    onBack: () -> Unit,
    onOpenFile: (String, String, Int?) -> Unit,
) {
    val store = rememberWorkspaceFilesStore()
    val forgeStore = rememberForgeStore()
    val state by store.state.collectAsStateWithLifecycle()
    val previewListState = androidx.compose.runtime.key(path, line) { rememberLazyListState() }
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val assets = context.applicationContext.assets
    val grammarAsset = textMateGrammarAsset(path)
    val themeAsset = if (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES) "textmate-dark.json" else "textmate-light.json"
    val preview = state.preview
    val displayLines = (preview as? PreviewState.Text)?.displayLines.orEmpty()
    val lineOffset = (preview as? PreviewState.Text)?.value?.offset ?: 1
    val firstLine = displayLines.firstOrNull()
    val version = (preview as? PreviewState.Text)?.value?.version
    val imageResolver: suspend (String) -> String? = remember(store, sessionId, path, version) {
        val directory = path.replace("\\", "/").substringBeforeLast('/', "")
        val resolve: suspend (String) -> String? = { source -> resolveMarkdownImage(source, directory, store, sessionId) }
        resolve
    }
    val highlightIdentity = listOf(workspaceKey, sessionId, path, themeAsset, version, firstLine)
    val loaded by produceState<Pair<List<String?>, TextMateCodeHighlighter?>?>(null, workspaceKey, sessionId, path, themeAsset, version, firstLine) {
        value = highlightIdentity to if (firstLine != null && grammarAsset != null) withContext(Dispatchers.Default) {
            runCatching { TextMateCodeHighlighter(assets.open(grammarAsset), assets.open(themeAsset)) { scope ->
                textMateGrammarAssetForScope(scope)?.let(assets::open)
            } }
                .onFailure { android.util.Log.w("CodeHighlight", "Failed to load TextMate grammar for $path", it) }
                .getOrNull()
        } else null
    }
    val textMate = loaded?.takeIf { it.first == highlightIdentity }?.second
    var refreshing by remember { mutableStateOf(false) }
    var selection by remember(path, line) { mutableStateOf<PreviewLineRange?>(null) }
    // A page can shrink after a refresh; a stale range would index past the new line list.
    val activeSelection = selection?.takeIf { it.last < displayLines.size }
    var commentTarget by remember { mutableStateOf<PreviewCommentTarget?>(null) }
    var commentBody by remember { mutableStateOf("") }
    val (toastMessage, showToast) = rememberAppToast()
    val commentAddedLabel = stringResource(R.string.forge_comment_added)
    val commentEmptyLabel = stringResource(R.string.forge_comment_empty)
    BackHandler(onBack = onBack)
    LaunchedEffect(workspaceKey, sessionId, path, line) {
        selection = null
        commentTarget = null
        store.reset(workspaceKey)
        store.readText(workspaceKey, sessionId, path, offset = line ?: 1)
    }
    // A refreshed Host version can move lines; a selection captured against the old text must
    // not be submitted as if it still described the new one.
    LaunchedEffect(version) {
        selection = null
        commentTarget = null
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PullToRefreshBox(
            isRefreshing = refreshing || preview is PreviewState.Loading,
            onRefresh = {
                refreshing = true
                selection = null
                commentTarget = null
                store.readText(workspaceKey, sessionId, path, offset = line ?: 1)
                refreshing = false
            },
            // The comment bar below must keep its own height; fillMaxSize here would let the
            // pull-to-refresh content consume the whole Column and push that bar off screen.
            modifier = Modifier.weight(1f),
        ) {
            when (preview) {
                null, PreviewState.Loading -> Text(stringResource(R.string.common_loading))
                is PreviewState.Text -> LazyColumn(
                    state = previewListState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    if (renderMarkdownPreview(path, line)) {
                        item {
                            MarkdownText(
                                preview.value.text,
                                modifier = Modifier.fillMaxWidth(),
                                imageResolver = imageResolver,
                                imageCacheScope = "preview:$workspaceKey:$sessionId:$path",
                                onOpenLink = { target ->
                                    resolveMarkdownFileTarget(path, target)?.let { linkedPath ->
                                        onOpenFile(linkedPath, linkedPath.substringAfterLast('/'), markdownFileLineTarget(target)?.line)
                                    } ?: runCatching { uriHandler.openUri(target) }
                                },
                            )
                        }
                    } else {
                        if (commentEnabled && displayLines.isNotEmpty()) {
                            item(key = "comment-hint") {
                                Text(
                                    stringResource(R.string.forge_comment_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
                        itemsIndexed(displayLines, key = { index, _ -> index }) { index, lineText ->
                            PreviewCodeLine(
                                number = lineOffset + index,
                                text = lineText,
                                path = path,
                                textMate = textMate,
                                lines = displayLines,
                                index = index,
                                sourceVersion = version,
                                selected = selection?.let { range -> index in range.start..range.last } == true,
                                selectable = commentEnabled,
                                onSelect = {
                                    val current = selection
                                    selection = when {
                                        current == null -> PreviewLineRange(index, index)
                                        current.anchor == index && current.end == index -> null
                                        else -> current.copy(end = index)
                                    }
                                },
                            )
                        }
                    }
                    state.previewPageError?.let { message ->
                        item(key = "preview-page-error") {
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
                is PreviewState.Bytes -> Text(stringResource(R.string.workspace_files_binary), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is PreviewState.Failed -> Text(preview.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!renderMarkdownPreview(path, line)) {
            activeSelection?.let { range ->
                if (commentEnabled && displayLines.isNotEmpty()) {
                    val start = lineOffset + range.start
                    val end = lineOffset + range.last
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${path.substringAfterLast('/')} ${previewLineRangeLabel(start, end)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = {
                                    previewCommentTarget(displayLines, lineOffset, range)?.let { target ->
                                        commentTarget = target
                                        commentBody = ""
                                    }
                                },
                            ) { Text(stringResource(R.string.forge_comment_action)) }
                        }
                    }
                }
            }
        }
        AppToastHost(toastMessage to showToast, modifier = Modifier.fillMaxWidth())
    }

    commentTarget?.let { target ->
        AppBottomSheet(
            title = stringResource(R.string.forge_comment_title, "${path.substringAfterLast('/')} ${previewLineRangeLabel(target.startLine, target.endLine)}"),
            onDismiss = { commentTarget = null },
        ) {
            Text(path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
            ) {
                Text(
                    target.excerpt,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                )
            }
            OutlinedTextField(
                value = commentBody,
                onValueChange = { commentBody = it },
                placeholder = { Text(stringResource(R.string.forge_comment_placeholder)) },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { commentTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (commentBody.isBlank()) {
                            showToast(commentEmptyLabel)
                        } else {
                            forgeStore.addFileCommentReference(
                                sessionId = sessionId,
                                path = path,
                                startLine = target.startLine,
                                endLine = target.endLine,
                                excerpt = target.excerpt,
                                body = commentBody.trim(),
                            )
                            showToast(commentAddedLabel)
                            commentTarget = null
                            selection = null
                        }
                    },
                ) { Text(stringResource(R.string.forge_comment_add_to_input)) }
            }
        }
    }
}

/** One numbered source line; the gutter is the hit target for comment selection. */
@Composable
private fun PreviewCodeLine(
    number: Int,
    text: String,
    path: String,
    textMate: TextMateCodeHighlighter?,
    lines: List<String>,
    index: Int,
    sourceVersion: String?,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
) {
    val gutterColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(Modifier.fillMaxWidth().background(background)) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .then(if (selectable) Modifier.clickable(onClick = onSelect) else Modifier)
                .padding(vertical = 1.dp, horizontal = 4.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = gutterColor,
                maxLines = 1,
            )
        }
        SelectionContainer(modifier = Modifier.weight(1f)) {
            KodeViewCode(
                code = text,
                pathOrLanguage = path,
                textMate = textMate,
                chunks = lines,
                chunkIndex = index,
                sourceVersion = sourceVersion,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            )
        }
    }
}
