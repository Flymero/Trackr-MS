@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.app.platform

import com.trackr.app.publishIosSnapshot
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.model.*
import com.trackr.core.navigation.deeplink.DeepLinkUris
import platform.Foundation.*

internal fun FavoriteItem.destination(): String = when (this) {
    is FavoriteItem.Stop -> DeepLinkUris.line(stop.cityId, stop.lineId, stop.source, stop.targetStopId, stop.targetOrder, stop.lineName)
    is FavoriteItem.Route -> DeepLinkUris.route(route.id)
    is FavoriteItem.Station -> DeepLinkUris.stop(station.cityId, station.stopId, station.stopName)
}

internal data class IosFavoriteSnapshot(val updatedAt: Long = 0L, val stops: Map<String, NearbyStop> = emptyMap())

internal fun publishSnapshot(favorites: List<FavoriteItem>, sessions: List<TrackingSession>, liveEnabled: Boolean,
    favoriteSnapshot: IosFavoriteSnapshot, active: Boolean) {
    fun sessionItem(session: TrackingSession): Map<String, Any> {
        val state = session.state
        val link = if (session.boardChoice != null) DeepLinkUris.chooseBoardedBus(session.sessionId)
            else DeepLinkUris.line(session.leg.cityId, state.lineId, session.leg.source, state.targetStopId, state.targetOrder, state.lineName)
        return mapOf("id" to session.sessionId, "title" to state.lineName, "subtitle" to state.targetStopName,
            "arrival" to if (session.refreshFailed) "刷新失败，请打开重试" else ArrivalText.format(ArrivalText.stateForSession(state)).short,
            "url" to link, "updatedAt" to session.lastRealtimeAtMillis / 1000.0, "paused" to session.interrupted)
    }
    val rows = favorites.take(12).map { favorite ->
        val match = sessions.firstOrNull { session -> when (favorite) {
            is FavoriteItem.Stop -> session.leg.lineId == favorite.stop.lineId && session.state.targetStopId == favorite.stop.targetStopId
            is FavoriteItem.Route -> (session.target as? TrackingTarget.Route)?.group?.id == favorite.route.id
            is FavoriteItem.Station -> false
        } }
        val recent = when (favorite) {
            is FavoriteItem.Stop -> favoriteSnapshot.stops[favorite.stop.id]?.lines?.firstOrNull()
            // 收藏的站：有车在路上的线优先（站点接口的线不按到站排）
            is FavoriteItem.Station -> favoriteSnapshot.stops[favorite.key]?.lines?.let { lines ->
                lines.firstOrNull { it.approachingBuses.isNotEmpty() } ?: lines.firstOrNull()
            }
            is FavoriteItem.Route -> null
        }
        val recentBase: Map<String, Any> = recent?.let {
            mapOf("arrival" to ArrivalText.format(ArrivalText.stateFor(it.targetOrder, it.approachingBuses,
                statusDescription = it.statusText, nextDeparture = it.preArrivalTime)).short,
                "updatedAt" to favoriteSnapshot.updatedAt / 1000.0, "paused" to !active)
        }.orEmpty()
        val base = match?.let(::sessionItem) ?: recentBase
        mapOf("id" to favorite.key, "title" to when (favorite) {
            is FavoriteItem.Stop -> favorite.stop.lineName
            is FavoriteItem.Route -> favorite.route.name
            is FavoriteItem.Station -> favorite.station.stopName
        }, "subtitle" to when (favorite) {
            is FavoriteItem.Stop -> favorite.stop.targetStopName
            is FavoriteItem.Route -> "收藏路线"
            is FavoriteItem.Station -> listOf("收藏的站", favorite.station.platformLabel).filter { it.isNotBlank() }.joinToString(" · ")
        }, "arrival" to (base["arrival"] ?: "打开查看到站"),
            "updatedAt" to (base["updatedAt"] ?: 0.0), "paused" to (base["paused"] ?: true),
            "url" to (NSURLComponents().apply { scheme = "trackr"; host = "favorite"; path = "/${favorite.key}" }.URL?.absoluteString ?: "trackr://"))
    }
    val data = NSJSONSerialization.dataWithJSONObject(mapOf("version" to 1,
        "favorites" to rows, "sessions" to if (liveEnabled) sessions.map(::sessionItem) else emptyList<Map<String, Any>>()), 0u, null) ?: return
    publishIosSnapshot?.invoke(NSString.create(data, NSUTF8StringEncoding).toString())
}
