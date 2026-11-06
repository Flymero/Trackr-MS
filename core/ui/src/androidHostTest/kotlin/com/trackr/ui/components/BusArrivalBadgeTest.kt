package com.trackr.ui.components

import com.trackr.core.model.LiveBus
import com.trackr.ui.components.common.computeArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusArrivalBadgeTest {

    @Test
    fun computeArrivalStatus_arrivedAtStation() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 5,
            isArrived = true
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("到站了", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_imminentEta_isArrived() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 5,
            etaSeconds = 40
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("到站了", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_zeroStopsWithoutArrival_isNextStopNotArrived() {
        // 剩 0 站、没到站标志、坐标缺失（插值不可信）、没 ETA：按 ArrivalRules 还没到
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 5,
            isArrived = false
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("下一站就到", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_remainingStopsWithEta() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3,
            etaSeconds = 600
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("约 10 分钟 · 还有 2 站", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_withinThreeMinutes_isHighlighted() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3,
            etaSeconds = 180
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("约 3 分钟 · 还有 2 站", status.text)
        assertTrue(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_remainingStopsWithoutEta() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3,
            etaSeconds = -1
        )
        val status = computeArrivalStatus(targetOrder = 5, approachingBus = bus)
        assertEquals("还有 2 站 · 暂无到站时间", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_invalidOrder_inTransit() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 0,
            etaSeconds = -1
        )
        val status = computeArrivalStatus(targetOrder = 0, approachingBus = bus)
        assertEquals("在路上 · 暂无到站时间", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_prioritizesExplicitStatusText() {
        val bus = LiveBus(
            busId = "bus_1",
            plateOrFleetNo = "粤B12345",
            order = 3
        )
        val status = computeArrivalStatus(
            targetOrder = 5,
            approachingBus = bus,
            statusText = "末班车已过"
        )
        assertEquals("末班车已过", status.text)
        assertFalse(status.isHighlighted)
    }

    @Test
    fun computeArrivalStatus_fallbackWhenNoBus() {
        val status = computeArrivalStatus(
            targetOrder = 5,
            approachingBus = null,
            fallbackStatusText = "暂无在途车辆"
        )
        assertEquals("暂无在途车辆", status.text)
        assertFalse(status.isHighlighted)
    }
}
