package com.trackr.feature.bus.elder

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.UiMode
import com.trackr.feature.bus.home.BusHomeEvent
import com.trackr.feature.bus.home.BusHomeMapper
import com.trackr.feature.bus.home.BusHomeUiState
import com.trackr.feature.bus.home.UserMessage
import com.trackr.ui.components.elder.SHOW_TRANSIT_CODE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 长辈版首页（§8.7）：可点元素都 ≥ 64dp、没有底部页签和地图入口、没有分组 chips / 编辑、「出示乘车码」一直在、
 * 浅 / 深 × 字号 1.0 / 1.5 / 2.0 倍下文字不被裁并生成截图（原生图形模式，和 ScreenshotGeneratorTest 同一手段）、状态恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ElderBusHomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<BusHomeEvent>()

    private val fav = FavoriteStationLine(
        id = "f1",
        cityId = "051",
        cityName = "深圳",
        lineId = "l325",
        lineName = "325",
        startStop = "白石洲",
        endStop = "科技园",
        targetStopId = "s1",
        targetStopName = "科技园站",
        targetOrder = 5
    )

    private fun trackingSession(): TrackingSession = TrackingSession(
        sessionId = "x",
        generation = 1,
        target = TrackingTarget.SingleLine(cityId = "051", lineId = "l325", lineName = "325", targetOrder = 5),
        leg = TrackingLegParams(cityId = "051", lineId = "l325", lineName = "325"),
        state = TrackingSessionState(
            isActive = true,
            cityId = "051",
            lineId = "l325",
            lineName = "325",
            targetOrder = 5,
            targetStopName = "科技园站",
            stopsRemaining = 2,
            etaSeconds = 240
        )
    )

    private fun stateOf(
        withFavorites: Boolean = true,
        tracking: Boolean = false,
        selectedGroupId: String? = null,
        messages: List<UserMessage> = emptyList()
    ): BusHomeUiState {
        val nearby = (1..4).map { i ->
            NearbyStop(
                stopId = "n$i",
                name = "高新园$i 站",
                distanceMeters = 100 * i,
                lines = listOf(
                    NearbyStopLineStatus(
                        line = BusLineDirection(lineId = "l19_$i", name = "M194", startStop = "西丽", endStop = "蛇口港"),
                        targetOrder = 6,
                        targetStopId = "n$i",
                        targetStopName = "高新园站",
                        approachingBuses = listOf(LiveBus(busId = "b", plateOrFleetNo = "", order = 2, etaSeconds = 540))
                    ),
                    NearbyStopLineStatus(
                        line = BusLineDirection(lineId = "l26_$i", name = "26", startStop = "南头", endStop = "福田口岸"),
                        targetOrder = 3,
                        targetStopId = "n$i",
                        targetStopName = "高新园站",
                        preArrivalTime = "07:20"
                    )
                )
            )
        }
        val raw = BusHomeUiState(
            currentCity = TransitCity(cityId = "051", cityName = "深圳", province = "广东"),
            favorites = if (withFavorites) listOf(fav) else emptyList(),
            favoriteItems = if (withFavorites) listOf(FavoriteItem.Stop(fav, groupId = "g1", sortOrder = 0)) else emptyList(),
            // 收藏的车现在没车：给下一班始发发车时间
            favoritesRealtime = mapOf(
                "f1" to NearbyStop(
                    stopId = "s1",
                    name = "科技园站",
                    lines = listOf(
                        NearbyStopLineStatus(
                            line = BusLineDirection(lineId = "l325", name = "325", startStop = "白石洲", endStop = "科技园"),
                            targetOrder = 5,
                            targetStopId = "s1",
                            targetStopName = "科技园站",
                            preArrivalTime = "07:20"
                        )
                    )
                )
            ),
            favoriteGroups = listOf(FavoriteGroup(id = "g1", name = "上班"), FavoriteGroup(id = "g2", name = "回家")),
            selectedGroupId = selectedGroupId,
            trackingSessions = if (tracking) listOf(trackingSession()) else emptyList(),
            nearbyStops = nearby,
            userMessages = messages
        )
        return BusHomeMapper.derive(BusHomeUiState(), raw)
    }

    private fun setContent(state: BusHomeUiState, fontScale: Float = 1f, darkTheme: Boolean = false) {
        composeTestRule.setContent { ElderHome(state, fontScale, darkTheme) }
    }

    @Composable
    private fun ElderHome(state: BusHomeUiState, fontScale: Float, darkTheme: Boolean = false) {
        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
            TrackrTheme(uiMode = UiMode.Elder, darkTheme = darkTheme) {
                ElderBusHomeScreen(state = state, onEvent = { events += it })
            }
        }
    }

    // ---- 尺寸 + 截图：浅 / 深 × 字号 1.0 / 1.5 / 2.0 ----

    @Test
    fun light_fontScale1() = assertFits(1f, darkTheme = false)

    @Test
    fun light_fontScale15() = assertFits(1.5f, darkTheme = false)

    @Test
    fun light_fontScale2() = assertFits(2f, darkTheme = false)

    @Test
    fun dark_fontScale1() = assertFits(1f, darkTheme = true)

    @Test
    fun dark_fontScale15() = assertFits(1.5f, darkTheme = true)

    @Test
    fun dark_fontScale2() = assertFits(2f, darkTheme = true)

    /**
     * 从上滚到下，每一屏都查：可点元素 ≥ 64dp、文字不被裁、「出示乘车码」在。
     * 首屏截图写到 build/screenshots/elder/（只生成，不比对；工程没有截图比对库，§5.4）。
     */
    private fun assertFits(scale: Float, darkTheme: Boolean) {
        setContent(stateOf(tracking = true), fontScale = scale, darkTheme = darkTheme)
        ElderScreenshots.capture(composeTestRule, "home", scale, darkTheme)
        val anchors = listOf("科技园站上车", "高新园1 站", "高新园2 站", "高新园3 站")
        anchors.forEach { anchor ->
            composeTestRule.onNodeWithTag(ElderBusHomeTags.LIST).performScrollToNode(hasText(anchor))
            ElderLayoutChecks.assertClickablesAtLeast64dp(composeTestRule)
            ElderLayoutChecks.assertNoTextClipped(composeTestRule, scale)
            composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed()
        }
    }

    // ---- 结构 ----

    @Test
    fun noTabsNoMapNoGroupChipsNoEdit() {
        setContent(stateOf(tracking = true))

        listOf("公交", "路线", "我的", "地图", "规划", "全部", "上班", "回家", "编辑").forEach { text ->
            composeTestRule.onNodeWithText(text).assertDoesNotExist()
        }
        composeTestRule.onNodeWithText("设置").assertIsDisplayed()
        composeTestRule.onNodeWithText("出示乘车码").assertIsDisplayed()
    }

    @Test
    fun showsAllFavorites_evenIfStandardSelectedAnotherGroup() {
        // 标准版选了「回家」分组（同一个 ViewModel），长辈版仍列全部收藏
        setContent(stateOf(selectedGroupId = "g2"))
        composeTestRule.onNodeWithText("科技园站上车").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        // 没车时给下一班实际发车时间；在途首页只显示剩余站数
        composeTestRule.onNodeWithText("下一班 07:20").assertIsDisplayed()
        composeTestRule.onNodeWithText("从 白石洲 发车").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ElderBusHomeTags.LIST).performScrollToNode(hasText("高新园1 站"))
        composeTestRule.onNodeWithText("还有 4 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("约 9 分钟").assertDoesNotExist()
    }

    @Test
    fun nearby_onlyThreeStops_nextDepartureForNoBus() {
        setContent(stateOf(withFavorites = false))
        composeTestRule.onNodeWithText("点一辆车，再点「收藏」，它就会放到最上面。").assertIsDisplayed()
        val list = composeTestRule.onNodeWithTag(ElderBusHomeTags.LIST)
        list.performScrollToNode(hasText("高新园3 站"))
        composeTestRule.onNodeWithText("高新园4 站").assertDoesNotExist()
    }

    @Test
    fun transitCode_visibleAfterScroll_oneTap() {
        setContent(stateOf().copy(showTransitCodeButton = true))
        composeTestRule.onNodeWithTag(ElderBusHomeTags.LIST).performScrollToNode(hasText("高新园3 站"))
        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertIsDisplayed().performClick()
        assertEquals(BusHomeEvent.TransitCodeClicked, events.last())
    }

    @Test
    fun transitCode_hiddenWhenSettingDisabled() {
        setContent(stateOf().copy(showTransitCodeButton = false))

        composeTestRule.onNodeWithTag(SHOW_TRANSIT_CODE_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("深圳").assertIsDisplayed()
        composeTestRule.onNodeWithText("科技园站上车").assertIsDisplayed()
    }

    // ---- 事件 ----

    @Test
    fun settings_search_favorite_nearbyLine_events() {
        setContent(stateOf())

        composeTestRule.onNodeWithTag(ElderBusHomeTags.SETTINGS).performClick()
        assertEquals(BusHomeEvent.OpenSettings, events.last())

        composeTestRule.onNodeWithTag(ElderBusHomeTags.SEARCH).performClick()
        assertEquals(BusHomeEvent.OpenSearch, events.last())

        composeTestRule.onNodeWithTag(ElderBusHomeTags.favorite("stop_f1")).performClick()
        val open = events.last() as BusHomeEvent.OpenLine
        assertEquals("l325", open.target.lineId)
        assertEquals(5, open.target.targetOrder)

        composeTestRule.onNodeWithTag(ElderBusHomeTags.CITY).performClick()
        assertEquals(BusHomeEvent.OpenCityPicker, events.last())
    }

    @Test
    fun readAloud_favoriteAndTrackingCards_sendCardSentence() {
        setContent(stateOf(tracking = true))

        composeTestRule.onNodeWithTag(ElderBusHomeTags.TRACKING_READ_ALOUD).performClick()
        assertEquals(BusHomeEvent.ReadAloud("325路，还有 2 站，大约 4 分钟到科技园站"), events.last())

        // 滚到下一节标题，车卡的按钮就不在底部「出示乘车码」后面了
        composeTestRule.onNodeWithTag(ElderBusHomeTags.LIST).performScrollToNode(hasText("离你最近"))
        composeTestRule.onNodeWithTag(ElderBusHomeTags.readAloud("stop_f1")).performClick()
        assertEquals(BusHomeEvent.ReadAloud("325路，开往科技园，等待发车，下一班 7点20分 从 白石洲 发车"), events.last())
    }

    @Test
    fun stopTracking_asksFirst() {
        setContent(stateOf(tracking = true))

        composeTestRule.onNodeWithTag(ElderBusHomeTags.STOP_TRACKING).performClick()
        assertFalse(events.any { it is BusHomeEvent.StopTracking })
        composeTestRule.onNodeWithText("不再提醒这班车了吗？").assertIsDisplayed()
        composeTestRule.onNodeWithText("继续提醒").performClick()
        assertFalse(events.any { it is BusHomeEvent.StopTracking })

        composeTestRule.onNodeWithTag(ElderBusHomeTags.STOP_TRACKING).performClick()
        // 对话框里的确认按钮（卡片上那个也叫「不用叫了」，对话框在最后）
        val buttons = composeTestRule.onAllNodes(hasText("不用叫了") and hasClickAction())
        buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
        assertEquals(BusHomeEvent.StopTracking("x"), events.last())
    }

    @Test
    fun message_staysUntilGotIt() {
        setContent(stateOf(messages = listOf(UserMessage(id = 7, text = "已收藏"))))

        composeTestRule.mainClock.advanceTimeBy(10_000)
        composeTestRule.onNodeWithText("已收藏").assertIsDisplayed()
        assertFalse(events.any { it is BusHomeEvent.MessageShown })
        composeTestRule.onNodeWithText("知道了").performClick()
        assertEquals(BusHomeEvent.MessageShown(7), events.last())
    }

    @Test
    fun message_usesElderWording() {
        setContent(
            stateOf(
                messages = listOf(
                    UserMessage(id = 3, text = "附近站没刷出来，下拉再试一次", elderText = "附近站没刷出来，点下面的「重新定位」再试一次")
                )
            )
        )

        composeTestRule.onNodeWithText("附近站没刷出来，点下面的「重新定位」再试一次").assertIsDisplayed()
        composeTestRule.onNodeWithText("附近站没刷出来，下拉再试一次").assertDoesNotExist()
        composeTestRule.onNodeWithText("知道了").performClick()
        assertEquals(BusHomeEvent.MessageShown(3), events.last())
    }

    @Test
    fun confirmDialog_survivesRecreation() {
        val tester = StateRestorationTester(composeTestRule)
        tester.setContent { ElderHome(stateOf(tracking = true), 1f) }

        composeTestRule.onNodeWithTag(ElderBusHomeTags.STOP_TRACKING).performClick()
        composeTestRule.onNodeWithText("不再提醒这班车了吗？").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("不再提醒这班车了吗？").assertIsDisplayed()
    }
}
