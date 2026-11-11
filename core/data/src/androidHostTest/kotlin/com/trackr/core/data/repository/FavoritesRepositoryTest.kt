package com.trackr.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.trackr.core.data.legacy.LegacyAmapKeyMigration
import com.trackr.core.data.legacy.LegacyFixtures
import com.trackr.core.data.legacy.LegacyLibraryMigration
import com.trackr.core.data.legacy.LegacyPrefs
import com.trackr.core.data.legacy.LegacySettingsMigration
import com.trackr.core.data.local.FavoritesWrite
import com.trackr.core.data.local.RoomFavoritesStore
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.createInMemoryTrackrDatabase
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.datastore.TrackrDataStoreFiles
import com.trackr.core.datastore.createPreferencesDataStore
import com.trackr.core.datastore.trackrDataStorePath
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.GroupColor
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 收藏合一仓库（重构计划第 14 步）在真 Room 内存库上的行为：合并排序、改分组、删分组、旧数据迁入后顺序不变、启动对账。
 * 「重开」= 在同一个库上再建一个仓库，看写下去的东西读回来是不是一样。
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: TrackrDatabase
    private var scope: CoroutineScope? = null
    private var idCounter = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        LegacyFixtures.clearAll(context)
        File(context.filesDir, LegacyPrefs.BACKUP_DIR).deleteRecursively()
        File(context.filesDir, TrackrDataStoreFiles.DIRECTORY).deleteRecursively()
        db = createInMemoryTrackrDatabase(context)
        idCounter = 0
    }

    @After
    fun tearDown() = runBlocking {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        scope = null
        db.close()
    }

    private suspend fun open(): DefaultFavoritesRepository = DefaultFavoritesRepository.create(
        RoomFavoritesStore(db),
        ioDispatcher = Dispatchers.Unconfined,
        now = { NOW },
        newId = { "g${++idCounter}" }
    )

    private fun stop(id: String) = FavoriteStationLine(
        id = id, cityId = "051", cityName = "莆田", lineId = "L$id", lineName = "${id}路",
        startStop = "起点", endStop = "终点", targetStopId = "s$id", targetStopName = "站$id", targetOrder = 3,
        addedAtMillis = 1L
    )

    private fun route(
        id: String,
        sortOrder: Int = 0,
        customGroupId: String? = null,
        category: GroupCategory = GroupCategory.CUSTOM,
        name: String = "路线$id"
    ) = TransitGroup(
        id = id,
        name = name,
        category = category,
        legs = listOf(
            TransitLeg(
                legIndex = 0, fromStopName = "甲", toStopName = "乙",
                candidateLines = listOf(CandidateLine(cityId = "051", lineId = "line-$id", lineName = "1路"))
            )
        ),
        sortOrder = sortOrder,
        customGroupId = customGroupId,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun DefaultFavoritesRepository.keys() = favoriteItems.value.map { it.key }
    private fun DefaultFavoritesRepository.groupOf(key: String) = favoriteItems.value.single { it.key == key }.groupId
    private fun DefaultFavoritesRepository.routeById(id: String) = routes.value.single { it.id == id }

    /**
     * 仓库的不变式：路线的 customGroupId 和合一列表里的分组一致；路线按合一顺序排，
     * 路线自己的 sortOrder（旧字段，只在路线之间排）跟着这个先后不减。
     */
    private fun assertRoutesMirrorItems(repo: DefaultFavoritesRepository) {
        val items = repo.favoriteItems.value.filterIsInstance<FavoriteItem.Route>()
        assertEquals(items.map { it.route.id }, repo.routes.value.map { it.id })
        repo.routes.value.forEach { route ->
            assertEquals(items.single { it.route.id == route.id }.groupId, route.customGroupId)
        }
        val orders = repo.routes.value.map { it.sortOrder }
        assertEquals(orders.sorted(), orders)
    }

    private fun stopKey(id: String) = FavoriteItem.stopKey(id)
    private fun routeKey(id: String) = FavoriteItem.routeKey(id)

    /** 车 c、b、a 在前，路线 r1、r2 在后 */
    private suspend fun seeded(): DefaultFavoritesRepository = open().apply {
        toggleFavorite(stop("a"))
        toggleFavorite(stop("b"))
        saveRoute(route("r1"))
        saveRoute(route("r2"))
        toggleFavorite(stop("c"))
    }

    // ---- 合并排序 ----

    @Test
    fun merge_newStopGoesFirst_newRouteGoesLast() = runBlocking {
        val repo = seeded()

        assertEquals(
            listOf(stopKey("c"), stopKey("b"), stopKey("a"), routeKey("r1"), routeKey("r2")),
            repo.keys()
        )
        assertEquals(listOf("c", "b", "a"), repo.favorites.value.map { it.id })
        assertEquals(listOf("r1", "r2"), repo.routes.value.map { it.id })
        assertTrue(repo.favoriteItems.value.zipWithNext().all { (x, y) -> x.sortOrder < y.sortOrder })
        assertEquals(NOW, repo.routeById("r1").updatedAt)
        assertRoutesMirrorItems(repo)

        val reopened = open()
        assertEquals(repo.keys(), reopened.keys())
        assertEquals(listOf("c", "b", "a"), reopened.favorites.value.map { it.id })
    }

    @Test
    fun move_acrossTypes_viewsFollowUnifiedOrder_andPersists() = runBlocking {
        val repo = seeded()

        repo.move(listOf(routeKey("r2"), stopKey("c"), routeKey("r1"), stopKey("a"), stopKey("b")))

        val expected = listOf(routeKey("r2"), stopKey("c"), routeKey("r1"), stopKey("a"), stopKey("b"))
        assertEquals(expected, repo.keys())
        assertEquals(listOf("c", "a", "b"), repo.favorites.value.map { it.id })
        assertEquals(listOf("r2", "r1"), repo.routes.value.map { it.id })
        assertRoutesMirrorItems(repo)

        val reopened = open()
        assertEquals(expected, reopened.keys())
        assertEquals(listOf("c", "a", "b"), reopened.favorites.value.map { it.id })
        assertEquals(listOf("r2", "r1"), reopened.routes.value.map { it.id })
        assertRoutesMirrorItems(reopened)
        assertEquals(expected, db.favorites().getItems().map { it.id })
    }

    @Test
    fun move_subset_swapsOnlyThoseSlots() = runBlocking {
        val repo = seeded()
        val before = repo.favoriteItems.value.associate { it.key to it.sortOrder }

        // 只给两条：r2 占 b 原来的位置，b 占 r2 原来的位置，其余不动
        repo.move(listOf(routeKey("r2"), stopKey("b")))

        assertEquals(
            listOf(stopKey("c"), routeKey("r2"), stopKey("a"), routeKey("r1"), stopKey("b")),
            repo.keys()
        )
        val after = repo.favoriteItems.value.associate { it.key to it.sortOrder }
        assertEquals(before.getValue(stopKey("b")), after.getValue(routeKey("r2")))
        assertEquals(before.getValue(routeKey("r2")), after.getValue(stopKey("b")))
        listOf(stopKey("c"), stopKey("a"), routeKey("r1")).forEach { assertEquals(before[it], after[it]) }

        assertEquals(repo.keys(), open().keys())
    }

    @Test
    fun move_unknownKeysIgnored() = runBlocking {
        val repo = seeded()
        val before = repo.keys()

        // 只剩一条认得出的：什么都不变
        repo.move(listOf("route_nope", "stop_zzz", stopKey("c")))
        assertEquals(before, repo.keys())

        // 认得出的两条照常对调
        repo.move(listOf("route_nope", routeKey("r1"), "stop_zzz", stopKey("c")))
        assertEquals(
            listOf(routeKey("r1"), stopKey("b"), stopKey("a"), stopKey("c"), routeKey("r2")),
            repo.keys()
        )
        assertEquals(repo.keys(), open().keys())
    }

    @Test
    fun toggleAndRemove_dropTheirItemRows() = runBlocking {
        val repo = seeded()

        assertFalse(repo.toggleFavorite(stop("b")))
        repo.removeRoute("r1")
        repo.removeFavorite("unknown")

        val expected = listOf(stopKey("c"), stopKey("a"), routeKey("r2"))
        assertEquals(expected, repo.keys())
        assertEquals(expected.toSet(), db.favorites().getItems().map { it.id }.toSet())
        assertEquals(expected, open().keys())
    }

    // ---- 改分组 ----

    @Test
    fun setGroup_onStopAndRoute_routeCustomGroupIdFollows_andPersists() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup(" 健身 ")
        assertEquals("g1", gym.id)
        assertEquals("健身", gym.name)

        assertTrue(repo.setGroup(stopKey("a"), gym.id))
        assertTrue(repo.setGroup(routeKey("r1"), gym.id))

        assertEquals(gym.id, repo.groupOf(stopKey("a")))
        assertEquals(gym.id, repo.groupOf(routeKey("r1")))
        assertEquals(gym.id, repo.routeById("r1").customGroupId)
        assertRoutesMirrorItems(repo)

        val reopened = open()
        assertEquals(gym.id, reopened.groupOf(stopKey("a")))
        assertEquals(gym.id, reopened.groupOf(routeKey("r1")))
        assertEquals(gym.id, reopened.routeById("r1").customGroupId)
        assertNull(reopened.groupOf(stopKey("b")))
        // 路线表里的镜像列也写了
        assertEquals(gym.id, db.routes().getRoutes().single { it.id == "r1" }.customGroupId)
        // 改分组不动顺序
        assertEquals(repo.keys(), reopened.keys())
    }

    @Test
    fun setGroup_missingGroupOrUnknownKey_returnsFalseAndChangesNothing() = runBlocking {
        val repo = seeded()
        assertTrue(repo.setGroup(routeKey("r2"), FavoriteItemOrder.HOME_GROUP_ID))
        val itemsBefore = repo.favoriteItems.value
        val rowsBefore = db.favorites().getItems()

        assertFalse(repo.setGroup(routeKey("r2"), "no-such-group"))
        assertFalse(repo.setGroup(stopKey("a"), "no-such-group"))
        assertFalse(repo.setGroup("route_unknown", FavoriteItemOrder.HOME_GROUP_ID))

        assertEquals(itemsBefore, repo.favoriteItems.value)
        assertEquals(FavoriteItemOrder.HOME_GROUP_ID, repo.routeById("r2").customGroupId)
        assertEquals(rowsBefore, db.favorites().getItems())
    }

    @Test
    fun setGroup_null_ungroups_andPersists() = runBlocking {
        val repo = seeded()
        repo.setGroup(stopKey("a"), FavoriteItemOrder.COMMUTE_GROUP_ID)
        repo.setGroup(routeKey("r1"), FavoriteItemOrder.COMMUTE_GROUP_ID)

        assertTrue(repo.setGroup(stopKey("a"), null))
        assertTrue(repo.setGroup(routeKey("r1"), null))

        assertNull(repo.groupOf(stopKey("a")))
        assertNull(repo.routeById("r1").customGroupId)
        val reopened = open()
        assertNull(reopened.groupOf(stopKey("a")))
        assertNull(reopened.groupOf(routeKey("r1")))
        assertNull(reopened.routeById("r1").customGroupId)
    }

    // ---- 删分组 ----

    @Test
    fun deleteGroup_itemsBecomeUngrouped_notDeleted_otherGroupsUntouched() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup("健身")
        val school = repo.createGroup("学校")
        repo.setGroup(stopKey("a"), gym.id)
        repo.setGroup(routeKey("r1"), gym.id)
        repo.setGroup(stopKey("b"), school.id)
        repo.setGroup(routeKey("r2"), FavoriteItemOrder.HOME_GROUP_ID)
        val keysBefore = repo.keys()

        repo.deleteGroup(gym.id)

        fun check(r: DefaultFavoritesRepository) {
            assertEquals(keysBefore, r.keys())
            assertNull(r.groupOf(stopKey("a")))
            assertNull(r.groupOf(routeKey("r1")))
            assertNull(r.routeById("r1").customGroupId)
            assertEquals(school.id, r.groupOf(stopKey("b")))
            assertEquals(FavoriteItemOrder.HOME_GROUP_ID, r.groupOf(routeKey("r2")))
            assertEquals(FavoriteItemOrder.HOME_GROUP_ID, r.routeById("r2").customGroupId)
            assertEquals(
                listOf(FavoriteItemOrder.COMMUTE_GROUP_ID, FavoriteItemOrder.HOME_GROUP_ID, school.id),
                r.favoriteGroups.value.map { it.id }
            )
            assertTrue(r.favorites.value.any { it.id == "a" })
            assertTrue(r.routes.value.any { it.id == "r1" })
            assertRoutesMirrorItems(r)
        }
        check(repo)
        check(open())
        assertTrue(db.favorites().getItems().none { it.groupId == gym.id })
        assertTrue(db.routes().getRoutes().none { it.customGroupId == gym.id })
    }

    // ---- 分组颜色（issue #38） ----

    @Test
    fun groupColor_createAndChange_persists_otherGroupsUntouched() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup("健身", GroupColor.Preset.GREEN)
        val school = repo.createGroup("学校")
        assertEquals(GroupColor.Preset.GREEN, gym.color)
        assertNull(school.color)

        repo.setGroupColor(school.id, GroupColor.Preset.PINK)
        repo.setGroupColor(FavoriteItemOrder.HOME_GROUP_ID, GroupColor.Preset.BLUE)
        repo.setGroupColor(gym.id, null)
        // 不存在的分组：什么都不做
        repo.setGroupColor("missing", GroupColor.Preset.RED)

        fun check(r: DefaultFavoritesRepository) {
            assertEquals(
                listOf(FavoriteItemOrder.COMMUTE_GROUP_ID, FavoriteItemOrder.HOME_GROUP_ID, gym.id, school.id),
                r.favoriteGroups.value.map { it.id }
            )
            assertEquals(listOf(null, GroupColor.Preset.BLUE, null, GroupColor.Preset.PINK), r.favoriteGroups.value.map { it.color })
            assertEquals(listOf("上班通勤", "回家线路", "健身", "学校"), r.favoriteGroups.value.map { it.name })
        }
        check(repo)
        check(open())
        assertEquals(listOf(null, "BLUE", null, "PINK"), db.favoriteGroups().getAll().map { it.color })
    }

    @Test
    fun groupColor_survivesRenameAndReorder() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup("健身", GroupColor.Preset.ORANGE)
        repo.renameGroup(gym.id, "游泳")
        repo.reorderGroups(listOf(gym.id))

        val reopened = open()
        val group = reopened.favoriteGroups.value.first()
        assertEquals(gym.id, group.id)
        assertEquals("游泳", group.name)
        assertEquals(GroupColor.Preset.ORANGE, group.color)
    }

    @Test
    fun groupColor_unknownStoredValue_readsAsNoColor() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup("健身", GroupColor.Preset.RED)
        // 以后的版本加了新颜色、又装回这个版本：认不出的名字按不设颜色读，分组本身照常
        val rows = db.favoriteGroups().getAll().map { if (it.id == gym.id) it.copy(color = "ULTRAVIOLET") else it }
        db.favoriteGroups().replaceAll(rows)

        val group = open().favoriteGroups.value.single { it.id == gym.id }
        assertEquals("健身", group.name)
        assertNull(group.color)
    }

    @Test
    fun groupColor_custom_storedAsHex_andReadBack() = runBlocking {
        val repo = seeded()
        val gym = repo.createGroup("健身", GroupColor.Custom(0x12AB9F))
        repo.setGroupColor(FavoriteItemOrder.HOME_GROUP_ID, GroupColor.Custom(0x000000))

        assertEquals("#12AB9F", db.favoriteGroups().getAll().single { it.id == gym.id }.color)
        val reopened = open()
        assertEquals(GroupColor.Custom(0x12AB9F), reopened.favoriteGroups.value.single { it.id == gym.id }.color)
        assertEquals(GroupColor.Custom(0), reopened.favoriteGroups.value.single { it.id == FavoriteItemOrder.HOME_GROUP_ID }.color)

        // 自定义换回色板颜色
        reopened.setGroupColor(gym.id, GroupColor.Preset.TEAL)
        assertEquals("TEAL", db.favoriteGroups().getAll().single { it.id == gym.id }.color)
    }

    @Test
    fun groupColor_keys() {
        assertEquals("BLUE", GroupColor.Preset.BLUE.key)
        assertEquals("#0A0B0C", GroupColor.Custom(0x0A0B0C).key)
        assertEquals(GroupColor.Preset.PINK, GroupColor.fromKey("PINK"))
        assertEquals(GroupColor.Custom(0xABCDEF), GroupColor.fromKey("#abcdef"))
        listOf(null, "", "pink", "#12345", "#1234567", "12AB9F", "#GGGGGG").forEach { assertNull(it, GroupColor.fromKey(it)) }
        // JSON 里仍然是 color 字段、字符串值（和第一版存的格式一样）
        val encoded = json.encodeToString(FavoriteGroup("g", "健身", color = GroupColor.Custom(0x12AB9F)))
        assertTrue(encoded, encoded.contains("\"color\":\"#12AB9F\""))
        assertEquals(GroupColor.Preset.RED, json.decodeFromString<FavoriteGroup>("""{"id":"g","name":"n","color":"RED"}""").color)
        // 认不出的值：整份照样读出来，只是没颜色
        assertNull(json.decodeFromString<FavoriteGroup>("""{"id":"g","name":"n","color":"ULTRAVIOLET"}""").color)
    }

    @Test
    fun legacyImport_keepsGroupColor() = runBlocking {
        val groups = listOf(FavoriteGroup("gym", "健身", sortOrder = 0, color = GroupColor.Preset.TEAL))
        writeLegacy(groups)
        assertTrue(runLegacyMigration().ran)

        assertEquals(GroupColor.Preset.TEAL, open().favoriteGroups.value.single().color)
    }

    // ---- 旧数据迁入后顺序不变 ----

    /** 和 LegacyLibraryMigrationTest 一样建两个 DataStore，跑一次旧数据导入 */
    private suspend fun runLegacyMigration(): LegacyLibraryMigration.Report {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        val settings: DataStore<Preferences> = createPreferencesDataStore(
            context.trackrDataStorePath(TrackrDataStoreFiles.SETTINGS),
            newScope,
            listOf(LegacySettingsMigration(context, 0))
        )
        val keys: DataStore<Preferences> = createPreferencesDataStore(
            context.trackrDataStorePath(TrackrDataStoreFiles.AMAP_KEYS),
            newScope,
            listOf(LegacyAmapKeyMigration(context, 0))
        )
        return LegacyLibraryMigration(context, db, settings, keys, generation = 0).runIfNeeded()
    }

    /**
     * 旧数据：车 s3、s1、s2（JSON 里的顺序）；路线在数组里是 rA、rB、rC、rD，但旧收藏页按 sortOrder 排：
     * rB(0)、rD(0，和 rB 相同时按数组先后)、rC(1)、rA(2)。
     */
    private fun writeLegacy(groups: List<FavoriteGroup>) {
        val stops = listOf(stop("s3"), stop("s1"), stop("s2"))
        val routes = listOf(
            route("rA", sortOrder = 2, customGroupId = "gym"),
            route("rB", sortOrder = 0, category = GroupCategory.COMMUTE),
            route("rC", sortOrder = 1, name = "公司返程"),
            route("rD", sortOrder = 0)
        )
        LegacyFixtures.prefs(context, LegacyPrefs.TRANSIT).edit()
            .putString(LegacyPrefs.KEY_FAVORITES, json.encodeToString(stops))
            .commit()
        LegacyFixtures.prefs(context, LegacyPrefs.ROUTE_GROUPS).edit()
            .putString(LegacyPrefs.KEY_GROUPS, json.encodeToString(routes))
            .putString(LegacyPrefs.KEY_FAVORITE_GROUPS, json.encodeToString(groups))
            .commit()
    }

    @Test
    fun legacyImport_emptyGroupsStaysEmpty() = runBlocking {
        // 旧版本里已经把两个默认分组删光了（存成 "[]"），迁进新库之后不该再补回来
        LegacyFixtures.prefs(context, LegacyPrefs.ROUTE_GROUPS).edit()
            .putString(LegacyPrefs.KEY_FAVORITE_GROUPS, "[]")
            .commit()
        runLegacyMigration()

        val repo = open()

        assertTrue(repo.favoriteGroups.value.isEmpty())
        assertTrue(db.favoriteGroups().getAll().isEmpty())
    }

    @Test
    fun legacyImport_thenCreate_keepsOldOrderAndGroups() = runBlocking {
        val groups = listOf(
            FavoriteGroup("gym", "健身", sortOrder = 0),
            FavoriteGroup(FavoriteItemOrder.COMMUTE_GROUP_ID, "上班通勤", sortOrder = 1, isDefault = true),
            FavoriteGroup(FavoriteItemOrder.HOME_GROUP_ID, "回家线路", sortOrder = 2, isDefault = true)
        )
        writeLegacy(groups)
        assertTrue(runLegacyMigration().ran)
        assertTrue(db.favorites().getItems().isEmpty())

        val repo = open()

        val expectedKeys = listOf(
            stopKey("s3"), stopKey("s1"), stopKey("s2"),
            routeKey("rB"), routeKey("rD"), routeKey("rC"), routeKey("rA")
        )
        val expectedGroups = listOf(null, null, null, FavoriteItemOrder.COMMUTE_GROUP_ID, null, FavoriteItemOrder.HOME_GROUP_ID, "gym")

        fun check(r: DefaultFavoritesRepository) {
            assertEquals(expectedKeys, r.keys())
            assertEquals(expectedGroups, r.favoriteItems.value.map { it.groupId })
            assertEquals(listOf("s3", "s1", "s2"), r.favorites.value.map { it.id })
            assertEquals(listOf("rB", "rD", "rC", "rA"), r.routes.value.map { it.id })
            assertEquals(
                listOf(FavoriteItemOrder.COMMUTE_GROUP_ID, null, FavoriteItemOrder.HOME_GROUP_ID, "gym"),
                r.routes.value.map { it.customGroupId }
            )
            assertEquals(groups, r.favoriteGroups.value)
            assertRoutesMirrorItems(r)
        }
        check(repo)

        // 只打开不写盘：索引是在内存里推出来的，重开推出来的一样
        assertTrue(db.favorites().getItems().isEmpty())
        check(open())

        // 第一次改动时整份索引一起写进 favorite_item，之后按行改也不会丢顺序
        repo.setGroup(stopKey("s1"), "gym")
        assertEquals(expectedKeys, db.favorites().getItems().map { it.id })
        assertEquals((0 until 7).toList(), db.favorites().getItems().map { it.sortOrder })
        val reopened = open()
        assertEquals(expectedKeys, reopened.keys())
        assertEquals("gym", reopened.groupOf(stopKey("s1")))
    }

    @Test
    fun legacyImport_derivedGroupsOnlyWhenThatGroupExists() = runBlocking {
        // 没有 commute / home 分组：COMMUTE 和「返程」路线都不补分组
        writeLegacy(listOf(FavoriteGroup("gym", "健身", sortOrder = 0)))
        runLegacyMigration()

        val repo = open()

        assertNull(repo.groupOf(routeKey("rB")))
        assertNull(repo.groupOf(routeKey("rC")))
        assertEquals("gym", repo.groupOf(routeKey("rA")))
        assertEquals(listOf("gym"), repo.favoriteGroups.value.map { it.id })
    }

    @Test
    fun legacyImport_withFavoriteItemsJson_orderFromIndex() = runBlocking {
        writeLegacy(
            listOf(
                FavoriteGroup(FavoriteItemOrder.COMMUTE_GROUP_ID, "上班通勤", sortOrder = 0, isDefault = true),
                FavoriteGroup(FavoriteItemOrder.HOME_GROUP_ID, "回家线路", sortOrder = 1, isDefault = true),
                FavoriteGroup("gym", "健身", sortOrder = 2)
            )
        )
        // 旧存储模式下跑过第 14 步：索引里路线 rA 被拖到了最前，车 s1 放进了 gym
        val items = listOf(
            FavoriteItemRecord(FavoriteItemType.ROUTE, "rA", "gym", 0),
            FavoriteItemRecord(FavoriteItemType.STOP, "s3", null, 1),
            FavoriteItemRecord(FavoriteItemType.STOP, "s1", "gym", 2),
            FavoriteItemRecord(FavoriteItemType.STOP, "s2", null, 3),
            FavoriteItemRecord(FavoriteItemType.ROUTE, "rB", FavoriteItemOrder.COMMUTE_GROUP_ID, 4),
            FavoriteItemRecord(FavoriteItemType.ROUTE, "rD", null, 5),
            FavoriteItemRecord(FavoriteItemType.ROUTE, "rC", FavoriteItemOrder.HOME_GROUP_ID, 6)
        )
        LegacyFixtures.prefs(context, LegacyPrefs.ROUTE_GROUPS).edit()
            .putString(LegacyPrefs.KEY_FAVORITE_ITEMS, json.encodeToString(items))
            .commit()
        runLegacyMigration()

        val repo = open()

        assertEquals(items.map { it.key }, repo.keys())
        assertEquals("gym", repo.groupOf(stopKey("s1")))
        assertEquals(listOf("rA", "rB", "rD", "rC"), repo.routes.value.map { it.id })
        assertRoutesMirrorItems(repo)
    }

    // ---- 启动对账 ----

    @Test
    fun create_reconcilesIndex_dropsOrphans_prependsMissingStops_appendsMissingRoutes() = runBlocking {
        RoomFavoritesStore(db).write(
            FavoritesWrite(
                stops = listOf(stop("a"), stop("b")),
                routes = listOf(route("r1"), route("r2", category = GroupCategory.COMMUTE)),
                groups = DefaultFavoritesRepository.DEFAULT_GROUPS,
                items = listOf(
                    FavoriteItemRecord(FavoriteItemType.ROUTE, "ghost", "gym", 0),
                    FavoriteItemRecord(FavoriteItemType.STOP, "zombie", null, 1),
                    FavoriteItemRecord(FavoriteItemType.ROUTE, "r1", FavoriteItemOrder.HOME_GROUP_ID, 2),
                    FavoriteItemRecord(FavoriteItemType.STOP, "a", null, 5)
                )
            )
        )
        // 以后的版本加的类型（又回退了）：读的时候跳过
        db.favorites().insertItems(listOf(FavoriteItemEntity("future_x", "FUTURE", "x", null, 3)))

        val repo = open()

        // 缺的车 b 排最前，索引里有的按原顺序，缺的路线 r2 接最后（COMMUTE 补 commute 分组）
        val expected = listOf(stopKey("b"), routeKey("r1"), stopKey("a"), routeKey("r2"))
        assertEquals(expected, repo.keys())
        assertEquals(listOf(null, FavoriteItemOrder.HOME_GROUP_ID, null, FavoriteItemOrder.COMMUTE_GROUP_ID), repo.favoriteItems.value.map { it.groupId })
        assertEquals(FavoriteItemOrder.HOME_GROUP_ID, repo.routeById("r1").customGroupId)
        assertRoutesMirrorItems(repo)

        // 写回：孤儿行和认不出的行都没了，sortOrder 从 0 连续
        val rows = db.favorites().getItems()
        assertEquals(expected, rows.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.sortOrder })
        assertEquals(expected, open().keys())
    }

    @Test
    fun create_emptyDb_writesDefaultGroups_andNoItems() = runBlocking {
        val repo = open()

        assertEquals(DefaultFavoritesRepository.DEFAULT_GROUPS, repo.favoriteGroups.value)
        assertTrue(repo.favoriteItems.value.isEmpty())
        assertEquals(
            DefaultFavoritesRepository.DEFAULT_GROUPS.map { it.id },
            db.favoriteGroups().getAll().map { it.id }
        )
        assertTrue(db.favorites().getItems().isEmpty())
    }

    @Test
    fun deleteAllGroups_staysDeletedAfterReopen() = runBlocking {
        val repo = open()
        assertEquals(
            DefaultFavoritesRepository.DEFAULT_GROUPS.map { it.id },
            repo.favoriteGroups.value.map { it.id }
        )

        repo.deleteGroup(FavoriteItemOrder.COMMUTE_GROUP_ID)
        repo.deleteGroup(FavoriteItemOrder.HOME_GROUP_ID)
        assertTrue(repo.favoriteGroups.value.isEmpty())

        // 重开：分组表空着，但那是用户删空的，不是从没建过，不再补默认分组（issue #24）
        val reopened = open()
        assertTrue(reopened.favoriteGroups.value.isEmpty())
        assertTrue(db.favoriteGroups().getAll().isEmpty())
    }

    @Test
    fun create_keepsEmptyGroups_whenFavoritesStillExist() = runBlocking {
        val repo = open()
        repo.toggleFavorite(stop("a"))
        val gym = repo.createGroup("健身")
        repo.deleteGroup(FavoriteItemOrder.COMMUTE_GROUP_ID)
        repo.deleteGroup(FavoriteItemOrder.HOME_GROUP_ID)
        repo.deleteGroup(gym.id)

        val reopened = open()

        assertTrue(reopened.favoriteGroups.value.isEmpty())
        assertTrue(reopened.favorites.value.any { it.id == "a" })
    }

    // ---- 收藏的站（issue #47） ----

    private fun station(name: String, platform: String = "往北") = FavoriteStation(
        id = FavoriteStation.idOf("051", DataSourceType.CHELAILE, name, ""),
        cityId = "051", cityName = "莆田", stopName = name, stopId = "s-$name", physicalStId = "p-$name",
        platformLabel = platform, wgsLat = 25.4, wgsLng = 119.0, addedAtMillis = 1L
    )

    private fun stationKey(station: FavoriteStation) = FavoriteItem.stationKey(station.id)

    @Test
    fun station_toggle_addsFirst_mixesWithOtherFavorites_andPersists() = runBlocking {
        val repo = seeded()
        val st = station("科技园")

        assertTrue(repo.toggleStation(st))

        val expected = listOf(stationKey(st), stopKey("c"), stopKey("b"), stopKey("a"), routeKey("r1"), routeKey("r2"))
        assertEquals(expected, repo.keys())
        assertEquals(listOf(st), repo.favoriteStations.value)
        val reopened = open()
        assertEquals(expected, reopened.keys())
        assertEquals(listOf(st), reopened.favoriteStations.value)
        assertEquals(st, (reopened.favoriteItems.value.first() as FavoriteItem.Station).station)

        // 再点一次是取消：站和它的索引行都没了
        assertFalse(repo.toggleStation(st))
        assertTrue(repo.favoriteStations.value.isEmpty())
        assertTrue(db.favorites().getStations().isEmpty())
        assertTrue(db.favorites().getItems().none { it.type == "STATION" })
        assertEquals(expected.drop(1), open().keys())
    }

    @Test
    fun station_update_changesDefaultPlatform_keepsPositionAndGroup() = runBlocking {
        val repo = seeded()
        val st = station("科技园")
        repo.toggleStation(st)
        val gym = repo.createGroup("健身")
        repo.setGroup(stationKey(st), gym.id)
        repo.move(listOf(stopKey("c"), stationKey(st)))
        val keysBefore = repo.keys()

        val south = st.copy(stopId = "s-south", physicalStId = "p-south", platformLabel = "往南")
        repo.updateStation(south)

        fun check(r: DefaultFavoritesRepository) {
            assertEquals(keysBefore, r.keys())
            assertEquals(gym.id, r.groupOf(stationKey(st)))
            assertEquals(listOf(south), r.favoriteStations.value)
        }
        check(repo)
        check(open())

        // 没收藏的站改了也不会加进来
        repo.updateStation(station("别的站"))
        assertEquals(listOf(south), repo.favoriteStations.value)
    }

    @Test
    fun station_remove_andDeleteGroup_behaveLikeOtherFavorites() = runBlocking {
        val repo = seeded()
        val a = station("科技园")
        val b = station("深大", platform = "")
        repo.toggleStation(a)
        repo.toggleStation(b)
        val gym = repo.createGroup("健身")
        repo.setGroup(stationKey(a), gym.id)

        repo.deleteGroup(gym.id)
        assertNull(repo.groupOf(stationKey(a)))

        repo.removeStation(b.id)
        assertEquals(listOf(a), repo.favoriteStations.value)
        assertEquals(listOf(a), open().favoriteStations.value)
    }

    @Test
    fun station_missingFromIndex_isReconciledInFront_staleRowsDropped() = runBlocking {
        val repo = seeded()
        val st = station("科技园")
        // 站写进了表但索引里没有（例如旧存储期间加的），另外索引里有一行指向不存在的站
        RoomFavoritesStore(db).write(
            FavoritesWrite(
                stations = listOf(st),
                itemUpdates = listOf(FavoriteItemRecord(FavoriteItemType.STATION, "ghost", null, 99))
            )
        )

        val reopened = open()
        // 和索引里没有的车一样补在最前
        assertEquals(listOf(stationKey(st), stopKey("c"), stopKey("b"), stopKey("a")), reopened.keys().take(4))
        assertTrue(reopened.keys().none { it == FavoriteItem.stationKey("ghost") })
        assertEquals(listOf(st), reopened.favoriteStations.value)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
