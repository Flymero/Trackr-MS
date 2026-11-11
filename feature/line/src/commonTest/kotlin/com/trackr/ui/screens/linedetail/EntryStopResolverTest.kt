package com.trackr.ui.screens.linedetail

import com.trackr.core.model.BusStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntryStopResolverTest {

    private val stops = listOf(
        BusStop(stopId = "s1", name = "汉口路", order = 1),
        BusStop(stopId = "s2", name = "人民广场(西藏中路)", order = 2),
        BusStop(stopId = "s3", name = "上海体育馆", order = 3),
        BusStop(stopId = "s4", name = "人民广场", order = 4)
    )

    @Test
    fun matchesCoreName_ignoringBrackets() {
        assertEquals("s2", resolveEntryStop(stops, "人民广场(西藏中路)")?.stopId)
        assertEquals("s3", resolveEntryStop(stops, "上海体育馆(公交站)")?.stopId)
    }

    @Test
    fun prefersExactName_thenPreferredOrder() {
        assertEquals("s4", resolveEntryStop(stops, "人民广场")?.stopId)
        assertEquals("s2", resolveEntryStop(stops, "人民广场站", preferredOrder = 2)?.stopId)
    }

    @Test
    fun boardMustBeBeforeAlight() {
        // 下车站在 3：只有 2 号「人民广场」在它前面
        assertEquals("s2", resolveEntryStop(stops, "人民广场", alightStopName = "上海体育馆")?.stopId)
        // 下车站在上车站前面：方向反了
        assertNull(resolveEntryStop(stops, "上海体育馆", alightStopName = "汉口路"))
    }

    @Test
    fun alightNotOnLine_ignoresIt() {
        assertEquals("s3", resolveEntryStop(stops, "上海体育馆", alightStopName = "外滩")?.stopId)
    }

    @Test
    fun blankOrMissingBoard_returnsNull() {
        assertNull(resolveEntryStop(stops, ""))
        assertNull(resolveEntryStop(stops, "外滩"))
    }
}
