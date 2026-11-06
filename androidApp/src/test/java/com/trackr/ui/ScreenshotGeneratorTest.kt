package com.trackr.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.repository.DefaultLocationRepository
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.testing.FakeAmapKeyRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeTransitCodeLauncher
import com.trackr.data.location.AndroidLocationSource
import com.trackr.data.location.AndroidMonotonicClock
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.feature.bus.home.BusHomeScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailScreen
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateStatus
import com.trackr.ui.screens.me.AboutScreen
import com.trackr.ui.screens.me.AboutUiState
import com.trackr.ui.screens.me.MeNotices
import com.trackr.ui.screens.me.MeScreen
import com.trackr.ui.screens.me.MeUiState
import com.trackr.ui.screens.me.MeViewModel
import com.trackr.ui.screens.search.SearchScreen
import com.trackr.ui.screens.search.SearchViewModel
import com.trackr.di.TransitUserLocationStore
import com.trackr.ui.screens.stopdetail.StopDetailScreen
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi", sdk = [34])
class ScreenshotGeneratorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application
    private lateinit var trackingRepository: DefaultTrackingRepository
    private lateinit var trackingController: FakeTrackingController
    private val outputDir = File("build/screenshots/promo")

    private val shanghai = TransitCity(
        cityId = "034",
        cityName = "上海",
        pinyin = "shanghai",
        supportSubway = true,
        isHot = true,
        province = "直辖市"
    )

    // 线路 1：71路中运量 (旗舰示范线)
    private val line71 = BusLineDirection(
        lineId = "sh_71",
        name = "71路中运量",
        lineNo = "71",
        direction = 0,
        startStop = "延安东路外滩",
        endStop = "申昆路枢纽站",
        firstTime = "05:30",
        lastTime = "23:30",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    private val line71Rev = BusLineDirection(
        lineId = "sh_71_rev",
        name = "71路中运量",
        lineNo = "71",
        direction = 1,
        startStop = "申昆路枢纽站",
        endStop = "延安东路外滩",
        firstTime = "05:30",
        lastTime = "23:30",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    // 线路 2：20路百年电车
    private val line20 = BusLineDirection(
        lineId = "sh_20",
        name = "20路 [百年电车]",
        lineNo = "20",
        direction = 0,
        startStop = "九江路中山东一路",
        endStop = "中山公园(万航渡路)",
        firstTime = "05:00",
        lastTime = "23:30",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    // 线路 3：911路淮海路双层巴士
    private val line911 = BusLineDirection(
        lineId = "sh_911",
        name = "911路 [双层观光巴士]",
        lineNo = "911",
        direction = 0,
        startStop = "老西门",
        endStop = "万科城市花园",
        firstTime = "06:00",
        lastTime = "22:30",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    // 线路 4：49路上海模范线
    private val line49 = BusLineDirection(
        lineId = "sh_49",
        name = "49路",
        lineNo = "49",
        direction = 0,
        startStop = "汉口路江西中路",
        endStop = "上海体育馆",
        firstTime = "05:30",
        lastTime = "23:50",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    // 线路 5：14路无轨电车
    private val line14 = BusLineDirection(
        lineId = "sh_14",
        name = "14路无轨电车",
        lineNo = "14",
        direction = 0,
        startStop = "东新桥",
        endStop = "江浦路中山北二路",
        firstTime = "05:00",
        lastTime = "23:00",
        price = "2.00元",
        source = DataSourceType.CHELAILE
    )

    private val stops71 = listOf(
        BusStop(stopId = "s1", name = "延安东路外滩", order = 1, wgsLat = 31.2370, wgsLng = 121.4910),
        BusStop(stopId = "s2", name = "西藏中路", order = 2, wgsLat = 31.2330, wgsLng = 121.4780, metroTransfers = listOf("1号线", "2号线", "8号线")),
        BusStop(stopId = "s3", name = "黄陂北路", order = 3, wgsLat = 31.2300, wgsLng = 121.4700),
        BusStop(stopId = "s4", name = "成都北路", order = 4, wgsLat = 31.2280, wgsLng = 121.4640),
        BusStop(stopId = "s5", name = "陕西北路", order = 5, wgsLat = 31.2250, wgsLng = 121.4550),
        BusStop(stopId = "s6", name = "华山路", order = 6, wgsLat = 31.2210, wgsLng = 121.4460),
        BusStop(stopId = "s7", name = "江苏路", order = 7, wgsLat = 31.2180, wgsLng = 121.4360, metroTransfers = listOf("2号线", "11号线")),
        BusStop(stopId = "s8", name = "镇宁路", order = 8, wgsLat = 31.2160, wgsLng = 121.4300),
        BusStop(stopId = "s9", name = "定西路", order = 9, wgsLat = 31.2140, wgsLng = 121.4210),
        BusStop(stopId = "s10", name = "凯旋路", order = 10, wgsLat = 31.2110, wgsLng = 121.4130, metroTransfers = listOf("3号线", "4号线")),
        BusStop(stopId = "s11", name = "番禺路", order = 11, wgsLat = 31.2090, wgsLng = 121.4050),
        BusStop(stopId = "s12", name = "剑河路", order = 12, wgsLat = 31.2040, wgsLng = 121.3850),
        BusStop(stopId = "s13", name = "虹井路", order = 13, wgsLat = 31.1980, wgsLng = 121.3680),
        BusStop(stopId = "s14", name = "申昆路枢纽站", order = 14, wgsLat = 31.1910, wgsLng = 121.3480)
    )

    private val buses71 = listOf(
        LiveBus(
            busId = "bus_71_01",
            plateOrFleetNo = "沪A·D8866",
            order = 4,
            segmentProgress = 0.78f,
            distanceToTargetMeters = 680,
            etaSeconds = 115,
            speedKmh = 32.5,
            isArrived = false,
            capacityLevel = 0,
            nextStopName = "陕西北路"
        ),
        LiveBus(
            busId = "bus_71_02",
            plateOrFleetNo = "沪A·D6521",
            order = 2,
            segmentProgress = 0.42f,
            distanceToTargetMeters = 2450,
            etaSeconds = 620,
            speedKmh = 26.8,
            isArrived = false,
            capacityLevel = 1,
            nextStopName = "黄陂北路"
        ),
        LiveBus(
            busId = "bus_71_03",
            plateOrFleetNo = "沪A·D3390",
            order = 1,
            segmentProgress = 0.85f,
            distanceToTargetMeters = 4820,
            etaSeconds = 1250,
            speedKmh = 29.4,
            isArrived = false,
            capacityLevel = 0,
            nextStopName = "西藏中路"
        ),
        LiveBus(
            busId = "bus_71_04",
            plateOrFleetNo = "沪A·D1099",
            order = 1,
            segmentProgress = 0.1f,
            distanceToTargetMeters = 7200,
            etaSeconds = 1950,
            speedKmh = 24.0,
            isArrived = false,
            capacityLevel = 0,
            nextStopName = "延安东路外滩"
        )
    )

    private val lineDetail71 = BusLineDetail(
        line = line71,
        reverseDirection = line71Rev,
        stops = stops71,
        buses = buses71,
        targetOrder = 5
    )

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        outputDir.mkdirs()
        fakeLocation()
        trackingRepository = DefaultTrackingRepository(
            InMemoryTrackingSessionStore(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        trackingController = FakeTrackingController(trackingRepository)
    }

    /**
     * 截图里的首页要显示真实定位状态。Robolectric 默认既没授权、也没有缓存定位，
     * 首页会顶出「定位不可用，已保留当前位置」——那是测试环境缺定位，不是产品状态。
     * 这里授权并塞一个静安寺附近的缓存定位（WGS-84）。
     */
    private fun fakeLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            setLastKnownLocation(
                LocationManager.GPS_PROVIDER,
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 31.2230
                    longitude = 121.4465
                    accuracy = 12f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
            )
        }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val outFile = File(outputDir, name)
        FileOutputStream(outFile).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("Generated promo screenshot: ${outFile.absolutePath} (${bitmap.width}x${bitmap.height})")
    }

    @Test
    fun generateLineDetailScreenshots() {
        val fakeRepo = FakeTransitRepository(initialCity = shanghai)
        fakeRepo.lineDetailResult = Result.success(lineDetail71)

        val vm = LineDetailViewModel(
            cityId = "034",
            initialLineId = "sh_71",
            initialTargetOrder = 5,
            initialStationId = "s5",
            settings = fakeRepo,
            favorites = fakeRepo,
            realtime = fakeRepo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false
        )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // 1. 线路详情 (Light Theme)。默认横排走向条，站名竖排
        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LineDetailScreen(
                        state = vm.uiState.collectAsState().value,
                        onEvent = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("02_line_detail_horizontal.png")

        // 2. 在途车辆雷达列表 (Light Theme)
        vm.openBusList()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("04_fleet_radar_bus_list.png")
    }

    @Test
    fun generateLineDetailDarkScreenshot() {
        val fakeRepo = FakeTransitRepository(initialCity = shanghai)
        fakeRepo.lineDetailResult = Result.success(lineDetail71)

        val vm = LineDetailViewModel(
            cityId = "034",
            initialLineId = "sh_71",
            initialTargetOrder = 5,
            initialStationId = "s5",
            settings = fakeRepo,
            favorites = fakeRepo,
            realtime = fakeRepo,
            reminderRepository = FakeScheduledReminderRepository(),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            enableAutoPolling = false
        )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // 4. 深色模式线路详情 (Dark Theme)
        composeTestRule.setContent {
            TrackrTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LineDetailScreen(
                        state = vm.uiState.collectAsState().value,
                        onEvent = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("07_line_detail_dark.png")
    }

    @Test
    fun generateHomeScreenLightScreenshot() {
        val vm = setupHomeViewModel()

        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by vm.uiState.collectAsState()
                    BusHomeScreen(state = state, onEvent = vm::onEvent)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("01_home_screen_light.png")
    }

    @Test
    fun generateHomeScreenDarkScreenshot() {
        val vm = setupHomeViewModel()

        composeTestRule.setContent {
            TrackrTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by vm.uiState.collectAsState()
                    BusHomeScreen(state = state, onEvent = vm::onEvent)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("08_home_screen_dark.png")
    }

    private fun setupHomeViewModel(): BusHomeViewModel {
        val initialFavorites = listOf(
            FavoriteStationLine(
                id = "fav_1",
                cityId = "034",
                cityName = "上海",
                lineId = "sh_71",
                lineName = "71路中运量",
                direction = 0,
                startStop = "延安东路外滩",
                endStop = "申昆路枢纽站",
                targetStopId = "s5",
                targetStopName = "陕西北路",
                targetOrder = 5,
                source = DataSourceType.CHELAILE
            ),
            FavoriteStationLine(
                id = "fav_2",
                cityId = "034",
                cityName = "上海",
                lineId = "sh_20",
                lineName = "20路 [百年电车]",
                direction = 0,
                startStop = "九江路中山东一路",
                endStop = "中山公园",
                targetStopId = "s_ja",
                targetStopName = "静安寺",
                targetOrder = 7,
                source = DataSourceType.CHELAILE
            ),
            FavoriteStationLine(
                id = "fav_3",
                cityId = "034",
                cityName = "上海",
                lineId = "sh_911",
                lineName = "911路 [双层观光]",
                direction = 0,
                startStop = "老西门",
                endStop = "万科城市花园",
                targetStopId = "s_sn",
                targetStopName = "陕西南路淮海中路",
                targetOrder = 4,
                source = DataSourceType.CHELAILE
            )
        )

        val fakeRepo = FakeTransitRepository(
            initialCity = shanghai,
            initialFavorites = initialFavorites
        )

        // 正在进行的常驻追踪状态
        val activeTracking = TrackingSessionState(
            isActive = true,
            cityId = "034",
            lineId = "sh_71",
            lineName = "71路中运量",
            endStopName = "申昆路枢纽站",
            targetStopId = "s5",
            targetStopName = "陕西北路",
            targetOrder = 5,
            totalStops = 14,
            currentBusOrder = 4,
            currentBusProgress = 0.8f,
            stopsRemaining = 1,
            etaSeconds = 115,
            statusSummary = "下一班 1 站 · 约 2 分钟 (680m)"
        )
        // 让追踪会话进入这一状态：先开始追踪这条线，再把轮询结果写回
        val session = trackingRepository.start(
            TrackingTarget.SingleLine(
                cityId = activeTracking.cityId,
                lineId = activeTracking.lineId,
                lineName = activeTracking.lineName,
                endStopName = activeTracking.endStopName,
                targetStopId = activeTracking.targetStopId,
                targetStopName = activeTracking.targetStopName,
                targetOrder = activeTracking.targetOrder,
                totalStops = activeTracking.totalStops
            )
        )
        checkNotNull(session)
        trackingRepository.update(session.sessionId, session.generation) { it.copy(state = activeTracking) }

        // 附近站点丰富模拟数据
        val nearbyStops = listOf(
            NearbyStop(
                stopId = "stop_jingan",
                name = "静安寺枢纽站",
                distanceMeters = 120,
                wgsLat = 31.2230,
                wgsLng = 121.4465,
                subwayLineNames = listOf("2号线", "7号线", "14号线"),
                lines = listOf(
                    NearbyStopLineStatus(
                        line = line20,
                        targetOrder = 7,
                        targetStopId = "stop_jingan",
                        targetStopName = "静安寺枢纽站",
                        statusText = "即将进站",
                        approachingBuses = listOf(
                            LiveBus("b20_1", "沪A·88321", 7, 0.95f, false, speedKmh = 18.0, distanceToTargetMeters = 160, etaSeconds = 45)
                        )
                    ),
                    NearbyStopLineStatus(
                        line = line71,
                        targetOrder = 5,
                        targetStopId = "stop_jingan",
                        targetStopName = "静安寺枢纽站",
                        statusText = "1站 · 约2分钟",
                        approachingBuses = listOf(
                            LiveBus("b71_1", "沪A·D8866", 4, 0.78f, false, speedKmh = 32.5, distanceToTargetMeters = 680, etaSeconds = 115)
                        )
                    ),
                    NearbyStopLineStatus(
                        line = BusLineDirection(
                            lineId = "sh_37",
                            name = "37路",
                            lineNo = "37",
                            direction = 0,
                            startStop = "扬州路齐齐哈尔路",
                            endStop = "愚园路胶州路",
                            source = DataSourceType.CHELAILE
                        ),
                        targetOrder = 12,
                        targetStopId = "stop_jingan",
                        targetStopName = "静安寺枢纽站",
                        statusText = "4站 · 约8分钟",
                        approachingBuses = listOf(
                            LiveBus("b37_1", "沪A·55120", 8, 0.45f, false, speedKmh = 22.0, distanceToTargetMeters = 1920, etaSeconds = 480)
                        )
                    )
                )
            ),
            NearbyStop(
                stopId = "stop_shaanxi",
                name = "延安中路陕西北路",
                distanceMeters = 380,
                wgsLat = 31.2250,
                wgsLng = 121.4550,
                subwayLineNames = listOf("12号线", "13号线"),
                lines = listOf(
                    NearbyStopLineStatus(
                        line = line71,
                        targetOrder = 5,
                        targetStopId = "stop_shaanxi",
                        targetStopName = "延安中路陕西北路",
                        statusText = "1站 · 约2分钟",
                        approachingBuses = listOf(
                            LiveBus("b71_1", "沪A·D8866", 4, 0.78f, false, speedKmh = 32.5, distanceToTargetMeters = 680, etaSeconds = 115)
                        )
                    ),
                    NearbyStopLineStatus(
                        line = line49,
                        targetOrder = 4,
                        targetStopId = "stop_shaanxi",
                        targetStopName = "延安中路陕西北路",
                        statusText = "2站 · 约4分钟",
                        approachingBuses = listOf(
                            LiveBus("b49_1", "沪A·12345", 2, 0.6f, false, speedKmh = 27.0, distanceToTargetMeters = 1100, etaSeconds = 240)
                        )
                    )
                )
            ),
            NearbyStop(
                stopId = "stop_huaihai",
                name = "淮海中路陕西南路",
                distanceMeters = 650,
                wgsLat = 31.2200,
                wgsLng = 121.4580,
                subwayLineNames = listOf("1号线", "10号线", "12号线"),
                lines = listOf(
                    NearbyStopLineStatus(
                        line = line911,
                        targetOrder = 4,
                        targetStopId = "stop_huaihai",
                        targetStopName = "淮海中路陕西南路",
                        statusText = "2站 · 约5分钟",
                        approachingBuses = listOf(
                            LiveBus("b911_1", "沪A·91101", 2, 0.5f, false, speedKmh = 25.0, distanceToTargetMeters = 1350, etaSeconds = 300)
                        )
                    )
                )
            )
        )
        fakeRepo.nearbyStopsResult = Result.success(nearbyStops)

        val favRealtime = mapOf(
            "fav_1" to nearbyStops[0],
            "fav_2" to nearbyStops[0],
            "fav_3" to nearbyStops[2]
        )
        fakeRepo.favoritesRealtimeResult = Result.success(favRealtime)

        val vm = BusHomeViewModel(
            locationRepository = DefaultLocationRepository(AndroidLocationSource(app), AndroidMonotonicClock),
            trackingController = trackingController,
            trackingRepository = trackingRepository,
            settings = fakeRepo,
            favorites = fakeRepo,
            realtime = fakeRepo,
            userLocationStore = TransitUserLocationStore(fakeRepo),
            transitCodeLauncher = FakeTransitCodeLauncher(),
            enableAutoPolling = false
        )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun generateStopDetailScreenshot() {
        val hubStop = NearbyStop(
            stopId = "stop_ps_1",
            name = "人民广场枢纽站",
            distanceMeters = 85,
            wgsLat = 31.2330,
            wgsLng = 121.4780,
            subwayLineNames = listOf("地铁 1 号线", "地铁 2 号线", "地铁 8 号线"),
            lines = listOf(
                NearbyStopLineStatus(
                    line = line49,
                    targetOrder = 2,
                    targetStopId = "stop_ps_1",
                    targetStopName = "人民广场(广东路)",
                    statusText = "即将进站",
                    approachingBuses = listOf(
                        LiveBus("b49_1", "沪A·55891", 2, 0.92f, false, speedKmh = 22.0, distanceToTargetMeters = 180, etaSeconds = 50)
                    )
                ),
                NearbyStopLineStatus(
                    line = line14,
                    targetOrder = 1,
                    targetStopId = "stop_ps_1",
                    targetStopName = "东新桥(人民广场)",
                    statusText = "1站 · 约2分钟",
                    approachingBuses = listOf(
                        LiveBus("b14_1", "沪A·14002", 1, 0.65f, false, speedKmh = 26.0, distanceToTargetMeters = 520, etaSeconds = 120)
                    )
                ),
                NearbyStopLineStatus(
                    line = line20,
                    targetOrder = 2,
                    targetStopId = "stop_ps_1",
                    targetStopName = "九江路浙江中路",
                    statusText = "2站 · 约4分钟",
                    approachingBuses = listOf(
                        LiveBus("b20_1", "沪A·20038", 1, 0.3f, false, speedKmh = 28.0, distanceToTargetMeters = 1150, etaSeconds = 240)
                    )
                ),
                NearbyStopLineStatus(
                    line = BusLineDirection(
                        lineId = "sh_112",
                        name = "112路",
                        lineNo = "112",
                        direction = 0,
                        startStop = "人民广场(武胜路)",
                        endStop = "万里城",
                        price = "2.00元",
                        source = DataSourceType.CHELAILE
                    ),
                    targetOrder = 1,
                    targetStopId = "stop_ps_1",
                    targetStopName = "人民广场(武胜路)",
                    statusText = "始发站已就绪 · 即将发车",
                    approachingBuses = listOf(
                        LiveBus("b112_1", "沪A·11209", 1, 1.0f, true, speedKmh = 0.0, distanceToTargetMeters = 0, etaSeconds = 0)
                    )
                )
            )
        )

        val hubStop2 = NearbyStop(
            stopId = "stop_ps_2",
            name = "人民广场(西藏中路)",
            distanceMeters = 210,
            wgsLat = 31.2345,
            wgsLng = 121.4790,
            subwayLineNames = listOf("地铁 1 号线", "地铁 2 号线", "地铁 8 号线"),
            lines = listOf(
                NearbyStopLineStatus(
                    line = line71,
                    targetOrder = 2,
                    targetStopId = "stop_ps_2",
                    targetStopName = "西藏中路",
                    statusText = "2站 · 约3分钟",
                    approachingBuses = listOf(
                        LiveBus("b71_1", "沪A·D8866", 1, 0.5f, false, speedKmh = 30.0, distanceToTargetMeters = 980, etaSeconds = 180)
                    )
                )
            )
        )

        val fakeRepo = FakeTransitRepository(initialCity = shanghai)
        fakeRepo.stopDetailResult = Result.success(listOf(hubStop, hubStop2))

        val vm = StopDetailViewModel(
            cityId = "034",
            stopId = "stop_ps_1",
            initialStopName = "人民广场枢纽站",
            physicalStId = "pst_ps_1",
            namesakeStId = "nst_ps_1",
            stationLat = 31.2330,
            stationLng = 121.4780,
            settings = fakeRepo,
            favorites = fakeRepo,
            realtime = fakeRepo,
            userLocationStore = TransitUserLocationStore(fakeRepo),
            trackingRepository = trackingRepository,
            trackingController = trackingController,
            transitCodeLauncher = FakeTransitCodeLauncher(),
            enableAutoPolling = false
        )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // 7. 综合枢纽站点详情 (Light Theme)；第 21 步起页面无状态，直接喂 VM 的状态
        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StopDetailScreen(
                        state = vm.uiState.value,
                        onEvent = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("05_stop_detail_hub.png")
    }

    @Test
    fun generateSearchScreenScreenshot() {
        val fakeRepo = FakeTransitRepository(initialCity = shanghai)
        fakeRepo.searchResult = Result.success(
            TransitSearchResult(
                keyword = "71",
                lines = listOf(
                    com.trackr.core.model.BusLineSummary(
                        name = "71路中运量",
                        lineNo = "71",
                        isSubway = false,
                        directions = listOf(line71, line71Rev),
                        source = DataSourceType.CHELAILE
                    ),
                    com.trackr.core.model.BusLineSummary(
                        name = "71路区间",
                        lineNo = "71区间",
                        isSubway = false,
                        directions = listOf(
                            BusLineDirection(
                                lineId = "sh_71_qj",
                                name = "71路区间",
                                lineNo = "71区间",
                                direction = 0,
                                startStop = "黄陂北路",
                                endStop = "申昆路枢纽站",
                                source = DataSourceType.CHELAILE
                            )
                        ),
                        source = DataSourceType.CHELAILE
                    )
                ),
                stops = listOf(
                    NearbyStop(
                        stopId = "s_71_wh",
                        name = "延安东路外滩(71路起讫站)",
                        distanceMeters = 350,
                        lines = listOf()
                    ),
                    NearbyStop(
                        stopId = "s_71_sk",
                        name = "申昆路枢纽站(71路公交总站)",
                        distanceMeters = 12500,
                        lines = listOf()
                    )
                )
            )
        )

        // 第 17 步：搜索从 HomeViewModel 拆成 SearchViewModel
        val vm = SearchViewModel(
            settings = fakeRepo,
            realtime = fakeRepo,
            searchHistoryRepository = fakeRepo,
            userLocationStore = TransitUserLocationStore(fakeRepo)
        )
        vm.updateSearchQuery("71")
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        // 8. 实时搜索界面 (Light Theme)
        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 第 23 步：无状态 SearchScreen(state, queryText, onEvent)
                    // 输入框文字单独传 ViewModel 的 Compose 快照，不从 state.searchQuery 取
                    SearchScreen(state = vm.uiState.value, queryText = vm.queryText, onEvent = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("06_search_screen.png")
    }

    @Test
    fun generateMoreScreenLightScreenshot() {
        val state = meScreenshotState()

        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeScreen(state = state, onEvent = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("09_more_screen_light.png")
    }

    @Test
    fun generateMoreScreenDarkScreenshot() {
        val state = meScreenshotState()

        composeTestRule.setContent {
            TrackrTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeScreen(state = state, onEvent = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("10_more_screen_dark.png")
    }

    @Test
    fun generateAboutScreenLightScreenshot() {
        composeTestRule.setContent {
            TrackrTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AboutScreen(state = aboutScreenshotState, onEvent = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("11_about_screen_light.png")
    }

    @Test
    fun generateAboutScreenDarkScreenshot() {
        composeTestRule.setContent {
            TrackrTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AboutScreen(state = aboutScreenshotState, onEvent = {})
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
        saveScreenshot("12_about_screen_dark.png")
    }

    private val aboutScreenshotState = AboutUiState(versionName = "0.0.3", canCheckUpdates = true, showLiveUpdateNote = true)

    /** 「我的」页截图用的状态：两个定时提醒、小米焦点通知没开 */
    private fun meScreenshotState(): MeUiState {
        val initialReminders = listOf(
            ScheduledReminder(
                id = "rem_morning",
                enabled = true,
                hour = 8,
                minute = 15,
                cityId = "034",
                cityName = "上海",
                lineId = "sh_71",
                lineName = "71路中运量",
                direction = 0,
                endStopName = "申昆路枢纽站",
                targetStopId = "s5",
                targetStopName = "陕西北路",
                targetOrder = 5,
                autoStartLiveTracking = true
            ),
            ScheduledReminder(
                id = "rem_evening",
                enabled = true,
                hour = 18,
                minute = 30,
                cityId = "034",
                cityName = "上海",
                lineId = "sh_20",
                lineName = "20路 [百年电车]",
                direction = 0,
                endStopName = "中山公园",
                targetStopId = "s_ja",
                targetStopName = "静安寺",
                targetOrder = 7,
                autoStartLiveTracking = false
            )
        )
        val vm = MeViewModel(
            settings = FakeTransitRepository(initialCity = shanghai),
            reminderRepository = FakeScheduledReminderRepository(initialReminders = initialReminders),
            amapKeys = FakeAmapKeyRepository(),
            notices = MeNotices(),
            liveUpdateAccess = object : LiveUpdateAccess {
                override fun status() = LiveUpdateStatus(supported = true, allowed = false, systemName = "小米焦点通知")
                override fun openSettings() = Unit
            },
            versionName = "0.0.3"
        )
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        return vm.uiState.value
    }
}
