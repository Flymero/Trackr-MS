package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.CoordTransform
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitLeg
import com.trackr.core.model.UserFix

/**
 * 追踪状态机（纯函数，重构计划 §2.6）。
 *
 * 合并了原来分散的逻辑：单线选车与提醒（[SingleLegTracker]）、路线两阶段与提醒编排（[GroupLegTracker]）、
 * 定位判上车（[BoardingDetector]）、定位档位（[LocationCadence]）、到站口径（[ArrivalRules]），
 * 以及原 `TransferAutoSwitchManager` 里的下车站判定、自适应轮询间隔、选候选线和切程。
 *
 * 不读时钟：墙钟毫秒（`nowMillis`，写进 `updatedAtMillis`、待确认超时）和单调毫秒（`nowElapsedMillis`，
 * 定位年龄）都由调用方传入。会话怎么存、什么时候轮询、提醒怎么发由调用方（`TrackingRepository`、追踪服务）负责。
 */
object TrackingEngine {

    /** 下车站定位辅助判定：定位最长有效期 */
    const val USER_FIX_MAX_AGE_MILLIS = 60_000L

    /** 下车站定位辅助判定：定位精度上限（米） */
    const val USER_FIX_MAX_ACCURACY_METERS = 50f

    /** 下车站定位辅助判定：距下车站小于该距离（米）视为到达 */
    const val DROP_STOP_RADIUS_METERS = 150

    /** 定位判上车：只用这么新的定位 */
    const val BOARDING_FIX_MAX_AGE_MILLIS = 30_000L

    /** 定位判上车：只用精度不差于这个的定位（米） */
    const val BOARDING_FIX_MAX_ACCURACY_METERS = 50f

    /** 多个定位来源同时回调时，手上的好定位在这段时间内不被差的顶掉 */
    const val KEEP_GOOD_FIX_MILLIS = 15_000L

    /** 失败退避的最长间隔 */
    const val MAX_FAILURE_BACKOFF_MS = 120_000L

    /** 线路非运营（等待发车 / 停运 / 末班已过 / 无实时）时的轮询间隔 */
    const val NOT_RUNNING_POLL_INTERVAL_MS = 300_000L

    /** 单次追踪最长运行时长，避免网络异常时无限轮询。 */
    const val MAX_TRACKING_DURATION_MILLIS = 3 * 60 * 60 * 1000L

    /** 锁定车辆信号丢失期间的最短轮询间隔 */
    const val SIGNAL_LOST_MIN_INTERVAL_MS = 30_000L

    /** 提醒类型：即将到站 / 到站 / 没赶上（车已经开走了） */
    enum class AlertKind { APPROACHING, ARRIVED, MISSED }

    /** 一次 reduce 产出的副作用，调用方按顺序执行 */
    sealed interface Effect {
        /** 发到站 / 临近提醒；[state] 是提醒用的状态（站名、剩余站数已按提醒对象改写） */
        data class Alert(
            val kind: AlertKind,
            val state: TrackingSessionState,
            /**
             * 必须发独立通知，不能因为「实时活动 / 焦点通知 / 胶囊已经在展示同一趟车」就省掉。
             *
             * 到站（上车站、下车站）一律为 true：这一刻只有一次机会，而「胶囊可用」只说明权限给了，
             * 不代表那条胶囊真的升起来了（issue #27 就是权限给了但没上岛）。赌它可见，输了就是错过一班车；
             * 多弹一条通知的代价，比错过车小得多。
             *
             * 临近提醒（还有 N 站）仍可为 false：后面还有到站那一次兜底。
             */
            val forceStandalone: Boolean = false
        ) : Effect

        /** 到换乘站：发换乘提醒（会话已切到下一程，代际号已 +1） */
        data class TransferAlert(val stationName: String, val nextLineName: String) : Effect

        /** 整条路线走完：结束追踪 */
        data object Finished : Effect
    }

    /**
     * reduce 的结果。
     * @param session 新会话；[Effect.Finished] 时是结束前的最后状态，调用方随后把会话清掉
     */
    data class Result(val session: TrackingSession, val effects: List<Effect> = emptyList())

    // ---------------------------------------------------------------- 开始 / 切程

    /**
     * 按目标建一个新会话（单线路：SINGLE 阶段；路线：起始程的 WAITING_BOARD 阶段）。
     * 路线没有程、或起始程没有候选线时返回 null（不开始追踪）。
     */
    fun begin(sessionId: String, generation: Int, target: TrackingTarget, nowMillis: Long): TrackingSession? =
        when (target) {
            is TrackingTarget.SingleLine -> TrackingSession(
                sessionId = sessionId,
                generation = generation,
                target = target,
                leg = TrackingLegParams(
                    cityId = target.cityId,
                    lineId = target.lineId,
                    lineName = target.lineName,
                    targetStopId = target.targetStopId,
                    targetOrder = target.targetOrder,
                    source = target.source,
                    mygolbsCityCode = target.mygolbsCityCode,
                    direction = target.direction
                ),
                state = TrackingSessionState(
                    isActive = true,
                    cityId = target.cityId,
                    lineId = target.lineId,
                    lineName = target.lineName,
                    endStopName = target.endStopName,
                    targetStopId = target.targetStopId,
                    targetStopName = target.targetStopName,
                    targetOrder = target.targetOrder,
                    totalStops = target.totalStops,
                    // 单线路从 (targetOrder-1) 作为起始基准
                    startTrackingOrder = (target.targetOrder - 1).coerceAtLeast(1),
                    alertThresholdStops = target.alertThresholdStops,
                    legPhase = LegPhase.SINGLE,
                    statusSummary = "正在连接实时公交数据…",
                    updatedAtMillis = nowMillis,
                    startedAtMillis = nowMillis
                )
            )

            is TrackingTarget.Route -> {
                val group = target.group
                // 第 21 步：站点页收藏的「1 程、没有下车站」的路线按一起等追（否则上车即到「终点」）
                val watch = WatchTogetherRoute.watchTargetOf(group)
                if (watch != null) {
                    WatchTogetherTracker.begin(sessionId, generation, watch, nowMillis)
                } else if (group.legs.isEmpty()) {
                    null
                } else {
                    val legIndex = target.startLeg.coerceIn(0, group.legs.lastIndex)
                    routeLegSession(sessionId, generation, target, legIndex, nowMillis)
                }
            }

            // 一起等：只有 1 程、没有下车站的临时路线（第 15 步）
            is TrackingTarget.WatchTogether -> WatchTogetherTracker.begin(sessionId, generation, target, nowMillis)
        }

    /** 路线第 [legIndex] 程的初始会话（WAITING_BOARD 起步）；该程没有候选线时返回 null */
    private fun routeLegSession(
        sessionId: String,
        generation: Int,
        target: TrackingTarget.Route,
        legIndex: Int,
        nowMillis: Long
    ): TrackingSession? {
        val group = target.group
        val leg = group.legs.getOrNull(legIndex) ?: return null
        val line = resolveLineForLeg(leg, legIndex, target.selectedLineIds) ?: return null
        val boardOrder = line.fromStopOrder
        val targetOrder = line.toStopOrder
        return TrackingSession(
            sessionId = sessionId,
            generation = generation,
            target = target,
            leg = TrackingLegParams(
                cityId = line.cityId,
                lineId = line.lineId,
                lineName = line.lineName,
                targetStopId = leg.toStopId,
                targetOrder = targetOrder,
                source = line.source,
                mygolbsCityCode = line.mygolbsCityCode.orEmpty(),
                direction = line.direction
            ),
            state = TrackingSessionState(
                isActive = true,
                cityId = line.cityId,
                lineId = line.lineId,
                lineName = line.lineName,
                endStopName = line.endStopName,
                // 目标下车站 B
                targetStopId = leg.toStopId,
                targetStopName = leg.toStopName,
                targetOrder = targetOrder,
                totalStops = targetOrder.coerceAtLeast(boardOrder),
                // 上车站 A 作为 WAITING_BOARD 阶段目标
                startTrackingOrder = if (boardOrder > 0) boardOrder else (targetOrder - 1).coerceAtLeast(1),
                alertThresholdStops = target.alertThresholdStops,
                groupId = group.id,
                groupName = group.name,
                currentLegIndex = legIndex,
                totalLegs = group.legs.size,
                legPhase = LegPhase.WAITING_BOARD,
                boardStopOrder = if (boardOrder > 0) boardOrder else 0,
                boardStopName = leg.fromStopName,
                statusSummary = "正在连接实时公交数据，等待上车站车辆…",
                updatedAtMillis = nowMillis,
                startedAtMillis = nowMillis
            )
        )
    }

    /** 选择某一程要追踪的线路：优先用户选中项，回落首个候选线路 */
    fun resolveLineForLeg(leg: TransitLeg, legIndex: Int, selected: Map<Int, String>): CandidateLine? {
        val chosenId = selected[legIndex]
        return leg.candidateLines.firstOrNull { it.lineId == chosenId } ?: leg.primaryLine
    }

    /**
     * 手动切到下一程（通知「下一程」按钮）：代际号 +1，下一程从等车开始，不发换乘提醒。
     * 不是路线、已是最后一程、或下一程没有候选线时返回 null（什么都不做）。
     */
    fun nextLeg(session: TrackingSession, nowMillis: Long): TrackingSession? {
        val target = session.target as? TrackingTarget.Route ?: return null
        val nextIndex = session.state.currentLegIndex + 1
        if (nextIndex >= target.group.legs.size) return null
        return routeLegSession(session.sessionId, session.generation + 1, target, nextIndex, nowMillis)
    }

    /** 第 [legIndex] 程之后那一程要坐的线路名（通知「下车后换乘 X」）；没有下一程或不是路线时为 null */
    fun nextLegLineName(session: TrackingSession, legIndex: Int = session.state.currentLegIndex): String? {
        val target = session.target as? TrackingTarget.Route ?: return null
        val nextIndex = legIndex + 1
        val nextLeg = target.group.legs.getOrNull(nextIndex) ?: return null
        return resolveLineForLeg(nextLeg, nextIndex, target.selectedLineIds)?.lineName
    }

    /** 这次追踪要不要用定位（判上车、下车站辅助）：路线和一起等要（Q17），单线路不要 */
    fun wantsLocation(target: TrackingTarget): Boolean =
        target is TrackingTarget.Route || target is TrackingTarget.WatchTogether

    // ---------------------------------------------------------------- 一起等（第 15 步）

    /**
     * 一起等这一轮要请求什么；不是一起等、或已上车（上车后按 [TrackingSession.leg] 请求那一条线）时为 null。
     * 等车：1 次站点请求，条数不影响请求数（Q8）；待确认 / 「上了哪辆？」：只请求在站车所在的线（Q16）。
     */
    fun watchPollPlan(session: TrackingSession): WatchPollPlan? = WatchTogetherTracker.plan(session)

    /**
     * 处理一轮一起等的数据（等车阶段）。已上车时原样返回（上车前发出的请求，结果过期了）。
     * 参数同 [reducePoll]；判上车只用 [serviceFix]（≤30 秒、≤50 m）。
     */
    fun reduceWatchPoll(
        session: TrackingSession,
        poll: WatchPoll,
        locationEnabled: Boolean,
        serviceFix: UserFix?,
        nowMillis: Long,
        nowElapsedMillis: Long
    ): Result = WatchTogetherTracker.reduce(
        session = session,
        poll = poll,
        locationEnabled = locationEnabled,
        fix = usableBoardingFix(serviceFix, nowElapsedMillis),
        nowMillis = nowMillis
    )

    /**
     * 一起等「上了哪辆？」用户选了 [busId]（null = 还没上，清掉待选接着等）。
     * 没有待选、或 [busId] 不在选项里时返回 null（什么都不做）。
     */
    fun chooseBoardedBus(session: TrackingSession, busId: String?, nowMillis: Long): TrackingSession? =
        WatchTogetherTracker.chooseBoardedBus(session, busId, nowMillis)

    /**
     * 一起等里去掉一条线（首页那一张卡点「结束」）：只停这一条，其他线接着等。
     * @return 新会话；null 表示这份会话该结束（一条不剩，或去掉的是正在追的那条）
     */
    fun removeWatchLine(session: TrackingSession, lineId: String, nowMillis: Long): TrackingSession? =
        if (session.watchTarget == null) null else WatchTogetherTracker.removeLine(session, lineId, nowMillis)

    // ---------------------------------------------------------------- 轮询

    /**
     * 处理一次成功的轮询。
     *
     * @param detail 当前这一程线路的实时数据
     * @param locationEnabled 这次会话能用定位判上车（前台服务带上了 location 类型，且仍有精确定位权限）
     * @param serviceFix 追踪服务自己拿到的最近一次定位（判上车只用它，≤30 秒、≤50 m）
     * @param dropFix 下车站辅助判定用的最近定位（服务定位和 App 前台定位里最后来的那个）
     * @param nowMillis 墙钟毫秒
     * @param nowElapsedMillis 单调毫秒（和 [UserFix.elapsedMillis] 同一时基）
     */
    fun reducePoll(
        session: TrackingSession,
        detail: BusLineDetail,
        locationEnabled: Boolean,
        serviceFix: UserFix?,
        dropFix: UserFix?,
        nowMillis: Long,
        nowElapsedMillis: Long
    ): Result {
        val previous = session.state
        if (session.watchTarget != null) {
            return reduceWatchOnBoard(session, detail, locationEnabled, serviceFix, nowMillis, nowElapsedMillis)
        }
        if (previous.legPhase == LegPhase.SINGLE) {
            val update = SingleLegTracker.processPoll(
                session = previous,
                buses = detail.buses,
                operatingState = detail.operatingState,
                statusDescription = detail.statusDescription,
                nowMillis = nowMillis
            )
            val effects = buildList {
                val alertState = update.alertState
                if (alertState != null) {
                    when (update.alert) {
                        SingleLegTracker.AlertKind.ARRIVED ->
                            add(Effect.Alert(AlertKind.ARRIVED, alertState, forceStandalone = true))
                        SingleLegTracker.AlertKind.APPROACHING -> add(Effect.Alert(AlertKind.APPROACHING, alertState))
                        null -> Unit
                    }
                }
            }
            return Result(session.copy(state = update.state), effects)
        }

        // 路线：两阶段（等车站 A / 锁定车 -> 下车站 B）
        val boarding = boardingInput(previous, detail, locationEnabled, serviceFix, nowMillis, nowElapsedMillis)
        val update = GroupLegTracker.processPoll(previous, detail.buses, boarding, nowMillis)
        // 提醒编排按“轮询前”的阶段判断：先发上车站到站提醒，再重置标志进入 ON_BOARD
        val decision = GroupLegTracker.planAlerts(previous, update)
        val effects = mutableListOf<Effect>()
        val alertState = decision.alertState
        if (alertState != null) {
            when (decision.alert) {
                // 上车站到站：车就在站台上，这是这一趟唯一一次机会。焦点通知 / 胶囊只能证明权限给了，
                // 证明不了它真的可见，所以照样独立发一条——宁可和胶囊重复，也不要让人错过车
                GroupLegTracker.AlertKind.BOARD_ARRIVED ->
                    effects += Effect.Alert(AlertKind.ARRIVED, alertState, forceStandalone = true)
                // 下车站到站是这一程的结束提醒，必须独立发，避免实时活动清理常驻通知时一并撤掉
                GroupLegTracker.AlertKind.DROP_ARRIVED ->
                    effects += Effect.Alert(AlertKind.ARRIVED, alertState, forceStandalone = true)
                GroupLegTracker.AlertKind.BOARD_APPROACHING,
                GroupLegTracker.AlertKind.DROP_APPROACHING -> effects += Effect.Alert(AlertKind.APPROACHING, alertState)
                null -> Unit
            }
        }
        // 没赶上：定位判定没上，或者提醒过到站后那班车又开走了。补一条「错过了，下一班 X」，
        // 不然用户只看到通知换了辆车，不知道自己已经被落下。同一次轮询里已经有到站提醒时不叠发。
        if (update.missedBoarding && effects.none { it is Effect.Alert && it.kind == AlertKind.ARRIVED }) {
            // 下一班紧接着就要到站：那句「还有 1 站」和「错过了」说的是同一件事，只留后者
            effects.removeAll { it is Effect.Alert && it.kind == AlertKind.APPROACHING }
            effects += Effect.Alert(AlertKind.MISSED, missedAlertState(previous, decision.state), forceStandalone = true)
        }
        val state = decision.state
        val updated = session.copy(state = state)

        // 换乘流转（只有锁定车到下车站、或 ON_BOARD 时定位到了下车站，才切下一程 / 完成）
        val target = session.target as? TrackingTarget.Route ?: return Result(updated, effects)
        val legIndex = state.currentLegIndex
        val currentLeg = target.group.legs.getOrNull(legIndex)
        if (!isArrivalSatisfied(state, currentLeg, dropFix, nowElapsedMillis)) return Result(updated, effects)

        // 定位先于车辆站序判定到达时，GroupLegTracker 不会产出 DROP_ARRIVED。
        // 最后一程结束前补发一次到站提醒，避免用户只看到追踪结束而没有提醒。
        val isLastLeg = legIndex >= target.group.legs.lastIndex
        if (isLastLeg && effects.none { it is Effect.Alert && it.kind == AlertKind.ARRIVED }) {
            effects += Effect.Alert(
                AlertKind.ARRIVED,
                state.copy(stopsRemaining = 0, isBusArrivedAtStop = true),
                forceStandalone = true
            )
        }

        val nextIndex = legIndex + 1
        val nextLeg = target.group.legs.getOrNull(nextIndex)
        val nextLine = nextLeg?.let { resolveLineForLeg(it, nextIndex, target.selectedLineIds) }
        if (nextLeg == null || nextLine == null) {
            // 最后一程到了（或下一程没有可坐的线）：结束
            val finished = updated.copy(
                state = state.copy(statusSummary = "已顺利到达终点站 ${currentLeg?.toStopName.orEmpty()}！")
            )
            return Result(finished, effects + Effect.Finished)
        }
        // 非最后一程只发换乘提醒（提醒内部会先取消到站 / 临近通知），然后直接切到下一程
        effects += Effect.TransferAlert(currentLeg?.toStopName.orEmpty(), nextLine.lineName)
        val next = routeLegSession(session.sessionId, session.generation + 1, target, nextIndex, nowMillis)
            ?: return Result(updated, effects + Effect.Finished)
        return Result(next, effects)
    }

    /**
     * 一起等上车后：只追坐上的那一辆（路线的乘车阶段，改锁规则照用）。没有下车站，目标是终点站；
     * 上车时还不知道终点（手动上车只有站点接口的数据）就用这次线路详情补上。到终点就结束。
     * 还在等车阶段时原样返回（等车阶段走 [reduceWatchPoll]）。
     */
    private fun reduceWatchOnBoard(
        session: TrackingSession,
        detail: BusLineDetail,
        locationEnabled: Boolean,
        serviceFix: UserFix?,
        nowMillis: Long,
        nowElapsedMillis: Long
    ): Result {
        var state = session.state
        if (state.legPhase != LegPhase.ON_BOARD) return Result(session)
        if (state.targetOrder <= state.boardStopOrder) {
            val terminal = detail.stops.maxByOrNull { it.order }?.takeIf { it.order > state.boardStopOrder }
                ?: return Result(
                    session.copy(
                        state = state.copy(statusSummary = "已上车，正在获取线路站点…", updatedAtMillis = nowMillis)
                    )
                )
            state = state.copy(
                targetOrder = terminal.order,
                totalStops = terminal.order,
                targetStopId = terminal.stopId,
                targetStopName = terminal.name
            )
        }
        val boarding = boardingInput(state, detail, locationEnabled, serviceFix, nowMillis, nowElapsedMillis)
        val update = GroupLegTracker.processPoll(state, detail.buses, boarding, nowMillis)
        val decision = GroupLegTracker.planAlerts(state, update)
        val effects = mutableListOf<Effect>()
        val alertState = decision.alertState
        if (alertState != null) {
            when (decision.alert) {
                GroupLegTracker.AlertKind.DROP_ARRIVED ->
                    effects += Effect.Alert(AlertKind.ARRIVED, alertState, forceStandalone = true)
                GroupLegTracker.AlertKind.DROP_APPROACHING -> effects += Effect.Alert(AlertKind.APPROACHING, alertState)
                else -> Unit
            }
        }
        val updated = session.copy(state = decision.state)
        if (!update.reachedDropStop) return Result(updated, effects)
        val finished = updated.copy(
            state = decision.state.copy(statusSummary = "已到终点站 ${decision.state.targetStopName}")
        )
        return Result(finished, effects + Effect.Finished)
    }

    /** 轮询失败：保持状态，只改文案 */
    fun reduceFailure(session: TrackingSession, nowMillis: Long): TrackingSession = session.copy(
        state = session.state.copy(
            statusSummary = "实时数据刷新失败，正在重试…",
            updatedAtMillis = nowMillis
        )
    )

    /**
     * 构建定位上车检测上下文。返回 null 表示本轮不用定位（保持“车到上车站即上车”的原行为）：
     * 不能用定位、或拿不到上车站坐标。
     */
    /**
     * 「错过了」提醒用的状态：站名写回上车站（等车阶段 [TrackingSessionState.targetStopName] 才是上车站），
     * 站数 / ETA 保持下一班的值；下一班还没有数据时是 -1，通知里就只说「暂时没车」。
     */
    private fun missedAlertState(
        previous: TrackingSessionState,
        state: TrackingSessionState
    ): TrackingSessionState = state.copy(
        targetStopName = previous.boardStopName.ifBlank { state.boardStopName }.ifBlank { state.targetStopName },
        isBusArrivedAtStop = false
    )

    private fun boardingInput(
        state: TrackingSessionState,
        detail: BusLineDetail,
        locationEnabled: Boolean,
        serviceFix: UserFix?,
        nowMillis: Long,
        nowElapsedMillis: Long
    ): BoardingDetector.Input? {
        if (!locationEnabled) return null
        val boardStop = detail.stops.firstOrNull { it.order == state.boardStopOrder } ?: return null
        if (boardStop.wgsLat <= 0.0 || boardStop.wgsLng <= 0.0) return null
        val fix = usableBoardingFix(serviceFix, nowElapsedMillis)
        return BoardingDetector.Input(
            user = fix?.let {
                BoardingDetector.UserPosition(it.lat, it.lng, requireNotNull(it.accuracyMeters), it.elapsedMillis)
            },
            boardStopLat = boardStop.wgsLat,
            boardStopLng = boardStop.wgsLng,
            nowMillis = nowMillis
        )
    }

    // ---------------------------------------------------------------- 用户操作

    /**
     * 手动“我已上车”：锁定待确认的车，否则锁 order 在 A..A+1 且离上车站 A 最近的车，切入 ON_BOARD；
     * 锁不到时下一次轮询按同样规则补锁。不在等车阶段时返回 null（什么都不做）。
     *
     * 一起等按 §2.6 的一条规则在所有在等的线里选（见 `WatchTogetherTracker.confirmBoarded`）：分不清时写
     * `boardChoice`、会话仍在等车；选中后会话切到那条线（代际号 +1）。
     *
     * @param lastBuses 最近一次成功轮询拿到的在途车辆
     * @param lineBuses 一起等：最近一次轮询每条线的车（lineId -> 车）
     * @param fix 一起等：最近一次手机定位（判上车按 ≤30 秒、≤50 m 过滤）；拿不到单调时钟时传 null
     */
    fun confirmBoarded(
        session: TrackingSession,
        lastBuses: List<LiveBus>,
        nowMillis: Long,
        lineBuses: Map<String, List<LiveBus>> = emptyMap(),
        fix: UserFix? = null,
        nowElapsedMillis: Long = 0L
    ): TrackingSession? {
        if (session.watchTarget != null) {
            return WatchTogetherTracker.confirmBoarded(session, lineBuses, usableBoardingFix(fix, nowElapsedMillis), nowMillis)
        }
        if (session.state.legPhase != LegPhase.WAITING_BOARD) return null
        return session.copy(state = GroupLegTracker.confirmBoarded(session.state, lastBuses, nowMillis))
    }

    /** 切换提前几站提醒（1 -> 2 -> 3 -> 1） */
    fun cycleThreshold(session: TrackingSession, nowMillis: Long): TrackingSession {
        val state = session.state
        val nextThreshold = when (state.alertThresholdStops) {
            1 -> 2
            2 -> 3
            else -> 1
        }
        val resetApproaching = if (state.stopsRemaining > nextThreshold) false else state.hasTriggeredApproachingAlert
        return session.copy(
            state = state.copy(
                alertThresholdStops = nextThreshold,
                hasTriggeredApproachingAlert = resetApproaching,
                updatedAtMillis = nowMillis
            )
        )
    }

    // ---------------------------------------------------------------- 节奏与判定

    /**
     * 自适应防刷轮询间隔：
     * - 连续失败 n 次：在正常间隔基础上 ×2^n 指数退避，最多 120 秒；成功后（n=0）恢复
     * - 线路非运营：5 分钟一次
     * - 即将到站（<= 1 站 或 ETA <= 180 秒）：10 秒
     * - 中距离（2-4 站 或 ETA <= 600 秒）：20 秒
     * - 远距离（> 4 站 或 暂无实时数据）：45 秒
     * - 锁定车辆信号丢失期间：至少 30 秒
     */
    fun pollingIntervalMillis(
        state: TrackingSessionState,
        consecutiveFailures: Int = 0,
        operatingState: LineOperatingState = LineOperatingState.RUNNING
    ): Long {
        val stopsRemaining = state.stopsRemaining
        val etaSeconds = state.etaSeconds

        val base = when {
            stopsRemaining in 0..1 || (etaSeconds in 1..180) -> 10_000L
            stopsRemaining in 2..4 || (etaSeconds in 181..600) -> 20_000L
            else -> 45_000L
        }

        if (consecutiveFailures > 0) {
            val shift = consecutiveFailures.coerceAtMost(4)
            val backedOff = (base shl shift).coerceAtMost(MAX_FAILURE_BACKOFF_MS)
            return if (operatingState == LineOperatingState.RUNNING) backedOff
            else backedOff.coerceAtLeast(NOT_RUNNING_POLL_INTERVAL_MS)
        }
        if (operatingState != LineOperatingState.RUNNING) return NOT_RUNNING_POLL_INTERVAL_MS
        if (state.isSignalLost) return base.coerceAtLeast(SIGNAL_LOST_MIN_INTERVAL_MS)
        return base
    }

    /**
     * 这次追踪是否该停了。
     *
     * 用墙钟差值判断，所以系统时间被往回调时差值会变成负数：如果只判断「≥ 上限」，
     * 这一次追踪就永远不会过期（轮询一直跑下去）。差值为负就当作用户改了系统时间，
     * 同样判为过期，让服务停下来，避免无限追踪。
     */
    fun isTrackingExpired(session: TrackingSession, nowMillis: Long): Boolean {
        val elapsed = nowMillis - session.state.startedAtMillis
        return elapsed < 0 || elapsed >= MAX_TRACKING_DURATION_MILLIS
    }

    /** 本阶段的定位档位；不能用定位时恒为 OFF */
    fun locationTier(state: TrackingSessionState, locationEnabled: Boolean): LocationCadence.Tier =
        if (locationEnabled) LocationCadence.tierFor(state) else LocationCadence.Tier.OFF

    /** 定位是否足够新且足够准，可用于下车站辅助判定（≤60 秒、≤50 m） */
    fun isUserFixUsable(fix: UserFix?, nowElapsedMillis: Long): Boolean {
        if (fix == null) return false
        val accuracy = fix.accuracyMeters ?: return false
        if (accuracy <= 0f || accuracy > USER_FIX_MAX_ACCURACY_METERS) return false
        val age = nowElapsedMillis - fix.elapsedMillis
        return age in 0..USER_FIX_MAX_AGE_MILLIS
    }

    /** 判上车用的定位：≤30 秒、精度 ≤50 m，否则 null */
    fun usableBoardingFix(fix: UserFix?, nowElapsedMillis: Long): UserFix? {
        if (fix == null) return null
        val accuracy = fix.accuracyMeters ?: return null
        if (accuracy > BOARDING_FIX_MAX_ACCURACY_METERS) return null
        val age = nowElapsedMillis - fix.elapsedMillis
        return fix.takeIf { age in 0..BOARDING_FIX_MAX_AGE_MILLIS }
    }

    /**
     * 服务收到一个新定位时要不要换掉手上的：新定位不够准、而手上的定位仍新（≤15 秒）且准，就保留旧的。
     */
    fun shouldAcceptServiceFix(current: UserFix?, incoming: UserFix): Boolean {
        if (current == null) return true
        val incomingAccuracy = incoming.accuracyMeters
        val newIsPoor = incomingAccuracy == null || incomingAccuracy > BOARDING_FIX_MAX_ACCURACY_METERS
        if (!newIsPoor) return true
        val currentAccuracy = current.accuracyMeters
        val currentIsGood = currentAccuracy != null && currentAccuracy <= BOARDING_FIX_MAX_ACCURACY_METERS
        val currentAgeMillis = incoming.elapsedMillis - current.elapsedMillis
        return !(currentIsGood && currentAgeMillis in 0..KEEP_GOOD_FIX_MILLIS)
    }

    /**
     * ON_BOARD 阶段到达下车站判定（基于阶段 + 锁定车辆）：
     * 1. 公交到达事件（锁定车到下车站：已停靠 / ETA 在 1..45 秒 / 已开过）
     * 2. 手机定位辅助（距下车站 < 150 m）——仅 ON_BOARD、且定位 ≤60 秒、精度 ≤50 m 时有效
     *
     * WAITING_BOARD（和 SINGLE）阶段恒返回 false：等车阶段不能用定位触发切程。
     * 下车站坐标 [TransitLeg.toStopLat] 是 GCJ-02，用户定位是 WGS-84，先把用户坐标转成 GCJ-02 再比。
     */
    fun isArrivalSatisfied(
        state: TrackingSessionState,
        leg: TransitLeg?,
        userFix: UserFix?,
        nowElapsedMillis: Long
    ): Boolean {
        if (state.legPhase == LegPhase.WAITING_BOARD) return false

        val busCondition = ArrivalRules.isSessionArrived(state)

        val locationCondition = if (state.legPhase == LegPhase.ON_BOARD && isUserFixUsable(userFix, nowElapsedMillis)) {
            val fix = requireNotNull(userFix)
            val targetLat = leg?.toStopLat ?: 0.0
            val targetLng = leg?.toStopLng ?: 0.0
            if (targetLat > 0.0 && targetLng > 0.0) {
                val (userGcjLat, userGcjLng) = CoordTransform.wgs84ToGcj02(fix.lat, fix.lng)
                val dist = RouteProjectionMath.calculateDistanceMeters(userGcjLat, userGcjLng, targetLat, targetLng)
                dist != null && dist < DROP_STOP_RADIUS_METERS
            } else {
                false
            }
        } else {
            false
        }

        return busCondition || locationCondition
    }

    /** 路线追踪时的当前程；单线路为 null */
    fun currentLeg(session: TrackingSession): TransitLeg? =
        (session.target as? TrackingTarget.Route)?.group?.legs?.getOrNull(session.state.currentLegIndex)
}
