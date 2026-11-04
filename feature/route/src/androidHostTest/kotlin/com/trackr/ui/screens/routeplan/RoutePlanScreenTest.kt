package com.trackr.ui.screens.routeplan

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.route.DefaultRoutePlanRepository
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.data.route.RouteGroupSaver
import com.trackr.core.domain.platform.AppSigningInfo
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeAmapRouteGateway
import com.trackr.core.testing.FakeAppSigningInfo
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeUserLocationStore
import com.trackr.core.testing.InMemoryRouteGroupRepository
import com.trackr.ui.screens.routedetail.RouteDetailViewModel
import com.trackr.ui.screens.routeplan.dialog.SaveFavoriteDialog
import com.trackr.ui.screens.routeplan.map.ROUTE_PLAN_NO_MAP_TAG
import com.trackr.ui.screens.routeplan.map.RouteMapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.annotation.Config

/** 第 24 步：测无状态 [RoutePlanScreen]（假状态 + 记事件），外加一条 [RoutePlanRoute] 的「已收藏 · 去看看」 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoutePlanScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<RoutePlanEvent>()

    /**
     * 点击走语义动作：Robolectric 默认窗口只有 320x470，底部面板在窗口下沿外，坐标点击会被丢掉。
     */
    private fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction =
        performSemanticsAction(SemanticsActions.OnClick)

    /** 最近一个用户操作发出的事件（Pager 停稳时会自动发 SelectScheme，跳过它） */
    private fun lastAction(): RoutePlanEvent = events.last { it !is RoutePlanEvent.SelectScheme }

    @Before
    fun setup() {
        // 地图诊断弹窗用 koinInject() 取签名信息
        stopKoin()
        startKoin { modules(module { single<AppSigningInfo> { FakeAppSigningInfo() } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun scheme(id: String, minutes: Int, transfers: Int, walk: Int, lineNames: List<String>): AmapTransitSchemeResult {
        val legs = lineNames.mapIndexed { index, name ->
            TransitLeg(
                legIndex = index,
                fromStopName = "站$index",
                toStopName = "站${index + 1}",
                candidateLines = listOf(
                    CandidateLine(cityId = "0755", lineId = "amap_$name", lineName = name, fromStopOrder = index + 1, toStopOrder = index + 5)
                ),
                walkingDistanceMeters = if (index == 0) 0 else 80
            )
        }
        return AmapTransitSchemeResult(
            title = id,
            durationMinutes = minutes,
            walkingMeters = walk,
            costYuan = "4.0",
            transferCount = transfers,
            group = TransitGroup(id = id, name = id, legs = legs)
        )
    }

    private val demoTabs = pickSchemeTabs(
        listOf(
            scheme("s0", 32, 1, 820, listOf("B612", "M492")),
            scheme("s1", 38, 0, 640, listOf("M375")),
            scheme("s2", 35, 1, 260, listOf("B796", "323")),
            scheme("s3", 36, 1, 1100, listOf("地铁1号线", "B612"))
        )
    )

    /** 无状态页面 + 一个最小的「VM」：只把输入框事件写回状态，其他事件记下来 */
    private fun setScreen(initial: RoutePlanUiState) {
        composeTestRule.setContent {
            var state by remember { mutableStateOf(initial) }
            RoutePlanScreen(
                state = state,
                onEvent = { event ->
                    events += event
                    when (event) {
                        is RoutePlanEvent.OriginChanged -> state = state.copy(originText = event.text)
                        is RoutePlanEvent.DestChanged -> state = state.copy(destText = event.text)
                        RoutePlanEvent.SwapOriginDest -> state = state.copy(originText = state.destText, destText = state.originText)
                        else -> Unit
                    }
                }
            )
        }
    }

    @Test
    fun searchConsole_placeholdersAndTyping() {
        setScreen(RoutePlanUiState())

        composeTestRule.onNodeWithText("输入起点站").assertIsDisplayed()
        composeTestRule.onNodeWithText("输入目的地、商圈或公交地铁站").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_trigger_plan").assertIsDisplayed()
        // 「配置Key」胶囊已删（D2：Key 在「更多」页配）
        composeTestRule.onNodeWithTag("btn_key_config").assertDoesNotExist()
        composeTestRule.onNodeWithText("配置 Key").assertDoesNotExist()

        composeTestRule.onNodeWithTag("input_origin").performTextInput("科技园")
        composeTestRule.onNodeWithText("科技园").assertIsDisplayed()
        assertEquals(RoutePlanEvent.OriginChanged("科技园"), lastAction())

        composeTestRule.onNodeWithTag("btn_trigger_plan").tap()
        assertEquals(RoutePlanEvent.Submit, lastAction())
    }

    @Test
    fun emptyField_showsMyLocationOption_forBothFields() {
        setScreen(RoutePlanUiState())

        // 起点空着点进去：先弹「我的位置」，不是写死在提示里
        composeTestRule.onNodeWithTag("input_origin").performClick()
        composeTestRule.onNodeWithTag("tip_my_location").assertIsDisplayed()
        composeTestRule.onNodeWithText("从当前位置出发").assertIsDisplayed()

        // 换到终点也一样，文案跟着变成「到当前位置」
        composeTestRule.onNodeWithTag("input_dest").performClick()
        composeTestRule.onNodeWithTag("tip_my_location").assertIsDisplayed()
        composeTestRule.onNodeWithText("到当前位置").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tip_my_location").tap()
        assertEquals(RoutePlanEvent.PickMyLocation(TipsField.Dest), lastAction())
    }

    @Test
    fun filledField_doesNotShowMyLocationOption() {
        setScreen(RoutePlanUiState(originText = "深大北门"))
        composeTestRule.onNodeWithTag("input_origin").performClick()
        composeTestRule.onNodeWithTag("tip_my_location").assertDoesNotExist()
    }

    @Test
    fun swap_sendsSwapEvent() {
        setScreen(RoutePlanUiState(originText = "深大北门", destText = "高新园"))
        composeTestRule.onNodeWithTag("btn_swap_origin_dest").tap()
        composeTestRule.waitForIdle()
        assertEquals(RoutePlanEvent.SwapOriginDest, lastAction())
        composeTestRule.onNodeWithTag("input_origin").assertTextEquals("高新园")
    }

    @Test
    fun cannotShowMap_onlyWebKey_noMapAreaNoRecenter() {
        // Q18：只有 Web Key 时有路线页，能搜能算，但不画地图（也就没有回到定位按钮）
        setScreen(RoutePlanUiState(canShowMap = false))
        composeTestRule.onNodeWithTag("top_search_console").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ROUTE_PLAN_NO_MAP_TAG).assertExists()
        composeTestRule.onNodeWithTag("btn_recenter").assertDoesNotExist()
    }

    @Test
    fun canShowMap_butNoMapSession_drawsNoMapAndNoFallbackSketch() {
        // 有 Android Key 但拿不到地图会话（测试环境 / 设备不支持）：不退回内置示意图，只留底色
        setScreen(RoutePlanUiState(canShowMap = true, map = RouteMapState(androidKey = "android_key_123456")))
        composeTestRule.onNodeWithTag(ROUTE_PLAN_NO_MAP_TAG).assertExists()
        composeTestRule.onNodeWithTag("btn_recenter").assertDoesNotExist()
        composeTestRule.onNodeWithTag("card_map_timeout").assertDoesNotExist()
    }

    @Test
    fun tips_listAndPick_andBackDismissesTips() {
        val tips = listOf(AmapTipItem(name = "西湖站", district = "西湖区"), AmapTipItem(name = "西湖站", address = "同名"))
        setScreen(RoutePlanUiState(destText = "西湖", tipsField = TipsField.Dest, tips = tips))
        // 提示只给正在编辑的框：先点进终点
        composeTestRule.onNodeWithTag("input_dest").performClick()

        // 重名的提示也能显示（key 带序号）
        composeTestRule.onAllNodesWithText("西湖站").assertCountEquals(2)
        composeTestRule.onNodeWithText("西湖区").tap()
        assertEquals(RoutePlanEvent.PickTip(TipsField.Dest, tips[0]), lastAction())

        // 返回键先收起提示
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertEquals(RoutePlanEvent.DismissTips, lastAction())
    }

    @Test
    fun noTips_backIsNotConsumed() {
        setScreen(RoutePlanUiState())
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertTrue(events.none { it == RoutePlanEvent.DismissTips })
    }

    @Test
    fun schemePanel_threeFixedTabs_switchPager_andStartTrackingUsesSelected() {
        setScreen(RoutePlanUiState(schemeTabs = demoTabs))

        // 3 个标签一直可见，名字写清楚
        (0 until 3).forEach { composeTestRule.onNodeWithTag("scheme_tab_$it").assertExists() }
        composeTestRule.onNodeWithTag("scheme_tab_3").assertDoesNotExist()
        composeTestRule.onNodeWithText("最快").assertExists()
        composeTestRule.onNodeWithText("少换乘").assertExists()
        composeTestRule.onNodeWithText("少走路").assertExists()
        // 分钟只在页签写一次；详情里只剩「出发 · 到」（不再重复一个大号分钟）
        composeTestRule.onNodeWithTag("scheme_duration_0").assertDoesNotExist()
        composeTestRule.onNodeWithTag("scheme_depart_arrive_0").assertExists()
        composeTestRule.onNodeWithText("收藏").assertExists()

        // 点第 3 个标签（少走路 = s2）切到对应详情
        composeTestRule.onNodeWithTag("scheme_tab_2").tap()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scheme_summary_2").assertTextEquals("步行 260 米 · 换乘 1 次 · ¥4.0")
        assertEquals(RoutePlanEvent.SelectScheme(2), events.last { it is RoutePlanEvent.SelectScheme })

        // 线路胶囊 → 打开线路
        composeTestRule.onNodeWithTag("scheme_line_chip_B796").tap()
        assertEquals("B796", (lastAction() as RoutePlanEvent.OpenLine).line.lineName)

        // 开始追踪只追踪选中的这个方案（不收藏）
        composeTestRule.onNodeWithTag("btn_start_monitor").tap()
        assertEquals("s2", (lastAction() as RoutePlanEvent.StartTracking).scheme.group.id)
        assertTrue(events.none { it is RoutePlanEvent.ConfirmSave || it == RoutePlanEvent.OpenSaveDialog })

        composeTestRule.onNodeWithTag("btn_save_favorite").tap()
        assertEquals(RoutePlanEvent.OpenSaveDialog, lastAction())
    }

    @Test
    fun schemePanel_trackingCurrentScheme_showsStatusBarInsteadOfButtons() {
        val session = GroupTrackingSessionState(
            groupId = "s0",
            activeLegIndex = 0,
            totalLegs = 2,
            trackingSessionState = TrackingSessionState(
                isActive = true,
                legPhase = LegPhase.WAITING_BOARD,
                stopsRemaining = 2,
                etaSeconds = 240,
                currentLegIndex = 0,
                totalLegs = 2
            )
        )
        setScreen(RoutePlanUiState(schemeTabs = demoTabs, trackingSession = session))

        composeTestRule.onNodeWithTag("tracking_status_text").assertTextEquals("第 1/2 程 · 等车 · 约 4 分钟")
        composeTestRule.onNodeWithTag("btn_plan_confirm_boarded").tap()
        assertEquals(RoutePlanEvent.ConfirmBoarded, lastAction())
        composeTestRule.onNodeWithTag("btn_start_monitor").assertDoesNotExist()
        composeTestRule.onNodeWithTag("btn_plan_stop_tracking").tap()
        assertEquals(RoutePlanEvent.StopTracking, lastAction())
    }

    @Test
    fun planningAndError_states() {
        setScreen(RoutePlanUiState(planError = "在当前城市未找到合适的公交换乘方案"))
        composeTestRule.onNodeWithTag("route_plan_error").assertExists()
        composeTestRule.onNodeWithText("重新规划").tap()
        assertEquals(RoutePlanEvent.Retry, lastAction())
    }

    @Test
    fun saveDialog_savesWithChosenGroup() {
        var savedGroupId: String? = "uncalled"
        var savedName: String? = null
        composeTestRule.setContent {
            SaveFavoriteDialog(
                scheme = demoTabs[0].scheme,
                favoriteGroups = listOf(FavoriteGroup(id = "commute", name = "上班通勤"), FavoriteGroup(id = "home", name = "回家线路")),
                onDismiss = {},
                onConfirmSave = { groupId, name ->
                    savedGroupId = groupId
                    savedName = name
                },
                onCreateNewGroup = {}
            )
        }

        composeTestRule.onNodeWithText("存入收藏").assertIsDisplayed()
        composeTestRule.onNodeWithText("上班通勤").tap()
        composeTestRule.onNodeWithTag("btn_confirm_save_favorite").tap()
        composeTestRule.waitForIdle()

        assertEquals("commute", savedGroupId)
        assertEquals("s0", savedName)
    }

    // ---------------- Route：收藏后留在本页，Snackbar「去看看」 ----------------

    @Test
    fun route_saveFavorite_staysOnPage_snackbarGoToFavorites() {
        val shenzhen = TransitCity(cityId = "0755", cityName = "深圳")
        val settings = FakeSettingsRepository(initialCity = shenzhen)
        val realtime = FakeRealtimeRepository(currentCity = { shenzhen })
        val groupRepository = InMemoryRouteGroupRepository(Dispatchers.Unconfined)
        val alignment = RouteAlignmentService(settings, realtime, coordinator = null, ioDispatcher = Dispatchers.Unconfined)
        val gateway = FakeAmapRouteGateway().apply { planResult = { listOf(demoTabs[0].scheme) } }
        val viewModel = RoutePlanViewModel(
            routePlan = DefaultRoutePlanRepository(gateway, alignment, coordinator = null, ioDispatcher = Dispatchers.Unconfined),
            settings = settings,
            realtime = realtime,
            userLocationStore = FakeUserLocationStore(),
            groupRepository = groupRepository,
            groupSaver = RouteGroupSaver(groupRepository, alignment, FavoriteGroupSelection())
        )
        val trackingRepository = DefaultTrackingRepository(
            InMemoryTrackingSessionStore(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val routeDetailViewModel = RouteDetailViewModel(
            groupRepository = groupRepository,
            settings = settings,
            realtime = realtime,
            alignment = alignment,
            trackingRepository = trackingRepository,
            trackingController = FakeTrackingController(trackingRepository),
            ioDispatcher = Dispatchers.Unconfined,
            enableAutoRefresh = false
        )
        var wentToFavorites = 0
        composeTestRule.setContent {
            RoutePlanRoute(
                viewModel = viewModel,
                routeDetailViewModel = routeDetailViewModel,
                onNavigateToFavorites = { wentToFavorites++ },
                onNavigateToLineDetail = { _, _ -> }
            )
        }

        viewModel.planTransitRoute(AmapTipItem(name = "甲", location = "114.1,22.5"), AmapTipItem(name = "乙", location = "114.2,22.6"))
        composeTestRule.waitUntil(5_000) { viewModel.uiState.value.schemeTabs.isNotEmpty() }
        composeTestRule.onNodeWithTag("btn_save_favorite").tap()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithTag("btn_confirm_save_favorite").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("btn_confirm_save_favorite").tap()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText(RoutePlanViewModel.SAVED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
        }
        // 没有自动跳收藏页，方案还在
        assertEquals(0, wentToFavorites)
        composeTestRule.onNodeWithTag("btn_start_monitor").assertExists()
        assertTrue(groupRepository.groups.value.any { it.id == "s0" })

        composeTestRule.onNodeWithText("去看看").tap()
        composeTestRule.waitForIdle()
        assertEquals(1, wentToFavorites)
    }
}
