package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.core.wire.dto.DirectoryListing
import dev.dsh.mobile.mesh.data.WorkspaceRow
import dev.dsh.mobile.mesh.ui.components.DisclosureRow
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.components.DsDialog
import dev.dsh.mobile.mesh.ui.components.DsIconButton
import dev.dsh.mobile.mesh.ui.components.DsPill
import dev.dsh.mobile.mesh.ui.components.DsMenu
import dev.dsh.mobile.mesh.ui.components.EmptyHero
import dev.dsh.mobile.mesh.ui.components.MenuItem
import dev.dsh.mobile.mesh.ui.components.SectionHeader
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.relativeTime
import dev.dsh.mobile.mesh.ui.rememberHostsStore
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import dev.dsh.mobile.mesh.ui.theme.DsShapes
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SessionListConnectionStatusDot(phase: ConnectionPhase) {
    val state = when (phase) {
        ConnectionPhase.CONNECTED -> StateDotState.Done
        ConnectionPhase.RECONNECTING -> StateDotState.Warning
        ConnectionPhase.CONNECTING -> StateDotState.Running
        ConnectionPhase.DISCONNECTED -> StateDotState.Error
    }
    StateDot(state, size = 8.dp)
}

/** [dev.dsh.mobile.mesh.connection.HostsStore.sessionSort]: the workspace's own row order. */
private const val SORT_MANUAL = "manual"

/** [dev.dsh.mobile.mesh.connection.HostsStore.sessionSort]: most recently updated first. */
private const val SORT_UPDATED = "updated"
internal const val SESSION_PAGE_SIZE = 5

/**
 * The chat history: workspaces, their sessions, and search.
 *
 * Two rules keep it readable. Blank sessions are hidden — the harness treats a session with no turn
 * as scratch space and reuses it, so listing them just accumulates empty rows. And times are
 * relative, because a clock time cannot distinguish "an hour ago" from "last Tuesday".
 */
@Composable
fun ChatListDrawer(
    connectionPhase: ConnectionPhase,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = DsTheme.colors
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    val hostsStore = rememberHostsStore()

    val sessions by store.sessions.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    val workspacesLoaded by store.workspacesLoaded.collectAsStateWithLifecycle()
    val archivedIds by store.archivedSessionIds.collectAsStateWithLifecycle()
    val searchResults by store.searchResults.collectAsStateWithLifecycle()
    val contentSearchAvailable by store.contentSearchAvailable.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val hostInfo by store.hostInfo.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    // Persisted, not remembered: the order you read your sessions in is a preference, and it used
    // to reset every time the drawer was closed.
    val sessionSort by hostsStore.sessionSort.collectAsStateWithLifecycle(initialValue = SORT_MANUAL)
    val workspaceExpansion by hostsStore.workspaceExpansion.collectAsStateWithLifecycle(initialValue = emptyMap())
    val sortByRecency = sessionSort == SORT_UPDATED
    var newSessionOpen by remember { mutableStateOf(false) }
    var newWorkspaceOpen by remember { mutableStateOf(false) }
    val workspaceOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val childCollapsed = remember { mutableStateMapOf<String, Boolean>() }
    val visiblePageByWorkspace = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(query) {
        delay(250)
        store.search(query.trim())
    }

    // Local matching is not debounced: it is a string comparison over a list already in memory, and
    // making someone wait a quarter second for it is what made search feel like it did nothing.
    val visibleSessions = remember(sessions, archivedIds) {
        sessions.filter { session ->
            session.sessionId !in archivedIds &&
                !session.blank &&
                (session.origin != "subagent" || session.running)
        }
    }
    val searchHits = remember(visibleSessions, workspaces, query, searchResults) {
        deriveSearchResults(
            sessions = visibleSessions,
            workspaces = workspaces,
            archivedIds = archivedIds,
            query = query,
            contentHits = searchResults,
        )
    }

    // Blank sessions are scratch space the harness reuses. Active subagent transcripts remain
    // visible under their parent; completed subagents are omitted from grouped and search results.
    val listable = visibleSessions
    val sessionsById = sessions.associateBy { it.sessionId }
    val archivedSessions = sessions.filter { it.sessionId in archivedIds && it.origin != "subagent" }
    val workspaceSessionIds = workspaces.flatMap { it.sessionIds }.toSet()

    // Subagents nest under the session that spawned them. `origin` is the discriminator, not
    // `parentSessionId` — an ordinary fork sets a parent too, and a fork is a session in its own
    // right that belongs at the top level. Grouping is by *immediate* parent so a subagent that
    // spawned its own subagents nests to whatever depth the run actually reached; a child whose
    // parent is archived or blank attaches to the nearest ancestor still on screen instead of
    // disappearing with it.
    val childrenByParent = remember(listable) { indexSubagents(listable, sessionsById) }
    val nestedIds = remember(childrenByParent) {
        childrenByParent.values.flatten().mapTo(HashSet()) { it.sessionId }
    }
    // Every session between the open one and the root, so a subtree holding it opens by default.
    val openPath = remember(currentSessionId, sessionsById) {
        buildSet {
            var cursor = currentSessionId?.let { sessionsById[it] }
            while (cursor != null && add(cursor.sessionId)) {
                cursor = cursor.parentSessionId?.let { sessionsById[it] }
            }
        }
    }

    fun isExpanded(workspaceId: String): Boolean =
        workspaceOverrides[workspaceId] ?: workspaceExpansion[workspaceId] ?: false
    fun isChildExpanded(sessionId: String): Boolean = childCollapsed[sessionId]?.not() ?: (sessionId in openPath)
    fun toggleChildren(sessionId: String) {
        childCollapsed[sessionId] = !isChildExpanded(sessionId)
    }

    /** Depth-first expansion of one top-level session, honouring each row's collapse state. */
    fun subtree(root: SessionRow): List<Pair<SessionRow, Int>> {
        val out = mutableListOf<Pair<SessionRow, Int>>()
        fun walk(row: SessionRow, depth: Int) {
            out += row to depth
            val children = childrenByParent[row.sessionId].orEmpty()
            if (children.isEmpty() || !isChildExpanded(row.sessionId)) return
            val ordered = if (sortByRecency) children.sortedByDescending(SessionRow::updatedAt) else children
            ordered.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.sidebar)
            .safeDrawingPadding()
            .padding(horizontal = DsSpacing.medium),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = DsSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chatlist_title),
                style = DsType.large20,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier.size(DsSpacing.touchTarget),
                contentAlignment = Alignment.Center,
            ) {
                SessionListConnectionStatusDot(connectionPhase)
            }
            DsIconButton(
                icon = Icons.Filled.Search,
                contentDescription = stringResource(R.string.common_search),
                // Closing the field clears the query too: a hidden field holding text left the list
                // filtered by something no longer on screen.
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
                tint = if (searchOpen) colors.accent else colors.labelTertiary,
            )
            SortChip(sortByRecency) { next ->
                scope.launch { hostsStore.setSessionSort(if (next) SORT_UPDATED else SORT_MANUAL) }
            }
            AddMenu(
                onNewSession = { newSessionOpen = true },
                onNewWorkspace = { newWorkspaceOpen = true },
            )
            DsIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings_title),
                onClick = onOpenSettings,
                tint = colors.labelTertiary,
            )
        }

        // The search field folds away rather than permanently occupying a row of a phone-height
        // drawer, which is otherwise pure overhead for the common case.
        AnimatedVisibility(visible = searchOpen) {
            Column(modifier = Modifier.padding(top = DsSpacing.small)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.chatlist_search_hint), style = DsType.std14) },
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                )
                // Stated once, quietly, and only while searching. Most harnesses ship with the
                // content index off, so this is a normal capability note — not a failure.
                if (!contentSearchAvailable && query.isNotBlank()) {
                    Text(
                        stringResource(R.string.chatlist_search_content_off),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                        modifier = Modifier.padding(top = DsSpacing.tiny),
                    )
                }
            }
        }

        Spacer(Modifier.height(DsSpacing.small))

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (query.isNotBlank()) {
                item(key = "search-header") { SectionHeader(stringResource(R.string.common_search)) }
                if (searchHits.items.isEmpty()) {
                    item(key = "search-empty") {
                        Text(
                            stringResource(R.string.chatlist_search_empty),
                            style = DsType.std14,
                            color = colors.labelTertiary,
                            modifier = Modifier.padding(vertical = DsSpacing.small),
                        )
                    }
                }
                items(searchHits.items, key = { it.session.sessionId }) { hit ->
                    SearchResultRow(hit, store, scope, onClose)
                }
                if (searchHits.hasMore) {
                    item(key = "search-more") {
                        Text(
                            stringResource(R.string.chatlist_search_refine),
                            style = DsType.caption11,
                            color = colors.labelCaption,
                            modifier = Modifier.padding(vertical = DsSpacing.xsmall),
                        )
                    }
                }
                return@LazyColumn
            }

            var anyShown = false
            for (workspace in workspaces) {
                val roots = workspace.sessionIds
                    .mapNotNull { id -> listable.firstOrNull { it.sessionId == id } }
                    .filterNot { it.sessionId in nestedIds }
                    .let { if (sortByRecency) it.sortedByDescending(SessionRow::updatedAt) else it }
                if (roots.isEmpty()) continue
                anyShown = true
                // Workspace expansion is persisted per workspace; session-child expansion remains
                // a local UI choice and does not affect the workspace preference.
                val isCollapsed = !isExpanded(workspace.workspaceId)
                val orderedRoots = roots.sortedWith(
                    compareByDescending<SessionRow> { it.updatedAt }
                        .thenByDescending { it.sessionId },
                )
                val page = (visiblePageByWorkspace[workspace.workspaceId] ?: 1).coerceAtLeast(1)
                val pageRoots = orderedRoots.take(page * SESSION_PAGE_SIZE)
                val hasMore = pageRoots.size < orderedRoots.size
                item(key = "ws-${workspace.workspaceId}") {
                    WorkspaceHeader(
                        workspace = workspace,
                        collapsed = isCollapsed,
                        // Sessions, not sessions-plus-their-subagents: a subagent count belongs on
                        // the row that spawned them, where it says something.
                        sessionCount = orderedRoots.size,
                        onToggle = {
                             val expanded = !isExpanded(workspace.workspaceId)
                             workspaceOverrides[workspace.workspaceId] = expanded
                             scope.launch { hostsStore.setWorkspaceExpanded(workspace.workspaceId, expanded) }
                         },
                        store = store,
                        scope = scope,
                        onNewSession = {
                            scope.launch {
                                store.createSession(workspaceId = workspace.workspaceId)
                                onClose()
                            }
                        },
                    )
                }
                if (!isCollapsed) {
                    val flat = pageRoots.flatMap { subtree(it) }
                    items(flat, key = { it.first.sessionId }) { (session, depth) ->
                        Box(Modifier.animateItem()) {
                            SessionRowItem(
                                session = session,
                                isCurrent = session.sessionId == currentSessionId,
                                store = store,
                                scope = scope,
                                onClose = onClose,
                                depth = depth,
                                childCount = childrenByParent[session.sessionId].orEmpty().size,
                                childrenExpanded = isChildExpanded(session.sessionId),
                                onToggleChildren = { toggleChildren(session.sessionId) },
                            )
                        }
                    }
                    if (hasMore) {
                        item(key = "ws-more-${workspace.workspaceId}") {
                            DsButton(
                                text = stringResource(R.string.chatlist_load_more),
                                onClick = { visiblePageByWorkspace[workspace.workspaceId] = page + 1 },
                                variant = DsButtonVariant.Ghost,
                                size = DsButtonSize.Small,
                                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                            )
                        }
                    }
                }
            }

            // Sessions the harness never registered in a workspace, plus any subagent whose whole
            // ancestry is archived or blank — those have no row left to nest under.
            val ungrouped = listable.filter {
                it.sessionId !in workspaceSessionIds && it.sessionId !in nestedIds
            }
            if (ungrouped.isNotEmpty()) {
                anyShown = true
                item(key = "sessions-header") { SectionHeader(stringResource(R.string.chatlist_sessions)) }
                val flat = ungrouped
                    .let { if (sortByRecency) it.sortedByDescending(SessionRow::updatedAt) else it }
                    .flatMap { subtree(it) }
                items(flat, key = { it.first.sessionId }) { (session, depth) ->
                    Box(Modifier.animateItem()) {
                        SessionRowItem(
                            session = session,
                            isCurrent = session.sessionId == currentSessionId,
                            store = store,
                            scope = scope,
                            onClose = onClose,
                            depth = depth,
                            childCount = childrenByParent[session.sessionId].orEmpty().size,
                            childrenExpanded = isChildExpanded(session.sessionId),
                            onToggleChildren = { toggleChildren(session.sessionId) },
                        )
                    }
                }
            }

            if (archivedSessions.isNotEmpty()) {
                anyShown = true
                item(key = "archived") {
                    var archivedExpanded by remember { mutableStateOf(false) }
                    DisclosureRow(
                        title = stringResource(R.string.chatlist_archived),
                        summary = archivedSessions.size.toString(),
                        expanded = archivedExpanded,
                        onToggle = { archivedExpanded = !archivedExpanded },
                    ) {
                        archivedSessions.forEach { session ->
                            SessionRowItem(session, false, store, scope, onClose)
                        }
                    }
                }
            }

            if (!anyShown) {
                item(key = "empty") {
                    EmptyHero(
                        headline = stringResource(R.string.chatlist_empty),
                        subtitle = stringResource(R.string.chatlist_empty_hint),
                    )
                }
            }
        }

    }

    if (newWorkspaceOpen) {
        NewWorkspaceDialog(
            onDismiss = { newWorkspaceOpen = false },
            onCreate = { path ->
                scope.launch {
                    store.createWorkspace(path)
                    newWorkspaceOpen = false
                }
            },
        )
    }

    if (newSessionOpen) {
        NewSessionDialog(
            workspaces = workspaces,
            workspacesLoaded = workspacesLoaded,
            homeCwd = hostInfo?.home,
            onPick = { workspaceId ->
                newSessionOpen = false
                scope.launch {
                    store.createSession(workspaceId = workspaceId)
                    onClose()
                }
            },
            onDismiss = { newSessionOpen = false },
        )
    }
}

@Composable
private fun AddMenu(onNewSession: () -> Unit, onNewWorkspace: () -> Unit) {
    DsMenu(
        anchor = { onOpen ->
            DsIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chatlist_new_session),
                onClick = onOpen,
                tint = DsTheme.colors.labelTertiary,
                iconSize = 20.dp,
            )
        },
        items = listOf(
            MenuItem(
                text = stringResource(R.string.chatlist_new_session),
                icon = Icons.Filled.ChatBubbleOutline,
                onClick = onNewSession,
            ),
            MenuItem(
                text = stringResource(R.string.chatlist_new_workspace),
                icon = Icons.Filled.CreateNewFolder,
                onClick = onNewWorkspace,
            ),
        ),
    )
}

/**
 * The session-order control.
 *
 * It used to be a bare ⇅ icon whose only label was a content description, which told a sighted user
 * nothing: two arrows over a chat list could as easily mean sync, move, or reorder. Naming the
 * current order and offering the other one is the whole fix — and the strings for both modes were
 * already translated in all eleven locales, waiting for a control to use them.
 */
@Composable
private fun SortChip(byRecency: Boolean, onPick: (byRecency: Boolean) -> Unit) {
    val colors = DsTheme.colors
    val updated = stringResource(R.string.chatlist_sort_updated)
    val manual = stringResource(R.string.chatlist_sort_manual)
    DsMenu(
        anchor = { onOpen ->
            DsIconButton(
                icon = Icons.Filled.SwapVert,
                contentDescription = stringResource(R.string.chatlist_sort_title),
                onClick = onOpen,
                tint = colors.labelTertiary,
                iconSize = 20.dp,
            )
        },
        items = listOf(
            MenuItem(text = manual, selected = !byRecency) { onPick(false) },
            MenuItem(text = updated, selected = byRecency) { onPick(true) },
        ),
    )
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * A workspace header that collapses its group and carries the workspace verbs.
 *
 * Rename and remove exist on the wire and had no UI at all; a long-press menu is where a
 * phone user expects to find them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceHeader(
    workspace: WorkspaceRow,
    collapsed: Boolean,
    sessionCount: Int,
    onToggle: () -> Unit,
    store: SessionStore,
    scope: CoroutineScope,
    onNewSession: () -> Unit,
) {
    val colors = DsTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = DsAnimations.chevron,
        label = "workspaceChevron",
    )
    val label = workspace.title.ifBlank { basename(workspace.path) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(DsShapes.row)
                .combinedClickable(onClick = onToggle, onLongClick = { menuOpen = true })
                .padding(vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
            Spacer(Modifier.width(DsSpacing.tiny))
            Text(
                label,
                style = DsType.std14Strong,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(sessionCount.toString(), style = DsType.caption11, color = colors.labelCaption)
        }
        if (menuOpen) {
            WorkspaceMenu(
                onDismiss = { menuOpen = false },
                onNewSession = {
                    menuOpen = false
                    onNewSession()
                },
                onRename = {
                    menuOpen = false
                    renaming = true
                },
                onDelete = {
                    menuOpen = false
                    deleting = true
                },
            )
        }
    }

    if (renaming) {
        RenameDialog(
            initial = workspace.title,
            title = stringResource(R.string.chatlist_workspace_rename),
            onDismiss = { renaming = false },
            onConfirm = {
                scope.launch { store.renameWorkspace(workspace.workspaceId, it) }
                renaming = false
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_workspace_delete),
            body = stringResource(R.string.chatlist_workspace_delete_confirm),
            confirmLabel = stringResource(R.string.common_remove),
            onDismiss = { deleting = false },
            onConfirm = {
                scope.launch { store.deleteWorkspace(workspace.workspaceId) }
                deleting = false
            },
        )
    }
}

@Composable
private fun WorkspaceMenu(
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DsDialog(title = null, onDismiss = onDismiss) {
        SheetRow(title = stringResource(R.string.chatlist_workspace_new_session), onClick = onNewSession)
        SheetRow(title = stringResource(R.string.chatlist_workspace_rename), onClick = onRename)
        SheetRow(title = stringResource(R.string.chatlist_workspace_delete), onClick = onDelete)
    }
}

/**
 * One session row: status, title, relative time, and the session verbs on long-press.
 *
 * [depth] indents the row under whatever spawned it, and a row with [childCount] subagents grows a
 * disclosure chevron that opens them in place. Subagents used to be dumped into one flat
 * "Subagents" heading per workspace, which said nothing about which run produced which — with a
 * dozen of them from three sessions it was a wall of near-identical rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowItem(
    session: SessionRow,
    isCurrent: Boolean,
    store: SessionStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
    depth: Int = 0,
    childCount: Int = 0,
    childrenExpanded: Boolean = false,
    onToggleChildren: () -> Unit = {},
) {
    val colors = DsTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var archiveConfirmOpen by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (childrenExpanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "sessionChevron",
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp)
                .clip(DsShapes.row)
                .background(if (isCurrent) colors.sidebarNavActive else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(
                    onClick = {
                        // Launch from the drawer scope first. Closing the drawer disposes this
                        // composable and cancels its coroutine scope, which used to cancel the child
                        // address lookup before it could switch away from the parent transcript.
                        scope.launch {
                            if (session.origin == "subagent" && session.parentSessionId != null) {
                                store.openSubagentSession(session.parentSessionId, session.sessionId)
                            } else {
                                store.openSession(session.sessionId)
                            }
                            onClose()
                        }
                    },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A current-session accent rail reads faster than a background tint alone on a
            // low-contrast sidebar.
            Box(
                Modifier
                    .width(2.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isCurrent) colors.accent else androidx.compose.ui.graphics.Color.Transparent),
            )
            Spacer(Modifier.width(DsSpacing.small))
            // The chevron is its own tap target: opening a session and looking at what it spawned
            // are different intentions, and conflating them means you cannot do one without the
            // other. The spacer keeps titles aligned down a column of mixed rows.
            if (childCount > 0) {
                Box(
                    modifier = Modifier
                        .size(DsSpacing.touchTarget)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onToggleChildren),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.chatlist_subagents),
                        tint = colors.labelTertiary,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.width(DsSpacing.tiny))
            StateDot(
                state = when {
                    session.running -> StateDotState.Running
                    session.pendingInteraction != null -> StateDotState.Warning
                    else -> StateDotState.Idle
                },
            )
            Spacer(Modifier.width(DsSpacing.small))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sessionTitle(session),
                    style = DsType.std14,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    session.cwd?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            basename(it),
                            style = DsType.caption11,
                            color = colors.labelCaption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(" · ", style = DsType.caption11, color = colors.labelCaption)
                    }
                    Text(
                        relativeTime(session.updatedAt),
                        style = DsType.caption11,
                        color = colors.labelCaption,
                    )
                }
            }
            if (session.pendingInteraction != null) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_needs_action), warn = true)
            }
            // The count replaces the old "Subagents" pill on parents: with the children indented
            // underneath, what is worth saying is how many are down there when the row is closed.
            if (childCount > 0) {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = childCount.toString())
            } else if (session.origin == "subagent" && depth == 0) {
                // Only reached by an orphan — its whole ancestry is archived or blank — where the
                // indent cannot say what the row is.
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_subagents))
            }
        }

        if (menuOpen) {
            DsDialog(title = null, onDismiss = { menuOpen = false }) {
                SheetRow(title = stringResource(R.string.chatlist_session_rename)) {
                    menuOpen = false
                    renameOpen = true
                }
                SheetRow(title = stringResource(R.string.chatlist_session_fork)) {
                    menuOpen = false
                    scope.launch { store.forkSession(session.sessionId) }
                }
                SheetRow(title = stringResource(R.string.chatlist_session_archive)) {
                    menuOpen = false
                    archiveConfirmOpen = true
                }
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            initial = session.title.orEmpty(),
            title = stringResource(R.string.chatlist_session_rename),
            onDismiss = { renameOpen = false },
            onConfirm = {
                scope.launch { store.renameSession(session.sessionId, it) }
                renameOpen = false
            },
        )
    }

    if (archiveConfirmOpen) {
        ConfirmDialog(
            title = stringResource(R.string.chatlist_session_archive),
            body = sessionTitle(session),
            confirmLabel = stringResource(R.string.common_archive),
            onDismiss = { archiveConfirmOpen = false },
            onConfirm = {
                scope.launch { store.archiveSession(session.sessionId) }
                archiveConfirmOpen = false
            },
        )
    }
}

/**
 * One search result: the session's own name first, then where it lives, then the matching excerpt
 * if the host had one.
 *
 * The row used to lead with the excerpt and label itself with a raw session id, which is neither
 * something anyone searched for nor something they can recognise. A result should name the thing
 * you are about to open.
 */
@Composable
private fun SearchResultRow(
    hit: SearchHit,
    store: SessionStore,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DsShapes.row)
            .clickable {
                onClose()
                scope.launch { store.openSession(hit.session.sessionId) }
            }
            .padding(horizontal = DsSpacing.tiny, vertical = DsSpacing.xsmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sessionTitle(hit.session),
                style = DsType.rowText,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hit.session.origin == "subagent") {
                Spacer(Modifier.width(DsSpacing.xsmall))
                DsPill(text = stringResource(R.string.chatlist_subagents))
            }
        }
        hit.workspaceLabel.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = DsType.caption11,
                color = colors.labelCaption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        hit.snippet?.let {
            Text(
                text = it,
                style = DsType.caption11,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun NewSessionDialog(
    workspaces: List<WorkspaceRow>,
    workspacesLoaded: Boolean,
    homeCwd: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    DsDialog(title = stringResource(R.string.chatlist_new_session_in), onDismiss = onDismiss) {
        if (!workspacesLoaded) {
            Text(
                stringResource(R.string.common_loading),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        } else if (workspaces.isEmpty()) {
            Text(
                stringResource(R.string.chatlist_no_workspaces),
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }
        workspaces.forEach { workspace ->
            SheetRow(
                title = workspace.title.ifBlank { basename(workspace.path) },
                subtitle = workspace.path,
                onClick = { onPick(workspace.workspaceId) },
            )
        }
        SheetRow(
            title = stringResource(R.string.chatlist_home_directory),
            subtitle = homeCwd,
            onClick = { onPick(null) },
        )
    }
}

@Composable
private fun NewWorkspaceDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun load(path: String? = null) {
        loading = true
        scope.launch {
            listing = store.listDirectory(path)
            selectedPath = path ?: listing?.path
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    val current = listing
    val query = search.trim().lowercase()
    val entries = current?.entries.orEmpty().filter {
        query.isBlank() || it.name.lowercase().contains(query) || it.path.lowercase().contains(query)
    }

    DsDialog(title = stringResource(R.string.chatlist_new_workspace), onDismiss = onDismiss) {
        TextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.chatlist_workspace_search), style = DsType.std14) },
            singleLine = true,
            colors = dialogTextFieldColors(),
        )
        current?.let { directory ->
            Text(
                text = selectedPath ?: directory.path,
                style = DsType.std14Strong.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                color = DsTheme.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { selectedPath = directory.path },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                directory.crumbs.forEach { crumb ->
                    DsButton(
                        text = crumb.name,
                        onClick = { selectedPath = crumb.path; load(crumb.path) },
                        variant = if (crumb.path == directory.path) DsButtonVariant.Info else DsButtonVariant.Ghost,
                        size = DsButtonSize.Small,
                    )
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(entries, key = { it.path }) { entry ->
                    SheetRow(
                        title = entry.name,
                        subtitle = entry.path,
                        onClick = { selectedPath = entry.path; load(entry.path) },
                    )
                }
            }
        }
        if (loading) Text(stringResource(R.string.common_loading), style = DsType.std14, color = DsTheme.colors.labelSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DsButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                variant = DsButtonVariant.Ghost,
            )
            Spacer(Modifier.width(DsSpacing.small))
            DsButton(
                text = stringResource(R.string.common_save),
                onClick = { selectedPath?.let(onCreate) },
                variant = DsButtonVariant.Info,
                enabled = selectedPath != null && !loading,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Walk a (possibly nested) subagent session's parent chain up to the session directly registered in
 * a workspace, returning that workspace id — or null for an orphan.
 */
/**
 * Group subagent sessions under the visible session that spawned them.
 *
 * Keyed by immediate parent, so a subagent that spawned its own subagents nests as deeply as the
 * run actually went. Free function so the rules that are easy to get wrong — forks staying at the
 * top level, orphans re-attaching, cycles not hanging — can be tested without a device.
 */
internal fun indexSubagents(
    listable: List<SessionRow>,
    sessionsById: Map<String, SessionRow>,
): Map<String, List<SessionRow>> {
    val listableIds = listable.mapTo(HashSet()) { it.sessionId }

    /**
     * The nearest ancestor that is actually on screen.
     *
     * Usually the immediate parent. The walk exists for the case that used to lose rows entirely:
     * archiving a session, or the harness reusing a blank one, removes it from the list while its
     * subagents remain — those attach to the next ancestor up rather than vanishing with it. The
     * visited set guards against a lineage cycle, which would otherwise hang the drawer.
     */
    fun attachPoint(child: SessionRow): String? {
        // Seeded with the child so a lineage cycle cannot walk back around and make the row its own
        // parent — which would nest it inside itself and render nothing at all.
        val visited = hashSetOf(child.sessionId)
        var cursor = child.parentSessionId
        while (cursor != null && visited.add(cursor)) {
            if (cursor in listableIds) return cursor
            cursor = sessionsById[cursor]?.parentSessionId
        }
        return null
    }

    return listable
        .filter { it.origin == "subagent" }
        .mapNotNull { child -> attachPoint(child)?.let { it to child } }
        .groupBy({ it.first }, { it.second })
}

/** Display title: an explicit title, else the working directory's folder, else the id. */
internal fun sessionTitle(session: SessionRow): String {
    val title = session.title?.takeIf { it.isNotBlank() }
    val folder = session.cwd?.takeIf { it.isNotBlank() }?.let { basename(it) }?.takeIf { it.isNotBlank() }
    return title ?: folder ?: session.sessionId
}
