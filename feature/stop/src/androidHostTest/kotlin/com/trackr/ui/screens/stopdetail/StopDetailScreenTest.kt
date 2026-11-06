package com.trackr.ui.screens.stopdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.NearbyStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** v3 站点详情页（第 21 步）：无状态页面，喂手写的状态、记下发出的事件 */
@RunWith(RobolectricTestRunner::class)
class StopDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<StopDetailEvent>()

    private fun arrival(value: String, unit: String?, detail: String, tone: ArrivalTone = ArrivalTone.Normal) =
        ArrivalDisplay(value = value, unit = unit, detail = detail, short = value, tone = tone, spoken = value)

    private val row325 = StopLineRowUi(
        key = "r325", lineId = "L325", lineName = "325", endStop = "科技园", hours = "06:30–22:30",
        arrival = arrival("4", "分钟", "还有 2 站"), isFavorite = true, selectable = true,
        targetOrder = 5, stopId = "st1", source = DataSourceType.CHELAILE
    )
    private val row19 = row325.copy(
        key = "r19", lineId = "L19", lineName = "19", endStop = "蛇口港", hours = "06:00–23:00", isFavorite = false,
        arrival = arrival("2", "分钟", "还有 1 站", ArrivalTone.Go)
    )

    /** 掌上公交的站点行：线路名和状态文字都很长，而且没有站序（targetOrder = 0） */
    private val rowLong = row325.copy(
        key = "r351",
        lineId = "mg:0:莆田市:351路（临时改道）:2",
        lineName = "351路（临时改道）",
        endStop = "莆田汽车站",
        hours = "06:10–19:10",
        arrival = arrival("8站 [开往 秀屿区政府]", null, ""),
        isFavorite = false,
        selectable = false,
        targetOrder = 0,
        stopId = "mg:莆田市:东津",
        source = DataSourceType.MYGOLBS
    )

    /** 十几字的线路名（截图里「盘龙大观园河西旅游专线」这种） */
    private val rowVeryLong = row325.copy(
        key = "rTour",
        lineId = "LTOUR",
        lineName = "盘龙大观园河西旅游专线",
        endStop = "河西公交总站",
        hours = "09:40–18:00",
        arrival = arrival("今日不运营", null, "", ArrivalTone.Mute),
        isFavorite = false,
        selectable = false,
        targetOrder = 0,
        stopId = "st_tour"
    )

    private val base = StopDetailUiState(
        isLoading = false,
        stopName = "科技园站",
        stops = listOf(NearbyStop(stopId = "st1", name = "科技园站"), NearbyStop(stopId = "st2", name = "科技园站")),
        platformLabels = listOf("往 科技园", "往 蛇口港"),
        subtitle = "120米 · 2 条线",
        rows = listOf(row325, row19),
        transferText = "可换乘：地铁 1 号线"
    )

    private fun setContent(state: StopDetailUiState) {
        composeTestRule.setContent {
            TrackrTheme {
                StopDetailScreen(state = state, onEvent = { events += it })
            }
        }
    }

    @Test
    fun normalMode_showsPlatformsRowsAndNoViewAllLink() {
        setContent(base)
        composeTestRule.onNodeWithText("往 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("往 蛇口港").assertIsDisplayed()
        composeTestRule.onNodeWithText("120米 · 2 条线").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("06:30–22:30", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("可换乘：地铁 1 号线").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTextCount("查看全线") == 0)
    }

    @Test
    fun normalMode_rowOpensLine_platformSelects_buttonsSendEvents() {
        setContent(base)
        composeTestRule.onNodeWithText("→ 蛇口港").performClick()
        composeTestRule.onNodeWithText("往 蛇口港").performClick()
        composeTestRule.onNodeWithText("选几条一起等").performClick()
        composeTestRule.onNodeWithContentDescription("乘车码").performClick()
        composeTestRule.onNodeWithContentDescription("取消收藏").performClick()
        assertEquals(
            listOf(
                StopDetailEvent.OpenLine(row19),
                StopDetailEvent.SelectPlatform(1),
                StopDetailEvent.StartSelecting,
                StopDetailEvent.OpenTransitCode,
                StopDetailEvent.ToggleFavorite("r325")
            ),
            events
        )
    }

    @Test
    fun transitCodeButton_hiddenWhenSettingOff() {
        // 「我的」里关掉「显示乘车码按钮」：站点页底栏这个圆钮也不留
        setContent(base.copy(showTransitCodeButton = false))

        composeTestRule.onNodeWithContentDescription("乘车码").assertDoesNotExist()
        composeTestRule.onNodeWithText("选几条一起等").assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun longLineName_withoutStationOrder_stillShowsHoursAndOpensLine() {
        // 长线路名 + 长状态文字不能把行挤成竖排；没有站序也要能点开线路页（候车站由线路页按站名找）
        setContent(base.copy(rows = listOf(rowLong)))
        val hours = composeTestRule.onNodeWithText("06:10–19:10", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val hoursHeight = with(composeTestRule.density) { hours.size.height.toDp() }
        assertTrue("运营时间被挤成竖排：$hoursHeight", hoursHeight < 40.dp)
        composeTestRule.onNodeWithText("8站 [开往 秀屿区政府]").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 莆田汽车站").performClick()
        assertEquals(listOf(StopDetailEvent.OpenLine(rowLong)), events)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun veryLongLineName_keepsEndStopAndHoursOnTheirLines() {
        // 名字和线路一行、运营时间和到站一行：长名字只在自己格子里省略，不能把终点和时间挤没
        setContent(base.copy(rows = listOf(rowVeryLong)))
        val end = composeTestRule.onNodeWithText("→ 河西公交总站").assertIsDisplayed().fetchSemanticsNode()
        val endWidth = with(composeTestRule.density) { end.boundsInRoot.width.toDp() }
        assertTrue("终点被线路名挤没了：$endWidth", endWidth > 60.dp)
        val hours = composeTestRule.onNodeWithText("09:40–18:00", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val hoursHeight = with(composeTestRule.density) { hours.size.height.toDp() }
        assertTrue("运营时间被挤成竖排：$hoursHeight", hoursHeight < 40.dp)
        composeTestRule.onNodeWithText("今日不运营").assertIsDisplayed()
    }

    @Test
    fun longPressRow_startsSelectingWithThatLine() {
        setContent(base)
        composeTestRule.onNodeWithText("→ 蛇口港").performTouchInput { longClick() }
        assertEquals(listOf(StopDetailEvent.StartSelectingWith("r19")), events)
    }

    @Test
    fun selectingMode_countsAndActions() {
        setContent(base.copy(selecting = true, selectedLineKeys = setOf("r325", "r19")))
        composeTestRule.onNodeWithText("已选 2 条").assertIsDisplayed()
        composeTestRule.onNodeWithText("科技园站 · 往 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithText("一起等这 2 条").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("收藏").performClick()
        composeTestRule.onNodeWithText("→ 科技园").performClick()
        assertEquals(
            listOf(StopDetailEvent.StartWatching, StopDetailEvent.SaveSelectionAsRoute, StopDetailEvent.ToggleLine("r325")),
            events
        )
    }

    @Test
    fun selectingMode_nothingChecked_buttonsDisabled() {
        setContent(base.copy(selecting = true))
        composeTestRule.onNodeWithText("一起等这 0 条").assertIsNotEnabled()
        composeTestRule.onNodeWithText("收藏").assertIsNotEnabled()
    }

    @Test
    fun watchMode_showsFastestFirstAndBoardButton() {
        val watch = WatchTogetherUi(
            lineCount = 2,
            lines = listOf(
                WatchLineUi("L325", "325", "科技园", arrival("1", "分钟", "还有 1 站", ArrivalTone.Go), "粤B·58921", "还有 1 站 · 粤B·58921"),
                WatchLineUi("LM194", "M194", "西丽", arrival("6", "分钟", "还有 3 站"), "", "还有 3 站")
            )
        )
        setContent(base.copy(watch = watch))
        composeTestRule.onNodeWithText("一起等 2 条").assertIsDisplayed()
        composeTestRule.onNodeWithText("325 先到").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 1 站 · 粤B·58921").assertIsDisplayed()
        // 行可能被底栏盖住（列表留了底栏高度的 contentPadding，测试里不滑），直接走点击语义
        composeTestRule.onNodeWithText("再加一条").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("我上车了").performClick()
        composeTestRule.onNodeWithText("结束").performClick()
        assertEquals(
            listOf(StopDetailEvent.AddLine, StopDetailEvent.ConfirmBoarded, StopDetailEvent.StopWatching),
            events
        )
    }

    @Test
    fun watchMode_stillShowsLineList_andRowsOpenTheirLine() {
        val watch = WatchTogetherUi(
            lineCount = 2,
            lines = listOf(
                WatchLineUi("L325", "325", "科技园", arrival("1", "分钟", "还有 1 站", ArrivalTone.Go), "粤B·58921", "还有 1 站 · 粤B·58921")
            )
        )
        setContent(base.copy(watch = watch))

        // 一起等进行中页面还是完整的线路列表：线路行照常点得进线路页
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        // 行可能被底栏盖住，直接走点击语义
        composeTestRule.onNodeWithText("→ 科技园").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(StopDetailEvent.OpenLine(row325), events.last())
    }

    @Test
    fun watchMode_onBoard_hidesBoardButton() {
        val watch = WatchTogetherUi(lineCount = 2, lines = emptyList(), isOnBoard = true, boardedTitle = "已上 325", boardedDetail = "还有 5 站到终点")
        setContent(base.copy(watch = watch))
        composeTestRule.onNodeWithText("已上 325").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 5 站到终点").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTextCount("我上车了") == 0)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
