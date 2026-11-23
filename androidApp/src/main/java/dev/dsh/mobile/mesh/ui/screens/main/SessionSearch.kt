package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.SessionRow
import dev.dsh.mobile.mesh.data.WorkspaceRow
import java.text.Normalizer
import java.util.Locale

/** One merged search row: a locally matched Session and/or a Host content-search hit. */
internal data class SearchHit(
    val session: SessionRow,
    /** The workspace title, or the working directory's folder when the session belongs to none. */
    val workspaceLabel: String,
    /** The excerpt returned by the Harness session.search endpoint, when content matched too. */
    val snippet: String?,
)

/** Bounded merged results plus the bit that says the query was too broad to show it all. */
internal data class SearchResultSet(
    val items: List<SearchHit>,
    val hasMore: Boolean,
)

/**
 * Rows one query shows before asking for a narrower query.
 *
 * The Harness web sidebar caps its merged list at the Host's `session/search`
 * page size (`SESSION_SEARCH_RESULT_LIMIT`, 20), so the app shows the same page.
 */
internal const val SEARCH_RESULT_LIMIT = 20

/**
 * Merge local metadata matches with the Host's content-search hits, the way the
 * Harness web sidebar does.
 *
 * `session/search` is full-text over **message content**: it never matches a
 * Session title, a Workspace name, or a Session id. The web sidebar therefore
 * searches those locally and appends the Host's ranked content hits. Rendering
 * only the Host half is why a Session whose title matched on the web could not
 * be found in the app at all.
 *
 * Local matching reproduces the web `TextSearchIndex` contract: every
 * whitespace-separated term must match one of the row's keys; a title or
 * Workspace label matches as a substring or as a word-start fuzzy subsequence,
 * and the Session id matches as a contiguous substring. Local rows lead best
 * match first and newest first among equal matches; content-only rows keep the
 * Host's order, and a row matched both ways keeps its local position and gains
 * the excerpt.
 *
 * Rows the session list hides are left out of both halves: blank Sessions
 * (their title is a localized placeholder), archived Sessions unless the
 * archived filter shows them, and completed subagent transcripts (running
 * subagents stay searchable, as they stay visible under their parent).
 *
 * The `/api/session/search` Remote remains the sole authority for message
 * content; this function never synthesizes a content match.
 */
internal fun deriveSearchResults(
    sessions: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    archivedIds: Set<String>,
    archivedFilter: ArchivedSessionFilter,
    query: String,
    contentHits: List<Pair<String, String>>,
    contentHasMore: Boolean = false,
    limit: Int = SEARCH_RESULT_LIMIT,
): SearchResultSet {
    val terms = searchTerms(query)
    if (terms.isEmpty()) return SearchResultSet(emptyList(), hasMore = false)

    val sessionsById = sessions.associateBy(SessionRow::sessionId)
    val workspaceTitleOf = HashMap<String, String>()
    for (workspace in workspaces) {
        val label = workspace.title.ifBlank { basename(workspace.path) }
        for (sessionId in workspace.sessionIds) workspaceTitleOf.putIfAbsent(sessionId, label)
    }
    fun labelOf(session: SessionRow): String =
        workspaceTitleOf[session.sessionId] ?: session.cwd?.let { basename(it) }.orEmpty()

    fun visible(session: SessionRow): Boolean {
        if (session.blank) return false
        if (session.origin == "subagent" && !session.running) return false
        return when (archivedFilter) {
            ArchivedSessionFilter.HIDE -> session.sessionId !in archivedIds
            ArchivedSessionFilter.SHOW -> true
            ArchivedSessionFilter.ONLY -> session.sessionId in archivedIds
        }
    }

    // Local rows: title, Workspace label, and Session id. The Session id is an
    // opaque substring key, exactly as in the web index; it never takes part in
    // fuzzy matching.
    val local = sessions
        .filter { visible(it) }
        .mapNotNull { session ->
            val score = sessionSearchScore(
                terms = terms,
                title = session.title?.trim().orEmpty(),
                workspace = labelOf(session),
                sessionId = session.sessionId,
            ) ?: return@mapNotNull null
            session to score
        }
        .sortedWith(
            compareByDescending<Pair<SessionRow, Int>> { it.second }
                .thenByDescending { it.first.updatedAt }
                .thenBy { it.first.sessionId },
        )
        .map { it.first }

    // The first Host snippet for a Session wins, matching the web merge.
    val snippetBySession = LinkedHashMap<String, String>()
    for ((sessionId, snippet) in contentHits) {
        if (!snippetBySession.containsKey(sessionId)) snippetBySession[sessionId] = snippet
    }

    val included = HashSet<String>()
    val rows = ArrayList<SearchHit>()
    fun append(session: SessionRow) {
        if (!included.add(session.sessionId)) return
        rows += SearchHit(
            session = session,
            workspaceLabel = labelOf(session),
            snippet = snippetBySession[session.sessionId]?.takeIf { it.isNotBlank() },
        )
    }
    // Local rows lead; a content hit for one of them only adds its snippet.
    local.forEach(::append)
    // Content-only rows keep the Host's ranking and may still name a Session the
    // current list page does not render, which is not a result the app can show.
    for (sessionId in snippetBySession.keys) {
        val session = sessionsById[sessionId]?.takeIf { visible(it) } ?: continue
        append(session)
    }

    return SearchResultSet(
        items = rows.take(limit),
        hasMore = contentHasMore || rows.size > limit,
    )
}

/** Tier bases mirroring the web `TextSearchIndex`: a fuzzy subsequence never outranks a substring. */
private const val FUZZY_PREFIX = 4
private const val FUZZY_SUBSTRING = 3
private const val FUZZY_SUBSEQUENCE = 2
private const val SUBSTRING = 1

private val QUERY_WHITESPACE = Regex("\\s+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Split and fold the query exactly as the web index does: terms are trimmed and case-insensitive. */
private fun searchTerms(query: String): List<String> =
    query.trim().split(QUERY_WHITESPACE).map(::foldSearchText).filter { it.isNotEmpty() }

/** Case-, width-, and diacritic-insensitive form used for substring tests. */
private fun foldSearchText(value: String): String =
    stripDiacritics(value).lowercase(Locale.ROOT)

/** Width- and diacritic-insensitive form that keeps case, so camel-case word starts survive. */
private fun stripDiacritics(value: String): String =
    if (value.all { it.code < 0x80 }) value
    else Normalizer.normalize(value, Normalizer.Form.NFKD).replace(COMBINING_MARKS, "")

/** Best score of every query term over one Session's keys, or null when any term misses. */
private fun sessionSearchScore(
    terms: List<String>,
    title: String,
    workspace: String,
    sessionId: String,
): Int? {
    val fuzzyKeys = listOf(title, workspace).map(::SearchableKey)
    val foldedId = foldSearchText(sessionId)
    var total = 0
    for (term in terms) {
        var best = if (foldedId.contains(term)) SUBSTRING else Int.MIN_VALUE
        for (key in fuzzyKeys) {
            val score = key.score(term) ?: continue
            if (score > best) best = score
        }
        if (best == Int.MIN_VALUE) return null
        total += best
    }
    return total
}

/** One prepared fuzzy key: folded text for substring tiers, case-preserved text for word starts. */
private class SearchableKey(value: String) {
    private val stripped = stripDiacritics(value)
    private val folded = stripped.lowercase(Locale.ROOT)

    /**
     * Web tiers: prefix, then substring, then a subsequence that starts at a
     * word start; an absent or empty key never matches.
     */
    fun score(term: String): Int? {
        if (folded.isEmpty() || term.isEmpty()) return null
        val at = folded.indexOf(term)
        if (at == 0) return FUZZY_PREFIX
        if (at > 0) return FUZZY_SUBSTRING
        return if (matchesWordStartSubsequence(stripped, term)) FUZZY_SUBSEQUENCE else null
    }
}

/**
 * Whether [term] occurs in [text] in order from some word start, such as
 * `wkpl` in `Weekly planning`. The first matched character must itself start a
 * word, so `fab` never matches inside `Refactor build`.
 */
private fun matchesWordStartSubsequence(text: String, term: String): Boolean {
    if (text.length < term.length) return false
    for (start in text.indices) {
        if (!isWordStart(text, start)) continue
        if (text[start].lowercaseChar() != term[0]) continue
        if (isSubsequence(text, start, term)) return true
    }
    return false
}

private fun isSubsequence(text: String, start: Int, term: String): Boolean {
    var cursor = start
    for (character in term) {
        while (cursor < text.length && text[cursor].lowercaseChar() != character) cursor++
        if (cursor == text.length) return false
        cursor++
    }
    return true
}

/** Mirrors the web word-start rule: start, after a separator, a camel hump, or an unspaced script. */
private fun isWordStart(text: String, index: Int): Boolean {
    if (index == 0) return true
    val current = text[index]
    val previous = text[index - 1]
    return !previous.isLetterOrDigit() || isUnspacedScript(current) ||
        (current.isUpperCase() && previous.isLowerCase())
}

private fun isUnspacedScript(character: Char): Boolean =
    when (Character.UnicodeScript.of(character.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }
