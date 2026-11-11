package com.trackr.feature.bus.home

import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupColor
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlin.test.Test
import kotlin.test.assertEquals

/** 收藏卡片带上所在分组的颜色（issue #38）：「全部」里按颜色分辨分组 */
class BusHomeMapperGroupColorTest {

    private fun stop(id: String) = FavoriteStationLine(
        id = id, cityId = "034", cityName = "上海", lineId = "L$id", lineName = "${id}路",
        startStop = "起点", endStop = "终点", targetStopId = "s$id", targetStopName = "站$id", targetOrder = 3
    )

    private val route = TransitGroup(
        id = "r1",
        name = "家 → 公司",
        legs = listOf(
            TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "公司", candidateLines = listOf(CandidateLine(cityId = "034", lineId = "a", lineName = "325")))
        )
    )

    private val state = BusHomeUiState(
        favoriteGroups = listOf(
            FavoriteGroup(id = "work", name = "上班", color = GroupColor.Preset.BLUE),
            FavoriteGroup(id = "gym", name = "健身", color = GroupColor.Custom(0x12AB9F)),
            FavoriteGroup(id = "home", name = "回家", color = GroupColor.Preset.ORANGE)
        ),
        favoriteItems = listOf(
            FavoriteItem.Stop(stop("a"), groupId = "work", sortOrder = 0),
            FavoriteItem.Stop(stop("b"), groupId = "gym", sortOrder = 1),
            FavoriteItem.Stop(stop("c"), groupId = null, sortOrder = 2),
            FavoriteItem.Route(route, groupId = "home", sortOrder = 3),
            // 指向已删分组的老数据：没有颜色
            FavoriteItem.Stop(stop("d"), groupId = "deleted", sortOrder = 4)
        )
    )

    @Test
    fun allView_eachRowCarriesItsGroupColor() {
        val rows = BusHomeMapper.favoriteRows(state)

        assertEquals(listOf("stop_a", "stop_b", "stop_c", "route_r1", "stop_d"), rows.map { it.key })
        assertEquals(listOf(GroupColor.Preset.BLUE, GroupColor.Custom(0x12AB9F), null, GroupColor.Preset.ORANGE, null), rows.map { it.groupColor })
    }

    @Test
    fun groupView_rowsKeepTheGroupColor() {
        val rows = BusHomeMapper.favoriteRows(state.copy(selectedGroupId = "home"))

        assertEquals(listOf("route_r1"), rows.map { it.key })
        assertEquals(listOf<GroupColor?>(GroupColor.Preset.ORANGE), rows.map { it.groupColor })
    }
}
