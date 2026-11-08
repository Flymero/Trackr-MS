package com.trackr.core.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.entity.SearchHistoryEntity
import com.trackr.core.datastore.AmapKeyKeys
import com.trackr.core.datastore.SettingsKeys
import com.trackr.core.model.ScheduledReminder
import kotlinx.coroutines.flow.first

/*
 * 新存储（USE_NEW_STORE 打开时用）：列表类进 Room，设置和高德 Key 进 DataStore（重构计划 §2.4）。
 */

/** 设置在 `trackr_settings`，搜索历史在 Room（收藏的车第 14 步挪到 [RoomFavoritesStore]） */
class RoomDataStoreTransitStore(
    private val db: TrackrDatabase,
    private val settings: DataStore<Preferences>
) : TransitLocalStore {

    override suspend fun load(): TransitLocalSnapshot {
        val prefs = settings.data.first()
        return TransitLocalSnapshot(
            selectedCityJson = prefs[SettingsKeys.SELECTED_CITY_JSON],
            engineMode = prefs[SettingsKeys.ENGINE_MODE],
            transitCodeProvider = prefs[SettingsKeys.PREFERRED_TRANSIT_CODE_PROVIDER],
            showTransitCodeButton = prefs[SettingsKeys.SHOW_TRANSIT_CODE_BUTTON],
            autoStopAfterBoarding = prefs[SettingsKeys.AUTO_STOP_AFTER_BOARDING],
            nearbyStopsCount = prefs[SettingsKeys.NEARBY_STOPS_COUNT],
            liveNotificationEnabled = prefs[SettingsKeys.LIVE_NOTIFICATION_ENABLED],
            showOnLockScreen = prefs[SettingsKeys.SHOW_ON_LOCK_SCREEN],
            alertThresholdStops = prefs[SettingsKeys.ALERT_THRESHOLD_STOPS],
            uiMode = prefs[SettingsKeys.UI_MODE],
            railMode = prefs[SettingsKeys.RAIL_MODE],
            themeMode = prefs[SettingsKeys.THEME_MODE],
            themeColorsJson = prefs[SettingsKeys.THEME_COLORS],
            fontScalePromptDismissed = prefs[SettingsKeys.FONT_SCALE_PROMPT_DISMISSED],
            searchHistory = db.searchHistory().getAll().map { it.query }
        )
    }

    override suspend fun saveSelectedCityJson(json: String) {
        settings.edit { it[SettingsKeys.SELECTED_CITY_JSON] = json }
    }

    override suspend fun saveEngineMode(name: String) {
        settings.edit { it[SettingsKeys.ENGINE_MODE] = name }
    }

    override suspend fun saveTransitCodeProvider(id: String) {
        settings.edit { it[SettingsKeys.PREFERRED_TRANSIT_CODE_PROVIDER] = id }
    }

    override suspend fun saveNearbyStopsCount(count: Int) {
        settings.edit { it[SettingsKeys.NEARBY_STOPS_COUNT] = count }
    }

    override suspend fun saveShowTransitCodeButton(enabled: Boolean) {
        settings.edit { it[SettingsKeys.SHOW_TRANSIT_CODE_BUTTON] = enabled }
    }

    override suspend fun saveAutoStopAfterBoarding(enabled: Boolean) {
        settings.edit { it[SettingsKeys.AUTO_STOP_AFTER_BOARDING] = enabled }
    }
    override suspend fun saveLiveNotificationEnabled(enabled: Boolean) {
        settings.edit { it[SettingsKeys.LIVE_NOTIFICATION_ENABLED] = enabled }
    }

    override suspend fun saveShowOnLockScreen(enabled: Boolean) {
        settings.edit { it[SettingsKeys.SHOW_ON_LOCK_SCREEN] = enabled }
    }

    override suspend fun saveAlertThresholdStops(stops: Int) {
        settings.edit { it[SettingsKeys.ALERT_THRESHOLD_STOPS] = stops }
    }

    override suspend fun saveUiMode(name: String) {
        settings.edit { it[SettingsKeys.UI_MODE] = name }
    }

    override suspend fun saveRailMode(name: String) {
        settings.edit { it[SettingsKeys.RAIL_MODE] = name }
    }

    override suspend fun saveThemeColors(json: String) {
        settings.edit { it[SettingsKeys.THEME_COLORS] = json }
    }

    override suspend fun saveThemeMode(name: String) {
        settings.edit { it[SettingsKeys.THEME_MODE] = name }
    }

    override suspend fun saveFontScalePromptDismissed(dismissed: Boolean) {
        settings.edit { it[SettingsKeys.FONT_SCALE_PROMPT_DISMISSED] = dismissed }
    }

    override suspend fun saveSearchHistory(list: List<String>) {
        // 旧版按不分大小写去重，主键是原文，这里再按原文去一次重，防止主键冲突时后面的覆盖前面的位置
        db.searchHistory().replaceAll(list.distinct().mapIndexed { index, query -> SearchHistoryEntity(query, index) })
    }

    override suspend fun clearSearchHistory() {
        db.searchHistory().deleteAll()
    }

    override suspend fun favoritesGroupMigrationVersion(): Int =
        settings.data.first()[SettingsKeys.FAVORITES_GROUP_MIGRATION_VERSION] ?: 0

    override suspend fun saveFavoritesGroupMigrationVersion(version: Int) {
        settings.edit { it[SettingsKeys.FAVORITES_GROUP_MIGRATION_VERSION] = version }
    }
}

/**
 * 收藏合一（第 14 步）：车（favorite_line）、路线（route / route_leg / route_candidate_line）、收藏分组（favorite_group）、
 * 合一索引（favorite_item）。读在一个事务里，写也在一个事务里（[com.trackr.core.database.dao.FavoritesDao.write]）。
 *
 * 车的 `position`、路线的 `position` / `sortOrder` / `customGroupId` 是写入时的镜像；顺序和分组以 favorite_item 为准，
 * 由仓库读出后套上（DefaultFavoritesRepository）。
 */
class RoomFavoritesStore(private val db: TrackrDatabase) : FavoritesStore {

    override suspend fun load(): FavoritesSnapshot {
        val rows = db.favorites().loadAll()
        return FavoritesSnapshot(
            stops = rows.stops.map { it.toModel() },
            routes = if (rows.routes.isEmpty()) null else rows.routes.map { it.toModel() },
            groups = if (rows.groups.isEmpty()) null else rows.groups.map { it.toModel() },
            items = if (rows.items.isEmpty()) null else rows.items.mapNotNull { it.toRecordOrNull() },
            groupsInitialized = rows.groupsInitialized
        )
    }

    override suspend fun write(change: FavoritesWrite) {
        db.favorites().write(
            stops = change.stops?.mapIndexed { index, item -> item.toEntity(index) },
            routes = change.routes?.mapIndexed { index, route -> route.toRows(index) },
            groups = change.groups?.mapIndexed { index, group -> group.toEntity(index) },
            items = change.items?.map { it.toEntity() },
            itemUpdates = change.itemUpdates.map { it.toEntity() },
            markGroupsInitialized = change.markGroupsInitialized
        )
    }
}

/** 定时提醒（reminder 表）；改列表时保留 lastScheduledTriggerAt */
class RoomReminderStore(private val db: TrackrDatabase) : ReminderStore {

    override suspend fun load(): List<ScheduledReminder> = db.reminders().getAll().map { it.toModel() }

    override suspend fun save(list: List<ScheduledReminder>) {
        db.reminders().replaceAllKeepingTriggers(list.mapIndexed { index, reminder -> reminder.toEntity(index) })
    }
}

/**
 * 计划触发时刻存在 `reminder.lastScheduledTriggerAt`。
 * 提醒行还不存在时（理论上不会：仓库先写提醒、再排闹钟）这次记录会被丢掉，效果等同「没记过」，开机时照常重排。
 */
class RoomReminderScheduleStateStore(private val db: TrackrDatabase) : ReminderScheduleStateStore {

    override suspend fun loadAll(): Map<String, Long> =
        db.reminders().getTriggers().mapNotNull { row -> row.lastScheduledTriggerAt?.let { row.id to it } }.toMap()

    override suspend fun set(reminderId: String, triggerAtMillis: Long) {
        db.reminders().setLastScheduledTriggerAt(reminderId, triggerAtMillis)
    }

    override suspend fun remove(reminderId: String) {
        db.reminders().setLastScheduledTriggerAt(reminderId, null)
    }
}

/** 高德 Key 在单独的 `trackr_amap_keys`（备份排除）。空串按没填处理，存的时候也不写空串 */
class DataStoreAmapKeyStore(private val keys: DataStore<Preferences>) : AmapKeyStore {
    override suspend fun loadIosKey(): String? = keys.data.first()[AmapKeyKeys.IOS]?.trim()?.ifBlank { null }
    override suspend fun saveIosKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        keys.edit { if (value == null) it.remove(AmapKeyKeys.IOS) else it[AmapKeyKeys.IOS] = value }
    }


    override suspend fun loadAndroidKey(): String? = keys.data.first()[AmapKeyKeys.ANDROID]?.trim()?.ifBlank { null }

    override suspend fun loadWebKey(): String? = keys.data.first()[AmapKeyKeys.WEB]?.trim()?.ifBlank { null }

    override suspend fun saveAndroidKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        keys.edit { if (value == null) it.remove(AmapKeyKeys.ANDROID) else it[AmapKeyKeys.ANDROID] = value }
    }

    override suspend fun saveWebKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        keys.edit { if (value == null) it.remove(AmapKeyKeys.WEB) else it[AmapKeyKeys.WEB] = value }
    }

    override suspend fun loadPrivacyConsent(): Boolean? = keys.data.first()[AmapKeyKeys.PRIVACY_CONSENT]

    override suspend fun savePrivacyConsent(consented: Boolean) {
        keys.edit { it[AmapKeyKeys.PRIVACY_CONSENT] = consented }
    }
}
