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
import com.trackr.core.database.entity.ReminderEntity
import com.trackr.core.database.entity.RouteEntity
import com.trackr.core.database.entity.RouteLegEntity
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.database.entity.StoreMetaEntity
import com.trackr.core.database.entity.StoreMetaKeys

/**
 * 一次导入的旧数据。某一项为 null 表示这次不动那张表（旧文件里没有这个 key，或者 JSON 坏了跳过）。
 */
data class LegacyImport(
    val favorites: List<FavoriteLineEntity>? = null,
    val searchHistory: List<SearchHistoryEntity>? = null,
    val routes: List<RouteRows>? = null,
    val favoriteGroups: List<FavoriteGroupEntity>? = null,
    val reminders: List<ReminderEntity>? = null,
    /** 收藏合一索引（旧存储里的 `favorite_items_json`，第 14 步起才有）。null = 不动，启动时仓库按车和路线对账补齐 */
    val favoriteItems: List<FavoriteItemEntity>? = null,
    /** 写进 store_meta 的导入标记 */
    val meta: StoreMetaEntity
)

/** 旧 SharedPreferences -> Room：所有表和导入标记在**一个**事务里写，要么全成、要么全不变 */
@Dao
interface LegacyImportDao {

    @Query("DELETE FROM favorite_line")
    suspend fun deleteFavorites()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(items: List<FavoriteLineEntity>)

    @Query("DELETE FROM search_history")
    suspend fun deleteSearchHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(items: List<SearchHistoryEntity>)

    @Query("DELETE FROM route_candidate_line")
    suspend fun deleteCandidates()

    @Query("DELETE FROM route_leg")
    suspend fun deleteLegs()

    @Query("DELETE FROM route")
    suspend fun deleteRoutes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(items: List<RouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLegs(items: List<RouteLegEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandidates(items: List<CandidateLineEntity>)

    @Query("DELETE FROM favorite_group")
    suspend fun deleteFavoriteGroups()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteGroups(items: List<FavoriteGroupEntity>)

    @Query("DELETE FROM reminder")
    suspend fun deleteReminders()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(items: List<ReminderEntity>)

    @Query("DELETE FROM favorite_item")
    suspend fun deleteFavoriteItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavoriteItems(items: List<FavoriteItemEntity>)

    @Upsert
    suspend fun putMeta(entry: StoreMetaEntity)

    @Transaction
    suspend fun importAll(data: LegacyImport) {
        data.favorites?.let {
            deleteFavorites()
            insertFavorites(it)
        }
        data.searchHistory?.let {
            deleteSearchHistory()
            insertSearchHistory(it)
        }
        data.routes?.let { rows ->
            deleteCandidates()
            deleteLegs()
            deleteRoutes()
            insertRoutes(rows.map { it.route })
            insertLegs(rows.flatMap { it.legs })
            insertCandidates(rows.flatMap { it.candidates })
        }
        data.favoriteGroups?.let {
            deleteFavoriteGroups()
            insertFavoriteGroups(it)
            // 旧文件里有这个 key 就说明分组建过（旧版存成 "[]" 就是用户删光了），迁过来之后不再补默认分组
            putMeta(StoreMetaEntity(StoreMetaKeys.FAVORITE_GROUPS_INITIALIZED, "1"))
        }
        data.reminders?.let {
            deleteReminders()
            insertReminders(it)
        }
        data.favoriteItems?.let {
            deleteFavoriteItems()
            insertFavoriteItems(it)
        }
        putMeta(data.meta)
    }
}
