package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.WorkspaceRow

/** One server-ranked content-search hit with locally available display labels. */
internal data class SearchHit(
    val session: SessionRow,
    /** The workspace title, or the working directory's folder when the session belongs to none. */
    val workspaceLabel: String,
    /** The excerpt returned by the Harness session.search endpoint. */
    val snippet: String,
)

/** Map authoritative Harness search results to rows without filtering, sorting, merging or capping. */
internal fun mapSearchResults(
    sessions: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    contentHits: List<Pair<String, String>>,
): List<SearchHit> {
    val sessionsById = sessions.associateBy { it.sessionId }
    val workspaceTitleOf = workspaces
        .flatMap { ws -> ws.sessionIds.map { it to ws.title.ifBlank { basename(ws.path) } } }
        .toMap()

    return contentHits.mapNotNull { (sessionId, snippet) ->
        val session = sessionsById[sessionId] ?: return@mapNotNull null
        SearchHit(
            session = session,
            workspaceLabel = workspaceTitleOf[sessionId] ?: session.cwd?.let { basename(it) } ?: "",
            snippet = snippet,
        )
    }
}
