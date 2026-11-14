package com.trackr.feature.bus.home

import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.WatchedLine
import com.trackr.core.model.displayLineName
import com.trackr.ui.components.arrival.ArrivalUi

/**
 * 首页数据 → 扁平 UI 模型（纯函数，单测直接测）。
 *
 * 文案按 v3：路线方向「始发 → 终点」；首页卡片的方向只写「→ 终点」（始发站太长放不下）；
 * 没车时有下一班发车时间就写「下一班 07:20 · 从 X 发车」，
 * 数据源给了状态字就照写，都没有才写「暂无在途车辆」。
 */
internal object BusHomeMapper {

    /** 重新算 UI 模型；和 [old] 相等的行沿用旧实例，列表没变就沿用旧列表（§4.1，强跳过靠 ===） */
    fun derive(old: BusHomeUiState, new: BusHomeUiState): BusHomeUiState {
        val cards = reuseList(old.trackingCards, trackingCards(new.trackingSessions, new.pushOnlineLines), TrackingCardUi::key)
        val rows = reuseList(old.favoriteRows, favoriteRows(new), FavoriteRowUi::key)
        val nearby = reuseList(old.nearbyCards, nearbyCards(new), NearbyStopUi::key)
        return new.copy(trackingCards = cards, favoriteRows = rows, nearbyCards = nearby)
    }

    // ---- 追踪卡 ----

    /**
     * 每一项追踪一张卡；一起等等车时**每条线各一张**（这样每张都能点进自己那条线的线路页，
     * 「结束」也只去掉这一条）。
     */
    fun trackingCards(
        sessions: List<TrackingSession>,
        pushOnlineLines: Map<String, Set<String>> = emptyMap()
    ): List<TrackingCardUi> = sessions.flatMap { session ->
        cardsOf(session).map { card ->
            card.copy(
                isPushOnline = !session.interrupted &&
                    (card.open.source == com.trackr.core.model.DataSourceType.MEISHAN || card.open.lineId.startsWith("ms:")) &&
                    card.open.lineId in pushOnlineLines[session.sessionId].orEmpty()
            )
        }
    }

    private fun cardsOf(session: TrackingSession): List<TrackingCardUi> {
        val state = session.state
        if (!state.isActive) return emptyList()
        val watch = session.watchTarget
        val watched = session.watchedLines
        // 等车阶段一起等的每条线各一张卡（上车后 watchedLines 清空了，只剩坐上的那辆）
        if (watch != null && watched.isNotEmpty()) {
            return watched.map { line -> watchCard(session, watch, line) }
        }

        val open = LineTarget(
            cityId = state.cityId.ifBlank { session.leg.cityId },
            lineId = state.lineId.ifBlank { session.leg.lineId },
            targetOrder = state.targetOrder,
            stopId = state.targetStopId,
            source = session.leg.source,
            lineName = state.lineName
        )
        val sessionState = ArrivalText.stateForSession(state)
        val display = ArrivalText.format(sessionState)
        val loadingDetail = if (state.stopsRemaining < 0) state.statusSummary.ifBlank { display.detail } else display.detail
        val group = session.group
        val key = session.sessionId
        return when {
            watch != null -> listOf(
                TrackingCardUi(
                    key = key,
                    sessionId = session.sessionId,
                    // 一起等上车后只追坐上的那辆，到终点结束
                    title = joinDot("一起等 · 已上 ${state.lineName}", watch.stopName),
                    arrival = ArrivalUi.of(display),
                    detail = joinDot("坐到 ${state.targetStopName}", loadingDetail),
                    open = open
                )
            )
            group != null -> {
                val routeName = routeTitle(group)
                val legText = if (group.legs.size > 1) "第 ${state.currentLegIndex + 1} 程，共 ${group.legs.size} 程" else ""
                val phaseText = when (state.legPhase) {
                    LegPhase.ON_BOARD -> "已上 ${state.lineName} · 到 ${state.targetStopName}下车"
                    else -> "${state.lineName} · ${state.boardStopName.ifBlank { state.targetStopName }}上车"
                }
                listOf(
                    TrackingCardUi(
                        key = key,
                        sessionId = session.sessionId,
                        title = joinDot("路线 $routeName", legText),
                        arrival = ArrivalUi.of(display),
                        detail = joinDot(phaseText, loadingDetail),
                        open = open,
                        // 车上（等下车）不读「快去上车」那套
                        spoken = if (state.legPhase == LegPhase.ON_BOARD) {
                            ""
                        } else {
                            ArrivalText.spoken(
                                sessionState,
                                lineName = state.lineName,
                                stopName = state.boardStopName.ifBlank { state.targetStopName },
                                towards = state.endStopName
                            )
                        }
                    )
                )
            }
            else -> listOf(
                TrackingCardUi(
                    key = key,
                    sessionId = session.sessionId,
                    title = joinDot("追踪 ${state.lineName}", state.targetStopName),
                    arrival = ArrivalUi.of(display),
                    detail = loadingDetail,
                    open = open,
                    spoken = ArrivalText.spoken(
                        sessionState,
                        lineName = state.lineName,
                        stopName = state.targetStopName,
                        towards = state.endStopName
                    )
                )
            )
        }
    }

    /** 一起等等车时一条线一张卡：大字是这条线自己到站情况，点进去是这条线的线路页 */
    private fun watchCard(
        session: TrackingSession,
        watch: TrackingTarget.WatchTogether,
        line: WatchedLine
    ): TrackingCardUi {
        val arrival = watchedLineState(line)
        val display = ArrivalText.format(arrival)
        val inChoice = session.boardChoice?.options?.any { it.lineId == line.line.lineId } == true
        val detail = when {
            inChoice -> joinDot("都在 ${watch.stopName}", "选一下上了哪辆")
            line.isArrived -> "到站了，快去上车"
            else -> display.detail
        }
        return TrackingCardUi(
            key = "${session.sessionId}:${line.line.lineId}",
            sessionId = session.sessionId,
            title = joinDot("追踪 ${line.line.lineName}", watch.stopName),
            arrival = ArrivalUi.of(display),
            detail = detail,
            open = LineTarget(
                cityId = watch.cityId,
                lineId = line.line.lineId,
                targetOrder = line.line.stopOrder,
                stopId = line.line.stopIdOnLine.ifBlank { watch.stopId },
                source = line.line.source,
                lineName = line.line.lineName
            ),
            spoken = ArrivalText.spoken(
                arrival,
                lineName = line.line.lineName,
                stopName = watch.stopName,
                towards = line.line.endStopName
            ),
            watchLineId = line.line.lineId
        )
    }

    /** 一起等里一条线此刻的到站状态 */
    fun watchedLineState(line: WatchedLine): ArrivalState = when {
        !line.hasData -> ArrivalState.NoService(line.statusText.ifBlank { "暂无数据" })
        line.busId == null -> ArrivalState.NoService(line.statusText.ifBlank { "暂无在途车辆" })
        line.isArrived -> ArrivalState.Arriving()
        else -> ArrivalState.OnTheWay(
            stopsAway = line.stopsRemaining.takeIf { it >= 0 },
            minutes = ArrivalText.minutesOf(line.etaSeconds)
        )
    }

    // ---- 收藏 ----

    fun favoriteRows(state: BusHomeUiState): List<FavoriteRowUi> {
        val groupId = state.selectedGroupId
        val colorOf = state.favoriteGroups.mapNotNull { group -> group.color?.let { group.id to it } }.toMap()
        return state.favoriteItems
            .filter { groupId == null || it.groupId == groupId }
            .map { item ->
                val color = item.groupId?.let(colorOf::get)
                when (item) {
                    is FavoriteItem.Stop -> stopRow(item, state.favoritesRealtime[item.stop.id]).copy(groupColor = color)
                    is FavoriteItem.Route -> routeRow(item).copy(groupColor = color)
                    is FavoriteItem.Station -> stationRow(item, state.favoritesRealtime[item.key]).copy(groupColor = color)
                }
            }
    }

    private fun stopRow(item: FavoriteItem.Stop, realtime: NearbyStop?): FavoriteRowUi.Stop {
        val fav = item.stop
        val status = realtime?.lines?.firstOrNull { it.line.lineId == fav.lineId } ?: realtime?.lines?.firstOrNull()
        // 收藏里可能没存到名字（数据源给了空名字、或者是旧版本存的）：先用实时数据里这条线的名字补，
        // 再退回线路号，免得首页只剩一个空徽标（issue #20）
        val lineName = fav.displayLineName.ifBlank { status?.line?.displayLineName.orEmpty() }
        val display = if (status == null) {
            ArrivalText.format(ArrivalState.Loading, lineName = lineName, stopName = fav.targetStopName, towards = fav.endStop)
        } else {
            arrivalDisplayFor(
                status,
                nextDepartureIsFromStart = true,
                lineName = lineName,
                stopName = fav.targetStopName,
                towards = fav.endStop
            )
        }
        return FavoriteRowUi.Stop(
            key = item.key,
            lineName = lineName,
            boardText = "${fav.targetStopName}上车",
            direction = terminalText(fav.endStop, fav.startStop),
            arrival = ArrivalUi.of(display),
            open = lineTargetOf(fav, lineName),
            spoken = display.spoken
        )
    }

    /** 收藏的站首页列几条线（按到站先后） */
    const val STATION_PREVIEW_LINES = 3

    /**
     * 收藏的站（issue #47）。[platform] 是 `refreshFavoritesRealtime` 找回的默认站台：null = 还没查到（正在查车），
     * 没有线路 = 查不到这个站（可能临时改道）。
     */
    fun stationRow(item: FavoriteItem.Station, platform: NearbyStop?): FavoriteRowUi.Station {
        val fav = item.station
        val title = joinDot(fav.stopName, fav.platformLabel)
        val open = StopTarget(
            cityId = fav.cityId,
            stopId = fav.stopId,
            stopName = fav.stopName,
            physicalStId = fav.physicalStId,
            namesakeStId = fav.namesakeStId,
            lat = fav.wgsLat,
            lng = fav.wgsLng,
            source = fav.source,
            cityName = fav.cityName,
            mygolbsCityCode = fav.mygolbsCityCode
        )
        if (platform == null || platform.lines.isEmpty()) {
            return FavoriteRowUi.Station(
                key = item.key,
                title = title,
                lines = emptyList(),
                lineCount = 0,
                status = if (platform == null) StationStatus.Loading else StationStatus.NotFound,
                arrival = if (platform == null) ArrivalUi.of(ArrivalText.format(ArrivalState.Loading)) else null,
                open = open
            )
        }
        // 站点接口给的线：按到站先后排，先到的在前；同样先后时保持接口顺序
        val ranked = platform.lines
            .filter { it.line.lineId.isNotBlank() || it.line.displayLineName.isNotBlank() }
            .map { status ->
                val lineName = status.line.displayLineName
                val display = arrivalDisplayFor(
                    status,
                    nextDepartureIsFromStart = false,
                    lineName = lineName,
                    stopName = fav.stopName,
                    towards = status.line.endStop
                )
                Triple(status, display, arrivalRank(status))
            }
            .withIndex()
            .sortedWith(compareBy({ it.value.third }, { it.index }))
            .map { it.value }
        val lines = ranked.take(STATION_PREVIEW_LINES).map { (status, display, _) ->
            StationLineUi(
                lineName = status.line.displayLineName,
                direction = terminalText(status.line.endStop, status.line.startStop),
                short = display.short,
                arrival = ArrivalUi.of(display)
            )
        }
        // 评审 P3-6：站点接口只回了占位空壳行（补全失败）时，ranked 会全部被滤空——
        // 不能以 Ready + 零条线渲染成整行空白，按「查不到」兜底
        if (ranked.isEmpty()) {
            return FavoriteRowUi.Station(
                key = item.key,
                title = title,
                lines = emptyList(),
                lineCount = 0,
                status = StationStatus.NotFound,
                arrival = null,
                open = open
            )
        }
        val first = ranked.firstOrNull()
        return FavoriteRowUi.Station(
            key = item.key,
            title = title,
            lines = lines,
            lineCount = ranked.size,
            status = StationStatus.Ready,
            arrival = first?.second?.let { ArrivalUi.of(it).copy(small = "${ranked.size} 条线") },
            open = open,
            spoken = first?.second?.spoken.orEmpty()
        )
    }

    /**
     * 站里一条线的先后：到站了最先，其次按分钟（没有分钟按站数估），没车的排最后。
     * 和 [arrivalDisplayFor] 选的是同一辆车。
     */
    private fun arrivalRank(status: NearbyStopLineStatus): Int {
        val bus = ArrivalText.nearestBus(status.targetOrder, status.approachingBuses)
            ?: status.approachingBuses.firstOrNull()
            ?: return Int.MAX_VALUE
        return when (val state = ArrivalText.stateForBus(status.targetOrder, bus)) {
            is ArrivalState.Arriving -> 0
            is ArrivalState.OnTheWay -> state.minutes?.let { it * 60 }
                ?: state.stopsAway?.let { it * STOP_SECONDS_ESTIMATE }
                ?: (Int.MAX_VALUE - 1)
            else -> Int.MAX_VALUE - 1
        }
    }

    /** 只有站数时按一站约 2 分钟估，用来和有分钟数的线排先后 */
    private const val STOP_SECONDS_ESTIMATE = 120

    private fun routeRow(item: FavoriteItem.Route): FavoriteRowUi.Route {
        val route = item.route
        val lines = route.legs.mapNotNull { it.primaryLine?.lineName?.takeIf(String::isNotBlank) }
        val transfers = route.legs.size - 1
        val linesText = joinDot(
            lines.joinToString(" › "),
            if (transfers > 0) "换乘 $transfers 次" else ""
        )
        return FavoriteRowUi.Route(
            key = item.key,
            routeId = route.id,
            title = routeTitle(route),
            linesText = linesText
        )
    }

    fun routeTitle(route: TransitGroup): String {
        val ends = directionText(route.originStopName, route.destinationStopName)
        return route.name.trim().ifBlank { ends }
    }

    fun lineTargetOf(fav: FavoriteStationLine, lineName: String = fav.displayLineName): LineTarget = LineTarget(
        cityId = fav.cityId,
        lineId = fav.lineId,
        targetOrder = fav.targetOrder,
        stopId = fav.targetStopId,
        source = fav.source,
        lineName = lineName
    )

    // ---- 附近 ----

    fun nearbyCards(state: BusHomeUiState): List<NearbyStopUi> {
        val cityId = state.currentCity.cityId
        val stops = if (state.nearbyStops.any { it.source == DataSourceType.MEISHAN }) {
            state.nearbyStops.sortedWith(
                compareBy<NearbyStop> { it.source == DataSourceType.MEISHAN && it.lines.isEmpty() }
                    .thenBy { it.distanceMeters ?: Int.MAX_VALUE }
            )
        } else state.nearbyStops
        return stops.map { stop ->
            NearbyStopUi(
                key = nearbyKey(stop),
                name = stop.name,
                meta = joinDot(
                    RouteProjectionMath.formatDistanceText(stop.distanceMeters),
                    if (stop.lines.isNotEmpty()) "${stop.lines.size} 条线" else "",
                    stop.subwayLineNames.firstOrNull().orEmpty()
                ),
                lines = stop.lines.mapIndexed { index, status ->
                    NearbyLineUi(
                        key = "${status.line.lineId}_${status.targetOrder}_$index",
                        lineName = status.line.displayLineName,
                        direction = terminalText(status.line.endStop, status.line.startStop),
                        arrival = arrivalFor(status, nextDepartureIsFromStart = false),
                        open = LineTarget(
                            cityId = cityId,
                            lineId = status.line.lineId,
                            targetOrder = status.targetOrder,
                            stopId = stop.stopId,
                            source = status.line.source,
                            lineName = status.line.displayLineName
                        )
                    )
                },
                open = StopTarget(
                    cityId = cityId,
                    stopId = stop.stopId,
                    stopName = stop.name,
                    physicalStId = stop.physicalStId.orEmpty(),
                    namesakeStId = stop.namesakeStId.orEmpty(),
                    lat = stop.wgsLat,
                    lng = stop.wgsLng
                )
            )
        }
    }

    /** 附近站的 Lazy key（和旧首页一样：数据源 + 站 id，没有 id 时用名字和坐标） */
    fun nearbyKey(stop: NearbyStop): String =
        "${stop.source.name}_${stop.stopId.ifBlank { "${stop.name}_${stop.wgsLat}_${stop.wgsLng}" }}"

    // ---- 到站文字 ----

    /**
     * 一条线在某站的首页到站文字。
     *
     * 有车时首页只显示剩余站数或状态，不把每 15 秒轮询得到的 ETA 当成倒计时：
     * 有站序写「还有 N 站」，没有站序写「在路上」。没车时保留下一班实际发车时间、
     * 数据源状态字和「暂无在途车辆」。线路详情、追踪卡等其他入口仍直接使用 [ArrivalText]。
     */
    fun arrivalFor(status: NearbyStopLineStatus, nextDepartureIsFromStart: Boolean): ArrivalUi =
        ArrivalUi.of(arrivalDisplayFor(status, nextDepartureIsFromStart))

    /** 同 [arrivalFor]，带朗读整句（长辈版「读给我听」）；[lineName]、[stopName]、[towards] 只用于朗读 */
    fun arrivalDisplayFor(
        status: NearbyStopLineStatus,
        nextDepartureIsFromStart: Boolean,
        lineName: String = "",
        stopName: String = "",
        towards: String = ""
    ): ArrivalDisplay {
        fun format(state: ArrivalState) = ArrivalText.format(state, lineName = lineName, stopName = stopName, towards = towards)
        // 不能直接拿列表第一条：收藏走的是线路详情接口，车是按出场顺序给的，第一辆可能是刚出场的头班车
        // （还有几十站，算出「89 分钟」），和线路页「最近那辆」的 7 分钟对不上（issue #33）。
        // 这里和线路页 / 小组件用同一个口径：开到本站之前的车里「站序 + 站间进度」最大的那辆。
        val bus = ArrivalText.nearestBus(status.targetOrder, status.approachingBuses)
            ?: status.approachingBuses.firstOrNull()
        if (bus != null) {
            val state = ArrivalText.stateForBus(status.targetOrder, bus)
            // 首页不展示静态轮询 ETA；到站状态保留，后车若只有站序也保留其站数。
            val homepageState = when (state) {
                is ArrivalState.OnTheWay -> state.copy(minutes = null)
                is ArrivalState.Arriving -> state.copy(nextBusMinutes = null)
                else -> state
            }
            val display = format(homepageState)
            return when (homepageState) {
                is ArrivalState.OnTheWay -> when (val stopsAway = homepageState.stopsAway) {
                    null -> display.copy(
                        detail = "",
                        short = "在路上"
                    )
                    else -> if (stopsAway > 0) {
                        display.copy(
                            value = "还有 $stopsAway",
                            unit = "站",
                            detail = "",
                            short = "还有 $stopsAway 站"
                        )
                    } else {
                        // 剩 0 站（未到站）：不再改写，直接用 ArrivalText 的「即将到站」
                        display
                    }
                }
                else -> display
            }
        }
        val next = status.preArrivalTime?.takeIf { nextDepartureIsFromStart && ArrivalText.parseClock(it) != null }
        if (next != null && !isEndedText(status.statusText)) {
            return format(
                ArrivalState.AllPassed(nextDeparture = next, startStop = status.line.startStop, lastTime = status.line.lastTime)
            )
        }
        val text = status.statusText.trim()
        if (text.isNotEmpty()) {
            val tone = if (isEndedText(text)) ArrivalTone.Warn else ArrivalTone.Mute
            // 数据源的状态字照写照读
            return ArrivalDisplay(
                value = text,
                unit = null,
                detail = "",
                short = text,
                tone = tone,
                spoken = format(ArrivalState.NoService(text)).spoken
            )
        }
        return format(ArrivalState.NoService("暂无在途车辆"))
    }

    private fun isEndedText(text: String): Boolean =
        END_WORDS.any { text.contains(it) }

    private val END_WORDS = listOf("末班已过", "停运", "不在运营", "未开通", "暂停")

    // ---- 小工具 ----

    /**
     * 首页卡片的方向只写「→ 终点」，不写始发站：掌上公交的始发站名很长，和到站文字挤一行放不下。
     * 没有终点时退回始发站。
     */
    fun terminalText(end: String, start: String = ""): String {
        val e = end.trim()
        return if (e.isNotEmpty()) "→ $e" else start.trim()
    }

    /** 「始发 → 终点」；没有始发站时只写「→ 终点」（路线标题用，首页卡片用 [terminalText]） */
    fun directionText(start: String, end: String): String {
        val s = start.trim()
        val e = end.trim()
        return when {
            s.isNotEmpty() && e.isNotEmpty() -> "$s → $e"
            e.isNotEmpty() -> "→ $e"
            else -> s
        }
    }

    /** 用「 · 」连起非空的几段 */
    fun joinDot(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" · ")

    private fun <T : Any> reuseList(old: List<T>, new: List<T>, key: (T) -> String): List<T> {
        if (old == new) return old
        val oldByKey = old.associateBy(key)
        return new.map { item -> oldByKey[key(item)]?.takeIf { it == item } ?: item }
    }
}
