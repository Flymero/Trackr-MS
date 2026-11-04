package com.trackr.feature.bus.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
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
import com.trackr.core.model.WatchLine
import com.trackr.core.model.WatchedLine
import com.trackr.ui.components.transitcode.TRANSIT_CODE_FAB_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v3 公交首页（无状态 Screen）：标题是城市名、没有乘车码卡、悬浮按钮、收藏合一 + chips、一起等折叠、附近站 */
@RunWith(RobolectricTestRunner::class)
class BusHomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<BusHomeEvent>()

    private val shenzhen = TransitCity(cityId = "051", cityName = "深圳", province = "广东")

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

    private fun watchSession(lineCount: Int): TrackingSession {
        val lines = (1..lineCount).map { i -> WatchLine(lineId = "w$i", lineName = "L$i", stopOrder = 5) }
        val watched = lines.mapIndexed { i, line ->
            WatchedLine(line = line, busId = "b$i", stopsRemaining = i + 1, etaSeconds = (i + 1) * 120, hasData = true)
        }
        val target = TrackingTarget.WatchTogether(cityId = "051", stopId = "s1", stopName = "科技园站", lines = lines)
        return TrackingSession(
            sessionId = "x",
            generation = 1,
            target = target,
            leg = TrackingLegParams(cityId = "051", lineId = "w1", lineName = "L1"),
            state = TrackingSessionState(isActive = true, cityId = "051", lineId = "w1", lineName = "L1", targetOrder = 5),
            watchedLines = watched
        )
    }

    private fun stateOf(
        session: TrackingSession? = null,
        withFavorites: Boolean = true,
        groups: List<FavoriteGroup> = emptyList(),
        nearbyLineName: String = "19"
    ): BusHomeUiState {
        val nearby = NearbyStop(
            stopId = "n1",
            name = "高新园站",
            distanceMeters = 120,
            lines = listOf(
                NearbyStopLineStatus(
                    line = BusLineDirection(lineId = "l19", name = nearbyLineName, startStop = "西丽", endStop = "蛇口港"),
                    targetOrder = 6,
                    targetStopId = "n1",
                    targetStopName = "高新园站",
                    approachingBuses = listOf(LiveBus(busId = "b", plateOrFleetNo = "", order = 2, etaSeconds = 540))
                )
            )
        )
        val raw = BusHomeUiState(
            currentCity = shenzhen,
            favorites = if (withFavorites) listOf(fav) else emptyList(),
            favoriteItems = if (withFavorites) listOf(FavoriteItem.Stop(fav, groupId = null, sortOrder = 0)) else emptyList(),
            favoriteGroups = groups,
            trackingSession = session,
            nearbyStops = listOf(nearby)
        )
        return BusHomeMapper.derive(BusHomeUiState(), raw)
    }

    private fun setContent(state: BusHomeUiState) {
        composeTestRule.setContent {
            TrackrTheme {
                BusHomeScreen(state = state, onEvent = { events += it })
            }
        }
    }

    @Test
    fun standardHome_hasNoSettingsButton() {
        // 「设置」只在长辈版首页（ElderBusHomeScreen）；标准版从底部「我的」页签进
        setContent(stateOf())
        composeTestRule.onNodeWithText("设置").assertDoesNotExist()
    }

    @Test
    fun titleIsCityName_noTransitCodeCard_fabOpensTransitCode() {
        setContent(stateOf())

        composeTestRule.onNodeWithText("深圳").assertIsDisplayed()
        composeTestRule.onNodeWithText("支付宝乘车码").assertDoesNotExist()
        composeTestRule.onNodeWithText("车在路上").assertDoesNotExist()

        composeTestRule.onNodeWithTag(TRANSIT_CODE_FAB_TAG).performClick()
        assertEquals(listOf<BusHomeEvent>(BusHomeEvent.TransitCodeClicked), events)

        composeTestRule.onNodeWithTag(BusHomeTags.CITY_TITLE).performClick()
        assertEquals(BusHomeEvent.OpenCityPicker, events.last())
    }

    @Test
    fun favoriteRow_showsBoardStopAndDirection_clickOpensLine_editOpensEditor() {
        setContent(stateOf())

        composeTestRule.onNodeWithText("科技园站上车").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 科技园").assertIsDisplayed()
        composeTestRule.onNodeWithTag(BusHomeTags.favoriteRow("stop_f1")).performClick()
        val open = events.last() as BusHomeEvent.OpenLine
        assertEquals("l325", open.target.lineId)
        assertEquals(5, open.target.targetOrder)

        composeTestRule.onNodeWithTag(BusHomeTags.EDIT_FAVORITES).performClick()
        assertEquals(BusHomeEvent.EditFavorites, events.last())
    }

    @Test
    fun groupChips_onlyWhenGroupsExist_andSendSelection() {
        setContent(stateOf(groups = listOf(FavoriteGroup(id = "g1", name = "上班"))))

        composeTestRule.onNodeWithText("全部").assertIsDisplayed()
        composeTestRule.onNodeWithText("上班").performClick()
        assertEquals(BusHomeEvent.SelectGroup("g1"), events.last())
    }

    @Test
    fun noFavorites_showsOneLineHint_noEditButton() {
        setContent(stateOf(withFavorites = false))

        composeTestRule.onNodeWithText("在线路页点 ☆，常坐的车会出现在这里").assertIsDisplayed()
        composeTestRule.onNodeWithTag(BusHomeTags.EDIT_FAVORITES).assertDoesNotExist()
        composeTestRule.onNodeWithText("全部").assertDoesNotExist()
    }

    @Test
    fun watchTogetherCard_foldsExtraLines_andExpandsOnTap() {
        setContent(stateOf(session = watchSession(lineCount = 5)))

        composeTestRule.onNodeWithText("一起等 5 条 · 科技园站").assertIsDisplayed()
        composeTestRule.onNodeWithText("约 2 分钟").assertIsDisplayed()
        composeTestRule.onNodeWithText("L1 先到 · 还有 1 站").assertIsDisplayed()
        // 大字 1 条 + 下面 2 条，剩下 2 条折叠
        composeTestRule.onNodeWithText("L2").assertIsDisplayed()
        composeTestRule.onNodeWithText("L3").assertIsDisplayed()
        composeTestRule.onNodeWithText("L4").assertDoesNotExist()
        composeTestRule.onNodeWithText("还有 2 条").performClick()
        composeTestRule.onNodeWithText("L4").assertIsDisplayed()
        composeTestRule.onNodeWithText("L5").assertIsDisplayed()

        composeTestRule.onNodeWithTag(BusHomeTags.STOP_TRACKING).performClick()
        assertEquals(BusHomeEvent.StopTracking, events.last())
    }

    @Test
    fun watchTogetherCard_fewLines_noFoldButton() {
        setContent(stateOf(session = watchSession(lineCount = 2)))

        composeTestRule.onNodeWithText("一起等 2 条 · 科技园站").assertIsDisplayed()
        composeTestRule.onNodeWithTag(BusHomeTags.WATCH_MORE).assertDoesNotExist()
    }

    @Test
    fun nearbyStop_longLineName_keepsDirectionAndArrival() {
        // 长线路名占满自己那一行后省略，不能把终点方向压没；到站信息在第二行，靠右对齐
        setContent(stateOf(nearbyLineName = "盘龙大观园河西旅游专线"))

        composeTestRule.onNodeWithTag(BusHomeTags.LIST).performScrollToNode(hasText("→ 蛇口港"))
        val row = composeTestRule.onNodeWithText("→ 蛇口港").fetchSemanticsNode()
        val direction = composeTestRule.onNodeWithText("→ 蛇口港", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val directionWidth = with(composeTestRule.density) { direction.boundsInRoot.width.toDp() }
        assertTrue("终点方向被线路名挤没了：$directionWidth", directionWidth > 60.dp)
        composeTestRule.onNodeWithText("盘龙大观园河西旅游专线").assertIsDisplayed()

        val arrival = composeTestRule.onAllNodes(hasText("分钟", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.boundsInRoot.top >= direction.boundsInRoot.bottom }
        assertTrue("到站时间没在第二行", arrival.boundsInRoot.top >= direction.boundsInRoot.bottom)
        val small = composeTestRule.onNodeWithText("还有 4 站", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode()
        val gap = with(composeTestRule.density) { (row.boundsInRoot.right - small.boundsInRoot.right).toDp() }
        assertTrue("第二行没右对齐，右边还空 $gap", gap < 24.dp)
    }

    @Test
    fun nearbyStop_lineRowOpensLine_headerOpensStop() {
        setContent(stateOf())

        composeTestRule.onNodeWithTag(BusHomeTags.LIST).performScrollToNode(hasText("→ 蛇口港"))
        composeTestRule.onNodeWithText("120米 · 1 条线").assertIsDisplayed()
        composeTestRule.onNodeWithText("→ 蛇口港").performClick()
        val line = events.last() as BusHomeEvent.OpenLine
        assertEquals("l19", line.target.lineId)
        assertEquals("n1", line.target.stopId)

        composeTestRule.onNodeWithText("高新园站").performClick()
        val stop = events.last() as BusHomeEvent.OpenStop
        assertEquals("n1", stop.target.stopId)
        assertTrue(events.none { it is BusHomeEvent.Refresh })
    }

}
