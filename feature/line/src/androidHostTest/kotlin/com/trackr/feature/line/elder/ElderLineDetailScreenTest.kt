package com.trackr.feature.line.elder

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.UiMode
import com.trackr.ui.components.elder.ELDER_BACK_TAG
import com.trackr.ui.components.elder.SHOW_TRANSIT_CODE_TAG
import com.trackr.ui.screens.linedetail.LineDetailEvent
import com.trackr.ui.screens.linedetail.LineDetailUiState
import com.trackr.ui.screens.linedetail.LineLive
import com.trackr.ui.screens.linedetail.LineStatic
import com.trackr.ui.screens.linedetail.LineUserMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版线路页（§8.7）：可点元素都 ≥ 64dp、没有底部页签和地图入口、没有走向条 / 时刻表 / 定时提醒、
 * 「出示乘车码」一直在；浅 / 深 × 字号 1.0 / 1.5 / 2.0 文字不被裁并生成截图；换站；状态恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderLineDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<LineDetailEvent>()

    private val line = BusLineDirection(
        lineId = "l325",
        name = "325",
        startStop = "白石洲",
        endStop = "科技园",
        firstTime = "6:30",
        lastTime = "22:30"
    )
    private val reverse = line.copy(lineId = "l325r", startStop = "科技园", endStop = "白石洲")
    private val stops = listOf("白石洲", "大冲", "高新园", "深大", "科技园站", "科技中一路", "海岸城")
        .mapIndexed { i, name -> BusStop(stopId = "s$i", name = name, order = i + 1) }

    private fun stateOf(
        buses: List<LiveBus> = listOf(
            LiveBus(busId = "a", plateOrFleetNo = "", order = 3, etaSeconds = 240),
            LiveBus(busId = "b", plateOrFleetNo = "", order = 0 + 1, etaSeconds = 720)
        ),
        nextDeparture: String? = null,
        tracking: Boolean = false,
        favorite: Boolean = false,
        messages: List<LineUserMessage> = emptyList(),
        showTransitCode: Boolean = true
    ): LineDetailUiState = LineDetailUiState(
        lineNameHint = "325",
        isLoading = false,
        static = LineStatic(line = line, reverseDirection = reverse, stops = stops),
        live = LineLive(buses = buses, nextDepartureFromStart = nextDeparture, updatedAtMillis = 1_000L),
        selectedStop = stops[4],
        isFavorite = favorite,
        trackingSession = if (tracking) {
            TrackingSessionState(isActive = true, lineId = "l325", lineName = "325", targetOrder = 5)
        } else {
            TrackingSessionState()
        },
        userMessages = messages,
        showTransitCodeButton = showTransitCode
    )

    @Composable
    private fun ElderLine(state: LineDetailUiState, fontScale: Float = 1f, darkTheme: Boolean = false) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                ElderLineDetailScreen(state = state, onEvent = { events += it })
            }
        }
    }

    private fun setContent(state: LineDetailUiState, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeTestRule.setContent { ElderLine(state, fontScale, darkTheme) }
    }

    // ---- 尺寸 + 截图 ----

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
        setContent(stateOf(), fontScale = scale, darkTheme = darkTheme)
        ElderScreenshots.capture(composeTestRule, "line", scale, darkTheme)
        listOf(ElderLineTags.PICK_STOP, ElderLineTags.ALERT, ElderLineTags.FAVORITE).forEach { tag ->
            composeTestRule.onNodeWithTag(tag).performScrollTo()
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
            composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed()
        }
    }

    // ---- 内容 ----

    @Test
    fun answersHowLong_noRailNoTimetableNoReminderNoMapNoTabs() {
        setContent(stateOf())

        composeTestRule.onNodeWithText("白石洲 → 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("运营 06:30–22:30").assertIsDisplayed()
        composeTestRule.onNodeWithText("在 科技园站 上车").assertIsDisplayed()
        composeTestRule.onNodeWithText("约 4 分钟").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 2 站 · 下一辆 约 12 分钟 · 4 站").assertIsDisplayed()

        listOf("时刻表 ›", "定时提醒", "地图", "规划", "公交", "路线", "我的", "全线 2 辆车在途 · 查看", "大冲", "海岸城").forEach {
            composeTestRule.onNodeWithText(it).assertDoesNotExist()
        }
        composeTestRule.onNodeWithText("出示乘车码").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_showsNextDepartureFromStart() {
        setContent(stateOf(buses = emptyList(), nextDeparture = "07:20"))
        composeTestRule.onNodeWithText("下一班 07:20").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 白石洲 发车").assertIsDisplayed()
    }

    // ---- 事件 ----

    @Test
    fun buttons_sendSharedEvents() {
        setContent(stateOf())

        composeTestRule.onNodeWithTag(ElderLineTags.READ_ALOUD).performScrollTo().performClick()
        assertEquals(LineDetailEvent.ReadAloud, events.last())
        composeTestRule.onNodeWithTag(ElderLineTags.ALERT).performScrollTo().performClick()
        assertEquals(LineDetailEvent.ToggleTracking, events.last())
        composeTestRule.onNodeWithTag(ElderLineTags.SWITCH_DIRECTION).performScrollTo().performClick()
        assertEquals(LineDetailEvent.SwitchDirection, events.last())
        composeTestRule.onNodeWithTag(ElderLineTags.FAVORITE).performScrollTo().performClick()
        assertEquals(LineDetailEvent.ToggleFavorite, events.last())
        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).performClick()
        assertEquals(LineDetailEvent.OpenTransitCode, events.last())
        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).performClick()
        assertEquals(LineDetailEvent.Back, events.last())
    }

    @Test
    fun transitCodeButton_hiddenWhenSettingOff() {
        // 「我的」里关掉「显示乘车码按钮」：底部「出示乘车码」也不留
        setContent(stateOf(showTransitCode = false))

        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertDoesNotExist()
    }

    @Test
    fun readAloud_shownWhileTracking() {
        setContent(stateOf(tracking = true))
        composeTestRule.onNodeWithText("读给我听").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(LineDetailEvent.ReadAloud, events.last())
    }

    @Test
    fun refreshError_showsRetry() {
        setContent(stateOf().copy(errorMessage = "网络不太好，没刷出来"))
        composeTestRule.onNodeWithText("网络不太好，没刷出来").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").performClick()
        assertEquals(LineDetailEvent.Refresh(fromPull = false), events.last())
    }

    @Test
    fun alertDisabled_untilLiveDataArrives() {
        setContent(stateOf().copy(live = null))
        composeTestRule.onNodeWithText("正在查车…").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderLineTags.ALERT).assertIsNotEnabled()
    }

    @Test
    fun tracking_stopAsksFirst() {
        setContent(stateOf(tracking = true))
        composeTestRule.onNodeWithText("车快到时会叫你").assertIsDisplayed()

        composeTestRule.onNodeWithTag(ElderLineTags.ALERT).performScrollTo().performClick()
        assertFalse(events.contains(LineDetailEvent.ToggleTracking))
        composeTestRule.onNodeWithText("继续提醒").performClick()
        assertFalse(events.contains(LineDetailEvent.ToggleTracking))

        composeTestRule.onNodeWithTag(ElderLineTags.ALERT).performClick()
        val buttons = composeTestRule.onAllNodes(hasText("不用叫了") and hasClickAction())
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        assertEquals(LineDetailEvent.ToggleTracking, events.last())
    }

    @Test
    fun pickStop_choosesAndComesBack() {
        setContent(stateOf())

        composeTestRule.onNodeWithTag(ElderLineTags.PICK_STOP).performClick()
        composeTestRule.onNodeWithText("在哪一站上车？").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderPickStopTags.stop(2)).performClick()

        val select = events.last() as LineDetailEvent.SelectStop
        assertEquals("大冲", select.stop.name)
        composeTestRule.onNodeWithText("在哪一站上车？").assertDoesNotExist()
        composeTestRule.onNodeWithTag(ElderLineTags.ALERT).assertExists()
    }

    @Test
    fun pickStop_backKeepsStop() {
        setContent(stateOf())
        composeTestRule.onNodeWithTag(ElderLineTags.PICK_STOP).performClick()
        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).performClick()

        composeTestRule.onNodeWithText("在 科技园站 上车").assertIsDisplayed()
        assertTrue(events.none { it is LineDetailEvent.SelectStop || it == LineDetailEvent.Back })
    }

    @Test
    fun message_staysUntilGotIt() {
        setContent(stateOf(messages = listOf(LineUserMessage(id = 3, text = "已收藏", action = LineUserMessage.Action.CHANGE_GROUP))))
        composeTestRule.mainClock.advanceTimeBy(10_000)
        composeTestRule.onNodeWithText("已收藏").assertIsDisplayed()
        composeTestRule.onNodeWithText("换分组").assertDoesNotExist()
        composeTestRule.onNodeWithText("知道了").performClick()
        assertEquals(LineDetailEvent.MessageShown(3), events.last())
    }

    @Test
    fun pickingStop_survivesRecreation() {
        val tester = StateRestorationTester(composeTestRule)
        tester.setContent { ElderLine(stateOf()) }

        composeTestRule.onNodeWithTag(ElderLineTags.PICK_STOP).performClick()
        tester.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("在哪一站上车？").assertIsDisplayed()
    }
}
