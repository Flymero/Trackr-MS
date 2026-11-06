package com.trackr.feature.bus.home

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStopLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusHomeMapperArrivalDisplayTest {

    private fun status(
        targetOrder: Int = 5,
        bus: LiveBus? = LiveBus(busId = "bus", plateOrFleetNo = "", order = 3, etaSeconds = 240),
        statusText: String = "",
        preArrivalTime: String? = null
    ) = NearbyStopLineStatus(
        line = BusLineDirection(
            lineId = "line",
            name = "325",
            startStop = "白石洲",
            endStop = "科技园",
            lastTime = "22:30"
        ),
        targetOrder = targetOrder,
        targetStopId = "stop",
        targetStopName = "科技园站",
        statusText = statusText,
        preArrivalTime = preArrivalTime,
        approachingBuses = listOfNotNull(bus)
    )

    @Test
    fun onTheWay_prefersRemainingStops_andOmitsEtaFromDisplayAndSpoken() {
        val display = BusHomeMapper.arrivalDisplayFor(
            status(),
            nextDepartureIsFromStart = false,
            lineName = "325",
            stopName = "科技园站",
            towards = "科技园"
        )

        assertEquals("还有 2 站", display.headline)
        assertEquals("", display.detail)
        assertEquals("还有 2 站", display.short)
        assertTrue(display.spoken.contains("还有 2 站"))
        assertFalse(display.spoken.contains("分钟"))
    }

    @Test
    fun onTheWay_withoutStationOrder_showsStatusOnly() {
        val display = BusHomeMapper.arrivalDisplayFor(
            status(targetOrder = 0, bus = LiveBus(busId = "bus", plateOrFleetNo = "", order = 0, etaSeconds = 240)),
            nextDepartureIsFromStart = false
        )

        assertEquals("在路上", display.headline)
        assertEquals("", display.detail)
        assertEquals("在路上", display.short)
        assertFalse(display.spoken.contains("分钟"))
    }

    @Test
    fun arriving_keepsExistingArrivedState_withoutEta() {
        val display = BusHomeMapper.arrivalDisplayFor(
            status(bus = LiveBus(busId = "bus", plateOrFleetNo = "", order = 5, isArrived = true, etaSeconds = 20)),
            nextDepartureIsFromStart = false
        )

        assertEquals("到站了", display.headline)
        assertTrue(display.detail.contains("快去上车"))
        assertFalse(display.spoken.contains("分钟"))
    }

    @Test
    fun noBus_keepsNextDepartureActualTime() {
        val display = BusHomeMapper.arrivalDisplayFor(
            status(bus = null, preArrivalTime = "07:20"),
            nextDepartureIsFromStart = true,
            lineName = "325",
            stopName = "科技园站",
            towards = "科技园"
        )

        assertEquals("下一班 07:20", display.headline)
        assertEquals("从 白石洲 发车", display.detail)
        assertTrue(display.spoken.contains("7点20分"))
    }

    @Test
    fun authoritativeStatus_isKept() {
        val display = BusHomeMapper.arrivalDisplayFor(
            status(bus = null, statusText = "暂停运营"),
            nextDepartureIsFromStart = true
        )

        assertEquals("暂停运营", display.headline)
        assertEquals("暂停运营", display.short)
        assertEquals("暂停运营", display.spoken)
    }
}
