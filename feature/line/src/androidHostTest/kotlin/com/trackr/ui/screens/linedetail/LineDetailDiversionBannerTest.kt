package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * 临时改道提示：掌上公交只在线路名里写「（临时改道）」（莆田 153路实测）。
 * 线路页标题去掉这截括号，方向行下面挂一条提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineDetailDiversionBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stops = (1..10).map { BusStop(stopId = "s$it", name = "第${it}站", order = it) }

    private fun show(lineName: String) {
        val line = BusLineDirection(
            lineId = "mg:0:莆田市:$lineName:1",
            name = lineName,
            startStop = "莆田汽车站",
            endStop = "枫亭汽车站",
            firstTime = "06:20",
            lastTime = "18:30"
        )
        composeTestRule.setContent {
            TrackrTheme {
                LineDetailScreen(
                    state = LineDetailUiState(
                        lineNameHint = lineName,
                        isLoading = false,
                        static = LineStatic(line = line, reverseDirection = null, stops = stops),
                        selectedStop = stops[2],
                        live = LineLive(buses = emptyList()),
                        railMode = RailMode.Horizontal
                    ),
                    onEvent = {}
                )
            }
        }
    }

    @Test
    fun divertedLine_showsBannerAndPlainTitle() {
        show("153路（临时改道）")

        composeTestRule.onNodeWithText("153路").assertIsDisplayed()
        composeTestRule.onNodeWithText("临时改道").assertIsDisplayed()
        composeTestRule.onNodeWithText("这条线正在临时改道，走向和站点可能和平时不同，以下面的站点为准").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("153路（临时改道）").fetchSemanticsNodes().size)
    }

    @Test
    fun normalLine_hasNoBanner() {
        show("153路")

        composeTestRule.onNodeWithText("153路").assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithText("临时改道", substring = true).fetchSemanticsNodes().size)
    }
}
