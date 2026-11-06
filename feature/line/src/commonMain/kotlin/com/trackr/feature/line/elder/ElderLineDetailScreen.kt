package com.trackr.feature.line.elder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.trackr.core.designsystem.component.FullscreenErrorView
import com.trackr.core.designsystem.component.FullscreenLoadingView
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_detail_error
import com.trackr.feature.line.resources.line_detail_hours
import com.trackr.feature.line.resources.line_detail_loading
import com.trackr.feature.line.resources.line_elder_alert_me
import com.trackr.feature.line.resources.line_elder_alerting
import com.trackr.feature.line.resources.line_elder_board_at
import com.trackr.feature.line.resources.line_elder_favorite
import com.trackr.feature.line.resources.line_elder_keep_alert
import com.trackr.feature.line.resources.line_elder_no_stop
import com.trackr.feature.line.resources.line_elder_pick_stop
import com.trackr.feature.line.resources.line_elder_read_aloud
import com.trackr.feature.line.resources.line_elder_stop_alert
import com.trackr.feature.line.resources.line_elder_stop_alert_confirm
import com.trackr.feature.line.resources.line_elder_switch_direction
import com.trackr.feature.line.resources.line_elder_unfavorite
import com.trackr.ui.components.TransitCodeBottomSheet
import com.trackr.ui.components.arrival.ArrivalUi
import com.trackr.ui.components.elder.ElderArrival
import com.trackr.ui.components.elder.ElderButton
import com.trackr.ui.components.elder.ElderConfirmDialog
import com.trackr.ui.components.elder.ElderLineBadge
import com.trackr.ui.components.elder.ElderTopBar
import com.trackr.ui.components.elder.ShowTransitCodeButton
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.ui.screens.linedetail.LineDetailEvent
import com.trackr.ui.screens.linedetail.LineDetailUiState
import com.trackr.ui.screens.linedetail.LineStatic
import com.trackr.ui.screens.linedetail.components.followingBusText
import com.trackr.ui.screens.linedetail.components.stationArrivalState
import org.jetbrains.compose.resources.stringResource

/** 测试用的 tag */
internal object ElderLineTags {
    const val PICK_STOP = "elder_line_pick_stop"
    const val ALERT = "elder_line_alert"
    const val SWITCH_DIRECTION = "elder_line_switch_direction"
    const val FAVORITE = "elder_line_favorite"
    const val READ_ALOUD = "elder_line_read_aloud"
}

/**
 * 长辈版线路页（设计稿「线路」，§8.5）：只回答「车还有多久来」。和标准版共用 `LineDetailViewModel` / [LineDetailUiState]
 * 和 `LineDetailRoute` 的事件处理（追踪前的实时活动权限引导、乘车码默认渠道都一样）。
 *
 * 线路号 +「始发 → 终点」+ 运营时间；「在 X 站 上车」+「换站」；大字到站 + 一句说明（含下一辆）；
 *「读给我听」（第 33 步，朗读大字那一句）；主按钮「车快到时叫我」（提醒中换成「不用叫了」，先确认）；「换方向」「收藏」；底部「出示乘车码」。
 * 没有走向条、全部车辆、单车跟随、时刻表、定时提醒和地图；沿途车站放进「换站」（[ElderPickStopScreen]）。
 */
@Composable
fun ElderLineDetailScreen(
    state: LineDetailUiState,
    onEvent: (LineDetailEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickingStop by rememberSaveable { mutableStateOf(false) }
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val static = state.static

    // 换站页里系统返回 = 回到线路页，不退出
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = pickingStop,
        onBackCompleted = { pickingStop = false }
    )

    if (pickingStop && static != null) {
        ElderPickStopScreen(
            stops = static.stops,
            buses = state.live?.buses.orEmpty(),
            selectedOrder = state.selectedStop?.order,
            onPick = { stop ->
                pickingStop = false
                onEvent(LineDetailEvent.SelectStop(stop))
            },
            onBack = { pickingStop = false },
            onOpenTransitCode = { onEvent(LineDetailEvent.OpenTransitCode) },
            showTransitCode = state.showTransitCodeButton,
            modifier = modifier
        )
    } else {
        Scaffold(
            modifier = modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { ElderTopBar(title = null, onBack = { onEvent(LineDetailEvent.Back) }) },
            bottomBar = {
                // 「我的」里关掉「显示乘车码按钮」后，这里和标准版线路页一起不画
                if (state.showTransitCodeButton) {
                    ShowTransitCodeButton(onClick = { onEvent(LineDetailEvent.OpenTransitCode) })
                }
            }
        ) { innerPadding ->
            when {
                static == null && state.isLoading -> FullscreenLoadingView(
                    modifier = Modifier.padding(innerPadding),
                    message = stringResource(LineRes.string.line_detail_loading)
                )
                static == null -> FullscreenErrorView(
                    errorMessage = state.errorMessage ?: stringResource(LineRes.string.line_detail_error),
                    modifier = Modifier.padding(innerPadding),
                    onRetry = { onEvent(LineDetailEvent.Refresh(fromPull = false)) }
                )
                else -> ElderLineContent(
                    state = state,
                    static = static,
                    onEvent = onEvent,
                    onPickStop = { pickingStop = true },
                    onStopAlert = { confirmStop = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
        }
    }

    if (confirmStop) {
        ElderConfirmDialog(
            text = stringResource(LineRes.string.line_elder_stop_alert_confirm),
            confirmText = stringResource(LineRes.string.line_elder_stop_alert),
            dismissText = stringResource(LineRes.string.line_elder_keep_alert),
            onConfirm = {
                confirmStop = false
                onEvent(LineDetailEvent.ToggleTracking)
            },
            onDismiss = { confirmStop = false }
        )
    }

    // 一次性消息：点「知道了」才关（长辈版没有分组，提示条上的「换分组」不给）
    UserMessageHost(
        message = state.userMessages.firstOrNull()?.let { HostMessage(key = it.id, text = it.text) },
        onResult = { key, _ -> onEvent(LineDetailEvent.MessageShown(key as Long)) }
    )

    // 乘车码选择（只在「每次询问」时出现）
    if (state.isTransitCodeSheetOpen) {
        TransitCodeBottomSheet(
            onDismiss = { onEvent(LineDetailEvent.DismissTransitCodeSheet) },
            onSelectProvider = { provider, saveAsDefault ->
                onEvent(LineDetailEvent.PickTransitCode(provider, saveAsDefault))
            }
        )
    }
}

@Composable
private fun ElderLineContent(
    state: LineDetailUiState,
    static: LineStatic,
    onEvent: (LineDetailEvent) -> Unit,
    onPickStop: () -> Unit,
    onStopAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = TrackrTheme.dimens
    val line = static.line
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.screenPadding, vertical = dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceM)
    ) {
        // 线路号 +「始发 → 终点」+ 运营时间
        Row(verticalAlignment = Alignment.CenterVertically) {
            ElderLineBadge(name = line.name)
            Spacer(modifier = Modifier.width(dimens.spaceM))
            Text(
                text = "${line.startStop} → ${line.endStop}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        if (line.firstTime.isNotBlank() || line.lastTime.isNotBlank()) {
            Text(
                text = stringResource(
                    LineRes.string.line_detail_hours,
                    line.firstTime.ifBlank { "—" }.let(ArrivalText::clockText),
                    line.lastTime.ifBlank { "—" }.let(ArrivalText::clockText)
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 刷新出错（已有旧数据）：一句话 +「重试」，不用标准版那条小字错误条
        state.errorMessage?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodyLarge, color = TrackrTheme.colors.warn)
            ElderButton(
                text = stringResource(Res.string.action_retry),
                onClick = { onEvent(LineDetailEvent.Refresh(fromPull = false)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        ElderArrivalCard(state = state, static = static, onPickStop = onPickStop)

        ElderButton(
            text = stringResource(LineRes.string.line_elder_read_aloud),
            onClick = { onEvent(LineDetailEvent.ReadAloud) },
            enabled = state.selectedStop != null,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ElderLineTags.READ_ALOUD)
        )

        if (state.isTrackingThisStop) {
            ElderButton(
                text = stringResource(LineRes.string.line_elder_stop_alert),
                onClick = onStopAlert,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ElderLineTags.ALERT)
            )
        } else {
            ElderButton(
                text = stringResource(LineRes.string.line_elder_alert_me),
                onClick = { onEvent(LineDetailEvent.ToggleTracking) },
                enabled = state.selectedStop != null && state.live != null,
                primary = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ElderLineTags.ALERT)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
            if (static.reverseDirection != null) {
                ElderButton(
                    text = stringResource(LineRes.string.line_elder_switch_direction),
                    onClick = { onEvent(LineDetailEvent.SwitchDirection) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ElderLineTags.SWITCH_DIRECTION)
                )
            }
            ElderButton(
                text = if (state.isFavorite) {
                    stringResource(LineRes.string.line_elder_unfavorite)
                } else {
                    stringResource(LineRes.string.line_elder_favorite)
                },
                onClick = { onEvent(LineDetailEvent.ToggleFavorite) },
                enabled = state.selectedStop != null,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ElderLineTags.FAVORITE)
            )
        }
    }
}

/** 「在 科技园站 上车 · 换站」+ 大字到站 + 说明（「还有 2 站 · 下一辆 12 分钟 · 5 站」）；提醒中多一行「车快到时会叫你」 */
@Composable
private fun ElderArrivalCard(state: LineDetailUiState, static: LineStatic, onPickStop: () -> Unit) {
    val dimens = TrackrTheme.dimens
    val stop = state.selectedStop
    val live = state.live
    val order = stop?.order ?: 1
    val now = remember(live?.updatedAtMillis) { live?.let { ArrivalText.localTimeOf(it.updatedAtMillis) } }
    val arrivalState = remember(static, live, order, now) { stationArrivalState(static, live, order, now) }
    val display = remember(arrivalState, static.line.name, stop?.name) {
        ArrivalText.format(arrivalState, lineName = static.line.name, stopName = stop?.name.orEmpty())
    }
    // 「下一辆」：只在「在路上」时补一句（到站了的说明里已经带了下一辆）
    val detail = remember(display, arrivalState, live?.buses, order) {
        val buses = live?.buses.orEmpty()
        val nearest = ArrivalText.nearestBus(order, buses)
        val following = if (arrivalState is ArrivalState.OnTheWay && nearest != null) {
            ArrivalText.nearestBus(order, buses - nearest)?.let { followingBusText(order, it) }
        } else {
            null
        }
        listOf(display.detail, following.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (state.isTrackingThisStop) TrackrTheme.colors.goContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (stop != null) {
                        stringResource(LineRes.string.line_elder_board_at, stop.name)
                    } else {
                        stringResource(LineRes.string.line_elder_no_stop)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(dimens.spaceS))
                ElderButton(
                    text = stringResource(LineRes.string.line_elder_pick_stop),
                    onClick = onPickStop,
                    modifier = Modifier.testTag(ElderLineTags.PICK_STOP)
                )
            }
            if (!stop?.statusNote.isNullOrBlank()) {
                Text(text = stop.statusNote.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = TrackrTheme.colors.warn)
            }
            ElderArrival(
                arrival = ArrivalUi.of(display),
                detail = detail
            )
            if (state.isTrackingThisStop) {
                Text(
                    text = stringResource(LineRes.string.line_elder_alerting),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = TrackrTheme.colors.go
                )
            }
        }
    }
}
