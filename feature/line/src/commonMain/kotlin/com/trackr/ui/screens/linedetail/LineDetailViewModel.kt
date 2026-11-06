package com.trackr.ui.screens.linedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.local.NoLineStopsCache
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.MeishanPushAvailability
import com.trackr.core.data.repository.MeishanPushRepository
import com.trackr.core.data.repository.MeishanPushTarget
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.NoAnnouncer
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.LiveBus
import com.trackr.core.model.RailMode
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitProtocolFailure
import com.trackr.core.model.TransitServiceFailure
import com.trackr.core.model.findStop
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import com.trackr.ui.screens.linedetail.components.stationArrivalState
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.io.IOException
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 主卡的三种内容
 */
enum class PanelMode {
    STATION_CARD, // 站点到站
    BUS_LIST,     // 到本站的全部车辆
    BUS_FOLLOW    // 单车跟随
}

/**
 * 几乎不变的部分（重构计划 §4.4「状态按变化频率拆」）：线路、反向线路、站表。
 * 内容没变时沿用旧实例，头部和走向条的站序不跟着轮询重组。
 */
data class LineStatic(
    val line: BusLineDirection,
    val reverseDirection: BusLineDirection?,
    val stops: List<BusStop>
)

/**
 * 每 10 秒变的部分：车辆、运营状态、下一班。
 * @param fallbackNextDeparture 实时接口没给下一班时，从时刻表补的下一班始发时间（HH:mm）
 */
data class LineLive(
    val buses: List<LiveBus>,
    val idleBuses: List<LiveBus> = emptyList(),
    val operatingState: LineOperatingState = LineOperatingState.RUNNING,
    val statusDescription: String = "",
    val nextDepartureFromStart: String? = null,
    val updatedAtMillis: Long = 0L,
    val fallbackNextDeparture: String? = null
)

/**
 * 一次性提示（提示条），页面显示后调 [LineDetailViewModel.onMessageShown]。
 * @param action 提示条上的按钮；null = 没有按钮
 */
data class LineUserMessage(
    val id: Long,
    val text: String,
    val action: Action? = null
) {
    enum class Action(val label: String) {
        /** 「换分组」：打开分组选择 */
        CHANGE_GROUP("换分组")
    }
}

/**
 * 线路详情页 UI 状态。高频部分只在 [live] 里。
 */
data class LineDetailUiState(
    /** 进页面时带来的线路名（数据回来前顶栏先显示它） */
    val lineNameHint: String = "",
    /** 进页面时带来的站序（从某个站点点进来才有）；页面据此把走向条直接滚到那一站 */
    val entryStopOrder: Int? = null,
    /** 第一次网络请求还没回来 */
    val isLoading: Boolean = true,
    val isPushOnline: Boolean = false,
    /** 有请求在进行（轮询、选站、手动刷新）；不用来画下拉圈 */
    val isRefreshing: Boolean = false,
    /** 用户下拉触发的刷新还没完成（只有它画下拉圈） */
    val isPullRefreshing: Boolean = false,
    /** 换向请求进行中：底栏换向钮转圈并禁用，避免连点改两次线路 */
    val isSwitchingDirection: Boolean = false,
    val static: LineStatic? = null,
    val live: LineLive? = null,
    val selectedStop: BusStop? = null,
    val panelMode: PanelMode = PanelMode.STATION_CARD,
    val followedBusId: String? = null,
    /** 单车跟随时返回到哪一级：从车辆列表进来回列表，从走向条点车进来回站点卡 */
    val followBackTo: PanelMode = PanelMode.STATION_CARD,
    val timetable: LineTimetable? = null,
    val isTimetableSheetOpen: Boolean = false,
    val errorMessage: String? = null,
    val isFavorite: Boolean = false,
    /** 收藏分组（「换分组」弹层用） */
    val favoriteGroups: List<FavoriteGroup> = emptyList(),
    /** 当前「线路 + 候车站」收藏所在分组；null = 不分组或没收藏 */
    val favoriteGroupId: String? = null,
    val isGroupPickerOpen: Boolean = false,
    val isReminderDialogOpen: Boolean = false,
    val preferredTransitCodeProvider: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val isTransitCodeSheetOpen: Boolean = false,
    /** 底栏「乘车码」圆钮显不显示（「我的」里的开关，和首页悬浮按钮同一个） */
    val showTransitCodeButton: Boolean = true,
    /** 走向条样式（「我的」全局设置）：横排只有横条，竖排是时间轴列表 */
    val railMode: RailMode = RailMode.Horizontal,
    /** 当前追踪会话（页面据此判断「本站是否正在追踪」） */
    val trackingSession: TrackingSessionState = TrackingSessionState(),
    val userMessages: List<LineUserMessage> = emptyList()
) {
    /** 正在追踪的就是这条线的这个候车站 */
    val isTrackingThisStop: Boolean
        get() = trackingSession.isActive &&
            static != null &&
            trackingSession.lineId == static.line.lineId &&
            trackingSession.targetOrder == selectedStop?.order
}

/**
 * 线路详情 ViewModel：
 * 站表先读磁盘缓存再等网络；每 10 秒轮询（只在页面 RESUMED 时）；选站后按该站拿精确 ETA；单车跟随；换向找同名站；
 * ☆ 一点即收藏（提示条可「换分组」）；定时提醒；追踪本站；长辈版「读给我听」。
 */
@OptIn(ExperimentalAtomicApi::class)
class LineDetailViewModel(
    val cityId: String,
    initialLineId: String,
    private val initialTargetOrder: Int? = null,
    private val initialStationId: String? = null,
    private val initialSource: DataSourceType = when {
        initialLineId.startsWith("ms:") -> DataSourceType.MEISHAN
        initialLineId.startsWith("mg:") -> DataSourceType.MYGOLBS
        else -> DataSourceType.CHELAILE
    },
    private val lineNameHint: String = "",
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val realtime: RealtimeRepository,
    private val pushRepository: MeishanPushRepository? = null,
    /** 定时提醒仓库（顶栏时钟 → 定时提醒弹窗保存用） */
    private val reminderRepository: IScheduledReminderRepository,
    /** 追踪会话（页面据此判断「本站是否正在追踪」） */
    private val trackingRepository: TrackingRepository,
    /** 追踪命令入口（开始 / 结束追踪本站） */
    private val trackingController: TrackingController,
    /** 线路站表磁盘缓存（只用来先显示，§2.4） */
    private val lineStopsCache: LineStopsCache = NoLineStopsCache,
    /** 「读给我听」（长辈版，§8.6） */
    private val announcer: Announcer = NoAnnouncer,
    private val enableAutoPolling: Boolean = true,
    private val nowHHmm: () -> String = { shanghaiNowHHmm() },
    /** 单调时钟（毫秒），用于判断恢复页面时数据是否过期；测试可注入 */
    private val clockMillis: () -> Long = { ProcessMonotonicClock.elapsedMillis() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LineDetailUiState(
            lineNameHint = lineNameHint,
            entryStopOrder = initialTargetOrder?.takeIf { it > 0 }
        )
    )
    val uiState: StateFlow<LineDetailUiState> = _uiState.asStateFlow()

    private var currentLineId: String = initialLineId
    private var currentSource: DataSourceType = initialSource

    /** 最近一次成功拿到的整份数据（换向、收藏、时刻表要用） */
    @Volatile
    private var currentDetail: BusLineDetail? = null

    /** 用户自己点过站：初次请求晚回来时不再改成默认候车站 */
    @Volatile
    private var userPickedStop = false

    /** 最近一次写进磁盘缓存的站表，没变就不重复写 */
    @Volatile
    private var lastCachedStatic: LineStatic? = null

    /** 进行中的线路请求数（含手动刷新、选站、轮询） */
    private val activeLoads = AtomicInt(0)

    private val lastMessageId = AtomicLong(0L)

    /** 最近一次发起刷新的时刻（[clockMillis]），null 表示尚未请求过 */
    @Volatile
    private var lastRefreshAtMillis: Long? = null

    private var pushJob: Job? = null
    private var pushKey: String? = null
    private var pushTarget = MutableStateFlow(MeishanPushTarget(null, null))
    private var pushOnline = false
    private var screenResumed = false
    private var pushGeneration = 0L
    private var httpGeneration = 0L
    private var pushRevision = 0L

    private data class DirectionSnapshot(
        val lineId: String,
        val source: DataSourceType,
        val detail: BusLineDetail,
        val state: LineDetailUiState
    )

    /**
     * 每条线路方向的时刻表缓存，避免 10 秒轮询时反复拉取。
     * 必须在 [init] 之前初始化：`viewModelScope.launch` 跑在 `Dispatchers.Main.immediate` 上，
     * 命中缓存时首次请求会在构造期间同步跑完并读到这里，声明在 init 之后会拿到 null 而崩溃。
     */
    private val timetableCache = mutableMapOf<String, LineTimetable>()

    // C1：不再每秒倒计时，固定 10 秒一轮；手动刷新 / 选站后用 restart() 从现在起重新计时（e683286）
    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = FixedDelayPollingStrategy(delayMs = POLL_INTERVAL_SECONDS * 1000L),
        initialEnabled = enableAutoPolling
    ) {
        // 直接在轮询协程内挂起执行：暂停轮询时请求随之取消；上一轮未完成就跳过
        if (activeLoads.load() == 0) {
            fetchLineDetail(isInitial = false)
        }
    }

    /** 当前是否有线路请求在进行（测试用） */
    internal val isLoadInFlight: Boolean
        get() = activeLoads.load() > 0

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    /** 当前线路对应的追踪会话（一起等中也按当前线路返回该线独立状态） */
    private val _trackedSession = MutableStateFlow(TrackingSessionState())
    val activeTrackingSession: StateFlow<TrackingSessionState> = _trackedSession.asStateFlow()

    init {
        viewModelScope.launch {
            favorites.favorites.collectLatest { checkFavoriteState(it) }
        }
        viewModelScope.launch {
            favorites.favoriteItems.collectLatest { refreshFavoriteGroup(it) }
        }
        viewModelScope.launch {
            favorites.favoriteGroups.collectLatest { groups ->
                _uiState.update { it.copy(favoriteGroups = groups) }
            }
        }
        viewModelScope.launch {
            settings.preferredTransitCodeProvider.collectLatest { provider ->
                _uiState.update { it.copy(preferredTransitCodeProvider = provider) }
            }
        }
        viewModelScope.launch {
            settings.railMode.collectLatest { mode ->
                _uiState.update { it.copy(railMode = mode) }
            }
        }
        viewModelScope.launch {
            settings.showTransitCodeButton.collectLatest { visible ->
                _uiState.update { it.copy(showTransitCodeButton = visible) }
            }
        }
        viewModelScope.launch {
            trackingRepository.sessions.collectLatest { publishTrackingState() }
        }

        // 站表先读磁盘缓存：网络还没回来时先画站序（只用来先显示，不挡请求）
        viewModelScope.launch { showCachedStops() }

        loadLineDetail(isInitial = true)
    }

    private suspend fun showCachedStops() {
        val city = settings.selectedCity.value
        val key = LineStopsCache.keyOf(currentSource, city.cityId, city.mygolbsCityCode.orEmpty(), currentLineId)
        val cached = lineStopsCache.read(key) ?: return
        val static = LineStatic(cached.line, cached.reverseDirection, cached.stops)
        lastCachedStatic = static
        _uiState.update { state ->
            if (state.static != null) {
                state
            } else {
                state.copy(
                    static = static,
                    selectedStop = state.selectedStop
                        ?: initialTargetOrder?.takeIf { it > 0 }?.let { order -> cached.stops.find { it.order == order } }
                )
            }
        }
        checkFavoriteState(favorites.favorites.value)
    }

    /** 这份会话里这条线此刻的状态：单线路、路线、一起等里含这条线都算。 */
    private fun TrackingSession.stateForLine(lineId: String): TrackingSessionState? {
        if (interrupted || !state.isActive) return null
        if (state.lineId == lineId) return state
        val watched = watchedLines.firstOrNull { it.line.lineId == lineId } ?: return null
        return state.copy(
            lineId = lineId,
            lineName = watched.line.lineName,
            endStopName = watched.line.endStopName,
            targetOrder = watched.line.stopOrder,
            totalStops = watched.line.stopOrder,
            startTrackingOrder = (watched.line.stopOrder - 1).coerceAtLeast(1),
            boardStopOrder = watched.line.stopOrder,
            currentBusId = watched.busId,
            currentBusOrder = watched.busOrder,
            currentBusProgress = watched.busProgress,
            stopsRemaining = watched.stopsRemaining,
            etaSeconds = watched.etaSeconds,
            isBusArrivedAtStop = watched.isArrived
        )
    }

    /** 按当前线路和候车站发布状态；同线多会话时不能固定取会话列表第一项。 */
    private fun publishTrackingState() {
        val selectedOrder = _uiState.value.selectedStop?.order
        val states = trackingRepository.sessions.value.mapNotNull { it.stateForLine(currentLineId) }
        val state = if (selectedOrder == null) {
            states.firstOrNull()
        } else {
            states.firstOrNull { it.targetOrder == selectedOrder }
        } ?: TrackingSessionState()
        _trackedSession.value = state
        _uiState.update { it.copy(trackingSession = state) }
    }

    /**
     * 开始追踪 [line] 上的 [targetStop]（替换掉正在进行的追踪）。
     * @return 是否启动成功（后台不允许启动前台服务时为 false）
     */
    fun startTracking(
        line: BusLineDirection,
        targetStop: BusStop,
        totalStops: Int,
        alertThresholdStops: Int
    ): Boolean = trackingController.start(
        TrackingTarget.SingleLine(
            cityId = cityId,
            lineId = line.lineId,
            lineName = line.name,
            lineNo = line.lineNo,
            endStopName = line.endStop,
            targetStopId = targetStop.stopId,
            targetStopName = targetStop.name,
            targetOrder = targetStop.order,
            totalStops = totalStops,
            alertThresholdStops = alertThresholdStops,
            source = line.source,
            mygolbsCityCode = line.mygolbsCityCode,
            direction = line.direction
        )
    )

    /**
     * 追踪当前候车站（底栏主按钮）。提前几站提醒先固定 [DEFAULT_ALERT_THRESHOLD_STOPS]，
     * 线路页的预警 chips 删了，全局设置在「我的」（第 25 步）。
     */
    fun startTrackingSelectedStop(): Boolean {
        val state = _uiState.value
        val static = state.static ?: return false
        val stop = state.selectedStop ?: return false
        return startTracking(
            line = static.line,
            targetStop = stop,
            totalStops = static.stops.size,
            alertThresholdStops = DEFAULT_ALERT_THRESHOLD_STOPS
        )
    }

    /** 结束追踪 */
    fun stopTracking() {
        val selectedOrder = _uiState.value.selectedStop?.order
        val candidates = trackingRepository.sessions.value.mapNotNull { session ->
            session.stateForLine(currentLineId)?.let { state -> session to state }
        }
        val session = candidates.firstOrNull { (_, state) ->
            selectedOrder == null || state.targetOrder == selectedOrder
        }?.first ?: return
        if (session.watchTarget != null) {
            trackingController.removeWatchLine(session.sessionId, currentLineId)
        } else {
            trackingController.stopTracking(session.sessionId)
        }
    }

    /** 当前所选城市名（保存定时提醒时写进提醒） */
    fun selectedCityName(): String = settings.selectedCity.value.cityName

    /**
     * 加载当前线路详情与实时车辆（在 viewModelScope 中发起）
     */
    fun loadLineDetail(isInitial: Boolean = false, targetOrderOverride: Int? = null) {
        if (_uiState.value.isSwitchingDirection) return
        viewModelScope.launch {
            fetchLineDetail(isInitial = isInitial, targetOrderOverride = targetOrderOverride)
        }
    }

    /**
     * 挂起执行一次线路详情请求并更新 UI 状态
     */
    private suspend fun fetchLineDetail(
        isInitial: Boolean = false,
        targetOrderOverride: Int? = null,
        allowDuringDirectionSwitch: Boolean = false
    ): Boolean {
        if (_uiState.value.isSwitchingDirection && !allowDuringDirectionSwitch) return false
        activeLoads.incrementAndFetch()
        var applied = false
        val generation = ++httpGeneration
        val requestedLineId = currentLineId
        val requestedSource = currentSource
        val revision = pushRevision
        lastRefreshAtMillis = clockMillis()
        try {
            if (isInitial) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            }

            val currentCity = settings.selectedCity.value
            val targetOrder = targetOrderOverride
                ?: _uiState.value.selectedStop?.order
                ?: initialTargetOrder

            val result = realtime.getLineDetail(
                city = currentCity,
                lineId = requestedLineId,
                targetOrder = targetOrder,
                stationId = _uiState.value.selectedStop?.stopId ?: initialStationId,
                source = requestedSource,
                lineNameHint = lineNameHint
            )

            if (generation != httpGeneration || requestedLineId != currentLineId) return false
            if (revision != pushRevision) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                return false
            }
            result.onSuccess { detail ->
                val selected = _uiState.value.selectedStop
                // 初次加载或无选中站时，计算默认选中的候车站（首车前面一站）；用户已经在缓存的站表上点过站就保留。
                // 找回选中站时站序和站 ID 一起核对：服务端重排站序后按站 ID 找，不落到另一站上
                val targetStop = if (selected == null || (isInitial && !userPickedStop)) {
                    if (initialTargetOrder != null && initialTargetOrder > 0) {
                        detail.stops.findStop(initialTargetOrder, initialStationId) ?: calculateDefaultStop(detail)
                    } else {
                        calculateDefaultStop(detail)
                    }
                } else {
                    detail.stops.findStop(selected.order, selected.stopId) ?: calculateDefaultStop(detail)
                }

                applyDetail(detail, targetStop)
                applied = true
                startPush(detail, targetStop)

                refreshFallbackNextDeparture(detail, targetStop)

                // 初次加载未指定候车站时，需要再按默认候车站请求一次才能拿到到该站的精确 ETA；
                // 在同一协程内顺序执行并重新计时，避免轮询紧接着再发一次
                if (isInitial && targetStop != null && targetOrder == null) {
                    pollingCoordinator.restart()
                    fetchLineDetail(isInitial = false, targetOrderOverride = targetStop.order)
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = lineDetailErrorMessage(ex)
                    )
                }
            }
        } catch (ce: CancellationException) {
            if (!_uiState.value.isSwitchingDirection) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
            throw ce
        } finally {
            activeLoads.decrementAndFetch()
        }
        return applied
    }

    /** 线路详情只展示稳定产品文案，不把上游异常消息（可能包含 URL）透传到 UI。 */
    private fun lineDetailErrorMessage(error: Throwable): String = when {
        error is TransitProtocolFailure -> "数据异常，请稍后重试"
        error is TransitServiceFailure -> "服务请求失败，请稍后重试"
        error is IOException -> "网络请求失败，请检查网络后重试"
        else -> "获取实时公交数据失败，请稍后重试"
    }

    /** 把一份新数据拆成静态 / 实时两半写进状态；静态没变就沿用旧实例 */
    private fun applyDetail(detail: BusLineDetail, targetStop: BusStop?) {
        currentDetail = detail
        val newStatic = LineStatic(detail.line, detail.reverseDirection, detail.stops)
        _uiState.update {
            val static = if (it.static == newStatic) it.static else newStatic
            val live = LineLive(
                buses = detail.buses,
                idleBuses = detail.idleBuses,
                operatingState = detail.operatingState,
                statusDescription = detail.statusDescription,
                nextDepartureFromStart = detail.nextDepartureFromStart,
                updatedAtMillis = detail.updatedAtMillis,
                fallbackNextDeparture = it.live?.fallbackNextDeparture?.takeIf { _ -> it.static?.line?.lineId == detail.line.lineId }
            )
            it.copy(
                isLoading = false,
                isRefreshing = false,
                static = static,
                live = if (it.live == live) it.live else live,
                selectedStop = targetStop,
                errorMessage = null
            )
        }
        checkFavoriteState(favorites.favorites.value)
        refreshFavoriteGroup(favorites.favoriteItems.value)
        publishTrackingState()
        writeStopsCache(newStatic)
    }

    private fun startPush(detail: BusLineDetail, stop: BusStop?) {
        val repository = pushRepository ?: return
        if (!screenResumed || detail.line.source != DataSourceType.MEISHAN || detail.stops.isEmpty()) return
        val key = detail.line.lineId
        if (pushKey == key && pushJob?.isActive == true) {
            pushTarget.value = MeishanPushTarget(stop?.order, stop?.stopId)
            return
        }
        stopPush()
        pushKey = key
        pushTarget = MutableStateFlow(MeishanPushTarget(stop?.order, stop?.stopId))
        val generation = pushGeneration
        pushJob = viewModelScope.launch {
            var backoffMillis = PUSH_RETRY_MIN_MILLIS
            while (generation == pushGeneration && screenResumed && currentLineId == key) {
                try {
                    repository.stream(detail, pushTarget)
                        .catch {
                            if (it is CancellationException) throw it
                            if (generation == pushGeneration) setPushOnline(false)
                        }
                        .collect { update ->
                            if (generation != pushGeneration || currentLineId != key || !screenResumed) return@collect
                            val fresh = update.detail
                            if (fresh != null && fresh.line.lineId == currentLineId) {
                                pushRevision++
                                lastRefreshAtMillis = clockMillis()
                                applyDetail(fresh, _uiState.value.selectedStop)
                            }
                            if (update.availability == MeishanPushAvailability.ONLINE) {
                                backoffMillis = PUSH_RETRY_MIN_MILLIS
                            }
                            setPushOnline(update.availability == MeishanPushAvailability.ONLINE && (fresh != null || pushOnline))
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (generation == pushGeneration) setPushOnline(false)
                } finally {
                    if (generation == pushGeneration) setPushOnline(false)
                }
                if (generation != pushGeneration || !screenResumed || currentLineId != key) break
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(PUSH_RETRY_MAX_MILLIS)
            }
        }
    }

    private fun setPushOnline(online: Boolean) {
        val wasOnline = pushOnline
        pushOnline = online
        _uiState.update { it.copy(isPushOnline = online) }
        if (!screenResumed || !enableAutoPolling) return
        pollingCoordinator.setActive(!online)
        if (wasOnline && !online && activeLoads.load() == 0) {
            loadLineDetail(isInitial = false)
        }
    }

    private fun stopPush() {
        pushGeneration++
        pushJob?.cancel()
        pushJob = null
        pushKey = null
        pushOnline = false
        _uiState.update { it.copy(isPushOnline = false) }
    }

    private fun writeStopsCache(static: LineStatic) {
        if (static.stops.isEmpty() || static == lastCachedStatic) return
        lastCachedStatic = static
        val city = settings.selectedCity.value
        val key = LineStopsCache.keyOf(static.line.source, city.cityId, city.mygolbsCityCode.orEmpty(), static.line.lineId)
        viewModelScope.launch {
            lineStopsCache.write(key, static.line, static.reverseDirection, static.stops)
        }
    }

    /**
     * 候车站前方无车且实时接口未给下一班时间时，用时刻表补出下一班始发时间。
     */
    private suspend fun refreshFallbackNextDeparture(detail: BusLineDetail, stop: BusStop?) {
        val order = stop?.order ?: run {
            setFallbackNextDeparture(detail.line.lineId, null)
            return
        }
        val hasUpcomingBus = detail.buses.any { it.order <= order }
        if (hasUpcomingBus || !detail.nextDepartureFromStart.isNullOrBlank()) {
            setFallbackNextDeparture(detail.line.lineId, null)
            return
        }

        val lineId = detail.line.lineId
        val timetable = timetableCache[lineId]
            ?: realtime.getTimetable(settings.selectedCity.value, detail.line)
                .getOrNull()
                ?.also { timetableCache[lineId] = it }

        val now = nowHHmm()
        val next = timetable?.let { tb ->
            tb.todayDepartures.firstOrNull { it >= now }
                ?: tb.yesterdayDepartures.firstOrNull { it >= now }
                ?: tb.nextDeparture?.takeIf { it >= now }
        }
        setFallbackNextDeparture(lineId, next)
    }

    /** 防止切线后旧请求覆盖新线路 */
    private fun setFallbackNextDeparture(lineId: String, value: String?) {
        _uiState.update {
            val live = it.live
            if (live == null || it.static?.line?.lineId != lineId || live.fallbackNextDeparture == value) {
                it
            } else {
                it.copy(live = live.copy(fallbackNextDeparture = value))
            }
        }
    }

    /**
     * 计算默认选中的候车站：
     * 取「首辆车前面一站」（若车辆已在第 N 站，则看第 N+1 站）；无在途车退回首站。
     * 首车已到终点站时后面没有站，退回起点站走「等下一班」，不能退回首车所在站，
     * 否则每次打开线路页都会选中终点站、走向条滚到最右（issue #9）。
     */
    private fun calculateDefaultStop(detail: BusLineDetail): BusStop? {
        val stops = detail.stops
        if (stops.isEmpty()) return null
        val buses = detail.buses
        if (buses.isEmpty()) return stops.first()

        val leadOrder = buses.maxOfOrNull { it.order } ?: return stops.first()
        if (leadOrder >= stops.maxOf { it.order }) return stops.first()
        return stops.find { it.order == leadOrder + 1 }
            ?: stops.find { it.order == leadOrder }
            ?: stops.first()
    }

    /**
     * 设置轮询是否激活（生命周期感知或显式开关）
     */
    fun setPollingActive(active: Boolean) {
        if (!active) {
            onScreenPaused()
            return
        }
        screenResumed = true
        if (_uiState.value.isSwitchingDirection) return
        currentDetail?.let { startPush(it, _uiState.value.selectedStop) }
        pollingCoordinator.setActive(!pushOnline)
    }

    /**
     * 页面回到前台（Lifecycle RESUMED）：恢复轮询；若距上次刷新已超过一个周期，立即刷新一次。
     * 测试或预览中 enableAutoPolling = false 时不做任何事。
     */
    fun onScreenResumed() {
        screenResumed = true
        if (_uiState.value.isSwitchingDirection) return
        currentDetail?.let { startPush(it, _uiState.value.selectedStop) }
        if (!enableAutoPolling) return
        pollingCoordinator.setActive(!pushOnline)
        val last = lastRefreshAtMillis ?: return
        if (activeLoads.load() == 0 && clockMillis() - last >= POLL_INTERVAL_SECONDS * 1000L) {
            pollingCoordinator.restart()
            loadLineDetail(isInitial = false)
        }
    }

    /**
     * 页面离开前台（Lifecycle PAUSED / 移出组合）：停止轮询，进行中的轮询请求随之取消。
     */
    fun onScreenPaused() {
        screenResumed = false
        stopPush()
        pollingCoordinator.setActive(false)
    }

    /** 暂停后台轮询，安全取消当前轮询 Job */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /** 恢复 / 启动 10 秒自动轮询（幂等） */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    /**
     * 立即刷新（错误条「重试」、下拉刷新），并从现在起重新计时。
     * @param fromPull 是下拉触发的：完成前画下拉圈
     */
    fun refreshNow(fromPull: Boolean = false) {
        if (_uiState.value.isSwitchingDirection) return
        pollingCoordinator.restart()
        if (fromPull) _uiState.update { it.copy(isPullRefreshing = true) }
        viewModelScope.launch {
            try {
                fetchLineDetail(isInitial = false)
            } finally {
                if (fromPull) _uiState.update { it.copy(isPullRefreshing = false) }
            }
        }
    }

    /**
     * 选中站点：以该站为 targetOrder 计算精确到站 ETA
     */
    fun selectStop(stop: BusStop) {
        if (_uiState.value.isSwitchingDirection) return
        httpGeneration++
        userPickedStop = true
        _uiState.update {
            it.copy(
                selectedStop = stop,
                panelMode = PanelMode.STATION_CARD,
                followedBusId = null
            )
        }
        checkFavoriteState(favorites.favorites.value)
        refreshFavoriteGroup(favorites.favoriteItems.value)
        publishTrackingState()
        currentDetail?.let { startPush(it, stop) }
        if (!pushOnline && screenResumed && enableAutoPolling) pollingCoordinator.setActive(true)
        // 选站即刷新，同时重新计时，避免紧接着轮询又发一次
        pollingCoordinator.restart()
        loadLineDetail(isInitial = false, targetOrderOverride = stop.order)
    }

    /** 选中并跟随某辆车 */
    fun selectBusToFollow(bus: LiveBus) {
        _uiState.update {
            it.copy(
                followedBusId = bus.busId,
                panelMode = PanelMode.BUS_FOLLOW,
                followBackTo = if (it.panelMode == PanelMode.BUS_LIST) PanelMode.BUS_LIST else PanelMode.STATION_CARD
            )
        }
    }

    /** 展开全线车辆列表 */
    fun openBusList() {
        _uiState.update { it.copy(panelMode = PanelMode.BUS_LIST, followedBusId = null) }
    }

    /**
     * 返回上一级：单车跟随 → 车辆列表（从走向条点车进来的回站点卡），车辆列表 → 站点卡。
     * @return false 表示已经在站点卡，调用方应该退出页面
     */
    fun backFromPanel(): Boolean {
        val state = _uiState.value
        val target = when (state.panelMode) {
            PanelMode.STATION_CARD -> return false
            PanelMode.BUS_LIST -> PanelMode.STATION_CARD
            PanelMode.BUS_FOLLOW -> state.followBackTo
        }
        _uiState.update { it.copy(panelMode = target, followedBusId = null) }
        return true
    }

    /**
     * 换向：用 RouteProjectionMath.findOppositeStop 找反向线路上的同名候车站。
     */
    fun switchOppositeDirection() {
        val state = _uiState.value
        if (state.isSwitchingDirection) return
        val detail = currentDetail ?: return
        val reverse = detail.reverseDirection?.takeIf { it.lineId.isNotBlank() } ?: return
        val currentStop = state.selectedStop ?: return
        val snapshot = DirectionSnapshot(currentLineId, currentSource, detail, state)
        val wasTracking = state.isTrackingThisStop

        if (wasTracking) {
            stopTracking()
            postMessage("已结束追踪")
        }
        stopPush()
        pollingCoordinator.pause()
        val switchGeneration = ++httpGeneration
        currentLineId = reverse.lineId
        currentSource = reverse.source
        _uiState.update {
            it.copy(
                isSwitchingDirection = true,
                isLoading = true,
                isRefreshing = false,
                static = null,
                live = null,
                selectedStop = null,
                followedBusId = null,
                panelMode = PanelMode.STATION_CARD,
                errorMessage = null
            )
        }
        publishTrackingState()

        viewModelScope.launch {
            var directionCommitted = false
            try {
                val currentCity = settings.selectedCity.value
                val reverseResult = realtime.getLineDetail(
                    city = currentCity,
                    lineId = reverse.lineId,
                    source = reverse.source,
                    lineNameHint = reverse.name
                )
                if (switchGeneration != httpGeneration || currentLineId != reverse.lineId) return@launch
                val revDetail = reverseResult.getOrElse {
                    restoreDirectionSnapshot(snapshot)
                    return@launch
                }
                val oppositeStop = RouteProjectionMath.findOppositeStop(
                    currentStop = currentStop,
                    reverseStops = revDetail.stops
                )
                val targetStop = oppositeStop ?: calculateDefaultStop(revDetail)
                applyDetail(revDetail, targetStop)
                directionCommitted = true
                startPush(revDetail, targetStop)
                _uiState.update { it.copy(panelMode = PanelMode.STATION_CARD, followedBusId = null) }
                if (oppositeStop == null && targetStop != null) {
                    postMessage("反向线路没有「${currentStop.name}」，已选 ${targetStop.name}")
                }
                if (targetStop != null) {
                    fetchLineDetail(
                        isInitial = false,
                        targetOrderOverride = targetStop.order,
                        allowDuringDirectionSwitch = true
                    )
                }
            } catch (ce: CancellationException) {
                if (!directionCommitted) restoreDirectionSnapshot(snapshot)
                throw ce
            } finally {
                _uiState.update { it.copy(isSwitchingDirection = false) }
                if (screenResumed && enableAutoPolling) {
                    pollingCoordinator.setActive(!pushOnline)
                }
            }
        }
    }

    private fun restoreDirectionSnapshot(snapshot: DirectionSnapshot) {
        stopPush()
        currentLineId = snapshot.lineId
        currentSource = snapshot.source
        currentDetail = snapshot.detail
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isSwitchingDirection = true,
                static = snapshot.state.static,
                live = snapshot.state.live,
                selectedStop = snapshot.state.selectedStop,
                followedBusId = snapshot.state.followedBusId,
                panelMode = snapshot.state.panelMode,
                followBackTo = snapshot.state.followBackTo,
                errorMessage = "换向失败，请稍后重试"
            )
        }
        publishTrackingState()
        startPush(snapshot.detail, snapshot.state.selectedStop)
    }

    private fun favoriteIdOf(state: LineDetailUiState): String? {
        val static = state.static ?: return null
        val stop = state.selectedStop ?: return null
        return "${settings.selectedCity.value.cityId}_${static.line.lineId}_${stop.order}"
    }

    /**
     * ☆ 一点即收藏 / 取消收藏当前「线路 + 候车站」。收藏后提示条带「换分组」（有分组时）。
     */
    fun toggleFavoriteStop() {
        val state = _uiState.value
        val static = state.static ?: return
        val stop = state.selectedStop ?: return
        val line = static.line
        val city = settings.selectedCity.value

        val favItem = FavoriteStationLine(
            id = "${city.cityId}_${line.lineId}_${stop.order}",
            cityId = city.cityId,
            cityName = city.cityName,
            lineId = line.lineId,
            lineName = line.name,
            lineNo = line.lineNo,
            direction = line.direction,
            startStop = line.startStop,
            endStop = line.endStop,
            targetStopId = stop.stopId,
            targetStopName = stop.name,
            targetOrder = stop.order,
            totalStops = static.stops.size,
            wgsLat = stop.wgsLat,
            wgsLng = stop.wgsLng,
            source = line.source,
            mygolbsCityCode = line.mygolbsCityCode
        )

        viewModelScope.launch {
            val isNowFav = favorites.toggleFavorite(favItem)
            _uiState.update { it.copy(isFavorite = isNowFav) }
            if (isNowFav) {
                val canChangeGroup = favorites.favoriteGroups.value.isNotEmpty()
                postMessage("已收藏", if (canChangeGroup) LineUserMessage.Action.CHANGE_GROUP else null)
            } else {
                postMessage("已取消收藏")
            }
        }
    }

    /** 打开「换分组」弹层（提示条按钮） */
    fun openGroupPicker() {
        if (!_uiState.value.isFavorite) return
        _uiState.update { it.copy(isGroupPickerOpen = true) }
    }

    fun dismissGroupPicker() {
        _uiState.update { it.copy(isGroupPickerOpen = false) }
    }

    /** 把当前收藏移到 [groupId]（null = 不分组） */
    fun moveFavoriteToGroup(groupId: String?) {
        val favId = favoriteIdOf(_uiState.value) ?: return
        _uiState.update { it.copy(isGroupPickerOpen = false) }
        viewModelScope.launch {
            val moved = favorites.setGroup(FavoriteItem.stopKey(favId), groupId)
            if (moved) {
                val name = groupId?.let { id -> favorites.favoriteGroups.value.find { it.id == id }?.name } ?: "不分组"
                postMessage("已移到「$name」")
            }
        }
    }

    private fun checkFavoriteState(favList: List<FavoriteStationLine>) {
        val favId = favoriteIdOf(_uiState.value) ?: return
        val isFav = favList.any { it.id == favId }
        _uiState.update { if (it.isFavorite == isFav) it else it.copy(isFavorite = isFav) }
    }

    private fun refreshFavoriteGroup(items: List<FavoriteItem>) {
        val favId = favoriteIdOf(_uiState.value)
        val groupId = favId?.let { id -> items.find { it.key == FavoriteItem.stopKey(id) }?.groupId }
        _uiState.update { if (it.favoriteGroupId == groupId) it else it.copy(favoriteGroupId = groupId) }
    }

    /** 加载全天发车时刻表（信息行「时刻表 ›」） */
    fun loadTimetable() {
        val detail = currentDetail ?: return
        viewModelScope.launch {
            val city = settings.selectedCity.value
            realtime.getTimetable(city, detail.line)
                .onSuccess { tb ->
                    _uiState.update { it.copy(timetable = tb, isTimetableSheetOpen = true) }
                }.onFailure { ex ->
                    _uiState.update { it.copy(errorMessage = "获取发车时刻表失败：${ex.message ?: "无班表"}") }
                }
        }
    }

    fun dismissTimetable() {
        _uiState.update { it.copy(isTimetableSheetOpen = false) }
    }

    /** 顶栏时钟：定时提醒 */
    fun openReminderDialog() {
        if (_uiState.value.static == null || _uiState.value.selectedStop == null) return
        _uiState.update { it.copy(isReminderDialogOpen = true) }
    }

    fun dismissReminderDialog() {
        _uiState.update { it.copy(isReminderDialogOpen = false) }
    }

    /** 保存定时提醒（当前线路 + 候车站），保存后提示条报时间 */
    @OptIn(ExperimentalUuidApi::class)
    fun saveReminder(hour: Int, minute: Int, daysOfWeek: Set<Int>, autoStartTracking: Boolean) {
        val state = _uiState.value
        val static = state.static ?: return
        val stop = state.selectedStop ?: return
        _uiState.update { it.copy(isReminderDialogOpen = false) }
        val line = static.line
        val reminder = ScheduledReminder(
            id = Uuid.random().toString(),
            hour = hour,
            minute = minute,
            daysOfWeek = daysOfWeek,
            cityId = cityId,
            cityName = selectedCityName(),
            lineId = line.lineId,
            lineName = line.name,
            direction = line.direction,
            endStopName = line.endStop,
            targetStopId = stop.stopId,
            targetStopName = stop.name,
            targetOrder = stop.order,
            totalStops = static.stops.size,
            dataSourceType = line.source,
            mygolbsCityCode = line.mygolbsCityCode,
            autoStartLiveTracking = autoStartTracking
        )
        viewModelScope.launch {
            reminderRepository.saveReminder(reminder)
            postMessage("已设好定时提醒：${reminder.formatTime()}（${reminder.formatRepeatSummary()}）")
        }
    }

    fun openTransitCodeSheet() {
        _uiState.update { it.copy(isTransitCodeSheetOpen = true) }
    }

    fun dismissTransitCodeSheet() {
        _uiState.update { it.copy(isTransitCodeSheetOpen = false) }
    }

    /** 设置乘车码默认渠道 */
    fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        viewModelScope.launch {
            settings.setPreferredTransitCodeProvider(provider)
        }
    }

    /**
     * 「读给我听」：把主卡上的到站读出来，和屏幕大字同一套状态（[stationArrivalState]），
     * 如「325路，开往科技园，还有 2 站，大约 4 分钟到科技园站」。手机读不了时给一条提示。
     */
    fun onReadAloud() {
        val state = _uiState.value
        val static = state.static ?: return
        val stop = state.selectedStop ?: return
        val live = state.live
        val now = live?.let { ArrivalText.localTimeOf(it.updatedAtMillis) }
        val sentence = ArrivalText.spoken(
            stationArrivalState(static, live, stop.order, now),
            lineName = static.line.name,
            stopName = stop.name,
            towards = static.line.endStop
        )
        viewModelScope.launch {
            if (!announcer.speak(sentence)) postMessage(TTS_UNAVAILABLE_MESSAGE)
        }
    }

    /** 提示条显示过了 */
    fun onMessageShown(id: Long) {
        _uiState.update { state -> state.copy(userMessages = state.userMessages.filterNot { it.id == id }) }
    }

    private fun postMessage(text: String, action: LineUserMessage.Action? = null) {
        val message = LineUserMessage(id = lastMessageId.incrementAndFetch(), text = text, action = action)
        _uiState.update { it.copy(userMessages = it.userMessages + message) }
    }

    override fun onCleared() {
        super.onCleared()
        screenResumed = false
        stopPush()
        pausePolling()
        // 离开线路页就别再读了
        announcer.stop()
    }

    companion object {
        /** 自动轮询周期（秒） */
        const val POLL_INTERVAL_SECONDS = 10

        /** 追踪时提前几站提醒（原预警 chips 的默认值；全局设置见第 25 步） */
        const val DEFAULT_ALERT_THRESHOLD_STOPS = 2

        const val PUSH_RETRY_MIN_MILLIS = 2_000L
        const val PUSH_RETRY_MAX_MILLIS = 30_000L
    }
}

/** 上海时间的「HH:mm」 */
private fun shanghaiNowHHmm(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).time
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
