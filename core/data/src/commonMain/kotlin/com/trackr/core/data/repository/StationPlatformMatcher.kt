package com.trackr.core.data.repository

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.NearbyStop

/**
 * 收藏的站（issue #47）在站点接口结果里找回默认站台。
 *
 * 依次认：站台 id（`physicalStId`）→ 站 id → 同名站台里离收藏时坐标最近的 → 第一个。
 * 掌上公交临时改道时站号会跳到 900 多，前两步认不出，靠站名 + 坐标找回。
 */
object StationPlatformMatcher {

    fun pick(platforms: List<NearbyStop>, station: FavoriteStation): NearbyStop? {
        if (platforms.isEmpty()) return null
        station.physicalStId.takeIf { it.isNotBlank() }?.let { id ->
            platforms.firstOrNull { it.physicalStId == id }?.let { return it }
        }
        station.stopId.takeIf { it.isNotBlank() }?.let { id ->
            platforms.firstOrNull { it.stopId == id }?.let { return it }
        }
        val name = station.stopName.trim()
        val sameName = platforms.filter { it.name.trim() == name }.ifEmpty { platforms }
        if (station.wgsLat > 0.0 && station.wgsLng > 0.0) {
            sameName
                .filter { it.wgsLat > 0.0 && it.wgsLng > 0.0 }
                .minByOrNull { RouteProjectionMath.calculateDistanceMeters(station.wgsLat, station.wgsLng, it.wgsLat, it.wgsLng) ?: Int.MAX_VALUE }
                ?.let { return it }
        }
        return sameName.first()
    }
}
