package com.trackr.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.trackr.core.data.legacy.LegacyDataSource
import com.trackr.core.data.local.AmapKeyStore
import com.trackr.core.data.local.FavoritesSnapshot
import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.local.FavoritesWrite
import com.trackr.core.data.local.ReminderStore
import com.trackr.core.data.local.TransitLocalSnapshot
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitGroup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.trackr.data.remote.amap.AmapKeyStorage as LegacyAmapKeyStore

/*
 * 旧存储：原来的 SharedPreferences + JSON（USE_NEW_STORE 关掉时用，风险 R1）。
 *
 * 这些类是第 9 步之前三个仓库里读写 SharedPreferences 的代码原样搬出来的：文件名、key、JSON 格式、
 * 损坏时的备份（148d67c）、apply() 的写法都没变。旧存储至少保留一个发版周期，之后整体删掉。
 */

private val legacyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 宽松解析：没存过、空串或解析失败都给空列表（旧版收藏的车、搜索历史都是这样读的） */
private inline fun <reified T> decodeOrEmpty(raw: String?): List<T> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        legacyJson.decodeFromString<List<T>>(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

/** `trackr_prefs`：设置、搜索历史、收藏页数据修复版本号（收藏的车第 14 步由 [SharedPrefsFavoritesStore] 读写） */
class SharedPrefsTransitStore(private val prefs: SharedPreferences) : TransitLocalStore {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override suspend fun load(): TransitLocalSnapshot = TransitLocalSnapshot(
        // 旧版 hasSavedCity = prefs.contains(...)；存的是空串时也算存过
        selectedCityJson = if (prefs.contains(KEY_SELECTED_CITY)) prefs.getString(KEY_SELECTED_CITY, null).orEmpty() else null,
        // D1：旧的聚合配置按和新存储导入同样的规则落成主数据源（只读，不改旧文件）
        engineMode = LegacyDataSource.resolve(
            engineMode = prefs.getString(KEY_ENGINE_MODE, null),
            aggregatedPriority = prefs.getString(KEY_AGGREGATED_PRIORITY, null)
        ),
        transitCodeProvider = prefs.getString(KEY_TRANSIT_CODE_PROVIDER, null),
        showTransitCodeButton = if (prefs.contains(KEY_SHOW_TRANSIT_CODE_BUTTON)) prefs.getBoolean(KEY_SHOW_TRANSIT_CODE_BUTTON, true) else null,
        nearbyStopsCount = if (prefs.contains(KEY_NEARBY_STOPS_COUNT)) prefs.getInt(KEY_NEARBY_STOPS_COUNT, 4) else null,
        liveNotificationEnabled = if (prefs.contains(KEY_LIVE_NOTIFICATION)) prefs.getBoolean(KEY_LIVE_NOTIFICATION, true) else null,
        showOnLockScreen = if (prefs.contains(KEY_SHOW_ON_LOCK_SCREEN)) prefs.getBoolean(KEY_SHOW_ON_LOCK_SCREEN, true) else null,
        alertThresholdStops = if (prefs.contains(KEY_ALERT_THRESHOLD_STOPS)) prefs.getInt(KEY_ALERT_THRESHOLD_STOPS, 2) else null,
        uiMode = prefs.getString(KEY_UI_MODE, null),
        railMode = prefs.getString(KEY_RAIL_MODE, null),
        fontScalePromptDismissed = if (prefs.contains(KEY_FONT_SCALE_PROMPT_DISMISSED)) prefs.getBoolean(KEY_FONT_SCALE_PROMPT_DISMISSED, false) else null,
        searchHistory = decodeOrEmpty(prefs.getString(KEY_SEARCH_HISTORY, null))
    )

    override suspend fun saveSelectedCityJson(json: String) {
        prefs.edit().putString(KEY_SELECTED_CITY, json).apply()
    }

    override suspend fun saveEngineMode(name: String) {
        prefs.edit().putString(KEY_ENGINE_MODE, name).apply()
    }

    override suspend fun saveTransitCodeProvider(id: String) {
        prefs.edit().putString(KEY_TRANSIT_CODE_PROVIDER, id).apply()
    }

    override suspend fun saveNearbyStopsCount(count: Int) {
        prefs.edit().putInt(KEY_NEARBY_STOPS_COUNT, count).apply()
    }

    override suspend fun saveShowTransitCodeButton(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TRANSIT_CODE_BUTTON, enabled).apply()
    }

    override suspend fun saveLiveNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_NOTIFICATION, enabled).apply()
    }

    override suspend fun saveShowOnLockScreen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ON_LOCK_SCREEN, enabled).apply()
    }

    override suspend fun saveAlertThresholdStops(stops: Int) {
        prefs.edit().putInt(KEY_ALERT_THRESHOLD_STOPS, stops).apply()
    }

    override suspend fun saveUiMode(name: String) {
        prefs.edit().putString(KEY_UI_MODE, name).apply()
    }

    override suspend fun saveRailMode(name: String) {
        prefs.edit().putString(KEY_RAIL_MODE, name).apply()
    }

    override suspend fun saveFontScalePromptDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_FONT_SCALE_PROMPT_DISMISSED, dismissed).apply()
    }

    override suspend fun saveSearchHistory(list: List<String>) {
        prefs.edit().putString(KEY_SEARCH_HISTORY, legacyJson.encodeToString(list)).apply()
    }

    override suspend fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    override suspend fun favoritesGroupMigrationVersion(): Int = prefs.getInt(KEY_FAVORITES_GROUP_MIGRATION_VERSION, 0)

    override suspend fun saveFavoritesGroupMigrationVersion(version: Int) {
        prefs.edit().putInt(KEY_FAVORITES_GROUP_MIGRATION_VERSION, version).apply()
    }

    companion object {
        const val PREFS_NAME = "trackr_prefs"
        private const val KEY_SELECTED_CITY = "selected_city_json"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_AGGREGATED_PRIORITY = "aggregated_source_priority"
        private const val KEY_TRANSIT_CODE_PROVIDER = "preferred_transit_code_provider"
        private const val KEY_SHOW_TRANSIT_CODE_BUTTON = "show_transit_code_button"
        private const val KEY_NEARBY_STOPS_COUNT = "nearby_stops_count"
        private const val KEY_LIVE_NOTIFICATION = "is_live_notification_enabled"
        private const val KEY_SHOW_ON_LOCK_SCREEN = "show_on_lock_screen"
        private const val KEY_ALERT_THRESHOLD_STOPS = "alert_threshold_stops"
        private const val KEY_UI_MODE = "ui_mode"
        private const val KEY_RAIL_MODE = "rail_mode"
        private const val KEY_FONT_SCALE_PROMPT_DISMISSED = "font_scale_prompt_dismissed"
        /** 收藏的车（[SharedPrefsFavoritesStore] 读写） */
        const val KEY_FAVORITES = "favorites_json"
        private const val KEY_SEARCH_HISTORY = "search_history_json"
        const val KEY_FAVORITES_GROUP_MIGRATION_VERSION = "favorites_group_migration_version"
    }
}

/**
 * `trackr_route_groups`：路线、收藏分组、合一索引；JSON 损坏时先把原文存到 `<key>_corrupt_backup_<时间戳>`（只留最新一份）。
 * 第 14 步起不再单独当存储用，由 [SharedPrefsFavoritesStore] 调用。
 */
class SharedPrefsRouteGroupStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun loadGroups(): List<TransitGroup>? {
        val rawJson = prefs.getString(KEY_GROUPS_JSON, null)
        if (rawJson.isNullOrBlank()) return null
        return try {
            legacyJson.decodeFromString<List<TransitGroup>>(rawJson)
        } catch (e: Exception) {
            // 解析失败时先备份原始数据，避免下次写入无声覆盖
            Log.e(TAG, "Failed to parse groups json, backing up raw data", e)
            backupCorruptJson(KEY_GROUPS_JSON, rawJson)
            emptyList()
        }
    }

    fun loadFavoriteGroups(): List<FavoriteGroup>? {
        val rawJson = prefs.getString(KEY_FAVORITE_GROUPS_JSON, null)
        if (rawJson.isNullOrBlank()) return null
        return try {
            legacyJson.decodeFromString<List<FavoriteGroup>>(rawJson)
        } catch (e: Exception) {
            // 解析失败时先备份原始数据，再由仓库写入默认分组
            Log.e(TAG, "Failed to parse favorite groups json, backing up raw data", e)
            backupCorruptJson(KEY_FAVORITE_GROUPS_JSON, rawJson)
            null
        }
    }

    /** 合一索引（第 14 步新增）；没存过为 null。损坏时先备份原文，也给 null（仓库按车和路线现有的顺序重新推出来） */
    fun loadFavoriteItems(): List<FavoriteItemRecord>? {
        val rawJson = prefs.getString(KEY_FAVORITE_ITEMS, null)
        if (rawJson.isNullOrBlank()) return null
        return try {
            legacyJson.decodeFromString<List<FavoriteItemRecord>>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse favorite items json, backing up raw data", e)
            backupCorruptJson(KEY_FAVORITE_ITEMS, rawJson)
            null
        }
    }

    fun saveGroups(list: List<TransitGroup>) {
        try {
            prefs.edit().putString(KEY_GROUPS_JSON, legacyJson.encodeToString(list)).apply()
        } catch (_: Exception) {
        }
    }

    fun saveFavoriteGroups(list: List<FavoriteGroup>) {
        try {
            prefs.edit().putString(KEY_FAVORITE_GROUPS_JSON, legacyJson.encodeToString(list)).apply()
        } catch (_: Exception) {
        }
    }

    /** 分组建过没有（写过默认分组，或者用户自己建过 / 删过分组）；和 `favorite_groups_list_json` 一起写 */
    fun hasFavoriteGroupsInitialized(): Boolean = prefs.getBoolean(KEY_FAVORITE_GROUPS_INITIALIZED, false)

    fun markFavoriteGroupsInitialized() {
        try {
            prefs.edit().putBoolean(KEY_FAVORITE_GROUPS_INITIALIZED, true).apply()
        } catch (_: Exception) {
        }
    }

    fun saveFavoriteItems(list: List<FavoriteItemRecord>) {
        try {
            prefs.edit().putString(KEY_FAVORITE_ITEMS, legacyJson.encodeToString(list)).apply()
        } catch (_: Exception) {
        }
    }

    /** 将无法解析的原始 JSON 备份到 `<key>_corrupt_backup_<时间戳>`，同一 key 只保留最近一份 */
    private fun backupCorruptJson(key: String, rawJson: String) {
        val backupPrefix = key + CORRUPT_BACKUP_SUFFIX
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(backupPrefix) }
            .forEach { editor.remove(it) }
        editor.putString(backupPrefix + System.currentTimeMillis(), rawJson)
        editor.apply()
    }

    companion object {
        private const val TAG = "RouteGroupRepository"
        const val PREFS_NAME = "trackr_route_groups"
        const val CORRUPT_BACKUP_SUFFIX = "_corrupt_backup_"
        private const val KEY_GROUPS_JSON = "groups_list_json"
        private const val KEY_FAVORITE_GROUPS_JSON = "favorite_groups_list_json"

        /**
         * 收藏分组建过没有（v0.1.6 起）。旧版本没有这个 key：那时写的 `[]` 就是「删光了」，
         * 空列表已经能区分（见 DefaultFavoritesRepository.create）。
         */
        private const val KEY_FAVORITE_GROUPS_INITIALIZED = "favorite_groups_initialized"

        /** 合一索引；和 :core:data 里 LegacyPrefs.KEY_FAVORITE_ITEMS 必须一致（新存储导入时读它） */
        const val KEY_FAVORITE_ITEMS = "favorite_items_json"
    }
}

/**
 * 收藏合一的旧存储（第 14 步）：收藏的车在 `trackr_prefs` 的 `favorites_json`，路线、收藏分组、合一索引在
 * `trackr_route_groups`（见 [SharedPrefsRouteGroupStore]）。读法和第 14 步之前一样；写入时只写给了的部分，各自 apply()。
 */
class SharedPrefsFavoritesStore(
    private val transitPrefs: SharedPreferences,
    routeGroupPrefs: SharedPreferences
) : FavoritesStore {

    constructor(context: Context) : this(
        context.getSharedPreferences(SharedPrefsTransitStore.PREFS_NAME, Context.MODE_PRIVATE),
        context.getSharedPreferences(SharedPrefsRouteGroupStore.PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val routeGroups = SharedPrefsRouteGroupStore(routeGroupPrefs)

    /** 最近一次读到或写下的合一索引；[FavoritesWrite.itemUpdates] 合并到它上面再整份写回 */
    private var cachedItems: List<FavoriteItemRecord>? = null

    override suspend fun load(): FavoritesSnapshot {
        val items = routeGroups.loadFavoriteItems()
        cachedItems = items
        val groups = routeGroups.loadFavoriteGroups()
        return FavoritesSnapshot(
            stops = decodeOrEmpty<FavoriteStationLine>(transitPrefs.getString(SharedPrefsTransitStore.KEY_FAVORITES, null)),
            routes = routeGroups.loadGroups(),
            groups = groups,
            items = items,
            // JSON 读不出来（null）说明文件坏了，按「没建过」处理，和旧版一样回落到两个默认分组；
            // 存成 "[]" 读到的是空列表，那才是用户自己删光的
            groupsInitialized = groups != null && routeGroups.hasFavoriteGroupsInitialized()
        )
    }

    override suspend fun write(change: FavoritesWrite) {
        change.stops?.let { stops ->
            transitPrefs.edit().putString(SharedPrefsTransitStore.KEY_FAVORITES, legacyJson.encodeToString(stops)).apply()
        }
        // 旧版删收藏分组时就是先写收藏分组、再写路线
        change.groups?.let { routeGroups.saveFavoriteGroups(it) }
        change.routes?.let { routeGroups.saveGroups(it) }
        if (change.markGroupsInitialized) routeGroups.markFavoriteGroupsInitialized()
        if (change.items != null || change.itemUpdates.isNotEmpty()) {
            val full = change.items ?: mergeByKey(cachedItems.orEmpty(), change.itemUpdates)
            routeGroups.saveFavoriteItems(full)
            cachedItems = full
        }
    }

    /** 同 key 的换成新的，没有的加在后面 */
    private fun mergeByKey(base: List<FavoriteItemRecord>, updates: List<FavoriteItemRecord>): List<FavoriteItemRecord> {
        val updateByKey = updates.associateBy { it.key }
        val baseKeys = base.mapTo(HashSet()) { it.key }
        return base.map { updateByKey[it.key] ?: it } + updateByKey.values.filterNot { it.key in baseKeys }
    }
}

/** `trackr_scheduled_reminders`：定时提醒；JSON 损坏时先把原文存到 `reminders_list_json_corrupt_backup_<时间戳>` */
class SharedPrefsReminderStore(private val prefs: SharedPreferences) : ReminderStore {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override suspend fun load(): List<ScheduledReminder> {
        val rawJson = prefs.getString(KEY_REMINDERS_JSON, null) ?: return emptyList()
        return try {
            legacyJson.decodeFromString<List<ScheduledReminder>>(rawJson)
        } catch (e: Exception) {
            // 解析失败时先备份原始数据，避免下次写入无声覆盖
            Log.e(TAG, "Failed to parse reminders json, backing up raw data", e)
            backupCorruptJson(rawJson)
            emptyList()
        }
    }

    override suspend fun save(list: List<ScheduledReminder>) {
        try {
            prefs.edit().putString(KEY_REMINDERS_JSON, legacyJson.encodeToString(list)).apply()
        } catch (_: Exception) {
        }
    }

    private fun backupCorruptJson(rawJson: String) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_CORRUPT_BACKUP_PREFIX) }
            .forEach { editor.remove(it) }
        editor.putString(KEY_CORRUPT_BACKUP_PREFIX + System.currentTimeMillis(), rawJson)
        editor.apply()
    }

    companion object {
        private const val TAG = "ScheduledReminderRepo"
        const val PREFS_NAME = "trackr_scheduled_reminders"
        private const val KEY_REMINDERS_JSON = "reminders_list_json"
        const val KEY_CORRUPT_BACKUP_PREFIX = "reminders_list_json_corrupt_backup_"
    }
}

/**
 * 高德 Key 的旧存储：`trackr_amap_keys`（SharedPreferences）。读写走原来的 `AmapKeyStorage` 函数，
 * 打开时照旧把 0a4fdab 之前存在 `trackr_prefs` 里的 Key 搬过来（[LegacyAmapKeyStore.prefs]）。
 */
class SharedPrefsAmapKeyStore(private val prefs: SharedPreferences?) : AmapKeyStore {

    constructor(context: Context) : this(LegacyAmapKeyStore.prefs(context))

    override suspend fun loadAndroidKey(): String? = LegacyAmapKeyStore.readAndroidKey(prefs)

    override suspend fun loadWebKey(): String? = LegacyAmapKeyStore.readWebKey(prefs)

    override suspend fun saveAndroidKey(key: String?) = LegacyAmapKeyStore.writeAndroidKey(prefs, key)

    override suspend fun saveWebKey(key: String?) = LegacyAmapKeyStore.writeWebKey(prefs, key)

    override suspend fun loadPrivacyConsent(): Boolean? = prefs?.takeIf { it.contains(KEY_PRIVACY_CONSENT) }
        ?.getBoolean(KEY_PRIVACY_CONSENT, false)

    override suspend fun savePrivacyConsent(consented: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PRIVACY_CONSENT, consented)?.apply()
    }

    private companion object {
        const val KEY_PRIVACY_CONSENT = "amap_privacy_consent"
    }
}
