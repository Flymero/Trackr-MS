package com.trackr.core.data.local

import com.trackr.core.database.dao.RouteRows
import com.trackr.core.database.entity.CandidateLineEntity
import com.trackr.core.database.entity.FavoriteGroupEntity
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.database.entity.FavoriteLineEntity
import com.trackr.core.database.entity.ReminderEntity
import com.trackr.core.database.entity.RouteEntity
import com.trackr.core.database.entity.RouteLegEntity
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupColor
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * 模型 <-> 表行。每个字段都要搬：LegacyLibraryMigrationTest 用「旧 JSON 解出来的对象 == 存进库再读出来的对象」逐条核对。
 */

/** 和旧仓库一样的 JSON 设置（认不出的字段忽略、默认值也写出） */
internal val localJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // 认不出的枚举值（例如以后加的分组颜色）退回字段默认值
    coerceInputValues = true
}

private val geoPointList = ListSerializer(GeoPoint.serializer())

private fun dataSourceOf(name: String): DataSourceType =
    DataSourceType.entries.firstOrNull { it.name == name } ?: DataSourceType.CHELAILE

private fun categoryOf(name: String): GroupCategory =
    GroupCategory.entries.firstOrNull { it.name == name } ?: GroupCategory.CUSTOM

// ---- 收藏的车 ----

fun FavoriteStationLine.toEntity(position: Int) = FavoriteLineEntity(
    id = id,
    position = position,
    cityId = cityId,
    cityName = cityName,
    lineId = lineId,
    lineName = lineName,
    lineNo = lineNo,
    direction = direction,
    startStop = startStop,
    endStop = endStop,
    targetStopId = targetStopId,
    targetStopName = targetStopName,
    targetOrder = targetOrder,
    totalStops = totalStops,
    wgsLat = wgsLat,
    wgsLng = wgsLng,
    source = source.name,
    mygolbsCityCode = mygolbsCityCode,
    addedAtMillis = addedAtMillis
)

fun FavoriteLineEntity.toModel() = FavoriteStationLine(
    id = id,
    cityId = cityId,
    cityName = cityName,
    lineId = lineId,
    lineName = lineName,
    lineNo = lineNo,
    direction = direction,
    startStop = startStop,
    endStop = endStop,
    targetStopId = targetStopId,
    targetStopName = targetStopName,
    targetOrder = targetOrder,
    totalStops = totalStops,
    wgsLat = wgsLat,
    wgsLng = wgsLng,
    source = dataSourceOf(source),
    mygolbsCityCode = mygolbsCityCode,
    addedAtMillis = addedAtMillis
)

// ---- 收藏分组 ----

fun FavoriteGroup.toEntity(position: Int) = FavoriteGroupEntity(
    id = id,
    position = position,
    name = name,
    sortOrder = sortOrder,
    isDefault = isDefault,
    color = color?.name
)

fun FavoriteGroupEntity.toModel() = FavoriteGroup(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isDefault = isDefault,
    color = GroupColor.fromKey(color)
)

// ---- 收藏合一索引（第 14 步） ----

fun FavoriteItemRecord.toEntity() = FavoriteItemEntity(
    id = key,
    type = type.name,
    refId = refId,
    groupId = groupId,
    sortOrder = sortOrder
)

/** 认不出的类型（以后的版本加了新类型又回退）返回 null，这一行跳过，启动对账时会按车和路线重新补 */
fun FavoriteItemEntity.toRecordOrNull(): FavoriteItemRecord? {
    val itemType = FavoriteItemType.entries.firstOrNull { it.name == type } ?: return null
    return FavoriteItemRecord(type = itemType, refId = refId, groupId = groupId, sortOrder = sortOrder)
}

// ---- 路线 -> 程 -> 候选线 ----

fun TransitGroup.toRows(position: Int): RouteRows {
    val route = RouteEntity(
        id = id,
        position = position,
        name = name,
        category = category.name,
        isTemporary = isTemporary,
        sortOrder = sortOrder,
        customGroupId = customGroupId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
    val legRows = legs.mapIndexed { legPosition, leg ->
        RouteLegEntity(
            routeId = id,
            position = legPosition,
            legIndex = leg.legIndex,
            fromStopName = leg.fromStopName,
            fromStopId = leg.fromStopId,
            toStopName = leg.toStopName,
            toStopId = leg.toStopId,
            fromStopLat = leg.fromStopLat,
            fromStopLng = leg.fromStopLng,
            toStopLat = leg.toStopLat,
            toStopLng = leg.toStopLng,
            walkingDistanceMeters = leg.walkingDistanceMeters,
            ridePathJson = localJson.encodeToString(geoPointList, leg.ridePath),
            walkPathBeforeJson = localJson.encodeToString(geoPointList, leg.walkPathBefore)
        )
    }
    val candidates = legs.flatMapIndexed { legPosition, leg ->
        leg.candidateLines.mapIndexed { candidatePosition, line ->
            CandidateLineEntity(
                routeId = id,
                legPosition = legPosition,
                position = candidatePosition,
                cityId = line.cityId,
                cityName = line.cityName,
                lineId = line.lineId,
                lineName = line.lineName,
                source = line.source.name,
                direction = line.direction,
                fromStopOrder = line.fromStopOrder,
                toStopOrder = line.toStopOrder,
                startStopName = line.startStopName,
                endStopName = line.endStopName,
                mygolbsCityCode = line.mygolbsCityCode
            )
        }
    }
    return RouteRows(route, legRows, candidates)
}

fun RouteRows.toModel(): TransitGroup {
    val candidatesByLeg = candidates.groupBy { it.legPosition }
    return TransitGroup(
        id = route.id,
        name = route.name,
        category = categoryOf(route.category),
        legs = legs.sortedBy { it.position }.map { leg ->
            TransitLeg(
                legIndex = leg.legIndex,
                fromStopName = leg.fromStopName,
                fromStopId = leg.fromStopId,
                toStopName = leg.toStopName,
                toStopId = leg.toStopId,
                fromStopLat = leg.fromStopLat,
                fromStopLng = leg.fromStopLng,
                toStopLat = leg.toStopLat,
                toStopLng = leg.toStopLng,
                candidateLines = candidatesByLeg[leg.position].orEmpty().sortedBy { it.position }.map { line ->
                    CandidateLine(
                        cityId = line.cityId,
                        cityName = line.cityName,
                        lineId = line.lineId,
                        lineName = line.lineName,
                        source = dataSourceOf(line.source),
                        direction = line.direction,
                        fromStopOrder = line.fromStopOrder,
                        toStopOrder = line.toStopOrder,
                        startStopName = line.startStopName,
                        endStopName = line.endStopName,
                        mygolbsCityCode = line.mygolbsCityCode
                    )
                },
                walkingDistanceMeters = leg.walkingDistanceMeters,
                ridePath = decodePath(leg.ridePathJson),
                walkPathBefore = decodePath(leg.walkPathBeforeJson)
            )
        },
        isTemporary = route.isTemporary,
        sortOrder = route.sortOrder,
        customGroupId = route.customGroupId,
        createdAt = route.createdAt,
        updatedAt = route.updatedAt
    )
}

/** 轨迹点只用来画线；万一这一列坏了，丢掉轨迹，路线本身照常显示 */
private fun decodePath(json: String): List<GeoPoint> =
    if (json.isBlank()) emptyList() else runCatching { localJson.decodeFromString(geoPointList, json) }.getOrDefault(emptyList())

// ---- 定时提醒 ----

fun ScheduledReminder.toEntity(position: Int, lastScheduledTriggerAt: Long? = null) = ReminderEntity(
    id = id,
    position = position,
    enabled = enabled,
    hour = hour,
    minute = minute,
    daysOfWeek = daysOfWeek.joinToString(","),
    cityId = cityId,
    cityName = cityName,
    lineId = lineId,
    lineName = lineName,
    direction = direction,
    endStopName = endStopName,
    targetStopId = targetStopId,
    targetStopName = targetStopName,
    targetOrder = targetOrder,
    totalStops = totalStops,
    dataSourceType = dataSourceType.name,
    mygolbsCityCode = mygolbsCityCode,
    autoStartLiveTracking = autoStartLiveTracking,
    lastScheduledTriggerAt = lastScheduledTriggerAt
)

fun ReminderEntity.toModel() = ScheduledReminder(
    id = id,
    enabled = enabled,
    hour = hour,
    minute = minute,
    // 保持写入时的顺序（LinkedHashSet），和旧 JSON 解出来的一样
    daysOfWeek = daysOfWeek.split(',').mapNotNull { it.trim().toIntOrNull() }.toCollection(LinkedHashSet()),
    cityId = cityId,
    cityName = cityName,
    lineId = lineId,
    lineName = lineName,
    direction = direction,
    endStopName = endStopName,
    targetStopId = targetStopId,
    targetStopName = targetStopName,
    targetOrder = targetOrder,
    totalStops = totalStops,
    dataSourceType = dataSourceOf(dataSourceType),
    mygolbsCityCode = mygolbsCityCode,
    autoStartLiveTracking = autoStartLiveTracking
)
