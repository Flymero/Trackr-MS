package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.ui.screens.linedetail.components.BusListContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 停场/非运营车辆逐辆展示（方案 B）：跟排在途车后、红色「停场」标注、点击弹只读信息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BusListContentIdleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lineDirection = BusLineDirection(
        lineId = "line_001",
        name = "49路",
        direction = 0,
        startStop = "汉口路",
        endStop = "上海体育馆",
        source = DataSourceType.CHELAILE
    )

    private val stops = listOf(
        BusStop(stopId = "s1", name = "汉口路", order = 1),
        BusStop(stopId = "s2", name = "人民广场", order = 2),
        BusStop(stopId = "s3", name = "上海体育馆", order = 3)
    )

    @Test
    fun idleBuses_listedWithBadge_whenNoActiveBuses() {
        val idleBus = LiveBus(busId = "b_idle", plateOrFleetNo = "川Z10269D", order = 2, isArrived = true)
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            idleBuses = listOf(idleBus)
        )
        composeTestRule.setContent {
            TrackrTheme {
                BusListContent(
                    lineDetail = lineDetail,
                    selectedStop = stops[1],
                    idleBuses = lineDetail.idleBuses,
                    onBack = {},
                    onSelectBus = {},
                    onIdleBusClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("停场").assertIsDisplayed()
        composeTestRule.onNodeWithText("川Z10269D").assertIsDisplayed()
        composeTestRule.onNodeWithText("位于 人民广场").assertIsDisplayed()
        // 有停场车可看时，不再显示纯空态文案
        composeTestRule.onNodeWithText("当前线路上暂无在途运营车辆").assertDoesNotExist()
    }

    @Test
    fun idleBuses_appendedAfterActiveBuses() {
        val activeBus = LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 1, etaSeconds = 120)
        val idleBus = LiveBus(busId = "b_idle", plateOrFleetNo = "川Z10269D", order = 3, isArrived = true)
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(activeBus),
            idleBuses = listOf(idleBus)
        )
        composeTestRule.setContent {
            TrackrTheme {
                BusListContent(
                    lineDetail = lineDetail,
                    selectedStop = stops[1],
                    idleBuses = lineDetail.idleBuses,
                    onBack = {},
                    onSelectBus = {},
                    onIdleBusClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("沪A1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("停场").assertIsDisplayed()
        composeTestRule.onNodeWithText("川Z10269D").assertIsDisplayed()
    }

    @Test
    fun idleBusClick_reportsBus() {
        val idleBus = LiveBus(busId = "b_idle", plateOrFleetNo = "川Z10269D", order = 2, isArrived = true)
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            idleBuses = listOf(idleBus)
        )
        var clicked: LiveBus? = null
        composeTestRule.setContent {
            TrackrTheme {
                BusListContent(
                    lineDetail = lineDetail,
                    selectedStop = stops[1],
                    idleBuses = lineDetail.idleBuses,
                    onBack = {},
                    onSelectBus = {},
                    onIdleBusClick = { clicked = it }
                )
            }
        }

        composeTestRule.onNodeWithText("川Z10269D").performClick()
        assertEquals(idleBus, clicked)
    }
}
