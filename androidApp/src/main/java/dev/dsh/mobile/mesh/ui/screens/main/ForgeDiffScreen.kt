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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.forge.ForgeDiffRow
import dev.dsh.mobile.mesh.core.forge.ForgeReviewComment
import dev.dsh.mobile.mesh.core.forge.ForgeReviewSource
import dev.dsh.mobile.mesh.core.forge.forgeDiffRows
import dev.dsh.mobile.mesh.core.forge.forgeReviewAddPendingWrite
import dev.dsh.mobile.mesh.core.forge.forgeSubmitCommentRequest
import dev.dsh.mobile.mesh.core.forge.asLineComment
import dev.dsh.mobile.mesh.core.forge.forgeHunkText
import dev.dsh.mobile.mesh.core.forge.forgeReviewRange
import dev.dsh.mobile.mesh.core.forge.selectForgeComment
import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffFile
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalScope
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewData
import dev.dsh.mobile.mesh.core.wire.dto.ForgeReviewThread
import dev.dsh.mobile.mesh.ui.components.AppBottomSheet
import dev.dsh.mobile.mesh.ui.components.AppToastHost
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.KodeViewCode
import dev.dsh.mobile.mesh.ui.components.rememberAppToast
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import kotlinx.coroutines.launch

/** One same-side, same-hunk line range the reader is building. */
private data class ForgeLineSelection(
    val side: String,
    val anchor: Int,
    val end: Int,
    val hunkIndex: Int,
) {
    val start: Int get() = minOf(anchor, end)
    val last: Int get() = maxOf(anchor, end)
}

/**
 * One file's Host-computed diff, with line-number selection and composer references.
 *
 * The screen never computes a diff itself: it re-reads `forgePanel/localDiff` or
 * `forgePanel/changeRequestDiff` and shows the exact file the Host returned. A comment is captured
 * from the displayed hunk bytes, so a later refresh cannot silently rewrite an already-added
 * reference.
 */
@Composable
internal fun ForgeDiffScreen(
    sessionId: String,
    originKind: String,
    scopeKind: String,
    baseRef: String?,
    baseLabel: String?,
    commits: ForgeCommitRange?,
    changeUrl: String?,
    changeHeadSha: String?,
    path: String,
    title: String,
    onBack: () -> Unit,
) {
    val store = rememberForgeStore()
    val scope = remember(scopeKind, baseRef, commits) {
        when (scopeKind) {
            ForgeLocalScope.UNCOMMITTED -> ForgeLocalScope.uncommitted()
            ForgeLocalScope.UNSTAGED -> ForgeLocalScope.unstaged()
            ForgeLocalScope.STAGED -> ForgeLocalScope.staged()
            else -> ForgeLocalScope.branch(baseRef, commits)
        }
    }
    val clipboard = LocalClipboardManager.current
    val coroutine = rememberCoroutineScope()
    val (toastMessage, showToast) = rememberAppToast()
    var file by remember(sessionId, path) { mutableStateOf<ForgeDiffFile?>(null) }
    var headSha by remember(sessionId, path) { mutableStateOf(changeHeadSha) }
    var error by remember(sessionId, path) { mutableStateOf<RpcError?>(null) }
    var loading by remember(sessionId, path) { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var selection by remember(sessionId, path) { mutableStateOf<ForgeLineSelection?>(null) }
    var commentTarget by remember { mutableStateOf<ForgeReviewComment?>(null) }
    var commentBody by remember { mutableStateOf("") }
    var reviewData by remember(sessionId, changeUrl) { mutableStateOf<ForgeReviewData?>(null) }
    var reviewReload by remember { mutableIntStateOf(0) }
    var threadsOpen by remember { mutableStateOf(false) }
    var threadPathFilter by remember { mutableStateOf<String?>(null) }
    var threadIdsFilter by remember { mutableStateOf<Set<String>?>(null) }
    var submitReviewOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var expandedText by remember(sessionId, path, changeUrl) { mutableStateOf<String?>(null) }
    var expandLoading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.common_copied)
    val commentAddedLabel = stringResource(R.string.forge_comment_added)
    val commentEmptyLabel = stringResource(R.string.forge_comment_empty)

    LaunchedEffect(sessionId, path, reload) {
        // A refresh can move lines; a comment captured against the old patch must not be
        // submitted as if it still described the new one.
        selection = null
        commentTarget = null
        expandedText = null
    }

    LaunchedEffect(sessionId, path, originKind, scope, changeUrl, commits, reload) {
        loading = true
        error = null
        val result = if (originKind == "change") {
            val url = changeUrl
            if (url.isNullOrBlank()) {
                error = RpcError("forge/invalid-change", "No change request URL")
                loading = false
                return@LaunchedEffect
            }
            store.changeRequestDiff(sessionId, url, commits).let { result ->
                when (result) {
                    is RpcResult.Ok -> {
                        headSha = result.value.headSha
                        RpcResult.Ok(result.value.files)
                    }
                    is RpcResult.Err -> result
                }
            }
        } else {
            store.localDiff(sessionId, scope).let { result ->
                when (result) {
                    is RpcResult.Ok -> RpcResult.Ok(result.value.files)
                    is RpcResult.Err -> result
                }
            }
        }
        when (result) {
            is RpcResult.Ok -> {
                file = result.value.firstOrNull { it.path == path }
                    ?: result.value.firstOrNull { it.previousPath == path }
                if (file == null) error = RpcError("forge/file-missing", "This file is no longer in the diff")
            }
            is RpcResult.Err -> error = result.error
        }
        loading = false
    }

    LaunchedEffect(sessionId, changeUrl, headSha, reviewReload) {
        if (originKind != "change") return@LaunchedEffect
        val url = changeUrl ?: return@LaunchedEffect
        val head = headSha ?: return@LaunchedEffect
        when (val result = store.review(sessionId, url, head, fresh = reviewReload > 0)) {
            is RpcResult.Ok -> reviewData = result.value
            is RpcResult.Err -> reviewData = null
        }
    }

    fun expand() {
        if (expandedText != null || expandLoading) return
        expandLoading = true
        coroutine.launch {
            val result = if (originKind == "change") {
                val url = changeUrl
                val head = headSha
                if (url == null || head == null) RpcResult.Err(RpcError("forge/invalid-change", "No change request URL"))
                else store.changeRequestText(sessionId, url, head, path)
            } else {
                store.localText(sessionId, scope, path)
            }
            when (result) {
                is RpcResult.Ok -> expandedText = result.value
                is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
            }
            expandLoading = false
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            file?.let { loaded ->
                Text("+${loaded.additions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("-${loaded.deletions}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(
                onClick = {
                    reload++
                    reviewReload++
                },
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.forge_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.forge_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.forge_copy_path)) },
                        leadingIcon = { Icon(FeatherIcons.Code, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(path))
                            showToast(copiedLabel)
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(forgeErrorText(error!!), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = {
                            reload++
                            reviewReload++
                        },
                    ) { Text(stringResource(R.string.common_retry)) }
                }
                loading || file == null -> Text(
                    stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                file!!.binary -> Text(
                    stringResource(R.string.forge_binary_no_diff),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                file!!.oversized -> Text(
                    stringResource(R.string.forge_file_too_large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                file!!.hunks.isEmpty() -> Text(
                    stringResource(R.string.forge_no_text_hunks),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> ForgeDiffBody(
                    file = file!!,
                    selection = selection,
                    threads = reviewData?.threads?.filter { it.path == file!!.path }.orEmpty(),
                    expandedText = expandedText,
                    expandLoading = expandLoading,
                    onExpand = ::expand,
                    onOpenThreads = { threads ->
                        threadPathFilter = file?.path
                        threadIdsFilter = threads.mapTo(HashSet()) { it.id }
                        threadsOpen = true
                    },
                    onSelectLine = { side, hunkIndex, line ->
                        val current = selection
                        selection = when {
                            current == null || current.side != side || current.hunkIndex != hunkIndex ->
                                ForgeLineSelection(side, line, line, hunkIndex)
                            current.anchor == line && current.end == line -> null
                            else -> current.copy(end = line)
                        }
                    },
                )
            }
        }

        val pendingReview = reviewData?.pendingReview
        if (pendingReview != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.forge_review_pending_count, pendingReview.comments),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { submitReviewOpen = true }) {
                        Text(stringResource(R.string.forge_review_submit))
                    }
                }
            }
        }
        selection?.let { range ->
            file?.let { loaded ->
                val comment = selectForgeComment(loaded, range.side, range.start, range.last)
                if (comment != null) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "${file?.path?.substringAfterLast('/')} ${forgeReviewRange(comment)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = {
                                    commentTarget = comment
                                    commentBody = ""
                                },
                            ) { Text(stringResource(R.string.forge_comment_action)) }
                        }
                    }
                }
            }
        }
        AppToastHost(toastMessage to showToast, modifier = Modifier.fillMaxWidth())
    }

    if (threadsOpen && reviewData != null && changeUrl != null && headSha != null) {
        ForgeThreadsSheet(
            sessionId = sessionId,
            url = changeUrl,
            headSha = headSha!!,
            data = reviewData,
            showToast = showToast,
            onChanged = { reviewReload++ },
            onDismiss = { threadsOpen = false },
            pathFilter = threadPathFilter,
            threadIds = threadIdsFilter,
        )
    }
    if (submitReviewOpen && reviewData?.pendingReview != null && changeUrl != null && headSha != null) {
        ForgeSubmitReviewSheet(
            sessionId = sessionId,
            url = changeUrl,
            headSha = headSha!!,
            data = reviewData!!,
            showToast = showToast,
            onChanged = { reviewReload++ },
            onDismiss = { submitReviewOpen = false },
        )
    }
    commentTarget?.let { target ->
        val source: ForgeReviewSource = if (originKind == "change") {
            ForgeReviewSource.Change(
                url = changeUrl.orEmpty(),
                headSha = headSha ?: changeHeadSha.orEmpty(),
                commits = commits,
            )
        } else {
            ForgeReviewSource.Local(
                scope = scopeKind,
                baseLabel = baseLabel,
                baseRef = baseRef,
                commits = commits,
            )
        }
        AppBottomSheet(
            title = stringResource(R.string.forge_comment_title, forgeReviewRange(target)),
            onDismiss = { commentTarget = null },
        ) {
            Text(target.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
            ) {
                Text(
                    target.hunk,
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
            val review = reviewData?.takeIf { originKind == "change" && it.viewerCanReview }
            val pending = review?.pendingReview
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                val comment = target.copy(body = commentBody.trim())
                                store.addCommentReference(sessionId, source, comment)
                                showToast(commentAddedLabel)
                                commentTarget = null
                                selection = null
                            }
                        },
                    ) { Text(stringResource(R.string.forge_comment_add_to_input)) }
                }
                if (review != null && headSha != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (pending == null) {
                            TextButton(
                                onClick = {
                                    if (commentBody.isBlank()) {
                                        showToast(commentEmptyLabel)
                                    } else {
                                        coroutine.launch {
                                            val line = target.copy(body = commentBody.trim()).asLineComment()
                                            when (val result = store.submitReview(
                                                sessionId,
                                                changeUrl.orEmpty(),
                                                forgeSubmitCommentRequest(headSha!!, line),
                                            )) {
                                                is RpcResult.Ok -> {
                                                    val value = result.value
                                                    if (value.ok) {
                                                        showToast(context.getString(R.string.forge_review_published))
                                                        commentTarget = null
                                                        selection = null
                                                        reviewReload++
                                                    } else {
                                                        showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                                                    }
                                                }
                                                is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                                            }
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.forge_review_publish_comment)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (commentBody.isBlank()) {
                                    showToast(commentEmptyLabel)
                                } else {
                                    coroutine.launch {
                                        val line = target.copy(body = commentBody.trim()).asLineComment()
                                        when (val result = store.reviewWrite(
                                            sessionId,
                                            changeUrl.orEmpty(),
                                            forgeReviewAddPendingWrite(headSha!!, line),
                                        )) {
                                            is RpcResult.Ok -> {
                                                val value = result.value
                                                if (value.ok) {
                                                    showToast(context.getString(R.string.forge_review_added_pending))
                                                    commentTarget = null
                                                    selection = null
                                                    reviewReload++
                                                } else {
                                                    showToast(forgeReviewWriteErrorText(context, value.code, value.message))
                                                }
                                            }
                                            is RpcResult.Err -> showToast(forgeErrorTextValue(context, result.error))
                                        }
                                    }
                                }
                            },
                            ) {
                                Text(
                                    stringResource(
                                        if (pending == null) R.string.forge_review_start_pending else R.string.forge_review_add_to_pending,
                                    ),
                                )
                            }
                    }
                }
            }
        }
    }
}

private sealed interface ForgeDiffEntry {
    data class Header(val hunkIndex: Int) : ForgeDiffEntry
    data class Line(val row: ForgeDiffRow, val code: String, val serial: Int) : ForgeDiffEntry
    data class Threads(val threads: List<ForgeReviewThread>) : ForgeDiffEntry
    /** Unchanged lines the reader can load from the new version of the file. */
    data class Gap(val start: Int, val end: Int?) : ForgeDiffEntry
}

@Composable
private fun ForgeDiffBody(
    file: ForgeDiffFile,
    selection: ForgeLineSelection?,
    threads: List<ForgeReviewThread>,
    expandedText: String?,
    expandLoading: Boolean,
    onExpand: () -> Unit,
    onOpenThreads: (List<ForgeReviewThread>) -> Unit,
    onSelectLine: (side: String, hunkIndex: Int, line: Int) -> Unit,
) {
    val rows = remember(file) { forgeDiffRows(file.hunks) }
    val textLines = remember(expandedText) { expandedText?.let(::forgeTextLines).orEmpty() }
    val entries = remember(file, threads, textLines, rows, expandedText) {
        fun gapAfter(hunk: dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffHunk): Int =
            if (hunk.newLines == 0) hunk.newStart + 1 else hunk.newStart + hunk.newLines
        fun gapEndBefore(hunk: dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffHunk): Int =
            if (hunk.newLines == 0) hunk.newStart else hunk.newStart - 1
        val result = mutableListOf<ForgeDiffEntry>()
        val fileThreads = threads.filter { it.subject == "file" }
        val canExpand = file.status != "deleted"
        if (fileThreads.isNotEmpty()) result += ForgeDiffEntry.Threads(fileThreads)
        val matchedIds = mutableSetOf<String>()
        var serial = 0
        fun addGap(start: Int, end: Int?) {
            if (start <= 0) return
            if (end != null && end < start) return
            if (expandedText != null) {
                val last = (end ?: textLines.size).coerceAtMost(textLines.size)
                if (last >= start) {
                    for (line in start..last) {
                        val code = textLines[line - 1]
                        result += ForgeDiffEntry.Line(
                            row = ForgeDiffRow(hunkIndex = -1, marker = ' ', text = code, oldLine = null, newLine = line),
                            code = code,
                            serial = serial++,
                        )
                    }
                }
            } else {
                result += ForgeDiffEntry.Gap(start, end)
            }
        }
        fun addLine(row: ForgeDiffRow) {
            result += ForgeDiffEntry.Line(row, row.text, serial++)
            val atLine = threads.filter { thread ->
                val current = thread.current ?: return@filter false
                thread.subject == "line" && (
                    (thread.side == "L" && row.oldLine == current.endLine) ||
                        (thread.side == "R" && row.newLine == current.endLine)
                    )
            }
            if (atLine.isNotEmpty()) {
                matchedIds += atLine.map { it.id }
                result += ForgeDiffEntry.Threads(atLine)
            }
        }
        var previous: dev.dsh.mobile.mesh.core.wire.dto.ForgeDiffHunk? = null
        var rowIndex = 0
        while (rowIndex < rows.size) {
            val hunkIndex = rows[rowIndex].hunkIndex
            val hunk = file.hunks[hunkIndex]
            if (canExpand) {
                val gapStart = previous?.let(::gapAfter) ?: 1
                addGap(gapStart, gapEndBefore(hunk))
            }
            result += ForgeDiffEntry.Header(hunkIndex)
            while (rowIndex < rows.size && rows[rowIndex].hunkIndex == hunkIndex) {
                addLine(rows[rowIndex])
                rowIndex++
            }
            previous = hunk
        }
        if (canExpand) previous?.let { addGap(gapAfter(it), null) }
        val rest = threads.filter { it.subject == "line" && it.id !in matchedIds }
        if (rest.isNotEmpty()) result += ForgeDiffEntry.Threads(rest)
        result
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "hint") {
            Text(
                stringResource(R.string.forge_comment_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        items(entries.size, key = { index ->
            when (val entry = entries[index]) {
                is ForgeDiffEntry.Header -> "h:${entry.hunkIndex}"
                is ForgeDiffEntry.Line -> "l:${entry.serial}"
                is ForgeDiffEntry.Threads -> "t:${entry.threads.joinToString(",") { it.id }}"
                is ForgeDiffEntry.Gap -> "g:${entry.start}:${entry.end}"
            }
        }) { index ->
            when (val entry = entries[index]) {
                is ForgeDiffEntry.Header -> {
                    val hunk = file.hunks[entry.hunkIndex]
                    Text(
                        forgeHunkText(hunk).substringBefore('\n'),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 5.dp),
                        maxLines = 1,
                    )
                }
                is ForgeDiffEntry.Line -> ForgeDiffLineRow(
                    row = entry.row,
                    code = entry.code,
                    path = file.path,
                    selection = selection,
                    onSelectLine = onSelectLine,
                )
                is ForgeDiffEntry.Threads -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = entry.threads.any { !it.resolved },
                        onClick = { onOpenThreads(entry.threads) },
                        label = { Text(stringResource(R.string.forge_discussions_count, entry.threads.size)) },
                    )
                }
                is ForgeDiffEntry.Gap -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = if (entry.end != null) {
                        stringResource(R.string.forge_expand_lines, entry.end - entry.start + 1)
                    } else {
                        stringResource(R.string.forge_expand_remaining)
                    }
                    TextButton(onClick = onExpand, enabled = !expandLoading) {
                        Text(if (expandLoading) stringResource(R.string.forge_expand_loading) else label)
                    }
                }
            }
        }
    }
}

/** Split a Host text file into display lines without inventing an empty last line. */
private fun forgeTextLines(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n")
    if (normalized.isEmpty()) return emptyList()
    val lines = normalized.split('\n').toMutableList()
    if (normalized.endsWith('\n')) lines.removeAt(lines.lastIndex)
    return lines
}

@Composable
private fun ForgeDiffLineRow(
    row: ForgeDiffRow,
    code: String,
    path: String,
    selection: ForgeLineSelection?,
    onSelectLine: (side: String, hunkIndex: Int, line: Int) -> Unit,
) {
    val markerColor = when (row.marker) {
        '+' -> MaterialTheme.colorScheme.primary
        '-' -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = when (row.marker) {
        '+' -> MaterialTheme.colorScheme.primaryContainer
        '-' -> MaterialTheme.colorScheme.errorContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(Modifier.fillMaxWidth().background(background)) {
        ForgeLineGutter(
            number = row.oldLine,
            selected = selection != null && selection.side == "L" && row.oldLine?.let {
                it in selection.start..selection.last && selection.hunkIndex == row.hunkIndex
            } == true,
            onClick = { if (row.hunkIndex >= 0) row.oldLine?.let { onSelectLine("L", row.hunkIndex, it) } },
        )
        ForgeLineGutter(
            number = row.newLine,
            selected = selection != null && selection.side == "R" && row.newLine?.let {
                it in selection.start..selection.last && selection.hunkIndex == row.hunkIndex
            } == true,
            onClick = { if (row.hunkIndex >= 0) row.newLine?.let { onSelectLine("R", row.hunkIndex, it) } },
        )
        Text(
            row.marker.takeIf { it != '\\' }?.toString() ?: " ",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = markerColor,
            modifier = Modifier.width(12.dp),
        )
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)) {
            KodeViewCode(
                code = code,
                pathOrLanguage = path,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun ForgeLineGutter(number: Int?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(38.dp)
            .then(if (number != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .padding(vertical = 1.dp, horizontal = 2.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            number?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
