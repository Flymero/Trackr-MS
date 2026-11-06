package com.trackr.ui.screens.favoritesedit

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v3 编辑收藏页（第 22 步，第 33 步改成车 + 路线合一）：两种收藏都显示、分组按条数统计、
 * 点一条改分组、删除先确认。
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesEditScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<FavoritesEditEvent>()

    private val favoriteGroup = FavoriteGroup(id = "fav1", name = "上班", sortOrder = 0)

    private val favoriteStop = FavoriteStationLine(
        id = "034_L325_3",
        cityId = "034",
        cityName = "上海",
        lineId = "L325",
        lineName = "325",
        startStop = "火车站",
        endStop = "科技园",
        targetStopId = "s3",
        targetStopName = "科技园站",
        targetOrder = 3
    )

    private val route = TransitGroup(
        id = "g1",
        name = "家 → 公司",
        category = GroupCategory.COMMUTE,
        legs = listOf(
            TransitLeg(
                legIndex = 0,
                fromStopName = "深大北门",
                toStopName = "高新园",
                candidateLines = listOf(
                    CandidateLine(
                        cityId = "034",
                        lineId = "L325",
                        lineName = "325",
                        source = DataSourceType.CHELAILE,
                        fromStopOrder = 3,
                        toStopOrder = 8
                    )
                )
            )
        )
    )

    /** 一条收藏的车（在「上班」分组里）+ 一条不分组路线 */
    private val items = listOf(
        FavoriteItem.Stop(favoriteStop, favoriteGroup.id, 0),
        FavoriteItem.Route(route, null, 1)
    )

    private fun setContent() {
        composeTestRule.setContent {
            TrackrTheme {
                FavoritesEditScreen(
                    items = items,
                    favoriteGroups = listOf(favoriteGroup),
                    onEvent = { events += it }
                )
            }
        }
    }

    @Test
    fun showsFavoritedStopAndRoute() {
        setContent()

        composeTestRule.onNodeWithText("分组").assertIsDisplayed()
        // 「上班」出现两次：分组那一行 + 收藏的车那行的分组名
        composeTestRule.onAllNodesWithText("上班").assertCountEquals(2)
        // 分组计数按「条收藏」算，车也算进去（原来只数路线，永远是「还没有路线」）
        composeTestRule.onNodeWithText("1 条收藏").assertIsDisplayed()

        // 收藏的车：线路号 + 上车站（在列表下方，可能滚出可视区，只要求已渲染）
        composeTestRule.onNodeWithText("325").assertExists()
        composeTestRule.onNodeWithText("科技园站上车").assertExists()
        // 收藏的路线
        composeTestRule.onNodeWithText("家 → 公司").assertExists()
    }

    @Test
    fun createGroup_asksNameAndSendsEvent() {
        setContent()

        composeTestRule.onNodeWithTag("btn_create_favorite_group").performClick()
        composeTestRule.onNodeWithTag("input_group_name").performTextInput("健身房")
        composeTestRule.onNodeWithText("创建").performClick()

        assertEquals(
            listOf(FavoritesEditEvent.CreateGroup("健身房")),
            events.filterIsInstance<FavoritesEditEvent.CreateGroup>()
        )
    }

    @Test
    fun tappingFavoriteRow_opensGroupPickerAndSendsSetGroup() {
        setContent()

        composeTestRule.onNodeWithTag("favorite_row_route_g1").performClick()
        composeTestRule.onNodeWithText("放到哪个分组").assertIsDisplayed()
        composeTestRule.onNodeWithTag("group_option_fav1").performClick()

        assertEquals(FavoritesEditEvent.SetGroup("route_g1", "fav1"), events.single())
    }

    @Test
    fun groupPicker_canMoveBackToNoGroup() {
        setContent()

        composeTestRule.onNodeWithTag("favorite_row_stop_034_L325_3").performClick()
        composeTestRule.onNodeWithTag("group_option_none").performClick()

        assertEquals(FavoritesEditEvent.SetGroup("stop_034_L325_3", null), events.single())
    }

    @Test
    fun deleteFavorite_asksConfirmationFirst() {
        setContent()

        composeTestRule.onNodeWithTag("btn_delete_favorite_route_g1").performClick()
        composeTestRule.onNodeWithText("删除这条收藏？").assertIsDisplayed()
        composeTestRule.onNodeWithText("删除").performClick()

        assertEquals(FavoritesEditEvent.DeleteItem("route_g1"), events.single())
    }
}
