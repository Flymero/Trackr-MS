package com.trackr.core.domain.arrival

import com.trackr.core.model.BusStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopOrderResolverTest {

    private val stops = listOf(
        BusStop("1", "起点站", 1, 22.60, 110.10),
        BusStop("2", "市政广场", 2, 22.61, 110.11),
        BusStop("3", "人民路口", 3, 22.62, 110.12),
        BusStop("4", "市政广场", 4, 22.6101, 110.1101),
        BusStop("5", "终点站", 5, 22.63, 110.13)
    )

    @Test
    fun resolve_keepsOrderWhenNameMatches() {
        assertEquals(3, StopOrderResolver.resolve(stops, 3, "人民路口"))
        assertEquals(4, StopOrderResolver.resolve(stops, 4, "市政广场"))
    }

    @Test
    fun resolve_findsStopByNameWhenOrderIsBogus() {
        // 掌上公交 stationID 是站点编号：推出来的「第 900 站」
        assertEquals(3, StopOrderResolver.resolve(stops, 900, "人民路口"))
        assertEquals(3, StopOrderResolver.resolve(stops, 900, " 人民 路口 "))
    }

    @Test
    fun resolve_sameNameTwice_prefersNearestCoordinateThenNearestOrder() {
        assertEquals(4, StopOrderResolver.resolve(stops, 900, "市政广场", lat = 22.6101, lng = 110.1101))
        assertEquals(2, StopOrderResolver.resolve(stops, 1, "市政广场"))
        assertEquals(4, StopOrderResolver.resolve(stops, 900, "市政广场"))
    }

    @Test
    fun resolve_unknownName_returnsNull() {
        assertNull(StopOrderResolver.resolve(stops, 3, "不存在的站"))
        assertNull(StopOrderResolver.resolve(stops, 3, ""))
    }

    @Test
    fun correctedOrder_onlyTouchesSuspectOrders() {
        // 超出站表：总要改
        assertEquals(3, StopOrderResolver.correctedOrder(stops, 900, "人民路口", nameMismatchIsSuspect = false))
        // 在站表里、名字不对：只有站序本身不可靠（掌上公交）时才改
        assertNull(StopOrderResolver.correctedOrder(stops, 2, "人民路口", nameMismatchIsSuspect = false))
        assertEquals(3, StopOrderResolver.correctedOrder(stops, 2, "人民路口", nameMismatchIsSuspect = true))
        // 本来就对 / 找不到同名站：不改
        assertNull(StopOrderResolver.correctedOrder(stops, 3, "人民路口", nameMismatchIsSuspect = true))
        assertNull(StopOrderResolver.correctedOrder(stops, 900, "不存在的站", nameMismatchIsSuspect = true))
        assertNull(StopOrderResolver.correctedOrder(emptyList(), 900, "人民路口", nameMismatchIsSuspect = true))
    }

    @Test
    fun isInRange_checksStopList() {
        assertTrue(StopOrderResolver.isInRange(stops, 5))
        assertFalse(StopOrderResolver.isInRange(stops, 900))
        assertTrue(StopOrderResolver.isInRange(emptyList(), 900))
    }
}
