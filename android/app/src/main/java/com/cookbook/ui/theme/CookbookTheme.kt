package com.cookbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.PulseAccent
import design.pulse.ui.theme.PulseChannel
import design.pulse.ui.theme.PulseDataTypography
import design.pulse.ui.theme.PulseTheme
import design.pulse.ui.theme.Spacing
import design.pulse.ui.theme.LocalDataTypography
import design.pulse.ui.theme.LocalSpacing
import design.pulse.ui.theme.darkAmberChannel
import design.pulse.ui.theme.darkBlueChannel
import design.pulse.ui.theme.darkGreenChannel
import design.pulse.ui.theme.darkPulseStructure
import design.pulse.ui.theme.darkVioletChannel
import design.pulse.ui.theme.lightAmberChannel
import design.pulse.ui.theme.lightBlueChannel
import design.pulse.ui.theme.lightGreenChannel
import design.pulse.ui.theme.lightPulseStructure
import design.pulse.ui.theme.lightVioletChannel

/**
 * Cookbook's semantic layer over PULSE — the kitchen's channel map (CLAUDE.md §3):
 *  - heat:  orange/amber — the hero channel; cooking, primary actions, recipe identity
 *  - fresh: green        — done states: checked-off list items, completed steps
 *  - info:  blue         — counts, provenance accents, supporting data
 *  - plum:  violet       — secondary data voice (timers, servings)
 * Structure (hairlines/panels/glow) and the gradient voices ride along so screens have one stop.
 */
@Immutable
data class CookbookColors(
    val heat: PulseChannel,
    val fresh: PulseChannel,
    val info: PulseChannel,
    val plum: PulseChannel,
    val hairline: Color,
    val hairlineStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,
    /** Orange → amber, Cookbook's lead voice (identical to PULSE's energy gradient). */
    val heroGradient: Brush,
)

private fun cookbookColors(dark: Boolean): CookbookColors {
    val structure = if (dark) darkPulseStructure(PulseAccent.Amber) else lightPulseStructure(PulseAccent.Amber)
    return CookbookColors(
        heat = if (dark) darkAmberChannel() else lightAmberChannel(),
        fresh = if (dark) darkGreenChannel() else lightGreenChannel(),
        info = if (dark) darkBlueChannel() else lightBlueChannel(),
        plum = if (dark) darkVioletChannel() else lightVioletChannel(),
        hairline = structure.hairline,
        hairlineStrong = structure.hairlineStrong,
        panel = structure.panel,
        panelHigh = structure.panelHigh,
        glow = structure.glow,
        heroGradient = structure.heroGradient,
    )
}

val LocalCookbookColors = staticCompositionLocalOf { cookbookColors(dark = true) }

@Composable
fun CookbookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    PulseTheme(darkTheme = darkTheme, accent = PulseAccent.Amber) {
        CompositionLocalProvider(
            LocalCookbookColors provides cookbookColors(darkTheme),
        ) {
            content()
        }
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object CookbookTheme {
    val colors: CookbookColors
        @Composable @ReadOnlyComposable get() = LocalCookbookColors.current
    val dataType: PulseDataTypography
        @Composable @ReadOnlyComposable get() = LocalDataTypography.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
