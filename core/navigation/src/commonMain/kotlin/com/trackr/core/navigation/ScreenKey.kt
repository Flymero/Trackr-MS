package com.trackr.core.navigation

import androidx.navigation3.runtime.NavKey
import com.trackr.core.model.DataSourceType
import kotlinx.serialization.Serializable

/**
 * 所有页面的 Navigation 3 Key。第 16 步只从 `:androidApp` 原样搬来（字段、类名不变，旧返回栈仍能恢复）；
 * 改名（Home→Bus、More→Me）、`LineRef` 与新增页面等第 17 步起按 §3.2 做。
 *
 * 新增子类时要同时登记到 [TrackrSavedStateConfiguration]，否则非 Android 平台恢复返回栈会失败
 * （`NavigationStateRestorationTest` 会挡住漏登记）。
 */
@Serializable
sealed interface ScreenKey : NavKey {
    @Serializable
    data object Home : ScreenKey

    @Serializable
    data object RoutePlan : ScreenKey

    @Serializable
    data object FavoritesGroup : ScreenKey

    @Serializable
    data object Search : ScreenKey

    @Serializable
    data class LineDetail(
        val cityId: String,
        val lineId: String,
        val targetOrder: Int = 0,
        val stopId: String = "",
        val source: DataSourceType = DataSourceType.CHELAILE,
        val lineName: String = "",
        /**
         * 上车站名（路线规划 / 路线详情点线路进来才有）。高德方案没对齐到实时源时 [lineId] / [targetOrder]
         * 都靠不住（站序是占位的 1），线路页按站名找候车站，这个方向没有就换反向找。
         */
        val boardStopName: String = "",
        /** 下车站名：和 [boardStopName] 一起判方向（上车站要在下车站前面） */
        val alightStopName: String = ""
    ) : ScreenKey

    @Serializable
    data class StopDetail(
        val cityId: String,
        val stopId: String,
        val stopName: String = "",
        val physicalStId: String = "",
        val namesakeStId: String = "",
        val lat: Double = 0.0,
        val lng: Double = 0.0
    ) : ScreenKey

    @Serializable
    data object More : ScreenKey

    @Serializable
    data object About : ScreenKey

    @Serializable
    data object ThemeSettings : ScreenKey

    /** 编辑一个定时提醒（§3.1「我的」页签子页，第 25 步） */
    @Serializable
    data class ReminderEdit(val reminderId: String) : ScreenKey

    /** 高德 Key（§3.1「我的」页签子页，第 25 步；原「更多」页的对话框） */
    @Serializable
    data object AmapKey : ScreenKey

    /** 路线详情（§3.1 公交页签子页，第 22 步做页面；第 17 步只占位） */
    @Serializable
    data class RouteDetail(val groupId: String) : ScreenKey

    /** 编辑收藏（§3.1 公交页签子页，第 22 步做页面；第 17 步只占位） */
    @Serializable
    data object FavoritesEdit : ScreenKey

    /**
     * 「上了哪辆？」弹层（§2.6 第 ④ 条、§3.7）：叠在当前栈顶，用 `BottomSheetSceneStrategy` 显示。
     * 通知点开（`trackr://tracking/board?session=`）或站点页遇到待选时打开；会话换了、待选没了就自己关掉。
     */
    @Serializable
    data class ChooseBoardedBus(val sessionId: String) : ScreenKey, OverlayKey
}

/** 叠在页面上的弹层 Key：在栈里，但不算「离开了页签根页」（底栏照常显示）。 */
interface OverlayKey
