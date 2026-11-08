package com.trackr.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 要追踪什么（重构计划 §2.6）。开始追踪时由 UI / 提醒传给 `TrackingController.start`，存在会话里，进程重建后据此继续。
 * 第 15 步加 `WatchTogether`（一起等多条线）。
 */
@Serializable
sealed interface TrackingTarget {

    /** 单线路：等某条线的车到某一站 */
    @Serializable
    @SerialName("single")
    data class SingleLine(
        val cityId: String,
        val lineId: String,
        val lineName: String,
        val lineNo: String = "",
        val endStopName: String = "",
        val targetStopId: String = "",
        val targetStopName: String = "",
        val targetOrder: Int = 1,
        val totalStops: Int = targetOrder,
        val alertThresholdStops: Int = 2,
        val source: DataSourceType = DataSourceType.CHELAILE,
        val mygolbsCityCode: String = "",
        val direction: Int = 0
    ) : TrackingTarget

    /**
     * 路线（分组换乘）：从第 [startLeg] 程开始，每程用 [selectedLineIds]（legIndex -> lineId）里选中的候选线，
     * 找不到时用该程第一条候选线。[group] 是开始时的整条路线（临时方案不在收藏里，只能存在这里）。
     */
    @Serializable
    @SerialName("route")
    data class Route(
        val group: TransitGroup,
        val startLeg: Int = 0,
        val selectedLineIds: Map<Int, String> = emptyMap(),
        /** 每一程车离上车站 / 下车站还有几站时提醒（第 25 步起取「我的」里的全局设置；旧会话没有这个字段 = 2） */
        val alertThresholdStops: Int = AlertThreshold.DEFAULT_STOPS
    ) : TrackingTarget

    /**
     * 一起等（第 15 步）：在同一个站等 [lines] 里任意一条线，谁先到就提醒谁，条数不设上限（Q8）。
     * 就是「只有 1 程、没有下车站」的临时路线：等车阶段每轮只请求 1 次这个站的站点接口；上车后只追坐上的那辆。
     *
     * @param stopId 上车站（站点接口用的 id）
     * @param stopLat 上车站坐标（WGS-84），0 = 不知道（判上车时改用站点接口 / 线路详情给的坐标）
     */
    @Serializable
    @SerialName("watch")
    data class WatchTogether(
        val cityId: String,
        val stopId: String,
        val stopName: String,
        val lines: List<WatchLine>,
        val cityName: String = "",
        val physicalStId: String? = null,
        val namesakeStId: String? = null,
        val stopLat: Double = 0.0,
        val stopLng: Double = 0.0,
        val mygolbsCityCode: String = "",
        val alertThresholdStops: Int = 2
    ) : TrackingTarget
}

/**
 * 一起等里的一条线（站点页勾上的一条）。
 * @param stopOrder 上车站在这条线上的站序（1 起）
 * @param stopIdOnLine 上车站在这条线上的站 id（单独请求线路接口时用）
 */
@Serializable
data class WatchLine(
    val lineId: String,
    val lineName: String,
    val stopOrder: Int,
    val endStopName: String = "",
    val direction: Int = 0,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val stopIdOnLine: String = ""
)

/**
 * 一起等等车时一条线此刻的情况（会话里的 [TrackingSession.watchedLines]，按到站先后排，第一条是主显示）。
 *
 * @param busId 离上车站最近、还没开过上车站的车；没车时为 null
 * @param stopsRemaining 这辆车离上车站还有几站，-1 = 没车
 * @param isArrived 这辆车已到上车站
 * @param hasData 最近一次请求拿到了这条线的数据（false = 某线无数据）
 * @param candidateBusId 定位判上车（Q17）：车到站、人在站附近，正在确认是否上了这辆
 * @param approachAlertedBusId 已经为哪辆车发过「快到了」提醒（换车才再提醒）
 * @param arrivedAlertedBusId 已经为哪辆车发过「到站了」提醒
 */
@Serializable
data class WatchedLine(
    val line: WatchLine,
    val busId: String? = null,
    val plate: String = "",
    val busOrder: Int = 0,
    val busProgress: Float = 0f,
    val stopsRemaining: Int = -1,
    val etaSeconds: Int = -1,
    val isArrived: Boolean = false,
    /** 数据源明确给出停靠状态：ETA 不能替代到站标志 */
    val hasAuthoritativeArrival: Boolean = false,
    val hasData: Boolean = false,
    val statusText: String = "",
    val candidateBusId: String? = null,
    val candidateSinceMillis: Long = 0L,
    val confirmStreak: Int = 0,
    val missStreak: Int = 0,
    /** 最近一次用于判上车的定位单调时钟时间；同一定位只计一次 */
    val lastFixElapsedMillis: Long = -1L,
    val approachAlertedBusId: String? = null,
    val arrivedAlertedBusId: String? = null
)

/**
 * 「上了哪辆？」：点「我上车了」时不同线的车同时在站、定位又分不清（§2.6 第 ④ 条），等用户选。
 * @param sinceMillis 写下的时刻（墙钟）；超过 5 分钟没选就清掉，回到等车（Q16 的上限）
 */
@Serializable
data class BoardChoice(
    val options: List<BoardOption>,
    val sinceMillis: Long
)

/** 「上了哪辆？」里的一个选项 */
@Serializable
data class BoardOption(
    val lineId: String,
    val lineName: String,
    val busId: String,
    val plate: String = "",
    val order: Int = 0,
    /** 选项创建时那辆车是否带权威停靠标志：上车重建会话时传下去，保持到站判定口径一致 */
    val authoritativeArrival: Boolean = false
)

/**
 * 当前这一程的轮询参数（原 `TrackingStartParams` 里和请求有关的部分）。
 * 单线路就是目标本身；路线是当前程选中的候选线。
 */
@Serializable
data class TrackingLegParams(
    val cityId: String,
    val lineId: String,
    val lineName: String,
    val targetStopId: String = "",
    val targetOrder: Int = 1,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val direction: Int = 0
)

/**
 * 一份追踪会话（`TrackingRepository` 里可以同时有多份，一起存进 `tracking_session.json`；
 * 第 36 步起同时追多项，每份各自有代际号、各自轮询、各自一条常驻通知）。
 *
 * @param sessionId 这次会话的标识，服务只收它
 * @param generation 代际号：开始 / 切程 / 停止都 +1；轮询结果回来先比对，旧的直接丢
 * @param leg 当前这一程的轮询参数
 * @param state 当前这一程的状态（通知、首页追踪卡、收藏页都读它）
 * @param interrupted 进程被杀后读回的会话，追踪服务还没接上（接上后清掉）
 * @param watchedLines 一起等等车时每条线的情况，按到站先后排（第 15 步）；单线路、路线、一起等上车后为空。
 *   一起等时 [leg] 和 [state] 写的是排第一的那条线
 * @param boardChoice 一起等「上了哪辆？」待选（第 15 步）；没有时为 null
 */
@Serializable
data class TrackingSession(
    val sessionId: String,
    val generation: Int,
    val target: TrackingTarget,
    val leg: TrackingLegParams,
    val state: TrackingSessionState,
    val interrupted: Boolean = false,
    val watchedLines: List<WatchedLine> = emptyList(),
    val boardChoice: BoardChoice? = null,
    /** Snapshot freshness is independent of commands and failed refreshes. */
    val lastRealtimeAtMillis: Long = 0L,
    val refreshFailed: Boolean = false
) {
    /** 一起等（等车或已上车）；单线路、路线为 null */
    val watchTarget: TrackingTarget.WatchTogether?
        get() = target as? TrackingTarget.WatchTogether

    /** 路线追踪时的整条路线；单线路为 null */
    val group: TransitGroup?
        get() = (target as? TrackingTarget.Route)?.group

    /** 收藏页 / 规划页用的换乘组视图；单线路为 null */
    fun toGroupSessionState(): GroupTrackingSessionState? {
        val group = group ?: return null
        val legIndex = state.currentLegIndex
        return GroupTrackingSessionState(
            groupId = group.id,
            groupName = group.name,
            activeLegIndex = legIndex,
            totalLegs = group.legs.size,
            currentLeg = group.legs.getOrNull(legIndex),
            trackingSessionState = state,
            isCompleted = false
        )
    }
}

/** 到站前提醒的站数范围（「我的」全局设置、通知上的「切换」按钮都在这个范围里） */
object AlertThreshold {
    const val MIN_STOPS: Int = 1
    const val MAX_STOPS: Int = 3
    const val DEFAULT_STOPS: Int = 2
}

/** 换成 [stops] 站提醒（夹到 [AlertThreshold] 范围内）的同一个目标 */
fun TrackingTarget.withAlertThresholdStops(stops: Int): TrackingTarget {
    val value = stops.coerceIn(AlertThreshold.MIN_STOPS, AlertThreshold.MAX_STOPS)
    return when (this) {
        is TrackingTarget.SingleLine -> copy(alertThresholdStops = value)
        is TrackingTarget.Route -> copy(alertThresholdStops = value)
        is TrackingTarget.WatchTogether -> copy(alertThresholdStops = value)
    }
}
