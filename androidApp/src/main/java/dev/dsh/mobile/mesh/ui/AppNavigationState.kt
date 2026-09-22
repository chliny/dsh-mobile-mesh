package dev.dsh.mobile.mesh.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

internal data class SessionRouteRequest(val sessionId: String, val requestId: Long)

/** Process-scoped handoff between Activity intents, Compose routing, and notification visibility. */
@Singleton
class AppNavigationState @Inject constructor() {
    private val nextRequestId = AtomicLong()
    private val _sessionRoute = MutableStateFlow<SessionRouteRequest?>(null)
    internal val sessionRoute: StateFlow<SessionRouteRequest?> = _sessionRoute.asStateFlow()

    private val _visibleChatSessionId = MutableStateFlow<String?>(null)
    val visibleChatSessionId: StateFlow<String?> = _visibleChatSessionId.asStateFlow()

    @Volatile private var activityStarted = false

    fun requestSession(sessionId: String) {
        val clean = sessionId.trim()
        if (clean.isNotEmpty()) _sessionRoute.value = SessionRouteRequest(clean, nextRequestId.incrementAndGet())
    }

    internal fun consume(request: SessionRouteRequest) {
        _sessionRoute.compareAndSet(request, null)
    }

    fun setVisibleChatSession(sessionId: String?) {
        _visibleChatSessionId.value = sessionId
    }

    fun setActivityStarted(started: Boolean) {
        activityStarted = started
    }

    fun isChatVisible(sessionId: String): Boolean =
        activityStarted && _visibleChatSessionId.value == sessionId
}

internal fun notificationSessionId(extraSessionId: String?, uriText: String?): String? {
    extraSessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val segments = uriText?.substringAfter("://", "")?.substringAfter('/', "")?.substringBefore('?')
        ?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
    val sessionIndex = segments.indexOf("session")
    if (sessionIndex < 0) return null
    return segments.getOrNull(sessionIndex + 1)?.trim()?.takeIf { it.isNotEmpty() }
}
