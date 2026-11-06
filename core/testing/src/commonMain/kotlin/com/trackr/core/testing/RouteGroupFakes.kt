package com.trackr.core.testing

import com.trackr.core.data.local.FavoritesSnapshot
import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.local.FavoritesWrite
import com.trackr.core.data.local.LocalMigrationFlags
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.data.repository.DefaultFavoritesRepository
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import com.trackr.data.repository.IRouteGroupRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/*
 * 第 17 步：路线 / 收藏分组相关的 Fake（原 FavoritesGroupViewModelTest 用 :androidApp 的真实 RouteGroupRepository，
 * 搬进 commonTest 后看不到 :androidApp，这里给一份同样转调 DefaultFavoritesRepository 的实现）。
 */

/**
 * [FavoritesStore] 的内存实现：按 [FavoritesWrite] 的约定合并（null 的部分不动、给了的整份替换、[FavoritesWrite.itemUpdates] 按 key 改或补）。
 * [writes] 记下每次写入。
 */
class InMemoryFavoritesStore(initial: FavoritesSnapshot = FavoritesSnapshot()) : FavoritesStore {
    var snapshot: FavoritesSnapshot = initial
        private set

    val writes: MutableList<FavoritesWrite> = mutableListOf()

    override suspend fun load(): FavoritesSnapshot = snapshot

    override suspend fun write(change: FavoritesWrite) {
        writes += change
        var items = change.items ?: snapshot.items
        if (change.itemUpdates.isNotEmpty()) {
            val merged = items.orEmpty().toMutableList()
            for (update in change.itemUpdates) {
                val index = merged.indexOfFirst { it.key == update.key }
                if (index >= 0) merged[index] = update else merged += update
            }
            items = merged
        }
        snapshot = FavoritesSnapshot(
            stops = change.stops ?: snapshot.stops,
            routes = change.routes ?: snapshot.routes,
            groups = change.groups ?: snapshot.groups,
            items = items,
            groupsInitialized = snapshot.groupsInitialized || change.markGroupsInitialized
        )
    }
}

/**
 * [IRouteGroupRepository] 的内存实现，逻辑全部转调真实的 [DefaultFavoritesRepository]（和 :androidApp 的
 * RouteGroupRepository 一样），所以默认分组 commute / home、删分组清 customGroupId、saveOrConvertTemporaryGroup、
 * addCandidateLineToLeg 等行为和应用里一致。
 *
 * 和 RouteGroupRepository(store, ioDispatcher) 一样：构造时在调用线程上读一次存储，之后的改动走 [ioDispatcher]
 * （测试传 `MainDispatcherHolder.dispatcher`，改动要推进调度器才落地，和原测试一样）。
 */
class InMemoryRouteGroupRepository(
    ioDispatcher: CoroutineDispatcher,
    val store: InMemoryFavoritesStore = InMemoryFavoritesStore()
) : IRouteGroupRepository {

    val favorites: DefaultFavoritesRepository = runInline {
        DefaultFavoritesRepository.create(store, ioDispatcher, loadContext = EmptyCoroutineContext)
    }

    override val groups: StateFlow<List<TransitGroup>> get() = favorites.routes

    override val favoriteGroups: StateFlow<List<FavoriteGroup>> get() = favorites.favoriteGroups

    override suspend fun addGroup(group: TransitGroup) = favorites.saveRoute(group)

    override suspend fun updateGroup(group: TransitGroup) = favorites.updateRoute(group)

    override suspend fun deleteGroup(groupId: String) = favorites.removeRoute(groupId)

    override suspend fun saveOrConvertTemporaryGroup(
        groupId: String,
        newName: String?,
        newCategory: GroupCategory?,
        customGroupId: String?
    ) = favorites.saveOrConvertTemporaryRoute(groupId, newName, newCategory, customGroupId)

    override suspend fun reorderGroups(groupOrderIds: List<String>) = favorites.reorderRoutes(groupOrderIds)

    override fun getGroupById(groupId: String): TransitGroup? = favorites.getRoute(groupId)

    override suspend fun createFavoriteGroup(name: String): FavoriteGroup = favorites.createGroup(name)

    override suspend fun renameFavoriteGroup(groupId: String, newName: String) = favorites.renameGroup(groupId, newName)

    override suspend fun deleteFavoriteGroup(groupId: String) = favorites.deleteGroup(groupId)

    override suspend fun reorderFavoriteGroups(groupIds: List<String>) = favorites.reorderGroups(groupIds)

    override suspend fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) =
        favorites.addCandidateLineToLeg(groupId, legIndex, candidateLine)

    private companion object {
        /**
         * 在调用线程上把 [block] 跑完（内存存储不会挂起，读盘又在 EmptyCoroutineContext 上），
         * 代替 commonMain 里没有的 runBlocking；万一挂起了直接报错，不会悄悄拿到半成品。
         */
        fun <T> runInline(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
            return checkNotNull(outcome) { "InMemoryRouteGroupRepository: 建仓库时挂起了" }.getOrThrow()
        }
    }
}

/** 高德通道替身：记录请求，不发真实 HTTP / SDK 调用（原 FavoritesGroupViewModelTest 的私有 FakeAmapGateway） */
class FakeAmapRouteGateway : AmapRouteGateway {
    data class PlanCall(val originLngLat: String, val destLngLat: String, val destName: String, val cityd: String?)

    var configured = true
    val tipQueries = mutableListOf<String>()
    var tipsProvider: (String) -> List<AmapTipItem> = { kw ->
        listOf(AmapTipItem(name = "${kw}站", location = "120.100000,30.200000"))
    }
    val planCalls = mutableListOf<PlanCall>()
    var planDelayMs = 0L
    var planResult: (PlanCall) -> List<AmapTransitSchemeResult> = { emptyList() }

    override fun isConfigured(): Boolean = configured

    override suspend fun searchTips(
        keyword: String,
        cityName: String,
        location: String?
    ): Result<List<AmapTipItem>> {
        tipQueries += keyword
        return Result.success(tipsProvider(keyword))
    }

    override suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String,
        destName: String,
        cityName: String,
        cityd: String?
    ): Result<List<AmapTransitSchemeResult>> {
        val call = PlanCall(originLngLat, destinationLngLat, destName, cityd)
        planCalls += call
        if (planDelayMs > 0) delay(planDelayMs)
        return Result.success(planResult(call))
    }
}

/** 收藏页一次性数据修复的版本号，存在内存里 */
class FakeLocalMigrationFlags(var version: Int = 0) : LocalMigrationFlags {
    override suspend fun favoritesGroupMigrationVersion(): Int = version

    override suspend fun setFavoritesGroupMigrationVersion(version: Int) {
        this.version = version
    }
}
