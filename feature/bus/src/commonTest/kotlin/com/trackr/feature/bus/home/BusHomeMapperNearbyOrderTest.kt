package com.trackr.feature.bus.home

import com.trackr.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

class BusHomeMapperNearbyOrderTest {
    @Test
    fun officialStopsWithLinesComeFirstAndBothGroupsKeepDistanceOrder() {
        fun stop(id: String, distance: Int, hasLines: Boolean) = NearbyStop(
            stopId = id, name = id, distanceMeters = distance, source = DataSourceType.MEISHAN,
            lines = if (hasLines) listOf(NearbyStopLineStatus(
                line = BusLineDirection(lineId = id, name = id, startStop = "A", endStop = "B"),
                targetOrder = 1, targetStopId = id, targetStopName = id
            )) else emptyList()
        )
        val state = BusHomeUiState(nearbyStops = listOf(
            stop("empty-near", 10, false), stop("served-far", 200, true),
            stop("empty-far", 50, false), stop("served-near", 80, true)
        ))
        assertEquals(listOf("served-near", "served-far", "empty-near", "empty-far"),
            BusHomeMapper.nearbyCards(state).map { it.name })
    }
}
