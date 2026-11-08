package com.trackr.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeColorSource { System, Preset, Custom }

@Serializable
enum class ThemeColorStyle { TonalSpot, Neutral, Vibrant, Expressive, Fidelity, Content, Monochrome, Rainbow, FruitSalad }

@Serializable
enum class ThemeSeed(val rgb: Int) {
    Blue(0x1F5FD6), Teal(0x00796B), Purple(0x7954A1), Rose(0xB34F78), Orange(0xAA5C12), Gray(0x66717E)
}

@Serializable
enum class SystemThemeSeed { Primary, Secondary, Tertiary }

/** Store choices, never a snapshot of wallpaper colors: those must keep following the system. */
@Serializable
data class ThemeColors(
    val source: ThemeColorSource = ThemeColorSource.Preset,
    val preset: ThemeSeed = ThemeSeed.Blue,
    val customRgb: Int = ThemeSeed.Blue.rgb,
    val style: ThemeColorStyle = ThemeColorStyle.TonalSpot,
    val systemSeed: SystemThemeSeed = SystemThemeSeed.Primary,
    val followSystem: Boolean = true,
) {
    fun normalized(): ThemeColors = if (customRgb in 0..0xFFFFFF) this else copy(customRgb = ThemeSeed.Blue.rgb)
}
