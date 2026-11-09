package com.trackr.core.data.legacy

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.trackr.core.datastore.AmapKeyKeys
import com.trackr.core.datastore.SettingsKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 设置和高德 Key 的只读 DataMigration（不用会删旧 key 的 SharedPreferencesMigration） */
@RunWith(RobolectricTestRunner::class)
class SettingsMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        LegacyFixtures.clearAll(context)
    }

    @Test
    fun settings_lastHomeGpsImportsWithoutDeletingLegacyLocation() = runBlocking {
        val saved = """{"first":30.063,"second":103.836}"""
        val legacy = LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT)
        legacy.edit().putString("last_user_location_json", saved).commit()
        val migration = LegacySettingsMigration(context, generation = 0)
        val result = migration.migrate(emptyPreferences())
        migration.cleanUp()
        assertEquals(saved, result[SettingsKeys.LAST_USER_LOCATION_JSON])
        assertEquals(saved, legacy.getString("last_user_location_json", null))
    }

    @Test
    fun settings_copiedWithTypes_andLegacyKept() = runBlocking {
        LegacyFixtures.writeAll(context)
        val before = LegacyFixtures.snapshot(context)
        val migration = LegacySettingsMigration(context, generation = 0)

        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val result = migration.migrate(emptyPreferences())
        migration.cleanUp()

        assertEquals("MYGOLBS", result[SettingsKeys.ENGINE_MODE])
        assertEquals(6, result[SettingsKeys.NEARBY_STOPS_COUNT])
        assertEquals(false, result[SettingsKeys.LIVE_NOTIFICATION_ENABLED])
        assertEquals(2, result[SettingsKeys.FAVORITES_GROUP_MIGRATION_VERSION])
        assertEquals(0, result[SettingsKeys.LEGACY_IMPORT_GEN])
        // 6 个设置 + 导入代数（D1 起聚合优先级不导入）；收藏、历史这些列表进 Room，不进设置文件
        assertEquals(7, result.asMap().size)
        assertFalse(result.asMap().keys.any { it.name == LegacyPrefs.KEY_AGGREGATED_PRIORITY })
        assertEquals(before, LegacyFixtures.snapshot(context))
    }

    @Test
    fun settings_sameGeneration_notMigratedAgain() = runBlocking {
        val migration = LegacySettingsMigration(context, generation = 2)
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(SettingsKeys.LEGACY_IMPORT_GEN to 2)))
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsKeys.LEGACY_IMPORT_GEN to 1)))
    }

    @Test
    fun settings_reimport_overwritesOnlyKeysPresentInLegacy() = runBlocking {
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit().putString("engine_mode", "CHELAILE").commit()
        val current = mutablePreferencesOf(
            SettingsKeys.ENGINE_MODE to "MYGOLBS",
            SettingsKeys.NEARBY_STOPS_COUNT to 8,
            SettingsKeys.LEGACY_IMPORT_GEN to 0
        )
        val result = LegacySettingsMigration(context, generation = 1).migrate(current)
        assertEquals("CHELAILE", result[SettingsKeys.ENGINE_MODE])
        assertEquals(8, result[SettingsKeys.NEARBY_STOPS_COUNT])
        assertEquals(1, result[SettingsKeys.LEGACY_IMPORT_GEN])
    }

    @Test
    fun settings_showOnLockScreen_copiedOnlyWhenPresent() = runBlocking {
        // 旧版没有这个键：不写，读的时候按默认开
        val untouched = LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertNull(untouched[SettingsKeys.SHOW_ON_LOCK_SCREEN])

        // 退回旧存储期间关掉过：再导入时带上
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit().putBoolean("show_on_lock_screen", false).commit()
        val result = LegacySettingsMigration(context, generation = 1).migrate(emptyPreferences())
        assertEquals(false, result[SettingsKeys.SHOW_ON_LOCK_SCREEN])
    }

    @Test
    fun settings_alertThresholdStops_copiedOnlyWhenPresent() = runBlocking {
        // 旧版没有这个键（第 25 步新加）：不写，读的时候按默认 2
        val untouched = LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertNull(untouched[SettingsKeys.ALERT_THRESHOLD_STOPS])

        // 退回旧存储期间改过：再导入时带上
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit().putInt("alert_threshold_stops", 3).commit()
        val result = LegacySettingsMigration(context, generation = 1).migrate(emptyPreferences())
        assertEquals(3, result[SettingsKeys.ALERT_THRESHOLD_STOPS])
    }

    @Test
    fun settings_wrongType_isSkippedWithoutCrash() = runBlocking {
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit()
            .putInt("engine_mode", 3)
            .putString("nearby_stops_count", "6")
            .putBoolean("selected_city_json", true)
            .commit()
        val result = LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertNull(result[SettingsKeys.ENGINE_MODE])
        assertNull(result[SettingsKeys.NEARBY_STOPS_COUNT])
        assertNull(result[SettingsKeys.SELECTED_CITY_JSON])
    }

    /** 只写 engine_mode / aggregated_source_priority 两个旧 key（null 表示不写），跑一次导入，返回导入后的数据源 */
    private fun migrateDataSource(engineMode: String?, priority: String?): String? = runBlocking {
        val edit = LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit().clear()
        engineMode?.let { edit.putString(LegacyPrefs.KEY_ENGINE_MODE, it) }
        priority?.let { edit.putString(LegacyPrefs.KEY_AGGREGATED_PRIORITY, it) }
        edit.commit()
        val result = LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertFalse(result.asMap().keys.any { it.name == LegacyPrefs.KEY_AGGREGATED_PRIORITY })
        result[SettingsKeys.ENGINE_MODE]
    }

    @Test
    fun dataSource_legacyAggregatedMode_becomesPrimarySourceOfPriority() {
        assertEquals("MYGOLBS", migrateDataSource("ALL", "MYGOLBS_FIRST"))
        assertEquals("CHELAILE", migrateDataSource("ALL", "CHELAILE_FIRST"))
        assertEquals("CHELAILE", migrateDataSource("ALL", null))
        assertEquals("CHELAILE", migrateDataSource("ALL", "garbage"))
    }

    @Test
    fun dataSource_unsetModeWithMygolbsFirst_becomesMygolbs() {
        // ded8132 之前默认就是聚合模式，没存 engine_mode 的人用的是聚合 + 优先级
        assertEquals("MYGOLBS", migrateDataSource(null, "MYGOLBS_FIRST"))
        assertNull(migrateDataSource(null, "CHELAILE_FIRST"))
        assertNull(migrateDataSource(null, null))
    }

    @Test
    fun dataSource_singleSourceMode_keptAndStalePriorityIgnored() {
        assertEquals("CHELAILE", migrateDataSource("CHELAILE", "MYGOLBS_FIRST"))
        assertEquals("MYGOLBS", migrateDataSource("MYGOLBS", "CHELAILE_FIRST"))
        assertEquals("MYGOLBS", migrateDataSource("MYGOLBS", null))
    }

    @Test
    fun dataSource_unknownMode_notWritten() {
        assertNull(migrateDataSource("BOTH", "MYGOLBS_FIRST"))
        assertNull(migrateDataSource("", null))
    }

    @Test
    fun dataSource_legacyFileUntouched() = runBlocking {
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit()
            .putString(LegacyPrefs.KEY_ENGINE_MODE, "ALL")
            .putString(LegacyPrefs.KEY_AGGREGATED_PRIORITY, "MYGOLBS_FIRST")
            .commit()
        val before = LegacyFixtures.snapshot(context)
        LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertEquals(before, LegacyFixtures.snapshot(context))
        assertEquals("ALL", LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).getString(LegacyPrefs.KEY_ENGINE_MODE, null))
    }

    @Test
    fun amapKeys_blankLegacyValue_doesNotClearNewKey() = runBlocking {
        LegacyFixtures.prefs(context, LegacyPrefs.AMAP_KEYS).edit()
            .putString("custom_amap_android_key", "  ")
            .putBoolean("migrated_from_trackr_prefs", true)
            .commit()
        val current = mutablePreferencesOf(AmapKeyKeys.ANDROID to "typed-in-new-store", AmapKeyKeys.LEGACY_IMPORT_GEN to 0)
        val result = LegacyAmapKeyMigration(context, generation = 1).migrate(current)
        assertEquals("typed-in-new-store", result[AmapKeyKeys.ANDROID])
        assertEquals(1, result[AmapKeyKeys.LEGACY_IMPORT_GEN])
    }

    @Test
    fun amapKeys_neverWrittenToSettingsMigration() = runBlocking {
        LegacyFixtures.writeAll(context)
        val result = LegacySettingsMigration(context, generation = 0).migrate(emptyPreferences())
        assertNull(result[AmapKeyKeys.ANDROID])
        assertNull(result[AmapKeyKeys.WEB])
    }

    @Test
    fun amapKeys_trimmed_andLegacyKept() = runBlocking {
        LegacyFixtures.prefs(context, LegacyPrefs.AMAP_KEYS).edit()
            .putString("custom_amap_web_key", " web ")
            .commit()
        val result = LegacyAmapKeyMigration(context, generation = 0).migrate(emptyPreferences())
        assertEquals("web", result[AmapKeyKeys.WEB])
        assertEquals(" web ", LegacyFixtures.prefs(context, LegacyPrefs.AMAP_KEYS).getString("custom_amap_web_key", null))
    }
}
