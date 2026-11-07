package com.trackr.ui.screens.linedetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.TransitCodeProvider
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_focus_permission_xiaomi
import com.trackr.feature.line.resources.line_focus_title
import com.trackr.feature.line.resources.line_live_permission
import com.trackr.feature.line.resources.line_live_title
import com.trackr.ui.components.FocusPermissionGuideDialog
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * 线路详情的有状态入口：收集状态、轮询随页面 RESUMED 启停（§4.4），把页面事件分给 ViewModel 或平台能力
 * （乘车码、实时活动权限引导）。页面本身是无状态的 [LineDetailScreen]。
 */
@Composable
fun LineDetailRoute(
    viewModel: LineDetailViewModel,
    onBack: () -> Unit,
    /** 画哪个页面：标准版 [LineDetailScreen]，长辈版 `ElderLineDetailScreen`（§8.5：共用 ViewModel 和事件处理，只换页面） */
    screen: @Composable (state: LineDetailUiState, onEvent: (LineDetailEvent) -> Unit) -> Unit = { state, onEvent ->
        LineDetailScreen(state = state, onEvent = onEvent)
    }
) {
    // 只在页面处于前台（RESUMED）时轮询；回桌面、锁屏或被其他页面覆盖时停止（重构计划 §4.4）
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val transitCodeLauncher: TransitCodeLauncher = koinInject()
    val liveUpdateGate = rememberLiveUpdateGate()
    var pendingGuide by rememberSaveable { mutableStateOf<LiveUpdateGuide?>(null) }

    screen(
        state,
        { event ->
            when (event) {
                // 顶栏返回：卡片里有车辆列表 / 跟随就先退一级，站点卡时才离开页面
                LineDetailEvent.Back -> if (!viewModel.backFromPanel()) onBack()
                is LineDetailEvent.Refresh -> viewModel.refreshNow(fromPull = event.fromPull)
                is LineDetailEvent.SelectStop -> viewModel.selectStop(event.stop)
                is LineDetailEvent.FollowBus -> viewModel.selectBusToFollow(event.bus)
                is LineDetailEvent.ShowIdleBusInfo -> viewModel.showIdleBusInfo(event.bus)
                LineDetailEvent.DismissIdleBusInfo -> viewModel.dismissIdleBusInfo()
                LineDetailEvent.OpenBusList -> viewModel.openBusList()
                LineDetailEvent.BackFromPanel -> viewModel.backFromPanel()
                LineDetailEvent.SwitchDirection -> viewModel.switchOppositeDirection()
                LineDetailEvent.ToggleFavorite -> viewModel.toggleFavoriteStop()
                LineDetailEvent.OpenGroupPicker -> viewModel.openGroupPicker()
                LineDetailEvent.DismissGroupPicker -> viewModel.dismissGroupPicker()
                is LineDetailEvent.MoveToGroup -> viewModel.moveFavoriteToGroup(event.groupId)
                LineDetailEvent.OpenTimetable -> viewModel.loadTimetable()
                LineDetailEvent.DismissTimetable -> viewModel.dismissTimetable()
                LineDetailEvent.OpenReminder -> viewModel.openReminderDialog()
                LineDetailEvent.DismissReminder -> viewModel.dismissReminderDialog()
                is LineDetailEvent.SaveReminder -> viewModel.saveReminder(
                    hour = event.hour,
                    minute = event.minute,
                    daysOfWeek = event.daysOfWeek,
                    autoStartTracking = event.autoStartTracking
                )
                LineDetailEvent.ToggleTracking -> {
                    if (state.isTrackingThisStop) {
                        viewModel.stopTracking()
                    } else if (state.selectedStop != null) {
                        val guide = liveUpdateGate.blockingGuide()
                        if (guide != null) pendingGuide = guide else viewModel.startTrackingSelectedStop()
                    }
                }
                // 一点就开默认渠道，「每次询问」才弹选择（不用长按）
                LineDetailEvent.OpenTransitCode -> when (val provider = state.preferredTransitCodeProvider) {
                    TransitCodeProvider.ALIPAY, TransitCodeProvider.UNIONPAY -> transitCodeLauncher.open(provider)
                    TransitCodeProvider.ASK_ALWAYS -> viewModel.openTransitCodeSheet()
                }
                LineDetailEvent.DismissTransitCodeSheet -> viewModel.dismissTransitCodeSheet()
                is LineDetailEvent.PickTransitCode -> {
                    viewModel.dismissTransitCodeSheet()
                    if (event.saveAsDefault) viewModel.setPreferredTransitCodeProvider(event.provider)
                    transitCodeLauncher.open(event.provider)
                }
                is LineDetailEvent.MessageShown -> viewModel.onMessageShown(event.id)
                LineDetailEvent.ReadAloud -> viewModel.onReadAloud()
            }
        }
    )

    // 系统实时活动权限（小米焦点通知 / Android 16 置顶通知）未开时的引导；不开也能继续追踪
    val guide = pendingGuide
    if (guide != null) {
        val isXiaomi = guide == LiveUpdateGuide.XIAOMI_FOCUS
        FocusPermissionGuideDialog(
            onDismissRequest = { pendingGuide = null },
            onContinueWithoutSettings = {
                pendingGuide = null
                viewModel.startTrackingSelectedStop()
            },
            onGoToSettings = {
                pendingGuide = null
                liveUpdateGate.openSettings(guide)
                viewModel.startTrackingSelectedStop()
            },
            title = if (isXiaomi) stringResource(LineRes.string.line_focus_title) else stringResource(LineRes.string.line_live_title),
            deviceHint = liveUpdateGate.deviceHint(guide),
            permissionName = if (isXiaomi) stringResource(LineRes.string.line_focus_permission_xiaomi) else stringResource(LineRes.string.line_live_permission)
        )
    }
}
