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
import com.trackr.core.network.remote.meishan.MeishanPayloadMissingException
import com.trackr.core.network.remote.meishan.MeishanResponseFormatException
import com.trackr.core.network.remote.meishan.MeishanThrottledException
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.intOrNull
import com.trackr.core.network.remote.meishan.push.MeishanPushFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * 眉山公交官方数据源（眉山特化，规范见 doc/勘误后的参考文档）。
 *
 * 自给自足，不依赖车来了 / 掌上公交：
 * - 线路列表 / 搜索：`/line/list` 本地过滤（H01）
 * - 线路详情 + 实时车辆快照（车牌 / 站序 / 到站状态）：`/line/byId`（H02）
 * - 附近站点：`/find/siteAndRedPacket`（H04）
 * - 同站线路：`/line/site`（H03，一次请求带回经过线路的站表与车辆）
 * - 城市识别：官方无逆地理接口，按 GPS 距眉山 / 仁寿区域中心就近判定
 * - ETA：车辆 GPS → 下一站 → … → 目标站 点距累计 ÷ 车速；`b_sd` 为 km/h（已确认），无效时用相邻帧位移兜底，
 *   仍无效按固定 4 m/s
 * - 时刻表：官方没有班次接口
 */
class MeishanTransitProvider(
    private val client: MeishanTransitClient,
    /** 墙钟：运营状态判断的「现在」；测试可注入 */
    private val wallClock: Clock = Clock.System,
    /** 单调时钟：相邻帧速度与静止计时；测试可注入 */
    private val monotonicClock: MonotonicClock = ProcessMonotonicClock
) : ITransitProvider {

    companion object {
        /** 时刻表按北京时间比较（服务端时区未确认，这是本应用的假设） */
        private val BEIJING = TimeZone.of("Asia/Shanghai")

        /** 官方服务范围的两个区域中心（WGS-84），城市识别用 */
        private val AREA_CENTERS = linkedMapOf(
            "眉山" to Pair(30.0489, 103.8517),
            "仁寿" to Pair(29.9957, 104.1434)
        )

        /** 城市识别判定半径（公里）：离两个中心都超出视为不在服务区 */
        private const val AREA_RADIUS_KM = 50.0

        /** 有效车速下限：低于它（可能在等灯）按固定 4 m/s 估 ETA，与原客户端口径一致 */
        private const val SPEED_NOISE_KMH = 2.0
        private const val DEFAULT_SPEED_KMH = 14.4

        /** 相邻帧速度：时间差有效窗口（秒），太短抖动、太长失真 */
        private const val SPEED_DT_MIN = 2.0
        private const val SPEED_DT_MAX = 120.0
        private const val SPEED_MAX_KMH = 80.0

        /**
         * 长时间未移动：无下发速度、离开始静止的位置不到 30m 并持续 5 分钟，不再参与到站计算，
         * 改放进 [BusLineDetail.idleBuses] 带标签展示。阈值是本应用的产品参数，待运营时段数据校准。
         */
        private const val STILL_AFTER_MS = 5 * 60_000L
        private const val STILL_MOVE_METERS = 30.0

        /**
         * 末班宽限期（分钟）：官方首末班是始发站发车时刻，末班车发出后还要跑完全程。
         * 本应用的经验参数（文档未规定）；宽限期外的车放进 idleBuses 展示，不删除。
         */
        private const val LAST_BUS_GRACE_MINUTES = 120

        /** 末班发出这么多分钟后，还在始发站的车不再当作末班车（留出晚发车的余量） */
        private const val ORIGIN_DEPARTURE_TOLERANCE_MINUTES = 10

        /** 进程内缓存上限；不随订阅和收到的帧数无限增长。 */
        private const val MAX_CACHED_PUSH_FRAMES = 128
        private const val MAX_STILL_LINES = 64
        private const val MAX_STILL_BUSES = 256
        private const val MAX_COPY_IDS = 256

        /**
         * 连续空帧清空阈值：推送帧约 3 秒一帧，单个/少量空 busInfos 是服务端数据抖动
         * （doc/眉山TCP推送抖动观察-2026-11-07.md）。不足阈值的空帧直接忽略，避免 HTTP
         * 暂停期间车辆列表闪成「暂无运营车辆」；真正的收车/停运在连续空帧达到阈值后正常清空。
         */
        private const val EMPTY_FRAME_CLEAR_THRESHOLD = 3

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

    /** 城市 → 官方区域；不是眉山 / 仁寿时按眉山（与旧客户端未设置区域时回退 1 一致） */
    private fun areaOf(city: TransitCity): MeishanArea = MeishanArea.forCityName(city.cityName) ?: MeishanArea.MEISHAN

    /**
     * 附近站点（H04）：`/find/siteAndRedPacket` 拿站点，本地按 dist 升序后截取（服务端排序未确认），
     * 再对每个站点并发拉 `/line/site` 补全经过线路与实时状态。
     * 附近站请求失败、或所有站的线路都取不到时抛错，调用方保留上一份结果，不显示成「附近没有站」。
     */
    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val area = areaOf(city)
        val siteInfos = client.getNearbySites(area, wgsLat, wgsLng)

        data class SiteEntry(val sId: Int, val name: String, val lat: Double, val lng: Double, val distMeters: Int?)

        val parsed = ArrayList<SiteEntry>(siteInfos.length())
        for (i in 0 until siteInfos.length()) {
            val site = siteInfos.optJSONObject(i) ?: continue
            val sId = site.intOrNull("s_id") ?: continue
            val name = cleanStopName(site.optString("s_name"))
            if (sId < 0 || name.isBlank()) continue
            // dist 与公里数量级吻合（勘误文档列为推断）；缺失时不编造距离
            val distKm = if (site.isNull("dist")) Double.NaN else site.optDouble("dist", Double.NaN)
            parsed.add(
                SiteEntry(
                    sId = sId,
                    name = name,
                    lat = site.optDouble("latitude", 0.0),
                    lng = site.optDouble("longitude", 0.0),
                    distMeters = distKm.takeIf { it.isFinite() && it >= 0.0 }?.let { (it * 1000).roundToInt() }
                )
            )
        }
        val sites = parsed.sortedBy { it.distMeters ?: Int.MAX_VALUE }.take(limit)

        val results = coroutineScope {
            sites.map { site -> async { site to getStopLinesWithRetry(area, site.sId) } }.map { it.await() }
        }
        if (results.isNotEmpty() && results.all { it.second.isFailure }) {
            throw results.first().second.exceptionOrNull() ?: IOException("眉山同站线路全部失败")
        }
        results.map { (site, lines) ->
            NearbyStop(
                stopId = encodeStopId(site.sId),
                name = site.name,
                distanceMeters = site.distMeters,
                wgsLat = site.lat,
                wgsLng = site.lng,
                physicalStId = encodeStopId(site.sId),
                namesakeStId = encodeStopId(site.sId),
                lines = lines.getOrNull()?.let { buildStopLineStatuses(it, site.sId, area) }.orEmpty(),
                source = DataSourceType.MEISHAN
            )
        }
    }

    /**
     * `/line/site` 带重试：偶发一次失败多半是移动网络抖动。失败重试最多 [attempts] 次、退避递增；
     * 空数组不重试（站点没有在营线路是正常业务态）；限流时不重试。
     */
    private suspend fun getStopLinesWithRetry(
        area: MeishanArea,
        sId: Int,
        attempts: Int = 3,
        backoffMs: Long = 400
    ): Result<JSONArray> {
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return Result.success(client.getStopLines(area, sId))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throttled: MeishanThrottledException) {
                return Result.failure(throttled)
            } catch (e: Exception) {
                last = e
            }
            if (attempt < attempts - 1) delay(backoffMs * (attempt + 1))
        }
        return Result.failure(last ?: IOException("眉山同站线路失败（s_id=$sId）"))
    }

    /** 站点详情（H03）：与附近站共用 `/line/site`；站 ID 来自我们自己的 `ms-st:` 编码。请求失败时抛错 */
    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val sId = parseStopId(request.stopId)
            ?: parseStopId(request.physicalStId)
            ?: return@withContext emptyList()
        val area = areaOf(city)
        val stopLines = getStopLinesWithRetry(area, sId).getOrThrow()

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
                    if (site.intOrNull("s_id") == sId) {
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
                lines = buildStopLineStatuses(stopLines, sId, area),
                source = DataSourceType.MEISHAN
            )
        )
    }

    /** 线路详情（H02）：站点表 + 车辆快照（车牌 / 站序 / 到站状态 / 估算 ETA） */
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

    /** 详情响应里的 `l_copy_id`（按区域 + 线路 + 方向），TCP 订阅优先用它 */
    private val copyIdLock = SynchronizedObject()
    private val copyIds = linkedMapOf<String, Int>()

    private fun copyIdKey(ref: MeishanIds.Ref) = "${ref.areaId}_${ref.lId}_${ref.state}"

    private fun rememberCopyId(ref: MeishanIds.Ref, copyId: Int) {
        synchronized(copyIdLock) {
            copyIds.remove(copyIdKey(ref))
            if (copyIds.size >= MAX_COPY_IDS) copyIds.remove(copyIds.keys.first())
            copyIds[copyIdKey(ref)] = copyId
        }
    }

    /**
     * TCP 订阅 tag `l_copy_id_state_areaId`（R01：三个值来自 HTTP 元数据）。
     * `l_copy_id` 优先取本方向详情响应；还没有详情时按列表里同线路、同方向的记录，最后才退到同线路首条。
     * 返回 null 表示没有可用的推送副本标识，应继续使用 HTTP 轮询。
     */
    suspend fun pushTag(lineId: String): String? {
        val ref = MeishanIds.parse(lineId) ?: return null
        if (ref.lId < 0 || ref.state !in 0..1) return null
        val copyId = synchronized(copyIdLock) { copyIds[copyIdKey(ref)] } ?: run {
            val lines = client.getLineList(MeishanArea.fromAreaId(ref.areaId)).filter { it.lId == ref.lId }
            (lines.firstOrNull { it.state == ref.state } ?: lines.firstOrNull())?.copyId
        } ?: return null
        if (copyId <= 0) return null
        return "${copyId}_${ref.state}_${ref.areaId}"
    }

    /**
     * 将 TCP 车辆帧套进已经由 HTTP 加载的线路详情。
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
        val copyId = expectedTag.substringBefore('_').toIntOrNull()
        val key = PushFrameKey(base.line.lineId, frame.tag, frame.pushTime, base.stops)
        // 单锁段内完成「解析缓存读写 + 空帧计数」，多订阅并发时口径一致；
        // 计数按 (tag, pushTime) 去重：copyId 撞号时同 tag 的两份订阅（缓存键不同）收到的是
        // 同一个物理帧，只能计 1 次，否则阈值 3 会被两个订阅在 2 个时间戳里凑满。
        val (parsed, emptyRun) = synchronized(pushFrameLock) {
            val cached = parsedPushFrames[key]
            if (cached != null) {
                cached to (emptyFrameRuns[frame.tag] ?: 0)
            } else {
                val parsed = parseBuses(ref, frame.busInfos, base.stops, copyId)
                if (parsedPushFrames.size >= MAX_CACHED_PUSH_FRAMES) {
                    parsedPushFrames.remove(parsedPushFrames.keys.first())
                }
                parsedPushFrames[key] = parsed
                val isNewPhysicalFrame = countedPushTimes[frame.tag] != frame.pushTime
                countedPushTimes[frame.tag] = frame.pushTime
                val run = when {
                    !parsed.isEmpty -> {
                        emptyFrameRuns.remove(frame.tag)
                        0
                    }
                    isNewPhysicalFrame -> {
                        val next = (emptyFrameRuns[frame.tag] ?: 0) + 1
                        emptyFrameRuns[frame.tag] = next
                        next
                    }
                    else -> emptyFrameRuns[frame.tag] ?: 0
                }
                parsed to run
            }
        }
        if (parsed.isEmpty && emptyRun < EMPTY_FRAME_CLEAR_THRESHOLD) {
            // 不足阈值的空帧按服务端抖动忽略：返回 null，调用方保持当前详情不变。
            return null
        }
        // 首末班可能在 HTTP 快照之后跨过边界，重放同一帧也必须重新判断。
        val scheduled = splitBySchedule(parsed, base.stops, base.line.firstTime, base.line.lastTime)
        val targetPos = resolveTargetPos(base.stops, targetOrder, stationId)
        return base.copy(
            buses = withEta(base.stops, scheduled.active, targetPos),
            idleBuses = scheduled.idle,
            operatingState = scheduled.state,
            updatedAtMillis = wallClock.now().toEpochMilliseconds()
        )
    }

    private data class PushFrameKey(
        val lineId: String, val tag: String, val pushTime: String, val stops: List<BusStop>
    )

    private val pushFrameLock = SynchronizedObject()
    private val parsedPushFrames = linkedMapOf<PushFrameKey, ParsedBuses>()

    /** 每个推送 tag 的连续空帧计数（按 tag+pushTime 去重，非空帧清零）；达到阈值才允许清空车辆列表 */
    private val emptyFrameRuns = HashMap<String, Int>()

    /** 每个 tag 最近一次参与计数/判重的帧时间戳：同 tag 多订阅共享同一物理帧，不重复计数 */
    private val countedPushTimes = HashMap<String, String>()

    /** 官方后台没有班次接口，返回空表，调用方按首末班展示 */
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
     * 缺 `siteList` / `state` 的线路记录跳过；缺 `busList` 显示「暂无实时数据」。
     */
    private fun buildStopLineStatuses(stopLines: JSONArray, targetSId: Int, requestArea: MeishanArea): List<NearbyStopLineStatus> {
        val statuses = ArrayList<NearbyStopLineStatus>()
        for (i in 0 until stopLines.length()) {
            val lineJson = stopLines.optJSONObject(i) ?: continue
            val state = lineJson.intOrNull("state") ?: continue
            val lId = lineJson.intOrNull("l_id")?.takeIf { it >= 0 } ?: continue
            val area = lineJson.intOrNull("areaId")?.let(MeishanArea::fromAreaId) ?: requestArea
            val sites: JSONArray = lineJson.optJSONArray("siteList") ?: continue
            val stops = parseStops(sites)
            // 同一 s_id 在站表里出现多次（如环线首末站）时取首个命中，与旧客户端一致
            val targetPos = stops.indexOfFirst { it.stopId == encodeStopId(targetSId) }
            if (targetPos < 0) continue // 该线路记录里没有本站（异常数据），跳过
            val target = stops[targetPos]

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

            val buses: JSONArray? = lineJson.optJSONArray("busList")
            val ref = MeishanIds.Ref(area.areaId, lId, state)
            val scheduled = buses?.let {
                val copyId = lineJson.intOrNull("l_copy_id")?.takeIf { id -> id > 0 }
                splitBySchedule(parseBuses(ref, it, stops, copyId), stops, line.firstTime, line.lastTime)
            }
            // 筛「还没到本站」的车：按站表位置比较，不比较原始 index 数值
            val approaching = scheduled?.active.orEmpty()
                .filter { bus -> busPosOf(stops, bus.order).let { it in 0..targetPos } }
                .sortedBy { targetPos - busPosOf(stops, it.order) }
            val approachingWithEta = withEta(stops, approaching, targetPos)

            val nearest = approachingWithEta.firstOrNull()
            val statusText = when {
                scheduled == null -> "暂无实时数据"
                nearest == null -> when (scheduled.schedule) {
                    ServiceHours.State.AFTER_LAST -> "末班已过"
                    ServiceHours.State.BEFORE_FIRST -> "等待发车"
                    else -> "暂无车辆"
                }
                ArrivalRules.isBusArrivedAtOrder(target.order, nearest) -> "已到站"
                nearest.stopsTo(target.order) == 0 -> "下一站就到"
                else -> "${nearest.stopsTo(target.order)}站"
            }

            statuses.add(
                NearbyStopLineStatus(
                    line = line,
                    targetOrder = target.order,
                    targetStopId = target.stopId,
                    targetStopName = target.name,
                    statusText = statusText,
                    approachingBuses = approachingWithEta.take(MAX_APPROACHING)
                )
            )
        }
        return statuses
    }

    private fun busPosOf(stops: List<BusStop>, order: Int): Int =
        stops.indexOfFirst { it.order == order }

    /** 站表：返回顺序即停靠顺序；缺 s_id / 站名 / index 的站跳过，不按数组位置编造 index */
    private fun parseStops(siteList: JSONArray): List<BusStop> {
        val stops = ArrayList<BusStop>(siteList.length())
        for (i in 0 until siteList.length()) {
            val site = siteList.optJSONObject(i) ?: continue
            val sId = site.intOrNull("s_id")?.takeIf { it >= 0 } ?: continue
            val index = site.intOrNull("index") ?: continue
            val siteName = cleanStopName(site.optString("s_name"))
            if (siteName.isBlank()) continue
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
        return stops
    }

    // ---- 线路详情解析 ----

    /** [l_remark] 自带「票价」字样（如「票 价 1 元 (另:空调开放1.5元)」），剥掉后 UI 的「票价 %1$s」才不重复 */
    private fun String.stripFareLabel(): String = replaceFirst(Regex("^票\\s*价\\s*"), "").trim()

    private fun JSONObject.toBusLineDetail(ref: MeishanIds.Ref, request: LineDetailRequest): BusLineDetail {
        // 响应与请求不一致（线路或方向）时不能把它套到这个 lineId 上
        intOrNull("l_id")?.let {
            if (it != ref.lId) throw MeishanResponseFormatException("眉山线路详情 l_id 与请求不一致")
        }
        intOrNull("state")?.let {
            if (it != ref.state) throw MeishanResponseFormatException("眉山线路详情 state 与请求不一致")
        }
        val copyId = intOrNull("l_copy_id")?.takeIf { it > 0 }
        copyId?.let { rememberCopyId(ref, it) }

        val area = MeishanArea.fromAreaId(ref.areaId)
        val name = optString("l_name").trim()
        val directionText = if (ref.state == 0) optString("l_sxpz") else optString("l_xxpz")
        val reverseText = if (ref.state == 0) optString("l_xxpz") else optString("l_sxpz")
        val first = if (ref.state == 0) optString("start_time") else optString("xx_start_time")
        val last = if (ref.state == 0) optString("end_time") else optString("xx_end_time")
        val (startStop, endStop) = splitDirectionText(directionText)
        // l_remark 是票价文案：剥掉自带「票价」字样放进 price 供详情页 meta 行展示；
        // 反方向没有站表但票价同行，直接带上，方向切换后的详情会再校准。
        val fare = optString("l_remark").trim()
        val farePrice = fare.stripFareLabel()

        val line = BusLineDirection(
            lineId = MeishanIds.encode(area.areaId, ref.lId, ref.state),
            name = name,
            lineNo = name,
            direction = ref.state,
            startStop = startStop,
            endStop = endStop,
            firstTime = first.trim(),
            lastTime = last.trim(),
            price = farePrice,
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
                price = farePrice,
                source = DataSourceType.MEISHAN
            )
        }

        val siteList = optJSONArray("siteList")
            ?: throw MeishanPayloadMissingException("眉山线路详情缺少 siteList（l_id=${ref.lId} state=${ref.state}）")
        val stops = parseStops(siteList)

        val busList = optJSONArray("busList")
            ?: return BusLineDetail(
                // 缺 busList 是「没有实时数据」，不是「没有车」
                line = line,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = emptyList(),
                operatingState = LineOperatingState.NO_REALTIME,
                remark = fare
            )
        val scheduled = splitBySchedule(parseBuses(ref, busList, stops, copyId), stops, first, last)
        val targetPos = resolveTargetPos(stops, request.targetOrder, request.stationId)
        return BusLineDetail(
            line = line,
            reverseDirection = reverseDirection,
            stops = stops,
            buses = withEta(stops, scheduled.active, targetPos),
            operatingState = scheduled.state,
            idleBuses = scheduled.idle,
            remark = fare
        )
    }

    /** 车辆按时刻表分组：[active] 参与到站计算，[idle] 只展示 */
    private class ScheduledBuses(
        val active: List<LiveBus>,
        val idle: List<LiveBus>,
        val schedule: ServiceHours.State,
        val state: LineOperatingState
    )

    /**
     * 运营状态与车流分开（主文 §6.4）：时刻表只决定车辆是否参与到站计算，车辆本身都保留。
     * - 运营时段内或首末班未提供：长时间未移动的车放进 idle，其余参与计算；
     * - 末班后宽限期内：只有还在线路上跑的车参与计算；
     * - 其他非运营时段：全部放进 idle（多为归场车快照，旧文档的观测）。
     */
    private fun splitBySchedule(parsed: ParsedBuses, stops: List<BusStop>, first: String, last: String): ScheduledBuses {
        val schedule = scheduleState(first, last)
        val inService = schedule == ServiceHours.State.IN_SERVICE || schedule == ServiceHours.State.UNKNOWN
        if (inService) {
            return ScheduledBuses(parsed.moving, parsed.still, schedule, LineOperatingState.RUNNING)
        }
        val (running, finished) = if (isInLastBusGrace(first, last)) {
            parsed.moving.partition { stillRunningAfterLastBus(it, stops, last) }
        } else {
            emptyList<LiveBus>() to parsed.moving
        }
        val state = when {
            running.isNotEmpty() -> LineOperatingState.RUNNING
            schedule == ServiceHours.State.BEFORE_FIRST -> LineOperatingState.WAITING
            else -> LineOperatingState.ENDED
        }
        return ScheduledBuses(running, finished + parsed.still, schedule, state)
    }

    /**
     * 首末班 vs 北京时间。首末班缺失、格式不对或首末相同（如 `00:00/00:00`，多为未配置）时为 UNKNOWN，
     * 不把它当成真实时刻去清空车辆。
     */
    private fun scheduleState(first: String, last: String): ServiceHours.State {
        val f = TimetableRules.normalizeClock(first) ?: return ServiceHours.State.UNKNOWN
        val l = TimetableRules.normalizeClock(last) ?: return ServiceHours.State.UNKNOWN
        if (f == l) return ServiceHours.State.UNKNOWN
        return ServiceHours.state(beijingNowHHMM(), listOf(ServiceHours.Window(first = f, last = l)))
    }

    /**
     * 给每辆车补「到目标站的点距累计 + 估算 ETA」。
     * [targetPos] 为 null 时按终点站（LiveBus 契约「目标站（或终点站）」）。
     * 距离 = 车辆 GPS → 下一站 → … → 目标站 逐段直线累加（不是道路里程）；
     * 耗时 = 距离 ÷ 该车车速，车速无效（<2km/h，可能在等灯）时退回固定 4 m/s（14.4 km/h）。
     */
    private fun withEta(stops: List<BusStop>, buses: List<LiveBus>, targetPos: Int?): List<LiveBus> {
        if (stops.isEmpty()) return buses
        val target = targetPos ?: stops.lastIndex
        return buses.map { bus ->
            val busPos = stops.indexOfFirst { it.order == bus.order }
            val meters = when {
                busPos < 0 || busPos > target -> null // 已过目标站 / 站序异常：不给 ETA
                busPos == target && bus.isArrived -> 0
                else -> pointDistanceMeters(stops, if (bus.isArrived) busPos else busPos - 1, target, bus.wgsLat, bus.wgsLng)
            } ?: return@map bus
            bus.copy(
                distanceToTargetMeters = meters,
                etaSeconds = etaSeconds(meters, bus.speedKmh, arrivedAtTarget = bus.isArrived && busPos == target)
            )
        }
    }

    /** 目标站下标：站序与站 ID 都对上优先；站序变了按站 ID；都没给或找不到返回 null（调用方回退终点站） */
    private fun resolveTargetPos(stops: List<BusStop>, targetOrder: Int?, stationId: String?): Int? {
        val order = targetOrder?.takeIf { it > 0 }
        val id = stationId?.takeIf { it.startsWith(STOP_ID_PREFIX) }
        if (order != null && id != null) {
            stops.indexOfFirst { it.order == order && it.stopId == id }.takeIf { it >= 0 }?.let { return it }
            stops.indexOfFirst { it.stopId == id }.takeIf { it >= 0 }?.let { return it }
        }
        order?.let { o -> stops.indexOfFirst { it.order == o }.takeIf { it >= 0 }?.let { return it } }
        id?.let { i -> stops.indexOfFirst { it.stopId == i }.takeIf { it >= 0 }?.let { return it } }
        return null
    }

    /** 车辆 GPS → s[busPos+1] → … → s[targetPos] 的逐段直线距离（米） */
    private fun pointDistanceMeters(
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

    /**
     * 距离 ÷ 速度：速度无效时按固定 4 m/s（14.4 km/h）的平均运营假设。
     * 没停靠在目标站时至少 1 秒：0 只表示已到站。
     */
    private fun etaSeconds(meters: Int, speedKmh: Double, arrivedAtTarget: Boolean): Int {
        if (arrivedAtTarget) return 0
        val effectiveKmh = if (speedKmh >= SPEED_NOISE_KMH) speedKmh else DEFAULT_SPEED_KMH
        return (meters / (effectiveKmh / 3.6)).roundToInt().coerceAtLeast(1)
    }

    /** 一次车辆快照的解析结果：[still] 是长时间未移动的车 */
    private class ParsedBuses(val moving: List<LiveBus>, val still: List<LiveBus>) {
        /** 解析后没有任何车辆（空帧，或车辆全部被过滤） */
        val isEmpty: Boolean get() = moving.isEmpty() && still.isEmpty()
    }

    /**
     * 车辆快照解析。
     * - `index` 与站表 `index` 按值匹配，匹配不到的车不纳入（不给 `index=0` 等值赋予业务含义）；
     * - 车辆自带的 `state` / `l_copy_id` 存在且与线路方向不一致时剔除；
     * - 速度：`b_sd`（km/h）有效直接用，无效时用相邻帧位移兜底；
     * - 长时间未移动的车放进 [ParsedBuses.still]，不删除。
     */
    private fun parseBuses(ref: MeishanIds.Ref, busList: JSONArray, stops: List<BusStop>, copyId: Int?): ParsedBuses {
        val nowMs = monotonicClock.elapsedMillis()
        val lineKey = "${ref.areaId}_${ref.lId}_${ref.state}"
        var anonymousSlot = 0
        val stopOrders = stops.map { it.order }

        val moving = ArrayList<LiveBus>(busList.length())
        val still = ArrayList<LiveBus>()
        for (i in 0 until busList.length()) {
            val bus = busList.optJSONObject(i) ?: continue
            val busState = bus.intOrNull("state")
            if (busState != null && busState != ref.state) continue
            val busCopyId = bus.intOrNull("l_copy_id")
            if (copyId != null && busCopyId != null && busCopyId != copyId) continue
            // 眉山/仁寿 copyId 撞号，服务端错投的异区域车辆在此逐辆剔除；
            // 仓库层已有整帧 areaId 门禁，这里是直接调用方的纵深防御。
            val busAreaId = bus.intOrNull("areaId")
            if (busAreaId != null && busAreaId != ref.areaId) continue
            val index = bus.intOrNull("index") ?: continue
            val position = stops.indexOfFirst { it.order == index }
            if (position < 0) continue
            val plate = bus.optString("b_plate_num").trim()
            // 缺失按 0（已离开 index 站）处理，与旧客户端解析默认值一致
            val arrived = bus.optInt("isArriveSite", 0) == 1
            // index 是已到达 / 最近驶离的站；通用模型的 order 是正在接近的站。
            val next = stops.getOrNull(position + 1)
            val order = if (arrived) index else next?.order ?: index
            val lat = bus.optDouble("b_latitude", 0.0)
            val lng = bus.optDouble("b_longitude", 0.0)
            val progress = if (arrived) 1.0f else RouteProjectionMath.computeSegmentProgress(
                lat, lng, false, stops[position], next
            ).coerceIn(0.0f, 0.97f)
            val reported = bus.optDouble("b_sd", 0.0)
            // b_id 常为 0：有车牌时不把站序或数组位置混入身份。
            // 无车牌优先用有效 b_id，否则使用匿名车序号；匿名车只用下发速度，不做跨帧测速和静止计时
            // （数组顺序不能代替车辆身份）。
            val namedIdentity = plate.ifBlank { bus.intOrNull("b_id")?.takeIf { it > 0 }?.let { "id:$it" }.orEmpty() }
            val identity = namedIdentity.ifBlank { "anonymous:${anonymousSlot++}" }
            val motion = if (namedIdentity.isNotBlank()) {
                synchronized(motionLock) { trackMotion(lineKey, namedIdentity, lat, lng, reported, nowMs) }
            } else {
                Motion(speedKmh = reported.takeIf { it.isFinite() && it > 0.0 } ?: 0.0, still = false)
            }

            val liveBus = LiveBus(
                // 车牌只留在内存展示用；ID 用摘要，追踪会话落盘时不带原车牌
                busId = "msb:${ref.areaId}:${ref.lId}:${ref.state}:${stableHash(identity)}",
                plateOrFleetNo = plate,
                order = order,
                segmentProgress = progress,
                isArrived = arrived,
                wgsLat = lat,
                wgsLng = lng,
                speedKmh = motion.speedKmh,
                nextStopName = if (arrived) stops[position].name else next?.name,
                stopOrders = stopOrders,
                hasAuthoritativeArrival = true
            )
            if (motion.still) still.add(liveBus) else moving.add(liveBus)
        }
        return ParsedBuses(moving, still)
    }

    private class Motion(val speedKmh: Double, val still: Boolean)

    /**
     * 单辆车速度 + 静止计时。调用方持有 [motionLock]。
     * - b_sd 有效（>0，km/h）直接用；
     * - 无效时若上一帧可比（2–120s）且位移合理，按位移估算；
     * - 无下发速度、离开始静止的位置不到 30m → 记下开始静止的时刻，持续满 [STILL_AFTER_MS] 视为长时间未移动。
     */
    private fun trackMotion(lineKey: String, identity: String, lat: Double, lng: Double, reported: Double, nowMs: Long): Motion {
        val line = motionSamples.remove(lineKey) ?: linkedMapOf()
        val previous = line[identity]
        val hasReportedSpeed = reported.isFinite() && reported > 0.0
        var speedKmh = if (hasReportedSpeed) reported else 0.0
        if (previous != null && !hasReportedSpeed) {
            val dtSec = (nowMs - previous.timeMs) / 1000.0
            val meters = RouteProjectionMath.calculateDistanceMeters(previous.lat, previous.lng, lat, lng)
            if (dtSec in SPEED_DT_MIN..SPEED_DT_MAX && meters != null && meters >= STILL_MOVE_METERS) {
                val kmh = (meters / dtSec) * 3.6
                if (kmh in SPEED_NOISE_KMH..SPEED_MAX_KMH) speedKmh = kmh
            }
        }
        // 以开始静止的位置为锚点，慢慢漂移的 GPS 也不会让计时一直重来
        val anchor = previous?.stillSince?.let { since ->
            val drift = RouteProjectionMath.calculateDistanceMeters(previous.stillLat, previous.stillLng, lat, lng)
            if (!hasReportedSpeed && drift != null && drift < STILL_MOVE_METERS) since else null
        }
        val sample = when {
            anchor != null ->
                MotionSample(lat, lng, nowMs, stillSince = anchor, stillLat = previous.stillLat, stillLng = previous.stillLng)
            hasReportedSpeed -> MotionSample(lat, lng, nowMs)
            else -> MotionSample(lat, lng, nowMs, stillSince = nowMs, stillLat = lat, stillLng = lng)
        }
        line.remove(identity)
        if (line.size >= MAX_STILL_BUSES) line.remove(line.keys.first())
        line[identity] = sample
        if (motionSamples.size >= MAX_STILL_LINES) motionSamples.remove(motionSamples.keys.first())
        motionSamples[lineKey] = line
        val still = sample.stillSince != null && nowMs - sample.stillSince >= STILL_AFTER_MS
        return Motion(speedKmh, still)
    }

    /**
     * 一辆车上一帧的位置；[stillSince] 是开始静止（无下发速度、离锚点 < 30m）的时刻，
     * [stillLat] / [stillLng] 是开始静止时的位置（锚点）。在动的车 [stillSince] 为 null。
     */
    private data class MotionSample(
        val lat: Double,
        val lng: Double,
        val timeMs: Long,
        val stillSince: Long? = null,
        val stillLat: Double = lat,
        val stillLng: Double = lng
    )

    /** 每个线路方向一份上一帧位置（内存态，进程内有效）。线路详情 / 站点状态两条路径共用，访问走 [motionLock] */
    private val motionSamples = linkedMapOf<String, LinkedHashMap<String, MotionSample>>()
    private val motionLock = SynchronizedObject()

    /** FNV-1a 64 位摘要：车辆身份在 HTTP / TCP / 进程重启之间保持一致，不暴露原车牌 */
    private fun stableHash(value: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    // ---- 小工具 ----

    private fun beijingNowHHMM(): String =
        wallClock.now().toLocalDateTime(BEIJING).let { t ->
            "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        }

    /**
     * 末班（官方给的是始发站发车时刻）之后 [LAST_BUS_GRACE_MINUTES] 分钟内：末班车可能还在路上。
     * 只在时刻表判为非运营时成立；首末班缺失或格式不对时不成立。
     */
    private fun isInLastBusGrace(first: String, last: String): Boolean {
        val lastMinute = minuteOfDay(last) ?: return false
        val firstMinute = minuteOfDay(first) ?: return false
        if (firstMinute == lastMinute) return false
        val nowMinute = minuteOfDay(beijingNowHHMM()) ?: return false
        return scheduleState(first, last) != ServiceHours.State.IN_SERVICE &&
            minutesSince(lastMinute, nowMinute) in 1..LAST_BUS_GRACE_MINUTES
    }

    /**
     * 末班后的宽限期里，车是否还在线路上跑：
     * - 已到终点站的车跑完了；
     * - 末班发出 [ORIGIN_DEPARTURE_TOLERANCE_MINUTES] 分钟后还停在始发站的车不会再发。
     */
    private fun stillRunningAfterLastBus(bus: LiveBus, stops: List<BusStop>, last: String): Boolean {
        if (stops.isEmpty()) return true
        val lastMinute = minuteOfDay(last)
        val nowMinute = minuteOfDay(beijingNowHHMM())
        val pastDeparture = lastMinute != null && nowMinute != null &&
            minutesSince(lastMinute, nowMinute) > ORIGIN_DEPARTURE_TOLERANCE_MINUTES
        val pos = stops.indexOfFirst { it.order == bus.order }
        return when {
            pos == stops.lastIndex && bus.isArrived -> false
            pos == 0 && bus.isArrived && pastDeparture -> false
            else -> true
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

    /** "眉山东站:凯旋国际社区" → (起, 终)；缺分隔就整段做起站 */
    private fun splitDirectionText(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val sep = trimmed.indexOfFirst { it == ':' || it == '：' }
        if (sep <= 0 || sep >= trimmed.length - 1) return trimmed to ""
        return trimmed.substring(0, sep).trim() to trimmed.substring(sep + 1).trim()
    }

    /** 站名展示清洗：官方站名可能带 `_跑法2` / `._跑法2` 调度别名后缀。只用于展示，站点关联用 s_id */
    private fun cleanStopName(raw: String): String =
        raw.trim().substringBefore("._").substringBefore('_').trim()
}
