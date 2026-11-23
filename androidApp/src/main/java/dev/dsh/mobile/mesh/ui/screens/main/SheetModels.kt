package dev.dsh.mobile.mesh.ui.screens.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.dto.ModelBadge
import dev.dsh.mobile.mesh.core.wire.dto.ModelCatalogModel
import dev.dsh.mobile.mesh.core.wire.dto.SessionModelsValue
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.ui.components.DsBottomSheet
import dev.dsh.mobile.mesh.ui.components.DsSegment
import dev.dsh.mobile.mesh.ui.components.DsSegmented
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Model picker, grouped by provider.
 *
 * Two decisions live here, and they are not peers: which model, and — for a model that reasons —
 * how hard. The harness's own picker separates them into two panes for that reason. This sheet
 * keeps one list but nests the second decision inside the chosen model's card, so the tiers belong
 * visibly to the model they configure. Showing them under every model, as this sheet used to, put a
 * dead control under each row nobody had chosen and tripled the height of the list.
 */
@Composable
internal fun ModelsSheet(
    models: SessionModelsValue?,
    store: SessionStore,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    // The Host localizes badge text itself; this client only picks the reader's language out of
    // the map it was given, falling back to the Host's required `en` when that language is absent.
    val badgeLanguageTags = remember(configuration) { modelBadgeLanguageTags(configuration.locales.get(0)) }
    DsBottomSheet(title = stringResource(R.string.models_title), onDismiss = onDismiss) {
        if (models == null) {
            Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@DsBottomSheet
        }
        val current = models.current
        if (!models.routable) {
            Text(
                stringResource(R.string.models_unroutable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The sheet's own drag handle competes with the list's scroll for downward drags. Material3
        // hands a downward drag to the sheet whenever the inner scroll does not consume it, even when
        // the list is not yet at the top (a fling tail or a gesture the scrollable declined). That is
        // what turns a mid-list downward swipe into the sheet dragging/dismissing instead of scrolling
        // the models. Gate the leftover in onPostScroll: it runs after the list has already scrolled
        // what it can, so a downward leftover while the list is still not at the top is exactly the
        // stray gesture the sheet would otherwise steal — swallow it. Only once the list has reached
        // the top is a downward leftover the user genuinely dragging the sheet, and that passes on.
        //
        // A fling is the second leak: when it reaches the top mid-flight, the sheet is handed the
        // leftover downward velocity in onPostFling and settles to hidden. A downward fling on this
        // list should scroll it, never close the sheet, so swallow that velocity too. A drag that
        // starts at the top still dismisses — the sheet's own pointer detector owns that gesture, and
        // it never passes through nested scroll.
        val listScroll = rememberScrollState()
        val sheetBoundary = remember(listScroll) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    if (source == NestedScrollSource.UserInput && available.y > 0 && listScroll.canScrollBackward) {
                        // List not at the top: the inner scroll left downward distance unclaimed, which
                        // the sheet would read as a dismiss drag. Take it instead of letting it through.
                        Offset(0f, available.y)
                    } else {
                        Offset.Zero
                    }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                    if (available.y > 0f) available else Velocity.Zero
            }
        }
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .nestedScroll(sheetBoundary)
                .verticalScroll(listScroll),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
        ) {
            models.groups.forEach { group ->
                // The provider recedes: it names the shelf, and the models on it are the choice.
                // At the section weight this used the group read as loud as its own contents.
                Text(
                    group.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = DsSpacing.small,
                        top = DsSpacing.small,
                        bottom = DsSpacing.tiny,
                    ),
                )
                group.models.forEach { model ->
                    val isCurrent = current.provider == group.id && current.model == model.id
                    ModelRow(
                        model = model,
                        badgeLanguageTags = badgeLanguageTags,
                        selected = isCurrent,
                        selectedEffort = current.reasoningEffort.takeIf { isCurrent },
                        onSelect = { scope.launch { store.selectModel(group.id, model.id) } },
                        onSelectEffort = { effort ->
                            scope.launch { store.selectModel(group.id, model.id, reasoningEffortSelection(effort)) }
                        },
                    )
                }
            }
            models.failures.forEach { failure ->
                Text(
                    stringResource(R.string.err_model_unavailable, "${failure.name}: ${failure.message}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = DsSpacing.small, vertical = DsSpacing.tiny),
                )
            }
        }
    }
}

/**
 * Language tags used to pick one Host badge translation, most specific first.
 *
 * The Host keys translations by lowercase language id and always includes `en`, which
 * [ModelBadgeText.resolve] falls back to. Trying the full BCP 47 tag before its language keeps a
 * regional translation authoritative when the Host publishes one.
 */
internal fun modelBadgeLanguageTags(locale: Locale): List<String> {
    val tags = LinkedHashSet<String>()
    locale.toLanguageTag().lowercase().takeUnless { it.isEmpty() || it == "und" }?.let(tags::add)
    locale.language.lowercase().takeUnless { it.isEmpty() || it == "und" }?.let(tags::add)
    return tags.toList()
}

/**
 * One model as a card: the name, and — once it is the live one — the reasoning tiers beneath.
 *
 * The chosen card carries an accent wash and border rather than only a blue name and a check
 * stranded at the far edge. A tick 300dp from the word it qualifies is a footnote; a tinted card is
 * the answer to "which one am I on" read at a glance, which is the only question this sheet exists
 * to answer.
 */
// The segmented control uses non-null keys; this reserved key maps back to the wire's null
// selection, which asks the adapter/provider to use its default reasoning effort.
internal const val DEFAULT_REASONING_EFFORT_KEY = "\u0000default"

internal fun reasoningEffortSelection(key: String): String? =
    key.takeUnless { it == DEFAULT_REASONING_EFFORT_KEY }

@Composable
private fun ModelRow(
    model: ModelCatalogModel,
    badgeLanguageTags: List<String>,
    selected: Boolean,
    selectedEffort: String?,
    onSelect: () -> Unit,
    onSelectEffort: (String) -> Unit,
) {
    val efforts = model.reasoning?.efforts.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        // Every model is a card, not only the chosen one. A bare name on the sheet's own colour
        // offers nothing to press: a phone has no hover to discover it with, so the row has to look
        // like a control while it is at rest. `bgModulePlatform` is the one surface that steps off
        // the sheet in both themes — the three `bgLayer` tokens are all pure white in light mode.
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // `selectable` rather than `clickable`: the tint carries the state visually, so
                    // the selection has to reach assistive tech some other way.
                    .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                    .heightIn(min = DsSpacing.touchTarget)
                    .padding(horizontal = DsSpacing.medium, vertical = DsSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            model.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // The Host's own label, in the reader's language when it published one.
                        // It is shown beside the name and never filters, sorts, or gates a model.
                        val badge = model.badge
                        val badgeLabel = badge?.text?.resolve(badgeLanguageTags)
                        if (badge != null && badgeLabel != null) {
                            Spacer(Modifier.width(DsSpacing.xsmall))
                            ModelBadgePill(text = badgeLabel, tone = badge.tone)
                        }
                    }
                    model.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Spacer(Modifier.width(DsSpacing.small))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.models_current),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (selected && efforts.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(
                        start = DsSpacing.medium,
                        end = DsSpacing.medium,
                        bottom = DsSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall),
                ) {
                    // Four bare words under a model name say nothing about what they set. The
                    // harness gives the tier list a pane with a title; here the title is one line.
                    Text(
                        stringResource(R.string.models_reasoning_effort),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val segments = listOf(DsSegment(DEFAULT_REASONING_EFFORT_KEY, stringResource(R.string.presets_default))) +
                        efforts.map { DsSegment(it.id, it.name) }
                    // DsSegmented uses weighted children, which collapse when horizontalScroll
                    // gives its Row an unbounded width. Bound the track inside the scroll area.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val trackWidth = maxOf(maxWidth, 84.dp * segments.size)
                        androidx.compose.foundation.layout.Box(Modifier.horizontalScroll(rememberScrollState())) {
                            DsSegmented(
                                segments = segments,
                                selectedKey = selectedEffort ?: DEFAULT_REASONING_EFFORT_KEY,
                                onSelect = onSelectEffort,
                                modifier = Modifier.width(trackWidth),
                                stretch = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Host's label as a quiet pill. The tone only chooses an emphasis pair from the theme
 * palette; an unrecognized tone from a newer Host stays readable at the least emphasis.
 */
@Composable
private fun ModelBadgePill(text: String, tone: String) {
    val (container, content) = when (tone) {
        ModelBadge.TONE_INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        ModelBadge.TONE_SUCCESS -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ModelBadge.TONE_WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = DsSpacing.xsmall, vertical = DsSpacing.tiny),
        )
    }
}
