package com.trackr.core.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 三个 DataStore 文件（重构计划 §2.4）。Android 上都在 `files/datastore/` 下，备份规则按这里的文件名写：
 *
 * | 文件 | 内容 | 备份 |
 * | --- | --- | --- |
 * | `trackr_settings.preferences_pb` | 城市、数据源、乘车码、开关、迁移标记 | 参与 |
 * | `trackr_amap_keys.preferences_pb` | 高德 Key（Android 槽、Web 槽） | 排除 |
 * | `tracking_session.json` | 追踪会话（`TrackingRepository`） | 排除 |
 */
object TrackrDataStoreFiles {
    const val DIRECTORY = "datastore"
    const val SETTINGS = "trackr_settings.preferences_pb"
    const val AMAP_KEYS = "trackr_amap_keys.preferences_pb"
    const val TRACKING_SESSION = "tracking_session.json"
}

/**
 * `trackr_settings` 的键。名字沿用旧 `trackr_prefs` 里的键名，排查时好对照。
 */
object SettingsKeys {
    val SELECTED_CITY_JSON = stringPreferencesKey("selected_city_json")
    val ENGINE_MODE = stringPreferencesKey("engine_mode")
    val PREFERRED_TRANSIT_CODE_PROVIDER = stringPreferencesKey("preferred_transit_code_provider")

    /** 首页右下角的「乘车码」悬浮按钮（用 NFC 刷卡的关掉，免得挡住列表）；没有 = 显示 */
    val SHOW_TRANSIT_CODE_BUTTON = booleanPreferencesKey("show_transit_code_button")
    val NEARBY_STOPS_COUNT = intPreferencesKey("nearby_stops_count")
    val LIVE_NOTIFICATION_ENABLED = booleanPreferencesKey("is_live_notification_enabled")

    /** 追踪通知锁屏显示全部内容（D3，第 13 步新加，旧存储没有这个键）；没有 = 开 */
    val SHOW_ON_LOCK_SCREEN = booleanPreferencesKey("show_on_lock_screen")

    /** 到站前提醒：车离上车站还有几站时提醒（第 25 步「我的」全局设置，1–3，旧存储没有这个键）；没有 = 2 */
    val ALERT_THRESHOLD_STOPS = intPreferencesKey("alert_threshold_stops")

    /** 界面模式（`UiMode` 的名字，第 30 步新加，旧存储没有这个键）；没有或认不出 = 标准版 */
    val UI_MODE = stringPreferencesKey("ui_mode")

    /** 线路详情走向条样式（`RailMode` 的名字）；没有或认不出 = 横排 */
    val RAIL_MODE = stringPreferencesKey("rail_mode")

    /** 系统字体放大时的「试试长辈版」提示点过了（第 30 步新加）；没有 = 还没点过 */
    val FONT_SCALE_PROMPT_DISMISSED = booleanPreferencesKey("font_scale_prompt_dismissed")
    val FAVORITES_GROUP_MIGRATION_VERSION = intPreferencesKey("favorites_group_migration_version")

    /** 旧 SharedPreferences 的设置导入到第几代（见 LegacySettingsMigration）；没有 = 还没导入 */
    val LEGACY_IMPORT_GEN = intPreferencesKey("legacy_import_gen")

    /** 旧数据全部导入完成后记 1（计划 §2.4 的 legacyMigrated） */
    val LEGACY_MIGRATED = intPreferencesKey("legacy_migrated")

    /** 导入时解析失败的旧 key，格式 `<文件>/<key>`，原文在 `files/legacy_backup/` */
    val LEGACY_CORRUPT_KEYS = stringSetPreferencesKey("legacy_corrupt_keys")
}

/** `trackr_amap_keys` 的键，和旧 SharedPreferences `trackr_amap_keys` 同名 */
object AmapKeyKeys {
    val ANDROID = stringPreferencesKey("custom_amap_android_key")
    val WEB = stringPreferencesKey("custom_amap_web_key")
    val PRIVACY_CONSENT = booleanPreferencesKey("privacy_consent")

    /** 旧 Key 导入到第几代；没有 = 还没导入 */
    val LEGACY_IMPORT_GEN = intPreferencesKey("legacy_import_gen")
}

/** 各平台的系统文件系统（Android / iOS 都是 FileSystem.SYSTEM） */
expect fun systemFileSystem(): FileSystem

/**
 * 建一个 Preferences DataStore。
 *
 * 文件损坏时换成空内容继续（不崩）：迁移标记也在同一个文件里，跟着没了，下次读会重新从旧 SharedPreferences 导入。
 * 同一进程里同一个文件只能有一个实例（DataStore 自己会检查）；[scope] 取消后才能再建。
 */
fun createPreferencesDataStore(
    path: String,
    scope: CoroutineScope,
    migrations: List<DataMigration<Preferences>> = emptyList()
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    migrations = migrations,
    scope = scope,
    produceFile = { path.toPath() }
)

/** 建追踪会话的 DataStore（JSON 数组，没有会话时是空列表） */
fun createTrackingSessionDataStore(
    path: String,
    scope: CoroutineScope
): DataStore<List<com.trackr.core.model.TrackingSession>> = DataStoreFactory.create(
    storage = OkioStorage(
        fileSystem = systemFileSystem(),
        serializer = TrackingSessionSerializer,
        producePath = { path.toPath() }
    ),
    corruptionHandler = ReplaceFileCorruptionHandler { emptyList() },
    scope = scope
)
