package com.trackr.app

import com.trackr.core.model.ThemeColors
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.UiMode
import com.trackr.core.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 根级壳状态（重构计划 §8.2）。
 *
 * @param uiMode 当前界面模式，决定主题（第 31 步起也决定导航结构）
 * @param fontScalePromptDismissed 「试试长辈版」提示已经点过
 */
data class AppShellState(
    val uiMode: UiMode = UiMode.Standard,
    val fontScalePromptDismissed: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val themeColors: ThemeColors = ThemeColors(),
)

/**
 * Activity 级（[TrackrRoot] 在 NavDisplay 之上取），读设置里的界面模式。
 *
 * 计划里 `shell` 是可空的（null = 还没读到，配合启动图避免先闪标准版）；这里的设置仓库构造时就同步读完存储，
 * StateFlow 一开始就是真实值，所以不需要 null 状态和启动图。
 */
class AppShellViewModel(private val settings: SettingsRepository) : ViewModel() {

    val shell: StateFlow<AppShellState> = combine(settings.uiMode, settings.fontScalePromptDismissed, settings.themeMode, settings.themeColors) { mode, dismissed, theme, colors ->
        AppShellState(mode, dismissed, theme, colors)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppShellState(settings.uiMode.value, settings.fontScalePromptDismissed.value, settings.themeMode.value, settings.themeColors.value)
    )

    /** 提示里点「打开长辈版」：切过去，提示也算点过 */
    fun enableElderFromPrompt() {
        settings.setUiMode(UiMode.Elder)
        settings.dismissFontScalePrompt()
    }

    /** 提示里点「先不用」或点外面关掉：以后不再提示 */
    fun dismissFontScalePrompt() = settings.dismissFontScalePrompt()
}
