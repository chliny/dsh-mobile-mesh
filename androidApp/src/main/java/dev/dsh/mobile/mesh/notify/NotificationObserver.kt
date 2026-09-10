package dev.dsh.mobile.mesh.notify

import android.content.Context
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.connection.AppSettings
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.HostsStore
import dev.dsh.mobile.mesh.core.notify.CompletionClassifier
import dev.dsh.mobile.mesh.core.notify.CompletionEvent
import dev.dsh.mobile.mesh.core.session.SessionEventEnvelope
import dev.dsh.mobile.mesh.core.wire.dto.RemoteEventFrame
import dev.dsh.mobile.mesh.data.SessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns completion/needs-action wire signals into local notifications.
 *
 * `start()` launches one collector on the connection manager's host event stream; call it once
 * from [android.app.Application.onCreate] after the store is initialized. Each notification id is
 * stable per session (hash % 1000 + a per-channel offset) so re-notifying the same session
 * replaces the previous one instead of stacking.
 *
 * Turn and goal completions are not seen here any more. Through 0.1.1 they arrived as
 * `session/event` frames on the all-session mux, which this observer read directly; 0.1.2 carries
 * session events only on a per-session `session/follow` stream, so [SessionStore] — which owns
 * those streams — reports them through [onSessionEvent] instead. What remains on the host event
 * stream is the two pending-request waterfalls and the session status notification.
 */
@Singleton
class NotificationObserver @Inject constructor(
    private val store: SessionStore,
    private val connectionManager: ConnectionManager,
    private val notifications: DshNotifications,
    private val hostsStore: HostsStore,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val classifier = CompletionClassifier()
    private val seen = LinkedHashSet<String>()

    @Volatile
    private var settings = AppSettings()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        notifications.ensureChannels()
        scope.launch {
            hostsStore.settings.collect { settings = it }
        }
        scope.launch {
            connectionManager.eventFrames.collect { handleEventFrame(it) }
        }
    }

    private fun handleEventFrame(frame: RemoteEventFrame) {
        when (frame) {
            is RemoteEventFrame.Waterfall ->
                classifier.classifyWaterfall(frame.event, frame.eventId, frame.agentId, frame.request)
                    ?.let { maybeNotify(it) }

            is RemoteEventFrame.Emit ->
                classifier.classifyNotification(frame.event, frame.args)?.let { maybeNotify(it) }

            // A withdrawn request needs no notification of its own; the prompt it belongs to is
            // retired by whoever is showing it. Ready never reaches here, and an unknown frame
            // kind is exactly what this observer should ignore.
            is RemoteEventFrame.Cancel,
            is RemoteEventFrame.Ready,
            is RemoteEventFrame.Unknown,
            -> Unit
        }
    }

    /**
     * One session event, forwarded by [SessionStore] from that session's follow stream.
     *
     * The observer cannot subscribe to these itself: 0.1.2 has no all-session event stream, and
     * opening one follow per session purely to watch for completions would resume agents the user
     * never opened.
     */
    fun onSessionEvent(sessionId: String, envelope: SessionEventEnvelope) {
        when (envelope.type) {
            "turn/start" -> classifier.markSessionRunning(sessionId)
            "turn/end", "goal/change" ->
                classifier.classifyEvent(sessionId, envelope)?.let { maybeNotify(it) }
        }
    }

    private fun maybeNotify(event: CompletionEvent) {
        if (!notifications.canPost()) return
        if (store.isSessionOpen(event.sessionId)) return

        val spec = when (event) {
            is CompletionEvent.TurnComplete -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_turn_complete, sessionTitle(event.sessionId).orEmpty()),
                )
            }
            is CompletionEvent.GoalComplete -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_complete, event.objective.orEmpty()),
                )
            }
            is CompletionEvent.GoalBlocked -> {
                if (!settings.notifyGoal) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_goal_blocked, event.reason.orEmpty()),
                )
            }
            is CompletionEvent.ReviewRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_review_requested, event.toolName),
                    actionLabel = context.getString(R.string.notif_open),
                )
            }
            is CompletionEvent.QuestionRequested -> {
                if (!settings.notifyNeedsAction) return
                Spec(
                    channel = DshNotifications.CHANNEL_ACTION,
                    title = context.getString(R.string.notif_question, event.firstQuestion.orEmpty()),
                )
            }
            is CompletionEvent.SessionIdle -> {
                if (!settings.notifyTurnComplete) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_session_idle, sessionTitle(event.sessionId).orEmpty()),
                )
            }
        }

        if (isDuplicate(event.dedupKey)) return

        val text = context.getString(R.string.notif_open)
        val id = notificationId(event.sessionId, spec.channel)
        notifications.postSession(spec.channel, id, spec.title, text, event.sessionId, spec.actionLabel)
    }

    private data class Spec(
        val channel: String,
        val title: String,
        val actionLabel: String? = null,
    )

    private fun sessionTitle(sessionId: String): String? =
        store.sessions.value.firstOrNull { it.sessionId == sessionId }?.title

    private fun notificationId(sessionId: String, channel: String): Int {
        val offset = when (channel) {
            DshNotifications.CHANNEL_ACTION -> CHANNEL_ACTION_OFFSET
            DshNotifications.CHANNEL_CONNECTION -> CHANNEL_CONNECTION_OFFSET
            else -> CHANNEL_COMPLETIONS_OFFSET
        }
        val base = ((sessionId.hashCode() % 1000) + 1000) % 1000
        return base + offset
    }

    private fun isDuplicate(key: String): Boolean {
        synchronized(seen) {
            if (key in seen) return true
            seen.add(key)
            while (seen.size > MAX_DEDUP_KEYS) {
                val it = seen.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                } else {
                    break
                }
            }
            return false
        }
    }

    private companion object {
        const val MAX_DEDUP_KEYS = 1024
        const val CHANNEL_COMPLETIONS_OFFSET = 1000
        const val CHANNEL_ACTION_OFFSET = 2000
        const val CHANNEL_CONNECTION_OFFSET = 3000
    }
}
