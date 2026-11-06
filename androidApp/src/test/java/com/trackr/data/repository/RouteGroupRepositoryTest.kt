package com.trackr.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteGroupRepositoryTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RouteGroupRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = RouteGroupRepository(context, testDispatcher)
    }

    @Test
    fun testInitialGroupsEmptyAndNoFakeData() {
        val groups = repository.groups.value
        assertTrue(groups.isEmpty())
    }

    @Test
    fun testSanitizesLegacyFakeSampleDataOnLoad() {
        val fakeSampleJson = """
            [{"id":"sample_commute_group","name":"早高峰上班通勤","category":"COMMUTE","sortOrder":0,"legs":[]}]
        """.trimIndent()
        context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
            .edit()
            .putString("groups_list_json", fakeSampleJson)
            .commit()

        val reloadedRepo = RouteGroupRepository(context, testDispatcher)
        assertTrue(reloadedRepo.groups.value.isEmpty())
    }

    @Test
    fun testInitialFavoriteGroupsSeeded() {
        val favGroups = repository.favoriteGroups.value
        assertEquals(2, favGroups.size)
        assertEquals("commute", favGroups[0].id)
        assertEquals("上班通勤", favGroups[0].name)
        assertEquals("home", favGroups[1].id)
        assertEquals("回家线路", favGroups[1].name)
    }

    @Test
    fun testDeleteDefaultFavoriteGroupsStaysDeleted() = runTest(testDispatcher) {
        repository.deleteFavoriteGroup("commute")
        repository.deleteFavoriteGroup("home")
        assertTrue(repository.favoriteGroups.value.isEmpty())

        // 重启（重开仓库）后不再补默认分组（issue #24）
        val reloaded = RouteGroupRepository(context, testDispatcher)
        assertTrue(reloaded.favoriteGroups.value.isEmpty())
    }

    @Test
    fun testCreateAndRenameFavoriteGroup() = runTest(testDispatcher) {
        val created = repository.createFavoriteGroup("健身房")
        assertNotNull(created.id)
        assertEquals("健身房", created.name)
        assertEquals(2, created.sortOrder)

        val list = repository.favoriteGroups.value
        assertEquals(3, list.size)

        repository.renameFavoriteGroup(created.id, "瑜伽馆")
        val updated = repository.favoriteGroups.value.find { it.id == created.id }
        assertNotNull(updated)
        assertEquals("瑜伽馆", updated?.name)
    }

    @Test
    fun testDeleteFavoriteGroupKeepsTransitGroups() = runTest(testDispatcher) {
        val fav = repository.createFavoriteGroup("外勤出行")
        val transitGroup = TransitGroup(
            id = "tg_field_1",
            name = "外勤拜访客户",
            category = GroupCategory.CUSTOM,
            customGroupId = fav.id,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "A", toStopName = "B"))
        )
        repository.addGroup(transitGroup)

        assertEquals("外勤出行", repository.favoriteGroups.value.find { it.id == fav.id }?.name)
        assertEquals(fav.id, repository.getGroupById("tg_field_1")?.customGroupId)

        // 删除该收藏分组
        repository.deleteFavoriteGroup(fav.id)

        // 验证收藏分组已移除
        assertNull(repository.favoriteGroups.value.find { it.id == fav.id })

        // 验证原有的 TransitGroup 仍然存在，但 customGroupId 已被置空归入未分组
        val keptGroup = repository.getGroupById("tg_field_1")
        assertNotNull(keptGroup)
        assertNull(keptGroup?.customGroupId)
        assertEquals("外勤拜访客户", keptGroup?.name)
    }

    @Test
    fun testReorderFavoriteGroups() = runTest(testDispatcher) {
        repository.createFavoriteGroup("分组C")
        val initialList = repository.favoriteGroups.value
        assertEquals(3, initialList.size)

        // 将顺序反转
        val reversedIds = initialList.map { it.id }.reversed()
        repository.reorderFavoriteGroups(reversedIds)

        val reordered = repository.favoriteGroups.value
        assertEquals(reversedIds[0], reordered[0].id)
        assertEquals(reversedIds[1], reordered[1].id)
        assertEquals(reversedIds[2], reordered[2].id)
    }

    @Test
    fun testAddCandidateLineToLeg() = runTest(testDispatcher) {
        val group = TransitGroup(
            id = "group_direct_multi",
            name = "高新园通勤",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "深大北门",
                    toStopName = "高新园",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "0755",
                            lineId = "line_320",
                            lineName = "320路",
                            source = DataSourceType.CHELAILE,
                            fromStopOrder = 2,
                            toStopOrder = 6
                        )
                    )
                )
            )
        )
        repository.addGroup(group)

        val candidateM194 = CandidateLine(
            cityId = "0755",
            lineId = "line_m194",
            lineName = "M194路",
            source = DataSourceType.CHELAILE,
            fromStopOrder = 4,
            toStopOrder = 9
        )

        // 添加候选线路
        repository.addCandidateLineToLeg(group.id, 0, candidateM194)

        val updatedGroup = repository.getGroupById(group.id)
        assertNotNull(updatedGroup)
        assertEquals(2, updatedGroup!!.legs[0].candidateLines.size)
        assertEquals("line_320", updatedGroup.legs[0].candidateLines[0].lineId)
        assertEquals("line_m194", updatedGroup.legs[0].candidateLines[1].lineId)

        // 重复添加相同 lineId 不会产生重复项
        repository.addCandidateLineToLeg(group.id, 0, candidateM194)
        val deduplicatedGroup = repository.getGroupById(group.id)
        assertEquals(2, deduplicatedGroup!!.legs[0].candidateLines.size)
    }

    @Test
    fun testAddAndRetrieveGroup() = runTest(testDispatcher) {
        val newGroup = TransitGroup(
            id = "group_weekend",
            name = "周末西湖游玩",
            category = GroupCategory.LEISURE,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "杭州东站",
                    toStopName = "龙翔桥",
                    candidateLines = listOf(
                        CandidateLine(
                            cityId = "033",
                            cityName = "杭州",
                            lineId = "m1",
                            lineName = "地铁1号线",
                            source = DataSourceType.CHELAILE,
                            fromStopOrder = 5,
                            toStopOrder = 10
                        )
                    )
                )
            )
        )

        repository.addGroup(newGroup)

        val list = repository.groups.value
        assertEquals(1, list.size)
        val retrieved = repository.getGroupById("group_weekend")
        assertNotNull(retrieved)
        assertEquals("周末西湖游玩", retrieved?.name)
        assertEquals(GroupCategory.LEISURE, retrieved?.category)

        // 重新构建实例测试持久化读取
        val reloadedRepo = RouteGroupRepository(context, testDispatcher)
        val reloaded = reloadedRepo.getGroupById("group_weekend")
        assertNotNull(reloaded)
        assertEquals("周末西湖游玩", reloaded?.name)
    }

    @Test
    fun testUpdateGroup() = runTest(testDispatcher) {
        val group = TransitGroup(
            id = "group_work",
            name = "上班日常",
            category = GroupCategory.COMMUTE
        )
        repository.addGroup(group)
        val updated = group.copy(name = "日常上下班新名称")

        repository.updateGroup(updated)

        val retrieved = repository.getGroupById(group.id)
        assertEquals("日常上下班新名称", retrieved?.name)
    }

    @Test
    fun testDeleteGroup() = runTest(testDispatcher) {
        val group = TransitGroup(
            id = "group_temp_del",
            name = "待删除分组",
            category = GroupCategory.CUSTOM
        )
        repository.addGroup(group)
        repository.deleteGroup(group.id)

        val list = repository.groups.value
        assertTrue(list.isEmpty())
        assertNull(repository.getGroupById(group.id))
    }

    @Test
    fun testSaveOrConvertTemporaryGroup() = runTest(testDispatcher) {
        val tempGroup = TransitGroup(
            id = "temp_imported_1",
            name = "高德导入路线",
            category = GroupCategory.TEMPORARY,
            isTemporary = true,
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "西溪湿地",
                    toStopName = "黄龙体育中心"
                )
            )
        )
        repository.addGroup(tempGroup)
        assertTrue(repository.getGroupById("temp_imported_1")!!.isTemporary)

        repository.saveOrConvertTemporaryGroup(
            groupId = "temp_imported_1",
            newName = "西溪到黄龙固定路线",
            newCategory = GroupCategory.CUSTOM
        )

        val converted = repository.getGroupById("temp_imported_1")
        assertNotNull(converted)
        assertFalse(converted!!.isTemporary)
        assertEquals("西溪到黄龙固定路线", converted.name)
        assertEquals(GroupCategory.CUSTOM, converted.category)
    }

    @Test
    fun testReorderGroups() = runTest(testDispatcher) {
        val group1 = TransitGroup(id = "g1", name = "G1")
        val group2 = TransitGroup(id = "g2", name = "G2")
        repository.addGroup(group1)
        repository.addGroup(group2)

        repository.reorderGroups(listOf("g2", "g1"))

        val list = repository.groups.value
        val g2 = list.find { it.id == "g2" }
        val g1 = list.find { it.id == "g1" }
        assertNotNull(g2)
        assertNotNull(g1)
        assertTrue(g2!!.sortOrder < g1!!.sortOrder)
    }

    @Test
    fun testConcurrentAddGroupsAndFavoriteGroups_doNotLoseUpdates() = runBlocking {
        // 使用真实多线程调度器制造并发读改写，Mutex 保证每次写入都不丢失
        val concurrentRepo = RouteGroupRepository(context, Dispatchers.IO)
        val count = 40
        coroutineScope {
            val addGroups = (1..count).map { index ->
                async(Dispatchers.Default) {
                    concurrentRepo.addGroup(TransitGroup(id = "concurrent_$index", name = "并发分组$index"))
                }
            }
            val createFavorites = (1..count).map { index ->
                async(Dispatchers.Default) { concurrentRepo.createFavoriteGroup("收藏$index") }
            }
            (addGroups + createFavorites).awaitAll()
        }

        assertEquals(count, concurrentRepo.groups.value.size)
        // 2 个默认收藏分组 + 并发创建的分组
        assertEquals(count + 2, concurrentRepo.favoriteGroups.value.size)

        val reloaded = RouteGroupRepository(context, testDispatcher)
        assertEquals(count, reloaded.groups.value.size)
        assertEquals(count + 2, reloaded.favoriteGroups.value.size)
    }

    @Test
    fun testCorruptGroupsJson_isBackedUpBeforeOverwrite() = runTest(testDispatcher) {
        val prefs = context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
        val corrupt = "[{\"id\":\"broken_group\""
        prefs.edit().putString("groups_list_json", corrupt).commit()

        val corruptRepo = RouteGroupRepository(context, testDispatcher)
        assertTrue(corruptRepo.groups.value.isEmpty())

        val backupPrefix = "groups_list_json" + RouteGroupRepository.CORRUPT_BACKUP_SUFFIX
        assertEquals(corrupt, prefs.all.filterKeys { it.startsWith(backupPrefix) }.values.single())

        // 后续写入覆盖主 key，备份仍保留
        corruptRepo.addGroup(TransitGroup(id = "new_group", name = "新分组"))
        assertEquals(corrupt, prefs.all.filterKeys { it.startsWith(backupPrefix) }.values.single())
    }

    @Test
    fun testCorruptFavoriteGroupsJson_isBackedUpAndKeepsOnlyLatest() {
        val prefs = context.getSharedPreferences("trackr_route_groups", Context.MODE_PRIVATE)
        val backupPrefix = "favorite_groups_list_json" + RouteGroupRepository.CORRUPT_BACKUP_SUFFIX

        prefs.edit().putString("favorite_groups_list_json", "broken-1").commit()
        val first = RouteGroupRepository(context, testDispatcher)
        // 解析失败后仍回落到默认收藏分组
        assertEquals(2, first.favoriteGroups.value.size)
        assertEquals("broken-1", prefs.all.filterKeys { it.startsWith(backupPrefix) }.values.single())

        Thread.sleep(5)
        prefs.edit().putString("favorite_groups_list_json", "broken-2").commit()
        RouteGroupRepository(context, testDispatcher)
        val backups = prefs.all.filterKeys { it.startsWith(backupPrefix) }
        assertEquals(1, backups.size)
        assertEquals("broken-2", backups.values.single())
    }
}
