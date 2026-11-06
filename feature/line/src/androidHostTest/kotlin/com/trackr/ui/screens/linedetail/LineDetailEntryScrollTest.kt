package com.trackr.ui.screens.linedetail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.RailMode
import androidx.compose.ui.text.TextLayoutResult
import com.trackr.ui.components.HorizontalRailTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 从某个站点点进线路详情：整页列表（含走向条）直接跳到那一站，不是从第 1 站开始。
 * 没有带站序的入口（搜索、收藏等）保持从顶部开始。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineDetailEntryScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stops = (1..30).map { order ->
        BusStop(stopId = "s$order", name = "S" + order.toString().padStart(2, '0'), order = order)
    }

    private val line = BusLineDirection(
        lineId = "line_1",
        name = "1路",
        startStop = "起点站",
        endStop = "终点站",
        firstTime = "06:00",
        lastTime = "22:00"
    )

    private fun stateOf(entryStopOrder: Int?): LineDetailUiState = LineDetailUiState(
        lineNameHint = line.name,
        isLoading = false,
        static = LineStatic(line = line, reverseDirection = null, stops = stops),
        selectedStop = stops.firstOrNull { it.order == entryStopOrder } ?: stops.first(),
        entryStopOrder = entryStopOrder
    )

    private fun show(state: LineDetailUiState) {
        composeTestRule.setContent {
            TrackrTheme {
                LineDetailScreen(state = state, onEvent = {})
            }
        }
    }

    @Test
    fun openedFromStation_jumpsHorizontalRailToThatStation() {
        show(stateOf(entryStopOrder = 20))

        // 默认横排：横条滚到第 20 站，第 1 站被滚出视野
        composeTestRule.onNodeWithContentDescription("S20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("S01").assertIsNotDisplayed()
    }

    @Test
    fun openedFromStation_keepsCardVisibleWhileRailJumps() {
        show(stateOf(entryStopOrder = 20))

        // 走向条跳到第 20 站，主卡仍在顶上显示这一站的候车信息
        composeTestRule.onNodeWithText("S20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("S20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("S01").assertIsNotDisplayed()
    }

    @Test
    fun openedWithoutStation_startsFromTop() {
        show(stateOf(entryStopOrder = null))

        composeTestRule.onNodeWithContentDescription("S01").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("S20").assertIsNotDisplayed()
    }

    @Test
    fun longDirectionNames_scrollInsteadOfEllipsis() {
        // 顶上的「始发 → 终点」站名太长时不打省略号，交给跑马灯滚
        val longLine = line.copy(
            startStop = "人民东路学海路（南汇大学城）",
            endStop = "申昆路公交枢纽站（西区）"
        )
        val state = stateOf(entryStopOrder = null)
        show(state.copy(static = LineStatic(longLine, null, stops)))

        val text = "人民东路学海路（南汇大学城） → 申昆路公交枢纽站（西区）"
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)

        val layout = results.single()
        assertEquals(1, layout.lineCount)
        assertFalse("方向行被截成省略号", layout.isLineEllipsized(0))
    }

    @Test
    fun verticalMode_jumpsListToEntryStation() {
        // 走向条样式来自「我的」设置，线路页没有切换按钮
        show(stateOf(entryStopOrder = 20).copy(railMode = RailMode.Vertical))

        // 竖排走向条滚到第 20 站：主卡胶囊和走向条各出现一次「S20」，第 1 站没被组合出来
        composeTestRule.onAllNodesWithText("S20").assertCountEquals(2)
        composeTestRule.onNodeWithText("S01").assertDoesNotExist()
    }

    @Test
    fun busListMode_stillShowsBusRowsInFixedCard() {
        // 主卡固定 + 最多六成高：车辆列表在卡内显示，不被裁掉
        val state = stateOf(entryStopOrder = 20).copy(
            panelMode = PanelMode.BUS_LIST,
            live = LineLive(
                buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 21, etaSeconds = 120))
            )
        )
        show(state)

        composeTestRule.onNodeWithText("沪A1234").assertIsDisplayed()
    }

    @Test
    fun horizontalMode_tallCardScrollsWholePageDownToRail() {
        // 横排整页一起滚、主卡不限高：30 辆车把主卡撑高，走向条被推到首屏之外
        val buses = (1..30).map { index ->
            LiveBus(busId = "b$index", plateOrFleetNo = "沪A" + index.toString().padStart(4, '0'), order = index)
        }
        show(
            stateOf(entryStopOrder = 20).copy(
                panelMode = PanelMode.BUS_LIST,
                live = LineLive(buses = buses)
            )
        )

        composeTestRule.onNodeWithContentDescription("S20").assertIsNotDisplayed()

        // 滚下去能看到横条：主卡没被限高裁成卡内滚动
        // （先滚横条整体；横条自己的横向滚动带不动纵向位置）
        composeTestRule.onNodeWithTag(HorizontalRailTags.ROOT).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("S20").assertIsDisplayed()
    }

    @Test
    fun collapseButton_shrinksCardToSingleRowAndExpandsBack() {
        show(stateOf(entryStopOrder = 20))

        // 展开态：详细内容都在
        composeTestRule.onNodeWithText("第 20 站").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("收起卡片").performClick()

        // 收起：详细内容消失，只剩候车站、到站和展开按钮
        composeTestRule.onNodeWithText("第 20 站").assertDoesNotExist()
        composeTestRule.onNodeWithText("S20").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("展开卡片").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("展开卡片").performClick()

        // 再展开：详细内容回来
        composeTestRule.onNodeWithText("第 20 站").assertIsDisplayed()
    }
}
