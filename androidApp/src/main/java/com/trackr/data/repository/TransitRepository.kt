package com.trackr.data.repository

import android.content.Context
import android.util.Log
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.local.TransitLocalSnapshot
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.DefaultFavoritesRepository
import com.trackr.core.data.repository.DefaultRealtimeRepository
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.PlatformGeocoder
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.AlertThreshold
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.isMeishanLocal
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.RailMode
import com.trackr.core.model.UiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 统一实时公交数据仓库实现（过渡用）。
 *
 * 设置、搜索历史经 [TransitLocalStore] 存取（第 9 步）：USE_NEW_STORE 开时是 DataStore `trackr_settings` + Room，
 * 关时是原来的 SharedPreferences `trackr_prefs`；这里的逻辑两边共用一份。
 * 收藏（车、路线、分组）第 14 步整个转给 [DefaultFavoritesRepository]，和 RouteGroupRepository 用同一份。
 * 实时查询（附近、站点、线路、时刻表、搜索、定位城市）委托给 commonMain 的 [DefaultRealtimeRepository]。
 *
 * 构造时把存储整个读一次（和旧版一样是同步的，调用方在 IO 线程上读；见 StorageBackend）；之后的改动先改内存里的
 * StateFlow，再按调用顺序排队写盘（[SerialWriter]）。挂起的写方法等写完才返回。
 */
class TransitRepository(
    private val context: Context,
    val coordinator: TransitProviderCoordinator,
    private val store: TransitLocalStore,
    /** 收藏合一仓库（应用里是唯一一份，RouteGroupRepository 也转调它） */
    private val favoritesRepository: DefaultFavoritesRepository,
    /** 写盘排队用的作用域（应用级）；测试可注入 */
    writeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 单调时钟，用于线路详情短缓存；测试可注入 */
    clock: MonotonicClock = ProcessMonotonicClock,
    /** 线路详情合并请求的执行作用域；测试可注入 */
    fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 数据源逆地理拿不到城市时的兜底；默认用系统 Geocoder */
    geocoder: PlatformGeocoder = AndroidGeocoder(context)
) : ITransitRepository, FavoritesRepository by favoritesRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writer = SerialWriter(writeScope).apply {
        onError = { Log.e(TAG, "Failed to persist transit settings", it) }
    }

    /** 搜索历史「读内存 -> 算新列表 -> 排队写盘」整体串行，并发调用也不会丢更新或乱序写盘 */
    private val libraryMutex = Mutex()

    private val initial: TransitLocalSnapshot = runBlocking { store.load() }

    private val _selectedCity = MutableStateFlow(loadInitialCity())
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()

    private val _hasSavedCity = MutableStateFlow(initial.selectedCityJson != null)
    override val hasSavedCity: StateFlow<Boolean> = _hasSavedCity.asStateFlow()

    private val _engineMode = MutableStateFlow(loadInitialEngineMode())
    override val engineMode: StateFlow<EngineMode> = _engineMode.asStateFlow()


    private val _searchHistory = MutableStateFlow(initial.searchHistory)
    override val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _preferredTransitCodeProvider = MutableStateFlow(TransitCodeProvider.fromId(initial.transitCodeProvider ?: TransitCodeProvider.ASK_ALWAYS.id))
    override val preferredTransitCodeProvider: StateFlow<TransitCodeProvider> = _preferredTransitCodeProvider.asStateFlow()

    private val _showTransitCodeButton = MutableStateFlow(initial.showTransitCodeButton ?: true)
    override val showTransitCodeButton: StateFlow<Boolean> = _showTransitCodeButton.asStateFlow()

    private val _autoStopAfterBoarding = MutableStateFlow(initial.autoStopAfterBoarding ?: true)
    override val autoStopAfterBoarding: StateFlow<Boolean> = _autoStopAfterBoarding.asStateFlow()

    private val _nearbyStopsCount = MutableStateFlow((initial.nearbyStopsCount ?: 4).coerceIn(1, 16))
    override val nearbyStopsCount: StateFlow<Int> = _nearbyStopsCount.asStateFlow()

    private val _isLiveNotificationEnabled = MutableStateFlow(initial.liveNotificationEnabled ?: true)
    override val isLiveNotificationEnabled: StateFlow<Boolean> = _isLiveNotificationEnabled.asStateFlow()

    private val _showOnLockScreen = MutableStateFlow(initial.showOnLockScreen ?: true)
    override val showOnLockScreen: StateFlow<Boolean> = _showOnLockScreen.asStateFlow()

    private val _alertThresholdStops = MutableStateFlow(
        (initial.alertThresholdStops ?: AlertThreshold.DEFAULT_STOPS).coerceIn(AlertThreshold.MIN_STOPS, AlertThreshold.MAX_STOPS)
    )
    override val alertThresholdStops: StateFlow<Int> = _alertThresholdStops.asStateFlow()

    private val _uiMode = MutableStateFlow(UiMode.entries.firstOrNull { it.name == initial.uiMode } ?: UiMode.Standard)
    override val uiMode: StateFlow<UiMode> = _uiMode.asStateFlow()

    private val _railMode = MutableStateFlow(RailMode.entries.firstOrNull { it.name == initial.railMode } ?: RailMode.Horizontal)
    override val railMode: StateFlow<RailMode> = _railMode.asStateFlow()

    private val _fontScalePromptDismissed = MutableStateFlow(initial.fontScalePromptDismissed ?: false)
    override val fontScalePromptDismissed: StateFlow<Boolean> = _fontScalePromptDismissed.asStateFlow()

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    override fun updateUserLocation(lat: Double, lng: Double) {
        if (lat > 0.0 && lng > 0.0) {
            _userLocation.value = Pair(lat, lng)
        }
    }

    /** 实时查询部分（commonMain） */
    private val realtime = DefaultRealtimeRepository(
        coordinator = coordinator,
        settings = this,
        favoritesRepository = favoritesRepository,
        searchHistory = this,
        userLocation = { _userLocation.value },
        geocoder = geocoder,
        clock = clock,
        fetchScope = fetchScope
    )

    private fun loadInitialCity(): TransitCity {
        val cityJson = initial.selectedCityJson
        if (!cityJson.isNullOrBlank()) {
            try {
                val city = json.decodeFromString<TransitCity>(cityJson)
                val prov = if (city.province.isNotBlank()) city.province else ProvinceCityCatalog.inferProvince(city.cityName)
                return city.copy(province = prov)
            } catch (_: Exception) {}
        }
        return TransitCity(cityId = "034", cityName = "上海", pinyin = "ShangHai", supportSubway = true, isHot = true, province = "直辖市")
    }

    override fun setNearbyStopsCount(count: Int) {
        val clamped = count.coerceIn(1, 16)
        _nearbyStopsCount.value = clamped
        writer.enqueue { store.saveNearbyStopsCount(clamped) }
    }

    override fun setLiveNotificationEnabled(enabled: Boolean) {
        _isLiveNotificationEnabled.value = enabled
        writer.enqueue { store.saveLiveNotificationEnabled(enabled) }
    }

    override fun setShowOnLockScreen(enabled: Boolean) {
        _showOnLockScreen.value = enabled
        writer.enqueue { store.saveShowOnLockScreen(enabled) }
    }

    override fun setShowTransitCodeButton(enabled: Boolean) {
        _showTransitCodeButton.value = enabled
        writer.enqueue { store.saveShowTransitCodeButton(enabled) }
    }

    override fun setAutoStopAfterBoarding(enabled: Boolean) {
        _autoStopAfterBoarding.value = enabled
        writer.enqueue { store.saveAutoStopAfterBoarding(enabled) }
    }

    override fun setAlertThresholdStops(stops: Int) {
        val clamped = stops.coerceIn(AlertThreshold.MIN_STOPS, AlertThreshold.MAX_STOPS)
        _alertThresholdStops.value = clamped
        writer.enqueue { store.saveAlertThresholdStops(clamped) }
    }

    override fun setUiMode(mode: UiMode) {
        _uiMode.value = mode
        writer.enqueue { store.saveUiMode(mode.name) }
    }

    override fun setRailMode(mode: RailMode) {
        _railMode.value = mode
        writer.enqueue { store.saveRailMode(mode.name) }
    }

    override fun dismissFontScalePrompt() {
        if (_fontScalePromptDismissed.value) return
        _fontScalePromptDismissed.value = true
        writer.enqueue { store.saveFontScalePromptDismissed(true) }
    }

    private fun loadInitialEngineMode(): EngineMode {
        // 兜底默认掌上公交；眉山 / 仁寿由首页按城市联动默认官方源（BusHomeViewModel）
        val modeStr = initial.engineMode ?: EngineMode.MYGOLBS.name
        return runCatching { EngineMode.valueOf(modeStr) }.getOrDefault(EngineMode.MYGOLBS)
    }

    override fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
        writer.enqueue { store.saveEngineMode(mode.name) }
        // 若当前城市缺少掌上公交编码，尝试从缓存城市列表中补齐
        val currCity = _selectedCity.value
        val cachedCities = realtime.cachedCities
        if (currCity.mygolbsCityCode.isNullOrBlank() && cachedCities.isNotEmpty()) {
            val matched = cachedCities.firstOrNull {
                ProvinceCityCatalog.cleanCityName(it.cityName) == ProvinceCityCatalog.cleanCityName(currCity.cityName)
            }
            if (matched?.mygolbsCityCode?.isNotBlank() == true) {
                _selectedCity.value = currCity.copy(mygolbsCityCode = matched.mygolbsCityCode)
            }
        }
    }

    override suspend fun selectCity(city: TransitCity) {
        val effectiveCity = if (city.province.isBlank()) {
            city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
        } else {
            city
        }
        _selectedCity.value = effectiveCity
        _hasSavedCity.value = true
        // 眉山特化：官方源只覆盖眉山 / 仁寿，切到别的城市自动回退掌上公交
        if (_engineMode.value == EngineMode.MEISHAN && !effectiveCity.isMeishanLocal) {
            setEngineMode(EngineMode.MYGOLBS)
        }
        val str = json.encodeToString(effectiveCity)
        writer.enqueue { store.saveSelectedCityJson(str) }.join()
    }

    override suspend fun getSupportedCities(forceRefresh: Boolean): Result<List<TransitCity>> =
        realtime.getSupportedCities(forceRefresh)

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?> =
        realtime.resolveCityByLocation(wgsLat, wgsLng)

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): Result<List<NearbyStop>> = realtime.getNearbyStops(city, wgsLat, wgsLng, limit)

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
    ): Result<List<NearbyStop>> = realtime.getStopDetail(
        city, stopId, stopName, physicalStId, namesakeStId, stationLat, stationLng, userLat, userLng
    )

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): Result<TransitSearchResult> = realtime.search(city, keyword, userLat, userLng)

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
    ): Result<BusLineDetail> = realtime.getLineDetail(
        city, lineId, targetOrder, stationId, wgsLat, wgsLng, source, lineNameHint, directionHint
    )

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): Result<LineTimetable> = realtime.getTimetable(city, line, atTimeHHMM)

    override suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>> =
        realtime.refreshFavoritesRealtime()

    private suspend fun persistSearchHistory(list: List<String>) {
        writer.enqueue { store.saveSearchHistory(list) }.join()
    }

    override suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        libraryMutex.withLock { addSearchHistoryLocked(trimmed) }
    }

    private suspend fun addSearchHistoryLocked(trimmed: String) {
        val current = _searchHistory.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val limited = if (current.size > MAX_SEARCH_HISTORY) current.take(MAX_SEARCH_HISTORY) else current
        _searchHistory.value = limited
        persistSearchHistory(limited)
    }

    override suspend fun removeSearchHistory(query: String) = libraryMutex.withLock {
        val trimmed = query.trim()
        val current = _searchHistory.value.toMutableList()
        val removed = current.removeAll { it.equals(trimmed, ignoreCase = true) }
        if (removed) {
            _searchHistory.value = current
            persistSearchHistory(current)
        }
    }

    override suspend fun clearSearchHistory() = libraryMutex.withLock {
        _searchHistory.value = emptyList()
        writer.enqueue { store.clearSearchHistory() }.join()
    }

    override suspend fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        _preferredTransitCodeProvider.value = provider
        writer.enqueue { store.saveTransitCodeProvider(provider.id) }.join()
    }

    /** 等排队的写盘做完（测试用） */
    suspend fun flushWrites() = writer.flush()

    companion object {
        private const val TAG = "TransitRepository"
        const val MAX_SEARCH_HISTORY = 20

        /** 线路详情短缓存时长（实现搬到了 [DefaultRealtimeRepository]） */
        const val LINE_DETAIL_CACHE_TTL_MS = DefaultRealtimeRepository.LINE_DETAIL_CACHE_TTL_MS
    }
}

/** 系统 Geocoder 兜底（原来写在 `resolveCityByLocation` 里） */
private class AndroidGeocoder(context: Context) : PlatformGeocoder {
    private val appContext: Context = context.applicationContext ?: context

    override suspend fun locate(wgsLat: Double, wgsLng: Double): Pair<String?, String?>? {
        if (!android.location.Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val address = android.location.Geocoder(appContext, java.util.Locale.CHINA)
            .getFromLocation(wgsLat, wgsLng, 1)
            ?.firstOrNull()
            ?: return null
        return (address.locality ?: address.subAdminArea ?: address.adminArea) to address.subLocality
    }
}
