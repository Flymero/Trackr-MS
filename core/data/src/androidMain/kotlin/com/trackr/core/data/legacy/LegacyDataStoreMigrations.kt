package com.trackr.core.data.legacy

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.trackr.core.datastore.AmapKeyKeys
import com.trackr.core.datastore.SettingsKeys

/*
 * 设置和高德 Key 从旧 SharedPreferences 导入 DataStore（重构计划 §2.4）。
 *
 * 不用 DataStore 自带的 SharedPreferencesMigration：它迁完会删掉旧 key，和「旧 key 不删」冲突。
 * 这里的 DataMigration 只读不删，cleanUp 什么都不做。
 *
 * 「第几代」（generation）：每次以旧存储（USE_NEW_STORE 关）启动，代数 +1（见 :androidApp 的 StoreSwitch）。
 * 文件里记的代数和当前代数不同就重新导入一次，这样「新 -> 旧 -> 新」来回切时，用旧存储期间的改动也能带回来。
 * 重新导入时只覆盖旧文件里**有**的 key，旧文件里没有的保持新存储里的值。
 */

/** `trackr_prefs` 里的设置 -> `trackr_settings` */
class LegacySettingsMigration(
    context: Context,
    private val generation: Int
) : DataMigration<Preferences> {

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SettingsKeys.LEGACY_IMPORT_GEN] != generation

    override suspend fun migrate(currentData: Preferences): Preferences {
        val legacy = appContext.getSharedPreferences(LegacyPrefs.TRANSIT, Context.MODE_PRIVATE).all
        val result = currentData.toMutablePreferences()
        copySettings(legacy, result)
        result[SettingsKeys.LEGACY_IMPORT_GEN] = generation
        return result.toPreferences()
    }

    override suspend fun cleanUp() {
        // 旧 key 不删
    }

    companion object {
        /** 按类型逐个搬；类型对不上（理论上不会）就跳过这个 key，保留新存储里的值 */
        fun copySettings(legacy: Map<String, *>, target: MutablePreferences) {
            (legacy[LegacyPrefs.KEY_SELECTED_CITY] as? String)?.let { target[SettingsKeys.SELECTED_CITY_JSON] = it }
            // D1：旧的聚合配置（engine_mode = ALL + 优先级）落成主数据源，优先级本身不再导入
            LegacyDataSource.resolve(
                engineMode = legacy[LegacyPrefs.KEY_ENGINE_MODE] as? String,
                aggregatedPriority = legacy[LegacyPrefs.KEY_AGGREGATED_PRIORITY] as? String
            )?.let { target[SettingsKeys.ENGINE_MODE] = it }
            (legacy[LegacyPrefs.KEY_TRANSIT_CODE_PROVIDER] as? String)?.let { target[SettingsKeys.PREFERRED_TRANSIT_CODE_PROVIDER] = it }
            (legacy[LegacyPrefs.KEY_NEARBY_STOPS_COUNT] as? Int)?.let { target[SettingsKeys.NEARBY_STOPS_COUNT] = it }
            (legacy[LegacyPrefs.KEY_LIVE_NOTIFICATION] as? Boolean)?.let { target[SettingsKeys.LIVE_NOTIFICATION_ENABLED] = it }
            // 旧版没有这个键；只有退回旧存储期间改过才会有（第 13 步）
            (legacy[LegacyPrefs.KEY_SHOW_ON_LOCK_SCREEN] as? Boolean)?.let { target[SettingsKeys.SHOW_ON_LOCK_SCREEN] = it }
            // 同上，第 25 步新加
            (legacy[LegacyPrefs.KEY_ALERT_THRESHOLD_STOPS] as? Int)?.let { target[SettingsKeys.ALERT_THRESHOLD_STOPS] = it }
            // 同上，第 30 步新加
            (legacy[LegacyPrefs.KEY_UI_MODE] as? String)?.let { target[SettingsKeys.UI_MODE] = it }
            (legacy[LegacyPrefs.KEY_THEME_MODE] as? String)?.let { target[SettingsKeys.THEME_MODE] = it }
            (legacy[LegacyPrefs.KEY_THEME_COLORS] as? String)?.let { target[SettingsKeys.THEME_COLORS] = it }
            (legacy[LegacyPrefs.KEY_FONT_SCALE_PROMPT_DISMISSED] as? Boolean)?.let { target[SettingsKeys.FONT_SCALE_PROMPT_DISMISSED] = it }
            (legacy[LegacyPrefs.KEY_FAVORITES_GROUP_MIGRATION_VERSION] as? Int)?.let {
                target[SettingsKeys.FAVORITES_GROUP_MIGRATION_VERSION] = it
            }
        }
    }
}

/**
 * 高德 Key：`trackr_amap_keys`（SharedPreferences）-> `trackr_amap_keys`（DataStore）。
 *
 * 0a4fdab 之前 Key 在 `trackr_prefs` 里。旧 SharedPreferences 的 `trackr_amap_keys` 没有 `migrated_from_trackr_prefs`
 * 标记时（用户从 0a4fdab 之前直接升到这一版），某个槽在 `trackr_amap_keys` 里是空的，就用 `trackr_prefs` 里的值；
 * `trackr_amap_keys` 里有值时以它为准（和 `AmapKeyStorage.migrateFromLegacy` 的规则一样）。
 * 只写非空值：旧文件里是空的，不会清掉新存储里已经填好的 Key。
 */
class LegacyAmapKeyMigration(
    context: Context,
    private val generation: Int
) : DataMigration<Preferences> {

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[AmapKeyKeys.LEGACY_IMPORT_GEN] != generation

    override suspend fun migrate(currentData: Preferences): Preferences {
        val (androidKey, webKey) = readLegacyKeys(
            keyFile = appContext.getSharedPreferences(LegacyPrefs.AMAP_KEYS, Context.MODE_PRIVATE),
            transitPrefs = appContext.getSharedPreferences(LegacyPrefs.TRANSIT, Context.MODE_PRIVATE)
        )
        val result = currentData.toMutablePreferences()
        androidKey?.let { result[AmapKeyKeys.ANDROID] = it }
        webKey?.let { result[AmapKeyKeys.WEB] = it }
        result[AmapKeyKeys.LEGACY_IMPORT_GEN] = generation
        return result.toPreferences()
    }

    override suspend fun cleanUp() {
        // 旧 key 不删
    }

    companion object {
        /** 返回 (Android Key, Web Key)，空串当没填 */
        fun readLegacyKeys(keyFile: SharedPreferences, transitPrefs: SharedPreferences): Pair<String?, String?> {
            fun SharedPreferences.slot(key: String): String? =
                (all[key] as? String)?.trim()?.ifBlank { null }

            val migratedFromTransit = keyFile.all[LegacyPrefs.KEY_AMAP_MIGRATED_FROM_TRACKR_PREFS] == true
            val androidKey = keyFile.slot(LegacyPrefs.KEY_AMAP_ANDROID)
                ?: if (migratedFromTransit) null else transitPrefs.slot(LegacyPrefs.KEY_AMAP_ANDROID)
            val webKey = keyFile.slot(LegacyPrefs.KEY_AMAP_WEB)
                ?: if (migratedFromTransit) null else transitPrefs.slot(LegacyPrefs.KEY_AMAP_WEB)
            return androidKey to webKey
        }
    }
}
