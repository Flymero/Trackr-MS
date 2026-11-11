package com.trackr.ui.screens.stopdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.domain.tracking.WatchTogetherRoute
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.WatchLine
import com.trackr.core.model.displayLineName
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * 站点详情 ViewModel（第 21 步，v3）：
 * 拉取站点详情（各物理站台全部途经线路与实时到站）、10 秒自动轮询、收藏切换、站台切换；
 * 选几条线一起等 / 存成 1 程路线；本站一起等的进行中视图与「上了哪辆？」；乘车码一键打开。
 */
@OptIn(ExperimentalAtomicApi::class)
class StopDetailViewModel(
    val cityId: String,
    private val stopId: String,
    private val initialStopName: String,
    private val physicalStId: String = "",
    private val namesakeStId: String = "",
    private val stationLat: Double = 0.0,
    private val stationLng: Double = 0.0,
    // 第 17 步：原来的 ITransitRepository 拆成只拿用到的几个接口
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val realtime: RealtimeRepository,
    private val userLocationStore: UserLocationStore,
    // 第 21 步：一起等、乘车码
    private val trackingRepository: TrackingRepository,
    private val trackingController: TrackingController,
    private val transitCodeLauncher: TransitCodeLauncher,
    private val enableAutoPolling: Boolean = true,
    /** 单调时钟（毫秒），用于判断恢复页面时数据是否过期；测试可注入 */
    private val clockMillis: () -> Long = { ProcessMonotonicClock.elapsedMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StopDetailUiState(stopName = initialStopName.ifBlank { "站点详情" })
    )
    val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

    private val _userMessages = Channel<String>(Channel.BUFFERED)

    /** 一次性提示（Snackbar） */
    val userMessages: Flow<String> = _userMessages.receiveAsFlow()

    /** 进行中的站点请求数（含手动刷新、切换站台、轮询） */
    private val activeLoads = AtomicInt(0)

    /** 最近一次发起刷新的时刻（[clockMillis]），null 表示尚未请求过 */
    @Volatile
    private var lastRefreshAtMillis: Long? = null

    /** 当前追踪会话（只用来判断是不是本站的一起等） */
    private var session: TrackingSession? = null

    /** 用户划掉的「上了哪辆？」（按写下时刻认）；同一次待选不再弹 */
    private var dismissedBoardChoiceSince: Long? = null

    /** 已经按哪个一起等会话把站台切过去（只切一次，之后用户可以自己换） */
    private var alignedWatchSessionId: String? = null

    /** 收藏的站（issue #47），只用来认出这个站收藏了没有 */
    private var stationFavorites: List<FavoriteStation> = emptyList()

    /** 用户自己切过站台（之后才问「默认方向改成 X？」） */
    private var userSwitchedPlatform = false

    /** 「默认方向改成 X？」这次打开已经问过了 */
    private var platformPromptHandled = false

    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = FixedDelayPollingStrategy(delayMs = POLL_INTERVAL_SECONDS * 1000L),
        initialEnabled = enableAutoPolling
    ) {
        // 直接在轮询协程内挂起执行：暂停轮询时请求随之取消；上一轮未完成就跳过。
        // 本站一起等进行中时追踪服务在轮询，页面自己不再请求（省服务器）；页面顶部那张
        // 「最先到」的卡片用的是会话里的实时数据，下面的线路列表用打开页面时的那一份。
        if (activeLoads.load() == 0 && _uiState.value.watch == null) {
            fetchStopDetail(isInitial = false)
        }
    }

    /** 当前是否有站点请求在进行（测试用） */
    internal val isLoadInFlight: Boolean
        get() = activeLoads.load() > 0

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    init {
        // 监听收藏夹变动
        viewModelScope.launch {
            favorites.favorites.collectLatest { list ->
                val ids = list.map { it.id }.toSet()
                publish { it.copy(favoriteIds = ids) }
            }
        }
        viewModelScope.launch {
            favorites.favoriteStations.collectLatest { list ->
                stationFavorites = list
                publish { it }
            }
        }
        viewModelScope.launch {
            settings.preferredTransitCodeProvider.collectLatest { provider ->
                _uiState.update { it.copy(preferredTransitCodeProvider = provider) }
            }
        }
        // 「我的」里关掉「显示乘车码按钮」后，底栏这个圆钮和首页、线路页一起不画
        viewModelScope.launch {
            settings.showTransitCodeButton.collectLatest { visible ->
                _uiState.update { it.copy(showTransitCodeButton = visible) }
            }
        }
        // 同时可能追着好几项：只认这个站的那一份一起等（第 36 步）
        viewModelScope.launch {
            trackingRepository.sessions.collectLatest { onSession(watchSessionFor(it)) }
        }

        loadStopDetail(isInitial = true)
    }

    // ---------------------------------------------------------------- 事件

    fun onEvent(event: StopDetailEvent) {
        when (event) {
            StopDetailEvent.Back -> handleBack()
            is StopDetailEvent.OpenLine -> Unit // 导航由 Route 处理
            StopDetailEvent.PullRefresh -> pullRefresh()
            StopDetailEvent.Retry -> loadStopDetail(isInitial = true)
            is StopDetailEvent.SelectPlatform -> selectStopIndex(event.index)
            is StopDetailEvent.ToggleFavorite -> toggleFavoriteByKey(event.rowKey)
            StopDetailEvent.ToggleStationFavorite -> toggleStationFavorite()
            StopDetailEvent.AcceptDefaultPlatform -> acceptDefaultPlatform()
            StopDetailEvent.DismissDefaultPlatformPrompt -> {
                platformPromptHandled = true
                publish { it }
            }
            StopDetailEvent.StartSelecting -> _uiState.update { it.copy(selecting = true, selectedLineKeys = emptySet()) }
            is StopDetailEvent.StartSelectingWith -> startSelectingWith(event.rowKey)
            StopDetailEvent.CancelSelecting -> exitSelecting()
            is StopDetailEvent.ToggleLine -> toggleLine(event.rowKey)
            StopDetailEvent.SaveSelectionAsRoute -> saveSelectionAsRoute()
            StopDetailEvent.StartWatching -> startWatching()
            StopDetailEvent.AddLine -> addLine()
            StopDetailEvent.ConfirmBoarded -> trackingController.confirmBoarded(session?.sessionId)
            // 一起等会话 id 一定来自当前页面对上的那一份；对不上就不动，避免把别的追踪一起停掉
            StopDetailEvent.StopWatching -> session?.sessionId?.let(trackingController::stopTracking)
            is StopDetailEvent.ChooseBoardedBus ->
                trackingController.chooseBoardedBus(event.busId, session?.sessionId)
            StopDetailEvent.DismissBoardChoice -> {
                dismissedBoardChoiceSince = session?.boardChoice?.sinceMillis
                publish { it }
            }
            StopDetailEvent.OpenTransitCode -> openTransitCode()
            StopDetailEvent.DismissTransitCodeSheet -> _uiState.update { it.copy(isTransitCodeSheetOpen = false) }
            is StopDetailEvent.PickTransitCode -> pickTransitCode(event.provider, event.saveAsDefault)
        }
    }

    /** 返回键：选线模式里先退出选线（返回 true），否则交给导航（false） */
    fun handleBack(): Boolean {
        if (!_uiState.value.selecting) return false
        exitSelecting()
        return true
    }

    // ---------------------------------------------------------------- 加载与轮询

    /**
     * 加载当前站点详情与实时车辆
     */
    fun loadStopDetail(isInitial: Boolean = false) {
        viewModelScope.launch {
            fetchStopDetail(isInitial)
        }
    }

    /**
     * 手动立即刷新，并重新计时（下一轮自动刷新从现在起再等一个周期）
     */
    fun refreshNow() {
        pollingCoordinator.restart()
        loadStopDetail(isInitial = false)
    }

    private fun pullRefresh() {
        _uiState.update { it.copy(isPullRefreshing = true) }
        pollingCoordinator.restart()
        viewModelScope.launch {
            try {
                fetchStopDetail(isInitial = false)
            } finally {
                _uiState.update { it.copy(isPullRefreshing = false) }
            }
        }
    }

    /**
     * 挂起执行一次站点详情请求并更新 UI 状态
     */
    private suspend fun fetchStopDetail(isInitial: Boolean) {
        activeLoads.incrementAndFetch()
        lastRefreshAtMillis = clockMillis()
        try {
            if (isInitial) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val currentCity = settings.selectedCity.value
            val city = if (currentCity.cityId == cityId) {
                currentCity
            } else {
                TransitCity(cityId = cityId, cityName = "")
            }

            val userLoc = userLocationStore.userLocation.value
            val currentSelected = _uiState.value.stops.getOrNull(_uiState.value.selectedStopIndex)
            val queryPhysicalStId = currentSelected?.physicalStId?.takeIf { it.isNotBlank() } ?: physicalStId.takeIf { it.isNotBlank() }
            val queryNamesakeStId = currentSelected?.namesakeStId?.takeIf { it.isNotBlank() } ?: namesakeStId.takeIf { it.isNotBlank() }
            val queryStopId = currentSelected?.stopId?.takeIf { it.isNotBlank() } ?: stopId.takeIf { it.isNotBlank() }

            val result = realtime.getStopDetail(
                city = city,
                stopId = queryStopId,
                stopName = initialStopName.takeIf { it.isNotBlank() },
                physicalStId = queryPhysicalStId,
                namesakeStId = queryNamesakeStId,
                stationLat = stationLat.takeIf { it > 0.0 },
                stationLng = stationLng.takeIf { it > 0.0 },
                userLat = userLoc?.first,
                userLng = userLoc?.second
            )

            result.onSuccess { rawStopList ->
                // 若站点缺少 distanceMeters，且用户位置和站点坐标都已知，计算实际测地距离。
                // 坐标 0,0 是「没给」，不能拿去算：否则会算出上万公里。
                val stopList = if (userLoc != null) {
                    rawStopList.map { s ->
                        if ((s.distanceMeters ?: 0) <= 0 && s.wgsLat > 0.0 && s.wgsLng > 0.0) {
                            val dist = RouteProjectionMath.calculateDistanceMeters(
                                userLoc.first, userLoc.second, s.wgsLat, s.wgsLng
                            )
                            s.copy(distanceMeters = dist)
                        } else s
                    }
                } else rawStopList

                val resolvedName = stopList.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                    ?: initialStopName.ifBlank { "站点详情" }
                val currentPhyId = currentSelected?.physicalStId
                val newIndex = if (currentPhyId != null && stopList.isNotEmpty()) {
                    val idx = stopList.indexOfFirst { it.physicalStId == currentPhyId }
                    if (idx >= 0) idx else _uiState.value.selectedStopIndex.coerceIn(0, stopList.size - 1)
                } else if (stopList.isNotEmpty()) {
                    // 第一次加载：停在打开时给的那个站台上（从首页的站收藏点进来就是默认站台）
                    initialPlatformIndex(stopList) ?: _uiState.value.selectedStopIndex.coerceIn(0, stopList.size - 1)
                } else 0

                publish {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        stopName = resolvedName,
                        stops = stopList,
                        selectedStopIndex = newIndex,
                        errorMessage = if (stopList.isEmpty()) "暂未查询到该站点的途经线路" else null
                    )
                }
                alignToWatchedPlatform()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = err.message ?: "获取站点信息失败，请重试"
                    )
                }
            }
        } catch (ce: CancellationException) {
            // 轮询暂停等取消场景：复位加载指示，不显示错误
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            throw ce
        } finally {
            activeLoads.decrementAndFetch()
        }
    }

    /** 打开时给的站台在结果里的位置；认不出为 null */
    private fun initialPlatformIndex(stops: List<NearbyStop>): Int? {
        val idx = stops.indexOfFirst { stop ->
            (physicalStId.isNotBlank() && stop.physicalStId == physicalStId) ||
                (stopId.isNotBlank() && stop.stopId == stopId)
        }
        return idx.takeIf { it >= 0 }
    }

    fun selectStopIndex(index: Int) {
        if (index in _uiState.value.stops.indices) {
            val changed = index != _uiState.value.selectedStopIndex
            if (changed) userSwitchedPlatform = true
            // 一起等只在一个站台：换站台清空勾选
            publish {
                it.copy(
                    selectedStopIndex = index,
                    selectedLineKeys = if (changed) emptySet() else it.selectedLineKeys
                )
            }
            val targetStop = _uiState.value.stops[index]
            val lacksLines = targetStop.lines.isEmpty() || targetStop.lines.all { it.line.lineId.isBlank() }
            // 其他物理站台的数据来自缓存补全，切换后以该站台为主查询立即刷新一次，并重新计时
            if (changed || lacksLines) {
                pollingCoordinator.restart()
                loadStopDetail(isInitial = false)
            }
        }
    }

    // ---------------------------------------------------------------- 收藏

    fun toggleFavorite(stop: NearbyStop, lineStatus: NearbyStopLineStatus) {
        viewModelScope.launch {
            val city = settings.selectedCity.value
            val cityName = if (city.cityId == cityId) city.cityName else ""
            val favItem = FavoriteStationLine(
                id = favoriteIdOf(cityId, lineStatus),
                cityId = cityId,
                cityName = cityName,
                lineId = lineStatus.line.lineId,
                // 数据源偶尔只给线路号不给名字：收藏里存兜底后的名字，首页徽标才不会空白（issue #20）
                lineName = lineStatus.line.displayLineName,
                lineNo = lineStatus.line.lineNo,
                direction = lineStatus.line.direction,
                startStop = lineStatus.line.startStop,
                endStop = lineStatus.line.endStop,
                targetStopId = lineStatus.targetStopId.ifBlank { stop.stopId },
                targetStopName = lineStatus.targetStopName.ifBlank { stop.name },
                targetOrder = lineStatus.targetOrder,
                source = lineStatus.line.source
            )
            favorites.toggleFavorite(favItem)
        }
    }

    private fun toggleFavoriteByKey(rowKey: String) {
        val stop = currentStop() ?: return
        val status = stop.lines.firstOrNull { rowKeyOf(it) == rowKey } ?: return
        if (status.line.lineId.isBlank()) return
        toggleFavorite(stop, status)
    }

    // ---------------------------------------------------------------- 收藏整个站（issue #47）

    /**
     * 这个页面对应的站收藏：按站 id（城市 + 数据源 + 同名站 id / 站名）认；还没拿到站台时按打开时给的站台 id 认，
     * 站台有了以后任一站台和收藏的默认站台对上也算（换过数据源、同名站 id 变了也认得出）。
     */
    private fun matchedStation(state: StopDetailUiState): FavoriteStation? {
        val sameCity = stationFavorites.filter { it.cityId == cityId }
        if (sameCity.isEmpty()) return null
        val id = stationIdOf(state)
        return sameCity.firstOrNull { it.id == id }
            ?: sameCity.firstOrNull { fav -> state.stops.any { isSamePlatform(it, fav) } }
            ?: if (state.stops.isEmpty()) {
                sameCity.firstOrNull { fav ->
                    (physicalStId.isNotBlank() && fav.physicalStId == physicalStId) ||
                        (stopId.isNotBlank() && fav.stopId == stopId)
                }
            } else {
                null
            }
    }

    private fun stationIdOf(state: StopDetailUiState): String? {
        val first = state.stops.firstOrNull() ?: return null
        val namesake = state.stops.firstNotNullOfOrNull { it.namesakeStId?.takeIf(String::isNotBlank) } ?: namesakeStId
        return FavoriteStation.idOf(cityId, first.source, state.stopName, namesake)
    }

    private fun isSamePlatform(stop: NearbyStop, fav: FavoriteStation): Boolean =
        (fav.physicalStId.isNotBlank() && stop.physicalStId == fav.physicalStId) ||
            (fav.stopId.isNotBlank() && stop.stopId == fav.stopId)

    /** 当前站台存成站收藏（收藏时、改默认方向时用） */
    private fun stationFromCurrent(state: StopDetailUiState, base: FavoriteStation?): FavoriteStation? {
        val stop = state.stops.getOrNull(state.selectedStopIndex) ?: return null
        val id = base?.id ?: stationIdOf(state) ?: return null
        val city = settings.selectedCity.value
        val label = if (state.stops.size > 1) state.platformLabels.getOrNull(state.selectedStopIndex).orEmpty() else ""
        val lat = stop.wgsLat.takeIf { it > 0.0 } ?: stationLat
        val lng = stop.wgsLng.takeIf { it > 0.0 } ?: stationLng
        val mygolbsCode = stop.lines.firstNotNullOfOrNull { s -> s.line.mygolbsCityCode.takeIf { it.isNotBlank() } }
            ?: city.mygolbsCityCode?.takeIf { city.cityId == cityId }.orEmpty()
        val fresh = FavoriteStation(
            id = id,
            cityId = cityId,
            cityName = if (city.cityId == cityId) city.cityName else base?.cityName.orEmpty(),
            stopName = state.stopName,
            stopId = stop.stopId,
            physicalStId = stop.physicalStId.orEmpty(),
            namesakeStId = stop.namesakeStId?.takeIf { it.isNotBlank() } ?: namesakeStId,
            platformLabel = label,
            wgsLat = lat,
            wgsLng = lng,
            source = stop.source,
            mygolbsCityCode = mygolbsCode
        )
        return if (base != null) fresh.copy(addedAtMillis = base.addedAtMillis) else fresh
    }

    private fun stationTitle(fav: FavoriteStation): String =
        listOf(fav.stopName, fav.platformLabel).filter { it.isNotBlank() }.joinToString(" · ")

    private fun toggleStationFavorite() {
        val state = _uiState.value
        val existing = matchedStation(state)
        viewModelScope.launch {
            if (existing != null) {
                favorites.removeStation(existing.id)
                _userMessages.trySend("已取消收藏 ${existing.stopName}")
            } else {
                val station = stationFromCurrent(state, base = null) ?: return@launch
                favorites.toggleStation(station)
                // 刚收藏：当前站台就是默认，不再问
                platformPromptHandled = true
                _userMessages.trySend("已收藏 ${stationTitle(station)}")
            }
        }
    }

    private fun acceptDefaultPlatform() {
        platformPromptHandled = true
        val state = _uiState.value
        val existing = matchedStation(state)
        val updated = existing?.let { stationFromCurrent(state, base = it) }
        publish { it }
        if (updated == null) return
        viewModelScope.launch {
            favorites.updateStation(updated)
            _userMessages.trySend("默认方向改成「${updated.platformLabel.ifBlank { updated.stopName }}」")
        }
    }

    /** 「默认方向改成 X？」：收藏过、用户自己切到了不是默认的站台、这次还没问过 */
    private fun defaultPlatformPromptOf(state: StopDetailUiState, fav: FavoriteStation?): String? {
        if (fav == null || platformPromptHandled || !userSwitchedPlatform || state.stops.size < 2) return null
        val stop = state.stops.getOrNull(state.selectedStopIndex) ?: return null
        if (isSamePlatform(stop, fav)) return null
        return state.platformLabels.getOrNull(state.selectedStopIndex)?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- 选线 / 一起等

    private fun exitSelecting() {
        _uiState.update { it.copy(selecting = false, selectedLineKeys = emptySet()) }
    }

    /** 长按一条线进选线模式：这条能选就顺手勾上 */
    private fun startSelectingWith(rowKey: String) {
        _uiState.update {
            val row = it.rows.firstOrNull { r -> r.key == rowKey }
            it.copy(
                selecting = true,
                selectedLineKeys = if (row?.selectable == true) setOf(rowKey) else emptySet()
            )
        }
    }

    private fun toggleLine(rowKey: String) {
        val row = _uiState.value.rows.firstOrNull { it.key == rowKey } ?: return
        if (!row.selectable) return
        // 条数不设上限（Q8）
        _uiState.update {
            val keys = if (rowKey in it.selectedLineKeys) it.selectedLineKeys - rowKey else it.selectedLineKeys + rowKey
            it.copy(selectedLineKeys = keys)
        }
    }

    /** 勾上的线，按当前站台的行顺序 */
    private fun selectedStatuses(): List<NearbyStopLineStatus> {
        val stop = currentStop() ?: return emptyList()
        val keys = _uiState.value.selectedLineKeys
        return stop.lines.filter { rowKeyOf(it) in keys && it.line.lineId.isNotBlank() && it.targetOrder > 0 }
    }

    private fun watchTargetOfSelection(): TrackingTarget.WatchTogether? {
        val stop = currentStop() ?: return null
        val statuses = selectedStatuses()
        if (statuses.isEmpty()) return null
        val city = settings.selectedCity.value
        return TrackingTarget.WatchTogether(
            cityId = cityId,
            stopId = stop.stopId,
            stopName = stop.name.ifBlank { _uiState.value.stopName },
            lines = statuses.map { s ->
                WatchLine(
                    lineId = s.line.lineId,
                    lineName = s.line.displayLineName,
                    stopOrder = s.targetOrder,
                    endStopName = s.line.endStop,
                    direction = s.line.direction,
                    source = s.line.source,
                    stopIdOnLine = s.targetStopId
                )
            },
            cityName = if (city.cityId == cityId) city.cityName else "",
            physicalStId = stop.physicalStId?.takeIf { it.isNotBlank() },
            namesakeStId = stop.namesakeStId?.takeIf { it.isNotBlank() },
            stopLat = stop.wgsLat,
            stopLng = stop.wgsLng,
            mygolbsCityCode = statuses.firstNotNullOfOrNull { s -> s.line.mygolbsCityCode.takeIf { it.isNotBlank() } }.orEmpty()
        )
    }

    private fun saveSelectionAsRoute() {
        val target = watchTargetOfSelection() ?: return
        val extras = selectedStatuses().associate { it.line.lineId to WatchTogetherRoute.LineExtras(startStopName = it.line.startStop) }
        val route = WatchTogetherRoute.toRoute(target, extras)
        viewModelScope.launch {
            favorites.saveRoute(route)
            _userMessages.trySend("已收藏，首页点一下就能一起等")
        }
        exitSelecting()
    }

    private fun startWatching() {
        val target = watchTargetOfSelection() ?: return
        if (trackingController.start(target)) {
            exitSelecting()
        } else {
            _userMessages.trySend("没能开始一起等，请再试一次")
        }
    }

    /** 「再加一条」：带着正在等的线进选线模式，并刷新一次（进行中时页面自己不轮询，数据可能旧了） */
    private fun addLine() {
        val watching = session?.watchTarget?.lines.orEmpty().map { it.lineId }.toSet()
        val keys = currentStop()?.lines.orEmpty()
            .filter { it.line.lineId in watching }
            .map { rowKeyOf(it) }
            .toSet()
        _uiState.update { it.copy(selecting = true, selectedLineKeys = keys) }
        if (activeLoads.load() == 0) loadStopDetail(isInitial = false)
    }

    /** 这个站的那一份一起等会话（别的站、单线路、路线都不算） */
    private fun watchSessionFor(sessions: List<TrackingSession>): TrackingSession? = sessions.firstOrNull { candidate ->
        if (candidate.interrupted) return@firstOrNull false
        val watch = candidate.watchTarget ?: return@firstOrNull false
        isWatchingHere(candidate, stopId, _uiState.value.stops) ||
            (watch.stopId.isNotBlank() && watch.stopId == stopId) ||
            (!watch.physicalStId.isNullOrBlank() && watch.physicalStId == physicalStId) ||
            (!watch.namesakeStId.isNullOrBlank() && watch.namesakeStId == namesakeStId)
    }

    private fun onSession(next: TrackingSession?) {
        val wasWatching = _uiState.value.watch != null
        session = next
        publish { it }
        alignToWatchedPlatform()
        // 一起等结束：页面恢复自己轮询，先补刷一次（等的期间没请求过）
        if (wasWatching && _uiState.value.watch == null && enableAutoPolling && activeLoads.load() == 0) {
            pollingCoordinator.restart()
            loadStopDetail(isInitial = false)
        }
    }

    /** 本站一起等第一次对上时，把站台切到会话那个站台（不额外请求） */
    private fun alignToWatchedPlatform() {
        val current = session ?: return
        val watch = current.watchTarget ?: return
        if (alignedWatchSessionId == current.sessionId) return
        val stops = _uiState.value.stops
        if (stops.isEmpty() || !isWatchingHere(current, stopId, stops)) return
        alignedWatchSessionId = current.sessionId
        val index = stops.indexOfFirst {
            (watch.stopId.isNotBlank() && it.stopId == watch.stopId) ||
                (!watch.physicalStId.isNullOrBlank() && it.physicalStId == watch.physicalStId)
        }
        if (index >= 0 && index != _uiState.value.selectedStopIndex) {
            publish { it.copy(selectedStopIndex = index, selectedLineKeys = emptySet()) }
        }
    }

    // ---------------------------------------------------------------- 乘车码

    private fun openTransitCode() {
        when (val provider = _uiState.value.preferredTransitCodeProvider) {
            TransitCodeProvider.ASK_ALWAYS -> _uiState.update { it.copy(isTransitCodeSheetOpen = true) }
            else -> launchTransitCode(provider)
        }
    }

    private fun pickTransitCode(provider: TransitCodeProvider, saveAsDefault: Boolean) {
        _uiState.update { it.copy(isTransitCodeSheetOpen = false) }
        if (saveAsDefault) {
            viewModelScope.launch { settings.setPreferredTransitCodeProvider(provider) }
        }
        launchTransitCode(provider)
    }

    private fun launchTransitCode(provider: TransitCodeProvider) {
        if (!transitCodeLauncher.open(provider)) {
            _userMessages.trySend("没能打开乘车码，请确认装了对应 App")
        }
    }

    // ---------------------------------------------------------------- 状态

    private fun currentStop(): NearbyStop? = _uiState.value.stops.getOrNull(_uiState.value.selectedStopIndex)

    /** 改原始字段，并按新值重算派生字段（站台名、行、小字、一起等视图、弹层） */
    private fun publish(transform: (StopDetailUiState) -> StopDetailUiState) {
        _uiState.update { old ->
            val transformed = transform(old)
            // 站台名先算好：站收藏要用它
            val s = transformed.copy(platformLabels = platformLabels(transformed.stops))
            val stop = s.stops.getOrNull(s.selectedStopIndex)
            val station = matchedStation(s)
            s.copy(
                isStationFavorite = station != null,
                defaultPlatformPrompt = defaultPlatformPromptOf(s, station),
                subtitle = subtitleOf(stop),
                rows = rowsOf(cityId, stop, s.favoriteIds),
                transferText = transferTextOf(stop),
                watch = watchUiOf(session, stopId, s.stops),
                boardChoice = boardChoiceUiOf(session, stopId, s.stops, dismissedBoardChoiceSince)
            )
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 设置轮询是否激活（生命周期感知或显式开关）
     */
    fun setPollingActive(active: Boolean) {
        pollingCoordinator.setActive(active)
    }

    /**
     * 页面回到前台（Lifecycle RESUMED）：恢复轮询；若距上次刷新已超过一个周期，立即刷新一次。
     * 测试或预览中 enableAutoPolling = false 时不做任何事。
     */
    fun onScreenResumed() {
        if (!enableAutoPolling) return
        pollingCoordinator.setActive(true)
        val last = lastRefreshAtMillis ?: return
        if (activeLoads.load() == 0 && _uiState.value.watch == null &&
            clockMillis() - last >= POLL_INTERVAL_SECONDS * 1000L
        ) {
            pollingCoordinator.restart()
            loadStopDetail(isInitial = false)
        }
    }

    /**
     * 页面离开前台（Lifecycle PAUSED / 移出组合）：停止轮询，进行中的轮询请求随之取消。
     */
    fun onScreenPaused() {
        pollingCoordinator.setActive(false)
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /**
     * 恢复/启动 10 秒自动轮询（幂等控制）
     */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    override fun onCleared() {
        super.onCleared()
        pausePolling()
    }

    companion object {
        /** 自动轮询周期（秒） */
        const val POLL_INTERVAL_SECONDS = 10
    }
}
