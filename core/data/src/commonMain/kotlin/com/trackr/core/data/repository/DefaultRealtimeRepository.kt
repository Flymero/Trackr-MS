package com.trackr.core.data.repository

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.domain.arrival.StopOrderResolver
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.common.SingleFlightCache
import com.trackr.core.network.remote.common.suspendRunCatching
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * 系统自带的逆地理（Android：`android.location.Geocoder`；iOS 暂无）。
 * 数据源的逆地理拿不到城市时才用它兜底。
 */
fun interface PlatformGeocoder {

    /** 返回 (城市名, 区县名)；不可用或失败时返回 null */
    suspend fun locate(wgsLat: Double, wgsLng: Double): Pair<String?, String?>?

    companion object {
        val None: PlatformGeocoder = PlatformGeocoder { _, _ -> null }
    }
}

/**
 * [RealtimeRepository] 的实现：查询逻辑原样搬自 `TransitRepository`（重构计划 §2.5）。
 * 设置、收藏、搜索历史仍由 `TransitRepository`（第 9 步换存储）提供，这里只读。
 *
 * @param clock 线路详情短缓存用的单调时钟；测试可注入
 * @param fetchScope 线路详情合并请求的执行作用域；测试可注入
 */
class DefaultRealtimeRepository(
    private val coordinator: TransitProviderCoordinator,
    private val settings: SettingsRepository,
    private val favoritesRepository: FavoritesRepository,
    private val searchHistory: SearchHistoryRepository,
    /** 站点详情 / 搜索没传用户坐标时用的最近定位（WGS-84） */
    private val userLocation: () -> Pair<Double, Double>?,
    private val geocoder: PlatformGeocoder = PlatformGeocoder.None,
    clock: MonotonicClock = ProcessMonotonicClock,
    fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RealtimeRepository {

    /** 内存中的静态站表缓存（lineId -> List<BusStop>），受 [stopsLock] 保护 */
    private val stopsLock = SynchronizedObject()
    private val stopsCache = HashMap<String, List<BusStop>>()

    /**
     * 线路详情 key：同一数据源 / 城市 / 线路 / 候车站的请求视为同一份数据
     */
    private data class LineDetailKey(
        val source: DataSourceType,
        val cityId: String,
        val mygolbsCityCode: String,
        val lineId: String,
        val targetOrder: Int?,
        val stationId: String?
    )

    /**
     * 线路详情 in-flight 去重 + 短 TTL 缓存：
     * 首页收藏、详情页、追踪服务、小组件等同时请求同一线路时只发一次上游请求。
     */
    private val lineDetailFlights = SingleFlightCache<LineDetailKey, BusLineDetail>(
        ttlMillis = LINE_DETAIL_CACHE_TTL_MS,
        maxEntries = LINE_DETAIL_CACHE_MAX_ENTRIES,
        scope = fetchScope,
        clock = clock
    )

    private val _lineDetailUpdates = MutableSharedFlow<LineDetailUpdate>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val lineDetailUpdates: Flow<LineDetailUpdate> = _lineDetailUpdates.asSharedFlow()

    /** 收藏卡「没车时下一班」的时刻表短缓存：同一条线 30 秒内只查一次 */
    private val favoriteTimetableFlights = SingleFlightCache<String, LineTimetable>(
        ttlMillis = FAVORITE_TIMETABLE_TTL_MS,
        maxEntries = 64,
        scope = fetchScope,
        clock = clock
    )

    /** 最近一次拿到的城市列表（`TransitRepository.setEngineMode` 补掌上公交编码时读） */
    @Volatile
    var cachedCities: List<TransitCity> = emptyList()
        private set

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> = runCatching {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return@runCatching cachedCities
        }
        val cities = coordinator.getSupportedCities(forceRefresh)
        cachedCities = cities
        cities
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> = runCatching {
        withContext(Dispatchers.IO) {
            // 1) 优先尝试雷达 Provider GPS 逆地理服务（地级市 + 区县）
            var resolvedName: String? = coordinator.resolveCityByLocation(wgsLat, wgsLng)
            var rawDistrict: String? = coordinator.resolveDistrictByLocation(wgsLat, wgsLng)

            // 2) 若为空，尝试系统自带的逆地理（Android Geocoder）
            if (resolvedName.isNullOrBlank()) {
                try {
                    geocoder.locate(wgsLat, wgsLng)?.let { (city, district) ->
                        resolvedName = city
                        if (rawDistrict.isNullOrBlank()) rawDistrict = district
                    }
                } catch (_: Exception) {
                }
            }

            val supportedCities = getSupportedCities().getOrDefault(emptyList())

            // 2.5) 定位在县 / 县级市时，优先匹配支持列表里的同名县城（Issue #6）
            matchCountyLevelCity(rawDistrict, resolvedName, supportedCities)?.let { county ->
                return@withContext county.copy(
                    province = county.province.ifBlank { ProvinceCityCatalog.inferProvince(county.cityName) }
                )
            }

            // 3) 若仍为空，使用内置离线核心城市 Haversine 距离（80km 以内）兜底
            if (resolvedName.isNullOrBlank()) {
                resolvedName = ProvinceCityCatalog.findNearestCityName(wgsLat, wgsLng)
            }

            val cityName = resolvedName
            if (cityName.isNullOrBlank()) {
                return@withContext null
            }

            val cleanTarget = ProvinceCityCatalog.cleanCityName(cityName)

            // 4) 在支持的城市列表中匹配（无后缀精准与包含匹配）
            val matched = supportedCities.firstOrNull { city ->
                val cleanItem = ProvinceCityCatalog.cleanCityName(city.cityName)
                cleanItem == cleanTarget ||
                        cleanItem.contains(cleanTarget) ||
                        cleanTarget.contains(cleanItem)
            }

            if (matched != null) {
                val prov = if (matched.province.isNotBlank()) {
                    matched.province
                } else {
                    ProvinceCityCatalog.inferProvince(matched.cityName)
                }
                return@withContext matched.copy(province = prov)
            }

            // 若 getSupportedCities() 中未包含，但为 ProvinceCityCatalog 中已知城市，构造返回以确保定位切换城市永不失败
            if (cleanTarget.isNotBlank() && ProvinceCityCatalog.isKnownCity(cleanTarget)) {
                val inferredProvince = ProvinceCityCatalog.inferProvince(cleanTarget)
                return@withContext TransitCity(
                    cityId = "",
                    cityName = cleanTarget,
                    province = inferredProvince
                )
            }

            null
        }
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> = runCatching {
        coordinator.getNearbyStops(city, wgsLat, wgsLng, limit, settings.engineMode.value)
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        stopId: String?,
        stopName: String?,
        physicalStId: String?,
        namesakeStId: String?,
        stationLat: Double?,
        stationLng: Double?,
        userLat: Double?,
        userLng: Double?
    ): Result<List<NearbyStop>> = suspendRunCatching {
        val location = userLocation()
        val request = StopDetailRequest(
            stopId = stopId,
            stopName = stopName,
            physicalStId = physicalStId,
            namesakeStId = namesakeStId,
            stationLat = stationLat,
            stationLng = stationLng,
            userLat = userLat ?: location?.first,
            userLng = userLng ?: location?.second
        )
        coordinator.getStopDetail(city, request, settings.engineMode.value)
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> = runCatching {
        val location = userLocation()
        val result = coordinator.search(
            city = city,
            keyword = keyword,
            mode = settings.engineMode.value,
            userLat = userLat ?: location?.first,
            userLng = userLng ?: location?.second
        )
        if (result.lines.isNotEmpty() || result.stops.isNotEmpty()) {
            searchHistory.addSearchHistory(keyword)
        }
        result
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        lineId: String,
        targetOrder: Int?,
        stationId: String?,
        wgsLat: Double?,
        wgsLng: Double?,
        source: DataSourceType,
        lineNameHint: String,
        directionHint: Int
    ): Result<BusLineDetail> {
        val request = LineDetailRequest(
            lineId = lineId,
            targetOrder = targetOrder,
            stationId = stationId,
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            source = source,
            lineNameHint = lineNameHint,
            directionHint = directionHint
        )
        val key = LineDetailKey(
            source = source,
            cityId = city.cityId,
            mygolbsCityCode = city.mygolbsCityCode.orEmpty(),
            lineId = lineId,
            targetOrder = targetOrder,
            stationId = stationId
        )
        return suspendRunCatching {
            lineDetailFlights.get(key) {
                fetchLineDetail(city, request).also { detail ->
                    // 每份真正从上游拿到的数据都广播出去：追踪服务等车时直接用页面刚拿到的这份刷新通知，
                    // 通知和页面就是同一份数据，也不多打一次请求（issue #41）
                    _lineDetailUpdates.tryEmit(LineDetailUpdate(source, lineId, targetOrder, detail))
                }
            }
        }
    }

    private suspend fun fetchLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail {
        val lineId = request.lineId
        val detail = coordinator.getLineDetail(city, request, settings.engineMode.value)

        // 静态站表内存缓存维护
        val effectiveStops = synchronized(stopsLock) {
            if (detail.stops.isNotEmpty()) {
                stopsCache[lineId] = detail.stops
                detail.stops
            } else {
                stopsCache[lineId] ?: detail.stops
            }
        }

        return if (effectiveStops !== detail.stops) {
            detail.copy(stops = effectiveStops)
        } else {
            detail
        }
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> = runCatching {
        coordinator.getTimetable(city, line, atTimeHHMM, settings.engineMode.value)
    }

    override fun stopDetailIncludesBuses(source: DataSourceType): Boolean =
        coordinator.providerFor(source)?.stopDetailIncludesBuses() ?: true

    /** 收藏线路没车、线路详情又没给下一班时，用时刻表兜一次（30 秒内同一条线复用） */
    private suspend fun nextDepartureFromTimetable(city: TransitCity, line: BusLineDirection): String? {
        val key = "${line.source.name}|${line.lineId}|${line.direction}|${city.cityId}|${city.mygolbsCityCode.orEmpty()}"
        val timetable = runCatching {
            favoriteTimetableFlights.get(key) {
                coordinator.getTimetable(city, line, null, settings.engineMode.value)
            }
        }.getOrNull() ?: return null
        return timetable.nextDeparture
    }

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> = runCatching {
        val favs = favoritesRepository.favorites.value
        val stations = favoritesRepository.favoriteStations.value
        if (favs.isEmpty() && stations.isEmpty()) return@runCatching emptyMap()

        coroutineScope {
            // 收藏的站（issue #47）：每个站 1 次站点请求，找回默认站台
            val stationTasks = stations.map { station -> async { station.id to fetchFavoriteStation(station) } }
            val tasks = favs.map { fav ->
                async {
                    val city = TransitCity(
                        cityId = fav.cityId,
                        cityName = fav.cityName,
                        mygolbsCityCode = fav.mygolbsCityCode.takeIf { it.isNotBlank() }
                    )
                    suspend fun fetch(order: Int) = runCatching {
                        getLineDetail(
                            city = city,
                            lineId = fav.lineId,
                            targetOrder = order,
                            stationId = fav.targetStopId,
                            source = fav.source
                        ).getOrNull()
                    }.getOrNull()

                    var targetOrder = fav.targetOrder
                    var lineRes = fetch(targetOrder)
                    // 早先从掌上公交站点页收藏的线，站序可能是错推的（issue #41「900 站」）：按站名找回真站序再查一次
                    val corrected = lineRes?.let {
                        StopOrderResolver.correctedOrder(
                            stops = it.stops,
                            order = targetOrder,
                            stopName = fav.targetStopName,
                            lat = fav.wgsLat,
                            lng = fav.wgsLng,
                            nameMismatchIsSuspect = fav.source == DataSourceType.MYGOLBS
                        )
                    }
                    if (corrected != null) {
                        targetOrder = corrected
                        lineRes = fetch(corrected) ?: lineRes
                    }

                    if (lineRes != null) {
                        val approaching = lineRes.buses.filter { it.order <= targetOrder }
                        // 车来了的线路详情直接给下一班；掌上公交只有部分城市给 planTime，
                        // 没车又没给时回落时刻表，收藏卡才不会只剩「暂无在途车辆」。
                        val nextDeparture = lineRes.nextDepartureFromStart
                            ?: if (approaching.isEmpty()) {
                                nextDepartureFromTimetable(city, lineRes.line)
                            } else {
                                null
                            }
                        val lineStatus = NearbyStopLineStatus(
                            line = lineRes.line,
                            targetOrder = targetOrder,
                            targetStopId = fav.targetStopId,
                            targetStopName = fav.targetStopName,
                            statusText = lineRes.statusDescription,
                            preArrivalTime = nextDeparture,
                            approachingBuses = approaching
                        )
                        val stop = NearbyStop(
                            stopId = fav.targetStopId,
                            name = fav.targetStopName,
                            wgsLat = fav.wgsLat,
                            wgsLng = fav.wgsLng,
                            lines = listOf(lineStatus)
                        )
                        fav.id to stop
                    } else null
                }
            }
            val stationResults = stationTasks.awaitAll().mapNotNull { (id, stop) ->
                stop?.let { FavoriteItem.stationKey(id) to it }
            }
            tasks.awaitAll().filterNotNull().toMap() + stationResults
        }
    }

    /**
     * 查一个收藏的站的默认站台。请求失败返回 null（首页沿用「正在查车」）；请求成功但找不到站台时返回一个
     * 没有线路的站（首页显示「暂时查不到」）。
     */
    private suspend fun fetchFavoriteStation(station: FavoriteStation): NearbyStop? {
        val city = TransitCity(
            cityId = station.cityId,
            cityName = station.cityName,
            mygolbsCityCode = station.mygolbsCityCode.takeIf { it.isNotBlank() }
        )
        val platforms = getStopDetail(
            city = city,
            stopId = station.stopId.takeIf { it.isNotBlank() },
            stopName = station.stopName.takeIf { it.isNotBlank() },
            physicalStId = station.physicalStId.takeIf { it.isNotBlank() },
            namesakeStId = station.namesakeStId.takeIf { it.isNotBlank() },
            stationLat = station.wgsLat.takeIf { it > 0.0 },
            stationLng = station.wgsLng.takeIf { it > 0.0 }
        ).getOrNull() ?: return null
        return StationPlatformMatcher.pick(platforms, station)
            ?: NearbyStop(stopId = station.stopId, name = station.stopName, source = station.source)
    }

    companion object {
        /** 线路详情短缓存时长：小于详情页 10 秒轮询周期，保证每轮轮询仍拿到新数据 */
        const val LINE_DETAIL_CACHE_TTL_MS = 6_000L
        const val LINE_DETAIL_CACHE_MAX_ENTRIES = 64

        /** 收藏卡时刻表回落缓存时长 */
        const val FAVORITE_TIMETABLE_TTL_MS = 30_000L
    }
}

/**
 * 把逆地理得到的区县名匹配为支持列表里的县城（Issue #6）。
 *
 * - 只认 "县"/"市"/"旗" 结尾的县级单位；"区" 是地级市的市辖区，交给地级市匹配，
 *   避免北京 "朝阳区" 被认成辽宁 "朝阳"。
 * - 清洗后名字必须完全相同，不用包含匹配。
 * - 能推断出省份时，县城所在省须与定位地级市所在省一致。
 */
internal fun matchCountyLevelCity(
    rawDistrict: String?,
    prefectureName: String?,
    supportedCities: List<TransitCity>
): TransitCity? {
    val district = rawDistrict?.trim().orEmpty()
    if (district.isBlank()) return null
    if (!(district.endsWith("县") || district.endsWith("市") || district.endsWith("旗"))) return null

    val cleanDistrict = ProvinceCityCatalog.cleanCityName(district)
    if (cleanDistrict.isBlank()) return null
    if (!prefectureName.isNullOrBlank() && ProvinceCityCatalog.cleanCityName(prefectureName) == cleanDistrict) {
        return null
    }

    val expectedProvince = prefectureName
        ?.takeIf { it.isNotBlank() }
        ?.let { ProvinceCityCatalog.cleanCityName(ProvinceCityCatalog.inferProvince(it)) }
        ?.takeIf { it.isNotBlank() && it != "其他" }

    return supportedCities.firstOrNull { city ->
        if (ProvinceCityCatalog.cleanCityName(city.cityName) != cleanDistrict) return@firstOrNull false
        if (expectedProvince == null) return@firstOrNull true
        val cityProvince = ProvinceCityCatalog.cleanCityName(
            city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
        )
        cityProvince.isBlank() || cityProvince == "其他" || cityProvince == expectedProvince
    }
}
