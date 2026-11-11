package com.trackr.feature.bus.elder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_relocate
import com.trackr.core.designsystem.resources.ic_arrow_drop_down
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_person
import com.trackr.core.designsystem.resources.ic_search
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.feature.bus.citypicker.CityPickerBottomSheet
import com.trackr.feature.bus.home.BusHomeEvent
import com.trackr.feature.bus.home.BusHomeMapper
import com.trackr.feature.bus.home.BusHomeUiState
import com.trackr.feature.bus.home.FavoriteRowUi
import com.trackr.feature.bus.home.StationStatus
import com.trackr.feature.bus.home.LocationIssue
import com.trackr.feature.bus.home.NearbyLineUi
import com.trackr.feature.bus.home.NearbyStopUi
import com.trackr.feature.bus.home.TrackingCardUi
import com.trackr.feature.bus.home.actionEvent
import com.trackr.feature.bus.home.actionRes
import com.trackr.feature.bus.home.messageRes
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_elder_favorites_empty
import com.trackr.feature.bus.resources.bus_elder_favorites_title
import com.trackr.feature.bus.resources.bus_elder_keep_tracking
import com.trackr.feature.bus.resources.bus_elder_nearby_title
import com.trackr.feature.bus.resources.bus_elder_open_stop
import com.trackr.feature.bus.resources.bus_elder_read_aloud
import com.trackr.feature.bus.resources.bus_station_loading
import com.trackr.feature.bus.resources.bus_station_not_found
import com.trackr.feature.bus.resources.bus_elder_search_hint
import com.trackr.feature.bus.resources.bus_elder_stop_tracking
import com.trackr.feature.bus.resources.bus_elder_stop_tracking_confirm
import com.trackr.feature.bus.resources.bus_home_settings
import com.trackr.feature.bus.resources.bus_home_switch_city
import com.trackr.feature.bus.resources.bus_nearby_empty
import com.trackr.feature.bus.resources.bus_nearby_loading
import com.trackr.ui.components.arrival.ArrivalUi
import com.trackr.ui.components.arrival.arrivalToneColor
import com.trackr.ui.components.elder.ElderArrival
import com.trackr.ui.components.elder.ElderButton
import com.trackr.ui.components.elder.ElderButtonHeight
import com.trackr.ui.components.elder.ElderConfirmDialog
import com.trackr.ui.components.elder.ElderLineBadge
import com.trackr.ui.components.elder.ShowTransitCodeButton
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 测试用的 tag */
internal object ElderBusHomeTags {
    const val LIST = "elder_home_list"
    const val CITY = "elder_home_city"
    const val SETTINGS = "elder_home_settings"
    const val SEARCH = "elder_home_search"
    const val TRACKING = "elder_home_tracking"
    const val STOP_TRACKING = "elder_home_stop_tracking"
    const val TRACKING_READ_ALOUD = "elder_home_tracking_read_aloud"
    fun readAloud(key: String) = "elder_home_read_$key"
    fun favorite(key: String) = "elder_home_fav_$key"
    fun nearbyStop(key: String) = "elder_home_nearby_$key"
    fun nearbyLine(key: String) = "elder_home_line_$key"
}

/** 长辈版首页列附近几个站（§8.5：附近 3 个站） */
internal const val ELDER_NEARBY_STOPS = 3

/**
 * 长辈版首页（设计稿 redesign_v3_elder.html「首页」，§8.5）。和标准版共用 [BusHomeUiState] / `BusHomeViewModel`。
 *
 * 顶栏城市名 +「设置」；大搜索框；正在提醒的车（只有最先到的一辆，没有一起等的多线细节）；
 * 「我常坐的车」一张卡一辆车（全部收藏，没有分组 chips、没有「编辑」）；车卡和提醒卡上有「读给我听」（第 33 步）；「离你最近」3 个站；
 * 底部固定「出示乘车码」。没有下拉刷新（靠首页 15 秒自动刷新）、没有长按和滑动手势；提示要点「知道了」才关。
 */
@Composable
fun ElderBusHomeScreen(
    state: BusHomeUiState,
    onEvent: (BusHomeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // 长辈版没有分组 chips：总是列全部收藏。标准版选过分组时（ViewModel 是同一个）按「全部」重算，不改标准版的选择
    val favorites = remember(state.favoriteRows, state.selectedGroupId, state.favoriteItems, state.favoritesRealtime) {
        if (state.selectedGroupId == null) state.favoriteRows else BusHomeMapper.favoriteRows(state.copy(selectedGroupId = null))
    }
    // 要结束哪一张追踪卡（长辈版多一步确认）；存 key，转屏重建后还在
    var confirmStopKey by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ElderHomeTopBar(
                cityName = state.currentCity.cityName,
                onCityClick = { onEvent(BusHomeEvent.OpenCityPicker) },
                onSettingsClick = { onEvent(BusHomeEvent.OpenSettings) }
            )
        },
        bottomBar = { ShowTransitCodeButton(onClick = { onEvent(BusHomeEvent.TransitCodeClicked) }) }
    ) { innerPadding ->
        val dimens = TrackrTheme.dimens
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ElderBusHomeTags.LIST),
            contentPadding = PaddingValues(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + dimens.spaceL
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
        ) {
            item(key = "search", contentType = "search") {
                ElderSearchEntry(onClick = { onEvent(BusHomeEvent.OpenSearch) })
            }

            // 同时追多项时一项一张卡，各自点进自己的线路页
            state.trackingCards.forEach { card ->
                item(key = card.key, contentType = "tracking") {
                    ElderTrackingCard(
                        card = card,
                        onOpen = { onEvent(BusHomeEvent.OpenLine(card.open)) },
                        onReadAloud = { onEvent(BusHomeEvent.ReadAloud(card.spoken)) },
                        onStop = { confirmStopKey = card.key }
                    )
                }
            }

            state.locationIssue?.let { issue ->
                item(key = "location_hint", contentType = "location_hint") {
                    ElderLocationHint(issue = issue, onEvent = onEvent)
                }
            }

            elderFavorites(rows = favorites, onEvent = onEvent)

            elderNearby(state = state, onEvent = onEvent)
        }
    }

    val confirmStop = confirmStopKey?.let { key -> state.trackingCards.firstOrNull { it.key == key } }
    confirmStop?.let { card ->
        ElderConfirmDialog(
            text = stringResource(BusRes.string.bus_elder_stop_tracking_confirm),
            confirmText = stringResource(BusRes.string.bus_elder_stop_tracking),
            dismissText = stringResource(BusRes.string.bus_elder_keep_tracking),
            onConfirm = {
                confirmStopKey = null
                onEvent(BusHomeEvent.StopTracking(card.sessionId, card.watchLineId))
            },
            onDismiss = { confirmStopKey = null }
        )
    }

    // 一次性消息：长辈版不用会自己消失的提示条，点「知道了」才算显示过（UserMessageHost 按长辈主题选常驻提示）
    UserMessageHost(
        message = state.userMessages.firstOrNull()?.let { HostMessage(key = it.id, text = it.elderText ?: it.text) },
        onResult = { key, _ -> onEvent(BusHomeEvent.MessageShown(key as Long)) }
    )

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

/** 顶栏：左边城市名（点了换城市），右边「设置」进「我的」 */
@Composable
private fun ElderHomeTopBar(cityName: String, onCityClick: () -> Unit, onSettingsClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = dimens.spaceS, vertical = dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCityClick,
            modifier = Modifier
                .heightIn(min = ElderButtonHeight)
                .widthIn(min = ElderButtonHeight)
                .testTag(ElderBusHomeTags.CITY)
        ) {
            Text(
                text = cityName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(BusRes.string.bus_home_switch_city),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .heightIn(min = ElderButtonHeight)
                .widthIn(min = ElderButtonHeight)
                .testTag(ElderBusHomeTags.SETTINGS)
        ) {
            Icon(imageVector = vectorResource(Res.drawable.ic_person), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(dimens.spaceXs))
            Text(text = stringResource(BusRes.string.bus_home_settings), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ElderSearchEntry(onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ElderButtonHeight)
            .testTag(ElderBusHomeTags.SEARCH)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = dimens.spaceL, vertical = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(dimens.spaceM))
            Text(
                text = stringResource(BusRes.string.bus_elder_search_hint),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 正在提醒的车：标题、最先到的大字、一句说明、「读给我听」（在车上时不给）+「不用叫了」。点卡片去线路页 */
@Composable
private fun ElderTrackingCard(card: TrackingCardUi, onOpen: () -> Unit, onReadAloud: () -> Unit, onStop: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = TrackrTheme.colors.goContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ElderBusHomeTags.TRACKING)
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            Text(text = card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ElderArrival(arrival = card.arrival, detail = card.detail)
            if (card.spoken.isNotBlank()) {
                ElderButton(
                    text = stringResource(BusRes.string.bus_elder_read_aloud),
                    onClick = onReadAloud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ElderBusHomeTags.TRACKING_READ_ALOUD)
                )
            }
            ElderButton(
                text = stringResource(BusRes.string.bus_elder_stop_tracking),
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ElderBusHomeTags.STOP_TRACKING)
            )
        }
    }
}

/** 定位提示（长辈版）：一句原因 + 一个大按钮，文案和去处跟标准版同一套（[LocationIssue]） */
@Composable
private fun ElderLocationHint(issue: LocationIssue, onEvent: (BusHomeEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
        Text(
            text = stringResource(issue.messageRes),
            style = MaterialTheme.typography.bodyLarge,
            color = TrackrTheme.colors.warn
        )
        ElderButton(
            text = stringResource(issue.actionRes),
            onClick = { onEvent(issue.actionEvent) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ElderSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = TrackrTheme.dimens.spaceS)
    )
}

/** 「我常坐的车」：一张卡一辆车（路线也一张卡）。没收藏时一句话教怎么收藏 */
private fun LazyListScope.elderFavorites(rows: List<FavoriteRowUi>, onEvent: (BusHomeEvent) -> Unit) {
    item(key = "fav_title", contentType = "section_title") {
        ElderSectionTitle(stringResource(BusRes.string.bus_elder_favorites_title))
    }
    if (rows.isEmpty()) {
        item(key = "fav_empty", contentType = "empty_hint") {
            Text(
                text = stringResource(BusRes.string.bus_elder_favorites_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    items(
        items = rows,
        key = { it.key },
        contentType = {
            when (it) {
                is FavoriteRowUi.Stop -> "fav_stop"
                is FavoriteRowUi.Route -> "fav_route"
                is FavoriteRowUi.Station -> "fav_station"
            }
        }
    ) { row ->
        when (row) {
            is FavoriteRowUi.Stop -> ElderFavoriteStopCard(
                row,
                onClick = { onEvent(BusHomeEvent.OpenLine(row.open)) },
                onReadAloud = { onEvent(BusHomeEvent.ReadAloud(row.spoken)) }
            )
            is FavoriteRowUi.Route -> ElderFavoriteRouteCard(row, onClick = { onEvent(BusHomeEvent.OpenRoute(row.routeId)) })
            is FavoriteRowUi.Station -> ElderFavoriteStationCard(
                row,
                onClick = { onEvent(BusHomeEvent.OpenStop(row.open)) },
                onReadAloud = { onEvent(BusHomeEvent.ReadAloud(row.spoken)) }
            )
        }
    }
}

/** 一辆车：「325」「科技园站上车」「→ 科技园」+ 大字到站 +「读给我听」 */
@Composable
private fun ElderFavoriteStopCard(row: FavoriteRowUi.Stop, onClick: () -> Unit, onReadAloud: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ElderBusHomeTags.favorite(row.key))
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElderLineBadge(name = row.lineName)
                Spacer(modifier = Modifier.width(dimens.spaceM))
                Text(text = row.boardText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(text = row.direction, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ElderArrival(arrival = row.arrival)
            ElderButton(
                text = stringResource(BusRes.string.bus_elder_read_aloud),
                onClick = onReadAloud,
                enabled = row.spoken.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ElderBusHomeTags.readAloud(row.key))
            )
        }
    }
}

/**
 * 收藏的站（issue #47）：「科技园站 · 往北」，下面最先到的几条线各一行（线路号、「→ 终点」、大字到站）+「读给我听」。
 * 整张卡点开站点页
 */
@Composable
private fun ElderFavoriteStationCard(row: FavoriteRowUi.Station, onClick: () -> Unit, onReadAloud: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ElderBusHomeTags.favorite(row.key))
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(imageVector = vectorResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(32.dp))
            }
            when (row.status) {
                StationStatus.Loading -> Text(
                    text = stringResource(BusRes.string.bus_station_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StationStatus.NotFound -> Text(
                    text = stringResource(BusRes.string.bus_station_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StationStatus.Ready -> row.lines.forEach { line ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ElderLineBadge(name = line.lineName)
                            Spacer(modifier = Modifier.width(dimens.spaceM))
                            Text(text = line.direction, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                        ElderArrivalLine(arrival = line.arrival)
                    }
                }
            }
            if (row.status == StationStatus.Ready) {
                ElderButton(
                    text = stringResource(BusRes.string.bus_elder_read_aloud),
                    onClick = onReadAloud,
                    enabled = row.spoken.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ElderBusHomeTags.readAloud(row.key))
                )
            }
        }
    }
}

/** 收藏的路线：「家 → 公司」「325 › 19 · 换乘 1 次」，点了看路线详情（没有地图规划，Q10） */
@Composable
private fun ElderFavoriteRouteCard(row: FavoriteRowUi.Route, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ElderButtonHeight)
            .testTag(ElderBusHomeTags.favorite(row.key))
    ) {
        Row(modifier = Modifier.padding(dimens.cardPadding), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (row.linesText.isNotBlank()) {
                    Text(text = row.linesText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(imageVector = vectorResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}

/** 「离你最近」：最近 3 个站，每站一张卡：站名一行（点了看这个站），下面每条线一行（点了看这条线） */
private fun LazyListScope.elderNearby(state: BusHomeUiState, onEvent: (BusHomeEvent) -> Unit) {
    item(key = "nearby_title", contentType = "section_title") {
        ElderSectionTitle(stringResource(BusRes.string.bus_elder_nearby_title))
    }
    val stops = state.nearbyCards.take(ELDER_NEARBY_STOPS)
    when {
        stops.isEmpty() && state.isBusy -> item(key = "nearby_loading", contentType = "loading") {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 4.dp)
            }
        }
        stops.isEmpty() -> item(key = "nearby_empty", contentType = "empty_hint") {
            Column(verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
                Text(
                    text = stringResource(BusRes.string.bus_nearby_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ElderButton(
                    text = stringResource(Res.string.action_relocate),
                    onClick = { onEvent(BusHomeEvent.RetryLocation) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        else -> items(items = stops, key = { it.key }, contentType = { "nearby_stop" }) { stop ->
            ElderNearbyStopCard(
                stop = stop,
                onOpenStop = { onEvent(BusHomeEvent.OpenStop(stop.open)) },
                onOpenLine = { onEvent(BusHomeEvent.OpenLine(it.open)) }
            )
        }
    }
}

@Composable
private fun ElderNearbyStopCard(stop: NearbyStopUi, onOpenStop: () -> Unit, onOpenLine: (NearbyLineUi) -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ElderBusHomeTags.nearbyStop(stop.key))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ElderButtonHeight)
                    .clickable(onClick = onOpenStop)
                    .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceS),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stop.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (stop.meta.isNotBlank()) {
                        Text(text = stop.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_chevron_right),
                    contentDescription = stringResource(BusRes.string.bus_elder_open_stop),
                    modifier = Modifier.size(32.dp)
                )
            }
            if (stop.lines.isEmpty()) {
                Text(
                    text = stringResource(BusRes.string.bus_nearby_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = dimens.cardPadding, end = dimens.cardPadding, bottom = dimens.spaceM)
                )
            } else {
                stop.lines.forEach { line ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ElderNearbyLineRow(line = line, onClick = { onOpenLine(line) })
                }
            }
        }
    }
}

/**
 * 「325  → 科技园」下面一行「4 分钟 · 2 站」。到站字不放右边：字放大到 2 倍时三样挤一行会把方向挤成一字一行
 */
@Composable
private fun ElderNearbyLineRow(line: NearbyLineUi, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ElderButtonHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceS)
            .testTag(ElderBusHomeTags.nearbyLine(line.key)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElderLineBadge(name = line.lineName)
                Spacer(modifier = Modifier.width(dimens.spaceM))
                Text(text = line.direction, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            ElderArrivalLine(arrival = line.arrival)
        }
    }
}

/** 附近站里一条线的到站：大字（按颜色）+ 小字，一行放不下就换行 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ElderArrivalLine(arrival: ArrivalUi) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS),
        verticalArrangement = Arrangement.Bottom,
        itemVerticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = arrival.big,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = arrivalToneColor(arrival.tone)
        )
        if (arrival.small.isNotBlank()) {
            Text(
                text = arrival.small,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
