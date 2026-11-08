package com.trackr.ui.screens.me

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.designsystem.theme.*
import com.trackr.core.model.*
import com.trackr.core.testing.FakeSettingsRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val settings = FakeSettingsRepository()

    private fun show(system: SystemThemeColors? = null) {
        val vm = ThemeSettingsViewModel(settings)
        rule.setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            TrackrTheme(themeMode = state.mode, themeColors = state.colors) {
                ThemeSettingsScreen(state, vm::onEvent, systemColors = system)
            }
        }
    }
    private fun scrollTo(matcher: SemanticsMatcher) = rule.onNodeWithTag("theme_settings").performScrollToNode(matcher)

    @Test
    fun allNineStylesAreAvailableWithoutExpanding() {
        show()
        scrollTo(hasTestTag("theme_seed_Teal"))
        rule.onNodeWithTag("theme_seed_Teal").performClick()
        assertEquals(ThemeSeed.Teal, settings.themeColors.value.preset)
        scrollTo(hasTestTag("theme_style_Expressive"))
        rule.onNodeWithTag("theme_style_Expressive").performClick()
        scrollTo(hasTestTag("theme_style_Monochrome"))
        rule.onNodeWithTag("theme_style_Monochrome").performClick()
        assertEquals(ThemeColorStyle.Monochrome, settings.themeColors.value.style)
        rule.onNodeWithTag("theme_more").assertDoesNotExist()
        ThemeColorStyle.entries.forEach { rule.onNodeWithTag("theme_style_${it.name}").assertExists() }
    }

    @Test
    fun systemFollowingHidesStylePickerAndSystemSeedReenablesIt() {
        val system = SystemThemeColors(generatedThemeColorScheme(Color.Green, ThemeColorStyle.Neutral, false), generatedThemeColorScheme(Color.Green, ThemeColorStyle.Neutral, true), Color.Red, Color.Green, Color.Blue)
        show(system)
        scrollTo(hasTestTag("theme_source_System"))
        rule.onNodeWithTag("theme_source_System").performClick()
        scrollTo(hasTestTag("theme_system_managed"))
        rule.onNodeWithTag("theme_style_TonalSpot").assertDoesNotExist()
        scrollTo(hasTestTag("theme_seed_Tertiary"))
        rule.onNodeWithTag("theme_seed_Tertiary").performClick()
        assertFalse(settings.themeColors.value.followSystem)
        assertEquals(SystemThemeSeed.Tertiary, settings.themeColors.value.systemSeed)
        scrollTo(hasTestTag("theme_style_TonalSpot"))
        rule.onNodeWithTag("theme_style_TonalSpot").assertIsDisplayed()
    }

    @Test
    fun invalidHexKeepsLastValidColorAndDisplaysError() {
        show()
        scrollTo(hasTestTag("theme_source_Custom"))
        rule.onNodeWithTag("theme_source_Custom").performClick()
        scrollTo(hasTestTag("theme_hex"))
        rule.onNodeWithTag("theme_hex").performTextReplacement("#CF304A")
        assertEquals(0xCF304A, settings.themeColors.value.customRgb)
        rule.onNodeWithTag("theme_hex").performTextReplacement("#XYZ")
        assertEquals(0xCF304A, settings.themeColors.value.customRgb)
        scrollTo(hasTestTag("theme_hex_error"))
        rule.onNodeWithTag("theme_hex_error").assertIsDisplayed()
    }
}
