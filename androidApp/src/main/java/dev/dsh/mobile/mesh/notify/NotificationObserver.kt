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
import dev.dsh.mobile.mesh.ui.AppNavigationState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
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
 *
 * A delegated child's completion is silenced by default: one parent turn can fan out into many
 * children, and each one ending would otherwise bury the notification the user is waiting for.
 * [AppSettings.notifySubagentComplete] opts back in, layered under [AppSettings.notifyTurnComplete].
 */
@Singleton
class NotificationObserver @Inject constructor(
    private val store: SessionStore,
    private val connectionManager: ConnectionManager,
    private val notifications: DshNotifications,
    private val hostsStore: HostsStore,
    private val navigationState: AppNavigationState,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val classifier = CompletionClassifier()
    private val seen = LinkedHashSet<String>()
    private val settingsReady = CompletableDeferred<Unit>()
    // Preferences can be slow or unreadable on startup. Bound this queue so the event collectors
    // never accumulate an unbounded backlog while waiting for the first settings value.
    private val pendingNotifications = Channel<PendingNotification>(capacity = 512)

    @Volatile
    private var settings = AppSettings()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        notifications.ensureChannels()
        // Session events are delivered only through the per-session follow stream. SessionStore
        // owns that stream and forwards terminal events here, avoiding a second follower that could
        // resume sessions the user never opened.
        store.notificationSink = ::onSessionEvent
        scope.launch {
            hostsStore.settings
                .catch { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.w("NotificationObserver", "Unable to read notification settings; using defaults", error)
                    emit(AppSettings())
                }
                .collect {
                    settings = it
                    settingsReady.complete(Unit)
                }
        }
        scope.launch {
            settingsReady.await()
            for (event in pendingNotifications) postNotification(event)
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
        if (navigationState.isChatVisible(event.sessionId)) return
        val hostId = connectionManager.state.value.host?.id ?: return
        val row = store.sessions.value.firstOrNull { it.sessionId == event.sessionId }
        if (pendingNotifications.trySend(PendingNotification(event, hostId, row?.title, row?.origin == "subagent")).isFailure) {
            android.util.Log.w("NotificationObserver", "Notification backlog full before settings were ready")
        }
    }

    private fun postNotification(pending: PendingNotification) {
        if (!notifications.canPost()) return
        val event = pending.event
        val subagent = pending.subagent
        val spec = when (event) {
            is CompletionEvent.TurnComplete -> {
                if (!turnCompletionEnabled(subagent)) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = if (subagent) {
                        subagentFinishedTitle(pending.title)
                    } else {
                        context.getString(R.string.notif_turn_complete, pending.title.orEmpty())
                    },
                )
            }
            is CompletionEvent.TurnInterrupted -> {
                if (!turnCompletionEnabled(subagent)) return
                Spec(
                    channel = DshNotifications.CHANNEL_COMPLETIONS,
                    title = context.getString(R.string.notif_turn_interrupted, pending.title.orEmpty()),
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
                if (!turnCompletionEnabled(subagent)) return
                if (subagent) {
                    Spec(
                        channel = DshNotifications.CHANNEL_COMPLETIONS,
                        title = subagentFinishedTitle(pending.title),
                    )
                } else {
                    val title = pending.title
                    Spec(
                        channel = DshNotifications.CHANNEL_COMPLETIONS,
                        title = if (title.isNullOrBlank()) {
                            context.getString(R.string.notif_session_idle_untitled)
                        } else {
                            context.getString(R.string.notif_session_idle, title)
                        },
                    )
                }
            }
        }

        // Idle notifications have no durable sequence; the classifier already emits only once
        // per running-to-idle transition. A permanent zero-seq key would suppress later runs.
        if (event !is CompletionEvent.SessionIdle && isDuplicate(event.dedupKey)) return

        val id = notificationId(event.sessionId, spec.channel)
        notifications.postSession(spec.channel, id, spec.title, "", event.sessionId, pending.hostId)
    }

    private data class PendingNotification(
        val event: CompletionEvent,
        val hostId: String,
        val title: String?,
        val subagent: Boolean,
    )

    private data class Spec(
        val channel: String,
        val title: String,
    )

    /**
     * Turn completion as gated by the notification settings.
     *
     * [AppSettings.notifySubagentComplete] refines [AppSettings.notifyTurnComplete] instead of
     * replacing it: a child completes by ending a turn, so turning turn notifications off silences
     * its completions even when the child preference was left on.
     */
    private fun turnCompletionEnabled(subagent: Boolean): Boolean =
        settings.notifyTurnComplete && (!subagent || settings.notifySubagentComplete)

    /** A finished child's own title when the host projected one; children are often untitled. */
    private fun subagentFinishedTitle(title: String?): String {
        return if (title.isNullOrBlank()) {
            context.getString(R.string.notif_subagent_complete_untitled)
        } else {
            context.getString(R.string.notif_subagent_complete, title)
        }
    }

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
