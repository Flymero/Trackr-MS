package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.RailMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 换向从方向行挪到底栏：反向线路存在时是常驻圆钮，单程线时不占位；
 * 换向请求进行中转圈并禁用，挡住连点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineDetailBottomBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val line = BusLineDirection(
        lineId = "line_001",
        name = "302路",
        direction = 0,
        startStop = "科技园",
        endStop = "火车站",
        firstTime = "05:30",
        lastTime = "23:30"
    )

    private val reverseLine = line.copy(
        lineId = "line_002",
        direction = 1,
        startStop = "火车站",
        endStop = "科技园"
    )

    private val stops = (1..10).map { order ->
        BusStop(stopId = "s$order", name = "第${order}站", order = order)
    }

    private fun stateOf(
        reverse: BusLineDirection?,
        isSwitchingDirection: Boolean = false,
        showTransitCode: Boolean = true
    ) = LineDetailUiState(
        lineNameHint = line.name,
        isLoading = false,
        static = LineStatic(line = line, reverseDirection = reverse, stops = stops),
        selectedStop = stops[2],
        live = LineLive(buses = emptyList()),
        railMode = RailMode.Horizontal,
        isSwitchingDirection = isSwitchingDirection,
        showTransitCodeButton = showTransitCode
    )

    private fun show(state: LineDetailUiState, onEvent: (LineDetailEvent) -> Unit = {}) {
        composeTestRule.setContent {
            TrackrTheme {
                LineDetailScreen(state = state, onEvent = onEvent)
            }
        }
    }

    @Test
    fun reverseDirectionExists_showsSwitchButtonInBottomBar() {
        show(stateOf(reverse = reverseLine))

        composeTestRule.onNodeWithContentDescription("换向").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("换向").assertIsEnabled()
        // 换向挪走后，方向行只剩站名；底栏另外两个动作还在
        composeTestRule.onNodeWithText("科技园 → 火车站").assertIsDisplayed()
    }

    @Test
    fun oneWayLine_hidesSwitchButton() {
        show(stateOf(reverse = null))

        composeTestRule.onNodeWithContentDescription("换向").assertDoesNotExist()
    }

    @Test
    fun reverseWithoutLineId_hidesSwitchButton() {
        // 脏数据：反向对象在但 lineId 为空，点了也请求不出去，当没有处理
        show(stateOf(reverse = reverseLine.copy(lineId = "")))

        composeTestRule.onNodeWithContentDescription("换向").assertDoesNotExist()
    }

    @Test
    fun switching_disablesSwitchButton() {
        show(stateOf(reverse = reverseLine, isSwitchingDirection = true))

        composeTestRule.onNodeWithContentDescription("换向").assertIsNotEnabled()
    }

    @Test
    fun clickSwitch_sendsSwitchDirectionEvent() {
        var lastEvent: LineDetailEvent? = null
        show(stateOf(reverse = reverseLine)) { lastEvent = it }

        composeTestRule.onNodeWithContentDescription("换向").performClick()

        assertEquals(LineDetailEvent.SwitchDirection, lastEvent)
    }

    // ---- 乘车码按钮跟着「我的」里的开关 ----

    @Test
    fun transitCodeButton_shownByDefault() {
        show(stateOf(reverse = reverseLine))

        composeTestRule.onNodeWithContentDescription("乘车码").assertIsDisplayed()
    }

    @Test
    fun transitCodeButton_hiddenWhenSettingOff() {
        // 「我的」里关掉「显示乘车码按钮」：首页悬浮按钮去掉后，线路页底栏这个圆钮也不留
        show(stateOf(reverse = reverseLine, showTransitCode = false))

        composeTestRule.onNodeWithContentDescription("乘车码").assertDoesNotExist()
        // 其余动作照旧
        composeTestRule.onNodeWithText("追踪这班车").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("换向").assertIsDisplayed()
    }
}
