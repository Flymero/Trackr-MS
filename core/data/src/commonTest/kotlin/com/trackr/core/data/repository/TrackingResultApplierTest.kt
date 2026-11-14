package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*
import com.trackr.core.testing.FakeClock
import kotlinx.coroutines.test.*
import kotlin.test.*

class TrackingResultApplierTest {
    private val target = TrackingTarget.SingleLine(cityId = "ms", lineId = "ms:1_1_0", lineName = "1路",
        targetStopId = "ms-st:5", targetStopName = "站5", targetOrder = 5, source = DataSourceType.MEISHAN)

    @Test fun knownCoordinatesOverrideEqualStableIds() = runTest {
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, FakeClock())
        val a = assertNotNull(repo.start(target))
        val b = assertNotNull(repo.start(target.copy(lineId = "ms:1_2_0")))
        val here = a.copy(state = a.state.copy(targetStopLat = 31.23, targetStopLng = 121.47))
        val opposite = b.copy(state = b.state.copy(targetStopLat = 31.23 + 60.0 / 111_000, targetStopLng = 121.47))
        assertFalse(sameWaitingStop(here, opposite))
        assertTrue(sameWaitingStop(here, opposite.copy(state = opposite.state.copy(targetStopLat = 31.23))))
    }

    @Test fun orderIdsOrDifferentSourcesAreNotIdentityWithoutCoordinates() = runTest {
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, FakeClock())
        val a = assertNotNull(repo.start(target.copy(source = DataSourceType.MYGOLBS, targetStopId = "5")))
        val b = assertNotNull(repo.start(target.copy(lineId = "other", source = DataSourceType.MYGOLBS, targetStopId = "5")))
        assertFalse(sameWaitingStop(a, b))
        val otherSource = assertNotNull(repo.start(target.copy(lineId = "third", source = DataSourceType.CHELAILE)))
        val official = assertNotNull(repo.start(target))
        assertFalse(sameWaitingStop(official, otherSource))
    }

    @Test fun authoritativeOfficialStopIdReanchorsAndPreservesArrivalCapability() = runTest {
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, FakeClock())
        val original = assertNotNull(repo.start(target))
        val session = original.copy(state = original.state.copy(hasAuthoritativeArrival = true, currentBusId = "old"))
        val detail = BusLineDetail(line = BusLineDirection(lineId = target.lineId, name = "1路", startStop = "start", endStop = "end", source = DataSourceType.MEISHAN),
            buses = emptyList(), stops = listOf(BusStop("ms-st:other", "别的站", 5), BusStop("ms-st:5", "站5", 8, wgsLat = 31.23, wgsLng = 121.47)))
        val next = session.withResolvedWaitingStop(detail)
        assertEquals(8, next.state.targetOrder)
        assertEquals("ms-st:5", next.state.targetStopId)
        assertEquals(31.23, next.state.targetStopLat)
        assertTrue(next.state.hasAuthoritativeArrival)
        assertNull(next.state.currentBusId)
    }

    @Test fun orderFallbackWhenIdMissing_keepsOriginalTargetStopId() = runTest {
        // 评审 P3-1：改道删站后 ms-st:5 不在站表，站序 5 上是别的物理站——
        // 站序未变（moved=false）时不得改写 targetStopId，否则 TCP 订阅/后续锚定悄悄切到别的物理站
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, FakeClock())
        val session = assertNotNull(repo.start(target))
        val detail = BusLineDetail(line = BusLineDirection(lineId = target.lineId, name = "1路", startStop = "start", endStop = "end", source = DataSourceType.MEISHAN),
            buses = emptyList(),
            stops = listOf(BusStop("ms-st:other", "临时站", 5, wgsLat = 31.20, wgsLng = 121.47), BusStop("ms-st:7", "站7", 7)))
        val next = session.withResolvedWaitingStop(detail)
        assertEquals(5, next.state.targetOrder)
        assertEquals("ms-st:5", next.state.targetStopId, "站序兜底命中别的物理站时保留原目标站 id")
        assertEquals(31.20, next.state.targetStopLat)
    }

    @Test fun commonCompletionStopsSameWaitingStationButRetainsOppositeAndOldGeneration() = runTest {
        val repo = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, FakeClock())
        val a = assertNotNull(repo.start(target))
        val same = assertNotNull(repo.start(target.copy(lineId = "ms:1_2_0")))
        val opposite = assertNotNull(repo.start(target.copy(lineId = "ms:1_3_0")))
        for (s in listOf(a, same, opposite)) repo.update(s.sessionId, s.generation) {
            it.copy(state = it.state.copy(targetStopLat = 31.23 + if (s == opposite) 60.0 / 111_000 else 0.0,
                targetStopLng = 121.47))
        }
        val stale = repo.applyTrackingResult(a.sessionId, a.generation - 1, true) { error("must reject stale generation") }
        assertFalse(stale.applied)
        val applied = repo.applyTrackingResult(a.sessionId, a.generation, true) {
            TrackingEngine.Result(it, listOf(TrackingEngine.Effect.Finished.Boarded(it.state)))
        }
        assertTrue(applied.applied)
        assertEquals(listOf(opposite.sessionId), repo.sessions.value.map { it.sessionId })
    }

    @Test fun pendingBoardingKeepsFastFinalPlatformWaitEvenWhenLineNotRunning() {
        val state = TrackingSessionState(recentDepartedBusId = "bus")
        assertEquals(10_000L, trackingRoundInterval(state, 10_000L, LineOperatingState.ENDED))
        assertEquals(300_000L, trackingRoundInterval(TrackingSessionState(), 10_000L, LineOperatingState.ENDED))
    }

    @Test fun oldSessionStateJsonPreservesAuthoritativeArrivalAndDefaultsNewFields() {
        val old = """{"hasAuthoritativeArrival":true,"targetStopId":"ms-st:5","targetOrder":5}"""
        val state = kotlinx.serialization.json.Json.decodeFromString(TrackingSessionState.serializer(), old)
        assertTrue(state.hasAuthoritativeArrival)
        assertEquals("ms-st:5", state.targetStopId)
        assertNull(state.recentDepartedBusId)
        assertEquals(0L, state.recentDepartedAtMillis)
        assertEquals(0.0, state.targetStopLat)
        assertEquals(0.0, state.targetStopLng)
    }

}
