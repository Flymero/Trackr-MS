package com.trackr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.trackr.core.model.SystemThemeSeed
import com.trackr.core.model.ThemeColorSource
import com.trackr.core.model.ThemeColorStyle
import com.trackr.core.model.ThemeColors

@Immutable
data class SystemThemeColors(
    val light: ColorScheme,
    val dark: ColorScheme,
    val primarySeed: Color,
    val secondarySeed: Color,
    val tertiarySeed: Color,
) {
    fun seed(choice: SystemThemeSeed): Color = when (choice) {
        SystemThemeSeed.Primary -> primarySeed
        SystemThemeSeed.Secondary -> secondarySeed
        SystemThemeSeed.Tertiary -> tertiarySeed
    }
}

/** Null on platforms without Android system palettes. Reads configuration so overlay changes refresh it. */
@Composable
expect fun rememberSystemThemeColors(): SystemThemeColors?

fun themeSeedColor(colors: ThemeColors, system: SystemThemeColors?): Color = when (colors.source) {
    ThemeColorSource.System -> system?.seed(colors.systemSeed) ?: Color(0xFF000000L or colors.preset.rgb.toLong())
    ThemeColorSource.Preset -> Color(0xFF000000L or colors.preset.rgb.toLong())
    ThemeColorSource.Custom -> Color(0xFF000000L or colors.normalized().customRgb.toLong())
}

/** The same MCU spec as the approved mockup; all nine styles use the actual palette algorithm. */
fun generatedThemeColorScheme(seed: Color, style: ThemeColorStyle, dark: Boolean): ColorScheme =
    dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        style = PaletteStyle.valueOf(style.name),
        specVersion = ColorSpec.SpecVersion.SPEC_2021,
    )

@Composable
fun rememberThemeColorScheme(
    colors: ThemeColors,
    dark: Boolean,
    system: SystemThemeColors? = rememberSystemThemeColors(),
): ColorScheme {
    val seed = themeSeedColor(colors, system)
    return remember(colors, dark, system, seed) {
        if (colors.source == ThemeColorSource.System && colors.followSystem && system != null) {
            if (dark) system.dark else system.light
        } else {
            generatedThemeColorScheme(seed, colors.style, dark)
        }
    }
}
