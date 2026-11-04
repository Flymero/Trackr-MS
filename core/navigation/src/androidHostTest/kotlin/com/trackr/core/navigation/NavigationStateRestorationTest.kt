package com.trackr.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.trackr.core.model.DataSourceType
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 进程重建 / 配置变更后导航状态能恢复（§3.8），每条栈各自的 decorator 在切页签时不丢状态（修 N2），
 * 以及 [TrackrSavedStateConfiguration] 登记了全部 [ScreenKey]。
 */
@RunWith(RobolectricTestRunner::class)
class NavigationStateRestorationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val line49 = ScreenKey.LineDetail(
        cityId = "021", lineId = "line_49", targetOrder = 4, stopId = "stop_004",
        source = DataSourceType.MYGOLBS, lineName = "49路",
    )
    private val stop = ScreenKey.StopDetail(
        cityId = "021", stopId = "stop_555", stopName = "陕西南路",
        physicalStId = "pst_001", namesakeStId = "nst_001", lat = 31.22, lng = 121.45,
    )

    /** 每个 ScreenKey 子类一个样本；新增子类时这里和 TrackrSavedStateConfiguration 都要加。 */
    private val oneOfEachKey: List<ScreenKey> = listOf(
        ScreenKey.Home, ScreenKey.RoutePlan, ScreenKey.Lines, ScreenKey.FavoritesGroup, ScreenKey.Search,
        line49, stop, ScreenKey.More, ScreenKey.About,
        ScreenKey.RouteDetail(groupId = "g1"), ScreenKey.FavoritesEdit,
        ScreenKey.ChooseBoardedBus(sessionId = "s-1"),
        ScreenKey.ReminderEdit(reminderId = "r1"), ScreenKey.AmapKey,
    )

    class PageViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private lateinit var state: NavigationState
    private lateinit var navigator: Navigator
    private val counts = mutableMapOf<NavKey, Int>()
    private val increments = mutableMapOf<NavKey, () -> Unit>()
    private val viewModels = mutableMapOf<NavKey, PageViewModel>()

    @Composable
    private fun Host(tabs: List<ScreenKey> = TopLevel.tabs) {
        val navigationState = rememberNavigationState(topLevel = tabs)
        val nav = remember(navigationState) { Navigator(navigationState) }
        SideEffect {
            state = navigationState
            navigator = nav
        }
        NavDisplay(
            // 和 MainActivity 一样只显示当前页签的栈：切页签时另一条栈整个离开 NavDisplay，正好验证每栈 decorator 不丢状态
            entries = navigationState.toDecoratedEntries(startUnderneath = false) { key -> NavEntry(key) { Page(key) } },
            onBack = { nav.back() },
        )
    }

    @Composable
    private fun Page(key: NavKey) {
        var count by rememberSaveable { mutableIntStateOf(0) }
        val vm = viewModel { PageViewModel() }
        val shown = count // 在组合里读，改了才会重组
        SideEffect {
            counts[key] = shown
            increments[key] = { count++ }
            viewModels[key] = vm
        }
    }

    private fun act(block: () -> Unit) {
        composeTestRule.runOnIdle(block)
        composeTestRule.waitForIdle()
    }

    @Test
    fun emulatedRestore_keepsAllStacksAndCurrentTab() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { Host() }

        act {
            navigator.open(ScreenKey.Search)
            navigator.open(line49)
            navigator.selectTab(ScreenKey.RoutePlan)
            navigator.open(stop)
            navigator.selectTab(ScreenKey.More)
            navigator.open(ScreenKey.About)
        }
        val before = state.stacks.mapValues { (_, stack) -> stack.toList() }
        val stateBefore = state

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertNotSame("恢复后应是重新组合出来的新状态", stateBefore, state)
        assertEquals(ScreenKey.More, state.topLevel)
        assertEquals(before, state.stacks.mapValues { (_, stack) -> stack.toList() })
        assertEquals(listOf(ScreenKey.Home, ScreenKey.Search, line49), before[ScreenKey.Home])
        assertEquals(ScreenKey.About, state.currentKey)

        // 恢复后的导航照常工作：返回两次回到首页页签，它的子页还在
        act { navigator.back(); navigator.back() }
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(line49, state.currentKey)
    }

    @Test
    fun emulatedRestore_afterTabRemoved_eachStackStaysWithItsTab() {
        // 第 17 步前是 4 个页签（收藏在第 3 位）；存档后页签少了一个，「更多」不能拿到收藏那条栈
        val fourTabs = listOf(ScreenKey.Home, ScreenKey.RoutePlan, ScreenKey.FavoritesGroup, ScreenKey.More)
        var tabs = fourTabs
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { Host(tabs) }

        act {
            navigator.open(line49)
            navigator.selectTab(ScreenKey.FavoritesGroup)
            navigator.open(stop)
            navigator.selectTab(ScreenKey.More)
            navigator.open(ScreenKey.About)
            navigator.selectTab(ScreenKey.FavoritesGroup)
        }

        tabs = TopLevel.tabs
        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(TopLevel.tabs.toSet(), state.stacks.keys)
        assertEquals("当前页签已不存在：回起始页签", ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.Home, line49), state.stacks.getValue(ScreenKey.Home).toList())
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())
        assertEquals(listOf<NavKey>(ScreenKey.More, ScreenKey.About), state.stacks.getValue(ScreenKey.More).toList())
    }

    @Test
    fun emulatedRestore_keepsPageSaveableState() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { Host() }

        act { navigator.open(line49) }
        act { increments.getValue(line49)(); increments.getValue(line49)() }
        assertEquals(2, counts[line49])

        restorationTester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(line49, state.currentKey)
        assertEquals(2, counts[line49])
    }

    @Test
    fun switchingTabs_keepsOtherStacksSaveableStateAndViewModel() {
        composeTestRule.setContent { Host() }

        act { increments.getValue(ScreenKey.Home)() }
        val homeVm = viewModels.getValue(ScreenKey.Home)
        act { navigator.selectTab(ScreenKey.More) }
        act { navigator.open(ScreenKey.About) }
        val aboutVm = viewModels.getValue(ScreenKey.About)

        act { navigator.selectTab(ScreenKey.Home) }
        assertEquals("切走再回来首页的页内状态还在", 1, counts[ScreenKey.Home])
        assertSame(homeVm, viewModels.getValue(ScreenKey.Home))
        assertFalse(homeVm.cleared)

        act { navigator.selectTab(ScreenKey.More) }
        assertSame("「更多」栈的子页 ViewModel 没被切页签清掉", aboutVm, viewModels.getValue(ScreenKey.About))
        assertFalse(aboutVm.cleared)
    }

    @Test
    fun poppingEntry_clearsOnlyItsViewModel() {
        composeTestRule.setContent { Host() }

        act { navigator.open(line49) }
        val lineVm = viewModels.getValue(line49)
        act { increments.getValue(line49)() }

        act { navigator.back() }
        assertTrue("弹出的页面 ViewModel 被清掉", lineVm.cleared)
        assertFalse(viewModels.getValue(ScreenKey.Home).cleared)

        act { navigator.open(line49) }
        assertNotSame(lineVm, viewModels.getValue(line49))
        assertEquals("重新打开是新页面，页内状态从头开始", 0, counts[line49])
    }

    @Test
    fun configuration_roundTripsEveryScreenKeyInABackStack() {
        val sealedSubclassNames = ScreenKey.serializer().descriptor.getElementDescriptor(1)
            .let { value -> (0 until value.elementsCount).map { value.getElementName(it) } }
            .toSet()
        assertEquals(
            "样本要覆盖 ScreenKey 的全部子类",
            sealedSubclassNames,
            oneOfEachKey.map(::serialNameOf).toSet(),
        )

        val serializer = NavBackStackSerializer(PolymorphicSerializer(NavKey::class))
        val stack = NavBackStack<NavKey>(*oneOfEachKey.toTypedArray())
        val saved = encodeToSavedState(serializer, stack, TrackrSavedStateConfiguration)
        val restored = decodeFromSavedState(serializer, saved, TrackrSavedStateConfiguration)

        assertEquals(oneOfEachKey, restored.toList())
    }

    @Test(expected = SerializationException::class)
    fun defaultConfiguration_cannotSaveScreenKeys() {
        // 没登记多态子类就存不了（非 Android 平台没有反射兜底），所以必须用 TrackrSavedStateConfiguration
        val serializer = NavBackStackSerializer(PolymorphicSerializer(NavKey::class))
        encodeToSavedState(serializer, NavBackStack<NavKey>(ScreenKey.Home), SavedStateConfiguration.DEFAULT)
    }

    // when 穷举 sealed 子类：新增子类不改这里就编不过，提醒同时登记到 TrackrSavedStateConfiguration
    private fun serialNameOf(key: ScreenKey): String =
        when (key) {
            ScreenKey.Home -> ScreenKey.Home.serializer()
            ScreenKey.RoutePlan -> ScreenKey.RoutePlan.serializer()
            ScreenKey.Lines -> ScreenKey.Lines.serializer()
            ScreenKey.FavoritesGroup -> ScreenKey.FavoritesGroup.serializer()
            ScreenKey.Search -> ScreenKey.Search.serializer()
            is ScreenKey.LineDetail -> ScreenKey.LineDetail.serializer()
            is ScreenKey.StopDetail -> ScreenKey.StopDetail.serializer()
            ScreenKey.More -> ScreenKey.More.serializer()
            ScreenKey.About -> ScreenKey.About.serializer()
            is ScreenKey.RouteDetail -> ScreenKey.RouteDetail.serializer()
            ScreenKey.FavoritesEdit -> ScreenKey.FavoritesEdit.serializer()
            is ScreenKey.ChooseBoardedBus -> ScreenKey.ChooseBoardedBus.serializer()
            is ScreenKey.ReminderEdit -> ScreenKey.ReminderEdit.serializer()
            ScreenKey.AmapKey -> ScreenKey.AmapKey.serializer()
        }.descriptor.serialName
}
