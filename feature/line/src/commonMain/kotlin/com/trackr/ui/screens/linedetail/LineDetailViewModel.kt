package com.trackr.ui.screens.linedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.local.NoLineStopsCache
import com.trackr.core.data.repository.FavoritesRepository
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
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.displayLineName
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import com.trackr.ui.screens.linedetail.components.stationArrivalState
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
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
    val supportsAutoStartReminders: Boolean = true,
    /** 进页面时带来的线路名（数据回来前顶栏先显示它） */
    val lineNameHint: String = "",
    /** 进页面时带来的站序（从某个站点点进来才有）；页面据此把走向条直接滚到那一站 */
    val entryStopOrder: Int? = null,
    /** 第一次网络请求还没回来 */
    val isLoading: Boolean = true,
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
    private val initialSource: DataSourceType = if (initialLineId.startsWith("mg:")) DataSourceType.MYGOLBS else DataSourceType.CHELAILE,
    private val lineNameHint: String = "",
    /**
     * 进页面时带来的上车站 / 下车站名（路线规划、路线详情点线路进来）。有它时按站名定候车站、判方向，
     * 不信 [initialTargetOrder]：高德方案没对齐时站序是占位的 1。
     */
    private val entryBoardStopName: String = "",
    private val entryAlightStopName: String = "",
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val realtime: RealtimeRepository,
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
            supportsAutoStartReminders = reminderRepository.supportsAutoStart,
            lineNameHint = lineNameHint,
            // 按站名定站时站序要等站表回来才知道，先不滚
            entryStopOrder = initialTargetOrder?.takeIf { it > 0 && entryBoardStopName.isBlank() }
        )
    )

    /** 入口按站名定候车站（见 [entryBoardStopName]） */
    private val hasEntryStopName: Boolean = entryBoardStopName.isNotBlank()
    val uiState: StateFlow<LineDetailUiState> = _uiState.asStateFlow()

    private val entryLineId: String = initialLineId
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

    /** 这条线（当前方向）正在追的那一份会话的状态；没在追时 `isActive = false` */
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
        // 底栏的「乘车码」圆钮和首页悬浮按钮共用一个开关（用 NFC 的人两边都不用看它）
        viewModelScope.launch {
            settings.showTransitCodeButton.collectLatest { visible ->
                _uiState.update { it.copy(showTransitCodeButton = visible) }
            }
        }
        viewModelScope.launch {
            settings.railMode.collectLatest { mode ->
                _uiState.update { it.copy(railMode = mode) }
            }
        }
        // 同时可能追着好几项：只认这条线自己的那一份（一起等里含这条线也算）
        viewModelScope.launch {
            trackingRepository.sessions.collectLatest { publishTrackingState() }
        }

        // 站表先读磁盘缓存：网络还没回来时先画站序（只用来先显示，不挡请求）
        viewModelScope.launch { showCachedStops() }

        loadLineDetail(isInitial = true)
    }

    /**
     * 这份会话里这条线此刻的状态：单线路、路线、一起等里含这条线都算。
     * 一起等排第一的不是这条线时，用 [TrackingSession.watchedLines] 里这条线自己的数据拼一份。
     */
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

    /** 重新算「这条线是否在追」，写进页面状态 */
    private fun publishTrackingState() {
        val state = trackingRepository.sessions.value
            .firstNotNullOfOrNull { it.stateForLine(currentLineId) }
            ?: TrackingSessionState()
        _trackedSession.value = state
        _uiState.update { it.copy(trackingSession = state) }
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
                        ?: if (hasEntryStopName) {
                            resolveEntryStop(cached.stops, entryBoardStopName, entryAlightStopName, initialTargetOrder)
                        } else {
                            initialTargetOrder?.takeIf { it > 0 }?.let { order -> entryStopIn(cached.stops, order) }
                        }
                )
            }
        }
        checkFavoriteState(favorites.favorites.value)
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
            lineName = line.displayLineName,
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

    /**
     * 这条线正在追的那一份会话（结束追踪只停它，别的线路继续追）。
     * 同一条线在两个站各追一份时，优先取当前候车站那一份。
     */
    private val trackedSession: TrackingSession?
        get() {
            val order = _uiState.value.selectedStop?.order
            val candidates = trackingRepository.sessions.value.mapNotNull { session ->
                session.stateForLine(currentLineId)?.let { session to it }
            }
            val sameStop = candidates.firstOrNull { it.second.targetOrder == order }
            return (sameStop ?: candidates.firstOrNull())?.first
        }

    /** 结束这条线的追踪（同时追着别的线路时不影响它们；一起等里只去掉这一条） */
    fun stopTracking() {
        // 页面上这条线已经不在追了（比如状态还没刷过来）：什么都不做，别把别的追踪一起停掉
        val session = trackedSession ?: return
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
        viewModelScope.launch {
            fetchLineDetail(isInitial = isInitial, targetOrderOverride = targetOrderOverride)
        }
    }

    /**
     * 挂起执行一次线路详情请求并更新 UI 状态
     */
    private suspend fun fetchLineDetail(isInitial: Boolean = false, targetOrderOverride: Int? = null) {
        activeLoads.incrementAndFetch()
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

            val requestLineId = currentLineId
            // 入口带的站 id 只对应入口那一站：换过站或换过向后再传，上游会按旧站算到站时间
            val stationId = initialStationId.takeIf { !userPickedStop && requestLineId == entryLineId }
            val result = realtime.getLineDetail(
                city = currentCity,
                lineId = requestLineId,
                targetOrder = targetOrder,
                stationId = stationId,
                source = currentSource,
                lineNameHint = lineNameHint
            )

            result.onSuccess { fetched ->
                // 请求期间换了向：这份是旧方向的数据，按站序套到新方向会选错站
                if (requestLineId != currentLineId) {
                    _uiState.update { it.copy(isRefreshing = false) }
                    return@onSuccess
                }
                // 入口带了站名：按站名找候车站，这个方向没有（或上车站在下车站后面）就换反向找
                val byName = if (isInitial && !userPickedStop && hasEntryStopName) {
                    resolveEntryDirection(fetched, currentCity)
                } else {
                    null
                }
                if (byName != null && byName.first.line.lineId != requestLineId) {
                    currentLineId = byName.first.line.lineId
                    currentSource = byName.first.line.source
                    publishTrackingState()
                }
                val detail = byName?.first ?: fetched
                val selected = _uiState.value.selectedStop
                // 初次加载或无选中站时，计算默认选中的候车站（首车前面一站）；用户已经在缓存的站表上点过站就保留
                val targetStop = if (byName != null) {
                    byName.second
                } else if (selected == null || (isInitial && !userPickedStop)) {
                    if (initialTargetOrder != null && initialTargetOrder > 0) {
                        entryStopIn(detail.stops, initialTargetOrder) ?: calculateDefaultStop(detail)
                    } else {
                        calculateDefaultStop(detail)
                    }
                } else {
                    sameStopIn(detail.stops, selected) ?: calculateDefaultStop(detail)
                }

                applyDetail(detail, targetStop)
                if (byName != null) {
                    _uiState.update { it.copy(entryStopOrder = byName.second.order) }
                }

                refreshFallbackNextDeparture(detail, targetStop)

                // 初次加载未指定候车站（或按站名定的站和请求时带的站序 / 方向不一样）时，需要再按候车站请求一次
                // 才能拿到到该站的精确 ETA；在同一协程内顺序执行并重新计时，避免轮询紧接着再发一次
                val entryStopMoved = byName != null &&
                    (byName.second.order != targetOrder || detail.line.lineId != requestLineId)
                if (isInitial && targetStop != null && (targetOrder == null || entryStopMoved)) {
                    pollingCoordinator.restart()
                    fetchLineDetail(isInitial = false, targetOrderOverride = targetStop.order)
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = "获取实时公交数据失败：${ex.message ?: "网络异常"}"
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

    /**
     * 按入口站名定方向和候车站：当前方向找得到就用它；找不到再请求一次反向线路，在反向里找。
     * @return 用哪份数据 + 哪一站；两个方向都找不到返回 null（退回原来的站序 / 默认候车站逻辑）
     */
    private suspend fun resolveEntryDirection(
        detail: BusLineDetail,
        city: TransitCity
    ): Pair<BusLineDetail, BusStop>? {
        resolveEntryStop(detail.stops, entryBoardStopName, entryAlightStopName, initialTargetOrder)
            ?.let { return detail to it }
        val reverse = detail.reverseDirection?.takeIf { it.lineId.isNotBlank() } ?: return null
        val revDetail = realtime.getLineDetail(
            city = city,
            lineId = reverse.lineId,
            source = reverse.source,
            lineNameHint = reverse.name
        ).getOrNull() ?: return null
        val stop = resolveEntryStop(revDetail.stops, entryBoardStopName, entryAlightStopName) ?: return null
        return revDetail to stop
    }

    /** 入口指定的那一站：个别数据源站序会重复，能用站 id 区分时优先用站 id */
    private fun entryStopIn(stops: List<BusStop>, order: Int): BusStop? {
        val sameOrder = stops.filter { it.order == order }
        return sameOrder.firstOrNull { it.stopId == initialStationId } ?: sameOrder.firstOrNull()
    }

    /**
     * 在新站表里找回 [picked] 这一站。站序可能重复（掌上公交的站 id 又就是站序），也可能因站表调整整体移动，
     * 所以先比站序 + 站名，再按 id / 唯一的站名找；都找不到才认站序（比如站改了名）。
     */
    private fun sameStopIn(stops: List<BusStop>, picked: BusStop): BusStop? {
        val sameOrder = stops.filter { it.order == picked.order }
        return sameOrder.firstOrNull { it.stopId == picked.stopId && it.name == picked.name }
            ?: sameOrder.firstOrNull { it.name == picked.name }
            ?: stops.firstOrNull { it.stopId == picked.stopId && it.name == picked.name }
            ?: stops.singleOrNull { it.name == picked.name }
            ?: sameOrder.firstOrNull()
    }

    /** 把一份新数据拆成静态 / 实时两半写进状态；静态没变就沿用旧实例 */
    private fun applyDetail(detail: BusLineDetail, targetStop: BusStop?) {
        currentDetail = detail
        val newStatic = LineStatic(detail.line, detail.reverseDirection, detail.stops)
        _uiState.update {
            val static = if (it.static == newStatic) it.static else newStatic
            val live = LineLive(
                buses = detail.buses,
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
        writeStopsCache(newStatic)
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
        if (activeLoads.load() == 0 && clockMillis() - last >= POLL_INTERVAL_SECONDS * 1000L) {
            pollingCoordinator.restart()
            loadLineDetail(isInitial = false)
        }
    }

    /**
     * 页面离开前台（Lifecycle PAUSED / 移出组合）：停止轮询，进行中的轮询请求随之取消。
     */
    fun onScreenPaused() {
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
     * 换向（底栏圆钮）：用 RouteProjectionMath.findOppositeStop 找反向线路上的同名候车站。
     * 反向线没有同名站时退回默认候车站并提示，免得用户以为点错了。
     */
    fun switchOppositeDirection() {
        val state = _uiState.value
        if (state.isSwitchingDirection) return
        val detail = currentDetail ?: return
        // lineId 为空的反向（旧缓存里可能有）请求不出去，直接当没有
        val reverse = detail.reverseDirection?.takeIf { it.lineId.isNotBlank() } ?: return
        val currentStop = state.selectedStop ?: return

        // 换向换掉了线路方向，原来追的「本站」就不成立了：先结束追踪，别让后台继续追旧方向
        val wasTracking = state.isTrackingThisStop
        if (wasTracking) stopTracking()

        viewModelScope.launch {
            _uiState.update { it.copy(isSwitchingDirection = true, isLoading = true) }
            try {
                currentLineId = reverse.lineId
                publishTrackingState()
                currentSource = reverse.source

                val currentCity = settings.selectedCity.value
                val reverseResult = realtime.getLineDetail(
                    city = currentCity,
                    lineId = reverse.lineId,
                    source = reverse.source,
                    lineNameHint = reverse.name
                )

                reverseResult.onSuccess { revDetail ->
                    val oppositeStop = RouteProjectionMath.findOppositeStop(
                        currentStop = currentStop,
                        reverseStops = revDetail.stops
                    )
                    val targetStop = oppositeStop ?: calculateDefaultStop(revDetail)

                    applyDetail(revDetail, targetStop)
                    _uiState.update { it.copy(panelMode = PanelMode.STATION_CARD, followedBusId = null) }

                    if (oppositeStop == null && targetStop != null) {
                        postMessage("反向线路没有「${currentStop.name}」，已选 ${targetStop.name}")
                    }
                    if (wasTracking) postMessage("已结束追踪")

                    // 若找到了对应站，立即拉取到该站的精确 ETA
                    if (targetStop != null) {
                        loadLineDetail(isInitial = false, targetOrderOverride = targetStop.order)
                    }
                }.onFailure {
                    // 线路 id 已经换成反向，回滚掉，否则接下来的轮询会一直请求错的方向
                    currentLineId = detail.line.lineId
                    publishTrackingState()
                    currentSource = detail.line.source
                    _uiState.update { it.copy(isLoading = false, errorMessage = "换向失败，请稍后重试") }
                }
            } finally {
                _uiState.update { it.copy(isSwitchingDirection = false) }
            }
        }
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
            // 数据源偶尔只给线路号不给名字：收藏里存兜底后的名字，首页徽标才不会空白（issue #20）
            lineName = line.displayLineName,
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
        reminderRepository.unavailableReason?.let { postMessage(it); return }
        if (_uiState.value.static == null || _uiState.value.selectedStop == null) return
        _uiState.update { it.copy(isReminderDialogOpen = true) }
    }

    fun dismissReminderDialog() {
        _uiState.update { it.copy(isReminderDialogOpen = false) }
    }

    /** 保存定时提醒（当前线路 + 候车站），保存后提示条报时间 */
    @OptIn(ExperimentalUuidApi::class)
    fun saveReminder(hour: Int, minute: Int, daysOfWeek: Set<Int>, autoStartTracking: Boolean) {
        reminderRepository.unavailableReason?.let { postMessage(it); return }
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
            try {
                reminderRepository.saveReminder(reminder)
                postMessage("已设好定时提醒：${reminder.formatTime()}（${reminder.formatRepeatSummary()}）")
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                postMessage(error.message ?: "提醒保存失败，请重试")
            }
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
        pausePolling()
        // 离开线路页就别再读了
        announcer.stop()
    }

    companion object {
        /** 自动轮询周期（秒） */
        const val POLL_INTERVAL_SECONDS = 10

        /** 追踪时提前几站提醒（原预警 chips 的默认值；全局设置见第 25 步） */
        const val DEFAULT_ALERT_THRESHOLD_STOPS = 2
    }
}

/** 上海时间的「HH:mm」 */
private fun shanghaiNowHHmm(): String {
    val t = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).time
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
