package com.trackr.core.network.provider.meishan

import com.trackr.core.domain.arrival.ArrivalRules
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
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.common.ServiceHours
import com.trackr.core.network.remote.common.TimetableRules
import com.trackr.core.network.remote.meishan.MeishanIds
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.push.MeishanPushFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * 眉山公交官方数据源（眉山特化，doc/news 逆向文档）。
 *
 * 自给自足，不依赖车来了 / 掌上公交：
 * - 线路列表 / 搜索：`/line/list` 本地过滤（03 §3.1）
 * - 线路详情 + 实时车辆快照（车牌 / 站序 / 到站状态）：`/line/byId`（03 §3.2）
 * - 附近站点：`/find/siteAndRedPacket`（03 §3.4，dist 单位公里）
 * - 同站线路：`/line/site`（03 §3.3，一次请求带回全部经过线路的完整站表与车辆）
 * - 城市识别：官方无逆地理接口，按 GPS 距眉山 / 仁寿区域中心就近判定
 * - 速度：优先使用有效的 `b_sd`，缺失 / 无效时用相邻两次快照的 GPS 位移 ÷ 时间差兜底
 * - 时刻表：官方没有班次接口；ETA 按折线距离和车速估算
 */
class MeishanTransitProvider(
    private val client: MeishanTransitClient,
    /** 墙钟：运营状态判断的「现在」；测试可注入 */
    private val wallClock: Clock = Clock.System,
    /** 单调时钟：相邻帧速度计算；测试可注入 */
    private val monotonicClock: MonotonicClock = ProcessMonotonicClock
) : ITransitProvider {

    companion object {
        /** 时刻表按北京时间比较 */
        private val BEIJING = TimeZone.of("Asia/Shanghai")

        /** 官方服务范围的两个区域中心（WGS-84），城市识别用 */
        private val AREA_CENTERS = linkedMapOf(
            "眉山" to Pair(30.0489, 103.8517),
            "仁寿" to Pair(29.9957, 104.1434)
        )

        /** 城市识别判定半径（公里）：离两个中心都超出视为不在服务区 */
        private const val AREA_RADIUS_KM = 50.0

        /** 相邻帧速度：时间差有效窗口（秒），太短抖动、太长失真 */
        private const val SPEED_DT_MIN = 2.0
        private const val SPEED_DT_MAX = 120.0
        private const val SPEED_MAX_KMH = 80.0
        private const val SPEED_NOISE_KMH = 2.0

        /** 车速无效时的平均运营假设：4 m/s（14.4 km/h），与原客户端口径一致 */
        private const val DEFAULT_SPEED_KMH = 14.4

        /**
         * 停场车判定（06 §4.1）：无下发速度、离开始静止的位置不到 30m，并且持续 5 分钟 → 停场 / 离线，
         * 从到站列表剔除。按时长而不是帧数判：TCP 3 秒一帧，按帧数算十几秒就会把停站上下客、等红灯的车剔掉。
         */
        private const val PARKED_AFTER_MS = 5 * 60_000L
        private const val PARKED_MOVE_METERS = 30.0

        /**
         * 末班宽限期（分钟）：官方首末班是始发站的发车时刻，末班车发出后还要跑完全程。
         * 宽限期内照样解析车辆，只留还在线路上跑的车；车都跑完了才算今天收车。
         */
        private const val LAST_BUS_GRACE_MINUTES = 120

        /** 末班发出这么多分钟后，还在始发站的车不再当作末班车（留出晚发车的余量） */
        private const val ORIGIN_DEPARTURE_TOLERANCE_MINUTES = 10

        /** 进程内车辆 / 推送缓存上限；不随订阅和收到的帧数无限增长。 */
        private const val MAX_CACHED_PUSH_FRAMES = 128
        private const val MAX_SPEED_LINES = 64
        private const val MAX_SPEED_BUSES = 256

        /** 站点 ID 前缀：官方 s_id 打包进通用 stopId */
        const val STOP_ID_PREFIX = "ms-st:"

        /** 附近卡片 / 站点行最多展示的接近车辆 */
        private const val MAX_APPROACHING = 3

        fun encodeStopId(sId: Int): String = "$STOP_ID_PREFIX$sId"

        fun parseStopId(raw: String?): Int? =
            raw?.takeIf { it.startsWith(STOP_ID_PREFIX) }?.removePrefix(STOP_ID_PREFIX)?.toIntOrNull()
    }

    override val sourceType: DataSourceType = DataSourceType.MEISHAN

    override fun supportsLine(lineId: String): Boolean = MeishanIds.isMeishanId(lineId)

    /** 站点详情：认官方前缀的 s_id（来自附近站卡片 / 同站查询） */
    override fun supportsStop(request: StopDetailRequest): Boolean =
        parseStopId(request.stopId) != null || parseStopId(request.physicalStId) != null

    override fun supportsLocationRadar(): Boolean = true

    /** 站点接口直接带回每条线路的完整站表和车辆（03 §3.3），站数计算无需再请求 */
    override fun stopDetailIncludesBuses(): Boolean = true

    override suspend fun getSupportedCities(): List<TransitCity> = listOf(
        TransitCity(cityId = "", cityName = "眉山", pinyin = "meishan", province = "四川"),
        TransitCity(cityId = "", cityName = "仁寿", pinyin = "renshou", province = "四川")
    )

    /**
     * 官方无逆地理接口：按 GPS 离区域中心的距离就近判定（东坡 vs 仁寿相距约 27km，
     * 中点法在本场景足够；都不在半径内返回 null 交回调用方）。
     */
    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        var bestName: String? = null
        var bestKm = Double.MAX_VALUE
        for ((name, center) in AREA_CENTERS) {
            val meters = RouteProjectionMath.calculateDistanceMeters(wgsLat, wgsLng, center.first, center.second)
                ?: continue // 坐标非法（0/负值）：这个中心判不了
            val km = meters / 1000.0
            if (km < bestKm) {
                bestKm = km
                bestName = name
            }
        }
        return bestName?.takeIf { bestKm <= AREA_RADIUS_KM }
    }

    override suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? = null

    /**
     * 搜索（doc/news 03 §3.1 要点）：官方线路名可能重复，不做服务端 byName 查询，
     * 本地过滤线路列表。名称 / 起终点匹配；官方无站点搜索，站点结果为空。
     */
    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult {
        val area = MeishanArea.forCityName(city.cityName)
            ?: return TransitSearchResult(keyword = keyword)
        val q = keyword.trim()
        if (q.isBlank()) return TransitSearchResult(keyword = keyword)
        val lines = client.getLineList(area)
            .filter { it.name.contains(q, ignoreCase = true) || it.primaryText.contains(q, ignoreCase = true) }
            .map { it.toSummary() }
        return TransitSearchResult(keyword = keyword, lines = lines)
    }

    /**
     * 附近站点（03 §3.4）：`/find/siteAndRedPacket` 拿站点（服务端已按距离升序），
     * 再对每个站点并发拉 `/line/site` 补全经过线路与实时状态。
     */
    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val siteInfos = runCatching { client.getNearbySites(wgsLat, wgsLng) }.getOrNull()
            ?: return@withContext emptyList()

        data class SiteEntry(val sId: Int, val name: String, val lat: Double, val lng: Double, val distMeters: Int?)

        val sites = ArrayList<SiteEntry>()
        for (i in 0 until siteInfos.length()) {
            val site = siteInfos.optJSONObject(i) ?: continue
            val sId = site.optInt("s_id", -1)
            val name = cleanStopName(site.optString("s_name"))
            if (sId < 0 || name.isBlank()) continue
            sites.add(
                SiteEntry(
                    sId = sId,
                    name = name,
                    lat = site.optDouble("latitude", 0.0),
                    lng = site.optDouble("longitude", 0.0),
                    // dist 单位公里（03 §3.4 实测 0.053 ≈ 53 米）
                    distMeters = (site.optDouble("dist", -1.0) * 1000).roundToInt().takeIf { it >= 0 }
                )
            )
            if (sites.size >= limit) break
        }

        coroutineScope {
            sites.map { site ->
                async {
                    // 偶发失败会让整个站卡片没有线路信息，重试兜底
                    val stopLines = getStopLinesWithRetry(site.sId)
                    NearbyStop(
                        stopId = encodeStopId(site.sId),
                        name = site.name,
                        distanceMeters = site.distMeters,
                        wgsLat = site.lat,
                        wgsLng = site.lng,
                        physicalStId = encodeStopId(site.sId),
                        namesakeStId = encodeStopId(site.sId),
                        lines = stopLines?.let { buildStopLineStatuses(it, site.sId) }.orEmpty(),
                        source = DataSourceType.MEISHAN
                    )
                }
            }.map { it.await() }
        }
    }

    /**
     * `/line/site` 带重试：接口本身稳定（实测连发 5 次均正常），偶发一次失败 / 空响应
     * 多半是移动网络抖动。失败重试最多 [attempts] 次、退避 [backoffMs] 递增；
     * 空结果不重试（站点没有在营线路是正常业务态）。
     */
    private suspend fun getStopLinesWithRetry(sId: Int, attempts: Int = 3, backoffMs: Long = 400): JSONArray? {
        repeat(attempts) { attempt ->
            runCatching { client.getStopLines(sId) }.getOrNull()?.let { return it }
            if (attempt < attempts - 1) delay(backoffMs * (attempt + 1))
        }
        return null
    }

    /** 站点详情（03 §3.3）：与附近站共用 `/line/site`；站 ID 来自我们自己的 `ms-st:` 编码 */
    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val sId = parseStopId(request.stopId)
            ?: parseStopId(request.physicalStId)
            ?: return@withContext emptyList()
        // 偶发一次失败/空响应就整页「暂无运营车辆」太伤：重试几次（实测接口本身稳定）
        val stopLines = getStopLinesWithRetry(sId) ?: return@withContext emptyList()

        // 站名与坐标从任一线路的 siteList 里找（请求里未必带）
        var name = request.stopName.orEmpty().trim()
        var lat = request.stationLat ?: 0.0
        var lng = request.stationLng ?: 0.0
        if (name.isBlank() || lat == 0.0) {
            for (i in 0 until stopLines.length()) {
                val line = stopLines.optJSONObject(i) ?: continue
                val sites: JSONArray = line.optJSONArray("siteList") ?: continue
                for (j in 0 until sites.length()) {
                    val site = sites.optJSONObject(j) ?: continue
                    if (site.optInt("s_id", -1) == sId) {
                        if (name.isBlank()) name = cleanStopName(site.optString("s_name"))
                        if (lat == 0.0) {
                            lat = site.optDouble("latitude", 0.0)
                            lng = site.optDouble("longitude", 0.0)
                        }
                        break
                    }
                }
                if (name.isNotBlank() && lat != 0.0) break
            }
        }
        if (name.isBlank()) return@withContext emptyList()

        listOf(
            NearbyStop(
                stopId = encodeStopId(sId),
                name = name,
                wgsLat = lat,
                wgsLng = lng,
                physicalStId = encodeStopId(sId),
                namesakeStId = encodeStopId(sId),
                lines = buildStopLineStatuses(stopLines, sId),
                source = DataSourceType.MEISHAN
            )
        )
    }

    /** 线路详情（03 §3.2）：站点表 + 车辆快照（车牌 / 站序 / 到站状态 / 速度 / 估算 ETA） */
    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        val ref = MeishanIds.parse(request.lineId)
            ?: throw IllegalArgumentException("眉山官方源不认这个 lineId: ${request.lineId}")
        val area = MeishanArea.fromAreaId(ref.areaId)
        val detail = client.getLineDetail(ref.lId, ref.state, area)
        return detail.toBusLineDetail(ref, request)
    }

    /**
     * TCP 订阅 tag：官方线路列表的 copyId 区分服务端推送通道，areaId 仍必须保留。
     * 返回 null 表示该线路记录没有可用的推送副本标识，应继续使用 HTTP 轮询。
     */
    suspend fun pushTag(lineId: String): String? {
        val ref = MeishanIds.parse(lineId) ?: return null
        if (ref.lId < 0 || ref.state !in 0..1) return null
        val area = MeishanArea.fromAreaId(ref.areaId)
        val copyId = client.getLineList(area).firstOrNull { it.lId == ref.lId }?.copyId ?: return null
        if (copyId <= 0) return null
        return "${copyId}_${ref.state}_${ref.areaId}"
    }

    /**
     * 将 TCP 全量车辆帧套进已经由 HTTP 加载的线路详情。
     * 推送只包含 busInfos，不包含站表和首末班，因此静态线路信息必须来自 [base]。
     * [expectedTag] 必须由仓库传入 `pushTag(lineId)?.substringBeforeLast('_')`；
     * 未提供时拒绝匹配，不能把 HTTP l_id 当作推送 copyId。
     */
    fun applyPushFrame(
        base: BusLineDetail,
        frame: MeishanPushFrame,
        targetOrder: Int? = null,
        stationId: String? = null,
        expectedTag: String? = null
    ): BusLineDetail? {
        val ref = MeishanIds.parse(base.line.lineId) ?: return null
        if (expectedTag.isNullOrBlank() || frame.tag != expectedTag) return null
        val request = LineDetailRequest(
            lineId = base.line.lineId,
            targetOrder = targetOrder,
            stationId = stationId,
            source = DataSourceType.MEISHAN
        )
        // 首末班可能在 HTTP 快照之后跨过边界，重放同一帧也必须重新判断。
        val scheduled = operatingState(base.line.firstTime, base.line.lastTime)
        val grace = scheduled != LineOperatingState.RUNNING && isInLastBusGrace(base.line.firstTime, base.line.lastTime)
        val buses = if (scheduled != LineOperatingState.RUNNING && !grace) {
            emptyList()
        } else {
            val key = PushFrameKey(base.line.lineId, frame.tag, frame.pushTime, base.stops)
            val parsed = synchronized(pushFrameLock) {
                // 多订阅和选站重放共用不带 ETA 的车辆，速度 / 停场计数每帧只更新一次。
                parsedPushFrames[key] ?: parseBuses(ref, frame.busInfos, base.stops).also { parsed ->
                    if (parsedPushFrames.size >= MAX_CACHED_PUSH_FRAMES) {
                        parsedPushFrames.remove(parsedPushFrames.keys.first())
                    }
                    parsedPushFrames[key] = parsed
                }
            }
            val inService = if (grace) {
                lastBusesStillRunning(parsed, base.stops, base.line.lastTime)
            } else {
                parsed
            }
            withEta(base.stops, inService, request)
        }
        return base.copy(
            buses = buses,
            // 宽限期内还有末班车在跑就算运营中；都跑完了按时刻表收车
            operatingState = if (grace && buses.isNotEmpty()) LineOperatingState.RUNNING else scheduled,
            updatedAtMillis = wallClock.now().toEpochMilliseconds()
        )
    }

    private data class PushFrameKey(
        val lineId: String, val tag: String, val pushTime: String, val stops: List<BusStop>
    )

    private val pushFrameLock = SynchronizedObject()
    private val parsedPushFrames = linkedMapOf<PushFrameKey, List<LiveBus>>()

    /** 官方后台没有班次接口（05 §2），返回空表，调用方按首末班展示 */
    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable = LineTimetable(
        lineId = line.lineId,
        lineName = line.name,
        depDesc = "官方数据未提供班次表"
    )

    // ---- 同站线路解析（/line/site 与站点状态）----

    /**
     * 把 `/line/site` 的结果解析成每条经过线路在本站的状态。
     * 车辆先转换为正在接近 / 已停靠的站，随后与线路详情共用到站规则和距离计算。
     * 站数按站表位置计算；驶离本站的车转换后位于下一站，不再进入本站候选列表。
     */
    private fun buildStopLineStatuses(stopLines: JSONArray, targetSId: Int): List<NearbyStopLineStatus> {
        val nowHHMM = beijingNowHHMM()
        val statuses = ArrayList<NearbyStopLineStatus>()
        for (i in 0 until stopLines.length()) {
            val lineJson = stopLines.optJSONObject(i) ?: continue
            val state = lineJson.optInt("state", 0)
            val lId = lineJson.optInt("l_id", -1)
            val areaId = lineJson.optInt("areaId", MeishanArea.MEISHAN.areaId)
            if (lId < 0) continue
            val area = MeishanArea.fromAreaId(areaId)

            val sites: JSONArray = lineJson.optJSONArray("siteList") ?: JSONArray()
            var targetPos = -1
            var targetIndex = 0
            var targetName = ""
            val stops = ArrayList<BusStop>(sites.length())
            for (j in 0 until sites.length()) {
                val site = sites.optJSONObject(j) ?: continue
                val sId = site.optInt("s_id", -1)
                val siteName = cleanStopName(site.optString("s_name"))
                val index = site.optInt("index", j + 1)
                if (sId >= 0 && siteName.isNotBlank()) {
                    stops.add(
                        BusStop(
                            stopId = encodeStopId(sId),
                            name = siteName,
                            order = index,
                            wgsLat = site.optDouble("latitude", 0.0),
                            wgsLng = site.optDouble("longitude", 0.0)
                        )
                    )
                }
                if (sId == targetSId) {
                    targetPos = stops.lastIndex
                    targetIndex = index
                    targetName = siteName
                }
            }
            if (targetPos < 0) continue // 该车次记录里没有本站（异常数据），跳过

            val line = BusLineDirection(
                lineId = MeishanIds.encode(area.areaId, lId, state),
                name = lineJson.optString("l_name").trim(),
                lineNo = lineJson.optString("l_name").trim(),
                direction = state,
                startStop = splitDirectionText(
                    if (state == 0) lineJson.optString("l_sxpz") else lineJson.optString("l_xxpz")
                ).first,
                endStop = splitDirectionText(
                    if (state == 0) lineJson.optString("l_sxpz") else lineJson.optString("l_xxpz")
                ).second,
                firstTime = (if (state == 0) lineJson.optString("start_time") else lineJson.optString("xx_start_time")).trim(),
                lastTime = (if (state == 0) lineJson.optString("end_time") else lineJson.optString("xx_end_time")).trim(),
                source = DataSourceType.MEISHAN
            )

            // 发车状态机先行（05 §2）：未发车 / 末班已过时段不解析车辆——
            // 服务端仍返回归场车快照，但它们不是在途车，显示「等待发车 / 末班已过」。
            // 末班是始发站发车时刻：末班后的宽限期里末班车还在路上，照样解析（只留在途的末班车）
            val serviceState = ServiceHours.state(
                nowHHMM = nowHHMM,
                windows = listOfNotNull(
                    ServiceHours.Window(first = line.firstTime, last = line.lastTime)
                        .takeIf { it.first.isNotBlank() && it.last.isNotBlank() }
                )
            )
            val inGrace = isInLastBusGrace(line.firstTime, line.lastTime)
            val inService = serviceState == ServiceHours.State.IN_SERVICE ||
                serviceState == ServiceHours.State.UNKNOWN || // 没给首末班时无法判定，按现状解析
                inGrace

            // 筛「还没到本站」的车（05 §1.2）：站序匹配、下标做差
            val buses: JSONArray = lineJson.optJSONArray("busList") ?: JSONArray()
            val approaching = ArrayList<LiveBus>()
            if (inService) {
                val ref = MeishanIds.Ref(area.areaId, lId, state)
                val parsed = parseBuses(ref, buses, stops).let { all ->
                    if (inGrace) lastBusesStillRunning(all, stops, line.lastTime) else all
                }
                approaching.addAll(parsed.filter { bus ->
                    busPosOf(stops, bus.order) >= 0 && bus.order <= targetIndex
                })
            }
            approaching.sortBy { targetPos - stops.indexOfFirst { s -> s.order == it.order } }
            val approachingWithEta = withEta(
                stops, approaching, LineDetailRequest(lineId = line.lineId, targetOrder = targetIndex)
            )

            val nearest = approachingWithEta.firstOrNull()
            val statusText = when {
                nearest == null -> when (serviceState) {
                    ServiceHours.State.AFTER_LAST -> "末班已过"
                    ServiceHours.State.BEFORE_FIRST -> "等待发车"
                    else -> "暂无车辆"
                }
                ArrivalRules.isBusArrivedAtOrder(targetIndex, nearest) -> "已到站"
                nearest.stopsTo(targetIndex) == 0 -> "下一站就到"
                else -> "${nearest.stopsTo(targetIndex)}站"
            }

            statuses.add(
                NearbyStopLineStatus(
                    line = line,
                    targetOrder = targetIndex,
                    targetStopId = encodeStopId(targetSId),
                    targetStopName = targetName,
                    statusText = statusText,
                    approachingBuses = approachingWithEta.take(MAX_APPROACHING)
                )
            )
        }
        return statuses
    }

    private fun busPosOf(stops: List<BusStop>, order: Int): Int =
        stops.indexOfFirst { it.order == order }

    // ---- 线路详情解析 ----

    private fun JSONObject.toBusLineDetail(ref: MeishanIds.Ref, request: LineDetailRequest): BusLineDetail {
        val area = MeishanArea.fromAreaId(ref.areaId)
        val name = optString("l_name").trim()
        val directionText = if (ref.state == 0) optString("l_sxpz") else optString("l_xxpz")
        val reverseText = if (ref.state == 0) optString("l_xxpz") else optString("l_sxpz")
        val first = if (ref.state == 0) optString("start_time") else optString("xx_start_time")
        val last = if (ref.state == 0) optString("end_time") else optString("xx_end_time")
        val (startStop, endStop) = splitDirectionText(directionText)

        val line = BusLineDirection(
            lineId = MeishanIds.encode(area.areaId, ref.lId, ref.state),
            name = name,
            lineNo = name,
            direction = ref.state,
            startStop = startStop,
            endStop = endStop,
            firstTime = first.trim(),
            lastTime = last.trim(),
            source = DataSourceType.MEISHAN
        )
        // 反方向只有列表字段（无站表），方向切换时按它的 lineId 重新拉详情
        val reverseDirection = reverseText.trim().takeIf { it.isNotBlank() }?.let { text ->
            val (reverseStart, reverseEnd) = splitDirectionText(text)
            val reverseFirst = if (ref.state == 0) optString("xx_start_time") else optString("start_time")
            val reverseLast = if (ref.state == 0) optString("xx_end_time") else optString("end_time")
            BusLineDirection(
                lineId = MeishanIds.encode(area.areaId, ref.lId, 1 - ref.state),
                name = name,
                lineNo = name,
                direction = 1 - ref.state,
                startStop = reverseStart,
                endStop = reverseEnd,
                firstTime = reverseFirst.trim(),
                lastTime = reverseLast.trim(),
                source = DataSourceType.MEISHAN
            )
        }

        val stops = mutableListOf<BusStop>()
        val siteList: JSONArray = optJSONArray("siteList") ?: JSONArray()
        for (i in 0 until siteList.length()) {
            val site = siteList.optJSONObject(i) ?: continue
            val sId = site.optInt("s_id", -1)
            val siteName = cleanStopName(site.optString("s_name"))
            if (sId < 0 || siteName.isBlank()) continue
            stops.add(
                BusStop(
                    stopId = encodeStopId(sId),
                    name = siteName,
                    order = site.optInt("index", i + 1),
                    wgsLat = site.optDouble("latitude", 0.0),
                    wgsLng = site.optDouble("longitude", 0.0)
                )
            )
        }

        // 非运营时段（未发车 / 末班已过）：服务端仍返回归场车辆快照，但它们不是在途车——
        // 车辆列表置空，主卡按运营状态显示「未发车 / 末班已过」（06 §4.1 软件层规则优先）。
        // 末班是始发站发车时刻：末班后的宽限期里只留还在跑的末班车，都跑完了才收车
        val scheduled = operatingState(first, last)
        val grace = scheduled != LineOperatingState.RUNNING && isInLastBusGrace(first, last)
        val buses = if (scheduled == LineOperatingState.RUNNING || grace) {
            val parsed = parseBuses(ref, optJSONArray("busList") ?: JSONArray(), stops)
            withEta(stops, if (grace) lastBusesStillRunning(parsed, stops, last) else parsed, request)
        } else {
            emptyList()
        }
        val state = if (grace && buses.isNotEmpty()) LineOperatingState.RUNNING else scheduled
        return BusLineDetail(
            line = line,
            reverseDirection = reverseDirection,
            stops = stops,
            buses = buses,
            operatingState = state,
            statusDescription = optString("l_remark").trim()
        )
    }

    /**
     * 给每辆车补「到目标站的折线距离 + 估算 ETA」。
     * 目标站：请求带上车站（targetOrder / stationId）就用它，否则按终点站（LiveBus 契约）。
     * 距离 = 车辆 GPS → 下一站 → … → 目标站 逐段直线累加；耗时 = 距离 ÷ 该车实测速度，
     * 车速无效（<2km/h，可能在等灯）时退回固定 4 m/s（14.4 km/h）的平均假设。
     */
    private fun withEta(stops: List<BusStop>, buses: List<LiveBus>, request: LineDetailRequest): List<LiveBus> {
        if (stops.isEmpty()) return buses
        // 没选上车站就按终点站算（LiveBus 契约「目标站（或终点站）」）
        val targetPos = resolveTargetPos(stops, request.targetOrder, request.stationId) ?: stops.lastIndex
        return buses.map { bus ->
            val busPos = stops.indexOfFirst { it.order == bus.order }
            val meters = when {
                busPos < 0 || busPos > targetPos -> null // 已过目标站 / 站序异常：不给 ETA
                busPos == targetPos && bus.isArrived -> 0
                else -> polylineMeters(stops, if (bus.isArrived) busPos else busPos - 1, targetPos, bus.wgsLat, bus.wgsLng)
            } ?: return@map bus
            bus.copy(
                distanceToTargetMeters = meters,
                etaSeconds = etaSeconds(meters, bus.speedKmh)
            )
        }
    }

    /** 目标站下标：targetOrder → stationId(ms-st:) → null（调用方回退终点站） */
    private fun resolveTargetPos(stops: List<BusStop>, targetOrder: Int?, stationId: String?): Int? {
        targetOrder?.takeIf { it > 0 }?.let { order ->
            val pos = stops.indexOfFirst { it.order == order }
            if (pos >= 0) return pos
        }
        stationId?.let { id ->
            val pos = stops.indexOfFirst { it.stopId == id }
            if (pos >= 0) return pos
        }
        return null
    }

    /** 车辆 GPS → s[busPos+1] → … → s[targetPos] 的逐段直线距离（米） */
    private fun polylineMeters(
        stops: List<BusStop>,
        busPos: Int,
        targetPos: Int,
        busLat: Double,
        busLng: Double
    ): Int? {
        var total = 0.0
        var fromLat = busLat
        var fromLng = busLng
        for (i in busPos + 1..targetPos) {
            val to = stops[i]
            val seg = RouteProjectionMath.calculateDistanceMeters(fromLat, fromLng, to.wgsLat, to.wgsLng)
                ?: return null // 坐标缺失的站表算不了
            total += seg
            fromLat = to.wgsLat
            fromLng = to.wgsLng
        }
        return total.roundToInt()
    }

    /** 距离 ÷ 速度：速度无效时按固定 4 m/s（14.4 km/h）的平均运营假设 */
    private fun etaSeconds(meters: Int, speedKmh: Double): Int {
        val effectiveKmh = if (speedKmh >= SPEED_NOISE_KMH) speedKmh else DEFAULT_SPEED_KMH
        return (meters / (effectiveKmh / 3.6)).roundToInt()
    }

    /**
     * 车辆快照解析：速度优先用数据源自带的 b_sd，字段无效（0/缺失）时用相邻帧位移兜底；
     * 连续多帧不动的停场车从列表剔除。
     */
    private fun parseBuses(ref: MeishanIds.Ref, busList: JSONArray, stops: List<BusStop>): List<LiveBus> {
        val nowMs = monotonicClock.elapsedMillis()
        val cacheKey = "${ref.areaId}_${ref.lId}_${ref.state}"
        var anonymousSlot = 0
        val stopOrders = stops.map { it.order }

        val buses = ArrayList<LiveBus>(busList.length())
        for (i in 0 until busList.length()) {
            val bus = busList.optJSONObject(i) ?: continue
            val plate = bus.optString("b_plate_num").trim()
            val index = bus.optInt("index", -1)
            // 场站车过滤：站序从 1 开始，index=0 是未发车/收车归场的车（实测运营时段也有
            // 一堆 index=0 的车停在起点站）。它们不属于任何站，放进列表会被「最近的车」
            // 筛选（order <= 目标站）误判成目标站上「已到站」
            if (index < 1) continue
            val position = stops.indexOfFirst { it.order == index }
            if (position < 0) continue
            val arrived = bus.optInt("isArriveSite", 0) == 1
            // index 是已到达 / 最近驶离的站；通用模型的 order 是正在接近的站。
            val next = stops.getOrNull(position + 1)
            val order = if (arrived) index else next?.order ?: index
            val progress = if (arrived) 1.0f else RouteProjectionMath.computeSegmentProgress(
                bus.optDouble("b_latitude", 0.0), bus.optDouble("b_longitude", 0.0),
                false, stops[position], next
            ).coerceIn(0.0f, 0.97f)
            // b_id 常为 0：有车牌时不把站序或数组位置混入身份。
            // 无车牌优先用有效 b_id，否则使用匿名车序号；后者只在匿名车顺序稳定时可跟随。
            val identity = plate.ifBlank {
                bus.optInt("b_id", 0).takeIf { it > 0 }?.let { "id:$it" }
                    ?: "anonymous:${anonymousSlot++}"
            }
            val lat = bus.optDouble("b_latitude", 0.0)
            val lng = bus.optDouble("b_longitude", 0.0)
            val reported = bus.optDouble("b_sd", 0.0)

            val (speedKmh, _, parked) = synchronized(speedLock) {
                resolveBusSpeed(cacheKey, identity, lat, lng, reported, nowMs).also { (_, sample, _) ->
                    storeSample(cacheKey, identity, sample)
                }
            }
            if (parked) continue // 连续多帧未动：停场/离线车，不出现在车辆列表

            buses.add(
                LiveBus(
                    busId = "msb:${ref.areaId}:${ref.lId}:${ref.state}:$identity",
                    plateOrFleetNo = plate,
                    order = order,
                    segmentProgress = progress,
                    isArrived = arrived,
                    wgsLat = lat,
                    wgsLng = lng,
                    speedKmh = speedKmh,
                    nextStopName = if (arrived) stops[position].name else next?.name,
                    stopOrders = stopOrders,
                    hasAuthoritativeArrival = true
                )
            )
        }
        return buses
    }

    /**
     * 单辆车速度解析 + 停场计时（06 §4.1）：
     * - b_sd 有效（>0）直接用；
     * - 无效时若上一帧可比（2–120s）且位移合理，按位移估算；
     * - 无下发速度、离开始静止的位置不到 30m → 记下开始静止的时刻，持续满 [PARKED_AFTER_MS] 判为停场车。
     *   按时长判：停站上下客、等红灯一两分钟不会被剔除，不受推送帧率影响。
     */
    private fun resolveBusSpeed(
        key: String,
        plate: String,
        lat: Double,
        lng: Double,
        reported: Double,
        nowMs: Long
    ): Triple<Double, SpeedSample, Boolean> {
        val previous = synchronized(speedLock) { speedSamples[key]?.get(plate) }
        val hasReportedSpeed = reported.isFinite() && reported > 0.0
        var speedKmh = if (hasReportedSpeed) reported else 0.0
        if (previous != null && !hasReportedSpeed) {
            val dtSec = (nowMs - previous.timeMs) / 1000.0
            val meters = RouteProjectionMath.calculateDistanceMeters(previous.lat, previous.lng, lat, lng)
            if (dtSec in SPEED_DT_MIN..SPEED_DT_MAX && meters != null && meters >= PARKED_MOVE_METERS) {
                val kmh = (meters / dtSec) * 3.6
                if (kmh in SPEED_NOISE_KMH..SPEED_MAX_KMH) speedKmh = kmh
            }
        }
        // 静止计时：以开始静止的位置为锚点，慢慢漂移的 GPS 也不会让计时一直重来
        val anchor = previous?.stillSince?.let { since ->
            val drift = RouteProjectionMath.calculateDistanceMeters(previous.stillLat, previous.stillLng, lat, lng)
            if (!hasReportedSpeed && drift != null && drift < PARKED_MOVE_METERS) since else null
        }
        val sample = if (anchor != null) {
            SpeedSample(lat, lng, nowMs, stillSince = anchor, stillLat = previous!!.stillLat, stillLng = previous.stillLng)
        } else if (hasReportedSpeed) {
            SpeedSample(lat, lng, nowMs)
        } else {
            SpeedSample(lat, lng, nowMs, stillSince = nowMs, stillLat = lat, stillLng = lng)
        }
        val parked = sample.stillSince != null && nowMs - sample.stillSince >= PARKED_AFTER_MS
        return Triple(speedKmh, sample, parked)
    }

    private fun storeSample(key: String, plate: String, sample: SpeedSample) {
        synchronized(speedLock) {
            val map = speedSamples.remove(key)?.toMutableMap() ?: mutableMapOf()
            if (plate !in map && map.size >= MAX_SPEED_BUSES) map.remove(map.keys.first())
            map[plate] = sample
            if (speedSamples.size >= MAX_SPEED_LINES) speedSamples.remove(speedSamples.keys.first())
            speedSamples[key] = map
        }
    }

    /**
     * 一辆车上一帧的位置；[stillSince] 是开始静止（无下发速度、离锚点 < 30m）的时刻，
     * [stillLat] / [stillLng] 是开始静止时的位置（锚点）。在动的车 [stillSince] 为 null。
     */
    private data class SpeedSample(
        val lat: Double,
        val lng: Double,
        val timeMs: Long,
        val stillSince: Long? = null,
        val stillLat: Double = lat,
        val stillLng: Double = lng
    )

    /** 每个线路方向一份上一帧位置（内存态，进程内有效；车牌不落盘）。线路详情 / 站点状态两条路径共用，访问走 [speedLock] */
    private val speedSamples = linkedMapOf<String, Map<String, SpeedSample>>()
    private val speedLock = SynchronizedObject()

    // ---- 小工具 ----

    /** 首末班 vs 北京时间 → 运营状态（05 §2：末班后已收车，不把归场车当在途） */
    private fun operatingState(first: String, last: String): LineOperatingState = when (
        ServiceHours.state(
            nowHHMM = beijingNowHHMM(),
            windows = listOfNotNull(
                ServiceHours.Window(first = first, last = last).takeIf { w ->
                    w.first.isNotBlank() && w.last.isNotBlank()
                }
            )
        )
    ) {
        ServiceHours.State.IN_SERVICE -> LineOperatingState.RUNNING
        ServiceHours.State.BEFORE_FIRST -> LineOperatingState.WAITING
        ServiceHours.State.AFTER_LAST -> LineOperatingState.ENDED
        ServiceHours.State.UNKNOWN -> LineOperatingState.RUNNING
    }

    private fun beijingNowHHMM(): String =
        wallClock.now().toLocalDateTime(BEIJING).let { t ->
            "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        }

    /**
     * 末班（官方给的是始发站发车时刻）之后 [LAST_BUS_GRACE_MINUTES] 分钟内：末班车可能还在路上。
     * 只在时刻表判为非运营时成立；首末班缺失或格式不对时不成立。
     */
    private fun isInLastBusGrace(first: String, last: String): Boolean {
        if (first.isBlank() || last.isBlank()) return false
        val lastMinute = minuteOfDay(last) ?: return false
        val firstMinute = minuteOfDay(first) ?: return false
        if (firstMinute == lastMinute) return false // 首末班相同（常见于缺失填 00:00）：判不了
        val now = beijingNowHHMM()
        val nowMinute = minuteOfDay(now) ?: return false
        val inService = ServiceHours.state(now, listOf(ServiceHours.Window(first, last))) ==
            ServiceHours.State.IN_SERVICE
        return !inService && minutesSince(lastMinute, nowMinute) in 1..LAST_BUS_GRACE_MINUTES
    }

    /**
     * 末班后的宽限期里，只留还在线路上跑的车：
     * - 已到终点站的车跑完了；
     * - 末班发出 [ORIGIN_DEPARTURE_TOLERANCE_MINUTES] 分钟后还停在始发站的车不会再发。
     */
    private fun lastBusesStillRunning(buses: List<LiveBus>, stops: List<BusStop>, last: String): List<LiveBus> {
        if (stops.isEmpty()) return buses
        val lastMinute = minuteOfDay(last)
        val nowMinute = minuteOfDay(beijingNowHHMM())
        val pastDeparture = lastMinute != null && nowMinute != null &&
            minutesSince(lastMinute, nowMinute) > ORIGIN_DEPARTURE_TOLERANCE_MINUTES
        return buses.filter { bus ->
            val pos = stops.indexOfFirst { it.order == bus.order }
            when {
                pos == stops.lastIndex && bus.isArrived -> false
                // 停在始发站（已到站标志）的车：末班发出一会儿还没走，就不会再发了
                pos == 0 && bus.isArrived && pastDeparture -> false
                else -> true
            }
        }
    }

    private fun minuteOfDay(hhmm: String): Int? =
        TimetableRules.normalizeClock(hhmm)?.split(":")?.let { (h, m) -> h.toInt() * 60 + m.toInt() }

    /** 从 [fromMinute] 到 [toMinute] 过了多少分钟（跨零点按次日算） */
    private fun minutesSince(fromMinute: Int, toMinute: Int): Int = (toMinute - fromMinute + 24 * 60) % (24 * 60)

    private fun MeishanLine.toSummary(): BusLineSummary {
        val directions = buildList {
            forwardText?.let { add(toDirection(state = 0, text = it, first = startTime, last = endTime)) }
            backwardText?.let { add(toDirection(state = 1, text = it, first = backwardStartTime, last = backwardEndTime)) }
        }
        return BusLineSummary(
            name = name,
            lineNo = name,
            isSubway = false,
            directions = directions,
            source = DataSourceType.MEISHAN
        )
    }

    private fun MeishanLine.toDirection(state: Int, text: String, first: String?, last: String?): BusLineDirection {
        val (start, end) = splitDirectionText(text)
        return BusLineDirection(
            lineId = MeishanIds.encode(area.areaId, lId, state),
            name = name,
            lineNo = name,
            direction = state,
            startStop = start,
            endStop = end,
            firstTime = first.orEmpty(),
            lastTime = last.orEmpty(),
            source = DataSourceType.MEISHAN
        )
    }

    /** "眉山东站:凯旋国际社区" → (起, 终)；缺分隔就整段做起站（02 §1 容错） */
    private fun splitDirectionText(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val sep = trimmed.indexOfFirst { it == ':' || it == '：' }
        if (sep <= 0 || sep >= trimmed.length - 1) return trimmed to ""
        return trimmed.substring(0, sep).trim() to trimmed.substring(sep + 1).trim()
    }

    /** 站名清洗：官方站名可能带 `_跑法2` / `._跑法2` 调度别名后缀（02 §2） */
    private fun cleanStopName(raw: String): String =
        raw.trim().substringBefore("._").substringBefore('_').trim()
}
