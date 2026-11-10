package com.trackr.feature.bus.home

import com.trackr.core.data.repository.DefaultLocationRepository
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.GroupColor
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.UserFix
import com.trackr.core.testing.FakeAnnouncer
import com.trackr.core.testing.FakeClock
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeLocationRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeSystemSettingsLauncher
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeTransitCodeLauncher
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 第 17 步：HomeViewModel 改拿拆开的接口，这里把几个 Fake 放在一起，保留原测试里 `fakeRepository.xxx` 的写法
 * （转发方式同旧 FakeTransitRepository）。
 */
private class HomeFakes(city: TransitCity, val favoritesRepo: FakeFavoritesRepository = FakeFavoritesRepository()) {
    val settings = FakeSettingsRepository(city)
    val realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
    val userLocationStore = FakeUserLocationStore()
    val location = FakeLocationRepository()

    val selectedCity get() = settings.selectedCity
    val userLocation get() = userLocationStore.userLocation
    fun setHasSavedCity(saved: Boolean) = settings.setHasSavedCity(saved)
    fun setNearbyStopsCount(count: Int) = settings.setNearbyStopsCount(count)
    suspend fun toggleFavorite(item: FavoriteStationLine) = favoritesRepo.toggleFavorite(item)

    var supportedCitiesResult: Result<List<TransitCity>> by realtime::supportedCitiesResult
    var nearbyStopsResult: Result<List<NearbyStop>> by realtime::nearbyStopsResult
    var resolveCityResult: Result<TransitCity?>? by realtime::resolveCityResult
    val nearbyStopsCalls get() = realtime.nearbyStopsCalls
    var nearbyStopsResultProvider: ((TransitCity, Double, Double) -> Result<List<NearbyStop>>)? by realtime::nearbyStopsResultProvider
}

@OptIn(ExperimentalCoroutinesApi::class)
class BusHomeViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var fakeRepository: HomeFakes
    private lateinit var trackingRepository: DefaultTrackingRepository
    private lateinit var trackingController: FakeTrackingController
    private val transitCodeLauncher = FakeTransitCodeLauncher()
    private val systemSettings = FakeSystemSettingsLauncher()
    private val shanghai = TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", province = "直辖市")
    private val beijing = TransitCity(cityId = "001", cityName = "北京", pinyin = "beijing", province = "直辖市")
    private val hangzhou = TransitCity(cityId = "0571", cityName = "杭州", pinyin = "hangzhou", province = "浙江省")

    @BeforeTest
    fun setup() {
        main.install()
        fakeRepository = HomeFakes(shanghai)
        fakeRepository.supportedCitiesResult = Result.success(listOf(shanghai, beijing, hangzhou))
        trackingRepository = DefaultTrackingRepository(
            InMemoryTrackingSessionStore(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        trackingController = FakeTrackingController(trackingRepository)
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun createViewModel(
        enableAutoPolling: Boolean = false,
        elapsedRealtimeMillis: (() -> Long)? = null,
        announcer: FakeAnnouncer = FakeAnnouncer()
    ): BusHomeViewModel {
        val f = fakeRepository
        return if (elapsedRealtimeMillis == null) {
            BusHomeViewModel(
                settings = f.settings,
                favorites = f.favoritesRepo,
                realtime = f.realtime,
                userLocationStore = f.userLocationStore,
                locationRepository = f.location,
                trackingController = trackingController,
                trackingRepository = trackingRepository,
                transitCodeLauncher = transitCodeLauncher,
                systemSettings = systemSettings,
                enableAutoPolling = enableAutoPolling,
                announcer = announcer
            )
        } else {
            BusHomeViewModel(
                settings = f.settings,
                favorites = f.favoritesRepo,
                realtime = f.realtime,
                userLocationStore = f.userLocationStore,
                locationRepository = f.location,
                trackingController = trackingController,
                trackingRepository = trackingRepository,
                transitCodeLauncher = transitCodeLauncher,
                systemSettings = systemSettings,
                enableAutoPolling = enableAutoPolling,
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                announcer = announcer
            )
        }
    }

    /** 原 Robolectric 的「授予权限 + GPS 新鲜缓存 3 m」：设备定位直接给出这个坐标 */
    private fun grantFreshGpsLocation(lat: Double, lng: Double) {
        fakeRepository.location.bestFixResult = UserFix(lat = lat, lng = lng, accuracyMeters = 3.0f, elapsedMillis = 1_000_000L)
    }

    @Test
    fun testInitialState_loadsSelectedCityAndObservesRepository() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals("上海", viewModel.uiState.value.currentCity.cityName)
        assertEquals("034", viewModel.uiState.value.currentCity.cityId)
    }

    @Test
    fun testSelectCity_updatesCurrentCityInRepositoryAndState() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.selectCity(beijing)
        testScheduler.runCurrent()

        assertEquals("北京", viewModel.uiState.value.currentCity.cityName)
        assertEquals("001", fakeRepository.selectedCity.value.cityId)
    }

    @Test
    fun testFilterCities_queryMatchesCityNameAndPinyin() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.setCityPickerOpen(true)
        testScheduler.runCurrent()

        assertEquals(3, viewModel.uiState.value.cities.size)

        // Filter by pinyin prefix "hangz" (since "shanghai" contains "hang")
        viewModel.updateCitySearchQuery("hangz")
        val filteredPinyin = viewModel.uiState.value.filteredCities
        assertEquals(1, filteredPinyin.size)
        assertEquals("杭州", filteredPinyin.first().cityName)

        // Filter by unique city character "京"
        viewModel.updateCitySearchQuery("京")
        val filteredName = viewModel.uiState.value.filteredCities
        assertEquals(1, filteredName.size)
        assertEquals("北京", filteredName.first().cityName)
    }

    @Test
    fun testFavorites_observesRepositoryChanges() = runTest(main.dispatcher) {
        val fav = FavoriteStationLine(
            id = "fav_1",
            cityId = "034",
            cityName = "上海",
            lineId = "l1",
            lineName = "49路",
            direction = 0,
            startStop = "A",
            endStop = "B",
            targetStopId = "s1",
            targetStopName = "人民广场",
            targetOrder = 2,
            source = DataSourceType.CHELAILE
        )

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        fakeRepository.toggleFavorite(fav)
        testScheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.favorites.size)
        assertEquals("人民广场", viewModel.uiState.value.favorites.first().targetStopName)
    }

    @Test
    fun testTransitCodeButtonVisibility_followsSetting() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        // 默认显示
        assertTrue(viewModel.uiState.value.showTransitCodeButton)

        fakeRepository.settings.setShowTransitCodeButton(false)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.showTransitCodeButton)

        fakeRepository.settings.setShowTransitCodeButton(true)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.showTransitCodeButton)
    }

    @Test
    fun testEngineModeSwitching() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals(EngineMode.CHELAILE, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)

        viewModel.toggleEngineMode()
        testScheduler.runCurrent()
        assertEquals(EngineMode.CHELAILE, viewModel.uiState.value.engineMode)

        viewModel.setEngineMode(EngineMode.MYGOLBS)
        testScheduler.runCurrent()
        assertEquals(EngineMode.MYGOLBS, viewModel.uiState.value.engineMode)
    }

    @Test
    fun testEngineModeSwitching_immediatelyRefreshesNearbyStops() = runTest(main.dispatcher) {
        val stopA = NearbyStop(stopId = "stop_a", name = "站点A")
        val stopB = NearbyStop(stopId = "stop_b", name = "站点B")
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopA))

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals("站点A", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)

        // 切换引擎模式前更新 mock 数据，模拟切换后数据源的数据
        fakeRepository.nearbyStopsResult = Result.success(listOf(stopB))
        viewModel.setEngineMode(EngineMode.MYGOLBS)
        testScheduler.runCurrent()

        // 验证无需重启，周边站点即刻被重新拉取并更新为站点B
        assertEquals("站点B", viewModel.uiState.value.nearbyStops.firstOrNull()?.name)
    }

    @Test
    fun testNearbyStops_alwaysRequestsSixStops_ignoringOldSetting() = runTest(main.dispatcher) {
        // v3：附近站固定 6 个，「附近站数」设置不再影响首页，改了也不重新请求
        fakeRepository.nearbyStopsResult = Result.success(listOf(NearbyStop(stopId = "stop_1", name = "站点1")))

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val callsBefore = fakeRepository.nearbyStopsCalls.size

        fakeRepository.setNearbyStopsCount(8)
        testScheduler.runCurrent()

        assertEquals(callsBefore, fakeRepository.nearbyStopsCalls.size)
        assertTrue(fakeRepository.realtime.nearbyStopsLimits.isNotEmpty())
        assertTrue(fakeRepository.realtime.nearbyStopsLimits.all { it == BusHomeViewModel.NEARBY_STOPS_LIMIT })
        assertEquals(6, BusHomeViewModel.NEARBY_STOPS_LIMIT)
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)
    }

    @Test
    fun testFilteredCities_filtersByEngineMode() = runTest(main.dispatcher) {
        val clCity = TransitCity(cityId = "034", cityName = "上海", mygolbsCityCode = null)
        val mgCity = TransitCity(cityId = "", cityName = "玉林", mygolbsCityCode = "450900")
        val bothCity = TransitCity(cityId = "051", cityName = "深圳", mygolbsCityCode = "440300")
        fakeRepository.supportedCitiesResult = Result.success(listOf(clCity, mgCity, bothCity))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        // 1. CHELAILE 模式包含车来了城市 (clCity, bothCity)
        viewModel.setEngineMode(EngineMode.CHELAILE)
        testScheduler.runCurrent()
        assertEquals(2, viewModel.uiState.value.filteredCities.size)

        // 2. MYGOLBS 模式只包含有 mygolbsCityCode 的城市 (mgCity, bothCity)
        viewModel.setEngineMode(EngineMode.MYGOLBS)
        testScheduler.runCurrent()
        val mgList = viewModel.uiState.value.filteredCities
        assertEquals(2, mgList.size)
        assertTrue(mgList.any { it.cityName == "玉林" })
        assertTrue(mgList.any { it.cityName == "深圳" })

        // 3. CHELAILE 模式只包含有 cityId 的城市 (clCity, bothCity)
        viewModel.setEngineMode(EngineMode.CHELAILE)
        testScheduler.runCurrent()
        val clList = viewModel.uiState.value.filteredCities
        assertEquals(2, clList.size)
        assertTrue(clList.any { it.cityName == "上海" })
        assertTrue(clList.any { it.cityName == "深圳" })
    }

    @Test
    fun testRefresh_triggersRelocationAndRefreshesData() = runTest(main.dispatcher) {
        grantFreshGpsLocation(30.2741, 120.1551)

        val dummyStop = NearbyStop(
            stopId = "stop_hz_1",
            name = "武林广场",
            distanceMeters = 100,
            lines = emptyList()
        )
        fakeRepository.nearbyStopsResult = Result.success(listOf(dummyStop))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.refresh()
        testScheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLocating)
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)
        assertEquals("武林广场", viewModel.uiState.value.nearbyStops.first().name)
        // 验证用户定位坐标被同步更新到 repository
        assertEquals(30.2741, fakeRepository.userLocation.value?.first ?: 0.0, absoluteTolerance = 0.0001)
        assertEquals(120.1551, fakeRepository.userLocation.value?.second ?: 0.0, absoluteTolerance = 0.0001)
        // 启动 + 下拉刷新都按强制刷新取定位（原用例靠 Robolectric 定位缓存年龄间接覆盖）
        assertEquals(listOf(true, true), fakeRepository.location.bestFixCalls)
    }

    @Test
    fun testRefreshLocationAndNearby_delegatesToRefresh() = runTest(main.dispatcher) {
        val dummyStop = NearbyStop(
            stopId = "stop_sh_1",
            name = "人民广场",
            distanceMeters = 50,
            lines = emptyList()
        )
        fakeRepository.nearbyStopsResult = Result.success(listOf(dummyStop))

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.refreshLocationAndNearby()
        testScheduler.runCurrent()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertEquals(false, viewModel.uiState.value.isLocating)
        assertEquals(1, viewModel.uiState.value.nearbyStops.size)
        assertEquals("人民广场", viewModel.uiState.value.nearbyStops.first().name)
    }

    @Test
    fun testStartupLocate_keepsSavedCity_whenLocatedCityDiffers() = runTest(main.dispatcher) {
        // Issue #6：已选城市（上海），定位到另一个城市（杭州），重启后不应被覆盖
        grantFreshGpsLocation(30.2741, 120.1551)
        fakeRepository.resolveCityResult = Result.success(hangzhou)

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals("034", fakeRepository.selectedCity.value.cityId)
        assertEquals("上海", viewModel.uiState.value.currentCity.cityName)
        assertEquals("杭州", viewModel.uiState.value.locatedCity?.cityName)
        assertEquals(30.2741, fakeRepository.userLocation.value?.first ?: 0.0, absoluteTolerance = 0.0001)
    }

    @Test
    fun testPullToRefresh_keepsSavedCity() = runTest(main.dispatcher) {
        grantFreshGpsLocation(30.2741, 120.1551)
        fakeRepository.resolveCityResult = Result.success(hangzhou)

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        viewModel.refresh()
        testScheduler.runCurrent()

        assertEquals("034", fakeRepository.selectedCity.value.cityId)
        assertEquals("上海", viewModel.uiState.value.currentCity.cityName)
    }

    @Test
    fun testStartupLocate_switchesToLocatedCity_whenNoCitySavedYet() = runTest(main.dispatcher) {
        grantFreshGpsLocation(30.2741, 120.1551)
        fakeRepository.setHasSavedCity(false)
        fakeRepository.resolveCityResult = Result.success(hangzhou)

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals("0571", fakeRepository.selectedCity.value.cityId)
        assertEquals("杭州", viewModel.uiState.value.currentCity.cityName)
    }

    @Test
    fun testRelocateFromCityPicker_switchesToLocatedCity() = runTest(main.dispatcher) {
        grantFreshGpsLocation(30.2741, 120.1551)
        fakeRepository.resolveCityResult = Result.success(hangzhou)

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals("上海", viewModel.uiState.value.currentCity.cityName)

        viewModel.refreshLocationAndNearby(switchCity = true)
        testScheduler.runCurrent()

        assertEquals("0571", fakeRepository.selectedCity.value.cityId)
        assertEquals("杭州", viewModel.uiState.value.currentCity.cityName)
    }

    @Test
    fun testStartupLocate_awayFromSavedCity_fallsBackToSavedCityCenter() = runTest(main.dispatcher) {
        // 人在杭州，已选上海：GPS 坐标查不到上海站点，改用上海中心点再查
        grantFreshGpsLocation(30.2741, 120.1551)
        fakeRepository.resolveCityResult = Result.success(hangzhou)
        val center = assertNotNull(ProvinceCityCatalog.findCityCenterCoordinates("上海"))
        val centerStop = NearbyStop(stopId = "sh_center", name = "人民广场", distanceMeters = 0, lines = emptyList())
        fakeRepository.nearbyStopsResultProvider = { _, lat, lng ->
            if (lat == center.first && lng == center.second) Result.success(listOf(centerStop))
            else Result.success(emptyList())
        }

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        assertEquals("034", fakeRepository.selectedCity.value.cityId)
        assertEquals(listOf("人民广场"), viewModel.uiState.value.nearbyStops.map { it.name })
        assertEquals(center.first, viewModel.uiState.value.currentLat, absoluteTolerance = 0.0001)
        assertEquals(center.second, viewModel.uiState.value.currentLng, absoluteTolerance = 0.0001)
    }

    @Test
    fun testStopTracking_callsControllerAndActiveTrackingBecomesInactive() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        trackingController.start(
            TrackingTarget.SingleLine(
                cityId = "034",
                lineId = "line_71",
                lineName = "71路",
                targetStopId = "stop_5",
                targetStopName = "人民广场",
                targetOrder = 5,
                totalStops = 20
            )
        )
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.activeTracking.isActive)
        assertEquals("line_71", viewModel.uiState.value.activeTracking.lineId)

        viewModel.stopTracking(assertNotNull(trackingRepository.session.value).sessionId)
        testScheduler.runCurrent()

        assertEquals(1, trackingController.stopCount)
        assertFalse(trackingRepository.legState.value.isActive)
        assertFalse(viewModel.uiState.value.activeTracking.isActive)
    }

    @Test
    fun testPollingLifecycle_pauseResumeAndExplicitControl() = runTest(main.dispatcher) {
        val vmWithAutoPolling = createViewModel(enableAutoPolling = true)
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
    fun testSetPollingActive_resumeAfterOneInterval_refreshesImmediately() = runTest(main.dispatcher) {
        val clock = FakeClock()
        val viewModel = createViewModel(elapsedRealtimeMillis = clock::elapsedMillis)
        testScheduler.runCurrent()
        val callsBefore = fakeRepository.nearbyStopsCalls.size

        // 离开首页超过一个轮询周期后回来：立即补刷一次，而不是再等 15 秒
        clock.advanceBy((BusHomeViewModel.POLLING_INTERVAL_MS + 1).milliseconds)
        viewModel.setPollingActive(true)
        testScheduler.runCurrent()

        assertEquals(callsBefore + 1, fakeRepository.nearbyStopsCalls.size)
        viewModel.setPollingActive(false)
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testSetPollingActive_resumeAtExactlyOneInterval_refreshesImmediately() = runTest(main.dispatcher) {
        val clock = FakeClock()
        val viewModel = createViewModel(elapsedRealtimeMillis = clock::elapsedMillis)
        testScheduler.runCurrent()
        val callsBefore = fakeRepository.nearbyStopsCalls.size

        // 边界：距上次刷新正好一个轮询周期（15000 ms）也算到期，补刷一次
        clock.advanceBy(BusHomeViewModel.POLLING_INTERVAL_MS.milliseconds)
        viewModel.setPollingActive(true)
        testScheduler.runCurrent()

        assertEquals(callsBefore + 1, fakeRepository.nearbyStopsCalls.size)
        viewModel.setPollingActive(false)
        assertFalse(viewModel.isPollingActive)
    }

    @Test
    fun testSetPollingActive_resumeWithinInterval_doesNotRefreshImmediately() = runTest(main.dispatcher) {
        val clock = FakeClock()
        val viewModel = createViewModel(elapsedRealtimeMillis = clock::elapsedMillis)
        testScheduler.runCurrent()
        val callsBefore = fakeRepository.nearbyStopsCalls.size

        clock.advanceBy(1_000L.milliseconds)
        viewModel.setPollingActive(true)
        testScheduler.runCurrent()

        assertEquals(callsBefore, fakeRepository.nearbyStopsCalls.size)
        viewModel.setPollingActive(false)
    }

    @Test
    fun testRefresh_whenNoUsableFix_showsLocationErrorAndKeepsPosition() = runTest(main.dispatcher) {
        // 原 testRefresh_whenCachedLocationOlderThanTenMinutes_isRejected 的 VM 部分：
        // 「超 10 分钟的缓存不用」由 LocationRepositoryTest.staleLocationOlderThanTenMinutes_isRejected 覆盖，
        // 这里只验证定位仓库给不出定位时首页的表现
        fakeRepository.location.bestFixResult = null

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()

        assertNull(fakeRepository.userLocation.value)
        assertEquals(LocationIssue.Unavailable, viewModel.uiState.value.locationIssue)
        assertFalse(viewModel.uiState.value.isLocating)
    }

    /** issue #34：权限和系统开关都没问题，只是这一轮没取到 */
    @Test
    fun testRefresh_noFixWithPermissionAndServiceOn_reportsUnavailable() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()

        assertEquals(LocationIssue.Unavailable, viewModel.uiState.value.locationIssue)
    }

    /** issue #34：定位权限没给 → 提示说权限，按钮去应用详情页 */
    @Test
    fun testRefresh_noPermission_reportsPermissionMissingAndOpensAppSettings() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null
        fakeRepository.location.permissionGranted = false

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()

        assertEquals(LocationIssue.PermissionMissing, viewModel.uiState.value.locationIssue)

        viewModel.onEvent(BusHomeEvent.OpenAppSettings)
        assertEquals(listOf("app"), systemSettings.opened)
    }

    /** issue #34 的主场景：权限给了，但手机「位置信息」总开关关着 → 提示说服务，按钮去位置信息页 */
    @Test
    fun testRefresh_permissionButServiceDisabled_reportsServiceDisabledAndOpensLocationSettings() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null
        fakeRepository.location.systemLocationEnabled = false

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()

        assertEquals(LocationIssue.ServiceDisabled, viewModel.uiState.value.locationIssue)

        viewModel.onEvent(BusHomeEvent.OpenLocationSettings)
        assertEquals(listOf("location"), systemSettings.opened)
    }

    /** 没授权时就算系统总开关也关着，先说的是权限（否则用户被指去开一个不该先开的开关） */
    @Test
    fun testRefresh_bothMissing_reportsPermissionFirst() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null
        fakeRepository.location.permissionGranted = false
        fakeRepository.location.systemLocationEnabled = false

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()

        assertEquals(LocationIssue.PermissionMissing, viewModel.uiState.value.locationIssue)
    }

    /** 回到前台时，上次失败的原因被用户补上了，就静默重试一次（用户多半刚从系统设置里开了定位） */
    @Test
    fun testRetryLocationIfFailed_afterServiceTurnedOn_relocatesAndClearsIssue() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null
        fakeRepository.location.systemLocationEnabled = false

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()
        assertEquals(LocationIssue.ServiceDisabled, viewModel.uiState.value.locationIssue)
        val callsBefore = fakeRepository.location.bestFixCalls.size

        // 用户在系统设置里开了「位置信息」，切回 App
        fakeRepository.location.systemLocationEnabled = true
        grantFreshGpsLocation(31.2, 121.4)
        viewModel.retryLocationIfFailed()
        testScheduler.runCurrent()

        assertTrue(fakeRepository.location.bestFixCalls.size > callsBefore)
        assertNull(viewModel.uiState.value.locationIssue)
        assertEquals(31.2, fakeRepository.userLocation.value?.first)
    }

    /** 原因没变就不重试：不然每次从搜索页返回都空转 4.5 秒定位、再打一次附近站 */
    @Test
    fun testRetryLocationIfFailed_sameIssue_doesNotRelocate() = runTest(main.dispatcher) {
        fakeRepository.location.bestFixResult = null
        fakeRepository.location.systemLocationEnabled = false

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(DefaultLocationRepository.LOCATION_WINDOW_MS + 500L)
        testScheduler.runCurrent()
        val callsBefore = fakeRepository.location.bestFixCalls.size

        viewModel.retryLocationIfFailed()
        testScheduler.runCurrent()

        assertEquals(callsBefore, fakeRepository.location.bestFixCalls.size)
    }

    /** 没有失败过就不重试：每次回前台都定位一次会白烧电 */
    @Test
    fun testRetryLocationIfFailed_withoutFailure_doesNotRelocate() = runTest(main.dispatcher) {
        grantFreshGpsLocation(31.2, 121.4)

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.locationIssue)
        val callsBefore = fakeRepository.location.bestFixCalls.size

        viewModel.retryLocationIfFailed()
        testScheduler.runCurrent()

        assertEquals(callsBefore, fakeRepository.location.bestFixCalls.size)
    }

    // ---- 第 19 步新加 ----

    private fun fav(id: String, line: String, stop: String) = FavoriteStationLine(
        id = id,
        cityId = "034",
        cityName = "上海",
        lineId = "l_$id",
        lineName = line,
        startStop = "白石洲",
        endStop = "科技园",
        targetStopId = "s_$id",
        targetStopName = stop,
        targetOrder = 5
    )

    @Test
    fun testFavorites_stopsAndRoutesInOneList_groupChipsFilter() = runTest(main.dispatcher) {
        val route = TransitGroup(
            id = "r1",
            name = "家 → 公司",
            legs = listOf(
                TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "换乘站", candidateLines = listOf(CandidateLine(cityId = "034", lineId = "a", lineName = "325"))),
                TransitLeg(legIndex = 1, fromStopName = "换乘站", toStopName = "公司", candidateLines = listOf(CandidateLine(cityId = "034", lineId = "b", lineName = "19")))
            )
        )
        val work = FavoriteGroup(id = "g_work", name = "上班")
        val repo = FakeFavoritesRepository(
            initialFavorites = listOf(fav("f1", "325", "科技园站"), fav("f2", "M194", "深大北门")),
            initialRoutes = listOf(route),
            initialGroups = listOf(work)
        )
        repo.setGroup(FavoriteItem.stopKey("f2"), "g_work")
        repo.setGroup(FavoriteItem.routeKey("r1"), "g_work")
        fakeRepository = HomeFakes(shanghai, repo)

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val rows = viewModel.uiState.value.favoriteRows
        assertEquals(listOf("stop_f1", "stop_f2", "route_r1"), rows.map { it.key })
        val first = rows[0] as FavoriteRowUi.Stop
        assertEquals("科技园站上车", first.boardText)
        assertEquals("→ 科技园", first.direction)
        val routeRow = rows[2] as FavoriteRowUi.Route
        assertEquals("家 → 公司", routeRow.title)
        assertEquals("325 › 19 · 换乘 1 次", routeRow.linesText)

        viewModel.onEvent(BusHomeEvent.SelectGroup("g_work"))
        assertEquals(listOf("stop_f2", "route_r1"), viewModel.uiState.value.favoriteRows.map { it.key })

        // 分组被删了：回到「全部」
        repo.deleteGroup("g_work")
        testScheduler.runCurrent()
        assertNull(viewModel.uiState.value.selectedGroupId)
        assertEquals(3, viewModel.uiState.value.favoriteRows.size)
    }

    @Test
    fun testFavorites_groupColorChange_updatesRows() = runTest(main.dispatcher) {
        val repo = FakeFavoritesRepository(
            initialFavorites = listOf(fav("f1", "325", "科技园站"), fav("f2", "M194", "深大北门")),
            initialGroups = listOf(FavoriteGroup(id = "g_work", name = "上班"))
        )
        repo.setGroup(FavoriteItem.stopKey("f2"), "g_work")
        fakeRepository = HomeFakes(shanghai, repo)

        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertEquals(listOf<GroupColor?>(null, null), viewModel.uiState.value.favoriteRows.map { it.groupColor })

        // 编辑页给分组设了颜色：首页卡片跟着有色条
        repo.setGroupColor("g_work", GroupColor.GREEN)
        testScheduler.runCurrent()
        assertEquals(listOf(null, GroupColor.GREEN), viewModel.uiState.value.favoriteRows.map { it.groupColor })
        assertEquals(GroupColor.GREEN, viewModel.uiState.value.favoriteGroups.single().color)
    }

    @Test
    fun testFavoriteRow_noBus_showsNextDepartureFromStart() = runTest(main.dispatcher) {
        val f = fav("f1", "325", "科技园站")
        fakeRepository = HomeFakes(shanghai, FakeFavoritesRepository(initialFavorites = listOf(f)))
        val status = NearbyStopLineStatus(
            line = BusLineDirection(lineId = f.lineId, name = "325", startStop = "白石洲", endStop = "科技园"),
            targetOrder = 5,
            targetStopId = f.targetStopId,
            targetStopName = f.targetStopName,
            statusText = "等待发车",
            preArrivalTime = "07:20"
        )
        fakeRepository.realtime.favoritesRealtimeResult = Result.success(
            mapOf(f.id to NearbyStop(stopId = f.targetStopId, name = f.targetStopName, lines = listOf(status)))
        )

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val row = viewModel.uiState.value.favoriteRows.single() as FavoriteRowUi.Stop
        assertEquals("下一班 07:20", row.arrival.big)
        assertEquals("从 白石洲 发车", row.arrival.small)
    }

    @Test
    fun testPolling_unchangedRowsKeepSameInstance() = runTest(main.dispatcher) {
        fakeRepository.nearbyStopsResult = Result.success(listOf(NearbyStop(stopId = "s1", name = "站点1")))
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        val before = viewModel.uiState.value.nearbyCards

        // 再刷一次，数据一样：沿用旧列表实例（§4.1 强跳过）
        viewModel.refresh()
        testScheduler.runCurrent()
        assertTrue(before === viewModel.uiState.value.nearbyCards)
    }

    @Test
    fun testTransitCode_preferredProviderOpensDirectly() = runTest(main.dispatcher) {
        fakeRepository.settings.setPreferredTransitCodeProvider(TransitCodeProvider.ALIPAY)
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(BusHomeEvent.TransitCodeClicked)

        assertEquals(listOf(TransitCodeProvider.ALIPAY), transitCodeLauncher.opened)
        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)
    }

    @Test
    fun testTransitCode_askAlways_opensChooser_andRemembersChoice() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(BusHomeEvent.TransitCodeClicked)
        assertTrue(viewModel.uiState.value.isTransitCodeSheetOpen)
        assertTrue(transitCodeLauncher.opened.isEmpty())

        viewModel.onEvent(BusHomeEvent.TransitCodeChosen(TransitCodeProvider.UNIONPAY, saveAsDefault = true))
        testScheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isTransitCodeSheetOpen)
        assertEquals(listOf(TransitCodeProvider.UNIONPAY), transitCodeLauncher.opened)
        assertEquals(TransitCodeProvider.UNIONPAY, fakeRepository.settings.preferredTransitCodeProvider.value)
    }

    @Test
    fun testTransitCode_chooseWithoutRemember_keepsAskAlways() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.onEvent(BusHomeEvent.TransitCodeClicked)
        viewModel.onEvent(BusHomeEvent.TransitCodeChosen(TransitCodeProvider.ALIPAY, saveAsDefault = false))
        testScheduler.runCurrent()

        assertEquals(listOf(TransitCodeProvider.ALIPAY), transitCodeLauncher.opened)
        assertEquals(TransitCodeProvider.ASK_ALWAYS, fakeRepository.settings.preferredTransitCodeProvider.value)
    }

    @Test
    fun testRefresh_nearbyRequestFails_addsOneShotMessage() = runTest(main.dispatcher) {
        fakeRepository.nearbyStopsResult = Result.failure(RuntimeException("no network"))
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val message = viewModel.uiState.value.userMessages.single()
        assertEquals(BusHomeViewModel.NEARBY_FAILED, message.text)
        // 长辈首页没有下拉刷新，换成点「重新定位」的说法
        assertEquals(BusHomeViewModel.NEARBY_FAILED_ELDER, message.elderText)
        assertFalse(message.elderText.orEmpty().contains("下拉"))

        viewModel.onEvent(BusHomeEvent.MessageShown(message.id))
        assertTrue(viewModel.uiState.value.userMessages.isEmpty())
    }

    @Test
    fun testTrackingCard_singleLine_endedByStopTracking() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.trackingCards.isEmpty())

        trackingController.start(
            TrackingTarget.SingleLine(
                cityId = "034",
                lineId = "line_71",
                lineName = "71路",
                targetStopId = "stop_5",
                targetStopName = "人民广场",
                targetOrder = 5,
                totalStops = 20,
                source = DataSourceType.MYGOLBS
            )
        )
        testScheduler.runCurrent()

        val card = viewModel.uiState.value.trackingCards.single()
        assertEquals("追踪 71路 · 人民广场", card.title)
        // 长辈版「读给我听」：还没选到车时读「正在查车」
        assertEquals("71路，正在查车", card.spoken)
        assertEquals("line_71", card.open.lineId)
        assertEquals(5, card.open.targetOrder)
        // 点卡片按会话里的数据源打开线路页（旧首页写死 CHELAILE）
        assertEquals(DataSourceType.MYGOLBS, card.open.source)

        viewModel.onEvent(BusHomeEvent.StopTracking(card.sessionId))
        testScheduler.runCurrent()
        assertEquals(1, trackingController.stopCount)
        assertTrue(viewModel.uiState.value.trackingCards.isEmpty())
        assertNull(trackingRepository.groupSession.value)
    }

    @Test
    fun testTrackingCards_twoLines_stopOnlyTheOne() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        fun target(lineId: String, lineName: String, order: Int) = TrackingTarget.SingleLine(
            cityId = "034",
            lineId = lineId,
            lineName = lineName,
            targetStopId = "stop_$order",
            targetStopName = "人民广场",
            targetOrder = order,
            totalStops = 20
        )
        trackingController.start(target("line_71", "71路", 5))
        trackingController.start(target("line_59", "59路", 9))
        testScheduler.runCurrent()

        // 两项同时追：两张卡，各写自己的线路、各点进自己的线路页
        val cards = viewModel.uiState.value.trackingCards
        assertEquals(listOf("追踪 71路 · 人民广场", "追踪 59路 · 人民广场"), cards.map { it.title })
        assertEquals(listOf("line_71", "line_59"), cards.map { it.open.lineId })

        // 结束第一张：只停那一项，第二项还在
        viewModel.onEvent(BusHomeEvent.StopTracking(cards[0].sessionId))
        testScheduler.runCurrent()

        assertEquals(1, trackingController.stopCount)
        assertEquals(listOf("line_59"), viewModel.uiState.value.trackingCards.map { it.open.lineId })
        assertEquals(1, trackingRepository.sessions.value.size)
    }

    // ---- 第 33 步：读给我听 ----

    @Test
    fun testFavoriteRow_spokenSentence() = runTest(main.dispatcher) {
        val onTheWay = fav("f1", "325", "科技园")
        val passed = fav("f2", "M194", "深大北门")
        fakeRepository = HomeFakes(shanghai, FakeFavoritesRepository(initialFavorites = listOf(onTheWay, passed)))
        fun status(f: FavoriteStationLine, buses: List<LiveBus>, pre: String?) = NearbyStopLineStatus(
            line = BusLineDirection(lineId = f.lineId, name = f.lineName, startStop = "白石洲", endStop = "科技园"),
            targetOrder = 5,
            targetStopId = f.targetStopId,
            targetStopName = f.targetStopName,
            approachingBuses = buses,
            preArrivalTime = pre
        )
        fakeRepository.realtime.favoritesRealtimeResult = Result.success(
            mapOf(
                onTheWay.id to NearbyStop(
                    stopId = onTheWay.targetStopId,
                    name = onTheWay.targetStopName,
                    lines = listOf(status(onTheWay, listOf(LiveBus(busId = "b", plateOrFleetNo = "", order = 3, etaSeconds = 240)), null))
                ),
                passed.id to NearbyStop(
                    stopId = passed.targetStopId,
                    name = passed.targetStopName,
                    lines = listOf(status(passed, emptyList(), "07:20"))
                )
            )
        )

        val viewModel = createViewModel()
        testScheduler.runCurrent()

        val rows = viewModel.uiState.value.favoriteRows.map { it as FavoriteRowUi.Stop }
        assertEquals("325路，开往科技园，还有 2 站，大约 4 分钟到科技园站", rows[0].spoken)
        assertEquals("M194路，开往科技园，车都过了，下一班 7点20分 从 白石洲 发车", rows[1].spoken)
        // 屏幕上的字不变
        assertEquals("4 分钟", rows[0].arrival.big)
        assertEquals("下一班 07:20", rows[1].arrival.big)
    }

    @Test
    fun testReadAloud_speaksCardSentence() = runTest(main.dispatcher) {
        val announcer = FakeAnnouncer()
        val viewModel = createViewModel(announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onEvent(BusHomeEvent.ReadAloud("325路，开往科技园，还有 2 站，大约 4 分钟到科技园站"))
        viewModel.onEvent(BusHomeEvent.ReadAloud(" "))
        testScheduler.runCurrent()

        assertEquals(listOf("325路，开往科技园，还有 2 站，大约 4 分钟到科技园站"), announcer.spoken)
        assertTrue(viewModel.uiState.value.userMessages.none { it.text == TTS_UNAVAILABLE_MESSAGE })
    }

    @Test
    fun testReadAloud_ttsUnavailable_addsMessage() = runTest(main.dispatcher) {
        val announcer = FakeAnnouncer(isAvailable = false)
        val viewModel = createViewModel(announcer = announcer)
        testScheduler.runCurrent()

        viewModel.onEvent(BusHomeEvent.ReadAloud("325路，到站了"))
        testScheduler.runCurrent()

        assertTrue(announcer.spoken.isEmpty())
        assertEquals(TTS_UNAVAILABLE_MESSAGE, viewModel.uiState.value.userMessages.last().text)
    }
}
