package com.trackr.ui.screens.linedetail

import com.trackr.core.data.local.CachedLineStops
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.LiveBus
import com.trackr.core.model.RailMode
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.WatchLine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.testing.FakeAnnouncer
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第 17 步：ViewModel 改拿拆开的接口，这里把三个 Fake 放在一起，保留原测试里 `fakeRepository.xxx` 的写法
 */
private class LineFakes(city: TransitCity, groups: List<FavoriteGroup> = emptyList()) {
    val settings = FakeSettingsRepository(city)
    val favoritesRepo = FakeFavoritesRepository(initialGroups = groups)
    val realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
    var lineDetailResult: Result<BusLineDetail>? by realtime::lineDetailResult
    var timetableResult: Result<LineTimetable> by realtime::timetableResult
    val favorites get() = favoritesRepo.favorites
    val preferredTransitCodeProvider get() = settings.preferredTransitCodeProvider
}

/**
 * 记录线路请求次数、可挂起请求的仓库包装（其余行为委托给 FakeRealtimeRepository）
 */
private class CountingLineRepository(
    private val delegate: FakeRealtimeRepository
) : RealtimeRepository by delegate {
    val lineDetailTargetOrders = mutableListOf<Int?>()
    val lineIds = mutableListOf<String>()
    val stationIds = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null

    /** 单独卡住某条线路的请求；回来的数据在发请求时就定了，用来模拟晚回来的旧方向响应 */
    val lineGates = mutableMapOf<String, CompletableDeferred<Unit>>()

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
        lineDetailTargetOrders += targetOrder
        lineIds += lineId
        stationIds += stationId
        gate?.await()
        val lineGate = lineGates[lineId]
        val result = delegate.getLineDetail(city, lineId, targetOrder, stationId, wgsLat, wgsLng, source, lineNameHint, directionHint)
        lineGate?.await()
        return result
    }
}

/** 记录读写的站表缓存 */
private class FakeLineStopsCache(
    private val entries: MutableMap<String, CachedLineStops> = mutableMapOf()
) : LineStopsCache {
    val writes = mutableListOf<Pair<String, List<BusStop>>>()

    override suspend fun read(key: String): CachedLineStops? = entries[key]

    override suspend fun write(key: String, line: BusLineDirection, reverseDirection: BusLineDirection?, stops: List<BusStop>) {
        writes += key to stops
        entries[key] = CachedLineStops(line, reverseDirection, stops, fetchedAtMillis = 0L)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LineDetailViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var fakeRepository: LineFakes
    private lateinit var trackingRepository: DefaultTrackingRepository
    private lateinit var trackingController: FakeTrackingController
    private val defaultCity = TransitCity(cityId = "034", cityName = "上海")
    private val forwardDirection = BusLineDirection(
        lineId = "line_001",
        name = "49路",
        direction = 0,
        startStop = "汉口路",
        endStop = "上海体育馆",
        source = DataSourceType.CHELAILE
    )
    private val reverseDirection = BusLineDirection(
        lineId = "line_002",
        name = "49路",
        direction = 1,
        startStop = "上海体育馆",
        endStop = "汉口路",
        source = DataSourceType.CHELAILE
    )
    private val stops = listOf(
        BusStop(stopId = "s1", name = "汉口路", order = 1),
        BusStop(stopId = "s2", name = "人民广场", order = 2),
        BusStop(stopId = "s3", name = "上海体育馆", order = 3)
    )
    private val buses = listOf(
        LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 2, etaSeconds = 120, isArrived = false)
    )

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    @BeforeTest
    fun setup() {
        main.install()
        fakeRepository = LineFakes(defaultCity)
        trackingRepository = DefaultTrackingRepository(
            InMemoryTrackingSessionStore(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        trackingController = FakeTrackingController(trackingRepository)
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = buses,
                targetOrder = 2
            )
        )
    }

    private fun createViewModel(
        targetOrder: Int? = null,
        stationId: String? = null,
        realtime: RealtimeRepository = fakeRepository.realtime,
        cache: LineStopsCache = FakeLineStopsCache(),
        reminders: com.trackr.data.repository.IScheduledReminderRepository = FakeScheduledReminderRepository(),
        announcer: FakeAnnouncer = FakeAnnouncer(),
        lineId: String = "line_001",
        boardStopName: String = "",
        alightStopName: String = ""
    ): LineDetailViewModel {
        return LineDetailViewModel(
            cityId = "034",
            initialLineId = lineId,
            initialTargetOrder = targetOrder,
            initialStationId = stationId,
            initialSource = DataSourceType.CHELAILE,
            entryBoardStopName = boardStopName,
            entryAlightStopName = alightStopName,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = realtime,
            reminderRepository = reminders,
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            lineStopsCache = cache,
            announcer = announcer,
            enableAutoPolling = false
        )
    }

    @Test
    fun railMode_followsGlobalSetting() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals(RailMode.Horizontal, viewModel.uiState.value.railMode)

        fakeRepository.settings.setRailMode(RailMode.Vertical)
        testScheduler.runCurrent()

        assertEquals(RailMode.Vertical, viewModel.uiState.value.railMode)
    }

    /** 底栏「乘车码」圆钮和首页悬浮按钮共用一个开关（用 NFC 的人两边都不用看） */
    @Test
    fun showTransitCodeButton_followsGlobalSetting() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showTransitCodeButton)

        fakeRepository.settings.setShowTransitCodeButton(false)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showTransitCodeButton)
    }

    // ---------- 读给我听（第 33 步） ----------

    @Test
    fun readAloud_speaksSameArrivalAsMainCard() = runTest(main.dispatcher) {
        val announcer = FakeAnnouncer()
        val viewModel = createViewModel(targetOrder = 3, announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onReadAloud()
        testScheduler.runCurrent()

        assertEquals(listOf("49路，开往上海体育馆，还有 1 站，大约 2 分钟到上海体育馆站"), announcer.spoken)
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())
    }

    @Test
    fun readAloud_allPassed_readsNextDepartureTime() = runTest(main.dispatcher) {
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = emptyList(),
                targetOrder = 2,
                nextDepartureFromStart = "07:20"
            )
        )
        val announcer = FakeAnnouncer()
        val viewModel = createViewModel(targetOrder = 2, announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onReadAloud()
        testScheduler.runCurrent()

        assertEquals(listOf("49路，开往上海体育馆，车都过了，下一班 7点20分 从 汉口路 发车"), announcer.spoken)
    }

    @Test
    fun readAloud_ttsUnavailable_postsMessage() = runTest(main.dispatcher) {
        val announcer = FakeAnnouncer(isAvailable = false)
        val viewModel = createViewModel(targetOrder = 3, announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onReadAloud()
        testScheduler.runCurrent()

        assertTrue(announcer.spoken.isEmpty())
        assertEquals(
            listOf(TTS_UNAVAILABLE_MESSAGE),
            viewModel.uiState.value.userMessages.map { it.text }
        )
    }

    @Test
    fun readAloud_beforeLineLoaded_doesNothing() = runTest(main.dispatcher) {
        fakeRepository.lineDetailResult = Result.failure(IllegalStateException("网络异常"))
        val announcer = FakeAnnouncer()
        val viewModel = createViewModel(targetOrder = 3, announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onReadAloud()
        testScheduler.runCurrent()

        assertTrue(announcer.spoken.isEmpty())
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())
    }

    @Test
    fun testStartTracking_passesSingleLineTargetToController() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()
        val line = forwardDirection.copy(
            lineNo = "49",
            source = DataSourceType.MYGOLBS,
            mygolbsCityCode = "310000",
            direction = 1
        )

        val started = viewModel.startTracking(
            line = line,
            targetStop = stops[1],
            totalStops = stops.size,
            alertThresholdStops = 3
        )

        assertTrue(started)
        // 追踪状态由 ViewModel 收集会话列表得到，跑一轮调度器
        testScheduler.runCurrent()
        assertEquals(
            listOf<TrackingTarget>(
                TrackingTarget.SingleLine(
                    cityId = "034",
                    lineId = "line_001",
                    lineName = "49路",
                    lineNo = "49",
                    endStopName = "上海体育馆",
                    targetStopId = "s2",
                    targetStopName = "人民广场",
                    targetOrder = 2,
                    totalStops = 3,
                    alertThresholdStops = 3,
                    source = DataSourceType.MYGOLBS,
                    mygolbsCityCode = "310000",
                    direction = 1
                )
            ),
            trackingController.started
        )
        // 页面据此判断「本站正在追踪」
        val session = viewModel.activeTrackingSession.value
        assertTrue(session.isActive)
        assertEquals("line_001", session.lineId)
        assertEquals(2, session.targetOrder)

        viewModel.stopTracking()
        testScheduler.runCurrent()

        assertEquals(1, trackingController.stopCount)
        assertFalse(viewModel.activeTrackingSession.value.isActive)
    }

    @Test
    fun testInitialLoad_populatesLineDetailAndSelectsTargetStop() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)

        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.static)
        assertNotNull(state.live)
        assertEquals("49路", state.static?.line?.name)
        assertEquals(3, state.static?.stops?.size)
        assertEquals(1, state.live?.buses?.size)
        assertEquals("人民广场", state.selectedStop?.name)
        assertEquals(2, state.selectedStop?.order)
    }

    @Test
    fun testSelectStop_updatesSelectedStopAndPanelMode() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val newStop = stops[2]
        viewModel.selectStop(newStop)

        val state = viewModel.uiState.value
        assertEquals("上海体育馆", state.selectedStop?.name)
        assertEquals(PanelMode.STATION_CARD, state.panelMode)
    }

    @Test
    fun testFollowBus_updatesFollowedBusIdAndSetsPanelMode() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.openBusList()
        viewModel.selectBusToFollow(buses.first())
        assertEquals("b1", viewModel.uiState.value.followedBusId)
        assertEquals(PanelMode.BUS_FOLLOW, viewModel.uiState.value.panelMode)

        // 从车辆列表进来的跟随：返回先回列表，再回站点卡，最后交给页面退出
        assertTrue(viewModel.backFromPanel())
        assertNull(viewModel.uiState.value.followedBusId)
        assertEquals(PanelMode.BUS_LIST, viewModel.uiState.value.panelMode)

        assertTrue(viewModel.backFromPanel())
        assertEquals(PanelMode.STATION_CARD, viewModel.uiState.value.panelMode)
        assertFalse(viewModel.backFromPanel())
    }

    @Test
    fun testFollowBus_fromRouteRail_backReturnsToStationCard() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        // 走向条上直接点车：没有经过车辆列表，返回就回站点卡
        viewModel.selectBusToFollow(buses.first())
        assertEquals(PanelMode.BUS_FOLLOW, viewModel.uiState.value.panelMode)

        assertTrue(viewModel.backFromPanel())
        assertNull(viewModel.uiState.value.followedBusId)
        assertEquals(PanelMode.STATION_CARD, viewModel.uiState.value.panelMode)
    }

    @Test
    fun testToggleFavorite_updatesRepositoryAndState() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isFavorite)
        assertEquals(1, fakeRepository.favorites.value.size)
        assertEquals("人民广场", fakeRepository.favorites.value.first().targetStopName)

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)
        assertTrue(fakeRepository.favorites.value.isEmpty())
    }

    @Test
    fun testTransitCodeSheet_openAndDismiss() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)

        viewModel.openTransitCodeSheet()
        assertTrue(viewModel.uiState.value.isTransitCodeSheetOpen)

        viewModel.dismissTransitCodeSheet()
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)
    }

    @Test
    fun testTransitCodeProvider_updatesRepositoryAndState() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.ASK_ALWAYS, viewModel.uiState.value.preferredTransitCodeProvider)

        viewModel.setPreferredTransitCodeProvider(TransitCodeProvider.ALIPAY)
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.ALIPAY, viewModel.uiState.value.preferredTransitCodeProvider)
        assertEquals(TransitCodeProvider.ALIPAY, fakeRepository.preferredTransitCodeProvider.value)

        viewModel.setPreferredTransitCodeProvider(TransitCodeProvider.UNIONPAY)
        testScheduler.runCurrent()

        assertEquals(TransitCodeProvider.UNIONPAY, viewModel.uiState.value.preferredTransitCodeProvider)
        assertEquals(TransitCodeProvider.UNIONPAY, fakeRepository.preferredTransitCodeProvider.value)
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest(main.dispatcher) {
        val vmWithAutoPolling = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = fakeRepository.realtime,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = true
        )
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.resumePolling()
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(false)
        assertFalse(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.setPollingActive(true)
        assertTrue(vmWithAutoPolling.isPollingActive)

        vmWithAutoPolling.pausePolling()
    }

    @Test
    fun testStartTrackingSelectedStop_usesSelectedStopAndDefaultThreshold() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isTrackingThisStop)

        assertTrue(viewModel.startTrackingSelectedStop())
        testScheduler.runCurrent()

        val target = trackingController.started.single() as TrackingTarget.SingleLine
        assertEquals(2, target.targetOrder)
        assertEquals(3, target.totalStops)
        // 线路页的预警 chips 删了：提前站数固定为原来的默认 2 站
        assertEquals(LineDetailViewModel.DEFAULT_ALERT_THRESHOLD_STOPS, target.alertThresholdStops)
        assertEquals(2, target.alertThresholdStops)
        assertTrue(viewModel.uiState.value.isTrackingThisStop)

        // 换到别的站：不再算「追踪本站」
        viewModel.selectStop(stops[2])
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isTrackingThisStop)
    }

    @Test
    fun testAllBusesPassed_fillsNextDepartureFromTimetable() = runTest(main.dispatcher) {
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                stops = stops,
                buses = listOf(LiveBus(busId = "b1", plateOrFleetNo = "沪A1234", order = 3)),
                nextDepartureFromStart = null
            )
        )
        fakeRepository.timetableResult = Result.success(
            LineTimetable(
                lineId = "line_001",
                lineName = "49路",
                todayDepartures = listOf("21:00", "21:40", "22:20")
            )
        )
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 1,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = fakeRepository.realtime,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false,
            nowHHmm = { "21:28" }
        )
        testScheduler.runCurrent()

        assertEquals("21:40", viewModel.uiState.value.live?.fallbackNextDeparture)
    }

    // 第二次进同一条线路时，实时数据可能一次都不挂起（命中缓存）：请求会在构造函数里同步跑完。
    // 以前 timetableCache 声明在 init 之后，这里读到的字段还是 null，点几次线路就崩（真机崩溃栈 NPE at :466）。
    @Test
    fun construct_whenFirstLoadCompletesSynchronously_doesNotCrashOnTimetableCache() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            fakeRepository.lineDetailResult = Result.success(
                BusLineDetail(
                    line = forwardDirection,
                    reverseDirection = reverseDirection,
                    stops = stops,
                    // 车都过了候车站（站序 1），实时接口也没给下一班：会去读时刻表缓存
                    buses = buses,
                    targetOrder = 1,
                    nextDepartureFromStart = null
                )
            )
            fakeRepository.timetableResult = Result.success(
                LineTimetable(lineId = "line_001", lineName = "49路", todayDepartures = listOf("21:00", "21:40"))
            )

            val viewModel = LineDetailViewModel(
                cityId = "034",
                initialLineId = "line_001",
                initialTargetOrder = 1,
                settings = fakeRepository.settings,
                favorites = fakeRepository.favoritesRepo,
                realtime = fakeRepository.realtime,
                reminderRepository = FakeScheduledReminderRepository(),
                trackingRepository = trackingRepository,
                trackingController = trackingController,
                enableAutoPolling = false,
                nowHHmm = { "21:28" }
            )

            // 读时刻表这一步跑完了：构造函数里的请求没有因为字段还没初始化而中断
            assertEquals("21:40", viewModel.uiState.value.live?.fallbackNextDeparture)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun testUpcomingBus_doesNotUseTimetableFallback() = runTest(main.dispatcher) {
        fakeRepository.timetableResult = Result.success(
            LineTimetable(lineId = "line_001", lineName = "49路", todayDepartures = listOf("21:40"))
        )
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 3,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = fakeRepository.realtime,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false,
            nowHHmm = { "21:28" }
        )
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.live?.fallbackNextDeparture)
    }

    @Test
    fun testPollingLifecycle_disabledByDefaultInTest() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        assertFalse(viewModel.isPollingActive)

        viewModel.setPollingActive(true)
        assertTrue(viewModel.isPollingActive)

        viewModel.pausePolling()
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testInitialLoad_withTargetOrder_requestsOnce() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false
        )
        testScheduler.runCurrent()

        assertEquals(listOf<Int?>(2), repo.lineDetailTargetOrders)
    }

    @Test
    fun testInitialLoad_withoutTargetOrder_followsUpOnceForDefaultStop() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false
        )
        testScheduler.runCurrent()

        // 首车在第 2 站 → 默认候车站为第 3 站；只补一次精确请求
        assertEquals(listOf<Int?>(null, 3), repo.lineDetailTargetOrders)
        assertEquals(3, viewModel.uiState.value.selectedStop?.order)
    }

    @Test
    fun testInitialLoad_leadBusAtTerminal_fallsBackToFirstStop() = runTest(main.dispatcher) {
        // issue #9：首车已到终点站时，后面没有站，默认候车站回到起点，不能选终点站
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = listOf(
                    LiveBus(busId = "b3", plateOrFleetNo = "沪A9999", order = 3, etaSeconds = 60, isArrived = true)
                ),
                targetOrder = 3
            )
        )
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(realtime = repo)
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.selectedStop?.order)
        // 默认站回到起点后，按起点站补一次精确请求
        assertEquals(listOf<Int?>(null, 1), repo.lineDetailTargetOrders)
    }

    @Test
    fun testInitialLoad_explicitTerminalTarget_keepsTerminalStop() = runTest(main.dispatcher) {
        // 用户明确打开终点站的线路详情时，仍保留终点站
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 3, realtime = repo)
        testScheduler.runCurrent()

        assertEquals(3, viewModel.uiState.value.selectedStop?.order)
        assertEquals(listOf<Int?>(3), repo.lineDetailTargetOrders)
    }

    @Test
    fun testInitialLoad_tappedStopWinsOverLeadBusAtTerminal() = runTest(main.dispatcher) {
        // 首车已在终点站，但用户是从第 2 站点进来的：默认候车站仍是第 2 站，不按车猜
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection,
                stops = stops,
                buses = listOf(
                    LiveBus(busId = "b3", plateOrFleetNo = "沪A9999", order = 3, etaSeconds = 60, isArrived = true)
                ),
                targetOrder = 2
            )
        )
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, realtime = repo)
        testScheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.selectedStop?.order)
        assertEquals(listOf<Int?>(2), repo.lineDetailTargetOrders)
    }

    @Test
    fun entryStopOrder_exposedWhenOpenedFromStation() = runTest(main.dispatcher) {
        // 从某个站点点进来：页面要拿这个站序把走向条直接滚到那一站
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        assertEquals(2, viewModel.uiState.value.entryStopOrder)
    }

    @Test
    fun entryStopOrder_nullWhenOpenedWithoutStation() = runTest(main.dispatcher) {
        // 搜索 / 收藏这类没有带站序的入口：页面保持从顶部开始
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.entryStopOrder)
    }

    @Test
    fun testPolling_skipsWhenPreviousLoadStillInFlight() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        assertTrue(viewModel.isLoadInFlight)

        // 10 秒到期时上一轮仍未返回 → 跳过
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.isLoadInFlight)

        // 下一周期正常请求
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)

        viewModel.pausePolling()
    }

    @Test
    fun testSelectStop_resetsCountdown() = runTest(main.dispatcher) {
        // C1 后没有每秒倒计时：断言选站后下一轮轮询从选站时刻起再等 10 秒（e683286）
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()

        viewModel.selectStop(stops[2])
        testScheduler.runCurrent()
        assertEquals(listOf<Int?>(2, 3), repo.lineDetailTargetOrders)

        // 原来的第 10 秒：不再轮询
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)

        // 选站后第 10 秒（第 15 秒）：按新站轮询
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        assertEquals(listOf<Int?>(2, 3, 3), repo.lineDetailTargetOrders)

        viewModel.pausePolling()
    }

    @Test
    fun testRefreshNow_restartsPollingTimer() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = true
        )
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(8_000L)
        testScheduler.runCurrent()

        viewModel.refreshNow(fromPull = true)
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)

        testScheduler.advanceTimeBy(2_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)

        testScheduler.advanceTimeBy(8_000L)
        testScheduler.runCurrent()
        assertEquals(3, repo.lineDetailTargetOrders.size)

        viewModel.pausePolling()
    }

    @Test
    fun testRefreshNow_fromPull_showsPullIndicatorUntilDone() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, realtime = repo)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPullRefreshing)

        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        viewModel.refreshNow(fromPull = true)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPullRefreshing)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPullRefreshing)

        // 错误条「重试」不画下拉圈
        val gate2 = CompletableDeferred<Unit>()
        repo.gate = gate2
        viewModel.refreshNow(fromPull = false)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPullRefreshing)
        assertTrue(viewModel.uiState.value.isRefreshing)
        gate2.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun testCachedStops_shownBeforeNetworkThenReplaced() = runTest(main.dispatcher) {
        val key = LineStopsCache.keyOf(DataSourceType.CHELAILE, "034", "", "line_001")
        val cachedStops = stops.take(2)
        val cache = FakeLineStopsCache(
            mutableMapOf(key to CachedLineStops(forwardDirection, reverseDirection, cachedStops, fetchedAtMillis = 1L))
        )
        val repo = CountingLineRepository(fakeRepository.realtime)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = createViewModel(targetOrder = 2, realtime = repo, cache = cache)
        testScheduler.runCurrent()

        // 网络还没回来：先画缓存的站表，选中进页面时带的站；实时部分为空（主卡显示「正在查车…」）
        var state = viewModel.uiState.value
        assertTrue(state.isLoading)
        assertEquals(cachedStops, state.static?.stops)
        assertNull(state.live)
        assertEquals(2, state.selectedStop?.order)
        // 缓存不挡请求
        assertEquals(listOf<Int?>(2), repo.lineDetailTargetOrders)

        gate.complete(Unit)
        testScheduler.runCurrent()

        state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(stops, state.static?.stops)
        assertNotNull(state.live)
        // 站表变了才写回
        assertEquals(listOf(key to stops), cache.writes)
    }

    @Test
    fun testStopsCache_writtenOnceWhileStopsUnchanged_andStaticInstanceReused() = runTest(main.dispatcher) {
        val cache = FakeLineStopsCache()
        val viewModel = createViewModel(targetOrder = 2, cache = cache)
        testScheduler.runCurrent()
        val firstStatic = viewModel.uiState.value.static

        viewModel.refreshNow()
        testScheduler.runCurrent()
        viewModel.refreshNow()
        testScheduler.runCurrent()

        assertEquals(1, cache.writes.size)
        // 站表没变：沿用同一个实例，头部和站序不跟着轮询重组（§4.4）
        assertTrue(firstStatic === viewModel.uiState.value.static)
    }

    @Test
    fun testCachedStops_userPickedStopKeptWhenInitialLoadArrives() = runTest(main.dispatcher) {
        val key = LineStopsCache.keyOf(DataSourceType.CHELAILE, "034", "", "line_001")
        val cache = FakeLineStopsCache(
            mutableMapOf(key to CachedLineStops(forwardDirection, reverseDirection, stops, fetchedAtMillis = 1L))
        )
        val repo = CountingLineRepository(fakeRepository.realtime)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = createViewModel(realtime = repo, cache = cache)
        testScheduler.runCurrent()

        // 在缓存的站表上先点了第 1 站
        viewModel.selectStop(stops[0])
        gate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.selectedStop?.order)
    }

    @Test
    fun testScreenResume_refreshesOnlyWhenStale() = runTest(main.dispatcher) {
        var now = 0L
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = LineDetailViewModel(
            cityId = "034",
            initialLineId = "line_001",
            initialTargetOrder = 2,
            settings = fakeRepository.settings,
            favorites = fakeRepository.favoritesRepo,
            realtime = repo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = true,
            clockMillis = { now }
        )
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        // 刚加载完就 resume：不额外请求
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(1, repo.lineDetailTargetOrders.size)

        viewModel.onScreenPaused()
        assertFalse(viewModel.isPollingActive)

        // 后台超过一个周期后回到前台：立即刷新一次并恢复轮询
        now += 11_000L
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(2, repo.lineDetailTargetOrders.size)
        assertTrue(viewModel.isPollingActive)

        viewModel.onScreenPaused()
    }

    @Test
    fun testScreenResume_noopWhenAutoPollingDisabled() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testToggleFavorite_postsMessages() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()
        // 没有分组：提示条不带「换分组」
        val added = viewModel.uiState.value.userMessages.single()
        assertEquals("已收藏", added.text)
        assertNull(added.action)

        viewModel.onMessageShown(added.id)
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()
        assertEquals("已取消收藏", viewModel.uiState.value.userMessages.single().text)
    }

    @Test
    fun testToggleFavorite_withGroups_offersChangeGroup() = runTest(main.dispatcher) {
        fakeRepository = LineFakes(defaultCity, groups = listOf(FavoriteGroup(id = "g1", name = "上班")))
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(line = forwardDirection, reverseDirection = reverseDirection, stops = stops, buses = buses, targetOrder = 2)
        )
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        viewModel.toggleFavoriteStop()
        testScheduler.runCurrent()
        val message = viewModel.uiState.value.userMessages.single()
        assertEquals("已收藏", message.text)
        assertEquals(LineUserMessage.Action.CHANGE_GROUP, message.action)
        assertNull(viewModel.uiState.value.favoriteGroupId)

        viewModel.onMessageShown(message.id)
        viewModel.openGroupPicker()
        assertTrue(viewModel.uiState.value.isGroupPickerOpen)
        assertEquals(listOf("上班"), viewModel.uiState.value.favoriteGroups.map { it.name })

        viewModel.moveFavoriteToGroup("g1")
        testScheduler.runCurrent()
        val state = viewModel.uiState.value
        assertFalse(state.isGroupPickerOpen)
        assertEquals("g1", state.favoriteGroupId)
        assertEquals("已移到「上班」", state.userMessages.single().text)
    }

    @Test
    fun testOpenGroupPicker_ignoredWhenNotFavorite() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        viewModel.openGroupPicker()
        assertFalse(viewModel.uiState.value.isGroupPickerOpen)
    }

    @Test
    fun unavailableRemindersDoNotOpenOrSave() = runTest(main.dispatcher) {
        val backing = FakeScheduledReminderRepository()
        val unavailable = object : com.trackr.data.repository.IScheduledReminderRepository by backing {
            override val unavailableReason = "此平台尚未接入定时提醒"
        }
        val viewModel = createViewModel(targetOrder = 2, reminders = unavailable)
        testScheduler.runCurrent()
        viewModel.openReminderDialog()
        assertFalse(viewModel.uiState.value.isReminderDialogOpen)
        viewModel.saveReminder(7, 30, setOf(2), true)
        testScheduler.runCurrent()
        assertTrue(backing.reminders.value.isEmpty())
        assertTrue(viewModel.uiState.value.userMessages.all { it.text == unavailable.unavailableReason })
        assertTrue(viewModel.uiState.value.userMessages.isNotEmpty())
    }

    @Test
    fun testSaveReminder_savesSelectedStopAndPostsMessage() = runTest(main.dispatcher) {
        val reminders = FakeScheduledReminderRepository()
        val viewModel = createViewModel(targetOrder = 2, reminders = reminders)
        testScheduler.runCurrent()

        viewModel.openReminderDialog()
        assertTrue(viewModel.uiState.value.isReminderDialogOpen)

        viewModel.saveReminder(hour = 7, minute = 30, daysOfWeek = setOf(2, 3, 4, 5, 6), autoStartTracking = true)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isReminderDialogOpen)
        val saved: ScheduledReminder = reminders.reminders.value.single()
        assertEquals("line_001", saved.lineId)
        assertEquals("人民广场", saved.targetStopName)
        assertEquals(2, saved.targetOrder)
        assertEquals(3, saved.totalStops)
        assertEquals("上海", saved.cityName)
        assertTrue(saved.autoStartLiveTracking)
        assertTrue(saved.id.isNotBlank())
        val text = viewModel.uiState.value.userMessages.single().text
        assertTrue(text.startsWith("已设好定时提醒：${saved.formatTime()}"), text)
    }

    @Test
    fun testSwitchOppositeDirection_findsSameNameStop() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = reverseDirection,
                reverseDirection = forwardDirection,
                stops = stops.reversed().mapIndexed { i, stop -> stop.copy(stopId = "r${i + 1}", order = i + 1) },
                buses = emptyList()
            )
        )
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("line_002", state.static?.line?.lineId)
        assertEquals("人民广场", state.selectedStop?.name)
        assertEquals(PanelMode.STATION_CARD, state.panelMode)
    }

    // ---------- 选中的候车站不能被刷新换掉 ----------

    private val duplicateOrderStops = listOf(
        BusStop(stopId = "d1", name = "汉口路", order = 1),
        BusStop(stopId = "d2", name = "人民广场", order = 2),
        BusStop(stopId = "d3", name = "南京路", order = 2),
        BusStop(stopId = "d4", name = "上海体育馆", order = 3)
    )

    private fun useDuplicateOrderStops() {
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(line = forwardDirection, reverseDirection = reverseDirection, stops = duplicateOrderStops, buses = buses)
        )
    }

    @Test
    fun selectStop_duplicateOrder_keepsTappedStopAfterRefresh() = runTest(main.dispatcher) {
        useDuplicateOrderStops()
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectStop(duplicateOrderStops[2])
        testScheduler.runCurrent()
        assertEquals("南京路", viewModel.uiState.value.selectedStop?.name)

        viewModel.refreshNow()
        testScheduler.runCurrent()
        assertEquals("南京路", viewModel.uiState.value.selectedStop?.name)
    }

    @Test
    fun initialLoad_duplicateOrder_usesEntryStationId() = runTest(main.dispatcher) {
        useDuplicateOrderStops()
        val viewModel = createViewModel(targetOrder = 2, stationId = "d3")
        testScheduler.runCurrent()

        assertEquals("南京路", viewModel.uiState.value.selectedStop?.name)
    }

    @Test
    fun selectStop_orderShifted_findsStopByIdentity() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        viewModel.selectStop(stops[2])
        testScheduler.runCurrent()

        // 上游站表多了一站，站序整体后移：还应该是用户选的那一站，不能被第 3 站的新站顶替
        val shifted = listOf(BusStop(stopId = "s0", name = "新增站", order = 1)) +
            stops.map { it.copy(order = it.order + 1) }
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(line = forwardDirection, reverseDirection = reverseDirection, stops = shifted, buses = buses)
        )
        viewModel.refreshNow()
        testScheduler.runCurrent()

        assertEquals("s3", viewModel.uiState.value.selectedStop?.stopId)
        assertEquals(4, viewModel.uiState.value.selectedStop?.order)
    }

    @Test
    fun selectStop_stopsSendingEntryStationId() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, stationId = "s2", realtime = repo)
        testScheduler.runCurrent()
        assertEquals("s2", repo.stationIds.last())

        viewModel.selectStop(stops[2])
        testScheduler.runCurrent()

        assertEquals(3, repo.lineDetailTargetOrders.last())
        assertNull(repo.stationIds.last())
    }

    @Test
    fun switchDirection_lateOldDirectionResponse_isIgnored() = runTest(main.dispatcher) {
        val repo = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 1, realtime = repo)
        testScheduler.runCurrent()

        // 旧方向的刷新发出去了但还没回来，用户此时换向
        val oldResponse = CompletableDeferred<Unit>()
        repo.lineGates["line_001"] = oldResponse
        viewModel.refreshNow()
        testScheduler.runCurrent()

        fakeRepository.lineDetailResult = Result.success(reverseDetail())
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()
        assertEquals("汉口路", viewModel.uiState.value.selectedStop?.name)

        oldResponse.complete(Unit)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("line_002", state.static?.line?.lineId)
        assertEquals("汉口路", state.selectedStop?.name)
        assertFalse(state.isRefreshing)
    }

    // ---------- 底栏换向 ----------

    @Test
    fun switchDirection_staysSwitchingUntilReverseLineArrives() = runTest(main.dispatcher) {
        val counting = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, realtime = counting)
        testScheduler.runCurrent()

        fakeRepository.lineDetailResult = Result.success(reverseDetail())
        counting.gate = CompletableDeferred()
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        // 请求还没回来：底栏换向钮转圈并禁用，挡住连点
        assertTrue(viewModel.uiState.value.isSwitchingDirection)

        counting.gate?.complete(Unit)
        counting.gate = null
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isSwitchingDirection)
        assertEquals("line_002", viewModel.uiState.value.static?.line?.lineId)
    }

    @Test
    fun switchDirection_whileTracking_endsTrackingFirst() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()
        assertTrue(viewModel.startTrackingSelectedStop())
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isTrackingThisStop)

        fakeRepository.lineDetailResult = Result.success(reverseDetail())
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        assertEquals(1, trackingController.stopCount)
        assertTrue(viewModel.uiState.value.userMessages.any { it.text == "已结束追踪" })
        assertFalse(viewModel.uiState.value.isTrackingThisStop)
    }

    @Test
    fun switchDirection_withoutSameNameStop_fallsBackAndExplains() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        // 反向线站名全不一样：只能退回默认候车站，并说明为什么换了站
        val renamed = listOf(
            BusStop(stopId = "r1", name = "甲站", order = 1),
            BusStop(stopId = "r2", name = "乙站", order = 2),
            BusStop(stopId = "r3", name = "丙站", order = 3)
        )
        fakeRepository.lineDetailResult = Result.success(reverseDetail(stops = renamed))
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("甲站", state.selectedStop?.name)
        assertEquals(
            "反向线路没有「人民广场」，已选 甲站",
            state.userMessages.single().text
        )
    }

    @Test
    fun switchDirection_failure_rollsBackToCurrentLine() = runTest(main.dispatcher) {
        val counting = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, realtime = counting)
        testScheduler.runCurrent()
        counting.lineIds.clear()

        fakeRepository.lineDetailResult = Result.failure(IllegalStateException("boom"))
        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        assertEquals("换向失败，请稍后重试", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSwitchingDirection)

        // 失败后线路 id 回滚：下一轮刷新仍然请求原来的方向
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(line = forwardDirection, reverseDirection = reverseDirection, stops = stops, buses = buses, targetOrder = 2)
        )
        viewModel.refreshNow(fromPull = false)
        testScheduler.runCurrent()
        assertEquals("line_001", counting.lineIds.last())
    }

    @Test
    fun switchDirection_blankReverseLineId_isIgnored() = runTest(main.dispatcher) {
        // 旧缓存里可能留着 lineId 为空的反向：当没有，别发一条必然失败的请求
        fakeRepository.lineDetailResult = Result.success(
            BusLineDetail(
                line = forwardDirection,
                reverseDirection = reverseDirection.copy(lineId = ""),
                stops = stops,
                buses = buses,
                targetOrder = 2
            )
        )
        val counting = CountingLineRepository(fakeRepository.realtime)
        val viewModel = createViewModel(targetOrder = 2, realtime = counting)
        testScheduler.runCurrent()
        counting.lineIds.clear()

        viewModel.switchOppositeDirection()
        testScheduler.runCurrent()

        assertTrue(counting.lineIds.isEmpty())
        assertEquals("line_001", viewModel.uiState.value.static?.line?.lineId)
        assertFalse(viewModel.uiState.value.isSwitchingDirection)
    }

    private fun reverseDetail(stops: List<BusStop> = reversedStops()) = BusLineDetail(
        line = reverseDirection,
        reverseDirection = forwardDirection,
        stops = stops,
        buses = emptyList()
    )

    private fun reversedStops(): List<BusStop> = stops.reversed().mapIndexed { i, stop ->
        stop.copy(stopId = "r${i + 1}", order = i + 1)
    }

    @Test
    fun watchTogether_lineThatIsNotThePrimary_showsTrackingForThisStop() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 2)
        testScheduler.runCurrent()

        // 一起等里排第一的是 9 路，这条线（49 路）是第二条
        trackingController.start(
            TrackingTarget.WatchTogether(
                cityId = "034",
                stopId = "s2",
                stopName = "人民广场",
                lines = listOf(WatchLine(lineId = "line_009", lineName = "9路", stopOrder = 2), WatchLine(lineId = "line_001", lineName = "49路", stopOrder = 2))
            )
        )
        testScheduler.runCurrent()

        val session = viewModel.activeTrackingSession.value
        assertTrue(session.isActive)
        // 状态按这条线自己的数据拼：站序、线名都是它的
        assertEquals("line_001", session.lineId)
        assertEquals("49路", session.lineName)
        assertEquals(2, session.targetOrder)
        assertTrue(viewModel.uiState.value.isTrackingThisStop)

        // 结束追踪只去掉这一条，一起等还在
        viewModel.stopTracking()
        testScheduler.runCurrent()
        assertEquals(listOf("line_009"), trackingRepository.session.value?.watchTarget?.lines?.map { it.lineId })
        assertFalse(viewModel.activeTrackingSession.value.isActive)
    }

    // ---------- 从路线规划进来：按上车站名定站和方向 ----------

    /** 高德方案没对齐时站序是占位的 1：按站名停在上车站，不停首站，并按该站再请求一次拿准 ETA */
    @Test
    fun entryStopName_overridesPlaceholderOrder() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 1, boardStopName = "人民广场", alightStopName = "上海体育馆")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("人民广场", state.selectedStop?.name)
        assertEquals(2, state.entryStopOrder)
        assertEquals("line_001", state.static?.line?.lineId)
        assertEquals(listOf<Int?>(1, 2), fakeRepository.realtime.lineDetailTargetOrders)
    }

    /** 当前方向上车站在下车站后面：换到反向，停在反向的上车站 */
    @Test
    fun entryStopName_wrongDirection_switchesToReverse() = runTest(main.dispatcher) {
        val reverseStops = listOf(
            BusStop(stopId = "r1", name = "上海体育馆", order = 1),
            BusStop(stopId = "r2", name = "人民广场", order = 2),
            BusStop(stopId = "r3", name = "汉口路", order = 3)
        )
        fakeRepository.realtime.lineDetailsMap["line_001"] = BusLineDetail(
            line = forwardDirection, reverseDirection = reverseDirection, stops = stops, buses = buses
        )
        fakeRepository.realtime.lineDetailsMap["line_002"] = BusLineDetail(
            line = reverseDirection, reverseDirection = forwardDirection, stops = reverseStops, buses = emptyList()
        )

        val viewModel = createViewModel(targetOrder = 1, boardStopName = "人民广场", alightStopName = "汉口路")
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("line_002", state.static?.line?.lineId)
        assertEquals("r2", state.selectedStop?.stopId)
        assertEquals(2, state.entryStopOrder)
        // 之后的请求（含轮询）都走反向
        assertEquals("line_002", fakeRepository.realtime.lineDetailCalls.last())
        assertEquals(2, fakeRepository.realtime.lineDetailTargetOrders.last())
    }

    /** 两个方向都找不到这个站名：退回原来的站序逻辑 */
    @Test
    fun entryStopName_notFound_fallsBackToOrder() = runTest(main.dispatcher) {
        val viewModel = createViewModel(targetOrder = 3, boardStopName = "不存在的站")
        testScheduler.runCurrent()

        assertEquals("上海体育馆", viewModel.uiState.value.selectedStop?.name)
        assertEquals("line_001", viewModel.uiState.value.static?.line?.lineId)
    }
}
