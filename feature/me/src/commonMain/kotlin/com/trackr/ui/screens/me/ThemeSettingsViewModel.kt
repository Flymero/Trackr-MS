package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.SystemThemeSeed
import com.trackr.core.model.ThemeColorSource
import com.trackr.core.model.ThemeColorStyle
import com.trackr.core.model.ThemeColors
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.ThemeSeed
import com.trackr.core.model.UiMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ThemeSettingsUiState(
    val mode: ThemeMode = ThemeMode.System,
    val colors: ThemeColors = ThemeColors(),
    val elderMode: Boolean = false,
)

sealed interface ThemeSettingsEvent {
    data object Back : ThemeSettingsEvent
    data class SetMode(val mode: ThemeMode) : ThemeSettingsEvent
    data class SetSource(val source: ThemeColorSource) : ThemeSettingsEvent
    data class SetPreset(val preset: ThemeSeed) : ThemeSettingsEvent
    data class SetStyle(val style: ThemeColorStyle) : ThemeSettingsEvent
    data class SetSystemSeed(val seed: SystemThemeSeed) : ThemeSettingsEvent
    data object FollowSystem : ThemeSettingsEvent
    data class SetCustomColor(val rgb: Int) : ThemeSettingsEvent
}

class ThemeSettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val uiState = combine(settings.themeMode, settings.themeColors, settings.uiMode) { mode, colors, uiMode ->
        ThemeSettingsUiState(mode, colors, uiMode == UiMode.Elder)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ThemeSettingsUiState(settings.themeMode.value, settings.themeColors.value, settings.uiMode.value == UiMode.Elder),
    )

    fun onEvent(event: ThemeSettingsEvent) {
        // Read the repository, not the asynchronously combined UI state: rapid taps must retain every choice.
        val colors = settings.themeColors.value
        when (event) {
            ThemeSettingsEvent.Back -> Unit
            is ThemeSettingsEvent.SetMode -> settings.setThemeMode(event.mode)
            is ThemeSettingsEvent.SetSource -> settings.setThemeColors(colors.copy(source = event.source))
            is ThemeSettingsEvent.SetPreset -> settings.setThemeColors(colors.copy(preset = event.preset))
            is ThemeSettingsEvent.SetStyle -> settings.setThemeColors(colors.copy(style = event.style))
            is ThemeSettingsEvent.SetSystemSeed -> settings.setThemeColors(colors.copy(systemSeed = event.seed, followSystem = false))
            ThemeSettingsEvent.FollowSystem -> settings.setThemeColors(colors.copy(followSystem = true))
            is ThemeSettingsEvent.SetCustomColor -> if (event.rgb in 0..0xFFFFFF) {
                settings.setThemeColors(colors.copy(customRgb = event.rgb))
            }
        }
    }
}
