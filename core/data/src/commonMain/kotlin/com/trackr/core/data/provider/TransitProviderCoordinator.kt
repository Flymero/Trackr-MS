package com.trackr.core.data.provider

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.isPureSubwayStation
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.meishan.MeishanIds
import com.trackr.core.network.remote.mygolbs.MygolbsIds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 数据源模式对应的数据源 */
val EngineMode.dataSource: DataSourceType
    get() = when (this) {
        EngineMode.CHELAILE -> DataSourceType.CHELAILE
        EngineMode.MYGOLBS -> DataSourceType.MYGOLBS
        EngineMode.MEISHAN -> DataSourceType.MEISHAN
    }

/**
 * 实时公交数据源分发协调器（D1：数据源二选一，不做聚合；眉山特化加官方源）。
 *
 * 搜索、附近站、站点详情按当前数据源（[EngineMode]）选一个 Provider；线路详情、时刻表按线路自带的来源或 id 前缀选，
 * 认不出来才用当前数据源。城市列表合并三家，官方源只补眉山 / 仁寿。
 * 官方源不做附近站 / 站点经过线路，官方模式下这两类查询回退掌上公交。
 */
class TransitProviderCoordinator(
    val providers: Map<DataSourceType, ITransitProvider>
) {
    constructor(
        chelaileProvider: ITransitProvider,
        mygolbsProvider: ITransitProvider,
        meishanProvider: ITransitProvider? = null
    ) : this(
        buildMap {
            put(DataSourceType.CHELAILE, chelaileProvider)
            put(DataSourceType.MYGOLBS, mygolbsProvider)
            if (meishanProvider != null) {
                put(DataSourceType.MEISHAN, meishanProvider)
            }
        }
    )

    private var cachedCities: List<TransitCity> = emptyList()

    /** 某个数据源对应的 Provider；认不出来返回 null */
    fun providerFor(source: DataSourceType): ITransitProvider? = providers[source]

    /**
     * 并发获取车来了与掌上公交支持的城市列表，并进行编码与独有城市双源融合
     */
    suspend fun getSupportedCities(forceRefresh: Boolean = false): List<TransitCity> {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return cachedCities
        }

        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        val (chelaileCities, mygolbsCities) = coroutineScope {
            val clDeferred = async {
                runCatching { chelaileProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            val mgDeferred = async {
                runCatching { mygolbsProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            Pair(clDeferred.await(), mgDeferred.await())
        }
        val meishanCities = runCatching { meishanProvider()?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())

        // 构建掌上公交映射 cleanName -> TransitCity
        val mgMap = LinkedHashMap<String, TransitCity>()
        for (mgCity in mygolbsCities) {
            val cleanName = ProvinceCityCatalog.cleanCityName(mgCity.cityName)
            if (cleanName.isNotBlank() && !mgMap.containsKey(cleanName)) {
                mgMap[cleanName] = mgCity
            }
        }

        val matchedMgKeys = HashSet<String>()
        val result = ArrayList<TransitCity>()

        // 1. 以车来了城市为主干进行聚合去重，统一采用前者简洁名称（如“上海”而非“上海市”）
        for (cCity in chelaileCities) {
            val cleanName = ProvinceCityCatalog.cleanCityName(cCity.cityName)
            val mgMatch = mgMap[cleanName]
            val effectiveProv = if (cCity.province.isNotBlank()) {
                cCity.province
            } else {
                ProvinceCityCatalog.inferProvince(cCity.cityName)
            }
            if (mgMatch != null) {
                matchedMgKeys.add(cleanName)
            }
            val displayName = cleanName.ifBlank { cCity.cityName }
            result.add(
                cCity.copy(
                    cityName = displayName,
                    province = effectiveProv,
                    mygolbsCityCode = mgMatch?.mygolbsCityCode
                )
            )
        }

        // 2. 掌上公交独有的城市列表追加合入（只留简洁名称）
        for ((cleanName, mgCity) in mgMap) {
            if (!matchedMgKeys.contains(cleanName)) {
                val effectiveProv = if (mgCity.province.isNotBlank()) {
                    mgCity.province
                } else {
                    ProvinceCityCatalog.inferProvince(mgCity.cityName)
                }
                val displayName = cleanName.ifBlank { mgCity.cityName }
                result.add(
                    mgCity.copy(
                        cityId = "",
                        cityName = displayName,
                        province = effectiveProv
                    )
                )
            }
        }

        // 3. 眉山特化：官方源独有的城市（眉山 / 仁寿）也补进来
        val existingNames = result.mapTo(HashSet()) { it.cityName }
        for (msCity in meishanCities) {
            if (msCity.cityName !in existingNames) {
                result.add(msCity)
                existingNames.add(msCity.cityName)
            }
        }

        cachedCities = result
        return result
    }

    private fun meishanProvider(): ITransitProvider? = providers[DataSourceType.MEISHAN]

    /** 逆地理先问车来了、再问掌上公交（只用来定城市）；官方模式只问官方（就近区域中心判定） */
    private fun geocodeProviders(mode: EngineMode? = null): List<ITransitProvider> {
        if (mode == EngineMode.MEISHAN) {
            return listOfNotNull(providers[DataSourceType.MEISHAN])
        }
        return GEOCODE_ORDER.mapNotNull { providers[it] } +
                providers.values.filter { it.sourceType !in GEOCODE_ORDER }
    }

    /**
     * 逆地理定位：依次查询具备雷达能力的 Provider，返回第一个非空城市名
     */
    suspend fun resolveCityByLocation(
        wgsLat: Double,
        wgsLng: Double,
        mode: EngineMode? = null
    ): String? {
        for (provider in geocodeProviders(mode)) {
            if (provider.supportsLocationRadar()) {
                val resolved = runCatching { provider.resolveCityByLocation(wgsLat, wgsLng) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            }
        }
        return null
    }

    /**
     * 逆地理区县：依次查询，返回第一个非空的区县名（未清洗）
     */
    suspend fun resolveDistrictByLocation(
        wgsLat: Double,
        wgsLng: Double,
        mode: EngineMode? = null
    ): String? {
        for (provider in geocodeProviders(mode)) {
            if (provider.supportsLocationRadar()) {
                val resolved = runCatching { provider.resolveDistrictByLocation(wgsLat, wgsLng) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            }
        }
        return null
    }

    /**
     * 获取周边站点：根据 EngineMode 单源路由至支持位置雷达能力的 Provider。
     * 官方源自带附近站接口（siteAndRedPacket），不再回退掌上公交。
     */
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4,
        mode: EngineMode = EngineMode.MYGOLBS
    ): List<NearbyStop> {
        val targetProvider = providers[mode.dataSource]

        if (targetProvider != null && targetProvider.supportsLocationRadar()) {
            return runCatching { targetProvider.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
        }

        return emptyList()
    }

    /**
     * 搜索：只查当前数据源，去掉地铁线路和纯地铁站，线路按名字自然排序
     */
    suspend fun search(
        city: TransitCity,
        keyword: String,
        mode: EngineMode,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult {
        val raw = providers[mode.dataSource]?.let {
            runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
        }
        // 第三方源没有地铁的在途实时数据，地铁线路和纯地铁站不展示
        val lines = raw?.lines.orEmpty()
            .filterNot { it.isSubway || it.name.contains("地铁") }
            .sortedWith { a, b -> RouteProjectionMath.compareLineNames(a.name, b.name) }
        val stops = raw?.stops.orEmpty().filterNot { it.isPureSubwayStation }
        return TransitSearchResult(keyword = keyword, lines = lines, stops = stops)
    }

    /**
     * 站点详情：根据 EngineMode 或请求显式特征精准单源路由。
     * 官方源自带同站线路接口（line/site），官方模式不再回退掌上公交。
     */
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest,
        mode: EngineMode
    ): List<NearbyStop> {
        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        if (mode == EngineMode.MEISHAN) {
            return providers[DataSourceType.MEISHAN]?.getStopDetail(city, request).orEmpty()
        }

        // 若请求明确包含掌上公交前缀特征，或当前引擎模式为掌上公交，路由至掌上公交
        val isExplicitMygolbs = when {
            mygolbsProvider?.supportsStop(request) == true -> true
            chelaileProvider?.supportsStop(request) == false -> true
            else -> mode == EngineMode.MYGOLBS
        }

        return if (isExplicitMygolbs) {
            mygolbsProvider?.getStopDetail(city, request).orEmpty()
        } else {
            chelaileProvider?.getStopDetail(city, request).orEmpty()
        }
    }

    /** 认不出线路来源时用的 Provider：当前数据源，没有就随便一个 */
    private fun fallbackProvider(mode: EngineMode): ITransitProvider? =
        providers[mode.dataSource] ?: providers.values.firstOrNull()

    /**
     * 线路详情：根据请求显式 source 或 lineId 前缀特征精准路由，认不出来走当前数据源
     */
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest,
        mode: EngineMode = EngineMode.MYGOLBS
    ): BusLineDetail {
        val defaultFallback = fallbackProvider(mode)

        val targetProvider = when {
            request.source != null -> providers[request.source]
            providers[DataSourceType.MEISHAN]?.supportsLine(request.lineId) == true -> providers[DataSourceType.MEISHAN]
            providers[DataSourceType.MYGOLBS]?.supportsLine(request.lineId) == true -> providers[DataSourceType.MYGOLBS]
            providers[DataSourceType.CHELAILE]?.supportsLine(request.lineId) == true -> providers[DataSourceType.CHELAILE]
            else -> providers.values.firstOrNull { it.supportsLine(request.lineId) } ?: defaultFallback
        } ?: throw IllegalStateException("No provider found for lineId: ${request.lineId}")

        return targetProvider.getLineDetail(city, request)
    }

    /**
     * 运营时刻表：根据线路显式 source 或 lineId 前缀特征精准路由，认不出来走当前数据源
     */
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null,
        mode: EngineMode = EngineMode.MYGOLBS
    ): LineTimetable {
        val defaultFallback = fallbackProvider(mode)

        val targetProvider = when {
            line.source == DataSourceType.MEISHAN || MeishanIds.isMeishanId(line.lineId) -> providers[DataSourceType.MEISHAN]
            line.source == DataSourceType.MYGOLBS || MygolbsIds.isMygolbsId(line.lineId) -> providers[DataSourceType.MYGOLBS]
            line.source == DataSourceType.CHELAILE -> providers[DataSourceType.CHELAILE]
            providers[DataSourceType.MEISHAN]?.supportsLine(line.lineId) == true -> providers[DataSourceType.MEISHAN]
            providers[DataSourceType.MYGOLBS]?.supportsLine(line.lineId) == true -> providers[DataSourceType.MYGOLBS]
            providers[DataSourceType.CHELAILE]?.supportsLine(line.lineId) == true -> providers[DataSourceType.CHELAILE]
            else -> defaultFallback
        } ?: throw IllegalStateException("No provider found for timetable: ${line.lineId}")

        return targetProvider.getTimetable(city, line, atTimeHHMM)
    }

    private companion object {
        val GEOCODE_ORDER = listOf(DataSourceType.CHELAILE, DataSourceType.MYGOLBS)
    }
}
