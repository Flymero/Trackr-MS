package com.trackr.ui.screens.routeplan

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import com.trackr.ui.bottombar.LocalBottomBarHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_plan_empty
import com.trackr.feature.route.resources.route_plan_loading
import com.trackr.feature.route.resources.route_plan_retry
import com.trackr.ui.screens.routeplan.dialog.SaveFavoriteDialog
import com.trackr.ui.screens.routeplan.map.NoMapBackground
import com.trackr.ui.screens.routeplan.map.RouteMap
import com.trackr.ui.screens.routeplan.panel.SchemePanel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * 路线规划页（无状态）：全屏地图（没 Android Key / 平台没地图时只留底色）+ 顶部搜索岛 + 底部方案面板。
 * 状态全在 [state]，操作全走 [onEvent]；Snackbar 由 Route 管（[snackbarHostState]）。
 */
@Composable
fun RoutePlanScreen(
    state: RoutePlanUiState,
    onEvent: (RoutePlanEvent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scope = rememberCoroutineScope()
    // 底部方案面板的实时高度：地图上的「回到定位」按钮贴着它的上沿一起上下移动
    val sheetHeight = remember { mutableStateOf(0.dp) }

    // 返回键先收起提示：先终点、再起点（和原来一样）
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.tipsField != null,
        onBackCompleted = { onEvent(RoutePlanEvent.DismissTips) }
    )

    // 方案详情的 HorizontalPager 和标签联动；选中的方案以停稳的那页为准
    val tabs by rememberUpdatedState(state.schemeTabs)
    val pagerState = rememberPagerState(initialPage = state.selectedTab) { tabs.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { onEvent(RoutePlanEvent.SelectScheme(it)) }
    }
    // 新的规划结果回到第一个方案；配置变更（同一份结果）时保持当前选择
    var handledTabs by remember { mutableStateOf(state.schemeTabs) }
    LaunchedEffect(state.schemeTabs) {
        if (state.schemeTabs !== handledTabs) {
            handledTabs = state.schemeTabs
            if (state.schemeTabs.isNotEmpty()) pagerState.scrollToPage(0)
        }
    }

    // 顶部留白和公交首页一致：整页从状态栏下方开始（地图不再顶到状态栏后面）
    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        if (state.canShowMap) {
            RouteMap(
                state = state.map,
                modifier = Modifier.fillMaxSize(),
                bottomInset = { sheetHeight.value }
            )
        } else {
            NoMapBackground()
        }

        SearchConsole(
            originText = state.originText,
            destText = state.destText,
            tipsField = state.tipsField,
            tips = state.tips,
            onEvent = onEvent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 10.dp, start = 16.dp, end = 16.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.fillMaxWidth())
            BottomSheetCard(
                state = state,
                pagerState = pagerState,
                onEvent = onEvent,
                onSelectTab = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                onHeightChanged = { height -> sheetHeight.value = height }
            )
        }
    }

    val saveScheme = state.currentScheme
    if (state.showSaveDialog && saveScheme != null) {
        SaveFavoriteDialog(
            scheme = saveScheme,
            favoriteGroups = state.favoriteGroups,
            onDismiss = { onEvent(RoutePlanEvent.DismissSaveDialog) },
            onConfirmSave = { groupId, name -> onEvent(RoutePlanEvent.ConfirmSave(groupId, name)) },
            onCreateNewGroup = { onEvent(RoutePlanEvent.CreateGroup(it)) }
        )
    }
}

/** 底部方案面板：规划中 / 出错 / 方案 / 引导 四种 */
@Composable
private fun BottomSheetCard(
    state: RoutePlanUiState,
    pagerState: PagerState,
    onEvent: (RoutePlanEvent) -> Unit,
    onSelectTab: (Int) -> Unit,
    onHeightChanged: (Dp) -> Unit = {}
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            // 在 animateContentSize 外面：拿到的是动画中的高度，「回到定位」按钮才能跟着一起滑
            .onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) }
            .animateContentSize()
            .testTag("bottom_route_sheet"),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarPadding + LocalBottomBarHeight.current + 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(36.dp, 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Spacer(modifier = Modifier.height(10.dp))

            when {
                state.isPlanning -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(RouteRes.string.route_plan_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.planError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.planError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("route_plan_error")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { onEvent(RoutePlanEvent.Retry) }) {
                            Text(stringResource(RouteRes.string.route_plan_retry))
                        }
                    }
                }

                state.schemeTabs.isNotEmpty() -> {
                    SchemePanel(
                        tabs = state.schemeTabs,
                        pagerState = pagerState,
                        trackingSession = state.trackingSession,
                        onSelectScheme = onSelectTab,
                        onLineClick = { onEvent(RoutePlanEvent.OpenLine(it)) },
                        onSaveFavorite = { onEvent(RoutePlanEvent.OpenSaveDialog) },
                        onStartTracking = { onEvent(RoutePlanEvent.StartTracking(it)) },
                        onConfirmBoarded = { onEvent(RoutePlanEvent.ConfirmBoarded) },
                        onStopTracking = { onEvent(RoutePlanEvent.StopTracking) }
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(RouteRes.string.route_plan_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
