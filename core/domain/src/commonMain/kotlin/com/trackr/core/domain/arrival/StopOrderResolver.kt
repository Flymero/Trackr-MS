package com.trackr.core.domain.arrival

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusStop

/**
 * 站序核对（issue #41「出现 900 站」）。
 *
 * 有的接口给的站序不可靠：掌上公交站点接口（CMD=105）的 stationOrder 恒为 0，只能拿 stationID 推站序，
 * 而有的城市 stationID 是真正的站点编号（如 899），推出来的「第 900 站」比全线站数还大，
 * 页面和通知里就成了「还有 9xx 站」。拿到线路站表后用站名核对一遍。
 */
object StopOrderResolver {

    /**
     * 站表里名字叫 [stopName] 的那一站的站序。
     *
     * - [order] 那一站就叫这个名字：原样返回 [order]
     * - 同名站有好几个（环线去程 / 回程各经过一次）：有坐标时取离 ([lat], [lng]) 最近的，否则取站序离 [order] 最近的
     * - 站表里没有这个名字（或 [stopName] 为空）：返回 null，调用方自己决定保留还是丢弃
     */
    fun resolve(
        stops: List<BusStop>,
        order: Int,
        stopName: String,
        lat: Double = 0.0,
        lng: Double = 0.0
    ): Int? {
        val wanted = normalize(stopName)
        if (wanted.isEmpty()) return null
        val sameName = stops.filter { normalize(it.name) == wanted }
        if (sameName.isEmpty()) return null
        if (sameName.any { it.order == order }) return order
        if (sameName.size == 1) return sameName.single().order
        if (lat > 0.0 && lng > 0.0) {
            sameName
                .mapNotNull { stop ->
                    RouteProjectionMath.calculateDistanceMeters(lat, lng, stop.wgsLat, stop.wgsLng)?.let { stop to it }
                }
                .minByOrNull { it.second }
                ?.let { return it.first.order }
        }
        return sameName.minBy { kotlin.math.abs(it.order - order) }.order
    }

    /**
     * 已经存下来的站序（追踪会话、收藏）要不要改：要改时返回新站序，不用改 / 改不了时返回 null。
     *
     * @param nameMismatchIsSuspect 站序在站表范围里、但那一站名字不对时也按站名改。掌上公交传 true
     *   （它的站序是推出来的）；车来了的站序本身可靠，只在超出站表时才改
     */
    fun correctedOrder(
        stops: List<BusStop>,
        order: Int,
        stopName: String,
        lat: Double = 0.0,
        lng: Double = 0.0,
        nameMismatchIsSuspect: Boolean
    ): Int? {
        if (stops.isEmpty()) return null
        if (isInRange(stops, order) && !nameMismatchIsSuspect) return null
        val resolved = resolve(stops, order, stopName, lat, lng) ?: return null
        return resolved.takeIf { it != order }
    }

    /** [order] 在不在站表的站序范围里（站表为空时不知道，当作在） */
    fun isInRange(stops: List<BusStop>, order: Int): Boolean {
        if (stops.isEmpty()) return true
        return stops.any { it.order == order }
    }

    private fun normalize(name: String): String = name.filterNot { it.isWhitespace() }
}
