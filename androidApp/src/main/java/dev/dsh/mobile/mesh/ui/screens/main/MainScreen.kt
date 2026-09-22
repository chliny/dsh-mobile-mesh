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
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import kotlinx.coroutines.launch

internal fun safePreviewPath(path: String, cwd: String?): String? {
    val clean = path.trim().trim('"').replace('\\', '/')
    if (clean.isBlank()) return null
    val root = cwd?.trim()?.trimEnd('/', '\\')?.replace('\\', '/')
    if (clean.startsWith("/", ignoreCase = false) || clean.matches(Regex("^[A-Za-z]:/.*"))) {
        if (!root.isNullOrBlank() && !clean.startsWith(root, ignoreCase = true)) return null
    }
    val relative = if (!root.isNullOrBlank() && clean.startsWith(root, ignoreCase = true)) {
        clean.substring(root.length).trimStart('/')
    } else {
        clean.trimStart('/')
    }
    return relative.takeIf { it.isNotBlank() && it != "." && !hasUnsafePreviewSegment(it) }
}

private fun hasUnsafePreviewSegment(path: String): Boolean =
    path.split('/').any { it == ".." }

/** The workspace-files API is workspace-relative; `.` is the only valid root request. */
internal fun workspaceFilesRootPath(sessionCwd: String?, workspacePath: String?): String = "."

internal fun workspaceFilesScopeSessionId(
    currentSessionId: String,
    workspaceSessionIds: List<String>?,
    sessions: List<dev.dsh.mobile.mesh.data.SessionRow>,
    workspacePath: String?,
): String {
    val candidates = workspaceSessionIds.orEmpty()
        .asSequence()
        .mapNotNull { id -> sessions.firstOrNull { it.sessionId == id } }
        .filter { !it.cwd.isNullOrBlank() }
    val normalizedWorkspace = workspacePath?.trim()?.trimEnd('/', '\\')
    return candidates.firstOrNull { row ->
        row.sessionId != currentSessionId &&
            normalizedWorkspace != null && row.cwd?.trim()?.trimEnd('/', '\\') == normalizedWorkspace
    }?.sessionId
        ?: candidates.firstOrNull { it.sessionId == currentSessionId }?.sessionId
        ?: candidates.firstOrNull()?.sessionId
        ?: currentSessionId
}

private sealed interface MainPage {
    data object Chat : MainPage
    data class Files(
        val path: String = ".",
        val rootTitle: String? = null,
        val rootPath: String = ".",
    ) : MainPage
    data class Preview(val path: String, val title: String, val returnPage: MainPage) : MainPage
}

internal data class MainPageRoute(
    val kind: String = "chat",
    val path: String = "",
    val title: String = "",
    val returnKind: String = "chat",
    val returnPath: String = ".",
    val rootTitle: String? = null,
    val rootPath: String = ".",
) : java.io.Serializable

private fun MainPage.toRoute(): MainPageRoute = when (this) {
    MainPage.Chat -> MainPageRoute()
    is MainPage.Files -> MainPageRoute("files", path, rootTitle.orEmpty(), rootTitle = rootTitle, rootPath = rootPath)
    is MainPage.Preview -> {
        val files = returnPage as? MainPage.Files
        MainPageRoute("preview", path, title, if (files == null) "chat" else "files", files?.path ?: ".", files?.rootTitle, files?.rootPath ?: ".")
    }
}

internal fun MainPageRoute.restoredKind(): String = when (kind) {
    "files", "preview" -> kind
    else -> "chat"
}

private fun MainPageRoute.toPage(): MainPage = when (restoredKind()) {
    "files" -> MainPage.Files(path, rootTitle, rootPath)
    "preview" -> MainPage.Preview(
        path,
        title,
        if (returnKind == "files") MainPage.Files(returnPath, rootTitle, rootPath) else MainPage.Chat,
    )
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
fun MainScreen(
    connectionPhase: ConnectionPhase,
    onOpenSessionList: () -> Unit,
    reconnectAttempt: Int,
    onReconnect: () -> Unit,
) {
    var savedPageRoute by rememberSaveable { mutableStateOf(MainPageRoute()) }
    var page by remember(savedPageRoute) { mutableStateOf(savedPageRoute.toPage()) }
    fun navigate(next: MainPage) {
        page = next
        savedPageRoute = next.toRoute()
    }

    var detailsOpen by remember { mutableStateOf(false) }
    val detailsWidth = 300.dp
    val scope = rememberCoroutineScope()

    val store = dev.dsh.mobile.mesh.ui.rememberSessionStore()
    val navigationState = dev.dsh.mobile.mesh.ui.rememberAppNavigationState()
    val sessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val visibleChatSessionId = sessionId.takeIf { page == MainPage.Chat }
    DisposableEffect(navigationState, visibleChatSessionId) {
        navigationState.setVisibleChatSession(visibleChatSessionId)
        onDispose { navigationState.setVisibleChatSession(null) }
    }
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    val currentWorkspace = sessionId?.let { sid -> workspaces.firstOrNull { sid in it.sessionIds } }
    val workspaceKey = currentWorkspace?.workspaceId ?: sessionId?.let { "session:$it" }
    val scopeSessionId = sessionId?.let { sid ->
        workspaceFilesScopeSessionId(sid, currentWorkspace?.sessionIds, sessions, currentWorkspace?.path)
    }
    val currentSession = sessions.firstOrNull { it.sessionId == sessionId }
    val currentCwd = currentSession?.cwd
    val currentWorkspacePath = workspaces.firstOrNull { sessionId != null && sessionId in it.sessionIds }?.path
    val rootPath = workspaceFilesRootPath(currentCwd, currentWorkspacePath)
    // The files API accepts `.` as the workspace root; never pass a blank session cwd through the UI.
    val rootDirectoryName = (currentCwd ?: currentWorkspacePath)
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    if (page != MainPage.Chat) {
        val sid = sessionId
        if (sid == null) {
            navigate(MainPage.Chat)
        } else {
            when (val current = page) {
                is MainPage.Files -> WorkspaceFilesScreen(
                    workspaceKey = workspaceKey ?: "session:$sid",
                    sessionId = scopeSessionId ?: sid,
                    initialPath = current.path,
                    rootPath = current.rootPath,
                    rootTitle = current.rootTitle,
                    onBack = { navigate(MainPage.Chat) },
                    onOpenFile = { path, title, parentPath ->
                        safePreviewPath(path, sessions.firstOrNull { it.sessionId == sid }?.cwd)?.let { safePath ->
                            navigate(MainPage.Preview(
                                safePath,
                                title,
                                MainPage.Files(parentPath, current.rootTitle, current.rootPath),
                            ))
                        }
                    },
                )
                is MainPage.Preview -> FilePreviewScreen(
                    workspaceKey = workspaceKey ?: "session:$sid",
                    sessionId = sid,
                    path = current.path,
                    title = current.title,
                    onBack = { navigate(current.returnPage) },
                )
                MainPage.Chat -> Unit
            }
            // WorkspaceFilesScreen and FilePreviewScreen own the system BackHandler. A parent
            // handler here would consume the same event and collapse the file stack to chat.
            return
        }
    }
    BackHandler { onOpenSessionList() }
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
                onOpenDetails = { detailsOpen = true },
                onOpenDrawer = onOpenSessionList,
                detailsOpen = detailsOpen,
                connectionPhase = connectionPhase,
                reconnectAttempt = reconnectAttempt,
                onReconnect = onReconnect,
                onOpenSubagent = { childId ->
                    val parentId = sessionId
                    if (parentId != null) {
                        detailsOpen = false
                        scope.launch { store.openSubagentSession(parentId, childId) }
                    }
                },
                onOpenFiles = {
                    navigate(MainPage.Files(path = rootPath, rootTitle = rootDirectoryName, rootPath = rootPath))
                },
                onOpenFile = { path, title ->
                    safePreviewPath(path, sessions.firstOrNull { it.sessionId == sessionId }?.cwd)?.let { safePath ->
                        navigate(MainPage.Preview(safePath, title, MainPage.Chat))
                    }
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
                    onOpenSubagent = { childId ->
                        val parentId = sessionId
                        if (parentId != null) {
                            detailsOpen = false
                            scope.launch { store.openSubagentSession(parentId, childId) }
                        }
                    },
                )
            }
        }
}
