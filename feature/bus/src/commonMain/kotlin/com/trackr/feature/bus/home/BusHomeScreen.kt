package com.trackr.feature.bus.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.trackr.ui.bottombar.LocalBottomBarHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_arrow_drop_down
import com.trackr.core.designsystem.resources.ic_my_location
import com.trackr.core.designsystem.resources.ic_search
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.feature.bus.citypicker.CityPickerBottomSheet
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_home_search_hint
import com.trackr.feature.bus.resources.bus_home_switch_city
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import com.trackr.ui.components.transitcode.TransitCodeFab
import com.trackr.ui.components.transitcode.rememberTransitCodeFabState
import com.trackr.ui.bottombar.LocalBottomBarCollapseOffset
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 测试用的 tag */
internal object BusHomeTags {
    const val LIST = "bus_home_list"
    const val CITY_TITLE = "bus_home_city"
    const val SEARCH = "bus_home_search"
    const val TRACKING_CARD = "bus_home_tracking"
    const val STOP_TRACKING = "bus_home_stop_tracking"
    const val EDIT_FAVORITES = "bus_home_edit_favorites"
    const val NEARBY_HEADER = "bus_home_nearby_header"
    fun favoriteRow(key: String) = "bus_home_fav_$key"
    fun groupChip(id: String?) = "bus_home_group_${id ?: "all"}"

    /** 分组 chip 前的色点（分组设了颜色才有） */
    fun groupChipDot(id: String) = "bus_home_group_dot_$id"

    /** 卡片左边的分组色条，[cardTag] 是那张卡的 testTag */
    fun accentStripe(cardTag: String) = "${cardTag}_accent"
    fun nearbyStop(key: String) = "bus_home_nearby_$key"
}

/**
 * v3 公交首页（无状态）：顶栏城市名、搜索、追踪卡、收藏（车和路线一个列表 + 分组 chips）、附近 6 个站，
 * 右下角「乘车码」悬浮按钮（往下滑缩小、往上滑展开）。下拉刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusHomeScreen(
    state: BusHomeUiState,
    onEvent: (BusHomeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val fabState = rememberTransitCodeFabState()
    // 底栏（页签）收起时，「乘车码」跟着一起往下走；底栏是根布局画的，这里只读它滑下去的距离
    val bottomBarCollapseOffset = LocalBottomBarCollapseOffset.current
    // 标题栏和「我的」页一样，用 Material3 原生的滚动折叠：往下滑收成一条，往上滑展开
    val topBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier
            .nestedScroll(fabState.nestedScrollConnection)
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            BusHomeTopBar(
                cityName = state.currentCity.cityName,
                onCityClick = { onEvent(BusHomeEvent.OpenCityPicker) },
                scrollBehavior = topBarScrollBehavior
            )
        },
        floatingActionButton = {
            // 「我的」里关掉了就不显示（用 NFC 刷卡的人嫌它挡住列表最后几条）
            if (state.showTransitCodeButton) {
                TransitCodeFab(
                    expanded = fabState.expanded,
                    onClick = { onEvent(BusHomeEvent.TransitCodeClicked) },
                    // 底栏（页签）盖在页面上面，按钮要让开它；底栏收起时再把它挪到系统导航栏上方
                    modifier = Modifier
                        .padding(bottom = LocalBottomBarHeight.current)
                        .offset { IntOffset(0, bottomBarCollapseOffset().roundToInt()) }
                )
            }
        },
        // 一次性消息：一条一条显示，显示完回报
        snackbarHost = {
            UserMessageHost(
                message = state.userMessages.firstOrNull()?.let { HostMessage(key = it.id, text = it.text) },
                onResult = { key, _ -> onEvent(BusHomeEvent.MessageShown(key as Long)) },
                modifier = Modifier.padding(bottom = LocalBottomBarHeight.current)
            )
        }
    ) { innerPadding ->
        val pullState = rememberPullToRefreshState()
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(BusHomeEvent.Refresh) },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding())
                )
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(BusHomeTags.LIST),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    // 留出底栏和悬浮按钮的高度，最后一个站不被挡住（按钮关掉了就不用留）
                    bottom = innerPadding.calculateBottomPadding() + LocalBottomBarHeight.current +
                        if (state.showTransitCodeButton) FAB_CLEARANCE else 0.dp
                ),
                verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)
            ) {
                // 同时追多项时一项一张卡（一起等的每条线也各一张），各自点进自己的线路页
                state.trackingCards.forEach { card ->
                    item(key = card.key, contentType = "tracking") {
                        TrackingCard(
                            card = card,
                            onOpen = { onEvent(BusHomeEvent.OpenLine(card.open)) },
                            onStop = { onEvent(BusHomeEvent.StopTracking(card.sessionId, card.watchLineId)) }
                        )
                    }
                }

                item(key = "search", contentType = "search") {
                    SearchEntry(onClick = { onEvent(BusHomeEvent.OpenSearch) })
                }

                state.locationIssue?.let { issue ->
                    item(key = "location_hint", contentType = "location_hint") {
                        LocationHint(issue = issue, onEvent = onEvent)
                    }
                }

                favoritesSection(state = state, onEvent = onEvent)

                nearbySection(state = state, onEvent = onEvent)
            }
        }
    }

    if (state.isCityPickerOpen) {
        CityPickerBottomSheet(
            currentCity = state.currentCity,
            locatedCity = state.locatedCity,
            selectedProvince = state.selectedProvince,
            cities = state.cities,
            searchQuery = state.citySearchQuery,
            onSearchQueryChange = { onEvent(BusHomeEvent.CitySearchChanged(it)) },
            onSelectProvince = { onEvent(BusHomeEvent.ProvinceSelected(it)) },
            onSelectCity = { onEvent(BusHomeEvent.CitySelected(it)) },
            onRelocate = { onEvent(BusHomeEvent.RelocateCity) },
            onDismiss = { onEvent(BusHomeEvent.CloseCityPicker) }
        )
    }

    if (state.isTransitCodeSheetOpen) {
        TransitCodeChooserSheet(
            onDismiss = { onEvent(BusHomeEvent.TransitCodeSheetDismissed) },
            onSelect = { provider, save -> onEvent(BusHomeEvent.TransitCodeChosen(provider, save)) }
        )
    }
}

/** 顶栏：大标题是城市名（v3），点它换城市；和「我的」页一样滚动收起成一条 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusHomeTopBar(
    cityName: String,
    onCityClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior
) {
    LargeTopAppBar(
        title = {
            Surface(
                onClick = onCityClick,
                shape = CircleShape,
                // 透明底：展开 / 收起时都让标题栏自己的底色透出来，只保留点击涟漪
                color = Color.Transparent,
                modifier = Modifier.testTag(BusHomeTags.CITY_TITLE)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(text = cityName, fontWeight = FontWeight.Bold)
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = stringResource(BusRes.string.bus_home_switch_city)
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding)
            .testTag(BusHomeTags.SEARCH)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = dimens.spaceL, vertical = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(dimens.spaceS))
            Text(
                text = stringResource(BusRes.string.bus_home_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 定位拿不到时的常驻提示（是状态，不是一次性消息）：文案和按钮跟着原因变，见 [LocationIssue] */
@Composable
private fun LocationHint(issue: LocationIssue, onEvent: (BusHomeEvent) -> Unit) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_my_location),
            contentDescription = null,
            tint = TrackrTheme.colors.warn,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(dimens.spaceS))
        Text(
            text = stringResource(issue.messageRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onEvent(issue.actionEvent) }) { Text(stringResource(issue.actionRes)) }
    }
}

/** 区块标题（「收藏」「附近」），右边可以放一个文字按钮 */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = TrackrTheme.dimens.screenPadding + 4.dp, end = TrackrTheme.dimens.spaceXs, top = TrackrTheme.dimens.spaceM),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** 悬浮按钮的高度 + 间距 */
private val FAB_CLEARANCE = 72.dp
