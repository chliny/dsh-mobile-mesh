package dev.dsh.mobile.mesh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How a sheet may be closed by a drag. */
sealed interface SheetDragDismiss {
    /** Material3's sheet: a downward drag anywhere on the sheet closes it. */
    data object Draggable : SheetDragDismiss

    /**
     * A downward drag closes the sheet only when [atTop] says the body's scrollable list is already
     * at its top. Otherwise the drag belongs to the list and only scrolls it. The predicate is read
     * once, when a gesture starts, so a fling that reaches the top mid-flight cannot turn into a
     * dismissal.
     */
    data class FromTop(val atTop: () -> Boolean) : SheetDragDismiss
}

/**
 * The app's sheet surface, themed to the harness tokens.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 * [trailing] holds an optional action aligned with the title.
 *
 * @param dragDismiss how (and whether) a drag may close the sheet. Material3's sheet cannot tell
 *   "the body is still scrolling" from "the user is dismissing", so a sheet with a scrollable body
 *   that must never miscount a scroll as a dismissal uses [SheetDragDismiss.FromTop].
 */
@Composable
fun AppBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    dragDismiss: SheetDragDismiss = SheetDragDismiss.Draggable,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (dragDismiss) {
        SheetDragDismiss.Draggable ->
            DraggableBottomSheet(title, onDismiss, modifier, subtitle, trailing, content)
        is SheetDragDismiss.FromTop ->
            GuardedDragBottomSheet(title, onDismiss, modifier, subtitle, trailing, atTop = dragDismiss.atTop, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraggableBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        SheetBody(title, subtitle, trailing, grabber = true, content = content)
    }
}

/**
 * The same sheet driven by our own drag logic.
 *
 * A [Dialog] rather than a [ModalBottomSheet] because Material3 1.3's sheet always installs a
 * vertical drag detector and its nested-scroll handoff cannot reliably keep that detector off a
 * scrollable body. Owning the gesture lets [atTop] gate it: a downward swipe only moves (and can
 * close) the sheet when the list is already at the top; otherwise the list scrolls untouched.
 */
@Composable
private fun GuardedDragBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    atTop: () -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // The gesture handler is keyed on Unit so a recomposition (the drag offset changes every frame)
    // does not cancel the drag; lambdas are read through updated-state instead.
    val currentAtTop = rememberUpdatedState(atTop)
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { visible = true }
    val close = {
        if (!closing) {
            closing = true
            settleJob?.cancel()
            visible = false
            scope.launch {
                delay(220)
                onDismiss()
            }
        }
        Unit
    }
    val currentClose = rememberUpdatedState(close)
    val maxSheetHeight = with(LocalConfiguration.current) { screenHeightDp.dp * 0.9f }
    val distanceThreshold = with(density) { 96.dp.toPx() }
    val velocityThreshold = with(density) { 800.dp.toPx() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The dialog window already dims the app behind it; this layer only turns a tap outside
            // the sheet into a dismissal, so it stays transparent.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = close,
                    ),
            )
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                Surface(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .navigationBarsPadding()
                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // The predicate is sampled once per gesture: a list that is scrolled
                                // mid-flight must not hand over to a dismissal later.
                                if (!currentAtTop.value()) return@awaitEachGesture
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var ownsDrag = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        if (ownsDrag) {
                                            val velocity = tracker.calculateVelocity().y
                                            if (dragOffset > distanceThreshold || velocity > velocityThreshold) {
                                                currentClose.value()
                                            } else {
                                                settleJob?.cancel()
                                                settleJob = scope.launch {
                                                    animate(
                                                        initialValue = dragOffset,
                                                        targetValue = 0f,
                                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                                    ) { value, _ -> dragOffset = value }
                                                }
                                            }
                                        }
                                        break
                                    }
                                    // The list consumes even a downward move it cannot use (its
                                    // overscroll effect), so read the raw delta: a top-of-list
                                    // downward gesture still belongs to the sheet.
                                    val delta = change.positionChangeIgnoreConsumed()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    if (ownsDrag) {
                                        change.consume()
                                        dragOffset = (dragOffset + delta.y).coerceAtLeast(0f)
                                    } else if (delta.y > 0f && delta.y > abs(delta.x)) {
                                        settleJob?.cancel()
                                        ownsDrag = true
                                        change.consume()
                                        dragOffset = delta.y
                                    } else if (delta.y < 0f) {
                                        // Upward drag: the list scrolls; leave the gesture alone.
                                        break
                                    }
                                }
                            }
                        }
                        // Swallow taps on the sheet's own chrome so they do not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    SheetBody(title, subtitle, trailing, grabber = true, content = content)
                }
            }
        }
    }
}

@Composable
private fun SheetBody(
    title: String?,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    grabber: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.large, vertical = DsSpacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
    ) {
        // A short grabber stands in for the platform drag handle so a draggable sheet still reads
        // as such without the default's heavy vertical padding.
        if (grabber) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Spacer(
                    Modifier
                        .fillMaxWidth(0.12f)
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
        }
        if (title != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
        }
        content()
        Spacer(Modifier.height(DsSpacing.small))
    }
}
