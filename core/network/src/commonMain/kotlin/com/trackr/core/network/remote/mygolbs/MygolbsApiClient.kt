package com.trackr.core.network.remote.mygolbs

import com.trackr.core.domain.arrival.StopOrderResolver
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TimetableSegment
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.common.BaseTransitApiClient
import com.trackr.core.network.remote.common.ServiceHours
import com.trackr.core.network.remote.common.SingleFlightCache
import com.trackr.core.network.remote.common.TimetableRules
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.formUrlEncode
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * 掌上公交（Mygolbs）全国实时公交直连 API 客户端
 * 基于 H5 数据面协议实现，严格携带 Origin 与 Referer 头，支持纯本地直连
 */
class MygolbsApiClient(
    httpClient: HttpClient,
    /** 单调时钟：各级短缓存和逆地理复用窗口；测试可注入 */
    private val clock: MonotonicClock = ProcessMonotonicClock,
    /** 墙钟：线路详情的更新时刻；测试可注入 */
    private val wallClock: Clock = Clock.System
) : BaseTransitApiClient(httpClient) {

    companion object {
        const val API_URL = "https://h5.mygolbs.com/ApiData.do"
        const val ORIGIN = "https://h5.mygolbs.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val PRICE_REGEX = Regex("""票价[:：]\s*([^\s，,]+)""")

        /** CMD=103 站表 / 首末班 / 票价等静态数据缓存时长 */
        const val ROUTE_STATIC_TTL_MS = 12 * 60 * 60 * 1000L

        /** 附近站 CMD=105 短缓存时长 */
        const val NEARBY_STOP_LINES_TTL_MS = 20_000L

        /** 搜索降级 CMD=102 结果共享时长 */
        const val SEARCH_FALLBACK_TTL_MS = 10_000L

        /** 逆地理结果（含 null）复用时长 */
        const val REVERSE_GEOCODE_TTL_MS = 30_000L

        /** 附近站 CMD=105 并发上限 */
        const val MAX_CONCURRENT_STOP_LINES = 4

        /** 发车时刻表 CMD=207 短缓存：同一线路方向 30 秒内复用 */
        const val TIMETABLE_TTL_MS = 30_000L

        private val BEIJING = TimeZone.of("Asia/Shanghai")

        /** 时刻表拿不到时的固定说法（和改造前一致，避免 UI 文案漂移） */
        const val TIMETABLE_FALLBACK = "该线路暂不提供首站预测时刻表，请参考运营首末班"
        const val TIMETABLE_HISTORY_DESC = "本时刻表数据根据历史数据计算得出，仅供参考"
        const val TIMETABLE_PLANNED_DESC = "本时刻表为计划发车时间，可能临时调整，仅供参考"

        private val DEFAULT_CITIES = mapOf(
            "北京市" to "110000",
            "上海市" to "310000",
            "广州市" to "440100",
            "深圳市" to "440300",
            "成都市" to "510100",
            "重庆市" to "500000",
            "武汉市" to "420100",
            "南京市" to "320100",
            "福州市" to "350100",
            "贵阳市" to "520100",
            "玉林市" to "450900",
            "北流市" to "450981",
            "南宁市" to "450100",
            "柳州市" to "450200",
            "桂林市" to "450300"
        )

        /**
         * 将任意城市名规范化为掌上公交接收的带行政区划名称（如“福州” -> “福州市”）
         */
        fun normalizeCityName(cityName: String, knownCityNames: Set<String> = DEFAULT_CITIES.keys): String {
            val trimmed = cityName.trim()
            if (trimmed.isEmpty()) return ""
            if (knownCityNames.contains(trimmed)) return trimmed
            val withShi = if (!trimmed.endsWith("市") && !trimmed.endsWith("县") && !trimmed.endsWith("区")) "${trimmed}市" else trimmed
            if (knownCityNames.contains(withShi)) return withShi
            val found = knownCityNames.firstOrNull { it.startsWith(trimmed) || trimmed.startsWith(it.removeSuffix("市")) }
            if (found != null) return found
            return withShi
        }
    }

    private val cityNamesLock = SynchronizedObject()

    /** CMD=101 拉到的城市名（含内置兜底），受 [cityNamesLock] 保护 */
    private val cachedCityNames = HashSet<String>(DEFAULT_CITIES.keys)

    private fun addCityName(name: String) {
        synchronized(cityNamesLock) { cachedCityNames.add(name) }
    }

    fun normalizeCityName(cityName: String): String {
        val snapshot = synchronized(cityNamesLock) { cachedCityNames.toSet() }
        return normalizeCityName(cityName, snapshot)
    }

    /**
     * CMD=103 解析出的线路静态信息
     */
    private data class RouteStatic(
        val routeId: String,
        val price: String,
        val firstTime: String,
        val lastTime: String,
        val stops: List<BusStop>
    )

    /** CMD=207 解析出的原始时刻表；nextDeparture 在每次调用时按参考时间重算，所以只缓存原料 */
    private data class TimetableRaw(
        val departures: List<String>,
        val segments: List<TimetableSegment>,
        val nextTime: String?,
        val special: String
    )

    /** CMD=103 静态数据缓存（12 小时），轮询时只调 CMD=104 */
    private val routeStaticCache = SingleFlightCache<String, RouteStatic>(
        ttlMillis = ROUTE_STATIC_TTL_MS,
        maxEntries = 128,
        clock = clock
    )

    /** CMD=207 发车时刻表短缓存（30 秒），避免时刻表弹层和「没车回落」各打一次 */
    private val timetableCache = SingleFlightCache<String, TimetableRaw>(
        ttlMillis = TIMETABLE_TTL_MS,
        maxEntries = 64,
        clock = clock
    )

    /** 附近站 CMD=105 短缓存（只用于附近站，站点详情页仍每轮实时查询） */
    private val nearbyStopLinesCache = SingleFlightCache<String, NearbyStop>(
        ttlMillis = NEARBY_STOP_LINES_TTL_MS,
        maxEntries = 64,
        clock = clock
    )
    private val stopLinesSemaphore = Semaphore(MAX_CONCURRENT_STOP_LINES)

    /** 站点详情核对站序时补查 CMD=103 的并发上限 */
    private val routeStaticSemaphore = Semaphore(MAX_CONCURRENT_STOP_LINES)

    /** CMD=114 / CMD=110 失败时共用的 CMD=102 降级结果 */
    private val searchFallbackCache = SingleFlightCache<String, JSONObject>(
        ttlMillis = SEARCH_FALLBACK_TTL_MS,
        maxEntries = 16,
        clock = clock
    )

    private data class ReverseGeocodeCacheEntry(
        val lat: Double,
        val lng: Double,
        val atMillis: Long,
        val cityName: String?
    )

    @Volatile
    private var lastReverseGeocode: ReverseGeocodeCacheEntry? = null

    private suspend fun callApi(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val json = executeJson {
            method = HttpMethod.Post
            url(API_URL)
            header("Origin", ORIGIN)
            header("Referer", "$ORIGIN/")
            header("User-Agent", USER_AGENT)
            // 表单体；Content-Type 由 TextContent 给出，和原来一样不带 charset
            setBody(TextContent(params.toList().formUrlEncode(), ContentType.Application.FormUrlEncoded))
        }
        val status = json.opt("status")?.toString()?.trim('"')
        if (status != "1") {
            val msg = json.optString("msg")
            throw IOException("Mygolbs upstream error status=$status msg=$msg")
        }
        json
    }

    /**
     * 查询掌上公交支持的城市列表（CMD=101，返回 城市名 -> citycode 映射）
     */
    suspend fun getCities(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>(DEFAULT_CITIES)
        try {
            val json = callApi(mapOf("CMD" to "101"))
            val dataArr = json.optJSONArray("data") ?: json.optJSONArray("list")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val name = item.optString("cityname")
                        .ifBlank { item.optString("cityName") }
                        .ifBlank { item.optString("CITYNAME") }
                    val code = item.optString("cityCode")
                        .ifBlank { item.optString("CITYCODE") }
                        .ifBlank { item.optString("code") }
                        .ifBlank { "0" }
                    if (name.isNotBlank()) {
                        result[name] = code
                        addCityName(name)
                    }
                }
            }
        } catch (_: Exception) {
            // 异常时沿用内置兜底映射
        }
        result
    }

    /**
     * CMD=102 综合搜索降级：线路搜索与站点搜索同时失败时共享同一次请求结果
     */
    private suspend fun searchFallback(normalizedCity: String, keyword: String): JSONObject {
        return searchFallbackCache.get("$normalizedCity|$keyword") {
            callApi(
                mapOf(
                    "CMD" to "102",
                    "CITYNAME" to normalizedCity,
                    "KEYWORD" to keyword,
                    "CITYKEY" to ""
                )
            )
        }
    }

    /**
     * 按关键字搜索线路（CMD=114 / CMD=102）
     * 为每条匹配线路生成正向与反向两个方向（lineId 为 mg:${cityCode}:${cityName}:${lineName}:1 与 :2）
     */
    suspend fun searchLines(cityCode: String = "", cityName: String, keyword: String): List<BusLineSummary> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val results = ArrayList<BusLineSummary>()
        try {
            val json = try {
                callApi(
                    mapOf(
                        "CMD" to "114",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                searchFallback(normalizedCity, keyword)
            }

            val dataArr = json.optJSONArray("buslines")
                ?: json.optJSONArray("data")
                ?: json.optJSONArray("list")
                ?: JSONArray()
            val lineMap = LinkedHashMap<String, MutableList<BusLineDirection>>()
            val routeBeanByLine = LinkedHashMap<String, JSONObject>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val lineName = item.optString("lineName").ifBlank { item.optString("LINENAME") }
                if (lineName.isBlank()) continue

                val upperOrDown = item.optString("upperOrDown").ifBlank { "1" }
                val startStation = item.optString("from")
                    .ifBlank { item.optString("startStation") }
                    .ifBlank { item.optString("firstStation") }
                val endStation = item.optString("to")
                    .ifBlank { item.optString("endStation") }
                    .ifBlank { item.optString("lastStation") }
                val price = item.optString("comments")
                val routeBean = item.optJSONObject("routeBean")
                val isSubway = routeBean?.optString("routeType") == "3" || lineName.contains("地铁")
                if (isSubway) continue // 过滤纯地铁线路
                if (routeBean != null) routeBeanByLine[lineName] = routeBean

                // 首末班按方向取：上行用 seutimes，下行用 sedtimes；以前一律写 su/eu，反向会显示错的方向时段
                val windows = serviceWindows(routeBean, upperOrDown)
                val firstTime = windows.firstOrNull()?.first
                    ?: routeBean?.optString(if (upperOrDown == "1") "suTime" else "sdTime").orEmpty()
                val lastTime = windows.lastOrNull()?.last
                    ?: routeBean?.optString(if (upperOrDown == "1") "euTime" else "edTime").orEmpty()
                val dirInt = if (upperOrDown == "1") 0 else 1

                val dir = BusLineDirection(
                    lineId = "mg:$cityCode:$normalizedCity:$lineName:$upperOrDown",
                    name = lineName,
                    lineNo = lineName,
                    direction = dirInt,
                    startStop = startStation,
                    endStop = endStation,
                    firstTime = firstTime,
                    lastTime = lastTime,
                    price = price,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityCode = cityCode,
                    mygolbsCityName = normalizedCity
                )

                lineMap.getOrPut(lineName) { mutableListOf() }.add(dir)
            }

            for ((lineName, dirs) in lineMap) {
                val effectiveDirs = if (dirs.size == 1) {
                    val d1 = dirs[0]
                    val revUpperOrDown = if (d1.direction == 0) "2" else "1"
                    // 反向的首末班从同一 routeBean 的另一方向数组取，别直接抄正向
                    val revWindows = serviceWindows(routeBeanByLine[lineName], revUpperOrDown)
                    val revDir = BusLineDirection(
                        lineId = "mg:$cityCode:$normalizedCity:$lineName:$revUpperOrDown",
                        name = lineName,
                        lineNo = lineName,
                        direction = if (d1.direction == 0) 1 else 0,
                        startStop = d1.endStop,
                        endStop = d1.startStop,
                        firstTime = revWindows.firstOrNull()?.first ?: d1.firstTime,
                        lastTime = revWindows.lastOrNull()?.last ?: d1.lastTime,
                        price = d1.price,
                        source = DataSourceType.MYGOLBS,
                        mygolbsCityCode = cityCode,
                        mygolbsCityName = normalizedCity
                    )
                    listOf(d1, revDir)
                } else {
                    dirs.sortedBy { it.direction }
                }

                results.add(
                    BusLineSummary(
                        name = lineName,
                        lineNo = lineName,
                        isSubway = false,
                        directions = effectiveDirs,
                        source = DataSourceType.MYGOLBS
                    )
                )
            }
        } catch (_: Exception) {
            // 搜索失败返回空列表
        }
        results
    }

    /**
     * 按关键字搜索公交站点（CMD=110 / CMD=102）
     */
    suspend fun searchStops(cityName: String, keyword: String): List<NearbyStop> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val results = ArrayList<NearbyStop>()
        try {
            val json = try {
                callApi(
                    mapOf(
                        "CMD" to "110",
                        "CITYNAME" to normalizedCity,
                        "KEYWORD" to keyword,
                        "CITYKEY" to ""
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                searchFallback(normalizedCity, keyword)
            }

            val dataArr = json.optJSONArray("busstations") ?: JSONArray()
            val seenStops = HashSet<String>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val stationName = item.optString("stationName").trim()
                if (stationName.isBlank() || !seenStops.add(stationName)) continue
                if (isSubwayStationName(stationName)) continue // 过滤纯地铁站（保留名称以地铁口命名的地面公交站）

                val lat = item.optDouble("station_lat", 0.0)
                val lng = item.optDouble("station_lon", 0.0)
                val stopId = "mg:$normalizedCity:$stationName"

                results.add(
                    NearbyStop(
                        stopId = stopId,
                        name = stationName,
                        wgsLat = lat,
                        wgsLng = lng,
                        physicalStId = stopId,
                        namesakeStId = stopId,
                        source = DataSourceType.MYGOLBS
                    )
                )
            }
        } catch (_: Exception) {
            // 搜索失败返回空列表
        }
        results
    }

    /**
     * 根据站点名获取停靠该站点的所有线路及实时状态（CMD=105）
     */
    suspend fun getStopLines(cityName: String, stationName: String): NearbyStop = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        val cleanStation = stationName.trim()
        try {
            // 站点详情页是开始追踪 / 一起等的入口：站序必须按站表核对过（issue #41）
            fetchStopLines(normalizedCity, cleanStation, verifyOrders = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyStopLines(normalizedCity, cleanStation)
        }
    }

    private fun emptyStopLines(normalizedCity: String, cleanStation: String): NearbyStop {
        val stopId = "mg:$normalizedCity:$cleanStation"
        return NearbyStop(
            stopId = stopId,
            name = cleanStation,
            physicalStId = stopId,
            namesakeStId = stopId,
            source = DataSourceType.MYGOLBS
        )
    }

    /**
     * 掌上公交的方向首末班时段：上行（1）用 `seutimes`，下行（2）用 `sedtimes`。
     * 这些字段和 CMD=103 的 `firstLast` 一致；没有时才退回 `su/eu` 或 `sd/ed`。
     */
    private fun serviceWindows(routeBean: JSONObject?, upperOrDown: String): List<ServiceHours.Window> {
        val key = if (upperOrDown == "1") "seutimes" else "sedtimes"
        val arr = routeBean?.optJSONArray(key)
        val windows = ArrayList<ServiceHours.Window>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val first = item.optString("first").trim()
                val last = item.optString("last").trim()
                if (first.isNotBlank() && last.isNotBlank()) {
                    windows.add(ServiceHours.Window(first, last))
                }
            }
        }
        if (windows.isNotEmpty()) return windows

        val fallbackFirst = routeBean
            ?.optString(if (upperOrDown == "1") "suTime" else "sdTime")
            .orEmpty()
            .trim()
        val fallbackLast = routeBean
            ?.optString(if (upperOrDown == "1") "euTime" else "edTime")
            .orEmpty()
            .trim()
        return if (fallbackFirst.isNotBlank() && fallbackLast.isNotBlank()) {
            listOf(ServiceHours.Window(fallbackFirst, fallbackLast))
        } else {
            emptyList()
        }
    }

    /**
     * 附近站专用：CMD=105 结果 20 秒内复用，并限制并发；失败时不缓存，返回空线路站点
     */
    private suspend fun getStopLinesForNearby(normalizedCity: String, stationName: String): NearbyStop {
        val cleanStation = stationName.trim()
        return try {
            nearbyStopLinesCache.get("$normalizedCity|$cleanStation") {
                // 附近站一次查很多站，不为核对站序逐条线补查站表，只用已缓存的站表核对
                stopLinesSemaphore.withPermit { fetchStopLines(normalizedCity, cleanStation, verifyOrders = false) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyStopLines(normalizedCity, cleanStation)
        }
    }

    /**
     * CMD=105 请求与解析；网络或上游错误直接抛出，由调用方决定兜底
     */
    private suspend fun fetchStopLines(
        normalizedCity: String,
        cleanStation: String,
        verifyOrders: Boolean
    ): NearbyStop {
        val stopId = "mg:$normalizedCity:$cleanStation"
        val nowHHMM = beijingNowHHMM()
        val json = callApi(
            mapOf(
                "CMD" to "105",
                "CITYNAME" to normalizedCity,
                "STATIONNAME" to cleanStation,
                "MYLAT" to "",
                "MYLNG" to "",
                "CITYKEY" to ""
            )
        )

        val dataArr = json.optJSONArray("data") ?: JSONArray()
        val lineStatuses = ArrayList<NearbyStopLineStatus>()
        for (i in 0 until dataArr.length()) {
            val item = dataArr.optJSONObject(i) ?: continue
            val lineName = item.optString("lineName").ifBlank { item.optString("routeName") }
            if (lineName.isBlank()) continue

            val upperOrDown = item.optString("upperOrDown").ifBlank { "1" }
            val from = item.optString("from")
            val to = item.optString("to")
            val comments = item.optString("comments")
            val neartext = item.optString("neartext")
            val neartime = item.optString("neartime")
            // CMD=105 的 stationOrder 恒为 0；能用的站序在 stationID：它是这条线在本站的序号（0 起，站序 = stationID + 1）。
            // 莆田、厦门、泉州、玉林都对得上 CMD=103 站表里的 order。
            val stationOrder = item.optInt("stationOrder", 0).takeIf { it > 0 }
                ?: item.optString("stationID").toIntOrNull()?.takeIf { it >= 0 }?.plus(1)
                ?: 0
            val routeBean = item.optJSONObject("routeBean")
            val isSubway = routeBean?.optString("routeType") == "3" || lineName.contains("地铁")
            if (isSubway) continue // 过滤纯地铁线路

            val windows = serviceWindows(routeBean, upperOrDown)
            val firstTime = windows.firstOrNull()?.first
                ?: routeBean?.optString(if (upperOrDown == "1") "suTime" else "sdTime").orEmpty()
            val lastTime = windows.lastOrNull()?.last
                ?: routeBean?.optString(if (upperOrDown == "1") "euTime" else "edTime").orEmpty()

            val lineDir = BusLineDirection(
                lineId = "mg:0:$normalizedCity:$lineName:$upperOrDown",
                name = lineName,
                lineNo = lineName,
                direction = if (upperOrDown == "1") 0 else 1,
                startStop = from,
                endStop = to,
                firstTime = firstTime,
                lastTime = lastTime,
                price = comments,
                source = DataSourceType.MYGOLBS,
                mygolbsCityName = normalizedCity
            )

            val hasReal = routeBean?.let { rb ->
                if (rb.has("hasReal")) rb.optInt("hasReal", 1) else 1
            } ?: 1

            // 上游 neartext 形如「7站 [开往 市检察院]」「1站 [到达 祥和山庄]」：方括号里是终点或下一站，
            // 首页卡片和站点行放不下，只留「7站」「即将到站」「已到站」「等待发车」（「2站到达」这类也去掉「到达」）。
            val nearTextClean = neartext
                .substringBefore('[')
                .substringBefore('【')
                .trim()
                .removeSuffix("到达")
                .trim()
            // 上游末班之后仍回「等待发车」；用同一方向的首末班时段判断，和车来了统一成「末班已过」
            val serviceState = ServiceHours.state(nowHHMM, windows)
            val statusText = when {
                hasReal == 0 -> "未开通实时"
                nearTextClean.isNotBlank() -> {
                    if (serviceState == ServiceHours.State.AFTER_LAST &&
                        (nearTextClean == "等待发车" || nearTextClean == "正在运营中")
                    ) {
                        "末班已过"
                    } else {
                        nearTextClean
                    }
                }
                neartime.isNotBlank() -> "预计 ${neartime.trim()} 到达"
                serviceState == ServiceHours.State.AFTER_LAST -> "末班已过"
                else -> "正在运营中"
            }

            lineStatuses.add(
                NearbyStopLineStatus(
                    line = lineDir,
                    targetOrder = stationOrder,
                    targetStopId = stopId,
                    targetStopName = cleanStation,
                    statusText = statusText,
                    preArrivalTime = if (hasReal == 0) null else neartime.takeIf { it.isNotBlank() }
                )
            )
        }

        val verified = coroutineScope {
            lineStatuses.map { status ->
                async { verifyStationOrder(status, normalizedCity, cleanStation, loadIfMissing = verifyOrders) }
            }.awaitAll()
        }

        return NearbyStop(
            stopId = stopId,
            name = cleanStation,
            physicalStId = stopId,
            namesakeStId = stopId,
            lines = verified,
            source = DataSourceType.MYGOLBS
        )
    }

    /**
     * CMD=105 的站序是拿 stationID 推的（stationID + 1）。有的城市 stationID 是站点编号而不是序号，
     * 推出来的站序比全线站数还大，追踪和通知里就成了「还有 9xx 站」（issue #41）。
     * 用 CMD=103 站表按站名核对：站名对得上就用站表里的站序；对不上、推出来的站序又不在站表里时清成 0（站序未知）。
     *
     * @param loadIfMissing 站表没缓存时要不要现查（12 小时缓存，和线路详情共用）；false 时只用已缓存的站表
     */
    private suspend fun verifyStationOrder(
        status: NearbyStopLineStatus,
        normalizedCity: String,
        cleanStation: String,
        loadIfMissing: Boolean
    ): NearbyStopLineStatus {
        val ref = MygolbsIds.parseLineId(status.line.lineId) ?: return status
        val key = routeStaticKey(ref.cityCode, normalizedCity, ref.lineName, ref.direction)
        val stops = routeStaticCache.getCached(key)?.stops
            ?: if (loadIfMissing) {
                try {
                    routeStaticSemaphore.withPermit {
                        loadRouteStatic(ref.cityCode, normalizedCity, ref.lineName, ref.direction)
                    }.stops
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        if (stops.isNullOrEmpty()) return status
        val resolved = StopOrderResolver.resolve(stops, status.targetOrder, cleanStation)
            ?: status.targetOrder.takeIf { StopOrderResolver.isInRange(stops, it) }
            ?: 0
        return if (resolved == status.targetOrder) status else status.copy(targetOrder = resolved)
    }

    /**
     * 逆地理城市反查（CMD=108）：根据经纬度反查所在城市名
     */
    suspend fun reverseGeocodeCityName(wgsLat: Double, wgsLng: Double): String? {
        if (wgsLat <= 0.0 || wgsLng <= 0.0) return null
        val now = clock.elapsedMillis()
        lastReverseGeocode?.let { cached ->
            if (cached.lat == wgsLat && cached.lng == wgsLng && now - cached.atMillis in 0 until REVERSE_GEOCODE_TTL_MS) {
                return cached.cityName
            }
        }
        val result = fetchReverseGeocodeCityName(wgsLat, wgsLng)
        // null 结果也短时缓存，避免紧接着重复请求
        lastReverseGeocode = ReverseGeocodeCacheEntry(wgsLat, wgsLng, now, result)
        return result
    }

    private suspend fun fetchReverseGeocodeCityName(wgsLat: Double, wgsLng: Double): String? = withContext(Dispatchers.IO) {
        try {
            val json = callApi(
                mapOf(
                    "CMD" to "108",
                    "LAT" to wgsLat.toString(),
                    "LNG" to wgsLng.toString(),
                    "MYLAT" to wgsLat.toString(),
                    "MYLNG" to wgsLng.toString()
                )
            )
            val cityName = json.optString("msg").trim()
            if (cityName.isNotBlank() && cityName != "error") {
                cityName
            } else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 探测周边公交站点（CMD=106）：根据经纬度获取附近站点列表并并发拉取实时到站
     */
    suspend fun getNearbyStops(
        cityName: String,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val normalizedCity = normalizeCityName(cityName)
        try {
            val json = callApi(
                mapOf(
                    "CMD" to "106",
                    "CITYNAME" to normalizedCity,
                    "LAT" to wgsLat.toString(),
                    "LNG" to wgsLng.toString(),
                    "MYLAT" to wgsLat.toString(),
                    "MYLNG" to wgsLng.toString()
                )
            )
            val dataArr = json.optJSONArray("data") ?: return@withContext emptyList()
            val rawStationList = ArrayList<Triple<String, Double, Double>>() // name, lat, lng
            val distMap = HashMap<String, Int>()

            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                // 过滤纯地铁站点
                if (isSubwayStationName(name)) continue

                val lat = item.optDouble("lat", 0.0)
                val lng = item.optDouble("lon", 0.0)
                val dis = item.optInt("dis", 0)

                rawStationList.add(Triple(name, lat, lng))
                val resolvedDist = resolveDistance(dis.takeIf { it > 0 }, wgsLat, wgsLng, lat, lng)
                if (resolvedDist != null) {
                    distMap[name] = resolvedDist
                }
                if (rawStationList.size >= limit) break
            }

            if (rawStationList.isEmpty()) {
                return@withContext emptyList()
            }

            // 并发调用 CMD=105 补齐停靠线路与实时在途数据（20 秒短缓存 + 并发上限）
            coroutineScope {
                rawStationList.map { (name, lat, lng) ->
                    async {
                        val stop = getStopLinesForNearby(normalizedCity, name)
                        stop.copy(
                            wgsLat = if (lat > 0.0) lat else stop.wgsLat,
                            wgsLng = if (lng > 0.0) lng else stop.wgsLng,
                            distanceMeters = distMap[name] ?: stop.distanceMeters,
                            source = DataSourceType.MYGOLBS
                        )
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * CMD=103：站表、首末班与票价等静态数据
     */
    private suspend fun fetchRouteStatic(
        cityCode: String,
        cityName: String,
        lineName: String,
        dirStr: String
    ): RouteStatic {
        val routeJson = callApi(
            mapOf(
                "CMD" to "103",
                "CITYNAME" to cityName,
                "CITYCODE" to cityCode,
                "LINENAME" to lineName,
                "DIRECTION" to dirStr,
                "CITYKEY" to ""
            )
        )

        val commonts = routeJson.optString("commonts")
        val priceMatch = PRICE_REGEX.find(commonts)
        val price = priceMatch?.groupValues?.getOrNull(1) ?: ""

        val firstLastArr = routeJson.optJSONArray("firstLast")
        val firstTime = firstLastArr?.optJSONObject(0)?.optString("first")
            ?: routeJson.optString("beginTime")
        val lastTime = firstLastArr?.optJSONObject(0)?.optString("last")
            ?: routeJson.optString("endTime")

        val dataArr = routeJson.optJSONArray("data") ?: JSONArray()
        val stopsList = ArrayList<BusStop>()
        for (i in 0 until dataArr.length()) {
            val st = dataArr.getJSONObject(i)
            val stStatus = st.optInt("stationsStatus", 1)
            val statusNote = when (stStatus) {
                0 -> "临时不停靠"
                2 -> "只上不下"
                3 -> "只下不上"
                4 -> "临时停靠"
                5 -> "响应式停靠"
                6 -> "响铃式停靠"
                7 -> "招手即停"
                8 -> "大站车停靠"
                else -> null
            }
            val order = st.optInt("stationOrder", i + 1)
            stopsList.add(
                BusStop(
                    stopId = order.toString(),
                    name = st.optString("stationName"),
                    order = order,
                    wgsLat = st.optDouble("station_lat", 0.0),
                    wgsLng = st.optDouble("station_lon", 0.0),
                    statusNote = statusNote
                )
            )
        }
        return RouteStatic(
            routeId = routeJson.optString("routeId"),
            price = price,
            firstTime = firstTime,
            lastTime = lastTime,
            stops = stopsList
        )
    }

    private fun routeStaticKey(cityCode: String, cityName: String, lineName: String, dirStr: String): String =
        "$cityCode|$cityName|$lineName|$dirStr"

    /** CMD=103 静态数据：线路详情和时刻表共用同一份 12 小时缓存 */
    private suspend fun loadRouteStatic(
        cityCode: String,
        cityName: String,
        lineName: String,
        dirStr: String
    ): RouteStatic {
        val key = routeStaticKey(cityCode, cityName, lineName, dirStr)
        return routeStaticCache.get(key) {
            fetchRouteStatic(cityCode, cityName, lineName, dirStr)
        }
    }

    /**
     * 获取掌上公交线路详情（解析 mg:${cityCode}:${cityName}:${lineName}:${dir}）
     * 调用 CMD=103 获取站表/首末班/票价，调用 CMD=104 获取在途车辆及到站时间，使用 RouteProjectionMath 计算站间比例
     */
    suspend fun getLineDetail(lineId: String, targetOrder: Int? = null): BusLineDetail = withContext(Dispatchers.IO) {
        val ref = MygolbsIds.parseLineId(lineId)
            ?: throw IllegalArgumentException("Invalid Mygolbs lineId: $lineId. Expected format: mg:cityCode:cityName:lineName:dir")
        val cityCode = ref.cityCode
        val cityName = normalizeCityName(ref.cityName)
        val lineName = ref.lineName
        val dirStr = ref.direction

        // 1. 获取站表与基础信息 (CMD=103)：静态数据按线路方向缓存 12 小时，轮询时只调 CMD=104
        val routeStaticKey = routeStaticKey(cityCode, cityName, lineName, dirStr)
        val routeStatic = loadRouteStatic(cityCode, cityName, lineName, dirStr)
        if (routeStatic.stops.isEmpty()) {
            // 空站表不缓存 12 小时，下次重新拉取
            routeStaticCache.invalidate(routeStaticKey)
        }
        val price = routeStatic.price
        val firstTime = routeStatic.firstTime
        val lastTime = routeStatic.lastTime
        val stopsList = routeStatic.stops

        val totalStops = stopsList.size
        val effectiveTargetOrder = if (targetOrder != null && targetOrder > 0) targetOrder else totalStops

        // 2. 获取实时在途车辆与到站估计 (CMD=104)
        val realtimeJson = callApi(
            mapOf(
                "CMD" to "104",
                "CITYNAME" to cityName,
                "CITYCODE" to cityCode,
                "LINENAME" to lineName,
                "DIRECTION" to dirStr,
                "STATIONORDER" to effectiveTargetOrder.toString(),
                "CITYKEY" to ""
            )
        )

        val runState = realtimeJson.optInt("runState", 0)
        val hasReal = realtimeJson.optInt("hasReal", 1)

        // CMD=104 顶层 planTime 是「起点预计发车」，对应车来了的 preArrivalTime。
        // 首页收藏卡「没车时显示下一班」靠的就是这个字段。
        val planTime = realtimeJson.optString("planTime").trim().takeIf { it.isNotBlank() }

        // 解析逐站到站预测 (routeOnStationRTimeInfoList)
        val onStationArr = realtimeJson.optJSONArray("routeOnStationRTimeInfoList") ?: JSONArray()
        val etaMap = HashMap<String, Double>() // busNumber -> 分钟
        val distanceMap = HashMap<String, Int>() // busNumber -> 到本站距离（米）
        for (i in 0 until onStationArr.length()) {
            val e = onStationArr.getJSONObject(i)
            val bNum = e.optString("busNumber")
            if (bNum.isNotBlank()) {
                etaMap[bNum] = e.optDouble("busToStationTime", 0.0)
                // 上游偶尔给 0；0 米没有展示价值，按缺省处理
                e.optInt("busToStationDistance", -1).takeIf { it > 0 }?.let { distanceMap[bNum] = it }
            }
        }

        // 解析全线车辆并进行站间比例计算
        val listArr = realtimeJson.optJSONArray("list") ?: JSONArray()
        val liveBuses = ArrayList<LiveBus>()
        for (i in 0 until listArr.length()) {
            val b = listArr.getJSONObject(i)
            val busNumber = b.optString("busNumber")
            val bIndex = b.optInt("index", 0)
            val bOrder = bIndex + 1
            val statusType = b.optString("statusType")
            val isArrived = (statusType == "0")
            val bLat = b.optDouble("bus_lat", 0.0)
            val bLng = b.optDouble("bus_lng", 0.0)
            // list 里的 stationName 就是这辆车当前所在 / 正开往的站，和车来了 nextStationName 对齐
            val nextStopName = b.optString("stationName").takeIf { it.isNotBlank() }

            val prevStop = stopsList.find { it.order == bOrder - 1 }
            val curTargetStop = stopsList.find { it.order == bOrder }

            val segmentProgress = RouteProjectionMath.computeSegmentProgress(
                busLat = bLat,
                busLng = bLng,
                isArrived = isArrived,
                prevStop = prevStop,
                targetStop = curTargetStop
            )

            val etaMin = etaMap[busNumber]
            val etaSeconds = when {
                etaMin != null && etaMin >= 1.0 -> (etaMin * 60).toInt()
                etaMin != null && etaMin > 0.0 -> 60
                else -> -1
            }
            val etaText = if (etaSeconds > 0) RouteProjectionMath.formatEtaText(etaSeconds, isArrived && bOrder == effectiveTargetOrder) else null

            liveBuses.add(
                LiveBus(
                    busId = busNumber,
                    plateOrFleetNo = sanitizeBusPlate(busNumber),
                    order = bOrder,
                    segmentProgress = segmentProgress,
                    isArrived = isArrived,
                    wgsLat = bLat,
                    wgsLng = bLng,
                    speedKmh = 0.0,
                    capacityLevel = -1,
                    distanceToTargetMeters = distanceMap[busNumber],
                    etaSeconds = etaSeconds,
                    expectedArrivalTimeText = etaText,
                    nextStopName = nextStopName
                )
            )
        }

        // 候车站前面还有车就不动；车都过了本站才按首末班判运营状态：
        // 末班之后显示「末班已过」，首班之前显示「等待发车」。
        val hasUpcomingBus = liveBuses.any { it.order <= effectiveTargetOrder }
        val serviceState = if (hasUpcomingBus) {
            ServiceHours.State.UNKNOWN
        } else {
            ServiceHours.state(
                nowHHMM = beijingNowHHMM(),
                windows = listOf(ServiceHours.Window(firstTime, lastTime))
            )
        }
        val operatingState = when {
            runState == 1 -> LineOperatingState.SUSPENDED
            hasReal == 0 -> LineOperatingState.NO_REALTIME
            serviceState == ServiceHours.State.AFTER_LAST -> LineOperatingState.ENDED
            serviceState == ServiceHours.State.BEFORE_FIRST -> LineOperatingState.WAITING
            else -> LineOperatingState.RUNNING
        }
        val statusDescription = when {
            runState == 1 -> "该线路已停运"
            hasReal == 0 -> "该线路未开通实时数据查询"
            serviceState == ServiceHours.State.AFTER_LAST -> "末班已过"
            serviceState == ServiceHours.State.BEFORE_FIRST -> "等待发车"
            else -> ""
        }

        val startStop = stopsList.firstOrNull()?.name ?: ""
        val endStop = stopsList.lastOrNull()?.name ?: ""

        val currentDirection = BusLineDirection(
            lineId = lineId,
            name = lineName,
            lineNo = lineName,
            direction = if (dirStr == "1") 0 else 1,
            startStop = startStop,
            endStop = endStop,
            firstTime = firstTime,
            lastTime = lastTime,
            price = price,
            source = DataSourceType.MYGOLBS,
            mygolbsCityCode = cityCode,
            mygolbsCityName = cityName
        )

        val revDirInt = if (dirStr == "1") 2 else 1
        val reverseDirection = BusLineDirection(
            lineId = "mg:$cityCode:$cityName:$lineName:$revDirInt",
            name = lineName,
            lineNo = lineName,
            direction = if (dirStr == "1") 1 else 0,
            startStop = endStop,
            endStop = startStop,
            firstTime = firstTime,
            lastTime = lastTime,
            price = price,
            source = DataSourceType.MYGOLBS,
            mygolbsCityCode = cityCode,
            mygolbsCityName = cityName
        )

        BusLineDetail(
            line = currentDirection,
            reverseDirection = reverseDirection,
            stops = stopsList,
            buses = liveBuses,
            operatingState = operatingState,
            statusDescription = statusDescription,
            nextDepartureFromStart = planTime,
            targetOrder = targetOrder ?: 0,
            updatedAtMillis = wallClock.now().toEpochMilliseconds()
        )
    }

    /**
     * 首站发车时刻表（CMD=207）。
     *
     * 以前掌上公交这里只返回一句「暂不提供」，但上游其实有完整班表：
     * `list[].group` 是时段（如 07:43~10:00），`list[].times[].time` 是班次，
     * `special=1` 表示计划班次、0 表示按历史数据推算。ROUTEID 从 CMD=103 的 routeId 取。
     */
    suspend fun getTimetable(
        cityCode: String,
        cityName: String,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): LineTimetable = withContext(Dispatchers.IO) {
        val ref = MygolbsIds.lineRef(line.lineId)
        // 线路 id 里带的就是这条线真正的城市；收藏里的线路可能不属于当前选中的城市，
        // 所以优先用 id 里的城市名，只有 id 里没有时才退回调用方给的城市。
        val effectiveCity = normalizeCityName(
            ref?.cityName?.takeIf { it.isNotBlank() } ?: cityName
        )
        val lineName = line.name.ifBlank { ref?.lineName.orEmpty() }
        val direction = ref?.direction ?: if (line.direction == 0) "1" else "2"
        // 附近站来的 id 城市码是 0（占位），这时才用调用方的真实城市码
        val effectiveCode = ref?.cityCode?.takeIf { it.isNotBlank() && it != "0" } ?: cityCode

        if (lineName.isBlank() || effectiveCity.isBlank()) {
            return@withContext fallbackTimetable(line, null, lineName)
        }

        // 103 可能已经在看线路详情时拉过，命中 12 小时缓存就只多打一次 207
        val static = runCatching {
            loadRouteStatic(effectiveCode, effectiveCity, lineName, direction)
        }.getOrNull()
        val routeId = static?.routeId.orEmpty()
        if (routeId.isBlank()) {
            return@withContext fallbackTimetable(line, static, lineName)
        }

        val raw = runCatching {
            timetableCache.get("$routeId|$direction") {
                fetchTimetable(effectiveCity, direction, routeId)
            }
        }.getOrNull()

        if (raw == null || raw.departures.isEmpty()) {
            return@withContext fallbackTimetable(line, static, lineName)
        }

        val reference = atTimeHHMM?.let { TimetableRules.normalizeClock(it) } ?: beijingNowHHMM()
        val resolved = TimetableRules.resolveNext(
            todayDepartures = raw.departures,
            yesterdayDepartures = emptyList(),
            referenceHHMM = reference,
            strict = atTimeHHMM != null
        )
        val next = raw.nextTime ?: resolved.time

        LineTimetable(
            lineId = line.lineId,
            lineName = lineName,
            nextDeparture = next,
            nextDepartureSource = when {
                next == null -> null
                // special=1 是计划班次，0/缺省是按历史数据推算
                raw.special == "1" -> "计划发车时间"
                else -> "历史推算班次"
            },
            todayDepartures = raw.departures,
            segments = raw.segments,
            depDesc = if (raw.special == "1") TIMETABLE_PLANNED_DESC else TIMETABLE_HISTORY_DESC
        )
    }

    private suspend fun fetchTimetable(
        cityName: String,
        direction: String,
        routeId: String
    ): TimetableRaw {
        val json = callApi(
            mapOf(
                "CMD" to "207",
                "CITYNAME" to cityName,
                "DIRECTION" to direction,
                "ROUTEID" to routeId,
                "CITYKEY" to ""
            )
        )

        val list = json.optJSONArray("list") ?: JSONArray()
        val departures = ArrayList<String>()
        val segments = ArrayList<TimetableSegment>()
        for (i in 0 until list.length()) {
            val group = list.optJSONObject(i) ?: continue
            val times = group.optJSONArray("times") ?: continue
            val groupTimes = ArrayList<String>()
            for (j in 0 until times.length()) {
                val item = times.optJSONObject(j) ?: continue
                // isDelete=1 是临时取消的班次，接口里带删除线；我们没有删除线样式，直接不展示
                if (item.optInt("isDelete", 0) == 1) continue
                val clock = TimetableRules.normalizeClock(item.optString("time")) ?: continue
                groupTimes.add(clock)
            }
            groupTimes.sort()
            departures.addAll(groupTimes)
            val (fromTime, toTime) = parseTimetableGroup(group.optString("group"))
            segments.add(
                TimetableSegment(
                    fromTime = fromTime,
                    toTime = toTime,
                    intervalTips = when (group.optInt("peak", 0)) {
                        1 -> "早高峰"
                        2 -> "晚高峰"
                        else -> ""
                    },
                    departures = groupTimes
                )
            )
        }
        departures.sort()

        return TimetableRaw(
            departures = departures,
            segments = segments,
            nextTime = TimetableRules.normalizeClock(json.optString("nextTime")),
            special = json.optString("special").ifBlank { "0" }
        )
    }

    private fun parseTimetableGroup(group: String): Pair<String, String> {
        val clean = group.trim()
        if (clean.isBlank()) return "" to ""
        val separator = when {
            clean.contains('~') -> '~'
            clean.contains('～') -> '～'
            else -> return clean to ""
        }
        return clean.substringBefore(separator).trim() to clean.substringAfter(separator).trim()
    }

    private fun fallbackTimetable(
        line: BusLineDirection,
        static: RouteStatic?,
        lineName: String
    ): LineTimetable {
        val first = static?.firstTime?.takeIf { it.isNotBlank() } ?: line.firstTime
        val last = static?.lastTime?.takeIf { it.isNotBlank() } ?: line.lastTime
        val desc = if (first.isNotBlank() || last.isNotBlank()) {
            "$TIMETABLE_FALLBACK：${first.ifBlank { "--" }} - ${last.ifBlank { "--" }}"
        } else {
            TIMETABLE_FALLBACK
        }
        return LineTimetable(
            lineId = line.lineId,
            lineName = lineName.ifBlank { line.name },
            depDesc = desc
        )
    }

    private fun beijingNowHHMM(): String {
        val now = wallClock.now().toLocalDateTime(BEIJING)
        return "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
    }
}
