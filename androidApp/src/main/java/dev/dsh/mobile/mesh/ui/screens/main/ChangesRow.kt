package dev.dsh.mobile.mesh.ui.screens.main

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.session.ChangesNode
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiff
import dev.dsh.mobile.mesh.core.wire.dto.ChangesDiffHunk
import dev.dsh.mobile.mesh.core.wire.dto.ChangesSummary
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.KodeViewCode
import dev.dsh.mobile.mesh.ui.components.TextMateCodeHighlighter
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAsset
import dev.dsh.mobile.mesh.ui.components.textMateGrammarAssetForScope
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

internal data class DiffHighlightLine(val marker: Char?, val source: String, val chunkIndex: Int)
internal data class DiffHighlightPlan(val lines: List<DiffHighlightLine>, val chunks: List<String>, val markers: List<Char>, val resetAt: Set<Int>)

/** Build one unified diff stream; marker rows and tokenizer consume the same order. */
internal fun buildDiffHighlightPlan(hunks: List<ChangesDiffHunk>, maxLines: Int = 12): DiffHighlightPlan {
    val chunks = mutableListOf<String>()
    val resets = mutableSetOf<Int>()
    val mapped = mutableListOf<DiffHighlightLine>()
    var remaining = maxLines
    hunks.forEach { hunk ->
        val lines = hunk.lines.take(remaining)
        if (lines.isNotEmpty()) resets += chunks.size
        for (line in lines) {
            val marker = line.firstOrNull()?.takeIf { it == '+' || it == '-' || it == ' ' }
            val source = if (marker == null) line else line.drop(1)
            val index = chunks.size
            chunks += source
            mapped += DiffHighlightLine(marker, source, index)
        }
        remaining -= lines.size
    }
    return DiffHighlightPlan(mapped, chunks, mapped.map { it.marker ?: ' ' }, resets)
}

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
                ChangedFileRow(store, sessionId, node.seq, index, file.path, file.display, file.added, file.deleted, context.onOpenFile, onOpenDiff = { context.onOpenChangedDiff?.invoke(node.seq, index, file.path, file.display, file.added, file.deleted) })
            }
        }
    }
}

@Composable
internal fun ChangedFileRow(store: dev.dsh.mobile.mesh.data.SessionStore, sessionId: String, seq: Long, index: Int, path: String, display: String, added: Int, deleted: Int, onOpenFile: ((String, String) -> Unit)?, onOpenDiff: (() -> Unit)? = null, full: Boolean = false) {
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
    val context = LocalContext.current
    val assets = context.applicationContext.assets
    val grammarAsset = textMateGrammarAsset(path)
    val darkMode = isSystemInDarkTheme()
    val themeAsset = if (darkMode) "textmate-dark.json" else "textmate-light.json"
    val highlightIdentity = Triple(path, themeAsset, diff)
    val loaded by produceState<Pair<Triple<String, String, ChangesDiff?>, TextMateCodeHighlighter?>?>(null, path, themeAsset, diff) {
        value = highlightIdentity to if (diff is ChangesDiff.Text && grammarAsset != null) withContext(Dispatchers.Default) {
            runCatching { TextMateCodeHighlighter(assets.open(grammarAsset), assets.open(themeAsset)) { scope ->
                textMateGrammarAssetForScope(scope)?.let(assets::open)
            } }
                .onFailure { Log.w("CodeHighlight", "Failed to load TextMate grammar for changed file $path", it) }
                .getOrNull()
        } else null
    }
    val textMate = loaded?.takeIf { it.first == highlightIdentity }?.second
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!full) Row(Modifier.fillMaxWidth().clickable(enabled = onOpenFile != null) { onOpenFile?.invoke(path, display) }) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = DsTheme.colors.labelSecondary)
            Text(display, modifier = Modifier.weight(1f).padding(start = 8.dp), style = DsType.small13, color = DsTheme.colors.labelPrimary)
            Text("+$added -$deleted", style = DsType.caption11, color = DsTheme.colors.labelTertiary)
            if (onOpenDiff != null) Text("完整差异 ↗", modifier = Modifier.padding(start = 8.dp).clickable(onClick = onOpenDiff), style = DsType.caption11, color = DsTheme.colors.labelSecondary)
        }
        when (val value = diff) {
            is ChangesDiff.Text -> {
                val plan = remember(value, full) { buildDiffHighlightPlan(value.hunks, if (full) Int.MAX_VALUE else 12) }
                plan.lines.forEach { line ->
                    val markerColor = when (line.marker) {
                        '+' -> DsTheme.colors.success
                        '-' -> DsTheme.colors.error
                        else -> DsTheme.colors.labelTertiary
                    }
                    val background = when (line.marker) {
                        '+' -> DsTheme.colors.successTertiary
                        '-' -> DsTheme.colors.errorTertiary
                        else -> DsTheme.colors.codeBlockBg
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp).background(background)) {
                        Text("${line.marker ?: ' '} ", style = DsType.mdCode, color = markerColor)
                        CompositionLocalProvider(LocalTextStyle provides DsType.mdCode.copy(color = DsTheme.colors.labelPrimary)) {
                            KodeViewCode(
                                code = line.source,
                                pathOrLanguage = value.path,
                                textMate = textMate,
                                chunks = plan.chunks,
                                chunkIndex = line.chunkIndex,
                                resetAt = plan.resetAt,
                                diffMarkers = plan.markers,
                                sourceVersion = value,
                                darkMode = darkMode,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            is ChangesDiff.Unavailable -> Text(value.kind, style = DsType.caption11, color = DsTheme.colors.labelTertiary, modifier = Modifier.padding(start = 24.dp))
            null -> if (loading) Text(stringResource(R.string.chat_changes_loading), style = DsType.caption11, modifier = Modifier.padding(start = 24.dp))
        }
    }
}
