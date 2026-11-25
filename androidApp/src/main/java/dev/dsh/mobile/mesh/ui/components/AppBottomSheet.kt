package dev.dsh.mobile.mesh.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's sheet surface, themed to the harness tokens.
 *
 * Sheets rather than dialogs for pickers: they arrive from the thumb's end of the screen, size
 * themselves to their content, and let a long list scroll without fighting a fixed-height plate.
 * [trailing] holds an optional action aligned with the title.
 *
 * @param dragToDismiss whether a downward drag may close the sheet. Material3's sheet cannot tell
 *   "the body is still scrolling" from "the user is dismissing", so a sheet whose body is a
 *   scrollable list can close on a swipe that was meant to scroll. Turning this off renders the
 *   same sheet without any drag gesture; scrim taps and the system back gesture still dismiss.
 */
@Composable
fun AppBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    dragToDismiss: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (dragToDismiss) {
        DraggableBottomSheet(title, onDismiss, modifier, subtitle, trailing, content)
    } else {
        FixedBottomSheet(title, onDismiss, modifier, subtitle, trailing, content)
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
 * The same sheet without a drag gesture.
 *
 * A [Dialog] rather than a [ModalBottomSheet] because Material3 1.3's sheet always installs a
 * vertical drag detector, and its nested-scroll handoff cannot reliably keep that detector off a
 * scrollable body. The dialog keeps the sheet's visuals and dismissal surface (scrim, back) while
 * making a downward swipe over the body mean only one thing: scroll.
 */
@Composable
private fun FixedBottomSheet(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val close = {
        if (!closing) {
            closing = true
            visible = false
            scope.launch {
                delay(220)
                onDismiss()
            }
        }
        Unit
    }
    val maxSheetHeight = with(LocalConfiguration.current) { screenHeightDp.dp * 0.9f }
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
                        // Swallow taps on the sheet's own chrome so they do not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    SheetBody(title, subtitle, trailing, grabber = false, content = content)
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
