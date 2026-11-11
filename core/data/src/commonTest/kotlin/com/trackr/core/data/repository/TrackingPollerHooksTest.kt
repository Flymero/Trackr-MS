package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*
import com.trackr.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingPollerHooksTest {
    private val target = TrackingTarget.SingleLine(cityId = "ms", lineId = "ms:1_1_0", lineName = "1路",
        source = DataSourceType.MEISHAN, targetStopId = "ms-st:5", targetStopName = "站5", targetOrder = 5)
    private fun detail(order: Int) = BusLineDetail(
        line = BusLineDirection(lineId = target.lineId, name = "1路", startStop = "始", endStop = "终", source = DataSourceType.MEISHAN),
        stops = listOf(BusStop("ms-st:5", "站5", 5)),
        buses = listOf(LiveBus("bus", "bus", order, segmentProgress = .5f))
    )

    @Test fun platformWaitAndFetchHookStillReuseSharedPageDataWithoutHttpRequest() = runTest {
        val clock = FakeClock()
        val tracking = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, clock, clock)
        val realtime = FakeRealtimeRepository()
        val settings = FakeSettingsRepository()
        val session = assertNotNull(tracking.start(target))
        var httpCalls = 0
        var pushOnline = false
        val poller = TrackingPoller(tracking, realtime, settings, clock, clock, { false }, { null }, { _, _ -> },
            singleLineFetch = { _, _, _, _, _, shared, onOperating ->
                val fetched = shared ?: detail(1).also { httpCalls++ }
                onOperating(LineOperatingState.RUNNING)
                Result.success { current ->
                    if (pushOnline) TrackingEngine.Result(current) else reduceTrackingLine(current, fetched,
                        false, null, null, clock.now().toEpochMilliseconds(), clock.elapsedMillis(), false)
                }
            }, roundWait = { _, _ -> delay(300_000) })
        backgroundScope.launch { poller.run(session.sessionId, session.generation) }
        runCurrent()
        assertEquals(4, tracking.session.value?.state?.stopsRemaining)
        clock.advanceBy(6.seconds)
        realtime.lineDetailUpdateFlow.emit(LineDetailUpdate(DataSourceType.MEISHAN, target.lineId, 5, detail(4), "ms", "ms-st:5"))
        runCurrent()
        assertEquals(1, tracking.session.value?.state?.stopsRemaining)
        assertEquals(1, httpCalls)
        // A shared HTTP snapshot is allowed to wake the service, but cannot override online TCP.
        pushOnline = true
        clock.advanceBy(6.seconds)
        realtime.lineDetailUpdateFlow.emit(LineDetailUpdate(DataSourceType.MEISHAN, target.lineId, 5, detail(1), "ms", "ms-st:5"))
        runCurrent()
        assertEquals(1, tracking.session.value?.state?.stopsRemaining)
        assertEquals(1, httpCalls)
    }

    @Test fun sharedUpdatesForAnotherCityOrStationCannotWakeTheHook() = runTest {
        val clock = FakeClock()
        val tracking = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope, clock, clock)
        val realtime = FakeRealtimeRepository()
        val session = assertNotNull(tracking.start(target))
        var calls = 0
        val poller = TrackingPoller(tracking, realtime, FakeSettingsRepository(), clock, clock, { false }, { null }, { _, _ -> },
            singleLineFetch = { _, _, _, _, _, _, _ -> calls++; Result.success { TrackingEngine.Result(it) } },
            roundWait = { _, _ -> delay(300_000) })
        backgroundScope.launch { poller.run(session.sessionId, session.generation) }
        runCurrent()
        clock.advanceBy(6.seconds)
        realtime.lineDetailUpdateFlow.emit(LineDetailUpdate(DataSourceType.MEISHAN, target.lineId, 5, detail(4), "other-city", "ms-st:5"))
        realtime.lineDetailUpdateFlow.emit(LineDetailUpdate(DataSourceType.MEISHAN, target.lineId, 5, detail(4), "ms", "ms-st:other"))
        runCurrent()
        assertEquals(1, calls)
    }
}
