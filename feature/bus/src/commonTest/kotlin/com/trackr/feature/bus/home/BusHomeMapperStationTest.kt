package com.trackr.feature.bus.home

import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.GroupColor
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 首页收藏的站（issue #47，方案 A 一行）：标题、按到站先后取前 3 条、几种状态 */
class BusHomeMapperStationTest {

    private val station = FavoriteStation(
        id = "034_CHELAILE_科技园", cityId = "034", stopName = "科技园", stopId = "s-north", physicalStId = "p-north",
        namesakeStId = "ns1", platformLabel = "往北", wgsLat = 31.2, wgsLng = 121.4, source = DataSourceType.CHELAILE
    )
    private val item = FavoriteItem.Station(station, groupId = "work", sortOrder = 0)

    private fun status(name: String, end: String, etaSeconds: Int? = null, order: Int = 3) = NearbyStopLineStatus(
        line = BusLineDirection(lineId = "L$name", name = name, startStop = "起点", endStop = end),
        targetOrder = 5,
        targetStopId = "s-north",
        targetStopName = "科技园",
        approachingBuses = etaSeconds?.let { listOf(LiveBus(busId = "b$name", plateOrFleetNo = "", order = order, etaSeconds = it)) }.orEmpty()
    )

    private fun stateWith(platform: NearbyStop?) = BusHomeUiState(
        favoriteGroups = listOf(FavoriteGroup(id = "work", name = "上班", color = GroupColor.Preset.BLUE)),
        favoriteItems = listOf(item),
        favoritesRealtime = platform?.let { mapOf(item.key to it) }.orEmpty()
    )

    private fun rowOf(platform: NearbyStop?): FavoriteRowUi.Station =
        assertIs<FavoriteRowUi.Station>(BusHomeMapper.favoriteRows(stateWith(platform)).single())

    @Test
    fun ready_sortsByArrival_takesThree_leadsWithFirst() {
        val platform = NearbyStop(
            stopId = "s-north", name = "科技园",
            lines = listOf(
                status("325", "科技园", etaSeconds = 240),
                status("M194", "西丽", etaSeconds = 540),
                status("B796", "深圳湾"),
                status("19", "蛇口港", etaSeconds = 120),
                status("204", "南山中心", etaSeconds = 660)
            )
        )

        val row = rowOf(platform)

        assertEquals("station_034_CHELAILE_科技园", row.key)
        assertEquals("科技园 · 往北", row.title)
        assertEquals(StationStatus.Ready, row.status)
        assertEquals(listOf("19", "325", "M194"), row.lines.map { it.lineName })
        assertEquals("→ 蛇口港", row.lines.first().direction)
        assertEquals(5, row.lineCount)
        assertEquals("2 分钟", row.arrival?.big)
        assertEquals("5 条线", row.arrival?.small)
        assertEquals(GroupColor.Preset.BLUE, row.groupColor)
        assertTrue(row.spoken.isNotBlank())
        // 点开站点页停在默认站台
        assertEquals(
            StopTarget(cityId = "034", stopId = "s-north", stopName = "科技园", physicalStId = "p-north", namesakeStId = "ns1", lat = 31.2, lng = 121.4),
            row.open
        )
    }

    @Test
    fun noRealtimeYet_isLoading() {
        val row = rowOf(null)
        assertEquals(StationStatus.Loading, row.status)
        assertTrue(row.lines.isEmpty())
        assertEquals(ArrivalTone.Mute, row.arrival?.tone)
    }

    @Test
    fun platformWithoutLines_isNotFound() {
        val row = rowOf(NearbyStop(stopId = "s-north", name = "科技园"))
        assertEquals(StationStatus.NotFound, row.status)
        assertNull(row.arrival)
    }

    @Test
    fun noBusesAnywhere_keepsInterfaceOrder_noSinglePlatformLabel() {
        val single = item.copy(station = station.copy(platformLabel = ""))
        val state = BusHomeUiState(
            favoriteItems = listOf(single),
            favoritesRealtime = mapOf(
                single.key to NearbyStop(stopId = "s", name = "科技园", lines = listOf(status("A", "x"), status("B", "y")))
            )
        )

        val row = assertIs<FavoriteRowUi.Station>(BusHomeMapper.favoriteRows(state).single())

        assertEquals("科技园", row.title)
        assertEquals(listOf("A", "B"), row.lines.map { it.lineName })
    }
}
