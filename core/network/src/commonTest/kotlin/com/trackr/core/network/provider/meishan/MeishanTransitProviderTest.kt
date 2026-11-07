package com.trackr.core.network.provider.meishan

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitProtocolFailure
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.push.MeishanPushFrame
import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.roundToInt

/** network 不依赖 :core:testing，测试内同时控制墙钟和单调时钟。 */
private class FakeClock : Clock, MonotonicClock {
    private var instant = Instant.parse("2026-11-04T10:00:00Z")
    private var elapsed = 0L

    override fun now(): Instant = instant
    override fun elapsedMillis(): Long = elapsed

    fun advanceBy(millis: Long) {
        instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + millis)
        elapsed += millis
    }

    fun setWallClock(value: String) {
        instant = Instant.parse(value)
    }
}

private fun pushBus(
    plate: String = "川Z10091D",
    index: Int = 2,
    lat: Double = 30.065,
    speed: Double = 0.0,
    id: Int = 0,
    arrived: Int = 1
): String = """
    {"b_id":$id,"b_plate_num":"$plate","index":$index,"isArriveSite":$arrived,
     "b_latitude":$lat,"b_longitude":103.825,"b_sd":$speed}
""".trimIndent()

private fun pushFrame(
    vararg buses: String,
    time: String = "2026-11-04 18:00:00",
    tag: String = "11_0"
): MeishanPushFrame = MeishanPushFrame(tag, time, JSONArray(buses.joinToString(",", "[", "]")))

/** 北京时间 18:00（10:00Z）：在 06:30–19:00 运营时段内 */
private val fixedClock = object : Clock {
    override fun now(): Instant = Instant.parse("2026-11-04T10:00:00Z")
}

private val lineListJson = """
    {"code":101,"result":[
      {"l_id":1,"l_name":"1路","l_sxpz":"眉山东站:千禾味业","l_xxpz":"千禾味业:眉山东站",
       "l_copy_id":11,"start_time":"06:30","end_time":"19:00",
       "xx_start_time":"06:30","xx_end_time":"19:00","state":0,"areaId":1}
    ]}
""".trimIndent()

private fun stopLinesJson(
    firstLast: Pair<String, String> = "06:30" to "19:00",
    busAtStop: Boolean = false
): String = """
    {"code":101,"result":[
      {"l_id":1,"l_name":"1路","l_copy_id":11,"state":0,
       "l_sxpz":"眉山东站:千禾味业","l_xxpz":"千禾味业:眉山东站",
       "start_time":"${firstLast.first}","end_time":"${firstLast.second}",
       "xx_start_time":"${firstLast.first}","xx_end_time":"${firstLast.second}","areaId":1,
       "siteList":[
         {"s_id":1,"s_name":"眉山东站","latitude":30.06,"longitude":103.82,"index":1},
         {"s_id":2,"s_name":"紫竹西街._跑法2","latitude":30.07,"longitude":103.83,"index":2},
         {"s_id":3,"s_name":"千禾味业","latitude":30.08,"longitude":103.84,"index":3}
       ],
       "busList":[
         {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10091D","index":${if (busAtStop) 2 else 1},"state":0,
          "isArriveSite":${if (busAtStop) 1 else 0},"b_latitude":30.062,"b_longitude":103.821,"b_sd":0.0,"areaId":1}
       ]}
    ]}
""".trimIndent()

private val nearbyJson = """
    {"code":101,"result":{"siteInfos":[
      {"s_id":2,"s_name":"紫竹西街._跑法2","latitude":30.07,"longitude":103.83,
       "dist":0.053,"index":0,"lineIds":"1"},
      {"s_id":1,"s_name":"眉山东站","latitude":30.06,"longitude":103.82,
       "dist":0.31,"index":0,"lineIds":"1"}
    ],"redPackets":[]}}
""".trimIndent()

/** 线路详情的车辆位置按命中次数前移，模拟车辆在 3 秒里走了约 30 米（≈36 km/h）；b_sd 可注入；可选附带场站车 */
private fun lineDetailJson(
    hit: Int,
    bsd: Double = 0.0,
    withYardBuses: Boolean = false,
    firstLast: Pair<String, String> = "06:30" to "19:00",
    latOverride: String? = null,
    busIndex: Int = 2,
    busArrived: Int = 0
): String {
    val lat = latOverride ?: if (hit <= 1) "30.065" else "30.06527"
    // 场站车：index=0、isArriveSite=1、速度 0（实测运营时段大量存在于起点站 / 车场）
    val yardBuses = if (withYardBuses) {
        """,
        {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10269D","index":0,"state":0,
         "isArriveSite":1,"b_latitude":30.061,"b_longitude":103.820,"b_sd":0.0,"areaId":1},
        {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10205D","index":0,"state":0,
         "isArriveSite":1,"b_latitude":30.0612,"b_longitude":103.8202,"b_sd":0.0,"areaId":1}"""
    } else ""
    return """
    {"code":101,"result":{
      "l_id":1,"l_name":"1路","l_copy_id":11,"state":0,
      "l_sxpz":"眉山东站:千禾味业","l_xxpz":"千禾味业:眉山东站",
      "start_time":"${firstLast.first}","end_time":"${firstLast.second}",
      "xx_start_time":"${firstLast.first}","xx_end_time":"${firstLast.second}",
      "l_remark":"票价 1 元",
      "siteList":[
        {"s_id":1,"s_name":"眉山东站","latitude":30.06,"longitude":103.82,"index":1},
        {"s_id":2,"s_name":"紫竹西街","latitude":30.07,"longitude":103.83,"index":2},
        {"s_id":3,"s_name":"千禾味业","latitude":30.08,"longitude":103.84,"index":3}
      ],
      "busList":[
        {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10091D","index":$busIndex,"state":0,
         "isArriveSite":$busArrived,"b_latitude":$lat,"b_longitude":103.825,"b_sd":$bsd,"areaId":1}$yardBuses
      ]
    }}
    """.trimIndent()
}

/**
 * 眉山官方源的解析与判定（doc/news 逆向文档）：线路列表缓存、线路详情、附近站、
 * 同站线路的「还有 X 站」规则（05 §1.2）、城市就近识别、相邻帧实测速度、`ms-st:` 站点 ID。
 */
class MeishanTransitProviderTest {

    private val city = TransitCity(cityId = "", cityName = "眉山", province = "四川")

    /** 按 path 分发的 mock 后端 + 各 path 命中计数 */
    private class MockBackend {
        val hits = HashMap<String, Int>()
        var byIdHits = 0
        var listJson = lineListJson
        var detailResponse: String? = null
        var siteResponse: String? = null

        /** 注入车辆 b_sd（服务端下发的实时速度）；默认 0 模拟字段无效 */
        var byIdBsd = 0.0

        /** 线路详情里附带场站车（index=0）：模拟未发车 / 归场车辆 */
        var withYardBuses = false

        /** 线路详情首末班（默认 06:30–19:00，在 fixedClock 的 18:00 北京时间之内） */
        var byIdFirstLast: Pair<String, String> = "06:30" to "19:00"

        /** 线路详情车辆位置固定（不随命中前移）：模拟停场不动 */
        var byIdLatOverride: String? = null

        /** 线路详情里那辆车的站序和到站标志 */
        var byIdBusIndex = 2
        var byIdBusArrived = 0

        /** `/line/site` 首/末班 + 车辆是否停在本站（isArriveSite=1） */
        var siteFirstLast: Pair<String, String> = "06:30" to "19:00"
        var siteBusAtStop = false

        /** `/line/site` 前 N 次返回业务失败（模拟网络抖动），用于验证重试 */
        var siteFailuresBeforeSuccess = 0
        private var siteHits = 0

        val httpClient = mockHttpClient { request ->
            val path = request.encodedPath.substringAfterLast('/')
            hits[path] = (hits[path] ?: 0) + 1
            when (path) {
                "list" -> MockReply(listJson)
                "byId" -> MockReply(
                    detailResponse ?: lineDetailJson(
                        ++byIdHits, byIdBsd, withYardBuses, byIdFirstLast, byIdLatOverride,
                        byIdBusIndex, byIdBusArrived
                    )
                )
                "site" -> {
                    siteHits++
                    if (siteHits <= siteFailuresBeforeSuccess) {
                        MockReply("""{"code":500}""")
                    } else {
                        MockReply(siteResponse ?: stopLinesJson(siteFirstLast, siteBusAtStop))
                    }
                }
                "siteAndRedPacket" -> MockReply(nearbyJson)
                else -> MockReply("""{"code":102}""")
            }
        }
    }

    private fun newProvider(backend: MockBackend, nowMillis: Long = 0L): MeishanTransitProvider =
        MeishanTransitProvider(
            client = MeishanTransitClient(backend.httpClient),
            wallClock = fixedClock,
            monotonicClock = { nowMillis }
        )

    private fun newProvider(backend: MockBackend, clock: FakeClock): MeishanTransitProvider =
        MeishanTransitProvider(MeishanTransitClient(backend.httpClient), clock, clock)

    @Test
    fun pushTag_usesListCopyIdAndCachesPerArea() = runBlocking {
        val backend = MockBackend()
        val provider = newProvider(backend)
        assertEquals("11_0_1", provider.pushTag("ms:1_1_0"))
        assertEquals("11_1_1", provider.pushTag("ms:1_1_1"))
        assertEquals("11_0_2", provider.pushTag("ms:2_1_0"))
        assertNull(provider.pushTag("ms:1_99_0"))
        assertNull(provider.pushTag("ms:1_1_2"))
        assertNull(provider.pushTag("not-a-meishan-line"))
        assertEquals(2, backend.hits["list"], "每个区域只请求一次线路列表")

        for (copyId in listOf(0, -1)) {
            val missingCopy = MockBackend().apply {
                listJson = lineListJson.replace("\"l_copy_id\":11", "\"l_copy_id\":$copyId")
            }
            assertNull(newProvider(missingCopy).pushTag("ms:1_1_0"), "无有效 copyId 时退回 HTTP")
        }
    }

    @Test
    fun pushFrame_requiresExplicitCopyIdTagAndRejectsLIdFallback() = runBlocking {
        val backend = MockBackend()
        val provider = newProvider(backend)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val expected = assertNotNull(provider.pushTag(base.line.lineId)).substringBeforeLast('_')
        val frame = pushFrame(pushBus(speed = 36.0))
        assertEquals("11_0", expected) // copyId=11 != lId=1
        assertNull(provider.applyPushFrame(base, frame))
        assertNull(provider.applyPushFrame(base, frame, expectedTag = ""))
        assertNull(provider.applyPushFrame(base, frame.copy(tag = "1_0"), expectedTag = expected))
        assertNull(provider.applyPushFrame(base, frame.copy(tag = "11_1"), expectedTag = expected))
        assertNull(provider.applyPushFrame(base, frame, expectedTag = "11_0_1"))
        val detail = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = expected))
        assertEquals(36.0, detail.buses.single().speedKmh)
        assertEquals(base.line, detail.line)
        assertEquals(base.stops, detail.stops)
        assertEquals(base.reverseDirection, detail.reverseDirection)
        assertEquals(base.statusDescription, detail.statusDescription)
        assertEquals(mapOf("byId" to 1), backend.hits, "套用推送不发 HTTP；copyId 应复用详情响应")
    }

    @Test
    fun pushFrame_loneEmptyFramesIgnoredUntilThreshold() = runBlocking {
        val clock = FakeClock()
        val provider = newProvider(MockBackend(), clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val expected = "11_0"
        // 前两帧空（服务端数据抖动）：忽略，调用方保持当前详情不闪断
        assertNull(provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:00"), expectedTag = expected))
        clock.advanceBy(3_000)
        assertNull(provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:03"), expectedTag = expected))
        // 非空帧清零计数，随后孤立空帧重新从 1 数起
        clock.advanceBy(3_000)
        assertNotNull(provider.applyPushFrame(base, pushFrame(pushBus(), time = "2026-11-04 18:00:06"), expectedTag = expected))
        clock.advanceBy(3_000)
        assertNull(provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:09"), expectedTag = expected))
        clock.advanceBy(3_000)
        assertNull(provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:12"), expectedTag = expected))
        // 连续空帧达到阈值：按收车/停运正常清空
        clock.advanceBy(3_000)
        val cleared = assertNotNull(provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:15"), expectedTag = expected))
        assertTrue(cleared.buses.isEmpty() && cleared.idleBuses.isEmpty())
        // 同一帧重放（选站重放/多订阅共用解析）不重复计数：重放仍是清空结果而不是被拒
        val replay = provider.applyPushFrame(base, pushFrame(time = "2026-11-04 18:00:15"), expectedTag = expected)
        assertNotNull(replay)
        assertTrue(replay.buses.isEmpty())
    }

    @Test
    fun pushFrame_dropsBusesFromMismatchedArea() = runBlocking {
        val provider = newProvider(MockBackend())
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val frame = pushFrame(
            pushBus(plate = "川Z10091D", index = 2),
            pushBus(plate = "川Z99999", index = 2).replace("}", ",\"areaId\":2}"),
            time = "2026-11-04 18:00:00"
        )
        val detail = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0"))
        assertEquals(listOf("川Z10091D"), detail.buses.map { it.plateOrFleetNo }, "异区域车辆逐辆剔除，本区域保留")
    }

    @Test
    fun lineDetail_mapsRemarkToPriceAndKeepsRawRemark() = runBlocking {
        val provider = newProvider(MockBackend())
        val detail = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        assertEquals("1 元", detail.line.price, "l_remark「票价 1 元」剥掉前缀后进 price，供「票价 %1\$s」展示")
        assertEquals("1 元", detail.reverseDirection?.price, "票价同行，反方向直接带上")
        assertEquals("票价 1 元", detail.remark, "remark 保留原始文案")
    }

    @Test
    fun pushFrame_reportedSpeedWinsAndReplayRecalculatesOnlyTargetEta() = runBlocking {
        val clock = FakeClock()
        val provider = newProvider(MockBackend(), clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        clock.advanceBy(3_000)
        val frame = pushFrame(pushBus(lat = 30.06554, speed = 25.0))
        val terminal = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0"))
        val bus = terminal.buses.single()
        assertEquals(25.0, bus.speedKmh, "有效 b_sd 不应被位移速度覆盖")
        val meters = assertNotNull(RouteProjectionMath.calculateDistanceMeters(30.06554, 103.825, 30.08, 103.84))
        assertEquals(meters, bus.distanceToTargetMeters)
        assertEquals((meters / (25.0 / 3.6)).roundToInt(), bus.etaSeconds)
        clock.advanceBy(15_000)
        val atBus = assertNotNull(provider.applyPushFrame(base, frame, targetOrder = 2, expectedTag = "11_0"))
        assertEquals(0, atBus.buses.single().etaSeconds)
        assertEquals(0, atBus.buses.single().distanceToTargetMeters)
        val passed = assertNotNull(provider.applyPushFrame(base, frame, stationId = "ms-st:1", expectedTag = "11_0"))
        assertNull(passed.buses.single().distanceToTargetMeters)
        assertEquals(-1, passed.buses.single().etaSeconds, "缓存不能带入另一目标站的 ETA")
        val byId = assertNotNull(provider.applyPushFrame(base, frame, stationId = "ms-st:3", expectedTag = "11_0"))
        assertEquals(bus, byId.buses.single(), "重放应保留同一帧解析出的速度和 ID")
        assertEquals(clock.now().toEpochMilliseconds(), byId.updatedAtMillis)
    }

    @Test
    fun pushFrame_missingSpeedUsesDisplacementAndThenDefaultEta() = runBlocking {
        val clock = FakeClock()
        val provider = newProvider(MockBackend(), clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        clock.advanceBy(3_000)
        val frame = pushFrame(pushBus(lat = 30.06554))
        val measured = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0")).buses.single()
        val traveled = assertNotNull(RouteProjectionMath.calculateDistanceMeters(30.065, 103.825, 30.06554, 103.825))
        assertEquals(traveled / 3.0 * 3.6, measured.speedKmh, 0.001)
        assertEquals((assertNotNull(measured.distanceToTargetMeters) / (measured.speedKmh / 3.6)).roundToInt(), measured.etaSeconds)
        clock.advanceBy(121_000)
        val stale = assertNotNull(provider.applyPushFrame(
            base, pushFrame(pushBus(lat = 30.066), time = "2026-11-04 18:02:04"), expectedTag = "11_0"
        )).buses.single()
        assertEquals(0.0, stale.speedKmh)
        assertEquals((assertNotNull(stale.distanceToTargetMeters) / 4.0).roundToInt(), stale.etaSeconds)
    }

    @Test
    fun pushFrame_filtersIndexZeroAndKeepsBusIdentityAcrossMovementAndReordering() = runBlocking {
        val clock = FakeClock()
        val provider = newProvider(MockBackend(), clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val first = assertNotNull(provider.applyPushFrame(base, pushFrame(
            pushBus(index = 0, plate = "场站车"), pushBus(index = 1), pushBus(plate = "川Z20000D", index = 2)
        ), expectedTag = "11_0"))
        assertEquals(2, first.buses.size)
        assertTrue(first.buses.all { it.order > 0 })
        clock.advanceBy(3_000)
        val moved = assertNotNull(provider.applyPushFrame(base, pushFrame(
            pushBus(plate = "川Z20000D", index = 3), pushBus(index = 2, lat = 30.06554),
            time = "2026-11-04 18:00:03"
        ), expectedTag = "11_0"))
        for (bus in first.buses) {
            assertEquals(bus.busId, moved.buses.single { it.plateOrFleetNo == bus.plateOrFleetNo }.busId)
        }
        assertEquals(base.buses.single().busId, moved.buses.single { it.plateOrFleetNo == "川Z10091D" }.busId)
        val stopBus = provider.getStopDetail(city, StopDetailRequest(stopId = "ms-st:2"))
            .single().lines.single().approachingBuses.single()
        assertEquals(base.buses.single().busId, stopBus.busId, "HTTP 站点、线路和推送使用同一车辆身份")
    }

    @Test
    fun pushFrame_missingPlateUsesPositiveIdOrSeparateAnonymousSlots() = runBlocking {
        val provider = newProvider(MockBackend())
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val first = assertNotNull(provider.applyPushFrame(base, pushFrame(
            pushBus(plate = "", id = 42), pushBus(plate = ""), pushBus(plate = "", lat = 30.07)
        ), expectedTag = "11_0"))
        assertEquals(3, first.buses.map { it.busId }.toSet().size)
        assertNotEquals(first.buses[1].busId, first.buses[2].busId)
        val moved = assertNotNull(provider.applyPushFrame(base, pushFrame(
            pushBus(plate = "", id = 42, index = 3, lat = 30.066),
            pushBus(plate = "", index = 3), pushBus(plate = "", index = 3, lat = 30.071),
            time = "2026-11-04 18:00:03"
        ), expectedTag = "11_0"))
        assertEquals(first.buses.map { it.busId }, moved.buses.map { it.busId })
    }

    @Test
    fun pushFrame_rechecksServiceHoursOnEveryReplay() = runBlocking {
        val clock = FakeClock()
        clock.setWallClock("2026-11-04T22:29:00Z") // 北京时间次日 06:29
        val provider = newProvider(MockBackend(), clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val frame = pushFrame(pushBus(speed = 36.0))
        assertEquals(LineOperatingState.WAITING, base.operatingState)
        val waiting = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0"))
        assertTrue(waiting.buses.isEmpty())
        clock.setWallClock("2026-11-04T22:30:00Z") // 首班边界
        val running = assertNotNull(provider.applyPushFrame(waiting, frame, expectedTag = "11_0"))
        assertEquals(LineOperatingState.RUNNING, running.operatingState)
        assertEquals(1, running.buses.size)
        clock.setWallClock("2026-11-05T11:01:00Z") // 北京时间 19:01：末班刚发出，车还在路上
        val lastRun = assertNotNull(provider.applyPushFrame(running, frame, expectedTag = "11_0"))
        assertEquals(LineOperatingState.RUNNING, lastRun.operatingState)
        assertEquals(1, lastRun.buses.size)
        clock.setWallClock("2026-11-05T13:01:00Z") // 北京时间 21:01：末班宽限期也过了
        val ended = assertNotNull(provider.applyPushFrame(running, frame, expectedTag = "11_0"))
        assertEquals(LineOperatingState.ENDED, ended.operatingState)
        assertTrue(ended.buses.isEmpty())
        assertEquals(clock.now().toEpochMilliseconds(), ended.updatedAtMillis)
    }

    @Test
    fun pushFrame_cacheSeparatesAreasWithTheSameTagAndTime() = runBlocking {
        val provider = newProvider(MockBackend())
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val otherArea = base.copy(line = base.line.copy(lineId = "ms:2_1_0"))
        val frame = pushFrame(pushBus(speed = 25.0))
        val first = assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0"))
        val second = assertNotNull(provider.applyPushFrame(otherArea, frame, expectedTag = "11_0"))
        assertTrue(first.buses.single().busId.startsWith("msb:1:1:0:"))
        assertTrue(second.buses.single().busId.startsWith("msb:2:1:0:"))
    }

    @Test
    fun pushFrame_replayDoesNotAdvanceOrResetParkedTimer() = runBlocking {
        val clock = FakeClock()
        val backend = MockBackend()
        val provider = newProvider(backend, clock)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        var second = 0
        fun nextFrame(speed: Double = 0.0) = pushFrame(
            pushBus(speed = speed),
            time = "2026-11-04 18:${(second / 60).toString().padStart(2, '0')}:${(second % 60).toString().padStart(2, '0')}"
        ).also { second += 3 }
        // 停站上下客、等红灯：3 秒一帧连续 4 分半钟不动，车不能被剔除
        repeat(90) {
            clock.advanceBy(3_000)
            val frame = nextFrame()
            assertEquals(1, assertNotNull(provider.applyPushFrame(base, frame, expectedTag = "11_0")).buses.size, "第 ${it + 1} 帧不应剔除")
            // 同时订阅的另一个页面拿相同帧、换站重放：不能让静止计时重来或多算
            assertEquals(1, assertNotNull(provider.applyPushFrame(base, frame, targetOrder = 2, expectedTag = "11_0")).buses.size)
        }
        // 静止满 5 分钟 → 停场 / 离线，剔除
        repeat(15) {
            clock.advanceBy(3_000)
            provider.applyPushFrame(base, nextFrame(), expectedTag = "11_0")
        }
        clock.advanceBy(3_000)
        val parked = assertNotNull(provider.applyPushFrame(base, nextFrame(), expectedTag = "11_0"))
        assertTrue(parked.buses.isEmpty(), "静止满 5 分钟应判停场")
        clock.advanceBy(3_000)
        val resumed = assertNotNull(provider.applyPushFrame(base, nextFrame(speed = 18.0), expectedTag = "11_0"))
        assertEquals(18.0, resumed.buses.single().speedKmh, "有效下发速度应解除停场")
        assertEquals(1, backend.hits["byId"])
    }

    @Test
    fun departedBus_isPastPreviousStop_andApproachesNextStopForHttpAndPush() = runBlocking {
        val provider = newProvider(MockBackend())
        val request = LineDetailRequest(lineId = "ms:1_1_0", targetOrder = 2)
        val base = provider.getLineDetail(city, request)
        val bus = base.buses.single()
        assertEquals(3, bus.order, "离开第2站后，正在接近第3站")
        assertFalse(bus.isArrived)
        assertFalse(ArrivalRules.isBusStopped(bus))
        assertNull(ArrivalText.nearestBus(2, base.buses), "已离开候车站的车不能继续报到站")
        assertNull(bus.distanceToTargetMeters)
        assertEquals(-1, bus.etaSeconds)
        val pushed = assertNotNull(provider.applyPushFrame(
            base, pushFrame(pushBus(index = 2, arrived = 0)), targetOrder = 2, expectedTag = "11_0"
        ))
        assertEquals(base.buses, pushed.buses, "HTTP与TCP必须使用相同转换")
        val approaching = provider.getLineDetail(city, request.copy(targetOrder = 3)).buses.single()
        assertTrue(assertNotNull(approaching.distanceToTargetMeters) > 0)
        assertTrue(approaching.etaSeconds > 45)
        assertTrue(ArrivalText.stateForBus(3, approaching) is ArrivalState.OnTheWay)
    }

    @Test
    fun stopAndLine_shareArrivalState_forArrivingStoppedAndDepartedBus() = runBlocking {
        for ((index, arrived) in listOf(1 to 0, 2 to 1, 2 to 0)) {
            val response = lineDetailJson(1, busIndex = index, busArrived = arrived)
            val backend = MockBackend().apply {
                detailResponse = response
                siteResponse = response.replace("\"result\":{", "\"result\":[{").replace("}}", "}]}")
            }
            val provider = newProvider(backend)
            val detail = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0", targetOrder = 2))
            val status = provider.getStopDetail(city, StopDetailRequest(stopId = "ms-st:2")).single().lines.single()
            val nearest = ArrivalText.nearestBus(2, detail.buses)
            if (index == 2 && arrived == 0) {
                assertNull(nearest)
                assertTrue(status.approachingBuses.isEmpty())
            } else {
                assertEquals(assertNotNull(nearest), status.approachingBuses.single())
                assertEquals(ArrivalText.stateForBus(2, nearest), ArrivalText.stateForBus(status.targetOrder, status.approachingBuses.single()))
                assertEquals(arrived == 1, ArrivalRules.isBusStopped(nearest))
            }
        }
    }

    @Test
    fun nonContinuousOrders_useNextListedStop_andCountActualStops() = runBlocking {
        val response = lineDetailJson(1, busIndex = 1, busArrived = 0)
            .replace("\"index\":2", "\"index\":10").replace("\"index\":3", "\"index\":30")
        val backend = MockBackend().apply { detailResponse = response }
        val provider = newProvider(backend)
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0", targetOrder = 30))
        val bus = base.buses.single()
        assertEquals(listOf(1, 10, 30), bus.stopOrders)
        assertEquals(10, bus.order)
        assertEquals(1, bus.stopsTo(30))
        assertEquals(ArrivalState.OnTheWay(1, ArrivalText.minutesOf(bus.etaSeconds)), ArrivalText.stateForBus(30, bus))
        val pushed = assertNotNull(provider.applyPushFrame(base, pushFrame(pushBus(index = 10, arrived = 0)), targetOrder = 30, expectedTag = "11_0"))
        assertEquals(30, pushed.buses.single().order)
        assertEquals(0, pushed.buses.single().stopsTo(30))
    }

    @Test
    fun authoritativeArrival_doesNotTreatGpsProjectionAsStop() = runBlocking {
        val provider = newProvider(MockBackend())
        val base = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val bus = assertNotNull(provider.applyPushFrame(base, pushFrame(pushBus(index = 1, arrived = 0, lat = 30.08)), expectedTag = "11_0")).buses.single()
        assertTrue(bus.segmentProgress < 1.0f)
        assertFalse(ArrivalRules.isBusStopped(bus.copy(segmentProgress = 1.0f)))
        assertTrue(ArrivalRules.isBusStopped(bus.copy(isArrived = true)))
    }

    @Test
    fun lineSearch_parsesAndCachesPerArea() = runBlocking {
        val backend = MockBackend()
        val provider = newProvider(backend)
        val lines = provider.search(city, "1路").lines
        assertEquals(1, lines.size)
        assertEquals("1路", lines[0].name)
        assertEquals("眉山东站", lines[0].directions[0].startStop)
        // 同区域第二次搜索走会话缓存：line/list 只拉一次
        provider.search(city, "千禾").lines
        assertEquals(1, backend.hits["list"])
    }

    @Test
    fun lineDetail_mapsStopsBusesAndReverse() = runBlocking {
        val detail = newProvider(MockBackend()).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        assertEquals("1路", detail.line.name)
        assertEquals("眉山东站", detail.line.startStop)
        assertEquals("千禾味业", detail.line.endStop)
        assertEquals(3, detail.stops.size)
        assertEquals("ms-st:2", detail.stops[1].stopId)
        assertEquals("", detail.statusDescription)
        assertEquals("ms:1_1_1", detail.reverseDirection?.lineId)
        assertEquals(1, detail.buses.size)
        assertEquals("川Z10091D", detail.buses[0].plateOrFleetNo)
        // 首帧没有上一帧可比：速度为 0（b_sd 无效）
        assertEquals(0.0, detail.buses[0].speedKmh)
    }

    @Test
    fun lineDetail_eta_polylineToTargetStop_atMeasuredOrFallbackSpeed() = runBlocking {
        val provider = newProvider(MockBackend())

        // 未选上车站：目标站 = 终点站。车已驶离紫竹西街：
        // 距离从车辆 GPS 到正在接近的千禾味业；速度无效按 4 m/s 兜底。
        val toTerminus = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        val bus = toTerminus.buses[0]
        val expected = assertNotNull(RouteProjectionMath.calculateDistanceMeters(30.065, 103.825, 30.08, 103.84))
        assertEquals(expected, bus.distanceToTargetMeters)
        assertEquals((expected / 4.0).roundToInt(), bus.etaSeconds)

        // 指定上车站 = 终点站（order 3）：同样有距离与 ETA（车又前移了一帧，数值略变）
        val withTarget = provider.getLineDetail(
            city,
            LineDetailRequest(lineId = "ms:1_1_0", targetOrder = 3)
        )
        val movedMeters = assertNotNull(RouteProjectionMath.calculateDistanceMeters(30.06527, 103.825, 30.08, 103.84))
        assertEquals(movedMeters, withTarget.buses[0].distanceToTargetMeters)
        assertEquals((movedMeters / 4.0).roundToInt(), withTarget.buses[0].etaSeconds)

        // 目标站是车辆后方（已过站）：不给 ETA
        val passed = provider.getLineDetail(
            city,
            LineDetailRequest(lineId = "ms:1_1_0", targetOrder = 1)
        )
        assertEquals(-1, passed.buses[0].etaSeconds)
        assertEquals(null, passed.buses[0].distanceToTargetMeters)
    }

    @Test
    fun lineDetail_eta_usesMeasuredSpeedOverFallback() = runBlocking {
        var now = 0L
        val backend = MockBackend()
        backend.byIdBsd = 36.0 // 服务端下发 36 km/h
        val provider = MeishanTransitProvider(
            client = MeishanTransitClient(backend.httpClient),
            wallClock = fixedClock,
            monotonicClock = { now }
        )
        // ≈2206m ÷ (36/3.6 = 10 m/s) ≈ 221s（4 m/s 兜底会是 ~550s，完全不同）
        val detail = provider.getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        assertEquals((assertNotNull(detail.buses[0].distanceToTargetMeters) / 10.0).roundToInt(), detail.buses[0].etaSeconds)
    }

    @Test
    fun nearbyStops_buildPerStopLineStatus_withStopCount() = runBlocking {
        val stops = newProvider(MockBackend()).getNearbyStops(city, 30.068, 103.819, limit = 4)
        assertEquals(2, stops.size)
        // 服务端按 dist 升序：紫竹西街（53m）在前
        assertEquals("紫竹西街", stops[0].name) // "_跑法2" 调度别名已清洗
        assertEquals(53, stops[0].distanceMeters)
        assertEquals("ms-st:2", stops[0].stopId)

        val status = stops[0].lines.single()
        assertEquals("1路", status.line.name)
        assertEquals(2, status.targetOrder)
        // 车在本站后方一站：还有 1 站（05 §1.2 的下标做差）
        assertEquals("下一站就到", status.statusText)
        assertEquals(1, status.approachingBuses.size)
        assertEquals("川Z10091D", status.approachingBuses[0].plateOrFleetNo)
    }

    @Test
    fun stopDetail_resolvesFromEncodedStopId() = runBlocking {
        val provider = newProvider(MockBackend())
        assertTrue(provider.supportsStop(StopDetailRequest(stopId = "ms-st:2")))
        val stops = provider.getStopDetail(city, StopDetailRequest(stopId = "ms-st:2"))
        assertEquals(1, stops.size)
        assertEquals("紫竹西街", stops[0].name) // 站名从 siteList 里找到并清洗
        assertEquals("下一站就到", stops[0].lines.single().statusText)
        // 第三方前缀不认
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "12345")))
    }

    @Test
    fun stopDetail_retriesTransientFailure() = runBlocking {
        val backend = MockBackend()
        backend.siteFailuresBeforeSuccess = 1 // 第一次业务失败，第二次成功
        val stops = newProvider(backend).getStopDetail(city, StopDetailRequest(stopId = "ms-st:2"))

        assertEquals(2, backend.hits["site"], "失败后应重试一次")
        assertEquals(1, stops.size, "重试后应正常返回，而不是整页「暂无运营车辆」")
        assertEquals("下一站就到", stops[0].lines.single().statusText)
    }

    @Test
    fun lineDetail_filtersYardBuses_indexZero() = runBlocking {
        // 场站车（index=0、isArriveSite=1）不属于任何站：不能出现在车辆列表，
        // 否则「最近的车」筛选（order <= 目标站）会把它当成目标站上的「已到站」
        val backend = MockBackend()
        backend.withYardBuses = true
        val detail = newProvider(backend).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))

        assertEquals(1, detail.buses.size, "场站车应被过滤")
        assertEquals("川Z10091D", detail.buses.single().plateOrFleetNo)
        assertTrue(detail.buses.none { it.order < 1 })
    }

    @Test
    fun lineDetail_offHours_returnsNoBusesAndWaitingState() = runBlocking {
        // 未发车时段（首班 19:30 > 当前 18:00）：服务端仍返回归场车快照，但它们不是在途车
        val backend = MockBackend()
        backend.byIdFirstLast = "19:30" to "22:00"
        backend.withYardBuses = true
        val detail = newProvider(backend).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))

        assertEquals(LineOperatingState.WAITING, detail.operatingState)
        assertTrue(detail.buses.isEmpty(), "非运营时段车辆列表应为空，不能把归场车当在途")
    }

    @Test
    fun stopStatuses_offHours_showsWaitingInsteadOfArrived() = runBlocking {
        // 车停在本站（isArriveSite=1）但还没到首班：应显示「等待发车」而不是「已到站」
        val backend = MockBackend()
        backend.siteFirstLast = "19:30" to "22:00"
        backend.siteBusAtStop = true
        val stops = newProvider(backend).getStopDetail(city, StopDetailRequest(stopId = "ms-st:2"))

        assertEquals("等待发车", stops[0].lines.single().statusText)
    }

    @Test
    fun lineDetail_parkedBus_filteredAfterFiveStillMinutes() = runBlocking {
        // 运营时段内无下发速度、位置不动：停站 / 等灯（几十秒到两三分钟）不剔除，静止满 5 分钟才判停场（06 §4.1）
        var now = 0L
        val backend = MockBackend()
        backend.byIdLatOverride = "30.065" // 每帧位置不变
        val provider = MeishanTransitProvider(
            client = MeishanTransitClient(backend.httpClient),
            wallClock = fixedClock,
            monotonicClock = { now }
        )
        val request = LineDetailRequest(lineId = "ms:1_1_0")

        repeat(29) { // 10 秒一帧，共 290 秒：还没满 5 分钟，车辆仍在列表
            assertEquals(1, provider.getLineDetail(city, request).buses.size, "静止 ${it * 10} 秒不应剔除")
            now += 10_000
        }
        now += 20_000 // 静止 310 秒
        assertTrue(provider.getLineDetail(city, request).buses.isEmpty(), "静止满 5 分钟应判停场")
    }

    @Test
    fun lineDetail_afterLastDeparture_keepsLastBusesStillRunning() = runBlocking {
        // 末班 19:00 是始发站发车时刻：19:20 末班车还在路上，不能直接显示「今天没车了」
        val backend = MockBackend()
        val clock = FakeClock()
        clock.setWallClock("2026-11-04T11:20:00Z") // 北京时间 19:20
        val detail = newProvider(backend, clock).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        assertEquals(LineOperatingState.RUNNING, detail.operatingState)
        assertEquals("川Z10091D", detail.buses.single().plateOrFleetNo)
    }

    @Test
    fun lineDetail_afterLastDeparture_dropsBusesThatFinishedOrStayAtOrigin() = runBlocking {
        val clock = FakeClock()
        clock.setWallClock("2026-11-04T11:20:00Z") // 北京时间 19:20，末班 19:00 已发出 20 分钟
        val request = LineDetailRequest(lineId = "ms:1_1_0")

        // 车停在终点站：末班车跑完了，今天收车
        val atTerminal = MockBackend().apply { byIdBusIndex = 3; byIdBusArrived = 1 }
        val finished = newProvider(atTerminal, clock).getLineDetail(city, request)
        assertTrue(finished.buses.isEmpty(), "已到终点站的末班车跑完了")
        assertEquals(LineOperatingState.ENDED, finished.operatingState, "车都跑完了才算今天收车")

        // 车停在始发站：末班发出 20 分钟还没走，不会再发
        val atOrigin = MockBackend().apply { byIdBusIndex = 1; byIdBusArrived = 1 }
        val origin = newProvider(atOrigin, clock).getLineDetail(city, request)
        assertTrue(origin.buses.isEmpty())
        assertEquals(LineOperatingState.ENDED, origin.operatingState)
    }

    @Test
    fun lineDetail_lastBusGrace_endsAfterTwoHours() = runBlocking {
        val clock = FakeClock()
        clock.setWallClock("2026-11-04T13:01:00Z") // 北京时间 21:01，末班 19:00 已过 2 小时
        val detail = newProvider(MockBackend(), clock).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        assertEquals(LineOperatingState.ENDED, detail.operatingState)
        assertTrue(detail.buses.isEmpty(), "宽限期过后不再把车辆当末班车")
    }

    @Test
    fun stopStatuses_afterLastDeparture_showsLastBusStillComing() = runBlocking {
        val clock = FakeClock()
        clock.setWallClock("2026-11-04T11:20:00Z") // 北京时间 19:20，末班 19:00
        val stops = newProvider(MockBackend(), clock).getStopDetail(city, StopDetailRequest(stopId = "ms-st:2"))
        assertEquals("下一站就到", stops[0].lines.single().statusText, "末班车还没到本站：照样显示站数")
    }

    @Test
    fun resolveCityByLocation_nearestCenterWithinRadius() = runBlocking {
        val provider = newProvider(MockBackend())
        assertEquals("眉山", provider.resolveCityByLocation(30.06, 103.82))
        assertEquals("仁寿", provider.resolveCityByLocation(30.0, 104.14))
        assertEquals(null, provider.resolveCityByLocation(39.9, 116.4)) // 北京：不在服务区
    }

    @Test
    fun lineDetail_protocolFailures_areTyped() = runBlocking {
        val malformedBackend = MockBackend().apply { detailResponse = "not-json" }
        val malformed = runCatching {
            newProvider(malformedBackend).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        }.exceptionOrNull()
        assertTrue(malformed is TransitProtocolFailure)

        val mismatchBackend = MockBackend().apply {
            detailResponse = lineDetailJson(hit = 1).replace("\"l_id\":1", "\"l_id\":2")
        }
        val mismatch = runCatching {
            newProvider(mismatchBackend).getLineDetail(city, LineDetailRequest(lineId = "ms:1_1_0"))
        }.exceptionOrNull()
        assertTrue(mismatch is TransitProtocolFailure)
    }

    @Test
    fun speed_reportedBsdPreferred_deltaAsFallback() = runBlocking {
        var now = 0L
        val backend = MockBackend()
        val provider = MeishanTransitProvider(
            client = MeishanTransitClient(backend.httpClient),
            wallClock = fixedClock,
            monotonicClock = { now }
        )
        val request = LineDetailRequest(lineId = "ms:1_1_0")

        // b_sd 无效（0，可能是停运时段没填）+ 无上一帧：速度 0
        assertEquals(0.0, provider.getLineDetail(city, request).buses[0].speedKmh)

        // b_sd 仍无效，但 3 秒里车挪了约 30 米 → 用相邻帧位移兜底出约 36 km/h
        now = 3_000L
        val fallback = provider.getLineDetail(city, request).buses[0].speedKmh
        assertTrue(fallback in 10.0..70.0, "位移兜底应算出合理速度，实际 $fallback")

        // b_sd 有效（运营时段服务端下发）时优先用字段，不走位移估算
        backend.byIdBsd = 25.0
        now = 6_000L
        assertEquals(25.0, provider.getLineDetail(city, request).buses[0].speedKmh)

        // 时间差过大（>120s）且 b_sd 无效：放弃估算，回 0
        backend.byIdBsd = 0.0
        now = 300_000L
        assertEquals(0.0, provider.getLineDetail(city, request).buses[0].speedKmh)
    }
}
