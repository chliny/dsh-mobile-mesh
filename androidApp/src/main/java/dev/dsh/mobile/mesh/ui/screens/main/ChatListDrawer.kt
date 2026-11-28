package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.core.wire.dto.DirectoryListing
import dev.dsh.mobile.mesh.core.wire.dto.ForgeChangeRequestListState
import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.data.WorkspaceRow
import dev.dsh.mobile.mesh.ui.components.EmptyHero
import dev.dsh.mobile.mesh.ui.components.FeatherIcons
import dev.dsh.mobile.mesh.ui.components.StateDot
import dev.dsh.mobile.mesh.ui.components.StateDotState
import dev.dsh.mobile.mesh.ui.components.StatusBadge
import dev.dsh.mobile.mesh.ui.components.relativeTime
import dev.dsh.mobile.mesh.ui.rememberForgeStore
import dev.dsh.mobile.mesh.ui.rememberHostsStore
import dev.dsh.mobile.mesh.ui.rememberSessionStore
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The connection phase as the one status dot the list header carries. */
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

/** Folding a workspace restarts its own and its tree descendants' session pages. */
internal fun resetCollapsedWorkspacePages(
    pages: MutableMap<String, Int>,
    workspaces: List<WorkspaceRow>,
    workspaceId: String,
) {
    val path = workspaces.firstOrNull { it.workspaceId == workspaceId }?.path
        ?.replace('\\', '/')?.trimEnd('/')
    pages.keys.toList().forEach { key ->
        val candidate = workspaces.firstOrNull { it.workspaceId == key }?.path
            ?.replace('\\', '/')?.trimEnd('/')
        if (key == workspaceId || (path != null && path.isNotEmpty() && candidate?.startsWith("$path/") == true)) {
            pages.remove(key)
        }
    }
}

internal enum class SessionGrouping { WORKSPACE, WORKSPACE_TREE, FLAT }
internal enum class ArchivedSessionFilter { HIDE, SHOW, ONLY }

/** A registered workspace has a header even when no visible session belongs to it yet. */
internal fun showWorkspaceHeader(grouping: SessionGrouping, archivedFilter: ArchivedSessionFilter): Boolean =
    grouping != SessionGrouping.FLAT && archivedFilter != ArchivedSessionFilter.ONLY

/** Keep the registry's manual order; recency is the only mode allowed to reorder rows. */
internal fun orderedSessionRoots(rows: List<SessionRow>, byRecency: Boolean): List<SessionRow> =
    if (byRecency) rows.sortedWith(compareByDescending<SessionRow> { it.updatedAt }.thenByDescending { it.sessionId })
    else rows

/**
 * Depth-first workspace tree, siblings in registry order.
 *
 * A workspace's parent is its closest registered ancestor path, wherever it sits in the registry:
 * rows arrive in manual order, so a child may well be listed before its parent. A collapsed parent
 * hides its subtree, matching what the disclosure chevron states.
 */
internal fun workspaceTreeRows(
    workspaces: List<WorkspaceRow>,
    isExpanded: (String) -> Boolean = { true },
): List<Pair<WorkspaceRow, Int>> {
    fun normalized(path: String): String = path.replace('\\', '/').trimEnd('/')
    val paths = workspaces.map { normalized(it.path) }
    val parentByIndex = paths.indices.associateWith { index ->
        val own = paths[index]
        if (own.isEmpty()) return@associateWith null
        paths.indices
            .filter { candidate -> candidate != index && paths[candidate].isNotEmpty() && paths[candidate] != own }
            .filter { candidate -> own.startsWith("${paths[candidate]}/") }
            .maxByOrNull { candidate -> paths[candidate].length }
    }
    val children = paths.indices.groupBy { parentByIndex[it] }
    val result = mutableListOf<Pair<WorkspaceRow, Int>>()
    fun walk(parent: Int?, depth: Int) {
        children[parent].orEmpty().forEach { index ->
            val workspace = workspaces[index]
            result += workspace to depth
            if (isExpanded(workspace.workspaceId)) walk(index, depth + 1)
        }
    }
    walk(null, 0)
    return result
}

internal enum class SessionListEmptyState { LOADING, FAILED, EMPTY, NOT_EMPTY }

internal fun sessionListEmptyState(
    anyShown: Boolean,
    baselineLoaded: Boolean,
    baselineFailed: Boolean,
    generationMatches: Boolean = true,
) = when {
    anyShown -> SessionListEmptyState.NOT_EMPTY
    !generationMatches || !baselineLoaded -> SessionListEmptyState.LOADING
    baselineFailed -> SessionListEmptyState.FAILED
    else -> SessionListEmptyState.EMPTY
}

/**
 * The chat history: workspaces, their sessions, and search.
 *
 * Two rules keep it readable. Blank sessions are hidden — the harness treats a session with no turn
 * as scratch space and reuses it, so listing them just accumulates empty rows. And times are
 * relative, because a clock time cannot distinguish "an hour ago" from "last Tuesday".
 *
 * The surface itself is Material 3: a top app bar with the connection dot and the list-wide actions,
 * two-line list items per session, anchored dropdowns for every verb set, and dialogs for the
 * prompts. The only bespoke pieces left are the status dot and the empty-state hero, which have no
 * Material equivalent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListDrawer(
    visiblePageByWorkspace: MutableMap<String, Int>,
    connectionPhase: ConnectionPhase,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val store = rememberSessionStore()
    val scope = rememberCoroutineScope()
    val hostsStore = rememberHostsStore()
    val forgeStore = rememberForgeStore()
    DisposableEffect(forgeStore) {
        val token = forgeStore.observeChangeRequestStates()
        onDispose { forgeStore.releaseChangeRequestStates(token) }
    }
    val forgeStates by forgeStore.changeRequestStates.collectAsStateWithLifecycle()

    val sessions by store.sessions.collectAsStateWithLifecycle()
    val sessionsBaselineLoaded by store.sessionsBaselineLoaded.collectAsStateWithLifecycle()
    val sessionsBaselineFailed by store.sessionsBaselineFailed.collectAsStateWithLifecycle()
    val sessionsBaselineGenerationId by store.sessionsBaselineGenerationId.collectAsStateWithLifecycle()
    val sessionsCurrentGenerationId by store.sessionsCurrentGenerationId.collectAsStateWithLifecycle()
    val workspaces by store.workspaces.collectAsStateWithLifecycle()
    val workspacesLoaded by store.workspacesLoaded.collectAsStateWithLifecycle()
    val archivedIds by store.archivedSessionIds.collectAsStateWithLifecycle()
    val pinnedIds by store.pinnedSessionIds.collectAsStateWithLifecycle()
    val pinAvailable by store.pinAvailable.collectAsStateWithLifecycle()
    val searchResults by store.searchResults.collectAsStateWithLifecycle()
    val searchHasMore by store.searchHasMore.collectAsStateWithLifecycle()
    val contentSearchAvailable by store.contentSearchAvailable.collectAsStateWithLifecycle()
    val currentSessionId by store.currentSessionId.collectAsStateWithLifecycle()
    val hostInfo by store.hostInfo.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var schedulesOpen by remember { mutableStateOf(false) }
    var optionsMenuOpen by remember { mutableStateOf(false) }
    // Persisted, not remembered: the order you read your sessions in is a preference, and it used
    // to reset every time the drawer was closed.
    val sessionSort by hostsStore.sessionSort.collectAsStateWithLifecycle(initialValue = SORT_MANUAL)
    val workspaceExpansion by hostsStore.workspaceExpansion.collectAsStateWithLifecycle(initialValue = emptyMap())
    val sortByRecency = sessionSort == SORT_UPDATED
    var grouping by remember { mutableStateOf(SessionGrouping.WORKSPACE) }
    var archivedFilter by remember { mutableStateOf(ArchivedSessionFilter.HIDE) }
    var newSessionOpen by remember { mutableStateOf(false) }
    var newWorkspaceOpen by remember { mutableStateOf(false) }
    // Dragging workspace rows is a temporary mode with its own screen, not a persisted preference:
    // leaving the drawer or tapping Done restores the ordinary grouped list untouched.
    var workspaceSortMode by remember { mutableStateOf(false) }
    val workspaceOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val childCollapsed = remember { mutableStateMapOf<String, Boolean>() }
    var workspaceMovePending by remember { mutableStateOf(false) }

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
    // The Host supplies content hits; titles, Workspace names, and Session ids are matched locally
    // by the same rules as the web sidebar's search index.
    val searchSet = remember(sessions, workspaces, archivedIds, archivedFilter, query, searchResults, searchHasMore) {
        deriveSearchResults(
            sessions = sessions,
            workspaces = workspaces,
            archivedIds = archivedIds,
            archivedFilter = archivedFilter,
            query = query,
            contentHits = searchResults,
            contentHasMore = searchHasMore,
        )
    }

    // Blank sessions are scratch space the harness reuses. Active subagent transcripts remain
    // visible under their parent; completed subagents are omitted from grouped and search results.
    val listable = visibleSessions
    val sessionsById = sessions.associateBy { it.sessionId }
    val archivedSessions = sessions.filter { it.sessionId in archivedIds && it.origin != "subagent" }
    val workspaceSessionIds = workspaces.flatMap { it.sessionIds }.toSet()

    // Subagents nest under the session that spawned them. `origin` is the discriminator, not
    // `parentSessionId` — hosts before 0.2.2 also set a parent on ordinary forks, and a fork is a
    // session in its own right that belongs at the top level; 0.2.2 stopped publishing that parent
    // for forks, while subagent sessions still carry it. Grouping is by *immediate* parent so a
    // subagent that spawned its own subagents nests to whatever depth the run actually reached; a
    // child whose parent is archived or blank attaches to the nearest ancestor still on screen
    // instead of disappearing with it.
    val childrenByParent = remember(listable) { indexSubagents(listable, sessionsById) }
    val nestedIds = remember(childrenByParent) {
        childrenByParent.values.flatten().mapTo(HashSet()) { it.sessionId }
    }
    val pinnedSet = pinnedIds.toSet()
    // Empty workspaces remain actionable in the list (notably their New session shortcut).
    // Their order must also be movable before the first session is created.
    val pinnedRoots = pinnedRootIds(pinnedIds, listable.mapTo(HashSet()) { it.sessionId }, nestedIds)
        .mapNotNull(sessionsById::get)
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
    fun isChildExpanded(sessionId: String): Boolean = sessionChildrenExpanded(sessionId, childCollapsed, openPath)
    fun toggleChildren(sessionId: String) {
        toggleSessionChildren(sessionId, childCollapsed, openPath)
    }

    /** Depth-first expansion of one top-level session, honouring each row's collapse state. */
    fun subtree(root: SessionRow): List<Pair<SessionRow, Int>> {
        val out = mutableListOf<Pair<SessionRow, Int>>()
        fun walk(row: SessionRow, depth: Int) {
            out += row to depth
            val children = childrenByParent[row.sessionId].orEmpty()
            if (children.isEmpty() || !isChildExpanded(row.sessionId)) return
            val ordered = orderedSessionRoots(children, sortByRecency)
            ordered.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** The root sessions a workspace header counts and lists: no subagents, no pinned duplicates. */
    fun rootsOf(workspace: WorkspaceRow): List<SessionRow> = workspace.sessionIds
        .mapNotNull { id -> listable.firstOrNull { it.sessionId == id } }
        .filterNot { it.sessionId in nestedIds || it.sessionId in pinnedSet }

    val workspaceRootCounts = remember(workspaces, listable, nestedIds, pinnedSet) {
        workspaces.associate { it.workspaceId to rootsOf(it).size }
    }

    /**
     * Persist one drag result as a single `workspace/insertBefore`.
     *
     * Dropping in the last slot means append, which the wire expresses by omitting the anchor.
     * The registry order is the only order dragging edits, so the rows are the registry rows.
     */
    fun moveWorkspace(from: Int, to: Int) {
        if (workspaceMovePending) return
        val ids = workspaces.map { it.workspaceId }
        if (from !in ids.indices || to !in ids.indices || from == to) return
        val moved = ids[from]
        val next = ids.toMutableList().apply { add(to, removeAt(from)) }
        workspaceMovePending = true
        scope.launch {
            try {
                store.reorderWorkspace(moved, next.getOrNull(to + 1))
            } finally {
                workspaceMovePending = false
            }
        }
    }

    if (schedulesOpen) {
        ScheduleScreen(
            onBack = { schedulesOpen = false },
            onNewTask = {
                schedulesOpen = false
                // Scheduling a task is an auxiliary blank session; a conversation draft typed
                // from the session list belongs to that flow, not to this one.
                scope.launch { store.createSession(ownsNewSessionDraft = false); onClose() }
            },
            onOpenSession = { sessionId ->
                schedulesOpen = false
                scope.launch { store.openSession(sessionId); onClose() }
            },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(
                                if (workspaceSortMode) R.string.chatlist_sort_workspaces
                                else R.string.chatlist_title,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(DsSpacing.small))
                        SessionListConnectionStatusDot(connectionPhase)
                    }
                },
                actions = {
                    if (workspaceSortMode) {
                        IconButton(onClick = { workspaceSortMode = false }) {
                            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_done))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                searchOpen = !searchOpen
                                if (!searchOpen) query = ""
                            },
                            // The tint is the only thing saying the field is open, so it doubles as the
                            // state: accent while searching, neutral while closed.
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = if (searchOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.common_search))
                        }
                        IconButton(onClick = { schedulesOpen = true }) {
                            Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.schedule_title))
                        }
                        Box {
                            IconButton(onClick = { optionsMenuOpen = true }) {
                                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.chatlist_view_options))
                            }
                            ViewOptionsMenu(
                                expanded = optionsMenuOpen,
                                onDismiss = { optionsMenuOpen = false },
                                grouping = grouping,
                                byRecency = sortByRecency,
                                archivedFilter = archivedFilter,
                                onGroupingPick = { grouping = it },
                                onSortPick = { next ->
                                    scope.launch { hostsStore.setSessionSort(if (next) SORT_UPDATED else SORT_MANUAL) }
                                },
                                onArchivedPick = {
                                    archivedFilter = it
                                    if (query.isNotBlank()) {
                                        query = ""
                                        searchOpen = false
                                    }
                                },
                                onNewSession = { newSessionOpen = true },
                                onNewWorkspace = { newWorkspaceOpen = true },
                                onSortWorkspaces = {
                                    // Sorting workspaces is its own focused screen; a search field
                                    // there would be inert, so close it before switching.
                                    searchOpen = false
                                    query = ""
                                    workspaceSortMode = true
                                },
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (workspaceSortMode) {
                WorkspaceSortList(
                    workspaces = workspaces,
                    loaded = workspacesLoaded,
                    sessionCounts = workspaceRootCounts,
                    store = store,
                    scope = scope,
                    pending = workspaceMovePending,
                    onMove = { from, to -> moveWorkspace(from, to) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                return@Column
            }
            // The search field folds away rather than permanently occupying a row of a phone-height
            // drawer, which is otherwise pure overhead for the common case.
            AnimatedVisibility(visible = searchOpen) {
                Column(modifier = Modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.small)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.chatlist_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    // The Harness supplies message-content hits; titles, Workspace names, and
                    // Session ids are matched locally like the web sidebar. Without the optional
                    // content service the local half still answers, which is what the note above
                    // states.
                    if (!contentSearchAvailable && query.isNotBlank()) {
                        Text(
                            stringResource(R.string.chatlist_search_content_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = DsSpacing.tiny, start = DsSpacing.comfortable),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
            ) {
                if (query.isNotBlank()) {
                    item(key = "search-header") { ChatListSectionLabel(stringResource(R.string.common_search)) }
                    if (searchSet.items.isEmpty()) {
                        item(key = "search-empty") {
                            Text(
                                stringResource(R.string.chatlist_search_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.small),
                            )
                        }
                    }
                    items(searchSet.items, key = { it.session.sessionId }) { hit ->
                        SearchResultRow(hit, store, scope, onClose)
                    }
                    if (searchSet.items.isNotEmpty() && searchSet.hasMore) {
                        item(key = "search-more") {
                            Text(
                                stringResource(R.string.chatlist_search_refine),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.xsmall),
                            )
                        }
                    }
                    return@LazyColumn
                }

                var anyShown = false
                if (archivedFilter != ArchivedSessionFilter.ONLY && pinnedRoots.isNotEmpty()) {
                    anyShown = true
                    item(key = "pinned-header") { ChatListSectionLabel(stringResource(R.string.chatlist_pinned)) }
                    val pinnedRows = pinnedRoots.flatMap { subtree(it) }
                    items(pinnedRows, key = { it.first.sessionId }) { (session, depth) ->
                        SessionRowItem(
                            session = session, isCurrent = session.sessionId == currentSessionId,
                            store = store, scope = scope, onClose = onClose, depth = depth,
                            isPinned = session.sessionId in pinnedSet, pinAvailable = pinAvailable,
                            childCount = childrenByParent[session.sessionId].orEmpty().size,
                            childrenExpanded = isChildExpanded(session.sessionId),
                            onToggleChildren = { toggleChildren(session.sessionId) },
                            changeRequestStates = forgeStates,
                        )
                    }
                }
                val treeMode = grouping == SessionGrouping.WORKSPACE_TREE
                val displayedWorkspaces = if (treeMode) {
                    workspaceTreeRows(workspaces, ::isExpanded)
                } else workspaces.map { it to 0 }
                for ((workspace, workspaceDepth) in displayedWorkspaces) {
                    if (!showWorkspaceHeader(grouping, archivedFilter)) continue
                    val roots = rootsOf(workspace)
                    // A workspace is a registry row even before it owns a visible session. Keep its
                    // collapsed header and New session action available in every grouped view.
                    anyShown = true
                    // Workspace expansion is persisted per workspace; session-child expansion
                    // remains a local UI choice and does not affect the workspace preference.
                    val isCollapsed = !isExpanded(workspace.workspaceId)
                    val orderedRoots = orderedSessionRoots(roots, sortByRecency)
                    val page = (visiblePageByWorkspace[workspace.workspaceId] ?: 1).coerceAtLeast(1)
                    val pageRoots = orderedRoots.take(page * SESSION_PAGE_SIZE)
                    val hasMore = pageRoots.size < orderedRoots.size
                    item(key = "ws-${workspace.workspaceId}") {
                        WorkspaceHeader(
                            workspace = workspace,
                            depth = workspaceDepth,
                            collapsed = isCollapsed,
                            // Sessions, not sessions-plus-their-subagents: a subagent count belongs
                            // on the row that spawned them, where it says something.
                            sessionCount = orderedRoots.size,
                            onToggle = {
                                val expanded = !isExpanded(workspace.workspaceId)
                                if (!expanded) {
                                    resetCollapsedWorkspacePages(visiblePageByWorkspace, workspaces, workspace.workspaceId)
                                }
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
                                    isPinned = session.sessionId in pinnedSet,
                                    pinAvailable = pinAvailable,
                                    onToggleChildren = { toggleChildren(session.sessionId) },
                                    changeRequestStates = forgeStates,
                                )
                            }
                        }
                        if (hasMore) {
                            item(key = "ws-more-${workspace.workspaceId}") {
                                TextButton(
                                    onClick = { visiblePageByWorkspace[workspace.workspaceId] = page + 1 },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.xsmall),
                                ) { Text(stringResource(R.string.chatlist_load_more)) }
                            }
                        }
                    }
                }

                // Sessions the harness never registered in a workspace, plus any subagent whose whole
                // ancestry is archived or blank — those have no row left to nest under.
                val ungrouped = listable.filter {
                    (grouping == SessionGrouping.FLAT || it.sessionId !in workspaceSessionIds) &&
                        it.sessionId !in nestedIds && it.sessionId !in pinnedSet
                }
                if (archivedFilter != ArchivedSessionFilter.ONLY && ungrouped.isNotEmpty()) {
                    anyShown = true
                    item(key = "sessions-header") { ChatListSectionLabel(stringResource(R.string.chatlist_sessions)) }
                    val flat = orderedSessionRoots(ungrouped, sortByRecency).flatMap { subtree(it) }
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
                                isPinned = session.sessionId in pinnedSet,
                                pinAvailable = pinAvailable,
                                onToggleChildren = { toggleChildren(session.sessionId) },
                                changeRequestStates = forgeStates,
                            )
                        }
                    }
                }

                if (archivedFilter != ArchivedSessionFilter.HIDE && archivedSessions.isNotEmpty()) {
                    anyShown = true
                    item(key = "archived") {
                        var archivedExpanded by remember(archivedFilter) { mutableStateOf(true) }
                        Column {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { archivedExpanded = !archivedExpanded },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.chatlist_archived),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            archivedSessions.size.toString(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .graphicsLayer { rotationZ = if (archivedExpanded) 90f else 0f },
                                        )
                                    }
                                },
                            )
                            if (archivedExpanded) {
                                archivedSessions.forEach { session ->
                                    SessionRowItem(
                                        session, false, store, scope, onClose,
                                        isArchived = true, changeRequestStates = forgeStates,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!anyShown) {
                    val currentGenerationBaseline = sessionsBaselineGenerationId != null &&
                        sessionsBaselineGenerationId == sessionsCurrentGenerationId
                    val emptyState = sessionListEmptyState(
                        anyShown = false,
                        baselineLoaded = sessionsBaselineLoaded,
                        baselineFailed = sessionsBaselineFailed,
                        generationMatches = currentGenerationBaseline,
                    )
                    item(key = "empty") {
                        when (emptyState) {
                            SessionListEmptyState.LOADING -> Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = DsSpacing.large),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    stringResource(R.string.chatlist_loading_sessions),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SessionListEmptyState.FAILED -> Text(
                                stringResource(R.string.chatlist_sessions_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = DsSpacing.large),
                            )
                            SessionListEmptyState.EMPTY -> EmptyHero(
                                headline = stringResource(R.string.chatlist_empty),
                                subtitle = stringResource(R.string.chatlist_empty_hint),
                            )
                            SessionListEmptyState.NOT_EMPTY -> Unit
                        }
                    }
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

/** Small section caption above a group of rows. */
@Composable
private fun ChatListSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(
            start = DsSpacing.small,
            top = DsSpacing.medium,
            bottom = DsSpacing.tiny,
        ),
    )
}

/** One material dropdown row with its icon and, for the current choice, a check. */
@Composable
private fun ChatListMenuItem(
    text: String,
    icon: ImageVector,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val dangerColor = MaterialTheme.colorScheme.error
    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) dangerColor else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (danger) dangerColor else normalColor) },
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun ChatListMenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ViewOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    grouping: SessionGrouping,
    byRecency: Boolean,
    archivedFilter: ArchivedSessionFilter,
    onGroupingPick: (SessionGrouping) -> Unit,
    onSortPick: (Boolean) -> Unit,
    onArchivedPick: (ArchivedSessionFilter) -> Unit,
    onNewSession: () -> Unit,
    onNewWorkspace: () -> Unit,
    onSortWorkspaces: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ChatListMenuHeading(stringResource(R.string.chatlist_group_title))
        ChatListMenuItem(
            stringResource(R.string.chatlist_group_workspace), Icons.Filled.Folder,
            selected = grouping == SessionGrouping.WORKSPACE,
        ) { onDismiss(); onGroupingPick(SessionGrouping.WORKSPACE) }
        ChatListMenuItem(
            stringResource(R.string.chatlist_group_tree), Icons.Filled.AccountTree,
            selected = grouping == SessionGrouping.WORKSPACE_TREE,
        ) { onDismiss(); onGroupingPick(SessionGrouping.WORKSPACE_TREE) }
        ChatListMenuItem(
            stringResource(R.string.chatlist_group_flat), Icons.AutoMirrored.Filled.ViewList,
            selected = grouping == SessionGrouping.FLAT,
        ) { onDismiss(); onGroupingPick(SessionGrouping.FLAT) }
        HorizontalDivider()
        ChatListMenuHeading(stringResource(R.string.chatlist_order_title))
        ChatListMenuItem(
            stringResource(R.string.chatlist_sort_manual), Icons.Filled.SwapVert,
            selected = !byRecency,
        ) { onDismiss(); onSortPick(false) }
        ChatListMenuItem(
            stringResource(R.string.chatlist_sort_updated), Icons.Filled.AccessTime,
            selected = byRecency,
        ) { onDismiss(); onSortPick(true) }
        HorizontalDivider()
        ChatListMenuHeading(stringResource(R.string.chatlist_filter_title))
        ChatListMenuItem(
            stringResource(R.string.chatlist_hide_archived), Icons.Filled.Archive,
            selected = archivedFilter == ArchivedSessionFilter.HIDE,
        ) { onDismiss(); onArchivedPick(ArchivedSessionFilter.HIDE) }
        ChatListMenuItem(
            stringResource(R.string.chatlist_show_archived), Icons.Filled.Archive,
            selected = archivedFilter == ArchivedSessionFilter.SHOW,
        ) { onDismiss(); onArchivedPick(ArchivedSessionFilter.SHOW) }
        ChatListMenuItem(
            stringResource(R.string.chatlist_only_archived), Icons.Filled.Archive,
            selected = archivedFilter == ArchivedSessionFilter.ONLY,
        ) { onDismiss(); onArchivedPick(ArchivedSessionFilter.ONLY) }
        HorizontalDivider()
        ChatListMenuItem(
            stringResource(R.string.chatlist_new_session), Icons.Filled.Add,
        ) { onDismiss(); onNewSession() }
        ChatListMenuItem(
            stringResource(R.string.chatlist_new_workspace), Icons.Filled.CreateNewFolder,
        ) { onDismiss(); onNewWorkspace() }
        ChatListMenuItem(
            stringResource(R.string.chatlist_sort_workspaces), Icons.Filled.DragHandle,
        ) { onDismiss(); onSortWorkspaces() }
    }
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/** One in-flight registry drag: where it started and how far its finger has moved. */
private data class WorkspaceSortDrag(
    val index: Int,
    val scrollStart: Int,
    val pointerStartY: Float,
)

/**
 * Every registered workspace in registry order, each row carrying a drag handle.
 *
 * The grouped list is deliberately not reused here: a drop has to map to exactly one
 * `workspace/insertBefore` anchor, so the rows must be the flat registry order rather than a
 * filtered or tree-derived view of it.
 */
@Composable
private fun WorkspaceSortList(
    workspaces: List<WorkspaceRow>,
    loaded: Boolean,
    sessionCounts: Map<String, Int>,
    store: SessionStore,
    scope: CoroutineScope,
    pending: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (workspaces.isEmpty()) {
        Text(
            stringResource(
                if (loaded) R.string.chatlist_sort_workspaces_empty else R.string.common_loading,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = DsSpacing.comfortable, vertical = DsSpacing.medium),
        )
        return
    }
    val rowPositions = remember { mutableMapOf<String, LayoutCoordinates>() }
    val handlePositions = remember { mutableMapOf<String, LayoutCoordinates>() }
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    // A registry change mid-drag rebuilds the rows under the finger, so the drag is dropped rather
    // than continuing against a list that no longer matches where it started. Session churn inside
    // a workspace leaves the ids untouched and therefore does not interrupt the drag.
    var drag by remember(workspaces.map { it.workspaceId }) { mutableStateOf<WorkspaceSortDrag?>(null) }
    var deltaY by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { DsSpacing.touchTarget.toPx() }
    val step = with(LocalDensity.current) { DsSpacing.small.toPx() }
    val scrollDirection = drag?.let {
        val pointerY = it.pointerStartY + deltaY
        when {
            viewport.height <= 0f -> 0
            pointerY < viewport.top + edge && deltaY < 0f -> -1
            pointerY > viewport.bottom - edge && deltaY > 0f -> 1
            else -> 0
        }
    } ?: 0
    LaunchedEffect(drag?.index, scrollDirection) {
        if (scrollDirection != 0) {
            while (true) {
                withFrameNanos { }
                if (scroll.scrollBy(scrollDirection * step) == 0f) break
            }
        }
    }

    Box(modifier.onGloballyPositioned { viewport = it.boundsInRoot() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            workspaces.forEachIndexed { index, workspace ->
                val active = drag?.takeIf { it.index == index }
                val handleModifier = Modifier
                    .onGloballyPositioned { handlePositions[workspace.workspaceId] = it }
                    .pointerInput(workspace.workspaceId, index, pending) {
                        detectDragGestures(
                            onDragStart = { start ->
                                if (!pending) {
                                    val handle = handlePositions[workspace.workspaceId]?.takeIf { it.isAttached }
                                    if (handle != null) {
                                        deltaY = 0f
                                        drag = WorkspaceSortDrag(
                                            index = index,
                                            scrollStart = scroll.value,
                                            pointerStartY = handle.localToRoot(start).y,
                                        )
                                    }
                                }
                            },
                            onDrag = { change, amount ->
                                if (drag?.index == index) {
                                    change.consume()
                                    deltaY += amount.y
                                }
                            },
                            onDragCancel = { drag = null },
                            onDragEnd = {
                                val current = drag
                                drag = null
                                val row = rowPositions[workspace.workspaceId]?.takeIf { it.isAttached }
                                if (current?.index == index && row != null) {
                                    // Compare content coordinates: rows keep their position in the
                                    // column while the viewport scrolls under the finger.
                                    val center = row.positionInParent().y + row.size.height / 2f +
                                        deltaY + scroll.value - current.scrollStart
                                    val target = workspaces.indices.count { other ->
                                        val otherRow = rowPositions[workspaces[other].workspaceId]
                                        other != index && otherRow != null && otherRow.isAttached &&
                                            otherRow.positionInParent().y + otherRow.size.height / 2f < center
                                    }
                                    if (target != index) onMove(index, target)
                                }
                            },
                        )
                    }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { rowPositions[workspace.workspaceId] = it }
                        .zIndex(if (active != null) 1f else 0f)
                        .then(
                            if (active != null) Modifier.background(MaterialTheme.colorScheme.surface)
                            else Modifier,
                        )
                        .graphicsLayer {
                            translationY = if (active != null) deltaY + scroll.value - active.scrollStart else 0f
                        },
                ) {
                    WorkspaceHeader(
                        workspace = workspace,
                        depth = 0,
                        collapsed = false,
                        sessionCount = sessionCounts[workspace.workspaceId] ?: 0,
                        onToggle = {},
                        store = store,
                        scope = scope,
                        onNewSession = {},
                        sorting = true,
                        sortHandleContent = {
                            Box(
                                modifier = Modifier.fillMaxSize().then(handleModifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.DragHandle,
                                    contentDescription = stringResource(R.string.chatlist_sort_workspaces),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A workspace header that collapses its group and carries the workspace verbs.
 *
 * Rename and remove exist on the wire and had no UI at all; a long-press menu is where a phone user
 * expects to find them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceHeader(
    workspace: WorkspaceRow,
    depth: Int,
    collapsed: Boolean,
    sessionCount: Int,
    onToggle: () -> Unit,
    store: SessionStore,
    scope: CoroutineScope,
    onNewSession: () -> Unit,
    sorting: Boolean = false,
    sortHandleContent: @Composable () -> Unit = {},
) {
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
        Column {
            // A rule alone separates the workspaces: it is enough at 48dp rows, and a tinted band
            // on top of it made the list look heavier than the sessions it groups.
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (depth * 12).dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        // While sorting, a row is a drag surface, not a disclosure control:
                        // collapsing it here would hide the very rows being arranged.
                        if (sorting) Modifier
                        else Modifier.combinedClickable(onClick = onToggle, onLongClick = { menuOpen = true }),
                    )
                    .padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sorting) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    sessionCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sorting) {
                    // Takes the exact slot the New session shortcut occupied, so the row keeps its
                    // width and the handle lands under the thumb that opened this mode. The caller
                    // owns the handle's contents because only it knows the drag gesture.
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        sortHandleContent()
                    }
                } else {
                    IconButton(onClick = onNewSession, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.chatlist_workspace_new_session),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        WorkspaceMenu(
            expanded = menuOpen,
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
    expanded: Boolean,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ChatListMenuItem(stringResource(R.string.chatlist_workspace_new_session), Icons.Filled.Add) {
            onDismiss()
            onNewSession()
        }
        ChatListMenuItem(stringResource(R.string.chatlist_workspace_rename), Icons.Filled.Edit) {
            onDismiss()
            onRename()
        }
        HorizontalDivider()
        ChatListMenuItem(stringResource(R.string.chatlist_workspace_delete), Icons.Filled.Delete, danger = true) {
            onDismiss()
            onDelete()
        }
    }
}

/** Worktree and most-urgent bound change-request markers in one session row. */
@Composable
private fun SessionForgeIcons(session: SessionRow, states: Map<String, ForgeChangeRequestListState>) {
    val colors = MaterialTheme.colorScheme
    session.worktreeBranch?.let { branch ->
        Icon(
            FeatherIcons.GitBranch,
            contentDescription = stringResource(R.string.chatlist_worktree, branch),
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
    if (session.boundChangeRequestUrls.isEmpty()) return
    val changeRequest = session.boundChangeRequestUrls
        .mapNotNull { states[it] }
        .minByOrNull(::forgeChangeRequestRank)
    val tint = when {
        changeRequest == null -> colors.onSurfaceVariant
        changeRequest.state == "open" && !changeRequest.draft &&
            (changeRequest.checksRollup == "failure" ||
                changeRequest.reviewDecision == "changes-requested" ||
                changeRequest.mergeability == "conflicting") -> colors.error
        changeRequest.state == "open" && !changeRequest.draft -> colors.primary
        changeRequest.state == "merged" -> colors.tertiary
        else -> colors.onSurfaceVariant
    }
    Icon(
        FeatherIcons.GitPullRequest,
        contentDescription = if (changeRequest == null) {
            stringResource(R.string.chatlist_change_request_pending)
        } else {
            stringResource(R.string.chatlist_change_request, changeRequest.number, forgeChangeRequestStateLabel(changeRequest))
        },
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}

private fun forgeChangeRequestRank(state: ForgeChangeRequestListState): Int = when {
    state.state == "open" && !state.draft &&
        (state.checksRollup == "failure" ||
            state.reviewDecision == "changes-requested" ||
            state.mergeability == "conflicting") -> 0
    state.state == "open" && !state.draft -> 1
    state.draft && state.state == "open" -> 2
    state.state == "merged" -> 3
    state.state == "closed" -> 4
    else -> 5
}

@Composable
private fun forgeChangeRequestStateLabel(state: ForgeChangeRequestListState): String = stringResource(
    when {
        state.state == "merged" -> R.string.forge_state_merged
        state.state == "closed" -> R.string.forge_state_closed
        state.draft -> R.string.forge_state_draft
        else -> R.string.forge_state_open
    },
)

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
    isArchived: Boolean = false,
    isPinned: Boolean = false,
    pinAvailable: Boolean = false,
    changeRequestStates: Map<String, ForgeChangeRequestListState> = emptyMap(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var archiveConfirmOpen by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (childrenExpanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "sessionChevron",
    )
    val meta = listOfNotNull(
        session.cwd?.takeIf { it.isNotBlank() }?.let(::basename),
        relativeTime(session.updatedAt),
    ).joinToString(" · ")

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 12).dp)
                .clip(MaterialTheme.shapes.small)
                .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
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
                            awaitSessionSelected(store.currentSessionId, session.sessionId)
                            onClose()
                        }
                    },
                    onLongClick = { menuOpen = true },
                )
                // Two text lines at 14/11 rather than Material's 72dp list row: the drawer is a
                // navigation list, and the extra air between rows was pure scrolling.
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron is its own tap target: opening a session and looking at what it spawned
            // are different intentions, and conflating them means you cannot do one without the
            // other. The spacer keeps titles aligned down a column of mixed rows.
            if (childCount > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.chatlist_subagents),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                        .clickable(onClick = onToggleChildren),
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
            }
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
                    sessionTitle(session),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DsSpacing.xsmall))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
            ) {
                SessionForgeIcons(session, changeRequestStates)
                if (session.pendingInteraction != null) {
                    StatusBadge(
                        stringResource(R.string.chatlist_needs_action),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (isPinned) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.chatlist_pinned),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                // The count replaces the old "Subagents" pill on parents: with the children
                // indented underneath, what is worth saying is how many are down there when the
                // row is closed.
                if (childCount > 0) {
                    StatusBadge(childCount.toString())
                } else if (session.origin == "subagent" && depth == 0) {
                    // Only reached by an orphan — its whole ancestry is archived or blank — where
                    // the indent cannot say what the row is.
                    StatusBadge(stringResource(R.string.chatlist_subagents))
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            ChatListMenuItem(stringResource(R.string.chatlist_session_rename), Icons.Filled.Edit) {
                menuOpen = false
                renameOpen = true
            }
            ChatListMenuItem(stringResource(R.string.chatlist_session_fork), Icons.Filled.CallSplit) {
                menuOpen = false
                scope.launch { store.forkSession(session.sessionId) }
            }
            if (pinAvailable && !isArchived) {
                ChatListMenuItem(
                    stringResource(if (isPinned) R.string.chatlist_session_unpin else R.string.chatlist_session_pin),
                    Icons.Filled.PushPin,
                ) {
                    menuOpen = false
                    scope.launch { store.setSessionPinned(session.sessionId, !isPinned) }
                }
            }
            HorizontalDivider()
            if (isArchived) {
                ChatListMenuItem(stringResource(R.string.chatlist_session_unarchive), Icons.Filled.Unarchive) {
                    menuOpen = false
                    scope.launch { store.unarchiveSession(session.sessionId) }
                }
            } else {
                ChatListMenuItem(stringResource(R.string.chatlist_session_archive), Icons.Filled.Archive) {
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
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable {
                scope.launch {
                    store.openSession(hit.session.sessionId)
                    awaitSessionSelected(store.currentSessionId, hit.session.sessionId)
                    onClose()
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionTitle(hit.session),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (hit.session.origin == "subagent") {
                    Spacer(Modifier.width(DsSpacing.xsmall))
                    StatusBadge(stringResource(R.string.chatlist_subagents))
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                hit.workspaceLabel.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                hit.snippet?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatlist_new_session_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny)) {
                if (!workspacesLoaded) {
                    Text(
                        stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (workspaces.isEmpty()) {
                    Text(
                        stringResource(R.string.chatlist_no_workspaces),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(workspaces, key = { it.workspaceId }) { workspace ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onPick(workspace.workspaceId) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(
                                    workspace.title.ifBlank { basename(workspace.path) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(workspace.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
                HorizontalDivider()
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPick(null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.chatlist_home_directory)) },
                    supportingContent = {
                        homeCwd?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatlist_new_workspace)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.chatlist_workspace_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                current?.let { directory ->
                    Text(
                        text = selectedPath ?: directory.path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { selectedPath = directory.path },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
                    ) {
                        directory.crumbs.forEach { crumb ->
                            AssistChip(
                                onClick = { selectedPath = crumb.path; load(crumb.path) },
                                label = { Text(crumb.name, maxLines = 1) },
                            )
                        }
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(entries, key = { it.path }) { entry ->
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClick = {
                                        selectedPath = entry.path
                                        search = ""
                                        load(entry.path)
                                    }),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                if (loading) {
                    Text(
                        stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedPath?.let(onCreate) },
                enabled = selectedPath != null && !loading,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

internal fun sessionChildrenExpanded(
    sessionId: String,
    childCollapsed: Map<String, Boolean>,
    openPath: Set<String>,
): Boolean = childCollapsed[sessionId]?.not() ?: (sessionId in openPath)

internal fun toggleSessionChildren(
    sessionId: String,
    childCollapsed: MutableMap<String, Boolean>,
    openPath: Set<String>,
) {
    // Store the *previous expanded* state as the new collapsed state. Negating it here
    // would leave the chevron unchanged, including when an active child opens its parent by default.
    childCollapsed[sessionId] = sessionChildrenExpanded(sessionId, childCollapsed, openPath)
}

/** Preserve the server pin order, excluding only rows not rendered as top-level sessions. */
internal fun pinnedRootIds(pins: List<String>, visibleIds: Set<String>, nestedIds: Set<String>): List<String> =
    pins.filter { it in visibleIds && it !in nestedIds }

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
