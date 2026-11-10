package com.trackr.ui.screens.stopdetail

import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LegPhase
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.WatchedLine
import com.trackr.core.model.displayLineName

/*
 * 站点详情页的纯映射（第 21 步）：原始数据 -> 页面直接用的扁平 UI 模型。全是纯函数，单测直接调。
 */

/**
 * 站台名（计划第 21 步、R7）：每个站台取线路终点里出现最多的那个，叫「往 X」（平局取先出现的）。
 * 有站台拿不到线路数据，或两个站台算出同名时，全部退回「站台 1 / 2」。数据源不给罗盘方向，按终点命名最稳定。
 */
internal fun platformLabels(stops: List<NearbyStop>): List<String> {
    val fallback = stops.indices.map { "站台 ${it + 1}" }
    val names = stops.map { stop ->
        val ends = stop.lines
            .filter { it.line.lineId.isNotBlank() }
            .map { it.line.endStop.trim() }
            .filter { it.isNotEmpty() }
        if (ends.isEmpty()) return fallback
        val counts = ends.groupingBy { it }.eachCount()
        val top = counts.values.max()
        "往 ${ends.first { counts[it] == top }}"
    }
    return if (names.toSet().size == names.size) names else fallback
}

/** 列表 key（同一站台内唯一，和旧页面的组成一致） */
internal fun rowKeyOf(status: NearbyStopLineStatus): String {
    val line = status.line
    return "${line.source.name}_${line.lineId.ifBlank { line.name }}_${status.targetOrder}_${line.direction}"
}

/** 收藏 id（和旧页面一样：城市_线路_站序） */
internal fun favoriteIdOf(cityId: String, status: NearbyStopLineStatus): String =
    "${cityId}_${status.line.lineId}_${status.targetOrder}"

/** 运营时间「06:30–22:30」；缺一个就不写 */
internal fun hoursText(firstTime: String, lastTime: String): String =
    if (firstTime.isNotBlank() && lastTime.isNotBlank()) "$firstTime–$lastTime" else ""

/**
 * 一行的到站文字。和旧页面 `computeArrivalStatus` 同一优先级：
 * 数据源给了状态文字就用它；否则按最近一辆车；都没有时「暂无在途车辆」。
 */
internal fun arrivalOf(status: NearbyStopLineStatus): ArrivalDisplay {
    val text = status.statusText.trim()
    if (text.isNotEmpty()) {
        return ArrivalDisplay(value = text, unit = null, detail = "", short = text, tone = ArrivalTone.Mute, spoken = text)
    }
    val bus = status.approachingBuses.firstOrNull()
    val state = if (bus != null) {
        ArrivalText.stateForBus(status.targetOrder, bus)
    } else {
        // 运营时间已写在行左边，这里不重复
        ArrivalState.NoService("暂无在途车辆")
    }
    return ArrivalText.format(state, lineName = status.line.displayLineName, stopName = status.targetStopName)
}

/** 状态文字里出现这些词就算不在运营（「末班已过」「暂停运营」「不在运营时间」等） */
private val OUT_OF_SERVICE_WORDS = listOf("末班已过", "停运", "暂停运营", "不在运营", "非运营", "未运营", "收班")

/**
 * 这条线现在有没有在运营：有来车就算；数据源给了状态文字时，不含 [OUT_OF_SERVICE_WORDS] 就算
 * （「等待发车」「3站」「正在运营中」都算在运营）；既没车也没文字（「暂无在途车辆」）不算。
 */
internal fun isInService(status: NearbyStopLineStatus): Boolean {
    if (status.approachingBuses.isNotEmpty()) return true
    val text = status.statusText.trim()
    if (text.isEmpty()) return false
    return OUT_OF_SERVICE_WORDS.none { text.contains(it) }
}

/**
 * 站点页的线路行。排序：运营中的在前、不在运营的在后；每一段里收藏的在前；
 * 其余保持数据源给的顺序（稳定排序）。傍晚停运的线多，运营中的线不再夹在中间难找。
 */
internal fun rowsOf(cityId: String, stop: NearbyStop?, favoriteIds: Set<String>): List<StopLineRowUi> =
    stop?.lines.orEmpty().map { status ->
        val inService = isInService(status)
        val line = status.line
        StopLineRowUi(
            key = rowKeyOf(status),
            lineId = line.lineId,
            lineName = line.displayLineName,
            endStop = line.endStop,
            hours = hoursText(line.firstTime, line.lastTime),
            arrival = arrivalOf(status),
            isFavorite = line.lineId.isNotBlank() && favoriteIds.contains(favoriteIdOf(cityId, status)),
            selectable = line.lineId.isNotBlank() && status.targetOrder > 0,
            targetOrder = status.targetOrder,
            stopId = stop?.stopId.orEmpty(),
            source = line.source
        ) to inService
    }
        .sortedWith(compareBy({ !it.second }, { !it.first.isFavorite }))
        .map { it.first }

/** 顶栏小字「120米 · 8 条线」 */
internal fun subtitleOf(stop: NearbyStop?): String {
    stop ?: return ""
    val count = "${stop.lines.size} 条线"
    val dist = RouteProjectionMath.formatDistanceText(stop.distanceMeters)
    return if (dist.isNotBlank()) "$dist · $count" else count
}

internal fun transferTextOf(stop: NearbyStop?): String? =
    stop?.subwayLineNames?.takeIf { it.isNotEmpty() }?.let { "可换乘：${it.joinToString(" · ")}" }

/** 会话是不是这个站的一起等（站点 id 或物理站台对得上） */
internal fun isWatchingHere(session: TrackingSession?, pageStopId: String, stops: List<NearbyStop>): Boolean {
    val watch = session?.watchTarget ?: return false
    if (session.interrupted) return false
    if (watch.stopId.isNotBlank() && (watch.stopId == pageStopId || stops.any { it.stopId == watch.stopId })) return true
    val physical = watch.physicalStId?.takeIf { it.isNotBlank() } ?: return false
    return stops.any { it.physicalStId == physical }
}

/** 一起等进行中视图；不是这个站的一起等时为 null */
internal fun watchUiOf(session: TrackingSession?, pageStopId: String, stops: List<NearbyStop>): WatchTogetherUi? {
    if (session == null || !isWatchingHere(session, pageStopId, stops)) return null
    val target = session.watchTarget ?: return null
    val state = session.state
    if (state.legPhase == LegPhase.ON_BOARD) {
        return WatchTogetherUi(
            lineCount = target.lines.size,
            lines = emptyList(),
            isOnBoard = true,
            boardedTitle = "已上 ${state.lineName}",
            boardedDetail = if (state.stopsRemaining >= 0) "还有 ${state.stopsRemaining} 站到终点" else state.statusSummary
        )
    }
    return WatchTogetherUi(
        lineCount = target.lines.size,
        lines = session.watchedLines.map { watchLineUiOf(it, target.stopName) }
    )
}

internal fun watchLineUiOf(wl: WatchedLine, stopName: String): WatchLineUi {
    val state = when {
        wl.isArrived -> ArrivalState.Arriving()
        !wl.hasData -> ArrivalState.Loading
        wl.busId == null -> ArrivalState.NoService("暂无来车", wl.statusText)
        else -> ArrivalState.OnTheWay(
            stopsAway = wl.stopsRemaining.takeIf { it >= 0 },
            minutes = ArrivalText.minutesOf(wl.etaSeconds)
        )
    }
    val stopsText = if (wl.busId != null && !wl.isArrived && wl.stopsRemaining >= 0) "还有 ${wl.stopsRemaining} 站" else ""
    return WatchLineUi(
        lineId = wl.line.lineId,
        lineName = wl.line.lineName,
        endStop = wl.line.endStopName,
        arrival = ArrivalText.format(state, lineName = wl.line.lineName, stopName = stopName),
        plate = wl.plate,
        detail = listOf(stopsText, wl.plate).filter { it.isNotBlank() }.joinToString(" · ")
    )
}

/** 站点页要不要打开「上了哪辆？」；没有待选、不是这个站、或这次待选已经弹过 / 被划掉时为 null */
internal fun boardChoiceUiOf(
    session: TrackingSession?,
    pageStopId: String,
    stops: List<NearbyStop>,
    dismissedSinceMillis: Long?
): BoardChoiceUi? {
    if (session == null || !isWatchingHere(session, pageStopId, stops)) return null
    if (session.boardChoice?.sinceMillis == dismissedSinceMillis) return null
    return boardChoiceUiOf(session)
}

/** 「上了哪辆？」弹层内容；会话没有待选（或不是一起等）时为 null */
internal fun boardChoiceUiOf(session: TrackingSession): BoardChoiceUi? {
    val choice = session.boardChoice ?: return null
    if (choice.options.isEmpty()) return null
    val target = session.watchTarget ?: return null
    val count = choice.options.size
    val countText = if (count == 2) "两辆车" else "$count 辆车"
    return BoardChoiceUi(
        sessionId = session.sessionId,
        subtitle = "${countText}都在 ${target.stopName}",
        options = choice.options.map { option ->
            BoardOptionUi(
                busId = option.busId,
                lineName = option.lineName,
                endStop = target.lines.firstOrNull { it.lineId == option.lineId }?.endStopName.orEmpty(),
                plate = option.plate
            )
        }
    )
}
