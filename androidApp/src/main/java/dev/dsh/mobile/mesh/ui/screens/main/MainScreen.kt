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
import dev.dsh.mobile.mesh.core.wire.dto.ForgeCommitRange
import dev.dsh.mobile.mesh.core.wire.dto.ForgeLocalScope
import dev.dsh.mobile.mesh.data.WORKING_DIRECTORY_PROJECTION
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    data object Details : MainPage
    data class Files(
        val path: String = ".",
        val rootTitle: String? = null,
        val rootPath: String = ".",
    ) : MainPage
    data class Preview(val path: String, val title: String, val returnPage: MainPage, val line: Int? = null) : MainPage
    data class Diff(val seq: Long, val index: Int, val path: String, val title: String, val added: Int, val deleted: Int) : MainPage
    data class ForgeChanges(val returnPath: String = ".") : MainPage
    data class ForgeChangeRequest(val url: String) : MainPage
    data class ForgeDiff(
        val originKind: String,
        val scopeKind: String,
        val baseRef: String?,
        val baseLabel: String?,
        val commitsFrom: String?,
        val commitsTo: String?,
        val url: String?,
        val headSha: String?,
        val path: String,
        val title: String,
        val returnKind: String,
        val returnPath: String,
        val returnUrl: String?,
    ) : MainPage
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
    val forgeOrigin: String = "",
    val forgeScope: String = ForgeLocalScope.UNCOMMITTED,
    val forgeBaseRef: String? = null,
    val forgeBaseName: String? = null,
    val forgeCommitsFrom: String? = null,
    val forgeCommitsTo: String? = null,
    val forgeUrl: String? = null,
    val forgeHeadSha: String? = null,
    val forgeReturnKind: String = "",
    val forgeReturnPath: String = ".",
    val forgeReturnUrl: String? = null,
) : java.io.Serializable

private fun MainPage.toRoute(): MainPageRoute = when (this) {
    MainPage.Chat -> MainPageRoute()
    MainPage.Terminal -> MainPageRoute("terminal")
    MainPage.Details -> MainPageRoute("details")
    is MainPage.Files -> MainPageRoute("files", path, rootTitle.orEmpty(), rootTitle = rootTitle, rootPath = rootPath)
    is MainPage.Preview -> {
        val files = returnPage as? MainPage.Files
        MainPageRoute("preview", path, title, if (files == null) "chat" else "files", files?.path ?: ".", files?.rootTitle, files?.rootPath ?: ".", line = line)
    }
    is MainPage.Diff -> MainPageRoute("diff", path, title, seq = seq, index = index, added = added, deleted = deleted)
    is MainPage.ForgeChanges -> MainPageRoute("forge_changes", returnPath)
    is MainPage.ForgeChangeRequest -> MainPageRoute("forge_change", url, forgeUrl = url)
    is MainPage.ForgeDiff -> MainPageRoute(
        kind = "forge_diff",
        path = path,
        title = title,
        forgeOrigin = originKind,
        forgeScope = scopeKind,
        forgeBaseRef = baseRef,
        forgeBaseName = baseLabel,
        forgeCommitsFrom = commitsFrom,
        forgeCommitsTo = commitsTo,
        forgeUrl = url,
        forgeHeadSha = headSha,
        forgeReturnKind = returnKind,
        forgeReturnPath = returnPath,
        forgeReturnUrl = returnUrl,
    )
}

internal fun MainPageRoute.restoredKind(): String = when (kind) {
    "files", "preview", "terminal", "details", "diff", "forge_changes", "forge_change", "forge_diff" -> kind
    else -> "chat"
}

private fun MainPageRoute.toPage(): MainPage = when (restoredKind()) {
    "terminal" -> MainPage.Terminal
    "details" -> MainPage.Details
    "files" -> MainPage.Files(path, rootTitle, rootPath)
    "preview" -> MainPage.Preview(
        path,
        title,
        if (returnKind == "files") MainPage.Files(returnPath, rootTitle, rootPath) else MainPage.Chat,
        line,
    )
    "diff" -> MainPage.Diff(seq, index, path, title, added, deleted)
    "forge_changes" -> MainPage.ForgeChanges(path.ifEmpty { "." })
    "forge_change" -> MainPage.ForgeChangeRequest(forgeUrl ?: path)
    "forge_diff" -> MainPage.ForgeDiff(
        originKind = forgeOrigin,
        scopeKind = forgeScope,
        baseRef = forgeBaseRef,
        baseLabel = forgeBaseName,
        commitsFrom = forgeCommitsFrom,
        commitsTo = forgeCommitsTo,
        url = forgeUrl,
        headSha = forgeHeadSha,
        path = path,
        title = title,
        returnKind = forgeReturnKind.ifEmpty { "forge_changes" },
        returnPath = forgeReturnPath,
        returnUrl = forgeReturnUrl,
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

    var subagentParentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val store = dev.dsh.mobile.mesh.ui.rememberSessionStore()
    val forgeStore = rememberForgeStore()
    // Only the file-list entry's visibility belongs to this screen. The changes, PR and worktree
    // surfaces collect the full panel state themselves, so an ordinary tool result does not
    // recompose the transcript shell.
    val forgeChangesEntry by remember(forgeStore) {
        forgeStore.state
            .map { state ->
                state.available == true && state.panel?.checkout?.state != null &&
                    state.panel?.checkout?.state != "none"
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val connectionManager = dev.dsh.mobile.mesh.ui.rememberConnectionManager()
    val terminalAvailable by connectionManager.terminalAvailable.collectAsStateWithLifecycle()
    val navigationState = dev.dsh.mobile.mesh.ui.rememberAppNavigationState()
    val sessionId by store.currentSessionId.collectAsStateWithLifecycle()
    DisposableEffect(sessionId) {
        val token = sessionId?.let(forgeStore::observe)
        onDispose { token?.let(forgeStore::release) }
    }
    var forgeScope by rememberSaveable(sessionId) { mutableStateOf(ForgeLocalScope.UNCOMMITTED) }
    var forgeBaseRef by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeBaseName by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeCommitsFrom by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeCommitsTo by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeChangeCommitsFrom by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeChangeCommitsTo by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var forgeChangesReturnPath by rememberSaveable(sessionId) { mutableStateOf(".") }
    var forgeLens by rememberSaveable(sessionId) { mutableStateOf(FORGE_LAYER_ALL) }
    var forgeHideTests by rememberSaveable(sessionId) { mutableStateOf(false) }
    fun commitsRange(from: String?, to: String?): ForgeCommitRange? =
        if (from != null && to != null) ForgeCommitRange(from, to) else null
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
        if (page is MainPage.ForgeChanges || page is MainPage.ForgeChangeRequest || page is MainPage.ForgeDiff) {
            val sid = sessionId
            if (sid == null || savedPageRoute.sessionId != sid) {
                navigate(MainPage.Chat)
            } else {
                when (val current = page) {
                    is MainPage.ForgeChanges -> ForgeChangesScreen(
                        sessionId = sid,
                        scopeKind = forgeScope,
                        baseRef = forgeBaseRef,
                        baseName = forgeBaseName,
                        commits = commitsRange(forgeCommitsFrom, forgeCommitsTo),
                        lens = forgeLens,
                        hideTests = forgeHideTests,
                        onLensChange = { forgeLens = it },
                        onHideTestsChange = { forgeHideTests = it },
                        onBack = { navigate(MainPage.Files(current.returnPath)) },
                        onScopeChange = { kind ->
                            // The Web keeps the chosen base across scope switches; only the
                            // commit range belongs to one branch comparison.
                            forgeScope = kind
                            forgeCommitsFrom = null
                            forgeCommitsTo = null
                        },
                        onBaseChange = { branch ->
                            forgeBaseRef = branch?.ref
                            forgeBaseName = branch?.name
                            forgeCommitsFrom = null
                            forgeCommitsTo = null
                        },
                        onCommitsChange = { range ->
                            forgeCommitsFrom = range?.from
                            forgeCommitsTo = range?.to
                        },
                        onOpenDiff = { target ->
                            when (val origin = target.origin) {
                                is ForgeDiffOrigin.Local -> navigate(
                                    MainPage.ForgeDiff(
                                        originKind = "local",
                                        scopeKind = origin.scope.kind,
                                        baseRef = origin.scope.base,
                                        baseLabel = origin.baseLabel,
                                        commitsFrom = origin.commits?.from,
                                        commitsTo = origin.commits?.to,
                                        url = null,
                                        headSha = null,
                                        path = target.path,
                                        title = target.title,
                                        returnKind = "forge_changes",
                                        returnPath = current.returnPath,
                                        returnUrl = null,
                                    ),
                                )
                                is ForgeDiffOrigin.Change -> navigate(
                                    MainPage.ForgeDiff(
                                        originKind = "change",
                                        scopeKind = forgeScope,
                                        baseRef = null,
                                        baseLabel = null,
                                        commitsFrom = origin.commits?.from,
                                        commitsTo = origin.commits?.to,
                                        url = origin.url,
                                        headSha = origin.headSha,
                                        path = target.path,
                                        title = target.title,
                                        returnKind = "forge_change",
                                        returnPath = origin.url,
                                        returnUrl = origin.url,
                                    ),
                                )
                            }
                        },
                        onOpenChangeRequest = { request ->
                            forgeChangeCommitsFrom = null
                            forgeChangeCommitsTo = null
                            navigate(MainPage.ForgeChangeRequest(request.url))
                        },
                    )
                    is MainPage.ForgeChangeRequest -> ForgeChangeRequestScreen(
                        sessionId = sid,
                        url = current.url,
                        commits = commitsRange(forgeChangeCommitsFrom, forgeChangeCommitsTo),
                        onCommitsChange = { range ->
                            forgeChangeCommitsFrom = range?.from
                            forgeChangeCommitsTo = range?.to
                        },
                        lens = forgeLens,
                        hideTests = forgeHideTests,
                        onLensChange = { forgeLens = it },
                        onHideTestsChange = { forgeHideTests = it },
                        onBack = { navigate(MainPage.ForgeChanges(forgeChangesReturnPath)) },
                        onOpenDiff = { target ->
                            val origin = target.origin as? ForgeDiffOrigin.Change
                            if (origin != null) {
                                navigate(
                                    MainPage.ForgeDiff(
                                        originKind = "change",
                                        scopeKind = forgeScope,
                                        baseRef = null,
                                        baseLabel = null,
                                        commitsFrom = origin.commits?.from,
                                        commitsTo = origin.commits?.to,
                                        url = origin.url,
                                        headSha = origin.headSha,
                                        path = target.path,
                                        title = target.title,
                                        returnKind = "forge_change",
                                        returnPath = origin.url,
                                        returnUrl = origin.url,
                                    ),
                                )
                            }
                        },
                    )
                    is MainPage.ForgeDiff -> ForgeDiffScreen(
                        sessionId = sid,
                        originKind = current.originKind,
                        scopeKind = current.scopeKind,
                        baseRef = current.baseRef,
                        baseLabel = current.baseLabel,
                        commits = commitsRange(current.commitsFrom, current.commitsTo),
                        changeUrl = current.url,
                        changeHeadSha = current.headSha,
                        path = current.path,
                        title = current.title,
                        onBack = {
                            if (current.returnKind == "forge_change") {
                                navigate(MainPage.ForgeChangeRequest(current.returnUrl ?: current.url.orEmpty()))
                            } else {
                                navigate(MainPage.ForgeChanges(current.returnPath))
                            }
                        },
                    )
                    else -> Unit
                }
            }
            return
        }
        if (fileScope == null || savedPageRoute.sessionId != fileScope.sessionId) {
            // A saved relative path must never be reinterpreted in a newly selected Session.
            navigate(MainPage.Chat)
        } else {
            val sid = fileScope.sessionId
            when (val current = page) {
                MainPage.Terminal -> TerminalScreen(sessionId = sid, onBack = { navigate(MainPage.Chat) })
                MainPage.Details -> DetailsPanel(
                    onClose = { navigate(MainPage.Chat) },
                    disclosures = DisclosureScope(disclosures, sid),
                    onOpenSubagent = { childId ->
                        subagentParentSessionId = sid
                        scope.launch { store.openSubagentSession(sid, childId) }
                        navigate(MainPage.Chat)
                    },
                )
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
                    showChangesEntry = forgeChangesEntry,
                    onOpenChanges = { path ->
                        forgeChangesReturnPath = path
                        navigate(MainPage.ForgeChanges(path))
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
                is MainPage.ForgeChanges, is MainPage.ForgeChangeRequest, is MainPage.ForgeDiff -> Unit
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
    Box(modifier = Modifier.fillMaxSize()) {
            ChatScreen(
                readingPositions = readingPositions,
                transcriptListStates = transcriptListStates,
                disclosures = disclosures,
                onOpenDetails = { navigate(MainPage.Details) },
                onOpenDrawer = {
                    subagentParentSessionId = null
                    onOpenSessionList()
                },
                detailsOpen = false,
                connectionPhase = connectionPhase,
                reconnectAttempt = reconnectAttempt,
                onReconnect = onReconnect,
                onOpenSubagent = { childId ->
                    val parentId = sessionId
                    if (parentId != null) {
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

    }
}
