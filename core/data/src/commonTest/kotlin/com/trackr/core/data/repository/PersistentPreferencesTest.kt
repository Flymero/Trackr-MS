package com.trackr.core.data.repository

import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.local.TransitLocalSnapshot
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentPreferencesTest {
    @Test
    fun settingsSurviveReopeningAndClampInvalidValues() = runTest {
        val store = MemoryPreferencesStore()
        val writer = SerialWriter(backgroundScope)
        val first = DefaultSettingsRepository.create(store, writer)
        assertFalse(first.hasSavedCity.value)
        first.selectCity(TransitCity(cityId = "001", cityName = "北京"))
        first.setThemeMode(ThemeMode.Dark)
        first.setEngineMode(EngineMode.MYGOLBS)
        first.setNearbyStopsCount(100)
        first.setAlertThresholdStops(-5)
        first.setShowTransitCodeButton(false)
        first.dismissFontScalePrompt()
        first.flushWrites()
        val reopened = DefaultSettingsRepository.create(store, writer)
        assertTrue(reopened.hasSavedCity.value)
        assertEquals("北京", reopened.selectedCity.value.cityName)
        assertEquals(ThemeMode.Dark, reopened.themeMode.value)
        assertEquals(EngineMode.MYGOLBS, reopened.engineMode.value)
        assertEquals(16, reopened.nearbyStopsCount.value)
        assertEquals(AlertThreshold.MIN_STOPS, reopened.alertThresholdStops.value)
        assertFalse(reopened.showTransitCodeButton.value)
        assertTrue(reopened.fontScalePromptDismissed.value)
    }

    @Test
    fun malformedValuesFallBackToUsableDefaults() = runTest {
        val store = MemoryPreferencesStore(TransitLocalSnapshot(
            selectedCityJson = "{broken", engineMode = "unknown", themeColorsJson = "not json",
            nearbyStopsCount = -2, alertThresholdStops = 99, uiMode = "unknown", themeMode = "unknown"
        ))
        val settings = DefaultSettingsRepository.create(store, SerialWriter(backgroundScope))
        assertEquals("上海", settings.selectedCity.value.cityName)
        assertEquals(EngineMode.CHELAILE, settings.engineMode.value)
        assertEquals(ThemeMode.System, settings.themeMode.value)
        assertEquals(1, settings.nearbyStopsCount.value)
        assertEquals(AlertThreshold.MAX_STOPS, settings.alertThresholdStops.value)
    }

    @Test
    fun historyIsDeduplicatedBoundedAndPersistedUnderConcurrentEdits() = runTest {
        val store = MemoryPreferencesStore()
        val writer = SerialWriter(backgroundScope)
        val history = DefaultSearchHistoryRepository.create(store, writer)
        (1..30).map { launch { history.addSearchHistory("Line $it") } }.forEach { it.join() }
        history.addSearchHistory(" line 30 ")
        history.addSearchHistory("   ")
        assertEquals(20, history.searchHistory.value.size)
        assertEquals("line 30", history.searchHistory.value.first())
        assertEquals(1, history.searchHistory.value.count { it.equals("line 30", true) })
        val reopened = DefaultSearchHistoryRepository.create(store, writer)
        assertEquals(history.searchHistory.value, reopened.searchHistory.value)
        reopened.removeSearchHistory("LINE 30")
        assertEquals(19, store.snapshot.searchHistory.size)
        reopened.clearSearchHistory()
        assertTrue(DefaultSearchHistoryRepository.create(store, writer).searchHistory.value.isEmpty())
    }
}

private class MemoryPreferencesStore(var snapshot: TransitLocalSnapshot = TransitLocalSnapshot()) : TransitLocalStore {
    private var migrationVersion = 0
    override suspend fun load() = snapshot
    override suspend fun saveSelectedCityJson(json: String) { snapshot = snapshot.copy(selectedCityJson = json) }
    override suspend fun saveEngineMode(name: String) { snapshot = snapshot.copy(engineMode = name) }
    override suspend fun saveTransitCodeProvider(id: String) { snapshot = snapshot.copy(transitCodeProvider = id) }
    override suspend fun saveShowTransitCodeButton(enabled: Boolean) { snapshot = snapshot.copy(showTransitCodeButton = enabled) }
    override suspend fun saveAutoStopAfterBoarding(enabled: Boolean) { snapshot = snapshot.copy(autoStopAfterBoarding = enabled) }
    override suspend fun saveNearbyStopsCount(count: Int) { snapshot = snapshot.copy(nearbyStopsCount = count) }
    override suspend fun saveLiveNotificationEnabled(enabled: Boolean) { snapshot = snapshot.copy(liveNotificationEnabled = enabled) }
    override suspend fun saveShowOnLockScreen(enabled: Boolean) { snapshot = snapshot.copy(showOnLockScreen = enabled) }
    override suspend fun saveAlertThresholdStops(stops: Int) { snapshot = snapshot.copy(alertThresholdStops = stops) }
    override suspend fun saveUiMode(name: String) { snapshot = snapshot.copy(uiMode = name) }
    override suspend fun saveRailMode(name: String) { snapshot = snapshot.copy(railMode = name) }
    override suspend fun saveThemeMode(name: String) { snapshot = snapshot.copy(themeMode = name) }
    override suspend fun saveThemeColors(json: String) { snapshot = snapshot.copy(themeColorsJson = json) }
    override suspend fun saveFontScalePromptDismissed(dismissed: Boolean) { snapshot = snapshot.copy(fontScalePromptDismissed = dismissed) }
    override suspend fun saveSearchHistory(list: List<String>) { snapshot = snapshot.copy(searchHistory = list) }
    override suspend fun clearSearchHistory() { snapshot = snapshot.copy(searchHistory = emptyList()) }
    override suspend fun favoritesGroupMigrationVersion() = migrationVersion
    override suspend fun saveFavoritesGroupMigrationVersion(version: Int) { migrationVersion = version }
}
