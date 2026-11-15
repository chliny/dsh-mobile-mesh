package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.data.NewSessionDraftScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One composer draft captured in a new (blank) session.
 *
 * [sessionId] is the blank session the attachments were staged in. File uploads answer with a
 * receipt that is only valid in the session that minted it, so when the Host hands back another
 * blank session the files have to be uploaded again instead of citing the old receipt.
 */
internal data class NewSessionDraft(
    val text: String,
    val attachments: List<PendingAttachment>,
    val sessionId: String?,
)

/**
 * Process-scoped drafts for new sessions, keyed by the Host workspace they were typed for.
 *
 * Deliberately not keyed by session: `session/create` may reuse a different blank session, and a
 * draft typed while starting a session in one workspace must never appear in another. The store is
 * cleared when the first message turns the draft into a real transcript, so reopening "New session"
 * starts empty again.
 */
@Singleton
class NewSessionDraftStore @Inject constructor() {
    private val drafts = mutableMapOf<NewSessionDraftScope, NewSessionDraft>()

    @Synchronized
    internal fun get(scope: NewSessionDraftScope): NewSessionDraft? = drafts[scope]

    @Synchronized
    internal fun set(scope: NewSessionDraftScope, draft: NewSessionDraft) {
        if (draft.text.isEmpty() && draft.attachments.isEmpty()) drafts.remove(scope)
        else drafts[scope] = draft
    }

    @Synchronized
    internal fun clear(scope: NewSessionDraftScope) {
        drafts.remove(scope)
    }
}
