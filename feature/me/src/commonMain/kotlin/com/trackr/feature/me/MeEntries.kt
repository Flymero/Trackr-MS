package com.trackr.feature.me

import com.trackr.ui.screens.me.ThemeSettingsScreen
import com.trackr.ui.screens.me.ThemeSettingsEvent
import com.trackr.ui.screens.me.ThemeSettingsViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.screens.me.AboutEvent
import com.trackr.ui.screens.me.AboutScreen
import com.trackr.ui.screens.me.AboutViewModel
import com.trackr.ui.screens.me.AmapKeyEvent
import com.trackr.ui.screens.me.AmapKeyScreen
import com.trackr.ui.screens.me.AmapKeyViewModel
import com.trackr.ui.screens.me.MeEvent
import com.trackr.ui.screens.me.MeScreen
import com.trackr.ui.screens.me.MeViewModel
import com.trackr.ui.screens.me.ReminderEditEvent
import com.trackr.ui.screens.me.ReminderEditScreen
import com.trackr.ui.screens.me.ReminderEditViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「我的」页签的导航 entry（第 25 步：页面都在 commonMain，Android 和 iOS 同一份）：
 * 我的（根页）、定时提醒编辑、高德 Key、关于。
 */
fun EntryProviderScope<NavKey>.meEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.More> {
        MeRoute(viewModel = koinViewModel(), navigator = navigator)
    }

    entry<ScreenKey.ReminderEdit> { key ->
        val viewModel: ReminderEditViewModel = koinViewModel(key = "reminder_edit_${key.reminderId}") {
            parametersOf(key)
        }
        ReminderEditRoute(viewModel = viewModel, navigator = navigator)
    }

    entry<ScreenKey.AmapKey> {
        AmapKeyRoute(viewModel = koinViewModel(), navigator = navigator)
    }

    entry<ScreenKey.ThemeSettings> {
        val viewModel: ThemeSettingsViewModel = koinViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val back = dropUnlessResumed { navigator.popChildPage() }
        ThemeSettingsScreen(state = state, onEvent = { event ->
            if (event == ThemeSettingsEvent.Back) back() else viewModel.onEvent(event)
        })
    }

    entry<ScreenKey.About> {
        AboutRoute(viewModel = koinViewModel(), navigator = navigator)
    }
}

@Composable
private fun MeRoute(viewModel: MeViewModel, navigator: TrackrNavigator) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从系统设置回来时重查状态栏胶囊权限（和原来的「更多」页一样）
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose { }
    }

    // 跳转只在 RESUMED 时做（防连点压两层，和 dropUnlessResumed 一样）
    val lifecycleOwner = LocalLifecycleOwner.current
    val whenResumed: (() -> Unit) -> Unit = { block ->
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
    }

    MeScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is MeEvent.OpenReminder -> whenResumed { navigator.open(ScreenKey.ReminderEdit(event.id)) }
                MeEvent.OpenAmapKey -> whenResumed { navigator.open(ScreenKey.AmapKey) }
                MeEvent.OpenAbout -> whenResumed { navigator.open(ScreenKey.About) }
                MeEvent.OpenThemeSettings -> whenResumed { navigator.open(ScreenKey.ThemeSettings) }
                else -> viewModel.onEvent(event)
            }
        }
    )
}

@Composable
private fun ReminderEditRoute(viewModel: ReminderEditViewModel, navigator: TrackrNavigator) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从系统「闹钟和提醒」设置页回来时重查权限
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose { }
    }

    val back = dropUnlessResumed { navigator.popChildPage() }
    // 原来提醒列表点一条去线路页；编辑页顶部那一行保留这个入口（在「我的」栈上打开，和原来一样）
    val openLine = dropUnlessResumed {
        // 线路、站这几项编辑页不改，用草稿里的就行
        val reminder = state.draft ?: return@dropUnlessResumed
        navigator.open(
            ScreenKey.LineDetail(
                cityId = reminder.cityId,
                lineId = reminder.lineId,
                targetOrder = reminder.targetOrder,
                stopId = reminder.targetStopId,
                source = reminder.dataSourceType,
                lineName = reminder.lineName
            )
        )
    }

    // 保存 / 删除完返回「我的」（提示在「我的」页显示）
    LaunchedEffect(state.closed) {
        if (state.closed) navigator.popChildPage()
    }

    ReminderEditScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                ReminderEditEvent.Back -> back()
                ReminderEditEvent.OpenLine -> openLine()
                else -> viewModel.onEvent(event)
            }
        }
    )
}

@Composable
private fun AmapKeyRoute(viewModel: AmapKeyViewModel, navigator: TrackrNavigator) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back = dropUnlessResumed { navigator.popChildPage() }

    LaunchedEffect(state.closed) {
        if (state.closed) navigator.popChildPage()
    }

    AmapKeyScreen(
        state = state,
        onEvent = { event ->
            if (event == AmapKeyEvent.Back) back() else viewModel.onEvent(event)
        }
    )
}

@Composable
private fun AboutRoute(viewModel: AboutViewModel, navigator: TrackrNavigator) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back = dropUnlessResumed { navigator.popChildPage() }

    // 从「安装未知应用」设置页回来：权限给了就接着装已经下好的包，不用重新下载
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(AboutEvent.ResumeInstall)
        onPauseOrDispose { }
    }

    AboutScreen(
        state = state,
        onEvent = { event ->
            if (event == AboutEvent.Back) back() else viewModel.onEvent(event)
        }
    )
}
