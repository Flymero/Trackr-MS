package com.trackr.feature.bus.home

import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 收藏卡的到站时间（issue #33）。
 *
 * 收藏走线路详情接口，`buses` 是按出场顺序给的，第一条可能是刚出场的头班车（还有几十站，
 * 算出来「89 分钟」），而线路页显示的是最近那辆（7 分钟）。收藏卡和线路页必须挑同一辆车。
 *
 * 本地差异：上游首页大字显示 ETA 分钟；本 fork 首页口径是「站数优先、不把静态轮询 ETA
 * 当倒计时」（见 [BusHomeMapper.arrivalDisplayFor]），所以断言按站数写，选车口径和上游一致。
 */
class BusHomeMapperFavoriteArrivalTest {

    private val fav = FavoriteStationLine(
        id = "034_cl_line_1_10",
        cityId = "034",
        cityName = "上海",
        lineId = "cl_line_1",
        lineName = "71路",
        lineNo = "71",
        startStop = "申昆路枢纽站",
        endStop = "延安东路外滩",
        targetStopId = "stn_10",
        targetStopName = "西藏中路",
        targetOrder = 10,
        source = DataSourceType.CHELAILE
    )

    /** 头班车（刚出场，还有十几站）排在最近那辆前面——线路详情接口就是这么给的 */
    private val headBus = LiveBus(busId = "bus_head", plateOrFleetNo = "沪A·71001", order = 1, etaSeconds = 89 * 60)
    private val nearBus = LiveBus(busId = "bus_near", plateOrFleetNo = "沪A·71002", order = 9, etaSeconds = 7 * 60)

    private fun stateOf(buses: List<LiveBus>) = BusHomeUiState(
        favoriteItems = listOf(FavoriteItem.Stop(fav, groupId = null, sortOrder = 0)),
        favoritesRealtime = mapOf(
            fav.id to NearbyStop(
                stopId = "stn_10",
                name = "西藏中路",
                lines = listOf(
                    NearbyStopLineStatus(
                        line = BusLineDirection(
                            lineId = fav.lineId,
                            name = "71路",
                            lineNo = "71",
                            startStop = fav.startStop,
                            endStop = fav.endStop,
                            source = DataSourceType.CHELAILE
                        ),
                        targetOrder = fav.targetOrder,
                        targetStopId = fav.targetStopId,
                        targetStopName = fav.targetStopName,
                        approachingBuses = buses
                    )
                )
            )
        )
    )

    private fun BusHomeUiState.stopRow(): FavoriteRowUi.Stop =
        BusHomeMapper.favoriteRows(this).filterIsInstance<FavoriteRowUi.Stop>().single()

    @Test
    fun favoriteRow_usesNearestBusNotFirstInList() {
        val row = stateOf(listOf(headBus, nearBus)).stopRow()

        // 近车（order 9）离目标站还有 1 站；错拿头班车会显示「还有 9 站」
        assertEquals("还有 1 站", row.arrival.big)
    }

    @Test
    fun favoriteRow_sameBusAsLinePage() {
        // 线路页（ArrivalText.stateFor）对同一份数据选的也是最近那辆；两边必须同一辆车（issue #33）
        val pageState = ArrivalText.stateFor(targetOrder = fav.targetOrder, buses = listOf(headBus, nearBus))
        val row = stateOf(listOf(headBus, nearBus)).stopRow()

        assertTrue(pageState is ArrivalState.OnTheWay)
        assertEquals(1, pageState.stopsAway)
        assertEquals(7, pageState.minutes)
        // 首页大字按站数口径渲染同一辆近车
        assertEquals("还有 1 站", row.arrival.big)
    }

    @Test
    fun favoriteRow_fallsBackToFirstBusWhenTargetOrderUnknown() {
        // 站序拿不到时没法比远近，只能照上游顺序取第一条；本 fork 首页不显示 ETA，
        // 有车且站序未知时显示「在路上」（上游同场景显示第一条车的分钟数）
        val status = NearbyStopLineStatus(
            line = BusLineDirection(
                lineId = "cl_line_1",
                name = "71路",
                startStop = fav.startStop,
                endStop = fav.endStop,
                source = DataSourceType.CHELAILE
            ),
            targetOrder = 0,
            targetStopId = fav.targetStopId,
            targetStopName = fav.targetStopName,
            approachingBuses = listOf(
                LiveBus(busId = "b1", plateOrFleetNo = "", order = 3, etaSeconds = 5 * 60),
                LiveBus(busId = "b2", plateOrFleetNo = "", order = 5, etaSeconds = 40 * 60)
            )
        )

        val arrival = BusHomeMapper.arrivalFor(status, nextDepartureIsFromStart = false)

        assertEquals("在路上", arrival.big)
    }
}
