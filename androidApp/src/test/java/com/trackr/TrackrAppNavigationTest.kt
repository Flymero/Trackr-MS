package com.trackr

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.trackr.app.TrackrRoot
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.WatchLine
import org.koin.core.context.GlobalContext
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCity
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkParser
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeLocationRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.search.SearchViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第 17 步：首页轮询开关从 MainActivity 的 shouldPollHome 挪进首页 Route 的 LifecycleStartEffect（重构计划 §4.4）。
 * 用真实的 TrackrRoot（App 启动时装好的 Koin 图，首页 ViewModel 换成接 Fake 的）走一遍：
 * 进子页 / 切页签后首页停轮询，回来时离上次刷新超过一个周期（15 秒）就立即补刷一次，没超过就不补。
 *
 * 第 18 步：deep link 冷启动（首帧就带着链接）/ 热启动（App 已开着、停在别处）都落成合成返回栈 `[首页, 线路详情]`（§3.6）。
 */
// 屏幕给大一点：默认的小屏上收藏区在底栏下面，点不到
@Config(qualifiers = "w411dp-h891dp")
@RunWith(RobolectricTestRunner::class)
class TrackrAppNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val city = TransitCity(cityId = "034", cityName = "上海")
    private val settings = FakeSettingsRepository(city)
    private val realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
    private var now = 1_000_000L
    private lateinit var homeViewModel: BusHomeViewModel

    /** 线路详情页按哪个 Key 建的 ViewModel（证明 deep link 打开的是哪条线） */
    private val openedLines = mutableListOf<ScreenKey.LineDetail>()

    @Before
    fun setUp() {
        // 已授权：不弹首次定位说明（它是另一个窗口，会挡住点击）
        shadowOf(ApplicationProvider.getApplicationContext<TrackrApp>()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        loadKoinModules(
            module {
                viewModel {
                    BusHomeViewModel(
                        settings = settings,
                        // 有一条收藏，首页才显示「编辑」
                        favorites = FakeFavoritesRepository(initialFavorites = listOf(SAMPLE_FAVORITE)),
                        realtime = realtime,
                        userLocationStore = FakeUserLocationStore(),
                        locationRepository = FakeLocationRepository(),
                        trackingController = get(),
                        trackingRepository = get(),
                        transitCodeLauncher = get(),
                        enableAutoPolling = true,
                        elapsedRealtimeMillis = { now }
                    )
                }
                // 线路详情接 Fake 实时数据，不发真请求
                viewModel { (key: ScreenKey.LineDetail) ->
                    openedLines += key
                    LineDetailViewModel(
                        cityId = key.cityId,
                        initialLineId = key.lineId,
                        initialTargetOrder = key.targetOrder.takeIf { it > 0 },
                        initialStationId = key.stopId.takeIf { it.isNotBlank() },
                        initialSource = key.source,
                        lineNameHint = key.lineName,
                        settings = settings,
                        favorites = FakeFavoritesRepository(),
                        realtime = realtime,
                        reminderRepository = FakeScheduledReminderRepository(),
                        trackingRepository = get(),
                        trackingController = get(),
                        lineStopsCache = get(),
                        enableAutoPolling = false
                    )
                }
                // 搜索页也接 Fake，免得测试里真发网络请求；SavedStateHandle 由 koinViewModel() 从导航 entry 带进来
                viewModel {
                    SearchViewModel(
                        settings = settings,
                        realtime = realtime,
                        searchHistoryRepository = FakeSearchHistoryRepository(),
                        userLocationStore = FakeUserLocationStore(),
                        savedStateHandle = get()
                    )
                }
            }
        )
    }

    /** 和 MainActivity 一样：链接放进一个状态，TrackrRoot 落完回调清掉 */
    private var deepLink by mutableStateOf<DeepLink?>(null)
    private var consumedLinks = 0

    private fun openSearch() {
        composeTestRule.onNodeWithText("搜线路、车站").performClick()
        composeTestRule.waitForIdle()
    }

    private fun searchField() = composeTestRule.onNode(hasSetTextAction())

    @Composable
    private fun Content() {
        TrackrRoot(
            deepLink = deepLink,
            onDeepLinkConsumed = {
                consumedLinks++
                deepLink = null
            }
        )
        // TrackrRoot 把 Activity 当作 Activity 级的 ViewModelStoreOwner：这里取到的和首页是同一个实例
        homeViewModel = koinViewModel(viewModelStoreOwner = composeTestRule.activity)
    }

    private fun launch() {
        composeTestRule.setContent { Content() }
        composeTestRule.waitForIdle()
    }

    @After
    fun tearDown() {
        if (::homeViewModel.isInitialized) homeViewModel.pausePolling()
        runCatching { GlobalContext.get().get<TrackingRepository>().stop() }
    }

    /** 点底栏页签（眉山特化页签文字：公交 / 线路 / 路线 / 设置；只认带 Selected 语义的页签） */
    private fun clickTab(label: String) {
        composeTestRule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
    }

    private fun pressBack() {
        composeTestRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
    }

    @Test
    fun homeAtRoot_isPolling() {
        launch()
        assertTrue("首页显示着就在轮询", homeViewModel.isPollingActive)
    }

    @Test
    fun switchToMeTab_stopsHomePolling_andBackAfterOneInterval_catchesUpImmediately() {
        launch()
        clickTab("设置")
        composeTestRule.waitForIdle()
        assertFalse("切到「我的」后首页停轮询", homeViewModel.isPollingActive)

        val callsBefore = realtime.nearbyStopsCalls.size
        now += 16_000L
        clickTab("公交")
        composeTestRule.waitForIdle()

        assertTrue("回到首页恢复轮询", homeViewModel.isPollingActive)
        assertEquals("离上次刷新超过 15 秒：立即补刷一次", callsBefore + 1, realtime.nearbyStopsCalls.size)
    }

    @Test
    fun openSearch_stopsHomePolling_andBackWithinInterval_doesNotRefreshImmediately() {
        launch()
        composeTestRule.onNodeWithText("搜线路、车站").performClick()
        composeTestRule.waitForIdle()
        assertFalse("进搜索页（首页栈上的子页）后首页停轮询", homeViewModel.isPollingActive)

        val callsBefore = realtime.nearbyStopsCalls.size
        now += 5_000L
        pressBack()

        assertTrue("返回首页恢复轮询", homeViewModel.isPollingActive)
        assertEquals("不到 15 秒：不补刷", callsBefore, realtime.nearbyStopsCalls.size)
    }

    @Test
    fun favoritesGroup_isChildPageOfHome_andBackReturnsHome() {
        launch()
        // 「收藏」不再是页签：首页收藏的「编辑」把旧收藏页压在首页栈上（新编辑页第 22 步）
        // 底栏页签带 Selected 语义；不应再有叫「收藏」的页签
        val favoriteTabs = composeTestRule.onAllNodes(
            hasText("收藏") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        ).fetchSemanticsNodes()
        assertTrue("底栏不应再有「收藏」页签", favoriteTabs.isEmpty())
        composeTestRule.onNodeWithText("编辑").performClick()
        composeTestRule.waitForIdle()
        assertFalse("收藏页盖住首页：首页停轮询", homeViewModel.isPollingActive)

        pressBack()
        composeTestRule.onNodeWithText("编辑").assertExists()
        assertTrue(homeViewModel.isPollingActive)
    }

    @Test
    fun restoredOntoAnotherTab_newHomeViewModelDoesNotPoll() {
        // 进程被杀后恢复到「我的」：首页 ViewModel 是新建的（默认开着轮询），首页 Route 没组合，不能一直在后台轮询
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { Content() }
        composeTestRule.waitForIdle()
        clickTab("设置")
        composeTestRule.waitForIdle()
        val before = homeViewModel

        // 清掉 Activity 级的 ViewModel，模拟进程重建后重新创建
        composeTestRule.runOnIdle { composeTestRule.activity.viewModelStore.clear() }
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertNotSame("恢复后是新建的首页 ViewModel", before, homeViewModel)
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist()
        assertFalse("首页没显示：新建的首页 ViewModel 不轮询", homeViewModel.isPollingActive)

        clickTab("公交")
        composeTestRule.waitForIdle()
        assertTrue("切回首页开始轮询", homeViewModel.isPollingActive)
    }

    // ---- 第 18 步：deep link（合成返回栈，§3.6） ----

    private val line49 = ScreenKey.LineDetail(
        cityId = "034",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.MYGOLBS,
        lineName = "49路"
    )

    /** 小组件 / 快捷方式 / 提醒通知发的那种链接，经解析器得到 */
    private val line49Link: DeepLink =
        requireNotNull(DeepLinkParser.parse(DeepLinkUris.line("034", "line_49", DataSourceType.MYGOLBS, "stop_004", 4, "49路")))

    private fun assertOnLineDetail() {
        assertEquals("打开的是链接里那条线", line49, openedLines.lastOrNull())
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist()
        composeTestRule.onNodeWithText("设置").assertDoesNotExist() // 子页不显示底栏
    }

    private fun assertOnHomeRoot() {
        composeTestRule.onNodeWithText("编辑").assertExists()
        composeTestRule.onNodeWithText("设置").assertExists()
    }

    @Test
    fun coldStart_lineDeepLink_opensLineDetailOverHomeRoot_andBackReturnsToHomeRoot() {
        deepLink = line49Link
        launch()

        assertOnLineDetail()
        assertEquals("一次性消费", 1, consumedLinks)
        assertFalse("线路页盖住首页：首页停轮询", homeViewModel.isPollingActive)

        pressBack()
        assertOnHomeRoot()
        assertTrue(homeViewModel.isPollingActive)
    }

    @Test
    fun warmStart_onAnotherTab_switchesToHomeTabAndOpensLineDetail() {
        launch()
        composeTestRule.onNodeWithText("设置").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { deepLink = line49Link }
        composeTestRule.waitForIdle()

        assertOnLineDetail()
        pressBack()
        assertOnHomeRoot()
    }

    @Test
    fun warmStart_onHomeSubPage_resetsHomeStack_soBackSkipsTheOldSubPage() {
        launch()
        composeTestRule.onNodeWithText("搜线路、车站").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("编辑").assertDoesNotExist() // 在搜索页

        composeTestRule.runOnIdle { deepLink = line49Link }
        composeTestRule.waitForIdle()
        assertOnLineDetail()

        // 合成返回栈：[首页, 线路详情]，返回直接回首页根页（main 上会先回到搜索页）
        pressBack()
        assertOnHomeRoot()
    }

    @Test
    fun replayedDeepLink_doesNotStackASecondLineDetail() {
        deepLink = line49Link
        launch()
        assertOnLineDetail()

        // 同一个 Intent 再来一次（比如又点了一次小组件）
        composeTestRule.runOnIdle { deepLink = line49Link }
        composeTestRule.waitForIdle()
        assertEquals(2, consumedLinks)
        assertOnLineDetail()

        pressBack()
        assertOnHomeRoot()
    }

    /** App 的追踪会话（Koin 里那份）：开一个一起等，写上「上了哪辆？」待选 */
    private fun startWatchWithBoardChoice(): String {
        val repo = GlobalContext.get().get<TrackingRepository>()
        val session = requireNotNull(
            repo.start(
                TrackingTarget.WatchTogether(
                    cityId = "034",
                    stopId = "stop_001",
                    stopName = "人民广场",
                    lines = listOf(WatchLine("line_49", "49路", 2, "上海体育馆"), WatchLine("line_71", "71路", 6, "徐家汇"))
                )
            )
        )
        repo.update(session.generation) {
            it.copy(
                boardChoice = BoardChoice(
                    options = listOf(
                        BoardOption(lineId = "line_49", lineName = "49路", busId = "b49"),
                        BoardOption(lineId = "line_71", lineName = "71路", busId = "b71")
                    ),
                    sinceMillis = 1L
                )
            )
        }
        return session.sessionId
    }

    @Test
    fun chooseBoardedBusLink_opensSheetOverCurrentPage() {
        val sessionId = startWatchWithBoardChoice()
        launch()
        composeTestRule.runOnIdle { deepLink = DeepLink.ChooseBoardedBus(sessionId) }
        composeTestRule.waitForIdle()

        assertEquals(1, consumedLinks)
        composeTestRule.onNodeWithText("上了哪辆？").assertExists()
        composeTestRule.onNodeWithText("两辆车都在 人民广场").assertExists()
        // 弹层叠在首页根页上：首页和底栏照常在（返回 / 下滑关弹层见 NavigationSceneAndResultTest）
        assertOnHomeRoot()
    }

    @Test
    fun chooseBoardedBusLink_choosingABus_closesSheet() {
        val sessionId = startWatchWithBoardChoice()
        launch()
        composeTestRule.runOnIdle { deepLink = DeepLink.ChooseBoardedBus(sessionId) }
        composeTestRule.waitForIdle()

        // 弹层在单独的窗口里、还在展开动画中，Robolectric 下触摸点不到：直接触发点击语义
        composeTestRule.onNodeWithText("还没上").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("上了哪辆？").assertDoesNotExist()
        assertOnHomeRoot()
        assertEquals(null, GlobalContext.get().get<TrackingRepository>().session.value?.boardChoice)
    }

    @Test
    fun chooseBoardedBusLink_forAnOldSession_closesByItself() {
        // 链接里的会话已经结束（比如点的是一条旧通知）：弹层不留在栈上
        launch()
        composeTestRule.runOnIdle { deepLink = DeepLink.ChooseBoardedBus("s-old") }
        composeTestRule.waitForIdle()

        assertEquals(1, consumedLinks)
        composeTestRule.onNodeWithText("上了哪辆？").assertDoesNotExist()
        assertOnHomeRoot()
        assertTrue(openedLines.isEmpty())
    }

    // ---- 第 23 步：搜索 ViewModel 改 entry 级，输入存 SavedStateHandle（重构计划 §3.4、§3.8） ----

    @Test
    fun searchQuery_survivesProcessRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { Content() }
        composeTestRule.waitForIdle()
        openSearch()
        searchField().performTextInput("49")
        composeTestRule.waitForIdle()

        // 进程重建：ViewModel 全没了（entry 级的也挂在 Activity 的 ViewModelStore 下），只剩保存的状态
        composeTestRule.runOnIdle { composeTestRule.activity.viewModelStore.clear() }
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        searchField().assert(hasText("49"))
    }

    @Test
    fun leavingSearch_andComingBack_startsEmpty() {
        launch()
        openSearch()
        searchField().performTextInput("49")
        composeTestRule.waitForIdle()

        pressBack()
        openSearch()

        searchField().assert(hasText("49").not())
        composeTestRule.onNodeWithText("搜索 上海 公交线路、车站").assertExists()
    }

    private companion object {
        val SAMPLE_FAVORITE = FavoriteStationLine(
            id = "fav_1",
            cityId = "034",
            cityName = "上海",
            lineId = "l1",
            lineName = "49路",
            startStop = "A",
            endStop = "B",
            targetStopId = "s1",
            targetStopName = "人民广场",
            targetOrder = 2
        )
    }
}
