package com.trackr.core.data.repository

import com.trackr.core.model.*
import com.trackr.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTrackingControllerTest {
    private val target = TrackingTarget.SingleLine(cityId = "sh", lineId = "L1", lineName = "1路",
        targetStopId = "s5", targetStopName = "站5", targetOrder = 5)

    private class Fixture(val test: TestScope) {
        val clock = FakeClock()
        val store = InMemoryTrackingSessionStore()
        val repo = DefaultTrackingRepository(store, test.backgroundScope, clock, clock)
        val realtime = FakeRealtimeRepository()
        val settings = FakeSettingsRepository()
        val location = FakeLocationSource(precisePermission = true)
        val effects = mutableListOf<String>()
        val controller = ForegroundTrackingController(repo, realtime, settings, location,
            test.backgroundScope, clock, clock, onEffect = { _, id -> effects += id },
            requestDispatcher = StandardTestDispatcher(test.testScheduler))
    }

    @Test fun backgroundCancelsPollingAndResumesWithNewGeneration() = runTest {
        val f = Fixture(this)
        assertFalse(f.controller.start(target))
        f.controller.setActive(true)
        runCurrent()
        assertTrue(f.controller.start(target))
        runCurrent()
        val before = f.repo.sessions.value.single()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.setActive(false)
        runCurrent()
        assertTrue(f.repo.sessions.value.single().interrupted)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.setActive(true)
        runCurrent()
        assertEquals(2, f.realtime.lineDetailCalls.size)
        assertTrue(f.repo.sessions.value.single().generation > before.generation)
        assertFalse(f.repo.sessions.value.single().interrupted)
    }

    @Test fun nonCooperativeLateResponseCannotUpdatePausedOrReplacedSession() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        f.realtime.lineDetailHook = { withContext(NonCancellable) { pending.await() } }
        f.controller.setActive(true)
        runCurrent()
        f.controller.start(target)
        runCurrent()
        f.controller.setActive(false)
        val paused = f.repo.sessions.value.single()
        pending.complete(Unit)
        runCurrent()
        assertEquals(paused, f.repo.sessions.value.single())
        assertTrue(f.effects.isEmpty())
        f.controller.setActive(true)
        runCurrent()
        assertFalse(f.repo.sessions.value.single().interrupted)
    }

    @Test fun backgroundExpiredSessionIsRemovedBeforeAnyRefresh() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        f.controller.setActive(false)
        f.clock.advanceBy(12.hours)
        f.controller.setActive(true); runCurrent()
        assertTrue(f.repo.sessions.value.isEmpty())
        assertEquals(1, f.realtime.lineDetailCalls.size)
    }

    @Test fun repeatedForegroundAndStartDoNotDuplicateWorkers() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        repeat(3) { f.controller.setActive(true) }
        runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.controller.start(target); runCurrent()
        assertEquals(1, f.repo.sessions.value.size)
        assertEquals(2, f.realtime.lineDetailCalls.size)
        f.controller.start(target.copy(lineId = "L2")); runCurrent()
        assertEquals(2, f.repo.sessions.value.size)
        f.controller.stopTracking(f.repo.sessions.value.first().sessionId); runCurrent()
        assertEquals("L2", f.repo.sessions.value.single().leg.lineId)
    }

    @Test fun failureBacksOffAndThenRecovers() = runTest {
        val f = Fixture(this)
        f.realtime.lineDetailResult = Result.failure(IllegalStateException("offline"))
        f.controller.setActive(true); runCurrent()
        f.controller.start(target); runCurrent()
        advanceTimeBy(89_999); runCurrent()
        assertEquals(1, f.realtime.lineDetailCalls.size)
        f.realtime.lineDetailResult = null
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.realtime.lineDetailCalls.size)
        assertFalse(f.repo.sessions.value.single().refreshFailed)
        val fresh = f.repo.sessions.value.single().lastRealtimeAtMillis
        f.realtime.lineDetailResult = Result.failure(IllegalStateException("offline again"))
        f.clock.advanceBy(1.hours)
        advanceTimeBy(45_000); runCurrent()
        assertEquals(3, f.realtime.lineDetailCalls.size)
        assertTrue(f.repo.sessions.value.single().refreshFailed)
        assertEquals(fresh, f.repo.sessions.value.single().lastRealtimeAtMillis)
    }

    @Test fun closeBeforeDiskLoadDoesNotStartWorkers() = runTest {
        val f = Fixture(this)
        f.controller.setActive(true)
        f.controller.close()
        runCurrent()
        assertFalse(f.controller.start(target))
        assertTrue(f.realtime.lineDetailCalls.isEmpty())
    }

    @Test fun pauseDropsCachedLocationAndRejectsOldGeneration() = runTest {
        val f = Fixture(this)
        runCurrent()
        val session = assertNotNull(f.repo.start(target))
        f.repo.updateUserFix(UserFix(31.0, 121.0, 10f, f.clock.elapsed))
        f.repo.pauseAll()
        assertNull(f.repo.latestUserFix)
        assertFalse(f.repo.update(session.sessionId, session.generation) { error("must not apply") })
        f.repo.resumeAll()
        assertFalse(f.repo.update(session.sessionId, session.generation) { error("must not apply") })
    }
}
