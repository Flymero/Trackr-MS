package com.trackr.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.trackr.core.data.local.RoomFavoritesStore
import com.trackr.core.data.repository.DefaultFavoritesRepository
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.database.TrackrDatabaseConstructor
import com.trackr.core.database.buildTrackrDatabase
import com.trackr.core.database.entity.FavoriteItemEntity
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.GroupColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 库升级：版本 1（第 9 步）-> 2（第 14 步，加 favorite_item）-> 3（issue #38，分组加 color）：收藏、分组、顺序都不变。
 *
 * 不用 room-testing：按 `core/database/schemas/.../<版本>.json` 里的建表语句手工建一个旧版本的库文件、塞数据，
 * 再用正式的 builder（带全部迁移）打开。Room 打开后会按当前版本的表结构校验，迁移写错这里就会失败。
 */
@RunWith(RobolectricTestRunner::class)
class TrackrDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var db: TrackrDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        db?.close()
        deleteDbFiles()
    }

    private fun deleteDbFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
    }

    // ---- 建版本 1 的库 ----

    private fun schemaFile(version: Int): File {
        val relative = "com.trackr.core.database.TrackrDatabase/$version.json"
        val candidates = listOf(
            File("../database/schemas/$relative"),
            File("core/database/schemas/$relative")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 schema 文件 $relative（试过 ${candidates.map { it.absolutePath }}）")
    }

    /** 按 1.json 建表、写 room_master_table，user_version = 1 */
    private fun createV1Database(fill: SQLiteDatabase.() -> Unit) = createDatabase(1, fill)

    /** 按 <version>.json 建表、写 room_master_table，user_version = [version] */
    private fun createDatabase(version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = Json.parseToJsonElement(schemaFile(version).readText()).jsonObject.getValue("database").jsonObject
        assertEquals(version, schema.getValue("version").jsonPrimitive.content.toInt())
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                sqlite.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    sqlite.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { sqlite.execSQL(it.jsonPrimitive.content) }
            sqlite.version = version
            sqlite.fill()
        } finally {
            sqlite.close()
        }
    }

    private fun openMigrated(): TrackrDatabase =
        Room.databaseBuilder<TrackrDatabase>(
            context = context,
            name = dbFile.absolutePath,
            factory = { TrackrDatabaseConstructor.initialize() }
        ).buildTrackrDatabase().also { db = it }

    private fun SQLiteDatabase.stop(id: String, position: Int, lineName: String) {
        insertOrThrow("favorite_line", null, ContentValues().apply {
            put("id", id); put("position", position); put("cityId", "051"); put("cityName", "莆田")
            put("lineId", "l-$id"); put("lineName", lineName); put("lineNo", ""); put("direction", 0)
            put("startStop", "A"); put("endStop", "B"); put("targetStopId", "s-$id"); put("targetStopName", "站$id")
            put("targetOrder", 3); put("totalStops", 20); put("wgsLat", 25.4); put("wgsLng", 119.0)
            put("source", "CHELAILE"); put("mygolbsCityCode", ""); put("addedAtMillis", 100L + position)
        })
    }

    private fun SQLiteDatabase.group(id: String, position: Int, name: String, isDefault: Boolean) {
        insertOrThrow("favorite_group", null, ContentValues().apply {
            put("id", id); put("position", position); put("name", name); put("sortOrder", position); put("isDefault", isDefault)
        })
    }

    private fun SQLiteDatabase.route(id: String, position: Int, sortOrder: Int, name: String, category: String, groupId: String?) {
        insertOrThrow("route", null, ContentValues().apply {
            put("id", id); put("position", position); put("name", name); put("category", category)
            put("isTemporary", false); put("sortOrder", sortOrder)
            if (groupId == null) putNull("customGroupId") else put("customGroupId", groupId)
            put("createdAt", 1L); put("updatedAt", 2L)
        })
    }

    /** 第 9 步版本的典型数据：3 辆车、3 个分组、4 条路线（sortOrder 有并列、position 乱序）、程和候选线、提醒、搜索历史 */
    private fun SQLiteDatabase.fillTypical() {
        // 车：物理插入顺序和 position 不同
        stop("c", 2, "3路")
        stop("a", 0, "1路")
        stop("b", 1, "2路")
        group("commute", 0, "上班通勤", true)
        group("home", 1, "回家线路", true)
        group("gym", 2, "健身", false)
        // 收藏页原来的顺序：按 sortOrder，相同时按 position -> r-commute(1,p0), r-back(1,p3), r-gym(2,p1), r-orphan(5,p2)
        route("r-gym", 1, 2, "去健身", "CUSTOM", "gym")
        route("r-orphan", 2, 5, "孤儿", "LEISURE", "deleted-group")
        route("r-back", 3, 1, "公司 ➔ 家（返程）", "CUSTOM", null)
        route("r-commute", 0, 1, "家 ➔ 公司", "COMMUTE", null)
        insertOrThrow("route_leg", null, ContentValues().apply {
            put("routeId", "r-commute"); put("position", 0); put("legIndex", 0)
            put("fromStopName", "甲"); put("fromStopId", "s1"); put("toStopName", "乙"); put("toStopId", "s2")
            put("fromStopLat", 25.4); put("fromStopLng", 119.0); put("toStopLat", 25.5); put("toStopLng", 119.1)
            put("walkingDistanceMeters", 104); put("ridePathJson", "[{\"lat\":25.4,\"lng\":119.0}]"); put("walkPathBeforeJson", "[]")
        })
        insertOrThrow("route_candidate_line", null, ContentValues().apply {
            put("routeId", "r-commute"); put("legPosition", 0); put("position", 0)
            put("cityId", "051"); put("cityName", "莆田"); put("lineId", "7"); put("lineName", "7路"); put("source", "CHELAILE")
            put("direction", 1); put("fromStopOrder", 21); put("toStopOrder", 25); put("startStopName", "S"); put("endStopName", "E")
            putNull("mygolbsCityCode")
        })
        insertOrThrow("reminder", null, ContentValues().apply {
            put("id", "rem-1"); put("position", 0); put("enabled", true); put("hour", 7); put("minute", 30); put("daysOfWeek", "1,2,3")
            put("cityId", "051"); put("cityName", "莆田"); put("lineId", "7"); put("lineName", "7路"); put("direction", 0)
            put("endStopName", "E"); put("targetStopId", "s2"); put("targetStopName", "乙"); put("targetOrder", 5); put("totalStops", 20)
            put("dataSourceType", "CHELAILE"); putNull("mygolbsCityCode"); put("autoStartLiveTracking", false)
            put("lastScheduledTriggerAt", 1_800_000_000_000L)
        })
        insertOrThrow("search_history", null, ContentValues().apply { put("query", "59"); put("position", 0) })
    }

    // ---- 用例 ----

    @Test
    fun migrate1To2_buildsFavoriteItemsInOldOrder() = runBlocking {
        createV1Database { fillTypical() }
        val database = openMigrated()

        assertEquals(
            listOf(
                FavoriteItemEntity("stop_a", "STOP", "a", null, 0),
                FavoriteItemEntity("stop_b", "STOP", "b", null, 1),
                FavoriteItemEntity("stop_c", "STOP", "c", null, 2),
                // customGroupId 为空、场景是通勤 -> commute（分组还在）
                FavoriteItemEntity("route_r-commute", "ROUTE", "r-commute", "commute", 3),
                // customGroupId 为空、名字里有「返程」-> home
                FavoriteItemEntity("route_r-back", "ROUTE", "r-back", "home", 4),
                FavoriteItemEntity("route_r-gym", "ROUTE", "r-gym", "gym", 5),
                // 指向已删分组的照原样保留
                FavoriteItemEntity("route_r-orphan", "ROUTE", "r-orphan", "deleted-group", 6)
            ),
            database.favorites().getItems()
        )
    }

    @Test
    fun migrate1To2_leavesOldTablesUntouched() = runBlocking {
        createV1Database { fillTypical() }
        val database = openMigrated()

        assertEquals(listOf("a", "b", "c"), database.favoriteLines().getAll().map { it.id })
        assertEquals(listOf("1路", "2路", "3路"), database.favoriteLines().getAll().map { it.lineName })
        assertEquals(listOf("commute", "home", "gym"), database.favoriteGroups().getAll().map { it.id })
        val routes = database.routes().getAll()
        assertEquals(listOf("r-commute", "r-gym", "r-orphan", "r-back"), routes.map { it.route.id })
        assertEquals(listOf(null, "gym", "deleted-group", null), routes.map { it.route.customGroupId })
        val commute = routes.first { it.route.id == "r-commute" }
        assertEquals(1, commute.legs.size)
        assertEquals(104, commute.legs.single().walkingDistanceMeters)
        assertEquals(listOf("7路"), commute.candidates.map { it.lineName })
        assertEquals(1_800_000_000_000L, database.reminders().getLastScheduledTriggerAt("rem-1"))
        assertEquals(listOf("59"), database.searchHistory().getAll().map { it.query })
    }

    @Test
    fun migrate1To2_repositoryShowsSameOrderAndGroupsAsBefore() = runBlocking {
        createV1Database { fillTypical() }
        val database = openMigrated()
        val repository = DefaultFavoritesRepository.create(RoomFavoritesStore(database), Dispatchers.IO)

        // 收藏的车：按 position
        assertEquals(listOf("a", "b", "c"), repository.favorites.value.map { it.id })
        // 路线：收藏页原来的顺序（sortOrder，相同时按 position）
        assertEquals(listOf("r-commute", "r-back", "r-gym", "r-orphan"), repository.routes.value.map { it.id })
        // 原来的 sortOrder 不变
        assertEquals(listOf(1, 1, 2, 5), repository.routes.value.map { it.sortOrder })
        // 分组归属和收藏页原来的筛选结果一样
        assertEquals(listOf("commute", "home", "gym", "deleted-group"), repository.routes.value.map { it.customGroupId })
        assertEquals(listOf("commute", "home", "gym"), repository.favoriteGroups.value.map { it.id })
        assertEquals(
            listOf("stop_a", "stop_b", "stop_c", "route_r-commute", "route_r-back", "route_r-gym", "route_r-orphan"),
            repository.favoriteItems.value.map { it.key }
        )
        assertTrue(repository.favoriteItems.value.take(3).all { it is FavoriteItem.Stop && it.groupId == null })
    }

    @Test
    fun migrate1To2_withoutDefaultGroups_doesNotInventGroups() = runBlocking {
        createV1Database {
            route("r1", 0, 1, "家 ➔ 公司", "COMMUTE", null)
            route("r2", 1, 2, "返程", "CUSTOM", null)
        }
        val database = openMigrated()
        assertEquals(listOf<String?>(null, null), database.favorites().getItems().map { it.groupId })
    }

    @Test
    fun migrate1To2_emptyDatabase() = runBlocking {
        createV1Database { }
        val database = openMigrated()
        assertTrue(database.favorites().getItems().isEmpty())
        assertTrue(database.favoriteLines().getAll().isEmpty())
    }

    @Test
    fun migrate1To2_dataSurvivesReopen() = runBlocking {
        createV1Database { fillTypical() }
        val before = openMigrated().favorites().getItems()
        db?.close()
        db = null
        val reopened = openMigrated()
        assertEquals(before, reopened.favorites().getItems())
        assertEquals(TrackrDatabase.VERSION, reopened.openHelper.readableDatabase.version)
    }

    // ---- 2 -> 3：分组颜色（issue #38） ----

    @Test
    fun migrate2To3_addsColorColumn_existingGroupsHaveNoColor() = runBlocking {
        createDatabase(2) {
            group("commute", 0, "上班通勤", true)
            group("gym", 1, "健身", false)
            stop("a", 0, "1路")
            insertOrThrow("favorite_item", null, ContentValues().apply {
                put("id", "stop_a"); put("type", "STOP"); put("refId", "a"); put("groupId", "gym"); put("sortOrder", 0)
            })
        }
        val database = openMigrated()

        val groups = database.favoriteGroups().getAll()
        assertEquals(listOf("commute", "gym"), groups.map { it.id })
        assertEquals(listOf("上班通勤", "健身"), groups.map { it.name })
        assertEquals(listOf<String?>(null, null), groups.map { it.color })
        assertEquals(listOf(FavoriteItemEntity("stop_a", "STOP", "a", "gym", 0)), database.favorites().getItems())

        // 迁移后能写颜色、读回来
        val repository = DefaultFavoritesRepository.create(RoomFavoritesStore(database), Dispatchers.IO)
        repository.setGroupColor("gym", GroupColor.PURPLE)
        assertEquals("PURPLE", database.favoriteGroups().getAll().single { it.id == "gym" }.color)
        assertEquals(TrackrDatabase.VERSION, database.openHelper.readableDatabase.version)
    }

    @Test
    fun migrate1To3_groupsHaveNoColor() = runBlocking {
        createV1Database { fillTypical() }
        val database = openMigrated()
        assertTrue(database.favoriteGroups().getAll().all { it.color == null })
    }
}
