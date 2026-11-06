package com.trackr.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.rememberResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.savedstate.SavedState
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.PolymorphicSerializer

/**
 * 记住导航状态：每个顶层页签一条返回栈 + 当前页签，都能跨配置变更和进程重建恢复（§3.3、§3.8）。
 * 页签集合在一次组合里必须固定（每条栈各占一个 `rememberSerializable` 槽位）；以后页签动态显示 / 隐藏，
 * 栈照样都建，可见性交给 [Navigator.onTabsChanged]。
 */
@Composable
fun rememberNavigationState(
    start: ScreenKey = TopLevel.start,
    topLevel: List<ScreenKey> = TopLevel.tabs,
    configuration: SavedStateConfiguration = TrackrSavedStateConfiguration,
): NavigationState {
    require(start in topLevel) { "start=$start 不在顶层页签 $topLevel 里" }
    val current = rememberSerializable(
        start, topLevel,
        stateSerializer = ScreenKey.serializer(),
        configuration = configuration,
    ) { mutableStateOf(start) }
    // 升级前恢复出来的当前页签可能已经不是页签了（第 17 步删了「收藏」页签）：回到起始页签
    remember(current, topLevel) {
        if (current.value !in topLevel) current.value = start
    }
    // 每条栈跟着页签名走，不按调用位置。第 17 步页签从 4 个变 3 个，按位置存的话旧版存下的第 3 条（收藏）会被「更多」拿走
    val stackSaver = remember(configuration) { navBackStackSaver(configuration) }
    val stacks = topLevel.associateWith { tab ->
        // key(tab) 给每条栈独立的组合身份，等价于以前 saveable 的自定义 key（循环里按位置会互相抢槽位）
        key(tab) {
            rememberSaveable(saver = stackSaver) { NavBackStack<NavKey>(tab) }.also { stack ->
                // 兜底：恢复出来的栈底不是这个页签（旧版存档、页签改动）就丢掉，从页签根页重来
                remember(stack) {
                    if (stack.firstOrNull() != tab) {
                        stack.clear()
                        stack.add(tab)
                    }
                }
            }
        }
    }
    return remember(start, topLevel, current, stacks) { NavigationState(start, current, stacks) }
}

/** 一条返回栈和 [SavedState] 互转（和 `rememberNavBackStack` 用的序列化一样，只是存档 key 由调用方给） */
internal fun navBackStackSaver(configuration: SavedStateConfiguration): Saver<NavBackStack<NavKey>, SavedState> {
    val serializer = NavBackStackSerializer(PolymorphicSerializer(NavKey::class))
    return Saver(
        save = { stack -> encodeToSavedState(serializer, stack, configuration) },
        restore = { saved -> decodeFromSavedState(serializer, saved, configuration) },
    )
}

/**
 * 导航状态本身（不含 Compose 槽位），[Navigator] 只改它；单元测试可以直接 new。
 *
 * @param stacks 顶层页签 -> 该页签的返回栈（栈底就是页签自己）。
 */
class NavigationState(
    val start: ScreenKey,
    current: MutableState<ScreenKey>,
    val stacks: Map<ScreenKey, NavBackStack<NavKey>>,
) {
    init {
        require(start in stacks) { "start=$start 没有对应的返回栈" }
    }

    /** 当前页签。 */
    var topLevel: ScreenKey by current

    /** 当前页签的返回栈。 */
    val currentStack: NavBackStack<NavKey> get() = stacks.getValue(topLevel)

    /** 当前显示的页面。 */
    val currentKey: NavKey? get() = currentStack.lastOrNull()

    /** 当前页签停在根页（底栏显示的条件）。根页上叠着弹层（[OverlayKey]）也算，和页内弹层一样底栏不收起。 */
    val isAtTabRoot: Boolean get() = currentStack.count { it !is OverlayKey } <= 1

    /**
     * 起始页签垫底、当前页签在上（recipe 的 exit-through-home 写法）：非起始页签根页按返回会回到起始页签。
     * 第 16 步 `MainActivity` 不用这个（见 [stacksShown]），计划 §3.1 的返回规则留到第 17 步改 3 页签时启用。
     */
    val stacksInUse: List<ScreenKey>
        get() = if (topLevel == start) listOf(start) else listOf(start, topLevel)

    /**
     * 交给 NavDisplay 的栈。[startUnderneath] = false 时只给当前页签的栈：和第 16 步之前一样，
     * 非首页页签根页按返回直接交给系统（退出 App），切页签是整条栈替换（转场走 push）。
     */
    fun stacksShown(startUnderneath: Boolean): List<ScreenKey> =
        if (startUnderneath) stacksInUse else listOf(topLevel)

    /** 「当前页签是 [tab]，且它停在根页」（第 16 步首页轮询开关用过；第 17 步起轮询改由首页 Route 的生命周期控制） */
    fun isShowingRootOf(tab: ScreenKey): Boolean =
        topLevel == tab && stacks[tab]?.lastOrNull() == tab

    /** 按 [stacksShown] 拼出的 Key 列表（和 [toDecoratedEntries] 的顺序一致），测试和调试用。 */
    fun keysShown(startUnderneath: Boolean = true): List<NavKey> =
        stacksShown(startUnderneath).flatMap { stacks.getValue(it) }

    /**
     * 每条栈各自一组 decorator（保存页内状态 + ViewModel 作用域），切页签不会清掉另一条栈的状态（修 N2）；
     * 只有真的从某条栈里弹出的 entry 才清它的 rememberSaveable 状态和 ViewModel。
     *
     * 所有 entry 共用一个 `ResultEventBus`（§3.5）：弹层用 `LocalResultEventBus.current.sendResult(…)` 回传一次性结果，
     * 上一页用 `ResultEffect<T> { … }` 收（第 22 步「收藏弹层 → 规划页『去看看』」用）。
     */
    @Composable
    fun toDecoratedEntries(
        startUnderneath: Boolean = true,
        entryProvider: (NavKey) -> NavEntry<NavKey>,
    ): List<NavEntry<NavKey>> {
        val resultEventBus = rememberResultEventBus()
        val resultDecorator = rememberResultEventBusNavEntryDecorator<NavKey>(resultEventBus)
        val decorated = stacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                    resultDecorator,
                ),
                entryProvider = entryProvider,
            )
        }
        return stacksShown(startUnderneath).flatMap { decorated.getValue(it) }
    }
}
