package com.trackr.core.data.repository

import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup

/**
 * 收藏合一索引的对账（第 14 步）。规则和库迁移 `MIGRATION_1_2` 一样：
 *
 * - 索引里有的收藏：按索引的 sortOrder 排（相同时按原来的先后）。
 * - 索引里没有的车（从没建过索引、旧存储期间新加的）：排在最前，按车列表原来的顺序；没有的站（issue #47）接在这些车后面。
 * - 索引里没有的路线：接在最后，按收藏页原来的顺序（`sortOrder`，相同时按列表里的先后）；分组见 [derivedRouteGroupId]。
 * - 指向已不存在的车 / 路线的索引行删掉；同一个 key 出现多次只留第一条。
 * - 最后 sortOrder 从 0 连续编号。
 */
object FavoriteItemOrder {

    const val COMMUTE_GROUP_ID = "commute"
    const val HOME_GROUP_ID = "home"

    fun reconcile(
        stops: List<FavoriteStationLine>,
        routes: List<TransitGroup>,
        groups: List<FavoriteGroup>,
        items: List<FavoriteItemRecord>?,
        stations: List<FavoriteStation> = emptyList()
    ): List<FavoriteItemRecord> {
        val stopKeys = stops.mapTo(HashSet()) { FavoriteItem.stopKey(it.id) }
        val routeKeys = routes.mapTo(HashSet()) { FavoriteItem.routeKey(it.id) }
        val stationKeys = stations.mapTo(HashSet()) { FavoriteItem.stationKey(it.id) }

        val seen = HashSet<String>()
        val kept = items.orEmpty()
            .withIndex()
            .sortedWith(compareBy({ it.value.sortOrder }, { it.index }))
            .map { it.value }
            .filter { record ->
                val exists = when (record.type) {
                    FavoriteItemType.STOP -> record.key in stopKeys
                    FavoriteItemType.ROUTE -> record.key in routeKeys
                    FavoriteItemType.STATION -> record.key in stationKeys
                }
                exists && seen.add(record.key)
            }

        val missingStops = stops
            .filter { FavoriteItem.stopKey(it.id) !in seen }
            .distinctBy { it.id }
            .map { FavoriteItemRecord(FavoriteItemType.STOP, it.id, groupId = null) }
        val missingStations = stations
            .filter { FavoriteItem.stationKey(it.id) !in seen }
            .distinctBy { it.id }
            .map { FavoriteItemRecord(FavoriteItemType.STATION, it.id, groupId = null) }
        val groupIds = groups.mapTo(HashSet()) { it.id }
        val missingRoutes = routes
            .withIndex()
            .sortedWith(compareBy({ it.value.sortOrder }, { it.index }))
            .map { it.value }
            .filter { FavoriteItem.routeKey(it.id) !in seen }
            .distinctBy { it.id }
            .map { FavoriteItemRecord(FavoriteItemType.ROUTE, it.id, groupId = derivedRouteGroupId(it, groupIds)) }

        return (missingStops + missingStations + kept + missingRoutes).mapIndexed { index, record -> record.copy(sortOrder = index) }
    }

    /**
     * 路线第一次进索引时的分组：有 `customGroupId` 就用它（指向已删分组的也照原样）；没有时按收藏页原来的筛选规则补
     * （`FavoritesGroupViewModel.isGroupInFavoriteGroup`：场景是上班通勤 -> commute，名字里有「返程」-> home），
     * 只在那个分组还在时才补。这样用户看到的分组归属和以前一样。
     */
    fun derivedRouteGroupId(route: TransitGroup, existingGroupIds: Set<String>): String? = when {
        route.customGroupId != null -> route.customGroupId
        route.category == GroupCategory.COMMUTE && COMMUTE_GROUP_ID in existingGroupIds -> COMMUTE_GROUP_ID
        route.name.contains("返程") && HOME_GROUP_ID in existingGroupIds -> HOME_GROUP_ID
        else -> null
    }
}
