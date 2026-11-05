package com.trackr.ui.screens.linedetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.ui.screens.linedetail.components.BusListContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BusListContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val lineDirection = BusLineDirection(
        lineId = "line_001",
        name = "71路",
        direction = 0,
        startStop = "延安东路外滩",
        endStop = "申昆路枢纽站",
        source = DataSourceType.CHELAILE
    )

    private val stops = (1..5).map { order ->
        BusStop(stopId = "s$order", name = "第${order}站", order = order)
    }

    private fun setContent(bus: LiveBus) {
        val detail = BusLineDetail(
            line = lineDirection,
            stops = stops,
            buses = listOf(bus),
            targetOrder = 5
        )
        composeTestRule.setContent {
            TrackrTheme {
                BusListContent(
                    lineDetail = detail,
                    selectedStop = stops[4],
                    onBack = {},
                    onSelectBus = {}
                )
            }
        }
    }

    @Test
    fun missingEta_withDistance_showsDistanceInsteadOfNoPrediction() {
        setContent(
            LiveBus(
                busId = "b1",
                plateOrFleetNo = "沪A1234",
                order = 2,
                distanceToTargetMeters = 320
            )
        )

        composeTestRule.onNodeWithText("距本站 320米").assertIsDisplayed()
        composeTestRule.onNodeWithText("无预测").assertDoesNotExist()
    }

    @Test
    fun sparseStopOrders_showsActualStopCount() {
        setContent(
            LiveBus(
                busId = "sparse",
                plateOrFleetNo = "川Z1234",
                order = 1,
                stopOrders = listOf(1, 3, 5),
                hasAuthoritativeArrival = true
            )
        )

        composeTestRule.onNodeWithText("还有 2 站 · 正在接近第 1 站").assertIsDisplayed()
        composeTestRule.onNodeWithText("还有 4 站", substring = true).assertDoesNotExist()
    }

    @Test
    fun passedBus_hidesDistance() {
        setContent(
            LiveBus(
                busId = "b1",
                plateOrFleetNo = "沪A1234",
                order = 6,
                distanceToTargetMeters = 320
            )
        )

        composeTestRule.onNodeWithText("距本站", substring = true).assertDoesNotExist()
    }
}
