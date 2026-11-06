package com.trackr.feature.stop.elder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.feature.stop.resources.Res as StopRes
import com.trackr.feature.stop.resources.stop_elder_pick_platform
import com.trackr.feature.stop.resources.stop_loading
import com.trackr.feature.stop.resources.stop_no_lines
import com.trackr.ui.components.arrival.ArrivalUi
import com.trackr.ui.components.elder.ElderArrival
import com.trackr.ui.components.elder.ElderButton
import com.trackr.ui.components.elder.ElderButtonHeight
import com.trackr.ui.components.elder.ElderLineBadge
import com.trackr.ui.components.elder.ElderTopBar
import com.trackr.ui.components.elder.ShowTransitCodeButton
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import com.trackr.ui.screens.stopdetail.StopDetailEvent
import com.trackr.ui.screens.stopdetail.StopDetailUiState
import com.trackr.ui.screens.stopdetail.StopLineRowUi
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 测试用的 tag */
internal object ElderStopTags {
    const val LIST = "elder_stop_list"
    fun line(key: String) = "elder_stop_line_$key"
    fun platform(index: Int) = "elder_stop_platform_$index"
}

/**
 * 长辈版站点页（§8.5，第 34 步）。和标准版共用 `StopDetailViewModel` / [StopDetailUiState] 和 `StopDetailRoute` 的事件处理。
 *
 * 顶栏「← 返回」+ 站名；同名几个站台时一行一个大按钮选方向；每条线一张卡（线路号、「→ 终点」、大字到站、运营时间），
 * 点卡片去长辈线路页（在那里「车快到时叫我」「收藏」）；底部固定「出示乘车码」。
 * 没有选线、一起等（长辈版不做一起等）、收藏星标、下拉刷新（站点页在前台时自己轮询）；出错时一句话 +「重试」。
 *
 * @param messageHost 一次性提示（`UserMessageHost`，长辈版下点「知道了」才关）
 */
@Composable
fun ElderStopDetailScreen(
    state: StopDetailUiState,
    onEvent: (StopDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    messageHost: @Composable () -> Unit = {}
) {
    val dimens = TrackrTheme.dimens
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ElderTopBar(title = state.stopName, onBack = { onEvent(StopDetailEvent.Back) }) },
        bottomBar = {
            // 「我的」里关掉「显示乘车码按钮」后，这里和标准版站点页一起不画
            if (state.showTransitCodeButton) {
                ShowTransitCodeButton(onClick = { onEvent(StopDetailEvent.OpenTransitCode) })
            }
        }
    ) { innerPadding ->
        when {
            state.isLoading && state.stops.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(dimens.screenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(strokeWidth = 4.dp)
                Spacer(modifier = Modifier.heightIn(min = dimens.spaceL))
                Text(text = stringResource(StopRes.string.stop_loading), style = MaterialTheme.typography.bodyLarge)
            }

            state.errorMessage != null && state.stops.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceM, Alignment.CenterVertically)
            ) {
                ElderErrorRetry(message = state.errorMessage, onRetry = { onEvent(StopDetailEvent.Retry) })
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(ElderStopTags.LIST),
                contentPadding = PaddingValues(
                    start = dimens.screenPadding,
                    end = dimens.screenPadding,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + dimens.spaceL
                ),
                verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
            ) {
                if (state.subtitle.isNotBlank()) {
                    item(key = "subtitle", contentType = "subtitle") {
                        Text(
                            text = state.subtitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 刷新出错（已有旧数据）：一句话 +「重试」
                state.errorMessage?.let { error ->
                    item(key = "error", contentType = "error") {
                        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                            ElderErrorRetry(message = error, onRetry = { onEvent(StopDetailEvent.Retry) })
                        }
                    }
                }
                if (state.stops.size > 1) {
                    item(key = "platforms", contentType = "platforms") {
                        Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                            Text(
                                text = stringResource(StopRes.string.stop_elder_pick_platform),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            state.platformLabels.forEachIndexed { index, label ->
                                ElderButton(
                                    text = label,
                                    onClick = { onEvent(StopDetailEvent.SelectPlatform(index)) },
                                    primary = index == state.selectedStopIndex,
                                    modifier = Modifier.fillMaxWidth().testTag(ElderStopTags.platform(index))
                                )
                            }
                        }
                    }
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Text(
                            text = stringResource(StopRes.string.stop_no_lines),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = dimens.spaceXl)
                        )
                    }
                } else {
                    items(items = state.rows, key = { it.key }, contentType = { "line" }) { row ->
                        ElderStopLineCard(row = row, onClick = { onEvent(StopDetailEvent.OpenLine(row)) })
                    }
                }
                state.transferText?.let { text ->
                    item(key = "transfer", contentType = "hint") {
                        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    messageHost()

    if (state.isTransitCodeSheetOpen) {
        TransitCodeChooserSheet(
            onDismiss = { onEvent(StopDetailEvent.DismissTransitCodeSheet) },
            onSelect = { provider, save -> onEvent(StopDetailEvent.PickTransitCode(provider, save)) }
        )
    }
}

@Composable
private fun ElderErrorRetry(message: String, onRetry: () -> Unit) {
    Text(text = message, style = MaterialTheme.typography.bodyLarge, color = TrackrTheme.colors.warn)
    ElderButton(text = stringResource(Res.string.action_retry), onClick = onRetry, modifier = Modifier.fillMaxWidth())
}

/** 一条线：「325  → 科技园」+ 大字到站 + 说明 + 运营时间。能点开线路页时整张卡能点、右边有箭头 */
@Composable
private fun ElderStopLineCard(row: StopLineRowUi, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ElderButtonHeight)
                .padding(dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ElderLineBadge(name = row.lineName)
                    Spacer(modifier = Modifier.width(dimens.spaceM))
                    Text(
                        text = if (row.endStop.isNotBlank()) "→ ${row.endStop}" else row.lineName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                ElderArrival(arrival = ArrivalUi.of(row.arrival))
                if (row.hours.isNotBlank()) {
                    Text(text = row.hours, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.lineId.isNotBlank()) {
                Box(modifier = Modifier.padding(start = dimens.spaceS)) {
                    Icon(imageVector = vectorResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
    val cardModifier = Modifier.fillMaxWidth().testTag(ElderStopTags.line(row.key))
    // 能点开线路页只看 lineId：掌上公交的站点行不带站序，但线路页可以按站名找到候车站
    if (row.lineId.isNotBlank()) {
        Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = cardModifier) {
            content()
        }
    } else {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = cardModifier) {
            content()
        }
    }
}
