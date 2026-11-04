package com.trackr.core.network.provider.meishan

import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

private val stopLinesJson = """
    {"code":101,"result":[
      {"l_id":1,"l_name":"1路","l_copy_id":11,"state":0,
       "l_sxpz":"眉山东站:千禾味业","l_xxpz":"千禾味业:眉山东站",
       "start_time":"06:30","end_time":"19:00",
       "xx_start_time":"06:30","xx_end_time":"19:00","areaId":1,
       "siteList":[
         {"s_id":1,"s_name":"眉山东站","latitude":30.06,"longitude":103.82,"index":1},
         {"s_id":2,"s_name":"紫竹西街._跑法2","latitude":30.07,"longitude":103.83,"index":2},
         {"s_id":3,"s_name":"千禾味业","latitude":30.08,"longitude":103.84,"index":3}
       ],
       "busList":[
         {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10091D","index":1,"state":0,
          "isArriveSite":0,"b_latitude":30.062,"b_longitude":103.821,"b_sd":0.0,"areaId":1}
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

/** 线路详情的车辆位置按命中次数前移，模拟车辆在 3 秒里走了约 30 米（≈36 km/h） */
private fun lineDetailJson(hit: Int): String {
    val lat = if (hit <= 1) "30.065" else "30.06527"
    return """
    {"code":101,"result":{
      "l_id":1,"l_name":"1路","l_copy_id":11,"state":0,
      "l_sxpz":"眉山东站:千禾味业","l_xxpz":"千禾味业:眉山东站",
      "start_time":"06:30","end_time":"19:00",
      "xx_start_time":"06:30","xx_end_time":"19:00",
      "l_remark":"票价 1 元",
      "siteList":[
        {"s_id":1,"s_name":"眉山东站","latitude":30.06,"longitude":103.82,"index":1},
        {"s_id":2,"s_name":"紫竹西街","latitude":30.07,"longitude":103.83,"index":2},
        {"s_id":3,"s_name":"千禾味业","latitude":30.08,"longitude":103.84,"index":3}
      ],
      "busList":[
        {"b_id":0,"l_copy_id":11,"b_plate_num":"川Z10091D","index":2,"state":0,
         "isArriveSite":0,"b_latitude":$lat,"b_longitude":103.825,"b_sd":0.0,"areaId":1}
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

        val httpClient = mockHttpClient { request ->
            val path = request.encodedPath.substringAfterLast('/')
            hits[path] = (hits[path] ?: 0) + 1
            when (path) {
                "list" -> MockReply(lineListJson)
                "byId" -> MockReply(lineDetailJson(++byIdHits))
                "site" -> MockReply(stopLinesJson)
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
        assertEquals("票价 1 元", detail.statusDescription)
        assertEquals("ms:1_1_1", detail.reverseDirection?.lineId)
        assertEquals(1, detail.buses.size)
        assertEquals("川Z10091D", detail.buses[0].plateOrFleetNo)
        // 首帧没有上一帧可比：速度为 0（b_sd 恒 0，不采用）
        assertEquals(0.0, detail.buses[0].speedKmh)
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
        assertEquals("1站", status.statusText)
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
        assertEquals("1站", stops[0].lines.single().statusText)
        // 第三方前缀不认
        assertFalse(provider.supportsStop(StopDetailRequest(stopId = "12345")))
    }

    @Test
    fun resolveCityByLocation_nearestCenterWithinRadius() = runBlocking {
        val provider = newProvider(MockBackend())
        assertEquals("眉山", provider.resolveCityByLocation(30.06, 103.82))
        assertEquals("仁寿", provider.resolveCityByLocation(30.0, 104.14))
        assertEquals(null, provider.resolveCityByLocation(39.9, 116.4)) // 北京：不在服务区
    }

    @Test
    fun speed_computedFromConsecutiveFrames() = runBlocking {
        var now = 0L
        val backend = MockBackend()
        val provider = MeishanTransitProvider(
            client = MeishanTransitClient(backend.httpClient),
            wallClock = fixedClock,
            monotonicClock = { now }
        )
        val request = LineDetailRequest(lineId = "ms:1_1_0")

        // 第一帧：无上一帧可比，速度 0
        assertEquals(0.0, provider.getLineDetail(city, request).buses[0].speedKmh)

        // 3 秒后车挪了约 30 米 → 约 36 km/h；b_sd 仍是 0，不采用
        now = 3_000L
        val speed = provider.getLineDetail(city, request).buses[0].speedKmh
        assertTrue(speed in 10.0..70.0, "相邻帧位移应算出合理速度，实际 $speed")

        // 时间差过大（>120s）：放弃计算，回 0
        now = 300_000L
        assertEquals(0.0, provider.getLineDetail(city, request).buses[0].speedKmh)
    }
}
