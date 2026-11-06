package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.model.UserFix
import com.trackr.core.model.WatchLine
import com.trackr.core.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * [DefaultTrackingRepository]：代际号、落盘读回（两个实例共用一个 store 模拟进程被杀后重建）、命令。
 */
class TrackingRepositoryTest {

    private val clock = FakeClock()
    private val store = InMemoryTrackingSessionStore()
    private var idCounter = 0

    private fun TestScope.newRepo(
        sessionStore: TrackingSessionStore = store,
        scope: CoroutineScope = backgroundScope
    ) = DefaultTrackingRepository(sessionStore, scope, clock) { "s${++idCounter}" }

    private val leg0 = TransitLeg(
        legIndex = 0,
        fromStopName = "上车站A",
        toStopName = "换乘站B",
        toStopId = "B",
        candidateLines = listOf(CandidateLine(cityId = "hz", lineId = "L1", lineName = "156路", fromStopOrder = 3, toStopOrder = 8))
    )
    private val leg1 = TransitLeg(
        legIndex = 1,
        fromStopName = "换乘站B",
        toStopName = "终点站C",
        toStopId = "C",
        candidateLines = listOf(CandidateLine(cityId = "hz", lineId = "L2", lineName = "28路", fromStopOrder = 2, toStopOrder = 6))
    )
    private val route = TrackingTarget.Route(
        group = TransitGroup(id = "g1", name = "上班", legs = listOf(leg0, leg1), createdAt = 0L, updatedAt = 0L)
    )
    private val single = TrackingTarget.SingleLine(
        cityId = "sh",
        lineId = "L49",
        lineName = "49路",
        targetStopName = "上海体育馆",
        targetOrder = 10
    )

    private fun bus(id: String, order: Int, progress: Float = 0.5f) = LiveBus(
        busId = id,
        plateOrFleetNo = id,
        order = order,
        segmentProgress = progress,
        wgsLat = 30.0,
        wgsLng = 120.0
    )

    private fun fix() = UserFix(lat = 30.0, lng = 120.0, accuracyMeters = 10f, elapsedMillis = clock.elapsedMillis())

    /** 盘里总是读到 [onDisk]（读盘慢于本进程第一次写盘的情形），写入另记 */
    private class StaleReadStore(private val onDisk: TrackingSession?) : TrackingSessionStore {
        var written: List<TrackingSession> = listOfNotNull(onDisk)
        override suspend fun read(): List<TrackingSession> = listOfNotNull(onDisk)
        override suspend fun write(sessions: List<TrackingSession>) {
            written = sessions
        }
    }

    /**
     * 测试里只追一项：这些便捷写法都作用在「当前那一份会话」上，省得每处都写一遍 sessionId。
     */
    private fun TrackingRepository.update(generation: Int, transform: (TrackingSession) -> TrackingSession?): Boolean {
        val current = session.value ?: return false
        return update(current.sessionId, generation, transform)
    }

    private fun TrackingRepository.recordBuses(generation: Int, buses: List<LiveBus>) {
        val current = session.value ?: return
        recordBuses(current.sessionId, generation, buses)
    }

    private fun TrackingRepository.recordLineBuses(generation: Int, lineBuses: Map<String, List<LiveBus>>) {
        val current = session.value ?: return
        recordLineBuses(current.sessionId, generation, lineBuses)
    }

    private fun TrackingRepository.resume(): TrackingSession? {
        val current = session.value ?: return null
        return resume(current.sessionId)
    }

    @Test
    fun pushOnline_validatesGenerationAndMembership_andKeepsOtherSessions() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val first = assertNotNull(repo.start(single.copy(lineId = "ms:1_1_0")))
        val second = assertNotNull(repo.start(single.copy(lineId = "ms:1_2_0")))
        repo.setPushOnline(first.sessionId, first.generation - 1, first.leg.lineId, true)
        repo.setPushOnline(first.sessionId, first.generation, second.leg.lineId, true)
        assertTrue(repo.pushOnlineLines.value.isEmpty())
        repo.setPushOnline(first.sessionId, first.generation, first.leg.lineId, true)
        repo.setPushOnline(second.sessionId, second.generation, second.leg.lineId, true)
        repo.setPushOnline(first.sessionId, first.generation, first.leg.lineId, false)
        assertEquals(mapOf(second.sessionId to setOf(second.leg.lineId)), repo.pushOnlineLines.value)
        repo.setPushOnline(first.sessionId, first.generation, first.leg.lineId, true)
        repo.stop(first.sessionId)
        repo.setPushOnline(first.sessionId, first.generation, first.leg.lineId, true)
        assertEquals(mapOf(second.sessionId to setOf(second.leg.lineId)), repo.pushOnlineLines.value)
        repo.stop()
        assertTrue(repo.pushOnlineLines.value.isEmpty())
    }

    @Test
    fun pushOnline_removedWatchLineCannotReturn_andGenerationChangeClearsStatus() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val target = TrackingTarget.WatchTogether(
            cityId = "ms", stopId = "stop", stopName = "站",
            lines = listOf(WatchLine("ms:1_1_0", "1路", 3), WatchLine("ms:1_2_0", "2路", 3))
        )
        val session = assertNotNull(repo.start(target))
        target.lines.forEach { repo.setPushOnline(session.sessionId, session.generation, it.lineId, true) }
        repo.removeWatchLine(session.sessionId, target.lines.first().lineId)
        repo.setPushOnline(session.sessionId, session.generation, target.lines.first().lineId, true)
        assertEquals(setOf(target.lines.last().lineId), repo.pushOnlineLines.value[session.sessionId])
        repo.update(session.sessionId, session.generation) { it.copy(generation = it.generation + 1) }
        repo.setPushOnline(session.sessionId, session.generation, target.lines.last().lineId, true)
        assertTrue(repo.pushOnlineLines.value.isEmpty())
    }

    @Test
    fun pushOnline_isNotPersisted_andRestoreRequiresFreshOnlineData() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val target = single.copy(lineId = "ms:1_1_0")
        val first = assertNotNull(repo.start(target))
        repo.setPushOnline(first.sessionId, first.generation, first.leg.lineId, true)
        repo.flush()
        val restoredProcess = newRepo()
        restoredProcess.awaitLoaded()
        restoredProcess.setPushOnline(first.sessionId, first.generation, first.leg.lineId, true)
        assertTrue(restoredProcess.pushOnlineLines.value.isEmpty())
        val resumed = assertNotNull(restoredProcess.resume(first.sessionId))
        assertTrue(restoredProcess.pushOnlineLines.value.isEmpty())
        restoredProcess.setPushOnline(resumed.sessionId, resumed.generation, resumed.leg.lineId, true)
        assertEquals(setOf(resumed.leg.lineId), restoredProcess.pushOnlineLines.value[resumed.sessionId])

        val failed = assertNotNull(repo.start(target))
        repo.setPushOnline(failed.sessionId, failed.generation, failed.leg.lineId, true)
        repo.restore(failed, first)
        assertTrue(repo.pushOnlineLines.value.isEmpty())
    }

    // ---------------- 落盘读回 ----------------

    @Test
    fun newInstance_readsBackSessionAsInterrupted_untilResume() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        val started = assertNotNull(first.start(route))
        first.flush()

        // 进程被杀后重建
        clock.advanceBy(10.minutes)
        val second = newRepo()
        second.awaitLoaded()

        val restored = assertNotNull(second.session.value)
        assertTrue(restored.interrupted)
        assertEquals(started.sessionId, restored.sessionId)
        assertEquals(started.generation, restored.generation)
        assertEquals(started.state, restored.state)
        assertFalse(second.legState.value.isActive)
        assertNull(second.groupSession.value)
        // 中断期间写回一律丢弃
        assertFalse(second.update(restored.generation) { it })

        val resumed = assertNotNull(second.resume())
        assertFalse(resumed.interrupted)
        assertEquals(started.generation + 1, resumed.generation)
        assertEquals(resumed, second.session.value)
        assertEquals(started.state, second.legState.value)
        assertTrue(second.legState.value.isActive)
        assertEquals("g1", second.groupSession.value?.groupId)
        // 接上之后不再是中断状态，再 resume 原样返回
        assertEquals(resumed, second.resume())
    }

    @Test
    fun readBack_olderThanMaxResumeAge_isDroppedAndStoreCleared() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        assertNotNull(first.start(single))
        first.flush()

        clock.advanceBy((DefaultTrackingRepository.MAX_RESUME_AGE_MILLIS / 60_000L + 1).minutes)
        val second = newRepo()
        second.awaitLoaded()
        second.flush()

        assertNull(second.session.value)
        assertFalse(second.legState.value.isActive)
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun startBeforeLoadCompletes_discardsReadBackSession() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        val old = assertNotNull(first.start(route))
        first.flush()

        val second = newRepo()
        // 还没让出线程：init 里的读盘还没跑
        val fresh = assertNotNull(second.start(single))
        second.awaitLoaded()
        second.flush()

        assertEquals(fresh, second.session.value)
        assertFalse(fresh.interrupted)
        assertEquals(listOf(fresh), store.read())
        assertTrue(fresh.sessionId != old.sessionId)
    }

    @Test
    fun startBeforeLoadCompletes_withSlowRead_ignoresStaleDiskSession_andKeepsGenerationsAbove() = runTest {
        // 上个进程用到了第 50 代
        val old = assertNotNull(TrackingEngine.begin("old", 50, route, clock.nowMillis))

        // 盘里读到的始终是旧会话（读盘发生在本进程第一次写盘之后，也仍拿到旧值）
        val slowStore = StaleReadStore(old)
        val second = newRepo(sessionStore = slowStore)
        val fresh = assertNotNull(second.start(single))
        second.awaitLoaded()
        second.flush()

        assertEquals(fresh, second.session.value)
        assertFalse(assertNotNull(second.session.value).interrupted)
        assertEquals(listOf(fresh), slowStore.written)
        // 读回的旧会话代际号也算“用过的”：之后的会话代际号都比它大
        second.stop()
        val later = assertNotNull(second.start(single))
        assertTrue(later.generation > old.generation, "later=${later.generation} old=${old.generation}")
    }

    // ---------------- 代际号 ----------------

    @Test
    fun update_withStaleGeneration_isDiscarded() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))
        val g = session.generation

        assertFalse(repo.update(g - 1) { it.copy(state = it.state.copy(statusSummary = "旧结果")) })
        assertEquals(session, repo.session.value)

        assertTrue(repo.update(g) { it.copy(state = it.state.copy(statusSummary = "新结果")) })
        assertEquals("新结果", repo.session.value?.state?.statusSummary)
        assertEquals("新结果", repo.legState.value.statusSummary)

        // 再开一项追踪：老会话不受影响，它自己的代际号照样写得进去
        val next = assertNotNull(repo.start(single))
        assertTrue(next.generation > g)
        assertEquals(listOf(session.sessionId, next.sessionId), repo.sessions.value.map { it.sessionId })
        assertTrue(repo.update(g) { it.copy(state = it.state.copy(statusSummary = "老会话")) })
        assertEquals("老会话", repo.sessions.value.first { it.sessionId == session.sessionId }.state.statusSummary)

        // 停止后：任何代际号都不生效
        repo.stop()
        assertFalse(repo.update(next.sessionId, next.generation) { it })
        assertNull(repo.session.value)
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun update_transformReturningNull_endsSession() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))

        assertTrue(repo.update(session.generation) { null })
        assertNull(repo.session.value)
        assertFalse(repo.legState.value.isActive)
        assertNull(repo.groupSession.value)
        assertFalse(repo.update(session.generation) { it })
        repo.flush()
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun update_transformSeesCurrentValue_afterConfirmBoarded() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))
        assertEquals(LegPhase.WAITING_BOARD, session.state.legPhase)

        // 轮询在路上时用户点了「我上车了」
        repo.confirmBoarded()
        var seen: LegPhase? = null
        assertTrue(repo.update(session.generation) { current -> seen = current.state.legPhase; current })
        assertEquals(LegPhase.ON_BOARD, seen)
        assertEquals(LegPhase.ON_BOARD, repo.session.value?.state?.legPhase)
    }

    @Test
    fun stop_doesNotRollBackGeneration() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val seen = mutableListOf<Int>()

        val a = assertNotNull(repo.start(route))
        seen += a.generation
        // 引擎切程（代际号 +1）
        assertTrue(repo.update(a.generation) { it.copy(generation = it.generation + 1) })
        seen += assertNotNull(repo.session.value).generation
        repo.stop()

        val b = assertNotNull(repo.start(single))
        assertTrue(b.generation > seen.max(), "b=${b.generation} seen=$seen")
        seen += b.generation
        repo.stop()

        val c = assertNotNull(repo.start(single))
        assertTrue(c.generation > seen.max(), "c=${c.generation} seen=$seen")
    }

    // ---------------- 命令 ----------------

    @Test
    fun confirmBoarded_locksBusRecordedForCurrentGeneration() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))

        repo.recordBuses(session.generation, listOf(bus("next", order = 2), bus("mine", order = 4, progress = 0.2f)))
        repo.confirmBoarded()

        val boarded = assertNotNull(repo.session.value)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("mine", boarded.state.lockedBusId)
        assertEquals(session.generation, boarded.generation)
    }

    @Test
    fun recordBuses_withWrongGeneration_isIgnored() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))

        repo.recordBuses(session.generation - 1, listOf(bus("mine", order = 4, progress = 0.2f)))
        repo.confirmBoarded()

        val boarded = assertNotNull(repo.session.value)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertNull(boarded.state.lockedBusId)
    }

    @Test
    fun confirmBoarded_outsideWaitingBoard_doesNothing() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(single))
        repo.confirmBoarded()
        assertEquals(session, repo.session.value)
    }

    @Test
    fun switchToNextLeg_bumpsGeneration_andClearsUserFix() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))
        repo.updateUserFix(fix())
        assertNotNull(repo.latestUserFix)

        repo.switchToNextLeg()

        val next = assertNotNull(repo.session.value)
        assertEquals(session.generation + 1, next.generation)
        assertEquals(1, next.state.currentLegIndex)
        assertEquals("L2", next.leg.lineId)
        assertNull(repo.latestUserFix)
        assertEquals(1, repo.groupSession.value?.activeLegIndex)
        assertFalse(repo.update(session.generation) { it })

        // 最后一程：什么都不做
        repo.switchToNextLeg()
        assertEquals(next, repo.session.value)
    }

    @Test
    fun startAndStop_clearUserFix() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        repo.updateUserFix(fix())
        // 再开一项不清定位：同时追着的好几项都要用它判下车站（过期与否按年龄判断）
        assertNotNull(repo.start(route))
        assertNotNull(repo.latestUserFix)
        repo.stop()
        assertNull(repo.latestUserFix)
    }

    @Test
    fun groupSession_forRoute_andNullForSingleLine() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))

        val group = assertNotNull(repo.groupSession.value)
        assertEquals("g1", group.groupId)
        assertEquals("上班", group.groupName)
        assertEquals(0, group.activeLegIndex)
        assertEquals(2, group.totalLegs)
        assertEquals(session.state, group.trackingSessionState)
        assertEquals(session.state, repo.legState.value)

        // 再追一条单线路：多出一份会话，路线那份还在（列表第一份仍是它）
        val singleSession = assertNotNull(repo.start(single))
        assertEquals(2, repo.sessions.value.size)
        assertEquals("g1", repo.groupSession.value?.groupId)
        assertTrue(repo.legState.value.isActive)

        // 结束路线那一份之后，主会话变成单线路，换乘组视图跟着没了
        repo.stop(session.sessionId)
        assertEquals(listOf(singleSession.sessionId), repo.sessions.value.map { it.sessionId })
        assertNull(repo.groupSession.value)
        assertTrue(repo.legState.value.isActive)
    }

    @Test
    fun start_routeWithoutLegs_returnsNullAndKeepsCurrentSession() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(single))
        assertNull(repo.start(route.copy(group = route.group.copy(legs = emptyList()))))
        assertEquals(session, repo.session.value)
    }

    @Test
    fun cycleThreshold_cyclesAndIsIgnoredWhileInterrupted() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(single))
        assertEquals(2, session.state.alertThresholdStops)

        repo.cycleThreshold()
        assertEquals(3, repo.session.value?.state?.alertThresholdStops)
        repo.cycleThreshold()
        assertEquals(1, repo.session.value?.state?.alertThresholdStops)
        repo.cycleThreshold()
        assertEquals(2, repo.legState.value.alertThresholdStops)
        repo.flush()

        // 读回的中断会话：不改
        val restored = newRepo()
        restored.awaitLoaded()
        val interrupted = assertNotNull(restored.session.value)
        assertTrue(interrupted.interrupted)
        restored.cycleThreshold()
        assertEquals(interrupted, restored.session.value)
    }

    @Test
    fun writesArePersisted_inCallOrder() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val session = assertNotNull(repo.start(route))
        repo.cycleThreshold()
        repo.flush()
        assertEquals(listOfNotNull(repo.session.value), store.read())
        assertEquals(listOf(TrackingEngine.cycleThreshold(session, clock.nowMillis)), store.read())

        repo.stop()
        repo.flush()
        assertTrue(store.read().isEmpty())
    }

    // ---------------- 开始失败放回 / 无效目标 / 改系统时间 ----------------

    @Test
    fun restore_putsPreviousSessionBackWithNewerGeneration() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val previous = assertNotNull(repo.start(single))
        val failed = assertNotNull(repo.start(route))

        repo.restore(failed, previous)

        val restored = assertNotNull(repo.session.value)
        assertEquals(previous.sessionId, restored.sessionId)
        assertTrue(restored.generation > failed.generation)
        assertEquals(LegPhase.SINGLE, repo.legState.value.legPhase)
        // 失败的那一代写回被丢弃
        assertFalse(repo.update(failed.generation) { it })
    }

    @Test
    fun restore_withoutPrevious_endsSession_andIgnoresIfSessionMovedOn() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val failed = assertNotNull(repo.start(single))
        val newer = assertNotNull(repo.start(route))

        // 已经换成别的会话了：不动
        repo.restore(failed, null)
        assertEquals(newer.sessionId, repo.session.value?.sessionId)

        repo.restore(newer, null)
        assertNull(repo.session.value)
        assertFalse(repo.legState.value.isActive)
    }

    @Test
    fun invalidStartBeforeLoad_doesNotDiscardReadBackSession() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        val started = assertNotNull(first.start(single))
        first.flush()

        val second = newRepo()
        // 读盘完成前点了一个没有程的路线：什么都不动
        assertNull(second.start(TrackingTarget.Route(group = TransitGroup(id = "empty", name = "空", createdAt = 0L, updatedAt = 0L))))
        second.awaitLoaded()

        val readBack = assertNotNull(second.session.value)
        assertEquals(started.sessionId, readBack.sessionId)
        assertTrue(readBack.interrupted)
    }

    @Test
    fun readBack_whenWallClockMovedBackwards_isStillResumable() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        assertNotNull(first.start(single))
        first.flush()

        // 用户把系统时间往回调了 10 分钟
        clock.setWallClock(clock.nowMillis - 10.minutes.inWholeMilliseconds)
        val second = newRepo()
        second.awaitLoaded()

        assertNotNull(second.session.value)
        assertNotNull(second.resume())
        assertTrue(second.legState.value.isActive)
    }

    // ---------------------------------------------------------------- 一起等（第 15 步）

    private val watch = TrackingTarget.WatchTogether(
        cityId = "sz",
        stopId = "st1",
        stopName = "科技园站",
        lines = listOf(
            WatchLine(lineId = "L1", lineName = "325", stopOrder = 5),
            WatchLine(lineId = "L2", lineName = "M194", stopOrder = 5)
        )
    )

    private fun atStop(id: String) = LiveBus(busId = id, plateOrFleetNo = id, order = 5, isArrived = true)

    @Test
    fun watch_confirmBoarded_twoLinesAtStop_writesBoardChoice_thenChoosingBoards() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val started = assertNotNull(repo.start(watch))
        assertEquals(2, started.watchedLines.size)
        repo.recordLineBuses(started.generation, mapOf("L1" to listOf(atStop("a"))))
        repo.recordLineBuses(started.generation, mapOf("L2" to listOf(atStop("b"))))

        repo.confirmBoarded()
        val choosing = assertNotNull(repo.session.value)
        assertEquals(setOf("a", "b"), assertNotNull(choosing.boardChoice).options.map { it.busId }.toSet())
        assertEquals(started.generation, choosing.generation)
        assertEquals(LegPhase.WAITING_BOARD, repo.legState.value.legPhase)

        repo.chooseBoardedBus("b")
        val boarded = assertNotNull(repo.session.value)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("L2", boarded.leg.lineId)
        assertEquals("b", boarded.state.lockedBusId)
        assertEquals(started.generation + 1, boarded.generation)
        // 上车前那一代的轮询结果作废
        assertFalse(repo.update(started.generation) { it })
        // 没有待选了：再选什么都不做
        repo.chooseBoardedBus("a")
        assertEquals(boarded, repo.session.value)
    }

    @Test
    fun watch_confirmBoarded_oneLineAtStop_boardsDirectly() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val started = assertNotNull(repo.start(watch))
        repo.recordLineBuses(started.generation, mapOf("L1" to listOf(bus("far", order = 2)), "L2" to listOf(atStop("b"))))

        repo.confirmBoarded()

        val boarded = assertNotNull(repo.session.value)
        assertNull(boarded.boardChoice)
        assertEquals("L2", boarded.leg.lineId)
        assertEquals("b", repo.legState.value.lockedBusId)
    }

    @Test
    fun route_confirmBoarded_keepsGenerationAndUsesLastBuses() = runTest {
        // 第 15 步回归：路线「我已上车」不切代际号，照旧用 recordBuses 记下的车
        val repo = newRepo()
        repo.awaitLoaded()
        val started = assertNotNull(repo.start(route))
        repo.recordBuses(started.generation, listOf(bus("near", order = 3, progress = 0.9f)))

        repo.confirmBoarded()

        val boarded = assertNotNull(repo.session.value)
        assertEquals(started.generation, boarded.generation)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("near", boarded.state.lockedBusId)
        assertNull(boarded.boardChoice)
        assertTrue(repo.update(started.generation) { it })
    }

    @Test
    fun watch_sessionWithWatchedLines_survivesReadBack() = runTest {
        val first = newRepo()
        first.awaitLoaded()
        val started = assertNotNull(first.start(watch))
        first.flush()

        val second = newRepo()
        second.awaitLoaded()
        val resumed = assertNotNull(second.resume())
        assertEquals(started.sessionId, resumed.sessionId)
        assertEquals(started.watchedLines, resumed.watchedLines)
        assertEquals(watch, resumed.target)
    }

    // ---------------- 同时追多项（第 36 步） ----------------

    @Test
    fun twoTargets_coexist_andStopOnlyEndsThatOne() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val first = assertNotNull(repo.start(single))
        val second = assertNotNull(repo.start(single.copy(lineId = "L71", lineName = "71路")))

        // 两份都在，按开始先后排；主会话是第一份
        assertEquals(listOf(first.sessionId, second.sessionId), repo.sessions.value.map { it.sessionId })
        assertEquals(first.sessionId, repo.session.value?.sessionId)
        assertEquals(second.sessionId, repo.sessionById(second.sessionId)?.sessionId)

        // 结束其中一份，另一份接着追
        repo.stop(first.sessionId)
        assertEquals(listOf(second.sessionId), repo.sessions.value.map { it.sessionId })
        assertEquals(second.sessionId, repo.session.value?.sessionId)
        assertTrue(repo.legState.value.isActive)

        repo.stop()
        assertTrue(repo.sessions.value.isEmpty())
        assertFalse(repo.legState.value.isActive)
    }

    @Test
    fun start_sameTarget_replacesInsteadOfStacking() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val first = assertNotNull(repo.start(single))
        val again = assertNotNull(repo.start(single))

        val only = assertNotNull(repo.session.value)
        assertEquals(1, repo.sessions.value.size)
        assertEquals(again.sessionId, only.sessionId)
        assertTrue(only.generation > first.generation)
    }

    @Test
    fun start_moreThanMaxSessions_dropsOldest() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val started = (1..TrackingRepository.MAX_SESSIONS + 1).map { i ->
            assertNotNull(repo.start(single.copy(lineId = "L$i", lineName = "${i}路")))
        }

        assertEquals(TrackingRepository.MAX_SESSIONS, repo.sessions.value.size)
        // 最早开始的那一份被挤掉
        assertNull(repo.sessionById(started.first().sessionId))
        assertEquals(started.last().sessionId, repo.sessions.value.last().sessionId)
    }

    @Test
    fun removeWatchLine_dropsOnlyThatLine_andEndsSessionWithTheLastOne() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val lines = listOf(
            WatchLine(lineId = "L1", lineName = "325", stopOrder = 5),
            WatchLine(lineId = "L2", lineName = "M194", stopOrder = 8)
        )
        val session = assertNotNull(
            repo.start(
                TrackingTarget.WatchTogether(cityId = "sz", stopId = "st", stopName = "科技园站", lines = lines)
            )
        )

        repo.removeWatchLine(session.sessionId, "L1")

        val after = assertNotNull(repo.session.value)
        assertEquals(listOf("L2"), after.watchTarget?.lines?.map { it.lineId })
        assertEquals(listOf("L2"), after.watchedLines.map { it.line.lineId })
        // 主显示跟着换成剩下那条线
        assertEquals("L2", after.state.lineId)

        repo.removeWatchLine(after.sessionId, "L2")
        assertTrue(repo.sessions.value.isEmpty())
        assertNull(repo.session.value)
    }

    @Test
    fun update_onlyAppliesToItsOwnSession() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val first = assertNotNull(repo.start(single))
        val second = assertNotNull(repo.start(single.copy(lineId = "L71", lineName = "71路")))

        assertTrue(repo.update(first.sessionId, first.generation) { it.copy(state = it.state.copy(statusSummary = "一号")) })
        assertFalse(repo.update(second.sessionId, first.generation) { it })

        assertEquals("一号", repo.sessionById(first.sessionId)?.state?.statusSummary)
        assertEquals(second, repo.sessionById(second.sessionId))
    }

    @Test
    fun start_singleLineAlreadyWaitingInWatch_doesNotAddASecondSession() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val watch = assertNotNull(
            repo.start(
                TrackingTarget.WatchTogether(
                    cityId = "sz",
                    stopId = "st",
                    stopName = "科技园站",
                    lines = listOf(WatchLine(lineId = "L1", lineName = "325", stopOrder = 5), WatchLine(lineId = "L2", lineName = "M194", stopOrder = 8))
                )
            )
        )

        // 同一站同一批线里点这条线的「追踪」：已经在一起等里了，不叠第二份
        val again = assertNotNull(
            repo.start(
                TrackingTarget.SingleLine(
                    cityId = "sz",
                    lineId = "L1",
                    lineName = "325",
                    targetStopId = "st",
                    targetStopName = "科技园站",
                    targetOrder = 5
                )
            )
        )

        assertEquals(watch.sessionId, again.sessionId)
        assertEquals(1, repo.sessions.value.size)
    }

    @Test
    fun start_watchAbsorbsLineTrackedAloneAtTheSameStop() = runTest {
        val repo = newRepo()
        repo.awaitLoaded()
        val single = assertNotNull(
            repo.start(
                TrackingTarget.SingleLine(
                    cityId = "sz",
                    lineId = "L1",
                    lineName = "325",
                    targetStopId = "st",
                    targetStopName = "科技园站",
                    targetOrder = 5
                )
            )
        )

        val watch = assertNotNull(
            repo.start(
                TrackingTarget.WatchTogether(
                    cityId = "sz",
                    stopId = "st",
                    stopName = "科技园站",
                    lines = listOf(WatchLine(lineId = "L1", lineName = "325", stopOrder = 5), WatchLine(lineId = "L2", lineName = "M194", stopOrder = 8))
                )
            )
        )

        // 那条线被一起等收进来，只剩一起等这一份
        assertEquals(listOf(watch.sessionId), repo.sessions.value.map { it.sessionId })
        assertNull(repo.sessionById(single.sessionId))
    }
}
