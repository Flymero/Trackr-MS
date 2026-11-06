package com.trackr.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.trackr.core.database.entity.CandidateLineEntity
import com.trackr.core.database.entity.FavoriteGroupEntity
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.database.entity.FavoriteLineEntity
import com.trackr.core.database.entity.LineStopsCacheEntity
import com.trackr.core.database.entity.ReminderEntity
import com.trackr.core.database.entity.RouteEntity
import com.trackr.core.database.entity.RouteLegEntity
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.database.entity.StoreMetaEntity
import com.trackr.core.database.entity.StoreMetaKeys
import kotlinx.coroutines.flow.Flow

/*
 * 第 9 步的写法：仓库在内存里算好整份列表，DAO 在一个事务里整份替换（和旧版每次整份写 JSON 的语义一样，最稳）。
 * 第 14 步：收藏（车 + 路线 + 分组 + 合一索引）改由 [FavoritesDao] 读写，按行改（拖动排序、改分组）也在那里。
 */

@Dao
interface FavoriteLineDao {
    @Query("SELECT * FROM favorite_line ORDER BY position")
    suspend fun getAll(): List<FavoriteLineEntity>

    @Query("SELECT * FROM favorite_line ORDER BY position")
    fun observeAll(): Flow<List<FavoriteLineEntity>>

    @Query("DELETE FROM favorite_line")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FavoriteLineEntity>)

    @Transaction
    suspend fun replaceAll(items: List<FavoriteLineEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface FavoriteGroupDao {
    @Query("SELECT * FROM favorite_group ORDER BY position")
    suspend fun getAll(): List<FavoriteGroupEntity>

    @Query("SELECT COUNT(*) FROM favorite_group")
    suspend fun count(): Int

    @Query("DELETE FROM favorite_group")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FavoriteGroupEntity>)

    @Transaction
    suspend fun replaceAll(items: List<FavoriteGroupEntity>) {
        deleteAll()
        insertAll(items)
    }
}

/** 一条路线连同它的程和候选线 */
data class RouteRows(
    val route: RouteEntity,
    val legs: List<RouteLegEntity>,
    val candidates: List<CandidateLineEntity>
)

@Dao
interface RouteDao {
    @Query("SELECT * FROM route ORDER BY position")
    suspend fun getRoutes(): List<RouteEntity>

    @Query("SELECT * FROM route_leg ORDER BY routeId, position")
    suspend fun getLegs(): List<RouteLegEntity>

    @Query("SELECT * FROM route_candidate_line ORDER BY routeId, legPosition, position")
    suspend fun getCandidates(): List<CandidateLineEntity>

    @Query("SELECT COUNT(*) FROM route")
    suspend fun count(): Int

    @Query("DELETE FROM route_candidate_line")
    suspend fun deleteAllCandidates()

    @Query("DELETE FROM route_leg")
    suspend fun deleteAllLegs()

    @Query("DELETE FROM route")
    suspend fun deleteAllRoutes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(items: List<RouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLegs(items: List<RouteLegEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(items: List<CandidateLineEntity>)

    /** 全部路线（按 position），每条带上自己的程和候选线 */
    @Transaction
    suspend fun getAll(): List<RouteRows> {
        val legsByRoute = getLegs().groupBy { it.routeId }
        val candidatesByRoute = getCandidates().groupBy { it.routeId }
        return getRoutes().map { route ->
            RouteRows(
                route = route,
                legs = legsByRoute[route.id].orEmpty(),
                candidates = candidatesByRoute[route.id].orEmpty()
            )
        }
    }

    /** 整份替换。不靠外键级联，三张表都显式清空，结果和级联开没开无关 */
    @Transaction
    suspend fun replaceAll(rows: List<RouteRows>) {
        deleteAllCandidates()
        deleteAllLegs()
        deleteAllRoutes()
        insertRoutes(rows.map { it.route })
        insertLegs(rows.flatMap { it.legs })
        insertCandidates(rows.flatMap { it.candidates })
    }

    @Query("DELETE FROM favorite_group")
    suspend fun deleteAllFavoriteGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteGroups(items: List<FavoriteGroupEntity>)

    /** 路线和收藏分组一起换（删分组时同时把它的路线改成不分组），一个事务 */
    @Transaction
    suspend fun replaceRoutesAndGroups(rows: List<RouteRows>, groups: List<FavoriteGroupEntity>) {
        replaceAll(rows)
        deleteAllFavoriteGroups()
        insertFavoriteGroups(groups)
    }
}

/** 收藏合一要的全部行：车、路线（连同程和候选线）、收藏分组、合一索引。一个事务里读出 */
data class FavoritesRows(
    val stops: List<FavoriteLineEntity>,
    val routes: List<RouteRows>,
    val groups: List<FavoriteGroupEntity>,
    val items: List<FavoriteItemEntity>,
    /** 分组建过没有（`store_meta`，见 [StoreMetaKeys.FAVORITE_GROUPS_INITIALIZED]） */
    val groupsInitialized: Boolean
)

/**
 * 收藏合一（第 14 步）：`favorite_item` 索引 + 车 / 路线 / 分组的组合写入。
 *
 * [write] 里为 null 的部分不动；给了的整份替换（和第 9 步一样），[FavoritesDao.write] 的 `itemUpdates` 只改那几行（拖动排序、改分组）。
 * 所有部分在**一个**事务里写，删分组（分组 + 路线 + 索引）要么全成、要么全不变。
 */
@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorite_item ORDER BY sortOrder, id")
    suspend fun getItems(): List<FavoriteItemEntity>

    @Query("SELECT * FROM favorite_item ORDER BY sortOrder, id")
    fun observeItems(): Flow<List<FavoriteItemEntity>>

    @Query("UPDATE favorite_item SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: String, groupId: String?)

    @Query("UPDATE favorite_item SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)

    /** 按给的顺序把 sortOrder 改成 0、1、2…（只动列出的行） */
    @Transaction
    suspend fun move(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("DELETE FROM favorite_item")
    suspend fun deleteAllItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<FavoriteItemEntity>)

    @Upsert
    suspend fun upsertItems(items: List<FavoriteItemEntity>)

    @Query("SELECT * FROM favorite_line ORDER BY position")
    suspend fun getStops(): List<FavoriteLineEntity>

    @Query("DELETE FROM favorite_line")
    suspend fun deleteAllStops()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(items: List<FavoriteLineEntity>)

    @Query("SELECT * FROM route ORDER BY position")
    suspend fun getRoutes(): List<RouteEntity>

    @Query("SELECT * FROM route_leg ORDER BY routeId, position")
    suspend fun getLegs(): List<RouteLegEntity>

    @Query("SELECT * FROM route_candidate_line ORDER BY routeId, legPosition, position")
    suspend fun getCandidates(): List<CandidateLineEntity>

    @Query("DELETE FROM route_candidate_line")
    suspend fun deleteAllCandidates()

    @Query("DELETE FROM route_leg")
    suspend fun deleteAllLegs()

    @Query("DELETE FROM route")
    suspend fun deleteAllRoutes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(items: List<RouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLegs(items: List<RouteLegEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(items: List<CandidateLineEntity>)

    @Query("SELECT * FROM favorite_group ORDER BY position")
    suspend fun getGroups(): List<FavoriteGroupEntity>

    @Query("DELETE FROM favorite_group")
    suspend fun deleteAllGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(items: List<FavoriteGroupEntity>)

    @Transaction
    suspend fun loadAll(): FavoritesRows {
        val legsByRoute = getLegs().groupBy { it.routeId }
        val candidatesByRoute = getCandidates().groupBy { it.routeId }
        val routes = getRoutes().map { route ->
            RouteRows(
                route = route,
                legs = legsByRoute[route.id].orEmpty(),
                candidates = candidatesByRoute[route.id].orEmpty()
            )
        }
        return FavoritesRows(
            stops = getStops(),
            routes = routes,
            groups = getGroups(),
            items = getItems(),
            groupsInitialized = getMeta(StoreMetaKeys.FAVORITE_GROUPS_INITIALIZED) != null
        )
    }

    /** `store_meta` 的键值（分组建过没有）；和收藏的写入放在同一个事务里 */
    @Query("SELECT value FROM store_meta WHERE `key` = :key")
    suspend fun getMeta(key: String): String?

    @Upsert
    suspend fun putMeta(entry: StoreMetaEntity)

    @Transaction
    suspend fun write(
        stops: List<FavoriteLineEntity>? = null,
        routes: List<RouteRows>? = null,
        groups: List<FavoriteGroupEntity>? = null,
        items: List<FavoriteItemEntity>? = null,
        itemUpdates: List<FavoriteItemEntity> = emptyList(),
        /** 记下分组建过了：`favorite_group` 表空之后就不再是「从没建过」 */
        markGroupsInitialized: Boolean = false
    ) {
        stops?.let {
            deleteAllStops()
            insertStops(it)
        }
        routes?.let { rows ->
            // 不靠外键级联，三张表都显式清空
            deleteAllCandidates()
            deleteAllLegs()
            deleteAllRoutes()
            insertRoutes(rows.map { it.route })
            insertLegs(rows.flatMap { it.legs })
            insertCandidates(rows.flatMap { it.candidates })
        }
        groups?.let {
            deleteAllGroups()
            insertGroups(it)
        }
        items?.let {
            deleteAllItems()
            insertItems(it)
        }
        if (itemUpdates.isNotEmpty()) upsertItems(itemUpdates)
        if (markGroupsInitialized) putMeta(StoreMetaEntity(StoreMetaKeys.FAVORITE_GROUPS_INITIALIZED, "1"))
    }
}

/** 提醒的计划触发时刻 */
data class ReminderTrigger(
    val id: String,
    val lastScheduledTriggerAt: Long?
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder ORDER BY position")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT id, lastScheduledTriggerAt FROM reminder")
    suspend fun getTriggers(): List<ReminderTrigger>

    @Query("SELECT lastScheduledTriggerAt FROM reminder WHERE id = :id")
    suspend fun getLastScheduledTriggerAt(id: String): Long?

    @Query("UPDATE reminder SET lastScheduledTriggerAt = :at WHERE id = :id")
    suspend fun setLastScheduledTriggerAt(id: String, at: Long?)

    @Query("DELETE FROM reminder")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReminderEntity>)

    /**
     * 整份替换提醒列表，但保留每个提醒已记下的计划触发时刻：
     * 传进来的行里 `lastScheduledTriggerAt` 一律忽略，按 id 用库里现有的值（新提醒为 null）。
     * 读旧值和写新值在同一个事务里，期间别的写入插不进来。
     */
    @Transaction
    suspend fun replaceAllKeepingTriggers(items: List<ReminderEntity>) {
        val triggers = getTriggers().associate { it.id to it.lastScheduledTriggerAt }
        deleteAll()
        insertAll(items.map { it.copy(lastScheduledTriggerAt = triggers[it.id]) })
    }

    /** 迁移用：整份替换，计划触发时刻用传进来的值 */
    @Transaction
    suspend fun replaceAll(items: List<ReminderEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY position")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SearchHistoryEntity>)

    @Transaction
    suspend fun replaceAll(items: List<SearchHistoryEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Dao
interface LineStopsCacheDao {
    @Query("SELECT * FROM line_stops_cache WHERE cacheKey = :key")
    suspend fun get(key: String): LineStopsCacheEntity?

    @Upsert
    suspend fun upsert(entry: LineStopsCacheEntity)

    @Query("DELETE FROM line_stops_cache WHERE fetchedAt < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface StoreMetaDao {
    @Query("SELECT value FROM store_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: StoreMetaEntity)
}
