package com.trackr.ui.screens.me

import com.trackr.core.model.*
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    private val main = MainDispatcherHolder()
    @BeforeTest fun setUp() = main.install()
    @AfterTest fun tearDown() = main.reset()

    @Test
    fun rapidChoicesKeepIndependentPreferencesAndReopenImmediately() = runTest(main.dispatcher) {
        val settings = FakeSettingsRepository()
        val vm = ThemeSettingsViewModel(settings)
        vm.onEvent(ThemeSettingsEvent.SetPreset(ThemeSeed.Teal))
        vm.onEvent(ThemeSettingsEvent.SetStyle(ThemeColorStyle.Expressive))
        vm.onEvent(ThemeSettingsEvent.SetSource(ThemeColorSource.Custom))
        vm.onEvent(ThemeSettingsEvent.SetCustomColor(0xCF304A))
        vm.onEvent(ThemeSettingsEvent.SetMode(ThemeMode.Dark))
        testScheduler.runCurrent()
        val reopened = ThemeSettingsViewModel(settings).uiState.value
        assertEquals(ThemeMode.Dark, reopened.mode)
        assertEquals(ThemeSeed.Teal, reopened.colors.preset)
        assertEquals(ThemeColorStyle.Expressive, reopened.colors.style)
        assertEquals(0xCF304A, reopened.colors.customRgb)
        assertEquals(ThemeColorSource.Custom, reopened.colors.source)
    }

    @Test
    fun systemFollowingAndSourceSwitchingKeepPreviousChoices() = runTest(main.dispatcher) {
        val settings = FakeSettingsRepository()
        val vm = ThemeSettingsViewModel(settings)
        vm.onEvent(ThemeSettingsEvent.SetPreset(ThemeSeed.Rose))
        vm.onEvent(ThemeSettingsEvent.SetStyle(ThemeColorStyle.Monochrome))
        vm.onEvent(ThemeSettingsEvent.SetSource(ThemeColorSource.System))
        vm.onEvent(ThemeSettingsEvent.SetSystemSeed(SystemThemeSeed.Tertiary))
        assertFalse(settings.themeColors.value.followSystem)
        vm.onEvent(ThemeSettingsEvent.FollowSystem)
        assertTrue(settings.themeColors.value.followSystem)
        vm.onEvent(ThemeSettingsEvent.SetSource(ThemeColorSource.Preset))
        assertEquals(ThemeSeed.Rose, settings.themeColors.value.preset)
        assertEquals(ThemeColorStyle.Monochrome, settings.themeColors.value.style)
        assertEquals(SystemThemeSeed.Tertiary, settings.themeColors.value.systemSeed)
    }

    @Test
    fun invalidRgbDoesNotOverwriteLastColorAndElderModeRetainsPreferences() = runTest(main.dispatcher) {
        val settings = FakeSettingsRepository()
        settings.setUiMode(UiMode.Elder)
        val vm = ThemeSettingsViewModel(settings)
        vm.onEvent(ThemeSettingsEvent.SetCustomColor(0x00796B))
        vm.onEvent(ThemeSettingsEvent.SetCustomColor(-1))
        vm.onEvent(ThemeSettingsEvent.SetCustomColor(0x1000000))
        testScheduler.runCurrent()
        assertEquals(0x00796B, vm.uiState.value.colors.customRgb)
        assertTrue(vm.uiState.value.elderMode)
        settings.setUiMode(UiMode.Standard)
        testScheduler.runCurrent()
        assertFalse(vm.uiState.value.elderMode)
        assertEquals(0x00796B, vm.uiState.value.colors.customRgb)
    }
}
