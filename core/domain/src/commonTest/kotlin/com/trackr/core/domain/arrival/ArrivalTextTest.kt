package com.trackr.core.domain.arrival

import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrivalTextTest {

    private fun bus(
        order: Int,
        etaSeconds: Int = -1,
        isArrived: Boolean = false,
        hasAuthoritativeArrival: Boolean = false,
        progress: Float = 0.5f,
        hasCoords: Boolean = true,
        id: String = "b$order"
    ) = LiveBus(
        busId = id,
        plateOrFleetNo = "粤B12345",
        order = order,
        segmentProgress = progress,
        isArrived = isArrived,
        hasAuthoritativeArrival = hasAuthoritativeArrival,
        wgsLat = if (hasCoords) 22.54 else 0.0,
        wgsLng = if (hasCoords) 113.95 else 0.0,
        etaSeconds = etaSeconds
    )

    private fun runningLine(
        target: Int,
        buses: List<LiveBus>,
        next: String? = null,
        now: LocalTime? = null,
        first: String = "06:30",
        last: String = "22:30"
    ) = ArrivalText.stateFor(
        targetOrder = target,
        buses = buses,
        operatingState = LineOperatingState.RUNNING,
        startStop = "白石洲",
        firstTime = first,
        lastTime = last,
        nextDeparture = next,
        now = now
    )

    // ---------- 1. 在路上 ----------

    @Test
    fun onTheWay_minutesBigStopsSmall() {
        val state = runningLine(target = 5, buses = listOf(bus(order = 1, etaSeconds = 480)))
        assertEquals(ArrivalState.OnTheWay(stopsAway = 4, minutes = 8), state)

        val d = ArrivalText.format(state, lineName = "325路", stopName = "科技园")
        assertEquals("约 8", d.value)
        assertEquals("分钟", d.unit)
        assertEquals("约 8 分钟", d.headline)
        assertEquals("还有 4 站", d.detail)
        assertEquals("约 8 分钟 · 还有 4 站", d.short)
        assertEquals(ArrivalTone.Normal, d.tone)
        assertEquals("325路，还有 4 站，大约 8 分钟到科技园站", d.spoken)
    }

    @Test
    fun onTheWay_withinThreeMinutes_isGo() {
        val d = ArrivalText.format(ArrivalState.OnTheWay(stopsAway = 4, minutes = 3))
        assertEquals(ArrivalTone.Go, d.tone)
        assertEquals(ArrivalTone.Normal, ArrivalText.format(ArrivalState.OnTheWay(4, 4)).tone)
    }

    @Test
    fun onTheWay_oneStopWithoutEta_showsStopsAndIsGo() {
        val state = runningLine(target = 5, buses = listOf(bus(order = 4, etaSeconds = -1)))
        val d = ArrivalText.format(state)
        assertEquals("1", d.value)
        assertEquals("站", d.unit)
        assertEquals("暂无到站时间", d.detail)
        assertEquals("还有 1 站 · 暂无到站时间", d.short)
        assertEquals(ArrivalTone.Go, d.tone)
    }

    @Test
    fun zeroStops_etaOutsideImminentRange_isNotArriving() {
        // 剩 0 站但 ETA 90 秒，不在 1..45 秒：还没到，写「下一站就到」，不写「到站了」
        val state = runningLine(target = 5, buses = listOf(bus(order = 5, etaSeconds = 90)))
        assertEquals(ArrivalState.OnTheWay(stopsAway = 0, minutes = 2), state)
        val d = ArrivalText.format(state)
        assertEquals("约 2 分钟 · 下一站就到", d.short)
        assertEquals(ArrivalTone.Go, d.tone)

        val noEta = ArrivalText.format(runningLine(5, listOf(bus(order = 5, etaSeconds = -1))))
        assertEquals("下一站", noEta.value)
        assertEquals("下一站就到", noEta.short)
    }

    @Test
    fun noStopOrder_usesEtaOnly() {
        val withEta = ArrivalText.stateForBus(targetOrder = 0, bus = bus(order = 0, etaSeconds = 300))
        assertEquals(ArrivalState.OnTheWay(stopsAway = null, minutes = 5), withEta)
        assertEquals("约 5 分钟", ArrivalText.format(withEta).short)

        val nothing = ArrivalText.format(ArrivalText.stateForBus(0, bus(order = 0)))
        assertEquals("在路上 · 暂无到站时间", nothing.short)
    }

    // ---------- 2. 快到 / 已到 ----------

    @Test
    fun arriving_followsArrivalRules() {
        // 上游到站标志
        assertTrue(runningLine(5, listOf(bus(order = 5, isArrived = true))) is ArrivalState.Arriving)
        // 未声明权威到站状态时，ETA 在 1..45 秒可以判为进站
        assertTrue(runningLine(5, listOf(bus(order = 5, etaSeconds = 45))) is ArrivalState.Arriving)
        assertFalse(runningLine(5, listOf(bus(order = 5, etaSeconds = 46))) is ArrivalState.Arriving)
        // 眉山官方有 isArriveSite：ETA 很短也只能显示即将到站，不能冒充已到站
        assertFalse(
            runningLine(5, listOf(bus(order = 5, etaSeconds = 10, hasAuthoritativeArrival = true)))
                is ArrivalState.Arriving
        )
        assertTrue(
            runningLine(5, listOf(bus(order = 5, etaSeconds = 10, isArrived = true, hasAuthoritativeArrival = true)))
                is ArrivalState.Arriving
        )
        // ETA <= 0 不算到站
        assertFalse(runningLine(5, listOf(bus(order = 5, etaSeconds = 0))) is ArrivalState.Arriving)
        // 坐标有效时插值 >= 0.98 算停靠
        assertTrue(runningLine(5, listOf(bus(order = 5, progress = 0.99f))) is ArrivalState.Arriving)
        // 坐标缺失时上游默认 1.0，不信插值
        assertFalse(runningLine(5, listOf(bus(order = 5, progress = 1.0f, hasCoords = false))) is ArrivalState.Arriving)
        // 单辆车已开过目标站（两次轮询之间错过停靠）
        assertTrue(ArrivalText.stateForBus(5, bus(order = 6)) is ArrivalState.Arriving)
    }

    @Test
    fun arriving_showsNextBus() {
        val state = runningLine(
            target = 5,
            buses = listOf(bus(order = 5, isArrived = true), bus(order = 2, etaSeconds = 540))
        )
        assertEquals(ArrivalState.Arriving(nextBusMinutes = 9, nextBusStops = 3), state)

        val d = ArrivalText.format(state, lineName = "325路", stopName = "科技园")
        assertEquals("到站了", d.headline)
        assertNull(d.unit)
        assertEquals("快去上车 · 下一辆 约 9 分钟", d.detail)
        assertEquals("到站了 · 下一辆 约 9 分钟", d.short)
        assertEquals(ArrivalTone.Go, d.tone)
        assertEquals("325路，车到科技园站了，快去上车，下一辆 约 9 分钟", d.spoken)

        val noNext = ArrivalText.format(ArrivalState.Arriving())
        assertEquals("快去上车", noNext.detail)
        assertEquals("到站了", noNext.short)

        val nextByStops = ArrivalText.format(ArrivalState.Arriving(nextBusStops = 4))
        assertEquals("快去上车 · 下一辆还有 4 站", nextByStops.detail)
    }

    // ---------- 3. 车都过了 ----------

    @Test
    fun allPassed_showsNextStartDepartureOnly() {
        val state = runningLine(target = 5, buses = listOf(bus(order = 6)), next = "7:20")
        val d = ArrivalText.format(state)
        assertEquals("下一班 07:20", d.headline)
        assertEquals("从 白石洲 发车", d.detail)
        assertEquals("下一班 07:20 从 白石洲 发车", d.short)
        assertEquals(ArrivalTone.Mute, d.tone)
        // 不估算到站
        assertFalse(d.short.contains("分钟"))
        assertFalse(d.detail.contains("到"))
    }

    @Test
    fun allPassed_withoutNextDeparture_showsLastBus() {
        val state = runningLine(target = 5, buses = listOf(bus(order = 6)), now = LocalTime(21, 0))
        val d = ArrivalText.format(state)
        assertEquals("等待发车", d.value)
        assertEquals("暂无下一班发车时间 · 末班 22:30", d.detail)
        assertEquals("等待发车 · 末班 22:30", d.short)
        assertEquals(ArrivalTone.Mute, d.tone)
    }

    @Test
    fun allPassed_noNextDeparture_noStartStop_fallsBackToStartWord() {
        val d = ArrivalText.format(ArrivalState.AllPassed(nextDeparture = "21:05", startStop = "", lastTime = ""))
        assertEquals("从始发站发车", d.detail)
    }

    // ---------- 4. 还没发车 ----------

    @Test
    fun notStarted_showsFirstOrNextDeparture() {
        val waiting = ArrivalText.stateFor(
            targetOrder = 5,
            buses = emptyList(),
            operatingState = LineOperatingState.WAITING,
            startStop = "白石洲",
            firstTime = "06:30",
            lastTime = "22:30"
        )
        val d = ArrivalText.format(waiting)
        assertEquals("首班 06:30", d.headline)
        assertEquals("从 白石洲 发车", d.detail)
        assertEquals(ArrivalTone.Mute, d.tone)

        val withNext = ArrivalText.format(ArrivalState.NotStarted("06:30", "06:40", "白石洲"))
        assertEquals("下一班 06:40", withNext.headline)

        val unknown = ArrivalText.format(ArrivalState.NotStarted("", null, "白石洲"))
        assertEquals("还没发车", unknown.headline)
        assertEquals("暂无发车时间", unknown.detail)
    }

    // ---------- 5. 末班已过 ----------

    @Test
    fun ended_showsTomorrowFirstBus() {
        val ended = ArrivalText.stateFor(
            targetOrder = 5,
            buses = emptyList(),
            operatingState = LineOperatingState.ENDED,
            firstTime = "06:30",
            lastTime = "22:30"
        )
        val d = ArrivalText.format(ended)
        assertEquals("今天没车了", d.headline)
        assertEquals("明天首班 06:30", d.detail)
        assertEquals(ArrivalTone.Warn, d.tone)

        assertEquals("末班 22:30 已过", ArrivalText.format(ArrivalState.Ended("", "22:30")).detail)
        assertEquals("末班已过", ArrivalText.format(ArrivalState.Ended("", "")).detail)
    }

    @Test
    fun lastBusJustPassed_boundary() {
        val passed = listOf(bus(order = 6))
        // 末班 22:30，22:30 整还不算过，22:31 算过
        assertTrue(runningLine(5, passed, now = LocalTime(22, 30)) is ArrivalState.AllPassed)
        assertEquals(ArrivalState.Ended("06:30", "22:30"), runningLine(5, passed, now = LocalTime(22, 31)))
        // 有下一班时一定是「车都过了」
        assertTrue(runningLine(5, passed, next = "22:40", now = LocalTime(22, 31)) is ArrivalState.AllPassed)
        // 跨午夜的线（末班早于首班）不判
        assertTrue(runningLine(5, passed, now = LocalTime(23, 0), first = "05:30", last = "00:30") is ArrivalState.AllPassed)
        // 没给现在时刻不判
        assertTrue(runningLine(5, passed, now = null) is ArrivalState.AllPassed)
    }

    // ---------- 6. 停运 / 无数据 ----------

    @Test
    fun noService_suspendedAndNoData() {
        val suspended = ArrivalText.format(
            ArrivalText.stateFor(
                targetOrder = 5,
                buses = emptyList(),
                operatingState = LineOperatingState.SUSPENDED,
                statusDescription = "道路施工"
            )
        )
        assertEquals("暂停运营", suspended.headline)
        assertEquals("道路施工", suspended.detail)
        assertEquals(ArrivalTone.Warn, suspended.tone)

        val suspendedNoReason = ArrivalText.format(
            ArrivalText.stateFor(5, emptyList(), LineOperatingState.SUSPENDED)
        )
        assertEquals("暂无发车计划", suspendedNoReason.detail)

        val noBus = ArrivalText.format(runningLine(target = 5, buses = emptyList()))
        assertEquals("暂无在途车辆", noBus.headline)
        assertEquals("运营 06:30–22:30", noBus.detail)
        assertEquals("暂无在途车辆 · 运营 06:30–22:30", noBus.short)

        val noRealtime = ArrivalText.format(
            ArrivalText.stateFor(5, emptyList(), LineOperatingState.NO_REALTIME)
        )
        assertEquals("未开通实时", noRealtime.short)
    }

    // ---------- 7. 加载中 ----------

    @Test
    fun loading() {
        val d = ArrivalText.format(ArrivalState.Loading)
        assertEquals("— 分钟", d.headline)
        assertEquals("正在查车…", d.detail)
        assertEquals(ArrivalTone.Mute, d.tone)
    }

    // ---------- 8. 没网 ----------

    @Test
    fun offline_showsStaleMinutesWithWarning() {
        val d = ArrivalText.format(ArrivalState.Offline(lastMinutes = 4, ageMinutes = 1))
        assertEquals("4 分钟", d.headline)
        assertEquals("没网，显示的是 1 分钟前", d.detail)
        assertTrue(d.detailIsWarning)
        assertEquals(ArrivalTone.Mute, d.tone)

        val noData = ArrivalText.format(ArrivalState.Offline())
        assertEquals("— 分钟", noData.headline)
        assertEquals("没网，连上后自动刷新", noData.detail)
    }

    // ---------- 共用 ----------

    @Test
    fun everyStateHasSpokenText() {
        val states = listOf(
            ArrivalState.Loading,
            ArrivalState.OnTheWay(4, 8),
            ArrivalState.Arriving(),
            ArrivalState.AllPassed("07:20", "白石洲", "22:30"),
            ArrivalState.NotStarted("06:30", null, "白石洲"),
            ArrivalState.Ended("06:30", "22:30"),
            ArrivalState.NoService("暂停运营"),
            ArrivalState.Offline(4, 1)
        )
        states.forEach { state ->
            val d = ArrivalText.format(state, lineName = "325路", stopName = "科技园")
            assertTrue(d.spoken.isNotBlank(), "$state 缺朗读文本")
            assertTrue(d.value.isNotBlank(), "$state 缺大字")
            assertTrue(d.short.isNotBlank(), "$state 缺短文字")
        }
        assertEquals("325路，等待发车，下一班 7点20分 从 白石洲 发车", ArrivalText.format(states[3], "325路").spoken)
    }

    @Test
    fun spoken_allEightStates() {
        fun say(state: ArrivalState) = ArrivalText.spoken(state, lineName = "325路", stopName = "科技园", towards = "科技园")
        val who = "325路，开往科技园，"
        assertEquals("${who}正在查车", say(ArrivalState.Loading))
        assertEquals("${who}还有 2 站，大约 4 分钟到科技园站", say(ArrivalState.OnTheWay(2, 4)))
        assertEquals("${who}还有 3 站到科技园站", say(ArrivalState.OnTheWay(3, null)))
        assertEquals("${who}车到科技园站了，快去上车", say(ArrivalState.Arriving()))
        assertEquals("${who}等待发车，下一班 7点20分 从 白石洲 发车", say(ArrivalState.AllPassed("07:20", "白石洲", "22:30")))
        assertEquals("${who}等待发车，暂无下一班发车时间，末班 22点30分", say(ArrivalState.AllPassed(null, "白石洲", "22:30")))
        assertEquals("${who}还没发车，首班 6点30分 从 白石洲 发车", say(ArrivalState.NotStarted("06:30", null, "白石洲")))
        assertEquals("${who}还没发车，下一班 7点 从 白石洲 发车", say(ArrivalState.NotStarted("06:30", "7:00", "白石洲")))
        assertEquals("${who}今天没车了，明天首班 6点30分", say(ArrivalState.Ended("06:30", "22:30")))
        assertEquals("${who}暂停运营", say(ArrivalState.NoService("暂停运营", "暂无发车计划")))
        assertEquals("${who}没网，上次查到大约 4 分钟到科技园站", say(ArrivalState.Offline(4, 1)))
        assertEquals("${who}没网，查不到车", say(ArrivalState.Offline()))
    }

    @Test
    fun spoken_withoutNamesAndStationSuffix() {
        // 不给线路名 / 开往时不读前缀；站名已经带「站」不重复
        assertEquals("还有 2 站，大约 4 分钟到站", ArrivalText.spoken(ArrivalState.OnTheWay(2, 4)))
        assertEquals("车到深大地铁站了，快去上车", ArrivalText.spoken(ArrivalState.Arriving(), stopName = "深大地铁站"))
        assertEquals("325路，车到站了，快去上车", ArrivalText.spoken(ArrivalState.Arriving(), lineName = "325路"))
        // 数字结尾的线路号补「路」
        assertEquals("325路，车到站了，快去上车", ArrivalText.spoken(ArrivalState.Arriving(), lineName = "325"))
        assertEquals("M194路，车到站了，快去上车", ArrivalText.spoken(ArrivalState.Arriving(), lineName = "M194"))
        assertEquals("地铁1号线，车到站了，快去上车", ArrivalText.spoken(ArrivalState.Arriving(), lineName = "地铁1号线"))
        // 屏幕上的字不受 towards 影响
        val plain = ArrivalText.format(ArrivalState.OnTheWay(2, 4))
        val withTowards = ArrivalText.format(ArrivalState.OnTheWay(2, 4), towards = "科技园")
        assertEquals(plain.copy(spoken = ""), withTowards.copy(spoken = ""))
    }

    @Test
    fun spokenClock_readsLikeSpeech() {
        assertEquals("7点20分", ArrivalText.spokenClock("07:20"))
        assertEquals("7点", ArrivalText.spokenClock("7:00"))
        assertEquals("22点30分", ArrivalText.spokenClock("22:30:00"))
        assertEquals("0点5分", ArrivalText.spokenClock("00:05"))
        assertEquals("约半小时", ArrivalText.spokenClock(" 约半小时 "))
    }

    @Test
    fun minutesOf_roundsAndIgnoresNoPrediction() {
        assertNull(ArrivalText.minutesOf(0))
        assertNull(ArrivalText.minutesOf(-1))
        assertEquals(1, ArrivalText.minutesOf(29))
        assertEquals(1, ArrivalText.minutesOf(89))
        assertEquals(2, ArrivalText.minutesOf(90))
        assertEquals(8, ArrivalText.minutesOf(480))
        assertEquals("约 8 分钟", ArrivalText.minutesText(480))
        assertNull(ArrivalText.minutesText(0))
    }

    @Test
    fun stateForSession_usesArrivalRules() {
        val base = TrackingSessionState(isActive = true, stopsRemaining = 2, etaSeconds = 240)
        assertEquals(ArrivalState.OnTheWay(2, 4), ArrivalText.stateForSession(base))
        assertEquals(ArrivalState.Loading, ArrivalText.stateForSession(base.copy(stopsRemaining = -1)))
        assertEquals(ArrivalState.Arriving(), ArrivalText.stateForSession(base.copy(stopsRemaining = 0, etaSeconds = 30)))
        assertEquals(
            ArrivalState.Arriving(),
            ArrivalText.stateForSession(base.copy(stopsRemaining = 0, isBusArrivedAtStop = true, etaSeconds = 0))
        )
        assertEquals(ArrivalState.OnTheWay(0, 2), ArrivalText.stateForSession(base.copy(stopsRemaining = 0, etaSeconds = 90)))
    }

    @Test
    fun clockText_normalizes() {
        assertEquals("05:30", ArrivalText.clockText("5:30"))
        assertEquals("05:30", ArrivalText.clockText("05:30:00"))
        assertEquals("23:05", ArrivalText.clockText(" 23:05 "))
        assertEquals("约半小时", ArrivalText.clockText("约半小时"))
        assertNull(ArrivalText.parseClock("25:00"))
    }

    @Test
    fun localTimeOf_usesGivenZone() {
        // 2026-10-31T23:40:00Z = 上海 2026-11-01 07:40
        val millis = 1_793_490_000_000L
        assertEquals(LocalTime(7, 40), ArrivalText.localTimeOf(millis, TimeZone.of("Asia/Shanghai")))
    }
}
