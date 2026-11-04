package com.trackr.core.navigation

import androidx.navigation3.runtime.NavKey

/**
 * 顶层页签（每个页签一条返回栈）。第 17 步按 §3.1 改成 3 个：首页、路线规划、更多
 * （「收藏」不再是页签，[ScreenKey.FavoritesGroup] 作为首页栈的子页保留到第 22 步）。
 * 页签的 Key 和底栏文字暂不改名（Home→Bus、More→Me 留给阶段 D）。
 */
object TopLevel {
    /** 起始页签：非起始页签在根页按返回回到这里；它的根页再按返回就退出（exit-through-home）。 */
    val start: ScreenKey = ScreenKey.Home

    /** 底栏顺序（眉山特化：公交 / 线路 / 路线 / 设置）。 */
    val tabs: List<ScreenKey> = listOf(
        ScreenKey.Home,
        ScreenKey.Lines,
        ScreenKey.RoutePlan,
        ScreenKey.More,
    )

    fun isTopLevel(key: NavKey?): Boolean = key != null && key in tabs

    /**
     * 底栏实际显示的页签（D2）：没有高德 Key（`AmapKeyRepository.hasMapKey` 为 false）时去掉「路线规划」。
     * 顺序同 [tabs]。页签集合变了交给 [Navigator.onTabsChanged]（在被去掉的页签上就清栈回起始页签）。
     */
    fun visibleTabs(hasMapKey: Boolean): List<ScreenKey> =
        if (hasMapKey) tabs else tabs.filterNot { it == ScreenKey.RoutePlan }
}
