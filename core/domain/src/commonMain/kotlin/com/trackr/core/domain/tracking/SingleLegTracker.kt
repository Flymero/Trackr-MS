package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState

/**
 * 单线路（非分组）候车追踪的纯函数核心：选车 + 提醒编排。
 *
 * 规则：
 * - 只考虑未开过目标站的车（order <= targetOrder），优先沿用正在追踪的车，否则取离目标站最近的一辆。
 * - 正在追踪的车短暂从数据里消失时，保持原车 [MAX_MISSING_POLLS] 轮，不立刻换车。
 * - 正在追踪的车已开过目标站或消失超过上限时换车；换车时重置提醒触发标志，下一辆车照常提醒。
 */
internal object SingleLegTracker {

    /** 追踪车辆短暂消失时最多保持原车的轮询次数 */
    const val MAX_MISSING_POLLS = 2

    /** 单线路提醒类型 */
    enum class AlertKind { APPROACHING, ARRIVED }

    /**
     * 单次轮询结果。
     * @param state 更新后的会话状态（已更新提醒触发标志）
     * @param alert 本轮要发的提醒，null 表示不发
     * @param alertState 发提醒时用的状态
     */
    data class SingleUpdate(
        val state: TrackingSessionState,
        val alert: AlertKind? = null,
        val alertState: TrackingSessionState? = null
    )

    /**
     * 处理一次单线路轮询。
     *
     * @param session 当前会话状态（legPhase == SINGLE）
     * @param buses 本次实时数据中该线路的在途车辆
     * @param operatingState 线路运营状态（无车时用于文案）
     * @param statusDescription 上游运营状态描述（无车时用于文案）
     * @param nowMillis 当前墙钟时刻（毫秒），写进 `updatedAtMillis`
     */
    fun processPoll(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        operatingState: LineOperatingState = LineOperatingState.RUNNING,
        statusDescription: String = "",
        nowMillis: Long
    ): SingleUpdate {
        val targetOrder = session.targetOrder
        val trackedId = session.currentBusId

        // 1. 正在追踪的车短暂消失：保持原车一两轮
        if (trackedId != null && buses.none { it.busId == trackedId } &&
            session.missingBusPolls < MAX_MISSING_POLLS
        ) {
            return SingleUpdate(
                session.copy(
                    missingBusPolls = session.missingBusPolls + 1,
                    statusSummary = "车辆信号短暂丢失，保持上次位置（距 ${session.targetStopName}）",
                    updatedAtMillis = nowMillis
                )
            )
        }

        // 2. 选车：只看未过站车辆，优先沿用正在追踪的车
        val candidates = buses.filter { it.order <= targetOrder }
        val tracked = trackedId?.let { id -> candidates.find { it.busId == id } }
        val best = tracked ?: candidates.maxByOrNull { it.order.toFloat() + it.segmentProgress }

        if (best == null) {
            val summary = when {
                operatingState != LineOperatingState.RUNNING ->
                    statusDescription.ifEmpty { operatingState.defaultLabel }
                buses.isNotEmpty() -> "车辆均已过站，等待下一班车调度"
                else -> "暂无在途车辆运营数据"
            }
            return SingleUpdate(
                session.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    isBusArrivedAtStop = false,
                    missingBusPolls = 0,
                    // 原车已过站 / 消失：下一辆车出现时重新提醒
                    hasTriggeredApproachingAlert = false,
                    hasTriggeredArrivedAlert = false,
                    statusSummary = summary,
                    updatedAtMillis = nowMillis
                )
            )
        }

        val isNewVehicle = trackedId == null || trackedId != best.busId
        val stopsRemaining = best.stopsTo(targetOrder).coerceAtLeast(0)
        val isStoppedAtTarget = stopsRemaining == 0 && ArrivalRules.isBusStopped(best)
        val isArrivedCondition = ArrivalRules.isBusArrivedAtOrder(targetOrder, best)

        // 换车或初次锁定：重新确定起始追踪基准站
        val startOrder = if (isNewVehicle) {
            val base = if (ArrivalRules.isBusStopped(best)) best.order else best.order - 1
            base.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
        } else {
            session.startTrackingOrder
        }

        // 换车时重置提醒触发标志
        var hasApproaching = if (isNewVehicle) false else session.hasTriggeredApproachingAlert
        var hasArrived = if (isNewVehicle) false else session.hasTriggeredArrivedAlert

        val etaFormatted = RouteProjectionMath.formatEtaText(best.etaSeconds, isStoppedAtTarget)
        val statusSummary = when {
            isStoppedAtTarget -> "车辆已到达 ${session.targetStopName}！"
            stopsRemaining == 0 -> "车辆即将进站 ${session.targetStopName}"
            best.etaSeconds > 0 -> "距 ${session.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
            else -> "距 ${session.targetStopName} 还有 $stopsRemaining 站"
        }

        val baseState = session.copy(
            startTrackingOrder = startOrder,
            currentBusId = best.busId,
            currentBusOrder = best.order,
            currentBusProgress = best.segmentProgress,
            isBusArrivedAtStop = isStoppedAtTarget,
            stopsRemaining = stopsRemaining,
            etaSeconds = best.etaSeconds,
            expectedArrivalTime = best.expectedArrivalTimeText,
            missingBusPolls = 0,
            statusSummary = statusSummary,
            updatedAtMillis = nowMillis
        )

        var alert: AlertKind? = null
        var alertState: TrackingSessionState? = null
        if (isArrivedCondition && !hasArrived) {
            alert = AlertKind.ARRIVED
            alertState = baseState.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
            hasArrived = true
            hasApproaching = true
        } else if (!isArrivedCondition && stopsRemaining <= session.alertThresholdStops && !hasApproaching) {
            alert = AlertKind.APPROACHING
            alertState = baseState
            hasApproaching = true
        }

        return SingleUpdate(
            baseState.copy(
                hasTriggeredApproachingAlert = hasApproaching,
                hasTriggeredArrivedAlert = hasArrived
            ),
            alert,
            alertState
        )
    }
}
