package com.trackr.core.domain.arrival

import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState

/**
 * 到站判定的统一口径（纯函数）。
 *
 * 追踪服务、换乘引擎、常驻通知、小米焦点通知都用这里的判断，避免各写一套区间：
 * - ETA 只认 [IMMINENT_ETA_RANGE]（1..45 秒）；etaSeconds <= 0 表示“无预测/已过站”，不算到站。
 * - segmentProgress 只有在 [LiveBus.hasValidProgress] 时才可信；坐标缺失时上游默认填 1.0，不能当成已到站。
 * - 数据源明确给出停靠状态时（[LiveBus.hasAuthoritativeArrival]，如眉山官方 isArriveSite），
 *   「已到站」只认停靠标志；ETA 再短也只是「即将到站」，不用本地估算代替到站协议。
 */
object ArrivalRules {

    /** 视为“即将/已经进站”的 ETA 区间（秒） */
    val IMMINENT_ETA_RANGE: IntRange = 1..45

    /** 站间插值达到该比例视为已停靠 */
    const val STOPPED_PROGRESS_THRESHOLD = 0.98f

    /** ETA 是否落在进站区间；<=0 为无预测 */
    fun isEtaImminent(etaSeconds: Int): Boolean = etaSeconds in IMMINENT_ETA_RANGE

    /** 车辆是否已停靠在它正在接近的站（order）：上游到站标志，或可信的站间插值 >= 0.98 */
    fun isBusStopped(bus: LiveBus): Boolean =
        bus.isArrived || (!bus.hasAuthoritativeArrival && bus.hasValidProgress && bus.segmentProgress >= STOPPED_PROGRESS_THRESHOLD)

    /** 车正在接近的站已到：停靠；没有权威停靠状态时 ETA 进入进站区间也算 */
    fun isArrivingNow(bus: LiveBus): Boolean =
        isBusStopped(bus) || (!bus.hasAuthoritativeArrival && isEtaImminent(bus.etaSeconds))

    /**
     * 车辆是否到达 [targetOrder]：
     * - 已开过该站（order > targetOrder，两次轮询之间错过了停靠）视为已到达；
     * - 正在接近该站时，按 [isArrivingNow]。
     */
    fun isBusArrivedAtOrder(targetOrder: Int, bus: LiveBus): Boolean {
        if (bus.order > targetOrder) return true
        if (bus.order < targetOrder) return false
        return isArrivingNow(bus)
    }

    /** 会话层面的到站：剩 0 站且（已停靠 或 没有权威停靠状态时 ETA 在进站区间） */
    fun isSessionArrived(state: TrackingSessionState): Boolean =
        state.stopsRemaining == 0 && (
            state.isBusArrivedAtStop || (!state.hasAuthoritativeArrival && isEtaImminent(state.etaSeconds))
        )
}
