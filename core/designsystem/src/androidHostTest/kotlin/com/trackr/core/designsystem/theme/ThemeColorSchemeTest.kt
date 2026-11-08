package com.trackr.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.trackr.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeColorSchemeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun everyStyleGeneratesLegibleLightAndDarkColorPairs() {
        for (seed in ThemeSeed.entries) for (style in ThemeColorStyle.entries) for (dark in listOf(false, true)) {
            val scheme = generatedThemeColorScheme(Color(0xFF000000L or seed.rgb.toLong()), style, dark)
            for ((foreground, background) in listOf(scheme.onPrimary to scheme.primary, scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onSurface to scheme.surface, scheme.onSecondaryContainer to scheme.secondaryContainer)) {
                val contrast = (maxOf(foreground.luminance(), background.luminance()) + 0.05) / (minOf(foreground.luminance(), background.luminance()) + 0.05)
                assertTrue("$seed $style dark=$dark contrast=$contrast", contrast >= 4.45)
            }
        }
    }

    @Test
    fun stylesAreDistinctAndMonochromeIgnoresSeed() {
        val blue = Color(0xFF1F5FD6)
        assertNotEquals(generatedThemeColorScheme(blue, ThemeColorStyle.TonalSpot, false).primary, generatedThemeColorScheme(blue, ThemeColorStyle.Vibrant, false).primary)
        assertEquals(generatedThemeColorScheme(blue, ThemeColorStyle.Monochrome, true).primary, generatedThemeColorScheme(Color.Red, ThemeColorStyle.Monochrome, true).primary)
    }

    @Test
    fun systemSchemeIsUsedIntactAndSelectingSeedGeneratesChosenStyle() {
        val light = generatedThemeColorScheme(Color.Green, ThemeColorStyle.Neutral, false)
        val dark = generatedThemeColorScheme(Color.Green, ThemeColorStyle.Neutral, true)
        val system = SystemThemeColors(light, dark, Color.Red, Color.Green, Color.Blue)
        var direct = Color.Unspecified
        var fromSeed = Color.Unspecified
        var arrival = Color.Unspecified
        rule.setContent {
            direct = rememberThemeColorScheme(ThemeColors(source = ThemeColorSource.System), false, system).primary
            fromSeed = rememberThemeColorScheme(ThemeColors(source = ThemeColorSource.System, followSystem = false, systemSeed = SystemThemeSeed.Tertiary, style = ThemeColorStyle.Vibrant), false, system).primary
            TrackrTheme(themeColors = ThemeColors(preset = ThemeSeed.Orange), darkTheme = false) { arrival = TrackrTheme.colors.go }
        }
        rule.waitForIdle()
        assertEquals(light.primary, direct)
        assertEquals(generatedThemeColorScheme(Color.Blue, ThemeColorStyle.Vibrant, false).primary, fromSeed)
        assertEquals(LightTrackrColors.go, arrival)
    }

    @Test
    @Config(sdk = [30])
    fun unsupportedSystemColorsFallBackToSavedPreset() {
        var supported: SystemThemeColors? = null
        var actual = Color.Unspecified
        rule.setContent {
            supported = rememberSystemThemeColors()
            actual = rememberThemeColorScheme(ThemeColors(source = ThemeColorSource.System, preset = ThemeSeed.Teal), false).primary
        }
        rule.waitForIdle()
        assertNull(supported)
        assertEquals(generatedThemeColorScheme(Color(0xFF00796B), ThemeColorStyle.TonalSpot, false).primary, actual)
    }

    @Test
    fun elderModeUsesExistingContrastColorsRegardlessOfPreference() {
        var primary = Color.Unspecified
        var background = Color.Unspecified
        rule.setContent {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = true, themeColors = ThemeColors(preset = ThemeSeed.Rose)) {
                primary = MaterialTheme.colorScheme.primary
                background = MaterialTheme.colorScheme.background
            }
        }
        rule.waitForIdle()
        assertEquals(ElderDarkColorScheme.primary, primary)
        assertEquals(Color.Black, background)
    }
}
