package com.trackr.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.trackr.core.database.dao.FavoriteGroupDao
import com.trackr.core.database.dao.FavoriteLineDao
import com.trackr.core.database.dao.FavoritesDao
import com.trackr.core.database.dao.LegacyImportDao
import com.trackr.core.database.dao.LineStopsCacheDao
import com.trackr.core.database.dao.ReminderDao
import com.trackr.core.database.dao.RouteDao
import com.trackr.core.database.dao.SearchHistoryDao
import com.trackr.core.database.dao.StoreMetaDao
import com.trackr.core.database.entity.CandidateLineEntity
import com.trackr.core.database.entity.FavoriteGroupEntity
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.database.entity.FavoriteLineEntity
import com.trackr.core.database.entity.FavoriteStationEntity
import com.trackr.core.database.entity.LineStopsCacheEntity
import com.trackr.core.database.entity.ReminderEntity
import com.trackr.core.database.entity.RouteEntity
import com.trackr.core.database.entity.RouteLegEntity
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.database.entity.StoreMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * 追客本地数据库（Android：`databases/trackr.db`，参与备份；重构计划 §2.4）。
 *
 * 改表结构：版本号 +1、在 [TRACKR_MIGRATIONS] 里加 Migration、用 schemas/ 里导出的 JSON 做迁移测试，**不要**开 destructive fallback。
 *
 * - 版本 1（第 9 步）：车、路线、分组、提醒、搜索历史、站表缓存、迁移标记。
 * - 版本 2（第 14 步）：加 `favorite_item`（收藏合一的顺序和分组），见 [MIGRATION_1_2]。
 * - 版本 3（issue #38）：`favorite_group` 加 `color`（分组颜色），见 [MIGRATION_2_3]。
 * - 版本 4（issue #47）：加 `favorite_station`（收藏的站），见 [MIGRATION_3_4]。
 *
 * 新版本的包装到手机上之后，不能再装库版本更低的包（Room 不认更高的版本，会打不开库）。
 */
@Database(
    entities = [
        FavoriteLineEntity::class,
        FavoriteGroupEntity::class,
        RouteEntity::class,
        RouteLegEntity::class,
        CandidateLineEntity::class,
        ReminderEntity::class,
        SearchHistoryEntity::class,
        LineStopsCacheEntity::class,
        StoreMetaEntity::class,
        FavoriteItemEntity::class,
        FavoriteStationEntity::class
    ],
    version = TrackrDatabase.VERSION,
    exportSchema = true
)
@ConstructedBy(TrackrDatabaseConstructor::class)
abstract class TrackrDatabase : RoomDatabase() {
    abstract fun favoriteLines(): FavoriteLineDao
    abstract fun favoriteGroups(): FavoriteGroupDao
    abstract fun favorites(): FavoritesDao
    abstract fun routes(): RouteDao
    abstract fun reminders(): ReminderDao
    abstract fun searchHistory(): SearchHistoryDao
    abstract fun lineStopsCache(): LineStopsCacheDao
    abstract fun storeMeta(): StoreMetaDao
    abstract fun legacyImport(): LegacyImportDao

    companion object {
        /** Android 上的库文件名（`databases/` 下），备份规则按这个名字写 */
        const val FILE_NAME = "trackr.db"

        /** 当前库版本（issue #47：4） */
        const val VERSION = 4
    }
}

/** Room 编译器为每个平台生成 actual */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object TrackrDatabaseConstructor : RoomDatabaseConstructor<TrackrDatabase> {
    override fun initialize(): TrackrDatabase
}

/** 各平台的 builder 统一收尾：加上全部迁移（不开 destructive fallback），查询跑在 IO 线程 */
fun RoomDatabase.Builder<TrackrDatabase>.buildTrackrDatabase(): TrackrDatabase =
    addMigrations(*TRACKR_MIGRATIONS)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
