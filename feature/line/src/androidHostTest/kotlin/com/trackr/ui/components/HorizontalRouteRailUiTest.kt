package com.trackr.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 横排走向条：站名竖排但无障碍读完整站名；点站换候车、点车标跟随。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HorizontalRouteRailUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val stops = listOf(
        BusStop(stopId = "1", name = "汉口路", order = 1),
        BusStop(stopId = "2", name = "人民广场", order = 2),
        BusStop(stopId = "3", name = "上海体育馆", order = 3)
    )

    @Test
    fun rendersAllStops_clickSelectsStation() {
        var selected: BusStop? = null

        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = stops,
                    buses = emptyList(),
                    selectedStop = stops[1],
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = { selected = it },
                    onSelectBus = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("汉口路").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("人民广场").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("上海体育馆").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("上海体育馆").performClick()

        assertEquals("上海体育馆", selected?.name)
        assertEquals(3, selected?.order)
    }

    @Test
    fun clickBusChip_followsBus() {
        var followed: LiveBus? = null
        val bus = LiveBus(
            busId = "bus_101",
            plateOrFleetNo = "沪A1234",
            order = 2,
            segmentProgress = 0.5f,
            wgsLat = 31.0,
            wgsLng = 121.0
        )

        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = stops,
                    buses = listOf(bus),
                    selectedStop = stops[1],
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = {},
                    onSelectBus = { followed = it }
                )
            }
        }

        // 车牌在窄车标里缩成 4 位
        composeTestRule.onNodeWithText("1234").performClick()

        assertEquals("bus_101", followed?.busId)
    }

    @Test
    fun emptyStops_rendersNothingAndDoesNotCrash() {
        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = emptyList(),
                    buses = emptyList(),
                    selectedStop = null,
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = {},
                    onSelectBus = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("汉口路").assertDoesNotExist()
    }

    @Test
    fun longStopName_getsFullHeightWithoutEllipsis() {
        val longName = "浦东国际机场T2航站楼"
        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = listOf(BusStop(stopId = "1", name = longName, order = 1)),
                    buses = emptyList(),
                    selectedStop = null,
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = {},
                    onSelectBus = {}
                )
            }
        }

        // 站名一个字一行，逐行读：字数全部渲染出来，最后一行没有省略号
        val vertical = longName.map { it.toString() }.joinToString("\n")
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(vertical, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)

        val layout = results.single()
        assertEquals(longName.length, layout.lineCount)
        assertFalse("站名高度被裁", layout.didOverflowHeight)
        assertFalse("站名被省略号截断", layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test
    fun bracketedStopName_drawsBracketsInsteadOfText() {
        // #23：整段还是一个 Text（拆成单字会被逐个截底），括号那两行换成占位空格，
        // 横版括号画在占位上；读屏仍读完整站名
        val name = "人民广场（武胜路）"
        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = listOf(BusStop(stopId = "1", name = name, order = 1)),
                    buses = emptyList(),
                    selectedStop = null,
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = {},
                    onSelectBus = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(name).assertIsDisplayed()
        // 括号不再是文字，位置留成全角空格
        composeTestRule.onNodeWithText("（", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("）", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(railVerticalNameText(name), useUnmergedTree = true).assertExists()
    }

    @Test
    fun rail_fillsFullWidthWithoutCard() {
        composeTestRule.setContent {
            TrackrTheme {
                HorizontalRouteRail(
                    stops = stops,
                    buses = emptyList(),
                    selectedStop = stops[1],
                    selectedBusId = null,
                    scrollState = rememberScrollState(),
                    onSelectStop = {},
                    onSelectBus = {}
                )
            }
        }

        // 没有卡片外壳：贴满整宽，左右不再留页面边距（轨道自己留 16dp）
        val bounds = composeTestRule.onNodeWithTag(HorizontalRailTags.ROOT).getUnclippedBoundsInRoot()
        assertEquals(360f, (bounds.right - bounds.left).value, 0.5f)
    }

    @Test
    fun railHeight_followsLongestStopName() {
        val short = listOf(BusStop(stopId = "1", name = "汉口路", order = 1))
        val long = listOf(BusStop(stopId = "2", name = "浦东国际机场T2航站楼", order = 1))

        assertEquals(3, railNameLines(short))
        assertEquals(11, railNameLines(long))

        val lineHeight = 14.dp
        assertEquals(
            horizontalRailHeight(short, lineHeight) + lineHeight * (11 - 3),
            horizontalRailHeight(long, lineHeight)
        )
    }
}
