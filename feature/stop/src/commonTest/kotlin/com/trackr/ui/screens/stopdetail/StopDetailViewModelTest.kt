package com.trackr.ui.screens.stopdetail

import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.domain.tracking.WatchTogetherRoute
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.WatchLine
import com.trackr.core.model.WatchedLine
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeTransitCodeLauncher
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 记录站点请求次数、可挂起请求的仓库包装（其余行为委托给 FakeRealtimeRepository）
 */
private class CountingStopRepository(
    private val delegate: FakeRealtimeRepository
) : RealtimeRepository by delegate {
    val stopDetailPhysicalIds = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null

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
        stopDetailPhysicalIds += physicalStId
        gate?.await()
        return delegate.getStopDetail(city, stopId, stopName, physicalStId, namesakeStId, stationLat, stationLng, userLat, userLng)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StopDetailViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var settings: FakeSettingsRepository
    private lateinit var favorites: FakeFavoritesRepository
    private lateinit var fakeRepository: FakeRealtimeRepository
    private lateinit var controller: FakeTrackingController
    private lateinit var launcher: FakeTransitCodeLauncher
    private var trackingRepository: DefaultTrackingRepository? = null
    private val userLocationStore = FakeUserLocationStore()
    private val defaultCity = TransitCity(cityId = "034", cityName = "上海")

    private fun line(id: String, name: String, end: String, start: String = "起点") = BusLineDirection(
        lineId = id,
        name = name,
        startStop = start,
        endStop = end,
        firstTime = "05:30",
        lastTime = "23:00",
        source = DataSourceType.CHELAILE
    )

    private val sampleStop1 = NearbyStop(
        stopId = "stop_001",
        name = "人民广场（广东路）",
        physicalStId = "phy_01",
        wgsLat = 31.23,
        wgsLng = 121.47,
        lines = listOf(
            NearbyStopLineStatus(
                line = BusLineDirection(
                    lineId = "line_49",
                    name = "49路",
                    startStop = "汉口路",
                    endStop = "上海体育馆",
                    source = DataSourceType.CHELAILE
                ),
                targetOrder = 2,
                targetStopId = "stop_001",
                targetStopName = "人民广场（广东路）",
                statusText = "即将到站"
            ),
            NearbyStopLineStatus(
                line = line("line_71", "71路", "上海体育馆"),
                targetOrder = 6,
                targetStopId = "stop_001_71",
                targetStopName = "人民广场（广东路）",
                approachingBuses = listOf(LiveBus(busId = "b71", plateOrFleetNo = "沪A·71001", order = 3, etaSeconds = 480))
            ),
            NearbyStopLineStatus(
                line = line("", "临时线", "某地"),
                targetOrder = 0,
                targetStopId = "",
                targetStopName = "人民广场（广东路）"
            )
        )
    )

    private val sampleStop2 = NearbyStop(
        stopId = "stop_002",
        name = "人民广场（福州路）",
        physicalStId = "phy_02",
        lines = listOf(
            NearbyStopLineStatus(
                line = BusLineDirection(
                    lineId = "line_18",
                    name = "18路",
                    startStop = "鲁迅公园",
                    endStop = "世博会博物馆",
                    source = DataSourceType.CHELAILE
                ),
                targetOrder = 5,
                targetStopId = "stop_002",
                targetStopName = "人民广场（福州路）",
                statusText = "2站 / 5分钟"
            )
        )
    )

    @BeforeTest
    fun setup() {
        main.install()
        settings = FakeSettingsRepository(defaultCity)
        favorites = FakeFavoritesRepository()
        fakeRepository = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
        fakeRepository.stopDetailResult = Result.success(listOf(sampleStop1, sampleStop2))
        controller = FakeTrackingController()
        launcher = FakeTransitCodeLauncher()
        trackingRepository = null
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    /** 真的追踪仓库（一起等进行中视图要读它）；FakeTrackingController 把命令转给它 */
    private fun TestScope.useRealTracking(): DefaultTrackingRepository {
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope)
        trackingRepository = repo
        controller = FakeTrackingController(repo)
        return repo
    }

    private fun TestScope.newViewModel(
        realtime: RealtimeRepository = fakeRepository,
        enableAutoPolling: Boolean,
        clockMillis: (() -> Long)? = null
    ): StopDetailViewModel {
        val tracking = trackingRepository ?: useRealTracking()
        return if (clockMillis == null) {
            StopDetailViewModel(
                cityId = "034", stopId = "stop_001", initialStopName = "人民广场",
                settings = settings, favorites = favorites, realtime = realtime, userLocationStore = userLocationStore,
                trackingRepository = tracking, trackingController = controller, transitCodeLauncher = launcher,
                enableAutoPolling = enableAutoPolling
            )
        } else {
            StopDetailViewModel(
                cityId = "034", stopId = "stop_001", initialStopName = "人民广场",
                settings = settings, favorites = favorites, realtime = realtime, userLocationStore = userLocationStore,
                trackingRepository = tracking, trackingController = controller, transitCodeLauncher = launcher,
                enableAutoPolling = enableAutoPolling, clockMillis = clockMillis
            )
        }
    }

    private fun TestScope.createViewModel(): StopDetailViewModel = newViewModel(enableAutoPolling = false)

    // ================================================================ 原有用例

    @Test
    fun testInitialLoad_populatesStopsAndResolvesName() = runTest(main.dispatcher) {
        val viewModel = createViewModel()

        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.stops.size)
        assertEquals("人民广场（广东路）", state.stopName)
        assertEquals(0, state.selectedStopIndex)
        assertNull(state.errorMessage)
    }

    @Test
    fun testSelectStopIndex_updatesSelectedStop() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectStopIndex(1)
        assertEquals(1, viewModel.uiState.value.selectedStopIndex)
        assertEquals(listOf("18路"), viewModel.uiState.value.rows.map { it.lineName })
    }

    @Test
    fun testToggleFavorite_updatesFavoriteIds() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val targetLine = sampleStop1.lines.first()
        viewModel.toggleFavorite(sampleStop1, targetLine)
        testScheduler.runCurrent()

        val expectedFavId = "034_line_49_2"
        assertTrue(viewModel.uiState.value.favoriteIds.contains(expectedFavId))
        assertTrue(viewModel.uiState.value.rows.first().isFavorite)

        // Toggle again to remove（第 21 步起页面发事件，按行 key 找线）
        viewModel.onEvent(StopDetailEvent.ToggleFavorite(viewModel.uiState.value.rows.first().key))
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.favoriteIds.contains(expectedFavId))
        assertFalse(viewModel.uiState.value.rows.first().isFavorite)
    }

    // ================================================================ 收藏整个站（issue #47）

    private fun existingStation(): FavoriteStation = FavoriteStation(
        id = FavoriteStation.idOf("034", DataSourceType.CHELAILE, "人民广场（广东路）", ""),
        cityId = "034", cityName = "上海", stopName = "人民广场（广东路）", stopId = "stop_001", physicalStId = "phy_01",
        platformLabel = "往北"
    )

    @Test
    fun stationFavorite_toggle_savesCurrentPlatformAsDefault_thenRemoves() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isStationFavorite)

        viewModel.onEvent(StopDetailEvent.ToggleStationFavorite)
        testScheduler.runCurrent()

        val saved = favorites.favoriteStations.value.single()
        val state = viewModel.uiState.value
        assertTrue(state.isStationFavorite)
        assertEquals("stop_001", saved.stopId)
        assertEquals("phy_01", saved.physicalStId)
        assertEquals("人民广场（广东路）", saved.stopName)
        assertEquals(state.platformLabels[0], saved.platformLabel)
        assertEquals("034", saved.cityId)
        assertEquals("上海", saved.cityName)
        assertEquals(31.23, saved.wgsLat)
        assertEquals("已收藏 人民广场（广东路） · ${state.platformLabels[0]}", viewModel.userMessages.first())
        // 刚收藏：当前站台就是默认，切站台也不问
        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)

        viewModel.onEvent(StopDetailEvent.ToggleStationFavorite)
        testScheduler.runCurrent()
        assertTrue(favorites.favoriteStations.value.isEmpty())
        assertFalse(viewModel.uiState.value.isStationFavorite)
    }

    @Test
    fun stationFavorite_rowStarsStillFavoriteSingleLines() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.ToggleStationFavorite)
        testScheduler.runCurrent()

        // 收藏整个站不会把每条线都标成收藏
        assertTrue(viewModel.uiState.value.rows.none { it.isFavorite })
        assertTrue(favorites.favorites.value.isEmpty())
    }

    @Test
    fun stationFavorite_switchPlatform_asksOnce_acceptUpdatesDefault() = runTest(main.dispatcher) {
        favorites = FakeFavoritesRepository(initialStations = listOf(existingStation()))
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isStationFavorite)
        // 刚打开、还在默认站台：不问
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)

        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        val label = viewModel.uiState.value.platformLabels[1]
        assertEquals(label, viewModel.uiState.value.defaultPlatformPrompt)

        viewModel.onEvent(StopDetailEvent.AcceptDefaultPlatform)
        testScheduler.runCurrent()

        val updated = favorites.favoriteStations.value.single()
        assertEquals(existingStation().id, updated.id)
        assertEquals("stop_002", updated.stopId)
        assertEquals("phy_02", updated.physicalStId)
        assertEquals(label, updated.platformLabel)
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)
        assertTrue(viewModel.uiState.value.isStationFavorite)

        // 同一次打开不再问
        viewModel.selectStopIndex(0)
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)
    }

    @Test
    fun stationFavorite_dismissPrompt_keepsDefault_andDoesNotAskAgain() = runTest(main.dispatcher) {
        favorites = FakeFavoritesRepository(initialStations = listOf(existingStation()))
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.defaultPlatformPrompt)

        viewModel.onEvent(StopDetailEvent.DismissDefaultPlatformPrompt)
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)
        assertEquals(existingStation(), favorites.favoriteStations.value.single())

        viewModel.selectStopIndex(0)
        testScheduler.runCurrent()
        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.defaultPlatformPrompt)
    }

    @Test
    fun openedOnAPlatform_selectsThatPlatformFirst() = runTest(main.dispatcher) {
        val viewModel = StopDetailViewModel(
            cityId = "034", stopId = "stop_002", initialStopName = "人民广场", physicalStId = "phy_02",
            settings = settings, favorites = favorites, realtime = fakeRepository, userLocationStore = userLocationStore,
            trackingRepository = useRealTracking(), trackingController = controller, transitCodeLauncher = launcher,
            enableAutoPolling = false
        )
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.selectedStopIndex)
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest(main.dispatcher) {
        val vmWithAutoPolling = newViewModel(enableAutoPolling = true)
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
    fun testPollingLifecycle_disabledByDefaultInTest() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        assertFalse(viewModel.isPollingActive)

        viewModel.setPollingActive(true)
        assertTrue(viewModel.isPollingActive)

        viewModel.pausePolling()
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testSelectStopIndex_changedPlatform_refreshesWithThatPlatform() = runTest(main.dispatcher) {
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = newViewModel(realtime = repo, enableAutoPolling = true)
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        // t=4s 换站台：马上按新站台请求一次
        testScheduler.advanceTimeBy(4_000L)
        testScheduler.runCurrent()
        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertEquals(listOf<String?>(null, "phy_02"), repo.stopDetailPhysicalIds)
        assertEquals(1, viewModel.uiState.value.selectedStopIndex)

        // 重新计时：原来 t=10s 的一轮推到 t=14s
        testScheduler.advanceTimeBy(6_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)
        testScheduler.advanceTimeBy(4_000L)
        testScheduler.runCurrent()
        assertEquals(listOf<String?>(null, "phy_02", "phy_02"), repo.stopDetailPhysicalIds)

        // 选中同一站台不重复请求
        viewModel.selectStopIndex(1)
        testScheduler.runCurrent()
        assertEquals(3, repo.stopDetailPhysicalIds.size)

        viewModel.pausePolling()
    }

    @Test
    fun testPolling_skipsWhenPreviousLoadStillInFlight() = runTest(main.dispatcher) {
        val repo = CountingStopRepository(fakeRepository)
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate
        val viewModel = newViewModel(realtime = repo, enableAutoPolling = true)
        testScheduler.runCurrent()
        assertTrue(viewModel.isLoadInFlight)

        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        gate.complete(Unit)
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)

        viewModel.pausePolling()
    }

    @Test
    fun testScreenResume_refreshesOnlyWhenStale() = runTest(main.dispatcher) {
        var now = 0L
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = newViewModel(realtime = repo, enableAutoPolling = true, clockMillis = { now })
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        viewModel.onScreenPaused()
        assertFalse(viewModel.isPollingActive)

        now += 11_000L
        viewModel.onScreenResumed()
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)
        assertTrue(viewModel.isPollingActive)

        viewModel.onScreenPaused()
    }

    @Test
    fun testScreenResume_noopWhenAutoPollingDisabled() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onScreenResumed()
        assertFalse(viewModel.isPollingActive)
    }

    // ================================================================ 站台名 / 行

    @Test
    fun testPlatformLabels_namedByMostCommonTerminal() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(listOf("往 上海体育馆", "往 世博会博物馆"), viewModel.uiState.value.platformLabels)
        assertEquals("往 上海体育馆", viewModel.uiState.value.selectedPlatformLabel)
    }

    @Test
    fun testPlatformLabels_fallBackWhenMissingOrSameName() {
        // 有站台还没拿到线路（补全失败）
        val shell = sampleStop2.copy(lines = listOf(sampleStop2.lines.first().copy(line = line("", "", ""))))
        assertEquals(listOf("站台 1", "站台 2"), platformLabels(listOf(sampleStop1, shell)))
        // 两个站台都往同一个终点
        val same = sampleStop2.copy(lines = listOf(sampleStop2.lines.first().copy(line = line("line_x", "x路", "上海体育馆"))))
        assertEquals(listOf("站台 1", "站台 2"), platformLabels(listOf(sampleStop1, same)))
        // 平局取先出现的终点
        val tie = sampleStop2.copy(
            lines = listOf(
                sampleStop2.lines.first().copy(line = line("a", "a路", "甲")),
                sampleStop2.lines.first().copy(line = line("b", "b路", "乙"))
            )
        )
        assertEquals(listOf("往 上海体育馆", "往 甲"), platformLabels(listOf(sampleStop1, tie)))
    }

    @Test
    fun testRows_mapArrivalHoursAndSubtitle() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals("3 条线", state.subtitle)
        val (r49, r71, rTemp) = state.rows
        // 数据源给了状态文字：照旧优先显示
        assertEquals("即将到站", r49.arrival.value)
        assertEquals(ArrivalTone.Mute, r49.arrival.tone)
        assertEquals("", r49.hours)
        // 有来车：8 分钟 / 还有 3 站
        assertEquals("8", r71.arrival.value)
        assertEquals("分钟", r71.arrival.unit)
        assertEquals("还有 3 站", r71.arrival.detail)
        assertEquals("05:30–23:00", r71.hours)
        assertEquals("上海体育馆", r71.endStop)
        assertTrue(r71.selectable)
        // 没有 lineId 的行不能选、不能点开
        assertFalse(rTemp.selectable)
        assertEquals("暂无在途车辆", rTemp.arrival.value)
    }

    @Test
    fun testRows_inServiceFirstThenFavoritesFirstWithinEachGroup() {
        fun status(n: Int, text: String = "", bus: Boolean = false) = NearbyStopLineStatus(
            line = line("L$n", "${n}路", "终点$n"),
            targetOrder = 5,
            targetStopId = "s$n",
            targetStopName = "大站",
            statusText = text,
            approachingBuses = if (bus) listOf(LiveBus(busId = "b$n", plateOrFleetNo = "", order = 2, etaSeconds = 300)) else emptyList()
        )
        val stop = NearbyStop(
            stopId = "stop_x",
            name = "大站",
            lines = listOf(
                status(1, text = "末班已过"),
                status(2, bus = true),
                status(3, text = "暂停运营"),
                status(4, text = "等待发车"),
                status(5), // 没车没文字：算不在运营
                status(6, bus = true),
                status(7, text = "末班已过")
            )
        )
        // 收藏：一条运营中的（6 路）、一条不在运营的（7 路）
        val favorites = setOf("034_L6_5", "034_L7_5")

        val rows = rowsOf("034", stop, favorites)

        assertEquals(
            listOf("6路", "2路", "4路", "7路", "1路", "3路", "5路"),
            rows.map { it.lineName }
        )
        assertTrue(rows[0].isFavorite)
        assertTrue(rows[3].isFavorite)
    }

    // ================================================================ 选线 / 收藏 / 一起等

    @Test
    fun testSelecting_unlimitedLines() = runTest(main.dispatcher) {
        val many = NearbyStop(
            stopId = "stop_001",
            name = "大站",
            physicalStId = "phy_01",
            lines = (1..12).map { i ->
                NearbyStopLineStatus(line = line("L$i", "${i}路", "终点$i"), targetOrder = i, targetStopId = "s$i", targetStopName = "大站")
            }
        )
        fakeRepository.stopDetailResult = Result.success(listOf(many))
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.uiState.value.rows.forEach { viewModel.onEvent(StopDetailEvent.ToggleLine(it.key)) }

        assertTrue(viewModel.uiState.value.selecting)
        assertEquals(12, viewModel.uiState.value.selectedLineKeys.size)

        // 再点一次取消勾
        viewModel.onEvent(StopDetailEvent.ToggleLine(viewModel.uiState.value.rows.first().key))
        assertEquals(11, viewModel.uiState.value.selectedLineKeys.size)
    }

    @Test
    fun testStartSelectingWith_checksThatRow() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val rows = viewModel.uiState.value.rows

        viewModel.onEvent(StopDetailEvent.StartSelectingWith(rows[0].key))
        assertTrue(viewModel.uiState.value.selecting)
        assertEquals(setOf(rows[0].key), viewModel.uiState.value.selectedLineKeys)

        // 不能选的行：只进选线模式，不勾
        viewModel.onEvent(StopDetailEvent.CancelSelecting)
        viewModel.onEvent(StopDetailEvent.StartSelectingWith(rows[2].key))
        assertTrue(viewModel.uiState.value.selecting)
        assertTrue(viewModel.uiState.value.selectedLineKeys.isEmpty())
    }

    @Test
    fun testSelecting_unselectableRowIgnored_andPlatformChangeClears() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val rows = viewModel.uiState.value.rows

        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.onEvent(StopDetailEvent.ToggleLine(rows[2].key))
        assertTrue(viewModel.uiState.value.selectedLineKeys.isEmpty())

        viewModel.onEvent(StopDetailEvent.ToggleLine(rows[0].key))
        assertEquals(1, viewModel.uiState.value.selectedLineKeys.size)

        viewModel.onEvent(StopDetailEvent.SelectPlatform(1))
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.selecting)
        assertTrue(viewModel.uiState.value.selectedLineKeys.isEmpty())
    }

    @Test
    fun testHandleBack_exitsSelectingFirst() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertFalse(viewModel.handleBack())
        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.onEvent(StopDetailEvent.ToggleLine(viewModel.uiState.value.rows.first().key))
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.selecting)
        assertTrue(viewModel.uiState.value.selectedLineKeys.isEmpty())
    }

    @Test
    fun testSaveSelectionAsRoute_savesOneLegRouteOnce() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val rows = viewModel.uiState.value.rows

        repeat(2) {
            viewModel.onEvent(StopDetailEvent.StartSelecting)
            viewModel.onEvent(StopDetailEvent.ToggleLine(rows[1].key))
            viewModel.onEvent(StopDetailEvent.ToggleLine(rows[0].key))
            viewModel.onEvent(StopDetailEvent.SaveSelectionAsRoute)
            testScheduler.runCurrent()
        }

        val route = favorites.routes.value.single()
        assertTrue(WatchTogetherRoute.isWatchRoute(route))
        assertEquals("watch_034_stop_001_line_49-line_71", route.id)
        assertEquals("人民广场（广东路） 一起等 49路、71路", route.name)
        val leg = route.legs.single()
        assertEquals("stop_001", leg.fromStopId)
        assertEquals("", leg.toStopName)
        // 按行的顺序，不按点的顺序
        assertEquals(listOf("line_49", "line_71"), leg.candidateLines.map { it.lineId })
        assertEquals(listOf(2, 6), leg.candidateLines.map { it.fromStopOrder })
        assertEquals("起点", leg.candidateLines[1].startStopName)
        assertFalse(viewModel.uiState.value.selecting)
        assertEquals("已收藏，首页点一下就能一起等", viewModel.userMessages.first())
    }

    @Test
    fun testSaveSelectionAsRoute_nothingSelected_doesNothing() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.onEvent(StopDetailEvent.SaveSelectionAsRoute)
        testScheduler.runCurrent()
        assertTrue(favorites.routes.value.isEmpty())
        assertTrue(viewModel.uiState.value.selecting)
    }

    @Test
    fun testStartWatching_startsWatchTogetherWithSelectedLines() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val rows = viewModel.uiState.value.rows

        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.onEvent(StopDetailEvent.ToggleLine(rows[0].key))
        viewModel.onEvent(StopDetailEvent.ToggleLine(rows[1].key))
        viewModel.onEvent(StopDetailEvent.StartWatching)
        testScheduler.runCurrent()

        val target = assertIs<TrackingTarget.WatchTogether>(controller.started.single())
        assertEquals("034", target.cityId)
        assertEquals("上海", target.cityName)
        assertEquals("stop_001", target.stopId)
        assertEquals("phy_01", target.physicalStId)
        assertEquals(31.23, target.stopLat)
        assertEquals(
            listOf(
                WatchLine("line_49", "49路", 2, "上海体育馆", 0, DataSourceType.CHELAILE, "stop_001"),
                WatchLine("line_71", "71路", 6, "上海体育馆", 0, DataSourceType.CHELAILE, "stop_001_71")
            ),
            target.lines
        )
        assertFalse(viewModel.uiState.value.selecting)
        // 进行中视图接上
        assertEquals(2, viewModel.uiState.value.watch?.lineCount)
    }

    @Test
    fun testStartWatching_failure_keepsSelectingAndShowsMessage() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        controller.startResult = false

        viewModel.onEvent(StopDetailEvent.StartSelecting)
        viewModel.onEvent(StopDetailEvent.ToggleLine(viewModel.uiState.value.rows[0].key))
        viewModel.onEvent(StopDetailEvent.StartWatching)

        assertTrue(viewModel.uiState.value.selecting)
        assertEquals("没能开始一起等，请再试一次", viewModel.userMessages.first())
    }

    // ================================================================ 一起等进行中

    private fun watchHere(vararg lines: WatchLine, stopId: String = "stop_001") = TrackingTarget.WatchTogether(
        cityId = "034",
        stopId = stopId,
        stopName = "人民广场（广东路）",
        lines = lines.toList()
    )

    private val w49 = WatchLine("line_49", "49路", 2, "上海体育馆")
    private val w71 = WatchLine("line_71", "71路", 6, "上海体育馆")

    @Test
    fun testWatchInProgress_mapsWatchedLinesInArrivalOrder() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val session = assertNotNull(repo.start(watchHere(w49, w71)))
        repo.update(session.sessionId, session.generation) {
            it.copy(
                watchedLines = listOf(
                    WatchedLine(line = w71, busId = "b71", plate = "沪A·71001", stopsRemaining = 1, etaSeconds = 60, hasData = true),
                    WatchedLine(line = w49, busId = "b49", stopsRemaining = 4, etaSeconds = 600, hasData = true)
                )
            )
        }
        testScheduler.runCurrent()

        val watch = assertNotNull(viewModel.uiState.value.watch)
        assertFalse(watch.isOnBoard)
        assertEquals(2, watch.lineCount)
        assertEquals(listOf("71路", "49路"), watch.lines.map { it.lineName })
        val first = watch.lines.first()
        assertEquals("1", first.arrival.value)
        assertEquals("还有 1 站 · 沪A·71001", first.detail)
        assertEquals("上海体育馆", first.endStop)
    }

    @Test
    fun testWatchInProgress_onBoardView_andStop() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val session = assertNotNull(repo.start(watchHere(w49, w71)))
        repo.update(session.sessionId, session.generation) {
            it.copy(
                watchedLines = emptyList(),
                state = it.state.copy(legPhase = LegPhase.ON_BOARD, lineName = "71路", stopsRemaining = 5)
            )
        }
        testScheduler.runCurrent()

        val watch = assertNotNull(viewModel.uiState.value.watch)
        assertTrue(watch.isOnBoard)
        assertEquals("已上 71路", watch.boardedTitle)
        assertEquals("还有 5 站到终点", watch.boardedDetail)

        viewModel.onEvent(StopDetailEvent.StopWatching)
        testScheduler.runCurrent()
        assertEquals(1, controller.stopCount)
        assertNull(viewModel.uiState.value.watch)
    }

    @Test
    fun testWatchInProgress_otherStopNotShown() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        repo.start(watchHere(w49, stopId = "far_away"))
        testScheduler.runCurrent()

        assertNull(viewModel.uiState.value.watch)
    }

    @Test
    fun testWatchInProgress_alignsToWatchedPlatform() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        repo.start(watchHere(WatchLine("line_18", "18路", 5, "世博会博物馆"), stopId = "stop_002"))
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.selectedStopIndex)
        assertNotNull(viewModel.uiState.value.watch)
    }

    @Test
    fun testWatchInProgress_autoPollingSkipsRequests_resumesAfterStop() = runTest(main.dispatcher) {
        val tracking = useRealTracking()
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = newViewModel(realtime = repo, enableAutoPolling = true)
        testScheduler.runCurrent()
        tracking.start(watchHere(w49))
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(30_000L)
        testScheduler.runCurrent()
        assertEquals(1, repo.stopDetailPhysicalIds.size)

        // 下拉照样刷新
        viewModel.onEvent(StopDetailEvent.PullRefresh)
        testScheduler.runCurrent()
        assertEquals(2, repo.stopDetailPhysicalIds.size)

        // 结束一起等：马上补刷一次，之后照常轮询
        viewModel.onEvent(StopDetailEvent.StopWatching)
        testScheduler.runCurrent()
        assertEquals(3, repo.stopDetailPhysicalIds.size)
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(4, repo.stopDetailPhysicalIds.size)

        viewModel.pausePolling()
    }

    @Test
    fun testAddLine_preselectsWatchedLines() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        repo.start(watchHere(w71))
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.AddLine)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.selecting)
        assertEquals(setOf(state.rows[1].key), state.selectedLineKeys)
    }

    @Test
    fun testConfirmBoarded_forwardsToController() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.ConfirmBoarded)
        assertEquals(1, controller.boardedCount)
    }

    // ================================================================ 「上了哪辆？」

    @Test
    fun testBoardChoice_showChooseNotYetAndDismiss() = runTest(main.dispatcher) {
        val repo = useRealTracking()
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val session = assertNotNull(repo.start(watchHere(w49, w71)))
        val choice = BoardChoice(
            options = listOf(
                BoardOption(lineId = "line_49", lineName = "49路", busId = "b49", plate = "沪A·49001"),
                BoardOption(lineId = "line_71", lineName = "71路", busId = "b71", plate = "沪A·71001")
            ),
            sinceMillis = 1_000L
        )
        repo.update(session.sessionId, session.generation) { it.copy(boardChoice = choice) }
        testScheduler.runCurrent()

        val ui = assertNotNull(viewModel.uiState.value.boardChoice)
        assertEquals("两辆车都在 人民广场（广东路）", ui.subtitle)
        assertEquals(listOf("b49", "b71"), ui.options.map { it.busId })
        assertEquals("上海体育馆", ui.options[1].endStop)

        viewModel.onEvent(StopDetailEvent.ChooseBoardedBus("b71"))
        viewModel.onEvent(StopDetailEvent.ChooseBoardedBus(null))
        assertEquals(listOf("b71", null), controller.boardChoices)

        // 划掉：这次待选不再弹；新的待选照样弹
        repo.update(repo.session.value!!.sessionId, repo.session.value!!.generation) { it.copy(boardChoice = choice) }
        testScheduler.runCurrent()
        viewModel.onEvent(StopDetailEvent.DismissBoardChoice)
        assertNull(viewModel.uiState.value.boardChoice)
        repo.update(repo.session.value!!.sessionId, repo.session.value!!.generation) { it.copy(boardChoice = choice.copy(sinceMillis = 2_000L)) }
        testScheduler.runCurrent()
        assertNotNull(viewModel.uiState.value.boardChoice)
    }

    // ================================================================ 乘车码 / 下拉刷新

    @Test
    fun testTransitCode_defaultProviderOpensDirectly() = runTest(main.dispatcher) {
        settings = FakeSettingsRepository(defaultCity, initialTransitCodeProvider = TransitCodeProvider.ALIPAY)
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.OpenTransitCode)
        assertEquals(listOf(TransitCodeProvider.ALIPAY), launcher.opened)
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)

        launcher.installed = emptySet()
        viewModel.onEvent(StopDetailEvent.OpenTransitCode)
        assertEquals("没能打开乘车码，请确认装了对应 App", viewModel.userMessages.first())
    }

    @Test
    fun testTransitCode_askAlwaysOpensSheet_pickSavesDefault() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(StopDetailEvent.OpenTransitCode)
        assertTrue(viewModel.uiState.value.isTransitCodeSheetOpen)
        assertTrue(launcher.opened.isEmpty())

        viewModel.onEvent(StopDetailEvent.PickTransitCode(TransitCodeProvider.UNIONPAY, saveAsDefault = true))
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)
        assertEquals(listOf(TransitCodeProvider.UNIONPAY), launcher.opened)
        assertEquals(TransitCodeProvider.UNIONPAY, settings.preferredTransitCodeProvider.value)
    }

    /** 「显示乘车码按钮」开关和首页、线路页共用：关掉后站点页底栏也不画 */
    @Test
    fun testShowTransitCodeButton_followsGlobalSetting() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showTransitCodeButton)

        settings.setShowTransitCodeButton(false)
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.showTransitCodeButton)
    }

    @Test
    fun testPullRefresh_showsIndicatorUntilDone() = runTest(main.dispatcher) {
        val repo = CountingStopRepository(fakeRepository)
        val viewModel = newViewModel(realtime = repo, enableAutoPolling = false)
        testScheduler.runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.gate = gate

        viewModel.onEvent(StopDetailEvent.PullRefresh)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPullRefreshing)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isPullRefreshing)
        assertEquals(2, repo.stopDetailPhysicalIds.size)
    }
}
