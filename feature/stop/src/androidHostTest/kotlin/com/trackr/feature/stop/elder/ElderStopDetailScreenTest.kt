package com.trackr.feature.stop.elder

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.UiMode
import com.trackr.ui.components.elder.ELDER_BACK_TAG
import com.trackr.ui.components.elder.SHOW_TRANSIT_CODE_TAG
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.ui.screens.stopdetail.StopDetailEvent
import com.trackr.ui.screens.stopdetail.StopDetailUiState
import com.trackr.ui.screens.stopdetail.StopLineRowUi
import com.trackr.ui.screens.stopdetail.WatchTogetherUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版站点页：可点元素都 ≥ 64dp、文字在浅 / 深 × 字号 1.0 / 1.5 / 2.0 下不裁并截图；「出示乘车码」一直在；
 * 没有选线、一起等、收藏星标、下拉刷新；点卡片去线路页；提示点「知道了」才关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderStopDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<StopDetailEvent>()

    private fun arrival(value: String, unit: String?, detail: String, tone: ArrivalTone = ArrivalTone.Normal) =
        ArrivalDisplay(value = value, unit = unit, detail = detail, short = value, tone = tone, spoken = value)

    private val row325 = StopLineRowUi(
        key = "r325", lineId = "L325", lineName = "325", endStop = "科技园", hours = "06:30–22:30",
        arrival = arrival("约 4", "分钟", "还有 2 站"), isFavorite = true, selectable = true,
        targetOrder = 5, stopId = "st1", source = DataSourceType.CHELAILE
    )
    private val row19 = row325.copy(
        key = "r19", lineId = "L19", lineName = "19", endStop = "蛇口港", hours = "06:00–23:00", isFavorite = false,
        arrival = arrival("到站了", null, "快上车", ArrivalTone.Go)
    )
    private val rowM194 = row325.copy(
        key = "rM194", lineId = "LM194", lineName = "M194", endStop = "西丽", hours = "",
        arrival = arrival("下一班 07:35", null, "从 白石洲 发车", ArrivalTone.Mute)
    )

    private val base = StopDetailUiState(
        isLoading = false,
        stopName = "科技园站",
        stops = listOf(NearbyStop(stopId = "st1", name = "科技园站"), NearbyStop(stopId = "st2", name = "科技园站")),
        platformLabels = listOf("往 科技园", "往 蛇口港"),
        subtitle = "120米 · 3 条线",
        rows = listOf(row325, row19, rowM194),
        transferText = "可换乘：地铁 1 号线"
    )

    @Composable
    private fun ElderStop(state: StopDetailUiState, fontScale: Float = 1f, darkTheme: Boolean = false) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                ElderStopDetailScreen(state = state, onEvent = { events += it })
            }
        }
    }

    private fun setContent(state: StopDetailUiState, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeTestRule.setContent { ElderStop(state, fontScale, darkTheme) }
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
        setContent(base, fontScale = scale, darkTheme = darkTheme)
        ElderScreenshots.capture(composeTestRule, "stop", scale, darkTheme)
        listOf(ElderStopTags.platform(1), ElderStopTags.line("r325"), ElderStopTags.line("r19"), ElderStopTags.line("rM194")).forEach { tag ->
            composeTestRule.onNodeWithTag(ElderStopTags.LIST).performScrollToNode(hasTestTag(tag))
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
            composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed()
        }
    }

    // ---- 内容 ----

    @Test
    fun showsLinesWithBigArrival_noWatchTogetherNoStarsNoTabs() {
        setContent(base.copy(watch = WatchTogetherUi(lineCount = 2, lines = emptyList())))

        composeTestRule.onNodeWithText("科技园站").assertIsDisplayed()
        composeTestRule.onNodeWithText("120米 · 3 条线").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("约 4 分钟").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 2 站").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderStopTags.LIST).performScrollToNode(hasTestTag(ElderStopTags.line("rM194")))
        // 没车时给下一班发车时间
        composeTestRule.onNodeWithText("下一班 07:35").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 白石洲 发车").assertIsDisplayed()

        listOf("选几条一起等", "一起等 2 条", "我上车了", "再加一条", "收藏", "取消收藏", "公交", "路线", "我的").forEach {
            composeTestRule.onNodeWithText(it).assertDoesNotExist()
        }
        composeTestRule.onNodeWithText("出示乘车码").assertIsDisplayed()
    }

    @Test
    fun events_openLinePlatformBackTransitCode() {
        setContent(base)

        composeTestRule.onNodeWithTag(ElderStopTags.line("r325")).performClick()
        assertEquals(StopDetailEvent.OpenLine(row325), events.last())

        composeTestRule.onNodeWithText("在哪个方向上车？").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderStopTags.platform(1)).performClick()
        assertEquals(StopDetailEvent.SelectPlatform(1), events.last())

        composeTestRule.onNodeWithTag(ELDER_BACK_TAG).performClick()
        assertEquals(StopDetailEvent.Back, events.last())

        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).performClick()
        assertEquals(StopDetailEvent.OpenTransitCode, events.last())
    }

    @Test
    fun onePlatform_noDirectionButtons() {
        setContent(base.copy(stops = base.stops.take(1), platformLabels = base.platformLabels.take(1)))
        composeTestRule.onNodeWithText("在哪个方向上车？").assertDoesNotExist()
        composeTestRule.onNodeWithText("往 科技园").assertDoesNotExist()
    }

    @Test
    fun notSelectableLine_cannotBeOpened() {
        setContent(base.copy(rows = listOf(row325.copy(selectable = false, lineId = ""))))
        composeTestRule.onNodeWithTag(ElderStopTags.line("r325")).performClick()
        assertTrue(events.none { it is StopDetailEvent.OpenLine })
    }

    @Test
    fun loadFailed_sayItAndRetry() {
        setContent(StopDetailUiState(isLoading = false, stopName = "科技园站", errorMessage = "没连上网，站点没查出来"))
        composeTestRule.onNodeWithText("没连上网，站点没查出来").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").performClick()
        assertEquals(StopDetailEvent.Retry, events.last())
    }

    @Test
    fun refreshFailedWithOldData_retryButton() {
        setContent(base.copy(errorMessage = "刷新失败"))
        composeTestRule.onNodeWithText("刷新失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("重试").performClick()
        assertEquals(StopDetailEvent.Retry, events.last())
        composeTestRule.onNodeWithText("→ 科技园").assertExists()
    }

    @Test
    fun noLines_saysSo() {
        setContent(base.copy(rows = emptyList()))
        composeTestRule.onNodeWithText("这个站台暂时没有实时线路").assertIsDisplayed()
    }

    @Test
    fun message_staysUntilGotIt() {
        var shown = false
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = false) {
                ElderStopDetailScreen(
                    state = base,
                    onEvent = { events += it },
                    messageHost = {
                        UserMessageHost(
                            message = if (shown) null else HostMessage(key = 1L, text = "没能打开乘车码，请确认装了对应 App"),
                            onResult = { _, _ -> shown = true }
                        )
                    }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(10_000)
        composeTestRule.onNodeWithText("没能打开乘车码，请确认装了对应 App").assertIsDisplayed()
        composeTestRule.onNodeWithText("知道了").performClick()
        assertTrue(shown)
    }
}
