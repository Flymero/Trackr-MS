package com.trackr.feature.route

import com.trackr.core.model.CandidateLine
import kotlin.test.Test
import kotlin.test.assertEquals

class LineDetailKeysTest {

    @Test
    fun carriesBoardAndAlightStopNames() {
        val key = CandidateLine(
            cityId = "034",
            lineId = "amap_123",
            lineName = "49路",
            fromStopOrder = 1,
            toStopOrder = 6,
            startStopName = "人民广场",
            endStopName = "上海体育馆"
        ).toLineDetailKey()

        assertEquals("amap_123", key.lineId)
        assertEquals(1, key.targetOrder)
        assertEquals("人民广场", key.boardStopName)
        assertEquals("上海体育馆", key.alightStopName)
        assertEquals("49路", key.lineName)
    }
}
