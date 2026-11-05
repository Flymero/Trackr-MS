package com.trackr.core.domain.arrival

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * 到站文字的颜色（v3「到站数字怎么上色」）：
 * - [Go] 绿：已到站 / ≤ 1 站 / ≤ 3 分钟
 * - [Normal] 正文色：在路上
 * - [Mute] 灰：等待发车（车都过了）、还没发车、加载中、没网
 * - [Warn] 橙：今天末班已过、停运 / 没数据
 */
enum class ArrivalTone { Go, Normal, Mute, Warn }

/**
 * 到站大字的 8 种状态（v3「到站大字的 8 种状态」）。
 * 首页行、线路页、追踪卡、通知、小组件都用这一套。
 */
sealed interface ArrivalState {

    /** 还没拿到实时数据 */
    data object Loading : ArrivalState

    /**
     * 在路上。
     * @param stopsAway 还有几站；拿不到站序时为 null
     * @param minutes 还有几分钟；没有 ETA 时为 null（不按站数瞎估）
     */
    data class OnTheWay(val stopsAway: Int?, val minutes: Int?) : ArrivalState

    /**
     * 快到 / 已到（按 [ArrivalRules] 判：已停靠，或 ETA 在 1..45 秒）。
     * @param nextBusMinutes 后面那辆车还有几分钟
     * @param nextBusStops 后面那辆车还有几站（没有分钟时用）
     */
    data class Arriving(val nextBusMinutes: Int? = null, val nextBusStops: Int? = null) : ArrivalState

    /**
     * 车都过了，今天还有车。只给下一班始发发车时间，不估到站。
     * @param nextDeparture 下一班从始发站发车的时间（HH:mm）；拿不到为 null
     */
    data class AllPassed(
        val nextDeparture: String?,
        val startStop: String,
        val lastTime: String
    ) : ArrivalState

    /** 还没发车（首班前） */
    data class NotStarted(
        val firstTime: String,
        val nextDeparture: String?,
        val startStop: String
    ) : ArrivalState

    /** 今天末班已过 */
    data class Ended(val firstTime: String, val lastTime: String) : ArrivalState

    /**
     * 停运 / 无数据。
     * @param headline 大字（如「暂停运营」「暂无在途车辆」「未开通实时」）
     * @param detail 小字；空串表示不显示
     */
    data class NoService(val headline: String, val detail: String = "") : ArrivalState

    /**
     * 没网。
     * @param lastMinutes 上次拿到的分钟数；没有为 null
     * @param ageMinutes 上次数据是几分钟前的；不知道为 null
     */
    data class Offline(val lastMinutes: Int? = null, val ageMinutes: Int? = null) : ArrivalState
}

/**
 * 一条到站文字。
 *
 * @param value 大字的主体（「8」「到站了」「下一班 07:20」）
 * @param unit 大字后面的小单位（「分钟」「站」）；没有为 null
 * @param detail 大字下面的小字
 * @param short 放进一行的短文字（徽标、小组件、通知正文）
 * @param tone 大字颜色
 * @param detailIsWarning 小字是否要用 warn 色（没网时）
 * @param spoken 读给我听的整句
 */
data class ArrivalDisplay(
    val value: String,
    val unit: String?,
    val detail: String,
    val short: String,
    val tone: ArrivalTone,
    val detailIsWarning: Boolean = false,
    val spoken: String
) {
    /** 大字整句：「8 分钟」「到站了」 */
    val headline: String get() = if (unit == null) value else "$value $unit"
}

/**
 * 到站文字统一入口（纯函数，只依赖 kotlinx-datetime 和 kotlin 标准库，第 7 步原样搬进 :core:domain）。
 *
 * 两步：[stateFor] 由实时数据判出状态，[format] 把状态变成文字。
 * 「快到 / 已到」只认 [ArrivalRules]：ETA 1..45 秒，坐标缺失时不信站间插值。
 */
object ArrivalText {

    /** ≤ 这么多分钟用绿色 */
    const val GO_MINUTES = 3

    /** ≤ 这么多站用绿色 */
    const val GO_STOPS = 1

    /** ETA 秒数换成分钟（四舍五入，至少 1 分钟）；没有 ETA（<= 0）返回 null */
    fun minutesOf(etaSeconds: Int): Int? =
        if (etaSeconds > 0) (etaSeconds / 60.0).roundToInt().coerceAtLeast(1) else null

    /** 「约 8 分钟」；没有 ETA 返回 null（到站分钟各数据源都是估算口径，统一带「约」） */
    fun minutesText(etaSeconds: Int): String? = minutesOf(etaSeconds)?.let { "约 $it 分钟" }

    /** 选出离 [targetOrder] 最近、还没开过去的车（按 order + segmentProgress，和各页面口径一致） */
    fun nearestBus(targetOrder: Int, buses: List<LiveBus>): LiveBus? =
        buses.filter { it.order <= targetOrder }.maxByOrNull { it.order + it.segmentProgress }

    /**
     * 由一辆最近的车判状态（首页、站点页的行只有这一辆）。
     * 拿不到站序（[targetOrder] 或车的 order <= 0）时只看到站标志和 ETA。
     */
    fun stateForBus(targetOrder: Int, bus: LiveBus): ArrivalState {
        if (targetOrder <= 0 || bus.order <= 0) {
            return if (ArrivalRules.isBusStopped(bus) || ArrivalRules.isEtaImminent(bus.etaSeconds)) {
                ArrivalState.Arriving()
            } else {
                ArrivalState.OnTheWay(stopsAway = null, minutes = minutesOf(bus.etaSeconds))
            }
        }
        // 已开过目标站（两次轮询之间错过停靠）也算到站，由 ArrivalRules 判
        return if (ArrivalRules.isBusArrivedAtOrder(targetOrder, bus)) {
            ArrivalState.Arriving()
        } else {
            ArrivalState.OnTheWay(stopsAway = bus.stopsTo(targetOrder), minutes = minutesOf(bus.etaSeconds))
        }
    }

    /**
     * 由追踪会话判状态（通知、追踪卡）：还没选到车为 [ArrivalState.Loading]，
     * 到站按 [ArrivalRules.isSessionArrived]。
     */
    fun stateForSession(session: TrackingSessionState): ArrivalState = when {
        session.stopsRemaining < 0 -> ArrivalState.Loading
        ArrivalRules.isSessionArrived(session) -> ArrivalState.Arriving()
        else -> ArrivalState.OnTheWay(stopsAway = session.stopsRemaining, minutes = minutesOf(session.etaSeconds))
    }

    /**
     * 由整条线的实时数据判状态（线路页、小组件）。
     *
     * @param now 现在的本地时刻；给了才会在「车都过了、没有下一班、已过末班」时判成 [ArrivalState.Ended]
     */
    fun stateFor(
        targetOrder: Int,
        buses: List<LiveBus>,
        operatingState: LineOperatingState = LineOperatingState.RUNNING,
        startStop: String = "",
        firstTime: String = "",
        lastTime: String = "",
        nextDeparture: String? = null,
        statusDescription: String = "",
        now: LocalTime? = null
    ): ArrivalState {
        val next = nextDeparture?.takeIf { it.isNotBlank() }
        val nearest = nearestBus(targetOrder, buses)
        if (nearest != null) {
            val state = stateForBus(targetOrder, nearest)
            if (state !is ArrivalState.Arriving) return state
            val following = nearestBus(targetOrder, buses - nearest)
            return if (following == null) {
                state
            } else {
                ArrivalState.Arriving(
                    nextBusMinutes = minutesOf(following.etaSeconds),
                    nextBusStops = following.stopsTo(targetOrder).takeIf { targetOrder > 0 && following.order > 0 }
                )
            }
        }
        return when (operatingState) {
            LineOperatingState.ENDED -> ArrivalState.Ended(firstTime, lastTime)
            LineOperatingState.WAITING -> ArrivalState.NotStarted(firstTime, next, startStop)
            LineOperatingState.SUSPENDED ->
                ArrivalState.NoService("暂停运营", statusDescription.ifBlank { "暂无发车计划" })
            LineOperatingState.NO_REALTIME ->
                ArrivalState.NoService("未开通实时", operatingHoursText(firstTime, lastTime))
            LineOperatingState.RUNNING -> when {
                next != null -> ArrivalState.AllPassed(next, startStop, lastTime)
                buses.isNotEmpty() ->
                    if (isPastLastBus(now, firstTime, lastTime)) {
                        ArrivalState.Ended(firstTime, lastTime)
                    } else {
                        ArrivalState.AllPassed(null, startStop, lastTime)
                    }
                else -> ArrivalState.NoService("暂无在途车辆", operatingHoursText(firstTime, lastTime))
            }
        }
    }

    /**
     * 「读给我听」的整句（长辈版设计稿「朗读的句子」）：和屏幕大字同一套状态，时刻读成「7点20分」，
     * 线路号补「路」、站名补「站」，如「325路，开往科技园，还有 2 站，大约 4 分钟到科技园站」。
     *
     * @param lineName 线路名
     * @param stopName 候车站名
     * @param towards 开往哪里（终点站，和车身牌子一样）；空串不读
     */
    fun spoken(state: ArrivalState, lineName: String = "", stopName: String = "", towards: String = ""): String =
        format(state, lineName = lineName, stopName = stopName, towards = towards).spoken

    /**
     * 状态变成文字。
     * @param lineName 线路名（只用于朗读）
     * @param stopName 候车站名（只用于朗读）
     * @param towards 开往哪里（只用于朗读）
     */
    fun format(state: ArrivalState, lineName: String = "", stopName: String = "", towards: String = ""): ArrivalDisplay {
        val who = listOfNotNull(
            lineName.trim().takeIf { it.isNotEmpty() }?.let(::spokenLine),
            towards.trim().takeIf { it.isNotEmpty() }?.let { "开往$it" }
        ).joinToString("") { "$it，" }
        val toStop = stopName.trim().takeIf { it.isNotEmpty() }?.let { "到${spokenStop(it)}" }.orEmpty()
        return when (state) {
            ArrivalState.Loading -> ArrivalDisplay(
                value = "—",
                unit = "分钟",
                detail = "正在查车…",
                short = "正在查车…",
                tone = ArrivalTone.Mute,
                spoken = "${who}正在查车"
            )

            is ArrivalState.OnTheWay -> formatOnTheWay(state, who, toStop)

            is ArrivalState.Arriving -> {
                val nextPart = state.nextBusMinutes?.let { "下一辆 约 $it 分钟" }
                    ?: state.nextBusStops?.let { "下一辆还有 $it 站" }
                ArrivalDisplay(
                    value = "到站了",
                    unit = null,
                    detail = listOfNotNull("快去上车", nextPart).joinToString(" · "),
                    short = listOfNotNull("到站了", nextPart).joinToString(" · "),
                    tone = ArrivalTone.Go,
                    spoken = "${who}车${toStop.ifEmpty { "到站" }}了，快去上车" +
                        (nextPart?.let { "，$it" } ?: "")
                )
            }

            is ArrivalState.AllPassed -> {
                val next = state.nextDeparture?.let(::clockText)
                val from = fromStartText(state.startStop)
                if (next != null) {
                    ArrivalDisplay(
                        value = "下一班 $next",
                        unit = null,
                        detail = from,
                        short = "下一班 $next $from",
                        tone = ArrivalTone.Mute,
                        spoken = "${who}等待发车，下一班 ${spokenClock(state.nextDeparture)} $from"
                    )
                } else {
                    val last = state.lastTime.takeIf { it.isNotBlank() }?.let(::clockText)
                    val detail = listOfNotNull("暂无下一班发车时间", last?.let { "末班 $it" }).joinToString(" · ")
                    ArrivalDisplay(
                        value = "等待发车",
                        unit = null,
                        detail = detail,
                        short = listOfNotNull("等待发车", last?.let { "末班 $it" }).joinToString(" · "),
                        tone = ArrivalTone.Mute,
                        spoken = "${who}等待发车，暂无下一班发车时间" +
                            (state.lastTime.takeIf { it.isNotBlank() }?.let { "，末班 ${spokenClock(it)}" } ?: "")
                    )
                }
            }

            is ArrivalState.NotStarted -> {
                val next = state.nextDeparture?.let(::clockText)
                val first = state.firstTime.takeIf { it.isNotBlank() }?.let(::clockText)
                val value = when {
                    next != null -> "下一班 $next"
                    first != null -> "首班 $first"
                    else -> "还没发车"
                }
                val from = if (next != null || first != null) fromStartText(state.startStop) else "暂无发车时间"
                val spokenValue = when {
                    next != null -> "下一班 ${spokenClock(state.nextDeparture)}"
                    first != null -> "首班 ${spokenClock(state.firstTime)}"
                    else -> "还没发车"
                }
                ArrivalDisplay(
                    value = value,
                    unit = null,
                    detail = from,
                    short = "$value $from",
                    tone = ArrivalTone.Mute,
                    spoken = if (spokenValue == "还没发车") "${who}还没发车，$from" else "${who}还没发车，$spokenValue $from"
                )
            }

            is ArrivalState.Ended -> {
                val first = state.firstTime.takeIf { it.isNotBlank() }?.let(::clockText)
                val last = state.lastTime.takeIf { it.isNotBlank() }?.let(::clockText)
                val detail = when {
                    first != null -> "明天首班 $first"
                    last != null -> "末班 $last 已过"
                    else -> "末班已过"
                }
                val spokenDetail = when {
                    first != null -> "明天首班 ${spokenClock(state.firstTime)}"
                    last != null -> "末班 ${spokenClock(state.lastTime)} 已过"
                    else -> "末班已过"
                }
                ArrivalDisplay(
                    value = "今天没车了",
                    unit = null,
                    detail = detail,
                    short = "今天没车了 · $detail",
                    tone = ArrivalTone.Warn,
                    spoken = "${who}今天没车了，$spokenDetail"
                )
            }

            is ArrivalState.NoService -> ArrivalDisplay(
                value = state.headline,
                unit = null,
                detail = state.detail,
                short = listOf(state.headline, state.detail).filter { it.isNotBlank() }.joinToString(" · "),
                tone = ArrivalTone.Warn,
                spoken = "$who${state.headline}"
            )

            is ArrivalState.Offline -> {
                val detail = state.ageMinutes?.let { "没网，显示的是 ${it.coerceAtLeast(1)} 分钟前" } ?: "没网，连上后自动刷新"
                ArrivalDisplay(
                    value = state.lastMinutes?.toString() ?: "—",
                    unit = "分钟",
                    detail = detail,
                    short = state.lastMinutes?.let { "$it 分钟 · $detail" } ?: detail,
                    tone = ArrivalTone.Mute,
                    detailIsWarning = true,
                    spoken = state.lastMinutes?.let { "${who}没网，上次查到大约 $it 分钟${toStop.ifEmpty { "到站" }}" }
                        ?: "${who}没网，查不到车"
                )
            }
        }
    }

    private fun formatOnTheWay(state: ArrivalState.OnTheWay, who: String, toStop: String): ArrivalDisplay {
        val stops = state.stopsAway
        val minutes = state.minutes
        val stopsText = when {
            stops == null -> null
            stops <= 0 -> "下一站就到"
            else -> "还有 $stops 站"
        }
        val isGo = (stops != null && stops <= GO_STOPS) || (minutes != null && minutes <= GO_MINUTES)
        val tone = if (isGo) ArrivalTone.Go else ArrivalTone.Normal
        val spokenStops = stopsText?.let { "$it，" }.orEmpty()
        return when {
            minutes != null -> ArrivalDisplay(
                value = "约 $minutes",
                unit = "分钟",
                detail = stopsText.orEmpty(),
                short = listOfNotNull("约 $minutes 分钟", stopsText).joinToString(" · "),
                tone = tone,
                spoken = "$who${spokenStops}大约 $minutes 分钟${toStop.ifEmpty { "到站" }}"
            )
            stops != null && stops > 0 -> ArrivalDisplay(
                value = stops.toString(),
                unit = "站",
                detail = "暂无到站时间",
                short = "还有 $stops 站 · 暂无到站时间",
                tone = tone,
                spoken = "${who}还有 $stops 站${toStop.ifEmpty { "到站" }}"
            )
            stops != null -> ArrivalDisplay(
                value = "下一站",
                unit = null,
                detail = "暂无到站时间",
                short = "下一站就到",
                tone = tone,
                spoken = "${who}下一站就${toStop.ifEmpty { "到" }}"
            )
            else -> ArrivalDisplay(
                value = "在路上",
                unit = null,
                detail = "暂无到站时间",
                short = "在路上 · 暂无到站时间",
                tone = tone,
                spoken = "${who}车在路上，暂无到站时间"
            )
        }
    }

    /** 朗读用的线路名：数字结尾的补「路」（「325」读成「325路」，「M194」读成「M194路」，「地铁1号线」不动） */
    private fun spokenLine(name: String): String = if (name.last().isDigit()) "${name}路" else name

    /** 朗读用的站名：不以「站」结尾就补上（「科技园」读成「科技园站」） */
    private fun spokenStop(name: String): String = if (name.endsWith("站")) name else "${name}站"

    /** 朗读用的时刻：「07:20」读成「7点20分」，整点「7点」；认不出原样返回 */
    fun spokenClock(raw: String): String {
        val time = parseClock(raw) ?: return raw.trim()
        return if (time.minute == 0) "${time.hour}点" else "${time.hour}点${time.minute}分"
    }

    private fun fromStartText(startStop: String): String =
        startStop.trim().takeIf { it.isNotEmpty() }?.let { "从 $it 发车" } ?: "从始发站发车"

    private fun operatingHoursText(firstTime: String, lastTime: String): String {
        val first = firstTime.takeIf { it.isNotBlank() }?.let(::clockText)
        val last = lastTime.takeIf { it.isNotBlank() }?.let(::clockText)
        return if (first != null && last != null) "运营 $first–$last" else ""
    }

    /** 「5:30」「05:30:00」统一成「05:30」；认不出原样返回 */
    fun clockText(raw: String): String {
        val time = parseClock(raw) ?: return raw.trim()
        return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    }

    /** 解析「H:mm」「HH:mm」「HH:mm:ss」；认不出返回 null */
    fun parseClock(raw: String): LocalTime? {
        val parts = raw.trim().split(':')
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime(hour, minute)
    }

    /**
     * 是否已过末班：[now] 晚于末班时刻。
     * 末班早于首班（跨午夜的线）或时间认不出时不判，返回 false。
     */
    fun isPastLastBus(now: LocalTime?, firstTime: String, lastTime: String): Boolean {
        if (now == null) return false
        val last = parseClock(lastTime) ?: return false
        val first = parseClock(firstTime)
        if (first != null && last < first) return false
        return now > last
    }

    /** 毫秒时间戳换成本地时刻 */
    fun localTimeOf(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalTime =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).time
}
