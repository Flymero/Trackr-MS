package com.trackr.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.datastore.TrackrDataStoreFiles
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GeoPoint
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.data.repository.ScheduledReminderRepository
import com.trackr.data.repository.TransitRepository
import com.trackr.util.ReminderAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 整套存储端到端（第 9 步）：旧 SharedPreferences 里的数据，经新存储读出来和经旧存储读出来**完全一样**；
 * 旧文件不被改动；USE_NEW_STORE 来回切不丢改动；新存储打不开时退回旧存储。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StorageBackendTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private val opened = mutableListOf<StorageBackend>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val city = TransitCity(cityId = "051", cityName = "莆田", pinyin = "PuTian", mygolbsCityCode = "0", province = "福建")

    private val favorites = listOf(
        FavoriteStationLine(
            id = "051_100_7", cityId = "051", cityName = "莆田", lineId = "100", lineName = "59", lineNo = "r1",
            direction = 1, startStop = "公交南站", endStop = "大学城", targetStopId = "s7", targetStopName = "七站",
            targetOrder = 7, totalStops = 36, wgsLat = 25.42, wgsLng = 119.0, addedAtMillis = 1_793_444_022_365L
        ),
        FavoriteStationLine(
            id = "mg_1", cityId = "", cityName = "莆田", lineId = "mg:1", lineName = "夜1", startStop = "A", endStop = "B",
            targetStopId = "x", targetStopName = "X", targetOrder = 2, source = DataSourceType.MYGOLBS, mygolbsCityCode = "0",
            addedAtMillis = 1L
        )
    )

    private val routes = listOf(
        TransitGroup(
            id = "r1", name = "家 ➔ 公司", category = GroupCategory.COMMUTE, sortOrder = 1, customGroupId = "commute",
            createdAt = 10L, updatedAt = 20L,
            legs = listOf(
                TransitLeg(
                    legIndex = 0, fromStopName = "甲", toStopName = "乙", fromStopLat = 25.4, fromStopLng = 119.0,
                    candidateLines = listOf(CandidateLine(cityId = "051", cityName = "莆田", lineId = "7", lineName = "7路", fromStopOrder = 21, toStopOrder = 25, mygolbsCityCode = "0")),
                    walkingDistanceMeters = 104,
                    ridePath = listOf(GeoPoint(25.4, 119.0), GeoPoint(25.41, 119.01))
                ),
                TransitLeg(legIndex = 1, fromStopName = "乙", toStopName = "丙", candidateLines = listOf(CandidateLine(cityId = "051", lineId = "331", lineName = "331路")))
            )
        ),
        TransitGroup(id = "r2", name = "公司 ➔ 家", sortOrder = 2, customGroupId = "home", createdAt = 30L, updatedAt = 40L)
    )

    private val favoriteGroups = listOf(
        FavoriteGroup("commute", "上班通勤", 0, true),
        FavoriteGroup("home", "回家线路", 1, true),
        FavoriteGroup("gym", "健身", 2, false)
    )

    private val reminders = listOf(
        ScheduledReminder(
            id = "rem-1", hour = 7, minute = 30, cityId = "051", cityName = "莆田", lineId = "100", lineName = "59",
            direction = 1, endStopName = "大学城", targetStopId = "s7", targetStopName = "七站", targetOrder = 7,
            autoStartLiveTracking = true
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listOf("trackr_prefs", "trackr_route_groups", "trackr_scheduled_reminders", "trackr_amap_keys",
            "trackr_reminder_schedule_state", StoreSwitch.PREFS_NAME).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, TrackrDataStoreFiles.DIRECTORY).deleteRecursively()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).delete()
        context.getDatabasePath(TrackrDatabase.FILE_NAME).let { db ->
            if (db.isDirectory) db.deleteRecursively() else context.deleteDatabase(TrackrDatabase.FILE_NAME)
        }
    }

    @After
    fun tearDown() {
        closeAll()
        scope.cancel()
    }

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun writeLegacy() {
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit()
            .putString("selected_city_json", json.encodeToString(city))
            .putString("engine_mode", "MYGOLBS")
            .putString("preferred_transit_code_provider", "alipay")
            .putInt("nearby_stops_count", 6)
            .putBoolean("is_live_notification_enabled", false)
            .putInt("favorites_group_migration_version", 2)
            .putString("favorites_json", json.encodeToString(favorites))
            .putString("search_history_json", json.encodeToString(listOf("59", "7路")))
            .commit()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE).edit()
            .putString("groups_list_json", json.encodeToString(routes))
            .putString("favorite_groups_list_json", json.encodeToString(favoriteGroups))
            .commit()
        context.getSharedPreferences("trackr_scheduled_reminders", Context.MODE_PRIVATE).edit()
            .putString("reminders_list_json", json.encodeToString(reminders))
            .commit()
        context.getSharedPreferences("trackr_reminder_schedule_state", Context.MODE_PRIVATE).edit()
            .putLong("next_trigger_rem-1", 1_800_000_000_000L)
            .commit()
        context.getSharedPreferences("trackr_amap_keys", Context.MODE_PRIVATE).edit()
            .putString("custom_amap_android_key", "android-key")
            .putBoolean("migrated_from_trackr_prefs", true)
            .commit()
    }

    private fun legacySnapshot(): Map<String, Map<String, *>> =
        listOf("trackr_prefs", "trackr_route_groups", "trackr_scheduled_reminders", "trackr_amap_keys", "trackr_reminder_schedule_state")
            .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }

    private fun open(forceLegacy: Boolean = false) = StorageBackend.open(context, scope, forceLegacy).also { opened += it }

    /** 仓库看到的全部用户数据 */
    private data class Seen(
        val city: TransitCity,
        val hasSavedCity: Boolean,
        val engine: EngineMode,
        val transitCode: TransitCodeProvider,
        val nearby: Int,
        val live: Boolean,
        val lockScreen: Boolean,
        val alertStops: Int,
        val favorites: List<FavoriteStationLine>,
        val history: List<String>,
        val routes: List<TransitGroup>,
        val favoriteGroups: List<FavoriteGroup>,
        val reminders: List<ScheduledReminder>,
        val trigger: Long?,
        val androidKey: String?,
        val webKey: String?,
        val migrationVersion: Int
    )

    private fun seen(backend: StorageBackend): Seen {
        val library = RouteGroupRepository.openFavorites(backend.favoritesStore)
        val transit = TransitRepository(context, TransitProviderCoordinator(emptyMap()), backend.transitStore, library, scope)
        val groups = RouteGroupRepository(library)
        val rem = ScheduledReminderRepository(backend.reminderStore, ReminderAlarmScheduler(context, backend.reminderScheduleState))
        return Seen(
            city = transit.selectedCity.value,
            hasSavedCity = transit.hasSavedCity.value,
            engine = transit.engineMode.value,
            transitCode = transit.preferredTransitCodeProvider.value,
            nearby = transit.nearbyStopsCount.value,
            live = transit.isLiveNotificationEnabled.value,
            lockScreen = transit.showOnLockScreen.value,
            alertStops = transit.alertThresholdStops.value,
            favorites = transit.favorites.value,
            history = transit.searchHistory.value,
            routes = groups.groups.value,
            favoriteGroups = groups.favoriteGroups.value,
            reminders = rem.reminders.value,
            trigger = backend.reminderScheduleState.get("rem-1"),
            androidKey = backend.amapKeys.androidKey.value,
            webKey = backend.amapKeys.webKey.value,
            migrationVersion = runBlocking { backend.transitStore.favoritesGroupMigrationVersion() }
        )
    }

    @Test
    fun newStore_seesExactlyWhatLegacyStoreSees() {
        writeLegacy()
        val before = legacySnapshot()

        val newBackend = open()
        assertTrue(newBackend.usesNewStore)
        assertNotNull(newBackend.migrationReport)
        val viaNew = seen(newBackend)

        // 新存储读、建仓库都不改旧文件
        assertEquals(before, legacySnapshot())

        val viaLegacy = seen(open(forceLegacy = true))
        assertEquals(viaLegacy, viaNew)
        assertEquals(favorites, viaNew.favorites)
        assertEquals(routes, viaNew.routes)
        assertEquals("android-key", viaNew.androidKey)
        assertEquals(1_800_000_000_000L, viaNew.trigger)
        assertTrue(viaNew.hasSavedCity)
    }

    @Test
    fun legacyAggregatedConfig_bothStoresPickSamePrimarySource() {
        // D1：旧的「聚合 + 掌上公交优先」在新旧存储里都读成掌上公交，旧文件不改
        writeLegacy()
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit()
            .putString("engine_mode", "ALL")
            .putString("aggregated_source_priority", "MYGOLBS_FIRST")
            .commit()
        val before = legacySnapshot()

        val viaNew = seen(open())
        val viaLegacy = seen(open(forceLegacy = true))

        assertEquals(EngineMode.MYGOLBS, viaNew.engine)
        assertEquals(viaLegacy, viaNew)
        assertEquals(before, legacySnapshot())
    }

    @Test
    fun freshInstall_newStoreMatchesLegacyDefaults() {
        val viaNew = seen(open())
        val viaLegacy = seen(open(forceLegacy = true))
        assertEquals(viaLegacy, viaNew)
        assertFalse(viaNew.hasSavedCity)
    }

    @Test
    fun writesInNewStore_persist_andNeverTouchLegacy() = runBlocking {
        writeLegacy()
        val before = legacySnapshot()
        val backend = open()
        val transit = TransitRepository(
            context, TransitProviderCoordinator(emptyMap()), backend.transitStore,
            RouteGroupRepository.openFavorites(backend.favoritesStore), scope
        )
        transit.removeFavorite("mg_1")
        transit.setEngineMode(EngineMode.CHELAILE)
        transit.addSearchHistory("万达")
        transit.setShowOnLockScreen(false)
        transit.setAlertThresholdStops(3)
        backend.amapKeys.setWebKey("web-new")
        transit.flushWrites()
        (backend.amapKeys as com.trackr.core.data.repository.DefaultAmapKeyRepository).flush()
        closeAll()

        assertEquals(before, legacySnapshot())
        val reopened = seen(open())
        assertFalse(reopened.lockScreen)
        assertEquals(3, reopened.alertStops)
        assertEquals(listOf("051_100_7"), reopened.favorites.map { it.id })
        assertEquals(EngineMode.CHELAILE, reopened.engine)
        assertEquals(listOf("万达", "59", "7路"), reopened.history)
        assertEquals("web-new", reopened.webKey)
        assertEquals("android-key", reopened.androidKey)
    }

    @Test
    fun switchOldThenNew_bringsBackChangesMadeInOldStore() = runBlocking {
        writeLegacy()
        seen(open())
        closeAll()

        // 关掉开关：走旧存储，改一条收藏
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("old\n")
        val legacy = open()
        assertFalse(legacy.usesNewStore)
        assertEquals(1, StoreSwitch.generation(context))
        val transit = TransitRepository(
            context, TransitProviderCoordinator(emptyMap()), legacy.transitStore,
            RouteGroupRepository.openFavorites(legacy.favoritesStore), scope
        )
        transit.removeFavorite("051_100_7")
        transit.setShowOnLockScreen(false)
        transit.setAlertThresholdStops(1)
        transit.flushWrites()
        closeAll()

        // 打开开关：重新导入，带上旧存储期间的改动
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("new")
        val back = open()
        assertTrue(back.usesNewStore)
        assertTrue(back.migrationReport!!.ran)
        val seenBack = seen(back)
        assertEquals(listOf("mg_1"), seenBack.favorites.map { it.id })
        assertFalse(seenBack.lockScreen)
        assertEquals(1, seenBack.alertStops)
    }

    @Test
    fun newStoreCannotOpen_fallsBackToLegacy_withSameData() {
        writeLegacy()
        // 让库文件打不开：在库文件的位置放一个目录
        val dbPath = context.getDatabasePath(TrackrDatabase.FILE_NAME)
        dbPath.parentFile?.mkdirs()
        assertTrue(dbPath.mkdirs())

        val backend = open()
        assertFalse(backend.usesNewStore)
        assertEquals(favorites, seen(backend).favorites)
        // 自动回退只为本次启动兜底，不应把旧存储会话代数加一；否则下次新存储打开
        // 会把这次并未主动使用旧存储的数据重新导入并覆盖用户改动。
        assertEquals(0, StoreSwitch.generation(context))
    }

    @Test
    fun stationFavorites_newOldNewSwitch_preservesOrderGroupsAndEdits() = runBlocking {
        writeLegacy()
        val official = com.trackr.core.model.FavoriteStation(
            id = "眉山_MEISHAN_ms-st:10", cityId = "", cityName = "眉山", stopName = "苏祠路口",
            stopId = "ms-st:10", source = DataSourceType.MEISHAN, addedAtMillis = 1L
        )
        val first = RouteGroupRepository.openFavorites(open().favoritesStore)
        val originalKeys = first.favoriteItems.value.map { it.key }
        first.toggleStation(official)
        first.setGroup(com.trackr.core.model.FavoriteItem.stationKey(official.id), "gym")
        val savedItems = normalizedItems(first.favoriteItems.value)
        closeAll()
        val reopened = RouteGroupRepository.openFavorites(open().favoritesStore)
        assertEquals(savedItems, reopened.favoriteItems.value)
        closeAll()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("old")
        val oldBackend = open()
        assertFalse(oldBackend.usesNewStore)
        val old = RouteGroupRepository.openFavorites(oldBackend.favoritesStore)
        // Like existing old-store semantics, changes made since the switch are imported as a new generation.
        old.toggleStation(official)
        old.setGroup(com.trackr.core.model.FavoriteItem.stationKey(official.id), "gym")
        old.updateStation(official.copy(platformLabel = "官方站台"))
        old.move(listOf(originalKeys.first(), com.trackr.core.model.FavoriteItem.stationKey(official.id)))
        val expected = normalizedItems(old.favoriteItems.value)
        closeAll()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("new")
        val newBackend = open()
        assertTrue(newBackend.usesNewStore)
        val back = RouteGroupRepository.openFavorites(newBackend.favoritesStore)
        assertEquals(expected, back.favoriteItems.value)
        assertEquals("官方站台", back.favoriteStations.value.single().platformLabel)
        back.deleteGroup("gym")
        back.removeStation(official.id)
        assertEquals(originalKeys, back.favoriteItems.value.map { it.key })
        closeAll()
        val again = RouteGroupRepository.openFavorites(open().favoritesStore)
        assertTrue(again.favoriteStations.value.isEmpty())
        assertTrue(again.favoriteItems.value.none { it.groupId == "gym" })
    }

    // Repository reopen compacts sparse ranks without changing list order, content, or grouping.
    private fun normalizedItems(items: List<com.trackr.core.model.FavoriteItem>) = items.mapIndexed { index, item ->
        when (item) {
            is com.trackr.core.model.FavoriteItem.Stop -> item.copy(sortOrder = index)
            is com.trackr.core.model.FavoriteItem.Route -> item.copy(sortOrder = index)
            is com.trackr.core.model.FavoriteItem.Station -> item.copy(sortOrder = index)
        }
    }

}
