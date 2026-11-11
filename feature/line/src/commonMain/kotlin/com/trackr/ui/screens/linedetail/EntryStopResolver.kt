package com.trackr.ui.screens.linedetail

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusStop

/**
 * 按站名找进线路页时要停的候车站（路线规划 / 路线详情点线路进来）。
 *
 * - 上车站按站名匹配（同 RouteAlignmentService：去掉括号后的核心名互相包含），全名相同的排前面；
 * - 给了下车站名、站表里也找得到它时，上车站必须在下车站前面，否则说明方向反了，返回 null；
 * - 同名站不止一个时优先站序等于 [preferredOrder] 的。
 *
 * @return null = 这个方向找不到（调用方再试反向，或退回默认候车站）
 */
internal fun resolveEntryStop(
    stops: List<BusStop>,
    boardStopName: String,
    alightStopName: String = "",
    preferredOrder: Int? = null
): BusStop? {
    val boards = stops.filter { matchesStopName(it.name, boardStopName) }
    if (boards.isEmpty()) return null
    val alights = stops.filter { matchesStopName(it.name, alightStopName) }
    val candidates = if (alights.isEmpty()) {
        boards
    } else {
        boards.filter { board -> alights.any { it.order > board.order } }
    }
    if (candidates.isEmpty()) return null
    val exact = candidates.filter { it.name == boardStopName }.ifEmpty { candidates }
    return exact.firstOrNull { it.order == preferredOrder } ?: exact.first()
}

private fun matchesStopName(stopName: String, wanted: String): Boolean {
    val wantedCore = RouteProjectionMath.coreStopName(wanted)
    if (wantedCore.isBlank()) return false
    val stopCore = RouteProjectionMath.coreStopName(stopName)
    if (stopCore.isBlank()) return false
    return stopName == wanted || stopCore.contains(wantedCore) || wantedCore.contains(stopCore)
}
