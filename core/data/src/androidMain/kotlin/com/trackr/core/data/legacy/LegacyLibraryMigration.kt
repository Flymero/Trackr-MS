package com.trackr.core.data.legacy

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.trackr.core.data.local.localJson
import com.trackr.core.data.local.toEntity
import com.trackr.core.data.local.toRows
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.dao.LegacyImport
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.database.entity.StoreMetaEntity
import com.trackr.core.datastore.SettingsKeys
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitGroup
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * 旧数据一次性搬家（只在 Android；重构计划 §2.4、第 9 步、风险 R1）。
 *
 * 规则：
 * 1. **旧文件一个 key 都不删、不改**。回退旧版本、或把 USE_NEW_STORE 关掉，都还能读到原样的数据。
 * 2. 列表类（收藏的车、搜索历史、路线、收藏分组、收藏合一索引、定时提醒 + 计划触发时刻）在**一个** Room 事务里写入，连同导入标记；
 *    中途失败什么都不变，下次启动重来。
 * 3. 设置和高德 Key 由 [LegacySettingsMigration]、[LegacyAmapKeyMigration] 在 DataStore 第一次读时导入；
 *    这里先读一次两个 DataStore，确保它们已经导完。
 * 4. 某个 JSON 坏了：跳过这一项（那张表不动），其他照迁；原文复制到 `files/legacy_backup/<文件>_<key>.json`，
 *    记进设置的 `legacy_corrupt_keys`。148d67c 留下的 `<key>_corrupt_backup_<时间戳>` 也原样复制过去。
 * 5. 全部成功后在设置里记 `legacy_migrated = 1`。
 *
 * 什么时候跑：库里的导入标记（store_meta `legacy_import_gen`）和当前代数（[generation]）不同时。
 * 第一次装新版、库文件丢了重建、或者中间用旧存储跑过（代数 +1，见 :androidApp 的 StoreSwitch），都会再导一次；
 * 再导时只替换旧文件里**有**的那几项，旧文件里没有的保持库里的。
 *
 * 收藏合一索引（第 14 步）：旧文件里只有用旧存储跑过第 14 步以后的版本才有 `favorite_items_json`，有就一起导入；
 * 没有时不动库里的索引，启动时 DefaultFavoritesRepository 按车和路线对账补齐（车在前、路线在后，顺序和以前一样）。
 */
class LegacyLibraryMigration(
    context: Context,
    private val db: TrackrDatabase,
    private val settings: DataStore<Preferences>,
    private val amapKeys: DataStore<Preferences>,
    private val generation: Int,
    private val log: (String) -> Unit = {}
) {
    private val appContext: Context = context.applicationContext ?: context

    /** 导入结果，写进日志和第 9 步报告 */
    data class Report(
        val ran: Boolean,
        /** 每项导入的条数；没导（旧文件里没有，或 JSON 坏了）的不在表里 */
        val imported: Map<String, Int> = emptyMap(),
        /** 解析失败、被跳过的旧 key，格式 `<文件>/<key>` */
        val corruptKeys: List<String> = emptyList(),
        /** 复制到 legacy_backup/ 的文件名 */
        val backupFiles: List<String> = emptyList()
    )

    private val backupDir: File get() = File(appContext.filesDir, LegacyPrefs.BACKUP_DIR)

    suspend fun runIfNeeded(): Report {
        // 先让两个 DataStore 跑完各自的 DataMigration（设置、Key）
        settings.data.first()
        amapKeys.data.first()

        val doneGen = db.storeMeta().get(META_LEGACY_IMPORT_GEN)?.toIntOrNull()
        // A restored device can carry a newer store generation than the local
        // switch preference. Never import the older legacy snapshot over it.
        if (doneGen != null && doneGen >= generation) return Report(ran = false)

        val corrupt = mutableListOf<String>()
        val backups = mutableListOf<String>()
        val imported = linkedMapOf<String, Int>()

        val transit = prefs(LegacyPrefs.TRANSIT)
        val routeGroups = prefs(LegacyPrefs.ROUTE_GROUPS)
        val reminders = prefs(LegacyPrefs.REMINDERS)
        val scheduleState = prefs(LegacyPrefs.REMINDER_SCHEDULE_STATE)

        fun <T> decode(file: String, sp: SharedPreferences, key: String, serializer: KSerializer<List<T>>): List<T>? {
            val raw = sp.all[key] ?: return null
            if (raw !is String) {
                corrupt += "$file/$key"
                backups += copyToBackup(file, key, raw.toString())
                return null
            }
            if (raw.isBlank()) return emptyList()
            return try {
                localJson.decodeFromString(serializer, raw)
            } catch (e: Exception) {
                log("旧数据 $file/$key 解析失败，跳过并备份原文：${e.message}")
                corrupt += "$file/$key"
                backups += copyToBackup(file, key, raw)
                null
            }
        }

        val favorites = decode(LegacyPrefs.TRANSIT, transit, LegacyPrefs.KEY_FAVORITES, ListSerializer(FavoriteStationLine.serializer()))
        val history = decode(LegacyPrefs.TRANSIT, transit, LegacyPrefs.KEY_SEARCH_HISTORY, ListSerializer(String.serializer()))
        val groups = decode(LegacyPrefs.ROUTE_GROUPS, routeGroups, LegacyPrefs.KEY_GROUPS, ListSerializer(TransitGroup.serializer()))
        val favoriteGroups = decode(
            LegacyPrefs.ROUTE_GROUPS, routeGroups, LegacyPrefs.KEY_FAVORITE_GROUPS, ListSerializer(FavoriteGroup.serializer())
        )
        val favoriteItems = decode(
            LegacyPrefs.ROUTE_GROUPS, routeGroups, LegacyPrefs.KEY_FAVORITE_ITEMS, ListSerializer(FavoriteItemRecord.serializer())
        )
        val favoriteStations = decode(
            LegacyPrefs.ROUTE_GROUPS, routeGroups, LegacyPrefs.KEY_FAVORITE_STATIONS, ListSerializer(FavoriteStation.serializer())
        )
        val reminderList = decode(LegacyPrefs.REMINDERS, reminders, LegacyPrefs.KEY_REMINDERS, ListSerializer(ScheduledReminder.serializer()))
        val triggers = scheduleState.all.mapNotNull { (key, value) ->
            if (key.startsWith(LegacyPrefs.KEY_PREFIX_NEXT_TRIGGER) && value is Long) {
                key.removePrefix(LegacyPrefs.KEY_PREFIX_NEXT_TRIGGER) to value
            } else {
                null
            }
        }.toMap()

        // 148d67c 留下的损坏备份：不解析，原文复制
        for (file in LegacyPrefs.ALL_FILES) {
            prefs(file).all.forEach { (key, value) ->
                if (key.contains(LegacyPrefs.CORRUPT_BACKUP_MARK) && value != null) {
                    backups += copyToBackup(file, key, value.toString())
                }
            }
        }

        favorites?.let { imported["favorites"] = it.size }
        history?.let { imported["searchHistory"] = it.size }
        groups?.let { imported["routes"] = it.size }
        favoriteGroups?.let { imported["favoriteGroups"] = it.size }
        favoriteItems?.let { imported["favoriteItems"] = it.size }
        favoriteStations?.let { imported["favoriteStations"] = it.size }
        reminderList?.let { imported["reminders"] = it.size }

        db.legacyImport().importAll(
            LegacyImport(
                favorites = favorites?.mapIndexed { index, item -> item.toEntity(index) },
                searchHistory = history?.distinct()?.mapIndexed { index, query -> SearchHistoryEntity(query, index) },
                routes = groups?.mapIndexed { index, group -> group.toRows(index) },
                favoriteGroups = favoriteGroups?.mapIndexed { index, group -> group.toEntity(index) },
                reminders = reminderList?.mapIndexed { index, reminder -> reminder.toEntity(index, triggers[reminder.id]) },
                favoriteItems = favoriteItems?.distinctBy { it.key }?.map { it.toEntity() },
                favoriteStations = favoriteStations?.distinctBy { it.id }?.mapIndexed { index, station -> station.toEntity(index) },
                meta = StoreMetaEntity(META_LEGACY_IMPORT_GEN, generation.toString())
            )
        )

        settings.edit { prefs ->
            prefs[SettingsKeys.LEGACY_MIGRATED] = 1
            if (corrupt.isNotEmpty()) {
                prefs[SettingsKeys.LEGACY_CORRUPT_KEYS] = prefs[SettingsKeys.LEGACY_CORRUPT_KEYS].orEmpty() + corrupt
            }
        }
        val report = Report(ran = true, imported = imported, corruptKeys = corrupt, backupFiles = backups.distinct())
        log("旧数据导入完成（第 $generation 代）：$report")
        return report
    }

    private fun prefs(name: String): SharedPreferences = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /**
     * 原文写进 `legacy_backup/<文件>_<key>.json`。已有同名文件且内容一样就不再写；内容不同就换个名字（加 `_2`、`_3`……），
     * 已经备份过的原文不会被覆盖。先写临时文件再改名，写到一半被杀也不会留下半个文件。
     */
    private fun copyToBackup(file: String, key: String, raw: String): String {
        val dir = backupDir.apply { mkdirs() }
        val base = "${file}_$key"
        var name = "$base.json"
        var n = 1
        while (true) {
            val target = File(dir, name)
            if (!target.exists()) break
            if (target.readText(Charsets.UTF_8) == raw) return name
            n += 1
            name = "${base}_$n.json"
        }
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(raw, Charsets.UTF_8)
        if (!tmp.renameTo(File(dir, name))) {
            tmp.copyTo(File(dir, name), overwrite = true)
            tmp.delete()
        }
        return name
    }

    companion object {
        /** store_meta 里的导入标记 */
        const val META_LEGACY_IMPORT_GEN = "legacy_import_gen"
    }
}
