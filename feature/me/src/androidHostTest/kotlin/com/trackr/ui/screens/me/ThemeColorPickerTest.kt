package com.trackr.ui.screens.me

import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.trackr.core.designsystem.theme.TrackrTheme
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
class ThemeColorPickerTest {
    @get:Rule val rule = createComposeRule()
    private var applied: Int? = null
    private var dismissed = false

    private fun show() {
        rule.setContent {
            TrackrTheme {
                Surface(Modifier.fillMaxSize()) { ThemeColorPickerDialog(0x1F5FD6, onDismiss = { dismissed = true }, onColor = { applied = it }) }
            }
        }
    }

    @Test
    fun rgbAndHexInputsStaySynchronizedAndApplyExactRgb() {
        show()
        rule.onNodeWithTag("theme_picker_R").assertTextContains("31")
        rule.onNodeWithTag("theme_picker_R").performTextReplacement("255")
        rule.onNodeWithTag("theme_picker_hex").assertTextContains("#FF5FD6")
        rule.onNodeWithTag("theme_picker_hex").performTextReplacement("#00FF00")
        rule.onNodeWithTag("theme_picker_R").assertTextContains("0")
        rule.onNodeWithTag("theme_picker_G").assertTextContains("255")
        rule.onNodeWithTag("theme_picker_B").assertTextContains("0")
        rule.onNodeWithTag("theme_picker_confirm").performClick()
        assertEquals(0x00FF00, applied)
    }

    @Test
    fun tappingAndDraggingPaletteUpdateRgbAndHex() {
        show()
        rule.onNodeWithTag("theme_picker_hex").performTextReplacement("#00FF00")
        rule.onNodeWithTag("theme_picker_palette").performTouchInput { click(center) }
        rule.onNodeWithTag("theme_picker_R").assertTextContains("64")
        rule.onNodeWithTag("theme_picker_G").assertTextContains("128")
        rule.onNodeWithTag("theme_picker_B").assertTextContains("64")
        rule.onNodeWithTag("theme_picker_palette").performTouchInput {
            swipe(center, Offset(width - 1f, height - 1f))
        }
        rule.onNodeWithTag("theme_picker_R").assertTextContains("0")
        rule.onNodeWithTag("theme_picker_B").assertTextContains("0")
        assertNull(applied)
    }

    @Test
    fun invalidChannelsDisableApplyAndCancelLeavesAppliedColorUntouched() {
        show()
        rule.onNodeWithTag("theme_picker_R").performTextReplacement("999")
        rule.onNodeWithTag("theme_picker_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("theme_picker_R").performTextReplacement("128")
        rule.onNodeWithTag("theme_picker_confirm").assertIsEnabled()
        rule.onNodeWithTag("theme_picker_hex").performTextReplacement("#XYZ")
        rule.onNodeWithTag("theme_picker_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("theme_picker_cancel").performClick()
        assertTrue(dismissed)
        assertNull(applied)
    }

    @Test
    fun pickerDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            TrackrTheme { Surface(Modifier.fillMaxSize()) { ThemeColorPickerDialog(0x1F5FD6, onDismiss = {}, onColor = { applied = it }) } }
        }
        rule.onNodeWithTag("theme_picker_hex").performTextReplacement("#CF304A")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("theme_picker_hex").assertTextContains("#CF304A")
        rule.onNodeWithTag("theme_picker_R").assertTextContains("207")
        rule.onNodeWithTag("theme_picker_confirm").performClick()
        assertEquals(0xCF304A, applied)
    }
}
