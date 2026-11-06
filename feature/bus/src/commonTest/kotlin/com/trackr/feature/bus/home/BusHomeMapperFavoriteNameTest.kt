package com.trackr.feature.bus.home

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 收藏卡的线路号（issue #20）。
 *
 * 解析修好之后新收藏不会再有空名字，但用户手机上已经存着的老收藏改不了：
 * 首页要把它们显示成线路号，而不是一个空白的圆角块。
 */
class BusHomeMapperFavoriteNameTest {

    private fun favorite(lineName: String, lineNo: String, lineId: String = "cl_line_1") = FavoriteStationLine(
        id = "034_${lineId}_3",
        cityId = "034",
        cityName = "上海",
        lineId = lineId,
        lineName = lineName,
        lineNo = lineNo,
        startStop = "科技园",
        endStop = "人民广场",
        targetStopId = "stn_1",
        targetStopName = "科技园",
        targetOrder = 3,
        source = DataSourceType.CHELAILE
    )

    private fun stateOf(
        fav: FavoriteStationLine,
        realtime: Map<String, NearbyStop> = emptyMap()
    ) = BusHomeUiState(
        favoriteItems = listOf(FavoriteItem.Stop(fav, groupId = null, sortOrder = 0)),
        favoritesRealtime = realtime
    )

    private fun BusHomeUiState.stopRow(): FavoriteRowUi.Stop =
        BusHomeMapper.favoriteRows(this).filterIsInstance<FavoriteRowUi.Stop>().single()

    @Test
    fun favoriteWithoutName_showsLineNo() {
        val row = stateOf(favorite(lineName = "", lineNo = "M200")).stopRow()

        assertEquals("M200", row.lineName)
        assertEquals("M200", row.open.lineName)
    }

    @Test
    fun favoriteWithoutNameAndLineNo_usesRealtimeLineName() {
        val fav = favorite(lineName = "", lineNo = "")
        val realtime = mapOf(
            fav.id to NearbyStop(
                stopId = "stn_1",
                name = "科技园",
                lines = listOf(
                    NearbyStopLineStatus(
                        line = BusLineDirection(
                            lineId = fav.lineId,
                            name = "M200路",
                            lineNo = "M200",
                            startStop = "科技园",
                            endStop = "人民广场"
                        ),
                        targetOrder = 3,
                        targetStopId = "stn_1",
                        targetStopName = "科技园"
                    )
                )
            )
        )

        val row = stateOf(fav, realtime).stopRow()

        assertEquals("M200路", row.lineName)
    }

    @Test
    fun favoriteWithName_keepsName() {
        val row = stateOf(favorite(lineName = "M200路", lineNo = "M200")).stopRow()

        assertEquals("M200路", row.lineName)
    }
}
