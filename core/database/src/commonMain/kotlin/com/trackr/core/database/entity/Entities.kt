package com.trackr.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 表结构（重构计划 §2.4）。列和 :core:model 里的模型一一对应，转换在 :core:data 的 local/Mappers.kt。
 *
 * 每张列表表都有 `position`：旧数据是 JSON 数组，数组顺序就是界面顺序，读出来按 position 排，顺序和旧版完全一样。
 * 枚举存名字（字符串），读回时认不出的名字按模型默认值处理。
 */

/**
 * 收藏的车（站 + 线）的内容，对应 FavoriteStationLine。
 * 第 14 步起顺序和分组以 [FavoriteItemEntity] 为准；`position` 只是写入时的镜像，读的时候不用。
 */
@Entity(tableName = "favorite_line")
data class FavoriteLineEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String,
    val direction: Int,
    val startStop: String,
    val endStop: String,
    val targetStopId: String,
    val targetStopName: String,
    val targetOrder: Int,
    val totalStops: Int,
    val wgsLat: Double,
    val wgsLng: Double,
    val source: String,
    val mygolbsCityCode: String,
    val addedAtMillis: Long
)

/**
 * 收藏合一（第 14 步，库版本 2）：车和路线排在一个列表里，每条收藏一行，记顺序和分组。
 * 内容仍在 `favorite_line` / `route*` 表，这张表只是索引。
 *
 * - `id`：`stop_<收藏的车 id>` 或 `route_<路线 id>`（FavoriteItem.key），两种类型之间不重复。
 * - `type`：`STOP` / `ROUTE`（FavoriteItemType 的名字）；`refId`：对应表里的 id。
 * - `groupId`：收藏分组 id，null = 不分组。**不加外键**：`favorite_group` 是整份替换写的，外键的 SET NULL 会在每次改分组名时
 *   把所有收藏都改成不分组；旧数据里也有指向已删分组的 id。删分组时由仓库在同一个事务里把它的收藏改成 null（§2.4）。
 * - `sortOrder`：合一列表的顺序，小的在前，不要求连续。
 */
@Entity(
    tableName = "favorite_item",
    indices = [Index("groupId"), Index("sortOrder")]
)
data class FavoriteItemEntity(
    @PrimaryKey val id: String,
    val type: String,
    val refId: String,
    val groupId: String?,
    val sortOrder: Int
)

/** 收藏分组，对应 FavoriteGroup */
@Entity(tableName = "favorite_group")
data class FavoriteGroupEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val sortOrder: Int,
    val isDefault: Boolean
)

/**
 * 路线，对应 TransitGroup（不含程）。
 *
 * `customGroupId` 不加外键：旧数据里可能指向已经不存在的分组（旧版也照样显示），加外键会让这种行写不进来。
 * 删分组时由仓库在同一个事务里把它改成 null（§2.4「删分组后收藏变不分组」）。
 *
 * 第 14 步起路线的分组和顺序以 [FavoriteItemEntity] 为准；`customGroupId`、`sortOrder`、`position` 只是写入时的镜像，读的时候不用。
 */
@Entity(tableName = "route")
data class RouteEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val category: String,
    val isTemporary: Boolean,
    val sortOrder: Int,
    val customGroupId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/** 路线的一程，对应 TransitLeg（不含候选线）。轨迹点数组存 JSON 文本，只整体读写 */
@Entity(
    tableName = "route_leg",
    primaryKeys = ["routeId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RouteLegEntity(
    val routeId: String,
    val position: Int,
    val legIndex: Int,
    val fromStopName: String,
    val fromStopId: String,
    val toStopName: String,
    val toStopId: String,
    val fromStopLat: Double,
    val fromStopLng: Double,
    val toStopLat: Double,
    val toStopLng: Double,
    val walkingDistanceMeters: Int,
    val ridePathJson: String,
    val walkPathBeforeJson: String
)

/** 一程里的候选线，对应 CandidateLine。position = 0 的是主线路 */
@Entity(
    tableName = "route_candidate_line",
    primaryKeys = ["routeId", "legPosition", "position"],
    foreignKeys = [
        ForeignKey(
            entity = RouteLegEntity::class,
            parentColumns = ["routeId", "position"],
            childColumns = ["routeId", "legPosition"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class CandidateLineEntity(
    val routeId: String,
    val legPosition: Int,
    val position: Int,
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val source: String,
    val direction: Int,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val startStopName: String,
    val endStopName: String,
    val mygolbsCityCode: String?
)

/**
 * 定时提醒，对应 ScheduledReminder。
 *
 * `daysOfWeek` 存成逗号分隔（"1,2,3,4,5"；空串 = 仅一次）。
 * `lastScheduledTriggerAt`：上次给系统闹钟排的触发时刻（原 `trackr_reminder_schedule_state` 的 `next_trigger_<id>`），
 * 和提醒同生同死；开机时判断「已错过的一次性提醒」要读它。
 */
@Entity(tableName = "reminder")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: String,
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val direction: Int,
    val endStopName: String,
    val targetStopId: String,
    val targetStopName: String,
    val targetOrder: Int,
    val totalStops: Int,
    val dataSourceType: String,
    val mygolbsCityCode: String?,
    val autoStartLiveTracking: Boolean,
    val lastScheduledTriggerAt: Long?
)

/** 搜索历史，position = 0 是最近一次 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val position: Int
)

/**
 * 线路站表缓存（只用来先显示，从不挡住网络请求，§2.4）。
 * `cacheKey` 由调用方拼（数据源 + 城市 + 线路 + 方向），`stopsJson` 是站表的 JSON。空站表不写。
 */
@Entity(tableName = "line_stops_cache", indices = [Index("fetchedAt")])
data class LineStopsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val stopsJson: String,
    val fetchedAt: Long
)

/**
 * 迁移标记（键值）。`legacy_import_gen` 记旧 SharedPreferences 导入到第几代（见 LegacyLibraryMigration）。
 * 放在库里而不是设置文件里：库文件丢了或损坏重建时，标记跟着没了，下次启动会重新从旧文件导入。
 */
@Entity(tableName = "store_meta")
data class StoreMetaEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** `store_meta` 里用到的键 */
object StoreMetaKeys {
    /**
     * 收藏分组建过没有。有这一行（值为 "1"）时，`favorite_group` 表空表示用户把分组都删了，不再补两个默认分组；
     * 没有这一行才是从没建过（见 `DefaultFavoritesRepository.create`）。
     */
    const val FAVORITE_GROUPS_INITIALIZED = "favorite_groups_initialized"
}
