package com.trackr.core.domain.tracking

import com.trackr.core.domain.math.CoordTransform
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.model.UserFix
import com.trackr.core.model.WatchLine
import com.trackr.core.model.withAlertThresholdStops
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 追踪器写进 updatedAtMillis 的「现在」（第 7 步起由调用方传入），这些用例不看它 */
private const val NOW_MILLIS = 1_000_000L

/**
 * 追踪状态机 [TrackingEngine]，以及它内部用的 [GroupLegTracker]、[SingleLegTracker]、[BoardingDetector]、[LocationCadence]。
 *
 * - `group_*`：原 GroupLegTrackerTest（路线语义：上车站 A = order 3，下车站 B = order 8）
 * - `single_*`：原 SingleLegTrackerTest（目标候车站 = order 10，提前 2 站提醒）
 * - `boarding_*`：原 BoardingDetectionTest（上车站 A = order 3，坐标 STOP_LAT/STOP_LNG；“A 以北 N 米”，纬度每 0.001° ≈ 111 米）
 * - `transfer_*`：原 androidApp TransferAutoSwitchManagerTest 的纯逻辑部分（轮询间隔、下车站判定）
 * - `engine_*`：引擎对外行为（开始 / 轮询 / 切程 / 用户操作 / 定位取舍）
 */
class TrackingEngineTest {

    private companion object {
        // 原 BoardingDetectionTest
        const val STOP_LAT = 31.2300
        const val STOP_LNG = 121.4700
        const val T0 = 1_000_000L

        // 原 TransferAutoSwitchManagerTest
        const val TRANSFER_NOW = 1_000_000_000L

        // 引擎用例：单调时钟“现在”
        const val ELAPSED_NOW = 5_000_000L
    }

    // ================================================================ 原 GroupLegTrackerTest

    private fun groupBus(id: String, order: Int, arrived: Boolean = false, progress: Float = 0.5f, eta: Int = 300) =
        LiveBus(
            busId = id,
            plateOrFleetNo = id,
            order = order,
            segmentProgress = progress,
            isArrived = arrived,
            etaSeconds = eta
        )

    private fun groupWaitingSession() = TrackingSessionState(
        isActive = true,
        legPhase = LegPhase.WAITING_BOARD,
        lineName = "156路",
        boardStopOrder = 3,
        boardStopName = "上车站A",
        targetOrder = 8,
        targetStopName = "下车站B"
    )

    @Test
    fun group_waitingBoard_ignoresBusThatAlreadyPassedBoardStop() {
        // 人在 A（order 3）等车，前方一辆车已过 A（order 5），不应被选中
        val session = groupWaitingSession()
        val buses = listOf(
            groupBus("passed", order = 5, progress = 0.3f), // 已过 A
            groupBus("coming", order = 2, progress = 0.4f)  // 还没到 A
        )
        val update = GroupLegTracker.processPoll(session, buses, nowMillis = NOW_MILLIS)
        // 选中的应是还没到 A 的那辆
        assertEquals("coming", update.state.currentBusId)
        assertFalse(update.reachedBoardStop)
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
    }

    @Test
    fun group_waitingBoard_recordsWaitStartOrderForNotificationProgress() {
        // 选中车辆时记录它所在的站作为等车区间起点；同一辆车后续轮询保持不变
        val first = GroupLegTracker.processPoll(groupWaitingSession(), listOf(groupBus("coming", order = 2, progress = 0.4f)), nowMillis = NOW_MILLIS)
        assertEquals(1, first.state.startTrackingOrder)
        val second = GroupLegTracker.processPoll(first.state, listOf(groupBus("coming", order = 3, progress = 0.2f)), nowMillis = NOW_MILLIS)
        assertEquals(1, second.state.startTrackingOrder)
    }

    @Test
    fun group_waitingBoard_noEligibleBus_whenAllPassedBoardStop() {
        val session = groupWaitingSession()
        val buses = listOf(groupBus("passed", order = 6))
        val update = GroupLegTracker.processPoll(session, buses, nowMillis = NOW_MILLIS)
        assertNull(update.state.currentBusId)
        assertFalse(update.reachedBoardStop)
    }

    @Test
    fun group_waitingBoard_lockBusWhenArriveAtBoardStop() {
        // 车到达 A（order==3 且已停靠）-> 锁定该车，进入 ON_BOARD
        val session = groupWaitingSession()
        val buses = listOf(groupBus("busX", order = 3, arrived = true, progress = 1.0f, eta = 0))
        val update = GroupLegTracker.processPoll(session, buses, nowMillis = NOW_MILLIS)
        assertTrue(update.reachedBoardStop)
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("busX", update.state.lockedBusId)
    }

    @Test
    fun group_onBoard_tracksOnlyLockedBus_andReachesDropStop() {
        val onBoard = groupWaitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX"
        )
        // 另一辆非锁定车已到 B，不应触发切程
        val otherAtB = listOf(
            groupBus("other", order = 8, arrived = true, progress = 1.0f, eta = 0),
            groupBus("busX", order = 5, progress = 0.5f, eta = 300)
        )
        val update1 = GroupLegTracker.processPoll(onBoard, otherAtB, nowMillis = NOW_MILLIS)
        assertFalse(update1.reachedDropStop)
        assertEquals("busX", update1.state.currentBusId)

        // 锁定车 busX 到达 B -> 触发切程 / 完成
        val lockedAtB = listOf(groupBus("busX", order = 8, arrived = true, progress = 1.0f, eta = 0))
        val update2 = GroupLegTracker.processPoll(onBoard, lockedAtB, nowMillis = NOW_MILLIS)
        assertTrue(update2.reachedDropStop)
    }

    @Test
    fun group_onBoard_lockedBusDisappears_keepsStateAndSignalsLost() {
        val onBoard = groupWaitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX",
            stopsRemaining = 2,
            statusSummary = "距下车站B 还有 2 站"
        )
        // 锁定车从数据消失，只有别的车
        val update = GroupLegTracker.processPoll(onBoard, listOf(groupBus("other", order = 7)), nowMillis = NOW_MILLIS)
        assertFalse(update.reachedDropStop)
        assertTrue(update.state.isSignalLost)
        // 不换成别的车
        assertEquals("busX", update.state.lockedBusId)
    }

    // ---------------- 到站口径统一 ----------------

    @Test
    fun group_arrival_etaZeroMeansNoPrediction_notArrived() {
        // eta=0 表示无预测；坐标缺失时 progress 默认 1.0 也不可信
        val noPrediction = groupBus("b", order = 3, progress = 1.0f, eta = 0)
        assertFalse(GroupLegTracker.isArrivedAtOrder(3, noPrediction))
        assertTrue(GroupLegTracker.isArrivedAtOrder(3, noPrediction.copy(etaSeconds = 30)))
    }

    @Test
    fun group_arrival_progressTrustedOnlyWithValidCoordinates() {
        val noCoords = groupBus("b", order = 3, progress = 1.0f, eta = 300)
        assertFalse(noCoords.hasValidProgress)
        assertFalse(GroupLegTracker.isArrivedAtOrder(3, noCoords))

        val withCoords = noCoords.copy(wgsLat = 30.27, wgsLng = 120.15)
        assertTrue(withCoords.hasValidProgress)
        assertTrue(GroupLegTracker.isArrivedAtOrder(3, withCoords))
    }

    @Test
    fun group_waitingBoard_missingCoordinatesDefaultProgress_doesNotLockBus() {
        // 车在 A 前一段、坐标缺失（progress 默认 1.0），不应误判到站并锁车
        val update = GroupLegTracker.processPoll(groupWaitingSession(), listOf(groupBus("b", order = 3, progress = 1.0f, eta = 200)), nowMillis = NOW_MILLIS)
        assertFalse(update.reachedBoardStop)
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
    }

    @Test
    fun group_onBoard_lockedBusPassedDropStop_countsAsArrived() {
        val onBoard = groupWaitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "busX")
        val update = GroupLegTracker.processPoll(onBoard, listOf(groupBus("busX", order = 9, progress = 0.2f, eta = -1)), nowMillis = NOW_MILLIS)
        assertTrue(update.reachedDropStop)
    }

    // ---------------- 手动“我已上车”选车 ----------------

    @Test
    fun group_pickBoardedBus_prefersBusWithinAToAPlusOneNearestToA() {
        val buses = listOf(
            groupBus("next", order = 2, progress = 0.5f),           // 下一班，还没到 A
            groupBus("justLeft", order = 4, progress = 0.1f).copy(wgsLat = 30.0, wgsLng = 120.0), // 刚离开 A
            groupBus("farAhead", order = 6, progress = 0.5f)        // 早就开走
        )
        assertEquals("justLeft", GroupLegTracker.pickBoardedBus(3, buses)?.busId)
    }

    @Test
    fun group_pickBoardedBus_busStoppedAtA_wins() {
        val buses = listOf(
            groupBus("atA", order = 3, arrived = true, progress = 1.0f),
            groupBus("justLeft", order = 4, progress = 0.3f).copy(wgsLat = 30.0, wgsLng = 120.0)
        )
        assertEquals("atA", GroupLegTracker.pickBoardedBus(3, buses)?.busId)
    }

    @Test
    fun group_pickBoardedBus_noneInRange_returnsNull() {
        val buses = listOf(groupBus("next", order = 2), groupBus("farAhead", order = 6))
        assertNull(GroupLegTracker.pickBoardedBus(3, buses))
    }

    @Test
    fun group_confirmBoarded_locksPickedBus_insteadOfWaitingCandidate() {
        // 等车阶段 currentBusId 已经变成下一班（order 2），用户实际坐的是刚离开 A 的车
        val waiting = groupWaitingSession().copy(currentBusId = "next")
        val buses = listOf(
            groupBus("next", order = 2, progress = 0.5f),
            groupBus("mine", order = 4, progress = 0.2f).copy(wgsLat = 30.0, wgsLng = 120.0)
        )
        val confirmed = GroupLegTracker.confirmBoarded(waiting, buses, nowMillis = NOW_MILLIS)
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertEquals("mine", confirmed.lockedBusId)
        assertEquals(3, confirmed.startTrackingOrder)
        assertFalse(confirmed.hasTriggeredApproachingAlert)
        assertFalse(confirmed.hasTriggeredArrivedAlert)
    }

    @Test
    fun group_confirmBoarded_withoutBus_relocksOnNextPoll() {
        val confirmed = GroupLegTracker.confirmBoarded(groupWaitingSession(), emptyList(), nowMillis = NOW_MILLIS)
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertNull(confirmed.lockedBusId)

        // 下一次轮询：按同样规则补锁
        val update = GroupLegTracker.processPoll(
            confirmed,
            listOf(groupBus("next", order = 2), groupBus("mine", order = 4, progress = 0.4f)),
            nowMillis = NOW_MILLIS
        )
        assertEquals("mine", update.state.lockedBusId)
        assertEquals("mine", update.state.currentBusId)
        assertFalse(update.state.isSignalLost)
        assertEquals(4, update.state.stopsRemaining)
    }

    @Test
    fun group_onBoard_nullLockedBus_firstRelockStaysStrict_thenRelaxesToAPlusFour() {
        val confirmed = GroupLegTracker.confirmBoarded(groupWaitingSession(), emptyList(), nowMillis = NOW_MILLIS)
        assertNull(confirmed.lockedBusId)

        // 第一次补查仍按严格的 A..A+1（A=3）：order 4 在范围内，直接锁上
        val strict = GroupLegTracker.processPoll(
            confirmed,
            listOf(groupBus("farAhead", order = 6), groupBus("mine", order = 4, progress = 0.4f)),
            nowMillis = NOW_MILLIS
        )
        assertEquals("mine", strict.state.lockedBusId)
        assertEquals(0, strict.state.boardingLockAttempts)

        // 第一次一辆都没有：只记一次尝试，不锁车
        val missed = GroupLegTracker.processPoll(confirmed, listOf(groupBus("farAhead", order = 6)), nowMillis = NOW_MILLIS)
        assertNull(missed.state.lockedBusId)
        assertEquals(1, missed.state.boardingLockAttempts)

        // 第二次起放宽到 A..A+4：order 6 也能锁回来
        val relaxed = GroupLegTracker.processPoll(missed.state, listOf(groupBus("farAhead", order = 6)), nowMillis = NOW_MILLIS)
        assertEquals("farAhead", relaxed.state.lockedBusId)
        assertEquals(0, relaxed.state.boardingLockAttempts)
    }

    @Test
    fun group_onBoard_nullLockedBus_lockAttemptsStopGrowingAtCap() {
        var state = GroupLegTracker.confirmBoarded(groupWaitingSession(), emptyList(), nowMillis = NOW_MILLIS)
        assertNull(state.lockedBusId)

        // 一直锁不到车（A+4 之外才有车）：尝试次数涨到上限就不再涨，也不会锁错车
        repeat(GroupLegTracker.BOARDING_LOCK_MAX_ATTEMPTS * 3) {
            state = GroupLegTracker.processPoll(
                state,
                listOf(groupBus("tooFar", order = 3 + GroupLegTracker.BOARDING_LOCK_MAX_AHEAD_RELAXED + 2)),
                nowMillis = NOW_MILLIS
            ).state
            assertNull(state.lockedBusId)
            assertTrue(state.boardingLockAttempts <= GroupLegTracker.BOARDING_LOCK_MAX_ATTEMPTS)
        }
        assertEquals(GroupLegTracker.BOARDING_LOCK_MAX_ATTEMPTS, state.boardingLockAttempts)
    }

    @Test
    fun group_onBoard_nullLockedBus_noCandidate_keepsWaitingForLock() {
        val confirmed = GroupLegTracker.confirmBoarded(groupWaitingSession(), emptyList(), nowMillis = NOW_MILLIS)
        val update = GroupLegTracker.processPoll(confirmed, listOf(groupBus("farAhead", order = 7)), nowMillis = NOW_MILLIS)
        assertNull(update.state.lockedBusId)
        assertFalse(update.reachedDropStop)
    }

    // ---------------- 提醒编排 ----------------

    @Test
    fun group_planAlerts_boardArrival_firesBoardAlertThenResetsFlags_andSkipsDropApproaching() {
        // 这一程只有 2 站（A=3, B=5），阈值 2：旧逻辑会在上车同一轮立刻发“即将到下车站”
        val waiting = groupWaitingSession().copy(targetOrder = 5, alertThresholdStops = 2, hasTriggeredApproachingAlert = true)
        val update = GroupLegTracker.processPoll(waiting, listOf(groupBus("busX", order = 3, arrived = true, progress = 1.0f)), nowMillis = NOW_MILLIS)
        assertTrue(update.reachedBoardStop)

        val decision = GroupLegTracker.planAlerts(waiting, update)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, decision.alert)
        assertEquals("上车站A", decision.alertState?.targetStopName)
        assertEquals(LegPhase.ON_BOARD, decision.state.legPhase)
        assertFalse(decision.state.hasTriggeredApproachingAlert)
        assertFalse(decision.state.hasTriggeredArrivedAlert)

        // 下一轮才做下车站临近判定
        val next = GroupLegTracker.processPoll(decision.state, listOf(groupBus("busX", order = 4, progress = 0.3f)), nowMillis = NOW_MILLIS)
        val nextDecision = GroupLegTracker.planAlerts(decision.state, next)
        assertEquals(GroupLegTracker.AlertKind.DROP_APPROACHING, nextDecision.alert)
        assertTrue(nextDecision.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun group_planAlerts_boardApproaching_usesBoardStopName_once() {
        val waiting = groupWaitingSession()
        val update = GroupLegTracker.processPoll(waiting, listOf(groupBus("coming", order = 2, progress = 0.4f)), nowMillis = NOW_MILLIS)
        val decision = GroupLegTracker.planAlerts(waiting, update)
        assertEquals(GroupLegTracker.AlertKind.BOARD_APPROACHING, decision.alert)
        assertEquals("上车站A", decision.alertState?.targetStopName)

        val again = GroupLegTracker.processPoll(decision.state, listOf(groupBus("coming", order = 2, progress = 0.6f)), nowMillis = NOW_MILLIS)
        assertNull(GroupLegTracker.planAlerts(decision.state, again).alert)
    }

    @Test
    fun group_planAlerts_dropArrival_notLastLeg_noArrivedAlert_lastLeg_arrivedAlert() {
        val onBoard = groupWaitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "busX",
            currentLegIndex = 0,
            totalLegs = 2
        )
        val atB = listOf(groupBus("busX", order = 8, arrived = true, progress = 1.0f))
        val transfer = GroupLegTracker.planAlerts(onBoard, GroupLegTracker.processPoll(onBoard, atB, nowMillis = NOW_MILLIS))
        // 非最后一程：只由换乘引擎发换乘提醒
        assertNull(transfer.alert)
        assertTrue(transfer.state.hasTriggeredArrivedAlert)

        val lastLeg = onBoard.copy(currentLegIndex = 1)
        val finish = GroupLegTracker.planAlerts(lastLeg, GroupLegTracker.processPoll(lastLeg, atB, nowMillis = NOW_MILLIS))
        assertEquals(GroupLegTracker.AlertKind.DROP_ARRIVED, finish.alert)
    }

    @Test
    fun group_planAlerts_signalLost_noDropApproaching() {
        val onBoard = groupWaitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "busX", stopsRemaining = 1)
        val update = GroupLegTracker.processPoll(onBoard, emptyList(), nowMillis = NOW_MILLIS)
        assertTrue(update.state.isSignalLost)
        assertNull(GroupLegTracker.planAlerts(onBoard, update).alert)
    }

    // ================================================================ 原 SingleLegTrackerTest

    private fun singleBus(id: String, order: Int, arrived: Boolean = false, progress: Float = 0.5f, eta: Int = 300) =
        LiveBus(
            busId = id,
            plateOrFleetNo = id,
            order = order,
            segmentProgress = progress,
            isArrived = arrived,
            etaSeconds = eta
        )

    private fun singleSession() = TrackingSessionState(
        isActive = true,
        lineName = "49路",
        targetStopName = "上海体育馆",
        targetOrder = 10,
        startTrackingOrder = 9,
        alertThresholdStops = 2
    )

    @Test
    fun single_picksNearestBusNotPassedTarget() {
        val update = SingleLegTracker.processPoll(
            singleSession(),
            listOf(singleBus("passed", order = 11), singleBus("far", order = 3), singleBus("near", order = 6)),
            nowMillis = NOW_MILLIS
        )
        assertEquals("near", update.state.currentBusId)
        assertEquals(4, update.state.stopsRemaining)
        assertNull(update.alert)
    }

    @Test
    fun single_approachingThenArrived_eachAlertOnce() {
        val first = SingleLegTracker.processPoll(singleSession(), listOf(singleBus("b1", order = 8)), nowMillis = NOW_MILLIS)
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, first.alert)
        assertTrue(first.state.hasTriggeredApproachingAlert)

        val second = SingleLegTracker.processPoll(first.state, listOf(singleBus("b1", order = 9)), nowMillis = NOW_MILLIS)
        assertNull(second.alert)

        val arrived = SingleLegTracker.processPoll(second.state, listOf(singleBus("b1", order = 10, arrived = true, progress = 1f)), nowMillis = NOW_MILLIS)
        assertEquals(SingleLegTracker.AlertKind.ARRIVED, arrived.alert)
        assertTrue(arrived.state.isBusArrivedAtStop)
        assertTrue(arrived.state.hasTriggeredArrivedAlert)

        val again = SingleLegTracker.processPoll(arrived.state, listOf(singleBus("b1", order = 10, arrived = true, progress = 1f)), nowMillis = NOW_MILLIS)
        assertNull(again.alert)
    }

    @Test
    fun single_etaZeroAndDefaultProgress_isNotArrival() {
        // 坐标缺失时 progress 默认 1.0、eta=0 无预测：只能算“即将进站”，不能发到站提醒
        val update = SingleLegTracker.processPoll(singleSession(), listOf(singleBus("b1", order = 10, progress = 1f, eta = 0)), nowMillis = NOW_MILLIS)
        assertFalse(update.state.isBusArrivedAtStop)
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, update.alert)
    }

    @Test
    fun single_switchingBusAfterPassingTarget_resetsAlertFlags() {
        val arrived = SingleLegTracker.processPoll(singleSession(), listOf(singleBus("b1", order = 10, arrived = true, progress = 1f)), nowMillis = NOW_MILLIS)
        assertTrue(arrived.state.hasTriggeredArrivedAlert)

        // b1 开过目标站，b2 在 order 9：换车并重新提醒
        val switched = SingleLegTracker.processPoll(arrived.state, listOf(singleBus("b1", order = 11), singleBus("b2", order = 9)), nowMillis = NOW_MILLIS)
        assertEquals("b2", switched.state.currentBusId)
        assertEquals(SingleLegTracker.AlertKind.APPROACHING, switched.alert)
        assertTrue(switched.state.hasTriggeredApproachingAlert)
        assertFalse(switched.state.hasTriggeredArrivedAlert)
    }

    @Test
    fun single_trackedBusBrieflyMissing_keepsSameBusForTwoPolls() {
        val tracking = SingleLegTracker.processPoll(singleSession(), listOf(singleBus("b1", order = 5)), nowMillis = NOW_MILLIS).state
        assertEquals("b1", tracking.currentBusId)

        val miss1 = SingleLegTracker.processPoll(tracking, listOf(singleBus("b2", order = 7)), nowMillis = NOW_MILLIS)
        assertEquals("b1", miss1.state.currentBusId)
        assertEquals(1, miss1.state.missingBusPolls)
        assertNull(miss1.alert)

        val miss2 = SingleLegTracker.processPoll(miss1.state, listOf(singleBus("b2", order = 7)), nowMillis = NOW_MILLIS)
        assertEquals("b1", miss2.state.currentBusId)
        assertEquals(2, miss2.state.missingBusPolls)

        // 第三轮仍不见：换车
        val miss3 = SingleLegTracker.processPoll(miss2.state, listOf(singleBus("b2", order = 7)), nowMillis = NOW_MILLIS)
        assertEquals("b2", miss3.state.currentBusId)
        assertEquals(0, miss3.state.missingBusPolls)
    }

    @Test
    fun single_trackedBusReappears_resetsMissingCounter() {
        val tracking = SingleLegTracker.processPoll(singleSession(), listOf(singleBus("b1", order = 5)), nowMillis = NOW_MILLIS).state
        val miss1 = SingleLegTracker.processPoll(tracking, emptyList(), nowMillis = NOW_MILLIS)
        val back = SingleLegTracker.processPoll(miss1.state, listOf(singleBus("b1", order = 6)), nowMillis = NOW_MILLIS)
        assertEquals("b1", back.state.currentBusId)
        assertEquals(0, back.state.missingBusPolls)
    }

    @Test
    fun single_noBus_notRunningLine_showsOperatingState() {
        val update = SingleLegTracker.processPoll(
            singleSession(),
            emptyList(),
            operatingState = LineOperatingState.ENDED,
            statusDescription = "",
            nowMillis = NOW_MILLIS
        )
        assertNull(update.state.currentBusId)
        assertEquals(-1, update.state.stopsRemaining)
        assertEquals(LineOperatingState.ENDED.defaultLabel, update.state.statusSummary)
    }

    // ================================================================ 上车后自动结束追踪（issue #32）
    // 单线路：候车站就是目标站（order 10），坐标 STOP_LAT/STOP_LNG

    private fun singleWaitStop() =
        BusStop(stopId = "S10", name = "上海体育馆", order = 10, wgsLat = STOP_LAT, wgsLng = STOP_LNG)

    private fun singleDetail(buses: List<LiveBus>) = detail(buses, listOf(singleWaitStop()))

    private fun singlePoll(
        session: TrackingSession,
        buses: List<LiveBus>,
        fix: UserFix?,
        stopAfterBoarding: Boolean = true
    ) = TrackingEngine.reducePoll(
        session = session,
        detail = singleDetail(buses),
        locationEnabled = fix != null,
        serviceFix = fix,
        dropFix = null,
        nowMillis = NOW_MILLIS + 1,
        nowElapsedMillis = ELAPSED_NOW,
        stopAfterBoarding = stopAfterBoarding
    )

    @Test
    fun engine_single_autoStopAfterBoarding_endsTrackingWhenUserRidesAwayWithBus() {
        val session = beginSingle()
        // 车停到候车站、人在站台：进入待确认，追踪继续
        val arrived = singlePoll(session, listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)), serviceFixNorth(20))
        assertEquals("b1", arrived.session.state.boardingCandidateBusId)
        assertTrue(arrived.effects.none { it is TrackingEngine.Effect.Finished })

        // 人跟着车走了两次（车开过候车站后仍在数据里，按 busId 找得到）
        val riding = singlePoll(
            arrived.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 150)),
            serviceFixNorth(150, ageMillis = 6_000)
        )
        assertEquals(1, riding.session.state.boardingConfirmStreak)
        assertTrue(riding.effects.none { it is TrackingEngine.Effect.Finished })

        val done = singlePoll(
            riding.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 320)),
            serviceFixNorth(310, ageMillis = 7_000)
        )
        val finished = assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
        assertEquals("49路", finished.state.lineName)
        assertEquals(SingleLegTracker.BOARDED_SUMMARY, done.session.state.statusSummary)
        // 待确认字段清干净了，不会带着旧状态结束
        assertNull(done.session.state.boardingCandidateBusId)
    }

    @Test
    fun engine_single_autoStop_recoversWhenPollSkipsArrival() {
        val approaching = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 9, metersNorth = -100)), serviceFixNorth(0))
        assertNull(approaching.session.state.boardingCandidateBusId)
        val departed = singlePoll(approaching.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 150)), serviceFixNorth(150, ageMillis = 6_000))
        assertEquals("b1", departed.session.state.boardingCandidateBusId)
        assertEquals(LocationCadence.Tier.BOARDING_CHECK,
            TrackingEngine.locationTier(departed.session.state, locationEnabled = true, stopAfterBoarding = true))
        val repeated = singlePoll(departed.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 150)), serviceFixNorth(150, ageMillis = 6_000))
        assertEquals(1, repeated.session.state.boardingConfirmStreak)
        assertTrue(repeated.effects.none { it is TrackingEngine.Effect.Finished })
        val done = singlePoll(repeated.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 250)), serviceFixNorth(250, ageMillis = 5_000))
        assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
    }

    @Test
    fun engine_single_autoStop_recoversWhenLocationArrivesAfterDeparture() {
        val arrived = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 10, metersNorth = 0)), fix = null)
        val departed = singlePoll(arrived.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 150)), serviceFixNorth(150, ageMillis = 6_000))
        assertEquals("b1", departed.session.state.boardingCandidateBusId)
        val done = singlePoll(departed.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 250)), serviceFixNorth(250, ageMillis = 5_000))
        assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
    }

    @Test
    fun engine_single_coarseFixAtStop_startsCandidate() {
        // issue #41：街上定位常常只有 60–100 m 精度，车到站那一轮也要建得起待确认
        val arrived = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)),
            serviceFixNorth(60, accuracy = 90f))
        assertEquals("b1", arrived.session.state.boardingCandidateBusId)
        assertTrue(arrived.effects.none { it is TrackingEngine.Effect.Finished })
    }

    @Test
    fun engine_single_coarseFixesFarFromStop_confirmBoarding() {
        val arrived = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)), serviceFixNorth(20))
        // 粗定位：不算「贴着车」，但离站超过 400 m + 精度、车也开走了，连续两次就是上车了
        val near = singlePoll(arrived.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 150)),
            serviceFixNorth(150, ageMillis = 6_000, accuracy = 90f))
        assertEquals(0, near.session.state.boardingConfirmStreak)
        val far1 = singlePoll(near.session,
            listOf(boardingBus("b1", order = 12, metersNorth = 600)),
            serviceFixNorth(600, ageMillis = 5_000, accuracy = 90f))
        assertEquals(1, far1.session.state.boardingConfirmStreak)
        val far2 = singlePoll(far1.session,
            listOf(boardingBus("b1", order = 12, metersNorth = 700)),
            serviceFixNorth(700, ageMillis = 4_000, accuracy = 90f))
        assertIs<TrackingEngine.Effect.Finished.Boarded>(far2.effects.single())
    }

    @Test
    fun engine_single_coarseFixAtStop_neverCountsAsMissed() {
        val arrived = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)), serviceFixNorth(20))
        var session = arrived.session
        repeat(3) { index ->
            // 粗定位判不了「人还在站上」：既不算上车，也不算没赶上，待确认留着
            val result = singlePoll(session,
                listOf(boardingBus("b1", order = 12, metersNorth = 600)),
                serviceFixNorth(0, ageMillis = 6_000L - index * 1_000, accuracy = 90f))
            session = result.session
        }
        assertEquals("b1", session.state.boardingCandidateBusId)
        assertEquals(0, session.state.boardingMissStreak)
    }

    @Test
    fun engine_single_autoStop_recoversWhenPollSkipsTwoStops() {
        val approaching = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 9, metersNorth = -100)), serviceFixNorth(0))
        val departed = singlePoll(approaching.session,
            listOf(boardingBus("b1", order = 12, metersNorth = 150)), serviceFixNorth(150, ageMillis = 6_000))
        assertEquals("b1", departed.session.state.boardingCandidateBusId)
    }

    @Test
    fun engine_single_skippedArrival_userStaysAtStop_doesNotFinish() {
        var session = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 9, metersNorth = -100)), serviceFixNorth(0)).session
        repeat(2) { index ->
            val result = singlePoll(session,
                listOf(boardingBus("b1", order = 11, metersNorth = 350)),
                serviceFixNorth(0, ageMillis = 6_000L - index * 1_000))
            assertTrue(result.effects.none { it is TrackingEngine.Effect.Finished })
            session = result.session
        }
        assertNull(session.state.boardingCandidateBusId)
    }

    @Test
    fun engine_single_skippedArrival_doesNotRecoverUnrelatedOrOldVehicle() {
        val approaching = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 9, metersNorth = -100)), serviceFixNorth(0)).session
        val cases = listOf(
            approaching to boardingBus("other", order = 11, metersNorth = 150),
            // 一跳两站还认（issue #41），三站就不是「刚开走」了
            approaching to boardingBus("b1", order = 13, metersNorth = 150),
            approaching.copy(state = approaching.state.copy(updatedAtMillis = NOW_MILLIS - 61_000)) to
                boardingBus("b1", order = 11, metersNorth = 150)
        )
        cases.forEach { (session, bus) ->
            val result = singlePoll(session, listOf(bus), serviceFixNorth(150))
            assertNull(result.session.state.boardingCandidateBusId)
            assertTrue(result.effects.none { it is TrackingEngine.Effect.Finished })
        }
    }

    @Test
    fun engine_single_skippedArrival_requiresUsableLocationNearStop() {
        val approaching = singlePoll(beginSingle(),
            listOf(boardingBus("b1", order = 9, metersNorth = -100)), serviceFixNorth(0)).session
        val fixes = listOf(null, serviceFixNorth(500), serviceFixNorth(150, ageMillis = 31_000),
            serviceFixNorth(150).copy(accuracyMeters = 120f))
        fixes.forEach { fix ->
            val result = singlePoll(approaching,
                listOf(boardingBus("b1", order = 11, metersNorth = 150)), fix)
            assertNull(result.session.state.boardingCandidateBusId)
            assertTrue(result.effects.none { it is TrackingEngine.Effect.Finished })
        }
    }

    @Test
    fun engine_single_autoStopOff_neverEndsTracking() {
        val session = beginSingle()
        val arrived = singlePoll(
            session,
            listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)),
            serviceFixNorth(20),
            stopAfterBoarding = false
        )
        // 开关关着：连待确认都不创建（单线路原本就不用定位）
        assertNull(arrived.session.state.boardingCandidateBusId)
        val riding = singlePoll(
            arrived.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 320)),
            serviceFixNorth(310, ageMillis = 7_000),
            stopAfterBoarding = false
        )
        assertTrue(riding.effects.none { it is TrackingEngine.Effect.Finished })
    }

    @Test
    fun engine_single_autoStop_userStaysAtStop_keepsTracking() {
        val session = beginSingle()
        val arrived = singlePoll(session, listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)), serviceFixNorth(20))
        assertEquals("b1", arrived.session.state.boardingCandidateBusId)

        // 人还在站台、车自己开走了：判没赶上，追踪继续（等下一班）
        var polled = arrived.session
        repeat(2) { index ->
            val result = singlePoll(
                polled,
                listOf(boardingBus("b1", order = 11, metersNorth = 350)),
                serviceFixNorth(5, ageMillis = 6_000L + index * 1_000L)
            )
            assertTrue(result.effects.none { it is TrackingEngine.Effect.Finished })
            polled = result.session
        }
        assertNull(polled.state.boardingCandidateBusId)
        assertEquals(0, polled.state.boardingConfirmStreak)
        // 追踪还在：车过站后照原样等下一班
        assertEquals(-1, polled.state.stopsRemaining)
    }

    @Test
    fun engine_single_autoStop_withoutLocation_keepsTracking() {
        val session = beginSingle()
        val arrived = singlePoll(session, listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true)), fix = null)
        assertNull(arrived.session.state.boardingCandidateBusId)
        val riding = singlePoll(arrived.session, listOf(boardingBus("b1", order = 11, metersNorth = 320)), fix = null)
        assertTrue(riding.effects.none { it is TrackingEngine.Effect.Finished })
    }

    @Test
    fun engine_locationTier_singleTakesLocationOnlyWithAutoStop() {
        val single = beginSingle().state.copy(stopsRemaining = 2)
        assertEquals(LocationCadence.Tier.OFF, TrackingEngine.locationTier(single, locationEnabled = true))
        assertEquals(
            LocationCadence.Tier.WAITING_NEAR,
            TrackingEngine.locationTier(single, locationEnabled = true, stopAfterBoarding = true)
        )
        val atStop = single.copy(stopsRemaining = 0)
        assertEquals(
            LocationCadence.Tier.BOARDING_CHECK,
            TrackingEngine.locationTier(atStop, locationEnabled = true, stopAfterBoarding = true)
        )
        val candidate = single.copy(boardingCandidateBusId = "b1", stopsRemaining = -1)
        assertEquals(
            LocationCadence.Tier.BOARDING_CHECK,
            TrackingEngine.locationTier(candidate, locationEnabled = true, stopAfterBoarding = true)
        )
        assertEquals(10_000L, TrackingEngine.pollingIntervalMillis(candidate, operatingState = LineOperatingState.ENDED))
        assertEquals(
            LocationCadence.Tier.OFF,
            TrackingEngine.locationTier(single, locationEnabled = false, stopAfterBoarding = true)
        )
    }

    @Test
    fun engine_single_autoStop_recoversWhenFixArrivesOnePollAfterDeparture() {
        // 第 1 轮：车在站，定位还没回来
        val atStop = TrackingEngine.reducePoll(
            session = beginSingle(),
            detail = singleDetail(listOf(boardingBus("b1", order = 10, metersNorth = 0))),
            locationEnabled = true,
            serviceFix = null,
            dropFix = null,
            nowMillis = NOW_MILLIS,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        // 第 2 轮：车刚过站（order=11），定位依然没回来 -> 记录 recentDepartedBusId，保持高频定位与 10s 轮询
        val departedNoFix = TrackingEngine.reducePoll(
            session = atStop.session,
            detail = singleDetail(listOf(boardingBus("b1", order = 11, metersNorth = 80))),
            locationEnabled = true,
            serviceFix = null,
            dropFix = null,
            nowMillis = NOW_MILLIS + 10_000,
            nowElapsedMillis = ELAPSED_NOW + 10_000,
            stopAfterBoarding = true
        )
        assertNull(departedNoFix.session.state.boardingCandidateBusId)
        assertEquals("b1", departedNoFix.session.state.recentDepartedBusId)
        assertEquals(
            LocationCadence.Tier.BOARDING_CHECK,
            TrackingEngine.locationTier(departedNoFix.session.state, locationEnabled = true, stopAfterBoarding = true)
        )
        assertEquals(10_000L, TrackingEngine.pollingIntervalMillis(departedNoFix.session.state))

        // 第 3 轮：定位终于到了，补回 b1 并记第 1 次确认
        val firstConfirm = TrackingEngine.reducePoll(
            session = departedNoFix.session,
            detail = singleDetail(listOf(boardingBus("b1", order = 11, metersNorth = 150))),
            locationEnabled = true,
            serviceFix = serviceFixNorth(150, ageMillis = 6_000),
            dropFix = null,
            nowMillis = NOW_MILLIS + 20_000,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        assertEquals("b1", firstConfirm.session.state.boardingCandidateBusId)
        assertNull(firstConfirm.session.state.recentDepartedBusId)
        assertEquals(1, firstConfirm.session.state.boardingConfirmStreak)

        // 第 4 轮：第 2 次确认 -> 自动结束追踪
        val done = TrackingEngine.reducePoll(
            session = firstConfirm.session,
            detail = singleDetail(listOf(boardingBus("b1", order = 11, metersNorth = 280))),
            locationEnabled = true,
            serviceFix = serviceFixNorth(280, ageMillis = 4_000),
            dropFix = null,
            nowMillis = NOW_MILLIS + 30_000,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
    }

    @Test
    fun engine_single_autoStop_usesDropFix_toleratesMovingBusLag_andSuppressesNextBusAlertWhileConfirming() {
        // 车从还有 2 站（order=8）一轮跳到过站（order=11，已开出 340m），且只有仓库里的 dropFix（首页刚取到的定位）
        val twoStopsAway = TrackingEngine.reducePoll(
            session = beginSingle(),
            detail = singleDetail(listOf(boardingBus("b1", order = 8, metersNorth = -300))),
            locationEnabled = true,
            serviceFix = null,
            dropFix = serviceFixNorth(10, ageMillis = 8_000),
            nowMillis = NOW_MILLIS,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        val firstConfirm = TrackingEngine.reducePoll(
            session = twoStopsAway.session,
            detail = singleDetail(
                listOf(
                    boardingBus("b1", order = 11, metersNorth = 340),
                    boardingBus("b2", order = 9, metersNorth = -120)
                )
            ),
            locationEnabled = true,
            serviceFix = null,
            dropFix = serviceFixNorth(350, ageMillis = 6_000),
            nowMillis = NOW_MILLIS + 20_000,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        assertEquals("b1", firstConfirm.session.state.boardingCandidateBusId)
        assertEquals(1, firstConfirm.session.state.boardingConfirmStreak)
        // 正在确认用户已在 b1 上时，不抢发后车 b2 的临近提醒
        assertTrue(firstConfirm.effects.isEmpty())

        // 下一轮车载 GPS 滞后手机实时定位 120m（在移动车辆 160m 容差内），完成第 2 次确认并结束
        val done = TrackingEngine.reducePoll(
            session = firstConfirm.session,
            detail = singleDetail(
                listOf(
                    boardingBus("b1", order = 11, metersNorth = 380),
                    boardingBus("b2", order = 9, metersNorth = -80)
                )
            ),
            locationEnabled = true,
            serviceFix = null,
            dropFix = serviceFixNorth(500, ageMillis = 4_000),
            nowMillis = NOW_MILLIS + 30_000,
            nowElapsedMillis = ELAPSED_NOW,
            stopAfterBoarding = true
        )
        assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
    }

    @Test
    fun engine_single_autoStop_switchesCandidateWhenUserBoardsFollowingBus() {
        // 前车 b1 到站进待确认，但用户没上 b1，上了紧随其后的 b2
        val arrivedB1 = singlePoll(
            beginSingle(),
            listOf(boardingBus("b1", order = 10, metersNorth = 0, arrived = true), boardingBus("b2", order = 10, metersNorth = -20)),
            serviceFixNorth(10)
        )
        assertEquals("b1", arrivedB1.session.state.boardingCandidateBusId)

        val ridingB2 = singlePoll(
            arrivedB1.session,
            listOf(boardingBus("b1", order = 11, metersNorth = 450), boardingBus("b2", order = 11, metersNorth = 140)),
            serviceFixNorth(140, ageMillis = 6_000)
        )
        assertEquals("b2", ridingB2.session.state.boardingCandidateBusId)
        assertEquals(1, ridingB2.session.state.boardingConfirmStreak)

        val done = singlePoll(
            ridingB2.session,
            listOf(boardingBus("b1", order = 12, metersNorth = 800), boardingBus("b2", order = 11, metersNorth = 280)),
            serviceFixNorth(280, ageMillis = 4_000)
        )
        assertIs<TrackingEngine.Effect.Finished.Boarded>(done.effects.single())
    }

    // ================================================================ 原 BoardingDetectionTest

    private fun northOfStop(meters: Int): Double = STOP_LAT + meters / 111_000.0

    private fun boardingBus(
        id: String,
        order: Int,
        metersNorth: Int? = null,
        arrived: Boolean = false,
        progress: Float = 0.5f,
        eta: Int = 300
    ) = LiveBus(
        busId = id,
        plateOrFleetNo = id,
        order = order,
        segmentProgress = progress,
        isArrived = arrived,
        wgsLat = metersNorth?.let { northOfStop(it) } ?: 0.0,
        wgsLng = if (metersNorth != null) STOP_LNG else 0.0,
        etaSeconds = eta
    )

    private fun input(userMetersNorth: Int?, now: Long = T0, accuracy: Float = 10f) = BoardingDetector.Input(
        user = userMetersNorth?.let { BoardingDetector.UserPosition(northOfStop(it), STOP_LNG, accuracy) },
        boardStopLat = STOP_LAT,
        boardStopLng = STOP_LNG,
        nowMillis = now
    )

    private fun boardingWaitingSession() = TrackingSessionState(
        isActive = true,
        legPhase = LegPhase.WAITING_BOARD,
        lineName = "156路",
        boardStopOrder = 3,
        boardStopName = "上车站A",
        targetOrder = 8,
        targetStopName = "下车站B",
        totalLegs = 2
    )

    /** 车 b1 到站、用户就在站台：进入待确认 */
    private fun candidateSession(): TrackingSessionState {
        val update = GroupLegTracker.processPoll(
            boardingWaitingSession(),
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0),
            nowMillis = NOW_MILLIS
        )
        return GroupLegTracker.planAlerts(boardingWaitingSession(), update).state
    }

    @Test
    fun boarding_busArrives_userAtStop_startsCandidateInsteadOfBoarding() {
        val update = GroupLegTracker.processPoll(
            boardingWaitingSession(),
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 20),
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.WAITING_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.boardingCandidateBusId)
        assertTrue(update.state.isAwaitingBoardingConfirmation)
        assertTrue(update.busArrivedAtBoard)
        assertFalse(update.reachedBoardStop)
        assertNull(update.state.lockedBusId)
    }

    @Test
    fun boarding_busArrives_withoutLocation_keepsOriginalBoardingBehavior() {
        val update = GroupLegTracker.processPoll(
            boardingWaitingSession(),
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            boarding = null,
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.lockedBusId)
        assertTrue(update.reachedBoardStop)
    }

    @Test
    fun boarding_candidate_userRidesAwayWithBus_confirmsAfterTwoPolls() {
        val start = candidateSession()

        val first = GroupLegTracker.processPoll(
            start,
            listOf(boardingBus("b1", order = 4, metersNorth = 150)),
            input(userMetersNorth = 150, now = T0 + 10_000),
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.WAITING_BOARD, first.state.legPhase)
        assertEquals(1, first.state.boardingConfirmStreak)

        val second = GroupLegTracker.processPoll(
            first.state,
            listOf(boardingBus("b1", order = 4, metersNorth = 320)),
            input(userMetersNorth = 310, now = T0 + 20_000),
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.ON_BOARD, second.state.legPhase)
        assertEquals("b1", second.state.lockedBusId)
        assertTrue(second.reachedBoardStop)
        assertNull(second.state.boardingCandidateBusId)
        assertEquals(4, second.state.stopsRemaining)
    }

    @Test
    fun boarding_candidate_userStaysAtStopWhileBusLeaves_marksMissedAndWaitsForNextBus() {
        val start = candidateSession()
        assertTrue(start.hasTriggeredArrivedAlert)

        val first = GroupLegTracker.processPoll(
            start,
            listOf(boardingBus("b1", order = 4, metersNorth = 350), boardingBus("b2", order = 1, metersNorth = -2000)),
            input(userMetersNorth = 5, now = T0 + 10_000),
            nowMillis = NOW_MILLIS
        )
        assertEquals(1, first.state.boardingMissStreak)
        assertEquals(LegPhase.WAITING_BOARD, first.state.legPhase)

        val second = GroupLegTracker.processPoll(
            first.state,
            listOf(boardingBus("b1", order = 5, metersNorth = 700), boardingBus("b2", order = 1, metersNorth = -1800)),
            input(userMetersNorth = 5, now = T0 + 20_000),
            nowMillis = NOW_MILLIS
        )
        assertTrue(second.missedBoarding)
        assertEquals(LegPhase.WAITING_BOARD, second.state.legPhase)
        assertNull(second.state.boardingCandidateBusId)
        assertEquals("b2", second.state.currentBusId)
        // 新的一班车可以重新提醒
        assertFalse(second.state.hasTriggeredArrivedAlert)
        assertFalse(second.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun boarding_busArrives_userFarFromStop_doesNotBoardAndNextBusCanAlertAgain() {
        val arrived = GroupLegTracker.processPoll(
            boardingWaitingSession(),
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true), boardingBus("b2", order = 1, metersNorth = -2000)),
            input(userMetersNorth = 1200),
            nowMillis = NOW_MILLIS
        )
        assertTrue(arrived.busArrivedAtBoard)
        assertNull(arrived.state.boardingCandidateBusId)
        assertEquals(LegPhase.WAITING_BOARD, arrived.state.legPhase)
        val afterAlert = GroupLegTracker.planAlerts(boardingWaitingSession(), arrived)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, afterAlert.alert)

        // b1 开走了，b2 离 A 只剩 1 站：应重新发“即将到站”
        val next = GroupLegTracker.processPoll(
            afterAlert.state,
            listOf(boardingBus("b1", order = 4, metersNorth = 400), boardingBus("b2", order = 2, metersNorth = -300)),
            input(userMetersNorth = 1000, now = T0 + 30_000),
            nowMillis = NOW_MILLIS
        )
        assertEquals("b2", next.state.currentBusId)
        val nextAlert = GroupLegTracker.planAlerts(afterAlert.state, next)
        assertEquals(GroupLegTracker.AlertKind.BOARD_APPROACHING, nextAlert.alert)
    }

    @Test
    fun boarding_candidate_timeoutWithoutFix_boardsOnlyAfterBusLeftStop() {
        val start = candidateSession()

        val stillAtStop = GroupLegTracker.processPoll(
            start,
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = null, now = T0 + BoardingDetector.CANDIDATE_TIMEOUT_MILLIS + 1),
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.WAITING_BOARD, stillAtStop.state.legPhase)

        val left = GroupLegTracker.processPoll(
            stillAtStop.state,
            listOf(boardingBus("b1", order = 5, metersNorth = 900)),
            input(userMetersNorth = null, now = T0 + BoardingDetector.CANDIDATE_TIMEOUT_MILLIS + 10_000),
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.WAITING_BOARD, left.state.legPhase)
        assertNull(left.state.lockedBusId)
    }

    @Test
    fun boarding_candidate_locationBecomesUnavailable_fallsBackToBoarding() {
        val update = GroupLegTracker.processPoll(
            candidateSession(),
            listOf(boardingBus("b1", order = 4, metersNorth = 200)),
            boarding = null,
            nowMillis = NOW_MILLIS
        )
        assertEquals(LegPhase.ON_BOARD, update.state.legPhase)
        assertEquals("b1", update.state.lockedBusId)
    }

    @Test
    fun boarding_planAlerts_boardArrivedAlertFiresOnceAcrossCandidateAndConfirmation() {
        val waiting = boardingWaitingSession()
        val arrival = GroupLegTracker.processPoll(
            waiting,
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0),
            nowMillis = NOW_MILLIS
        )
        val first = GroupLegTracker.planAlerts(waiting, arrival)
        assertEquals(GroupLegTracker.AlertKind.BOARD_ARRIVED, first.alert)

        val pending = GroupLegTracker.processPoll(
            first.state,
            listOf(boardingBus("b1", order = 3, metersNorth = 0, arrived = true)),
            input(userMetersNorth = 0, now = T0 + 5_000),
            nowMillis = NOW_MILLIS
        )
        assertNull(GroupLegTracker.planAlerts(first.state, pending).alert)

        val c1 = GroupLegTracker.processPoll(
            pending.state,
            listOf(boardingBus("b1", order = 4, metersNorth = 150)),
            input(userMetersNorth = 150, now = T0 + 10_000),
            nowMillis = NOW_MILLIS
        )
        val c2 = GroupLegTracker.processPoll(
            c1.state,
            listOf(boardingBus("b1", order = 4, metersNorth = 300)),
            input(userMetersNorth = 300, now = T0 + 15_000),
            nowMillis = NOW_MILLIS
        )
        val boarded = GroupLegTracker.planAlerts(c1.state, c2)
        assertNull(boarded.alert)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        // 进入 ON_BOARD 后下车站提醒标志重置
        assertFalse(boarded.state.hasTriggeredArrivedAlert)
        assertFalse(boarded.state.hasTriggeredApproachingAlert)
    }

    @Test
    fun boarding_onBoard_wrongLockedBus_relocksAfterThreeConsistentPolls() {
        var session = boardingWaitingSession().copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = "wrong",
            currentBusId = "wrong"
        )
        val buses = listOf(
            boardingBus("wrong", order = 5, metersNorth = 3000),
            boardingBus("right", order = 5, metersNorth = 1520)
        )
        repeat(2) { i ->
            val update = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1500, now = T0 + i * 10_000L), nowMillis = NOW_MILLIS)
            assertFalse(update.relocked)
            assertEquals("wrong", update.state.lockedBusId)
            session = update.state
        }
        val third = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1500, now = T0 + 30_000), nowMillis = NOW_MILLIS)
        assertTrue(third.relocked)
        assertEquals("right", third.state.lockedBusId)
        assertEquals("right", third.state.currentBusId)
    }

    @Test
    fun boarding_onBoard_lockedBusNearUser_neverRelocks() {
        var session = boardingWaitingSession().copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "mine")
        val buses = listOf(boardingBus("mine", order = 5, metersNorth = 1500), boardingBus("other", order = 5, metersNorth = 1510))
        repeat(5) { i ->
            val update = GroupLegTracker.processPoll(session, buses, input(userMetersNorth = 1505, now = T0 + i * 10_000L), nowMillis = NOW_MILLIS)
            assertFalse(update.relocked)
            session = update.state
        }
        assertEquals("mine", session.lockedBusId)
    }

    @Test
    fun boarding_confirmBoarded_prefersBusAwaitingConfirmation() {
        val candidate = candidateSession()
        val buses = listOf(
            boardingBus("b1", order = 4, metersNorth = 200),
            boardingBus("closer", order = 3, metersNorth = -10, progress = 0.99f)
        )
        val confirmed = GroupLegTracker.confirmBoarded(candidate, buses, nowMillis = NOW_MILLIS)
        assertEquals(LegPhase.ON_BOARD, confirmed.legPhase)
        assertEquals("b1", confirmed.lockedBusId)
        assertNull(confirmed.boardingCandidateBusId)
    }

    @Test
    fun boarding_shouldStartCandidate_respectsRadius() {
        assertTrue(BoardingDetector.shouldStartCandidate(input(userMetersNorth = BoardingDetector.CANDIDATE_RADIUS_METERS - 20)))
        assertFalse(BoardingDetector.shouldStartCandidate(input(userMetersNorth = BoardingDetector.CANDIDATE_RADIUS_METERS + 50)))
        // 没有定位时不创建待确认，避免超时误判上车
        assertFalse(BoardingDetector.shouldStartCandidate(input(userMetersNorth = null)))
    }

    @Test
    fun boarding_locationCadence_followsTrackingPhase() {
        val waiting = boardingWaitingSession()
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(stopsRemaining = 6, etaSeconds = 900)))
        assertEquals(LocationCadence.Tier.WAITING_NEAR, LocationCadence.tierFor(waiting.copy(stopsRemaining = 2)))
        assertEquals(LocationCadence.Tier.WAITING_NEAR, LocationCadence.tierFor(waiting.copy(stopsRemaining = 5, etaSeconds = 120)))
        assertEquals(LocationCadence.Tier.BOARDING_CHECK, LocationCadence.tierFor(waiting.copy(boardingCandidateBusId = "b1")))
        val riding = waiting.copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "b1")
        assertEquals(LocationCadence.Tier.RIDING, LocationCadence.tierFor(riding.copy(stopsRemaining = 5)))
        assertEquals(LocationCadence.Tier.NEAR_DROP, LocationCadence.tierFor(riding.copy(stopsRemaining = 1)))
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(legPhase = LegPhase.SINGLE)))
        assertEquals(LocationCadence.Tier.OFF, LocationCadence.tierFor(waiting.copy(isActive = false)))
    }

    // ================================================================ 原 TransferAutoSwitchManagerTest（纯逻辑部分）

    private fun transferFix(lat: Double, lng: Double, accuracy: Float? = 10f, ageSeconds: Long = 5) =
        UserFix(lat, lng, accuracy, TRANSFER_NOW - ageSeconds * 1_000L)

    private fun transferLegAtUser(): Pair<TransitLeg, Pair<Double, Double>> {
        val userWgsLat = 30.2740
        val userWgsLng = 120.1550
        val (targetGcjLat, targetGcjLng) = CoordTransform.wgs84ToGcj02(userWgsLat, userWgsLng)
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = targetGcjLat,
            toStopLng = targetGcjLng
        )
        return leg to (userWgsLat to userWgsLng)
    }

    @Test
    fun transfer_adaptivePollingIntervalBackoff() {
        val approachingSession1 = TrackingSessionState(stopsRemaining = 1, etaSeconds = 120)
        assertEquals(10_000L, TrackingEngine.pollingIntervalMillis(approachingSession1))

        val approachingSession0 = TrackingSessionState(stopsRemaining = 0, etaSeconds = 30)
        assertEquals(10_000L, TrackingEngine.pollingIntervalMillis(approachingSession0))

        val mediumSession = TrackingSessionState(stopsRemaining = 3, etaSeconds = 450)
        assertEquals(20_000L, TrackingEngine.pollingIntervalMillis(mediumSession))

        val farSession = TrackingSessionState(stopsRemaining = 7, etaSeconds = 1200)
        assertEquals(45_000L, TrackingEngine.pollingIntervalMillis(farSession))

        val unknownSession = TrackingSessionState(stopsRemaining = -1, etaSeconds = -1)
        assertEquals(45_000L, TrackingEngine.pollingIntervalMillis(unknownSession))
    }

    @Test
    fun transfer_waitingBoardPhase_neverTriggersDropArrival() {
        // 等车阶段，即便 stopsRemaining==0 且公交到站，也绝不触发下车站到达 / 切程
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = 30.2740,
            toStopLng = 120.1550
        )
        val waiting = TrackingSessionState(
            legPhase = LegPhase.WAITING_BOARD,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        assertFalse(
            TrackingEngine.isArrivalSatisfied(
                state = waiting,
                leg = leg,
                userFix = transferFix(30.2740, 120.1550),
                nowElapsedMillis = TRANSFER_NOW
            )
        )
    }

    @Test
    fun transfer_onBoardPhase_busEventTriggersDropArrival() {
        val leg = TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B")
        val onBoard = TrackingSessionState(
            legPhase = LegPhase.ON_BOARD,
            stopsRemaining = 0,
            isBusArrivedAtStop = true
        )
        // 原用例走默认参数（setup 后没有定位）：这里显式传 null
        assertTrue(TrackingEngine.isArrivalSatisfied(onBoard, leg, userFix = null, nowElapsedMillis = TRANSFER_NOW))
    }

    @Test
    fun transfer_onBoardPhase_locationAssistTriggersDropArrival() {
        // ON_BOARD 阶段：公交未到站，但设备定位（WGS-84）距下车站（GCJ-02）< 150m，辅助判定到达。
        // leg 坐标是 GCJ-02；用户坐标是 WGS-84，判定时会 wgs84ToGcj02 转换对齐。
        val userWgsLat = 30.2740
        val userWgsLng = 120.1550
        val (targetGcjLat, targetGcjLng) = CoordTransform.wgs84ToGcj02(userWgsLat, userWgsLng)
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = targetGcjLat,
            toStopLng = targetGcjLng
        )
        val onBoard = TrackingSessionState(
            legPhase = LegPhase.ON_BOARD,
            stopsRemaining = 2,
            isBusArrivedAtStop = false
        )
        // 用户定位转换后恰好落在目标站附近，< 150m
        assertTrue(
            TrackingEngine.isArrivalSatisfied(
                state = onBoard,
                leg = leg,
                userFix = transferFix(userWgsLat, userWgsLng),
                nowElapsedMillis = TRANSFER_NOW
            )
        )

        // 距离很远 -> false
        assertFalse(
            TrackingEngine.isArrivalSatisfied(
                state = onBoard,
                leg = leg,
                userFix = transferFix(30.3900, 120.2700),
                nowElapsedMillis = TRANSFER_NOW
            )
        )
    }

    @Test
    fun transfer_onBoardPhase_staleOrInaccurateFix_doesNotTriggerDropArrival() {
        val (leg, user) = transferLegAtUser()
        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 2)

        // 定位超过 60 秒
        assertFalse(
            TrackingEngine.isArrivalSatisfied(onBoard, leg, transferFix(user.first, user.second, ageSeconds = 61), TRANSFER_NOW)
        )
        // 精度差于 50m
        assertFalse(
            TrackingEngine.isArrivalSatisfied(onBoard, leg, transferFix(user.first, user.second, accuracy = 80f), TRANSFER_NOW)
        )
        // 精度未知
        assertFalse(
            TrackingEngine.isArrivalSatisfied(onBoard, leg, transferFix(user.first, user.second, accuracy = null), TRANSFER_NOW)
        )
        // 边界：60 秒、50m 仍可用
        assertTrue(
            TrackingEngine.isArrivalSatisfied(
                onBoard, leg, transferFix(user.first, user.second, accuracy = 50f, ageSeconds = 60), TRANSFER_NOW
            )
        )
    }

    @Test
    fun transfer_onBoardPhase_etaZeroIsNotArrival() {
        // etaSeconds<=0 表示无预测，不能当成到站
        val leg = TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B")
        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 0, etaSeconds = 0)
        assertFalse(TrackingEngine.isArrivalSatisfied(onBoard, leg, userFix = null, nowElapsedMillis = TRANSFER_NOW))
        assertTrue(TrackingEngine.isArrivalSatisfied(onBoard.copy(etaSeconds = 30), leg, userFix = null, nowElapsedMillis = TRANSFER_NOW))
    }

    @Test
    fun transfer_pollingInterval_backsOffOnFailures_capsAt120s_andResetsOnSuccess() {
        val far = TrackingSessionState(stopsRemaining = 7, etaSeconds = 1200)
        assertEquals(90_000L, TrackingEngine.pollingIntervalMillis(far, consecutiveFailures = 1))
        assertEquals(120_000L, TrackingEngine.pollingIntervalMillis(far, consecutiveFailures = 2))
        assertEquals(120_000L, TrackingEngine.pollingIntervalMillis(far, consecutiveFailures = 30))

        val near = TrackingSessionState(stopsRemaining = 1, etaSeconds = 120)
        assertEquals(20_000L, TrackingEngine.pollingIntervalMillis(near, consecutiveFailures = 1))
        assertEquals(40_000L, TrackingEngine.pollingIntervalMillis(near, consecutiveFailures = 2))
        // 成功后（失败次数清零）恢复正常间隔
        assertEquals(10_000L, TrackingEngine.pollingIntervalMillis(near, consecutiveFailures = 0))
    }

    @Test
    fun transfer_pollingInterval_notRunningLine_pollsEveryFiveMinutes() {
        val session = TrackingSessionState(stopsRemaining = -1, etaSeconds = -1)
        assertEquals(
            300_000L,
            TrackingEngine.pollingIntervalMillis(session, operatingState = LineOperatingState.ENDED)
        )
        assertEquals(
            300_000L,
            TrackingEngine.pollingIntervalMillis(session, operatingState = LineOperatingState.WAITING)
        )
    }

    @Test
    fun transfer_pollingInterval_signalLost_atLeast30s() {
        val lost = TrackingSessionState(stopsRemaining = 1, etaSeconds = 60, isSignalLost = true)
        assertEquals(30_000L, TrackingEngine.pollingIntervalMillis(lost))
        val lostFar = TrackingSessionState(stopsRemaining = 7, isSignalLost = true)
        assertEquals(45_000L, TrackingEngine.pollingIntervalMillis(lostFar))
    }

    @Test
    fun transfer_isTrackingExpired_detectsTimeoutAndClockRollback() {
        // 一次正常的追踪：startedAtMillis 记的是开始那一刻
        val session = assertNotNull(
            TrackingEngine.begin("s1", 1, singleTarget(targetOrder = 10, threshold = 2), TRANSFER_NOW)
        )
        assertEquals(TRANSFER_NOW, session.state.startedAtMillis)

        // 刚追 1 分钟：没过期
        assertFalse(TrackingEngine.isTrackingExpired(session, TRANSFER_NOW + 60_000L))
        // 差 1 毫秒到上限：还没过期
        assertFalse(
            TrackingEngine.isTrackingExpired(
                session,
                TRANSFER_NOW + TrackingEngine.MAX_TRACKING_DURATION_MILLIS - 1
            )
        )
        // 正好到上限：过期
        assertTrue(
            TrackingEngine.isTrackingExpired(
                session,
                TRANSFER_NOW + TrackingEngine.MAX_TRACKING_DURATION_MILLIS
            )
        )
        // 系统时间往回调（差值为负）：同样判为过期，否则这次追踪永远不会结束
        assertTrue(TrackingEngine.isTrackingExpired(session, TRANSFER_NOW - 60_000L))
        assertTrue(TrackingEngine.isTrackingExpired(session, session.state.startedAtMillis - 1))
    }

    @Test
    fun transfer_isUserFixUsable_requiresFreshAndAccurateFix() {
        val (_, user) = transferLegAtUser()
        assertFalse(TrackingEngine.isUserFixUsable(null, TRANSFER_NOW))
        assertTrue(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second), TRANSFER_NOW))
        // 边界：60 秒、50m 仍可用
        assertTrue(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, accuracy = 50f, ageSeconds = 60), TRANSFER_NOW))
        assertFalse(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, ageSeconds = 61), TRANSFER_NOW))
        assertFalse(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, accuracy = 50.5f), TRANSFER_NOW))
        // 精度未知 / 精度为 0 都不用
        assertFalse(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, accuracy = null), TRANSFER_NOW))
        assertFalse(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, accuracy = 0f), TRANSFER_NOW))
        // 定位时刻在“现在”之后（时基不一致）：不用
        assertFalse(TrackingEngine.isUserFixUsable(transferFix(user.first, user.second, ageSeconds = -1), TRANSFER_NOW))
    }

    @Test
    fun transfer_onBoardPhase_userWgsIsConvertedToGcjBeforeComparingWithDropStop() {
        // 下车站坐标（GCJ-02）= 用户 WGS-84 坐标转 GCJ-02 后再往北 50 m
        val userWgsLat = 30.2740
        val userWgsLng = 120.1550
        val (userGcjLat, userGcjLng) = CoordTransform.wgs84ToGcj02(userWgsLat, userWgsLng)
        val dropGcjLat = userGcjLat + 50 / 111_000.0
        val dropGcjLng = userGcjLng
        val leg = TransitLeg(
            legIndex = 0,
            fromStopName = "A",
            toStopName = "B",
            toStopLat = dropGcjLat,
            toStopLng = dropGcjLng
        )
        // 前提：转换后 < 150 m；若直接拿 WGS 坐标和 GCJ 坐标比则 > 150 m
        val converted = assertNotNull(RouteProjectionMath.calculateDistanceMeters(userGcjLat, userGcjLng, dropGcjLat, dropGcjLng))
        val unconverted = assertNotNull(RouteProjectionMath.calculateDistanceMeters(userWgsLat, userWgsLng, dropGcjLat, dropGcjLng))
        assertTrue(converted < TrackingEngine.DROP_STOP_RADIUS_METERS, "转换后距离 $converted m")
        assertTrue(unconverted > TrackingEngine.DROP_STOP_RADIUS_METERS, "不转换的距离 $unconverted m")

        val onBoard = TrackingSessionState(legPhase = LegPhase.ON_BOARD, stopsRemaining = 2)
        assertTrue(
            TrackingEngine.isArrivalSatisfied(
                state = onBoard,
                leg = leg,
                userFix = transferFix(userWgsLat, userWgsLng),
                nowElapsedMillis = TRANSFER_NOW
            )
        )
    }

    // ================================================================ 引擎对外行为

    private val line1 = CandidateLine(
        cityId = "hz", lineId = "L1", lineName = "156路",
        fromStopOrder = 3, toStopOrder = 8, endStopName = "终点一"
    )
    private val line1b = CandidateLine(
        cityId = "hz", lineId = "L1b", lineName = "156B路",
        fromStopOrder = 4, toStopOrder = 9, endStopName = "终点一B"
    )
    private val line2 = CandidateLine(
        cityId = "hz", lineId = "L2", lineName = "28路",
        fromStopOrder = 2, toStopOrder = 6, endStopName = "终点二"
    )
    private val line2b = CandidateLine(
        cityId = "hz", lineId = "L2b", lineName = "K28路",
        fromStopOrder = 5, toStopOrder = 10, endStopName = "终点二B",
        source = DataSourceType.MYGOLBS, mygolbsCityCode = "0571", direction = 1
    )

    private val leg0 = TransitLeg(
        legIndex = 0,
        fromStopName = "上车站A",
        toStopName = "换乘站B",
        toStopId = "B",
        candidateLines = listOf(line1, line1b)
    )
    private val leg1 = TransitLeg(
        legIndex = 1,
        fromStopName = "换乘站B",
        toStopName = "终点站C",
        toStopId = "C",
        candidateLines = listOf(line2, line2b)
    )

    private val group = TransitGroup(id = "g1", name = "上班", legs = listOf(leg0, leg1), createdAt = 0L, updatedAt = 0L)

    private fun routeTarget(startLeg: Int = 0, selected: Map<Int, String> = emptyMap(), legs: List<TransitLeg> = group.legs) =
        TrackingTarget.Route(group = group.copy(legs = legs), startLeg = startLeg, selectedLineIds = selected)

    private fun singleTarget(targetOrder: Int = 10, threshold: Int = 2) = TrackingTarget.SingleLine(
        cityId = "sh",
        lineId = "L49",
        lineName = "49路",
        endStopName = "终点站",
        targetStopId = "S10",
        targetStopName = "上海体育馆",
        targetOrder = targetOrder,
        totalStops = 20,
        alertThresholdStops = threshold,
        source = DataSourceType.MYGOLBS,
        mygolbsCityCode = "021",
        direction = 1
    )

    private fun beginRoute(startLeg: Int = 0, selected: Map<Int, String> = emptyMap(), generation: Int = 3): TrackingSession =
        assertNotNull(TrackingEngine.begin("s1", generation, routeTarget(startLeg, selected), NOW_MILLIS))

    private fun beginSingle(targetOrder: Int = 10, threshold: Int = 2): TrackingSession =
        assertNotNull(TrackingEngine.begin("s1", 1, singleTarget(targetOrder, threshold), NOW_MILLIS))

    private fun TrackingSession.onBoard(lockedBusId: String): TrackingSession =
        copy(state = state.copy(legPhase = LegPhase.ON_BOARD, lockedBusId = lockedBusId))

    private fun detail(buses: List<LiveBus>, stops: List<BusStop> = emptyList()) = BusLineDetail(
        line = BusLineDirection(lineId = "L", name = "线", startStop = "起点", endStop = "终点"),
        stops = stops,
        buses = buses,
        updatedAtMillis = 0L
    )

    /** 上车站 A（order 3）带 WGS 坐标 */
    private val boardStopWithCoords = BusStop(stopId = "A", name = "上车站A", order = 3, wgsLat = STOP_LAT, wgsLng = STOP_LNG)

    private fun serviceFixNorth(meters: Int, ageMillis: Long = 5_000L, accuracy: Float? = 10f) =
        UserFix(northOfStop(meters), STOP_LNG, accuracy, ELAPSED_NOW - ageMillis)

    private fun poll(
        session: TrackingSession,
        detail: BusLineDetail,
        locationEnabled: Boolean = false,
        serviceFix: UserFix? = null,
        dropFix: UserFix? = null
    ) = TrackingEngine.reducePoll(session, detail, locationEnabled, serviceFix, dropFix, NOW_MILLIS + 1, ELAPSED_NOW)

    // ---------------- begin ----------------

    @Test
    fun engine_beginSingle_buildsSinglePhaseSessionWithLegParams() {
        val session = assertNotNull(TrackingEngine.begin("s1", 5, singleTarget(targetOrder = 10, threshold = 3), NOW_MILLIS))
        assertEquals("s1", session.sessionId)
        assertEquals(5, session.generation)
        assertFalse(session.interrupted)
        assertEquals(
            TrackingLegParams(
                cityId = "sh",
                lineId = "L49",
                lineName = "49路",
                targetStopId = "S10",
                targetOrder = 10,
                source = DataSourceType.MYGOLBS,
                mygolbsCityCode = "021",
                direction = 1
            ),
            session.leg
        )
        val state = session.state
        assertTrue(state.isActive)
        assertEquals(LegPhase.SINGLE, state.legPhase)
        assertEquals(9, state.startTrackingOrder)
        assertEquals(10, state.targetOrder)
        assertEquals(20, state.totalStops)
        assertEquals(3, state.alertThresholdStops)
        assertEquals("上海体育馆", state.targetStopName)
        assertEquals(NOW_MILLIS, state.updatedAtMillis)
        assertNull(state.groupId)
        assertNull(session.toGroupSessionState())
    }

    @Test
    fun engine_beginSingle_startTrackingOrderIsAtLeastOne() {
        assertEquals(1, beginSingle(targetOrder = 1).state.startTrackingOrder)
        assertEquals(1, beginSingle(targetOrder = 2).state.startTrackingOrder)
    }

    @Test
    fun engine_beginRoute_startsWaitingBoardOnFirstCandidateLine() {
        val session = beginRoute(generation = 3)
        assertEquals(3, session.generation)
        val state = session.state
        assertEquals(LegPhase.WAITING_BOARD, state.legPhase)
        assertEquals(0, state.currentLegIndex)
        assertEquals(2, state.totalLegs)
        assertEquals("g1", state.groupId)
        assertEquals("上班", state.groupName)
        assertEquals("L1", state.lineId)
        assertEquals("156路", state.lineName)
        assertEquals(3, state.boardStopOrder)
        assertEquals("上车站A", state.boardStopName)
        assertEquals(3, state.startTrackingOrder)
        assertEquals(8, state.targetOrder)
        assertEquals("换乘站B", state.targetStopName)
        assertEquals("B", state.targetStopId)
        assertEquals(2, state.alertThresholdStops)
        assertEquals(TrackingLegParams(cityId = "hz", lineId = "L1", lineName = "156路", targetStopId = "B", targetOrder = 8), session.leg)
    }

    @Test
    fun engine_route_usesTargetAlertThreshold_onEveryLeg() {
        // 第 25 步：路线每一程都按目标里的到站前提醒（「我的」全局设置），不再写死 2；切到下一程也一样
        val target = routeTarget().withAlertThresholdStops(3)
        val first = assertNotNull(TrackingEngine.begin("s1", 1, target, NOW_MILLIS))
        assertEquals(3, first.state.alertThresholdStops)

        val next = assertNotNull(TrackingEngine.nextLeg(first, NOW_MILLIS + 1))
        assertEquals(3, next.state.alertThresholdStops)

        // 超出 1–3 夹到范围内；旧会话没这个字段时按 2
        assertEquals(1, (routeTarget().withAlertThresholdStops(0) as TrackingTarget.Route).alertThresholdStops)
        assertEquals(2, routeTarget().alertThresholdStops)
        val single = singleTarget().withAlertThresholdStops(9) as TrackingTarget.SingleLine
        assertEquals(3, single.alertThresholdStops)
    }

    @Test
    fun engine_beginRoute_usesSelectedLine_andFallsBackToFirst() {
        val selected = beginRoute(selected = mapOf(0 to "L1b"))
        assertEquals("L1b", selected.leg.lineId)
        assertEquals(4, selected.state.boardStopOrder)
        assertEquals(9, selected.state.targetOrder)

        val missing = beginRoute(selected = mapOf(0 to "nope"))
        assertEquals("L1", missing.leg.lineId)

        // 选中项只对自己那一程生效
        val otherLeg = beginRoute(selected = mapOf(1 to "L1b"))
        assertEquals("L1", otherLeg.leg.lineId)
    }

    @Test
    fun engine_beginRoute_startLegOutOfRange_isCoerced() {
        val tooBig = beginRoute(startLeg = 5)
        assertEquals(1, tooBig.state.currentLegIndex)
        assertEquals("L2", tooBig.leg.lineId)

        val negative = beginRoute(startLeg = -2)
        assertEquals(0, negative.state.currentLegIndex)
        assertEquals("L1", negative.leg.lineId)
    }

    @Test
    fun engine_beginRoute_withoutLegsOrLines_returnsNull() {
        assertNull(TrackingEngine.begin("s1", 1, routeTarget(legs = emptyList()), NOW_MILLIS))
        assertNull(TrackingEngine.begin("s1", 1, routeTarget(legs = listOf(leg0.copy(candidateLines = emptyList()))), NOW_MILLIS))
    }

    // ---------------- reducePoll ----------------

    @Test
    fun engine_reducePollSingle_arrivalEmitsArrivedOnce() {
        val session = beginSingle()
        val atStop = detail(listOf(singleBus("b1", order = 10, arrived = true, progress = 1f)))

        val first = poll(session, atStop)
        assertEquals(1, first.effects.size)
        val alert = assertIs<TrackingEngine.Effect.Alert>(first.effects.single())
        assertEquals(TrackingEngine.AlertKind.ARRIVED, alert.kind)
        assertTrue(alert.state.isBusArrivedAtStop)
        assertEquals(0, alert.state.stopsRemaining)
        assertTrue(first.session.state.hasTriggeredArrivedAlert)
        assertEquals(session.generation, first.session.generation)

        val second = poll(first.session, atStop)
        assertTrue(second.effects.isEmpty())
    }

    @Test
    fun engine_reducePollRoute_lockedBusAtTransferStop_switchesToNextLegWithTransferAlertOnly() {
        val session = beginRoute(generation = 3).onBoard("busX")
        val result = poll(session, detail(listOf(groupBus("busX", order = 8, arrived = true, progress = 1f))))

        assertEquals(listOf<TrackingEngine.Effect>(TrackingEngine.Effect.TransferAlert("换乘站B", "28路")), result.effects)
        val next = result.session
        assertEquals("s1", next.sessionId)
        assertEquals(4, next.generation)
        assertEquals(1, next.state.currentLegIndex)
        assertEquals(LegPhase.WAITING_BOARD, next.state.legPhase)
        assertNull(next.state.lockedBusId)
        assertEquals(2, next.state.boardStopOrder)
        assertEquals("换乘站B", next.state.boardStopName)
        assertEquals("终点站C", next.state.targetStopName)
        assertEquals(TrackingLegParams(cityId = "hz", lineId = "L2", lineName = "28路", targetStopId = "C", targetOrder = 6), next.leg)
    }

    @Test
    fun engine_reducePollRoute_transferUsesSelectedLineOfNextLeg() {
        val session = beginRoute(selected = mapOf(1 to "L2b")).onBoard("busX")
        val result = poll(session, detail(listOf(groupBus("busX", order = 8, arrived = true, progress = 1f))))
        assertEquals(listOf<TrackingEngine.Effect>(TrackingEngine.Effect.TransferAlert("换乘站B", "K28路")), result.effects)
        assertEquals(
            TrackingLegParams(
                cityId = "hz", lineId = "L2b", lineName = "K28路", targetStopId = "C", targetOrder = 10,
                source = DataSourceType.MYGOLBS, mygolbsCityCode = "0571", direction = 1
            ),
            result.session.leg
        )
    }

    @Test
    fun engine_reducePollRoute_lastLegArrival_emitsArrivedThenFinished() {
        val session = beginRoute(startLeg = 1, generation = 7).onBoard("busY")
        val result = poll(session, detail(listOf(groupBus("busY", order = 6, arrived = true, progress = 1f))))

        assertEquals(2, result.effects.size)
        val alert = assertIs<TrackingEngine.Effect.Alert>(result.effects[0])
        assertEquals(TrackingEngine.AlertKind.ARRIVED, alert.kind)
        assertSame(TrackingEngine.Effect.Finished.Completed, result.effects[1])
        // 结束前的最后状态：代际号不变，还是这一程
        assertEquals(7, result.session.generation)
        assertEquals(1, result.session.state.currentLegIndex)
        assertEquals("已顺利到达终点站 终点站C！", result.session.state.statusSummary)
    }

    @Test
    fun engine_reducePollRoute_missedBus_emitsMissedAlertForNextBus() {
        // 车停在上车站（还没到「到站」判定），下一轮数据里它已经不在候车范围
        val atStop = poll(beginRoute(), detail(listOf(groupBus("busX", order = 3, progress = 0.9f, eta = 300))))
        assertEquals(LegPhase.WAITING_BOARD, atStop.session.state.legPhase)
        assertEquals(0, atStop.session.state.stopsRemaining)

        val missed = poll(atStop.session, detail(listOf(groupBus("busY", order = 2, progress = 0.2f, eta = 480))))

        val alert = missed.effects.filterIsInstance<TrackingEngine.Effect.Alert>().single()
        assertEquals(TrackingEngine.AlertKind.MISSED, alert.kind)
        assertEquals("上车站A", alert.state.targetStopName)
        assertEquals(480, alert.state.etaSeconds)
        assertTrue(alert.forceStandalone, "错过提醒必须独立发，藏进胶囊等于没提醒")
        // 换到下一班继续等，不结束追踪
        assertEquals("busY", missed.session.state.currentBusId)
        assertEquals(LegPhase.WAITING_BOARD, missed.session.state.legPhase)
    }

    @Test
    fun engine_reducePollRoute_missedBus_noNextBus_stillReportsMissed() {
        val atStop = poll(beginRoute(), detail(listOf(groupBus("busX", order = 3, progress = 0.9f, eta = 300))))

        val missed = poll(atStop.session, detail(emptyList()))

        val alert = missed.effects.filterIsInstance<TrackingEngine.Effect.Alert>().single()
        assertEquals(TrackingEngine.AlertKind.MISSED, alert.kind)
        assertEquals(-1, alert.state.etaSeconds)
    }

    @Test
    fun engine_reducePollRoute_busVanishesWhileStillApproaching_doesNotClaimMissed() {
        // 车还在上一站（stopsRemaining = 1）就从数据里消失：可能只是数据抽风，不能断言用户错过了
        val approaching = poll(beginRoute(), detail(listOf(groupBus("busX", order = 2, progress = 0.5f, eta = 120))))

        val next = poll(approaching.session, detail(listOf(groupBus("busY", order = 1, progress = 0.5f, eta = 600))))

        assertTrue(
            next.effects.none { it is TrackingEngine.Effect.Alert && it.kind == TrackingEngine.AlertKind.MISSED },
            "车没到站就消失，不该说用户错过了"
        )
    }

    @Test
    fun engine_reducePollRoute_onBoardNotArrived_noTransition() {
        val session = beginRoute().onBoard("busX")
        val result = poll(session, detail(listOf(groupBus("busX", order = 5))))
        assertTrue(result.effects.none { it is TrackingEngine.Effect.TransferAlert || it == TrackingEngine.Effect.Finished.Completed })
        assertEquals(session.generation, result.session.generation)
        assertEquals(0, result.session.state.currentLegIndex)
        assertEquals(LegPhase.ON_BOARD, result.session.state.legPhase)
    }

    @Test
    fun engine_reducePollRoute_waitingBusAtBoardStop_withoutLocation_boardsAndAlertsArrived() {
        val session = beginRoute()
        // 即便上车站有坐标、手上有站边的定位，locationEnabled=false 也不走定位判上车
        val result = poll(
            session,
            detail(listOf(boardingBus("busX", order = 3, metersNorth = 0, arrived = true)), listOf(boardStopWithCoords)),
            locationEnabled = false,
            serviceFix = serviceFixNorth(10)
        )
        assertEquals(LegPhase.ON_BOARD, result.session.state.legPhase)
        assertEquals("busX", result.session.state.lockedBusId)
        val alert = assertIs<TrackingEngine.Effect.Alert>(result.effects.single())
        assertEquals(TrackingEngine.AlertKind.ARRIVED, alert.kind)
        assertEquals("上车站A", alert.state.targetStopName)
        assertEquals(session.generation, result.session.generation)
        // 上车站到站也要独立发通知：胶囊可用只说明权限给了，不说明用户看得见
        assertTrue(alert.forceStandalone)
    }

    @Test
    fun engine_reducePollRoute_arrivedAlerts_alwaysForceStandalone() {
        // 上车站到站：BOARD_ARRIVED
        val boardAlert = assertIs<TrackingEngine.Effect.Alert>(
            poll(beginRoute(), detail(listOf(groupBus("busX", order = 3, arrived = true, progress = 1f))))
                .effects
                .filterIsInstance<TrackingEngine.Effect.Alert>()
                .single()
        )
        assertEquals(TrackingEngine.AlertKind.ARRIVED, boardAlert.kind)
        assertEquals("上车站A", boardAlert.state.targetStopName)
        assertTrue(boardAlert.forceStandalone, "上车站到站是唯一一次机会，必须独立发通知")

        // 下车站到站（最后一程）：DROP_ARRIVED
        val dropAlert = assertIs<TrackingEngine.Effect.Alert>(
            poll(
                beginRoute(startLeg = 1, generation = 7).onBoard("busY"),
                detail(listOf(groupBus("busY", order = 6, arrived = true, progress = 1f)))
            ).effects
                .filterIsInstance<TrackingEngine.Effect.Alert>()
                .single()
        )
        assertEquals(TrackingEngine.AlertKind.ARRIVED, dropAlert.kind)
        assertEquals("终点站C", dropAlert.state.targetStopName)
        assertTrue(dropAlert.forceStandalone, "下车站到站是结束提醒，应强制独立通知")
    }

    @Test
    fun engine_reducePollRoute_waitingBusAtBoardStop_withFreshFixAtStop_entersCandidate() {
        val session = beginRoute()
        val result = poll(
            session,
            detail(listOf(boardingBus("busX", order = 3, metersNorth = 0, arrived = true)), listOf(boardStopWithCoords)),
            locationEnabled = true,
            serviceFix = serviceFixNorth(20)
        )
        assertEquals(LegPhase.WAITING_BOARD, result.session.state.legPhase)
        assertEquals("busX", result.session.state.boardingCandidateBusId)
        assertNull(result.session.state.lockedBusId)
        val alert = assertIs<TrackingEngine.Effect.Alert>(result.effects.single())
        assertEquals(TrackingEngine.AlertKind.ARRIVED, alert.kind)
    }

    @Test
    fun engine_reducePollRoute_boardStopWithoutCoords_fallsBackToBoardingWithoutLocation() {
        val session = beginRoute()
        val result = poll(
            session,
            detail(listOf(boardingBus("busX", order = 3, metersNorth = 0, arrived = true)), listOf(boardStopWithCoords.copy(wgsLat = 0.0, wgsLng = 0.0))),
            locationEnabled = true,
            serviceFix = serviceFixNorth(20)
        )
        assertEquals(LegPhase.ON_BOARD, result.session.state.legPhase)
        assertEquals("busX", result.session.state.lockedBusId)
    }

    @Test
    fun engine_reducePollRoute_staleOrPoorServiceFix_countsAsNoFixButStillUsesLocationFlow() {
        val session = beginRoute()
        val atStop = detail(listOf(boardingBus("busX", order = 3, metersNorth = 0, arrived = true)), listOf(boardStopWithCoords))

        // 对照：新鲜定位离站 2 km -> 不进入待确认，也不算上车
        val farFresh = poll(session, atStop, locationEnabled = true, serviceFix = serviceFixNorth(2000))
        assertNull(farFresh.session.state.boardingCandidateBusId)
        assertEquals(LegPhase.WAITING_BOARD, farFresh.session.state.legPhase)

        // 同一个点但超过 30 秒：等同没定位，不创建待确认
        val farStale = poll(session, atStop, locationEnabled = true, serviceFix = serviceFixNorth(2000, ageMillis = 30_001L))
        assertNull(farStale.session.state.boardingCandidateBusId)
        assertEquals(LegPhase.WAITING_BOARD, farStale.session.state.legPhase)

        // 精度差于 50 m 同理
        val farPoor = poll(session, atStop, locationEnabled = true, serviceFix = serviceFixNorth(2000, accuracy = 80f))
        assertNull(farPoor.session.state.boardingCandidateBusId)
        assertEquals(LegPhase.WAITING_BOARD, farPoor.session.state.legPhase)
    }

    @Test
    fun engine_reducePollRoute_onBoard_dropFixNearDropStop_switchesLeg() {
        // 下车站辅助判定：公交还差 2 站，但 dropFix 已在下车站边上
        val (userGcjLat, userGcjLng) = CoordTransform.wgs84ToGcj02(30.2740, 120.1550)
        val legWithCoords = leg0.copy(toStopLat = userGcjLat, toStopLng = userGcjLng)
        val target = routeTarget(legs = listOf(legWithCoords, leg1))
        val session = assertNotNull(TrackingEngine.begin("s1", 3, target, NOW_MILLIS)).onBoard("busX")
        val dropFix = UserFix(30.2740, 120.1550, 10f, ELAPSED_NOW - 1_000L)

        val result = poll(session, detail(listOf(groupBus("busX", order = 6))), dropFix = dropFix)
        assertTrue(result.effects.contains(TrackingEngine.Effect.TransferAlert("换乘站B", "28路")))
        assertEquals(4, result.session.generation)
        assertEquals(1, result.session.state.currentLegIndex)
    }

    @Test
    fun engine_reduceFailure_onlyChangesStatusSummary() {
        val session = beginRoute().onBoard("busX")
        val failed = TrackingEngine.reduceFailure(session, NOW_MILLIS + 5)
        assertEquals("实时数据刷新失败，正在重试…", failed.state.statusSummary)
        assertEquals(
            session.copy(state = session.state.copy(statusSummary = failed.state.statusSummary, updatedAtMillis = NOW_MILLIS + 5)),
            failed
        )
    }

    // ---------------- 用户操作 ----------------

    @Test
    fun engine_confirmBoarded_onlyInWaitingBoard_picksBusWithinAToAPlusOne() {
        assertNull(TrackingEngine.confirmBoarded(beginSingle(), emptyList(), NOW_MILLIS))
        assertNull(TrackingEngine.confirmBoarded(beginRoute().onBoard("busX"), emptyList(), NOW_MILLIS))

        val waiting = beginRoute()
        val buses = listOf(
            groupBus("next", order = 2, progress = 0.5f),
            groupBus("mine", order = 4, progress = 0.2f).copy(wgsLat = 30.0, wgsLng = 120.0),
            groupBus("farAhead", order = 6)
        )
        val confirmed = assertNotNull(TrackingEngine.confirmBoarded(waiting, buses, NOW_MILLIS + 9))
        assertEquals(LegPhase.ON_BOARD, confirmed.state.legPhase)
        assertEquals("mine", confirmed.state.lockedBusId)
        assertEquals(NOW_MILLIS + 9, confirmed.state.updatedAtMillis)
        assertEquals(waiting.generation, confirmed.generation)
        assertEquals(waiting.leg, confirmed.leg)
    }

    @Test
    fun engine_nextLeg_bumpsGeneration_orReturnsNull() {
        assertNull(TrackingEngine.nextLeg(beginSingle(), NOW_MILLIS))
        assertNull(TrackingEngine.nextLeg(beginRoute(startLeg = 1), NOW_MILLIS))

        val current = beginRoute(generation = 7).onBoard("busX")
        val next = assertNotNull(TrackingEngine.nextLeg(current, NOW_MILLIS + 1))
        assertEquals(8, next.generation)
        assertEquals("s1", next.sessionId)
        assertEquals(1, next.state.currentLegIndex)
        assertEquals(LegPhase.WAITING_BOARD, next.state.legPhase)
        assertNull(next.state.lockedBusId)
        assertEquals("L2", next.leg.lineId)

        val selected = assertNotNull(TrackingEngine.nextLeg(beginRoute(selected = mapOf(1 to "L2b")), NOW_MILLIS))
        assertEquals("L2b", selected.leg.lineId)
    }

    @Test
    fun engine_nextLegLineName() {
        assertEquals("28路", TrackingEngine.nextLegLineName(beginRoute()))
        assertEquals("K28路", TrackingEngine.nextLegLineName(beginRoute(selected = mapOf(1 to "L2b"))))
        assertNull(TrackingEngine.nextLegLineName(beginRoute(startLeg = 1)))
        assertNull(TrackingEngine.nextLegLineName(beginRoute(), legIndex = 1))
        assertNull(TrackingEngine.nextLegLineName(beginSingle()))
    }

    @Test
    fun engine_cycleThreshold_cyclesOneTwoThree() {
        val one = beginSingle(threshold = 1)
        val two = TrackingEngine.cycleThreshold(one, NOW_MILLIS + 1)
        assertEquals(2, two.state.alertThresholdStops)
        assertEquals(NOW_MILLIS + 1, two.state.updatedAtMillis)
        val three = TrackingEngine.cycleThreshold(two, NOW_MILLIS)
        assertEquals(3, three.state.alertThresholdStops)
        val back = TrackingEngine.cycleThreshold(three, NOW_MILLIS)
        assertEquals(1, back.state.alertThresholdStops)
    }

    @Test
    fun engine_cycleThreshold_resetsApproachingFlagOnlyWhenStillFartherThanNewThreshold() {
        val base = beginSingle(threshold = 2)
        val far = base.copy(state = base.state.copy(stopsRemaining = 5, hasTriggeredApproachingAlert = true))
        assertFalse(TrackingEngine.cycleThreshold(far, NOW_MILLIS).state.hasTriggeredApproachingAlert)

        val near = base.copy(state = base.state.copy(stopsRemaining = 2, hasTriggeredApproachingAlert = true))
        assertTrue(TrackingEngine.cycleThreshold(near, NOW_MILLIS).state.hasTriggeredApproachingAlert)

        // 3 -> 1：剩 2 站已比新阈值远，重新允许“即将到站”
        val nearAtThree = near.copy(state = near.state.copy(alertThresholdStops = 3))
        val toOne = TrackingEngine.cycleThreshold(nearAtThree, NOW_MILLIS)
        assertEquals(1, toOne.state.alertThresholdStops)
        assertFalse(toOne.state.hasTriggeredApproachingAlert)
    }

    // ---------------- 定位取舍与档位 ----------------

    private fun fixAt(elapsed: Long, accuracy: Float?) = UserFix(STOP_LAT, STOP_LNG, accuracy, elapsed)

    @Test
    fun engine_shouldAcceptServiceFix_keepsGoodFixAgainstPoorOneFor15s() {
        val good = fixAt(ELAPSED_NOW, 10f)
        assertTrue(TrackingEngine.shouldAcceptServiceFix(null, fixAt(ELAPSED_NOW, 200f)))
        // 差定位在 15 秒内不顶掉好定位
        assertFalse(TrackingEngine.shouldAcceptServiceFix(good, fixAt(ELAPSED_NOW + 15_000L, 80f)))
        assertFalse(TrackingEngine.shouldAcceptServiceFix(good, fixAt(ELAPSED_NOW + 5_000L, null)))
        // 超过 15 秒就顶掉
        assertTrue(TrackingEngine.shouldAcceptServiceFix(good, fixAt(ELAPSED_NOW + 15_001L, 80f)))
        // 好定位总是接受
        assertTrue(TrackingEngine.shouldAcceptServiceFix(good, fixAt(ELAPSED_NOW + 1_000L, 50f)))
        assertTrue(TrackingEngine.shouldAcceptServiceFix(fixAt(ELAPSED_NOW, 200f), fixAt(ELAPSED_NOW + 1_000L, 20f)))
        // 手上的也是差定位：换成新的
        assertTrue(TrackingEngine.shouldAcceptServiceFix(fixAt(ELAPSED_NOW, 200f), fixAt(ELAPSED_NOW + 1_000L, 80f)))
    }

    @Test
    fun engine_usableBoardingFix_30sAnd50mBoundaries() {
        assertNull(TrackingEngine.usableBoardingFix(null, ELAPSED_NOW))
        val edge = fixAt(ELAPSED_NOW - 30_000L, 50f)
        assertSame(edge, TrackingEngine.usableBoardingFix(edge, ELAPSED_NOW))
        assertNull(TrackingEngine.usableBoardingFix(fixAt(ELAPSED_NOW - 30_001L, 10f), ELAPSED_NOW))
        assertNull(TrackingEngine.usableBoardingFix(fixAt(ELAPSED_NOW, 50.5f), ELAPSED_NOW))
        assertNull(TrackingEngine.usableBoardingFix(fixAt(ELAPSED_NOW, null), ELAPSED_NOW))
        // 定位时刻在“现在”之后：不用
        assertNull(TrackingEngine.usableBoardingFix(fixAt(ELAPSED_NOW + 1L, 10f), ELAPSED_NOW))
    }

    @Test
    fun engine_locationTier_isOffWhenLocationDisabled() {
        val waitingNear = beginRoute().state.copy(stopsRemaining = 1)
        assertEquals(LocationCadence.Tier.WAITING_NEAR, TrackingEngine.locationTier(waitingNear, locationEnabled = true))
        assertEquals(LocationCadence.Tier.OFF, TrackingEngine.locationTier(waitingNear, locationEnabled = false))

        val riding = beginRoute().onBoard("busX").state.copy(stopsRemaining = 5)
        assertEquals(LocationCadence.Tier.RIDING, TrackingEngine.locationTier(riding, locationEnabled = true))
        assertEquals(LocationCadence.Tier.OFF, TrackingEngine.locationTier(riding, locationEnabled = false))
    }

    @Test
    fun engine_wantsLocation_routesAndWatchTogetherOnly() {
        assertTrue(TrackingEngine.wantsLocation(routeTarget()))
        assertFalse(TrackingEngine.wantsLocation(singleTarget()))
        // 单线路只有开着「上车后自动结束追踪」才用定位（issue #32）
        assertTrue(TrackingEngine.wantsLocation(singleTarget(), stopAfterBoarding = true))
        // Q17：一起等也用定位判上车
        assertTrue(TrackingEngine.wantsLocation(watchTarget(watchLine("L1", "325"))))
    }

    // ================================================================ 一起等（第 15 步）
    // 上车站「科技园站」在每条线上都是第 5 站，坐标 STOP_LAT/STOP_LNG；线路详情第 n 站在 A 以北 (n-5)×111 米

    private fun watchLine(id: String, name: String, order: Int = 5) =
        WatchLine(lineId = id, lineName = name, stopOrder = order, endStopName = "${name}终点")

    private fun watchTarget(vararg lines: WatchLine) = TrackingTarget.WatchTogether(
        cityId = "sz",
        stopId = "st1",
        stopName = "科技园站",
        lines = lines.toList(),
        stopLat = STOP_LAT,
        stopLng = STOP_LNG
    )

    private fun watchBegin(vararg lines: WatchLine): TrackingSession =
        requireNotNull(TrackingEngine.begin("w1", 1, watchTarget(*lines), NOW_MILLIS))

    private val l325 = watchLine("L1", "325")
    private val lM194 = watchLine("L2", "M194")
    private val l19 = watchLine("L3", "19")

    /** 站点接口给的来车：没有坐标，order 由「还有几站」算出 */
    private fun stopBus(id: String, order: Int, eta: Int = -1, arrived: Boolean = false) =
        LiveBus(busId = id, plateOrFleetNo = id, order = order, etaSeconds = eta, isArrived = arrived)

    /** 线路接口给的车：带坐标（A 以北 [metersNorth] 米） */
    private fun lineBus(id: String, order: Int, metersNorth: Int, progress: Float = 0.5f, arrived: Boolean = false) =
        LiveBus(
            busId = id,
            plateOrFleetNo = id,
            order = order,
            segmentProgress = progress,
            isArrived = arrived,
            wgsLat = northOfStop(metersNorth),
            wgsLng = STOP_LNG
        )

    private fun stopPoll(vararg lines: Pair<String, List<LiveBus>>, requested: Set<String>? = null) = WatchPoll(
        requested = requested ?: lines.map { it.first }.toSet(),
        lines = lines.associate { it.first to LinePoll(buses = it.second) }
    )

    private fun watchStops(count: Int = 12) =
        (1..count).map { BusStop(stopId = "s$it", name = "站$it", order = it, wgsLat = northOfStop((it - 5) * 111), wgsLng = STOP_LNG) }

    private fun linePoll(vararg lines: Pair<String, List<LiveBus>>) = WatchPoll(
        requested = lines.map { it.first }.toSet(),
        lines = lines.associate { it.first to LinePoll(buses = it.second, fromLineDetail = true, stops = watchStops()) }
    )

    private fun watchLineDetail(lineId: String, buses: List<LiveBus>) = BusLineDetail(
        line = BusLineDirection(lineId = lineId, name = lineId, startStop = "站1", endStop = "站12"),
        stops = watchStops(),
        buses = buses
    )

    private fun userFix(metersNorth: Int, accuracy: Float = 10f, elapsedMillis: Long = ELAPSED_NOW) =
        UserFix(northOfStop(metersNorth), STOP_LNG, accuracy, elapsedMillis)

    private fun reduceWatch(
        session: TrackingSession,
        poll: WatchPoll,
        locationEnabled: Boolean = false,
        fix: UserFix? = null,
        now: Long = NOW_MILLIS,
        elapsedMillis: Long = ELAPSED_NOW,
        stopAfterBoarding: Boolean = false
    ) = TrackingEngine.reduceWatchPoll(session, poll, locationEnabled, fix, now, elapsedMillis, stopAfterBoarding)

    @Test
    fun watch_begin_dedupesLinesAndWaitsOnFirst() {
        val session = watchBegin(l325, lM194, l325)
        assertEquals(listOf("L1", "L2"), session.watchedLines.map { it.line.lineId })
        assertEquals(LegPhase.WAITING_BOARD, session.state.legPhase)
        assertEquals("L1", session.leg.lineId)
        assertEquals(5, session.state.boardStopOrder)
        assertEquals("科技园站", session.state.boardStopName)
        assertNull(session.boardChoice)
        // 一条线都没有：不开始
        assertNull(TrackingEngine.begin("w2", 1, watchTarget(), NOW_MILLIS))
    }

    @Test
    fun watch_multiLine_fastestIsPrimary() {
        val session = watchBegin(l325, lM194, l19)
        val result = reduceWatch(
            session,
            stopPoll(
                "L1" to listOf(stopBus("a", order = 1, eta = 600)),
                "L2" to listOf(stopBus("b", order = 3, eta = 240), stopBus("passed", order = 6, eta = -1)),
                "L3" to emptyList()
            )
        )
        val next = result.session
        assertEquals(listOf("L2", "L1", "L3"), next.watchedLines.map { it.line.lineId })
        assertEquals("L2", next.leg.lineId)
        assertEquals("M194", next.state.lineName)
        assertEquals("b", next.state.currentBusId)
        assertEquals(2, next.state.stopsRemaining)
        assertEquals(240, next.state.etaSeconds)
        // 第 3 条线这一轮有数据、只是没车
        val l3 = next.watchedLines.last()
        assertTrue(l3.hasData)
        assertNull(l3.busId)
        assertEquals(LegPhase.WAITING_BOARD, next.state.legPhase)
    }

    @Test
    fun watch_orderChanges_primaryFollows() {
        val first = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L1" to listOf(stopBus("a", order = 2, eta = 500)), "L2" to listOf(stopBus("b", order = 3, eta = 300)))
        ).session
        assertEquals("L2", first.leg.lineId)

        // M194 堵车，325 反超
        val second = reduceWatch(
            first,
            stopPoll("L1" to listOf(stopBus("a", order = 4, eta = 120)), "L2" to listOf(stopBus("b", order = 3, eta = 400)))
        ).session
        assertEquals(listOf("L1", "L2"), second.watchedLines.map { it.line.lineId })
        assertEquals("L1", second.leg.lineId)
        assertEquals("325", second.state.lineName)
        assertEquals("a", second.state.currentBusId)
        assertEquals(1, second.state.stopsRemaining)
    }

    @Test
    fun watch_alerts_approachingOncePerBus_arrivedOnce() {
        val session = watchBegin(l325, lM194)
        // 325 还有 2 站（提醒站数 2）：发一次「快到了」
        val approaching = reduceWatch(session, stopPoll("L1" to listOf(stopBus("a", order = 3, eta = 200)), "L2" to emptyList()))
        val alert = assertIs<TrackingEngine.Effect.Alert>(approaching.effects.single())
        assertEquals(TrackingEngine.AlertKind.APPROACHING, alert.kind)
        assertEquals("325", alert.state.lineName)
        assertEquals("科技园站", alert.state.targetStopName)

        // 同一辆车再近一站：不再提醒
        val again = reduceWatch(approaching.session, stopPoll("L1" to listOf(stopBus("a", order = 4, eta = 100)), "L2" to emptyList()))
        assertTrue(again.effects.isEmpty())

        // 到站：发一次「到站了」；没有定位不自动判上车
        val arrived = reduceWatch(again.session, stopPoll("L1" to listOf(stopBus("a", order = 5, eta = 20)), "L2" to emptyList()))
        assertEquals(TrackingEngine.AlertKind.ARRIVED, assertIs<TrackingEngine.Effect.Alert>(arrived.effects.single()).kind)
        assertEquals(LegPhase.WAITING_BOARD, arrived.session.state.legPhase)
        assertTrue(arrived.session.state.isBusArrivedAtStop)
        val still = reduceWatch(arrived.session, stopPoll("L1" to listOf(stopBus("a", order = 5, eta = 10)), "L2" to emptyList()))
        assertTrue(still.effects.isEmpty())
    }

    @Test
    fun watch_withoutLocation_neverAutoBoards() {
        val session = watchBegin(l325, lM194)
        val arrived = reduceWatch(
            session,
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to listOf(stopBus("b", order = 5, arrived = true)))
        ).session
        assertEquals(LegPhase.WAITING_BOARD, arrived.state.legPhase)
        assertTrue(arrived.watchedLines.all { it.candidateBusId == null })
        assertNull(arrived.boardChoice)
        // 车开走了也不算上车，接着等下一班
        val left = reduceWatch(arrived, stopPoll("L1" to listOf(stopBus("a2", order = 2, eta = 600)), "L2" to emptyList())).session
        assertEquals(LegPhase.WAITING_BOARD, left.state.legPhase)
        assertEquals("a2", left.state.currentBusId)
    }

    @Test
    fun watch_onlyOneBusAtStop_confirmBoardedTakesIt() {
        val waiting = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L1" to listOf(stopBus("a", order = 3, eta = 300)), "L2" to listOf(stopBus("b", order = 5, arrived = true)))
        ).session
        val lineBuses = mapOf(
            "L1" to listOf(stopBus("a", order = 3, eta = 300)),
            "L2" to listOf(stopBus("b", order = 5, arrived = true))
        )
        val boarded = requireNotNull(TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, lineBuses))
        assertNull(boarded.boardChoice)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("L2", boarded.leg.lineId)
        assertEquals("b", boarded.state.lockedBusId)
        // 上车后其他线停止追踪；会话切到这条线，代际号 +1
        assertTrue(boarded.watchedLines.isEmpty())
        assertEquals(waiting.generation + 1, boarded.generation)
        assertNull(TrackingEngine.watchPollPlan(boarded))
    }

    @Test
    fun watch_twoBusesAtStop_locationTellsThemApart_noPopup() {
        val waiting = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to listOf(stopBus("b", order = 5, arrived = true)))
        ).session
        // 线路接口的车有坐标：用户已坐着 b 离站 150 米，a 还停在站台
        val lineBuses = mapOf(
            "L1" to listOf(lineBus("a", order = 5, metersNorth = 0, arrived = true)),
            "L2" to listOf(lineBus("b", order = 6, metersNorth = 150, progress = 0.1f))
        )
        val boarded = requireNotNull(
            TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, lineBuses, userFix(150), ELAPSED_NOW)
        )
        assertNull(boarded.boardChoice)
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("L2", boarded.leg.lineId)
        assertEquals("b", boarded.state.lockedBusId)
    }

    @Test
    fun watch_twoBusesAtStop_ambiguousOrNoLocation_writesBoardChoice() {
        val stopBuses = mapOf(
            "L1" to listOf(stopBus("a", order = 5, arrived = true)),
            "L2" to listOf(stopBus("b", order = 5, eta = 20)),
            "L3" to listOf(stopBus("c", order = 2, eta = 600))
        )
        val waiting = reduceWatch(
            watchBegin(l325, lM194, l19),
            stopPoll(*stopBuses.map { it.key to it.value }.toTypedArray())
        ).session
        // 没有定位
        val noFix = requireNotNull(TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, stopBuses))
        val choice = assertNotNull(noFix.boardChoice)
        assertEquals(setOf("a", "b"), choice.options.map { it.busId }.toSet())
        assertEquals(LegPhase.WAITING_BOARD, noFix.state.legPhase)
        // Q16：等用户选的时候只请求这两条线的线路接口
        val plan = assertIs<WatchPollPlan.Lines>(TrackingEngine.watchPollPlan(noFix))
        assertEquals(setOf("L1", "L2"), plan.lines.map { it.lineId }.toSet())

        // 有定位但两辆都在人身边（站台上并排）：分不清
        val bothNear = mapOf(
            "L1" to listOf(lineBus("a", order = 5, metersNorth = 0, arrived = true)),
            "L2" to listOf(lineBus("b", order = 5, metersNorth = 10, arrived = true))
        )
        val ambiguous = requireNotNull(
            TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, bothNear, userFix(0), ELAPSED_NOW)
        )
        assertEquals(2, assertNotNull(ambiguous.boardChoice).options.size)

        // 用户在「上了哪辆？」里选了 b：上 M194
        val chosen = requireNotNull(TrackingEngine.chooseBoardedBus(noFix, "b", NOW_MILLIS))
        assertEquals(LegPhase.ON_BOARD, chosen.state.legPhase)
        assertEquals("L2", chosen.leg.lineId)
        assertEquals("b", chosen.state.lockedBusId)
        assertNull(chosen.boardChoice)
        // 「还没上」：清掉待选，接着等
        val notYet = requireNotNull(TrackingEngine.chooseBoardedBus(noFix, null, NOW_MILLIS))
        assertNull(notYet.boardChoice)
        assertEquals(LegPhase.WAITING_BOARD, notYet.state.legPhase)
        assertIs<WatchPollPlan.Stop>(TrackingEngine.watchPollPlan(notYet))
        // 选项外的车不认
        assertNull(TrackingEngine.chooseBoardedBus(noFix, "c", NOW_MILLIS))
    }

    @Test
    fun watch_boardChoice_clearedAfterFiveMinutes_orResolvedByLocation() {
        val stopBuses = mapOf(
            "L1" to listOf(stopBus("a", order = 5, arrived = true)),
            "L2" to listOf(stopBus("b", order = 5, arrived = true))
        )
        val waiting = reduceWatch(watchBegin(l325, lM194), stopPoll(*stopBuses.map { it.key to it.value }.toTypedArray())).session
        val choosing = requireNotNull(TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, stopBuses))
        assertNotNull(choosing.boardChoice)

        // 后来定位分清了：用户跟着 a 走了 200 米
        val resolved = reduceWatch(
            choosing,
            linePoll(
                "L1" to listOf(lineBus("a", order = 6, metersNorth = 200, progress = 0.2f)),
                "L2" to listOf(lineBus("b", order = 5, metersNorth = 0, arrived = true))
            ),
            locationEnabled = true,
            fix = userFix(200),
            now = NOW_MILLIS + 20_000
        ).session
        assertEquals(LegPhase.ON_BOARD, resolved.state.legPhase)
        assertEquals("L1", resolved.leg.lineId)
        assertEquals("a", resolved.state.lockedBusId)

        // 一直没选、也分不清：5 分钟后清掉，回到 1 次站点请求
        val timedOut = reduceWatch(
            choosing,
            linePoll(
                "L1" to listOf(lineBus("a", order = 7, metersNorth = 500, progress = 0.2f)),
                "L2" to listOf(lineBus("b", order = 7, metersNorth = 520, progress = 0.3f))
            ),
            now = NOW_MILLIS + 5 * 60_000
        ).session
        assertNull(timedOut.boardChoice)
        assertEquals(LegPhase.WAITING_BOARD, timedOut.state.legPhase)
        assertIs<WatchPollPlan.Stop>(TrackingEngine.watchPollPlan(timedOut))
    }

    @Test
    fun watch_manualBoard_picksNearestBusWithinAAndAPlus1() {
        val waiting = watchBegin(l325, lM194)
        // 325：一辆还差 0.4 站到 A，一辆刚开出 A 0.1 站（同一条线不问，按离 A 最近选）；M194 还远
        val lineBuses = mapOf(
            "L1" to listOf(
                lineBus("coming", order = 5, metersNorth = -40, progress = 0.6f),
                lineBus("left", order = 6, metersNorth = 11, progress = 0.1f),
                lineBus("far", order = 3, metersNorth = -250, progress = 0.5f)
            ),
            "L2" to listOf(lineBus("m", order = 2, metersNorth = -333, progress = 0.5f))
        )
        val boarded = requireNotNull(TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, lineBuses))
        assertNull(boarded.boardChoice)
        assertEquals("L1", boarded.leg.lineId)
        assertEquals("left", boarded.state.lockedBusId)
    }

    @Test
    fun watch_noBusToLock_locksOnLaterPoll_andTargetsTerminal() {
        val waiting = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L1" to listOf(stopBus("a", order = 2, eta = 500)), "L2" to listOf(stopBus("b", order = 4, eta = 90)))
        ).session
        // 一辆在 A..A+1 的车都没有：按排第一的线（M194）进乘车，先不锁车
        val boarded = requireNotNull(TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, emptyMap()))
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("L2", boarded.leg.lineId)
        assertNull(boarded.state.lockedBusId)
        assertEquals(0, boarded.state.targetOrder) // 还不知道终点

        // 下一次线路详情：补上终点站，按 A..A+1 最近补锁
        val next = TrackingEngine.reducePoll(
            boarded,
            watchLineDetail("L2", listOf(lineBus("b", order = 6, metersNorth = 30, progress = 0.2f))),
            locationEnabled = false,
            serviceFix = null,
            dropFix = null,
            nowMillis = NOW_MILLIS,
            nowElapsedMillis = ELAPSED_NOW
        )
        assertEquals("b", next.session.state.lockedBusId)
        assertEquals(12, next.session.state.targetOrder)
        assertEquals("站12", next.session.state.targetStopName)
        assertEquals(6, next.session.state.stopsRemaining)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun watch_lineWithoutData_sortsLastAndKeepsOthers() {
        val result = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L2" to listOf(stopBus("b", order = 3, eta = 300)), requested = setOf("L1", "L2"))
        ).session
        assertEquals(listOf("L2", "L1"), result.watchedLines.map { it.line.lineId })
        val missing = result.watchedLines.last()
        assertFalse(missing.hasData)
        assertNull(missing.busId)
        assertEquals("L2", result.leg.lineId)

        // 全都没数据：会话还在，等下一轮
        val none = reduceWatch(watchBegin(l325, lM194), stopPoll(requested = setOf("L1", "L2"))).session
        assertEquals(LegPhase.WAITING_BOARD, none.state.legPhase)
        assertEquals(-1, none.state.stopsRemaining)
        assertTrue(none.watchedLines.none { it.hasData })
    }

    @Test
    fun watch_tenLines_waitingStillOneStopRequest() {
        val lines = (1..10).map { watchLine("L$it", "${it}路") }
        val session = watchBegin(*lines.toTypedArray())
        // 等车阶段的计划只有 1 次站点请求（站点接口一次带回这个站每条线的来车）
        assertEquals(WatchPollPlan.Stop(lines), TrackingEngine.watchPollPlan(session))
        val polled = reduceWatch(
            session,
            stopPoll(*lines.mapIndexed { i, l -> l.lineId to listOf(stopBus("bus$i", order = 1 + i % 4, eta = 100 + 60 * i)) }.toTypedArray())
        ).session
        assertEquals(10, polled.watchedLines.size)
        assertEquals(WatchPollPlan.Stop(lines), TrackingEngine.watchPollPlan(polled))
        assertEquals("L1", polled.leg.lineId)
    }

    @Test
    fun watch_withLocation_autoBoards_onlyRequestingLinesAtStop() {
        val session = watchBegin(l325, lM194)
        // 325 到站、人在站台：进待确认（Q17），只请求在站这条线的线路接口（Q16）
        val pending = reduceWatch(
            session,
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to listOf(stopBus("b", order = 2, eta = 600))),
            locationEnabled = true,
            fix = userFix(10)
        ).session
        assertEquals("a", pending.watchedLines.first().candidateBusId)
        assertTrue(pending.state.isAwaitingBoardingConfirmation)
        assertEquals(LocationCadence.Tier.BOARDING_CHECK, TrackingEngine.locationTier(pending.state, locationEnabled = true))
        val plan = assertIs<WatchPollPlan.Lines>(TrackingEngine.watchPollPlan(pending))
        assertEquals(listOf("L1"), plan.lines.map { it.lineId })

        // 人跟着车走了两次：上车，会话切到 325，目标是终点站
        val first = reduceWatch(
            pending,
            linePoll("L1" to listOf(lineBus("a", order = 6, metersNorth = 150, progress = 0.3f))),
            locationEnabled = true,
            fix = userFix(150, elapsedMillis = ELAPSED_NOW + 10_000),
            now = NOW_MILLIS + 10_000,
            elapsedMillis = ELAPSED_NOW + 10_000
        ).session
        assertEquals(LegPhase.WAITING_BOARD, first.state.legPhase)
        // 没请求的 M194 保持上次的数据
        assertEquals("b", first.watchedLines.first { it.line.lineId == "L2" }.busId)
        val boarded = reduceWatch(
            first,
            linePoll("L1" to listOf(lineBus("a", order = 6, metersNorth = 320, progress = 0.6f))),
            locationEnabled = true,
            fix = userFix(310, elapsedMillis = ELAPSED_NOW + 20_000),
            now = NOW_MILLIS + 20_000,
            elapsedMillis = ELAPSED_NOW + 20_000
        ).session
        assertEquals(LegPhase.ON_BOARD, boarded.state.legPhase)
        assertEquals("L1", boarded.leg.lineId)
        assertEquals("a", boarded.state.lockedBusId)
        assertEquals(12, boarded.state.targetOrder)
        assertEquals(session.generation + 1, boarded.generation)
    }

    @Test
    fun watch_autoStopAfterBoarding_endsTrackingOnceBoarded() {
        val session = watchBegin(l325, lM194)
        val pending = reduceWatch(
            session,
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to listOf(stopBus("b", order = 2, eta = 600))),
            locationEnabled = true,
            fix = userFix(10),
            stopAfterBoarding = true
        ).session
        assertEquals("a", pending.watchedLines.first().candidateBusId)

        val first = reduceWatch(
            pending,
            linePoll("L1" to listOf(lineBus("a", order = 6, metersNorth = 150, progress = 0.3f))),
            locationEnabled = true,
            fix = userFix(150, elapsedMillis = ELAPSED_NOW + 10_000),
            now = NOW_MILLIS + 10_000,
            elapsedMillis = ELAPSED_NOW + 10_000,
            stopAfterBoarding = true
        )
        assertTrue(first.effects.none { it is TrackingEngine.Effect.Finished })

        // 上了 325：其他线不用再等，这一趟也到此为止
        val done = reduceWatch(
            first.session,
            linePoll("L1" to listOf(lineBus("a", order = 6, metersNorth = 320, progress = 0.6f))),
            locationEnabled = true,
            fix = userFix(310, elapsedMillis = ELAPSED_NOW + 20_000),
            now = NOW_MILLIS + 20_000,
            elapsedMillis = ELAPSED_NOW + 20_000,
            stopAfterBoarding = true
        )
        val finished = done.effects.filterIsInstance<TrackingEngine.Effect.Finished.Boarded>().single()
        assertEquals("325", finished.state.lineName)
        assertEquals("a", finished.state.lockedBusId)
    }

    @Test
    fun watch_userFarFromStop_busArrives_noCandidate() {
        val result = reduceWatch(
            watchBegin(l325, lM194),
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to emptyList()),
            locationEnabled = true,
            fix = userFix(800)
        ).session
        assertTrue(result.watchedLines.all { it.candidateBusId == null })
        assertIs<WatchPollPlan.Stop>(TrackingEngine.watchPollPlan(result))
    }

    @Test
    fun watch_locationOnButNoUsableFix_neverBoardsByTimeout() {
        val session = watchBegin(l325, lM194)
        // 开了定位但没有够新够准的定位：车到站不进待确认，接着按 1 次站点请求等
        val arrived = reduceWatch(
            session,
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to listOf(stopBus("b", order = 2, eta = 600))),
            locationEnabled = true,
            fix = null
        ).session
        assertTrue(arrived.watchedLines.all { it.candidateBusId == null })
        assertIs<WatchPollPlan.Stop>(TrackingEngine.watchPollPlan(arrived))

        // 已在待确认、之后定位没了：不计数；5 分钟后放掉，不按超时判上车
        val pending = reduceWatch(
            session,
            stopPoll("L1" to listOf(stopBus("a", order = 5, arrived = true)), "L2" to emptyList()),
            locationEnabled = true,
            fix = userFix(10)
        ).session
        assertEquals("a", pending.watchedLines.first().candidateBusId)
        val held = reduceWatch(
            pending,
            linePoll("L1" to listOf(lineBus("a", order = 7, metersNorth = 400))),
            locationEnabled = true,
            fix = null,
            now = NOW_MILLIS + 60_000
        ).session
        assertEquals("a", held.watchedLines.first().candidateBusId)
        val released = reduceWatch(
            held,
            linePoll("L1" to listOf(lineBus("a", order = 8, metersNorth = 600))),
            locationEnabled = true,
            fix = null,
            now = NOW_MILLIS + 5 * 60_000
        ).session
        assertEquals(LegPhase.WAITING_BOARD, released.state.legPhase)
        assertTrue(released.watchedLines.all { it.candidateBusId == null })
        assertIs<WatchPollPlan.Stop>(TrackingEngine.watchPollPlan(released))
    }

    @Test
    fun watch_singleLegWithoutDropStop_ridesToTerminalThenFinishes() {
        val waiting = watchBegin(l325)
        val boarded = requireNotNull(
            TrackingEngine.confirmBoarded(waiting, emptyList(), NOW_MILLIS, mapOf("L1" to listOf(stopBus("a", order = 5, arrived = true))))
        )
        // 没有下一程
        assertNull(TrackingEngine.nextLeg(boarded, NOW_MILLIS))
        assertNull(TrackingEngine.nextLegLineName(boarded))

        val riding = TrackingEngine.reducePoll(
            boarded,
            watchLineDetail("L1", listOf(lineBus("a", order = 8, metersNorth = 333))),
            locationEnabled = false,
            serviceFix = null,
            dropFix = null,
            nowMillis = NOW_MILLIS,
            nowElapsedMillis = ELAPSED_NOW
        )
        assertEquals(4, riding.session.state.stopsRemaining)
        assertTrue(riding.effects.none { it is TrackingEngine.Effect.Finished || it is TrackingEngine.Effect.TransferAlert })

        val end = TrackingEngine.reducePoll(
            riding.session,
            watchLineDetail("L1", listOf(lineBus("a", order = 12, metersNorth = 777, arrived = true))),
            locationEnabled = false,
            serviceFix = null,
            dropFix = null,
            nowMillis = NOW_MILLIS,
            nowElapsedMillis = ELAPSED_NOW
        )
        assertTrue(end.effects.any { it is TrackingEngine.Effect.Finished })
        assertTrue(end.effects.none { it is TrackingEngine.Effect.TransferAlert })
    }

    @Test
    fun watch_staleResultsAfterBoarding_areIgnored() {
        val boarded = requireNotNull(
            TrackingEngine.confirmBoarded(
                watchBegin(l325, lM194),
                emptyList(),
                NOW_MILLIS,
                mapOf("L1" to listOf(stopBus("a", order = 5, arrived = true)))
            )
        )
        val stale = reduceWatch(boarded, stopPoll("L1" to listOf(stopBus("x", order = 1, eta = 900)), "L2" to emptyList()))
        assertSame(boarded, stale.session)
        assertTrue(stale.effects.isEmpty())
    }

    // ================================================================ 站点页收藏的一起等路线（第 21 步）

    @Test
    fun watchRoute_roundTrip_keepsLinesAndStop() {
        val target = watchTarget(l325, lM194).copy(cityName = "深圳", stopLat = 0.0, stopLng = 0.0)
        val route = WatchTogetherRoute.toRoute(target)
        assertTrue(WatchTogetherRoute.isWatchRoute(route))
        assertEquals("watch_sz_st1_L1-L2", route.id)
        assertEquals(1, route.legs.size)
        assertEquals("", route.legs.single().toStopName)
        // 候选线没有「这条线上的站 id」字段，读回时用上车站 id 代替（线路接口按它找站）
        val expected = target.copy(lines = target.lines.map { it.copy(stopIdOnLine = "st1") })
        assertEquals(expected, WatchTogetherRoute.watchTargetOf(route))
        // 同一组线顺序不同，id 一样（再存只会更新）
        assertEquals(route.id, WatchTogetherRoute.toRoute(watchTarget(lM194, l325)).id)
    }

    @Test
    fun watchRoute_begin_startsWatchTogether() {
        val route = WatchTogetherRoute.toRoute(watchTarget(l325, lM194, l19))
        val session = assertNotNull(TrackingEngine.begin("s1", 1, TrackingTarget.Route(group = route), NOW_MILLIS))
        assertIs<TrackingTarget.WatchTogether>(session.target)
        assertEquals(3, session.watchedLines.size)
        assertEquals(LegPhase.WAITING_BOARD, session.state.legPhase)
    }

    @Test
    fun routeWithDestination_begin_staysRoute() {
        assertFalse(WatchTogetherRoute.isWatchRoute(group))
        val oneLeg = routeTarget(legs = listOf(leg0))
        val session = assertNotNull(TrackingEngine.begin("s1", 1, oneLeg, NOW_MILLIS))
        assertIs<TrackingTarget.Route>(session.target)
        assertTrue(session.watchedLines.isEmpty())
    }
}
