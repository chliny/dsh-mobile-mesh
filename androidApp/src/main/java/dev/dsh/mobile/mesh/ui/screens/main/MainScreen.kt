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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.connection.ConnectionPhase
import dev.dsh.mobile.mesh.ui.theme.DsAnimations

private sealed interface MainPage {
    data object Chat : MainPage
    data class Files(val path: String = ".") : MainPage
    data class Preview(val path: String, val title: String, val returnPage: MainPage) : MainPage
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
    var page by remember { mutableStateOf<MainPage>(MainPage.Chat) }

    var detailsOpen by remember { mutableStateOf(false) }
    val detailsWidth = 300.dp

    val store = dev.dsh.mobile.mesh.ui.rememberSessionStore()
    val sessionId by store.currentSessionId.collectAsStateWithLifecycle()
    if (page != MainPage.Chat) {
        val sid = sessionId
        if (sid == null) {
            page = MainPage.Chat
        } else {
            when (val current = page) {
                is MainPage.Files -> WorkspaceFilesScreen(
                    sessionId = sid,
                    initialPath = current.path,
                    onBack = { page = MainPage.Chat },
                    onOpenFile = { path, title -> page = MainPage.Preview(path, title, current) },
                )
                is MainPage.Preview -> FilePreviewScreen(
                    sessionId = sid,
                    path = current.path,
                    title = current.title,
                    onBack = { page = current.returnPage },
                )
                MainPage.Chat -> Unit
            }
            BackHandler {
                page = when (val current = page) {
                    is MainPage.Preview -> current.returnPage
                    is MainPage.Files -> MainPage.Chat
                    MainPage.Chat -> MainPage.Chat
                }
            }
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
                onOpenFiles = { page = MainPage.Files() },
                onOpenFile = { path, title -> page = MainPage.Preview(path, title, MainPage.Chat) },
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
                )
            }
        }
}
