package com.trackr.ui.screens.favoritesedit

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.trackr.core.data.local.LocalMigrationFlags
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.FavoritesGroupDataFix
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeLocalMigrationFlags
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.InMemoryRouteGroupRepository
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 编辑收藏页的 ViewModel（第 22 步拆出来的分组管理 + 第 33 步改成「收藏合一」）。
 *
 * 第 33 步之前这里读 `IRouteGroupRepository.groups`，只有路线，首页收藏的车一条都看不到；
 * 下面的测试都按 [FavoriteItem] 断言，车和路线必须在同一个列表里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesEditViewModelTest {

    private val main = MainDispatcherHolder()

    private lateinit var favorites: FakeFavoritesRepository
    private lateinit var groupRepository: InMemoryRouteGroupRepository
    private lateinit var selection: FavoriteGroupSelection
    private lateinit var alignment: RouteAlignmentService

    private val workGroup = FavoriteGroup(id = "fav_work", name = "上班", sortOrder = 0)
    private val homeGroup = FavoriteGroup(id = "fav_home", name = "回家", sortOrder = 1)

    @BeforeTest
    fun setup() {
        main.install()
        selection = FavoriteGroupSelection()
        // 默认一份：一条收藏的车（线路详情点 ☆ 收的那种）+ 一条路线
        favorites = FakeFavoritesRepository(
            initialFavorites = listOf(stopFavorite(id = "a", lineName = "325", stopName = "科技园")),
            initialRoutes = listOf(route(id = "r1", name = "家 → 公司")),
            initialGroups = listOf(workGroup, homeGroup)
        )
        val settings = FakeSettingsRepository(TransitCity(cityId = "033", cityName = "杭州"))
        val realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
        groupRepository = InMemoryRouteGroupRepository(main.dispatcher)
        alignment = RouteAlignmentService(settings, realtime, coordinator = null, ioDispatcher = main.dispatcher)
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    private fun stopFavorite(id: String, lineName: String, stopName: String) = FavoriteStationLine(
        id = id,
        cityId = "034",
        cityName = "上海",
        lineId = "L_$id",
        lineName = lineName,
        startStop = "始发站",
        endStop = "终点站",
        targetStopId = "s_$id",
        targetStopName = stopName,
        targetOrder = 3
    )

    private fun route(id: String, name: String) = TransitGroup(
        id = id,
        name = name,
        category = GroupCategory.CUSTOM,
        legs = listOf(TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "公司"))
    )

    /** 一次性修复只在数据迁移那条测试里开；其它测试不传 dataFix，避免碰 groupRepository */
    private fun createViewModel(
        migrationFlags: LocalMigrationFlags? = null,
        enableAutoMigration: Boolean = true
    ): FavoritesEditViewModel = FavoritesEditViewModel(
        favorites = favorites,
        selection = selection,
        dataFix = migrationFlags?.let { FavoritesGroupDataFix(groupRepository, alignment, it) },
        ioDispatcher = main.dispatcher,
        enableAutoMigration = enableAutoMigration
    )

    @Test
    fun items_holdFavoritedStopsAndRoutesTogether() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        // 这次修的就是这行：收藏的车原来根本不在编辑页的数据里
        assertEquals(listOf("stop_a", "route_r1"), viewModel.items.value.map { it.key })

        val stop = viewModel.items.value.first() as FavoriteItem.Stop
        assertEquals("325", stop.stop.lineName)
        assertEquals("科技园", stop.stop.targetStopName)

        val route = viewModel.items.value.last() as FavoriteItem.Route
        assertEquals("家 → 公司", route.route.name)

        assertEquals(listOf("上班", "回家"), viewModel.favoriteGroups.value.map { it.name })
    }

    @Test
    fun moveItems_reordersStopsAndRoutesTogether() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.moveItems(listOf("route_r1", "stop_a"))
        testScheduler.runCurrent()

        assertEquals(listOf("route_r1", "stop_a"), viewModel.items.value.map { it.key })
    }

    @Test
    fun setItemGroup_worksForStopAndRoute() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.setItemGroup("stop_a", workGroup.id)
        viewModel.setItemGroup("route_r1", homeGroup.id)
        testScheduler.runCurrent()

        assertEquals(workGroup.id, viewModel.items.value.first { it.key == "stop_a" }.groupId)
        assertEquals(homeGroup.id, viewModel.items.value.first { it.key == "route_r1" }.groupId)

        // 再移回「不分组」
        viewModel.setItemGroup("route_r1", null)
        testScheduler.runCurrent()
        assertNull(viewModel.items.value.first { it.key == "route_r1" }.groupId)
    }

    @Test
    fun removeItem_deletesStopAndRouteSeparately() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.removeItem("stop_a")
        testScheduler.runCurrent()
        assertEquals(listOf("route_r1"), viewModel.items.value.map { it.key })

        viewModel.removeItem("route_r1")
        testScheduler.runCurrent()
        assertEquals(emptyList(), viewModel.items.value)
    }

    @Test
    fun deleteFavoriteGroup_keepsItemsAndClearsGroup() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.setItemGroup("stop_a", workGroup.id)
        testScheduler.runCurrent()

        viewModel.deleteFavoriteGroup(workGroup.id)
        testScheduler.runCurrent()

        assertNull(viewModel.favoriteGroups.value.find { it.id == workGroup.id })
        // 收藏不删，变成「不分组」
        assertEquals(2, viewModel.items.value.size)
        assertNull(viewModel.items.value.first { it.key == "stop_a" }.groupId)
    }

    @Test
    fun createRenameAndReorderFavoriteGroups() = runTest(main.dispatcher) {
        val viewModel = createViewModel()
        testScheduler.runCurrent()

        viewModel.createFavoriteGroup("  健身房  ")
        testScheduler.runCurrent()
        val created = viewModel.favoriteGroups.value.single { it.name == "健身房" }

        viewModel.renameFavoriteGroup(created.id, "健身")
        testScheduler.runCurrent()
        assertEquals("健身", viewModel.favoriteGroups.value.single { it.id == created.id }.name)

        viewModel.reorderFavoriteGroups(listOf(created.id, homeGroup.id, workGroup.id))
        testScheduler.runCurrent()
        assertEquals(
            listOf(created.id, homeGroup.id, workGroup.id),
            viewModel.favoriteGroups.value.map { it.id }
        )
    }

    @Test
    fun clearingViewModel_resetsSharedFavoriteGroupSelection() = runTest(main.dispatcher) {
        // 选中的分组放在进程级的 FavoriteGroupSelection 里：ViewModel 清掉（Activity 结束）后不能留到下一次
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { createViewModel(enableAutoMigration = false) } }
        )[FavoritesEditViewModel::class]
        selection.select(workGroup.id)
        assertEquals(workGroup.id, selection.selectedFavoriteGroupId.value)

        store.clear()
        assertNull(selection.selectedFavoriteGroupId.value)
        // 用一下 viewModel，避免上面的取值被当成未使用
        assertEquals(2, viewModel.items.value.size)
    }

    @Test
    fun runDataMigration_migratesLegacyCommuteOnceAndRecordsVersion() = runTest(main.dispatcher) {
        val migrationFlags = FakeLocalMigrationFlags()

        // 遗留数据：通勤分类但没有 customGroupId
        val legacyCommute = TransitGroup(
            id = "legacy_commute",
            name = "家 ➔ 公司",
            category = GroupCategory.COMMUTE,
            legs = listOf(TransitLeg(legIndex = 0, fromStopName = "家", toStopName = "公司"))
        )
        groupRepository.addGroup(legacyCommute)
        testScheduler.runCurrent()

        fun newViewModel() = createViewModel(migrationFlags = migrationFlags, enableAutoMigration = false)

        val viewModel = newViewModel()
        viewModel.runDataMigrationIfNeeded().join()
        testScheduler.advanceUntilIdle()

        // 迁移补齐 customGroupId 并生成反向回家路线
        val migrated = groupRepository.getGroupById("legacy_commute")
        assertEquals("commute", migrated?.customGroupId)
        assertEquals(2, groupRepository.groups.value.size)
        assertEquals(GroupCategory.COMMUTE, migrated?.category)
        assertEquals(FavoritesGroupDataFix.CURRENT_MIGRATION_VERSION, migrationFlags.version)

        // 再写入一条新的遗留数据，已记录版本后不再重复迁移
        groupRepository.addGroup(legacyCommute.copy(id = "legacy_commute_2"))
        testScheduler.runCurrent()
        val secondViewModel = newViewModel()
        secondViewModel.runDataMigrationIfNeeded().join()
        testScheduler.advanceUntilIdle()

        assertNull(groupRepository.getGroupById("legacy_commute_2")?.customGroupId)
        assertEquals(3, groupRepository.groups.value.size)
    }
}
