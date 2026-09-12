package dev.dsh.mobile.mesh.ui.screens.main

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.ContextBreakdownView
import dev.dsh.mobile.mesh.core.wire.dto.ContextPressureView
import dev.dsh.mobile.mesh.core.wire.dto.CommandDescriptor
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceDirectoryEntry
import dev.dsh.mobile.mesh.core.wire.dto.FULL_ACCESS_PRESET
import dev.dsh.mobile.mesh.core.wire.dto.EncodedImageAttachment
import dev.dsh.mobile.mesh.core.wire.dto.FileAttachmentRef
import dev.dsh.mobile.mesh.core.wire.dto.PermissionSelect
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import dev.dsh.mobile.mesh.core.wire.dto.displayPermissionPreset
import dev.dsh.mobile.mesh.ui.components.ContextMeter
import dev.dsh.mobile.mesh.ui.components.skeleton
import dev.dsh.mobile.mesh.ui.theme.DsAnimations
import dev.dsh.mobile.mesh.ui.theme.DsShapes
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType

/**
 * Something picked and waiting to be sent with the next message.
 *
 * Two kinds, because the harness carries them two ways. An [Image] rides the prompt as bytes,
 * so it is held here fully encoded. A [File] is uploaded the moment it is picked — harness
 * 0.1.3 streams it to the host verbatim — and what the prompt carries is the receipt that upload
 * answered with, so the chip tracks the upload rather than the bytes.
 */
internal sealed interface PendingAttachment {
    /** Display name, when the picker had one. */
    val name: String?

    /**
     * A picked image, held with its decoded preview.
     *
     * [bytes], [width] and [height] are the encoded size and intrinsic dimensions the picker already
     * had to learn to admit the image; keeping them means the *next* pick can be measured against
     * the message's running totals without decoding everything already attached a second time.
     */
    data class Image(
        val mediaType: String,
        val base64: String,
        val preview: ImageBitmap?,
        val bytes: Int,
        val width: Int,
        val height: Int,
        override val name: String? = null,
    ) : PendingAttachment {
        /** The wire form `session/prompt` carries. */
        fun encoded(): EncodedImageAttachment = EncodedImageAttachment(mediaType, base64, name)
    }

    /** A picked file and the state of its upload. [id] is what a progress callback keys on. */
    data class File(
        val id: String,
        val uri: Uri,
        override val name: String,
        /** Size as the provider reported it; `-1` when it would not say. */
        val size: Long,
        val state: FileUploadState,
    ) : PendingAttachment {
        /** The receipt to cite, once the upload has one. */
        val receiptId: String? get() = (state as? FileUploadState.Ready)?.receiptId
    }
}

/** Where one file's upload stands. */
internal sealed interface FileUploadState {
    /** Bytes are still going up; [sent] is the running count. */
    data class Uploading(val sent: Long) : FileUploadState

    /** The host holds the file and minted a receipt for it. */
    data class Ready(val receiptId: String, val file: FileAttachmentRef) : FileUploadState

    /** The upload did not complete; the chip offers a retry. */
    data class Failed(val message: String) : FileUploadState
}

/**
 * The message composer, laid out like the harness's own: the `+` and the permission chip on the
 * left, the send affordance on the right.
 *
 * The model selector sits below the input beside the permission control, matching the web composer.
 */
@Composable
internal fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    attachments: List<PendingAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onRetryAttachment: (Int) -> Unit,
    permissions: PermissionSelect?,
    pendingPermission: String?,
    onPermissionPick: (String) -> Unit,
    contextBreakdown: ContextBreakdownView?,
    contextPressure: ContextPressureView?,
    models: SessionModelsValue?,
    onOpenModels: () -> Unit,
    running: Boolean,
    enabled: Boolean,
    onOpenSheet: () -> Unit,
    commands: List<CommandDescriptor> = emptyList(),
    fileCandidates: List<WorkspaceDirectoryEntry> = emptyList(),
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val haptics = LocalHapticFeedback.current
    // A file that is still uploading has no receipt to cite yet, and one that failed never will;
    // the send affordance waits for the chips rather than sending a message that names neither.
    val attachmentsSettled = attachments.none { it is PendingAttachment.File && it.state !is FileUploadState.Ready }
    val canSend = enabled && (draft.isNotBlank() || attachments.isNotEmpty()) && attachmentsSettled
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraftChange by rememberUpdatedState(onDraftChange)
    val currentOnSend by rememberUpdatedState(onSend)
    var commandPickerOpen by remember { mutableStateOf(false) }
    var filePickerOpen by remember { mutableStateOf(false) }
    val mentionQuery = draft.substringAfterLast('@').takeIf { '@' in draft && !draft.substringAfterLast('@').contains(' ') }.orEmpty()
    val mentionActive = '@' in draft && !draft.substringAfterLast('@').contains(' ')
    val matchingFiles = remember(fileCandidates, mentionQuery) {
        fileCandidates.filter { it.name.contains(mentionQuery, ignoreCase = true) }.take(8)
    }
    val commandQuery = draft.removePrefix("/").takeIf { draft.startsWith("/") && !draft.contains(' ') }.orEmpty()
    val matchingCommands = remember(commands, commandQuery) {
        commands.filter { it.name.startsWith(commandQuery, ignoreCase = true) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small)
            .animateContentSize(),
        shape = DsShapes.composer,
        color = colors.composerCard,
        border = BorderStroke(2.dp, colors.borderL2),
    ) {
        Column(
            Modifier.padding(DsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            TextField(
                value = draft,
                onValueChange = {
                    onDraftChange(it)
                    commandPickerOpen = it.startsWith("/") && !it.contains(' ')
                    filePickerOpen = '@' in it && !it.substringAfterLast('@').contains(' ')
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                placeholder = {
                    Text(
                        stringResource(R.string.chat_composer_hint),
                        style = DsType.std14,
                        color = colors.labelTertiary,
                    )
                },
                minLines = 1,
                maxLines = 8,
                textStyle = DsType.std14,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = colors.accent,
                    focusedTextColor = colors.labelPrimary,
                    unfocusedTextColor = colors.labelPrimary,
                ),
            )

            AnimatedVisibility(visible = mentionActive && filePickerOpen && matchingFiles.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(DsShapes.block)
                        .background(colors.bgModulePlatform).border(1.dp, colors.borderL2, DsShapes.block),
                ) {
                    matchingFiles.forEach { file ->
                        Text(
                            text = "@${file.name}", style = DsType.std14, color = colors.labelPrimary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onDraftChange(draft.substringBeforeLast('@') + "@${file.name} ")
                                filePickerOpen = false
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = commandPickerOpen && matchingCommands.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(DsShapes.block)
                        .background(colors.bgModulePlatform)
                        .border(1.dp, colors.borderL2, DsShapes.block),
                ) {
                    matchingCommands.take(8).forEach { command ->
                        Text(
                            text = command.line,
                            style = DsType.std14,
                            color = colors.labelPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDraftChange(command.draftPrefix)
                                    commandPickerOpen = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = attachments.isNotEmpty()) {
                AttachmentStrip(attachments, onRemoveAttachment, onRetryAttachment)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.compact),
            ) {
                CircleAction(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.chat_composer_commands),
                    size = 30,
                    background = colors.hoverSolid,
                    tint = colors.labelPrimary,
                    enabled = enabled,
                    onClick = onOpenSheet,
                )

                PermissionChip(
                    select = permissions,
                    pending = pendingPermission,
                    enabled = enabled,
                    onPick = onPermissionPick,
                )

                ModelChip(models = models, onClick = onOpenModels)

                Spacer(Modifier.weight(1f))

                // Send and stop occupy the same slot: the affordance changes meaning during a turn
                // rather than the row re-flowing around a second button appearing.
                AnimatedContent(
                    targetState = running to canSend,
                    transitionSpec = {
                        (fadeIn(DsAnimations.fade) + scaleIn(initialScale = 0.85f))
                            .togetherWith(fadeOut(DsAnimations.fade) + scaleOut(targetScale = 0.85f))
                    },
                    label = "sendStop",
                ) { (isRunning, canSubmit) ->
                    // A running turn keeps Stop only while the composer is empty. Once there is a
                    // message, the primary action becomes Send and uses the selected queue/steer
                    // mode, matching the web input bar.
                    if (isRunning && !canSubmit) {
                        CircleAction(
                            icon = null,
                            contentDescription = stringResource(R.string.chat_composer_stop),
                            size = 36,
                            background = colors.error,
                            tint = Color.White,
                            enabled = true,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            },
                        ) {
                            Box(
                                Modifier
                                    .size(11.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White),
                            )
                        }
                    } else {
                        CircleAction(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(R.string.chat_composer_send),
                            size = 36,
                            background = if (canSend) colors.buttonInfoFill else colors.buttonPrimaryDimmed,
                            tint = if (canSend) Color.White else colors.labelTertiary,
                            enabled = canSend,
                            onClick = {
                                val text = currentDraft
                                currentOnDraftChange("")
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnSend(text)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The permission preset chip.
 *
 * Renders nothing when the projection key is absent: that means the harness composes no permission
 * service at all, and a dead control would be worse than none. Labels come from the wire, because
 * the preset table is deployment-configurable — mapping ids to local strings would mislabel any
 * deployment that renamed one.
 */
@Composable
private fun ModelChip(
    models: SessionModelsValue?,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    val label = models?.let { current ->
        current.groups.firstOrNull { it.id == current.current.provider }
            ?.models?.firstOrNull { it.id == current.current.model }?.name
            ?: current.current.model
    } ?: stringResource(R.string.common_loading)
    Row(
        modifier = Modifier
            .widthIn(max = 112.dp)
            .clip(DsShapes.cube)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = DsType.small13,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun PermissionChip(
    select: PermissionSelect?,
    pending: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val colors = DsTheme.colors
    if (select == null) return
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<String?>(null) }
    val effective = pending ?: select.currentValue
    val option = select.options.firstOrNull { it.value == effective }
    val label = if (option != null) {
        displayPermissionPreset(option.value, option.name)
    } else {
        stringResource(R.string.permission_custom)
    }

    Box {
        Row(
            modifier = Modifier
                .clip(DsShapes.cube)
                .clickable(enabled = enabled && pending == null) { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .then(
                    if (pending != null) {
                        Modifier.skeleton(colors.bgLayer2, colors.hover, DsShapes.cube)
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = stringResource(R.string.permission_preset),
                tint = if (effective == FULL_ACCESS_PRESET) colors.warnLabel else colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                label,
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(12.dp),
            )
        }

        if (menuOpen) {
            PermissionMenu(
                select = select,
                current = effective,
                onDismiss = { menuOpen = false },
                onPick = { value ->
                    menuOpen = false
                    // Full access removes the approval prompt entirely, so it gets an explicit
                    // acknowledgement the way the desktop client does.
                    if (value == FULL_ACCESS_PRESET) confirming = value else onPick(value)
                },
            )
        }
    }

    confirming?.let { target ->
        FullAccessConfirmDialog(
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                onPick(target)
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentStrip(
    attachments: List<PendingAttachment>,
    onRemove: (Int) -> Unit,
    onRetry: (Int) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Box {
                when (attachment) {
                    is PendingAttachment.Image -> Box(
                        Modifier
                            .size(56.dp)
                            .clip(DsShapes.block)
                            .background(colors.bgModulePlatform),
                    ) {
                        attachment.preview?.let {
                            Image(
                                bitmap = it,
                                contentDescription = attachment.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    is PendingAttachment.File -> FileAttachmentChip(attachment, onRetry = { onRetry(index) })
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.toastBg)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(
                            if (attachment is PendingAttachment.File) R.string.chat_composer_remove_file
                            else R.string.chat_composer_remove_image,
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * One file in the strip: name, then either its size, its upload progress, or the failure.
 *
 * The chip is the same height as an image tile so the strip does not step. A failed upload is
 * retried by tapping the chip itself — the remove affordance stays where it is on every kind.
 */
@Composable
private fun FileAttachmentChip(file: PendingAttachment.File, onRetry: () -> Unit) {
    val colors = DsTheme.colors
    val failed = file.state is FileUploadState.Failed
    Row(
        modifier = Modifier
            .height(56.dp)
            .widthIn(min = 120.dp, max = 200.dp)
            .clip(DsShapes.block)
            .background(colors.bgModulePlatform)
            .border(1.dp, if (failed) colors.error else colors.borderL3, DsShapes.block)
            .clickable(enabled = failed, onClick = onRetry)
            .padding(start = 10.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = file.state) {
            is FileUploadState.Uploading -> CircularProgressIndicator(
                progress = {
                    if (file.size > 0) (state.sent.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                trackColor = colors.borderL3,
                strokeWidth = 2.dp,
            )
            else -> Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                tint = if (failed) colors.error else colors.labelSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column {
            Text(
                file.name,
                style = DsType.small13,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (val state = file.state) {
                    is FileUploadState.Uploading -> stringResource(R.string.chat_attachment_uploading)
                    is FileUploadState.Ready -> fileSizeText(state.file.bytes)
                    is FileUploadState.Failed -> stringResource(R.string.chat_attachment_upload_failed)
                },
                style = DsType.caption11,
                color = if (failed) colors.error else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared circular affordance
// ---------------------------------------------------------------------------

@Composable
private fun CircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    contentDescription: String,
    size: Int,
    background: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size.dp),
        enabled = enabled,
        shape = CircleShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            when {
                content != null -> content()
                icon != null -> Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size((size * 0.46f).dp),
                )
            }
        }
    }
}
