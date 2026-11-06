package com.trackr.ui.screens.favoritesedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.FavoritesGroupDataFix
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 编辑收藏（v3 第 22 步，第 33 步修正）：分组和收藏都在这一页管 —— 拖动排序、改名、删除、改分组。
 *
 * 第 33 步之前这里读的是 `IRouteGroupRepository.groups`，那只是「路线」一个视图，首页收藏的车
 * （线路详情点 ☆ 收的「线路 + 候车站」）根本不在里面，所以点进编辑页收藏区是空的、分组也一直是「暂无路线」。
 * 现在读收藏合一仓库的 [FavoritesRepository.favoriteItems]，和首页「收藏」是同一份数据、同一个顺序、同一套分组。
 *
 * 分组上仍然保留一次性数据修复（[FavoritesGroupDataFix]），时机和原来一样：进这一页时跑一次。
 */
class FavoritesEditViewModel(
    private val favorites: FavoritesRepository,
    /** 和规划页「存入收藏」共用的选中分组：编辑页不再筛选分组，但离开时要清掉，免得带上看不见的分组 */
    private val selection: FavoriteGroupSelection,
    /** 一次性数据修复；为 null 时不修（测试用） */
    private val dataFix: FavoritesGroupDataFix? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val enableAutoMigration: Boolean = true
) : ViewModel() {

    /** 收藏合一列表：车 + 路线，顺序就是首页「收藏」看到的顺序 */
    val items: StateFlow<List<FavoriteItem>> = favorites.favoriteItems

    val favoriteGroups: StateFlow<List<FavoriteGroup>> = favorites.favoriteGroups

    init {
        if (enableAutoMigration) {
            runDataMigrationIfNeeded()
        }
    }

    /**
     * 一次性数据迁移：补齐通勤分组归属、为通勤路线补返程（见 [FavoritesGroupDataFix]），只执行一次。
     * 合成线路的实时对齐不在此处持久化，交由路线的实时刷新在运行时完成。
     */
    fun runDataMigrationIfNeeded(): Job = viewModelScope.launch(ioDispatcher) {
        dataFix?.runIfNeeded()
    }

    // ---- 分组 ----

    fun createFavoriteGroup(name: String) {
        viewModelScope.launch {
            favorites.createGroup(name)
        }
    }

    fun renameFavoriteGroup(groupId: String, newName: String) {
        viewModelScope.launch {
            favorites.renameGroup(groupId, newName)
        }
    }

    /** 删分组不删收藏：组里的车和路线都变成「不分组」（仓库一个事务里做完） */
    fun deleteFavoriteGroup(groupId: String) {
        viewModelScope.launch {
            favorites.deleteGroup(groupId)
            if (selection.selectedFavoriteGroupId.value == groupId) {
                selection.select(null)
            }
        }
    }

    fun reorderFavoriteGroups(groupIds: List<String>) {
        viewModelScope.launch {
            favorites.reorderGroups(groupIds)
        }
    }

    // ---- 收藏（车 + 路线） ----

    /**
     * 拖动排序：只传这一页列表的 [FavoriteItem.key]，按新先后占回它们原来的位置。
     * 车和路线在一列里，所以这一列排出来就是首页的顺序。
     */
    fun moveItems(keys: List<String>) {
        if (keys.size < 2) return
        viewModelScope.launch {
            favorites.move(keys)
        }
    }

    /** 把一条收藏移到 [groupId]（null = 不分组） */
    fun setItemGroup(key: String, groupId: String?) {
        viewModelScope.launch {
            favorites.setGroup(key, groupId)
        }
    }

    /** 删掉一条收藏：车走 [FavoritesRepository.removeFavorite]，路线走 [FavoritesRepository.removeRoute] */
    fun removeItem(key: String) {
        viewModelScope.launch {
            when {
                key.startsWith(FavoriteItem.STOP_KEY_PREFIX) ->
                    favorites.removeFavorite(key.removePrefix(FavoriteItem.STOP_KEY_PREFIX))
                key.startsWith(FavoriteItem.ROUTE_KEY_PREFIX) ->
                    favorites.removeRoute(key.removePrefix(FavoriteItem.ROUTE_KEY_PREFIX))
            }
        }
    }

    /**
     * 原来选中的分组是 `FavoritesGroupViewModel` 自己的状态，随 Activity 一起清掉；
     * 拆开后放在进程级的 [FavoriteGroupSelection] 里，这里清一下，Activity 重建后不带上一次的选择。
     */
    override fun onCleared() {
        selection.select(null)
        super.onCleared()
    }
}
