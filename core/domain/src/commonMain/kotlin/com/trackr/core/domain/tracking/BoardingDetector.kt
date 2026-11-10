package com.trackr.core.domain.tracking

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import kotlin.math.max

/**
 * 定位上车检测（纯函数，不依赖 Android）。
 *
 * 只用于分组换乘，并且只在有“新鲜且精确”的手机定位时启用；没有定位时由 [GroupLegTracker] 保持原有行为
 * （车到上车站 A 即视为上车）。坐标一律用 WGS-84：手机系统定位、车辆 wgsLat/wgsLng、站点 wgsLat/wgsLng。
 *
 * 状态流转（都在 WAITING_BOARD 内部用 [TrackingSessionState.boardingCandidateBusId] 表示“待确认”）：
 * 1. 车到 A：用户离 A ≤ [CANDIDATE_RADIUS_METERS] → 进入待确认；离得更远 → 不算上车，继续等下一班。
 * 2. 待确认 → 上车：连续 [CONFIRM_STREAK] 次满足“人车距离 ≤ max(80m, 2×精度) 且人已离开 A ≥ 100m”，
 *    或“人离 A ≥ 400m 且车也已离开 A”。
 * 3. 待确认 → 没赶上：连续 [MISS_STREAK] 次满足“人仍在 A 120m 内，车已离 A ≥ 300m（无车辆坐标时按站序已过 2 站）”。
 * 4. 待确认超时（[CANDIDATE_TIMEOUT_MILLIS]）仍无结论：车已离开 A 就按已上车处理（与无定位时一致），否则继续等。
 * 5. ON_BOARD 错锁自愈：锁定车离用户 > 500m（或已从数据消失），同时另一辆车离用户 ≤ 80m，连续 [RELOCK_STREAK] 次 → 改锁。
 */
internal object BoardingDetector {

    const val CANDIDATE_RADIUS_METERS = 300
    const val CONFIRM_STREAK = 2
    const val MISS_STREAK = 2
    const val RELOCK_STREAK = 3
    const val CANDIDATE_TIMEOUT_MILLIS = 5 * 60_000L

    private const val NEAR_BUS_MIN_METERS = 80
    private const val NEAR_BUS_MOVING_METERS = 160
    private const val LEFT_BOARD_STOP_METERS = 100
    private const val FAR_FROM_BOARD_STOP_METERS = 400
    private const val STILL_AT_BOARD_STOP_METERS = 120
    private const val BUS_GONE_FROM_BOARD_STOP_METERS = 300
    private const val WRONG_LOCK_METERS = 500

    /** 一个已经通过新鲜度与精度过滤的用户位置 */
    data class UserPosition(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Float,
        /** 定位单调时刻，用于避免同一快照重复计数。 */
        val elapsedMillis: Long = -1L
    )

    /**
     * 一次轮询的定位上下文。
     * @param user 新鲜且精确的用户位置；null 表示本轮没有可用定位（不改变任何连续计数）
     * @param boardStopLat 上车站 A 坐标（WGS-84），无效时为 0
     * @param coarseUser 精度放宽（≤100 m）的用户位置；默认同 [user]。issue #41：街上、车里定位常常只有 60–100 m 精度，
     *   只认 ≤50 m 时车到站那一轮经常拿不到定位，待确认根本建不起来。粗定位只用在容得下误差的判断上：
     *   「人在站附近（300 m 内）」和「人离站已经很远（≥400 m + 精度）」；「贴着车」「人还在站上」仍只认精定位
     */
    data class Input(
        val user: UserPosition?,
        val boardStopLat: Double,
        val boardStopLng: Double,
        val nowMillis: Long,
        val coarseUser: UserPosition? = user
    ) {
        val hasBoardStop: Boolean get() = boardStopLat > 0.0 && boardStopLng > 0.0
    }

    /** 待确认阶段的判定结果 */
    enum class Verdict {
        /** 继续观察 */
        PENDING,
        /** 已上车：锁定待确认车辆进入 ON_BOARD */
        BOARDED,
        /** 没赶上：放弃待确认车辆，回到正常等车 */
        MISSED
    }

    data class CandidateResult(val state: TrackingSessionState, val verdict: Verdict)

    /**
     * 车刚到 A 时，判断是否要进入“待确认”。
     * @return true 进入待确认；false 用户明显不在站附近，不算上车
     */
    fun shouldStartCandidate(input: Input): Boolean {
        // 没有可用定位时不能创建待确认状态。否则候选车离站后的超时
        // 会把用户没有上车误判成已上车。
        val user = input.user ?: input.coarseUser ?: return false
        if (!input.hasBoardStop) return true
        val toStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng) ?: return true
        return toStop <= CANDIDATE_RADIUS_METERS
    }

    /**
     * 车刚从候车站开走（跳过了到站那轮）时，判断是否要补建“待确认”。
     * 除了人在站附近（[shouldStartCandidate]），若人已经跟着这辆车走出站（人已离开站 ≥ [LEFT_BOARD_STOP_METERS]
     * 且就在这辆车旁边），也补建待确认（仍需后续新定位满足连续两次确认才算上车）。
     */
    fun shouldRecoverDepartedCandidate(input: Input, departed: LiveBus): Boolean {
        if (shouldStartCandidate(input)) return true
        return isUserRidingBus(input, departed)
    }

    /** 用户是否已离开候车站且贴着 [bus]（用于在多辆车之间识别用户实际坐上的那辆） */
    fun isUserRidingBus(input: Input, bus: LiveBus): Boolean {
        val user = input.user ?: return false
        if (!input.hasBoardStop) return false
        val toStop = distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng) ?: return false
        val toBus = distance(user.lat, user.lng, bus.wgsLat, bus.wgsLng) ?: return false
        val nearBusLimit = max(NEAR_BUS_MIN_METERS, (user.accuracyMeters * 2).toInt())
        return toStop >= LEFT_BOARD_STOP_METERS && toBus <= nearBusLimit
    }

    /** 用户离上车站 A 的距离（米），算不出时为 null */
    fun userDistanceToBoardStop(input: Input): Int? {
        val user = input.user ?: return null
        if (!input.hasBoardStop) return null
        return distance(user.lat, user.lng, input.boardStopLat, input.boardStopLng)
    }

    /**
     * 待确认阶段的一次评估。
     * @param session 当前会话（[TrackingSessionState.boardingCandidateBusId] 非空）
     * @param candidate 本轮数据中的待确认车辆；从数据里消失时为 null
     */
    fun evaluateCandidate(
        session: TrackingSessionState,
        candidate: LiveBus?,
        input: Input
    ): CandidateResult {
        val boardOrder = session.boardStopOrder
        val user = input.user
        // 没有精定位时退而用粗定位，但只认「人离站已经很远」这一条
        val probe = user ?: input.coarseUser
        val precise = user != null
        var confirm = session.boardingConfirmStreak
        var miss = session.boardingMissStreak

        // 定位服务可能在同一轮回调同一个快照多次。连续计数只消费新定位，
        // 否则一条定位会把“连续两次”误算成两次。
        // 测试/旧调用方可能没有单调时刻（-1）；这种定位无法去重，按新快照处理。
        val isNewFix = probe != null &&
            (probe.elapsedMillis < 0L || probe.elapsedMillis != session.boardingLastFixElapsedMillis)
        if (isNewFix && input.hasBoardStop) {
            val fix = probe!!
            val userToStop = distance(fix.lat, fix.lng, input.boardStopLat, input.boardStopLng)
            val userToBus = candidate?.let { distance(fix.lat, fix.lng, it.wgsLat, it.wgsLng) }
            val busToStop = candidate?.let { distance(it.wgsLat, it.wgsLng, input.boardStopLat, input.boardStopLng) }
            val busLeftStop = candidate == null ||
                candidate.order > boardOrder ||
                (busToStop != null && busToStop >= LEFT_BOARD_STOP_METERS)

            val baseNearLimit = max(NEAR_BUS_MIN_METERS, (fix.accuracyMeters * 2).toInt())
            // 车已离站、人也已离站时，车载 GPS 上报往往比手机实时定位慢 10–15 秒，放宽人车距离上限
            val nearBusLimit = if (busLeftStop && (busToStop == null || busToStop >= LEFT_BOARD_STOP_METERS)) {
                max(NEAR_BUS_MOVING_METERS, baseNearLimit)
            } else {
                baseNearLimit
            }
            // 粗定位离站要再多出一个精度半径，才算真的走远了
            val farLimit = if (precise) FAR_FROM_BOARD_STOP_METERS else FAR_FROM_BOARD_STOP_METERS + fix.accuracyMeters.toInt()
            val boardedSignal = userToStop != null && (
                (precise && userToBus != null && userToBus <= nearBusLimit && userToStop >= LEFT_BOARD_STOP_METERS) ||
                    (userToStop >= farLimit && busLeftStop)
                )
            val missedSignal = precise && userToStop != null && userToStop <= STILL_AT_BOARD_STOP_METERS && (
                candidate == null ||
                    (busToStop != null && busToStop >= BUS_GONE_FROM_BOARD_STOP_METERS) ||
                    (busToStop == null && candidate.order >= boardOrder + 2)
                )

            confirm = when {
                boardedSignal -> confirm + 1
                confirm > 0 && !missedSignal && userToStop != null && userToStop >= LEFT_BOARD_STOP_METERS -> confirm
                else -> 0
            }
            miss = when {
                missedSignal -> miss + 1
                // 粗定位判不了「人还在站上」，不打断没赶上的连续计数
                precise -> 0
                else -> miss
            }
        }

        val updated = session.copy(
            boardingConfirmStreak = confirm,
            boardingMissStreak = miss,
            boardingLastFixElapsedMillis = if (isNewFix) probe!!.elapsedMillis else session.boardingLastFixElapsedMillis
        )
        val verdict = when {
            confirm >= CONFIRM_STREAK -> Verdict.BOARDED
            miss >= MISS_STREAK -> Verdict.MISSED
            input.nowMillis - session.boardingCandidateSinceMillis >= CANDIDATE_TIMEOUT_MILLIS &&
                user != null && (candidate == null || candidate.order > boardOrder) -> Verdict.BOARDED
            input.nowMillis - session.boardingCandidateSinceMillis >= CANDIDATE_TIMEOUT_MILLIS &&
                user == null -> Verdict.MISSED
            else -> Verdict.PENDING
        }
        return CandidateResult(updated, verdict)
    }

    /**
     * ON_BOARD 阶段的错锁自愈。
     * @param locked 本轮数据中的锁定车辆；消失时为 null
     * @return 需要改锁时返回新车辆 busId 与更新后的会话；否则返回 null 与（可能更新了计数的）会话
     */
    fun evaluateRelock(
        session: TrackingSessionState,
        locked: LiveBus?,
        buses: List<LiveBus>,
        input: Input
    ): Pair<String?, TrackingSessionState> {
        val user = input.user ?: return null to session
        if (session.legPhase != LegPhase.ON_BOARD || session.lockedBusId == null) return null to session

        val lockedDistance = locked?.let { distance(user.lat, user.lng, it.wgsLat, it.wgsLng) }
        // 锁定车坐标未知但仍在数据里：无法判断，不动
        if (locked != null && lockedDistance == null) {
            return null to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        val lockedLooksWrong = locked == null || lockedDistance!! > WRONG_LOCK_METERS

        val nearest = buses
            .asSequence()
            .filter { it.busId != session.lockedBusId }
            .filter { it.order >= session.boardStopOrder && it.order <= session.targetOrder }
            .mapNotNull { bus -> distance(user.lat, user.lng, bus.wgsLat, bus.wgsLng)?.let { bus to it } }
            .minByOrNull { it.second }
        val nearLimit = max(NEAR_BUS_MIN_METERS, (user.accuracyMeters * 2).toInt())

        if (!lockedLooksWrong || nearest == null || nearest.second > nearLimit) {
            return null to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        val otherId = nearest.first.busId
        val streak = if (session.relockCandidateBusId == otherId) session.relockStreak + 1 else 1
        if (streak >= RELOCK_STREAK) {
            return otherId to session.copy(relockCandidateBusId = null, relockStreak = 0)
        }
        return null to session.copy(relockCandidateBusId = otherId, relockStreak = streak)
    }

    /** 清空所有待确认 / 改锁相关字段 */
    fun clearBoardingFields(session: TrackingSessionState): TrackingSessionState = session.copy(
        boardingCandidateBusId = null,
        boardingCandidateSinceMillis = 0L,
        boardingConfirmStreak = 0,
        boardingMissStreak = 0,
        boardingLastFixElapsedMillis = -1L,
        boardingLockAttempts = 0,
        relockCandidateBusId = null,
        relockStreak = 0,
        recentDepartedBusId = null,
        recentDepartedAtMillis = 0L
    )

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int? =
        RouteProjectionMath.calculateDistanceMeters(lat1, lng1, lat2, lng2)
}
