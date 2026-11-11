package com.trackr.core.testing

import com.trackr.core.model.ThemeColors
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.LineDetailUpdate
import com.trackr.core.data.repository.LocationRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupColor
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.RailMode
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.UiMode
import com.trackr.core.model.UserFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update

/*
 * :core:data 仓库接口的 Fake（原 :androidApp 的 FakeTransitRepository 按接口拆开，重构计划第 8 步）。
 * 都是内存实现，结果由测试设定；只在单线程测试（runTest）里用。
 */

/** 默认城市：上海（和原 FakeTransitRepository 一致） */
val FAKE_DEFAULT_CITY = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", isHot = true)

class FakeSettingsRepository(
    initialCity: TransitCity = FAKE_DEFAULT_CITY,
    initialTransitCodeProvider: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS
) : SettingsRepository {

    private val _selectedCity = MutableStateFlow(initialCity)
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()

    private val _hasSavedCity = MutableStateFlow(true)
    override val hasSavedCity: StateFlow<Boolean> = _hasSavedCity.asStateFlow()

    /** 测试里设为 false 模拟首次安装（从未存过城市） */
    fun setHasSavedCity(saved: Boolean) {
        _hasSavedCity.value = saved
    }

    private val _engineMode = MutableStateFlow(EngineMode.CHELAILE)
    override val engineMode: StateFlow<EngineMode> = _engineMode.asStateFlow()

    private val _preferredTransitCodeProvider = MutableStateFlow(initialTransitCodeProvider)
    override val preferredTransitCodeProvider: StateFlow<TransitCodeProvider> = _preferredTransitCodeProvider.asStateFlow()

    private val _showTransitCodeButton = MutableStateFlow(true)
    override val showTransitCodeButton: StateFlow<Boolean> = _showTransitCodeButton.asStateFlow()

    private val _autoStopAfterBoarding = MutableStateFlow(true)
    override val autoStopAfterBoarding: StateFlow<Boolean> = _autoStopAfterBoarding.asStateFlow()

    private val _nearbyStopsCount = MutableStateFlow(4)
    override val nearbyStopsCount: StateFlow<Int> = _nearbyStopsCount.asStateFlow()

    private val _isLiveNotificationEnabled = MutableStateFlow(true)
    override val isLiveNotificationEnabled: StateFlow<Boolean> = _isLiveNotificationEnabled.asStateFlow()

    private val _showOnLockScreen = MutableStateFlow(true)
    override val showOnLockScreen: StateFlow<Boolean> = _showOnLockScreen.asStateFlow()

    private val _alertThresholdStops = MutableStateFlow(2)
    override val alertThresholdStops: StateFlow<Int> = _alertThresholdStops.asStateFlow()

    private val _uiMode = MutableStateFlow(UiMode.Standard)
    override val uiMode: StateFlow<UiMode> = _uiMode.asStateFlow()

    private val _railMode = MutableStateFlow(RailMode.Horizontal)
    override val railMode: StateFlow<RailMode> = _railMode.asStateFlow()

    private val _themeColors = MutableStateFlow(ThemeColors())
    override val themeColors: StateFlow<ThemeColors> = _themeColors.asStateFlow()

    override fun setThemeColors(colors: ThemeColors) {
        _themeColors.value = colors.normalized()
    }

    private val _themeMode = MutableStateFlow(ThemeMode.System)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _fontScalePromptDismissed = MutableStateFlow(false)
    override val fontScalePromptDismissed: StateFlow<Boolean> = _fontScalePromptDismissed.asStateFlow()

    override fun setUiMode(mode: UiMode) {
        _uiMode.value = mode
    }

    override fun setRailMode(mode: RailMode) {
        _railMode.value = mode
    }

    override fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    override fun dismissFontScalePrompt() {
        _fontScalePromptDismissed.value = true
    }

    override fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
    }

    override fun setNearbyStopsCount(count: Int) {
        _nearbyStopsCount.value = count.coerceIn(1, 16)
    }

    override fun setLiveNotificationEnabled(enabled: Boolean) {
        _isLiveNotificationEnabled.value = enabled
    }

    override fun setShowOnLockScreen(enabled: Boolean) {
        _showOnLockScreen.value = enabled
    }

    override fun setShowTransitCodeButton(enabled: Boolean) {
        _showTransitCodeButton.value = enabled
    }

    override fun setAutoStopAfterBoarding(enabled: Boolean) {
        _autoStopAfterBoarding.value = enabled
    }

    override fun setAlertThresholdStops(stops: Int) {
        _alertThresholdStops.value = stops.coerceIn(1, 3)
    }

    override suspend fun selectCity(city: TransitCity) {
        _selectedCity.value = city
        _hasSavedCity.value = true
    }

    override suspend fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        _preferredTransitCodeProvider.value = provider
    }
}

/**
 * 收藏 Fake：新收藏的车加在末尾（和原 FakeTransitRepository 一致），新路线也加在末尾。
 * 合一列表（第 14 步）按 [favoriteItems] 的顺序：先车后路线，[move] 后按给的顺序。
 */
class FakeFavoritesRepository(
    initialFavorites: List<FavoriteStationLine> = emptyList(),
    initialRoutes: List<TransitGroup> = emptyList(),
    initialGroups: List<FavoriteGroup> = emptyList(),
    initialStations: List<FavoriteStation> = emptyList()
) : FavoritesRepository {

    private val _favorites = MutableStateFlow(initialFavorites)
    override val favorites: StateFlow<List<FavoriteStationLine>> = _favorites.asStateFlow()

    private val _favoriteStations = MutableStateFlow(initialStations)
    override val favoriteStations: StateFlow<List<FavoriteStation>> = _favoriteStations.asStateFlow()

    private val _routes = MutableStateFlow(initialRoutes)
    override val routes: StateFlow<List<TransitGroup>> = _routes.asStateFlow()

    private val _favoriteGroups = MutableStateFlow(initialGroups)
    override val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

    /** key -> 分组；没有的算不分组 */
    private val groupOf = mutableMapOf<String, String?>()

    /** 合一顺序（key）；没列出的排在后面 */
    private var order: List<String> = emptyList()

    private val _favoriteItems = MutableStateFlow(buildItems())
    override val favoriteItems: StateFlow<List<FavoriteItem>> = _favoriteItems.asStateFlow()

    private var nextGroupId = 1

    private fun buildItems(): List<FavoriteItem> {
        val all = _favorites.value.map { FavoriteItem.stopKey(it.id) to it } +
            _favoriteStations.value.map { FavoriteItem.stationKey(it.id) to it } +
            _routes.value.map { FavoriteItem.routeKey(it.id) to it }
        val rank = order.withIndex().associate { it.value to it.index }
        return all.withIndex()
            .sortedWith(compareBy({ rank[it.value.first] ?: (order.size + it.index) }, { it.index }))
            .mapIndexed { index, entry ->
                val (key, value) = entry.value
                when (value) {
                    is FavoriteStationLine -> FavoriteItem.Stop(value, groupOf[key], index)
                    is FavoriteStation -> FavoriteItem.Station(value, groupOf[key], index)
                    else -> {
                        val route = value as TransitGroup
                        // 显式设成 null（拖到「不分组」）要盖掉路线自己的 customGroupId
                        val groupId = if (groupOf.containsKey(key)) groupOf[key] else route.customGroupId
                        FavoriteItem.Route(route.copy(customGroupId = groupId), groupId, index)
                    }
                }
            }
    }

    private fun refresh() {
        _favoriteItems.value = buildItems()
    }

    override suspend fun toggleFavorite(item: FavoriteStationLine): Boolean {
        val exists = _favorites.value.any { it.id == item.id }
        return if (exists) {
            _favorites.update { list -> list.filterNot { it.id == item.id } }
            refresh()
            false
        } else {
            _favorites.update { list -> list + item }
            refresh()
            true
        }
    }

    override suspend fun removeFavorite(id: String) {
        _favorites.update { list -> list.filterNot { it.id == id } }
        refresh()
    }

    override suspend fun toggleStation(station: FavoriteStation): Boolean {
        val exists = _favoriteStations.value.any { it.id == station.id }
        _favoriteStations.update { list -> if (exists) list.filterNot { it.id == station.id } else list + station }
        refresh()
        return !exists
    }

    override suspend fun updateStation(station: FavoriteStation) {
        _favoriteStations.update { list -> list.map { if (it.id == station.id) station else it } }
        refresh()
    }

    override suspend fun removeStation(id: String) {
        _favoriteStations.update { list -> list.filterNot { it.id == id } }
        refresh()
    }

    override suspend fun move(keys: List<String>) {
        val current = _favoriteItems.value.map { it.key }
        val moving = keys.distinct().filter { it in current }
        val slots = current.withIndex().filter { it.value in moving }.map { it.index }
        val result = current.toMutableList()
        slots.forEachIndexed { i, slot -> result[slot] = moving[i] }
        order = result
        refresh()
    }

    override suspend fun setGroup(key: String, groupId: String?): Boolean {
        if (groupId != null && _favoriteGroups.value.none { it.id == groupId }) return false
        if (_favoriteItems.value.none { it.key == key }) return false
        groupOf[key] = groupId
        if (key.startsWith(FavoriteItem.ROUTE_KEY_PREFIX)) {
            val id = key.removePrefix(FavoriteItem.ROUTE_KEY_PREFIX)
            _routes.update { list -> list.map { if (it.id == id) it.copy(customGroupId = groupId) else it } }
        }
        refresh()
        return true
    }

    override suspend fun saveRoute(route: TransitGroup) {
        _routes.update { list ->
            if (list.any { it.id == route.id }) list.map { if (it.id == route.id) route else it } else list + route
        }
        refresh()
    }

    override suspend fun removeRoute(routeId: String) {
        _routes.update { list -> list.filterNot { it.id == routeId } }
        refresh()
    }

    override suspend fun createGroup(name: String, color: GroupColor?): FavoriteGroup {
        val group = FavoriteGroup(
            id = "fake_group_${nextGroupId++}",
            name = name.trim(),
            sortOrder = _favoriteGroups.value.size,
            color = color
        )
        _favoriteGroups.update { it + group }
        return group
    }

    override suspend fun renameGroup(groupId: String, newName: String) {
        _favoriteGroups.update { list -> list.map { if (it.id == groupId) it.copy(name = newName.trim()) else it } }
    }

    override suspend fun setGroupColor(groupId: String, color: GroupColor?) {
        _favoriteGroups.update { list -> list.map { if (it.id == groupId) it.withColor(color) else it } }
    }

    override suspend fun deleteGroup(groupId: String) {
        _favoriteGroups.update { list -> list.filterNot { it.id == groupId } }
        groupOf.keys.filter { groupOf[it] == groupId }.forEach { groupOf[it] = null }
        _routes.update { list -> list.map { if (it.customGroupId == groupId) it.copy(customGroupId = null) else it } }
        refresh()
    }

    override suspend fun reorderGroups(groupIds: List<String>) {
        val byId = _favoriteGroups.value.associateBy { it.id }
        _favoriteGroups.value = (groupIds.mapNotNull { byId[it] } + _favoriteGroups.value.filterNot { it.id in groupIds })
            .mapIndexed { index, group -> group.copy(sortOrder = index) }
    }
}

class FakeSearchHistoryRepository(
    initialSearchHistory: List<String> = emptyList()
) : SearchHistoryRepository {

    private val _searchHistory = MutableStateFlow(initialSearchHistory)
    override val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    override suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        _searchHistory.update { list ->
            val updated = list.filterNot { it.equals(trimmed, ignoreCase = true) }.toMutableList()
            updated.add(0, trimmed)
            if (updated.size > 20) updated.take(20) else updated
        }
    }

    override suspend fun removeSearchHistory(query: String) {
        val trimmed = query.trim()
        _searchHistory.update { list -> list.filterNot { it.equals(trimmed, ignoreCase = true) } }
    }

    override suspend fun clearSearchHistory() {
        _searchHistory.value = emptyList()
    }
}

/**
 * 实时查询 Fake：每个查询返回测试设定的结果。
 * @param currentCity 定位城市默认返回的「当前城市」（原 Fake 返回已选城市）
 */
class FakeRealtimeRepository(
    private val currentCity: () -> TransitCity = { FAKE_DEFAULT_CITY }
) : RealtimeRepository {

    var supportedCitiesResult: Result<List<TransitCity>> = Result.success(listOf(currentCity()))
    var nearbyStopsResult: Result<List<NearbyStop>> = Result.success(emptyList())
    var stopDetailResult: Result<List<NearbyStop>> = Result.success(emptyList())
    var searchResult: Result<TransitSearchResult> = Result.success(TransitSearchResult(keyword = ""))
    var lineDetailResult: Result<BusLineDetail>? = null
    val lineDetailsMap: MutableMap<String, BusLineDetail> = mutableMapOf()
    var timetableResult: Result<LineTimetable> = Result.success(LineTimetable(lineId = "", lineName = ""))
    var favoritesRealtimeResult: Result<Map<String, NearbyStop>> = Result.success(emptyMap())

    /** 为 null 时默认返回当前城市 */
    var resolveCityResult: Result<TransitCity?>? = null

    /** [getStopDetail] 被调了几次（一起等：等车阶段每轮应只有 1 次） */
    var stopDetailCallCount: Int = 0
        private set

    /** 每次 [getLineDetail] 请求的 lineId（按调用顺序） */
    val lineDetailCalls: MutableList<String> = mutableListOf()

    /** [getLineDetail] 返回前先跑一下（测试用来挂起、数并发）；null = 直接返回 */
    var lineDetailHook: (suspend (lineId: String) -> Unit)? = null

    /** 每次 [getLineDetail] 请求的候车站序（按调用顺序） */
    val lineDetailTargetOrders: MutableList<Int?> = mutableListOf()

    /** 测试推「别处刚拿到的线路详情」 */
    val lineDetailUpdateFlow = MutableSharedFlow<LineDetailUpdate>(extraBufferCapacity = 16)

    override val lineDetailUpdates: Flow<LineDetailUpdate> get() = lineDetailUpdateFlow

    /** 记录每次附近站点查询的 (城市, 纬度, 经度) */
    val nearbyStopsCalls: MutableList<Triple<TransitCity, Double, Double>> = mutableListOf()

    /** 每次附近站点查询的 limit（和 [nearbyStopsCalls] 一一对应） */
    val nearbyStopsLimits: MutableList<Int> = mutableListOf()

    /** 可按查询坐标返回不同结果；为 null 时统一返回 [nearbyStopsResult] */
    var nearbyStopsResultProvider: ((TransitCity, Double, Double) -> Result<List<NearbyStop>>)? = null

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> = supportedCitiesResult

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> =
        resolveCityResult ?: Result.success(currentCity())

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> {
        nearbyStopsCalls += Triple(city, wgsLat, wgsLng)
        nearbyStopsLimits += limit
        return nearbyStopsResultProvider?.invoke(city, wgsLat, wgsLng) ?: nearbyStopsResult
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
    ): Result<List<NearbyStop>> {
        stopDetailCallCount++
        return stopDetailResult
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> = searchResult

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
        lineDetailCalls += lineId
        lineDetailTargetOrders += targetOrder
        lineDetailHook?.invoke(lineId)
        lineDetailsMap[lineId]?.let { return Result.success(it) }
        lineDetailResult?.let { return it }
        val direction = BusLineDirection(
            lineId = lineId,
            name = if (lineNameHint.isNotBlank()) lineNameHint else "测试线路",
            startStop = "起点站",
            endStop = "终点站",
            source = source
        )
        return Result.success(BusLineDetail(line = direction, stops = emptyList(), buses = emptyList()))
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> = timetableResult

    /** 和线上一致：掌上公交站点接口不带车辆明细 */
    override fun stopDetailIncludesBuses(source: DataSourceType): Boolean =
        source != DataSourceType.MYGOLBS

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> = favoritesRealtimeResult
}

/**
 * 定位仓库 Fake：[bestFix] / [lastKnown] 返回测试设定的值，[fixes] 由 [emit] 推送。
 */
class FakeLocationRepository(
    var bestFixResult: UserFix? = null,
    var lastKnownResult: UserFix? = null
) : LocationRepository {

    private val _latest = MutableStateFlow<UserFix?>(null)
    override val latest: StateFlow<UserFix?> = _latest.asStateFlow()

    private val live = MutableSharedFlow<UserFix>(extraBufferCapacity = 64)

    /** 每次 [bestFix] 调用时传的 forceFresh */
    val bestFixCalls: MutableList<Boolean> = mutableListOf()

    override suspend fun bestFix(forceFresh: Boolean): UserFix? {
        bestFixCalls += forceFresh
        return bestFixResult?.also { _latest.value = it }
    }

    override fun lastKnown(maxAgeMillis: Long): UserFix? = lastKnownResult

    /** 有没有定位权限；首页提示「没有定位权限」时置 false */
    var permissionGranted: Boolean = true

    /** 系统「位置信息」总开关；首页提示「手机定位服务未开启」时置 false */
    var systemLocationEnabled: Boolean = true

    override fun hasPermission(): Boolean = permissionGranted

    override fun isSystemLocationEnabled(): Boolean = systemLocationEnabled

    override fun fixes(tier: LocationCadence.Tier): Flow<UserFix> =
        if (tier == LocationCadence.Tier.OFF) emptyFlow() else live

    /** 推一个定位给正在收集的 [fixes]，同时更新 [latest] */
    fun emit(fix: UserFix) {
        _latest.value = fix
        live.tryEmit(fix)
    }
}
