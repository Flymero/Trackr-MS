package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.WatchPollPlan
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.WatchLine
import com.trackr.core.testing.FakeRealtimeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 一起等取数 [WatchTogetherPoller]：数请求次数（不发真实请求，用 [FakeRealtimeRepository]）。
 */
class WatchTogetherPollerTest {

    private val city = TransitCity(cityId = "sz", cityName = "深圳")

    private fun line(i: Int, order: Int = 5) = WatchLine(lineId = "L$i", lineName = "${i}路", stopOrder = order)

    private fun target(lines: List<WatchLine>) = TrackingTarget.WatchTogether(
        cityId = "sz",
        stopId = "st1",
        stopName = "科技园站",
        lines = lines,
        physicalStId = "p1"
    )

    private fun status(lineId: String, buses: List<LiveBus>, statusText: String = "", targetOrder: Int = 5) = NearbyStopLineStatus(
        line = BusLineDirection(lineId = lineId, name = lineId, startStop = "起", endStop = "终"),
        targetOrder = targetOrder,
        targetStopId = "st1",
        targetStopName = "科技园站",
        statusText = statusText,
        approachingBuses = buses
    )

    private fun stop(lines: List<NearbyStopLineStatus>, source: DataSourceType = DataSourceType.CHELAILE) = NearbyStop(
        stopId = "st1",
        name = "科技园站",
        wgsLat = 22.54,
        wgsLng = 113.95,
        physicalStId = "p1",
        lines = lines,
        source = source
    )

    private fun bus(id: String, order: Int) = LiveBus(busId = id, plateOrFleetNo = id, order = order, etaSeconds = 120)

    private fun detail(lineId: String) = BusLineDetail(
        line = BusLineDirection(lineId = lineId, name = lineId, startStop = "起", endStop = "终"),
        stops = (1..10).map { BusStop(stopId = "s$it", name = "站$it", order = it) },
        buses = listOf(bus("$lineId-bus", 4))
    )

    @Test
    fun tenLines_allInStopDetail_onlyOneRequest() = runTest {
        val lines = (1..10).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(
            listOf(stop(lines.map { status(it.lineId, listOf(bus("${it.lineId}-b", 3))) }))
        )

        val poll = WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()

        assertEquals(1, fake.stopDetailCallCount)
        assertTrue(fake.lineDetailCalls.isEmpty())
        assertEquals(lines.map { it.lineId }.toSet(), poll.lines.keys)
        assertEquals(22.54, poll.stopLat)
        assertEquals(3, poll.lines.getValue("L1").buses.single().order)
    }

    @Test
    fun lineMissingFromStopDetail_requestedAlone() = runTest {
        val lines = (1..4).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(
            listOf(
                stop(
                    listOf(
                        status("L1", listOf(bus("b1", 3))),
                        // 没车但有状态文字：这条线的情况站点接口已经说清楚了
                        status("L2", emptyList(), statusText = "等待发车"),
                        // 什么都没有：单独请求
                        status("L3", emptyList())
                    )
                    // L4 根本不在结果里：单独请求
                )
            )
        )
        fake.lineDetailsMap["L3"] = detail("L3")
        fake.lineDetailsMap["L4"] = detail("L4")

        val poll = WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()

        assertEquals(1, fake.stopDetailCallCount)
        assertEquals(setOf("L3", "L4"), fake.lineDetailCalls.toSet())
        assertEquals(2, fake.lineDetailCalls.size)
        assertTrue(poll.lines.getValue("L2").buses.isEmpty())
        assertEquals("等待发车", poll.lines.getValue("L2").statusText)
        assertTrue(poll.lines.getValue("L4").fromLineDetail)
        assertEquals(10, poll.lines.getValue("L4").stops.size)
    }

    @Test
    fun missingLines_atMostFourAtOnce() = runTest {
        val lines = (1..9).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(listOf(stop(emptyList())))
        var active = 0
        var peak = 0
        fake.lineDetailHook = {
            active++
            peak = maxOf(peak, active)
            delay(100)
            active--
        }

        val poll = WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()

        assertEquals(WatchTogetherPoller.MAX_LINES_PER_POLL, fake.lineDetailCalls.size)
        assertEquals(WatchTogetherPoller.MAX_CONCURRENT_LINE_REQUESTS, peak)
        assertEquals(WatchTogetherPoller.MAX_LINES_PER_POLL, poll.lines.size)
    }

    /**
     * 超过 [WatchTogetherPoller.MAX_LINES_PER_POLL] 条线时，每轮取的那一批要轮转：
     * 列表顺序稳定不变，每轮只取前 N 条的话第 N+1 条起的线永远拿不到数据。
     */
    @Test
    fun missingLines_rotateAcrossPolls_soEveryLineIsFetched() = runTest {
        val lines = (1..9).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(listOf(stop(emptyList())))
        val poller = WatchTogetherPoller(fake)

        val rounds = (1..3).map {
            val before = fake.lineDetailCalls.size
            val poll = poller.poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()
            val thisRound = fake.lineDetailCalls.drop(before)
            assertTrue(
                thisRound.size <= WatchTogetherPoller.MAX_LINES_PER_POLL,
                "单轮补查不应超过 ${WatchTogetherPoller.MAX_LINES_PER_POLL} 条，实际 $thisRound"
            )
            // requested 只包含这一轮真正请求过的线
            assertEquals(thisRound.toSet(), poll.requested)
            thisRound
        }

        val allFetched = rounds.flatten().toSet()
        assertEquals(lines.map { it.lineId }.toSet(), allFetched, "连续 3 轮后每条线都应被请求过一次")
        // 相邻两轮不应是同一批
        assertEquals(false, rounds[0].toSet() == rounds[1].toSet())
    }

    /** 只请求线路的分支（待确认 / 「上了哪辆？」）同样轮转，否则后面的线一直不刷新、待确认的车清不掉 */
    @Test
    fun linesPlan_rotatesAcrossPolls() = runTest {
        val lines = (1..9).map { line(it) }
        val fake = FakeRealtimeRepository()
        val poller = WatchTogetherPoller(fake)

        val requested = (1..3).map { poller.poll(target(lines), WatchPollPlan.Lines(lines), city).getOrThrow().requested }

        assertTrue(requested.all { it.size <= WatchTogetherPoller.MAX_LINES_PER_POLL })
        assertEquals(lines.map { it.lineId }.toSet(), requested.flatMap { it }.toSet())
        assertEquals(lines.map { it.lineId }.toSet(), fake.lineDetailCalls.toSet())
    }

    /** 并发的 poll() 不能拿到同一个游标起点，否则同一批线被重复请求、别的线轮空 */
    @Test
    fun concurrentPolls_takeDisjointBatches() = runTest {
        val lines = (1..12).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(listOf(stop(emptyList())))
        fake.lineDetailHook = { delay(50) }
        val poller = WatchTogetherPoller(fake)

        val requested = (1..3).map { async { poller.poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow().requested } }
            .awaitAll()

        assertEquals(lines.map { it.lineId }.toSet(), requested.flatMap { it }.toSet())
        // 每轮各取 4 条、三轮互不重叠：并集应是 12 条而不是更少
        assertEquals(
            requested.size * WatchTogetherPoller.MAX_LINES_PER_POLL,
            requested.flatMap { it }.distinct().size,
            "三个并发轮次应各取一批不同的线")

    }

    @Test
    fun mygolbsStopDetail_hasNoBuses_requestsEachLine() = runTest {
        val lines = (1..2).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(
            listOf(stop(lines.map { status(it.lineId, emptyList(), statusText = "正在运营中") }, source = DataSourceType.MYGOLBS))
        )

        WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()

        assertEquals(1, fake.stopDetailCallCount)
        assertEquals(setOf("L1", "L2"), fake.lineDetailCalls.toSet())
    }

    @Test
    fun linesPlan_requestsOnlyThoseLines_noStopRequest() = runTest {
        val lines = (1..5).map { line(it) }
        val fake = FakeRealtimeRepository()

        val poll = WatchTogetherPoller(fake)
            .poll(target(lines), WatchPollPlan.Lines(listOf(lines[1], lines[3])), city)
            .getOrThrow()

        assertEquals(0, fake.stopDetailCallCount)
        assertEquals(listOf("L2", "L4"), fake.lineDetailCalls.sorted())
        assertEquals(setOf("L2", "L4"), poll.requested)
    }

    @Test
    fun stopOrderDiffers_busOrdersShiftedToSessionOrder() = runTest {
        val watched = listOf(line(1, order = 8))
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.success(listOf(stop(listOf(status(
            "L1", listOf(bus("b", 4).copy(stopOrders = listOf(1, 4, 5, 10))), targetOrder = 5
        )))))

        val poll = WatchTogetherPoller(fake).poll(target(watched), WatchPollPlan.Stop(watched), city).getOrThrow()

        val shifted = poll.lines.getValue("L1").buses.single()
        assertEquals(7, shifted.order)
        assertEquals(listOf(4, 7, 8, 13), shifted.stopOrders)
        assertEquals(1, shifted.stopsTo(8))
    }

    @Test
    fun severalPlatforms_noneMatches_doesNotUseOppositePlatform() = runTest {
        val lines = listOf(line(1))
        val fake = FakeRealtimeRepository()
        val opposite = stop(listOf(status("L1", listOf(bus("wrong", 3))))).copy(stopId = "st9", physicalStId = "p9")
        fake.stopDetailResult = Result.success(listOf(opposite, opposite.copy(stopId = "st8", physicalStId = "p8")))
        fake.lineDetailsMap["L1"] = detail("L1")

        val poll = WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city).getOrThrow()

        assertEquals(listOf("L1"), fake.lineDetailCalls)
        assertEquals("L1-bus", poll.lines.getValue("L1").buses.single().busId)
    }

    @Test
    fun stopRequestFails_isFailure_noFallbackRequests() = runTest {
        val lines = (1..3).map { line(it) }
        val fake = FakeRealtimeRepository()
        fake.stopDetailResult = Result.failure(IllegalStateException("网络错误"))

        val result = WatchTogetherPoller(fake).poll(target(lines), WatchPollPlan.Stop(lines), city)

        assertTrue(result.isFailure)
        assertTrue(fake.lineDetailCalls.isEmpty())
    }
}
