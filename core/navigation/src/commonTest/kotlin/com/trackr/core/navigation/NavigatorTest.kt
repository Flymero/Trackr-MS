package com.trackr.core.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.trackr.core.model.DataSourceType
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkParser
import com.trackr.core.navigation.deeplink.DeepLinkUris
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NavigatorTest {

    private val line49 = ScreenKey.LineDetail(
        cityId = "021",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.CHELAILE,
        lineName = "49路",
    )
    private val stop = ScreenKey.StopDetail(cityId = "021", stopId = "stop_555", stopName = "陕西南路")

    private fun newState(): NavigationState = NavigationState(
        start = TopLevel.start,
        current = mutableStateOf(TopLevel.start),
        stacks = TopLevel.tabs.associateWith { NavBackStack<NavKey>(it) },
    )

    @Test
    fun initialState_startsAtHomeRoot_withThreeTabStacks() {
        val state = newState()
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(ScreenKey.Home, state.currentKey)
        assertTrue(state.isAtTabRoot)
        assertEquals(listOf(ScreenKey.Home, ScreenKey.Lines, ScreenKey.RoutePlan, ScreenKey.More), TopLevel.tabs, "眉山特化：4 个页签（公交 / 线路 / 路线 / 设置）")
        assertEquals(TopLevel.tabs.toSet(), state.stacks.keys)
        TopLevel.tabs.forEach { tab -> assertEquals(listOf<NavKey>(tab), state.stacks.getValue(tab).toList()) }
        assertEquals(listOf<NavKey>(ScreenKey.Home), state.keysShown())
    }

    @Test
    fun open_pushesOnCurrentTab_andDedupsTop() {
        val state = newState()
        val navigator = Navigator(state)

        navigator.open(ScreenKey.Search)
        navigator.open(line49)
        navigator.open(line49) // 连点 / 重放：栈顶已是它，不再压栈

        assertEquals(listOf(ScreenKey.Home, ScreenKey.Search, line49), state.currentStack.toList())
        assertFalse(state.isAtTabRoot)
        // 别的页签的栈不受影响
        assertEquals(listOf<NavKey>(ScreenKey.More), state.stacks.getValue(ScreenKey.More).toList())
    }

    @Test
    fun open_sameKeyNotOnTop_isPushedAgain() {
        val state = newState()
        val navigator = Navigator(state)

        navigator.open(line49)
        navigator.open(stop)
        navigator.open(line49)

        assertEquals(listOf(ScreenKey.Home, line49, stop, line49), state.currentStack.toList())
    }

    @Test
    fun back_popsChildPagesOneByOne_thenReturnsFalseAtStartRoot() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(ScreenKey.Search)
        navigator.open(line49)

        assertTrue(navigator.back())
        assertEquals(ScreenKey.Search, state.currentKey)
        assertTrue(navigator.back())
        assertEquals(ScreenKey.Home, state.currentKey)
        assertFalse(navigator.back(), "首页根页按返回交给系统退出")
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.Home), state.currentStack.toList())
    }

    @Test
    fun back_atNonStartTabRoot_returnsToStartTab_keepingItsStack() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(line49)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)

        assertTrue(navigator.back())
        assertEquals(ScreenKey.More, state.topLevel)
        assertEquals(ScreenKey.More, state.currentKey)

        assertTrue(navigator.back())
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(line49, state.currentKey, "回到首页页签时它的子页还在")
    }

    @Test
    fun selectTab_switchingPreservesEachTabsHistory() {
        val state = newState()
        val navigator = Navigator(state)

        navigator.open(ScreenKey.Search)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.open(line49)

        navigator.selectTab(ScreenKey.Home)
        assertEquals(ScreenKey.Search, state.currentKey)
        navigator.selectTab(ScreenKey.More)
        assertEquals(ScreenKey.About, state.currentKey)
        navigator.selectTab(ScreenKey.RoutePlan)
        assertEquals(listOf(ScreenKey.RoutePlan, line49), state.currentStack.toList())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun selectTab_reselectingCurrentTab_popsToRoot_andEmitsReselect() = runTest {
        val state = newState()
        val navigator = Navigator(state)
        val reselected = mutableListOf<ScreenKey>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { navigator.reselect.toList(reselected) }

        navigator.open(ScreenKey.Search)
        navigator.open(line49)
        navigator.selectTab(ScreenKey.Home)

        assertEquals(listOf<NavKey>(ScreenKey.Home), state.currentStack.toList())
        assertEquals(listOf<ScreenKey>(ScreenKey.Home), reselected)

        // 切到别的页签不算再点
        navigator.selectTab(ScreenKey.More)
        assertEquals(listOf<ScreenKey>(ScreenKey.Home), reselected)
        job.cancel()
    }

    @Test
    fun selectTab_unknownTab_isRejected() {
        val navigator = Navigator(newState())
        assertFailsWith<IllegalArgumentException> { navigator.selectTab(ScreenKey.Search) }
        // 第 17 步「收藏」不再是页签
        assertFailsWith<IllegalArgumentException> { navigator.selectTab(ScreenKey.FavoritesGroup) }
    }

    @Test
    fun favoritesGroup_isChildPageOfHomeTab() {
        // 首页「编辑收藏」：压在首页栈上，返回回首页
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(ScreenKey.FavoritesGroup)
        assertEquals(listOf<NavKey>(ScreenKey.Home, ScreenKey.FavoritesGroup), state.currentStack.toList())
        assertFalse(state.isAtTabRoot, "子页不显示底栏")
        assertTrue(navigator.back())
        assertEquals(ScreenKey.Home, state.currentKey)

        // 规划页「存入收藏」后去收藏：切到首页页签再压栈
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.showInTab(ScreenKey.Home, ScreenKey.FavoritesGroup)
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.Home, ScreenKey.FavoritesGroup), state.currentStack.toList())
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())
    }

    @Test
    fun showInTab_neverStacksDuplicatesOrOnTopOfOtherChildPages() {
        val state = newState()
        val navigator = Navigator(state)
        val homeStack = state.stacks.getValue(ScreenKey.Home)

        // 首页栈停在线路详情：去收藏先回根，不叠成 [Home, LineDetail, FavoritesGroup]
        navigator.open(line49)
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.showInTab(ScreenKey.Home, ScreenKey.FavoritesGroup)
        assertEquals(listOf<NavKey>(ScreenKey.Home, ScreenKey.FavoritesGroup), homeStack.toList())

        // 收藏页上又开了线路详情：再去收藏退回已有的收藏页，不压第二个
        navigator.open(line49)
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.showInTab(ScreenKey.Home, ScreenKey.FavoritesGroup)
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.Home, ScreenKey.FavoritesGroup), homeStack.toList())

        // 已在栈顶：不变
        navigator.showInTab(ScreenKey.Home, ScreenKey.FavoritesGroup)
        assertEquals(listOf<NavKey>(ScreenKey.Home, ScreenKey.FavoritesGroup), homeStack.toList())
        assertFailsWith<IllegalArgumentException> { navigator.showInTab(ScreenKey.Search, line49) }
    }

    @Test
    fun popChildPage_popsOnlyWithinCurrentStack() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)

        navigator.popChildPage()
        assertEquals(ScreenKey.More, state.currentKey)
        navigator.popChildPage() // 已在根页：不切页签（和系统返回不同）
        assertEquals(ScreenKey.More, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.More), state.currentStack.toList())
    }

    @Test
    fun stacksInUse_putsStartTabUnderCurrentTab() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(line49)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)

        assertEquals(listOf(ScreenKey.Home, ScreenKey.More), state.stacksInUse)
        assertEquals(listOf(ScreenKey.Home, line49, ScreenKey.More, ScreenKey.About), state.keysShown(startUnderneath = true))

        navigator.selectTab(ScreenKey.Home)
        assertEquals(listOf(ScreenKey.Home), state.stacksInUse)
        assertEquals(listOf<NavKey>(ScreenKey.Home, line49), state.keysShown(startUnderneath = true))
    }

    @Test
    fun stacksShown_withoutStartUnderneath_showsOnlyCurrentTab() {
        // 第 16 步 MainActivity 用这种：和旧版一样，非首页页签根页没有上一页，按返回交给系统
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(line49)
        navigator.selectTab(ScreenKey.More)

        assertEquals(listOf(ScreenKey.More), state.stacksShown(startUnderneath = false))
        assertEquals(listOf<NavKey>(ScreenKey.More), state.keysShown(startUnderneath = false))

        navigator.open(ScreenKey.About)
        assertEquals(listOf<NavKey>(ScreenKey.More, ScreenKey.About), state.keysShown(startUnderneath = false))

        navigator.selectTab(ScreenKey.Home)
        assertEquals(listOf<NavKey>(ScreenKey.Home, line49), state.keysShown(startUnderneath = false))
    }

    @Test
    fun applyDeepLink_coldStart_buildsSyntheticStack_andBackReturnsToTabRootThenExits() {
        val state = newState()
        val navigator = Navigator(state)

        navigator.applyDeepLink(DeepLink.Stack(ScreenKey.Home, listOf(line49)))

        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf(ScreenKey.Home, line49), state.currentStack.toList())
        assertFalse(state.isAtTabRoot, "子页不显示底栏")
        assertTrue(navigator.back())
        assertEquals(listOf<NavKey>(ScreenKey.Home), state.currentStack.toList(), "按返回回到首页根页，不退出")
        assertFalse(navigator.back(), "首页根页再按返回才交给系统")
    }

    @Test
    fun applyDeepLink_warmStart_resetsTargetTabStack_leavesOtherTabsAlone() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(ScreenKey.Search)
        navigator.open(stop)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)

        navigator.applyDeepLink(DeepLink.Stack(ScreenKey.Home, listOf(line49)))

        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf(ScreenKey.Home, line49), state.currentStack.toList(), "首页栈重置为 [首页, 线路]，原来的子页不留")
        assertEquals(listOf(ScreenKey.More, ScreenKey.About), state.stacks.getValue(ScreenKey.More).toList(), "别的页签的栈不动")
        assertTrue(navigator.back())
        assertEquals(ScreenKey.Home, state.currentKey)
    }

    @Test
    fun applyDeepLink_replay_isIdempotent() {
        val state = newState()
        val navigator = Navigator(state)
        val link = DeepLink.Stack(ScreenKey.Home, listOf(line49))

        navigator.applyDeepLink(link)
        navigator.applyDeepLink(link) // 同一个通知 / 小组件 Intent 重放

        assertEquals(listOf(ScreenKey.Home, line49), state.currentStack.toList())
    }

    @Test
    fun applyDeepLink_multiLevelStack_skipsTabRootAndAdjacentDuplicates() {
        val state = newState()
        val navigator = Navigator(state)

        navigator.applyDeepLink(DeepLink.Stack(ScreenKey.Home, listOf(ScreenKey.Home, stop, stop, line49)))

        assertEquals(listOf(ScreenKey.Home, stop, line49), state.currentStack.toList())
    }

    @Test
    fun applyDeepLink_fromParser_landsOnHomeTab() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.selectTab(ScreenKey.RoutePlan)
        val link = DeepLinkParser.parse(
            DeepLinkUris.line("021", "line_49", DataSourceType.CHELAILE, stopId = "stop_004", targetOrder = 4, lineName = "49路")
        )

        navigator.applyDeepLink(assertIs<DeepLink.Stack>(link))

        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf(ScreenKey.Home, line49), state.currentStack.toList())
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())
    }

    @Test
    fun applyDeepLink_unknownTab_fallsBackToStartTab() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.selectTab(ScreenKey.More)

        navigator.applyDeepLink(DeepLink.Stack(ScreenKey.Search, listOf(line49)))

        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf(ScreenKey.Home, line49), state.currentStack.toList())
    }

    @Test
    fun onTabsChanged_currentTabHidden_clearsItAndReturnsToStart() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(ScreenKey.Search)
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.open(line49)

        navigator.onTabsChanged(setOf(ScreenKey.Home, ScreenKey.More))

        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(ScreenKey.Search, state.currentKey, "起始页签的栈保留")
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())
    }

    @Test
    fun onTabsChanged_currentTabStillVisible_isNoOp() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.selectTab(ScreenKey.More)
        navigator.open(ScreenKey.About)

        navigator.onTabsChanged(setOf(ScreenKey.Home, ScreenKey.More))

        assertEquals(ScreenKey.More, state.topLevel)
        assertEquals(ScreenKey.About, state.currentKey)
    }

    @Test
    fun isShowingRootOf_matchesOldShouldPollHomeTabCondition() {
        // 旧算法：selectedTab == "home" && homeBackStack.last() is Home（再加 Activity ≥ STARTED，由页面层判断）
        val state = newState()
        val navigator = Navigator(state)
        assertTrue(state.isShowingRootOf(ScreenKey.Home))

        navigator.open(line49)
        assertFalse(state.isShowingRootOf(ScreenKey.Home), "进详情停轮询")

        navigator.back()
        assertTrue(state.isShowingRootOf(ScreenKey.Home))

        navigator.selectTab(ScreenKey.More)
        assertFalse(state.isShowingRootOf(ScreenKey.Home), "切页签停轮询")
        assertTrue(state.isShowingRootOf(ScreenKey.More))
    }

    @Test
    fun visibleTabs_withoutMapKey_dropsOnlyRoutePlan_keepingOrder() {
        assertEquals(TopLevel.tabs, TopLevel.visibleTabs(hasMapKey = true))
        assertEquals(
            listOf(ScreenKey.Home, ScreenKey.Lines, ScreenKey.More),
            TopLevel.visibleTabs(hasMapKey = false)
        )
        assertTrue(TopLevel.start in TopLevel.visibleTabs(hasMapKey = false), "起始页签总在")
    }

    @Test
    fun visibleTabs_linesTabHiddenOutsideMeishan() {
        // 眉山特化：「线路」只在眉山 / 仁寿显示；与高德 Key 互不影响
        assertEquals(
            listOf(ScreenKey.Home, ScreenKey.RoutePlan, ScreenKey.More),
            TopLevel.visibleTabs(hasMapKey = true, showLinesTab = false)
        )
        assertEquals(
            listOf(ScreenKey.Home, ScreenKey.More),
            TopLevel.visibleTabs(hasMapKey = false, showLinesTab = false)
        )
    }

    @Test
    fun visibleTabs_keyClearedWhileOnRoutePlan_returnsHome_andKeyBackLeavesStateAlone() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.selectTab(ScreenKey.RoutePlan)
        navigator.open(line49)

        navigator.onTabsChanged(TopLevel.visibleTabs(hasMapKey = false).toSet())
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())

        // Key 填回来：页签重新出现，但不自动切过去
        navigator.onTabsChanged(TopLevel.visibleTabs(hasMapKey = true).toSet())
        assertEquals(ScreenKey.Home, state.topLevel)
    }

    // ---- 「上了哪辆？」弹层（OverlayKey） ----

    @Test
    fun overlayOnTabRoot_stillCountsAsTabRoot_andBackClosesIt() {
        val state = newState()
        val navigator = Navigator(state)
        val sheet = ScreenKey.ChooseBoardedBus("s-1")

        navigator.open(sheet)
        navigator.open(sheet) // 通知重放：不叠第二个

        assertEquals(listOf<NavKey>(ScreenKey.Home, sheet), state.currentStack.toList())
        assertTrue(state.isAtTabRoot, "根页上叠弹层：底栏照常显示")
        assertTrue(navigator.back())
        assertEquals(listOf<NavKey>(ScreenKey.Home), state.currentStack.toList())
    }

    @Test
    fun overlayOnChildPage_isNotTabRoot() {
        val state = newState()
        val navigator = Navigator(state)
        navigator.open(stop)
        navigator.open(ScreenKey.ChooseBoardedBus("s-1"))

        assertFalse(state.isAtTabRoot)
    }

    @Test
    fun dismiss_removesOnlyThatOverlay_evenIfNotOnTop() {
        val state = newState()
        val navigator = Navigator(state)
        val sheet = ScreenKey.ChooseBoardedBus("s-1")
        navigator.open(stop)
        navigator.open(sheet)

        navigator.dismiss(sheet)
        assertEquals(listOf<NavKey>(ScreenKey.Home, stop), state.currentStack.toList())

        // 已经被返回关掉了再 dismiss：什么都不做，不会把站点页也退掉
        navigator.dismiss(sheet)
        assertEquals(listOf<NavKey>(ScreenKey.Home, stop), state.currentStack.toList())
        // 页签根页不会被拿掉
        navigator.dismiss(ScreenKey.Home)
        assertEquals(listOf<NavKey>(ScreenKey.Home, stop), state.currentStack.toList())
    }
}
