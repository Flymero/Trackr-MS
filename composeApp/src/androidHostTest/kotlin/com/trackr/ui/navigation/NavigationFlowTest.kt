package com.trackr.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.model.DataSourceType
import com.trackr.core.navigation.NavigationState
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TopLevel
import com.trackr.core.testing.FakeAmapKeyRepository
import com.trackr.core.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D2（第 11 步）：高德 Key 两槽 → `hasMapKey` → 底栏页签 → [Navigator]，走的是 MainActivity 用的同一个 [rememberVisibleTabs]。
 * 有 / 无 Key、只有 Web Key、只有 Android Key，以及停在「路线规划」时 Key 被清空。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavigationFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val line = ScreenKey.LineDetail(
        cityId = "021",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.CHELAILE,
        lineName = "49路",
    )

    private fun newState() = NavigationState(
        start = TopLevel.start,
        current = mutableStateOf(TopLevel.start),
        stacks = TopLevel.tabs.associateWith { NavBackStack<NavKey>(it) },
    )

    /** 最近一次组合时 [rememberVisibleTabs] 返回的页签 */
    private var visibleTabs: List<ScreenKey> = emptyList()

    /** 把底栏渲染成一排带 testTag 的文字，返回导航状态 */
    private fun render(
        amapKeys: FakeAmapKeyRepository,
        state: NavigationState = newState(),
        settings: FakeSettingsRepository = FakeSettingsRepository()
    ): NavigationState {
        composeTestRule.setContent {
            val navigator = remember { Navigator(state) }
            val tabs = rememberVisibleTabs(amapKeys, settings, navigator)
            visibleTabs = tabs
            Column {
                tabs.forEach { key -> Text(key.toString(), Modifier.testTag("tab_$key")) }
            }
        }
        composeTestRule.waitForIdle()
        return state
    }

    /**
     * 第 17 步起底栏页签：首页、路线规划、更多（「收藏」不再是页签）；
     * 眉山特化：「线路」页签只在眉山 / 仁寿出现，默认城市（上海）看不到。
     * 没 Key 时只剩首页、更多。
     */
    private fun assertRouteTabShown(shown: Boolean) {
        val node = composeTestRule.onNodeWithTag("tab_${ScreenKey.RoutePlan}")
        if (shown) node.assertExists() else node.assertDoesNotExist()
        composeTestRule.onNodeWithTag("tab_${ScreenKey.Home}").assertExists()
        composeTestRule.onNodeWithTag("tab_${ScreenKey.More}").assertExists()
        composeTestRule.onNodeWithTag("tab_${ScreenKey.FavoritesGroup}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tab_${ScreenKey.Lines}").assertDoesNotExist()
        val expected = if (shown) {
            listOf(ScreenKey.Home, ScreenKey.RoutePlan, ScreenKey.More)
        } else {
            listOf(ScreenKey.Home, ScreenKey.More)
        }
        composeTestRule.runOnIdle { assertEquals(expected, visibleTabs) }
    }

    @Test
    fun noKey_hidesRoutePlanTab() {
        render(FakeAmapKeyRepository())
        assertRouteTabShown(false)
    }

    @Test
    fun bothKeys_showRoutePlanTab() {
        render(FakeAmapKeyRepository(androidKey = "and", webKey = "web"))
        assertRouteTabShown(true)
    }

    @Test
    fun onlyWebKey_showsRoutePlanTab() {
        // Q18：只有 Web Key 也显示（能搜能算，不画地图）
        val keys = FakeAmapKeyRepository(webKey = "web")
        render(keys)
        assertRouteTabShown(true)
        assertEquals(false, keys.canShowMap.value)
    }

    @Test
    fun onlyAndroidKey_showsRoutePlanTab() {
        val keys = FakeAmapKeyRepository(androidKey = "and")
        render(keys)
        assertRouteTabShown(true)
        assertEquals(true, keys.canShowMap.value)
    }

    @Test
    fun buildTimeKeyOnly_showsRoutePlanTab() {
        render(FakeAmapKeyRepository(defaults = AmapKeyDefaults(webKey = "build_web")))
        assertRouteTabShown(true)
    }

    @Test
    fun keyClearedWhileOnRoutePlan_tabDisappears_andReturnsHomeWithRouteStackCleared() {
        val keys = FakeAmapKeyRepository(webKey = "web")
        val state = newState()
        Navigator(state).run {
            open(ScreenKey.Search)
            selectTab(ScreenKey.RoutePlan)
            open(line)
        }
        render(keys, state)
        assertRouteTabShown(true)
        assertEquals(ScreenKey.RoutePlan, state.topLevel)

        composeTestRule.runOnIdle { keys.setWebKey(null) }
        composeTestRule.waitForIdle()

        assertRouteTabShown(false)
        assertEquals(ScreenKey.Home, state.topLevel)
        assertEquals(listOf<NavKey>(ScreenKey.RoutePlan), state.stacks.getValue(ScreenKey.RoutePlan).toList())
        assertEquals("首页的栈保留", ScreenKey.Search, state.currentKey)
    }

    @Test
    fun savedOnRoutePlanButNoKeyAtStart_opensHome() {
        // 进程重建时恢复到「路线规划」，但 Key 已经没了：第一次组合就回首页
        val state = newState()
        Navigator(state).selectTab(ScreenKey.RoutePlan)
        render(FakeAmapKeyRepository(), state)
        assertEquals(ScreenKey.Home, state.topLevel)
    }

    @Test
    fun keyAddedLater_showsTabWithoutSwitching() {
        val keys = FakeAmapKeyRepository()
        val state = render(keys)
        assertRouteTabShown(false)

        composeTestRule.runOnIdle { keys.setAndroidKey("and") }
        composeTestRule.waitForIdle()

        assertRouteTabShown(true)
        assertEquals(ScreenKey.Home, state.topLevel)
    }
}
