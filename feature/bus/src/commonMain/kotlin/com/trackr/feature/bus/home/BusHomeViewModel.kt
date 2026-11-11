package com.trackr.feature.bus.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.HomeCoordinates
import com.trackr.core.data.repository.LocationRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.NoAnnouncer
import com.trackr.core.domain.platform.NoSystemSettingsLauncher
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.domain.platform.SystemSettingsLauncher
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * v3 公交首页（重构计划第 19 步，原 `HomeViewModel`）。
 *
 * - 附近站固定 6 个；附近站 + 收藏实时每 15 秒轮询一次，由 [BusHomeRoute] 随 STARTED 开关（§4.4），
 *   恢复时距上次刷新超过一个周期就立即补刷。
 * - 定位走 [LocationRepository.bestFix]（4.5 秒窗口内取最准）。
 * - 追踪卡读所有会话（同时可以追多项，一项一张卡）；「结束」只停那一项。
 * - 乘车码按钮：「我的」里选了平台就直接打开，选了「每次询问」才弹选择。
 */
class BusHomeViewModel(
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val realtime: RealtimeRepository,
    /** 首页定位写入的最近坐标（站点详情、搜索、规划页「我的位置」读它） */
    private val userLocationStore: UserLocationStore,
    /** 设备定位（首页刷新时取一次当前位置） */
    private val locationRepository: LocationRepository,
    /** 追踪服务控制（「结束追踪」、把定位喂给换乘引擎） */
    private val trackingController: TrackingController,
    /** 追踪会话（首页追踪卡） */
    private val trackingRepository: TrackingRepository,
    /** 打开支付宝 / 云闪付乘车码 */
    private val transitCodeLauncher: TransitCodeLauncher,
    /** 定位提示里的「去授权 / 去开启」跳系统设置页；不传（测试、还没做的平台）就什么都不打开 */
    private val systemSettings: SystemSettingsLauncher = NoSystemSettingsLauncher,
    private val enableAutoPolling: Boolean = true,
    /** 单调时钟（毫秒），用于判断恢复轮询时距上次刷新是否已超过一个周期；测试可替换 */
    private val elapsedRealtimeMillis: () -> Long = { ProcessMonotonicClock.elapsedMillis() },
    /** 把首页当前坐标发给搜索用（拆分前搜索直接读首页的 currentLat / currentLng）；为 null 时不发 */
    private val homeCoordinates: HomeCoordinates? = null,
    /** 长辈版卡片上的「读给我听」（§8.6） */
    private val announcer: Announcer = NoAnnouncer
) : ViewModel() {

    private val _uiState = MutableStateFlow(BusHomeUiState())
    val uiState: StateFlow<BusHomeUiState> = _uiState.asStateFlow()

    /** 改状态都走这里：数据改完立刻重算 UI 模型（没变的行沿用旧实例） */
    private inline fun edit(crossinline transform: (BusHomeUiState) -> BusHomeUiState) {
        _uiState.update { old -> BusHomeMapper.derive(old, transform(old)) }
    }

    /** 上一次实时数据（附近站 + 收藏）刷新完成的时间，基于 [elapsedRealtimeMillis]；0 表示还没刷新过 */
    @Volatile
    private var lastRealtimeRefreshAtMs: Long = 0L

    /** 恢复轮询时补发的一次立即刷新 */
    private var catchUpPollJob: Job? = null

    private var nextMessageId = 1L

    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = FixedDelayPollingStrategy(POLLING_INTERVAL_MS),
        initialEnabled = enableAutoPolling
    ) {
        pollRealtimeOnce()
    }

    /** 轮询一次附近站点与收藏线路的实时到站（定位 / 下拉刷新进行中时跳过，避免并发重复请求） */
    private suspend fun pollRealtimeOnce() {
        val state = _uiState.value
        if (!state.isRefreshing && !state.isLoadingNearby && !state.isLocating) {
            val nearbyResult = realtime.getNearbyStops(
                state.currentCity,
                state.currentLat,
                state.currentLng,
                limit = NEARBY_STOPS_LIMIT
            )
            val favResult = realtime.refreshFavoritesRealtime()
            edit {
                it.copy(
                    nearbyStops = nearbyResult.getOrNull() ?: it.nearbyStops,
                    favoritesRealtime = favResult.getOrNull() ?: it.favoritesRealtime
                )
            }
            lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
        }
    }

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    init {
        // 首页当前坐标同步给搜索（见 HomeCoordinates）；先同步写一次默认值，搜索任何时候都读得到
        homeCoordinates?.let { target ->
            _uiState.value.let { target.update(it.currentLat, it.currentLng) }
            viewModelScope.launch {
                _uiState
                    .map { it.currentLat to it.currentLng }
                    .distinctUntilChanged()
                    .collect { (lat, lng) -> target.update(lat, lng) }
            }
        }

        // 观察数据仓库中选中的城市
        viewModelScope.launch {
            settings.selectedCity.collect { city ->
                val state = _uiState.value
                if (state.currentCity.cityId == city.cityId && state.currentCity.cityName == city.cityName) {
                    return@collect
                }
                val isSameAsLocated = city.matchesCity(state.locatedCity)
                if (isSameAsLocated) {
                    edit {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        )
                    }
                } else {
                    val coords = ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
                    edit {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                            currentLat = coords.first,
                            currentLng = coords.second
                        )
                    }
                    loadNearbyStops(coords.first, coords.second, cityOverride = city)
                }
            }
        }

        // 收藏的车（小组件 / 快捷方式的同步在应用级收集器里做，这里只显示）
        viewModelScope.launch {
            favorites.favorites.collect { favs ->
                edit { it.copy(favorites = favs) }
            }
        }

        // 收藏合一列表（车和路线排在一起）
        viewModelScope.launch {
            favorites.favoriteItems.collect { items ->
                edit { it.copy(favoriteItems = items) }
            }
        }

        // 新收藏了站（issue #47）：马上查一次，不用等下一轮刷新才从「正在查车」变成到站
        viewModelScope.launch {
            favorites.favoriteStations
                .map { list -> list.map { it.id }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { ids ->
                    if (ids.any { FavoriteItem.stationKey(it) !in _uiState.value.favoritesRealtime }) refreshFavoritesRealtime()
                }
        }

        // 收藏分组；选中的分组被删了就回到「全部」
        viewModelScope.launch {
            favorites.favoriteGroups.collect { groups ->
                edit { state ->
                    val selected = state.selectedGroupId?.takeIf { id -> groups.any { it.id == id } }
                    state.copy(favoriteGroups = groups, selectedGroupId = selected)
                }
            }
        }

        // 追踪：所有会话（可以同时追多项，首页一项一张卡）+ 主会话这一程的状态
        viewModelScope.launch {
            trackingRepository.sessions.collect { sessions ->
                edit { it.copy(trackingSessions = sessions) }
            }
        }
        viewModelScope.launch {
            trackingRepository.legState.collect { session ->
                edit { it.copy(activeTracking = session) }
            }
        }

        // 观察当前引擎运行模式
        viewModelScope.launch {
            settings.engineMode.collect { mode ->
                val prevMode = _uiState.value.engineMode
                edit { it.copy(engineMode = mode) }
                if (prevMode != mode) {
                    val state = _uiState.value
                    loadNearbyStops(state.currentLat, state.currentLng)
                    refreshFavoritesRealtime()
                }
            }
        }

        // 首页右下角的「乘车码」按钮显不显示（「我的」里的开关）
        viewModelScope.launch {
            settings.showTransitCodeButton.collect { visible ->
                edit { it.copy(showTransitCodeButton = visible) }
            }
        }

        // 初始化加载支持的城市
        loadSupportedCities()

        // 刷新 GPS 定位并加载附近站点和收藏实时状态
        refreshLocationAndNearby()
    }

    /** 页面事件（导航类在 [BusHomeRoute] 里处理，到不了这里） */
    fun onEvent(event: BusHomeEvent) {
        when (event) {
            BusHomeEvent.Refresh -> refresh()
            BusHomeEvent.RetryLocation -> refreshLocationAndNearby()
            is BusHomeEvent.StopTracking -> stopTracking(event.sessionId, event.watchLineId)
            is BusHomeEvent.SelectGroup -> selectGroup(event.groupId)
            BusHomeEvent.TransitCodeClicked -> onTransitCodeClicked()
            is BusHomeEvent.TransitCodeChosen -> onTransitCodeChosen(event.provider, event.saveAsDefault)
            BusHomeEvent.TransitCodeSheetDismissed -> edit { it.copy(isTransitCodeSheetOpen = false) }
            is BusHomeEvent.MessageShown -> onMessageShown(event.id)
            is BusHomeEvent.ReadAloud -> readAloud(event.text)
            BusHomeEvent.OpenCityPicker -> setCityPickerOpen(true)
            BusHomeEvent.CloseCityPicker -> setCityPickerOpen(false)
            is BusHomeEvent.CitySearchChanged -> updateCitySearchQuery(event.query)
            is BusHomeEvent.ProvinceSelected -> selectProvince(event.province)
            is BusHomeEvent.CitySelected -> selectCity(event.city)
            BusHomeEvent.RelocateCity -> refreshLocationAndNearby(switchCity = true)
            BusHomeEvent.OpenLocationSettings -> systemSettings.openLocationSettings()
            BusHomeEvent.OpenAppSettings -> systemSettings.openAppSettings()
            BusHomeEvent.OpenSearch,
            is BusHomeEvent.OpenLine,
            is BusHomeEvent.OpenStop,
            is BusHomeEvent.OpenRoute,
            BusHomeEvent.EditFavorites,
            BusHomeEvent.OpenSettings -> Unit
        }
    }

    private fun fallbackCities(): List<TransitCity> = listOf(
        TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "027", cityName = "北京", pinyin = "beijing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "007", cityName = "广州", pinyin = "guangzhou", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "051", cityName = "深圳", pinyin = "shenzhen", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "015", cityName = "杭州", pinyin = "hangzhou", supportSubway = true, isHot = true, province = "浙江"),
        TransitCity(cityId = "055", cityName = "南京", pinyin = "nanjing", supportSubway = true, isHot = true, province = "江苏"),
        TransitCity(cityId = "028", cityName = "武汉", pinyin = "wuhan", supportSubway = true, isHot = true, province = "湖北"),
        TransitCity(cityId = "050", cityName = "成都", pinyin = "chengdu", supportSubway = true, isHot = true, province = "四川"),
        TransitCity(cityId = "036", cityName = "重庆", pinyin = "chongqing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "440", cityName = "玉林", pinyin = "yulin", supportSubway = false, isHot = true, mygolbsCityCode = "450900", province = "广西")
    )

    private fun loadSupportedCities() {
        viewModelScope.launch {
            val result = realtime.getSupportedCities()
            val list = result.getOrNull()
            if (!list.isNullOrEmpty()) {
                edit { it.copy(cities = list) }
            } else {
                edit { it.copy(cities = fallbackCities()) }
            }
        }
    }

    /**
     * 刷新定位并加载附近站点
     */
    fun refreshLocationAndNearby(switchCity: Boolean = false) {
        refresh(switchCity)
    }

    /**
     * 回到前台时调用：上次定位失败、且失败的原因已经变了，就静默再定位一次（issue #34）。
     *
     * 「原因变了」= 用户去系统设置里把那一层补上了（没授权→授权了、总开关关着→开了）。
     * 原因没变就不重试：每次从搜索页返回都空转 4.5 秒定位窗口、再打一次附近站，纯属白烧电。
     */
    fun retryLocationIfFailed() {
        val state = _uiState.value
        val issue = state.locationIssue ?: return
        if (state.isLocating || state.isRefreshing) return
        if (diagnoseLocationIssue() == issue) return
        refresh()
    }

    /**
     * [locationRepository.bestFix] 没给定位时，分清是哪一层的锅（提示文案和按钮都为它服务）。
     *
     * 顺序不能反：没授权时说「手机定位服务未开启」是误导（用户第一件事该是授权）；
     * 两层都正常时才是「这一轮没取到」。
     */
    private fun diagnoseLocationIssue(): LocationIssue = when {
        !locationRepository.hasPermission() -> LocationIssue.PermissionMissing
        !locationRepository.isSystemLocationEnabled() -> LocationIssue.ServiceDisabled
        else -> LocationIssue.Unavailable
    }

    private fun loadNearbyStops(
        lat: Double,
        lng: Double,
        cityOverride: TransitCity? = null,
        showLoading: Boolean = true
    ) {
        viewModelScope.launch {
            if (showLoading) {
                edit { it.copy(isLoadingNearby = true) }
            }
            val targetCity = cityOverride ?: _uiState.value.currentCity
            val result = realtime.getNearbyStops(targetCity, lat, lng, limit = NEARBY_STOPS_LIMIT)
            val stops = result.getOrNull().orEmpty()
            edit {
                it.copy(
                    nearbyStops = stops,
                    isLoadingNearby = false
                )
            }
        }
    }

    private fun refreshFavoritesRealtime() {
        viewModelScope.launch {
            val result = realtime.refreshFavoritesRealtime()
            val map = result.getOrNull().orEmpty()
            if (map.isNotEmpty()) {
                edit { it.copy(favoritesRealtime = map) }
            }
        }
    }

    /**
     * 整体刷新（重新定位、刷新附近公交与收藏线路实时到站）
     *
     * @param switchCity 为 true 时把城市切到定位城市（仅城市弹窗里的「重新定位」使用）。
     * 启动、授权、下拉刷新等自动场景传 false：只更新坐标，不覆盖用户已选的城市（Issue #6）。
     * 从未存过城市（首次安装）时，即使传 false 也会切到定位城市。
     */
    fun refresh(switchCity: Boolean = false) {
        viewModelScope.launch {
            edit { it.copy(isRefreshing = true, isLocating = true, locationIssue = null) }

            val favJob = launch {
                val favResult = realtime.refreshFavoritesRealtime()
                edit { it.copy(favoritesRealtime = favResult.getOrNull().orEmpty()) }
            }

            val gpsLocation = locationRepository.bestFix(forceFresh = true)

            val nearbyJob = launch {
                val (lat, lng, targetCity) = if (gpsLocation != null) {
                    val lat = gpsLocation.lat
                    val lng = gpsLocation.lng
                    userLocationStore.updateUserLocation(lat, lng)
                    // 带精度与定位时刻喂给换乘引擎，过期 / 不准的定位不参与下车站判定
                    trackingController.updateUserFix(gpsLocation)

                    // 尝试反向根据坐标解析匹配城市
                    val resolvedCity = try {
                        realtime.resolveCityByLocation(lat, lng).getOrNull()
                    } catch (_: Exception) {
                        null
                    }

                    val shouldSwitchCity = switchCity || !settings.hasSavedCity.value
                    if (resolvedCity != null && shouldSwitchCity) {
                        settings.selectCity(resolvedCity)
                        edit {
                            it.copy(
                                currentCity = resolvedCity,
                                locatedCity = resolvedCity,
                                selectedProvince = resolvedCity.province.ifBlank {
                                    ProvinceCityCatalog.inferProvince(resolvedCity.cityName)
                                },
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationIssue = null
                            )
                        }
                        Triple(lat, lng, resolvedCity)
                    } else {
                        // 保留用户已选城市，只记录定位城市供城市弹窗展示「定位: X」
                        edit {
                            it.copy(
                                locatedCity = resolvedCity ?: it.locatedCity,
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationIssue = null
                            )
                        }
                        Triple(lat, lng, settings.selectedCity.value)
                    }
                } else {
                    val currentCity = _uiState.value.currentCity
                    val state = _uiState.value
                    val lat = if (state.currentLat > 0.0) state.currentLat else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).first
                    val lng = if (state.currentLng > 0.0) state.currentLng else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).second
                    edit {
                        it.copy(
                            currentLat = lat,
                            currentLng = lng,
                            isLocating = false,
                            locationIssue = diagnoseLocationIssue()
                        )
                    }
                    Triple(lat, lng, currentCity)
                }

                val firstResult = realtime.getNearbyStops(targetCity, lat, lng, limit = NEARBY_STOPS_LIMIT)
                var stops = firstResult.getOrNull().orEmpty()

                // 人不在已选城市（如出差在外）时，GPS 坐标查不到该城市的站点：改用已选城市中心点再查一次
                if (stops.isEmpty() && gpsLocation != null && !targetCity.matchesCity(_uiState.value.locatedCity)) {
                    val center = ProvinceCityCatalog.findCityCenterCoordinates(targetCity.cityName)
                    if (center != null) {
                        stops = realtime.getNearbyStops(targetCity, center.first, center.second, limit = NEARBY_STOPS_LIMIT)
                            .getOrNull().orEmpty()
                        edit { it.copy(currentLat = center.first, currentLng = center.second) }
                    }
                }

                edit {
                    it.copy(
                        nearbyStops = stops,
                        isLoadingNearby = false
                    )
                }
                // 没连上网时附近站会空着：说一句，不然看着像「附近没站」
                if (firstResult.isFailure && stops.isEmpty()) {
                    addMessage(NEARBY_FAILED, elderText = NEARBY_FAILED_ELDER)
                }
            }

            favJob.join()
            nearbyJob.join()
            edit { it.copy(isRefreshing = false, isLocating = false, isLoadingNearby = false) }
            lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
        }
    }

    /**
     * 设置轮询是否激活（由 [BusHomeRoute] 的 `LifecycleStartEffect` 调：首页 entry 在组合里且 ≥ STARTED 时为 true）。
     * 从暂停恢复时，若距上次刷新已超过一个轮询周期，立即补刷一次，而不是再等一个完整周期。
     */
    fun setPollingActive(active: Boolean) {
        val resuming = active && !pollingCoordinator.isPollingActive
        pollingCoordinator.setActive(active)
        if (!active) {
            catchUpPollJob?.cancel()
            catchUpPollJob = null
            return
        }
        val lastRefresh = lastRealtimeRefreshAtMs
        if (resuming && lastRefresh > 0L &&
            elapsedRealtimeMillis() - lastRefresh >= POLLING_INTERVAL_MS &&
            catchUpPollJob?.isActive != true
        ) {
            catchUpPollJob = viewModelScope.launch { pollRealtimeOnce() }
        }
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /**
     * 恢复/启动后台轮询（幂等，避免重复启动）
     */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    fun setCityPickerOpen(isOpen: Boolean) {
        edit {
            val defaultProvince = if (isOpen) {
                it.currentCity.province.ifBlank { ProvinceCityCatalog.inferProvince(it.currentCity.cityName) }
            } else {
                it.selectedProvince
            }
            it.copy(isCityPickerOpen = isOpen, citySearchQuery = "", selectedProvince = defaultProvince)
        }
    }

    fun updateCitySearchQuery(query: String) {
        edit { it.copy(citySearchQuery = query) }
    }

    fun selectProvince(province: String) {
        edit { it.copy(selectedProvince = province) }
    }

    fun selectCity(city: TransitCity) {
        viewModelScope.launch {
            val state = _uiState.value
            val isLocated = city.matchesCity(state.locatedCity)
            val coords = if (isLocated) {
                // 优先用真实 GPS 坐标：当前坐标可能已回退为其他城市的中心点
                userLocationStore.userLocation.value ?: Pair(state.currentLat, state.currentLng)
            } else {
                ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
            }

            // 若所选城市仅某单一数据源支持，自动联动切换至支持的引擎
            if (city.cityId.isBlank() && !city.mygolbsCityCode.isNullOrBlank() && state.engineMode != EngineMode.MYGOLBS) {
                setEngineMode(EngineMode.MYGOLBS)
            } else if (city.cityId.isNotBlank() && city.mygolbsCityCode.isNullOrBlank() && state.engineMode != EngineMode.CHELAILE) {
                setEngineMode(EngineMode.CHELAILE)
            }

            edit {
                it.copy(
                    currentCity = city,
                    selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                    isCityPickerOpen = false,
                    citySearchQuery = "",
                    currentLat = coords.first,
                    currentLng = coords.second
                )
            }
            settings.selectCity(city)
            loadNearbyStops(coords.first, coords.second, cityOverride = city)
            refreshFavoritesRealtime()
        }
    }

    fun setEngineMode(mode: EngineMode) {
        settings.setEngineMode(mode)
    }

    fun toggleEngineMode() {
        val nextMode = when (_uiState.value.engineMode) {
            EngineMode.MYGOLBS -> EngineMode.CHELAILE
            EngineMode.CHELAILE -> EngineMode.MYGOLBS
        }
        setEngineMode(nextMode)
    }

    /** 分组 chips：null =「全部」；不存在的分组当「全部」 */
    fun selectGroup(groupId: String?) {
        edit { state ->
            val valid = groupId?.takeIf { id -> state.favoriteGroups.any { it.id == id } }
            state.copy(selectedGroupId = valid)
        }
    }

    /**
     * 一张追踪卡上的「结束」：只停这一项，别的追踪继续。
     * @param watchLineId 一起等里的哪一条线（只去掉这条）；null = 结束整份会话
     */
    fun stopTracking(sessionId: String, watchLineId: String? = null) {
        try {
            if (watchLineId != null) {
                trackingController.removeWatchLine(sessionId, watchLineId)
            } else {
                trackingController.stopTracking(sessionId)
            }
        } catch (_: Exception) {
        }
    }

    /** 点「乘车码」：选了平台就直接打开；「每次询问」才弹选择（v3，不藏在长按里） */
    fun onTransitCodeClicked() {
        when (val provider = settings.preferredTransitCodeProvider.value) {
            TransitCodeProvider.ASK_ALWAYS -> edit { it.copy(isTransitCodeSheetOpen = true) }
            else -> transitCodeLauncher.open(provider)
        }
    }

    /** 在选择弹层里选了平台；勾了「以后都用这个」就记成默认 */
    fun onTransitCodeChosen(provider: TransitCodeProvider, saveAsDefault: Boolean) {
        edit { it.copy(isTransitCodeSheetOpen = false) }
        if (provider == TransitCodeProvider.ASK_ALWAYS) return
        if (saveAsDefault) {
            viewModelScope.launch { settings.setPreferredTransitCodeProvider(provider) }
        }
        // 打不开（没装）时 Android 实现自己会弹 Toast，这里不再重复提示
        transitCodeLauncher.open(provider)
    }

    fun onMessageShown(id: Long) {
        edit { state -> state.copy(userMessages = state.userMessages.filterNot { it.id == id }) }
    }

    private fun addMessage(text: String, elderText: String? = null) {
        val id = nextMessageId++
        edit { state -> state.copy(userMessages = state.userMessages + UserMessage(id, text, elderText)) }
    }

    /** 读一张卡片的到站；手机读不了时给一条提示 */
    private fun readAloud(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (!announcer.speak(text)) addMessage(TTS_UNAVAILABLE_MESSAGE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pausePolling()
    }

    companion object {
        /** 附近站固定 6 个（v3：删掉「附近站数」设置） */
        const val NEARBY_STOPS_LIMIT = 6
        const val POLLING_INTERVAL_MS = 15_000L

        const val NEARBY_FAILED = "附近站没刷出来，下拉再试一次"

        /** 长辈首页没有下拉刷新：附近站空着时下面有「重新定位」按钮（第 32 步遗留） */
        const val NEARBY_FAILED_ELDER = "附近站没刷出来，点下面的「重新定位」再试一次"
    }
}
