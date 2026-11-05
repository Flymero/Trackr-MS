package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.ui.screens.linedetail.components.BusFollowContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BusFollowContentTest {

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
    fun testBusFollowContent_rendersFollowedBusAndStops() {
        val followedBus = LiveBus(
            busId = "b1",
            plateOrFleetNo = "沪A1234",
            order = 2,
            etaSeconds = 120,
            speedKmh = 35.0,
            isArrived = false
        )
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(followedBus)
        )
        var backClicked = false

        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = lineDetail,
                    followedBus = followedBus,
                    onBack = { backClicked = true }
                )
            }
        }

        // 验证标题与车牌
        composeTestRule.onNodeWithText("单车跟随 · 沪A1234").assertIsDisplayed()
        composeTestRule.onNodeWithText("开往  上海体育馆").assertIsDisplayed()

        // 验证统计概要卡
        composeTestRule.onNodeWithText("距终点").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("时速").assertIsDisplayed()
        composeTestRule.onNodeWithText("35 km/h").assertIsDisplayed()
        composeTestRule.onNodeWithText("到终点预测").assertIsDisplayed()

        // 验证站点与状态徽标
        composeTestRule.onNodeWithText("汉口路").assertIsDisplayed()
        composeTestRule.onNodeWithText("人民广场").assertIsDisplayed()
        composeTestRule.onNodeWithText("上海体育馆").assertIsDisplayed()
        composeTestRule.onNodeWithText("正在接近").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一站").assertIsDisplayed()
        composeTestRule.onNodeWithText("已过").assertIsDisplayed()
    }

    @Test
    fun testBusFollowContent_showsDistanceToWaitStation() {
        val followedBus = LiveBus(
            busId = "b1",
            plateOrFleetNo = "沪A1234",
            order = 2,
            etaSeconds = 120,
            distanceToTargetMeters = 320,
            isArrived = false
        )
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(followedBus)
        )

        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = lineDetail,
                    followedBus = followedBus,
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("距候车站 320米").assertIsDisplayed()
    }

    @Test
    fun sparseStopOrders_countsToTerminalAndMarksNextStop() {
        val sparseStops = stops.mapIndexed { index, stop -> stop.copy(order = listOf(1, 7, 20)[index]) }
        val followedBus = LiveBus(
            busId = "sparse",
            plateOrFleetNo = "川Z1234",
            order = 7,
            stopOrders = sparseStops.map { it.order },
            hasAuthoritativeArrival = true
        )
        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = BusLineDetail(line = lineDirection, stops = sparseStops, buses = listOf(followedBus)),
                    followedBus = followedBus,
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("1 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一站").assertIsDisplayed()
    }

    @Test
    fun testBusFollowContent_handlesNullBus() {
        val lineDetail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList()
        )

        composeTestRule.setContent {
            TrackrTheme {
                BusFollowContent(
                    lineDetail = lineDetail,
                    followedBus = null,
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("这辆车已不在线上（可能已收班或已到达终点）。").assertIsDisplayed()
    }
}
