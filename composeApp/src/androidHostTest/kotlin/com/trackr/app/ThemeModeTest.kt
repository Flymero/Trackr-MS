package com.trackr.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.UiMode
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.ui.screens.me.ThemeSettingsViewModel
import com.trackr.ui.screens.me.ThemeSettingsScreen
import com.trackr.core.designsystem.theme.generatedThemeColorScheme
import com.trackr.core.model.ThemeColorStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 真实设置事件 → 仓库 → 根主题，以及主题切换时系统栏图标的对比度。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeModeTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val settings = FakeSettingsRepository()
    private var background = Color.Unspecified
    private var isDark = false

    private fun show() {
        val shellViewModel = AppShellViewModel(settings)
        val meViewModel = ThemeSettingsViewModel(settings)
        rule.setContent {
            val shell by shellViewModel.shell.collectAsStateWithLifecycle()
            val me by meViewModel.uiState.collectAsStateWithLifecycle()
            TrackrTheme(uiMode = shell.uiMode, themeMode = shell.themeMode, themeColors = shell.themeColors) {
                val currentBackground = MaterialTheme.colorScheme.background
                val currentDark = TrackrTheme.isDark
                SideEffect {
                    background = currentBackground
                    isDark = currentDark
                }
                PlatformHost(enabled = false) {
                    ThemeSettingsScreen(state = me, onEvent = meViewModel::onEvent)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun choose(label: String) {
        val mode = when (label) { "深色" -> "Dark"; "浅色" -> "Light"; else -> "System" }
        rule.onNodeWithTag("theme_settings").performScrollToNode(hasTestTag("theme_mode_$mode"))
        rule.onNodeWithTag("theme_mode_$mode").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun standardBackground(dark: Boolean): Color =
        generatedThemeColorScheme(Color(0xFF1F5FD6), ThemeColorStyle.TonalSpot, dark).background

    private fun assertTheme(dark: Boolean, expectedBackground: Color) {
        assertEquals(dark, isDark)
        assertEquals(expectedBackground, background)
        val window = rule.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(!dark, controller.isAppearanceLightStatusBars)
        assertEquals(!dark, controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun choicesUpdateWholeThemeAndSystemBars_withoutLeavingSettings() {
        show()
        assertTheme(dark = false, standardBackground(false))
        choose("深色")
        assertEquals(ThemeMode.Dark, settings.themeMode.value)
        assertTheme(dark = true, standardBackground(true))

        rule.onNodeWithTag("theme_mode_Dark").assertIsSelected()

        choose("浅色")
        assertTheme(dark = false, standardBackground(false))
        choose("跟随系统")
        assertEquals(ThemeMode.System, settings.themeMode.value)
        assertTheme(dark = false, standardBackground(false))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-night")
    fun systemDark_canBeOverriddenAndRestored() {
        show()
        assertTheme(dark = true, standardBackground(true))
        choose("浅色")
        assertTheme(dark = false, standardBackground(false))
        choose("跟随系统")
        assertTheme(dark = true, standardBackground(true))
    }

    @Test
    fun savedDarkChoice_appliesImmediatelyInElderMode() {
        settings.setUiMode(UiMode.Elder)
        settings.setThemeMode(ThemeMode.Dark)
        show()
        assertTheme(dark = true, Color.Black)
        choose("浅色")
        assertTheme(dark = false, Color(0xFFF2F3F5))
    }
}
