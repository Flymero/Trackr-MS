package com.trackr.ui.screens.stopdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_boarded
import com.trackr.core.designsystem.resources.action_end
import com.trackr.core.designsystem.resources.action_favorite
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.resources.action_unfavorite
import com.trackr.core.designsystem.resources.transit_code
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalTone
import com.trackr.feature.stop.resources.Res as StopRes
import com.trackr.feature.stop.resources.stop_add_line
import com.trackr.feature.stop.resources.stop_boarded_kicker
import com.trackr.feature.stop.resources.stop_exit_select
import com.trackr.feature.stop.resources.stop_first_arrives
import com.trackr.feature.stop.resources.stop_loading
import com.trackr.feature.stop.resources.stop_no_lines
import com.trackr.feature.stop.resources.stop_pick_lines
import com.trackr.feature.stop.resources.stop_select_hint
import com.trackr.feature.stop.resources.stop_selected_count
import com.trackr.feature.stop.resources.stop_watch_count
import com.trackr.feature.stop.resources.stop_watch_hint
import com.trackr.feature.stop.resources.stop_watch_these
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import com.trackr.ui.icons.TrackrIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 站点详情（第 21 步，v3）：无状态，只读 [state]、只发 [StopDetailEvent]。三种样子：
 * 普通（线路列表 +「选几条一起等」）、选线（勾线 +「收藏 / 一起等这 N 条」）、本站一起等进行中。
 *
 * 一起等进行中**不替换线路列表**：只是在上方多一张「最先到」的小卡片（第 36 步）。
 * 之前整页换成追踪视图，同时追好几条线时这个站的其他线路就都点不进去了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopDetailScreen(
    state: StopDetailUiState,
    onEvent: (StopDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** 一次性提示（`StopDetailRoute` 传 `UserMessageHost`） */
    messageHost: @Composable () -> Unit = {}
) {
    val watch = state.watch.takeIf { !state.selecting }

    // 选线模式里系统返回 = 退出选线
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.selecting,
        onBackCompleted = { onEvent(StopDetailEvent.CancelSelecting) }
    )

    Scaffold(
        modifier = modifier,
        topBar = { StopDetailTopBar(state = state, watch = watch, onEvent = onEvent) },
        bottomBar = { StopDetailBottomBar(state = state, watch = watch, onEvent = onEvent) },
        snackbarHost = messageHost
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 16.dp
        )
        when {
            state.isLoading && state.stops.isEmpty() -> CenterMessage(contentPadding) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(StopRes.string.stop_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.errorMessage != null && state.stops.isEmpty() -> CenterMessage(contentPadding) {
                Text(state.errorMessage, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onEvent(StopDetailEvent.Retry) }) { Text(stringResource(Res.string.action_retry)) }
            }

            else -> PullToRefreshBox(
                isRefreshing = state.isPullRefreshing,
                onRefresh = { onEvent(StopDetailEvent.PullRefresh) },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 一起等只多一张「最先到」的小卡片，下面的线路列表照常显示、照常能点进线路页
                    if (watch != null) watchHeader(watch, onEvent)
                    lineItems(state, onEvent)
                }
            }
        }
    }

    // 「上了哪辆？」是导航弹层（ScreenKey.ChooseBoardedBus），由 StopDetailRoute 打开，不在这里画
    if (state.isTransitCodeSheetOpen) {
        TransitCodeChooserSheet(
            onDismiss = { onEvent(StopDetailEvent.DismissTransitCodeSheet) },
            onSelect = { provider, save -> onEvent(StopDetailEvent.PickTransitCode(provider, save)) }
        )
    }
}

// ------------------------------------------------------------------ 顶栏 / 底栏

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopDetailTopBar(state: StopDetailUiState, watch: WatchTogetherUi?, onEvent: (StopDetailEvent) -> Unit) {
    val (title, subtitle) = when {
        state.selecting -> stringResource(StopRes.string.stop_selected_count, state.selectedLineKeys.size) to
            listOf(state.stopName, state.selectedPlatformLabel).filter { it.isNotBlank() }.joinToString(" · ")
        watch != null -> state.stopName to stringResource(StopRes.string.stop_watch_count, watch.lineCount)
        else -> state.stopName to state.subtitle
    }
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (state.selecting) {
                IconButton(onClick = { onEvent(StopDetailEvent.CancelSelecting) }) {
                    Icon(TrackrIcons.Close, contentDescription = stringResource(StopRes.string.stop_exit_select))
                }
            } else {
                IconButton(onClick = { onEvent(StopDetailEvent.Back) }) {
                    Icon(TrackrIcons.Back, contentDescription = stringResource(Res.string.action_back))
                }
            }
        }
    )
}

@Composable
private fun StopDetailBottomBar(state: StopDetailUiState, watch: WatchTogetherUi?, onEvent: (StopDetailEvent) -> Unit) {
    // 没有站点数据时不放按钮（选线、一起等都没得选）
    if (state.stops.isEmpty() && watch == null) return
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                state.selecting -> {
                    val count = state.selectedLineKeys.size
                    OutlinedButton(
                        onClick = { onEvent(StopDetailEvent.SaveSelectionAsRoute) },
                        enabled = count > 0,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(TrackrIcons.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.action_favorite))
                    }
                    Button(
                        onClick = { onEvent(StopDetailEvent.StartWatching) },
                        enabled = count > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(TrackrIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(StopRes.string.stop_watch_these, count))
                    }
                }

                watch != null -> {
                    if (!watch.isOnBoard) {
                        Button(
                            onClick = { onEvent(StopDetailEvent.ConfirmBoarded) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(TrackrIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.action_boarded))
                        }
                    }
                    TextButton(
                        onClick = { onEvent(StopDetailEvent.StopWatching) },
                        modifier = Modifier
                            .then(if (watch.isOnBoard) Modifier.weight(1f) else Modifier)
                            .heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(Res.string.action_end), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.showTransitCodeButton) TransitCodeButton(onEvent)
                }

                else -> {
                    OutlinedButton(
                        onClick = { onEvent(StopDetailEvent.StartSelecting) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Icon(TrackrIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(StopRes.string.stop_pick_lines))
                    }
                    if (state.showTransitCodeButton) TransitCodeButton(onEvent)
                }
            }
        }
    }
}

/** 乘车码：一点就开（默认平台直接打开，「每次询问」才弹选择），不藏、不用长按 */
@Composable
private fun TransitCodeButton(onEvent: (StopDetailEvent) -> Unit) {
    FilledTonalIconButton(
        onClick = { onEvent(StopDetailEvent.OpenTransitCode) },
        modifier = Modifier.size(48.dp)
    ) {
        Icon(TrackrIcons.Qr, contentDescription = stringResource(Res.string.transit_code))
    }
}

// ------------------------------------------------------------------ 普通 / 选线：线路列表

private fun LazyListScope.lineItems(state: StopDetailUiState, onEvent: (StopDetailEvent) -> Unit) {
    if (state.stops.size > 1) {
        item(key = "platforms", contentType = "platforms") {
            PlatformChips(state.platformLabels, state.selectedStopIndex, onEvent)
        }
    }
    if (state.rows.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            Text(
                stringResource(StopRes.string.stop_no_lines),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
        }
    } else {
        items(items = state.rows, key = { it.key }, contentType = { "line_row" }) { row ->
            StopLineRow(
                row = row,
                selecting = state.selecting,
                checked = row.key in state.selectedLineKeys,
                onEvent = onEvent
            )
        }
    }
    if (state.selecting) {
        item(key = "select_hint", contentType = "hint") {
            Hint(stringResource(StopRes.string.stop_select_hint))
        }
    } else {
        state.transferText?.let { text ->
            item(key = "transfer", contentType = "hint") { Hint(text) }
        }
    }
}

@Composable
private fun PlatformChips(labels: List<String>, selected: Int, onEvent: (StopDetailEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onEvent(StopDetailEvent.SelectPlatform(index)) },
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopLineRow(
    row: StopLineRowUi,
    selecting: Boolean,
    checked: Boolean,
    onEvent: (StopDetailEvent) -> Unit
) {
    // 能点开线路页只看 lineId（掌上公交的站点行不带站序）；勾进「一起等」仍然要有站序
    val onClick: (() -> Unit)? = when {
        selecting -> if (row.selectable) ({ onEvent(StopDetailEvent.ToggleLine(row.key)) }) else null
        row.lineId.isNotBlank() -> ({ onEvent(StopDetailEvent.OpenLine(row)) })
        else -> null
    }
    // 长按一条线 = 快捷进「选几条一起等」，并把这条勾上
    val onLongClick: (() -> Unit)? = if (selecting) {
        null
    } else {
        ({ onEvent(StopDetailEvent.StartSelectingWith(row.key)) })
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClickLabel = stringResource(StopRes.string.stop_pick_lines),
                            onLongClick = onLongClick
                        )
                    } else {
                        Modifier
                    }
                )
                .heightIn(min = 64.dp)
                .padding(start = 12.dp, end = if (selecting) 14.dp else 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = if (row.selectable) ({ onEvent(StopDetailEvent.ToggleLine(row.key)) }) else null,
                    enabled = row.selectable
                )
                Spacer(Modifier.width(4.dp))
            }
            /* 上下两行：第一行线路号 + 终点，第二行运营时间 + 到站。
               线路名可能很长（如「盘龙大观园河西旅游专线」），到站也可能是整句状态文字（掌上公交），
               挤在一行里会把中间压成 0 宽、时间竖着排。第一行只放名字和线路，
               名字过长时在自己的格子里省略，不再把终点挤走；文字都限一行。 */
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LineBadge(row.lineName, modifier = Modifier.widthIn(max = 200.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (row.endStop.isNotBlank()) "→ ${row.endStop}" else row.lineName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (row.hours.isNotBlank() && !selecting) {
                        Text(
                            text = row.hours,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(8.dp))
                    ArrivalColumn(row.arrival, modifier = Modifier.widthIn(max = 176.dp))
                }
            }
            if (!selecting) {
                IconButton(
                    onClick = { onEvent(StopDetailEvent.ToggleFavorite(row.key)) },
                    enabled = row.lineId.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (row.isFavorite) TrackrIcons.StarFilled else TrackrIcons.Star,
                        contentDescription = if (row.isFavorite) stringResource(Res.string.action_unfavorite) else stringResource(Res.string.action_favorite),
                        tint = if (row.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 一起等进行中

private fun LazyListScope.watchHeader(watch: WatchTogetherUi, onEvent: (StopDetailEvent) -> Unit) {
    if (watch.isOnBoard) {
        item(key = "watch_boarded", contentType = "watch_hero") {
            HeroCard(
                kicker = stringResource(StopRes.string.stop_boarded_kicker),
                big = watch.boardedTitle,
                unit = null,
                detail = watch.boardedDetail
            )
        }
        return
    }
    val first = watch.lines.firstOrNull()
    if (first != null) {
        item(key = "watch_hero", contentType = "watch_hero") {
            HeroCard(
                kicker = stringResource(StopRes.string.stop_first_arrives, first.lineName),
                big = first.arrival.value,
                unit = first.arrival.unit,
                detail = first.detail.ifBlank { first.arrival.detail }
            )
        }
    }
    item(key = "watch_add", contentType = "watch_add") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(StopDetailEvent.AddLine) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TrackrIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(StopRes.string.stop_add_line),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun HeroCard(kicker: String, big: String, unit: String?, detail: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(kicker, style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(big, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold))
                if (unit != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun LineBadge(name: String, modifier: Modifier = Modifier) {
    if (name.isBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** 右侧到站大字：数字 + 小单位，下面一行小字；颜色按 [ArrivalTone]。宽度由调用方限制，超了省略 */
@Composable
private fun ArrivalColumn(arrival: ArrivalDisplay, modifier: Modifier = Modifier) {
    val color = toneColor(arrival.tone)
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            val numeric = arrival.unit != null
            Text(
                text = arrival.value,
                color = color,
                style = if (numeric) {
                    MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val unit = arrival.unit
            if (unit != null) {
                Spacer(Modifier.width(2.dp))
                Text(unit, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (arrival.detail.isNotBlank()) {
            Text(
                arrival.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (arrival.detailIsWarning) TrackrTheme.colors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun toneColor(tone: ArrivalTone): Color = when (tone) {
    ArrivalTone.Go -> TrackrTheme.colors.go
    ArrivalTone.Warn -> TrackrTheme.colors.warn
    ArrivalTone.Mute -> MaterialTheme.colorScheme.onSurfaceVariant
    ArrivalTone.Normal -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun CenterMessage(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
