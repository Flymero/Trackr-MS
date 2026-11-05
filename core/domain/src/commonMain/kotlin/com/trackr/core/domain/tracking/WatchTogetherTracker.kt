package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.BusStop
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.UserFix
import com.trackr.core.model.WatchLine
import com.trackr.core.model.WatchedLine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一起等这一轮要请求什么（[TrackingEngine.watchPollPlan]）。
 * 等车阶段条数不影响请求数（Q8）；车到站、正在确认上了哪辆时只请求在站车所在的线（Q16）。
 */
sealed interface WatchPollPlan {

    /** 等车：请求 1 次上车站的站点接口；站点结果里缺的线才逐条请求线路接口。[lines] 是全部在等的线 */
    data class Stop(val lines: List<WatchLine>) : WatchPollPlan

    /** 待确认 / 「上了哪辆？」：只请求这几条线的线路接口（车开走后站点接口就不给它的位置了） */
    data class Lines(val lines: List<WatchLine>) : WatchPollPlan
}

/**
 * 一条线这一轮的数据。
 * @param buses 这条线的车，`order` 已换算成这条线上的站序
 * @param fromLineDetail true = 来自线路接口（车辆有坐标、[stops] 有全线站点）；false = 来自站点接口（只有来车）
 * @param statusText 站点接口给的状态文字（如「等待发车」）
 */
data class LinePoll(
    val buses: List<LiveBus>,
    val fromLineDetail: Boolean = false,
    val statusText: String = "",
    val stops: List<BusStop> = emptyList()
)

/**
 * 一起等一轮的结果（`WatchTogetherPoller` 产出）。
 * @param requested 这一轮请求了哪些线（lineId）；请求了却不在 [lines] 里 = 这条线没数据
 * @param stopLat 站点接口给的上车站坐标（WGS-84），没有为 0
 */
data class WatchPoll(
    val requested: Set<String>,
    val lines: Map<String, LinePoll>,
    val stopLat: Double = 0.0,
    val stopLng: Double = 0.0
)

/**
 * 「我上车了」和「上了哪辆？」的一条规则（§2.6），在不同线的车之间分辨：
 * 同时有 2 辆以上不同线的车符合时，只有一辆离人 ≤ max(80 m, 2×精度) 就是它；否则分不清。
 * 同一条线的车不在这里比：同线同终点，坐错了由乘车中的改锁纠正（路线一程只追一条线，规则就退化成原来的选最近）。
 */
internal object BoardingRule {

    private const val NEAR_BUS_MIN_METERS = 80

    /** 一个候选：哪条线的哪辆车（[bus] 可能是 null：只知道 id，这一轮数据里没有它） */
    data class Option(val line: WatchLine, val busId: String, val bus: LiveBus?)

    /** 定位分得清时返回那一个，否则 null（没有定位、车没有坐标、离人近的不止一辆或一辆都没有） */
    fun distinguish(options: List<Option>, fix: UserFix?): Option? {
        if (fix == null || options.size < 2) return null
        val accuracy = fix.accuracyMeters ?: return null
        val limit = max(NEAR_BUS_MIN_METERS, (accuracy * 2).toInt())
        val near = options.filter { option ->
            val bus = option.bus ?: return@filter false
            if (bus.wgsLat == 0.0 || bus.wgsLng == 0.0) return@filter false
            val d = RouteProjectionMath.calculateDistanceMeters(fix.lat, fix.lng, bus.wgsLat, bus.wgsLng)
            d != null && d <= limit
        }
        return near.singleOrNull()
    }
}

/**
 * 一起等（[TrackingTarget.WatchTogether]）等车阶段的纯函数核心。上车后会话切到坐上的那条线，
 * 走路线的乘车阶段（[GroupLegTracker]），只追那一辆。
 *
 * 等车时每条线各自选「离上车站最近、还没开过」的车（和路线等车同一口径），按到站先后排，排第一的写进
 * `session.leg` / `session.state`（通知、首页卡、轮询间隔、定位档位都读它）。
 *
 * 判上车（Q17）：有定位时车到站、人在站附近，这条线进「待确认」，每条线自己用 [BoardingDetector] 计数；
 * 只有一条判成上车就上那辆，两条以上看定位分不分得清（[BoardingRule]），分不清写 `boardChoice`。
 * 没有定位不自动判上车（多条线会认错），只提醒到站，等用户点「我上车了」。
 */
internal object WatchTogetherTracker {

    /** 「上了哪辆？」没人选，过这么久清掉、回到等车（Q16：待确认期间最长 5 分钟） */
    const val BOARD_CHOICE_TIMEOUT_MILLIS = BoardingDetector.CANDIDATE_TIMEOUT_MILLIS

    /** 没有 ETA 的车按每站这么多秒估，用来和有 ETA 的线排先后 */
    private const val SECONDS_PER_STOP_GUESS = 120

    // ---------------------------------------------------------------- 开始

    fun begin(sessionId: String, generation: Int, target: TrackingTarget.WatchTogether, nowMillis: Long): TrackingSession? {
        val lines = target.lines
            .filter { it.lineId.isNotBlank() && it.stopOrder > 0 }
            .distinctBy { it.lineId }
        val first = lines.firstOrNull() ?: return null
        val normalized = target.copy(lines = lines)
        return TrackingSession(
            sessionId = sessionId,
            generation = generation,
            target = normalized,
            leg = legOf(normalized, first),
            state = TrackingSessionState(
                isActive = true,
                cityId = target.cityId,
                lineId = first.lineId,
                lineName = first.lineName,
                endStopName = first.endStopName,
                targetStopId = target.stopId,
                targetStopName = target.stopName,
                targetOrder = first.stopOrder,
                totalStops = first.stopOrder,
                startTrackingOrder = (first.stopOrder - 1).coerceAtLeast(1),
                alertThresholdStops = target.alertThresholdStops,
                legPhase = LegPhase.WAITING_BOARD,
                boardStopOrder = first.stopOrder,
                boardStopName = target.stopName,
                totalLegs = 1,
                statusSummary = "正在连接实时公交数据，一起等 ${lines.size} 条线…",
                updatedAtMillis = nowMillis,
                startedAtMillis = nowMillis
            ),
            watchedLines = lines.map { WatchedLine(line = it) }
        )
    }

    private fun legOf(target: TrackingTarget.WatchTogether, line: WatchLine, targetOrder: Int = line.stopOrder) =
        TrackingLegParams(
            cityId = target.cityId,
            lineId = line.lineId,
            lineName = line.lineName,
            targetStopId = line.stopIdOnLine.ifBlank { target.stopId },
            targetOrder = targetOrder,
            source = line.source,
            mygolbsCityCode = target.mygolbsCityCode,
            direction = line.direction
        )

    // ---------------------------------------------------------------- 请求计划

    fun plan(session: TrackingSession): WatchPollPlan? {
        val target = session.watchTarget ?: return null
        if (session.state.legPhase != LegPhase.WAITING_BOARD) return null
        val focus: Set<String> = session.boardChoice?.options?.map { it.lineId }?.toSet()
            ?: session.watchedLines.filter { it.candidateBusId != null }.map { it.line.lineId }.toSet()
        if (focus.isNotEmpty()) {
            val lines = target.lines.filter { it.lineId in focus }
            if (lines.isNotEmpty()) return WatchPollPlan.Lines(lines)
        }
        return WatchPollPlan.Stop(target.lines)
    }

    // ---------------------------------------------------------------- 轮询

    /**
     * 处理一轮一起等的数据。不在等车阶段（已上车）时原样返回：那是上车前发出去的请求，结果已过期。
     *
     * @param locationEnabled 这次会话能用定位判上车
     * @param fix 判上车用的定位，已按 ≤30 秒、≤50 m 过滤（[TrackingEngine.usableBoardingFix]），没有为 null
     */
    fun reduce(
        session: TrackingSession,
        poll: WatchPoll,
        locationEnabled: Boolean,
        fix: UserFix?,
        nowMillis: Long
    ): TrackingEngine.Result {
        val target = session.watchTarget ?: return TrackingEngine.Result(session)
        if (session.state.legPhase != LegPhase.WAITING_BOARD) return TrackingEngine.Result(session)

        val (stopLat, stopLng) = boardStopCoords(target, poll, session)
        val input = if (locationEnabled && stopLat > 0.0 && stopLng > 0.0) {
            BoardingDetector.Input(
                user = fix?.let {
                    BoardingDetector.UserPosition(it.lat, it.lng, requireNotNull(it.accuracyMeters), it.elapsedMillis)
                },
                boardStopLat = stopLat,
                boardStopLng = stopLng,
                nowMillis = nowMillis
            )
        } else {
            null
        }

        val boarded = mutableListOf<BoardingRule.Option>()
        // 「上了哪辆？」等用户选的时候不再开始新的待确认（否则同一批车会再判一次、待选被重写）
        val allowNewCandidates = session.boardChoice == null
        var lines = session.watchedLines.map { wl ->
            val lineId = wl.line.lineId
            if (lineId !in poll.requested) return@map wl // 待确认期间没请求它：保持上次
            val lp = poll.lines[lineId] ?: return@map noData(wl)
            updateLine(wl, lp, input, allowNewCandidates, boarded)
        }

        // 「上了哪辆？」：超时清掉；所有选项的车都不见了也清掉；定位分得清了就直接上那辆
        var choice = session.boardChoice
        var choicePick: BoardingRule.Option? = null
        if (choice != null) {
            val options = choice.options.mapNotNull { option ->
                val line = target.lines.firstOrNull { it.lineId == option.lineId } ?: return@mapNotNull null
                val lp = poll.lines[option.lineId]
                val bus = lp?.buses?.firstOrNull { it.busId == option.busId }
                if (option.lineId in poll.requested && lp != null && bus == null) return@mapNotNull null
                BoardingRule.Option(line, option.busId, bus)
            }
            choicePick = BoardingRule.distinguish(options, fix)
            if (nowMillis - choice.sinceMillis >= BOARD_CHOICE_TIMEOUT_MILLIS || options.isEmpty()) choice = null
        }

        lines = sortByArrival(lines)

        // 提醒：本轮新到站的线里排最前的那条发一次「到站了」；否则排第一的那条进了提醒站数发一次「快到了」
        val effects = mutableListOf<TrackingEngine.Effect>()
        val threshold = session.state.alertThresholdStops
        val newlyArrived = lines.filter { it.isArrived && it.busId != null && it.arrivedAlertedBusId != it.busId }
        if (newlyArrived.isNotEmpty()) {
            val first = newlyArrived.first()
            effects += TrackingEngine.Effect.Alert(TrackingEngine.AlertKind.ARRIVED, alertState(session.state, target, first))
            val ids = newlyArrived.map { it.line.lineId }.toSet()
            lines = lines.map { wl ->
                if (wl.line.lineId in ids) wl.copy(arrivedAlertedBusId = wl.busId, approachAlertedBusId = wl.busId) else wl
            }
        } else {
            val primary = lines.firstOrNull()
            if (primary != null && primary.busId != null && !primary.isArrived &&
                primary.stopsRemaining in 0..threshold && primary.approachAlertedBusId != primary.busId
            ) {
                effects += TrackingEngine.Effect.Alert(TrackingEngine.AlertKind.APPROACHING, alertState(session.state, target, primary))
                lines = listOf(primary.copy(approachAlertedBusId = primary.busId)) + lines.drop(1)
            }
        }

        // 判上车：定位判出来的、或「上了哪辆？」被定位分清了的
        val pick = when {
            boarded.size == 1 -> boarded.single()
            boarded.size >= 2 -> BoardingRule.distinguish(boarded, fix)
            else -> choicePick
        }
        if (pick != null) {
            val lp = poll.lines[pick.line.lineId]
            val boardedSession = board(session, target, pick, lp?.stops.orEmpty(), nowMillis, byLocation = true)
            return TrackingEngine.Result(boardedSession, effects)
        }
        if (boarded.size >= 2) {
            // 不同线的车同时判成上车、定位又分不清：等用户选
            choice = BoardChoice(options = boarded.map { it.toBoardOption() }, sinceMillis = nowMillis)
            val ids = boarded.map { it.line.lineId }.toSet()
            lines = lines.map { if (it.line.lineId in ids) clearCandidate(it) else it }
        }

        val updated = waitingSession(session, target, lines, choice, nowMillis)
        return TrackingEngine.Result(updated, effects)
    }

    /** 这一轮请求了这条线却没拿到数据（某线无数据）：正在确认的车先保留（多半是一次失败），否则清掉车 */
    private fun noData(wl: WatchedLine): WatchedLine =
        if (wl.candidateBusId != null) {
            wl.copy(hasData = false)
        } else {
            wl.copy(
                busId = null,
                plate = "",
                busOrder = 0,
                busProgress = 0f,
                stopsRemaining = -1,
                etaSeconds = -1,
                isArrived = false,
                hasData = false,
                statusText = ""
            )
        }

    private fun updateLine(
        wl: WatchedLine,
        lp: LinePoll,
        input: BoardingDetector.Input?,
        allowNewCandidates: Boolean,
        boarded: MutableList<BoardingRule.Option>
    ): WatchedLine {
        val a = wl.line.stopOrder
        var line = wl
        var exclude: String? = null
        val candidateId = wl.candidateBusId
        if (candidateId != null) {
            val candidate = lp.buses.firstOrNull { it.busId == candidateId }
            if (input == null) {
                // 定位不能用了：一起等不靠猜判上车，放掉待确认，照常等
                line = clearCandidate(wl)
            } else if (input.user == null) {
                // 这一轮没有够新够准的定位：不计数、不吃超时兜底（一起等没定位不判上车）；等满 5 分钟还没定位就放掉
                if (input.nowMillis - wl.candidateSinceMillis < BoardingDetector.CANDIDATE_TIMEOUT_MILLIS) {
                    return atStop(wl, candidate, candidateId, lp)
                }
                line = clearCandidate(wl)
                exclude = candidateId
            } else {
                val probe = TrackingSessionState(
                    legPhase = LegPhase.WAITING_BOARD,
                    boardStopOrder = a,
                    boardingCandidateBusId = candidateId,
                    boardingCandidateSinceMillis = wl.candidateSinceMillis,
                    boardingConfirmStreak = wl.confirmStreak,
                    boardingMissStreak = wl.missStreak,
                    boardingLastFixElapsedMillis = wl.lastFixElapsedMillis,
                    updatedAtMillis = input.nowMillis
                )
                val result = BoardingDetector.evaluateCandidate(probe, candidate, input)
                when (result.verdict) {
                    BoardingDetector.Verdict.BOARDED -> {
                        boarded += BoardingRule.Option(wl.line, candidateId, candidate)
                        return atStop(wl, candidate, candidateId, lp).copy(
                            confirmStreak = result.state.boardingConfirmStreak,
                            missStreak = result.state.boardingMissStreak,
                            lastFixElapsedMillis = result.state.boardingLastFixElapsedMillis
                        )
                    }
                    BoardingDetector.Verdict.PENDING -> return atStop(wl, candidate, candidateId, lp).copy(
                        confirmStreak = result.state.boardingConfirmStreak,
                        missStreak = result.state.boardingMissStreak,
                        lastFixElapsedMillis = result.state.boardingLastFixElapsedMillis
                    )
                    BoardingDetector.Verdict.MISSED -> {
                        // 没赶上：放掉这辆，改等这条线的下一班
                        line = clearCandidate(wl)
                        exclude = candidateId
                    }
                }
            }
        }

        val best = lp.buses
            .filter { it.order <= a && it.busId != exclude }
            .maxByOrNull { it.order.toFloat() + it.segmentProgress }
            ?: return line.copy(
                busId = null,
                plate = "",
                busOrder = 0,
                busProgress = 0f,
                stopsRemaining = -1,
                etaSeconds = -1,
                isArrived = false,
                hasData = true,
                statusText = lp.statusText
            )
        val arrived = ArrivalRules.isBusArrivedAtOrder(a, best)
        line = line.copy(
            busId = best.busId,
            plate = best.friendlyPlate,
            busOrder = best.order,
            busProgress = best.segmentProgress,
            stopsRemaining = best.stopsTo(a).coerceAtLeast(0),
            etaSeconds = best.etaSeconds,
            isArrived = arrived,
            hasData = true,
            statusText = lp.statusText
        )
        // 车到站、有定位且人在站附近才进待确认（没定位时不进：一起等没定位不自动判上车）
        if (arrived && allowNewCandidates && input?.user != null && BoardingDetector.shouldStartCandidate(input)) {
            line = line.copy(
                candidateBusId = best.busId,
                candidateSinceMillis = input.nowMillis,
                confirmStreak = 0,
                missStreak = 0
            )
        }
        return line
    }

    /** 正在确认的车：显示为到站了（车可能已开出站，站点接口不再给它，这里用线路接口给的位置） */
    private fun atStop(wl: WatchedLine, bus: LiveBus?, busId: String, lp: LinePoll): WatchedLine = wl.copy(
        busId = busId,
        plate = bus?.friendlyPlate ?: wl.plate,
        busOrder = bus?.order ?: wl.busOrder,
        busProgress = bus?.segmentProgress ?: wl.busProgress,
        stopsRemaining = 0,
        etaSeconds = bus?.etaSeconds ?: -1,
        isArrived = true,
        hasData = true,
        statusText = lp.statusText
    )

    private fun clearCandidate(wl: WatchedLine): WatchedLine = wl.copy(
        candidateBusId = null,
        candidateSinceMillis = 0L,
        confirmStreak = 0,
        missStreak = 0
    )

    /**
     * 按到站先后排（稳定排序，同样快的保持原来的先后）：到站了 / 正在确认的最前；
     * 有 ETA 按秒数，没有 ETA 按站数估；没车的排最后。
     */
    fun sortByArrival(lines: List<WatchedLine>): List<WatchedLine> = lines.sortedBy { wl ->
        when {
            wl.busId == null -> Long.MAX_VALUE
            wl.isArrived || wl.candidateBusId != null -> -1L
            wl.etaSeconds > 0 -> wl.etaSeconds.toLong()
            wl.stopsRemaining >= 0 -> wl.stopsRemaining.toLong() * SECONDS_PER_STOP_GUESS + SECONDS_PER_STOP_GUESS / 2
            else -> Long.MAX_VALUE - 1
        }
    }

    /** 上车站坐标：目标里给的 > 站点接口给的 > 某条线的线路接口里这一站的坐标 */
    private fun boardStopCoords(
        target: TrackingTarget.WatchTogether,
        poll: WatchPoll,
        session: TrackingSession
    ): Pair<Double, Double> {
        if (target.stopLat > 0.0 && target.stopLng > 0.0) return target.stopLat to target.stopLng
        if (poll.stopLat > 0.0 && poll.stopLng > 0.0) return poll.stopLat to poll.stopLng
        for (wl in session.watchedLines) {
            val stop = poll.lines[wl.line.lineId]?.stops?.firstOrNull { it.order == wl.line.stopOrder } ?: continue
            if (stop.wgsLat > 0.0 && stop.wgsLng > 0.0) return stop.wgsLat to stop.wgsLng
        }
        return 0.0 to 0.0
    }

    /** 提醒用的状态：线名、站名、站数按发提醒的那条线改写 */
    private fun alertState(
        base: TrackingSessionState,
        target: TrackingTarget.WatchTogether,
        wl: WatchedLine
    ): TrackingSessionState = base.copy(
        lineId = wl.line.lineId,
        lineName = wl.line.lineName,
        endStopName = wl.line.endStopName,
        targetStopName = target.stopName,
        targetOrder = wl.line.stopOrder,
        boardStopOrder = wl.line.stopOrder,
        currentBusId = wl.busId,
        currentBusOrder = wl.busOrder,
        stopsRemaining = if (wl.isArrived) 0 else wl.stopsRemaining,
        etaSeconds = wl.etaSeconds,
        isBusArrivedAtStop = wl.isArrived
    )

    /** 等车阶段的会话：排第一的线写进 leg / state */
    private fun waitingSession(
        session: TrackingSession,
        target: TrackingTarget.WatchTogether,
        lines: List<WatchedLine>,
        choice: BoardChoice?,
        nowMillis: Long
    ): TrackingSession {
        val primary = lines.firstOrNull() ?: return session
        val previous = session.state
        val a = primary.line.stopOrder
        val busChanged = primary.busId != previous.currentBusId || primary.line.lineId != previous.lineId
        val startOrder = when {
            primary.busId != null && busChanged -> (primary.busOrder - 1).coerceIn(1, (a - 1).coerceAtLeast(1))
            primary.line.lineId != previous.lineId -> (a - 1).coerceAtLeast(1)
            else -> previous.startTrackingOrder
        }
        val state = previous.copy(
            lineId = primary.line.lineId,
            lineName = primary.line.lineName,
            endStopName = primary.line.endStopName,
            targetStopId = target.stopId,
            targetStopName = target.stopName,
            targetOrder = a,
            totalStops = a,
            boardStopOrder = a,
            boardStopName = target.stopName,
            startTrackingOrder = startOrder,
            currentBusId = primary.busId,
            currentBusOrder = primary.busOrder,
            currentBusProgress = primary.busProgress,
            stopsRemaining = primary.stopsRemaining,
            etaSeconds = primary.etaSeconds,
            isBusArrivedAtStop = primary.isArrived,
            isSignalLost = false,
            boardingCandidateBusId = primary.candidateBusId,
            boardingCandidateSinceMillis = primary.candidateSinceMillis,
            boardingConfirmStreak = primary.confirmStreak,
            boardingMissStreak = primary.missStreak,
            hasTriggeredApproachingAlert = primary.busId != null && primary.approachAlertedBusId == primary.busId,
            hasTriggeredArrivedAlert = primary.busId != null && primary.arrivedAlertedBusId == primary.busId,
            statusSummary = summaryOf(target, lines, choice),
            updatedAtMillis = nowMillis
        )
        return session.copy(
            leg = legOf(target, primary.line),
            state = state,
            watchedLines = lines,
            boardChoice = choice
        )
    }

    private fun summaryOf(target: TrackingTarget.WatchTogether, lines: List<WatchedLine>, choice: BoardChoice?): String {
        if (choice != null) {
            return "${choice.options.joinToString("、") { it.lineName }} 都在 ${target.stopName}，上了哪辆？"
        }
        val primary = lines.first()
        val name = primary.line.lineName
        return when {
            primary.busId == null && lines.none { it.hasData } -> "一起等 ${lines.size} 条线 · 正在获取实时数据…"
            primary.busId == null -> "一起等 ${lines.size} 条线 · 暂无开往 ${target.stopName} 的车"
            primary.candidateBusId != null -> "$name 到站了，正在确认你是否上车…"
            primary.isArrived -> "$name 到站了 · ${target.stopName}"
            else -> {
                val eta = primary.etaSeconds.takeIf { it > 0 }
                    ?.let { " · 约 ${(it / 60.0).roundToInt().coerceAtLeast(1)} 分钟" }.orEmpty()
                "$name 先到 · 还有 ${primary.stopsRemaining} 站$eta"
            }
        }
    }

    private fun BoardingRule.Option.toBoardOption() = BoardOption(
        lineId = line.lineId,
        lineName = line.lineName,
        busId = busId,
        plate = bus?.friendlyPlate.orEmpty(),
        order = bus?.order ?: 0
    )

    // ---------------------------------------------------------------- 上车

    /**
     * 上车：会话切到 [pick] 那条线（代际号 +1，服务马上按这条线轮询），进入乘车阶段，只追这一辆。
     * 没有下车站：知道全线站点时目标设成终点站；不知道（[stops] 为空）时 `targetOrder = 0`，第一次拿到线路详情时补上。
     * [pick] 的车不知道 id 时（⑤：一辆都没有）`lockedBusId = null`，后续轮询按 A..A+1 最近补锁。
     */
    private fun board(
        session: TrackingSession,
        target: TrackingTarget.WatchTogether,
        pick: BoardingRule.Option?,
        stops: List<BusStop>,
        nowMillis: Long,
        byLocation: Boolean,
        line: WatchLine = requireNotNull(pick).line,
        fallbackOrder: Int = 0
    ): TrackingSession {
        val a = line.stopOrder
        val terminal = stops.maxByOrNull { it.order }?.takeIf { it.order > a }
        val targetOrder = terminal?.order ?: 0
        val busId = pick?.busId?.takeIf { it.isNotBlank() }
        val order = pick?.bus?.order ?: fallbackOrder
        val summary = when {
            busId == null -> "已确认上车，正在识别所乘车辆…"
            byLocation -> "已上 ${line.lineName}，只追这一辆"
            else -> "已确认上车 ${line.lineName}，只追这一辆"
        }
        val state = TrackingSessionState(
            isActive = true,
            cityId = target.cityId,
            lineId = line.lineId,
            lineName = line.lineName,
            endStopName = line.endStopName,
            targetStopId = terminal?.stopId.orEmpty(),
            targetStopName = terminal?.name ?: line.endStopName,
            targetOrder = targetOrder,
            totalStops = targetOrder.coerceAtLeast(a),
            startTrackingOrder = a,
            currentBusId = busId,
            currentBusOrder = order,
            currentBusProgress = pick?.bus?.segmentProgress ?: 0f,
            stopsRemaining = if (targetOrder > 0 && order > 0) {
                (pick?.bus?.stopsTo(targetOrder) ?: (targetOrder - order)).coerceAtLeast(0)
            } else -1,
            etaSeconds = -1,
            alertThresholdStops = session.state.alertThresholdStops,
            currentLegIndex = 0,
            totalLegs = 1,
            statusSummary = summary,
            updatedAtMillis = nowMillis,
            legPhase = LegPhase.ON_BOARD,
            boardStopOrder = a,
            boardStopName = target.stopName,
            lockedBusId = busId
        )
        return session.copy(
            generation = session.generation + 1,
            leg = legOf(target, line),
            state = state,
            watchedLines = emptyList(),
            boardChoice = null
        )
    }

    /**
     * 手动「我上车了」（§2.6 一条规则）：
     * ① 有正在确认的车 → 就是它；② 否则每条线取 order 在 A..A+1、离 A 最近的一辆，其中「在站」的
     * （已到站或刚开出 A）只有一辆就是它；③ 不同线的车同时在站、定位分得清 → 直接选那辆；
     * ④ 分不清或没定位 → 写 `boardChoice`，会话仍在等车；⑤ 没有在站的车 → 取离 A 最近的一辆；
     * 一辆都没有 → 按排第一的线进乘车阶段、`lockedBusId = null`，后续轮询补锁。
     *
     * @param lineBuses 最近一次轮询每条线的车（lineId -> 车）
     * @param fix 判上车用的定位（已过滤），没有为 null
     * @return 新会话；不在等车阶段时 null
     */
    fun confirmBoarded(
        session: TrackingSession,
        lineBuses: Map<String, List<LiveBus>>,
        fix: UserFix?,
        nowMillis: Long
    ): TrackingSession? {
        val target = session.watchTarget ?: return null
        if (session.state.legPhase != LegPhase.WAITING_BOARD) return null

        val pending = session.watchedLines.filter { it.candidateBusId != null }.map { wl ->
            val id = requireNotNull(wl.candidateBusId)
            BoardingRule.Option(wl.line, id, lineBuses[wl.line.lineId]?.firstOrNull { it.busId == id })
        }
        val options = pending.ifEmpty {
            val nearest = session.watchedLines.mapNotNull { wl ->
                val a = wl.line.stopOrder
                GroupLegTracker.pickBoardedBus(a, lineBuses[wl.line.lineId].orEmpty())
                    ?.let { BoardingRule.Option(wl.line, it.busId, it) }
            }
            val atStop = nearest.filter { option ->
                val bus = requireNotNull(option.bus)
                bus.order > option.line.stopOrder || ArrivalRules.isBusArrivedAtOrder(option.line.stopOrder, bus)
            }
            atStop.ifEmpty { listOfNotNull(nearest.minByOrNull { distanceToStop(it) }) }
        }

        val pick = when {
            options.size == 1 -> options.single()
            options.size >= 2 -> BoardingRule.distinguish(options, fix)
                ?: return session.copy(
                    boardChoice = BoardChoice(options.map { it.toBoardOption() }, sinceMillis = nowMillis),
                    watchedLines = session.watchedLines.map(::clearCandidate),
                    state = session.state.copy(
                        boardingCandidateBusId = null,
                        statusSummary = "${options.joinToString("、") { it.line.lineName }} 都在 ${target.stopName}，上了哪辆？",
                        updatedAtMillis = nowMillis
                    )
                )
            else -> null
        }
        if (pick == null) {
            // 一辆都没有：按排第一的线上车，后续轮询补锁
            val primary = session.watchedLines.firstOrNull()?.line ?: target.lines.first()
            return board(session, target, null, emptyList(), nowMillis, byLocation = false, line = primary)
        }
        return board(session, target, pick, emptyList(), nowMillis, byLocation = false)
    }

    /** 车到上车站的距离（站），同 [GroupLegTracker.pickBoardedBus] 的口径 */
    private fun distanceToStop(option: BoardingRule.Option): Float {
        val bus = requireNotNull(option.bus)
        val a = option.line.stopOrder
        val progress = when {
            bus.isArrived -> 1f
            bus.hasValidProgress -> bus.segmentProgress.coerceIn(0f, 1f)
            else -> 0.5f
        }
        return if (bus.order == a) 1f - progress else progress
    }

    /**
     * 「上了哪辆？」用户选了 [busId]：上那辆；[busId] 为 null（「还没上」）：清掉待选，接着等。
     * 没有待选、或 [busId] 不在选项里时返回 null。
     */
    fun chooseBoardedBus(session: TrackingSession, busId: String?, nowMillis: Long): TrackingSession? {
        val target = session.watchTarget ?: return null
        val choice = session.boardChoice ?: return null
        if (session.state.legPhase != LegPhase.WAITING_BOARD) return null
        if (busId == null) {
            val lines = session.watchedLines
            return waitingSession(session, target, lines, null, nowMillis)
        }
        val option = choice.options.firstOrNull { it.busId == busId } ?: return null
        val line = target.lines.firstOrNull { it.lineId == option.lineId } ?: return null
        return board(
            session,
            target,
            BoardingRule.Option(line, option.busId, null),
            emptyList(),
            nowMillis,
            byLocation = false,
            fallbackOrder = option.order
        )
    }
}
