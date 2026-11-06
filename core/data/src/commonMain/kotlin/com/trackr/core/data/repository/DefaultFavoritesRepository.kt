package com.trackr.core.data.repository

import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.local.FavoritesWrite
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteItemRecord
import com.trackr.core.model.FavoriteItemType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.TransitGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 收藏合一仓库（第 14 步）：收藏的车、路线、收藏分组、合一索引的**唯一**持有者。
 *
 * `:androidApp` 的 TransitRepository（收藏的车那部分）和 RouteGroupRepository（路线、收藏分组）都转调这里，
 * 所以两边看到的是同一份数据，不会互相覆盖。
 *
 * 写法沿用第 9 步：用 [create] 读一次，之后每次改动都在 [mutex] 里「改内存 -> 写盘（等写完）-> 发新值」。
 * 多步改动（删分组同时改路线和索引）交给 [FavoritesStore.write] 一次写完，新存储里是一个事务。
 *
 * 不变式：索引里每辆车、每条路线正好一行；内存里的车和路线都按索引的顺序排，路线的 `customGroupId` 和索引的分组一致
 * （旧页面靠它筛选）。路线自己的 `sortOrder` 保持旧仓库的含义（只在路线之间排，新路线 = 最大值 + 1，[reorderRoutes] 改成 0、1、2…），
 * 和索引里路线之间的先后一致，旧版本读旧存储的 JSON 时靠它排序。
 */
class DefaultFavoritesRepository private constructor(
    private val store: FavoritesStore,
    initial: State,
    /** 存储里有没有一份完整的索引；没有时（从没建过）第一次改动整份写入 */
    private var itemsPersisted: Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val newId: () -> String
) : FavoritesRepository {

    /** 内存里的一份完整数据，[items] 按 sortOrder 排好 */
    private data class State(
        val stops: List<FavoriteStationLine>,
        val routes: List<TransitGroup>,
        val groups: List<FavoriteGroup>,
        val items: List<FavoriteItemRecord>
    )

    private val mutex = Mutex()
    private var state: State = initial

    private val _favorites = MutableStateFlow(initial.stops)
    override val favorites: StateFlow<List<FavoriteStationLine>> = _favorites.asStateFlow()

    private val _routes = MutableStateFlow(initial.routes)
    override val routes: StateFlow<List<TransitGroup>> = _routes.asStateFlow()

    private val _favoriteGroups = MutableStateFlow(initial.groups)
    override val favoriteGroups: StateFlow<List<FavoriteGroup>> = _favoriteGroups.asStateFlow()

    private val _favoriteItems = MutableStateFlow(itemsOf(initial))
    override val favoriteItems: StateFlow<List<FavoriteItem>> = _favoriteItems.asStateFlow()

    // ---- 收藏的车 ----

    override suspend fun toggleFavorite(item: FavoriteStationLine): Boolean = locked {
        val exists = state.stops.any { it.id == item.id }
        if (exists) {
            val next = rebuild(
                stops = state.stops.filterNot { it.id == item.id },
                items = state.items.filterNot { it.type == FavoriteItemType.STOP && it.refId == item.id }
            )
            commit(next, FavoritesWrite(stops = next.stops, items = next.items))
            false
        } else {
            // 加到最前（和旧版一样）
            val record = FavoriteItemRecord(FavoriteItemType.STOP, item.id, groupId = null, sortOrder = minOrder() - 1)
            val next = rebuild(stops = state.stops + item, items = state.items + record)
            commit(next, FavoritesWrite(stops = next.stops, itemUpdates = listOf(record)))
            true
        }
    }

    override suspend fun removeFavorite(id: String) = locked {
        if (state.stops.none { it.id == id }) return@locked
        val next = rebuild(
            stops = state.stops.filterNot { it.id == id },
            items = state.items.filterNot { it.type == FavoriteItemType.STOP && it.refId == id }
        )
        commit(next, FavoritesWrite(stops = next.stops, items = next.items))
    }

    // ---- 合一列表 ----

    override suspend fun move(keys: List<String>) = locked {
        val byKey = state.items.associateBy { it.key }
        val moving = keys.distinct().mapNotNull { byKey[it] }
        if (moving.size < 2) return@locked
        // 这几条收藏原来占的位置，按新的先后重新分给它们
        val slots = moving.map { it.sortOrder }.sorted()
        val reassigned = moving.mapIndexed { index, record -> record.copy(sortOrder = slots[index]) }
            .filter { it.sortOrder != byKey.getValue(it.key).sortOrder }
        if (reassigned.isEmpty()) return@locked
        val updatedByKey = reassigned.associateBy { it.key }
        var next = rebuild(items = state.items.map { updatedByKey[it.key] ?: it })
        if (reassigned.any { it.type == FavoriteItemType.ROUTE }) {
            // 路线之间的先后变了：路线自己的 sortOrder 按新的先后改成 0、1、2…（保持和旧版 reorder 一样的含义）
            next = rebuild(routes = next.routes.mapIndexed { index, route -> route.copy(sortOrder = index) }, items = next.items)
        }
        commit(next, mirrorWrite(next, reassigned))
    }

    override suspend fun setGroup(key: String, groupId: String?): Boolean = locked {
        if (groupId != null && state.groups.none { it.id == groupId }) return@locked false
        val record = state.items.firstOrNull { it.key == key } ?: return@locked false
        if (record.groupId == groupId) return@locked true
        val updated = record.copy(groupId = groupId)
        val next = rebuild(items = state.items.map { if (it.key == key) updated else it })
        // 路线的分组在旧页面上看 customGroupId，镜像跟着写；车没有分组列，只改索引那一行
        commit(next, FavoritesWrite(routes = if (updated.type == FavoriteItemType.ROUTE) next.routes else null, itemUpdates = listOf(updated)))
        true
    }

    // ---- 路线 ----

    override suspend fun saveRoute(route: TransitGroup) = locked {
        val existing = state.items.firstOrNull { it.type == FavoriteItemType.ROUTE && it.refId == route.id }
        if (existing != null) {
            replaceRoute(route.copy(updatedAt = now()), existing)
        } else {
            val record = FavoriteItemRecord(FavoriteItemType.ROUTE, route.id, route.customGroupId, maxOrder() + 1)
            // 路线自己的 sortOrder 和旧版一样：路线里最大的 + 1（一条都没有时是 1）
            val routeOrder = (state.routes.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val next = rebuild(
                routes = state.routes + route.copy(sortOrder = routeOrder, updatedAt = now()),
                items = state.items + record
            )
            commit(next, FavoritesWrite(routes = next.routes, itemUpdates = listOf(record)))
        }
    }

    /** 只改已存在的路线（内容和分组按传进来的，在合一列表里的位置不变） */
    suspend fun updateRoute(route: TransitGroup) = locked {
        val existing = state.items.firstOrNull { it.type == FavoriteItemType.ROUTE && it.refId == route.id }
            ?: return@locked
        replaceRoute(route.copy(updatedAt = now()), existing)
    }

    private suspend fun replaceRoute(route: TransitGroup, existing: FavoriteItemRecord) {
        val record = existing.copy(groupId = route.customGroupId)
        val next = rebuild(
            routes = state.routes.map { if (it.id == route.id) route else it },
            items = state.items.map { if (it.key == record.key) record else it }
        )
        commit(next, FavoritesWrite(routes = next.routes, itemUpdates = listOf(record)))
    }

    override suspend fun removeRoute(routeId: String) = locked {
        if (state.routes.none { it.id == routeId }) return@locked
        val next = rebuild(
            routes = state.routes.filterNot { it.id == routeId },
            items = state.items.filterNot { it.type == FavoriteItemType.ROUTE && it.refId == routeId }
        )
        commit(next, FavoritesWrite(routes = next.routes, items = next.items))
    }

    fun getRoute(routeId: String): TransitGroup? = _routes.value.find { it.id == routeId }

    /** 把地图导入的临时方案转成正式收藏（逻辑同旧 RouteGroupRepository.saveOrConvertTemporaryGroup） */
    suspend fun saveOrConvertTemporaryRoute(
        routeId: String,
        newName: String?,
        newCategory: GroupCategory?,
        customGroupId: String?
    ) = locked {
        val route = state.routes.firstOrNull { it.id == routeId } ?: return@locked
        val converted = route.copy(
            name = newName?.takeIf { it.isNotBlank() } ?: route.name,
            category = newCategory ?: if (route.category == GroupCategory.TEMPORARY) GroupCategory.CUSTOM else route.category,
            customGroupId = customGroupId ?: route.customGroupId,
            isTemporary = false,
            updatedAt = now()
        )
        val existing = state.items.first { it.key == FavoriteItem.routeKey(routeId) }
        replaceRoute(converted, existing)
    }

    /**
     * 只排路线：[routeIds] 按给的先后占用路线原来的位置，没列出的路线排在它们后面（保持原来的先后）。车的位置不动。
     */
    suspend fun reorderRoutes(routeIds: List<String>) = locked {
        val current = state.routes
        val byId = current.associateBy { it.id }
        val ordered = routeIds.distinct().mapNotNull { byId[it] } + current.filterNot { it.id in routeIds }
        val records = state.items.associateBy { it.key }
        val slots = current.map { records.getValue(FavoriteItem.routeKey(it.id)).sortOrder }.sorted()
        val reassigned = ordered.mapIndexed { index, route ->
            records.getValue(FavoriteItem.routeKey(route.id)).copy(sortOrder = slots[index])
        }.filter { it.sortOrder != records.getValue(it.key).sortOrder }
        // 路线自己的 sortOrder 和旧版一样改成 0、1、2…
        val renumbered = ordered.mapIndexed { index, route -> route.copy(sortOrder = index) }
        val updatedByKey = reassigned.associateBy { it.key }
        val next = rebuild(routes = renumbered, items = state.items.map { updatedByKey[it.key] ?: it })
        commit(next, FavoritesWrite(routes = next.routes, itemUpdates = reassigned))
    }

    /** 给某条路线的某一程加一条候选线；已有同 lineId 的不重复加（逻辑同旧版） */
    suspend fun addCandidateLineToLeg(routeId: String, legIndex: Int, candidateLine: CandidateLine) = locked {
        val route = state.routes.firstOrNull { it.id == routeId } ?: return@locked
        val updatedLegs = route.legs.map { leg ->
            if (leg.legIndex == legIndex && leg.candidateLines.none { it.lineId == candidateLine.lineId }) {
                leg.copy(candidateLines = leg.candidateLines + candidateLine)
            } else {
                leg
            }
        }
        val updated = route.copy(legs = updatedLegs, updatedAt = now())
        val next = rebuild(routes = state.routes.map { if (it.id == routeId) updated else it })
        commit(next, FavoritesWrite(routes = next.routes))
    }

    // ---- 收藏分组 ----

    override suspend fun createGroup(name: String): FavoriteGroup = locked {
        val maxOrder = state.groups.maxOfOrNull { it.sortOrder } ?: -1
        val group = FavoriteGroup(id = newId(), name = name.trim(), sortOrder = maxOrder + 1, isDefault = false)
        val next = rebuild(groups = (state.groups + group).sortedBy { it.sortOrder })
        commit(next, FavoritesWrite(groups = next.groups))
        group
    }

    override suspend fun renameGroup(groupId: String, newName: String) = locked {
        if (state.groups.none { it.id == groupId }) return@locked
        val next = rebuild(
            groups = state.groups.map { if (it.id == groupId) it.copy(name = newName.trim()) else it }.sortedBy { it.sortOrder }
        )
        commit(next, FavoritesWrite(groups = next.groups))
    }

    override suspend fun deleteGroup(groupId: String) = locked {
        val groups = state.groups.filterNot { it.id == groupId }
        // 组里的收藏不删，都变成不分组（车和路线一样）
        val cleared = state.items.filter { it.groupId == groupId }.map { it.copy(groupId = null) }
        val clearedRouteIds = cleared.filter { it.type == FavoriteItemType.ROUTE }.mapTo(HashSet()) { it.refId }
        val routes = state.routes.map { route ->
            if (route.id in clearedRouteIds || route.customGroupId == groupId) {
                route.copy(customGroupId = null, updatedAt = now())
            } else {
                route
            }
        }
        val clearedByKey = cleared.associateBy { it.key }
        val next = rebuild(
            routes = routes,
            groups = groups,
            items = state.items.map { clearedByKey[it.key] ?: it }
        )
        val routesChanged = routes != state.routes
        commit(
            next,
            FavoritesWrite(
                groups = next.groups,
                routes = if (routesChanged) next.routes else null,
                itemUpdates = cleared
            )
        )
    }

    override suspend fun reorderGroups(groupIds: List<String>) = locked {
        val byId = state.groups.associateBy { it.id }
        val reordered = groupIds.mapNotNull { byId[it] }.mapIndexed { index, group -> group.copy(sortOrder = index) } +
            state.groups.filterNot { it.id in groupIds }.mapIndexed { extra, group ->
                group.copy(sortOrder = groupIds.size + extra)
            }
        val next = rebuild(groups = reordered)
        commit(next, FavoritesWrite(groups = next.groups))
    }

    // ---- 内部 ----

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) {
        mutex.withLock { block() }
    }

    private fun minOrder(): Int = state.items.minOfOrNull { it.sortOrder } ?: 0
    private fun maxOrder(): Int = state.items.maxOfOrNull { it.sortOrder } ?: -1

    /** 改了顺序：带上受影响那一类的整份列表，镜像列（旧存储的 JSON 顺序、Room 的 position 等）跟着一致 */
    private fun mirrorWrite(next: State, changed: List<FavoriteItemRecord>) = FavoritesWrite(
        stops = if (changed.any { it.type == FavoriteItemType.STOP }) next.stops else null,
        routes = if (changed.any { it.type == FavoriteItemType.ROUTE }) next.routes else null,
        itemUpdates = changed
    )

    /** 按索引重新排车和路线、把索引的分组和顺序套到路线上 */
    private fun rebuild(
        stops: List<FavoriteStationLine> = state.stops,
        routes: List<TransitGroup> = state.routes,
        groups: List<FavoriteGroup> = state.groups,
        items: List<FavoriteItemRecord> = state.items
    ): State = arrange(stops, routes, groups, items)

    /**
     * 写盘（等写完）再发新值。写失败时和旧版一样不让应用崩：记下错误（[onWriteError]），内存里照样换成新值，
     * 下次改动整份写回时补上（旧 TransitRepository 的 SerialWriter、SharedPreferences.apply() 也是这样静默失败）。
     */
    private suspend fun commit(next: State, write: FavoritesWrite) {
        // 存储里还没有索引：这次连同整份索引一起写，之后才能只改几行
        val effective = if (itemsPersisted) write else write.copy(items = next.items, itemUpdates = emptyList())
        // 每次写都顺手记下「分组建过了」：删光分组后重启才知道那是删空的，而不是从没建过（见 create）
        try {
            store.write(effective.copy(markGroupsInitialized = true))
            itemsPersisted = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onWriteError(e)
        }
        publish(next)
    }

    /** 写盘失败时的回调（默认什么都不做；Android 上接日志） */
    var onWriteError: (Exception) -> Unit = {}

    private fun publish(next: State) {
        state = next
        _favorites.value = next.stops
        _routes.value = next.routes
        _favoriteGroups.value = next.groups
        _favoriteItems.value = itemsOf(next)
    }

    companion object {
        /** 第 9 步之前就有的两个默认分组（首次使用时写入） */
        val DEFAULT_GROUPS = listOf(
            FavoriteGroup(id = FavoriteItemOrder.COMMUTE_GROUP_ID, name = "上班通勤", sortOrder = 0, isDefault = true),
            FavoriteGroup(id = FavoriteItemOrder.HOME_GROUP_ID, name = "回家线路", sortOrder = 1, isDefault = true)
        )

        /**
         * 读一次存储，建好仓库。
         *
         * 和旧 RouteGroupRepository 一样：清掉历史测试注入的假路线（`sample_commute_group`、含 `sample_` 线路）；
         * **从没建过分组**时写入两个默认分组。怎么算「从没建过」：存储里的 `groupsInitialized` 为 false，而且
         * 分组、车、路线、合一索引一样都没有（全新安装）；分组是空列表、或者已经有别的收藏，都算建过了 ——
         * 用户把两个默认分组删光之后重启不会再补回来（v0.1.5 的 bug，issue #24）。
         * 然后对账索引（[FavoriteItemOrder.reconcile]）：存储里有索引且对不上时写回；
         * 存储里从没有过索引时只在内存里推出来，不写盘（只打开不改东西，旧存储的文件一字不动），等第一次改动时整份写入。
         *
         * @param ioDispatcher 之后每次改动在哪执行
         * @param loadContext 这次读盘在哪执行，默认同 [ioDispatcher]；测试传 `EmptyCoroutineContext` 在调用方的线程上读
         *   （旧仓库在构造函数里同步读，测试传进来的 StandardTestDispatcher 不推进也不会卡住）
         */
        @OptIn(ExperimentalUuidApi::class)
        suspend fun create(
            store: FavoritesStore,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
            newId: () -> String = { Uuid.random().toString() },
            loadContext: CoroutineContext = ioDispatcher
        ): DefaultFavoritesRepository = withContext(loadContext) {
            val snapshot = store.load()
            val loadedRoutes = snapshot.routes.orEmpty()
            val routes = loadedRoutes.filterNot { route ->
                route.id == "sample_commute_group" ||
                    route.legs.any { leg -> leg.candidateLines.any { it.lineId.startsWith("sample_") } }
            }
            // 分组表 / 列表空 == 用户删光了；只有「一样数据都没有」才是第一次用
            val seedDefaultGroups = !snapshot.groupsInitialized &&
                snapshot.groups == null &&
                snapshot.items == null &&
                snapshot.stops.isEmpty() &&
                snapshot.routes == null
            val groups = if (seedDefaultGroups) DEFAULT_GROUPS else snapshot.groups.orEmpty().sortedBy { it.sortOrder }
            val items = FavoriteItemOrder.reconcile(snapshot.stops, routes, groups, snapshot.items)
            var state = arrange(snapshot.stops, routes, groups, items)
            // 路线自己的 sortOrder 要和合一列表里路线的先后一致（不减）；对不上时（例如索引是从旧存储导进来的）按先后改成 0、1、2…
            val routeOrders = state.routes.map { it.sortOrder }
            val renumbered = routeOrders != routeOrders.sorted()
            if (renumbered) {
                state = state.copy(routes = state.routes.mapIndexed { index, route -> route.copy(sortOrder = index) })
            }

            val routesChanged = routes.size != loadedRoutes.size || renumbered
            val hadItems = snapshot.items != null
            val itemsChanged = hadItems && items != snapshot.items
            if (routesChanged || seedDefaultGroups || itemsChanged) {
                store.write(
                    FavoritesWrite(
                        stops = if (itemsChanged) state.stops else null,
                        routes = if (routesChanged || itemsChanged) state.routes else null,
                        groups = if (seedDefaultGroups) state.groups else null,
                        items = if (itemsChanged) state.items else null,
                        markGroupsInitialized = seedDefaultGroups
                    )
                )
            }
            DefaultFavoritesRepository(store, state, itemsPersisted = hadItems, ioDispatcher, now, newId)
        }

        private fun arrange(
            stops: List<FavoriteStationLine>,
            routes: List<TransitGroup>,
            groups: List<FavoriteGroup>,
            items: List<FavoriteItemRecord>
        ): State {
            val sortedItems = items.withIndex()
                .sortedWith(compareBy({ it.value.sortOrder }, { it.index }))
                .map { it.value }
            val rank = sortedItems.withIndex().associate { it.value.key to it.index }
            val recordByKey = sortedItems.associateBy { it.key }
            val orderedStops = stops.sortedBy { rank[FavoriteItem.stopKey(it.id)] ?: Int.MAX_VALUE }
            val orderedRoutes = routes
                .sortedBy { rank[FavoriteItem.routeKey(it.id)] ?: Int.MAX_VALUE }
                .map { route ->
                    val record = recordByKey[FavoriteItem.routeKey(route.id)]
                    if (record == null || route.customGroupId == record.groupId) route else route.copy(customGroupId = record.groupId)
                }
            return State(orderedStops, orderedRoutes, groups, sortedItems)
        }

        private fun itemsOf(state: State): List<FavoriteItem> {
            val stops = state.stops.associateBy { it.id }
            val routes = state.routes.associateBy { it.id }
            return state.items.mapNotNull { record ->
                when (record.type) {
                    FavoriteItemType.STOP -> stops[record.refId]?.let { FavoriteItem.Stop(it, record.groupId, record.sortOrder) }
                    FavoriteItemType.ROUTE -> routes[record.refId]?.let { FavoriteItem.Route(it, record.groupId, record.sortOrder) }
                }
            }
        }
    }
}
