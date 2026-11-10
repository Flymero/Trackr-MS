package com.trackr.ui.screens.linedetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.trackr.core.designsystem.component.FullscreenErrorView
import com.trackr.core.designsystem.component.FullscreenLoadingView
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_end_tracking
import com.trackr.core.designsystem.resources.action_favorite
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.resources.action_unfavorite
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_clock
import com.trackr.core.designsystem.resources.ic_play
import com.trackr.core.designsystem.resources.ic_qr_code
import com.trackr.core.designsystem.resources.ic_star
import com.trackr.core.designsystem.resources.ic_star_filled
import com.trackr.core.designsystem.resources.ic_swap
import com.trackr.core.designsystem.resources.transit_code
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusStop
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.RailMode
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.displayLineName
import com.trackr.core.model.displayLineNameWithoutDiversion
import com.trackr.core.model.diversionNote
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_detail_error
import com.trackr.feature.line.resources.line_detail_fare
import com.trackr.feature.line.resources.line_detail_diversion_body
import com.trackr.feature.line.resources.line_detail_hours
import com.trackr.feature.line.resources.line_detail_loading
import com.trackr.feature.line.resources.line_detail_reminder
import com.trackr.feature.line.resources.line_detail_reverse
import com.trackr.feature.line.resources.line_detail_stop_count
import com.trackr.feature.line.resources.line_detail_switching
import com.trackr.feature.line.resources.line_detail_timetable
import com.trackr.feature.line.resources.line_detail_track_this
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.ui.components.HorizontalRailColumnWidth
import com.trackr.ui.components.HorizontalRouteRail
import com.trackr.ui.components.ScheduledReminderDialog
import com.trackr.ui.components.TransitCodeBottomSheet
import com.trackr.ui.components.routeRailItemIndex
import com.trackr.ui.components.routeRailItems
import com.trackr.ui.components.timetable.TimetableBottomSheet
import com.trackr.ui.screens.linedetail.components.BusFollowContent
import com.trackr.ui.screens.linedetail.components.BusListContent
import com.trackr.ui.screens.linedetail.components.FavoriteGroupPickerSheet
import com.trackr.ui.screens.linedetail.components.StationArrivalContent
import com.trackr.ui.screens.linedetail.components.overflowMarquee
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 线路详情页发出的事件（无状态页面只发事件，见 [LineDetailRoute]） */
sealed interface LineDetailEvent {
    data object Back : LineDetailEvent
    /** @param fromPull 下拉触发（画下拉圈）；false = 错误条「重试」 */
    data class Refresh(val fromPull: Boolean) : LineDetailEvent
    data class SelectStop(val stop: BusStop) : LineDetailEvent
    data class FollowBus(val bus: LiveBus) : LineDetailEvent
    data object OpenBusList : LineDetailEvent
    /** 卡片内的返回：退一级（跟随 → 车辆列表 / 站点卡，车辆列表 → 站点卡）；顶栏返回也先走它 */
    data object BackFromPanel : LineDetailEvent
    data object SwitchDirection : LineDetailEvent
    data object ToggleFavorite : LineDetailEvent
    data object OpenGroupPicker : LineDetailEvent
    data object DismissGroupPicker : LineDetailEvent
    data class MoveToGroup(val groupId: String?) : LineDetailEvent
    data object OpenTimetable : LineDetailEvent
    data object DismissTimetable : LineDetailEvent
    data object OpenReminder : LineDetailEvent
    data object DismissReminder : LineDetailEvent
    data class SaveReminder(
        val hour: Int,
        val minute: Int,
        val daysOfWeek: Set<Int>,
        val autoStartTracking: Boolean
    ) : LineDetailEvent
    /** 底栏主按钮：追踪这班车 / 结束追踪 */
    data object ToggleTracking : LineDetailEvent
    /** 底栏乘车码：一点就开默认渠道，「每次询问」才弹选择 */
    data object OpenTransitCode : LineDetailEvent
    data object DismissTransitCodeSheet : LineDetailEvent
    data class PickTransitCode(val provider: TransitCodeProvider, val saveAsDefault: Boolean) : LineDetailEvent
    data class MessageShown(val id: Long) : LineDetailEvent
    /** 长辈版「读给我听」：朗读主卡上的到站 */
    data object ReadAloud : LineDetailEvent
}

/**
 * 线路详情（v3）：顶栏「返回 · 线路名 · 时钟 · ☆」；方向「始发 → 终点」+「换向」、运营信息 +「时刻表 ›」；
 * 主卡固定在顶部；竖向走向条单独滚动；底栏「主按钮 + 乘车码」；下拉刷新；收藏等一次性提示走提示条。
 *
 * 底栏自己让开系统导航栏，列表底部留白 = Scaffold 的 innerPadding（含底栏和导航栏），三键导航下不重叠（issue #2）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineDetailScreen(
    state: LineDetailUiState,
    onEvent: (LineDetailEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val listState = rememberLazyListState()
    val stripScrollState = rememberScrollState()
    val pageScrollState = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val static = state.static

    // 走向条样式是「我的」里的全局设置，线路页不提供切换入口
    val isHorizontalRail = state.railMode == RailMode.Horizontal
    // 从某个站点点进来：走向条直接跳到那一站，只跳一次。主卡固定在顶部，不会跟着跳走。
    // 没带站序（搜索、收藏等）就保持从顶部开始；选站、轮询都不会再跳（换向另有滚动，见下）
    var didJumpToEntryStop by rememberSaveable { mutableStateOf(false) }
    // 主卡可以收起成一行，把高度让给走向条；收起状态跟着页面保存
    var cardCollapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(static?.stops, state.entryStopOrder) {
        val stops = static?.stops ?: return@LaunchedEffect
        val order = state.entryStopOrder ?: return@LaunchedEffect
        if (didJumpToEntryStop || stops.none { it.order == order }) return@LaunchedEffect
        didJumpToEntryStop = true
        val index = routeRailItemIndex(stops, order)
        val target = (index - 2).coerceAtLeast(0)
        if (isHorizontalRail) {
            stripScrollState.scrollTo(with(density) { (HorizontalRailColumnWidth * target).roundToPx() })
        } else {
            listState.scrollToItem(target)
        }
    }

    // 在「我的」改了走向条样式再回来：把新视图滚到当前候车站；选站、轮询都不滚
    var previousRailMode by remember { mutableStateOf(state.railMode) }
    LaunchedEffect(state.railMode) {
        if (previousRailMode == state.railMode) return@LaunchedEffect
        previousRailMode = state.railMode
        val stops = static?.stops ?: return@LaunchedEffect
        val order = state.selectedStop?.order ?: return@LaunchedEffect
        val index = routeRailItemIndex(stops, order)
        val target = (index - 2).coerceAtLeast(0)
        if (isHorizontalRail) {
            stripScrollState.animateScrollTo(with(density) { (HorizontalRailColumnWidth * target).roundToPx() })
        } else {
            listState.animateScrollToItem(target)
        }
    }

    // 换向后站表整个反过来：把走向条滚到新方向上的候车站（初次加载只记下线路，不滚）
    var lastLineId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(static?.line?.lineId) {
        val lineId = static?.line?.lineId ?: return@LaunchedEffect
        val previous = lastLineId
        lastLineId = lineId
        if (previous == null || previous == lineId) return@LaunchedEffect
        val stops = static.stops
        val order = state.selectedStop?.order ?: return@LaunchedEffect
        if (stops.none { it.order == order }) return@LaunchedEffect
        val target = (routeRailItemIndex(stops, order) - 2).coerceAtLeast(0)
        if (isHorizontalRail) {
            stripScrollState.scrollTo(with(density) { (HorizontalRailColumnWidth * target).roundToPx() })
        } else {
            listState.scrollToItem(target)
        }
    }

    // 车辆列表 / 单车跟随里，系统返回 = 退一级，不离开线路详情
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.panelMode != PanelMode.STATION_CARD,
        onBackCompleted = { onEvent(LineDetailEvent.BackFromPanel) }
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LineTopBar(
                title = static?.line?.displayLineNameWithoutDiversion?.takeIf { it.isNotBlank() } ?: state.lineNameHint,
                isFavorite = state.isFavorite,
                actionsEnabled = static != null && state.selectedStop != null,
                onBack = { onEvent(LineDetailEvent.Back) },
                onReminder = { onEvent(LineDetailEvent.OpenReminder) },
                onToggleFavorite = { onEvent(LineDetailEvent.ToggleFavorite) }
            )
        },
        bottomBar = {
            if (static != null) {
                LineBottomBar(
                    isTracking = state.isTrackingThisStop,
                    canTrack = state.selectedStop != null && state.live != null,
                    // 没有反向线路（单程 / 环线 / 上游缺数据）就不占位；lineId 为空同样当没有
                    canSwitchDirection = static.reverseDirection?.lineId?.isNotBlank() == true,
                    isSwitchingDirection = state.isSwitchingDirection,
                    // 「我的」里关掉「显示乘车码按钮」后，底栏这个圆钮和首页悬浮按钮一起消失
                    showTransitCode = state.showTransitCodeButton,
                    onToggleTracking = { onEvent(LineDetailEvent.ToggleTracking) },
                    onSwitchDirection = { onEvent(LineDetailEvent.SwitchDirection) },
                    onOpenTransitCode = { onEvent(LineDetailEvent.OpenTransitCode) }
                )
            }
        },
        // 一次性提示：一次显示一条，显示完告诉 ViewModel
        snackbarHost = {
            UserMessageHost(
                message = state.userMessages.firstOrNull()?.let {
                    HostMessage(key = it.id, text = it.text, actionLabel = it.action?.label, duration = SnackbarDuration.Short)
                },
                onResult = { key, actionPerformed ->
                    val message = state.userMessages.firstOrNull { it.id == key }
                    if (actionPerformed && message?.action == LineUserMessage.Action.CHANGE_GROUP) {
                        currentOnEvent(LineDetailEvent.OpenGroupPicker)
                    }
                    currentOnEvent(LineDetailEvent.MessageShown(key as Long))
                }
            )
        }
    ) { innerPadding ->
        when {
            static == null && state.isLoading -> FullscreenLoadingView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                message = stringResource(LineRes.string.line_detail_loading)
            )

            static == null -> FullscreenErrorView(
                errorMessage = state.errorMessage ?: stringResource(LineRes.string.line_detail_error),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onRetry = { onEvent(LineDetailEvent.Refresh(fromPull = false)) }
            )

            else -> PullToRefreshBox(
                isRefreshing = state.isPullRefreshing,
                onRefresh = { onEvent(LineDetailEvent.Refresh(fromPull = true)) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val viewportHeight = maxHeight
                    val live = state.live
                    val busesByOrder = remember(live?.buses) { live?.buses.orEmpty().groupBy { it.order } }
                    val hasError = state.errorMessage != null
                    // 竖排：主卡固定在顶部、最多占六成，剩下的给站点列表，内容多在卡内滚。
                    // 横排：整页一起滚，主卡按内容自然高（能完整展开），不再给它限高。
                    val cardMaxHeight = viewportHeight * 0.6f
                    // 横排没有可上下滚的列表；加一层纵向滚动，下拉刷新才收得到手势，
                    // 主卡展开后超过一屏也能滚下去看走向条
                    val contentModifier = if (isHorizontalRail) {
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(pageScrollState)
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    } else {
                        Modifier.fillMaxSize()
                    }
                    Column(modifier = contentModifier) {
                        // 横排要算横条还有多少剩余空间，先量出方向头 + 主卡的高度
                        var topBlockHeightPx by remember { mutableStateOf(0) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { topBlockHeightPx = it.size.height }
                        ) {
                            DirectionHeader(
                                static = static,
                                onOpenTimetable = { onEvent(LineDetailEvent.OpenTimetable) }
                            )
                            static.line.diversionNote?.let { DiversionBanner(note = it) }
                            if (hasError) {
                                ErrorBanner(
                                    text = state.errorMessage.orEmpty(),
                                    onRetry = { onEvent(LineDetailEvent.Refresh(fromPull = false)) }
                                )
                            }
                            // 主卡：竖排固定在顶部，不跟站点列表一起滚；横排整页一起滚
                            HeroCard(
                                state = state,
                                static = static,
                                onEvent = onEvent,
                                onPickStop = {
                                    val order = state.selectedStop?.order ?: return@HeroCard
                                    val index = routeRailItemIndex(static.stops, order)
                                    val target = (index - 2).coerceAtLeast(0)
                                    scope.launch {
                                        if (isHorizontalRail) {
                                            stripScrollState.animateScrollTo(
                                                with(density) { (HorizontalRailColumnWidth * target).roundToPx() }
                                            )
                                        } else {
                                            listState.animateScrollToItem(target)
                                        }
                                    }
                                },
                                modifier = if (isHorizontalRail) Modifier else Modifier.heightIn(max = cardMaxHeight),
                                // 横排整页一起滚：主卡不限高，卡内也不再滚一层，否则手势被主卡吃掉、页面滚不动
                                scrollable = !isHorizontalRail,
                                collapsed = cardCollapsed,
                                onToggleCollapsed = { cardCollapsed = !cardCollapsed }
                            )
                        }
                        if (isHorizontalRail) {
                            // 横排只有一条横线，没有竖排站点列表；不给卡片外壳，直接铺在页面背景上，
                            // 左右贴满整宽（轨道自身左右留 16dp）。
                            // 位置：主卡到页面底部的剩余空间里垂直居中（设计稿 §3）；主卡内容多、超过一屏时
                            // 这块区域只按横条自身高度排，整页一起上下滚
                            val remainingHeight = with(density) {
                                viewportHeight - topBlockHeightPx.toDp() - innerPadding.calculateBottomPadding()
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = remainingHeight.coerceAtLeast(0.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                HorizontalRouteRail(
                                    stops = static.stops,
                                    buses = live?.buses.orEmpty(),
                                    selectedStop = state.selectedStop,
                                    selectedBusId = state.followedBusId,
                                    scrollState = stripScrollState,
                                    onSelectStop = { onEvent(LineDetailEvent.SelectStop(it)) },
                                    onSelectBus = { onEvent(LineDetailEvent.FollowBus(it)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                // 底部 = 底栏 + 系统导航栏（Scaffold 已算进 innerPadding），不用 consumeWindowInsets，避免被吃成 0（issue #2）
                                contentPadding = PaddingValues(top = 4.dp, bottom = innerPadding.calculateBottomPadding() + 16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                routeRailItems(
                                    stops = static.stops,
                                    busesByOrder = busesByOrder,
                                    selectedOrder = state.selectedStop?.order,
                                    selectedBusId = state.followedBusId,
                                    onSelectStop = { onEvent(LineDetailEvent.SelectStop(it)) },
                                    onSelectBus = { onEvent(LineDetailEvent.FollowBus(it)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 时刻表
    val timetable = state.timetable
    if (state.isTimetableSheetOpen && timetable != null) {
        TimetableBottomSheet(
            timetable = timetable,
            line = static?.line,
            onDismiss = { onEvent(LineDetailEvent.DismissTimetable) }
        )
    }

    // 乘车码选择（只在「每次询问」时出现）
    if (state.isTransitCodeSheetOpen) {
        TransitCodeBottomSheet(
            onDismiss = { onEvent(LineDetailEvent.DismissTransitCodeSheet) },
            onSelectProvider = { provider, saveAsDefault ->
                onEvent(LineDetailEvent.PickTransitCode(provider, saveAsDefault))
            }
        )
    }

    // 定时提醒
    val selectedStop = state.selectedStop
    if (state.isReminderDialogOpen && static != null && selectedStop != null) {
        ScheduledReminderDialog(
            supportsAutoStart = state.supportsAutoStartReminders,
            lineName = static.line.name,
            endStopName = static.line.endStop,
            targetStopName = selectedStop.name,
            onDismissRequest = { onEvent(LineDetailEvent.DismissReminder) },
            onConfirm = { hour, minute, daysOfWeek, autoStartTracking ->
                onEvent(LineDetailEvent.SaveReminder(hour, minute, daysOfWeek, autoStartTracking))
            }
        )
    }

    // 换分组
    if (state.isGroupPickerOpen) {
        FavoriteGroupPickerSheet(
            groups = state.favoriteGroups,
            currentGroupId = state.favoriteGroupId,
            onPick = { onEvent(LineDetailEvent.MoveToGroup(it)) },
            onDismiss = { onEvent(LineDetailEvent.DismissGroupPicker) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineTopBar(
    title: String,
    isFavorite: Boolean,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    onReminder: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(imageVector = vectorResource(Res.drawable.ic_back), contentDescription = stringResource(Res.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = onReminder, enabled = actionsEnabled) {
                Icon(imageVector = vectorResource(Res.drawable.ic_clock), contentDescription = stringResource(LineRes.string.line_detail_reminder))
            }
            IconButton(onClick = onToggleFavorite, enabled = actionsEnabled) {
                Icon(
                    imageVector = vectorResource(if (isFavorite) Res.drawable.ic_star_filled else Res.drawable.ic_star),
                    contentDescription = if (isFavorite) stringResource(Res.string.action_unfavorite) else stringResource(Res.string.action_favorite),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

/** 「始发 → 终点」+「运营 05:30–23:30 · 票价 2元 · 共 24 站 · 时刻表 ›」。只读静态部分，轮询时不重组 */
@Composable
private fun DirectionHeader(
    static: LineStatic,
    onOpenTimetable: () -> Unit
) {
    val line = static.line
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // 换向已经挪到底栏，方向行整行留给站名；站名太长时在行里滚，不打省略号
        Text(
            text = "${line.startStop} → ${line.endStop}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.overflowMarquee()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val meta = listOfNotNull(
                stringResource(LineRes.string.line_detail_hours, line.firstTime.ifEmpty { "—" }, line.lastTime.ifEmpty { "—" }),
                line.price.takeIf { it.isNotEmpty() }?.let { stringResource(LineRes.string.line_detail_fare, it) },
                stringResource(LineRes.string.line_detail_stop_count, static.stops.size)
            ).joinToString(" · ")
            Text(
                text = meta,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(onClick = onOpenTimetable) {
                Text(text = stringResource(LineRes.string.line_detail_timetable), fontSize = 13.sp)
            }
        }
    }
}

/** 临时改道提示条：线路名里带「（临时改道）」时显示，标题已去掉这截括号 */
@Composable
private fun DiversionBanner(note: String) {
    Surface(
        color = TrackrTheme.colors.warn.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = note,
                color = TrackrTheme.colors.warn,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(LineRes.string.line_detail_diversion_body, note),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(Res.string.action_retry), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 主卡：到站 / 全部车辆 / 单车跟随 三种内容切换；固定在页面顶部。
 *
 * @param scrollable 内容超过限高时卡内自己滚（竖排：主卡限高、页面不滚）；
 *   横排整页一起滚，传 false，不然卡内滚和外层滚抢手势
 */
@Composable
private fun HeroCard(
    state: LineDetailUiState,
    static: LineStatic,
    onEvent: (LineDetailEvent) -> Unit,
    onPickStop: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null
) {
    val innerScrollState = rememberScrollState()
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.busColors.stationCardContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(modifier)
    ) {
        AnimatedContent(
            targetState = state.panelMode to collapsed,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 200, delayMillis = 60)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 140)) using
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ ->
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                        }
                    )
            },
            contentAlignment = Alignment.TopCenter,
            label = "panel_state_transition",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .then(if (scrollable) Modifier.verticalScroll(innerScrollState) else Modifier)
        ) { (mode, isCollapsed) ->
            when (mode) {
                PanelMode.STATION_CARD -> StationArrivalContent(
                    static = static,
                    live = state.live,
                    selectedStop = state.selectedStop,
                    isTrackingActive = state.isTrackingThisStop,
                    onPickStop = onPickStop,
                    onOpenBusList = { onEvent(LineDetailEvent.OpenBusList) },
                    collapsed = isCollapsed,
                    onToggleCollapsed = onToggleCollapsed
                )

                PanelMode.BUS_LIST -> BusListContent(
                    lineDetail = rememberLineDetail(static, state.live),
                    selectedStop = state.selectedStop,
                    onBack = { onEvent(LineDetailEvent.BackFromPanel) },
                    onSelectBus = { onEvent(LineDetailEvent.FollowBus(it)) }
                )

                PanelMode.BUS_FOLLOW -> {
                    val detail = rememberLineDetail(static, state.live)
                    BusFollowContent(
                        lineDetail = detail,
                        followedBus = detail.buses.find { it.busId == state.followedBusId },
                        onBack = { onEvent(LineDetailEvent.BackFromPanel) }
                    )
                }
            }
        }
    }
}

/** 车辆列表 / 单车跟随还吃整份 [BusLineDetail]，由静态 + 实时拼回去 */
@Composable
private fun rememberLineDetail(static: LineStatic, live: LineLive?): BusLineDetail = remember(static, live) {
    BusLineDetail(
        line = static.line,
        reverseDirection = static.reverseDirection,
        stops = static.stops,
        buses = live?.buses.orEmpty(),
        operatingState = live?.operatingState ?: LineOperatingState.RUNNING,
        statusDescription = live?.statusDescription.orEmpty(),
        nextDepartureFromStart = live?.nextDepartureFromStart,
        updatedAtMillis = live?.updatedAtMillis ?: 0L
    )
}

/**
 * 底栏：主按钮 + 换向 + 乘车码（和首页悬浮按钮同一个位置）；自己让开系统导航栏。
 * 换向是高频动作，从方向行挪到这里常驻；反向线路不存在时不渲染，主按钮自动撑满。
 * [showTransitCode] 是「我的」里的开关（关掉后乘车码圆钮不占位，主按钮和换向铺满）。
 */
@Composable
private fun LineBottomBar(
    isTracking: Boolean,
    canTrack: Boolean,
    canSwitchDirection: Boolean,
    isSwitchingDirection: Boolean,
    showTransitCode: Boolean,
    onToggleTracking: () -> Unit,
    onSwitchDirection: () -> Unit,
    onOpenTransitCode: () -> Unit
) {
    val switchDirectionLabel = stringResource(LineRes.string.line_detail_reverse)
    val switchingDirectionLabel = stringResource(LineRes.string.line_detail_switching)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            // 三钮并排时缝隙收窄，320dp 小屏也放得下「追踪这班车」
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isTracking) {
                OutlinedButton(
                    onClick = onToggleTracking,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.action_end_tracking),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            } else {
                Button(
                    onClick = onToggleTracking,
                    enabled = canTrack,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(LineRes.string.line_detail_track_this),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            if (canSwitchDirection) {
                FilledTonalIconButton(
                    onClick = onSwitchDirection,
                    enabled = !isSwitchingDirection,
                    shape = CircleShape,
                    // 语义挂在按钮上：转圈时图标没了，读屏也还能读出「换向 · 换向中」
                    modifier = Modifier
                        .size(52.dp)
                        .semantics {
                            contentDescription = switchDirectionLabel
                            if (isSwitchingDirection) stateDescription = switchingDirectionLabel
                        }
                ) {
                    if (isSwitchingDirection) {
                        // 换向要重新拉反向线路：转圈 + 禁用，挡住连点（否则会连改两次线路）
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .size(18.dp)
                                .clearAndSetSemantics { }
                        )
                    } else {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_swap),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            if (showTransitCode) {
                FilledTonalIconButton(
                    onClick = onOpenTransitCode,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_qr_code),
                        contentDescription = stringResource(Res.string.transit_code),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
