package dev.dsh.mobile.mesh.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.core.session.ConversationSnapshot
import dev.dsh.mobile.mesh.data.WORKING_DIRECTORY_PROJECTION
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private fun isAbsolutePreviewPath(path: String): Boolean = path.startsWith('/') || path.matches(Regex("^[A-Za-z]:/.*"))

private fun cleanPreviewPath(path: String): String? = path.replace('\\', '/').takeIf { clean ->
    clean.isNotEmpty() && clean != "." && !clean.startsWith("//") &&
        clean.none { it.isISOControl() } && clean.split('/').none { it == ".." }
}

internal fun safePreviewPath(path: String, cwd: String?): String? {
    val clean = cleanPreviewPath(path) ?: return null
    if (!isAbsolutePreviewPath(clean)) return clean
    val root = cwd?.replace('\\', '/')?.let { value ->
        if (value == "/" || value.matches(Regex("^[A-Za-z]:/$"))) value else value.trimEnd('/')
    }?.takeIf { it.isNotEmpty() } ?: return null
    val isWindowsRoot = root.matches(Regex("^[A-Za-z]:/.*"))
    if (!clean.startsWith(root, ignoreCase = isWindowsRoot) ||
        clean.length <= root.length || (!root.endsWith('/') && clean[root.length] != '/')) return null
    // Old Hosts may report a moved working directory but still resolve relative file paths
    // from the original header directory. Preserve explicit paths and let the Host read them.
    return clean
}

/** A selected Session's file requests must not share a cache with a different checkout. */
internal data class SessionFileScope(val sessionId: String, val workingDirectory: String?) {
    val cacheKey: String get() = "session:${sessionId.length}:$sessionId:${workingDirectory.orEmpty()}"
}

internal fun sessionFileScope(
    sessionId: String,
    conversation: ConversationSnapshot?,
    originalDirectory: String?,
): SessionFileScope {
    // The baseline/control value is an absolute path or null. Only absence permits a
    // legacy fallback; null means the Host has no recorded directory for this Session.
    val directory = conversationForSelectedSession(conversation, sessionId)?.projections?.get(WORKING_DIRECTORY_PROJECTION)
    val workingDirectory = when (directory) {
        null -> originalDirectory
        is JsonPrimitive -> directory.content.takeIf { directory.isString && it.isNotBlank() }
        else -> null
    }
    return SessionFileScope(sessionId, workingDirectory)
}

private sealed interface MainPage {
    data object Chat : MainPage
    data object Terminal : MainPage
    data class Files(
        val path: String = ".",
        val rootTitle: String? = null,
        val rootPath: String = ".",
    ) : MainPage
    data class Preview(val path: String, val title: String, val returnPage: MainPage, val line: Int? = null) : MainPage
    data class Diff(val seq: Long, val index: Int, val path: String, val title: String, val added: Int, val deleted: Int) : MainPage
}

internal data class MainPageRoute(
    val kind: String = "chat",
    val path: String = "",
    val title: String = "",
    val returnKind: String = "chat",
    val returnPath: String = ".",
    val rootTitle: String? = null,
    val rootPath: String = ".",
    val seq: Long = 0,
    val index: Int = 0,
    val added: Int = 0,
    val deleted: Int = 0,
    val line: Int? = null,
    val sessionId: String? = null,
) : java.io.Serializable

private fun MainPage.toRoute(): MainPageRoute = when (this) {
    MainPage.Chat -> MainPageRoute()
    MainPage.Terminal -> MainPageRoute("terminal")
    is MainPage.Files -> MainPageRoute("files", path, rootTitle.orEmpty(), rootTitle = rootTitle, rootPath = rootPath)
    is MainPage.Preview -> {
        val files = returnPage as? MainPage.Files
        MainPageRoute("preview", path, title, if (files == null) "chat" else "files", files?.path ?: ".", files?.rootTitle, files?.rootPath ?: ".", line = line)
    }
    is MainPage.Diff -> MainPageRoute("diff", path, title, seq = seq, index = index, added = added, deleted = deleted)
}

internal fun MainPageRoute.restoredKind(): String = when (kind) {
    "files", "preview", "terminal", "diff" -> kind
    else -> "chat"
}

private fun MainPageRoute.toPage(): MainPage = when (restoredKind()) {
    "terminal" -> MainPage.Terminal
    "files" -> MainPage.Files(path, rootTitle, rootPath)
    "preview" -> MainPage.Preview(
        path,
        title,
        if (returnKind == "files") MainPage.Files(returnPath, rootTitle, rootPath) else MainPage.Chat,
        line,
    )
    "diff" -> MainPage.Diff(seq, index, path, title, added, deleted)
    else -> MainPage.Chat
}

/**
 * Session conversation shell:
 *  - the session list is a previous full-screen page, reached with the top-left button or Back
 *  - swipe left from the RIGHT edge opens the session Details panel
 *  - swipe right anywhere on the open Details panel closes it
 *
 * The details gesture detector only claims the drags it owns (leftward from the right edge
 * band, or any drag on the details area while it is open) and leaves every other horizontal drag
 * unconsumed. Horizontal edge drags are axis-orthogonal to the chat transcript's vertical scroll.
 */
@Composable
internal fun MainScreen(
    readingPositions: TranscriptReadingPositions,
    transcriptListStates: TranscriptListStates,
    disclosures: TranscriptDisclosures,
    connectionPhase: ConnectionPhase,
    onOpenSessionList: () -> Unit,
    reconnectAttempt: Int,
    onReconnect: () -> Unit,
) {
    var savedPageRoute by rememberSaveable { mutableStateOf(MainPageRoute()) }
    var page by remember(savedPageRoute) { mutableStateOf(savedPageRoute.toPage()) }

    var detailsOpen by remember { mutableStateOf(false) }
    var subagentParentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailsWidth = 300.dp
    val scope = rememberCoroutineScope()

    val store = dev.dsh.mobile.mesh.ui.rememberSessionStore()
    val connectionManager = dev.dsh.mobile.mesh.ui.rememberConnectionManager()
    val terminalAvailable by connectionManager.terminalAvailable.collectAsStateWithLifecycle()
    val navigationState = dev.dsh.mobile.mesh.ui.rememberAppNavigationState()
    val sessionId by store.currentSessionId.collectAsStateWithLifecycle()
    fun navigate(next: MainPage) {
        page = next
        savedPageRoute = next.toRoute().copy(sessionId = sessionId.takeUnless { next == MainPage.Chat })
    }
    val visibleChatSessionId = sessionId.takeIf { page == MainPage.Chat }
    DisposableEffect(navigationState, visibleChatSessionId) {
        navigationState.setVisibleChatSession(visibleChatSessionId)
        onDispose { navigationState.setVisibleChatSession(null) }
    }
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val conversation by store.currentConversation.collectAsStateWithLifecycle()
    val fileScope = sessionId?.let { sid ->
        sessionFileScope(sid, conversation, sessions.firstOrNull { it.sessionId == sid }?.cwd)
    }
    val currentCwd = fileScope?.workingDirectory
    val rootDirectoryName = currentCwd?.let(::basename)?.takeIf { it.isNotBlank() }
    if (page != MainPage.Chat) {
        if (fileScope == null || savedPageRoute.sessionId != fileScope.sessionId) {
            // A saved relative path must never be reinterpreted in a newly selected Session.
            navigate(MainPage.Chat)
        } else {
            val sid = fileScope.sessionId
            when (val current = page) {
                MainPage.Terminal -> TerminalScreen(sessionId = sid, onBack = { navigate(MainPage.Chat) })
                is MainPage.Files -> WorkspaceFilesScreen(
                    workspaceKey = fileScope.cacheKey,
                    sessionId = sid,
                    initialPath = current.path,
                    rootPath = current.rootPath,
                    rootTitle = rootDirectoryName,
                    onBack = { navigate(MainPage.Chat) },
                    onOpenFile = { path, title, parentPath ->
                        safePreviewPath(path, currentCwd)?.let { safePath ->
                            navigate(MainPage.Preview(
                                safePath,
                                title,
                                MainPage.Files(parentPath, current.rootTitle, current.rootPath),
                            ))
                        }
                    },
                )
                is MainPage.Preview -> FilePreviewScreen(
                    workspaceKey = fileScope.cacheKey,
                    sessionId = sid,
                    path = current.path,
                    title = current.title,
                    line = current.line,
                    onBack = { navigate(current.returnPage) },
                    onOpenFile = { linkedPath, linkedTitle, line ->
                        val safePath = safePreviewPath(linkedPath, currentCwd)
                        if (safePath != null) navigate(MainPage.Preview(safePath, linkedTitle, current, line))
                    },
                )
                is MainPage.Diff -> FullChangesDiffScreen(
                    store = store,
                    sessionId = sid,
                    seq = current.seq,
                    index = current.index,
                    path = current.path,
                    title = current.title,
                    added = current.added,
                    deleted = current.deleted,
                    onBack = { navigate(MainPage.Chat) },
                )
                MainPage.Chat -> Unit
            }
            // WorkspaceFilesScreen and FilePreviewScreen own the system BackHandler. A parent
            // handler here would consume the same event and collapse the file stack to chat.
            return
        }
    }
    BackHandler {
        val parentSessionId = subagentReturnSessionId(subagentParentSessionId)
        if (parentSessionId != null) {
            subagentParentSessionId = null
            scope.launch { store.openSession(parentSessionId) }
        } else {
            onOpenSessionList()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(detailsOpen) {
                val width = size.width.toFloat()
                    val edgeBandPx = 28.dp.toPx()
                    val detailsAreaPx = detailsWidth.toPx() * 0.9f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        // Claim only gestures this screen handles: leftward drags starting in
                        // the right edge band open details; drags starting on the open details
                        // panel close it. Other horizontal drags remain unconsumed.
                        val owned = if (!detailsOpen) {
                            startX >= width - edgeBandPx
                        } else {
                            startX <= detailsAreaPx
                        }
                        if (!owned) return@awaitEachGesture

                        var claimed = false
                        awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            // While closed, only a leftward drag from the right edge belongs here.
                            claimed = detailsOpen || overSlop < 0f
                            if (claimed) change.consume()
                        } ?: return@awaitEachGesture
                        if (!claimed) return@awaitEachGesture

                        var totalX = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) break
                            // A deeper scrollable claimed the drag; let it keep it.
                            if (change.isConsumed) break
                            totalX += change.positionChange().x
                            change.consume()
                            val threshold = width * 0.12f
                            if (!detailsOpen && totalX <= -threshold) {
                                detailsOpen = true
                                break
                            }
                            if (detailsOpen && totalX >= threshold) {
                                detailsOpen = false
                                break
                            }
                        }
                    }
                },
            ) {
            ChatScreen(
                readingPositions = readingPositions,
                transcriptListStates = transcriptListStates,
                disclosures = disclosures,
                onOpenDetails = { detailsOpen = true },
                onOpenDrawer = {
                    subagentParentSessionId = null
                    onOpenSessionList()
                },
                detailsOpen = detailsOpen,
                connectionPhase = connectionPhase,
                reconnectAttempt = reconnectAttempt,
                onReconnect = onReconnect,
                onOpenSubagent = { childId ->
                    val parentId = sessionId
                    if (parentId != null) {
                        detailsOpen = false
                        subagentParentSessionId = parentId
                        scope.launch { store.openSubagentSession(parentId, childId) }
                    }
                },
                terminalAvailable = terminalAvailable,
                fileScope = fileScope,
                onOpenTerminal = { navigate(MainPage.Terminal) },
                onOpenFiles = { navigate(MainPage.Files()) },
                onOpenFile = { path, title ->
                    safePreviewPath(path, currentCwd)?.let { safePath ->
                        navigate(MainPage.Preview(safePath, title, MainPage.Chat))
                    }
                },
                onOpenFileAtLine = { path, title, line ->
                    safePreviewPath(path, currentCwd)?.let { safePath ->
                        navigate(MainPage.Preview(safePath, title, MainPage.Chat, line))
                    }
                },
                onOpenChangedFile = { hostPath, title ->
                    // Unlike user/model links, this absolute address comes from a Host change record.
                    cleanPreviewPath(hostPath)?.takeIf(::isAbsolutePreviewPath)?.let { path ->
                        navigate(MainPage.Preview(path, title, MainPage.Chat))
                    }
                },
                onOpenChangedDiff = { seq, index, path, title, added, deleted ->
                    navigate(MainPage.Diff(seq, index, path, title, added, deleted))
                },
            )

            AnimatedVisibility(
                visible = detailsOpen,
                // Explicit spec: the platform default runs 300ms, which lags behind the drag the
                // panel is usually opened with.
                enter = slideInHorizontally(DsAnimations.panelSlide) { it },
                exit = slideOutHorizontally(DsAnimations.panelSlide) { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                DetailsPanel(
                    onClose = { detailsOpen = false },
                    modifier = Modifier.width(detailsWidth),
                    disclosures = DisclosureScope(disclosures, sessionId),
                    onOpenSubagent = { childId ->
                        val parentId = sessionId
                        if (parentId != null) {
                            detailsOpen = false
                            subagentParentSessionId = parentId
                            scope.launch { store.openSubagentSession(parentId, childId) }
                        }
                    },
                )
            }
        }
}
