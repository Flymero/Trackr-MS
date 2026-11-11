package com.trackr.ui.screens.stopdetail

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trackr.core.designsystem.theme.LocalUiMode
import com.trackr.core.model.UiMode
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.feature.stop.resources.Res as StopRes
import com.trackr.feature.stop.resources.stop_default_platform_accept
import com.trackr.feature.stop.resources.stop_default_platform_prompt
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import org.jetbrains.compose.resources.getString

/**
 * 站点详情的有状态外壳：收集状态、轮询随 RESUMED 启停（§4.4）、一次性提示、导航。
 *
 * @param screen 画哪个页面：标准版 [StopDetailScreen]，长辈版 `ElderStopDetailScreen`（§8.5：共用 ViewModel 和事件处理，只换页面）。
 *   `messageHost` 是一次性提示的宿主（[UserMessageHost]，标准版 Snackbar、长辈版点「知道了」才关），页面放进自己的布局里
 */
@Composable
internal fun StopDetailRoute(
    viewModel: StopDetailViewModel,
    navigator: TrackrNavigator,
    screen: @Composable (
        state: StopDetailUiState,
        onEvent: (StopDetailEvent) -> Unit,
        messageHost: @Composable () -> Unit
    ) -> Unit = { state, onEvent, messageHost ->
        StopDetailScreen(state = state, onEvent = onEvent, messageHost = messageHost)
    }
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 只在页面处于前台（RESUMED）时轮询；回桌面、锁屏或被其他页面覆盖时停止（重构计划 §4.4）
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }

    // ViewModel 发来的一次性提示排成队，宿主一次显示一条，显示完删掉
    val messages = remember { mutableStateListOf<HostMessage>() }
    LaunchedEffect(viewModel) {
        var nextId = 0L
        viewModel.userMessages.collect { messages += HostMessage(key = nextId++, text = it) }
    }

    // 已收藏的站、切到了别的站台：提示条问一次「默认方向改成 X？」（issue #47）。
    // 长辈版的提示只有「知道了」、点不了「改」，而且长辈版站点页本来就不放收藏，不问
    val platformPrompt = state.defaultPlatformPrompt
    val promptAllowed = LocalUiMode.current != UiMode.Elder
    LaunchedEffect(platformPrompt, promptAllowed) {
        messages.removeAll { it.key is PlatformPromptKey }
        if (platformPrompt != null && promptAllowed) {
            messages += HostMessage(
                key = PlatformPromptKey(platformPrompt),
                text = getString(StopRes.string.stop_default_platform_prompt, platformPrompt),
                actionLabel = getString(StopRes.string.stop_default_platform_accept, platformPrompt),
                duration = SnackbarDuration.Long
            )
        }
    }

    // 本站一起等出现「上了哪辆？」待选：打开导航弹层（和通知点进来的是同一个，栈顶已是它就不重复叠），
    // 并把这次待选记成弹过，弹层关了不再自己弹（新的待选照样弹）
    val boardChoice = state.boardChoice
    LaunchedEffect(boardChoice) {
        if (boardChoice != null) {
            navigator.open(ScreenKey.ChooseBoardedBus(boardChoice.sessionId))
            viewModel.onEvent(StopDetailEvent.DismissBoardChoice)
        }
    }

    // 只在 RESUMED 时导航：防连点重复入栈、防转场中再点（同 dropUnlessResumed）
    val lifecycleOwner = LocalLifecycleOwner.current
    fun resumed() = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    screen(
        state,
        { event ->
            when (event) {
                StopDetailEvent.Back -> if (resumed() && !viewModel.handleBack()) navigator.popChildPage()
                is StopDetailEvent.OpenLine -> if (resumed()) {
                    val row = event.row
                    navigator.open(
                        ScreenKey.LineDetail(
                            cityId = viewModel.cityId,
                            lineId = row.lineId,
                            targetOrder = row.targetOrder,
                            stopId = row.stopId,
                            source = row.source,
                            lineName = row.lineName
                        )
                    )
                }
                else -> viewModel.onEvent(event)
            }
        },
        {
            UserMessageHost(
                message = messages.firstOrNull(),
                onResult = { key, actionPerformed ->
                    messages.removeAll { it.key == key }
                    if (key is PlatformPromptKey) {
                        viewModel.onEvent(
                            if (actionPerformed) StopDetailEvent.AcceptDefaultPlatform else StopDetailEvent.DismissDefaultPlatformPrompt
                        )
                    }
                }
            )
        }
    )
}

/** 「默认方向改成 X？」提示条的 key（和普通提示区分开，回调时知道是它） */
private data class PlatformPromptKey(val label: String)
