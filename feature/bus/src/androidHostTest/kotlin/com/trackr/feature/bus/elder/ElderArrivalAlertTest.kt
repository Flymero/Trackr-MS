package com.trackr.feature.bus.elder

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.UiMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版到站整屏提醒：内容（线路、「→ 终点」、「车到站了！」、「快去 X站 上车」）、两个大按钮的事件；
 * 浅 / 深 × 字号 1.0 / 1.5 / 2.0 下可点元素 ≥ 64dp、文字不裁，并截图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderArrivalAlertTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ArrivalAlertEvent>()
    private val alert = ArrivalAlertUi(key = "s1:bus1", sessionId = "s1", lineName = "325", endStop = "科技园", stopName = "科技园")

    private fun setContent(alert: ArrivalAlertUi = this.alert, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                    ElderArrivalAlert(alert = alert, onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun light_fontScale1() = assertFits(1f, false)

    @Test
    fun light_fontScale15() = assertFits(1.5f, false)

    @Test
    fun light_fontScale2() = assertFits(2f, false)

    @Test
    fun dark_fontScale1() = assertFits(1f, true)

    @Test
    fun dark_fontScale15() = assertFits(1.5f, true)

    @Test
    fun dark_fontScale2() = assertFits(2f, true)

    private fun assertFits(scale: Float, darkTheme: Boolean) {
        setContent(fontScale = scale, darkTheme = darkTheme)
        ElderScreenshots.capture(composeTestRule, "arrived", scale, darkTheme)
        listOf(ElderArrivalAlertTags.TRANSIT_CODE, ElderArrivalAlertTags.BOARDED).forEach { tag ->
            composeTestRule.onNodeWithTag(tag).performScrollTo()
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
        }
    }

    @Test
    fun content() {
        setContent()
        composeTestRule.onNodeWithText("325").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("车到站了！").assertIsDisplayed()
        // 站名补「站」
        composeTestRule.onNodeWithText("快去科技园站上车").assertIsDisplayed()
    }

    @Test
    fun stopNameAlreadyEndsWithZhan_notDoubled() {
        setContent(alert.copy(stopName = "科技园站"))
        composeTestRule.onNodeWithText("快去科技园站上车").assertIsDisplayed()
    }

    @Test
    fun buttons() {
        setContent()
        composeTestRule.onNodeWithText("出示乘车码").performClick()
        assertEquals(ArrivalAlertEvent.OpenTransitCode, events.last())
        composeTestRule.onNodeWithText("我上车了").performClick()
        assertEquals(ArrivalAlertEvent.Boarded, events.last())
    }
}
