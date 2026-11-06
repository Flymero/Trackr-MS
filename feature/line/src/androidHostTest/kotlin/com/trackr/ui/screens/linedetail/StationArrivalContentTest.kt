package com.trackr.ui.screens.linedetail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.ui.screens.linedetail.components.StationArrivalContent
import com.trackr.ui.screens.linedetail.components.StationArrivalTags
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StationArrivalContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lineDirection = BusLineDirection(
        lineId = "line_001",
        name = "71路",
        direction = 0,
        startStop = "延安东路外滩",
        endStop = "申昆路枢纽站",
        firstTime = "05:30",
        lastTime = "23:30",
        source = DataSourceType.CHELAILE
    )

    private val stops = listOf(
        BusStop(stopId = "s1", name = "延安东路外滩", order = 1),
        BusStop(stopId = "s2", name = "陕西北路", order = 5)
    )

    private fun BusLineDetail.static() = LineStatic(line, reverseDirection, stops)

    private fun BusLineDetail.live(fallbackNextDeparture: String? = null) = LineLive(
        buses = buses,
        operatingState = operatingState,
        statusDescription = statusDescription,
        nextDepartureFromStart = nextDepartureFromStart,
        updatedAtMillis = updatedAtMillis,
        fallbackNextDeparture = fallbackNextDeparture
    )

    private fun setContent(
        detail: BusLineDetail,
        selected: BusStop?,
        now: LocalTime? = LocalTime(12, 0),
        fallbackNextDeparture: String? = null,
        isTracking: Boolean = false,
        onOpenBusList: () -> Unit = {},
        onPickStop: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            TrackrTheme {
                StationArrivalContent(
                    static = detail.static(),
                    live = detail.live(fallbackNextDeparture),
                    selectedStop = selected,
                    isTrackingActive = isTracking,
                    onPickStop = onPickStop,
                    onOpenBusList = onOpenBusList,
                    now = now
                )
            }
        }
    }

    @Test
    fun allBusesPassed_withNextDeparture_showsStartDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = "21:05"
        )
        setContent(detail, stops[1])

        // 全车过站后，按当前 ArrivalText 规则显示下一班始发发车时间。
        composeTestRule.onNodeWithText("下一班 21:05").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
        composeTestRule.onNodeWithText("全线 1 辆车在途 · 查看").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_withoutNextDeparture_showsFallbackHint() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("等待发车").assertIsDisplayed()
        composeTestRule.onNodeWithText("暂无下一班发车时间 · 末班 23:30").assertIsDisplayed()
    }

    @Test
    fun upcomingBus_withDistance_showsDistanceToStation() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(
                LiveBus(
                    busId = "b1",
                    plateOrFleetNo = "沪A·D8866",
                    order = 4,
                    etaSeconds = 120,
                    distanceToTargetMeters = 320
                )
            ),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("距本站 320米").assertIsDisplayed()
    }

    @Test
    fun upcomingBus_withoutDistance_hidesDistanceFact() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(
                LiveBus(
                    busId = "b1",
                    plateOrFleetNo = "沪A·D8866",
                    order = 4,
                    etaSeconds = 120,
                    distanceToTargetMeters = -1
                )
            ),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("距本站", substring = true).assertDoesNotExist()
    }

    @Test
    fun allBusesPassed_withTimetableFallback_showsStartDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        setContent(detail, stops[1], now = null, fallbackNextDeparture = "21:40")

        composeTestRule.onNodeWithText("下一班 21:40").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
    }

    @Test
    fun lastBusEnded_showsLastDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.ENDED
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("今天没车了").assertIsDisplayed()
        composeTestRule.onNodeWithText("明天首班 05:30").assertIsDisplayed()
    }

    @Test
    fun allBusesPassed_afterLastBus_showsNoMoreBusToday() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 6)),
            operatingState = LineOperatingState.RUNNING,
            nextDepartureFromStart = null
        )
        setContent(detail, stops[1], now = LocalTime(23, 31))

        composeTestRule.onNodeWithText("今天没车了").assertIsDisplayed()
        composeTestRule.onNodeWithText("明天首班 05:30").assertIsDisplayed()
    }

    @Test
    fun busOnTheWay_showsMinutesBigAndStopsSmall() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 480)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("约 8").assertIsDisplayed()
        composeTestRule.onNodeWithText("分钟").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 3 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一辆还有 ", substring = true).assertDoesNotExist()
    }

    @Test
    fun busArrived_showsArrivedWithNextBus() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 5, isArrived = true),
                LiveBus(busId = "b2", plateOrFleetNo = "沪A·D8867", order = 2, etaSeconds = 540)
            ),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("到站了").assertIsDisplayed()
        composeTestRule.onNodeWithText("快去上车 · 下一辆 约 9 分钟").assertIsDisplayed()
    }

    @Test
    fun waiting_showsFirstDepartureTime() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.WAITING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("首班 05:30").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 延安东路外滩 发车").assertIsDisplayed()
    }

    @Test
    fun reverseSwitchButtonRemoved_notDisplayed() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = emptyList(),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        composeTestRule.onNodeWithText("切换反向同名站").assertDoesNotExist()
        composeTestRule.onNodeWithText("全线暂无在途车辆").assertIsDisplayed()
    }

    @Test
    fun busOnTheWay_showsPlateNextBusAndBoardingStop() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 3, etaSeconds = 240, expectedArrivalTimeText = "12:04"),
                LiveBus(busId = "b2", plateOrFleetNo = "沪A·D8867", order = 1, etaSeconds = 720)
            ),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1])

        // 胶囊只写站名，不再带「站上车」
        composeTestRule.onNodeWithText("陕西北路").assertIsDisplayed()
        composeTestRule.onNodeWithText("陕西北路站上车").assertDoesNotExist()
        composeTestRule.onNodeWithText("约 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 2 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("沪A·D8866").assertIsDisplayed()
        composeTestRule.onNodeWithText("12:04 到").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一辆 约 12 分钟 · 4 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("全线 2 辆车在途 · 查看").assertIsDisplayed()
    }

    @Test
    fun noLiveYet_showsLoadingFromCachedStops() {
        val detail = BusLineDetail(line = lineDirection, stops = stops, buses = emptyList())
        composeTestRule.setContent {
            TrackrTheme {
                StationArrivalContent(
                    static = LineStatic(detail.line, null, detail.stops),
                    live = null,
                    selectedStop = stops[1],
                    isTrackingActive = false,
                    onPickStop = {},
                    onOpenBusList = {}
                )
            }
        }

        composeTestRule.onNodeWithText("正在查车…").assertIsDisplayed()
        composeTestRule.onNodeWithText("全线暂无在途车辆").assertIsDisplayed()
    }

    @Test
    fun tracking_showsTrackingBadge_andAlertChipsGone() {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 480)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1], isTracking = true)

        composeTestRule.onNodeWithText("追踪中").assertIsDisplayed()
        composeTestRule.onNodeWithText("提前到站震动预警：").assertDoesNotExist()
        composeTestRule.onNodeWithText("开启实时追踪").assertDoesNotExist()
    }

    @Test
    fun clicks_openBusListAndPickStop() {
        var openedList = 0
        var picked = 0
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 480)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, stops[1], onOpenBusList = { openedList++ }, onPickStop = { picked++ })

        composeTestRule.onNodeWithText("全线 1 辆车在途 · 查看").performClick()
        composeTestRule.onNodeWithTag(StationArrivalTags.PICK_STOP).performClick()
        assertEquals(1, openedList)
        assertTrue(picked >= 1)
    }

    @Test
    fun longStopName_chipShowsJustTheStopName() {
        // #23：站名很长时主卡胶囊只写站名，末尾不再挂「站上车」
        val longStop = BusStop(stopId = "s3", name = "人民东路学海路（南汇大学城）", order = 3)
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops + longStop,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 300)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, longStop)

        composeTestRule.onNodeWithTag(StationArrivalTags.PICK_STOP).assertIsDisplayed()
        composeTestRule.onNodeWithText("人民东路学海路（南汇大学城）").assertIsDisplayed()
        composeTestRule.onNodeWithText("人民东路学海路（南汇大学城）站上车").assertDoesNotExist()
    }

    @Test
    fun longStopName_scrollsInsideChipInsteadOfEllipsis() {
        // 装不下的站名交给胶囊里的跑马灯：整段完整排版（一行、没省略号），滚动由 basicMarquee 做
        val longStop = BusStop(stopId = "s3", name = "人民东路学海路（南汇大学城）", order = 3)
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops + longStop,
            buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A·D8866", order = 2, etaSeconds = 300)),
            operatingState = LineOperatingState.RUNNING
        )
        setContent(detail, longStop)

        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText("人民东路学海路（南汇大学城）", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)

        val layout = results.single()
        assertEquals(1, layout.lineCount)
        assertFalse("站名被截成省略号", layout.isLineEllipsized(0))
    }
}
