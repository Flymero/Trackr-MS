package com.trackr.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 1 -> 2（第 14 步，收藏合一）：加 `favorite_item`，按现有数据填好，别的表一行不动。
 *
 * 顺序：先是收藏的车（按原来的 `position`），后面接路线（按收藏页原来的顺序：`sortOrder`，相同时按 `position`）。
 * sortOrder 从 0 连续编号。
 *
 * 分组：车原来没有分组，都是 null。路线用 `customGroupId`；它为 null 时，按收藏页原来的筛选规则补上
 * （`FavoritesGroupViewModel.isGroupInFavoriteGroup`：场景是上班通勤的算「commute」分组，名字里有「返程」的算「home」分组），
 * 只在那个分组还在时才补，用户看到的分组归属和升级前一样。`customGroupId` 指向已删分组的照原样保留。
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `favorite_item` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`refId` TEXT NOT NULL, `groupId` TEXT, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_item_groupId` ON `favorite_item` (`groupId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_item_sortOrder` ON `favorite_item` (`sortOrder`)")

        // 车：按 position 排名（position 相同时按 id），排在最前
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `favorite_item` (`id`, `type`, `refId`, `groupId`, `sortOrder`)
            SELECT 'stop_' || f.id, 'STOP', f.id, NULL,
                (SELECT COUNT(*) FROM `favorite_line` f2
                    WHERE f2.position < f.position OR (f2.position = f.position AND f2.id < f.id))
            FROM `favorite_line` f
            """.trimIndent()
        )

        // 路线：按 (sortOrder, position) 排名，接在车后面
        connection.execSQL(
            """
            INSERT OR REPLACE INTO `favorite_item` (`id`, `type`, `refId`, `groupId`, `sortOrder`)
            SELECT 'route_' || r.id, 'ROUTE', r.id,
                CASE
                    WHEN r.customGroupId IS NOT NULL THEN r.customGroupId
                    WHEN r.category = 'COMMUTE' AND EXISTS (SELECT 1 FROM `favorite_group` WHERE id = 'commute') THEN 'commute'
                    WHEN instr(r.name, '返程') > 0 AND EXISTS (SELECT 1 FROM `favorite_group` WHERE id = 'home') THEN 'home'
                    ELSE NULL
                END,
                (SELECT COUNT(*) FROM `favorite_line`) +
                (SELECT COUNT(*) FROM `route` r2
                    WHERE r2.sortOrder < r.sortOrder OR (r2.sortOrder = r.sortOrder AND r2.position < r.position))
            FROM `route` r
            """.trimIndent()
        )
    }
}

/**
 * 2 -> 3（issue #38，分组颜色）：`favorite_group` 加一列可空的 `color`，老分组都是 null（不设颜色），别的不动。
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `favorite_group` ADD COLUMN `color` TEXT")
    }
}

/** 全部迁移，按版本顺序。建库的地方统一加（[buildTrackrDatabase]） */
val TRACKR_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
