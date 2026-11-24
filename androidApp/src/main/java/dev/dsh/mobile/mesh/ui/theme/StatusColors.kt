package dev.dsh.mobile.mesh.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status colours that Material's own colour scheme has no role for.
 *
 * Material 3 defines primary/secondary/tertiary/error but not success, warning or "running". Mapping
 * those onto the nearest Material role changes their meaning — a connected host drawn in `primary`
 * is blue, while the palette's success green is the actual signal — so they stay a separate, named
 * set supplied by [DshTheme] from the same palette the rest of the scheme comes from.
 */
@Immutable
data class StatusColors(
    /** Success: connected, current default, a passed check. */
    val success: Color,
    /** Tinted container behind [success]. */
    val successContainer: Color,
    /** Warning accent: an icon or a dot that needs attention but is not a failure. */
    val warning: Color,
    /** Warning text: the darker amber that stays legible on light surfaces. */
    val warningText: Color,
    /** Tinted container behind a warning block. */
    val warningContainer: Color,
    /** Destructive/failed, matching the scheme's `error` where one is used. */
    val danger: Color,
    /** Tinted container behind a diff removal or a failed block. */
    val dangerContainer: Color,
    /** A turn that is executing right now. */
    val running: Color,
    /** Context meter: system tokens. */
    val meterSystem: Color,
    /** Context meter: tool schemas. */
    val meterTools: Color,
    /** Context meter: conversation messages. */
    val meterMessages: Color,
)

private val DefaultStatusColors = StatusColors(
    success = Ds.Green500,
    successContainer = Ds.Green100,
    warning = Ds.Amber500,
    warningText = Ds.Amber600,
    warningContainer = Ds.Amber100,
    danger = Ds.Red600,
    dangerContainer = Ds.Red50,
    running = Ds.Deepseek450,
    meterSystem = Ds.MeterSystem,
    meterTools = Ds.MeterTools,
    meterMessages = Ds.MeterMessages,
)

internal val LocalStatusColors = staticCompositionLocalOf { DefaultStatusColors }

/** The status palette of the current theme. */
val statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Builds the status palette for one scheme from the harness tokens. */
internal fun DsColors.statusColors(): StatusColors = StatusColors(
    success = success,
    successContainer = successTertiary,
    warning = warn,
    warningText = warnLabel,
    warningContainer = warnTertiary,
    danger = error,
    dangerContainer = errorTertiary,
    running = Ds.Deepseek450,
    meterSystem = Ds.MeterSystem,
    meterTools = Ds.MeterTools,
    meterMessages = Ds.MeterMessages,
)
