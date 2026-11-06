package com.trackr.ui.screens.stopdetail

import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCodeProvider

/**
 * 站点详情页 UI 状态（第 21 步，v3）。页面 [StopDetailScreen] 只读它、只发 [StopDetailEvent]。
 *
 * @param stops 同名的几个站台（原始数据，选线、一起等要用）
 * @param platformLabels 和 [stops] 一一对应的站台名：按方向「往 X」，没有方向数据时「站台 1 / 2」
 * @param subtitle 顶栏小字：「120 米 · 8 条线」
 * @param rows 当前站台的线路行（扁平，已按 [ArrivalText] 算好到站文字）
 * @param transferText 「可换乘：地铁 1 号线」；没有为 null
 * @param isPullRefreshing 只在下拉刷新时为 true（自动轮询不转圈）
 * @param selecting 选线模式（选几条一起等）
 * @param selectedLineKeys 选线模式里勾上的行（[StopLineRowUi.key]），条数不设上限
 * @param watch 这个站正在一起等时的进行中视图；没有为 null
 * @param boardChoice 「上了哪辆？」弹层；只在定位分不清时有（§2.6 第 ④ 条）
 */
data class StopDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isPullRefreshing: Boolean = false,
    val stopName: String = "",
    val stops: List<NearbyStop> = emptyList(),
    val selectedStopIndex: Int = 0,
    val platformLabels: List<String> = emptyList(),
    val subtitle: String = "",
    val rows: List<StopLineRowUi> = emptyList(),
    val transferText: String? = null,
    val errorMessage: String? = null,
    val favoriteIds: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val selectedLineKeys: Set<String> = emptySet(),
    val watch: WatchTogetherUi? = null,
    val boardChoice: BoardChoiceUi? = null,
    val preferredTransitCodeProvider: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val isTransitCodeSheetOpen: Boolean = false,
    /** 乘车码圆钮显不显示（「我的」里的开关，和首页、线路页同一个） */
    val showTransitCodeButton: Boolean = true
) {
    /** 当前站台名（选线模式顶栏小字「科技园站 · 往北」用）；只有一个站台时为空串 */
    val selectedPlatformLabel: String
        get() = if (stops.size > 1) platformLabels.getOrNull(selectedStopIndex).orEmpty() else ""
}

/**
 * 站点页的一行线路。
 *
 * @param key 列表 key，也是选线的 key（同一站台内唯一）
 * @param hours 运营时间「06:30–22:30」；没有为空串
 * @param arrival 到站文字（大字 + 小字 + 颜色）
 * @param selectable 能不能勾进一起等（要站序）；点开线路页只看 lineId（掌上公交的站点行不带站序）
 */
data class StopLineRowUi(
    val key: String,
    val lineId: String,
    val lineName: String,
    val endStop: String,
    val hours: String,
    val arrival: ArrivalDisplay,
    val isFavorite: Boolean,
    val selectable: Boolean,
    val targetOrder: Int,
    val stopId: String,
    val source: DataSourceType
)

/**
 * 一起等进行中视图（v3「一起等 · 进行中」）。
 *
 * @param lines 等车时按到站先后排，第一条放大；上车后为空
 * @param isOnBoard 已经上车（只追坐上的那辆）
 * @param boardedTitle 上车后的大字「已上 325」；等车时为空串
 * @param boardedDetail 上车后的小字「还有 5 站到终点」；等车时为空串
 */
data class WatchTogetherUi(
    val lineCount: Int,
    val lines: List<WatchLineUi>,
    val isOnBoard: Boolean = false,
    val boardedTitle: String = "",
    val boardedDetail: String = ""
)

/**
 * 一起等里的一条线。
 * @param detail 主卡小字「还有 1 站 · 粤B·58921」
 */
data class WatchLineUi(
    val lineId: String,
    val lineName: String,
    val endStop: String,
    val arrival: ArrivalDisplay,
    val plate: String,
    val detail: String
)

/** 「上了哪辆？」弹层（§2.6 第 ④ 条） */
data class BoardChoiceUi(
    /** 哪次追踪会话的待选（打开弹层的导航 Key 用） */
    val sessionId: String = "",
    /** 弹层小字「两辆车都在 科技园站」 */
    val subtitle: String,
    val options: List<BoardOptionUi>
)

/** 「上了哪辆？」里的一辆车 */
data class BoardOptionUi(
    val busId: String,
    val lineName: String,
    val endStop: String,
    val plate: String
)

/** 页面发给 ViewModel / Route 的事件 */
sealed interface StopDetailEvent {
    /** 返回（选线模式里是退出选线；由 Route 处理导航） */
    data object Back : StopDetailEvent

    /** 点开线路页（由 Route 处理导航） */
    data class OpenLine(val row: StopLineRowUi) : StopDetailEvent

    data object PullRefresh : StopDetailEvent
    data object Retry : StopDetailEvent
    data class SelectPlatform(val index: Int) : StopDetailEvent
    data class ToggleFavorite(val rowKey: String) : StopDetailEvent

    /** 进选线模式（「选几条一起等」） */
    data object StartSelecting : StopDetailEvent

    /** 长按一条线：进选线模式，这条能选就顺手勾上 */
    data class StartSelectingWith(val rowKey: String) : StopDetailEvent

    /** 退出选线模式 */
    data object CancelSelecting : StopDetailEvent
    data class ToggleLine(val rowKey: String) : StopDetailEvent

    /** 选线模式「收藏」：把勾上的线存成只有 1 程的路线 */
    data object SaveSelectionAsRoute : StopDetailEvent

    /** 选线模式「一起等这 N 条」 */
    data object StartWatching : StopDetailEvent

    /** 进行中「再加一条」：带着正在等的线进选线模式 */
    data object AddLine : StopDetailEvent
    data object ConfirmBoarded : StopDetailEvent
    data object StopWatching : StopDetailEvent

    /** 「上了哪辆？」选了 [busId]；null =「还没上」 */
    data class ChooseBoardedBus(val busId: String?) : StopDetailEvent

    /** 关掉「上了哪辆？」弹层但不选（这次待选不再弹） */
    data object DismissBoardChoice : StopDetailEvent

    /** 乘车码：选了默认平台就直接打开，「每次询问」才弹选择 */
    data object OpenTransitCode : StopDetailEvent
    data object DismissTransitCodeSheet : StopDetailEvent
    data class PickTransitCode(val provider: TransitCodeProvider, val saveAsDefault: Boolean) : StopDetailEvent
}
