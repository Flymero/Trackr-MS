package com.trackr.ui.screens.me

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.ui.platform.LiveUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「我的」→「实时活动通知」：用户自己点「去开启」时才先说明再跳系统设置，
 * 开始追踪时不再弹（issue #29，MIUI 上没有这个开关却每次追踪都弹）。
 *
 * 文案统一叫「实时活动通知」，不按小米 / OPPO / 原生各写一套。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeLiveUpdateGuideTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val rowTitle = "实时活动通知"

    private fun render(state: MeUiState, events: MutableList<MeEvent>) {
        composeTestRule.setContent {
            TrackrTheme {
                MeScreen(state = state, onEvent = { events += it })
            }
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(rowTitle))
    }

    @Test
    fun notAllowed_tappingRowExplainsFirst_thenJumpsToSettings() {
        val events = mutableListOf<MeEvent>()
        render(MeUiState(liveUpdate = LiveUpdateStatus(supported = true, allowed = false)), events)

        composeTestRule.onNodeWithText(rowTitle).performClick()
        composeTestRule.onNodeWithText("开启实时活动通知").assertIsDisplayed()
        assertTrue("只是弹说明，还没跳设置", events.isEmpty())

        composeTestRule.onNodeWithText("前往开启").performClick()
        assertEquals(listOf(MeEvent.OpenLiveUpdateSettings), events)
    }

    @Test
    fun notAllowed_choosingLater_keepsTrackingSettingUntouched() {
        val events = mutableListOf<MeEvent>()
        render(MeUiState(liveUpdate = LiveUpdateStatus(supported = true, allowed = false)), events)

        composeTestRule.onNodeWithText(rowTitle).performClick()
        composeTestRule.onNodeWithText("暂不开启").performClick()
        assertTrue("选了暂不开启就只是关掉弹窗，不跳设置", events.isEmpty())
    }

    @Test
    fun alreadyAllowed_goesStraightToSettings() {
        val events = mutableListOf<MeEvent>()
        render(MeUiState(liveUpdate = LiveUpdateStatus(supported = true, allowed = true)), events)

        composeTestRule.onNodeWithText(rowTitle).performClick()
        assertEquals(listOf(MeEvent.OpenLiveUpdateSettings), events)
    }

    @Test
    fun unsupportedRom_goesStraightToNotificationSettings() {
        // MIUI：系统没有这项功能，那一行写「这台手机不支持，只在通知栏显示」，点了直接去通知设置
        val events = mutableListOf<MeEvent>()
        render(MeUiState(liveUpdate = LiveUpdateStatus(supported = false, allowed = false)), events)

        composeTestRule.onNodeWithText(rowTitle).performClick()
        assertEquals(listOf(MeEvent.OpenLiveUpdateSettings), events)
    }

    @Test
    fun supportedRow_showsUnifiedName() {
        val events = mutableListOf<MeEvent>()
        render(MeUiState(liveUpdate = LiveUpdateStatus(supported = true, allowed = false)), events)

        // 不管底下是澎湃OS 3 的超级岛还是 OPPO 流体云，这一行统一叫「实时活动通知」
        composeTestRule.onNodeWithText("实时活动通知").assertIsDisplayed()
        composeTestRule.onNodeWithText("追踪时在状态栏常驻显示进度").assertIsDisplayed()
    }
}
