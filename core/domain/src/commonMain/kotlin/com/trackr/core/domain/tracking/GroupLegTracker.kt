package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import kotlin.math.roundToInt

/**
 * 分组换乘单程两阶段追踪的纯函数核心（选车 + 阶段流转）。
 *
 * 该对象不依赖 Android 环境，便于单元测试：
 * - WAITING_BOARD（等车）：目标是上车站 A（boardStopOrder）。只考虑 order<=A 的车，
 *   取离 A 最近的一辆；该车到 A 后锁定其 busId 并进入 ON_BOARD。
 * - ON_BOARD（已上车）：只跟踪锁定的 busId，目标下车站 B（targetOrder）；
 *   锁定车辆从数据里消失时保持上次状态并标记信号丢失，不会凭车辆数据换成别的车。
 *
 * 有精确定位时（传入 [BoardingDetector.Input]），车到 A 不再直接算上车，而是先进入“待确认”，
 * 由 [BoardingDetector] 根据人车距离判断上车 / 没赶上；ON_BOARD 阶段也可据定位纠正锁错的车。
 */
internal object GroupLegTracker {

    /**
     * 单次轮询结果。
     * @param state 更新后的追踪状态
     * @param reachedBoardStop WAITING_BOARD 阶段锁定车刚到达上车站（本次刚切入 ON_BOARD）
     * @param reachedDropStop ON_BOARD 阶段锁定车到达下车站（应切下一程 / 完成）
     */
    data class LegUpdate(
        val state: TrackingSessionState,
        val reachedBoardStop: Boolean = false,
        val reachedDropStop: Boolean = false,
        /** 定位上车检测：车刚到上车站 A，但还没确认用户上车（应发“车已到站”提醒，阶段仍为 WAITING_BOARD） */
        val busArrivedAtBoard: Boolean = false,
        /** 判定用户没赶上那班车、已改为等下一班：定位确认判定没上，或提醒过到站后那班车又开走了 */
        val missedBoarding: Boolean = false,
        /** 定位上车检测：ON_BOARD 阶段根据定位改锁了所乘车辆 */
        val relocked: Boolean = false
    )

    /** 本轮应发出的提醒类型 */
    enum class AlertKind {
        /** 车即将到上车站 A */
        BOARD_APPROACHING,
        /** 车已到上车站 A */
        BOARD_ARRIVED,
        /** 即将到下车站 B */
        DROP_APPROACHING,
        /** 已到下车站 B（仅最后一程；非最后一程由换乘引擎发换乘提醒） */
        DROP_ARRIVED
    }

    /**
     * 提醒编排结果。
     * @param state 写回会话的状态（已更新提醒触发标志）
     * @param alert 本轮要发的提醒，null 表示不发
     * @param alertState 发提醒时用的状态（站名、剩余站数已按提醒对象改写）
     */
    data class AlertDecision(
        val state: TrackingSessionState,
        val alert: AlertKind? = null,
        val alertState: TrackingSessionState? = null
    )

    /** 到站判定：统一走 [ArrivalRules.isBusArrivedAtOrder]（ETA 1..45s，不信任无效的站间插值） */
    fun isArrivedAtOrder(targetOrder: Int, bus: LiveBus): Boolean =
        ArrivalRules.isBusArrivedAtOrder(targetOrder, bus)

    /**
     * 手动“我已上车”时选择用户所乘车辆：只考虑 order 在 A..A+[maxAhead] 的车，取离 A 最近的一辆；
     * 距离相同时优先已开过 A 的车（用户点按钮时车多半已出站）。
     *
     * 默认 [maxAhead] = 1（刚到 A 或刚离开 A）；ON_BOARD 阶段补锁时第一次仍用 A..A+1，
     * 第二次起放宽到 A..A+[BOARDING_LOCK_MAX_AHEAD_RELAXED]（车已经开过几站也能锁回来）。
     * 找不到时返回 null，由后续轮询再补锁。
     */
    fun pickBoardedBus(boardOrder: Int, buses: List<LiveBus>, maxAhead: Int = 1): LiveBus? {
        if (boardOrder <= 0) return null
        return buses
            .filter { it.stopsTo(boardOrder) in -maxAhead..0 }
            .minWithOrNull(
                compareBy<LiveBus> { distanceToBoardStop(boardOrder, it) }
                    .thenByDescending { it.order }
            )
    }

    /**
     * 车辆到上车站 A 的站间距离（单位：站）。
     * order==A 时车在 (A-1)->A 之间，距离 = 1 - 进度；order==A+1 时车在 A->(A+1) 之间，距离 = 进度。
     * 插值不可信时按站间中点 0.5 估计。
     */
    private fun distanceToBoardStop(boardOrder: Int, bus: LiveBus): Float {
        val progress = when {
            bus.isArrived -> 1f
            bus.hasValidProgress -> bus.segmentProgress.coerceIn(0f, 1f)
            else -> 0.5f
        }
        return if (bus.order == boardOrder) 1f - progress else progress
    }

    /**
     * 手动确认“我已上车”：切入 ON_BOARD，按 [pickBoardedBus] 锁车；锁不到时 lockedBusId 为 null，等后续轮询补锁。
     *
     * @param buses 最近一次轮询拿到的在途车辆
     * @param nowMillis 当前墙钟时刻（毫秒），写进 `updatedAtMillis`
     */
    fun confirmBoarded(session: TrackingSessionState, buses: List<LiveBus>, nowMillis: Long): TrackingSessionState {
        val boardOrder = session.boardStopOrder
        // 定位上车检测正在确认某辆车时，用户手动确认的就是这辆
        val candidateId = session.boardingCandidateBusId
        val picked = candidateId?.let { id -> buses.find { it.busId == id } }
            ?: pickBoardedBus(boardOrder, buses)
        return BoardingDetector.clearBoardingFields(session).copy(
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = picked?.busId ?: candidateId,
            currentBusId = picked?.busId ?: session.currentBusId,
            currentBusOrder = picked?.order ?: session.currentBusOrder,
            currentBusProgress = picked?.segmentProgress ?: session.currentBusProgress,
            stopsRemaining = picked?.stopsTo(session.targetOrder)?.coerceAtLeast(0) ?: session.stopsRemaining,
            etaSeconds = picked?.etaSeconds ?: session.etaSeconds,
            hasAuthoritativeArrival = session.hasAuthoritativeArrival || picked?.hasAuthoritativeArrival == true,
            // 乘车区间从上车站算起
            startTrackingOrder = if (boardOrder > 0) boardOrder else session.startTrackingOrder,
            isBusArrivedAtStop = false,
            isSignalLost = false,
            hasTriggeredApproachingAlert = false,
            hasTriggeredArrivedAlert = false,
            statusSummary = if (picked != null) {
                "已手动确认上车，开始追踪下车站 ${session.targetStopName}"
            } else {
                "已手动确认上车，正在识别所乘车辆…"
            },
            updatedAtMillis = nowMillis
        )
    }

    /**
     * 提醒编排（纯函数）：根据轮询前的阶段决定本轮发什么提醒，并更新触发标志。
     *
     * - 轮询前为 WAITING_BOARD 且本轮车到上车站：先发上车站到站提醒，再重置标志进入 ON_BOARD，
     *   本轮不做下车站临近判定（避免一上车就提示“即将到下车站”）。
     * - ON_BOARD 到下车站：最后一程发到站提醒；非最后一程不发（换乘引擎会发换乘提醒），只置标志。
     *
     * @param previous 本轮轮询前的会话状态
     * @param update [processPoll] 的结果
     */
    fun planAlerts(previous: TrackingSessionState, update: LegUpdate): AlertDecision {
        var state = update.state
        when (previous.legPhase) {
            LegPhase.WAITING_BOARD -> {
                if (update.reachedBoardStop) {
                    val shouldAlert = !previous.hasTriggeredArrivedAlert
                    val alertState = state.copy(
                        targetStopName = previous.boardStopName,
                        stopsRemaining = 0,
                        isBusArrivedAtStop = true
                    )
                    // 刚进入 ON_BOARD，重置下车站提醒触发标志
                    state = state.copy(hasTriggeredApproachingAlert = false, hasTriggeredArrivedAlert = false)
                    return if (shouldAlert) {
                        AlertDecision(state, AlertKind.BOARD_ARRIVED, alertState)
                    } else {
                        AlertDecision(state)
                    }
                }
                if (update.busArrivedAtBoard) {
                    // 定位上车检测：车到站先提醒，阶段仍为等车（等定位确认是否上车）
                    if (state.hasTriggeredArrivedAlert) return AlertDecision(state)
                    val alertState = state.copy(
                        targetStopName = previous.boardStopName,
                        stopsRemaining = 0,
                        isBusArrivedAtStop = true
                    )
                    state = state.copy(hasTriggeredArrivedAlert = true, hasTriggeredApproachingAlert = true)
                    return AlertDecision(state, AlertKind.BOARD_ARRIVED, alertState)
                }
                if (state.stopsRemaining in 0..state.alertThresholdStops && !state.hasTriggeredApproachingAlert) {
                    val alertState = state.copy(targetStopName = previous.boardStopName)
                    state = state.copy(hasTriggeredApproachingAlert = true)
                    return AlertDecision(state, AlertKind.BOARD_APPROACHING, alertState)
                }
                return AlertDecision(state)
            }

            LegPhase.ON_BOARD -> {
                if (update.reachedDropStop) {
                    if (state.hasTriggeredArrivedAlert) return AlertDecision(state)
                    state = state.copy(hasTriggeredArrivedAlert = true, hasTriggeredApproachingAlert = true)
                    if (!isLastLeg(state)) return AlertDecision(state)
                    return AlertDecision(
                        state,
                        AlertKind.DROP_ARRIVED,
                        state.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
                    )
                }
                if (state.lockedBusId != null && !state.isSignalLost &&
                    state.stopsRemaining in 0..state.alertThresholdStops &&
                    !state.hasTriggeredApproachingAlert
                ) {
                    state = state.copy(hasTriggeredApproachingAlert = true)
                    return AlertDecision(state, AlertKind.DROP_APPROACHING, state)
                }
                return AlertDecision(state)
            }

            LegPhase.SINGLE -> Unit
        }
        return AlertDecision(state)
    }

    private fun isLastLeg(state: TrackingSessionState): Boolean =
        state.currentLegIndex >= state.totalLegs - 1

    /**
     * 处理一次分组换乘单程的轮询。
     *
     * @param session 当前会话状态（legPhase 必须为 WAITING_BOARD 或 ON_BOARD）
     * @param buses 本次实时数据中该线路的在途车辆
     * @param boarding 定位上车检测上下文；null 表示定位不可用（没有精确定位权限等），保持“车到 A 即上车”的原行为
     * @param nowMillis 当前墙钟时刻（毫秒），写进 `updatedAtMillis`
     */
    fun processPoll(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        boarding: BoardingDetector.Input? = null,
        nowMillis: Long
    ): LegUpdate {
        return when (session.legPhase) {
            LegPhase.WAITING_BOARD -> processWaitingBoard(session, buses, boarding, nowMillis)
            LegPhase.ON_BOARD -> processOnBoard(session, buses, boarding, nowMillis)
            LegPhase.SINGLE -> LegUpdate(session) // 不应走到这里
        }
    }

    private fun processWaitingBoard(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        boarding: BoardingDetector.Input?,
        nowMillis: Long
    ): LegUpdate {
        val candidateId = session.boardingCandidateBusId
            ?: return selectWaitingBus(session, buses, boarding, nowMillis)

        // 车已到 A、正在确认用户是否上了这辆车
        val candidate = buses.find { it.busId == candidateId }
        if (boarding == null) {
            // 定位中途不可用（如权限被撤销）：退回原行为，按已上车处理
            return boardBus(session, candidate, candidateId, nowMillis)
        }
        val result = BoardingDetector.evaluateCandidate(session, candidate, boarding)
        return when (result.verdict) {
            BoardingDetector.Verdict.BOARDED -> boardBus(result.state, candidate, candidateId, nowMillis)
            BoardingDetector.Verdict.MISSED -> {
                // 没赶上：放弃这辆车，重置提醒标志，改等下一班
                val released = BoardingDetector.clearBoardingFields(result.state).copy(
                    currentBusId = null,
                    hasTriggeredApproachingAlert = false,
                    hasTriggeredArrivedAlert = false
                )
                val next = selectWaitingBus(released, buses.filter { it.busId != candidateId }, boarding, nowMillis)
                next.copy(
                    state = next.state.copy(statusSummary = "似乎没赶上这班车，已改为等下一班 · ${next.state.statusSummary}"),
                    missedBoarding = true
                )
            }
            BoardingDetector.Verdict.PENDING -> LegUpdate(
                result.state.copy(
                    currentBusId = candidateId,
                    currentBusOrder = candidate?.order ?: result.state.currentBusOrder,
                    currentBusProgress = candidate?.segmentProgress ?: result.state.currentBusProgress,
                    hasAuthoritativeArrival = candidate?.hasAuthoritativeArrival ?: result.state.hasAuthoritativeArrival,
                    stopsRemaining = 0,
                    etaSeconds = candidate?.etaSeconds ?: -1,
                    isBusArrivedAtStop = true,
                    isSignalLost = false,
                    statusSummary = "车已到 ${session.boardStopName}，正在确认你是否上车…",
                    updatedAtMillis = nowMillis
                )
            )
        }
    }

    /** 锁定 [busId] 并进入 ON_BOARD（[bus] 为本轮数据中的这辆车，可能为 null） */
    private fun boardBus(session: TrackingSessionState, bus: LiveBus?, busId: String, nowMillis: Long): LegUpdate {
        val order = bus?.order ?: session.currentBusOrder
        val stopsToDrop = (bus?.stopsTo(session.targetOrder) ?: (session.targetOrder - order)).coerceAtLeast(0)
        val locked = BoardingDetector.clearBoardingFields(session).copy(
            hasAuthoritativeArrival = session.hasAuthoritativeArrival || bus?.hasAuthoritativeArrival == true,
            legPhase = LegPhase.ON_BOARD,
            lockedBusId = busId,
            currentBusId = busId,
            currentBusOrder = order,
            currentBusProgress = bus?.segmentProgress ?: session.currentBusProgress,
            startTrackingOrder = session.boardStopOrder,
            stopsRemaining = stopsToDrop,
            etaSeconds = bus?.etaSeconds ?: -1,
            isBusArrivedAtStop = false,
            isSignalLost = false,
            statusSummary = "已上车，距下车站 ${session.targetStopName} 还有 $stopsToDrop 站",
            updatedAtMillis = nowMillis
        )
        return LegUpdate(locked, reachedBoardStop = true)
    }

    /** 正常等车：在未开过 A 的车里选离 A 最近的一辆；车到 A 时按是否有定位决定直接上车还是进入待确认 */
    private fun selectWaitingBus(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        boarding: BoardingDetector.Input?,
        nowMillis: Long
    ): LegUpdate {
        val boardOrder = session.boardStopOrder
        // 只考虑尚未开过上车站 A 的车（order <= A），取离 A 最近的一辆
        val candidates = buses.filter { it.order <= boardOrder }
        val best = candidates.maxByOrNull { it.order.toFloat() + it.segmentProgress }

        // 上一班车已开过 A（或消失）而没有上车：提醒标志属于那班车，换到新车时重置
        val previousBusGone = session.currentBusId != null && candidates.none { it.busId == session.currentBusId }
        // 上一班车已经到了 / 就停在上车站，然后从数据里消失了（开走了，或者数据少给了一辆）：
        // 认定没赶上。只看 previousBusGone 不够——数据抽风少给一辆车时它也是 true，
        // 那时用户可能正站在车门口。所以再要求它上次确实停在站上（或已经提醒过到站）。
        val missedPreviousBus = previousBusGone &&
            (session.stopsRemaining == 0 || session.hasTriggeredArrivedAlert)

        if (best == null) {
            return LegUpdate(
                session.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    statusSummary = "暂无开往上车站的在途车辆，等待下一班",
                    isSignalLost = false,
                    updatedAtMillis = nowMillis
                ),
                missedBoarding = missedPreviousBus
            )
        }

        val base = if (previousBusGone && session.currentBusId != best.busId) {
            session.copy(hasTriggeredApproachingAlert = false, hasTriggeredArrivedAlert = false)
        } else {
            session
        }

        val stopsRemaining = best.stopsTo(boardOrder).coerceAtLeast(0)
        val arrivedBoard = isArrivedAtOrder(boardOrder, best)

        if (arrivedBoard) {
            // 没有可用定位：车到上车站 A 即锁定该车并进入 ON_BOARD（原行为）
            if (boarding == null) return boardBus(base, best, best.busId, nowMillis)

            if (BoardingDetector.shouldStartCandidate(boarding)) {
                return LegUpdate(
                    base.copy(
                        boardingCandidateBusId = best.busId,
                        boardingCandidateSinceMillis = boarding.nowMillis,
                        boardingConfirmStreak = 0,
                        boardingMissStreak = 0,
                        currentBusId = best.busId,
                        currentBusOrder = best.order,
                        currentBusProgress = best.segmentProgress,
                        stopsRemaining = 0,
                        etaSeconds = best.etaSeconds,
                        isBusArrivedAtStop = true,
                        isSignalLost = false,
                        statusSummary = "车已到 ${session.boardStopName}，正在确认你是否上车…",
                        updatedAtMillis = nowMillis
                    ),
                    busArrivedAtBoard = true
                )
            }
            // 用户明显不在站附近：只提醒车到了，不算上车
            val away = BoardingDetector.userDistanceToBoardStop(boarding)
            val awayText = away?.let { "你离站约 ${RouteProjectionMath.formatDistanceText(it)}，" }.orEmpty()
            return LegUpdate(
                base.copy(
                    currentBusId = best.busId,
                    currentBusOrder = best.order,
                    currentBusProgress = best.segmentProgress,
                    stopsRemaining = 0,
                    etaSeconds = best.etaSeconds,
                    isBusArrivedAtStop = true,
                    isSignalLost = false,
                    statusSummary = "车已到 ${session.boardStopName}，${awayText}赶不上可等下一班",
                    updatedAtMillis = nowMillis
                ),
                busArrivedAtBoard = true
            )
        }

        val etaText = formatEtaSeconds(best.etaSeconds)
        val summary = if (etaText.isNotEmpty()) {
            "${session.lineName} 距上车站 ${session.boardStopName} 还有 $stopsRemaining 站 · $etaText"
        } else {
            "${session.lineName} 距上车站 ${session.boardStopName} 还有 $stopsRemaining 站"
        }
        // 等车区间起点：选中这辆车时它所在的站（只供通知进度条使用，换车时重新记录）
        val waitStartOrder = if (session.currentBusId != best.busId) {
            (best.order - 1).coerceIn(1, (boardOrder - 1).coerceAtLeast(1))
        } else {
            session.startTrackingOrder
        }
        return LegUpdate(
            base.copy(
                startTrackingOrder = waitStartOrder,
                currentBusId = best.busId,
                currentBusOrder = best.order,
                currentBusProgress = best.segmentProgress,
                // 换车后权威性跟新车走：否则 TCP 车丢了 HTTP 车接上（或反之）时，
                // 到站判定与通知各用一套口径，通知会提前报「到站了」
                hasAuthoritativeArrival = best.hasAuthoritativeArrival,
                stopsRemaining = stopsRemaining,
                etaSeconds = best.etaSeconds,
                isBusArrivedAtStop = false,
                isSignalLost = false,
                statusSummary = summary,
                updatedAtMillis = nowMillis
            ),
            // 刚错过上一班、已经换到下一班：让引擎补一条「错过了，下一班 X」，
            // 否则用户只看到通知悄悄换了辆车，不知道自己已经错过
            missedBoarding = missedPreviousBus
        )
    }

    private fun processOnBoard(
        incoming: TrackingSessionState,
        buses: List<LiveBus>,
        boarding: BoardingDetector.Input?,
        nowMillis: Long
    ): LegUpdate {
        if (incoming.lockedBusId == null) {
            // 手动确认上车时没锁到车：第一次严格按 A..A+1 补查；还没拿到车就放宽到 A+4，
            // 覆盖用户点按钮后数据已经跳过几站的情况。之后一直用放宽范围，直到锁到车为止。
            val maxAhead = if (incoming.boardingLockAttempts == 0) BOARDING_LOCK_MAX_AHEAD
            else BOARDING_LOCK_MAX_AHEAD_RELAXED
            val picked = pickBoardedBus(incoming.boardStopOrder, buses, maxAhead)
                ?: return LegUpdate(
                    incoming.copy(
                        isSignalLost = false,
                        // 只涨到上限：放宽后一直锁不到也不能无限增长（本轮末仍是「识别中」，等下一轮）
                        boardingLockAttempts = (incoming.boardingLockAttempts + 1)
                            .coerceAtMost(BOARDING_LOCK_MAX_ATTEMPTS),
                        statusSummary = "已确认上车，正在识别所乘车辆…",
                        updatedAtMillis = nowMillis
                    )
                )
            return processOnBoard(incoming.copy(lockedBusId = picked.busId, boardingLockAttempts = 0), buses, boarding, nowMillis)
        }

        val lockedId = incoming.lockedBusId
        val locked = buses.find { it.busId == lockedId }

        // 定位上车检测：锁定车离用户很远、另一辆车就在用户身边，连续多次后改锁
        var session = incoming
        if (boarding != null) {
            val (relockId, updated) = BoardingDetector.evaluateRelock(incoming, locked, buses, boarding)
            session = updated
            if (relockId != null) {
                val relocked = processOnBoard(session.copy(lockedBusId = relockId), buses, null, nowMillis)
                return relocked.copy(
                    state = relocked.state.copy(statusSummary = "已根据定位更正所乘车辆 · ${relocked.state.statusSummary}"),
                    relocked = true
                )
            }
        }

        if (locked == null) {
            // 锁定车辆从数据里消失：保持上次状态，提示信号丢失，绝不换车
            return LegUpdate(
                session.copy(
                    isSignalLost = true,
                    statusSummary = "锁定车辆信号丢失，保持上次位置（距 ${session.targetStopName}）",
                    updatedAtMillis = nowMillis
                )
            )
        }

        val targetOrder = session.targetOrder
        val stopsRemaining = locked.stopsTo(targetOrder).coerceAtLeast(0)
        val arrivedDrop = isArrivedAtOrder(targetOrder, locked)

        val etaText = formatEtaSeconds(locked.etaSeconds)
        val summary = when {
            arrivedDrop -> "已到达下车站 ${session.targetStopName}"
            etaText.isNotEmpty() -> "距下车站 ${session.targetStopName} 还有 $stopsRemaining 站 · $etaText"
            else -> "距下车站 ${session.targetStopName} 还有 $stopsRemaining 站"
        }

        return LegUpdate(
            session.copy(
                currentBusId = locked.busId,
                currentBusOrder = locked.order,
                currentBusProgress = locked.segmentProgress,
                stopsRemaining = stopsRemaining,
                etaSeconds = locked.etaSeconds,
                isBusArrivedAtStop = arrivedDrop,
                isSignalLost = false,
                statusSummary = summary,
                updatedAtMillis = nowMillis
            ),
            reachedDropStop = arrivedDrop
        )
    }

    private fun formatEtaSeconds(etaSeconds: Int): String {
        if (etaSeconds <= 0) return ""
        // roundToInt 和原来的 Math.round 一样：.5 向正无穷进位
        val minutes = (etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
        return "约 $minutes 分钟"
    }

    /** 手动确认上车后第一次补锁的范围：A..A+1（刚到 A 或刚离开 A） */
    private const val BOARDING_LOCK_MAX_AHEAD = 1

    /** 补锁第二次起放宽的范围：A..A+4，覆盖用户点按钮后数据已经跳过几站的情况 */
    internal const val BOARDING_LOCK_MAX_AHEAD_RELAXED = 4

    /** `boardingLockAttempts` 的上限：放宽后一直锁不到车时不再往上加，避免无限增长 */
    internal const val BOARDING_LOCK_MAX_ATTEMPTS = 10
}
